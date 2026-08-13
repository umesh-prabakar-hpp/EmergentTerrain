package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectFrozenComputationClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void postsTheFrozenScanEnvelopeToTheResearchOwnedSidecarClient() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/physics/topic", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"primaryValue\":0.42,\"claimStatus\":\"OBSERVATIONAL\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/physics/topic");
            DirectFrozenComputationClient client = new DirectFrozenComputationClient(HttpClient.newHttpClient(), mapper,
                    endpoint, Duration.ofSeconds(2));

            JsonNode output = client.execute(supportedInput());

            assertThat(output.path("primaryValue").asDouble()).isEqualTo(0.42);
            assertThat(received.get().path("domain").asText()).isEqualTo("MATHEMATICS");
            assertThat(received.get().path("topicIdx").asInt()).isZero();
            assertThat(received.get().path("param1").asInt()).isEqualTo(42);
            assertThat(received.get().path("searchParams").path("candidateBreadth").asInt()).isEqualTo(40);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesUnsupportedFrozenTargetsBeforeMakingAnyHttpRequest() {
        DirectFrozenComputationClient client = new DirectFrozenComputationClient(HttpClient.newHttpClient(), mapper,
                URI.create("http://127.0.0.1:1/physics/topic"), Duration.ofSeconds(1));
        ResearchComputationSpecification unsupported = new ResearchComputationSpecification(
                ResearchComputationSpecification.LEGACY_COMPUTATION_INPUT_V1, UUID.randomUUID(), "PHYSICS",
                "Projectile Drag Optimisation", 42, 1.5, 0.5, 0.2, 40, 0.5, 0.0);

        assertThatThrownBy(() -> client.execute(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support");
    }

    private static ResearchComputationSpecification supportedInput() {
        return new ResearchComputationSpecification(ResearchComputationSpecification.LEGACY_COMPUTATION_INPUT_V1,
                UUID.randomUUID(), "MATHEMATICS", "Prime Constellation Search", 42, 1.5,
                0.5, 0.2, 40, 0.5, 0.0);
    }
}
