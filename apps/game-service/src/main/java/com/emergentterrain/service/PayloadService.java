package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class PayloadService {
    private static final int TOKEN_CACHE_MAX_ENTRIES = 512;
    private static final long TOKEN_CACHE_TTL_MS = 60_000;
    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final ConcurrentHashMap<String, CachedPayload> parsedTokenCache = new ConcurrentHashMap<>();

    public PayloadService(ObjectMapper objectMapper, @Value("${app.qr.hmacSecret}") String secret) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String buildToken(Payload payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            byte[] compressed = gzip(json);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed);
            String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(compressed));
            return payloadB64 + "." + sigB64;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build token", e);
        }
    }

    public Payload parseToken(String token) {
        CachedPayload cached = parsedTokenCache.get(token);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.createdAtMs) <= TOKEN_CACHE_TTL_MS) {
            return cached.payload;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid token format");
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            byte[] sig = Base64.getUrlDecoder().decode(parts[1]);
            byte[] expected = hmac(payloadBytes);
            if (!MessageDigest.isEqual(sig, expected)) {
                throw new IllegalArgumentException("Invalid token signature");
            }
            byte[] json = gunzip(payloadBytes);
            Payload payload = objectMapper.readValue(json, Payload.class);
            cacheParsedToken(token, payload, now);
            return payload;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid token", e);
        }
    }

    public String issuedAtNow() {
        return Instant.now().toString();
    }

    public long seedFromPayload(Payload payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xff);
            }
            return seed;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute seed", e);
        }
    }

    public String hashPayload(Payload payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash payload", e);
        }
    }

    private byte[] hmac(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(input);
        }
        return bos.toByteArray();
    }

    private byte[] gunzip(byte[] input) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(input))) {
            return gis.readAllBytes();
        }
    }

    private void cacheParsedToken(String token, Payload payload, long now) {
        if (parsedTokenCache.size() >= TOKEN_CACHE_MAX_ENTRIES) {
            pruneParsedTokenCache(now);
            if (parsedTokenCache.size() >= TOKEN_CACHE_MAX_ENTRIES) {
                String oldestKey = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<String, CachedPayload> entry : parsedTokenCache.entrySet()) {
                    if (entry.getValue().createdAtMs < oldestAt) {
                        oldestAt = entry.getValue().createdAtMs;
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey != null) {
                    parsedTokenCache.remove(oldestKey);
                }
            }
        }
        parsedTokenCache.put(token, new CachedPayload(payload, now));
    }

    private void pruneParsedTokenCache(long now) {
        parsedTokenCache.entrySet().removeIf(entry -> (now - entry.getValue().createdAtMs) > TOKEN_CACHE_TTL_MS);
    }

    private record CachedPayload(Payload payload, long createdAtMs) {
    }
}
