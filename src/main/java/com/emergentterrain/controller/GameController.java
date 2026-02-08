package com.emergentterrain.controller;

import com.emergentterrain.domain.GameConfirmation;
import com.emergentterrain.domain.Profile;
import com.emergentterrain.repo.GameConfirmationRepository;
import com.emergentterrain.repo.ProfileRepository;
import com.emergentterrain.service.ExplanationService;
import com.emergentterrain.service.Payload;
import com.emergentterrain.service.PayloadService;
import com.emergentterrain.service.WorldService;
import com.emergentterrain.web.ConfirmRequest;
import com.emergentterrain.web.GameStateRequest;
import com.emergentterrain.web.GameStateResponse;
import com.emergentterrain.web.GameUpdateRequest;
import com.emergentterrain.web.GameUpdateResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class GameController {
    private final PayloadService payloadService;
    private final WorldService worldService;
    private final ExplanationService explanationService;
    private final ProfileRepository profileRepository;
    private final GameConfirmationRepository confirmationRepository;
    private final ObjectMapper objectMapper;

    public GameController(PayloadService payloadService,
                          WorldService worldService,
                          ExplanationService explanationService,
                          ProfileRepository profileRepository,
                          GameConfirmationRepository confirmationRepository,
                          ObjectMapper objectMapper) {
        this.payloadService = payloadService;
        this.worldService = worldService;
        this.explanationService = explanationService;
        this.profileRepository = profileRepository;
        this.confirmationRepository = confirmationRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/game")
    public String gamePage(@RequestParam("token") String token, Model model) {
        model.addAttribute("token", token);
        return "game";
    }

    @PostMapping("/game/state")
    @ResponseBody
    public ResponseEntity<GameStateResponse> gameState(@RequestBody GameStateRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        long seed = payloadService.seedFromPayload(payload);
        GameStateResponse response = worldService.generateWorld(payload, seed);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/game/update")
    @ResponseBody
    public ResponseEntity<GameUpdateResponse> gameUpdate(@RequestBody GameUpdateRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        String explanation = explanationService.explain(request.getDelta(), payload.answers());
        return ResponseEntity.ok(new GameUpdateResponse(explanation, true));
    }

    @PostMapping("/game/confirm")
    @ResponseBody
    public ResponseEntity<?> confirm(@RequestBody ConfirmRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        Profile profile = profileRepository.findById(java.util.UUID.fromString(payload.profileId()))
            .orElseThrow();

        GameConfirmation confirmation = new GameConfirmation();
        confirmation.setProfile(profile);
        try {
            confirmation.setDeltaJson(objectMapper.writeValueAsString(request.getDelta()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize delta", e);
        }
        confirmation.setExplanationText(explanationService.explain(request.getDelta(), payload.answers()));
        confirmation.setConfirmed(request.isConfirmed());
        confirmationRepository.save(confirmation);

        return ResponseEntity.ok().build();
    }
}
