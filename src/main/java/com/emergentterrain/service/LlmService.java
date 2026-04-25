package com.emergentterrain.service;

import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.web.GovernmentSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final ConcurrentHashMap<UUID, Set<String>> generatedIds = new ConcurrentHashMap<>();
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmService(ChatClient.Builder chatClientBuilder, RoomUpdateBroadcaster broadcaster) {
        this.chatClient = chatClientBuilder.build();
        this.broadcaster = broadcaster;
    }

    // --- Public triggers ---

    public void generateCourtOpinionIfAbsent(UUID roomId, GovernmentState.CourtCase courtCase) {
        if (!markSeen(roomId, "court:" + courtCase.getCaseId())) return;
        generateAndStream(roomId, courtCase.getCaseId(), "court_opinion",
                buildCourtOpinionPrompt(courtCase));
    }

    public void generateActorStatementIfAbsent(UUID roomId, GovernmentState.CivicActor actor,
                                               String eventKind, String eventTitle,
                                               String eventSummary) {
        String key = "actor:" + actor.getActorId() + ":" + eventKind + ":" + eventTitle;
        if (!markSeen(roomId, key)) return;
        String streamId = actor.getActorId() + "-" + System.currentTimeMillis();
        generateAndStream(roomId, streamId, "actor_statement",
                buildActorStatementPrompt(actor, eventKind, eventTitle, eventSummary));
    }

    public void generateAdvice(UUID roomId, String streamId, GovernmentSummary summary,
                               String question) {
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

    // --- Prompt builders ---

    private String buildCourtOpinionPrompt(GovernmentState.CourtCase c) {
        String ruling = c.isBlockedAction() ? "BLOCKED — action exceeded constitutional bounds"
                                            : "UPHELD — action within constitutional bounds";
        return """
                You are a supreme court justice writing a brief opinion for a civic simulation game.
                Write a concise majority opinion (3 short paragraphs, no headers, plain prose, under 180 words).

                Case: %s  Court: %s
                Plaintiff: %s  Defendant: %s
                Challenged: %s
                Doctrine: %s  Scrutiny: %s
                Constitutional basis: %s
                Issue: %s
                Ruling: %s  Remedy: %s
                %s
                %s

                Write in formal legal voice. End with the ruling stated plainly."""
                .formatted(
                        nvl(c.getCaseId()), nvl(c.getCourtLevel()),
                        nvl(c.getPlaintiff()), nvl(c.getDefendant()),
                        nvl(c.getChallengedTitle()),
                        nvl(c.getDoctrine()), nvl(c.getScrutinyLevel()),
                        nvl(c.getConstitutionalBasis()),
                        nvl(c.getIssueSummary()),
                        ruling, nvl(c.getRemedy()),
                        c.getDissentSummary() != null ? "Dissent note: " + c.getDissentSummary() : "",
                        c.getPrecedentIds() != null && !c.getPrecedentIds().isEmpty()
                                ? "Precedents: " + String.join(", ", c.getPrecedentIds()) : "");
    }

    private String buildActorStatementPrompt(GovernmentState.CivicActor actor, String eventKind,
                                             String eventTitle, String eventSummary) {
        return """
                You are a spokesperson for "%s", a %s civic organization in a simulation game.
                Your organization is aligned with %s and your agenda focuses on: %s.

                React to this event in 2-3 sentences as a public statement:
                Event type: %s
                Event: %s
                Details: %s

                Speak in first-person plural ("We..."). Be direct, opinionated, and under 60 words."""
                .formatted(
                        nvl(actor.getName()),
                        nvl(actor.getActorType()),
                        actor.getAlignedPartyId() != null ? actor.getAlignedPartyId() : "no party",
                        actor.getAgendas() != null ? String.join(", ", actor.getAgendas()) : "general civic affairs",
                        eventKind, nvl(eventTitle), nvl(eventSummary));
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

    private boolean markSeen(UUID roomId, String key) {
        return generatedIds.computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet()).add(key);
    }

    private static String nvl(String value) {
        return value != null ? value : "";
    }
}
