package com.emergentterrain.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.emergentterrain.domain.GameConfirmation;
import com.emergentterrain.domain.Profile;
import com.emergentterrain.repo.GameConfirmationRepository;
import com.emergentterrain.repo.GameSnapshotRepository;
import com.emergentterrain.repo.ProfileRepository;
import com.emergentterrain.repo.ProfileQuestStateRepository;
import com.emergentterrain.service.ExplanationService;
import com.emergentterrain.service.Payload;
import com.emergentterrain.service.PayloadService;
import com.emergentterrain.service.SnapshotPayload;
import com.emergentterrain.service.SnapshotTokenService;
import com.emergentterrain.service.WorldService;
import com.emergentterrain.web.ConfirmRequest;
import com.emergentterrain.web.GameStateRequest;
import com.emergentterrain.web.GameStateResponse;
import com.emergentterrain.web.GameUpdateRequest;
import com.emergentterrain.web.GameUpdateResponse;
import com.emergentterrain.web.QuestItem;
import com.emergentterrain.web.QuestStateRequest;
import com.emergentterrain.web.QuestStateResponse;
import com.emergentterrain.web.SnapshotLoadResponse;
import com.emergentterrain.web.StateHashRequest;
import com.emergentterrain.web.StateHashResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

@Controller
public class GameController {
    private final PayloadService payloadService;
    private final WorldService worldService;
    private final ExplanationService explanationService;
    private final ProfileRepository profileRepository;
    private final GameConfirmationRepository confirmationRepository;
    private final GameSnapshotRepository snapshotRepository;
    private final ProfileQuestStateRepository questStateRepository;
    private final SnapshotTokenService snapshotTokenService;
    private final ObjectMapper objectMapper;

    public GameController(PayloadService payloadService,
                          WorldService worldService,
                          ExplanationService explanationService,
                          ProfileRepository profileRepository,
                          GameConfirmationRepository confirmationRepository,
                          GameSnapshotRepository snapshotRepository,
                          ProfileQuestStateRepository questStateRepository,
                          SnapshotTokenService snapshotTokenService,
                          ObjectMapper objectMapper) {
        this.payloadService = payloadService;
        this.worldService = worldService;
        this.explanationService = explanationService;
        this.profileRepository = profileRepository;
        this.confirmationRepository = confirmationRepository;
        this.snapshotRepository = snapshotRepository;
        this.questStateRepository = questStateRepository;
        this.snapshotTokenService = snapshotTokenService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/game")
    public String gamePage(@RequestParam("token") String token,
                           @RequestParam(value = "snapshot", required = false) String snapshot,
                           Model model) {
        model.addAttribute("token", token);
        model.addAttribute("snapshotId", snapshot == null ? "" : snapshot);
        return "game";
    }

    @PostMapping("/game/state")
    @ResponseBody
    public ResponseEntity<GameStateResponse> gameState(@RequestBody GameStateRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        long seed = payloadService.seedFromPayload(payload);
        GameStateResponse response;
        if (request.getSnapshotId() != null && !request.getSnapshotId().isBlank()) {
            response = worldService.generateWorld(payload, seed);
            java.util.UUID sid = java.util.UUID.fromString(request.getSnapshotId());
            snapshotRepository.findById(sid).ifPresent(snapshot -> {
                try {
                    int[][] tiles = objectMapper.readValue(snapshot.getTilesJson(), int[][].class);
                    response.setTiles(tiles);
                } catch (Exception ignored) {
                }
            });
        } else {
            response = worldService.generateWorld(payload, seed);
        }
        java.util.UUID pid = java.util.UUID.fromString(payload.profileId());
        Profile profile = profileRepository.findById(pid).orElseThrow();
        java.util.List<com.emergentterrain.domain.GameSnapshot> latest = snapshotRepository.findTop10ByProfileOrderByCreatedAtDesc(profile);
        java.util.List<com.emergentterrain.web.SnapshotInfo> infos = new java.util.ArrayList<>();
        for (com.emergentterrain.domain.GameSnapshot s : latest) {
            infos.add(new com.emergentterrain.web.SnapshotInfo(s.getId().toString(), s.getStateHash(), s.getCreatedAt()));
        }
        response.setSnapshots(infos);
        questStateRepository.findByProfile(profile).ifPresent(state -> {
            QuestStateResponse qs = new QuestStateResponse();
            qs.setSeason(state.getSeason());
            qs.setLastObserverPrompt(state.getLastObserverPrompt());
            try {
                QuestItem[] items = objectMapper.readValue(state.getQuestsJson(), QuestItem[].class);
                qs.setQuests(java.util.List.of(items));
            } catch (Exception ignored) {
            }
            response.setQuestState(qs);
        });
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

    @PostMapping("/game/qr/state")
    @ResponseBody
    public ResponseEntity<StateHashResponse> stateHash(@RequestBody StateHashRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        String hash = hashTiles(request.getTiles());
        SnapshotPayload snapshotPayload = new SnapshotPayload(
            1,
            payload.profileId(),
            "",
            hash,
            payloadService.issuedAtNow()
        );
        String snapshotToken = snapshotTokenService.buildToken(snapshotPayload);
        String qrData = "/game?token=" + request.getToken();
        if (!hash.isBlank()) {
            qrData = qrData + "&state=" + hash;
        }
        qrData = qrData + "&snapshotToken=" + snapshotToken;

        StateHashResponse response = new StateHashResponse();
        response.setStateHash(hash);
        response.setQrData(qrData);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/game/quest/save")
    @ResponseBody
    public ResponseEntity<?> saveQuestState(@RequestBody QuestStateRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        Profile profile = profileRepository.findById(java.util.UUID.fromString(payload.profileId()))
            .orElseThrow();
        com.emergentterrain.domain.ProfileQuestState state = questStateRepository.findByProfile(profile)
            .orElseGet(com.emergentterrain.domain.ProfileQuestState::new);
        state.setProfile(profile);
        state.setSeason(request.getSeason() == null ? "Exploration Phase" : request.getSeason());
        try {
            state.setQuestsJson(objectMapper.writeValueAsString(request.getQuests()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        state.setLastObserverPrompt(request.getLastObserverPrompt());
        state.setLastObserverAt(java.time.Instant.now());
        state.touch();
        questStateRepository.save(state);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/game/snapshot")
    @ResponseBody
    public ResponseEntity<StateHashResponse> snapshot(@RequestBody StateHashRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        Profile profile = profileRepository.findById(java.util.UUID.fromString(payload.profileId()))
            .orElseThrow();
        String hash = hashTiles(request.getTiles());
        com.emergentterrain.domain.GameSnapshot snapshot = new com.emergentterrain.domain.GameSnapshot();
        snapshot.setProfile(profile);
        snapshot.setStateHash(hash);
        try {
            snapshot.setTilesJson(objectMapper.writeValueAsString(request.getTiles()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize tiles", e);
        }
        snapshot = snapshotRepository.save(snapshot);

        SnapshotPayload snapshotPayload = new SnapshotPayload(
            1,
            payload.profileId(),
            snapshot.getId().toString(),
            hash,
            payloadService.issuedAtNow()
        );
        String snapshotToken = snapshotTokenService.buildToken(snapshotPayload);
        String qrData = "/game?token=" + request.getToken() + "&snapshot=" + snapshot.getId();
        qrData = qrData + "&snapshotToken=" + snapshotToken;

        StateHashResponse response = new StateHashResponse();
        response.setStateHash(hash);
        response.setSnapshotId(snapshot.getId().toString());
        response.setQrData(qrData);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/game/snapshot/latest")
    @ResponseBody
    public ResponseEntity<StateHashResponse> latestSnapshot(@RequestBody StateHashRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        Profile profile = profileRepository.findById(java.util.UUID.fromString(payload.profileId()))
            .orElseThrow();
        return snapshotRepository.findTopByProfileOrderByCreatedAtDesc(profile)
            .map(snapshot -> {
                SnapshotPayload snapshotPayload = new SnapshotPayload(
                    1,
                    payload.profileId(),
                    snapshot.getId().toString(),
                    snapshot.getStateHash(),
                    payloadService.issuedAtNow()
                );
                String snapshotToken = snapshotTokenService.buildToken(snapshotPayload);
                String qrData = "/game?token=" + request.getToken() + "&snapshot=" + snapshot.getId();
                qrData = qrData + "&snapshotToken=" + snapshotToken;

                StateHashResponse response = new StateHashResponse();
                response.setStateHash(snapshot.getStateHash());
                response.setSnapshotId(snapshot.getId().toString());
                response.setQrData(qrData);
                return ResponseEntity.ok(response);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/game/snapshot/load")
    @ResponseBody
    public ResponseEntity<SnapshotLoadResponse> loadSnapshot(@RequestBody StateHashRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        if (request.getSnapshotId() == null || request.getSnapshotId().isBlank()) {
            return ResponseEntity.<SnapshotLoadResponse>badRequest().build();
        }
        Profile profile = profileRepository.findById(java.util.UUID.fromString(payload.profileId()))
            .orElseThrow();
        java.util.UUID sid = java.util.UUID.fromString(request.getSnapshotId());
        com.emergentterrain.domain.GameSnapshot snapshot = snapshotRepository.findById(sid)
            .orElse(null);
        if (snapshot == null || !snapshot.getProfile().getId().equals(profile.getId())) {
            return ResponseEntity.<SnapshotLoadResponse>notFound().build();
        }
        SnapshotLoadResponse response = new SnapshotLoadResponse();
        response.setSnapshotId(snapshot.getId().toString());
        try {
            int[][] tiles = objectMapper.readValue(snapshot.getTilesJson(), int[][].class);
            response.setTiles(tiles);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.<SnapshotLoadResponse>internalServerError().build();
        }
    }

    private String hashTiles(int[][] tiles) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(tiles);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
