package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Game-side anti-corruption adapter; no game types cross the research contract. */
@Service
public class GameResearchWorkMapper {

    private final ObjectMapper objectMapper;

    public GameResearchWorkMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ResearchWorkRequest fromTick(UUID workspaceId, List<RoomBot> activeBots, long sourceRevision,
                                        GovernmentState governmentState) {
        List<ResearchSubject> subjects = activeBots.stream()
                .map(this::subjectFor)
                .toList();
        ObjectNode context = objectMapper.createObjectNode();
        context.set("governance", objectMapper.valueToTree(governmentState));
        context.put("subjectCount", subjects.size());
        UUID requestId = UUID.nameUUIDFromBytes(("research-cycle:" + workspaceId + ":" + sourceRevision)
                .getBytes(StandardCharsets.UTF_8));
        return new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", sourceRevision,
                Instant.now(), subjects, context);
    }

    private ResearchSubject subjectFor(RoomBot bot) {
        ObjectNode attributes = objectMapper.createObjectNode();
        attributes.put("classification", bot.getType());
        attributes.set("profile", readJson(bot.getProfileJson()));
        attributes.set("state", readJson(bot.getStateJson()));
        attributes.set("lifecycle", readJson(bot.getLifecycleJson()));
        return new ResearchSubject(bot.getId(), "AGENT", attributes);
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }
}
