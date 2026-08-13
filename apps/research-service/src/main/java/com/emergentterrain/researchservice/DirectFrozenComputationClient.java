package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;

/**
 * Minimal research-owned client for the versioned sidecar scan envelope.
 * It deliberately does not reuse the game delegate or its result composer.
 */
@Service
public class DirectFrozenComputationClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;

    public DirectFrozenComputationClient(ObjectMapper mapper,
                                         @Value("${app.research-service.direct-computation-audit.base-url:http://127.0.0.1:8088}") String baseUrl,
                                         @Value("${app.research-service.direct-computation-audit.timeout-ms:15000}") long timeoutMs) {
        this(HttpClient.newHttpClient(), mapper, URI.create(baseUrl + "/physics/topic"), Duration.ofMillis(timeoutMs));
    }

    DirectFrozenComputationClient(HttpClient http, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    public JsonNode execute(ResearchComputationSpecification input) {
        if (!supports(input)) throw new IllegalArgumentException("Direct computation audit does not support frozen target");
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody(input))))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Direct computation sidecar returned HTTP " + response.statusCode());
            }
            JsonNode output = mapper.readTree(response.body());
            if (output == null || !output.isObject()) {
                throw new IllegalStateException("Direct computation sidecar returned no object output");
            }
            return output;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Direct computation sidecar call interrupted", interrupted);
        } catch (Exception failure) {
            if (failure instanceof IllegalStateException known) throw known;
            throw new IllegalStateException("Direct computation sidecar call failed", failure);
        }
    }

    /** The first extracted target is intentionally narrow until raw-output parity is established. */
    public static boolean supports(ResearchComputationSpecification input) {
        return ResearchComputationSpecification.LEGACY_COMPUTATION_INPUT_V1.equals(input.computationVersion())
                && "MATHEMATICS".equals(input.domain()) && "Prime Constellation Search".equals(input.topic());
    }

    private static LinkedHashMap<String, Object> requestBody(ResearchComputationSpecification input) {
        LinkedHashMap<String, Object> scan = new LinkedHashMap<>();
        scan.put("stepSize", input.stepSize());
        scan.put("restartProb", input.restartProbability());
        scan.put("candidateBreadth", input.candidateBreadth());
        scan.put("depthBias", input.depthBias());
        scan.put("acceptThreshold", input.acceptanceThreshold());
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("domain", input.domain());
        body.put("topicIdx", 0);
        body.put("param1", input.parameterOne());
        body.put("param2", input.parameterTwo());
        body.put("searchParams", scan);
        return body;
    }
}
