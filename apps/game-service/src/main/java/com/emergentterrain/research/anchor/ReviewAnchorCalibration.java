package com.emergentterrain.research.anchor;

import com.emergentterrain.service.LlmRouter;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Phase 32 Wave 3 (32.4) — the calibration harness: grade the review head against the
 * operator's labeled verdicts and report accuracy / Reject-recall / per-class
 * confusion. Runs ONLY when the env var {@code ET_REVIEW_CALIBRATION=1} is
 * set at startup (the {@code ET_DISCOVER_EXEC} convention — never in a normal
 * research session; the operator starts a dedicated app run for it).
 *
 * <p>Protocol: deterministic 80/20 split on paperId hash; the harness
 * evaluates the HELD-OUT 20% only, so the 80% stays clean as a future
 * few-shot/prompt-sweep pool without ever contaminating the reported metrics.
 * ~20-30 LLM calls per run at current label counts.
 *
 * <p>Deploy bar (gates the {@code review-anchor.enabled} flip): binary
 * accuracy ≥ 0.80 AND Reject-recall ≥ 0.90 (a missed Reject is the expensive
 * error). Results CSV: {@code app.university.researchOutputDir/review-anchor-calibration.csv}.
 */
@Component
public class ReviewAnchorCalibration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewAnchorCalibration.class);

    private final LlmRouter router;
    private final Path researchRoot;

    public ReviewAnchorCalibration(LlmRouter router,
                                   @Value("${app.university.researchOutputDir:research}") String researchOutputDir) {
        this.router = router;
        this.researchRoot = Path.of(researchOutputDir);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"1".equals(System.getenv("ET_REVIEW_CALIBRATION"))) {
            return;
        }
        try {
            runCalibration(researchRoot);
        } catch (Exception e) {
            log.error("review-anchor calibration failed: {}", e.getMessage(), e);
        }
    }

    void runCalibration(Path researchRoot) throws Exception {
        VerdictLabels.LoadResult loaded =
                VerdictLabels.load(researchRoot.resolve("review-verdicts.csv"));
        List<VerdictLabels.Label> holdout = loaded.labels().stream()
                .filter(l -> Math.floorMod(l.paperId().hashCode(), 5) == 0)
                .toList();
        log.info("review-anchor calibration: labels={} (skipped={}) holdout={}",
                loaded.labels().size(), loaded.skipped(), holdout.size());

        List<String> rows = new ArrayList<>();
        rows.add("paperId,labeled,predicted,binaryCorrect,rationale");
        int n = 0, fourClassCorrect = 0, binaryCorrect = 0;
        int rejectLabeled = 0, rejectCaught = 0;
        Map<ReviewAnchor.Verdict, Map<ReviewAnchor.Verdict, Integer>> confusion =
                new EnumMap<>(ReviewAnchor.Verdict.class);

        for (VerdictLabels.Label label : holdout) {
            Optional<String> md = findPaperMarkdown(researchRoot, label.paperId());
            if (md.isEmpty()) {
                log.warn("calibration: no markdown found for {} — skipped", label.paperId());
                continue;
            }
            Optional<ReviewAnchor.AnchorVerdict> predicted;
            try {
                String response = com.emergentterrain.llm.LlmCallContext.withRole(
                        "review-anchor",
                        () -> router.callForRole("review-anchor",
                                ReviewAnchor.buildPrompt(md.get())));
                predicted = ReviewAnchor.parse(response);
            } catch (Exception e) {
                log.warn("calibration: call failed for {}: {}", label.paperId(), e.getMessage());
                continue;
            }
            if (predicted.isEmpty()) {
                log.warn("calibration: unparseable verdict for {} — skipped", label.paperId());
                continue;
            }
            n++;
            ReviewAnchor.Verdict want = label.verdict();
            ReviewAnchor.Verdict got = predicted.get().verdict();
            if (want == got) fourClassCorrect++;
            boolean binOk = want.accept() == got.accept();
            if (binOk) binaryCorrect++;
            if (want == ReviewAnchor.Verdict.REJECT) {
                rejectLabeled++;
                if (got == ReviewAnchor.Verdict.REJECT) rejectCaught++;
            }
            confusion.computeIfAbsent(want, k -> new EnumMap<>(ReviewAnchor.Verdict.class))
                    .merge(got, 1, Integer::sum);
            rows.add("%s,%s,%s,%s,\"%s\"".formatted(label.paperId(), want, got, binOk,
                    predicted.get().rationale().replace('"', '\'')));
        }

        Path out = researchRoot.resolve("review-anchor-calibration.csv");
        Files.write(out, rows);
        String rejectRecall = rejectLabeled == 0 ? "n/a (0 labeled Rejects in holdout)"
                : String.format("%.2f (%d/%d)", (double) rejectCaught / rejectLabeled,
                        rejectCaught, rejectLabeled);
        log.info("review-anchor calibration COMPLETE: n={} fourClass={} binary={} "
                        + "rejectRecall={} confusion={} -> {}",
                n,
                n == 0 ? "n/a" : String.format("%.2f", (double) fourClassCorrect / n),
                n == 0 ? "n/a" : String.format("%.2f", (double) binaryCorrect / n),
                rejectRecall, confusion, out);
        log.info("review-anchor deploy bar: binary >= 0.80 AND rejectRecall >= 0.90 "
                + "(n/a Reject-recall means the bar CANNOT pass — collect Reject labels first)");
    }

    /** Locate a paper's markdown across live room dirs and archived snapshots. */
    static Optional<String> findPaperMarkdown(Path researchRoot, String paperId) {
        try (Stream<Path> walk = Files.walk(researchRoot, 4)) {
            return walk.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("_" + paperId + ".md");
                    })
                    .findFirst()
                    .map(p -> {
                        try {
                            return Files.readString(p);
                        } catch (Exception e) {
                            return null;
                        }
                    });
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
