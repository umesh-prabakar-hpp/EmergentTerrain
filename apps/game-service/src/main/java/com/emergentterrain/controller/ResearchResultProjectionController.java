package com.emergentterrain.controller;

import com.emergentterrain.research.api.ResearchResultProjectionService;
import com.emergentterrain.researchcontract.ResearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal result endpoint authenticated by the research-result API-key filter. */
@RestController
@RequestMapping("/api/v1/game/research-results")
public class ResearchResultProjectionController {
    private final ResearchResultProjectionService projections;
    public ResearchResultProjectionController(ResearchResultProjectionService projections) { this.projections = projections; }
    @PostMapping public ResponseEntity<Void> receive(@RequestBody ResearchResult result) {
        projections.apply(result);
        return ResponseEntity.accepted().build();
    }
}
