package com.emergentterrain.gpu;

import com.emergentterrain.gpu.v1.EvalExprRequest;
import com.emergentterrain.gpu.v1.EvalExprResponse;
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

    /**
     * Phase 8 Tier 4 — evaluate a scalar expression f(n) on the GPU over
     * {@code [start, end)}. The Rust engine parses, validates, generates
     * WGSL, dispatches, and aggregates. Caller is responsible for the
     * formula's static check (see {@code GpuEvalStaticChecker}) before
     * this method is invoked.
     */
    public EvalExprResponse evalExpr(EvalExprRequest request) {
        return client.blockingStub().evalExpr(request);
    }
}
