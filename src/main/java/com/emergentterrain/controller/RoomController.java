package com.emergentterrain.controller;

import com.emergentterrain.service.LlmService;
import com.emergentterrain.service.Payload;
import com.emergentterrain.service.PayloadService;
import com.emergentterrain.service.ActivityMetrics;
import com.emergentterrain.service.RoomService;
import com.emergentterrain.web.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.Map;
import java.util.StringJoiner;

@RestController
public class RoomController {
    private final PayloadService payloadService;
    private final RoomService roomService;
    private final ActivityMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LlmService llmService;

    public RoomController(PayloadService payloadService, RoomService roomService, ActivityMetrics metrics) {
        this.payloadService = payloadService;
        this.roomService = roomService;
        this.metrics = metrics;
    }

    @PostMapping("/room/create")
    public ResponseEntity<RoomCreateResponse> createRoom(@RequestBody RoomCreateRequest request) {
        metrics.increment("room.create");
        Payload payload = payloadService.parseToken(request.getToken());
        RoomCreateResponse response = roomService.createRoom(payload, request.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/join")
    public ResponseEntity<RoomJoinResponse> joinRoom(@RequestBody RoomJoinRequest request) {
        metrics.increment("room.join");
        Payload payload = payloadService.parseToken(request.getToken());
        RoomJoinResponse response = roomService.joinRoom(payload, UUID.fromString(request.getRoomId()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/last")
    public ResponseEntity<RoomJoinResponse> lastRoom(@RequestBody RoomCreateRequest request) {
        metrics.increment("room.join");
        Payload payload = payloadService.parseToken(request.getToken());
        RoomJoinResponse response = roomService.joinOrCreateLastRoom(payload, request.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/{roomId}/state")
    public ResponseEntity<RoomStateResponse> roomState(@PathVariable("roomId") String roomId,
                                                       @RequestBody RoomJoinRequest request) {
        metrics.increment("room.state");
        Payload payload = payloadService.parseToken(request.getToken());
        RoomStateResponse response = roomService.getRoomState(payload, UUID.fromString(roomId));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/{roomId}/government-trend")
    public ResponseEntity<GovernmentTrendResponse> governmentTrend(@PathVariable("roomId") String roomId,
                                                                   @RequestBody GovernmentTrendRequest request) {
        metrics.increment("room.governmentTrend");
        Payload payload = payloadService.parseToken(request.getToken());
        GovernmentTrendResponse response =
            roomService.getGovernmentTrend(payload, UUID.fromString(roomId), request.getWindowMinutes());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/{roomId}/move")
    public ResponseEntity<?> move(@PathVariable("roomId") String roomId,
                                  @RequestBody RoomMoveRequest request) {
        metrics.increment("room.move");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomService.MoveResult result = roomService.moveWithTimings(payload, UUID.fromString(roomId), request);
            HttpHeaders headers = new HttpHeaders();
            headers.add("Server-Timing", formatServerTiming(result.timings()));
            headers.add("X-Move-Timing", formatMoveTiming(result.timings()));
            return ResponseEntity.ok().headers(headers).body(result.response());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/propose")
    public ResponseEntity<?> propose(@PathVariable("roomId") String roomId,
                                     @RequestBody RoomProposalRequest request) {
        metrics.increment("room.propose");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomStateResponse response = roomService.propose(payload, UUID.fromString(roomId), request);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/vote")
    public ResponseEntity<?> vote(@PathVariable("roomId") String roomId,
                                  @RequestBody RoomVoteRequest request) {
        metrics.increment("room.vote");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomStateResponse response = roomService.vote(payload, UUID.fromString(roomId), request);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/apply")
    public ResponseEntity<?> apply(@PathVariable("roomId") String roomId,
                                   @RequestBody RoomApplyRequest request) {
        metrics.increment("room.apply");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomStateResponse response = roomService.apply(payload, UUID.fromString(roomId), request);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/memory")
    public ResponseEntity<?> addMemory(@PathVariable("roomId") String roomId,
                                       @RequestBody RoomMemoryRequest request) {
        metrics.increment("room.memory");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomStateResponse response = roomService.addMemory(payload, UUID.fromString(roomId), request);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/bot")
    public ResponseEntity<?> botPrompt(@PathVariable("roomId") String roomId,
                                       @RequestBody RoomBotPromptRequest request) {
        metrics.increment("room.botPrompt");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomBotPromptResponse response = roomService.botPrompt(payload, UUID.fromString(roomId), request.getType());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/resonance")
    public ResponseEntity<?> resonance(@PathVariable("roomId") String roomId,
                                       @RequestBody RoomJoinRequest request) {
        metrics.increment("room.resonance");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomResonanceResponse response = roomService.resonance(payload, UUID.fromString(roomId));
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/poll/dismiss")
    public ResponseEntity<?> dismissPoll(@PathVariable("roomId") String roomId,
                                         @RequestBody RoomDismissRequest request) {
        metrics.increment("room.pollDismiss");
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomStateResponse response = roomService.dismissPoll(payload, UUID.fromString(roomId), request.getProposalId());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/explain")
    public ResponseEntity<Void> explain(@PathVariable("roomId") String roomId,
                                        @RequestBody ExplainRequest request) {
        metrics.increment("room.explain");
        Payload payload = payloadService.parseToken(request.getToken());
        if (llmService != null) {
            String streamId = request.getStreamId() != null ? request.getStreamId()
                    : java.util.UUID.randomUUID().toString();
            llmService.generateExplanation(
                    UUID.fromString(roomId), streamId,
                    request.getX(), request.getY(), request.getTileType(),
                    payload.answers());
        }
        return ResponseEntity.accepted().build();
    }

    private String formatServerTiming(Map<String, Double> timings) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, Double> entry : timings.entrySet()) {
            joiner.add(entry.getKey() + ";dur=" + String.format(java.util.Locale.ROOT, "%.1f", entry.getValue()));
        }
        return joiner.toString();
    }

    private String formatMoveTiming(Map<String, Double> timings) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, Double> entry : timings.entrySet()) {
            joiner.add(entry.getKey() + "=" + String.format(java.util.Locale.ROOT, "%.1f", entry.getValue()));
        }
        return joiner.toString();
    }
}
