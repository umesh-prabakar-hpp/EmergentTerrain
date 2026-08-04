package com.emergentterrain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.emergentterrain.research.api.ResearchApiProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ResearchApiProperties.class)
public class EmergentTerrainApplication {

    static final Path LOG_DIR = Paths.get("logs");
    static final Path LOG_ARCHIVE_DIR = LOG_DIR.resolve("archive");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) {
        ensureLogDir();
        archivePreviousLogs();
        SpringApplication.run(EmergentTerrainApplication.class, args);
    }

    static void ensureLogDir() {
        try {
            Files.createDirectories(LOG_ARCHIVE_DIR);
        } catch (IOException e) {
            System.err.println("[boot] could not create " + LOG_ARCHIVE_DIR + ": " + e.getMessage());
        }
    }

    static void archivePreviousLogs() {
        if (!Files.isDirectory(LOG_DIR)) return;
        try (var entries = Files.list(LOG_DIR)) {
            entries
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(EmergentTerrainApplication::moveToArchive);
        } catch (IOException e) {
            System.err.println("[boot] could not list " + LOG_DIR + ": " + e.getMessage());
        }
    }

    private static void moveToArchive(Path src) {
        String name = src.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot) : "";
        String stamp;
        try {
            stamp = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(src).toInstant(),
                    ZoneId.systemDefault()).format(STAMP);
        } catch (IOException e) {
            stamp = LocalDateTime.now().format(STAMP);
        }
        Path target = LOG_ARCHIVE_DIR.resolve(base + "-" + stamp + ext);
        int n = 2;
        while (Files.exists(target)) {
            target = LOG_ARCHIVE_DIR.resolve(base + "-" + stamp + "-" + n + ext);
            n++;
        }
        try {
            Files.move(src, target);
            System.out.println("[boot] archived " + src.getFileName() + " -> " + target);
        } catch (IOException e) {
            System.err.println("[boot] could not archive " + src + ": " + e.getMessage());
        }
    }
}
