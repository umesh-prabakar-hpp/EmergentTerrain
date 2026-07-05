package com.emergentterrain.research.question;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.repo.VerifiedResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 33 lane C (C1, 2026-07-05) — writes gate-surviving numerical results to the
 * {@link VerifiedResult} ledger at publish, extracted DETERMINISTICALLY from the tool-call
 * trace (no LLM in the loop: the tool result lines are the system's own verdicts, and only
 * cleanly-parsed entries are recorded). Failure-soft; bean gated on
 * {@code app.research.results-ledger.enabled}.
 *
 * <p>Extraction classes (v1, conservative):
 * <ul>
 *   <li><b>LADDER</b> — any {@code "… scale-ladder: …"} result (the cross-scale trajectory is
 *       exactly the data a later rate-fit paper needs).</li>
 *   <li><b>CONSTANT</b> — a {@code verify_constant} verdict line carrying a converged/estimated
 *       limit.</li>
 *   <li><b>TIGHT_BOUND</b> — a {@code verify_conjecture} HELD verdict at relative slack
 *       &lt; 0.05, with a note when the trace also carries the refuting counterpart (the
 *       demonstrated-binding pair).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(value = "app.research.results-ledger.enabled", havingValue = "true")
public class VerifiedResultLedgerService {

    private static final Logger log = LoggerFactory.getLogger(VerifiedResultLedgerService.class);

    static final int MAX_RESULTS_PER_PAPER = 4;
    static final double TIGHT_BOUND_MAX_REL_SLACK = 0.05;

    private final VerifiedResultRepository repo;

    public VerifiedResultLedgerService(VerifiedResultRepository repo) {
        this.repo = repo;
    }

    /** Extract and persist; call after publish for non-REJECTED papers. Never throws. */
    public void record(String paperId, String topicName, List<ToolInvocation> trace) {
        try {
            String family = OpenQuestionHarvestService.familyOf(topicName);
            List<VerifiedResult> results = extract(trace, paperId, family);
            if (results.isEmpty()) {
                return;
            }
            repo.saveAll(results);
            log.info("results-ledger: paperId={} family={} recorded={} kinds={}",
                    paperId, family, results.size(),
                    results.stream().map(VerifiedResult::kind).toList());
        } catch (Exception e) {
            log.warn("results-ledger record failed paperId={} (publication unaffected): {}",
                    paperId, e.getMessage());
        }
    }

    /** Deterministic trace → results extraction. Static + pure for tests. */
    static List<VerifiedResult> extract(List<ToolInvocation> trace, String paperId, String family) {
        List<VerifiedResult> out = new ArrayList<>();
        if (trace == null) {
            return out;
        }
        boolean traceHasRefuted = trace.stream().anyMatch(c -> c != null && c.resultJson() != null
                && c.resultJson().contains(" REFUTED across"));
        for (ToolInvocation call : trace) {
            if (out.size() >= MAX_RESULTS_PER_PAPER) {
                break;
            }
            String result = call == null ? null : call.resultJson();
            if (result == null || result.isBlank()) {
                continue;
            }
            if (result.contains("scale-ladder: ")) {
                out.add(new VerifiedResult(paperId, family, "LADDER", verdictLine(result)));
            } else if (result.startsWith("verify_constant: ")
                    && (result.contains("CONVERGED to target") || result.contains("estimated limit"))) {
                out.add(new VerifiedResult(paperId, family, "CONSTANT", verdictLine(result)));
            } else if ("verify_conjecture".equals(call.functionName())
                    && result.contains(" HELD across")
                    && relSlackBelow(result, TIGHT_BOUND_MAX_REL_SLACK)) {
                String stmt = verdictLine(result)
                        + (traceHasRefuted ? " [refuting counterpart present in the same trace]" : "");
                out.add(new VerifiedResult(paperId, family, "TIGHT_BOUND", stmt));
            }
        }
        return out;
    }

    /** The verdict line: everything before the per-window dump / legend, capped for the column. */
    static String verdictLine(String result) {
        int cut = result.length();
        for (String marker : new String[]{"\nverdict legend", " verdict legend",
                "\nPer-window:", " Per-window:", "\ndefinitions ("}) {
            int i = result.indexOf(marker);
            if (i > 0 && i < cut) {
                cut = i;
            }
        }
        String line = result.substring(0, cut).strip();
        return line.length() > 1400 ? line.substring(0, 1400) + "…" : line;
    }

    private static boolean relSlackBelow(String result, double threshold) {
        int i = result.indexOf("relative slack: ");
        if (i < 0) {
            return false;
        }
        int start = i + "relative slack: ".length();
        int end = start;
        while (end < result.length()
                && (Character.isDigit(result.charAt(end)) || result.charAt(end) == '.')) {
            end++;
        }
        try {
            return end > start && Double.parseDouble(result.substring(start, end)) < threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
