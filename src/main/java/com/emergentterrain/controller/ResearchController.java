package com.emergentterrain.controller;

import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.UniversityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnBean(UniversityService.class)
public class ResearchController {

    private final UniversityService universityService;

    public ResearchController(UniversityService universityService) {
        this.universityService = universityService;
    }

    @GetMapping("/room/{roomId}/research/papers")
    public ResponseEntity<List<ResearchPaper>> getPapers(
            @PathVariable("roomId") UUID roomId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(universityService.getPublishedPapers(roomId, Math.min(limit, 500)));
    }

    @GetMapping("/room/{roomId}/research/stats")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable("roomId") UUID roomId) {
        return ResponseEntity.ok(universityService.getStats(roomId));
    }
}
