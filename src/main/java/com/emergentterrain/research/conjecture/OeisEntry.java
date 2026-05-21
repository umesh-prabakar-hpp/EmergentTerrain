package com.emergentterrain.research.conjecture;

/**
 * One OEIS sequence reference: the canonical A-number plus a short
 * human-readable name. Mirrors the Python prototype's two-element tuple
 * {@code (A-number, description)} but lifted to a typed record for
 * clearer JSON serialization on the {@link Conjecture#oeisMatchJson} side.
 */
public record OeisEntry(String id, String name) {
}
