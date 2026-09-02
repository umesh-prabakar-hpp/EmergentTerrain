package com.emergentterrain.government;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class SimulationHistoryWriter {
    private final ObjectMapper objectMapper;
    private final Path root;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public SimulationHistoryWriter(ObjectMapper objectMapper,
                                   @Value("${app.simulationHistory.root:.runtime/runtime-history}") String rootPath) {
        this.objectMapper = objectMapper;
        this.root = Path.of(rootPath);
        ensureDirectories();
    }

    public void writeImmutableJson(String relativePath, Object payload) {
        writer.submit(() -> {
            try {
                Path target = root.resolve(relativePath);
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    return;
                }
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
                Files.writeString(target, json, StandardOpenOption.CREATE_NEW);
            } catch (IOException ignored) {
            }
        });
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(root.resolve("constitution"));
            Files.createDirectories(root.resolve("events"));
            Files.createDirectories(root.resolve("elections"));
            Files.createDirectories(root.resolve("courts"));
            Files.createDirectories(root.resolve("legislation"));
        } catch (IOException ignored) {
        }
    }

    @PreDestroy
    public void shutdown() {
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
