package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.EvidenceLabel;
import com.emergentterrain.research.PaperKind;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.memory.ResearchMemoryEntity;
import com.emergentterrain.research.memory.ResearchMemoryRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.research.synthesis.SynthesisComposer.SourcePaper;
import com.emergentterrain.service.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 31 §31.7 — the synthesis-paper orchestrator. Composes a review paper
 * over the room's own prior papers on one topic family (the kernel —
 * {@link SynthesisComposer} / {@link SynthesisWarrant} / {@link SynthesisLabelCap}
 * — supplies the pure pieces; this wires them to the corpus, the router, and
 * persistence).
 *
 * <p>One pass: pick the most-warranted family (≥{@code minSources} cite-able
 * papers, none yet synthesized this window) → outline call → draft each section
 * with a digest of the already-written sections (anti-repetition) → enforce
 * that every {@code [P-…]} in the prose is a cited source → cap the evidence
 * label at the weakest source → persist as {@link PaperKind#SYNTHESIS}.
 * {@code NULL_SYNTHESIS} (the corpus doesn't support a synthesis) is a
 * first-class outcome that publishes nothing.
 *
 * <p>Scheduled low-frequency, flag-gated OFF, drain-aware. The compose core is
 * router-free-testable via an injectable synthesizer.
 */
@Service
public class SynthesisPaperService {

    private static final Logger log = LoggerFactory.getLogger(SynthesisPaperService.class);

    private static final Pattern PAPER_CITE = Pattern.compile("\\[(P-[0-9a-f]{4}-[0-9]+)\\]");
    // 2026-06-08 (synthesis #2): cap was 120 — a 125-char descriptive title
    // (real, from the live outline) failed to match, so the regex desynced and
    // captured the ", " array separators as titles (→ "## ,"), corrupting
    // every title AFTER the long one too. Raising the MAX to 250 keeps the
    // regex aligned (the long title now matches). Min stays 1 — the
    // letter-less render guard (compose loop) catches any stray separator
    // capture, so no min cap is needed and short titles still parse.
    private static final Pattern TITLE_JSON = Pattern.compile("\"([^\"]{1,250})\"");

    private final ResearchPaperRepository papersRepository;
    private final ResearchMemoryRepository memoryRepository;
    private final LlmRouter router;
    private final boolean enabled;
    private final int minSources;
    private final int minSections;
    private final int maxSections;
    /**
     * 2026-06-11 — evidence-based re-synthesis. 0 (default) = legacy one-synthesis-per-family-ever (the
     * permanent dedup). When &gt; 0, a family is re-synthesized once it has gained at least this many NEW
     * cite-able (PUBLISHED, non-synthesis) source papers SINCE its most-recent synthesis — so a stale
     * review refreshes when the corpus has genuinely grown, without re-reviewing unchanged families.
     */
    private final int reSynthesisMinNewSources;

    /**
     * Phase 33 lane C (C2, 2026-07-06) — ledger-driven synthesis. The run-29 synthesis cited a
     * stale k because it reads paper prose; with the flag on, the family's verified-results
     * ledger rides both composer prompts (authoritative numbers + a mandatory quantitative-fit
     * section when ≥3 comparable results exist), and the published synthesis is harvested for
     * open questions like any paper — closing the papers→results→fit→questions loop.
     * Repos/harvester are optional so the service stays constructible in tests.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.results-ledger.synthesis-enabled:false}")
    private boolean ledgerSynthesisEnabled;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.repo.VerifiedResultRepository verifiedResultRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.question.OpenQuestionHarvestService questionHarvest;
    private final Path researchOutputDir;

    private volatile com.emergentterrain.llm.RunBudgetTracker runBudgetTracker;

    /** Test seam — replaces the router (one prompt → one completion). */
    private Function<String, String> synthesizer;

    @Autowired(required = false)
    public void setRunBudgetTracker(com.emergentterrain.llm.RunBudgetTracker tracker) {
        this.runBudgetTracker = tracker;
    }

    public SynthesisPaperService(ResearchPaperRepository papersRepository,
            ResearchMemoryRepository memoryRepository, LlmRouter router,
            @Value("${app.research.synthesis.enabled:false}") boolean enabled,
            @Value("${app.research.synthesis.minSources:5}") int minSources,
            @Value("${app.research.synthesis.minSections:3}") int minSections,
            @Value("${app.research.synthesis.maxSections:6}") int maxSections,
            @Value("${app.research.synthesis.reSynthesisMinNewSources:0}") int reSynthesisMinNewSources,
            @Value("${app.university.researchOutputDir:research}") String researchOutputDir) {
        this.papersRepository = papersRepository;
        this.memoryRepository = memoryRepository;
        this.router = router;
        this.enabled = enabled;
        this.minSources = minSources;
        this.minSections = minSections;
        this.maxSections = maxSections;
        this.reSynthesisMinNewSources = reSynthesisMinNewSources;
        this.researchOutputDir = Path.of(researchOutputDir);
        this.synthesizer = prompt -> com.emergentterrain.llm.LlmCallContext.withRole(
                "synthesis", () -> this.router.callForRole("synthesis", prompt));
    }

    void setSynthesizer(Function<String, String> synthesizer) {
        this.synthesizer = synthesizer;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The composed outcome (null-synthesis carries no body). */
    public record SynthesisResult(String family, String title, String bodyMarkdown,
                                  EvidenceLabel label, List<String> citedIds,
                                  List<String> uncitedViolations, boolean isNull,
                                  String nullReason) {
        public boolean publishable() {
            return !isNull && bodyMarkdown != null && !bodyMarkdown.isBlank();
        }
    }

    /**
     * First pass 4 min after startup, then every 6h. 2026-06-08: the original
     * 20-min initialDelay collided with the ~22-min budget-limited session
     * length — synthesis fired in a razor-thin window at drain and risked the
     * drain-skip. 4 min composes well before drain, off the hot path (capture/
     * consolidation/recall have settled by then).
     */
    @Scheduled(initialDelay = 240_000, fixedDelay = 21_600_000)
    public void scheduledPass() {
        if (!enabled) return;
        com.emergentterrain.llm.RunBudgetTracker t = runBudgetTracker;
        if (t != null && !t.allowDiscretionarySpend()) return;   // drain-aware
        try {
            runOnePass();
        } catch (Exception e) {
            log.warn("synthesis pass failed (no paper produced): {}", e.getMessage());
        }
    }

    /**
     * Pick a warranted family across the corpus and compose+persist one
     * synthesis. Returns the result, or empty when nothing is warranted /
     * the outline declined.
     */
    public Optional<SynthesisResult> runOnePass() {
        List<SourcePaper> citeable = citeableCorpus();
        List<SynthesisWarrant.Warrant> warrants =
                SynthesisWarrant.warranted(citeable, minSources);
        // C2 warrant hint (2026-07-06) — the fit-ready family goes first. max_prime_gap lost
        // two consecutive warrant races (to max_divisor and cramer) on raw source count while
        // holding five ledgered binding pairs; source count measures prose volume, but the
        // synthesis the mandate exists for needs QUANTITATIVE inventory. Stable re-sort by
        // ledger row count (ties keep the source-count order), only when C2 is on.
        if (ledgerSynthesisEnabled && verifiedResultRepo != null) {
            warrants = reorderByLedger(warrants, family -> {
                try {
                    return verifiedResultRepo.findTop20ByFamilyOrderByCreatedAtDesc(
                            com.emergentterrain.research.question.OpenQuestionHarvestService
                                    .familyOf(family)).size();
                } catch (RuntimeException e) {
                    return 0;
                }
            });
        }
        for (SynthesisWarrant.Warrant w : warrants) {
            if (alreadySynthesized(w.family())) continue;
            String lesson = lessonFor(w.family());
            SynthesisResult r = compose("MATHEMATICS", w, lesson);
            if (r.isNull()) {
                log.info("synthesis: family={} NULL_SYNTHESIS ({})", w.family(), r.nullReason());
                continue;   // try the next warranted family
            }
            persist(r, w);
            // C2 — the synthesis's own open questions enter the T1 ledger like any paper's.
            if (ledgerSynthesisEnabled && questionHarvest != null) {
                questionHarvest.harvest(
                        "SYN-" + com.emergentterrain.research.question.OpenQuestionHarvestService
                                .familyOf(w.family()),
                        w.family(), r.bodyMarkdown(), "");
            }
            log.info("synthesis PUBLISHED family={} sections={} cited={} label={} violations={}",
                    r.family(), r.bodyMarkdown().split("\n## ").length - 1,
                    r.citedIds().size(), r.label(), r.uncitedViolations().size());
            return Optional.of(r);
        }
        return Optional.empty();
    }

    /**
     * 2026-07-07 (SYN-30ace9e8) — the section model echoes its own heading as the first line
     * ("## title\n\n## title\nprose"), and the composer prepends the heading again: every
     * section published with a doubled heading. Strip a leading heading line that matches the
     * title (case-insensitive, punctuation-collapsed). Static for tests.
     */
    static String stripEchoedHeading(String section, String title) {
        if (section == null || title == null) {
            return section;
        }
        String t = section.stripLeading();
        if (!t.startsWith("#")) {
            return section;
        }
        int nl = t.indexOf('\n');
        String firstLine = (nl < 0 ? t : t.substring(0, nl)).replaceFirst("^#+\\s*", "");
        String norm = firstLine.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
        String normTitle = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
        return norm.equals(normTitle) ? (nl < 0 ? "" : t.substring(nl + 1).stripLeading()) : section;
    }

    /** C2 warrant hint — stable descending re-sort by ledger inventory. Static for tests. */
    static List<SynthesisWarrant.Warrant> reorderByLedger(
            List<SynthesisWarrant.Warrant> warrants,
            java.util.function.ToIntFunction<String> ledgerRows) {
        List<SynthesisWarrant.Warrant> sorted = new ArrayList<>(warrants);
        sorted.sort(java.util.Comparator.comparingInt(
                (SynthesisWarrant.Warrant w) -> ledgerRows.applyAsInt(w.family())).reversed());
        return sorted;
    }

    /** C2 — the family's ledger block for the composer prompts; "" when flag/repo absent. */
    private String ledgerBlockFor(String family) {
        if (!ledgerSynthesisEnabled || verifiedResultRepo == null) {
            return "";
        }
        try {
            return SynthesisComposer.renderLedgerBlock(
                    verifiedResultRepo.findTop20ByFamilyOrderByCreatedAtDesc(
                            com.emergentterrain.research.question.OpenQuestionHarvestService
                                    .familyOf(family)));
        } catch (RuntimeException e) {
            log.warn("synthesis ledger block failed family={} (prose-only synthesis): {}",
                    family, e.getMessage());
            return "";
        }
    }

    // ── compose (router-free testable) ────────────────────────────────────────

    public SynthesisResult compose(String domain, SynthesisWarrant.Warrant w, String lesson) {
        List<SourcePaper> sources = w.sources();
        String ledgerBlock = ledgerBlockFor(w.family());
        String outline = synthesizer.apply(SynthesisComposer.buildOutlinePrompt(
                domain, w.family(), lesson, sources, minSections, maxSections, ledgerBlock));
        if (SynthesisComposer.isNullSynthesis(outline)) {
            return new SynthesisResult(w.family(), null, null, null, List.of(), List.of(),
                    true, outline.strip());
        }
        log.info("synthesis outline (family {}): {}", w.family(),
                outline == null ? "null"
                        : outline.strip().substring(0, Math.min(outline.strip().length(), 240)));
        List<String> titles = parseTitles(outline);
        if (titles.isEmpty()) {
            return new SynthesisResult(w.family(), null, null, null, List.of(), List.of(),
                    true, "outline produced no parseable section titles");
        }
        StringBuilder body = new StringBuilder();
        StringBuilder digest = new StringBuilder();
        int leaked = 0;
        int sectionNo = 0;
        for (String rawTitle : titles) {
            sectionNo++;
            // Render guard (synthesis #2): an outline that parsed to a
            // letter-less title (e.g. ",") must not render as "## ,". Fall
            // back to a numbered heading; the diagnostic outline log above
            // captures the raw format for the parser fix.
            String title = rawTitle != null && rawTitle.matches(".*[A-Za-z].*")
                    ? rawTitle : "Section " + sectionNo;
            String section = synthesizer.apply(SynthesisComposer.buildSectionPrompt(
                    title, w.family(), digest.toString(), sources, ledgerBlock));
            if (section == null || section.isBlank()) continue;
            // Defensive (synthesis #1, P-b72c max_divisor): a section that
            // leaks the model's planning instead of prose is dropped, never
            // published. The OUTPUT FORMAT rule in the prompt is the primary
            // guard; this is the backstop.
            section = stripEchoedHeading(section, title);
            if (SynthesisComposer.looksLikeLeakedReasoning(section)) {
                leaked++;
                log.warn("synthesis: section '{}' leaked reasoning — dropped (family {})",
                        title, w.family());
                continue;
            }
            body.append("\n## ").append(title).append("\n\n").append(section.strip()).append('\n');
            // Digest = section title + first sentence, for the next section's
            // anti-repetition context (kept short to stay in budget).
            digest.append("- ").append(title).append(": ")
                    .append(firstSentence(section)).append('\n');
        }
        // If most sections leaked, the compose is unreliable — null it rather
        // than publish a one-section stub.
        if (body.length() == 0 || leaked > titles.size() / 2) {
            return new SynthesisResult(w.family(), null, null, null, List.of(), List.of(),
                    true, body.length() == 0 ? "no usable sections drafted"
                            : "majority of sections leaked reasoning (" + leaked + "/"
                                    + titles.size() + ")");
        }
        Set<String> sourceIds = sources.stream().map(SourcePaper::paperId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> cited = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        Matcher m = PAPER_CITE.matcher(body);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String id = m.group(1);
            if (!seen.add(id)) continue;
            if (sourceIds.contains(id)) cited.add(id);
            else violations.add(id);          // cited a paper not in the warrant corpus
        }
        EvidenceLabel label = SynthesisLabelCap.applyCap(EvidenceLabel.EMPIRICAL_RESULT,
                sources.stream().map(s -> parseLabel(s.evidenceLabel())).toList());
        String title = "Synthesis: " + w.family() + " across " + sources.size() + " papers";
        return new SynthesisResult(w.family(), title, body.toString(), label,
                List.copyOf(cited), List.copyOf(violations), false, null);
    }

    // ── corpus / lesson / dedup ───────────────────────────────────────────────

    private List<SourcePaper> citeableCorpus() {
        List<SourcePaper> out = new ArrayList<>();
        for (ResearchPaper p : papersRepository.findAll()) {
            if (p == null || p.kind() == PaperKind.SYNTHESIS) continue;
            if (!"PUBLISHED".equalsIgnoreCase(p.status())) continue;   // cite-worthy only
            out.add(new SourcePaper(p.paperId(), p.topic(),
                    p.evidenceLabel() == null ? "CONJECTURE" : p.evidenceLabel().name(),
                    p.peerScore(), oneLine(p.conclusion())));
        }
        return out;
    }

    private String lessonFor(String family) {
        return memoryRepository.findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtDesc(
                        ResearchMemoryEntity.TYPE_LESSON, family).stream()
                .findFirst().map(ResearchMemoryEntity::content).orElse(null);
    }

    /**
     * Whether {@code family} should be SKIPPED this pass. Never synthesized → compose (false). Once
     * synthesized: with {@link #reSynthesisMinNewSources} ≤ 0 it is a permanent skip (legacy
     * one-per-family-ever). With it &gt; 0, the family is re-synthesized — returns false — once it has
     * gained at least that many NEW cite-able (PUBLISHED, non-synthesis) sources SINCE its most-recent
     * synthesis, so a stale review refreshes only when the corpus has genuinely grown.
     */
    private boolean alreadySynthesized(String family) {
        Instant lastSynth = null;
        for (ResearchPaper p : papersRepository.findAll()) {
            if (p != null && p.kind() == PaperKind.SYNTHESIS
                    && ("synthesis/" + family).equalsIgnoreCase(p.topic())) {
                Instant t = p.publishedAt();
                if (t != null && (lastSynth == null || t.isAfter(lastSynth))) lastSynth = t;
            }
        }
        long newSources = 0;
        if (reSynthesisMinNewSources > 0) {
            final Instant since = lastSynth;
            newSources = papersRepository.findAll().stream()
                    .filter(p -> p != null && p.kind() != PaperKind.SYNTHESIS)
                    .filter(p -> "PUBLISHED".equalsIgnoreCase(p.status()))
                    .filter(p -> family.equalsIgnoreCase(p.topic()))
                    .filter(p -> p.publishedAt() != null && (since == null || p.publishedAt().isAfter(since)))
                    .count();
        }
        return shouldSkipFamily(/*everSynthesized*/ true, newSources, reSynthesisMinNewSources);
    }

    /** Pure skip/compose decision (package-private for unit tests). Never synthesized → compose (false);
     *  synthesized with {@code minNewSources ≤ 0} → permanent skip (true); else skip until the family has
     *  gained {@code minNewSources} new sources since its last synthesis. */
    static boolean shouldSkipFamily(boolean everSynthesized, long newSourcesSinceLast, int minNewSources) {
        if (!everSynthesized) return false;          // compose
        if (minNewSources <= 0) return true;         // legacy permanent dedup
        return newSourcesSinceLast < minNewSources;  // not enough new evidence yet → skip
    }

    // ── persist ───────────────────────────────────────────────────────────────

    private void persist(SynthesisResult r, SynthesisWarrant.Warrant w) {
        // A citation violation routes to NEEDS_REVISION (the synthesis cited a
        // paper outside its corpus); otherwise PUBLISHED at the capped label.
        String status = r.uncitedViolations().isEmpty() ? "PUBLISHED" : "NEEDS_REVISION";
        UUID roomId = anyRoomId();
        String paperId = "SYN-" + w.family().replaceAll("[^A-Za-z0-9]+", "_")
                + "-" + Integer.toHexString(r.bodyMarkdown().hashCode());
        ResearchPaper paper = new ResearchPaper(paperId, roomId, "synthesizer",
                "MATHEMATICS", "synthesis/" + w.family(), r.title(),
                "", "SYNTHESIS", r.bodyMarkdown(),
                lastSection(r.bodyMarkdown()), List.of(), 0.0, status,
                0L, Instant.now(), 0, 0.0, "synthesis", "synthesis:31.7");
        paper.setKind(PaperKind.SYNTHESIS);
        paper.setEvidenceLabel(r.label());
        try {
            papersRepository.save(paper);
        } catch (Exception e) {
            log.warn("synthesis persist (DB) failed family={}: {}", w.family(), e.getMessage());
        }
        writeMarkdown(roomId, paper, r);
    }

    private void writeMarkdown(UUID roomId, ResearchPaper paper, SynthesisResult r) {
        try {
            Path dir = researchOutputDir.resolve(roomId.toString().substring(0, 8));
            Files.createDirectories(dir);
            String md = "# " + paper.title() + "\n\n**Kind**: SYNTHESIS  |  **Evidence Label**: "
                    + r.label() + "  |  **Status**: " + paper.status() + "  |  **Sources**: "
                    + r.citedIds().size() + "\n\n---\n" + r.bodyMarkdown();
            Files.writeString(dir.resolve(paper.paperId() + ".synthesis.md"), md);
        } catch (Exception e) {
            log.warn("synthesis markdown write failed {}: {}", paper.paperId(), e.getMessage());
        }
    }

    // ── small helpers ─────────────────────────────────────────────────────────

    static List<String> parseTitles(String outline) {
        List<String> titles = new ArrayList<>();
        if (outline == null) return titles;
        Matcher m = TITLE_JSON.matcher(outline);
        // 2026-07-06 (SYN-max_divisor_count-59962f10) — the outline model emitted every title
        // twice in case variants ("The Wigert anchor: …" / "The Wigert Anchor: …"), and each
        // rendered as its own section: a 5-section synthesis published with 10. Dedupe on the
        // case-folded, punctuation-collapsed form; first occurrence wins.
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (m.find()) {
            String t = m.group(1).strip();
            String key = t.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
            if (seen.add(key)) {
                titles.add(t);
            }
        }
        return titles;
    }

    private static String firstSentence(String s) {
        if (s == null) return "";
        String t = s.strip();
        int dot = t.indexOf(". ");
        String first = dot > 0 ? t.substring(0, dot) : t;
        return first.length() <= 120 ? first : first.substring(0, 120);
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= 160 ? t : t.substring(0, 160);
    }

    private static String lastSection(String body) {
        int i = body.lastIndexOf("\n## ");
        return i < 0 ? "" : body.substring(i).replaceFirst("\n## [^\n]*\n+", "");
    }

    private static EvidenceLabel parseLabel(String name) {
        try {
            return name == null ? EvidenceLabel.CONJECTURE : EvidenceLabel.valueOf(name);
        } catch (IllegalArgumentException e) {
            return EvidenceLabel.CONJECTURE;
        }
    }

    private UUID anyRoomId() {
        for (ResearchPaper p : papersRepository.findAll()) {
            if (p != null && p.roomId() != null) return p.roomId();
        }
        return new UUID(0L, 0L);
    }
}
