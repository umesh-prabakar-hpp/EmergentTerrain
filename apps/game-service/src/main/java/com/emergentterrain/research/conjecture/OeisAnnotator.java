package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Computes the OEIS annotation for one conjecture (Phase 23.2). Given
 * an inequality's {@code lhs} and {@code rhs} scalar names, looks each
 * up in the {@link OeisCatalog} and returns the JSON blob that lands in
 * {@link Conjecture#oeisMatchJson}.
 *
 * <p>Annotation runs during {@code ConjectureMinerService.mine()} so the
 * persisted blob is fresh after each mining pass. Editing
 * {@link OeisCatalog} requires either another mine pass (which is the
 * normal path) or an admin re-annotation endpoint — the catalog itself
 * is static / in-code.
 *
 * <p>Output JSON shape (kept compatible with the Python prototype):
 * <pre>
 * { "lhs": [{"id":"A005101","name":"Abundant numbers"}],
 *   "rhs": [{"id":"A000040","name":"The prime numbers"}],
 *   "has_match": true }
 * </pre>
 */
@Component
public class OeisAnnotator {

    private final ObjectMapper objectMapper;

    public OeisAnnotator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the JSON annotation for a conjecture, or null if neither
     * side has a catalog entry. A null return tells the caller "don't
     * write to {@code oeisMatchJson}" so the column stays sparse for
     * conjectures involving only DERIVED scalars without an OEIS analog.
     */
    public String annotate(String lhs, String rhs) {
        List<OeisEntry> lhsEntries = OeisCatalog.lookup(lhs);
        List<OeisEntry> rhsEntries = OeisCatalog.lookup(rhs);
        boolean hasMatch = !lhsEntries.isEmpty() || !rhsEntries.isEmpty();
        if (!hasMatch) {
            return null;
        }
        Map<String, Object> payload = Map.of(
                "lhs", lhsEntries,
                "rhs", rhsEntries,
                "has_match", true
        );
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode OEIS annotation as JSON", e);
        }
    }
}
