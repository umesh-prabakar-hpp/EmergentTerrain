package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.List;

/**
 * One immutable, read-only revision in a research output's quality-assessment history.
 *
 * <p>The summary deliberately exposes assessment provenance and bounded evidence references,
 * not mutable entities, prompts, or the full retained research payload.</p>
 */
public record ResearchQualityAssessmentSummary(
        int assessmentRevision,
        String grade,
        String rubricVersion,
        String assessorModel,
        Instant assessedAt,
        List<String> evidenceReferences,
        List<String> unmetRequirements,
        List<String> contradictions) { }
