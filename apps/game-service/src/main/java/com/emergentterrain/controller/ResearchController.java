package com.emergentterrain.controller;

import com.emergentterrain.research.api.ResearchPaperSummary;
import com.emergentterrain.research.api.ResearchQueryClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnBean(ResearchQueryClient.class)
public class ResearchController {

    private final ResearchQueryClient researchClient;

    public ResearchController(ResearchQueryClient researchClient) {
        this.researchClient = researchClient;
    }

    @GetMapping({"/room/{roomId}/research/papers", "/api/v1/research/rooms/{roomId}/papers"})
    public ResponseEntity<List<ResearchPaperSummary>> getPapers(
            @PathVariable("roomId") UUID roomId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(researchClient.getPublishedPapers(roomId, Math.clamp(limit, 1, 500)));
    }

    @GetMapping({"/room/{roomId}/research/stats", "/api/v1/research/rooms/{roomId}/stats"})
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable("roomId") UUID roomId) {
        return ResponseEntity.ok(researchClient.getStats(roomId));
    }
}
