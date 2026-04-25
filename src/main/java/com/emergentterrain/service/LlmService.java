package com.emergentterrain.service;

import com.emergentterrain.government.GovernmentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ChatClient chatClient;
    private final RoomUpdateBroadcaster broadcaster;
    // Per-room set of caseIds that have already had opinions generated
    private final ConcurrentHashMap<UUID, Set<String>> generatedOpinions = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmService(ChatClient.Builder chatClientBuilder, RoomUpdateBroadcaster broadcaster) {
        this.chatClient = chatClientBuilder.build();
        this.broadcaster = broadcaster;
    }

    public void generateAndStream(UUID roomId, String streamId, String kind, String prompt) {
        executor.execute(() -> {
            try {
                chatClient.prompt()
                        .user(prompt)
                        .stream()
                        .content()
                        .toStream()
                        .forEach(token -> broadcaster.broadcastLlmToken(roomId, streamId, kind, token));
                broadcaster.broadcastLlmDone(roomId, streamId, kind);
            } catch (Exception e) {
                log.warn("LLM generation failed room={} kind={}: {}", roomId, kind, e.getMessage());
                broadcaster.broadcastLlmDone(roomId, streamId, kind);
            }
        });
    }

    public void generateCourtOpinionIfAbsent(UUID roomId, GovernmentState.CourtCase courtCase) {
        Set<String> seen = generatedOpinions.computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet());
        if (!seen.add(courtCase.getCaseId())) return;
        generateAndStream(roomId, courtCase.getCaseId(), "court_opinion", buildCourtOpinionPrompt(courtCase));
    }

    public void generateExplanation(UUID roomId, String streamId, int x, int y, String tileType,
                                    Map<String, Boolean> answers) {
        generateAndStream(roomId, streamId, "explanation", buildExplanationPrompt(x, y, tileType, answers));
    }

    private String buildCourtOpinionPrompt(GovernmentState.CourtCase c) {
        String ruling = c.isBlockedAction() ? "BLOCKED — action exceeded constitutional bounds"
                                            : "UPHELD — action within constitutional bounds";
        return """
                You are a supreme court justice writing a brief opinion for a civic simulation game.
                Write a concise majority opinion (3 short paragraphs, no headers, plain prose, under 180 words) for:

                Case: %s
                Court: %s
                Plaintiff: %s  Defendant: %s
                Challenged: %s
                Doctrine: %s  Scrutiny: %s
                Constitutional basis: %s
                Issue: %s
                Ruling: %s
                Remedy: %s
                %s
                %s

                Write in formal legal voice. End with the ruling stated plainly."""
                .formatted(
                        c.getCaseId(),
                        nvl(c.getCourtLevel()),
                        nvl(c.getPlaintiff()), nvl(c.getDefendant()),
                        nvl(c.getChallengedTitle()),
                        nvl(c.getDoctrine()), nvl(c.getScrutinyLevel()),
                        nvl(c.getConstitutionalBasis()),
                        nvl(c.getIssueSummary()),
                        ruling,
                        nvl(c.getRemedy()),
                        c.getDissentSummary() != null ? "Dissent: " + c.getDissentSummary() : "",
                        c.getPrecedentIds() != null && !c.getPrecedentIds().isEmpty()
                                ? "Precedents: " + String.join(", ", c.getPrecedentIds()) : ""
                );
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

    private static String nvl(String value) {
        return value != null ? value : "";
    }
}
