package com.emergentterrain.gpu;

import com.emergentterrain.gpu.v1.GpuTerrainServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GpuEngineClient {
    private final ManagedChannel channel;
    private final GpuTerrainServiceGrpc.GpuTerrainServiceBlockingStub blockingStub;

    @Autowired
    public GpuEngineClient(
        @Value("${app.gpu.host:127.0.0.1}") String host,
        @Value("${app.gpu.port:50051}") int port,
        @Value("${app.gpu.useTls:false}") boolean useTls
    ) {
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
        if (!useTls) {
            builder.usePlaintext();
        }
        this.channel = builder.build();
        this.blockingStub = GpuTerrainServiceGrpc.newBlockingStub(channel);
    }

    GpuEngineClient(ManagedChannel channel) {
        this.channel = channel;
        this.blockingStub = GpuTerrainServiceGrpc.newBlockingStub(channel);
    }

    public GpuTerrainServiceGrpc.GpuTerrainServiceBlockingStub blockingStub() {
        return blockingStub;
    }

    @PreDestroy
    public void shutdown() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
