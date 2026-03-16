package com.emergentterrain.service;

import com.emergentterrain.domain.Profile;
import com.emergentterrain.domain.User;
import com.emergentterrain.repo.ProfileRepository;
import com.emergentterrain.web.ProfileForm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@org.springframework.context.annotation.Profile("!perf")
public class ProfileService {
    private final ProfileRepository profileRepository;
    private final ObjectMapper objectMapper;
    private final PayloadService payloadService;

    public ProfileService(ProfileRepository profileRepository, ObjectMapper objectMapper, PayloadService payloadService) {
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
        this.payloadService = payloadService;
    }

    public ProfileResult createProfile(User user, ProfileForm form) {
        Map<String, Boolean> answers = new LinkedHashMap<>();
        answers.put("likes_forests", form.isLikesForests());
        answers.put("likes_water", form.isLikesWater());
        answers.put("likes_mountains", form.isLikesMountains());
        answers.put("likes_open_fields", form.isLikesOpenFields());
        answers.put("likes_wet_climate", form.isLikesWetClimate());
        answers.put("likes_sunny", form.isLikesSunny());

        HiddenProperties hidden = deriveHidden(form, answers);
        Payload payload = new Payload(
            1,
            "",
            form.getAge(),
            form.getSex(),
            form.getLocation(),
            answers,
            hidden,
            payloadService.issuedAtNow()
        );

        Profile profile = new Profile();
        profile.setId(java.util.UUID.randomUUID());
        profile.setUser(user);
        profile.setAge(form.getAge());
        profile.setSex(form.getSex());
        profile.setLocation(form.getLocation());
        try {
            profile.setAnswersJson(objectMapper.writeValueAsString(answers));
            profile.setHiddenPropsJson(objectMapper.writeValueAsString(hidden));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize profile", e);
        }

        Payload payloadWithId = new Payload(
            payload.v(),
            profile.getId().toString(),
            payload.age(),
            payload.sex(),
            payload.location(),
            payload.answers(),
            payload.hidden(),
            payload.issuedAt()
        );
        String token = payloadService.buildToken(payloadWithId);
        profile.setPayloadHash(payloadService.hashPayload(payloadWithId));
        profile = profileRepository.save(profile);

        return new ProfileResult(profile, token, payloadWithId);
    }

    public java.util.List<com.emergentterrain.domain.Profile> getProfilesForUser(User user) {
        return profileRepository.findByUser(user);
    }

    public String buildTokenForProfile(Profile profile) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Boolean> answers = objectMapper.readValue(profile.getAnswersJson(), Map.class);
            HiddenProperties hidden = objectMapper.readValue(profile.getHiddenPropsJson(), HiddenProperties.class);
            Payload payload = new Payload(
                1,
                profile.getId().toString(),
                profile.getAge(),
                profile.getSex(),
                profile.getLocation(),
                answers,
                hidden,
                payloadService.issuedAtNow()
            );
            return payloadService.buildToken(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build token for profile", e);
        }
    }

    public HiddenProperties deriveHidden(ProfileForm form, Map<String, Boolean> answers) {
        double ageN = clamp((form.getAge() - 13.0) / (80.0 - 13.0));
        double sexN = mapSex(form.getSex());
        double locN = hash01(form.getLocation());

        double[] f = new double[] {
            ageN,
            sexN,
            locN,
            answers.get("likes_forests") ? 1 : 0,
            answers.get("likes_water") ? 1 : 0,
            answers.get("likes_mountains") ? 1 : 0,
            answers.get("likes_open_fields") ? 1 : 0,
            answers.get("likes_wet_climate") ? 1 : 0,
            answers.get("likes_sunny") ? 1 : 0
        };

        double pressure = sigmoid(dot(f, new double[] {0.2,0.1,0.1,0.1,0,0.1,0.1,0.1,0.1}));
        double temperature = sigmoid(dot(f, new double[] {0.1,0.2,0.2,0,0.1,0.2,0,0.1,0.1}));
        double humidity = sigmoid(dot(f, new double[] {0.1,0.1,0.3,0.2,0.1,0,0,0.1,0.1}));
        double density = sigmoid(dot(f, new double[] {0.2,0.1,0.1,0.2,0,0.1,0.1,0.1,0.1}));
        double energy = sigmoid(dot(f, new double[] {0.1,0.2,0.1,0.1,0.1,0.1,0.1,0.1,0.1}));
        double entropy = sigmoid(1.0 - variance(f));

        return new HiddenProperties(pressure, temperature, humidity, density, energy, entropy);
    }

    private double mapSex(String sex) {
        if (sex == null) {
            return 0.4;
        }
        return switch (sex.toUpperCase()) {
            case "MALE" -> 0.2;
            case "FEMALE" -> 0.6;
            default -> 0.4;
        };
    }

    private double hash01(String input) {
        if (input == null) {
            return 0.5;
        }
        long h = 1125899906842597L;
        for (char c : input.toCharArray()) {
            h = 31 * h + c;
        }
        double v = (double) (h & 0x7fffffff) / (double) Integer.MAX_VALUE;
        return clamp(v);
    }

    private double dot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private double variance(double[] a) {
        double mean = 0.0;
        for (double v : a) {
            mean += v;
        }
        mean /= a.length;
        double sum = 0.0;
        for (double v : a) {
            double d = v - mean;
            sum += d * d;
        }
        return sum / a.length;
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
