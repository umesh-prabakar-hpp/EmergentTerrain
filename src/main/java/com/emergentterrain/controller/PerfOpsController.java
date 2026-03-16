package com.emergentterrain.controller;

import com.emergentterrain.service.RoomService;
import com.emergentterrain.web.PerfBotSimulationRequest;
import com.emergentterrain.web.PerfBotSimulationResponse;
import com.emergentterrain.web.PerfPopulateResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Profile("perf")
public class PerfOpsController {
    private final RoomService roomService;

    public PerfOpsController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping("/perf/room/{roomId}/populate")
    public ResponseEntity<PerfPopulateResponse> populateRoom(@PathVariable("roomId") String roomId) {
        PerfPopulateResponse response = new PerfPopulateResponse();
        response.setBotCount(roomService.populateRoomForPerf(UUID.fromString(roomId)));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/perf/room/{roomId}/bot-simulation")
    public ResponseEntity<PerfBotSimulationResponse> runBotSimulation(@PathVariable("roomId") String roomId,
                                                                      @RequestBody PerfBotSimulationRequest request) {
        RoomService.PerfBotSimulationResult result = roomService.runBotSimulationForPerf(
            UUID.fromString(roomId),
            request.getIterations(),
            request.isClearGpuWorldCache()
        );
        PerfBotSimulationResponse response = new PerfBotSimulationResponse();
        response.setIterations(result.iterations());
        response.setBotCount(result.botCount());
        response.setTotalMs(result.totalMs());
        response.setAverageMs(result.averageMs());
        response.setMinMs(result.minMs());
        response.setMaxMs(result.maxMs());
        return ResponseEntity.ok(response);
    }
}
