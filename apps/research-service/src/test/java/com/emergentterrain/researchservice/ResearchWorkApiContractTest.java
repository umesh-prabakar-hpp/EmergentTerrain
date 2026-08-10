package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchWorkReceipt;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchWorkApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsThePublishedWorkShapeWithItsDedicatedCredential() throws Exception {
        ResearchWorkInboxService inbox = mock(ResearchWorkInboxService.class);
        ResearchWorkRequest request = request();
        ResearchWorkReceipt receipt = new ResearchWorkReceipt(request.requestId(), "QUEUED",
                Instant.parse("2026-08-06T12:00:00Z"));
        when(inbox.accept(request)).thenReturn(receipt);

        var response = new ResearchWorkController(inbox, "work-key").submit("work-key", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(receipt);
        verify(inbox).accept(request);
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(request));
        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "requestId", "workspaceId", "workType", "sourceRevision", "requestedAt", "subjects", "contextSnapshot");
        assertThat(fieldNames(json.path("subjects").get(0)))
                .containsExactlyInAnyOrder("subjectId", "kind", "attributes");
    }

    @Test
    void rejectsAWorkRequestWithoutThePublishedCredential() {
        ResearchWorkInboxService inbox = mock(ResearchWorkInboxService.class);

        assertThatThrownBy(() -> new ResearchWorkController(inbox, "work-key").submit(null, request()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void reportsAConflictingDuplicateAsAnHttpConflict() {
        ResearchWorkInboxService inbox = mock(ResearchWorkInboxService.class);
        ResearchWorkRequest request = request();
        when(inbox.accept(request)).thenThrow(new ResearchWorkReplayConflictException());

        assertThatThrownBy(() -> new ResearchWorkController(inbox, "work-key").submit("work-key", request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(inbox).accept(request);
    }

    @Test
    void openApiPublishesTheWorkEndpointReceiptAndCredential() throws Exception {
        String spec = Files.readString(openApi());

        assertThat(spec).contains("/api/v1/research/work:", "operationId: submitResearchWork",
                "ResearchWorkRequest:", "ResearchWorkReceipt:", "ResearchDraftSelectionInput:",
                "ResearchDraftExecutionIntent:", "ResearchStudySpecification:", "ResearchParameterOverride:",
                "ResearchConjectureSpecification:", "ResearchConjectureQueueSnapshot:",
                "ResearchTopicHistorySnapshot:", "ResearchTopicHistoryEntry:",
                "ResearchTopicReselectionPolicy:",
                "name: X-Research-Work-Key");
    }

    private static ResearchWorkRequest request() {
        return new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 7,
                Instant.parse("2026-08-06T12:00:00Z"),
                List.of(new ResearchSubject(UUID.randomUUID(), "AGENT", MAPPER.createObjectNode().put("tier", "primary"))),
                MAPPER.createObjectNode().put("snapshot", "frozen"));
    }

    private static Path openApi() {
        Path root = Path.of("contracts", "research-api", "openapi.yaml");
        if (Files.isRegularFile(root)) return root;
        return Path.of("..", "..", "contracts", "research-api", "openapi.yaml");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
