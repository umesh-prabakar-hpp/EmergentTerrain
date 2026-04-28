package com.emergentterrain.service;

import com.emergentterrain.government.GovernmentState;
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
    // Policy consultation and streaming use separate slots so they don't starve each other.
    // Ollama serialises both on its CPU anyway; separate gates just prevent one from blocking the other.
    private final Semaphore consultSlot = new Semaphore(1);
    private final Semaphore streamSlot  = new Semaphore(1);
    private final long digestCooldownMs;

    public LlmService(ChatClient.Builder chatClientBuilder,
                      RoomUpdateBroadcaster broadcaster,
                      @Value("${app.ai.digestCooldownMinutes:10}") long digestCooldownMinutes) {
        this.chatClient = chatClientBuilder.build();
        this.broadcaster = broadcaster;
        this.digestCooldownMs = TimeUnit.MINUTES.toMillis(Math.max(0L, digestCooldownMinutes));
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

    // --- Synchronous consultation ---

    public String consultPolicySync(String prompt) {
        if (!consultSlot.tryAcquire()) {
            log.debug("LLM consult slot busy - skipping policy consultation");
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            String response = chatClient.prompt().user(prompt).call().content();
            log.info("LLM policy consultation done ms={}", System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            log.warn("LLM policy consultation failed ms={}: {}", System.currentTimeMillis() - start, e.getMessage());
            return null;
        } finally {
            consultSlot.release();
        }
    }

    // --- Core streaming ---

    public boolean generateAndStream(UUID roomId, String streamId, String kind, String prompt) {
        if (!streamSlot.tryAcquire()) {
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
                    streamSlot.release();
                }
            });
            return true;
        } catch (RuntimeException e) {
            streamSlot.release();
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

    // --- Helpers ---

    private static String nvl(String value) {
        return value != null ? value : "";
    }
}
