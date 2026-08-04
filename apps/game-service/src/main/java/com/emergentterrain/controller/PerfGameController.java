package com.emergentterrain.controller;

import com.emergentterrain.service.ActivityMetrics;
import com.emergentterrain.service.Payload;
import com.emergentterrain.service.PayloadService;
import com.emergentterrain.service.RoomService;
import com.emergentterrain.service.WorldService;
import com.emergentterrain.web.GameStateRequest;
import com.emergentterrain.web.GameStateResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@Profile("perf")
public class PerfGameController {
    private final PayloadService payloadService;
    private final WorldService worldService;
    private final RoomService roomService;
    private final ActivityMetrics metrics;
    private final boolean e2eEnabled;

    public PerfGameController(PayloadService payloadService,
                              WorldService worldService,
                              RoomService roomService,
                              ActivityMetrics metrics,
                              @Value("${app.e2e.enabled:false}") boolean e2eEnabled) {
        this.payloadService = payloadService;
        this.worldService = worldService;
        this.roomService = roomService;
        this.metrics = metrics;
        this.e2eEnabled = e2eEnabled;
    }

    @GetMapping("/game/test-token")
    @ResponseBody
    public ResponseEntity<Map<String, String>> gameTestToken() {
        if (!e2eEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        metrics.increment("game.test.token");
        return ResponseEntity.ok(Map.of("token", roomService.buildSystemToken()));
    }

    @PostMapping("/game/state")
    @ResponseBody
    public ResponseEntity<GameStateResponse> gameState(@RequestBody GameStateRequest request) {
        metrics.increment("game.state");
        Payload payload = payloadService.parseToken(request.getToken());
        long seed = payloadService.seedFromPayload(payload);
        return ResponseEntity.ok(worldService.generateWorld(payload, seed));
    }
}
