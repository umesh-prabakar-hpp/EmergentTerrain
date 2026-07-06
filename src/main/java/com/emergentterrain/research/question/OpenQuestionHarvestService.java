package com.emergentterrain.research.question;

import com.emergentterrain.repo.PaperOpenQuestionRepository;
import com.emergentterrain.service.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 33 lane T (T1, 2026-07-05) — open-question harvester. At publish time, one cheap
 * fast-audit-pool call extracts the follow-up questions the paper's own prose names as open or
 * untested, and persists them to the {@link PaperOpenQuestion} ledger. Extraction only — the
 * prompt forbids inventing questions the text does not state, so the ledger inherits the
 * papers' (good) taste rather than a model's guess. Serving them back into topic selection is
 * lane T2, deliberately separate.
 *
 * <p>Failure-soft everywhere: an LLM outage, a parse miss, or a repo error logs and returns —
 * publication is never affected. Bean exists only when
 * {@code app.research.question-harvest.enabled=true}; absent bean = byte-identical pipeline.
 */
@Component
@ConditionalOnProperty(value = "app.research.question-harvest.enabled", havingValue = "true")
public class OpenQuestionHarvestService {

    private static final Logger log = LoggerFactory.getLogger(OpenQuestionHarvestService.class);

    static final Set<String> KINDS = Set.of("RATE", "BINDING", "SENSITIVITY", "SCOPE", "OTHER");
    static final int MAX_QUESTIONS_PER_PAPER = 3;
    static final int MAX_INPUT_CHARS = 6000;

    private final LlmRouter router;
    private final PaperOpenQuestionRepository repo;
    private final ObjectMapper mapper;

    public OpenQuestionHarvestService(LlmRouter router, PaperOpenQuestionRepository repo,
                                      ObjectMapper mapper) {
        this.router = router;
        this.repo = repo;
        this.mapper = mapper;
    }

    /**
     * Extract and persist the paper's self-stated open questions. Call after publish; any
     * failure is contained here.
     */
    public void harvest(String paperId, String topicName, String findings, String conclusion) {
        try {
            String body = ((findings == null ? "" : findings) + "\n"
                    + (conclusion == null ? "" : conclusion)).strip();
            if (body.isBlank()) {
                return;
            }
            if (body.length() > MAX_INPUT_CHARS) {
                // The self-critique lives at the END of sections (limitations, "remains
                // untested", future work) — keep the tail, not the head.
                body = "…" + body.substring(body.length() - MAX_INPUT_CHARS);
            }
            String raw = router.callForRole("fast-audit", buildPrompt(body));
            List<PaperOpenQuestion> questions =
                    parse(raw, paperId, familyOf(topicName), mapper);
            if (questions.isEmpty()) {
                return;
            }
            repo.saveAll(questions);
            log.info("question-harvest: paperId={} family={} harvested={} kinds={}",
                    paperId, familyOf(topicName), questions.size(),
                    questions.stream().map(PaperOpenQuestion::kind).toList());
        } catch (Exception e) {
            log.warn("question-harvest failed paperId={} (publication unaffected): {}",
                    paperId, e.getMessage());
        }
    }

    /** The topic family: the conjecture lhs for conjecture topics, else the topic name itself. */
    public static String familyOf(String topicName) {
        if (topicName == null || topicName.isBlank()) {
            return "unknown";
        }
        return topicName.startsWith("conjecture/")
                ? topicName.substring("conjecture/".length())
                : topicName;
    }

    static String buildPrompt(String paperText) {
        return """
                Extract the open questions this research text ITSELF names as open, untested, or \
                future work. Extraction only — do NOT invent questions the text does not state; \
                if it names none, return [].
                Classify each: RATE (a rate/trajectory of approach or drift is the unknown), \
                BINDING (where a bound binds / a refutation is missing), SENSITIVITY (robustness \
                to window size, placement, or parameters), SCOPE (larger ranges / held-out \
                regions), OTHER.
                Return ONLY a JSON array, at most %d entries, each \
                {"question":"<one sentence, self-contained>","kind":"RATE|BINDING|SENSITIVITY|SCOPE|OTHER"}.

                TEXT:
                %s""".formatted(MAX_QUESTIONS_PER_PAPER, paperText);
    }

    /**
     * Lenient parse: first '[' to last ']' (fast-audit models wrap JSON in prose), unknown kinds
     * coerced to OTHER, blank/oversized questions dropped, capped at
     * {@link #MAX_QUESTIONS_PER_PAPER}. Static + pure for tests.
     */
    static List<PaperOpenQuestion> parse(String raw, String paperId, String family,
                                          ObjectMapper mapper) {
        List<PaperOpenQuestion> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        int a = raw.indexOf('[');
        int b = raw.lastIndexOf(']');
        if (a < 0 || b <= a) {
            return out;
        }
        try {
            JsonNode arr = mapper.readTree(raw.substring(a, b + 1));
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                if (out.size() >= MAX_QUESTIONS_PER_PAPER) {
                    break;
                }
                String q = n.path("question").asText("").strip();
                if (q.isBlank() || q.length() > 1000) {
                    continue;
                }
                String kind = n.path("kind").asText("OTHER").toUpperCase(java.util.Locale.ROOT);
                out.add(new PaperOpenQuestion(paperId, family,
                        q, KINDS.contains(kind) ? kind : "OTHER"));
            }
        } catch (Exception e) {
            return List.of();   // parse miss = harvest nothing; never throw
        }
        return out;
    }
}
