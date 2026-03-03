package com.emergentterrain.gpu;

import com.emergentterrain.gpu.v1.GenerateRegionRequest;
import com.emergentterrain.gpu.v1.GenerateRegionResponse;
import com.emergentterrain.gpu.v1.RebuildPatchRequest;
import com.emergentterrain.gpu.v1.RebuildPatchResponse;
import com.emergentterrain.gpu.v1.SimulateBotsRequest;
import com.emergentterrain.gpu.v1.SimulateBotsResponse;
import org.springframework.stereotype.Service;

@Service
public class GpuEngineAdapter {
    private final GpuEngineClient client;

    public GpuEngineAdapter(GpuEngineClient client) {
        this.client = client;
    }

    public GenerateRegionResponse generateRegion(GenerateRegionRequest request) {
        return client.blockingStub().generateRegion(request);
    }

    public RebuildPatchResponse rebuildPatch(RebuildPatchRequest request) {
        return client.blockingStub().rebuildPatch(request);
    }

    public SimulateBotsResponse simulateBots(SimulateBotsRequest request) {
        return client.blockingStub().simulateBots(request);
    }
}
