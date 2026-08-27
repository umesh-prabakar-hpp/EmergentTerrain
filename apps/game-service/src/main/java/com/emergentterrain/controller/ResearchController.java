package com.emergentterrain.controller;

import com.emergentterrain.research.api.ResearchQueryClient;
import com.emergentterrain.researchcontract.ResearchPaperSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public class ResearchController {

    private static final Logger log = LoggerFactory.getLogger(ResearchController.class);

    private final Optional<ResearchQueryClient> researchClient;

    public ResearchController(Optional<ResearchQueryClient> researchClient) {
        this.researchClient = researchClient;
    }

    @GetMapping({"/room/{roomId}/research/papers", "/api/v1/research/rooms/{roomId}/papers"})
    public ResponseEntity<List<ResearchPaperSummary>> getPapers(
            @PathVariable("roomId") UUID roomId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        if (researchClient.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        try {
            return ResponseEntity.ok(researchClient.get().getPublishedPapers(roomId, Math.clamp(limit, 1, 500)));
        } catch (RestClientException failure) {
            log.warn("Research paper query unavailable roomId={} type={}", roomId,
                    failure.getClass().getSimpleName());
            return ResponseEntity.status(503).build();
        }
    }

    @GetMapping({"/room/{roomId}/research/stats", "/api/v1/research/rooms/{roomId}/stats"})
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable("roomId") UUID roomId) {
        if (researchClient.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "status", "DISABLED",
                    "message", "University research is disabled.",
                    "totalPublished", 0L,
                    "totalRejected", 0L,
                    "researcherCount", 0L,
                    "maxGeneration", 0L,
                    "avgReputation", 0.0,
                    "avgPeerScore", 0.0,
                    "byDomain", Map.of()));
        }
        try {
            return ResponseEntity.ok(researchClient.get().getStats(roomId));
        } catch (RestClientException failure) {
            log.warn("Research stats query unavailable roomId={} type={}", roomId,
                    failure.getClass().getSimpleName());
            return ResponseEntity.status(503).build();
        }
    }
}
