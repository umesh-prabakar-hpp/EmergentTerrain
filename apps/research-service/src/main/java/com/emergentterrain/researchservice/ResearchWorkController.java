package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchWorkReceipt;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Generic work intake; source-specific authentication and mapping remain outside research. */
@RestController
@RequestMapping("/api/v1/research/work")
public class ResearchWorkController {

    private final ResearchWorkInboxService inbox;
    private final String apiKey;

    public ResearchWorkController(ResearchWorkInboxService inbox,
                                  @Value("${app.research-service.work-api-key:}") String apiKey) {
        this.inbox = inbox;
        this.apiKey = apiKey;
    }

    @PostMapping
    public ResponseEntity<ResearchWorkReceipt> submit(
            @RequestHeader(value = "X-Research-Work-Key", required = false) String suppliedApiKey,
            @RequestBody ResearchWorkRequest request) {
        if (apiKey.isBlank() || !apiKey.equals(suppliedApiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Research work credential required");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(inbox.accept(request));
    }
}
