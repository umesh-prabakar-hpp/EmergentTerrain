package com.emergentterrain.research.anchor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 32 Wave 3 (32.4) — loader/normalizer for the harvested operator
 * verdict store ({@code scripts/backfill-review-verdicts.sh} →
 * {@code research/review-verdicts.csv}). The harvest is raw: older reports
 * drifted in column order and verdict spelling ("Minor rev." vs "Minor
 * revision"; a few rows carry placeholders like "*(no .md — gap)*"). This
 * class normalizes what it can and SKIPS what it can't — a calibration set
 * with silent garbage rows is worse than a smaller clean one; skipped counts
 * are reported, never hidden.
 */
public final class VerdictLabels {

    private VerdictLabels() {}

    public record Label(String paperId, ReviewAnchor.Verdict verdict, String source) {}

    public record LoadResult(List<Label> labels, int skipped) {}

    /**
     * Parse the CSV; later rows win per paperId (sources are chronological in
     * the harvest, so a re-review supersedes). Verdict normalization is
     * deliberately a closed map — anything else skips.
     */
    public static LoadResult load(Path csv) throws IOException {
        Map<String, Label> byId = new LinkedHashMap<>();
        int skipped = 0;
        List<String> lines = Files.readAllLines(csv);
        for (int i = 1; i < lines.size(); i++) {           // skip header
            String[] f = splitCsv(lines.get(i));
            if (f.length < 8) {
                skipped++;
                continue;
            }
            String paperId = f[0].trim();
            Optional<ReviewAnchor.Verdict> v = normalize(f[4]);
            if (paperId.isEmpty() || !paperId.startsWith("P-") || v.isEmpty()) {
                skipped++;
                continue;
            }
            byId.put(paperId, new Label(paperId, v.get(), f[7].trim()));
        }
        return new LoadResult(List.copyOf(byId.values()), skipped);
    }

    static Optional<ReviewAnchor.Verdict> normalize(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.replace("\"", "").trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("acceptable")) return Optional.of(ReviewAnchor.Verdict.ACCEPTABLE);
        if (s.startsWith("minor rev")) return Optional.of(ReviewAnchor.Verdict.MINOR_REVISION);
        if (s.startsWith("major rev")) return Optional.of(ReviewAnchor.Verdict.MAJOR_REVISION);
        if (s.startsWith("reject")) return Optional.of(ReviewAnchor.Verdict.REJECT);
        return Optional.empty();
    }

    /** Minimal RFC-4180-ish split: quoted fields may contain commas. */
    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
