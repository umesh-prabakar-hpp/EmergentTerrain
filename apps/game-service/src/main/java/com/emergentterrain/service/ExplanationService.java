package com.emergentterrain.service;

import com.emergentterrain.web.GameDelta;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Profile("!perf")
public class ExplanationService {
    public String explain(GameDelta delta, Map<String, Boolean> answers) {
        if (delta == null || answers == null) {
            return "No changes detected.";
        }

        if (delta.getForestTilesAdded() > delta.getForestTilesRemoved()) {
            return "You increased forest density. This aligns with likes_forests=" + answers.get("likes_forests") +
                " and likes_open_fields=" + answers.get("likes_open_fields");
        }

        if (delta.getClearTilesAdded() > 0) {
            return "You increased open areas. This aligns with likes_open_fields=" + answers.get("likes_open_fields") +
                " and likes_forests=" + answers.get("likes_forests");
        }

        return "You adjusted terrain balance. This relates to your overall preferences.";
    }
}
