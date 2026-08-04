package com.emergentterrain.researchservice;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/research/rooms/{roomId}")
public class ResearchApiController {

    private final ResearchReadService research;

    public ResearchApiController(ResearchReadService research) {
        this.research = research;
    }

    @GetMapping("/papers")
    public ResponseEntity<List<ResearchPaperSummary>> papers(
            @PathVariable UUID roomId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(research.papers(roomId, limit));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable UUID roomId) {
        return ResponseEntity.ok(research.stats(roomId));
    }
}
