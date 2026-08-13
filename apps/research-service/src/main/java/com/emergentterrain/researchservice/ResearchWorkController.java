package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchWorkReceipt;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Generic work intake; source-specific authentication and mapping remain outside research. */
@RestController
@RequestMapping("/api/v1/research/work")
public class ResearchWorkController {

    private final ResearchWorkInboxService inbox;
    private final ResearchWorkStatusService status;
    private final String apiKey;

    public ResearchWorkController(ResearchWorkInboxService inbox, ResearchWorkStatusService status,
                                  @Value("${app.research-service.work-api-key:}") String apiKey) {
        this.inbox = inbox;
        this.status = status;
        this.apiKey = apiKey;
    }

    @PostMapping
    public ResponseEntity<ResearchWorkReceipt> submit(
            @RequestHeader(value = "X-Research-Work-Key", required = false) String suppliedApiKey,
            @RequestBody ResearchWorkRequest request) {
        requireCredential(suppliedApiKey);
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(inbox.accept(request));
        } catch (ResearchWorkReplayConflictException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<com.emergentterrain.researchcontract.ResearchWorkStatus> status(
            @RequestHeader(value = "X-Research-Work-Key", required = false) String suppliedApiKey,
            @PathVariable UUID requestId) {
        requireCredential(suppliedApiKey);
        return status.status(requestId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void requireCredential(String suppliedApiKey) {
        if (apiKey.isBlank() || !apiKey.equals(suppliedApiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Research work credential required");
        }
    }
}
