package com.emergentterrain.controller;

import com.emergentterrain.service.Payload;
import com.emergentterrain.service.PayloadService;
import com.emergentterrain.service.RoomService;
import com.emergentterrain.web.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class RoomController {
    private final PayloadService payloadService;
    private final RoomService roomService;

    public RoomController(PayloadService payloadService, RoomService roomService) {
        this.payloadService = payloadService;
        this.roomService = roomService;
    }

    @PostMapping("/room/create")
    public ResponseEntity<RoomCreateResponse> createRoom(@RequestBody RoomCreateRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        RoomCreateResponse response = roomService.createRoom(payload, request.getName());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/join")
    public ResponseEntity<RoomJoinResponse> joinRoom(@RequestBody RoomJoinRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        RoomJoinResponse response = roomService.joinRoom(payload, UUID.fromString(request.getRoomId()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/{roomId}/state")
    public ResponseEntity<RoomStateResponse> roomState(@PathVariable("roomId") String roomId,
                                                       @RequestBody RoomJoinRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        RoomStateResponse response = roomService.getRoomState(payload, UUID.fromString(roomId));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/room/{roomId}/move")
    public ResponseEntity<?> move(@PathVariable("roomId") String roomId,
                                  @RequestBody RoomMoveRequest request) {
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomStateResponse response = roomService.move(payload, UUID.fromString(roomId), request.getX(), request.getY());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/room/{roomId}/propose")
    public ResponseEntity<?> propose(@PathVariable("roomId") String roomId,
                                     @RequestBody RoomProposalRequest request) {
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
        Payload payload = payloadService.parseToken(request.getToken());
        try {
            RoomResonanceResponse response = roomService.resonance(payload, UUID.fromString(roomId));
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
