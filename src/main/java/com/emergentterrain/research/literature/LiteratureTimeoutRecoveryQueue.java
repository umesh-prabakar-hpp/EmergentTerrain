package com.emergentterrain.research.literature;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.ReworkDraftSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Durable recovery lane for bounded Phase 35 retrieval timeouts.
 *
 * <p>A timed-out local lookup is neither a no-match nor evidence.  The
 * originating paper is therefore held before publication, with its exact
 * semantic search request and a serialization-safe draft snapshot journaled
 * under {@code simulation-history/research-literature-recovery/}.  The worker
 * may consult OpenAlex only to discover possible open-access backfill targets;
 * those records are expressly non-evidentiary and are never exposed as an
 * {@code HF...} key or citation.  After an operator completes an offline index
 * backfill/import, a changed index fingerprint releases the draft through the
 * ordinary narration and local {@code search_literature → read_reference}
 * pipeline.
 *
 * <p>This deliberately does not mutate the SQLite index or fetch source text
 * on a paper request.  That preserves Phase 35's read-only query contract and
 * the publication requirement that sources are issued and read locally.
 */
@Component
public final class LiteratureTimeoutRecoveryQueue {

    private static final Logger log = LoggerFactory.getLogger(LiteratureTimeoutRecoveryQueue.class);
    private static final String TIMEOUT_MARKER = "phase 34 sqlite retrieval exceeded";

    enum State {
        AWAITING_DISCOVERY,
        AWAITING_INDEX_BACKFILL,
        RETRY_DISPATCHED,
        OPENALEX_UNAVAILABLE,
        COMPLETED
    }

    record OpenAlexCandidate(String workId, String title, int publicationYear, String openAccessUrl) {}

    record Job(String paperId, ReworkDraftSnapshot draft, String claim,
               List<String> subjectAnchors, String claimedRelation, String relevanceRationale,
               String indexFingerprint, State state, int discoveryAttempts,
               String createdAt, String updatedAt, List<OpenAlexCandidate> candidates,
               String lastError) {
        Job {
            subjectAnchors = subjectAnchors == null ? List.of() : List.copyOf(subjectAnchors);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            state = state == null ? State.AWAITING_DISCOVERY : state;
            createdAt = createdAt == null ? Instant.now().toString() : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
            claim = claim == null ? "" : claim;
            claimedRelation = claimedRelation == null ? "" : claimedRelation;
            relevanceRationale = relevanceRationale == null ? "" : relevanceRationale;
            indexFingerprint = indexFingerprint == null ? "unavailable" : indexFingerprint;
            lastError = lastError == null ? "" : lastError;
        }

        Job with(State next, int attempts, List<OpenAlexCandidate> discovered, String error) {
            return new Job(paperId, draft, claim, subjectAnchors, claimedRelation, relevanceRationale,
                    indexFingerprint, next, attempts, createdAt, Instant.now().toString(), discovered, error);
        }
    }

    private final boolean enabled;
    private final int maxDiscoveryAttempts;
    private final int maxQueueSize;
    private final Path stateDir;
    private final ObjectMapper objectMapper;
    private final OpenAlexClient openAlex;
    private final Phase34SqliteLiterature localIndex;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private volatile Consumer<PaperDraft> retryDispatch;

    public LiteratureTimeoutRecoveryQueue(
            ObjectMapper objectMapper,
            @Value("${app.research.literature.timeout-recovery.enabled:false}") boolean enabled,
            @Value("${app.research.literature.timeout-recovery.state-dir:simulation-history/research-literature-recovery}") String stateDir,
            @Value("${app.research.literature.timeout-recovery.max-discovery-attempts:3}") int maxDiscoveryAttempts,
            @Value("${app.research.literature.timeout-recovery.max-queue-size:100}") int maxQueueSize,
            @Autowired(required = false) OpenAlexClient openAlex,
            @Autowired(required = false) Phase34SqliteLiterature localIndex) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.stateDir = Path.of(stateDir).toAbsolutePath();
        this.maxDiscoveryAttempts = Math.max(1, maxDiscoveryAttempts);
        this.maxQueueSize = Math.max(1, maxQueueSize);
        this.openAlex = openAlex;
        this.localIndex = localIndex;
    }

    @PostConstruct
    void load() {
        if (!enabled) return;
        try {
            Files.createDirectories(stateDir);
            try (var files = Files.list(stateDir)) {
                files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted().forEach(path -> {
                            try {
                                Job job = objectMapper.readValue(Files.readString(path), Job.class);
                                if (job.paperId() != null && job.state() != State.COMPLETED) {
                                    // A process can stop after dispatching but before the
                                    // narration callback settles. Re-arm that durable job;
                                    // its recorded pre-backfill fingerprint still prevents a
                                    // retry against the unchanged index generation.
                                    if (job.state() == State.RETRY_DISPATCHED) {
                                        job = job.with(State.AWAITING_INDEX_BACKFILL,
                                                job.discoveryAttempts(), job.candidates(),
                                                "process restarted while retry was in flight");
                                        persist(job);
                                    }
                                    jobs.put(job.paperId(), job);
                                }
                            } catch (Exception error) {
                                log.warn("literature timeout recovery: ignoring unreadable job {}: {}",
                                        path, error.toString());
                            }
                        });
            }
            log.info("literature timeout recovery: enabled={} restoredJobs={} openAlexWired={} localIndexWired={}",
                    enabled, jobs.size(), openAlex != null, localIndex != null);
        } catch (IOException error) {
            log.error("literature timeout recovery: cannot initialize state directory {}: {}",
                    stateDir, error.toString());
        }
    }

    /** Wires the normal narration entry point after both services are constructed. */
    public void wireRetryDispatch(Consumer<PaperDraft> retryDispatch) {
        this.retryDispatch = retryDispatch;
    }

    /**
     * Journal and hold a draft iff its selected trace contains a Phase 35
     * timeout. Returning true authorizes the caller to suppress publication.
     */
    public synchronized boolean deferIfTimedOut(PaperDraft draft, NarrationResult narration) {
        if (!enabled || draft == null || draft.paperId() == null || narration == null) return false;
        TimeoutRequest request = timeoutRequest(narration.toolCallTrace());
        if (request == null || request.claim().isBlank()) return false;
        if (!Files.isDirectory(stateDir) && !ensureStateDirectory()) return false;
        Job prior = jobs.get(draft.paperId());
        if (prior == null && jobs.size() >= maxQueueSize) {
            log.warn("literature timeout recovery: queue at capacity {} — paperId={} will use the normal "
                            + "non-evidentiary timeout path",
                    maxQueueSize, draft.paperId());
            return false;
        }
        Job job = new Job(draft.paperId(), ReworkDraftSnapshot.from(draft), request.claim(),
                request.subjectAnchors(), request.claimedRelation(), request.relevanceRationale(),
                indexFingerprint(), State.AWAITING_DISCOVERY,
                prior == null ? 0 : prior.discoveryAttempts(),
                prior == null ? Instant.now().toString() : prior.createdAt(), Instant.now().toString(),
                prior == null ? List.of() : prior.candidates(), "");
        if (!persist(job)) return false;
        jobs.put(job.paperId(), job);
        log.info("literature timeout recovery: deferred paperId={} claim='{}' anchors={}",
                job.paperId(), abbreviate(job.claim()), job.subjectAnchors());
        return true;
    }

    /** Mark a released draft terminal once its next normal narration settles. */
    public synchronized void markCompleted(String paperId) {
        Job prior = jobs.get(paperId);
        if (prior == null || prior.state() != State.RETRY_DISPATCHED) return;
        Job completed = prior.with(State.COMPLETED, prior.discoveryAttempts(), prior.candidates(), "");
        if (persist(completed)) {
            jobs.remove(paperId);
            log.info("literature timeout recovery: completed paperId={}", paperId);
        }
    }

    /** Re-arm a released job when narration was suppressed before it could settle normally. */
    public synchronized void retryDidNotSettle(String paperId) {
        Job prior = jobs.get(paperId);
        if (prior == null || prior.state() != State.RETRY_DISPATCHED) return;
        update(prior.with(State.AWAITING_INDEX_BACKFILL, prior.discoveryAttempts(), prior.candidates(),
                "re-dispatched narration was suppressed"));
    }

    @Scheduled(fixedDelayString = "${app.research.literature.timeout-recovery.poll-interval-ms:60000}",
            initialDelay = 60_000L)
    public void tick() {
        if (!enabled) return;
        Job job;
        synchronized (this) {
            job = jobs.values().stream()
                    .filter(candidate -> candidate.state() == State.AWAITING_DISCOVERY
                            || candidate.state() == State.AWAITING_INDEX_BACKFILL)
                    .min(Comparator.comparing(Job::createdAt))
                    .orElse(null);
        }
        if (job == null) return;
        if (job.state() == State.AWAITING_DISCOVERY) {
            discover(job);
        } else {
            releaseWhenIndexChanges(job);
        }
    }

    private void discover(Job job) {
        if (openAlex == null) {
            update(job.with(State.OPENALEX_UNAVAILABLE, job.discoveryAttempts(), job.candidates(),
                    "OpenAlex client is not configured"));
            return;
        }
        if (job.discoveryAttempts() >= maxDiscoveryAttempts) {
            update(job.with(State.OPENALEX_UNAVAILABLE, job.discoveryAttempts(), job.candidates(),
                    "OpenAlex discovery attempts exhausted"));
            return;
        }
        try {
            JsonNode response = openAlex.searchWorks(job.claim(), Map.of(
                    "per-page", "5",
                    "select", "id,title,publication_year,open_access,best_oa_location"));
            List<OpenAlexCandidate> candidates = candidates(response);
            update(job.with(State.AWAITING_INDEX_BACKFILL, job.discoveryAttempts() + 1, candidates, ""));
            log.info("literature timeout recovery: OpenAlex discovery paperId={} candidates={} "
                            + "(non-evidentiary; awaiting offline index backfill)",
                    job.paperId(), candidates.size());
        } catch (RuntimeException error) {
            int attempts = job.discoveryAttempts() + 1;
            State next = attempts >= maxDiscoveryAttempts ? State.OPENALEX_UNAVAILABLE : State.AWAITING_DISCOVERY;
            update(job.with(next, attempts, job.candidates(), abbreviate(error.getMessage())));
            log.warn("literature timeout recovery: OpenAlex discovery failed paperId={} attempt={}/{}: {}",
                    job.paperId(), attempts, maxDiscoveryAttempts, error.toString());
        }
    }

    private void releaseWhenIndexChanges(Job job) {
        Consumer<PaperDraft> dispatch = retryDispatch;
        if (dispatch == null) return;
        String current = indexFingerprint();
        if (current.equals(job.indexFingerprint()) || "unavailable".equals(current)) return;
        Job dispatched = job.with(State.RETRY_DISPATCHED, job.discoveryAttempts(), job.candidates(), "");
        if (!update(dispatched)) return;
        try {
            dispatch.accept(dispatched.draft().toDraft());
            log.info("literature timeout recovery: index changed; re-dispatched paperId={} for normal local retrieval",
                    dispatched.paperId());
        } catch (RuntimeException error) {
            update(dispatched.with(State.AWAITING_INDEX_BACKFILL, dispatched.discoveryAttempts(),
                    dispatched.candidates(), abbreviate(error.getMessage())));
            log.warn("literature timeout recovery: retry dispatch failed paperId={}: {}",
                    dispatched.paperId(), error.toString());
        }
    }

    private synchronized boolean update(Job job) {
        if (!persist(job)) return false;
        if (job.state() == State.COMPLETED) jobs.remove(job.paperId());
        else jobs.put(job.paperId(), job);
        return true;
    }

    private boolean persist(Job job) {
        try {
            Files.createDirectories(stateDir);
            Path target = stateDir.resolve(safeFileName(job.paperId()) + ".json");
            Path temporary = stateDir.resolve(safeFileName(job.paperId()) + ".json.tmp");
            Files.writeString(temporary, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(job));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception error) {
            log.error("literature timeout recovery: could not persist paperId={}: {}",
                    job.paperId(), error.toString());
            return false;
        }
    }

    private boolean ensureStateDirectory() {
        try {
            Files.createDirectories(stateDir);
            return true;
        } catch (IOException error) {
            log.error("literature timeout recovery: cannot create {}: {}", stateDir, error.toString());
            return false;
        }
    }

    private String indexFingerprint() {
        return localIndex == null ? "unavailable" : localIndex.indexFingerprint();
    }

    private static TimeoutRequest timeoutRequest(List<ToolInvocation> trace) {
        if (trace == null) return null;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"search_literature".equals(invocation.functionName())
                    || invocation.resultJson() == null
                    || !invocation.resultJson().toLowerCase(java.util.Locale.ROOT).contains(TIMEOUT_MARKER)) {
                continue;
            }
            try {
                JsonNode args = new ObjectMapper().readTree(invocation.argsJson());
                String claim = args.path("claim").asText("").trim();
                List<String> anchors = new ArrayList<>();
                args.path("subject_anchors").forEach(node -> anchors.add(node.asText("")));
                return new TimeoutRequest(claim, anchors, args.path("claimed_relation").asText(""),
                        args.path("relevance_rationale").asText(""));
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<OpenAlexCandidate> candidates(JsonNode response) {
        List<OpenAlexCandidate> candidates = new ArrayList<>();
        if (response == null) return candidates;
        for (JsonNode work : response.path("results")) {
            String id = shortWorkId(work.path("id").asText(""));
            if (id.isBlank()) continue;
            String url = work.path("best_oa_location").path("pdf_url").asText("");
            if (url.isBlank()) url = work.path("best_oa_location").path("landing_page_url").asText("");
            candidates.add(new OpenAlexCandidate(id, work.path("title").asText(""),
                    work.path("publication_year").asInt(0), url));
        }
        return List.copyOf(candidates);
    }

    private static String shortWorkId(String id) {
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static String safeFileName(String paperId) {
        return paperId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 400 ? value : value.substring(0, 400) + "…";
    }

    private record TimeoutRequest(String claim, List<String> subjectAnchors,
                                  String claimedRelation, String relevanceRationale) {}

    // Package-private seam for focused tests.
    static boolean containsTimeout(List<ToolInvocation> trace) {
        return timeoutRequest(trace) != null;
    }

    int queuedJobs() {
        return jobs.size();
    }

    Job job(String paperId) {
        return jobs.get(paperId);
    }
}
