package com.emergentterrain.research.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.emergentterrain.researchcontract.ResearchPaperProvenanceSummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpResearchQueryClientTest {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> queryKeys = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            queryKeys.add(exchange.getRequestHeaders().getFirst(HttpResearchQueryClient.QUERY_KEY_HEADER));
            String path = exchange.getRequestURI().getPath();
            byte[] body = path.endsWith("/papers") ? "[]".getBytes(StandardCharsets.UTF_8)
                    : path.endsWith("/provenance")
                    ? "{\"paperId\":\"P-1\",\"artifact\":{\"available\":false,\"integrityStatus\":\"PENDING\",\"declaredFileCount\":0},\"study\":{\"preregistrationAvailable\":false,\"hypothesisDeclared\":false,\"predictionDeclared\":false,\"falsificationConditionDeclared\":false,\"computationalStudyPlanDeclared\":false,\"protocolReuseRecorded\":false},\"evidence\":{\"findingsRetained\":false,\"conclusionRetained\":false,\"recordedToolCallCount\":0,\"literature\":{\"available\":false,\"issuedReferenceCount\":0,\"readReferenceCount\":0,\"citedReferenceCount\":0,\"citedReadReferenceCount\":0,\"sourceGroundedRelatedWork\":false}}}".getBytes(StandardCharsets.UTF_8)
                    : "{\"totalPublished\":2}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTheDedicatedQueryKeyForPapersStatsAndProvenance() {
        UUID roomId = UUID.randomUUID();
        HttpResearchQueryClient client = new HttpResearchQueryClient(RestClient.builder(),
                new ResearchApiProperties("remote", "http://127.0.0.1:" + server.getAddress().getPort(), "query-key"));

        assertThat(client.getPublishedPapers(roomId, 25)).isEmpty();
        assertThat(client.getStats(roomId)).containsEntry("totalPublished", 2);
        ResearchPaperProvenanceSummary provenance = client.getPaperProvenance(roomId, "P-1");
        assertThat(provenance.artifact().integrityStatus()).isEqualTo("PENDING");
        assertThat(paths).containsExactly(
                "/api/v1/research/rooms/" + roomId + "/papers",
                "/api/v1/research/rooms/" + roomId + "/stats",
                "/api/v1/research/rooms/" + roomId + "/papers/P-1/provenance");
        assertThat(queryKeys).containsExactly("query-key", "query-key", "query-key");
    }

    @Test
    void remoteModeRejectsAnUnsetQueryKeyBeforeAnyRequest() {
        assertThatThrownBy(() -> new HttpResearchQueryClient(RestClient.builder(),
                new ResearchApiProperties("remote", "http://127.0.0.1:8082", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEARCH_QUERY_API_KEY");
    }
}
