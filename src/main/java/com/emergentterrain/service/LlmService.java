package com.emergentterrain.service;

import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.web.GovernmentSummary;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final int DIGEST_MAX_CASES = 3;
    private static final int DIGEST_MAX_BILLS = 3;

    private final ChatClient chatClient;
    private final RoomUpdateBroadcaster broadcaster;
    private final ConcurrentHashMap<String, Long> lastAcceptedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastDigestFingerprint = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore llmSlots = new Semaphore(1);
    private final long digestCooldownMs;
    private final long advisorCooldownMs;
    private final long explanationCooldownMs;

    public LlmService(ChatClient.Builder chatClientBuilder,
                      RoomUpdateBroadcaster broadcaster,
                      @Value("${app.ai.digestCooldownMinutes:10}") long digestCooldownMinutes,
                      @Value("${app.ai.advisorCooldownSeconds:15}") long advisorCooldownSeconds,
                      @Value("${app.ai.explanationCooldownMs:2500}") long explanationCooldownMs) {
        this.chatClient = chatClientBuilder.build();
        this.broadcaster = broadcaster;
        this.digestCooldownMs = TimeUnit.MINUTES.toMillis(Math.max(0L, digestCooldownMinutes));
        this.advisorCooldownMs = TimeUnit.SECONDS.toMillis(Math.max(0L, advisorCooldownSeconds));
        this.explanationCooldownMs = Math.max(0L, explanationCooldownMs);
    }

    // --- Public triggers ---

    public void generateGovernmentDigestIfAbsent(UUID roomId, GovernmentState state) {
        long now = System.currentTimeMillis();
        String kind = "government_digest";
        String cooldownKey = cooldownKey(roomId, kind);
        if (isOnCooldown(cooldownKey, now, digestCooldownMs)) {
            return;
        }

        // Collect events, cap to most recent N to keep prompt small
        List<GovernmentState.CourtCase> decidedCases = state.getCourtCases().stream()
                .filter(c -> "DECIDED".equalsIgnoreCase(c.getStatus()))
                .sorted(Comparator.comparing(this::courtCaseEventAt).reversed())
                .limit(DIGEST_MAX_CASES)
                .toList();
        List<GovernmentState.GovernmentBill> enactedBills = state.getBills().stream()
                .filter(b -> "ENACTED".equalsIgnoreCase(b.getLawStatus())
                          || "BLOCKED".equalsIgnoreCase(b.getLawStatus()))
                .sorted(Comparator.comparing(this::billEventAt).reversed())
                .limit(DIGEST_MAX_BILLS)
                .toList();
        GovernmentState.ElectionRecord recentElection = state.getElections().stream()
                .filter(e -> e.getHeldAt() != null)
                .max(Comparator.comparing(GovernmentState.ElectionRecord::getHeldAt))
                .orElse(null);

        if (decidedCases.isEmpty() && enactedBills.isEmpty() && recentElection == null) return;

        String fingerprint = digestFingerprint(decidedCases, enactedBills, recentElection);
        if (fingerprint.equals(lastDigestFingerprint.get(roomId))) {
            return;
        }

        String streamId = "digest-" + now;
        if (generateAndStream(roomId, streamId, kind,
                buildGovernmentDigestPrompt(decidedCases, enactedBills, recentElection))) {
            markAccepted(cooldownKey, now);
            lastDigestFingerprint.put(roomId, fingerprint);
            log.info("LLM digest accepted room={} cases={} bills={} election={}",
                    roomId, decidedCases.size(), enactedBills.size(), recentElection != null);
        }
    }

    public void generateAdvice(UUID roomId, String streamId, GovernmentSummary summary,
                               String question) {
        String kind = "advisor";
        long now = System.currentTimeMillis();
        String cooldownKey = cooldownKey(roomId, kind);
        if (isOnCooldown(cooldownKey, now, advisorCooldownMs)) {
            logCooldownSkip(roomId, streamId, kind, cooldownKey, now, advisorCooldownMs);
            return;
        }
        if (generateAndStream(roomId, streamId, kind, buildAdvisorPrompt(summary, question))) {
            markAccepted(cooldownKey, now);
            log.info("LLM advisor accepted room={} streamId={}", roomId, streamId);
        }
    }

    public void generateExplanation(UUID roomId, String streamId, int x, int y, String tileType,
                                    Map<String, Boolean> answers) {
        String kind = "explanation";
        long now = System.currentTimeMillis();
        String cooldownKey = cooldownKey(roomId, kind);
        if (isOnCooldown(cooldownKey, now, explanationCooldownMs)) {
            logCooldownSkip(roomId, streamId, kind, cooldownKey, now, explanationCooldownMs);
            return;
        }
        if (generateAndStream(roomId, streamId, kind, buildExplanationPrompt(x, y, tileType, answers))) {
            markAccepted(cooldownKey, now);
        }
    }

    // --- Core streaming ---

    public boolean generateAndStream(UUID roomId, String streamId, String kind, String prompt) {
        if (!llmSlots.tryAcquire()) {
            log.info("LLM slot busy - shedding room={} kind={}", roomId, kind);
            broadcaster.broadcastLlmDone(roomId, streamId, kind);
            return false;
        }
        try {
            executor.execute(() -> {
                long start = System.currentTimeMillis();
                try {
                    chatClient.prompt()
                            .user(prompt)
                            .stream()
                            .content()
                            .toStream()
                            .forEach(token -> broadcaster.broadcastLlmToken(roomId, streamId, kind, token));
                    log.info("LLM done room={} kind={} ms={}", roomId, kind, System.currentTimeMillis() - start);
                    broadcaster.broadcastLlmDone(roomId, streamId, kind);
                } catch (Exception e) {
                    log.warn("LLM failed room={} kind={} ms={}: {}", roomId, kind,
                            System.currentTimeMillis() - start, e.getMessage());
                    broadcaster.broadcastLlmDone(roomId, streamId, kind);
                } finally {
                    llmSlots.release();
                }
            });
            return true;
        } catch (RuntimeException e) {
            llmSlots.release();
            throw e;
        }
    }

    private String cooldownKey(UUID roomId, String kind) {
        return roomId + ":" + kind;
    }

    private boolean isOnCooldown(String cooldownKey, long now, long cooldownMs) {
        if (cooldownMs <= 0) {
            return false;
        }
        Long last = lastAcceptedAt.get(cooldownKey);
        return last != null && now - last < cooldownMs;
    }

    private void markAccepted(String cooldownKey, long now) {
        lastAcceptedAt.put(cooldownKey, now);
    }

    private void logCooldownSkip(UUID roomId, String streamId, String kind, String cooldownKey, long now, long cooldownMs) {
        Long last = lastAcceptedAt.get(cooldownKey);
        long remainingMs = last == null ? cooldownMs : Math.max(0L, cooldownMs - (now - last));
        log.info("LLM cooldown skip room={} kind={} remainingMs={}", roomId, kind, remainingMs);
        broadcaster.broadcastLlmDone(roomId, streamId, kind);
    }

    private String digestFingerprint(List<GovernmentState.CourtCase> cases,
                                     List<GovernmentState.GovernmentBill> bills,
                                     GovernmentState.ElectionRecord election) {
        List<String> parts = new ArrayList<>();
        for (GovernmentState.CourtCase c : cases) {
            parts.add("case:" + nvl(c.getCaseId()) + ":" + courtCaseEventAt(c));
        }
        for (GovernmentState.GovernmentBill b : bills) {
            parts.add("bill:" + nvl(b.getBillId()) + ":" + nvl(b.getLawStatus()) + ":" + billEventAt(b));
        }
        if (election != null) {
            parts.add("election:" + nvl(election.getElectionId()) + ":" + election.getHeldAt());
        }
        return String.join("|", parts);
    }

    private Instant courtCaseEventAt(GovernmentState.CourtCase courtCase) {
        return courtCase.getDecidedAt() == null ? Instant.EPOCH : courtCase.getDecidedAt();
    }

    private Instant billEventAt(GovernmentState.GovernmentBill bill) {
        if (bill.getUpdatedAt() != null) {
            return bill.getUpdatedAt();
        }
        return bill.getCreatedAt() == null ? Instant.EPOCH : bill.getCreatedAt();
    }

    // --- Prompt builders ---


    private String buildGovernmentDigestPrompt(List<GovernmentState.CourtCase> cases,
                                               List<GovernmentState.GovernmentBill> bills,
                                               GovernmentState.ElectionRecord election) {
        StringBuilder events = new StringBuilder();
        for (GovernmentState.CourtCase c : cases) {
            String ruling = c.isBlockedAction() ? "BLOCKED" : "UPHELD";
            events.append("- Court (%s): %s — %s [%s]\n"
                    .formatted(nvl(c.getCourtLevel()), nvl(c.getCaseId()),
                               nvl(c.getIssueSummary()), ruling));
        }
        for (GovernmentState.GovernmentBill b : bills) {
            events.append("- Bill %s: \"%s\" [%s, domain: %s]\n"
                    .formatted(nvl(b.getLawStatus()), nvl(b.getTitle()),
                               nvl(b.getLawStatus()), nvl(b.getPolicyDomain())));
        }
        if (election != null) {
            events.append("- Election %s: winner=%s, turnout=%.0f%%\n"
                    .formatted(nvl(election.getElectionId()),
                               nvl(election.getWinningPartyId()),
                               election.getTurnout() * 100));
        }
        return """
                You are a civic news anchor in a simulation game.
                Write a brief government bulletin (3-5 sentences, plain prose, under 100 words).
                Cover only the events listed. Be factual and neutral.

                Events:
                %s
                End with one sentence on what citizens should watch next."""
                .formatted(events.toString().trim());
    }

    private String buildAdvisorPrompt(GovernmentSummary summary, String question) {
        String budgetLine = summary.getFederalBudget() != null
                ? "Budget — education: %d, health: %d, infrastructure: %d, welfare: %d"
                        .formatted(
                                summary.getFederalBudget().getEducation(),
                                summary.getFederalBudget().getHealth(),
                                summary.getFederalBudget().getInfrastructure(),
                                summary.getFederalBudget().getWelfare())
                : "";
        return """
                You are a political advisor in a civic simulation game. Answer concisely (under 80 words).

                Current government snapshot:
                Constitution: %s  Tick: %d
                Federal control: executive=%s, lower=%s, upper=%s, court=%s
                Trust: %.2f
                Active bills: %d  Court cases: %d  Executive orders: %d
                %s
                Bots: %d active, %d petitioning, %d sanctioned

                Player question: %s"""
                .formatted(
                        nvl(summary.getConstitutionVersion()),
                        summary.getTick(),
                        nvl(summary.getFederalExecutiveParty()),
                        nvl(summary.getFederalLowerParty()),
                        nvl(summary.getFederalUpperParty()),
                        nvl(summary.getFederalChiefJusticeParty()),
                        summary.getFederalTrust(),
                        summary.getActiveBillCount(),
                        summary.getActiveCourtCaseCount(),
                        summary.getActiveExecutiveOrderCount(),
                        budgetLine,
                        summary.getActiveBotCount(),
                        summary.getPetitioningBotCount(),
                        summary.getSanctionedBotCount(),
                        question);
    }

    private String buildExplanationPrompt(int x, int y, String tileType,
                                          Map<String, Boolean> answers) {
        String preferences = answers == null || answers.isEmpty() ? "no stated preferences"
                : answers.entrySet().stream()
                        .map(e -> e.getKey().replace('_', ' ') + ": " + e.getValue())
                        .reduce((a, b) -> a + ", " + b).orElse("none");
        return """
                You are a terrain advisor in a civic simulation game.
                A player is proposing to place a %s tile at position (%d, %d).
                Their stated preferences: %s.
                In 1-2 sentences, explain what this change might mean for the simulated community.
                Be specific, direct, and under 50 words."""
                .formatted(tileType, x, y, preferences);
    }

    // --- Helpers ---

    private static String nvl(String value) {
        return value != null ? value : "";
    }
}
