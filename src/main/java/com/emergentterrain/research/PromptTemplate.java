package com.emergentterrain.research;

/**
 * Phase 4c Piece 2 — one prompt body keyed by stable id and a version tag.
 *
 * Templates use the {@code {{ name }}} slot syntax decided in
 * {@code phase-4c-piece-1-memo.md}: each occurrence in {@link #body()} is
 * substituted by {@link PromptTemplateRegistry#render} from a
 * {@code Map<String, Object>} of named values. Whitespace inside the braces
 * is tolerated so authors can write {@code {{name}}} or {@code {{ name }}}
 * to taste.
 *
 * Records are immutable on purpose — the registry holds a list of variants
 * per id and picks the active one at call-time. Piece 3 will turn that
 * "pick" into an A/B + epsilon-greedy decision; for now the pick rule is
 * "the only registered variant" (every id ships with exactly one
 * {@code v1}). When operators add a second variant in Piece 3, the registry
 * gets policy; templates themselves don't change shape.
 *
 * Slot rendering is intentionally narrow: the helper does not do numeric
 * formatting, conditional blocks, or loops. Callers pre-format numeric
 * values into strings ({@code String.format("%.2f", score)}) and assemble
 * any conditional context outside the template. This keeps the renderer at
 * ~20 lines of code with no Mustache / Jinja dependency, matching the
 * memo's stance that variant diffing should be "structural (slot-by-slot)
 * instead of textual."
 */
public record PromptTemplate(String id, String version, String body) {
}
