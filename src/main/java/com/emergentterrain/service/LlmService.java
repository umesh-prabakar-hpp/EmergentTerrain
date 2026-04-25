package com.emergentterrain.service;

import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.web.GovernmentSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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

    private static final int DIGEST_COOLDOWN_MINUTES = 10;
    private static final int DIGEST_MAX_CASES = 3;
    private static final int DIGEST_MAX_BILLS = 3;

    private final ChatClient chatClient;
    private final RoomUpdateBroadcaster broadcaster;
    private final ConcurrentHashMap<UUID, Long> lastDigestAt = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore llmSlots = new Semaphore(1);

    public LlmService(ChatClient.Builder chatClientBuilder, RoomUpdateBroadcaster broadcaster) {
        this.chatClient = chatClientBuilder.build();
        this.broadcaster = broadcaster;
    }

    // --- Public triggers ---

    public void generateGovernmentDigestIfAbsent(UUID roomId, GovernmentState state) {
        // Per-room cooldown: at most one digest every DIGEST_COOLDOWN_MINUTES
        long now = System.currentTimeMillis();
        Long last = lastDigestAt.get(roomId);
        if (last != null && now - last < TimeUnit.MINUTES.toMillis(DIGEST_COOLDOWN_MINUTES)) return;

        // Collect events, cap to most recent N to keep prompt small
        List<GovernmentState.CourtCase> decidedCases = state.getCourtCases().stream()
                .filter(c -> "DECIDED".equalsIgnoreCase(c.getStatus()))
                .limit(DIGEST_MAX_CASES)
                .toList();
        List<GovernmentState.GovernmentBill> enactedBills = state.getBills().stream()
                .filter(b -> "ENACTED".equalsIgnoreCase(b.getLawStatus())
                          || "BLOCKED".equalsIgnoreCase(b.getLawStatus()))
                .limit(DIGEST_MAX_BILLS)
                .toList();
        GovernmentState.ElectionRecord recentElection = state.getElections().stream()
                .filter(e -> e.getHeldAt() != null)
                .max(Comparator.comparing(GovernmentState.ElectionRecord::getHeldAt))
                .orElse(null);

        if (decidedCases.isEmpty() && enactedBills.isEmpty() && recentElection == null) return;

        lastDigestAt.put(roomId, now);
        log.info("LLM digest triggered room={} cases={} bills={} election={}",
                roomId, decidedCases.size(), enactedBills.size(), recentElection != null);

        String streamId = "digest-" + now;
        generateAndStream(roomId, streamId, "government_digest",
                buildGovernmentDigestPrompt(decidedCases, enactedBills, recentElection));
    }

    public void generateAdvice(UUID roomId, String streamId, GovernmentSummary summary,
                               String question) {
        log.info("LLM advisor triggered room={} streamId={}", roomId, streamId);
        generateAndStream(roomId, streamId, "advisor", buildAdvisorPrompt(summary, question));
    }

    public void generateExplanation(UUID roomId, String streamId, int x, int y, String tileType,
                                    Map<String, Boolean> answers) {
        generateAndStream(roomId, streamId, "explanation",
                buildExplanationPrompt(x, y, tileType, answers));
    }

    // --- Core streaming ---

    public void generateAndStream(UUID roomId, String streamId, String kind, String prompt) {
        executor.execute(() -> {
            if (!llmSlots.tryAcquire()) {
                log.info("LLM slot busy — shedding room={} kind={}", roomId, kind);
                broadcaster.broadcastLlmDone(roomId, streamId, kind);
                return;
            }
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
