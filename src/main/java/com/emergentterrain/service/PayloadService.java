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
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
public class PayloadService {
    private final ObjectMapper objectMapper;
    private final byte[] secret;

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
            return objectMapper.readValue(json, Payload.class);
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
}
