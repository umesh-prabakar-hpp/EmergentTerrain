package com.emergentterrain.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class DiagnosticsController {
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    @GetMapping("/diag/cdn")
    public ResponseEntity<List<Map<String, Object>>> cdnCheck() {
        List<String> urls = List.of(
            "https://cdn.jsdelivr.net/npm/@material/web@2.4.1/all.js",
            "https://cdn.jsdelivr.net/npm/tslib@2.6.2/tslib.es6.js",
            "https://fonts.googleapis.com/css?family=Roboto:300,400,500",
            "https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined",
            "https://fonts.gstatic.com/s/roboto/v30/KFOmCnqEu92Fr1Mu4mxP.ttf"
        );

        List<Map<String, Object>> results = new ArrayList<>();
        for (String url : urls) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("url", url);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                entry.put("status", response.statusCode());
                entry.put("ok", response.statusCode() >= 200 && response.statusCode() < 400);
            } catch (Exception e) {
                entry.put("status", -1);
                entry.put("ok", false);
                entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            results.add(entry);
        }

        return ResponseEntity.ok(results);
    }
}
