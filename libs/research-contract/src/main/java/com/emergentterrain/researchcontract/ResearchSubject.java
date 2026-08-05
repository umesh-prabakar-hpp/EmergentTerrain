package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Objects;
import java.util.UUID;

/** A research-relevant subject expressed without a source application's domain model. */
public record ResearchSubject(UUID subjectId, String kind, JsonNode attributes) {

    public ResearchSubject {
        Objects.requireNonNull(subjectId, "subjectId");
        kind = requireText(kind, "kind");
        attributes = attributes == null ? JsonNodeFactory.instance.objectNode() : attributes;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
