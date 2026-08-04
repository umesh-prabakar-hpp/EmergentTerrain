package com.emergentterrain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
@Profile("!perf")
public class SnapshotTokenService {
    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public SnapshotTokenService(ObjectMapper objectMapper, @Value("${app.qr.hmacSecret}") String secret) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String buildToken(SnapshotPayload payload) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(payload);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json);
            String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(json));
            return payloadB64 + "." + sigB64;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build snapshot token", e);
        }
    }

    public SnapshotPayload parseToken(String token) {
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
            return objectMapper.readValue(payloadBytes, SnapshotPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid token", e);
        }
    }

    private byte[] hmac(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
