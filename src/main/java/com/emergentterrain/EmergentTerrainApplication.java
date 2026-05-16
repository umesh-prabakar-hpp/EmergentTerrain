package com.emergentterrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EmergentTerrainApplication {

    static final Path LOG_DIR = Paths.get("logs");

    public static void main(String[] args) {
        archivePreviousLogs();
        ensureLogDir();
        SpringApplication.run(EmergentTerrainApplication.class, args);
    }

    static void archivePreviousLogs() {
        if (!Files.isDirectory(LOG_DIR)) return;
        boolean hasContent;
        try (var entries = Files.list(LOG_DIR)) {
            hasContent = entries.findAny().isPresent();
        } catch (IOException e) {
            System.err.println("[boot] could not list " + LOG_DIR + ": " + e.getMessage());
            return;
        }
        if (!hasContent) return;
        String stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path target = LOG_DIR.resolveSibling("logs-archive-" + stamp);
        int n = 2;
        while (Files.exists(target)) {
            target = LOG_DIR.resolveSibling("logs-archive-" + stamp + "-" + n);
            n++;
        }
        try {
            Files.move(LOG_DIR, target);
            System.out.println("[boot] archived previous logs to " + target);
        } catch (IOException e) {
            System.err.println("[boot] could not archive " + LOG_DIR + ": " + e.getMessage());
        }
    }

    static void ensureLogDir() {
        try {
            Files.createDirectories(LOG_DIR);
        } catch (IOException e) {
            System.err.println("[boot] could not create " + LOG_DIR + ": " + e.getMessage());
        }
    }
}
