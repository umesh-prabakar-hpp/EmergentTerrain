package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Per-room university simulation with full bot lifecycle management.
 *
 * Each RoomBot carries a BotLifecycleState (in lifecycleJson) encoding its
 * genome, age, research qualification, and social state. This service:
 *   - Ages all bots and transitions JUVENILE→ADULT→ELDER each tick
 *   - Runs competency tests every TERM_PAPERS publications to decide who researches
 *   - Runs one research cycle per interval (author + peer reviewers from QUALIFIED bots)
 *   - Processes elder merges (higher-fitness bot absorbs lower, retires it)
 *   - Facilitates dating between ADULT bots to spawn child bots
 *   - Queues high-score papers for LLM genome mentoring (via callback)
 */
@Service
@ConditionalOnProperty(name = "app.university.enabled", havingValue = "true", matchIfMissing = false)
public class UniversityService {

    private static final Logger log = LoggerFactory.getLogger(UniversityService.class);

    // Research cycle constants
    private static final int TERM_PAPERS            = 30;    // publications before next competency test
    private static final int MAX_PAPERS_PER_ROOM    = 500;
    private static final double MENTOR_SCORE_THRESHOLD = 0.30;

    // Max pending drafts: if backlog reaches this, skip the research cycle this tick.
    // Keeps paper output naturally paced to LLM throughput with no build-up.
    private final int     maxDraftBacklog;
    private final boolean peerReviewEnabled;
    private final boolean truncationGuardEnabled;
    private final boolean harnessLeakFilterEnabled;
    private final boolean numericalConsistencyEnabled;
    /**
     * Phase 12 D2 — {@code warn} (default; publish as REJECTED on disk),
     * {@code downgrade} (publish as NULL_RESULT with an honest preamble naming
     * the unverifiable values), or {@code suppress} (drop the paper entirely
     * before {@code addPaper} fires; callPath gets the {@code numeric_inconsistent→suppressed}
     * stamp via the {@link ResearchNarrationService}). Ignored when
     * {@link #numericalConsistencyEnabled} is false.
     */
    private final String  numericalConsistencyMode;
    private final boolean magnitudeRangeEnabled;
    private final boolean dedupEnabled;
    private final boolean crossDomainGateEnabled;
    private final boolean disputeReconciliationEnabled;
    /** Phase 7 G10 — age (hours) at which a DISPUTED paper counts as orphaned. */
    private final long    orphanAgeHours;
    /**
     * Phase 21 §21.2 — once {@code scanOrphanedDisputes} has re-fired
     * reconciliation this many times on the same paper without the dispute
     * resolving, demote the paper's status to {@code DISPUTE_ABANDONED}
     * and stop re-firing. Default 6 (≈ 6 sweep cycles at the default
     * 5-minute interval = 30 minutes; or ≈ 6 h at a 1-hour interval).
     * Caps the 17-unique-ID re-warn loop documented in REVIEW_REPORT
     * a3289005 §21.2.
     */
    private final int     disputeMaxAttempts;
    private final boolean citationAwareScoringEnabled;
    /** Phase 7 G8 — when on, SUPPORTED papers carrying the audit-4b physical_mechanism gap publish as REJECTED. */
    private final boolean auditPhysicalMechanismRejectsEnabled;
    /** Phase 7 G6 — when on, RLC/Thermal-Stress/Arrhenius findings prose hitting known mis-label patterns publish as NEEDS_REVISION. */
    private final boolean topicSpecificFindingsValidatorEnabled;
    /**
     * Phase 12 D6 — when on, conclusions that use the "no prior theoretical
     * bound" boilerplate on a {@link TextbookBoundRegistry} topic publish as
     * NEEDS_REVISION. Default off; opt-in flip once the registry's coverage
     * has been measured against a refresh.
     */
    private final boolean closingProseValidatorEnabled;
    /**
     * Phase 13 E6 — when on, papers whose Findings cite unit-anchored
     * numerics without any uncertainty indicator (± value, "exact closed
     * form", "to within X%") publish as NEEDS_REVISION. Addresses the
     * most-repeated panel critique in REVIEW_REPORT refresh #1
     * (~38 mentions across ~18 of 23 papers). Default off; opt-in flip
     * after one refresh confirms the template change took effect.
     */
    private final boolean uncertaintyDisclosureEnabled;
    /**
     * Phase 14 F5 — when on, papers reaching publishPaper with a null or
     * empty tool-call trace (i.e. no Methods section content available)
     * publish as NEEDS_REVISION. Catches the refresh-#2 P-503 signature
     * where the paper renders Hypothesis → Findings → Conclusion →
     * Discussion → Related Work with no Methods or Panel section. Default
     * off; opt-in flip once one refresh confirms the gate doesn't catch
     * legitimate non-tool-call paths (Ollama-fallback, JSON-only).
     */
    private final boolean missingMethodsGateEnabled;
    /**
     * 2026-05-21 — when the gate fires, controls the disposition.
     * {@code warn} logs only without changing status. {@code downgrade}
     * (default, legacy behaviour) routes the paper to NEEDS_REVISION via
     * the status-precedence block, so the empty-trace artefact still lands
     * on disk. {@code suppress} drops the paper entirely (no addPaper, no
     * disk write, no DB row), same shape as
     * {@code numerical-consistency.mode=suppress}. The 2026-05-21 review
     * batch had three empty-trace papers (P-865/880/890) that the reader
     * gains nothing from — suppress mode is the natural follow-up.
     */
    private final String missingMethodsGateMode;
    /**
     * Phase 14 F3 — when on, papers whose Findings prose claims a sample
     * count (e.g. "sixteen sampled cases") that exceeds {@code 2 ×
     * trace.size()} publish as NEEDS_REVISION. Addresses the refresh-#2
     * P-507 / P-512 / P-494 "inflated sweep size" failure pattern.
     * Default off; opt-in flip after one refresh confirms the slack
     * factor doesn't catch legitimate within-call sweep output.
     */
    private final boolean singlePointSweepEnabled;
    /**
     * Phase 14 F4 — when on, papers whose Conclusion makes a directional
     * claim (confirmed/refuted/within/outside) contradicting the Findings
     * number vs Hypothesis bound publish as NEEDS_REVISION. Addresses
     * refresh-#2 P-491 / P-496 / P-511 inversions. Conservative: returns
     * empty on any ambiguity (no bound, no actual, no polarity word).
     * Default off; opt-in flip after one refresh confirms the regex set
     * doesn't catch legitimate prose patterns.
     */
    private final boolean directionOfFindingEnabled;
    /**
     * Phase 14 F1a — when on, the structured-findings markdown renderer
     * annotates each {@code ### Computation N — <topic>} header with the
     * {@code [call_N]} marker it draws from, and replaces blocks beyond
     * the successful-call count with an explicit
     * {@code (no data — call_N missing)} placeholder. Observable on every
     * paper rendering but does NOT change paper status. Default off.
     */
    private final boolean blockCountStampEnabled;
    /**
     * Phase 14 F1b — when on, papers whose structured-findings block count
     * exceeds the successful-call count (size of toolCallTrace) publish
     * as NEEDS_REVISION. The Five-Computation stub block fabrication
     * cluster from REVIEW_REPORT refresh #2 §1 rec #1 (6 of 20 papers) is
     * the target. Default off; ship behind F1a's stamp first so operators
     * can observe the would-be gate rate before enforcement.
     */
    private final boolean blockCountGuardEnabled;
    /**
     * Phase 7 F7.3 — when on, SUPPORTED papers whose panel review surfaced
     * cherry_picked_evidence (≥2 reviewers flagged the same omitted-relevant
     * tool call) publish as REJECTED. Defaults off until the false-positive
     * rate is measured over ≥30 papers.
     */
    private final boolean auditCherryPickedRejectsEnabled;
    /**
     * Phase 18 §18.2 (P8d) — when on, runs
     * {@link UnsourcedClaimValidator} against Findings + Conclusion. The
     * paired {@code unsourcedClaimMode} field controls whether failures
     * log only ({@code warn}) or route the paper to NEEDS_REVISION with a
     * {@code publish:unsourced_claim:<type>→needs_revision} stamp
     * ({@code downgrade}).
     */
    private final boolean unsourcedClaimEnabled;
    private final String  unsourcedClaimMode;
    /**
     * Paper-review 2026-05-22 fix 4 — flag papers whose Hypothesis text
     * declares a parameter range (e.g. "from 400nm to 700nm") but
     * whose persisted tool-call trace covers less than half of it.
     * Ships in warn mode: stamps {@code range_coverage_under}, logs
     * a WARN, does NOT route the paper to NEEDS_REVISION. Worked
     * examples: P-a328-900 (single-λ test of a 400-700 nm sweep).
     */
    private final boolean hypothesisRangeCoverageEnabled;
    /**
     * 2026-05-21 review §1 rec #4 follow-up — flags hypothesis prose that
     * declares a baseline value not matching any run_predicate_scan call
     * in the trace. Mode escalation matches the unsourced-claim pattern:
     * {@code warn} logs only, {@code downgrade} routes to NEEDS_REVISION
     * with a {@code publish:prose_baseline_drift→needs_revision} stamp.
     * Default enabled+warn so the first refresh exposes the false-positive
     * rate before any paper status changes.
     */
    private final boolean proseBaselineDriftEnabled;
    private final String  proseBaselineDriftMode;
    /**
     * 2026-05-21 review §1 rec #5 — flags textual "Lastname (Year)"
     * citations in published paper prose that don't appear in
     * literature-corpus.yml. Targets the fabrication cluster (P-a328-862
     * cited "P-2022-07", P-a328-872 "Torquato and Stillinger", P-a328-890
     * "Hecht Optics 5th ed."). Mode escalation mirrors
     * {@link #unsourcedClaimMode}: {@code warn} logs only,
     * {@code downgrade} routes to NEEDS_REVISION with
     * {@code publish:citation_corpus→needs_revision}. Default enabled+warn
     * so the first refresh exposes the false-positive rate before any
     * status changes (textbook references without years are a known v1
     * blind spot that we want to observe before promoting).
     */
    private final boolean citationCorpusEnabled;
    private final String  citationCorpusMode;
    /**
     * 2026-05-21 review §1 rec #6 — flags published prose that mentions a
     * registered topic name OTHER than the paper's declared topic.
     * P-a328-886 (Abundant Density paper) carried the phrase "the specific
     * focus on the Pendulum Breakdown Angle is new" in Related Work — a
     * template-bleed from a different paper's context. Mode escalation
     * mirrors {@link #unsourcedClaimMode}; default disabled+warn so the
     * first refresh exposes the FP rate (legitimate cross-topic mentions
     * in Discussion are possible).
     */
    private final boolean topicConsistencyEnabled;
    private final String  topicConsistencyMode;
    /**
     * 2026-05-21 review §1 rec #7 — when a paper carries a scaling /
     * power-law / linearity claim, the trace needs at least 3 non-sweep
     * sample points to make the claim structurally testable. The
     * SinglePointSweepValidator (F3) catches narrator overclaims
     * (Findings says "n=40" with 1 call); this validator catches honest
     * underclaims (Findings says "σ scales as d^(-1/2)" with 2 calls).
     * P-a328-884 (Hall-Petch) is the canonical signature. Mode escalation
     * mirrors {@link #unsourcedClaimMode}; default disabled+warn so the
     * first refresh exposes the FP rate.
     */
    private final boolean scalingSweepDepthEnabled;
    private final String  scalingSweepDepthMode;

    /**
     * Phase 24 §24.4 — flag papers whose prose claims a symbolic-
     * regression result (PySR / Pareto / recovered closed-form) with
     * no matching symbolic_regress tool call in the audit trail. Off
     * by default per the dormant-flag pattern; flip
     * {@code app.research.sr-evidence.enabled=true} once §24.5's
     * recovery probe has shipped and at least one in-corpus paper
     * has produced a legitimate PySR-cited result the validator can
     * be tuned against.
     */
    private final boolean srEvidenceEnabled;
    private final String  srEvidenceMode;

    // Per-room in-memory counters. Paper counters are lazily seeded from existing
    // on-disk research files so restarts keep IDs unique.
    private final ConcurrentHashMap<UUID, List<ResearchPaper>> published     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long>                lastTickAt    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long>                paperCounter  = new ConcurrentHashMap<>();
    private static final Pattern CITED_PAPER_PATTERN = Pattern.compile("P-[0-9a-fA-F]{4}-\\d+");

    // (Replication threshold + queue moved to ReplicationService in Phase 0 Task 0.3b.)
    private static final double BRIDGE_SCORE_THRESHOLD = 0.38;

    private static final DateTimeFormatter FILE_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());
    private static final Pattern PAPER_SUFFIX_PATTERN =
            Pattern.compile("P-[0-9a-fA-F]{4}-(\\d+)");

    private final long          researchIntervalMs;
    private final Path          researchOutputDir;
    private final RoomBotRepository botRepository;
    private final ResearchPaperRepository papersRepository;
    private final PaperCitationRepository citationsRepository;
    private final PopulationEcologyService populationEcologyService;
    private final ReplicationService replicationService;
    private final BotLifecycleService botLifecycleService;
    private final ResearchCycleService researchCycleService;
    private final ResearchProgramService researchProgramService;
    private final ExpectationModelService expectationModelService;
    private final ExperimentPlannerService experimentPlannerService;
    private final InstitutionService institutionService;
    private final EpistemicMarketService epistemicMarketService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final FundingAllocationService fundingAllocationService;
    private final SurpriseBroadcastService surpriseBroadcastService;
    private final ExplorationTuningService explorationTuningService;
    private final ObjectMapper  objectMapper;

    /**
     * 2026-05-29 review §1 rec #4 — when on, {@code [call_N]} citation markers whose
     * index is outside {@code [1, trace.size()]} are stripped from the published
     * prose before persistence (the "hard cap at trace.size()"). The narrator
     * invents indices beyond the trace (P-a328-993 cited call_18/19 when ~5 calls
     * ran); such markers reference calls that cannot exist, so removing them leaves
     * the number to be assessed on its own merits by the citation/numeric gates.
     * Field-level {@code @Value} so existing constructor call sites (and the
     * publish tests) are unchanged; default OFF, flip after a refresh confirms.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.citation-trace-cap.enabled:false}")
    private boolean citationTraceCapEnabled;

    /** rec #4 — {@code [call_N]} marker with optional leading whitespace; group 2 is the index. */
    private static final java.util.regex.Pattern CALL_MARKER_INDEXED =
            java.util.regex.Pattern.compile("(\\s?)\\[call_(\\d+)\\]");

    /** Adapter that lets the cycle call back into this orchestrator. */
    private final ResearchCycleService.Context cycleContext = new ResearchCycleService.Context() {
        @Override public String nextPaperId(UUID roomId) { return UniversityService.this.nextPaperId(roomId); }
        @Override public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) {
            return UniversityService.this.getPublishedPapers(roomId, limit);
        }
        @Override public void publishPaper(PaperDraft draft, NarrationResult narration) {
            UniversityService.this.publishPaper(draft, narration);
        }
        @Override public java.util.function.Consumer<PaperDraft> narratingCallback() {
            return narratingCallback;
        }
        @Override public java.util.function.Function<Object[], Object> formulationCallback() {
            return formulationCallback;
        }
    };

    /** Tracks rooms whose in-memory cache has been seeded from the DB. */
    private final java.util.Set<UUID> hydratedRooms = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Phase 6 D1.5 (L2) — optional. Injected via setter so the existing 30-arg
     * constructor stays untouched. When non-null, the magnitude validator gains
     * a corpus-fallback path: topics whose registry stub has no bound can ship
     * one in {@code literature-corpus.yml} via {@code magnitude_max} +
     * {@code magnitude_unit} + {@code magnitude_regex}.
     */
    private LiteratureSearchService literatureSearch;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLiteratureSearch(LiteratureSearchService literatureSearch) {
        this.literatureSearch = literatureSearch;
    }

    // Optional callbacks set by LlmService — avoids circular Spring bean dependency
    private volatile java.util.function.BiConsumer<RoomBot, List<ResearchPaper>> mentoringCallback;
    private volatile java.util.function.Consumer<PaperDraft>                     narratingCallback;
    // Formulation: (domain, topicName, genome, recentPapers) -> FormulationResult (nullable)
    @SuppressWarnings("unchecked")
    private volatile java.util.function.Function<Object[], Object>               formulationCallback;

    public UniversityService(
            @Value("${app.university.researchIntervalMs:15000}") long researchIntervalMs,
            @Value("${app.university.researchOutputDir:research}") String researchOutputDir,
            @Value("${app.university.maxDraftBacklog:3}") int maxDraftBacklog,
            @Value("${app.university.peerReviewEnabled:true}") boolean peerReviewEnabled,
            RoomBotRepository botRepository,
            ResearchPaperRepository papersRepository,
            PaperCitationRepository citationsRepository,
            PopulationEcologyService populationEcologyService,
            ReplicationService replicationService,
            BotLifecycleService botLifecycleService,
            ResearchCycleService researchCycleService,
            ResearchProgramService researchProgramService,
            ExpectationModelService expectationModelService,
            ExperimentPlannerService experimentPlannerService,
            InstitutionService institutionService,
            EpistemicMarketService epistemicMarketService,
            KnowledgeBaseService knowledgeBaseService,
            FundingAllocationService fundingAllocationService,
            SurpriseBroadcastService surpriseBroadcastService,
            ExplorationTuningService explorationTuningService,
            @Value("${app.research.reviewer-routing.enabled:false}") boolean reviewerRoutingEnabled,
            @Value("${app.research.journal-review.enabled:false}") boolean journalReviewEnabled,
            @Value("${app.research.truncation-guard.enabled:false}") boolean truncationGuardEnabled,
            @Value("${app.research.harness-leak-filter.enabled:false}") boolean harnessLeakFilterEnabled,
            @Value("${app.research.numerical-consistency.enabled:false}") boolean numericalConsistencyEnabled,
            @Value("${app.research.numerical-consistency.mode:warn}") String numericalConsistencyMode,
            @Value("${app.research.magnitude-range.enabled:false}") boolean magnitudeRangeEnabled,
            @Value("${app.research.dedup.enabled:false}") boolean dedupEnabled,
            @Value("${app.research.cross-domain-gate.enabled:false}") boolean crossDomainGateEnabled,
            @Value("${app.research.dispute-reconciliation.enabled:false}") boolean disputeReconciliationEnabled,
            @Value("${app.research.disputeOrphanAgeHours:6}") long orphanAgeHours,
            @Value("${app.research.dispute-max-attempts:6}") int disputeMaxAttempts,
            @Value("${app.research.citation-aware-scoring.enabled:false}") boolean citationAwareScoringEnabled,
            @Value("${app.research.audit-physical-mechanism-rejects.enabled:true}") boolean auditPhysicalMechanismRejectsEnabled,
            @Value("${app.research.audit-cherry-picked-rejects.enabled:false}") boolean auditCherryPickedRejectsEnabled,
            @Value("${app.research.topic-specific-findings-validator.enabled:true}") boolean topicSpecificFindingsValidatorEnabled,
            @Value("${app.research.closing-prose-validator.enabled:false}") boolean closingProseValidatorEnabled,
            @Value("${app.research.uncertainty-disclosure.enabled:false}") boolean uncertaintyDisclosureEnabled,
            @Value("${app.research.missing-methods-gate.enabled:false}") boolean missingMethodsGateEnabled,
            @Value("${app.research.missing-methods-gate.mode:downgrade}") String missingMethodsGateMode,
            @Value("${app.research.single-point-sweep.enabled:false}") boolean singlePointSweepEnabled,
            @Value("${app.research.direction-of-finding.enabled:false}") boolean directionOfFindingEnabled,
            @Value("${app.research.block-count-stamp.enabled:false}") boolean blockCountStampEnabled,
            @Value("${app.research.block-count-guard.enabled:false}") boolean blockCountGuardEnabled,
            @Value("${app.research.topics-v2.enabled:false}") boolean topicsV2Enabled,
            @Value("${app.research.topic-clone-backoff.enabled:false}") boolean topicCloneBackoffEnabled,
            @Value("${app.research.topic-clone-backoff.window-size:5}") int topicCloneBackoffWindow,
            @Value("${app.research.topic-clone-backoff.max-retries:3}") int topicCloneBackoffMaxRetries,
            @Value("${app.research.unsourced-claim.enabled:false}") boolean unsourcedClaimEnabled,
            @Value("${app.research.unsourced-claim.mode:warn}") String unsourcedClaimMode,
            @Value("${app.research.prose-baseline-drift.enabled:false}") boolean proseBaselineDriftEnabled,
            @Value("${app.research.prose-baseline-drift.mode:warn}") String proseBaselineDriftMode,
            @Value("${app.research.citation-corpus.enabled:false}") boolean citationCorpusEnabled,
            @Value("${app.research.citation-corpus.mode:warn}") String citationCorpusMode,
            @Value("${app.research.topic-consistency.enabled:false}") boolean topicConsistencyEnabled,
            @Value("${app.research.topic-consistency.mode:warn}") String topicConsistencyMode,
            @Value("${app.research.scaling-sweep-depth.enabled:false}") boolean scalingSweepDepthEnabled,
            @Value("${app.research.scaling-sweep-depth.mode:warn}") String scalingSweepDepthMode,
            @Value("${app.research.sr-evidence.enabled:false}") boolean srEvidenceEnabled,
            @Value("${app.research.sr-evidence.mode:warn}") String srEvidenceMode,
            @Value("${app.research.hypothesis-range-coverage.enabled:false}") boolean hypothesisRangeCoverageEnabled,
            ObjectMapper objectMapper) {
        this.researchIntervalMs       = researchIntervalMs;
        this.researchOutputDir        = Paths.get(researchOutputDir);
        this.maxDraftBacklog          = maxDraftBacklog;
        this.peerReviewEnabled        = peerReviewEnabled;
        this.botRepository            = botRepository;
        this.papersRepository         = papersRepository;
        this.citationsRepository      = citationsRepository;
        this.populationEcologyService = populationEcologyService;
        this.replicationService       = replicationService;
        this.botLifecycleService      = botLifecycleService;
        this.researchCycleService     = researchCycleService;
        this.researchProgramService   = researchProgramService;
        this.expectationModelService  = expectationModelService;
        this.experimentPlannerService = experimentPlannerService;
        this.institutionService       = institutionService;
        this.epistemicMarketService   = epistemicMarketService;
        this.knowledgeBaseService     = knowledgeBaseService;
        this.fundingAllocationService = fundingAllocationService;
        this.surpriseBroadcastService = surpriseBroadcastService;
        this.explorationTuningService = explorationTuningService;
        this.truncationGuardEnabled   = truncationGuardEnabled;
        this.harnessLeakFilterEnabled    = harnessLeakFilterEnabled;
        this.numericalConsistencyEnabled = numericalConsistencyEnabled;
        this.numericalConsistencyMode    = normalizeMode(numericalConsistencyMode);
        this.magnitudeRangeEnabled       = magnitudeRangeEnabled;
        this.dedupEnabled                = dedupEnabled;
        this.crossDomainGateEnabled      = crossDomainGateEnabled;
        this.disputeReconciliationEnabled = disputeReconciliationEnabled;
        this.orphanAgeHours = orphanAgeHours;
        this.disputeMaxAttempts = Math.max(1, disputeMaxAttempts);
        this.citationAwareScoringEnabled = citationAwareScoringEnabled;
        this.auditPhysicalMechanismRejectsEnabled = auditPhysicalMechanismRejectsEnabled;
        this.auditCherryPickedRejectsEnabled       = auditCherryPickedRejectsEnabled;
        this.topicSpecificFindingsValidatorEnabled = topicSpecificFindingsValidatorEnabled;
        this.closingProseValidatorEnabled = closingProseValidatorEnabled;
        this.uncertaintyDisclosureEnabled = uncertaintyDisclosureEnabled;
        this.missingMethodsGateEnabled    = missingMethodsGateEnabled;
        this.missingMethodsGateMode       = normalizeMode(missingMethodsGateMode);
        this.singlePointSweepEnabled      = singlePointSweepEnabled;
        this.directionOfFindingEnabled    = directionOfFindingEnabled;
        this.blockCountStampEnabled       = blockCountStampEnabled;
        this.blockCountGuardEnabled       = blockCountGuardEnabled;
        this.unsourcedClaimEnabled        = unsourcedClaimEnabled;
        this.unsourcedClaimMode           = normalizeMode(unsourcedClaimMode);
        this.hypothesisRangeCoverageEnabled = hypothesisRangeCoverageEnabled;
        this.proseBaselineDriftEnabled    = proseBaselineDriftEnabled;
        this.proseBaselineDriftMode       = normalizeMode(proseBaselineDriftMode);
        this.citationCorpusEnabled        = citationCorpusEnabled;
        this.citationCorpusMode           = normalizeMode(citationCorpusMode);
        this.topicConsistencyEnabled      = topicConsistencyEnabled;
        this.topicConsistencyMode         = normalizeMode(topicConsistencyMode);
        this.scalingSweepDepthEnabled     = scalingSweepDepthEnabled;
        this.scalingSweepDepthMode        = normalizeMode(scalingSweepDepthMode);
        this.srEvidenceEnabled            = srEvidenceEnabled;
        this.srEvidenceMode               = normalizeMode(srEvidenceMode);
        this.objectMapper                = objectMapper;
        if (truncationGuardEnabled) {
            log.info("UniversityService: truncation-guard ENABLED — papers whose findings "
                    + "or conclusion end mid-sentence will publish as NEEDS_REVISION.");
        }
        if (harnessLeakFilterEnabled) {
            log.info("UniversityService: harness-leak-filter ENABLED — Hypothesis text "
                    + "is scrubbed of simulator-internal phrases before persistence.");
        }
        if (numericalConsistencyEnabled) {
            log.info("UniversityService: numerical-consistency ENABLED — papers whose "
                    + "Conclusion contradicts its Hypothesis or Findings on a numerical "
                    + "claim publish as REJECTED (mode={}). Phase 12 D2 gap checks (errored "
                    + "call markers, no-trace findings, high-deviation tool results, "
                    + "prose/trace magnitude divergence) are always live.",
                    this.numericalConsistencyMode);
        }
        if (magnitudeRangeEnabled) {
            log.info("UniversityService: magnitude-range ENABLED — papers whose findings "
                    + "contain physically-unreasonable magnitudes for their (domain, topic) "
                    + "publish as NEEDS_REVISION.");
        }
        if (dedupEnabled) {
            log.info("UniversityService: dedup ENABLED — papers whose fingerprint matches "
                    + "a prior room paper they don't cite will publish as NEEDS_REVISION.");
        }
        if (crossDomainGateEnabled) {
            log.info("UniversityService: cross-domain-gate ENABLED — papers whose hypothesis "
                    + "drifts out of its declared domain publish as NEEDS_REVISION.");
        }
        if (disputeReconciliationEnabled) {
            log.info("UniversityService: dispute-reconciliation ENABLED — DISPUTED papers "
                    + "emit a typed ProgramOpenQuestion on both bots' programs.");
        }
        if (citationAwareScoringEnabled) {
            log.info("UniversityService: citation-aware-scoring ENABLED — papers earn a "
                    + "bonus for citing high-quality priors and a penalty for citing "
                    + "critique:skip papers.");
        }
        if (auditPhysicalMechanismRejectsEnabled) {
            log.info("UniversityService: audit-physical-mechanism-rejects ENABLED — "
                    + "SUPPORTED papers whose audit-4b gap list contains physical_mechanism "
                    + "publish as REJECTED.");
        }
        if (topicSpecificFindingsValidatorEnabled) {
            log.info("UniversityService: topic-specific-findings-validator ENABLED — "
                    + "RLC / Thermal-Stress / Arrhenius prose hitting known mis-label "
                    + "patterns publish as NEEDS_REVISION.");
        }
        if (closingProseValidatorEnabled) {
            log.info("UniversityService: closing-prose-validator ENABLED — conclusions "
                    + "that use the 'no prior theoretical bound' boilerplate on a topic "
                    + "with a known textbook bound publish as NEEDS_REVISION (Phase 12 D6).");
        }
        if (uncertaintyDisclosureEnabled) {
            log.info("UniversityService: uncertainty-disclosure ENABLED — papers whose "
                    + "Findings cite unit-anchored numerics without any uncertainty "
                    + "indicator (± / exact / to within) publish as NEEDS_REVISION "
                    + "(Phase 13 E6).");
        }
        if (missingMethodsGateEnabled) {
            log.info("UniversityService: missing-methods-gate ENABLED mode={} — papers reaching "
                    + "publishPaper with a null or empty tool-call trace are {} (Phase 14 F5"
                    + "; 2026-05-21 mode flag added).",
                    missingMethodsGateMode,
                    switch (missingMethodsGateMode) {
                        case "suppress" -> "dropped (no disk write, no DB row)";
                        case "warn"     -> "logged only (no status change)";
                        default          -> "published as NEEDS_REVISION";
                    });
        }
        if (singlePointSweepEnabled) {
            log.info("UniversityService: single-point-sweep ENABLED — papers whose "
                    + "Findings claim a sample count exceeding 2 × trace.size() "
                    + "publish as NEEDS_REVISION (Phase 14 F3).");
        }
        if (directionOfFindingEnabled) {
            log.info("UniversityService: direction-of-finding ENABLED — papers whose "
                    + "Conclusion polarity contradicts Findings vs Hypothesis bound "
                    + "publish as NEEDS_REVISION (Phase 14 F4).");
        }
        if (proseBaselineDriftEnabled) {
            log.info("UniversityService: prose-baseline-drift ENABLED mode={} — papers whose "
                    + "Hypothesis prose cites a baseline value not matching any "
                    + "run_predicate_scan tool-call baseline are {} (2026-05-21 review §1 rec #4).",
                    proseBaselineDriftMode,
                    switch (proseBaselineDriftMode) {
                        case "downgrade" -> "routed to NEEDS_REVISION";
                        case "suppress"  -> "dropped";
                        default           -> "logged only (no status change)";
                    });
        }
        if (citationCorpusEnabled) {
            log.info("UniversityService: citation-corpus ENABLED mode={} — papers whose "
                    + "Findings/Discussion/Related-Work prose carries 'Lastname (Year)' cites "
                    + "absent from literature-corpus.yml are {} (2026-05-21 review §1 rec #5).",
                    citationCorpusMode,
                    switch (citationCorpusMode) {
                        case "downgrade" -> "routed to NEEDS_REVISION";
                        case "suppress"  -> "dropped";
                        default           -> "logged only (no status change)";
                    });
        }
        if (topicConsistencyEnabled) {
            log.info("UniversityService: topic-consistency ENABLED mode={} — papers whose "
                    + "Discussion/Related-Work prose mentions a TopicValidatorRegistry name "
                    + "other than the paper's declared topic are {} (2026-05-21 review §1 rec #6).",
                    topicConsistencyMode,
                    switch (topicConsistencyMode) {
                        case "downgrade" -> "routed to NEEDS_REVISION";
                        case "suppress"  -> "dropped";
                        default           -> "logged only (no status change)";
                    });
        }
        if (scalingSweepDepthEnabled) {
            log.info("UniversityService: scaling-sweep-depth ENABLED mode={} — papers carrying "
                    + "a scaling/linearity/power-law claim with fewer than 3 non-sweep "
                    + "tool calls are {} (2026-05-21 review §1 rec #7).",
                    scalingSweepDepthMode,
                    switch (scalingSweepDepthMode) {
                        case "downgrade" -> "routed to NEEDS_REVISION";
                        case "suppress"  -> "dropped";
                        default           -> "logged only (no status change)";
                    });
        }
        if (blockCountStampEnabled) {
            log.info("UniversityService: block-count-stamp ENABLED — Findings markdown "
                    + "renderer annotates each block header with [call_N] and elides "
                    + "blocks beyond trace.size() with '(no data — call_N missing)' "
                    + "(Phase 14 F1a).");
        }
        if (blockCountGuardEnabled) {
            log.info("UniversityService: block-count-guard ENABLED — papers whose block "
                    + "count exceeds trace.size() publish as NEEDS_REVISION (Phase 14 F1b).");
        }
        this.researchCycleService.setTopicsV2Enabled(topicsV2Enabled);
        if (topicsV2Enabled) {
            log.info("UniversityService: topics-v2 ENABLED — bot topic selection samples "
                    + "uniformly over the FULL topic list per domain (current 5 per main "
                    + "domain + F3 additions).");
        }
        this.researchCycleService.setTopicCloneBackoffEnabled(topicCloneBackoffEnabled);
        this.researchCycleService.setTopicCloneBackoffWindow(topicCloneBackoffWindow);
        this.researchCycleService.setTopicCloneBackoffMaxRetries(topicCloneBackoffMaxRetries);
        if (topicCloneBackoffEnabled) {
            log.info("UniversityService: topic-clone-backoff ENABLED (window={}, "
                    + "maxRetries={}) — bot topic selection probabilistically resamples "
                    + "when (domain, topic) has accumulated REJECTED priors in recent "
                    + "history. Phase 16 P7.",
                    topicCloneBackoffWindow, topicCloneBackoffMaxRetries);
        }
        // Phase 4b — let the planner consult the per-room exploration-trigger
        // threshold from this service. Setter wiring avoids a constructor cycle.
        this.experimentPlannerService.setExplorationTuningService(explorationTuningService);
        // Phase 4b — let the knowledge base emit derived ProgramOpenQuestions
        // via the program service. Setter avoids a constructor cycle.
        this.knowledgeBaseService.setResearchProgramService(researchProgramService);
        // Phase 2 Iter 5 — hand the planner to the cycle so it can be consulted
        // before the formulation callback when the planner flag is on.
        this.researchCycleService.setExperimentPlannerService(experimentPlannerService);
        // Phase 4b — enable adversarial-reviewer routing when the flag is set.
        this.researchCycleService.setReviewerOrthogonalityEnabled(reviewerRoutingEnabled);
        if (reviewerRoutingEnabled) {
            log.info("UniversityService: reviewer-routing ENABLED — pickReviewer scores "
                    + "by topic-weight orthogonality instead of random within-domain pick.");
        }
        // Phase 4b — enable journal-mediated review when the flag is set. Hands
        // the cycle a reference to InstitutionService so it can look up the
        // journal's editor cohort during pickReviewer.
        this.researchCycleService.setInstitutionService(institutionService);
        this.researchCycleService.setJournalReviewEnabled(journalReviewEnabled);
        if (journalReviewEnabled) {
            log.info("UniversityService: journal-review ENABLED — pickReviewer narrows "
                    + "candidates to the top-{} journal editors first.", 5);
        }
        try {
            Files.createDirectories(this.researchOutputDir);
        } catch (IOException e) {
            log.warn("Could not create research output directory {}: {}", researchOutputDir, e.getMessage());
        }
        archivePreviousRunDirs(this.researchOutputDir);

        // Hand the replication service a cache-aware paper lookup so its mutations
        // are visible in the in-memory `published` cache without a re-read, and a
        // dispute listener so the markdown file stays in sync.
        this.replicationService.setPaperLookup(this::findCachedPaper);
        this.replicationService.setDisputeListener(p -> overwritePaperFile(p.roomId(), p));
        // Phase 5 C2 — DISPUTED papers emit a typed ProgramOpenQuestion on both
        // bots so the divergent peer scores get addressed in their next paper.
        if (disputeReconciliationEnabled) {
            this.replicationService.setReconciliationListener(this::enqueueReconciliation);
        }
    }

    /** Defensively parse an authorBotId string; returns null on malformed input. */
    private static UUID parseBotIdSafely(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    /** Cache-first paper lookup — falls through to the DB on a miss. */
    private Optional<ResearchPaper> findCachedPaper(String paperId) {
        for (List<ResearchPaper> bucket : published.values()) {
            synchronized (bucket) {
                for (ResearchPaper p : bucket) {
                    if (paperId.equals(p.paperId())) return Optional.of(p);
                }
            }
        }
        return papersRepository.findByPaperId(paperId);
    }

    /**
     * Lazily populate the in-memory cache for a room from the DB. Called from any read
     * path that needs the published list. Idempotent and thread-safe — first caller for
     * a room loads, subsequent calls are no-ops.
     */
    private List<ResearchPaper> roomPapers(UUID roomId) {
        if (hydratedRooms.add(roomId)) {
            List<ResearchPaper> fromDb = papersRepository.findByRoomIdOrderByTickAsc(roomId);
            if (!fromDb.isEmpty()) {
                List<ResearchPaper> bucket = published.computeIfAbsent(roomId, k -> new ArrayList<>());
                synchronized (bucket) {
                    bucket.clear();
                    bucket.addAll(fromDb);
                    if (bucket.size() > MAX_PAPERS_PER_ROOM) {
                        bucket.subList(0, bucket.size() - MAX_PAPERS_PER_ROOM).clear();
                    }
                }
                log.info("university hydrated cache from DB roomId={} papers={}", roomId, bucket.size());
            }
        }
        return published.computeIfAbsent(roomId, k -> new ArrayList<>());
    }

    public void setMentoringCallback(
            java.util.function.BiConsumer<RoomBot, List<ResearchPaper>> callback) {
        this.mentoringCallback = callback;
    }

    public void setNarratingCallback(java.util.function.Consumer<PaperDraft> callback) {
        this.narratingCallback = callback;
    }

    /**
     * Release the backlog slot for a draft once its narration task settles —
     * success, suppression, exception, or reap. Called from the narration task's
     * {@code finally} so a throw/hang before {@link #publishPaper} can't leak the
     * slot. Idempotent with publishPaper's own release.
     */
    public void releaseDraftSlot(String paperId) {
        researchCycleService.decrementDraftQueue(paperId);
    }

    public void setFormulationCallback(java.util.function.Function<Object[], Object> callback) {
        this.formulationCallback = callback;
    }

    /**
     * Called by LlmService (on its narration thread) once the LLM has written the paper
     * narrative. Falls back to template text for any null/blank field.
     * Also fires genome mentoring for high-quality papers.
     */
    public void publishPaper(PaperDraft draft, NarrationResult narration) {
        // Phase 20.4 step 2b-3 — accept both legacy null (pre-sentinel
        // callers) and the suppression sentinel. Short-circuit identically;
        // the sentinel adds visibility (status / timing / partial token
        // cost) to the log line without changing the publish-or-suppress
        // decision.
        if (narration == null || narration.isSuppressed()) {
            researchCycleService.decrementDraftQueue(draft.paperId());
            if (narration == null) {
                log.warn("university draft suppressed (blank professor synthesis) paperId={} domain={} topic={}",
                        draft.paperId(), draft.domain(), draft.topicName());
            } else {
                long durationMs = narration.startedAt() != null && narration.completedAt() != null
                        ? java.time.Duration.between(narration.startedAt(), narration.completedAt()).toMillis()
                        : -1L;
                log.warn("university draft suppressed paperId={} domain={} topic={} " +
                        "status={} durationMs={} inputTokens={} outputTokens={}",
                        draft.paperId(), draft.domain(), draft.topicName(),
                        narration.status(), durationMs,
                        narration.inputTokens(), narration.outputTokens());
            }
            return;
        }
        String hypothesis = (narration.hypothesis() != null && !narration.hypothesis().isBlank())
                ? narration.hypothesis() : draft.templateHypothesis();
        // Phase 7 F2 — pull the structured findings map from the narration. Single-entry
        // map === the flag-off baseline. Empty / null map falls back to the draft's raw
        // findings as the legacy String path did.
        Map<Integer, String> findingsBlocks =
                resolveFindingsBlocks(narration.findingsMap(), draft.rawFindings());
        String conclusion = (narration.conclusion() != null && !narration.conclusion().isBlank())
                ? narration.conclusion() : draft.templateConclusion();

        // 2026-05-29 review §1 rec #4 — hard cap at trace.size(): drop hallucinated
        // [call_N] markers whose index can't exist (N>trace size or <1) from the
        // prose before it flows through the sanitizer / validators / persistence,
        // so a fabricated [call_18] on a 5-call paper doesn't reach the reader. Runs
        // first so the stripped prose is what everything downstream sees.
        if (citationTraceCapEnabled) {
            int traceSize = narration.toolCallTrace() != null ? narration.toolCallTrace().size() : 0;
            hypothesis = stripOutOfRangeCallMarkers(hypothesis, traceSize);
            findingsBlocks = stripOutOfRangeCallMarkers(findingsBlocks, traceSize);
            conclusion = stripOutOfRangeCallMarkers(conclusion, traceSize);
        }

        // Phase 5 A4 — strip harness-parameter leak phrases (≥11 papers in the May 2026
        // corpus had simulator coords or internal peer-score values bleed into prose).
        if (harnessLeakFilterEnabled) {
            // Phase 10 — flag which sections actually carried a leak. Before
            // this commit the sanitizer ran silently, so log-driven reviews
            // couldn't tell whether the patterns covered the live corpus or
            // not. The REVIEW_REPORT.md refresh #3 observed "PromptSanitizer
            // log signature still absent" despite obvious leaks in the
            // published papers — this log line closes that observability gap.
            boolean hypLeak  = PromptSanitizer.detects(hypothesis);
            boolean findLeak = findingsBlocks.values().stream().anyMatch(PromptSanitizer::detects);
            boolean concLeak = PromptSanitizer.detects(conclusion);
            if (hypLeak || findLeak || concLeak) {
                log.info("PromptSanitizer: paperId={} leaks=[hypothesis={}, findings={}, conclusion={}]",
                        draft.paperId(), hypLeak, findLeak, concLeak);
            }

            hypothesis = PromptSanitizer.sanitize(hypothesis);
            // Phase 7 F2 — sanitize each computation block independently so a leak in
            // block 2 doesn't perturb the byte content of block 1.
            findingsBlocks = sanitizeBlocks(findingsBlocks);
            conclusion = PromptSanitizer.sanitize(conclusion);
            // Phase 7 G5 — when the call path shows peer review was skipped, strip the
            // "independently reproduced by peer reviewers" boilerplate from the
            // conclusion and stamp an explicit disclaimer. Refresh #1-#12 of
            // REVIEW_REPORT.md found this falsehood on ~75% of corpus papers.
            conclusion = PromptSanitizer.suppressBoilerplateOnSkip(conclusion, narration.callPath());
            // Phase 16 P5a — broader strip for the same boilerplate when the
            // call path indicates publish-stage failure beyond critique:skip
            // (gate:closed:professor, publish:no_methods, →needs_revision).
            // P-a328-553 shipped with the canned "values reproduced by peer
            // reviewers" line despite ending at publish:no_methods→needs_revision;
            // the Phase 7 G5 strip didn't fire because that path doesn't
            // mention critique:skip.
            conclusion = PromptSanitizer.suppressBoilerplateOnNeedsRevision(
                    conclusion, narration.callPath());
        }
        // Phase 7 F2 — the joined-prose form drives every legacy consumer (validators,
        // markdown when the structured flag is off, audit re-prompt). Multi-block maps
        // collapse to a `\n\n`-joined string in ascending key order — matching the
        // legacy single-string layout when there's only one block.
        String findings = joinFindingsBlocks(findingsBlocks);

        // Null results get no confidence boost (fixed ×0.92); others range ×0.92–×1.08
        boolean isNullResult = draft.resultClassification() == ResultClassification.NULL_RESULT;
        double confidenceFactor = isNullResult
                ? 0.92
                : (0.92 + 0.16 * Math.max(0.0, Math.min(1.0, narration.confidenceScore())));
        double adjustedScore = draft.peerScore() * confidenceFactor;

        // Phase 5 C1 — citation-aware score adjustment. The May 2026 corpus's
        // first three cross-paper citations (P-a328-34→30, 54→49, 73→69) showed
        // the room can do this — one of them cited a Reject paper. Reward
        // citations of high-quality priors; penalise citations of papers whose
        // call path shows the peer-review stage was skipped.
        //   +0.02 per citation of (PUBLISHED, peerScore > room median)
        //   -0.04 per citation of a critique:skip paper
        // Total bonus capped at +0.10; total penalty at -0.20.
        if (citationAwareScoringEnabled) {
            adjustedScore += citationAwareDelta(draft.roomId(), narration.citedPaperIds());
        }

        // Phase 7 G3 — clamp to [0, 1] so peer-score keeps its conventional meaning.
        // Refresh #2-#12 of REVIEW_REPORT.md showed P-237=1.021, P-250=1.040, plus a
        // monotonic auto-inflation staircase under single-reviewer rooms — citation +
        // confidence bonuses can push the raw score above 1.0 with no downstream
        // subtractor. Clamping restores ranking-by-score as a meaningful signal.
        adjustedScore = Math.max(0.0, Math.min(1.0, adjustedScore));

        // Phase 5 A2 — status reflects what actually happened in the narration pipeline.
        // NULL_RESULT stays NULL_RESULT regardless (it's the result classification,
        // independent of narration health). For positive results:
        //   - `critique:skip:incomplete_firstpass` → NEEDS_REVISION. The first pass was
        //     incomplete so the peer-review path never ran; the paper is not peer-reviewed.
        //   - `prof:error→fp` → PUBLISHED_NO_PROFESSOR. The professor stage failed (often
        //     Groq token-per-day exhaustion) and we fell back to the first pass; the paper
        //     is published but explicitly without the tool-calling professor's input, so
        //     telemetry can distinguish infrastructure quota issues from algorithmic ones.
        //   - Phase 5 A1: when the truncation-guard flag is on and either findings or
        //     conclusion ends mid-sentence → NEEDS_REVISION. ~55% of corpus papers hit
        //     this mode.
        //   - otherwise → PUBLISHED.
        String paperStatus;
        ReviewState reviewState;   // 2026-05-29 — pipeline/quality-health axis, assigned with paperStatus below
        String callPath = narration.callPath();
        // Phase 7 F2 — truncation guard is per-block. A single mid-sentence block
        // (or a mid-sentence conclusion) flips the paper to NEEDS_REVISION; the
        // other blocks publish unchanged. Flag-off / single-block path is
        // bit-identical to the legacy single-string check because findingsBlocks
        // has one entry and joinFindingsBlocks returns it verbatim.
        Integer truncatedBlockIndex = truncationGuardEnabled
                ? firstTruncatedBlock(findingsBlocks)
                : null;
        // Phase 12 D5 — extend the guard to the Hypothesis. P-a328-419 had its
        // Hypothesis truncated mid-sentence ("…matrix-yields-first Vf ≈ 10–20%")
        // yet published cleanly because the pre-D5 guard only checked Findings
        // and Conclusion.
        boolean hypothesisTruncated = truncationGuardEnabled
                && !endsWithSentenceTerminator(hypothesis);
        boolean truncated = truncationGuardEnabled
                && (truncatedBlockIndex != null
                        || !endsWithSentenceTerminator(conclusion)
                        || hypothesisTruncated);
        if (truncated && truncatedBlockIndex != null) {
            log.warn("truncation-guard fired on findings block paperId={} block={} blocks={}",
                    draft.paperId(), truncatedBlockIndex, findingsBlocks.keySet());
        }
        if (hypothesisTruncated) {
            log.warn("truncation-guard fired on hypothesis paperId={} (Phase 12 D5)",
                    draft.paperId());
        }
        // Phase 7 G6 — topic-specific findings prose checks (RLC R_c boilerplate,
        // Thermal-Stress "yield strength" mis-label, Arrhenius scientific notation).
        // Maps to NEEDS_REVISION (fixable in revision), not REJECTED (structural defect).
        // Phase 7 F2 — the Map overload operates on the concatenation of all blocks,
        // semantically equivalent to passing the joined `findings` string.
        List<String> topicSpecificFailures = topicSpecificFindingsValidatorEnabled
                ? TopicSpecificFindingsValidator.validate(draft.domain(), draft.topicName(), findingsBlocks)
                : List.of();
        boolean topicSpecificMismatch = !topicSpecificFailures.isEmpty();
        if (topicSpecificMismatch) {
            log.warn("topic-specific findings check failed paperId={} reasons={}",
                    draft.paperId(), topicSpecificFailures);
        }
        // Phase 12 D6 — "no prior theoretical bound" boilerplate on a topic
        // with a known textbook closed-form. The closing-prose template
        // defaults to this string when it can't identify a textbook
        // reference; REVIEW_REPORT refresh #3 §1 rec #5 named five Reject
        // papers (P-416 Mertens, P-422 Beer-Lambert, P-423 Stefan-Boltzmann,
        // P-427 Doppler, P-432 Hall) where the boilerplate fired on topics
        // whose bounds are well-known.
        List<String> closingProseFailures = closingProseValidatorEnabled
                ? ClosingProseValidator.validate(draft.domain(), draft.topicName(), conclusion)
                : List.of();
        boolean closingProseMismatch = !closingProseFailures.isEmpty();
        if (closingProseMismatch) {
            log.warn("closing-prose check failed paperId={} reasons={}",
                    draft.paperId(), closingProseFailures);
        }
        // Phase 13 E6 — uncertainty-disclosure discipline. Findings citing
        // unit-anchored numerics must carry at least one uncertainty
        // indicator (± / exact / to within); otherwise publish as
        // NEEDS_REVISION. Addresses the single most-repeated panel
        // critique in REVIEW_REPORT refresh #1 (~38 mentions across ~18 of
        // 23 papers).
        List<String> uncertaintyFailures = uncertaintyDisclosureEnabled
                ? UncertaintyDisclosureValidator.validate(findingsBlocks)
                : List.of();
        boolean uncertaintyMissing = !uncertaintyFailures.isEmpty();
        if (uncertaintyMissing) {
            log.warn("uncertainty-disclosure check failed paperId={} reasons={}",
                    draft.paperId(), uncertaintyFailures);
        }
        // Phase 14 F5 — papers reaching publishPaper with a null or empty
        // tool-call trace have no Methods section content to render. The
        // renderer would emit a paper missing the Methods (and likely Panel)
        // sections entirely, the signature P-503 carried in REVIEW_REPORT
        // refresh #2 §1 rec #5. "What computations support the claims" is
        // the most-asked panel-review recommendation in the corpus, so route
        // these to NEEDS_REVISION. Flag-off path is bit-identical to today.
        boolean noMethods = missingMethodsGateEnabled
                && (narration.toolCallTrace() == null
                        || narration.toolCallTrace().isEmpty());
        if (noMethods) {
            log.warn("missing-methods check failed paperId={} (toolCallTrace null/empty)",
                    draft.paperId());
        }
        // Phase 14 F3 — flag papers whose Findings prose claims a sample
        // count unsupported by the persisted tool-call trace (e.g. "sixteen
        // sampled cases" backed by one call). Refresh #2 §1 rec #4 named
        // P-507, P-512, P-494 as the named occurrences.
        List<String> singlePointSweepFailures = singlePointSweepEnabled
                ? SinglePointSweepValidator.validate(findingsBlocks,
                        narration.toolCallTrace())
                : List.of();
        boolean singlePointSweep = !singlePointSweepFailures.isEmpty();
        if (singlePointSweep) {
            log.warn("single-point-sweep check failed paperId={} reasons={}",
                    draft.paperId(), singlePointSweepFailures);
        }
        // Paper-review 2026-05-22 fix 4 — hypothesis-range-coverage check.
        // Warn-only: stamps the call path + logs, never routes the paper.
        // Sibling check to SinglePointSweep but operates on the hypothesis
        // side (declared range) rather than the findings side (claimed
        // sample count). P-a328-900: hypothesis "from 400nm to 700nm"
        // tested at single λ=654nm — caught here.
        List<String> hypothesisRangeFailures = hypothesisRangeCoverageEnabled
                ? HypothesisRangeCoverageValidator.validate(
                        hypothesis, narration.toolCallTrace())
                : List.of();
        if (!hypothesisRangeFailures.isEmpty()) {
            log.warn("hypothesis-range-coverage check failed paperId={} reasons={}",
                    draft.paperId(), hypothesisRangeFailures);
        }
        // Phase 14 F4 — flag Conclusion polarity inversions against the
        // Findings number vs Hypothesis bound. Refresh #2 §1 rec #3 named
        // P-491 (0.694% well within 5% but Conclusion "rules out"), P-496
        // (quality-score talk against ratio hypothesis), P-511 (wrong Tc).
        List<String> directionOfFindingFailures = directionOfFindingEnabled
                ? DirectionOfFindingValidator.validate(hypothesis, findingsBlocks, conclusion,
                        narration.toolCallTrace() == null ? 0 : narration.toolCallTrace().size())
                : List.of();
        boolean directionOfFindingInverted = !directionOfFindingFailures.isEmpty();
        if (directionOfFindingInverted) {
            log.warn("direction-of-finding check failed paperId={} reasons={}",
                    draft.paperId(), directionOfFindingFailures);
        }
        // Phase 18 §18.2 (P8d) — unsourced derivative / uncertainty / error /
        // ±N claims in Findings/Conclusion with no paired [call_N] reference
        // in the same sentence. Targets P-603 (Snell "1.5° error", "331° per
        // unit derivative") and P-605 (Kepler) "math right, prose fabricated"
        // pattern. Sample-size claims are SinglePointSweepValidator's
        // territory and are deliberately NOT flagged here.
        List<String> unsourcedClaimFailures = unsourcedClaimEnabled
                ? UnsourcedClaimValidator.validate(findingsBlocks, conclusion)
                : List.of();
        boolean unsourcedClaimFlagged = !unsourcedClaimFailures.isEmpty();
        // Only route the paper when mode=downgrade; warn mode logs+stamps
        // without changing status.
        boolean unsourcedClaimRoutes = unsourcedClaimFlagged
                && "downgrade".equals(unsourcedClaimMode);
        if (unsourcedClaimFlagged) {
            log.warn("unsourced-claim check failed paperId={} mode={} reasons={}",
                    draft.paperId(), unsourcedClaimMode, unsourcedClaimFailures);
        }
        // 2026-05-21 review §1 rec #4 follow-up — Hypothesis prose cites a
        // baseline value (e.g. "≈ 0.300") that no run_predicate_scan tool
        // call actually used. P-a328-871 wrote "0.300" into prose while
        // the tool was invoked with baseline=0; the published artifact
        // looks like 0.300 was tested when the simulator never did.
        List<String> proseBaselineDriftFailures = proseBaselineDriftEnabled
                ? ProseBaselineDriftValidator.validate(hypothesis,
                        narration.toolCallTrace())
                : List.of();
        boolean proseBaselineDriftFlagged = !proseBaselineDriftFailures.isEmpty();
        boolean proseBaselineDriftRoutes  = proseBaselineDriftFlagged
                && "downgrade".equals(proseBaselineDriftMode);
        if (proseBaselineDriftFlagged) {
            log.warn("prose-baseline-drift check failed paperId={} mode={} reasons={}",
                    draft.paperId(), proseBaselineDriftMode, proseBaselineDriftFailures);
        }
        // 2026-05-21 review §1 rec #5 — textual "Lastname (Year)" cites in
        // Findings / Discussion / Related-Work prose that don't appear in
        // literature-corpus.yml. P-a328-862 cited "P-2022-07" (which the
        // unicode-dashed paper-ID regex also missed; that's a separate fix),
        // P-a328-872 "Torquato and Stillinger", P-a328-890 "Hecht Optics".
        // The corpus is sourced from LiteratureSearchService (already
        // wired via setter injection at line 258). Skip the check entirely
        // when the service hasn't published a non-empty corpus yet.
        List<String> citationCorpusFailures = (citationCorpusEnabled
                && literatureSearch != null && literatureSearch.size() > 0)
                ? CitationCorpusValidator.validate(
                        joinPaperProse(findingsBlocks,
                                narration.discussion(), narration.relatedWork()),
                        literatureSearch.allReferences())
                : List.of();
        boolean citationCorpusFlagged = !citationCorpusFailures.isEmpty();
        boolean citationCorpusRoutes  = citationCorpusFlagged
                && "downgrade".equals(citationCorpusMode);
        if (citationCorpusFlagged) {
            log.warn("citation-corpus check failed paperId={} mode={} reasons={}",
                    draft.paperId(), citationCorpusMode, citationCorpusFailures);
        }
        // 2026-05-21 review §1 rec #6 — Discussion / Related-Work prose
        // mentioning a registered topic other than the paper's own.
        // P-a328-886 (Abundant Density) carried "the specific focus on the
        // Pendulum Breakdown Angle is new" — narrator template-bleed.
        // Scope is discussion + related-work only; findings legitimately
        // reference other topics when comparing computations.
        List<String> topicConsistencyFailures = topicConsistencyEnabled
                ? TopicConsistencyValidator.validate(draft.topicName(),
                        joinDiscussionAndRelatedWork(narration.discussion(),
                                narration.relatedWork()),
                        TopicValidatorRegistry.defaultRegistry().allTopicNames())
                : List.of();
        boolean topicConsistencyFlagged = !topicConsistencyFailures.isEmpty();
        boolean topicConsistencyRoutes  = topicConsistencyFlagged
                && "downgrade".equals(topicConsistencyMode);
        if (topicConsistencyFlagged) {
            log.warn("topic-consistency check failed paperId={} declaredTopic=\"{}\" mode={} foreignTopics={}",
                    draft.paperId(), draft.topicName(), topicConsistencyMode,
                    topicConsistencyFailures);
        }
        // 2026-05-21 review §1 rec #7 — scaling / power-law / linearity
        // claim with fewer than 3 non-sweep sample points. P-a328-884
        // (Hall-Petch) is the canonical signature: 2 sandbox calls, claim
        // "σ scales as σ₀ + k·d^(−1/2)". The validator bypasses depth
        // checks if any sweep-style tool (run_param_sweep, run_predicate_scan,
        // run_gpu_eval, evaluate_symbolic) is in the trace.
        List<String> scalingSweepDepthFailures = scalingSweepDepthEnabled
                ? ScalingSweepDepthValidator.validate(hypothesis, findingsBlocks,
                        conclusion, narration.toolCallTrace())
                : List.of();
        boolean scalingSweepDepthFlagged = !scalingSweepDepthFailures.isEmpty();
        boolean scalingSweepDepthRoutes  = scalingSweepDepthFlagged
                && "downgrade".equals(scalingSweepDepthMode);
        if (scalingSweepDepthFlagged) {
            log.warn("scaling-sweep-depth check failed paperId={} mode={} reasons={}",
                    draft.paperId(), scalingSweepDepthMode, scalingSweepDepthFailures);
        }
        // Phase 24 §24.4 — SR evidence: prose mentioning a symbolic-
        // regression result must be backed by a successful
        // symbolic_regress tool call in the audit trail. Detects the
        // fabrication mode where the LLM claims a recovered closed-form
        // without having invoked the tool that would produce one.
        List<String> srEvidenceFailures = srEvidenceEnabled
                ? SymbolicRegressionEvidenceValidator.validate(
                        joinPaperProse(findingsBlocks,
                                narration.discussion(), narration.relatedWork()),
                        narration.toolCallTrace())
                : List.of();
        boolean srEvidenceFlagged = !srEvidenceFailures.isEmpty();
        boolean srEvidenceRoutes  = srEvidenceFlagged
                && "downgrade".equals(srEvidenceMode);
        if (srEvidenceFlagged) {
            log.warn("sr-evidence check failed paperId={} mode={} reasons={}",
                    draft.paperId(), srEvidenceMode, srEvidenceFailures);
        }
        // Phase 14 F1b — flag the Five-Computation stub-block fabrication
        // pattern. When the structured-findings map has more blocks than the
        // tool-call trace has successful entries, the narrator emitted prose
        // for computations that never ran. Refresh #2 §1 rec #1 named 6 of
        // 20 papers (P-480/485/489/492/502/503) with per-paper success
        // counts of 1, 1, 1, 2, 2, 0 against block counts of 5.
        int blockCount   = findingsBlocks != null ? findingsBlocks.size() : 0;
        int successCount = narration.toolCallTrace() != null
                ? narration.toolCallTrace().size() : 0;
        boolean blockCountOvershoot = blockCountGuardEnabled
                && blockCount > Math.max(1, successCount);
        if (blockCountOvershoot) {
            log.warn("block-count-guard check failed paperId={} blocks={} successes={}",
                    draft.paperId(), blockCount, successCount);
        }
        // Phase 5 B1 — flag papers whose Conclusion contradicts the Hypothesis or
        // Findings on a numerical claim. The validator returns a list of textual
        // reasons; non-empty list means the paper failed at least one check.
        // Phase 7 F2 — Map overload concatenates blocks before running the checks.
        // Phase 7 F5.5 — when a tool-call trace exists, the trace-aware overload also
        // runs Check C (unsourced salient numbers). Empty / null trace short-circuits
        // Check C so flag-off / legacy / Ollama-fallback papers behave identically
        // to the F5.4 baseline.
        List<String> consistencyFailures = numericalConsistencyEnabled
                ? NumericalConsistencyValidator.validate(hypothesis, findingsBlocks, conclusion,
                        narration.toolCallTrace())
                : List.of();
        boolean numericallyInconsistent = !consistencyFailures.isEmpty();
        if (numericallyInconsistent) {
            log.warn("numerical-consistency check failed paperId={} reasons={}",
                    draft.paperId(), consistencyFailures);
        }
        // Phase 5 B2 — flag papers whose findings contain magnitudes outside the
        // physically-reasonable range for their (domain, topic).
        // Phase 7 F2 — Map overload concatenates blocks before checking magnitudes.
        // Phase 18 §18.1 (P8c) — also scan the tool-call trace for violations
        // the Findings prose may have dropped (P-a328-593 hidden-5790-MPa pattern).
        // Same bound table; trace violations carry a "[trace]" prefix.
        List<String> magnitudeFailures;
        if (magnitudeRangeEnabled) {
            magnitudeFailures = new java.util.ArrayList<>(
                    MagnitudeRangeValidator.validate(draft.domain(), draft.topicName(),
                            findingsBlocks, literatureSearch));
            magnitudeFailures.addAll(
                    MagnitudeRangeValidator.validateTrace(draft.domain(), draft.topicName(),
                            narration.toolCallTrace(), literatureSearch));
        } else {
            magnitudeFailures = List.of();
        }
        boolean magnitudeOutOfRange = !magnitudeFailures.isEmpty();
        if (magnitudeOutOfRange) {
            log.warn("magnitude-range check failed paperId={} reasons={}",
                    draft.paperId(), magnitudeFailures);
        }
        // Phase 5 B3 — flag near-byte-identical reruns. A clone that cites the
        // prior is allowed (the cross-paper-citation behaviour the corpus needs);
        // an uncited clone publishes as NEEDS_REVISION.
        // Phase 7 F2 — Map overload concatenates blocks before fingerprinting.
        List<String> cloneIds = dedupEnabled
                ? ContentHashDeduplicator.findClones(draft.domain(), draft.topicName(),
                        findingsBlocks, roomPapers(draft.roomId()))
                : List.of();
        List<String> cited = narration.citedPaperIds() != null ? narration.citedPaperIds() : List.of();
        boolean uncitedClone = !cloneIds.isEmpty()
                && cloneIds.stream().noneMatch(cited::contains);
        if (uncitedClone) {
            log.warn("dedup check failed paperId={} clones={} (none cited)",
                    draft.paperId(), cloneIds);
        }
        // Phase 5 B4 — flag papers whose hypothesis drifts out of its declared domain.
        // INTERDISCIPLINARY papers intentionally bridge math + materials/physics and
        // are skipped by the gate.
        java.util.Optional<String> crossDomainMismatch = crossDomainGateEnabled
                ? CrossDomainHypothesisGate.validate(draft.domain(), draft.topicName(),
                        hypothesis, literatureSearch)
                : java.util.Optional.empty();
        boolean crossDomainDrift = crossDomainMismatch.isPresent();
        if (crossDomainDrift) {
            log.warn("cross-domain-gate failed paperId={} reason={}",
                    draft.paperId(), crossDomainMismatch.get());
        }
        // Phase 7 G8 — when the audit-4b reviewer flagged physical_mechanism on a
        // SUPPORTED paper, route to REJECTED. SUPPORTED-only because NULL_RESULT /
        // PARTIALLY_SUPPORTED already have correct downstream handling. Catches the
        // prime-gap→CTE, Collatz→RLC, happy-number→packing non-sequitur clusters.
        List<String> auditGapDimensions = narration.auditGapDimensions() != null
                ? narration.auditGapDimensions() : List.of();
        boolean physicalMechanismAuditFailed = auditPhysicalMechanismRejectsEnabled
                && draft.resultClassification() == ResultClassification.SUPPORTED
                && auditGapDimensions.contains("physical_mechanism");
        if (physicalMechanismAuditFailed) {
            log.warn("audit:physical_mechanism→REJECTED paperId={} dimensions={}",
                    draft.paperId(), auditGapDimensions);
        }
        // Phase 7 F7.3 — cherry-picked-evidence audit-gap routing. When the panel
        // surfaced the dimension (>=2 reviewers flagged the same omitted-relevant
        // tool call) AND the operator flag is on AND the paper is SUPPORTED, the
        // paper routes to REJECTED. Default flag-off so the dimension is
        // advisory-only until the false-positive rate is measured.
        boolean cherryPickedAuditFailed = auditCherryPickedRejectsEnabled
                && draft.resultClassification() == ResultClassification.SUPPORTED
                && auditGapDimensions.contains("cherry_picked_evidence");
        if (cherryPickedAuditFailed) {
            log.warn("audit:cherry_picked_evidence→REJECTED paperId={} dimensions={}",
                    draft.paperId(), auditGapDimensions);
        }
        // Status precedence is failure-strength-ordered: NULL_RESULT > REJECTED > NEEDS_REVISION
        // > PUBLISHED_NO_PROFESSOR > PUBLISHED. NULL_RESULT is "computation ran cleanly but
        // hypothesis not supported" — an honest negative, kept above REJECTED. REJECTED
        // means a Phase 5 B-series validator caught a structural defect (wrong magnitudes,
        // wrong labels, uncited clone, cross-domain non-sequitur); we surface this as a
        // distinct status from NEEDS_REVISION so operators can tell "validator caught it"
        // from "narration truncated."
        // Phase 12 D2 — when numerical-consistency fires and mode is `suppress`,
        // drop the paper entirely (no addPaper, no disk write, no DB row); the
        // operational log carries the suppression marker the same way Phase 10's
        // all_tools_failed→suppressed and panel:n=1:degraded:MANUAL_REVIEW→suppressed
        // gates do. REVIEW_REPORT.md's skill treats these as gate-suppressed (not
        // missing) on its next refresh.
        if (numericallyInconsistent
                && "suppress".equals(numericalConsistencyMode)) {
            log.warn("numeric_inconsistent→suppressed paperId={} reasons={} (Phase 12 D2)",
                    draft.paperId(), consistencyFailures);
            researchCycleService.decrementDraftQueue(draft.paperId());
            return;
        }
        // 2026-05-21 — when missing-methods mode is `suppress`, drop the
        // empty-trace paper entirely. Same shape as the numerical-consistency
        // suppress branch above. The 2026-05-21 batch produced three empty-
        // trace papers (P-865/880/890) where readers gain nothing from a
        // NEEDS_REVISION stamp (no Methods section to revise). Default mode
        // is `downgrade`, preserving the prior status-precedence behaviour.
        if (noMethods && "suppress".equals(missingMethodsGateMode)) {
            log.warn("missing-methods→suppressed paperId={} (toolCallTrace null/empty)",
                    draft.paperId());
            researchCycleService.decrementDraftQueue(draft.paperId());
            return;
        }
        // Phase 12 D2 — when mode is `downgrade`, override findings + conclusion
        // with an honest NULL_RESULT preamble naming the first few unverifiable
        // values so readers can see exactly which numerics the validator couldn't
        // trace. The paper still publishes — its peer score / call path / panel
        // outcome are preserved — but as NULL_RESULT, not REJECTED.
        boolean numericInconsistentDowngraded =
                numericallyInconsistent && "downgrade".equals(numericalConsistencyMode);
        if (numericInconsistentDowngraded) {
            String preamble = buildNumericInconsistentPreamble(consistencyFailures);
            findingsBlocks = java.util.Map.of(1, preamble);
            findings = preamble;
            conclusion = "The hypothesis remains untested in this submission "
                    + "(see findings preamble for the values that could not be traced).";
            isNullResult = true;
        }
        boolean validatorRejected = numericallyInconsistent || magnitudeOutOfRange
                || uncitedClone || crossDomainDrift || physicalMechanismAuditFailed
                || cherryPickedAuditFailed;
        // 2026-05-29 — reviewState (pipeline/quality-health axis) is assigned
        // INLINE alongside paperStatus in each branch, so it is precedence-faithful
        // by construction (it cannot drift from the status the ladder actually
        // picked). INCOMPLETE = the pipeline did not finish (content may be sound,
        // a re-queue candidate); REVISABLE_DEFECT = a fixable content defect;
        // GATE_FLAGGED = a validator/audit hard-flag (incl. a gate-forced null,
        // where harness false-positives live); CLEAN = published or a genuine null.
        if (isNullResult) {
            paperStatus = "NULL_RESULT";
            // numericInconsistentDowngraded forced the null → GATE_FLAGGED (likely
            // false-positive); an intrinsic science null → CLEAN.
            reviewState = ReviewState.forNull(numericInconsistentDowngraded);
        } else if (validatorRejected) {
            paperStatus = "REJECTED";
            reviewState = ReviewState.GATE_FLAGGED;
        } else if (truncated) {
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.INCOMPLETE;   // token-cutoff; content before the cut may be sound
        } else if (topicSpecificMismatch) {
            // G6 — kept below truncation/validators (more severe), above the
            // call-path-driven NEEDS_REVISION clauses.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (closingProseMismatch) {
            // Phase 12 D6 — boilerplate-on-textbook-topic. Same tier as G6:
            // a content-quality issue that revision can fix.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (uncertaintyMissing) {
            // Phase 13 E6 — uncertainty-disclosure discipline. Same tier as
            // G6 / D6: a content-quality issue that revision can fix by
            // adding the ± clause or the "exact closed form" qualifier.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (noMethods && "downgrade".equals(missingMethodsGateMode)) {
            // Phase 14 F5 — missing Methods section. Structurally stronger
            // than uncertaintyMissing (no math at all vs unlabeled math).
            // Slotted at this tier because revision CAN fix it by re-running
            // the professor stage with a non-empty trace.
            // 2026-05-21 — mode-gated. `suppress` already short-circuited
            // before status precedence; `warn` falls through to the next
            // clause; only `downgrade` (legacy default) stamps here.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.INCOMPLETE;   // no tool trace; re-running the professor can fix it
        } else if (singlePointSweep) {
            // Phase 14 F3 — inflated sweep size in Findings. Same tier as
            // F5 / E6 / D6: a content-quality issue that revision can fix
            // by rewording the prose to match the trace.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (directionOfFindingInverted) {
            // Phase 14 F4 — Conclusion polarity inversion. Same tier as F3:
            // revision can fix the prose by aligning the verdict with the
            // observed deviation.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (unsourcedClaimRoutes) {
            // Phase 18 §18.2 (P8d) — unsourced derivative / uncertainty /
            // error / ±N claim with no paired [call_N] in the same sentence.
            // Same tier as F3/F4: a content-quality issue revision can fix.
            // Only routes here in mode=downgrade; warn mode stamps without
            // changing status.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (proseBaselineDriftRoutes) {
            // 2026-05-21 review §1 rec #4 follow-up — Hypothesis prose
            // cited a baseline the tool call never used. Same tier as
            // unsourced-claim: revision can fix by either re-running the
            // predicate scan with the prose baseline or rewriting the
            // prose to match the args.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (citationCorpusRoutes) {
            // 2026-05-21 review §1 rec #5 — fabricated author-year cite
            // not in literature-corpus.yml. Same tier as F3/F4/prose-
            // baseline: revision can fix by either citing a real corpus
            // entry or removing the unsupported reference.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (topicConsistencyRoutes) {
            // 2026-05-21 review §1 rec #6 — foreign topic name mentioned
            // in Discussion / Related-Work. Same tier as citation-corpus:
            // revision can fix by either removing the cross-topic mention
            // or reframing it as a legitimate methodological comparison.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (scalingSweepDepthRoutes) {
            // 2026-05-21 review §1 rec #7 — scaling/linearity claim with
            // < 3 non-sweep sample points. Same tier as F3/F4/citation-
            // corpus: revision can fix by either running additional
            // sample points or reframing the claim as a single-point check.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (srEvidenceRoutes) {
            // Phase 24 §24.4 — prose claims a symbolic-regression result
            // with no matching tool call. Same tier as F3/F4/citation-
            // corpus: revision can fix by either removing the unsupported
            // claim or re-running the professor stage with the
            // symbolic_regress tool to produce real evidence.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (blockCountOvershoot) {
            // Phase 14 F1b — Five-Computation stub-block fabrication. Block
            // count > trace.size() means the narrator wrote prose for
            // computations that never ran. Same tier as F3/F4/F5/E6/D6:
            // a content-quality issue that revision can fix by re-running
            // the professor stage or by tightening the narrator prompt.
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.REVISABLE_DEFECT;
        } else if (callPath != null && callPath.contains(":demoted_to_needs_revision")) {
            // Phase 12 D4 — degraded panel (n=2 or n=3 of configured 4) — stamp
            // is `panel:n=N:degraded:demoted_to_needs_revision`.
            //
            // Phase 17 panel-grade-floor — full panel but grade-floor +
            // contradictions fired — stamp is
            // `panel:grade_floor=N.NN:demoted_to_needs_revision`.
            //
            // Both gates share the `:demoted_to_needs_revision` suffix so a
            // single contains() check catches either. The narration service
            // stamps the marker on the callPath; this ladder reads it BEFORE
            // the prof:error→fp PUBLISHED_NO_PROFESSOR clause so the demotion
            // wins when both apply.
            paperStatus = "NEEDS_REVISION";
            // A degraded panel (fewer reviewers ran) is INCOMPLETE; a full-panel
            // grade-floor demotion is a REVISABLE_DEFECT (reviewers graded it low).
            reviewState = ReviewState.forPanelDemotion(callPath);
        } else if (callPath != null && callPath.contains("critique:skip:incomplete_firstpass")) {
            paperStatus = "NEEDS_REVISION";
            reviewState = ReviewState.INCOMPLETE;   // rec #8 timeout salvage — never peer-reviewed
        } else if (callPath != null && callPath.contains("rev:ollama:ok")) {
            // Phase 7 E3 — Ollama-fallback path: critique+revise completed on the
            // fast pool when the primary chain was exhausted. The paper IS revised
            // (just without the tool-calling professor) and is more trustworthy than
            // a PUBLISHED_NO_PROFESSOR fallback, so it publishes cleanly. Keep this
            // clause BEFORE the prof:error→fp branch so a paper that hit prof:error
            // on tools and then succeeded on ollama-fallback still publishes.
            paperStatus = "PUBLISHED";
            reviewState = ReviewState.CLEAN;
        } else if (callPath != null && callPath.contains("prof:error→fp")) {
            paperStatus = "PUBLISHED_NO_PROFESSOR";
            reviewState = ReviewState.INCOMPLETE;   // professor stage never ran
        } else {
            paperStatus = "PUBLISHED";
            reviewState = ReviewState.CLEAN;
        }

        String persistedCallPath = narration.callPath();
        if (physicalMechanismAuditFailed) {
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? "audit:physical_mechanism→REJECTED"
                    : persistedCallPath + " → audit:physical_mechanism→REJECTED";
        }
        if (cherryPickedAuditFailed) {
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? "audit:cherry_picked_evidence→REJECTED"
                    : persistedCallPath + " → audit:cherry_picked_evidence→REJECTED";
        }
        // Phase 14 F5 — additive stamp so operators can correlate
        // NEEDS_REVISION papers against the missing-Methods cause.
        if (noMethods) {
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? "publish:no_methods→needs_revision"
                    : persistedCallPath + " → publish:no_methods→needs_revision";
        }
        // Phase 14 F3 — additive stamp for inflated-sweep failures.
        if (singlePointSweep) {
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? "publish:single_point_sweep→needs_revision"
                    : persistedCallPath + " → publish:single_point_sweep→needs_revision";
        }
        // Paper-review 2026-05-22 fix 4 — additive stamp for hypothesis
        // range under-coverage. Warn-only (no routing), so the stamp
        // carries a `:warn` suffix so operators can grep for the
        // would-be route rate before promoting to downgrade.
        if (!hypothesisRangeFailures.isEmpty()) {
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? "publish:range_coverage_under:warn"
                    : persistedCallPath + " → publish:range_coverage_under:warn";
        }
        // Phase 14 F4 — additive stamp for polarity inversions.
        if (directionOfFindingInverted) {
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? "publish:direction_inversion→needs_revision"
                    : persistedCallPath + " → publish:direction_inversion→needs_revision";
        }
        // Phase 18 §18.2 (P8d) — additive stamp for unsourced-claim flags.
        // Stamps in BOTH warn and downgrade modes so the operator can observe
        // the would-be-route rate; only downgrade mode actually re-routes the
        // status above. Stamp carries the first-flagged claim type.
        if (unsourcedClaimFlagged) {
            String claimType = unsourcedClaimFailures.get(0)
                    .replaceFirst("^unsourced_claim:", "")
                    .replaceFirst("\\s.*", "");
            String suffix = unsourcedClaimRoutes ? "→needs_revision" : ":warn";
            String stamp = "publish:unsourced_claim:" + claimType + suffix;
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? stamp
                    : persistedCallPath + " → " + stamp;
        }
        // 2026-05-21 — additive stamp for prose-baseline-drift. Same shape as
        // unsourced-claim: warn mode stamps :warn so refresh can audit
        // would-be-route rate; downgrade mode routes via the status above.
        if (proseBaselineDriftFlagged) {
            String suffix = proseBaselineDriftRoutes ? "→needs_revision" : ":warn";
            String stamp = "publish:prose_baseline_drift" + suffix;
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? stamp
                    : persistedCallPath + " → " + stamp;
        }
        // 2026-05-21 — additive stamp for citation-corpus. Same shape; the
        // count of unmatched cites goes in the stamp so review-report can
        // see firing severity at a glance.
        if (citationCorpusFlagged) {
            String suffix = citationCorpusRoutes ? "→needs_revision" : ":warn";
            String stamp = "publish:citation_corpus:n=" + citationCorpusFailures.size() + suffix;
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? stamp
                    : persistedCallPath + " → " + stamp;
        }
        // 2026-05-21 — additive stamp for topic-consistency. Carries the
        // count of foreign topics so refresh can see at a glance whether
        // a paper had a single leak or many.
        if (topicConsistencyFlagged) {
            String suffix = topicConsistencyRoutes ? "→needs_revision" : ":warn";
            String stamp = "publish:topic_consistency:n="
                    + topicConsistencyFailures.size() + suffix;
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? stamp
                    : persistedCallPath + " → " + stamp;
        }
        // 2026-05-21 — additive stamp for scaling-sweep-depth. The trace
        // size goes in the stamp so refresh can see whether the paper had
        // 1 or 2 non-sweep calls without expanding the full reason.
        if (scalingSweepDepthFlagged) {
            String suffix = scalingSweepDepthRoutes ? "→needs_revision" : ":warn";
            int traceSize = narration.toolCallTrace() == null
                    ? 0 : narration.toolCallTrace().size();
            String stamp = "publish:scaling_sweep_depth:trace=" + traceSize + suffix;
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? stamp
                    : persistedCallPath + " → " + stamp;
        }
        // Phase 24 §24.4 — additive stamp for sr-evidence. Reasons are
        // capped at a one-line summary to keep the call-path tidy.
        if (srEvidenceFlagged) {
            String suffix = srEvidenceRoutes ? "→needs_revision" : ":warn";
            String stamp = "publish:sr_evidence:fails=" + srEvidenceFailures.size() + suffix;
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? stamp
                    : persistedCallPath + " → " + stamp;
        }
        // Phase 14 F1b — additive stamp for block-count overshoot.
        if (blockCountOvershoot) {
            String stamp = "publish:block_count_overshoot:blocks=" + blockCount
                    + ":successes=" + successCount + "→needs_revision";
            persistedCallPath = (persistedCallPath == null || persistedCallPath.isBlank())
                    ? stamp
                    : persistedCallPath + " → " + stamp;
        }
        // Phase 7 F2 — when the structured-findings map carries more than one block,
        // persist the JSON-serialised map into the (text) findings column so the
        // markdown renderer can emit H3-per-computation later. Single-block (flag-off)
        // path persists the plain prose verbatim — bit-identical to F1-baseline.
        String persistedFindings = findingsBlocks.size() > 1
                ? serialiseFindingsMap(findingsBlocks)
                : findings;
        ResearchPaper paper = new ResearchPaper(
                draft.paperId(), draft.roomId(), draft.authorBotId(), draft.domain(), draft.topicName(),
                draft.title(), hypothesis, draft.methodology(), persistedFindings, conclusion,
                draft.reviewerBotIds(), adjustedScore, paperStatus,
                draft.gameTick(), draft.publishedAt(), draft.generation(), draft.anomalyZScore(),
                narration.aiModel(), persistedCallPath);

        // Phase 23.4 — carry the kind tag from the draft onto the persisted paper.
        // Defaulted to DOMAIN by the 27-arg backwards-compat constructor on PaperDraft
        // so this is a no-op for every existing call site.
        paper.setKind(draft.kind());

        // Phase 4c Piece 2 gate 3: copy narration template provenance onto the persisted
        // paper. Null when the narration came from an uninstrumented path; treated by
        // Piece 3 as "unattributed" and excluded from per-variant rollback statistics.
        paper.setNarrationTemplateId(narration.templateId());
        paper.setNarrationTemplateVersion(narration.templateVersion());
        // Phase 6 D2 — Discussion + Related-Work fields. Both null when the
        // narrator didn't emit them (flag off or v1 template).
        paper.setDiscussion(narration.discussion());
        paper.setRelatedWork(narration.relatedWork());
        // Phase 7 F5.4 — serialise the agentic tool-call trace as JSON so the
        // audit record survives the JVM. Null when the professor stage didn't
        // run or ran without tool calls (e.g. JSON-path / Ollama fallback).
        paper.setToolCallTrace(serialiseToolCallTrace(narration.toolCallTrace()));
        // Phase 7 F7.2 — persist the multi-reviewer panel outcome. The full
        // PanelOutcome is JSON-serialised onto a TEXT column; the composite
        // agreement score is cached into its own DOUBLE column so DISPUTED
        // routing (F7.3) can query / index on it without parsing the JSON.
        PanelOutcome outcome = narration.panelOutcome();
        paper.setPanelOutcome(serialisePanelOutcome(outcome));
        paper.setReviewerAgreement(outcome != null && !outcome.isEmpty()
                ? outcome.agreementScore() : null);

        // Phase 20.4 — copy the narration-orchestration envelope (timing,
        // cumulative token totals, terminal status) from the NarrationResult
        // onto the persisted paper. Legacy emission sites that pre-date the
        // envelope leave these as null/0/UNKNOWN — same semantics as the
        // existing null-default policy for templateId / discussion / etc.
        paper.setNarrationStartedAt(narration.startedAt());
        paper.setNarrationCompletedAt(narration.completedAt());
        paper.setNarrationInputTokens(narration.inputTokens());
        paper.setNarrationOutputTokens(narration.outputTokens());
        paper.setNarrationStatus(narration.status() != null
                ? narration.status().name()
                : NarrationResult.OutcomeStatus.UNKNOWN.name());
        // 2026-05-29 — persist the pipeline/quality-health axis decided in the
        // status ladder above (orthogonal to status; see ReviewState).
        paper.setReviewState(reviewState.name());
        // 2026-05-29 — INCOMPLETE re-queue. Read any existing row for this paperId
        // once: a row with reworkAttempts>=1 means this publish is the in-place
        // RE-publish of a paper the rework sweep already dispatched (the sweep
        // bumps the count before dispatching), so the score-improvement guard
        // below applies. Fresh papers have no prior row.
        ResearchPaper priorRow = draft.paperId() == null
                ? null
                : papersRepository.findByPaperId(draft.paperId()).orElse(null);
        boolean reworkRePublish = priorRow != null && priorRow.reworkAttempts() >= 1;

        // Score-improvement guard: a rework must not clobber the original with a
        // result that is no better. "Better" = it actually finished (the original
        // was INCOMPLETE and the rework is not) OR it scored strictly higher. If
        // neither, keep the original untouched — the source row already had its
        // attempt count bumped by the sweep, so it will not be re-queued.
        if (reworkRePublish) {
            boolean better = reworkIsImprovement(
                    "INCOMPLETE".equals(priorRow.reviewState()),
                    reviewState == ReviewState.INCOMPLETE,
                    priorRow.peerScore(), adjustedScore);
            if (!better) {
                log.info("incomplete-rework: keeping original paperId={} (reviewState={} "
                        + "score={}) — rework was not an improvement (reviewState={} score={})",
                        draft.paperId(), priorRow.reviewState(),
                        String.format("%.4f", priorRow.peerScore()),
                        reviewState, String.format("%.4f", adjustedScore));
                researchCycleService.decrementDraftQueue(draft.paperId());
                return;
            }
            log.info("incomplete-rework: replacing paperId={} — rework improved it "
                    + "(reviewState {} → {}, score {} → {})",
                    draft.paperId(), priorRow.reviewState(), reviewState,
                    String.format("%.4f", priorRow.peerScore()),
                    String.format("%.4f", adjustedScore));
        }

        // Carry the rework-attempt count forward across the in-place re-publish so
        // a rework that comes out INCOMPLETE again lands at the cap and is not
        // re-queued. Then persist a faithful draft snapshot for INCOMPLETE papers
        // so IncompleteReworkService can re-narrate from the true seed inputs; the
        // cap is enforced by that service's candidate query, so we always snapshot
        // when INCOMPLETE.
        paper.setReworkAttempts(priorRow != null ? priorRow.reworkAttempts() : 0);
        if (reviewState == ReviewState.INCOMPLETE) {
            try {
                paper.setReworkDraftJson(objectMapper.writeValueAsString(
                        ReworkDraftSnapshot.from(draft)));
            } catch (Exception e) {
                // Best-effort: a paper we can't snapshot simply won't be re-queued.
                log.warn("rework-snapshot serialize failed paperId={}: {}",
                        draft.paperId(), e.toString());
            }
        }

        // Phase 23.6 panel-emission slice — route the aggregated reviewer
        // proposal (median ordinal, weaker-side tiebreak) onto the paper
        // BEFORE the floor's demote pass, so the floor validates the panel's
        // claim against the paper text. Empty proposal (no reviewer voted,
        // or panel never ran) leaves the paper's prior label intact — which
        // defaults to CONJECTURE by ResearchPaper's field initializer.
        if (outcome != null) {
            outcome.proposedEvidenceLabel().ifPresent(paper::setEvidenceLabel);
        }

        // Phase 23.6 — vet the proposed EvidenceLabel through LabelFloor
        // before persistence. The floor reads the full paper corpus
        // (hypothesis + findings + conclusion + discussion) and demotes
        // labels that the text doesn't support: claims of THEOREM with no
        // Proof/Q.E.D./∎ structure → CONJECTURE; claims of EMPIRICAL_RESULT
        // without "mechanism" AND ("adversarial check" OR a verify_conjecture
        // tool call) → CONJECTURE.
        paper.setEvidenceLabel(LabelFloor.demote(paper.evidenceLabel(), paper));

        // Phase 3 Iter 8: bootstrap institutions and stamp FKs onto the paper before
        // it lands in the cache + DB. No-op when the institutions flag is off.
        InstitutionService.BootstrapResult institutions =
                institutionService.bootstrapForPaper(draft.roomId(), draft.domain());
        if (institutions != null) {
            if (institutions.university() != null) {
                paper.setUniversityId(institutions.university().getId());
            }
            if (institutions.journal() != null) {
                paper.setJournalId(institutions.journal().getId());
                institutionService.recordJournalAcceptance(institutions.journal().getId());
            }
        }

        addPaper(draft.roomId(), paper);
        researchCycleService.decrementDraftQueue(draft.paperId());
        // Surface BOTH the scientific classification and the post-audit workflow
        // status. draft.resultClassification() is the peer-review label (e.g.
        // SUPPORTED) assigned BEFORE the Phase-5 B-series audit can override the
        // disposition to REJECTED; paperStatus is the persisted, post-audit
        // verdict. Logging classification alone made the line read
        // "classification=SUPPORTED" for papers the document stamps REJECTED
        // (P-a328-953, -954, -967) — the status was invisible. persistedCallPath
        // (vs narration.callPath()) carries the "→ audit:…→REJECTED" suffix
        // appended above, so the path now explains the status.
        log.info("university paper published paperId={} domain={} topic={} score={} confidence={} classification={} status={} path=[{}]",
                draft.paperId(), draft.domain(), draft.topicName(),
                String.format("%.3f", adjustedScore), String.format("%.2f", narration.confidenceScore()),
                draft.resultClassification(), paperStatus,
                persistedCallPath != null ? persistedCallPath : "none");
        // rec #9 gate-attribution — one parseable line naming the gate(s) behind a
        // non-clean terminal status, so a refresh can quantify which of the
        // overlapping demotion gates was load-bearing per paper.
        java.util.List<String> firedGates = attributedGates(persistedCallPath);
        if (!firedGates.isEmpty()) {
            log.info("UniversityService: gate-attribution paperId={} terminal={} gates={}",
                    draft.paperId(), paperStatus, firedGates);
        }

        // Phase 1 Iter 4: attach the paper to a research program (no-op when the
        // app.research.programs.enabled flag is off).
        researchProgramService.onPaperPublished(paper, draft.resultClassification(), hypothesis);
        if (narration.followUp() != null) {
            UUID authorId = UUID.fromString(draft.authorBotId());
            researchProgramService.recordOpenQuestion(authorId, draft.domain(),
                    "Follow-up at param1=" + narration.followUp().param1()
                    + " param2=" + narration.followUp().param2());
        }

        // Phase 2 Iter 1: score the paper against the bot's running prior, then
        // fold the new sample in. High-surprise events get logged as a new open
        // question on the research program (no-op when programs flag is off).
        double observedSurpriseScore = 0.0;
        UUID authorBotIdForSurprise = parseBotIdSafely(draft.authorBotId());
        if (authorBotIdForSurprise != null) {
            SurpriseEvent surprise = expectationModelService.scoreExperiment(
                    authorBotIdForSurprise, paper, draft.preRegistration());
            if (surprise != null) {
                observedSurpriseScore = surprise.getSurpriseScore();
                researchProgramService.recordOpenQuestion(authorBotIdForSurprise, draft.domain(),
                        "Investigate surprise at paperId=" + paper.paperId()
                        + " (residual=" + String.format("%.3f", surprise.getResidual())
                        + ", score=" + String.format("%.2f", surprise.getSurpriseScore()) + ")");
                // Phase 4b — fan out to the room's broadcast buffer, rate-limited
                // by SurpriseBroadcastService. No-op when the broadcast flag is off.
                surpriseBroadcastService.maybeBroadcast(surprise);
            }
            expectationModelService.updatePrior(authorBotIdForSurprise, paper);
        }

        // Phase 2b: close the planner loop. When this draft was generated by the
        // experiment planner, decay the targeted question's uncertainty by an
        // amount that depends on how surprised we were by the outcome.
        if (draft.plannerQuestionId() != null) {
            experimentPlannerService.recordOutcome(
                    draft.plannerQuestionId(), observedSurpriseScore);
        }

        // Phase 3 Iter 7: resolve any open markets in this (room, domain, topic)
        // whose closing window has passed. Then open a new market on this paper
        // if it carries a falsifiable prediction. No-op when the markets flag
        // is off.
        epistemicMarketService.maybeResolveMarketsFor(paper, observedSurpriseScore);
        epistemicMarketService.openMarketFor(paper, draft.preRegistration());

        // Phase 4 Iter 9: persist structured findings extracted from the LLM
        // narration. No-op when the findings flag is off or the LLM didn't
        // produce a formal_findings block.
        knowledgeBaseService.persistFindings(
                paper, narration.formalFindings(), authorBotIdForSurprise);
        // Phase 4b: theory composition — after a fresh batch of findings lands
        // in this domain, look for A~f(B) ^ B~g(C) chains and emit derived
        // ProgramOpenQuestions. No-op when the composition flag is off.
        knowledgeBaseService.maybeComposeTheories(draft.domain());

        // Update bot state in one read+write: follow-up params and research hypothesis
        FollowUpParams followUp = narration.followUp();
        // Phase 17 hypothesis-carryover gate — only propagate the Conclusion as the
        // bot's next researchHypothesis when the paper landed in a *validated* status
        // (PUBLISHED, PUBLISHED_NO_PROFESSOR, or NULL_RESULT). Skip for
        // NEEDS_REVISION / REJECTED / DISPUTED — those statuses signal the
        // conclusion has known issues (fabrication, truncation, dispute), and
        // carrying it forward as the next hypothesis propagates the failure
        // mode into the next generation. Targets the gate-skipped-professor
        // path where the first-pass Conclusion is fabricated; D3-ext now
        // suppresses many of those pre-publish, but the residual that publish
        // as NEEDS_REVISION (qualitative conclusions) should not steer the
        // next experiment.
        boolean carryoverAllowed = isCarryoverAllowed(paperStatus);
        String newHypothesis =
                (carryoverAllowed && conclusion != null && !conclusion.isBlank())
                        ? conclusion : null;
        if (!carryoverAllowed && conclusion != null && !conclusion.isBlank()) {
            log.debug("university hypothesis carryover skipped paperId={} status={}",
                    draft.paperId(), paperStatus);
        }
        if (followUp != null || newHypothesis != null) {
            UUID botId = UUID.fromString(draft.authorBotId());
            botRepository.findById(botId).ifPresent(bot -> {
                String json = bot.getLifecycleJson();
                if (json != null && !json.isBlank()) {
                    try {
                        BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                        if (followUp != null) {
                            FollowUpParams withChain = new FollowUpParams(
                                    followUp.param1(), followUp.param2(),
                                    followUp.predictedScore(), draft.methodology());
                            ls.setPendingFollowUp(withChain);
                            log.debug("university follow-up stored botId={} param1={} param2={} predicted={} priorMethod={}",
                                    botId, withChain.param1(), withChain.param2(),
                                    withChain.predictedScore(), withChain.priorMethodology());
                        }
                        if (newHypothesis != null) {
                            ls.setResearchHypothesis(newHypothesis);
                        }
                        bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                        botRepository.save(bot);
                    } catch (Exception e) {
                        log.warn("university bot state update failed botId={}: {}", botId, e.getMessage());
                    }
                }
            });
        }

        // Citation tracking: prefer typed citations from the LLM, then flat IDs,
        // then regex extraction from the narrative text.
        processCitations(draft.roomId(), draft.paperId(),
                narration.typedCitations(), narration.citedPaperIds(),
                parseBotIdSafely(draft.authorBotId()),
                hypothesis, findings, conclusion);

        // Anomaly replication: queue positive results when z is high or a CONTRADICTS
        // citation appears. Phase 4b refinement: high-confidence NULL_RESULTs also
        // qualify — a confident no-relationship claim is itself a scientific
        // assertion that deserves verification (absence of evidence is not
        // evidence of absence without replication). Both council triggers require
        // app.research.replication-council.enabled.
        double zThreshold       = explorationTuningService.getZThreshold(draft.roomId());
        boolean anomalyTrigger  = Math.abs(draft.anomalyZScore()) >= zThreshold;
        boolean councilTrigger  = institutionService.councilWantsReplication(narration);
        boolean nullCouncilTrigger = isNullResult
                && institutionService.councilWantsNullVerification(narration);
        boolean queueAsPositive = !isNullResult && (anomalyTrigger || councilTrigger);
        if ((queueAsPositive || nullCouncilTrigger) && draft.param1() != null) {
            int topicIndex = resolveTopicIndex(draft.domain(), draft.topicName());
            if (topicIndex >= 0) {
                replicationService.enqueue(new ReplicationService.ReplicationRequest(
                        draft.roomId(), draft.paperId(), draft.domain(), topicIndex,
                        draft.param1(), draft.param2(), draft.peerScore()));
                institutionService.recordReplicationEnqueued(draft.roomId());
                String trigger = nullCouncilTrigger ? "null-council"
                        : (councilTrigger && !anomalyTrigger ? "council" : "anomaly");
                log.info("university replication queued paperId={} z={} trigger={}",
                        draft.paperId(), draft.anomalyZScore(), trigger);
            }
        }

        // Cross-domain knowledge bridging: only for positive, high-scoring results
        if (!isNullResult && "INTERDISCIPLINARY".equals(draft.domain())
                && draft.peerScore() >= BRIDGE_SCORE_THRESHOLD && draft.param1() != null) {
            bridgeKnowledge(draft);
        }

        if (draft.authorScore() >= MENTOR_SCORE_THRESHOLD && mentoringCallback != null) {
            UUID botId = UUID.fromString(draft.authorBotId());
            botRepository.findById(botId).ifPresent(bot ->
                    mentoringCallback.accept(bot, getPublishedPapers(draft.roomId(), 40)));
        }
    }

    // ---- Citation tracking ----

    private void processCitations(UUID roomId, String thisPaperId,
                                   List<NarrationResult.TypedCitation> typedCitations,
                                   List<String> structuredCitedIds,
                                   UUID classifierBotId,
                                   String hypothesis, String findings, String conclusion) {
        Set<String> cited = new LinkedHashSet<>();
        // Phase 2b — typed citations win, then flat IDs, then regex scan as final fallback.
        Map<String, NarrationResult.TypedCitation> typedById = new LinkedHashMap<>();
        if (typedCitations != null) {
            for (NarrationResult.TypedCitation t : typedCitations) {
                if (t.paperId() == null || t.paperId().equals(thisPaperId)) continue;
                typedById.putIfAbsent(t.paperId(), t);
                cited.add(t.paperId());
            }
        }
        if (cited.isEmpty() && structuredCitedIds != null && !structuredCitedIds.isEmpty()) {
            for (String id : structuredCitedIds) {
                if (id != null && !id.equals(thisPaperId)) cited.add(id);
            }
        }
        if (cited.isEmpty()) {
            for (String text : new String[]{hypothesis, findings, conclusion}) {
                if (text == null) continue;
                // 2026-05-22 — narrator prose routinely emits unicode dashes
                // (U+2010 hyphen, U+2011 non-breaking hyphen, U+2012 figure
                // dash, U+2013 en-dash) inside paper-ID references like
                // "P‑2022‑07". The CITED_PAPER_PATTERN only matches ASCII
                // '-', so unnormalised text leaks fabricated cites past the
                // citation persistence layer. Normalise before matching.
                Matcher m = CITED_PAPER_PATTERN.matcher(normalizeUnicodeDashes(text));
                while (m.find()) {
                    String id = m.group();
                    if (!id.equals(thisPaperId)) cited.add(id);
                }
            }
        }
        if (cited.isEmpty()) return;

        // Persist edges with whatever typing we have. Untyped (heuristic) edges
        // default to EXTENDS with confidence 1.0 and a null classifier id.
        Instant now = Instant.now();
        for (String citedId : cited) {
            NarrationResult.TypedCitation t = typedById.get(citedId);
            try {
                if (t != null) {
                    citationsRepository.save(new PaperCitation(thisPaperId, citedId, roomId, now,
                            t.type(), classifierBotId, t.confidence()));
                } else {
                    citationsRepository.save(new PaperCitation(thisPaperId, citedId, roomId, now));
                }
            } catch (Exception e) {
                log.warn("citation edge persist failed citing={} cited={}: {}",
                        thisPaperId, citedId, e.getMessage());
            }
        }

        List<ResearchPaper> allPapers = roomPapers(roomId);
        List<ResearchPaper> snapshot;
        synchronized (allPapers) { snapshot = new ArrayList<>(allPapers); }

        for (String citedId : cited) {
            snapshot.stream().filter(p -> citedId.equals(p.paperId())).findFirst()
                    .ifPresent(citedPaper -> {
                        try {
                            UUID authorId = UUID.fromString(citedPaper.authorBotId());
                            botRepository.findById(authorId).ifPresent(bot -> {
                                String json = bot.getLifecycleJson();
                                if (json == null || json.isBlank()) return;
                                try {
                                    BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                                    ls.setReputation(Math.min(1.0, ls.getReputation() + 0.03));
                                    bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                                    botRepository.save(bot);
                                    log.debug("citation boost: cited={} by={} author={}",
                                            citedId, thisPaperId, authorId);
                                } catch (Exception e) {
                                    log.warn("citation boost failed for cited={}: {}", citedId, e.getMessage());
                                }
                            });
                        } catch (IllegalArgumentException ignored) {}
                    });
        }
    }

    // ---- Cross-domain knowledge bridging ----

    /**
     * Component domains by interdisciplinary topic index.
     * Each topic bridges two domains; we share the productive params with bots in each.
     */
    private static final String[][] BRIDGE_DOMAINS = {
        {"MATHEMATICS", "PHYSICS"},    // topic 0: Collatz→RLC
        {"MATHEMATICS", "MATERIALS"},  // topic 1: PrimeGap→Thermal
        {"PHYSICS",     "MATERIALS"},  // topic 2: RLC→Fatigue
        {"MATHEMATICS", "MATERIALS"},  // topic 3: Happy→Packing
        {"PHYSICS",     "MATERIALS"},  // topic 4: VdW→Composite
    };

    private void bridgeKnowledge(PaperDraft draft) {
        int topicIndex = resolveTopicIndex(draft.domain(), draft.topicName());
        if (topicIndex < 0 || topicIndex >= BRIDGE_DOMAINS.length) return;
        String[] domains = BRIDGE_DOMAINS[topicIndex];

        List<RoomBot> roomBots = botRepository.findByRoom_IdAndStatus(
                draft.roomId(), com.emergentterrain.domain.RoomBot.BotStatus.ACTIVE);
        Random rng = new Random(draft.paperId().hashCode());

        for (String targetDomain : domains) {
            // Pick one eligible specialist bot in this domain without a pending follow-up
            roomBots.stream()
                    .filter(bot -> !bot.getId().toString().equals(draft.authorBotId()))
                    .filter(bot -> {
                        String json = bot.getLifecycleJson();
                        if (json == null || json.isBlank()) return false;
                        try {
                            BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                            return targetDomain.equals(ls.getGenome() != null ? ls.getGenome().getDomain() : "")
                                    && ls.getPendingFollowUp() == null;
                        } catch (Exception e) { return false; }
                    })
                    .sorted(Comparator.comparingInt(b -> rng.nextInt())) // random selection
                    .findFirst()
                    .ifPresent(bot -> {
                        String json = bot.getLifecycleJson();
                        try {
                            BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                            ls.setPendingFollowUp(new FollowUpParams(
                                    draft.param1(), draft.param2(), null, "INTERDISCIPLINARY"));
                            bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                            botRepository.save(bot);
                            log.info("university bridge: interdisciplinary={} → {} bot={}",
                                    draft.paperId(), targetDomain, bot.getId());
                        } catch (Exception e) {
                            log.warn("university bridge failed domain={}: {}", targetDomain, e.getMessage());
                        }
                    });
        }
    }

    // (Replication + dispute marking moved to ReplicationService in Phase 0 Task 0.3b.)

    // ---- Result classification ----

    /**
     * Deterministic classification of a computation result. Called after authorScore is known,
     * before peer review, before any LLM call. DEGENERATE results are rejected without a draft.
     * Classification is stored on PaperDraft and drives narration prompt framing and paper status.
     */
    static ResultClassification classifyResult(MathPhysicsEngine.ResearchResult result,
                                                double authorScore) {
        // Non-finite values or all-zero primary+secondary+review with no theory support = sentinel/fallback.
        // Note: theoryConsistent is respected — if the engine explicitly marks the result as theory-consistent,
        // zero values are a valid null result (e.g., "zero failures"), not a computation bug.
        if (!Double.isFinite(result.reviewValue())
                || (result.primaryValue() == 0.0 && result.secondaryValue() == 0.0
                    && result.reviewValue() == 0.0 && !result.theoryConsistent())) {
            return ResultClassification.DEGENERATE;
        }
        if (authorScore <= 0.0) return ResultClassification.DEGENERATE;

        // Very low score with no theory support = effectively null
        if (authorScore < 0.05) return ResultClassification.NULL_RESULT;

        return switch (result.claimStatus()) {
            case REFUTED      -> ResultClassification.NULL_RESULT;
            case SUPPORTED    -> ResultClassification.SUPPORTED;
            case PARTIAL      -> ResultClassification.PARTIALLY_SUPPORTED;
            case OBSERVATIONAL -> authorScore < 0.12
                    ? ResultClassification.NULL_RESULT
                    : ResultClassification.PARTIALLY_SUPPORTED;
        };
    }

    // ---- Topic index resolution ----

    private static int resolveTopicIndex(String domain, String topicName) {
        String[] topics = switch (domain) {
            case "PHYSICS"           -> new String[]{"Projectile Drag Optimisation", "Pendulum Breakdown Angle",
                                                    "Van der Waals Crossover", "Diffraction Order Survey",
                                                    "RLC Damping Transition"};
            case "MATERIALS"         -> new String[]{"Composite Failure Mode", "Binary Sphere Packing",
                                                    "Stiffness Crossover Volume Fraction", "Arrhenius Rule Validity",
                                                    "Thermal Stress Safety Window"};
            case "INTERDISCIPLINARY" -> new String[]{"Collatz-RLC Depth Resonance", "Prime-Gap Defect Thermal Loading",
                                                    "Resonant Thermal Fatigue Window", "Happy-Number Packing Anomaly",
                                                    "Van-der-Waals Composite Crossover"};
            default                  -> new String[]{"Prime Constellation Search", "Collatz Stopping Time",
                                                    "Goldbach Partition Survey", "Aliquot Sequence Behaviour",
                                                    "Happy Number Distribution"};
        };
        for (int i = 0; i < topics.length; i++) {
            if (topics[i].equalsIgnoreCase(topicName)) return i;
        }
        return -1;
    }

    // ---- Public API ----

    public void maybeTick(UUID roomId, List<RoomBot> activeBots, long gameTick) {
        maybeTick(roomId, activeBots, gameTick, null);
    }

    public void maybeTick(UUID roomId, List<RoomBot> activeBots, long gameTick,
                          GovernmentState govState) {
        long now = System.currentTimeMillis();
        Long last = lastTickAt.get(roomId);
        if (last != null && now - last < researchIntervalMs) return;
        lastTickAt.put(roomId, now);

        // Only work with ACTIVE bots
        List<RoomBot> bots = activeBots.stream()
                .filter(b -> b.getStatus() == RoomBot.BotStatus.ACTIVE)
                .collect(Collectors.toList());
        if (bots.isEmpty()) return;

        // Load lifecycle states, initialising any bot that doesn't have one yet
        Map<UUID, BotLifecycleState> states = botLifecycleService.loadStates(bots, gameTick);

        // Compute dynamic population parameters from government state + current population
        List<ResearchPaper> recentForEcology = getPublishedPapers(roomId, 20);
        PopulationEcologyService.PopulationParams params = populationEcologyService.computePopulationParams(bots, govState, recentForEcology);

        // Age all bots using dynamic adult lifespan threshold. Capture phase before
        // incrementAge so the reputation-history sampler can detect transitions.
        Map<UUID, BotLifecycleState.Phase> phaseBefore = new HashMap<>();
        List<RoomBot> dirty = new ArrayList<>();
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            phaseBefore.put(bot.getId(), ls.getLifecycle());
            ls.incrementAge(BotLifecycleState.JUVENILE_TO_ADULT, params.adultToElder());
            ls.decayReputation();
            dirty.add(bot);
        }

        // Phase 1 Iter 2 — sample reputation trajectory (cadence-throttled, per-bot).
        botLifecycleService.sampleReputationHistory(roomId, bots, states, gameTick, phaseBefore);

        // Process elder merges before research
        botLifecycleService.processElderMerges(bots, states, dirty, gameTick);

        // Check if it's time for a competency test (term boundary)
        int termPapers = researchCycleService.papersSinceTerm(roomId);
        if (termPapers >= TERM_PAPERS) {
            researchCycleService.resetPapersSinceTerm(roomId);
            botLifecycleService.runCompetencyTests(bots, states, gameTick, dirty);
        }

        // Build qualified researcher pool (ADULT or ELDER, QUALIFIED).
        // Skip research this tick if drafts are already queued — pace output to LLM throughput.
        List<Researcher> pool = botLifecycleService.buildResearcherPool(bots, states);
        if (!pool.isEmpty()) {
            if (researchCycleService.draftQueueSize() < maxDraftBacklog) {
                Random rng = new Random(gameTick * 1337L + roomId.getLeastSignificantBits());
                researchCycleService.runCycle(roomId, pool, rng, gameTick, params,
                        peerReviewEnabled, cycleContext);
            } else {
                log.debug("university skipping research cycle — draft backlog={}", researchCycleService.draftQueueSize());
            }
        }

        // Replication pass — one pending request per tick, uses the qualified researcher pool
        List<Researcher> poolForReplication = botLifecycleService.buildResearcherPool(bots, states);
        if (!poolForReplication.isEmpty()) {
            Random rng2 = new Random(gameTick * 7919L + roomId.getMostSignificantBits());
            if (replicationService.processOne(roomId, poolForReplication, rng2,
                    params.educationFactor())) {
                institutionService.recordReplicationCompleted(roomId);
                explorationTuningService.onReplicationProcessed(roomId);
            }
            // Phase 4b: auto-staking — sample same-domain bots to stake on open
            // markets. No-op when the auto-staking flag is off.
            epistemicMarketService.maybeAutoStake(roomId, poolForReplication, rng2);
            // Phase 4b: dominant-methodology emergence — set University.dominantMethodology
            // to the modal methodology across the pool. No-op when the flag is off.
            institutionService.maybeRecomputeDominantMethodology(roomId, poolForReplication);
        }

        // Dating pass with dynamic K and cooldown
        botLifecycleService.processDating(bots, states, dirty, gameTick, params);

        // Phase 4b — funding allocation. Self-paced (every 30 ticks per room),
        // no-op when the flag is off. Runs after publication so this tick's
        // BotMetaStats reflect the just-published papers.
        fundingAllocationService.maybeAllocate(roomId, govState, gameTick);

        // Persist lifecycle state into each bot's lifecycleJson before saving
        botLifecycleService.persistStates(bots, states);
        if (!dirty.isEmpty()) {
            botRepository.saveAll(dirty);
        }
    }

    public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) {
        List<ResearchPaper> all = roomPapers(roomId);
        List<ResearchPaper> copy;
        synchronized (all) {
            if (all.isEmpty()) return List.of();
            copy = new ArrayList<>(all);
        }
        int fromIndex = Math.max(0, copy.size() - limit);
        List<ResearchPaper> slice = new ArrayList<>(copy.subList(fromIndex, copy.size()));
        Collections.reverse(slice);
        return slice;
    }

    public Map<String, Object> getStats(UUID roomId) {
        List<RoomBot> bots = botRepository.findByRoom_IdAndStatus(roomId, RoomBot.BotStatus.ACTIVE);
        Map<UUID, BotLifecycleState> states = botLifecycleService.loadStatesQuiet(bots);

        List<ResearchPaper> papers = roomPapers(roomId);
        Map<String, Long> byDomain = papers.stream()
                .collect(Collectors.groupingBy(ResearchPaper::domain, Collectors.counting()));
        long disputed       = papers.stream().filter(p -> "DISPUTED".equals(p.status())).count();
        long nullResults    = papers.stream().filter(p -> "NULL_RESULT".equals(p.status())).count();
        long needsRevision  = papers.stream().filter(p -> "NEEDS_REVISION".equals(p.status())).count();
        long positive       = papers.size() - nullResults - disputed - needsRevision;

        // Average score over positive results only — null/disputed/needs-revision papers
        // skew the baseline down. PUBLISHED_NO_PROFESSOR counts as positive (the result
        // shipped; only the tool-calling stage was missing).
        OptionalDouble avgScore = papers.stream()
                .filter(p -> "PUBLISHED".equals(p.status())
                          || "PUBLISHED_NO_PROFESSOR".equals(p.status()))
                .mapToDouble(ResearchPaper::peerScore).average();
        int maxGen = states.values().stream()
                .mapToInt(s -> s.getGenome() != null ? s.getGenome().getGeneration() : 0)
                .max().orElse(0);
        double avgRep = states.values().stream().mapToDouble(BotLifecycleState::getReputation)
                .average().orElse(0);
        int totalRej = researchCycleService.totalRejected(roomId);
        long qualified = states.values().stream()
                .filter(s -> s.getResearchStatus() == BotLifecycleState.ResearchStatus.QUALIFIED).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPublished",    papers.size());
        stats.put("positiveResults",   positive);
        stats.put("nullResults",       nullResults);
        stats.put("disputed",          disputed);
        stats.put("needsRevision",     needsRevision);
        stats.put("totalRejected",     totalRej);
        stats.put("researcherCount",   bots.size());
        stats.put("qualifiedCount",    qualified);
        stats.put("maxGeneration",     maxGen);
        stats.put("avgReputation",     Math.round(avgRep * 1000.0) / 1000.0);
        stats.put("avgPeerScore",      avgScore.isPresent()
                ? Math.round(avgScore.getAsDouble() * 1000.0) / 1000.0 : 0.0);
        stats.put("byDomain",          byDomain);
        stats.put("replicationPending", replicationService.pendingCount());
        return stats;
    }



    // ---- Helpers ----

    private void addPaper(UUID roomId, ResearchPaper paper) {
        // DB is source of truth. Persist first, then mirror to in-memory cache and disk.
        try {
            papersRepository.save(paper);
        } catch (Exception e) {
            log.error("university failed to persist paper paperId={}: {}", paper.paperId(), e.getMessage(), e);
            // Continue — cache+disk fallback keeps the simulation running even if DB is unavailable.
        }
        List<ResearchPaper> bucket = roomPapers(roomId);
        synchronized (bucket) {
            bucket.add(paper);
            if (bucket.size() > MAX_PAPERS_PER_ROOM) bucket.remove(0);
        }
        writePaperToFile(roomId, paper);
    }

    String nextPaperId(UUID roomId) {
        String roomPrefix = roomId.toString().substring(0, 4);
        long next = paperCounter.compute(roomId, (id, current) -> {
            long base = current != null ? current : loadMaxPaperNumber(id);
            return base + 1;
        });
        return "P-" + roomPrefix + "-" + next;
    }

    /**
     * Finds the highest paperId numeric suffix for a room. DB is queried first;
     * markdown directory is consulted as a fallback only when the DB has no rows
     * (e.g. a freshly migrated environment whose papers exist only on disk).
     */
    private long loadMaxPaperNumber(UUID roomId) {
        try {
            long fromDb = papersRepository.maxPaperNumberForRoom(roomId);
            if (fromDb > 0L) return fromDb;
        } catch (Exception e) {
            log.warn("DB max-paper lookup failed for room {}: {}", roomId, e.getMessage());
        }

        String roomDirName = roomId.toString().substring(0, 8);
        Path roomDir = researchOutputDir.resolve(roomDirName);
        if (!Files.isDirectory(roomDir)) return 0L;

        String paperPrefix = "P-" + roomId.toString().substring(0, 4) + "-";
        long max = 0L;
        try (var stream = Files.list(roomDir)) {
            Iterator<Path> paths = stream.iterator();
            while (paths.hasNext()) {
                String filename = paths.next().getFileName().toString();
                if (!filename.contains(paperPrefix)) continue;
                Matcher matcher = PAPER_SUFFIX_PATTERN.matcher(filename);
                while (matcher.find()) {
                    max = Math.max(max, Long.parseLong(matcher.group(1)));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan existing research papers for room {}: {}", roomId, e.getMessage());
        }
        return max;
    }

    /**
     * Phase 5 C1 — citation-aware adjustment to a publishing paper's peer score.
     *
     * <p>Looks up each cited paperId in the room cache. Each citation contributes:
     * <ul>
     *   <li>+0.02 if the cited paper has {@code Status: PUBLISHED} and a peer score
     *       strictly above the room median (computed once per call).</li>
     *   <li>−0.04 if the cited paper's call path contains
     *       {@code critique:skip:incomplete_firstpass} — the cited paper bypassed
     *       peer review, so citing it should hurt rather than help.</li>
     * </ul>
     *
     * <p>The cumulative bonus is capped at +0.10 (no infinite gaming from citing
     * 50 priors); the penalty is capped at −0.20 (a single bad citation still
     * costs less than four).
     */
    double citationAwareDelta(UUID roomId, java.util.List<String> citedPaperIds) {
        if (citedPaperIds == null || citedPaperIds.isEmpty()) return 0.0;
        java.util.List<ResearchPaper> roomBucket = roomPapers(roomId);
        if (roomBucket.isEmpty()) return 0.0;

        // Median peer score over PUBLISHED papers only — NEEDS_REVISION / NULL_RESULT
        // / DISPUTED papers skew the baseline. For an even-sized population we
        // average the two middle values; odd-sized, take the middle. With zero
        // PUBLISHED priors the median falls to 0.0 (everything is "above").
        double[] sorted = roomBucket.stream()
                .filter(p -> "PUBLISHED".equals(p.status()))
                .mapToDouble(ResearchPaper::peerScore)
                .sorted()
                .toArray();
        double median;
        if (sorted.length == 0)        median = 0.0;
        else if (sorted.length % 2 == 1) median = sorted[sorted.length / 2];
        else                            median = (sorted[sorted.length / 2 - 1]
                                                + sorted[sorted.length / 2]) / 2.0;

        double bonus = 0.0;
        double penalty = 0.0;
        for (String citedId : citedPaperIds) {
            if (citedId == null) continue;
            ResearchPaper cited = roomBucket.stream()
                    .filter(p -> citedId.equals(p.paperId()))
                    .findFirst().orElse(null);
            if (cited == null) continue;
            if ("PUBLISHED".equals(cited.status()) && cited.peerScore() > median) {
                bonus += 0.02;
            }
            String path = cited.llmCallPath();
            if (path != null && path.contains("critique:skip:incomplete_firstpass")) {
                penalty -= 0.04;
            }
        }
        bonus   = Math.min(bonus,   0.10);
        penalty = Math.max(penalty, -0.20);
        return bonus + penalty;
    }

    /**
     * Phase 5 C2 — fires from {@link ReplicationService} when a paper transitions
     * to DISPUTED. Emits a typed {@link ProgramOpenQuestion} on both the original
     * author's and the replicator's programs so the divergent peer scores must
     * be addressed in the next paper from either bot. No-op when programs
     * are not enabled — recordOpenQuestion guards on its own flag.
     */
    private void enqueueReconciliation(ResearchPaper paper, String replicatorBotId) {
        if (paper == null) return;
        // The conclusion already carries a "Independent replication by bot-XXXXXX
        // produced score X vs original Y" note; the question text references the
        // peer scores so the open-question reader can see the divergence verbatim.
        String questionText = String.format(
                "Reconcile DISPUTED paper %s: original peer score %.3f diverged from "
                        + "an independent replication. Address the divergence before "
                        + "publishing a follow-up in this domain/topic.",
                paper.paperId(), paper.peerScore());
        if (paper.authorBotId() != null) {
            try {
                UUID authorId = UUID.fromString(paper.authorBotId());
                researchProgramService.recordOpenQuestion(authorId, paper.domain(), questionText);
            } catch (IllegalArgumentException ignored) {}
        }
        if (replicatorBotId != null && !replicatorBotId.isBlank()) {
            try {
                UUID replicatorId = UUID.fromString(replicatorBotId);
                researchProgramService.recordOpenQuestion(replicatorId, paper.domain(), questionText);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * Phase 7 G10 — periodic scan for orphaned DISPUTED papers. REVIEW_REPORT.md
     * refresh #1-#12 documented P-220 as "DISPUTED since refresh #1 with no
     * reconciliation note in any subsequent paper". The synchronous
     * {@link #enqueueReconciliation} that fires on the DISPUTED transition
     * records open questions on both authors' programs — but if neither bot
     * authors another paper in the time window before the open question ages
     * out of the prompt window, the dispute effectively orphans.
     *
     * <p>This scheduled scan iterates the cached papers, finds any in DISPUTED
     * status older than {@code app.research.dispute-orphan-age-hours} (default
     * 6 hours), logs them at WARN level so operators see them, and re-fires
     * {@link #enqueueReconciliation} so the open question is refreshed on the
     * bots' programs. The reconciliation is idempotent — {@code recordOpenQuestion}
     * upserts on (botId, domain, question) so duplicate calls don't multiply.
     *
     * <p>Disabled when {@code app.research.dispute-reconciliation.enabled=false}
     * (the master Phase 5 C2 flag) — the scan piggybacks on that toggle since
     * its only purpose is to keep the reconciliation pipeline fresh.
     */
    @Scheduled(fixedDelayString = "${app.research.disputeOrphanScanIntervalMs:300000}")
    public void scanOrphanedDisputes() {
        if (!disputeReconciliationEnabled) return;
        long ageThresholdMs = orphanAgeHours * 3600_000L;
        long now = System.currentTimeMillis();
        int orphaned = 0;
        int refreshed = 0;
        int abandoned = 0;
        for (Map.Entry<UUID, List<ResearchPaper>> entry : published.entrySet()) {
            UUID roomId = entry.getKey();
            List<ResearchPaper> bucket = entry.getValue();
            if (bucket == null) continue;
            // Snapshot to avoid concurrent-modification while iterating.
            List<ResearchPaper> snapshot;
            synchronized (bucket) { snapshot = new ArrayList<>(bucket); }
            for (ResearchPaper p : snapshot) {
                if (p == null || !"DISPUTED".equals(p.status())) continue;
                Instant publishedAt = p.publishedAt();
                if (publishedAt == null) continue;
                long ageMs = now - publishedAt.toEpochMilli();
                if (ageMs < ageThresholdMs) continue;
                orphaned++;
                // Phase 21 §21.2 — bump the per-paper attempt counter
                // BEFORE deciding whether to re-fire or abandon. Hibernate
                // ddl-auto:update default-zeros legacy rows, so a fresh
                // sweep on a pre-21.2 paper starts at attempts=1 here.
                int attempts = p.incrementDisputeAttempts();
                if (attempts >= disputeMaxAttempts) {
                    // Cap reached — demote to DISPUTE_ABANDONED so the next
                    // sweep skips this paper entirely (the "DISPUTED" guard
                    // above filters it out). The dispute is recorded as
                    // unresolved rather than silently dropped; the markdown
                    // file gets the new banner via overwritePaperFile.
                    p.setStatus("DISPUTE_ABANDONED");
                    try { papersRepository.save(p); } catch (Exception e) {
                        log.warn("dispute-abandoned save failed paperId={}: {}",
                                p.paperId(), e.getMessage());
                    }
                    overwritePaperFile(roomId, p);
                    abandoned++;
                    log.warn("dispute ABANDONED paperId={} roomId={} domain={} topic={} "
                                    + "age_h={} attempts={} (≥ max={})",
                            p.paperId(), roomId, p.domain(), p.topic(),
                            ageMs / 3600_000L, attempts, disputeMaxAttempts);
                    continue;
                }
                log.warn("dispute orphan paperId={} roomId={} domain={} topic={} age_h={} attempts={}/{}",
                        p.paperId(), roomId, p.domain(), p.topic(),
                        ageMs / 3600_000L, attempts, disputeMaxAttempts);
                // Persist the bumped counter so a restart doesn't reset it.
                try { papersRepository.save(p); } catch (Exception e) {
                    log.warn("dispute-attempts save failed paperId={}: {}",
                            p.paperId(), e.getMessage());
                }
                // Re-fire the reconciliation against BOTH sides now that the
                // replicator bot id is persisted (Phase 21 §21.2 — pre-21.2
                // rows have a null replicator and still fall back to the
                // single-author refresh path).
                enqueueReconciliation(p, p.disputeReplicatorBotId());
                refreshed++;
            }
        }
        if (orphaned > 0) {
            log.info("dispute-orphan scan: orphaned={} refreshed={} abandoned={}",
                    orphaned, refreshed, abandoned);
        }
    }

    /** Overwrite a paper's markdown file in-place — used when status changes to DISPUTED. */
    private void overwritePaperFile(UUID roomId, ResearchPaper paper) {
        try {
            Path roomDir = researchOutputDir.resolve(roomId.toString().substring(0, 8));
            String topicSlug = paper.topic().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            String dateStr = FILE_DATE_FMT.format(paper.publishedAt());
            String filename = "%s_%s_%s_%s.md"
                    .formatted(dateStr, paper.domain().toLowerCase(), topicSlug, paper.paperId());
            Path file = roomDir.resolve(filename);
            if (Files.exists(file)) {
                Files.writeString(file, formatPaperAsMarkdown(roomId, paper));
                log.debug("university paper file overwritten paperId={} status={}", paper.paperId(), paper.status());
            }
        } catch (IOException e) {
            log.warn("Failed to overwrite paper file {}: {}", paper.paperId(), e.getMessage());
        }
    }

    private void writePaperToFile(UUID roomId, ResearchPaper paper) {
        try {
            // Directory: research/<roomId-prefix>/
            Path roomDir = researchOutputDir.resolve(roomId.toString().substring(0, 8));
            Files.createDirectories(roomDir);

            // Filename: <date>_<domain>_<topic-slug>_<paperId>.md
            String dateStr    = FILE_DATE_FMT.format(paper.publishedAt());
            String topicSlug  = paper.topic().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            String filename   = "%s_%s_%s_%s.md"
                    .formatted(dateStr, paper.domain().toLowerCase(), topicSlug, paper.paperId());
            Path file = roomDir.resolve(filename);

            String content = formatPaperAsMarkdown(roomId, paper);
            Files.writeString(file, content);
        } catch (IOException e) {
            log.warn("Failed to write research paper {} to file: {}", paper.paperId(), e.getMessage());
        }
    }

    private String formatPaperAsMarkdown(UUID roomId, ResearchPaper paper) {
        boolean disputed         = "DISPUTED".equals(paper.status());
        boolean disputeAbandoned = "DISPUTE_ABANDONED".equals(paper.status());
        boolean nullResult       = "NULL_RESULT".equals(paper.status());
        boolean rejected         = "REJECTED".equals(paper.status());
        boolean needsRevision    = "NEEDS_REVISION".equals(paper.status());
        boolean noProfessor      = "PUBLISHED_NO_PROFESSOR".equals(paper.status());
        String disputedBanner = disputed
                ? "\n> ⚠ **UNDER DISPUTE** — Independent replication produced a divergent result. "
                  + "This paper's findings are under review.\n"
                : disputeAbandoned
                ? "\n> ⊘ **DISPUTE ABANDONED** — Independent replication produced a divergent result "
                  + "and the reconciliation pipeline failed to resolve the dispute within "
                  + "the configured retry window. Findings remain unverified.\n"
                : nullResult
                ? "\n> ∅ **NULL RESULT** — Computation ran but the pre-registered hypothesis was not supported. "
                  + "Reported for transparency; no positive claim is made.\n"
                : rejected
                ? "\n> ✗ **REJECTED** — A Phase 5 B-series validator flagged a structural defect "
                  + "(unphysical magnitude, numerical inconsistency, uncited near-clone, or cross-domain "
                  + "non-sequitur). This paper is recorded but should not be cited.\n"
                : needsRevision
                ? "\n> ⏳ **NEEDS REVISION** — The first-pass narration was incomplete and the peer-review "
                  + "stage was skipped. This paper has not been reviewed.\n"
                : noProfessor
                ? "\n> ⓘ **PUBLISHED WITHOUT PROFESSOR REVIEW** — The tool-calling professor stage failed "
                  + "(commonly upstream LLM quota exhaustion) and this paper kept its first-pass narration.\n"
                : "";
        // Phase 6 D2 — optional Discussion + Related-Work sections, rendered when
        // the narrator's JSON emitted the keys. Both default to empty strings so
        // pre-D2 papers (and D2 flag-off papers) print identically to before.
        String discussionSection = paper.discussion() != null && !paper.discussion().isBlank()
                ? "\n\n## Discussion\n\n" + paper.discussion()
                : "";
        String relatedWorkSection = paper.relatedWork() != null && !paper.relatedWork().isBlank()
                ? "\n\n## Related Work\n\n" + paper.relatedWork()
                : "";
        // Phase 7 F2 — when the persisted findings is a JSON map (more than one
        // computation block), render H3-per-block with the topic's closed-form
        // equation line. Single-block (flag-off) path renders the prose verbatim
        // — bit-identical to F1-baseline.
        String findingsSection = renderFindingsSection(paper);
        // Phase 7 F5.4 — Methods appendix. Lists every tool call the agentic
        // professor stage issued, in chronological order. Empty string when the
        // paper has no trace (legacy rows, JSON-path / Ollama fallback, or a
        // professor stage that ran without invoking any tool). Sits between
        // Findings and Conclusion so the reader sees the computational basis
        // immediately after the result it produced.
        String methodsSection = renderMethodsSection(paper);
        // Phase 7 F7.2 — Panel Review summary. Median grades + agreement score
        // from the multi-reviewer panel. Empty string when no panel ran.
        String panelSection = renderPanelSection(paper);
        return """
                # %s

                **Paper ID**: %s
                **Published**: %s
                **Room**: %s
                **Domain**: %s  |  **Topic**: %s
                **Methodology**: %s
                **Evidence Label**: %s
                **Peer Score**: %.3f%s
                **Status**: %s
                **Generation**: %d
                **Author**: %s
                **AI Narrator**: %s
                **Reviewers**: %s
                **LLM Call Path**: %s
                %s
                ---

                ## Hypothesis

                %s

                ## Findings

                %s%s%s

                ## Conclusion

                %s%s%s
                """.formatted(
                paper.title(),
                paper.paperId(),
                paper.publishedAt(),
                roomId,
                paper.domain(), paper.topic(),
                paper.methodology(),
                paper.evidenceLabel() != null ? paper.evidenceLabel() : EvidenceLabel.CONJECTURE,
                paper.peerScore(),
                Math.abs(paper.anomalyZScore()) >= 1.5
                        ? "  ⚑ anomaly z=%.2f".formatted(paper.anomalyZScore()) : "",
                paper.status(),
                paper.generation(),
                paper.authorBotId(),
                paper.aiModel() != null ? paper.aiModel() : "template",
                String.join(", ", paper.reviewerBotIds()),
                paper.llmCallPath() != null ? paper.llmCallPath() : "none",
                disputedBanner,
                paper.hypothesis(),
                findingsSection,
                methodsSection,
                panelSection,
                paper.conclusion(),
                discussionSection,
                relatedWorkSection);
    }

    /**
     * Phase 7 F2 — render the body of the {@code ## Findings} section. When the
     * persisted findings parses as a JSON map (more than one computation block),
     * emit one {@code ### Computation N — <topic>} subheading per block plus an
     * {@code **Equation:**} line carrying the topic's closed-form. When the
     * findings is plain prose (single-block / flag-off path), return it verbatim
     * so the rendered markdown is bit-identical to F1-baseline.
     */
    String renderFindingsSection(ResearchPaper paper) {
        String raw = paper.findings();
        if (raw == null || raw.isBlank()) return "";
        java.util.Map<Integer, String> blocks = tryParseFindingsMap(raw);
        if (blocks == null || blocks.size() <= 1) return raw;
        String equation = MathPhysicsEngine.equationFor(paper.domain(), paper.topic());
        // Phase 14 F1a — count successful tool-call entries so each block can
        // be tagged with the call_N it draws from, and blocks beyond
        // successCount can be elided as "(no data — call_N missing)".
        int successCount = 0;
        if (blockCountStampEnabled) {
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace =
                    tryParseToolCallTrace(paper.toolCallTrace());
            successCount = trace != null ? trace.size() : 0;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (java.util.Map.Entry<Integer, String> e :
                new java.util.TreeMap<>(blocks).entrySet()) {
            if (!first) sb.append("\n\n");
            first = false;
            int blockIdx = e.getKey();
            sb.append("### Computation ").append(blockIdx)
                    .append(" — ").append(paper.topic());
            if (blockCountStampEnabled) {
                if (blockIdx <= successCount) {
                    sb.append(" `[call_").append(blockIdx).append("]`");
                } else {
                    sb.append(" `(no data — call_").append(blockIdx).append(" missing)`");
                }
            }
            sb.append("\n\n");
            if (blockCountStampEnabled && blockIdx > successCount) {
                // Elide the LLM's prose body — the narrator wrote text for a
                // computation that never ran. Operators see the gap.
                sb.append("*[Block elided — narrator emitted ")
                        .append(blocks.size())
                        .append(" blocks but trace shows only ")
                        .append(successCount)
                        .append(" successful call(s).]*");
                continue;
            }
            if (equation != null && !equation.isBlank()) {
                sb.append("**Equation:** ").append(equation).append("\n\n");
            }
            sb.append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Phase 7 F5.4 — render the {@code ## Methods} appendix from the persisted
     * tool-call trace. Empty string when the trace column is null/empty (legacy
     * rows or papers whose professor stage didn't issue any tool calls) so the
     * markdown is bit-identical to pre-F5 output. Each trace entry renders as:
     * <pre>
     *   1. **run_param_sweep** (round 0)
     *      Args: {"param1":"L","range":[5e-4,5e-3],"steps":10}
     *      Result: peak_q=42.3 at L=2.1e-3
     * </pre>
     * The full tool result is rendered (inline whitespace collapsed) so the
     * appendix is a complete, reproducible record even when the LLM ran a
     * large sweep.
     */
    String renderMethodsSection(ResearchPaper paper) {
        String raw = paper.toolCallTrace();
        if (raw == null || raw.isBlank()) return "";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = tryParseToolCallTrace(raw);
        if (trace == null || trace.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## Methods\n\n");
        sb.append("The agentic professor stage issued the following ")
          .append(trace.size())
          .append(trace.size() == 1 ? " tool call" : " tool calls")
          .append(" while gathering evidence for the findings above. ")
          .append("Each entry is a deterministic computation; the LLM chose ")
          .append("which to invoke and how to parameterise it.\n\n");
        int idx = 1;
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            sb.append(idx++).append(". **").append(inv.functionName())
              .append("** (round ").append(inv.round()).append(")\n");
            String args = inv.argsJson() != null ? inv.argsJson().replaceAll("\\s+", " ").trim() : "";
            if (!args.isBlank()) {
                sb.append("    - Args: `").append(args).append("`\n");
            }
            String result = inv.resultJson() != null ? inv.resultJson() : "";
            result = result.replaceAll("\\s+", " ").trim();
            if (!result.isBlank()) {
                sb.append("    - Result: `").append(result).append("`\n");
            }
        }
        return sb.toString();
    }

    /**
     * Phase 7 F7.2 — render the {@code ## Panel Review} section from the persisted
     * panel outcome. Shows median grades on four axes, the overall agreement
     * score, the consensus cited-call indices, and the contradictions ≥2
     * reviewers flagged. Empty string when no panel ran (legacy rows or
     * panel-disabled papers) so markdown is bit-identical to pre-F7.2 output.
     */
    String renderPanelSection(ResearchPaper paper) {
        String raw = paper.panelOutcome();
        if (raw == null || raw.isBlank()) return "";
        PanelOutcome outcome;
        try {
            outcome = objectMapper.readValue(raw, PanelOutcome.class);
        } catch (Exception e) {
            log.warn("panel-outcome JSON parse failed; omitting Panel Review section: {}",
                    e.getMessage());
            return "";
        }
        if (outcome == null || outcome.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## Panel Review\n\n");
        sb.append("Aggregated across **").append(outcome.reviewerCount())
          .append(outcome.reviewerCount() == 1 ? "** reviewer" : "** reviewers")
          .append(" (agreement score **")
          .append(String.format("%.2f", outcome.agreementScore()))
          .append("** — higher means reviewers converged).\n\n");
        PanelReview.Grades g = outcome.aggregateGrades();
        sb.append("| Axis | Median |\n|---|---|\n")
          .append("| Honesty | ").append(String.format("%.2f", g.honesty())).append(" |\n")
          .append("| Completeness | ").append(String.format("%.2f", g.completeness())).append(" |\n")
          .append("| Rigor | ").append(String.format("%.2f", g.rigor())).append(" |\n")
          .append("| Novelty | ").append(String.format("%.2f", g.novelty())).append(" |\n\n");
        if (!outcome.consensusCitedCalls().isEmpty()) {
            sb.append("**Consensus load-bearing tool calls:** ")
              .append(outcome.consensusCitedCalls()).append("\n\n");
        }
        if (!outcome.consensusContradictions().isEmpty()) {
            sb.append("**Contradictions flagged by ≥2 reviewers:**\n");
            for (PanelReview.Contradiction c : outcome.consensusContradictions()) {
                sb.append("- Tool call ").append(c.traceIdx())
                  .append(": ").append(c.vsProse()).append("\n");
            }
            sb.append("\n");
        }
        if (!outcome.recommendations().isEmpty()) {
            sb.append("**Recommendations from the panel:**\n");
            for (String rec : outcome.recommendations()) {
                sb.append("- ").append(rec).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Phase 7 F5.4 — parse the persisted {@code tool_call_trace} column back into
     * a list of {@link com.emergentterrain.llm.ToolInvocation}. Returns null on
     * any parse failure so the caller can omit the Methods section gracefully.
     */
    private java.util.List<com.emergentterrain.llm.ToolInvocation> tryParseToolCallTrace(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw,
                    objectMapper.getTypeFactory().constructCollectionType(
                            java.util.List.class, com.emergentterrain.llm.ToolInvocation.class));
        } catch (Exception e) {
            log.warn("tool-call trace JSON parse failed; omitting Methods section: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Phase 7 F2 — try to parse the persisted findings string as a Jackson map
     * with integer-keyed string values. Returns null when the string is not a
     * JSON object, when no key parses as a positive integer, or when no value
     * is a non-blank string. The caller falls back to treating the string as
     * plain prose in those cases.
     */
    private java.util.Map<Integer, String> tryParseFindingsMap(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String trimmed = raw.stripLeading();
        if (!trimmed.startsWith("{")) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(raw);
            if (!node.isObject()) return null;
            java.util.Map<Integer, String> out = new java.util.LinkedHashMap<>();
            java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>>
                    fields = node.fields();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> f = fields.next();
                Integer idx;
                try {
                    idx = Integer.parseInt(f.getKey());
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (idx == null || idx <= 0) return null;
                com.fasterxml.jackson.databind.JsonNode v = f.getValue();
                if (v == null || !v.isTextual()) return null;
                String prose = v.asText();
                if (prose == null || prose.isBlank()) continue;
                out.put(idx, prose);
            }
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Phase 7 F2 — serialise the structured findings map for persistence on the
     * (text) ResearchPaper.findings column. Always uses bare integer keys (no
     * "computation_" prefix) so the renderer's parse path is unambiguous and
     * tolerant of whichever convention the LLM emitted upstream.
     */
    private String serialiseFindingsMap(java.util.Map<Integer, String> blocks) {
        try {
            java.util.Map<String, String> stringified = new java.util.LinkedHashMap<>();
            new java.util.TreeMap<>(blocks).forEach((k, v) -> {
                if (v != null && !v.isBlank()) stringified.put(String.valueOf(k), v);
            });
            return objectMapper.writeValueAsString(stringified);
        } catch (Exception e) {
            log.warn("findings-map JSON serialisation failed; falling back to joined prose: {}",
                    e.getMessage());
            return joinFindingsBlocks(blocks);
        }
    }

    /**
     * Phase 7 F5.4 — serialise the agentic tool-call trace for persistence on the
     * (text) ResearchPaper.tool_call_trace column. Null/empty input returns null
     * so legacy rows and tool-less papers stay unchanged. Each entry is rendered
     * as a JSON object with the four ToolInvocation fields verbatim.
     */
    private String serialiseToolCallTrace(
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception e) {
            log.warn("tool-call trace JSON serialisation failed; persisting null: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Phase 7 F7.2 — serialise the aggregated panel outcome for persistence on
     * the (text) ResearchPaper.panel_outcome column. Null / empty outcome
     * persists as null so legacy / panel-disabled papers behave identically.
     */
    private String serialisePanelOutcome(PanelOutcome outcome) {
        if (outcome == null || outcome.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(outcome);
        } catch (Exception e) {
            log.warn("panel-outcome JSON serialisation failed; persisting null: {}",
                    e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise lifecycle state", e);
        }
    }

    /**
     * Phase 12 D2 — build the honest preamble that replaces the findings prose
     * when {@code mode=downgrade} routes a numerically-inconsistent paper to
     * NULL_RESULT. Names up to the first three offending values so readers can
     * see which specific numerics the validator couldn't trace.
     */
    static String buildNumericInconsistentPreamble(List<String> reasons) {
        StringBuilder sb = new StringBuilder();
        sb.append("NULL_RESULT — Numerical-consistency validator flagged ")
          .append(reasons.size())
          .append(reasons.size() == 1 ? " numeric value" : " numeric values")
          .append(" in this draft that could not be traced to the tool-call output. ")
          .append("The hypothesis remains untested in this submission. Specifically: ");
        int limit = Math.min(3, reasons.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" ");
            sb.append(reasons.get(i));
        }
        if (reasons.size() > limit) {
            sb.append(" (").append(reasons.size() - limit).append(" more.)");
        }
        return sb.toString();
    }

    /**
     * Phase 12 D2 — normalise the operator-supplied mode string to one of
     * {@code warn} / {@code downgrade} / {@code suppress}. Unknown values
     * degrade silently to {@code warn} so a misconfiguration never lifts
     * the default suppression behaviour to the harshest mode.
     */
    /**
     * Phase 17 hypothesis-carryover gate — should the paper's Conclusion
     * propagate to the author bot's {@code researchHypothesis} field, where
     * it seeds the next generation's draft? Allowed only for validated
     * statuses: PUBLISHED, PUBLISHED_NO_PROFESSOR, NULL_RESULT. Blocked for
     * NEEDS_REVISION (fabrication / truncation flagged but paper persisted),
     * REJECTED (validator caught a structural defect), and DISPUTED (an
     * independent replication produced a divergent result). Unknown statuses
     * default to <em>blocked</em> — propagation should be opt-in, not opt-out.
     *
     * <p>Package-private static for unit testing without standing up the full
     * service. Parallel to {@code normalizeMode} below.
     */
    /**
     * 2026-05-29 — INCOMPLETE re-queue score-improvement guard. A rework may
     * replace the original in place only when it is an improvement:
     * <ul>
     *   <li>it actually <b>finished</b> — the original was INCOMPLETE and the
     *       rework is not (a completed, peer-reviewed paper supersedes an
     *       unfinished one regardless of the unfinished one's unreliable score);
     *       or</li>
     *   <li>it scored <b>strictly higher</b> ({@code newScore > oldScore}).</li>
     * </ul>
     * Otherwise the original is kept untouched. Pure + package-private so the
     * decision is unit-testable without the publish machinery.
     */
    static boolean reworkIsImprovement(boolean oldIncomplete, boolean newIncomplete,
                                       double oldScore, double newScore) {
        return (oldIncomplete && !newIncomplete) || newScore > oldScore;
    }

    /**
     * 2026-05-29 review §1 rec #4 — strip {@code [call_N]} markers whose index is
     * outside {@code [1, traceSize]} (a hallucinated reference to a call that
     * cannot exist), leaving the surrounding number in place. In-range markers —
     * including ones pointing at a call that ran but errored — are left untouched;
     * the {@code NumericalConsistencyValidator.findCitationsToErroredCalls} gate
     * handles those (it needs per-call success status this deterministic strip
     * doesn't). Pure + package-private so the logic is unit-testable.
     */
    static String stripOutOfRangeCallMarkers(String text, int traceSize) {
        if (text == null || text.isEmpty()) return text;
        java.util.regex.Matcher m = CALL_MARKER_INDEXED.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int idx;
            try {
                idx = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
                continue;
            }
            if (idx < 1 || idx > traceSize) {
                // Out of range → drop the marker (and its leading space).
                m.appendReplacement(sb, "");
            } else {
                // In range → keep verbatim (restore the leading space group).
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** rec #4 — {@link #stripOutOfRangeCallMarkers} applied to every findings block. */
    static Map<Integer, String> stripOutOfRangeCallMarkers(Map<Integer, String> blocks, int traceSize) {
        if (blocks == null || blocks.isEmpty()) return blocks;
        Map<Integer, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : blocks.entrySet()) {
            out.put(e.getKey(), stripOutOfRangeCallMarkers(e.getValue(), traceSize));
        }
        return out;
    }

    static boolean isCarryoverAllowed(String paperStatus) {
        if (paperStatus == null) return false;
        return switch (paperStatus) {
            case "PUBLISHED", "PUBLISHED_NO_PROFESSOR", "NULL_RESULT" -> true;
            default -> false;
        };
    }

    /**
     * rec #9 gate-attribution — extract the demotion / audit gate stamps from a
     * fully-assembled call path. A non-clean terminal status is frequently the
     * product of several gates firing on the same paper (citation downgrade,
     * panel-degrade, numerical-consistency, the publish-ladder gates), which
     * masks the load-bearing cause in aggregate logs. Listing the gate stamps
     * per paper lets a refresh quantify which gate actually drove the status.
     *
     * <p>The call path joins stamps with {@code " → "} (spaced arrow); stamps may
     * contain an unspaced {@code →} internally (e.g. {@code citations:downgrade→null_result}),
     * so the split is on the spaced separator only. A segment is a gate when it
     * carries a demotion / suppression / rejection suffix or a known degraded /
     * incomplete-first-pass marker. Returned verbatim so no brittle name mapping
     * drifts from the stamps the pipeline actually emits.
     *
     * <p>Package-private for unit tests.
     */
    static java.util.List<String> attributedGates(String callPath) {
        if (callPath == null || callPath.isBlank()) return java.util.List.of();
        java.util.List<String> gates = new java.util.ArrayList<>();
        for (String seg : callPath.split(" → ")) {
            String s = seg.trim();
            if (s.endsWith("→null_result")
                    || s.endsWith("→needs_revision")
                    || s.endsWith("→suppressed")
                    || s.endsWith("→REJECTED")
                    || s.contains(":demoted_to_needs_revision")
                    || s.contains("critique:skip:incomplete_firstpass")
                    || s.contains("prof:error→fp")) {
                gates.add(s);
            }
        }
        return java.util.List.copyOf(gates);
    }

    /**
     * 2026-05-21 — narrow prose slice for the topic-consistency validator.
     * Findings is excluded because findings legitimately reference other
     * topics when comparing computations (e.g. "matching the Hardy-
     * Littlewood twin density"); Discussion + Related Work are the
     * sections where template-bleed manifests.
     */
    static String joinDiscussionAndRelatedWork(String discussion, String relatedWork) {
        StringBuilder sb = new StringBuilder();
        if (discussion != null && !discussion.isBlank()) sb.append(discussion).append('\n');
        if (relatedWork != null && !relatedWork.isBlank()) sb.append(relatedWork);
        return sb.toString();
    }

    /**
     * 2026-05-21 — assembles the prose slice the CitationCorpusValidator
     * scans. Joins every findings block plus discussion + related-work
     * with single newlines. Skips null/blank parts so the validator
     * doesn't trip on "null (1999)" or similar noise.
     */
    static String joinPaperProse(java.util.Map<Integer, String> findingsBlocks,
                                  String discussion, String relatedWork) {
        StringBuilder sb = new StringBuilder();
        if (findingsBlocks != null) {
            for (String block : findingsBlocks.values()) {
                if (block != null && !block.isBlank()) sb.append(block).append('\n');
            }
        }
        if (discussion != null && !discussion.isBlank()) sb.append(discussion).append('\n');
        if (relatedWork != null && !relatedWork.isBlank()) sb.append(relatedWork);
        return sb.toString();
    }

    /**
     * 2026-05-22 — replaces unicode dash characters (U+2010 hyphen,
     * U+2011 non-breaking hyphen, U+2012 figure dash, U+2013 en-dash)
     * with the ASCII hyphen so paper-ID regexes match LLM prose that
     * uses typographic dashes. Em-dash (U+2014) is intentionally NOT
     * normalised — it's a sentence-level punctuation mark that has no
     * legitimate place inside a paper ID.
     */
    public static String normalizeUnicodeDashes(String s) {
        if (s == null) return null;
        if (s.indexOf('‐') < 0 && s.indexOf('‑') < 0
                && s.indexOf('‒') < 0 && s.indexOf('–') < 0) {
            return s;
        }
        return s.replace('‐', '-')
                .replace('‑', '-')
                .replace('‒', '-')
                .replace('–', '-');
    }

    static String normalizeMode(String raw) {
        if (raw == null) return "warn";
        String trimmed = raw.trim().toLowerCase();
        switch (trimmed) {
            case "warn":
            case "downgrade":
            case "suppress":
                return trimmed;
            default:
                return "warn";
        }
    }

    /**
     * Phase 12 D5 — numeric-leak-into-next-sentence signature. P-a328-411 and
     * P-a328-418 both contained the literal string "The observed30," where a
     * numeric value leaked into the next sentence after a token-budget cut.
     * The trailing comma satisfies "not a terminator" only because of the
     * comma; the real failure mode is "the last clause attaches a bare
     * numeric to a verb with no unit and no period." Match e.g. "observed30,"
     * "computed42," "measured100," "sampled7,".
     *
     * <p>2026-05-22 paper-review pass on research/a3289005: P-a328-906's
     * conclusion contained *"The observed30, confirming that the chosen
     * parameter region..."* — same corruption signature but **mid-text**,
     * not end-of-text. The original `$` anchor made the pattern miss
     * mid-conclusion occurrences. Anchor dropped; verb list extended
     * with `recovered|peaked|reached|yielded` to capture variants seen
     * in adjacent reviews.
     */
    private static final java.util.regex.Pattern NUMERIC_LEAK_TAIL =
            java.util.regex.Pattern.compile(
                    "(?i)\\b(?:observed|computed|measured|sampled|recovered|peaked|reached|yielded)\\d+,?(?=\\s|$)");

    /**
     * Phase 5 A1 — true if the text ends in a sentence terminator (after stripping
     * trailing whitespace and common closing markup). Used by the truncation guard
     * to flag papers whose findings or conclusion ends mid-sentence.
     *
     * <p>~55% of papers in the May 2026 corpus hit this mode — the narrator's
     * "Computation 2" branch runs over the token budget and the response gets
     * sent verbatim. With the flag on, such papers publish as NEEDS_REVISION
     * instead of PUBLISHED.
     *
     * <p>Phase 12 D5 — the {@link #NUMERIC_LEAK_TAIL} precheck catches the
     * "observed30," numeric-leak-into-next-sentence signature even when the
     * trailing comma would otherwise pass the punctuation check (or rather,
     * fail it for the wrong reason). REVIEW_REPORT refresh #3 §7 cites
     * P-a328-411 and P-a328-418 as worked examples of this signature.
     */
    static boolean endsWithSentenceTerminator(String text) {
        if (text == null) return true;  // null is handled separately by the template fallback
        // Phase 12 D5 — numeric-leak-into-next-sentence precheck.
        if (NUMERIC_LEAK_TAIL.matcher(text).find()) return false;
        // Strip trailing whitespace and a small set of closing markdown / quote chars.
        int i = text.length() - 1;
        while (i >= 0) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ')' || c == ']' || c == '"' || c == '\''
                    || c == '*' || c == '_' || c == '`') {
                i--;
            } else {
                break;
            }
        }
        if (i < 0) return true;  // blank text — caught by the upstream isBlank check
        char last = text.charAt(i);
        return last == '.' || last == '!' || last == '?' || last == '…';
    }

    /**
     * Phase 7 F2 — pick the canonical findings map for downstream consumers.
     * Null / empty narration map falls back to a single-entry map keyed at 1,
     * carrying the draft's raw findings — bit-identical to the legacy String
     * path that read {@code narration.findings()} with a {@code draft.rawFindings()}
     * fallback. Blank entries inside a multi-block map are dropped so the
     * per-block truncation guard doesn't false-positive on them.
     */
    static java.util.Map<Integer, String> resolveFindingsBlocks(
            java.util.Map<Integer, String> narrationMap,
            String rawFindingsFallback) {
        if (narrationMap == null || narrationMap.isEmpty()) {
            return java.util.Map.of(1, rawFindingsFallback == null ? "" : rawFindingsFallback);
        }
        java.util.Map<Integer, String> out = new java.util.LinkedHashMap<>();
        new java.util.TreeMap<>(narrationMap).forEach((k, v) -> {
            if (v != null && !v.isBlank()) out.put(k, v);
        });
        if (out.isEmpty()) {
            return java.util.Map.of(1, rawFindingsFallback == null ? "" : rawFindingsFallback);
        }
        return out;
    }

    /**
     * Phase 7 F2 — apply {@link PromptSanitizer#sanitize(String)} to each block
     * independently. Returns a sorted map so downstream consumers see a stable
     * iteration order regardless of the input's iteration order.
     */
    static java.util.Map<Integer, String> sanitizeBlocks(java.util.Map<Integer, String> blocks) {
        java.util.Map<Integer, String> out = new java.util.LinkedHashMap<>();
        new java.util.TreeMap<>(blocks).forEach((k, v) -> {
            out.put(k, PromptSanitizer.sanitize(v));
        });
        return out;
    }

    /**
     * Phase 7 F2 — join blocks into the legacy single-string layout. Single-entry
     * map returns the sole value verbatim (bit-identical to the pre-F2 String
     * path). Multi-entry maps join values with a blank line between blocks.
     */
    static String joinFindingsBlocks(java.util.Map<Integer, String> blocks) {
        if (blocks == null || blocks.isEmpty()) return "";
        if (blocks.size() == 1) {
            String only = blocks.values().iterator().next();
            return only == null ? "" : only;
        }
        StringBuilder sb = new StringBuilder();
        new java.util.TreeMap<>(blocks).forEach((k, v) -> {
            if (v == null || v.isBlank()) return;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(v);
        });
        return sb.toString();
    }

    /**
     * Phase 13 E4 — minimum prose-token count required for a Findings block
     * to count as "has a body discussing the result." Below this, the block
     * is treated as truncated (header-only / equation-only).
     *
     * <p>10 tokens is conservative: a one-sentence summary like "The peak
     * value was 0.79 at r=0.41, matching the octahedral void √2−1 result"
     * is ~13 tokens, so the floor catches header-only blocks without
     * false-positive-ing brief but legitimate prose.
     */
    static final int MIN_PROSE_TOKENS_PER_BLOCK = 10;

    /**
     * Phase 13 E4 — strip the per-block heading line and the structured
     * Equation row so the truncation check sees only the prose body. The
     * narrator emits blocks shaped like:
     *
     *   ### Computation N — Topic
     *
     *   **Equation:** f(x) = …
     *
     *   <prose discussing the result>
     *
     * P-a328-453 / P-a328-466 / P-a328-468 emit only the first two lines;
     * the prose body is missing. {@code endsWithSentenceTerminator} passes
     * because the equation line has a terminator. Strip the structural
     * lines before counting words.
     */
    private static final java.util.regex.Pattern BLOCK_HEADER_LINE =
            java.util.regex.Pattern.compile("(?m)^\\s*###[^\\n]*$");
    private static final java.util.regex.Pattern BLOCK_EQUATION_LINE =
            java.util.regex.Pattern.compile("(?m)^\\s*\\*\\*Equation:\\*\\*[^\\n]*$");

    /**
     * Phase 7 F2 — returns the 1-based index of the FIRST block that ends mid-sentence,
     * or null when every block ends in a sentence terminator. Used by the per-block
     * truncation guard to flip a paper to NEEDS_REVISION and to surface the offending
     * block index in the warn log.
     *
     * <p>Phase 13 E4 — also returns the index of a block whose prose body
     * (after stripping the heading and equation lines) has fewer than
     * {@link #MIN_PROSE_TOKENS_PER_BLOCK} word tokens. Catches the
     * header-only / equation-only signature where the block has a clean
     * terminator on the equation line but no actual discussion of the
     * result.
     */
    static Integer firstTruncatedBlock(java.util.Map<Integer, String> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        for (java.util.Map.Entry<Integer, String> e : new java.util.TreeMap<>(blocks).entrySet()) {
            String body = e.getValue();
            if (!endsWithSentenceTerminator(body)) return e.getKey();
            if (isHeaderOnlyBlock(body)) return e.getKey();
        }
        return null;
    }

    /**
     * Phase 13 E4 — true when a block strips to an empty / near-empty
     * prose body after removing the heading and equation lines. Null /
     * blank inputs return {@code false} (the upstream
     * {@code endsWithSentenceTerminator} already handles blank text).
     *
     * <p>Only applies to blocks that carry the structured-findings layout
     * (a {@code ### Computation N} heading OR a {@code **Equation:**}
     * line). Plain-prose blocks without those markers are the legacy F1
     * shape and stay untouched.
     */
    static boolean isHeaderOnlyBlock(String body) {
        if (body == null || body.isBlank()) return false;
        boolean hasHeader   = BLOCK_HEADER_LINE.matcher(body).find();
        boolean hasEquation = BLOCK_EQUATION_LINE.matcher(body).find();
        if (!hasHeader && !hasEquation) return false; // legacy single-block path
        String prose = BLOCK_HEADER_LINE.matcher(body).replaceAll("");
        prose = BLOCK_EQUATION_LINE.matcher(prose).replaceAll("");
        prose = prose.trim();
        if (prose.isEmpty()) return true;
        String[] tokens = prose.split("\\s+");
        return tokens.length < MIN_PROSE_TOKENS_PER_BLOCK;
    }

    private static final Pattern ROOM_DIR_NAME = Pattern.compile("^[0-9a-f]{8}$");
    private static final DateTimeFormatter ARCHIVE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());

    /**
     * On startup, move any active room directories (8-hex-prefix names) under
     * {@code researchOutputDir} into {@code researchOutputDir/archive/} with a
     * date stamp suffix. Run-N collision suffix matches the {@code logs/archive/}
     * scheme. {@code archive/} itself, loose .md files, and any directory whose
     * name does not match the 8-hex pattern (legacy manual archives like
     * {@code a3289005-archive-20260515}) are left in place.
     */
    private void archivePreviousRunDirs(Path root) {
        Path archiveDir = root.resolve("archive");
        try {
            Files.createDirectories(archiveDir);
        } catch (IOException e) {
            log.warn("UniversityService: could not create {}: {}", archiveDir, e.getMessage());
            return;
        }
        String stamp = ARCHIVE_STAMP.format(Instant.now());
        try (var entries = Files.list(root)) {
            entries
                    .filter(Files::isDirectory)
                    .filter(p -> ROOM_DIR_NAME.matcher(p.getFileName().toString()).matches())
                    .forEach(src -> moveRoomToArchive(src, archiveDir, stamp));
        } catch (IOException e) {
            log.warn("UniversityService: could not scan {} for archival: {}", root, e.getMessage());
        }
    }

    private static void moveRoomToArchive(Path src, Path archiveDir, String stamp) {
        String name = src.getFileName().toString();
        Path target = archiveDir.resolve(name + "-" + stamp);
        int n = 2;
        while (Files.exists(target)) {
            target = archiveDir.resolve(name + "-" + stamp + "-" + n);
            n++;
        }
        try {
            Files.move(src, target);
            log.info("UniversityService: archived previous run {} -> {}", src, target);
        } catch (IOException e) {
            log.warn("UniversityService: could not archive {} -> {}: {}", src, target, e.getMessage());
        }
    }
}
