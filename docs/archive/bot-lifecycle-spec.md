# Bot Lifecycle Specification

A unified model for bot identity, democratic participation, research qualification,
reproduction, aging, and AI-assisted genome evolution.

---

## Core Principle

Every bot is a first-class citizen with equal democratic rights. Research capability
is earned through competency, not assigned by birth. Population is self-regulating
through reproduction and age-driven merging.

---

## Architecture Shift

### Current state (problem)

`Researcher` is a parallel in-memory object that shadows `RoomBot` by ID. They are
separate populations. Genome lives only in `Researcher`, not in the bot itself. The
connection is a string prefix match — fragile and lossy.

### New model

The genome, lifecycle state, and competency history move into `RoomBot.stateJson`.
`Researcher` becomes a **role** — a subset of bots that currently hold researcher
status — not a separate class with its own population. `UniversityService` queries
which bots are qualified rather than maintaining a shadow list.

```
RoomBot (persisted, JPA entity)
  └─ stateJson  →  BotLifecycleState (serialised JSON)
       ├─ genome: BotGenome
       ├─ age: long              (game ticks alive)
       ├─ lifecycle: LifecyclePhase
       ├─ researchStatus: ResearchStatus
       ├─ partnerId: UUID        (set during dating / merge negotiation)
       └─ competencyHistory: List<CompetencyRecord>
```

`Researcher` class is kept but refactored to a thin wrapper around `BotGenome` used
only inside `MathPhysicsEngine` calls. It no longer maintains its own population list.

---

## 1. Equal Voting Rights

All bots vote with weight 1.0. No research score, reputation, or lifecycle phase
changes vote weight. Research rewards affect capability (genome) and game resources,
never political power.

**Impact on existing code**: Remove the 1.2× vote weight proposal from the previous
design doc. Ensure `GovernmentSimulationService` applies uniform weight to all bot
votes. No code change is needed if the weight modifier was never implemented.

---

## 2. Genome Model — `BotGenome`

```java
public record BotGenome(
    String   domain,           // MATHEMATICS | PHYSICS | MATERIALS
    String   methodology,      // THEORETICAL | COMPUTATIONAL | EMPIRICAL
    double[] topicWeights,     // [5] — search strategy parameters
    double   explorationRate,  // mutation rate in search
    int      generation        // how many crossover/AI-edit cycles applied
) {
    // Clamp all weights to [0.05, 1.0] on construction
}
```

Stored as JSON inside `RoomBot.stateJson`. Survives server restarts. When a bot
has no genome yet (legacy bots), one is initialised from a seeded random using the
bot's UUID as seed on first access.

---

## 3. Competency Tests — Dynamic Researcher Selection

### What changes

The fixed `POPULATION_SIZE = 12` constant is removed. Instead, a **competency test**
runs on a rolling basis. Any bot that passes is an active researcher. The test
changes each term so bots must earn qualification repeatedly.

### Test structure

```
Term length:     every TERM_PAPERS = 30 published papers (room-scoped counter)
Test type:       one puzzle type, selected randomly at term start, same for all bots
Test instance:   parameters derived from (roomId XOR termIndex) — changes each term
Test budget:     fixed at 60% of the full-puzzle iteration budget (harder than normal)
Pass threshold:  score >= 0.60
```

At each term boundary:
1. All bots in the room take the same test puzzle simultaneously (computed in one
   `maybeTick` call, no extra wall-clock time — results are deterministic from genome).
2. Bots that score ≥ 0.60 earn `ResearchStatus.QUALIFIED` for the next term.
3. Bots below 0.60 become `ResearchStatus.UNQUALIFIED` — they continue playing the
   game normally but are not selected as authors or reviewers until next term.
4. The test puzzle and threshold are broadcast as a game event so players can see
   which bots qualified and what the term's research focus is.

### Consequence

Bots with better genomes naturally pass more often. Evolution pressure compounds:
qualified bots publish → AI genome reward makes them better → they pass next term too.
Bots with poor genomes cycle in and out of qualification, creating natural variation
in the research population size.

---

## 4. Bot Lifecycle Phases

```
JUVENILE  →  ADULT  →  ELDER  →  (MERGING)  →  removed
                ↕
           (DATING)  →  child bot spawned
```

### Age thresholds (game ticks)

| Phase | Tick range | Behaviour |
|---|---|---|
| JUVENILE | 0 – 99 | Can vote; cannot date or take competency test |
| ADULT | 100 – 799 | Full rights; can date; takes competency tests |
| ELDER | 800+ | Full rights; enters merge queue |

### Lifecycle state transitions

Stored as `LifecyclePhase` enum in `BotLifecycleState`. Updated each `maybeTick`.

---

## 5. Dating and Child Spawning

### Trigger

Any two ADULT bots in the same room can date. Dating is initiated by `UniversityService`
opportunistically — not triggered by players. Conditions:

- Both bots are ADULT
- Room bot count < `BOT_POPULATION_CAP` (default: 40)
- Neither bot has dated in the last 200 ticks (`lastDatedAt` field)
- Probability per tick pair: `explorationRate_A * explorationRate_B * 0.05`
  (genome curiosity drives social behaviour — high-exploration bots date more)

### Child genome

```
crossoverPoint = rng.nextInt(4) + 1        // 1–4 within topicWeights[5]
childWeights   = parentA[0..point] + parentB[point..5]
childDomain    = fitterParent.domain       // domain from higher-fitness parent
childExploration = (A.explorationRate + B.explorationRate) / 2.0
childGeneration  = max(A.generation, B.generation) + 1
```

Identical crossover to the existing `Researcher.crossover()` method.

### Child bot creation

A new `RoomBot` is inserted into the database with:
- New UUID
- `type` = "CHILD"
- `lifecycle` = JUVENILE
- Genome from crossover above
- Age = 0, reputation = 0.1
- Position = midpoint of both parents' coordinates

Both parents survive. Their `lastDatedAt` is updated.

### Resource cap

If `currentBotCount >= BOT_POPULATION_CAP`, dating is suspended until merging brings
the count below cap. This creates natural population pressure cycles.

---

## 6. Aging and Merging

### Trigger

When a bot reaches ELDER phase (age ≥ 800 ticks), it enters the merge queue. The
merge queue is processed each `maybeTick`:

1. Find all ELDER bots in the room.
2. Pair them by genome similarity (lowest Euclidean distance between `topicWeights`
   arrays — similar bots merge, preserving diversity by not pairing opposites).
3. For each pair: create one merged child bot, remove both parents from the database.

### Merged genome

```
mergedWeights[i]  = (A.topicWeights[i] + B.topicWeights[i]) / 2.0
mergedDomain      = fitterParent.domain
mergedMethodology = randomly chosen from {A.methodology, B.methodology}
mergedExploration = min(A.explorationRate, B.explorationRate)  // conserve; merging is cautious
mergedGeneration  = max(A.generation, B.generation) + 1
mergedReputation  = (A.reputation + B.reputation) / 2.0
```

### Why averaging (not crossover) for merging

Crossover produces a child with one parent's segment and the other's. Merging two
ELDER bots that have spent 800+ ticks refining their genomes should produce a
synthesis, not a split. Averaging preserves what both converged toward.

### Bot removal

Both parent `RoomBot` entities are deleted from `room_bots` table. Their in-flight
publications remain in the publications ring buffer (papers don't disappear with their author).

---

## 7. AI-Assisted Genome Editing

### Trigger

When a researcher publishes a paper with `peerScore >= 0.80`, the author bot is
eligible for an AI genome consultation. One consultation is allowed per bot per
`AI_EDIT_COOLDOWN_TICKS = 200` ticks (prevents runaway improvement).

### Prompt structure

```
You are advising a research bot in a simulation.
The bot's genome controls its research strategy.

Current genome:
  Domain:        {domain}
  Methodology:   {methodology}
  Topic weights: [{w0:.3f}, {w1:.3f}, {w2:.3f}, {w3:.3f}, {w4:.3f}]
    (w0=step_size, w1=restart_prob, w2=sample_budget, w3=depth_bias, w4=tolerance)
  Exploration rate: {explorationRate:.3f}
  Generation: {generation}
  Publications: {publishedCount}, Rejections: {rejectedCount}
  Reputation: {reputation:.3f}

The bot just published a high-scoring paper in domain {domain}, topic "{topicName}".
Paper score: {peerScore:.3f}

Suggest ONE small genome adjustment (one gene, delta <= 0.15) that would help this
bot publish more reliably in its domain. Reply in this exact format:
GENE: <w0|w1|w2|w3|w4|explorationRate>
DELTA: <number between -0.15 and +0.15>
REASON: <one sentence>
```

### Response parsing

```java
// Extract from LLM response:
String gene  = extract("GENE:", response);     // e.g. "w2"
double delta = parseDouble(extract("DELTA:", response));
delta = Math.max(-0.15, Math.min(0.15, delta));  // hard clamp regardless of LLM output

// Apply:
switch (gene) {
    case "w0" -> genome.topicWeights()[0] += delta;
    case "w1" -> genome.topicWeights()[1] += delta;
    case "w2" -> genome.topicWeights()[2] += delta;
    case "w3" -> genome.topicWeights()[3] += delta;
    case "w4" -> genome.topicWeights()[4] += delta;
    case "explorationRate" -> genome = genome.withExplorationRate(genome.explorationRate() + delta);
}
// Clamp all weights to [0.05, 1.0]
// Increment generation
// Persist updated genome to RoomBot.stateJson
```

### Visibility

The AI edit is broadcast as a game event: `"Bot {botId} received AI genome guidance: {REASON}"`.
This appears in the government commentary area as a news item — the research reward is
visible to players.

### Why one gene, small delta

The LLM cannot see the actual puzzle code. Limiting it to one gene and ±0.15 prevents
hallucinated large jumps. The bot's own evolutionary history through crossover and
mutation accumulates most of the learning; the AI nudge is a targeted refinement, not
a rewrite. Multiple publications over time compound many small edits.

---

## Population Dynamics Summary

```
New room opens
  └─ initial bots created (JUVENILE phase, random genomes)

Every 15 seconds (maybeTick):
  ├─ Age all bots by 1 tick
  ├─ JUVENILE → ADULT at tick 100
  ├─ ADULT → ELDER at tick 800
  ├─ Process merge queue (ELDER pairs → new merged bot)
  ├─ Check dating opportunities (ADULT pairs → new child if under cap)
  ├─ At term boundary (every 30 papers): run competency test on all bots
  ├─ Run one research cycle (author selected from QUALIFIED ADULT/ELDER bots)
  └─ On high-score publication: schedule AI genome consultation via LlmService
```

---

## New Fields Required on `BotLifecycleState` (stored in `RoomBot.stateJson`)

```java
public record BotLifecycleState(
    BotGenome         genome,
    long              ageTicks,
    LifecyclePhase    lifecycle,           // JUVENILE | ADULT | ELDER
    ResearchStatus    researchStatus,      // UNQUALIFIED | QUALIFIED
    double            lastCompetencyScore,
    long              lastCompetencyTick,
    UUID              partnerId,           // null unless actively dating/merging
    long              lastDatedAt,
    long              lastAiEditAt,
    int               publishedCount,
    int               rejectedCount,
    double            reputation
) {}
```

---

## 8. Soft Deactivation (No Deletion)

Bots are never deleted from the database. A bot that ends its active life is
**retired** by setting a status flag. This avoids foreign key breakage on votes,
proposals, civic state, and published papers — all of which reference the bot ID
and remain meaningful historical records.

### New field on `RoomBot`

```java
@Column(nullable = false)
@Enumerated(EnumType.STRING)
private BotStatus status = BotStatus.ACTIVE;

public enum BotStatus {
    ACTIVE,    // participates in all game loops
    RETIRED    // no longer active; record preserved for history
}
```

### Game loop filter

Every query that builds the active bot list adds `WHERE status = 'ACTIVE'`.
Retired bots are invisible to the game but their rows and all associated records
are preserved.

### Merge simplification

When two ELDER bots merge, **one survives and one retires**:

- Bot A (higher fitness) stays `ACTIVE` and receives the merged genome
- Bot B (lower fitness) is set to `RETIRED`

No third bot is created. The merge is an **absorption** — B's genome material folds
into A. A continues with an updated genome and incremented generation. This is
simpler than creating a new entity and retiring both, and avoids the `MERGED`
status which was only meaningful if both had to disappear.

```java
// In UniversityService.processMerge(botA, botB):
BotGenome merged = BotGenome.merge(botA.genome(), botB.genome());
botA.lifecycle().applyMergedGenome(merged);   // genome updated, generation++
botA.setStatus(BotStatus.ACTIVE);             // survivor unchanged in DB except genome
botB.setStatus(BotStatus.RETIRED);            // absorbed bot retired
// Broadcast: "Bot {botB.id} was absorbed by {botA.id} at tick {age}"
```

---

## 9. AI Genome Mentoring — Constructive, Not Diagnostic

### What the LLM should do

The LLM is not a failure analyst. It is a **research advisor** who meets a bot
after a significant achievement. The framing is: *"given who you are as a
researcher, here is where your strengths can take you and how to develop further."*

Random mutation changes genes without knowing what they mean. The LLM's value is
that it understands what each gene represents behaviourally, reads the research
history as a narrative of a researcher's interests and momentum, and suggests how
to deepen or redirect that momentum intentionally.

### Prompt structure

Genes are described in human terms. The history is framed as a career arc, not a
performance report.

```
You are a research advisor in a simulation. A researcher bot has just published
notable work and is eligible for a mentoring session.

Researcher: {botId}  |  Domain: {domain}  |  Methodology: {methodology}
Generation: {generation}  (how many evolution or mentoring cycles they have been through)

Research personality (genome):
  Exploration boldness:  {w0:.2f}  — how large their search moves are
  Restart willingness:   {w1:.2f}  — how readily they abandon a direction and try another
  Candidate breadth:     {w2:.2f}  — how many options they consider before committing
  Depth vs breadth:      {w3:.2f}  — whether they pursue one path deeply or survey widely
  Acceptance threshold:  {w4:.2f}  — how close to perfect a solution must be to satisfy them
  Curiosity:             {er:.2f}  — overall openness to new approaches

Career so far: {published} papers published, {rejected} drafts not accepted
Recent work:
{last 6 outcomes, published and rejected, most recent first:}
  {PUBLISHED|REJECTED} — {domain} / {topicName} — score {score:.2f}

As their advisor, reflect on the arc of their recent work. What does it say about
where their research instincts are taking them? Then suggest one or two small
genome adjustments that would help them go further in the direction they are
already moving — not to fix weaknesses, but to sharpen what is already working.

Also identify one trait to protect: something in their genome that is working well
and should not be changed.

Reply in this format:
REFLECTION: <2–3 sentences on what this researcher's recent work reveals about
             their emerging research identity and direction>
PROTECT: <gene name> — <one sentence on why this trait is an asset>
DEVELOP 1 — GENE: <w0|w1|w2|w3|w4|explorationRate>
            DELTA: <number, -0.15 to +0.15>
            REASON: <one sentence on how this sharpens their existing direction>
DEVELOP 2 (optional) — GENE: <...> DELTA: <...> REASON: <...>
```

### Why this framing produces better genome changes

A failure-focused prompt asks the LLM to identify what is broken and compensate
for it. That produces generic corrections (increase what is low, decrease what
is high) that random mutation could approximate.

An identity-focused prompt asks the LLM to read the research trajectory and
amplify what is already emerging. That produces directional changes — a bot
that has been publishing consistently in deep-search problems gets a nudge
further into depth, not corrected toward breadth. The genome evolves toward a
distinct research character rather than toward an average.

### Streaming and application

The response streams to the room UI via `broadcastLlmToken` with
`kind = "genome_mentor"`. Players see the reflection and recommendation live.
The genome change is applied after the full response is received and parsed.

```java
String reflection = extract("REFLECTION:", response);
String protect    = extract("PROTECT:",    response);
List<GenomeEdit> edits = parseEdits(response);  // 1–2 DEVELOP blocks

for (GenomeEdit edit : edits) {
    double delta = Math.max(-0.15, Math.min(0.15, edit.delta()));
    applyGeneDelta(genome, edit.gene(), delta);
}
// Clamp all weights to [0.05, 1.0]
// Increment generation
// Persist to RoomBot.stateJson
// Broadcast: "Bot {botId} mentoring complete — {reflection first 80 chars}"
```

If the response does not parse (LLM did not follow format), log and skip the
genome update. The stream already delivered value to the player; no crash occurs.

---

## 10. LLM Architecture — Three Independent Semaphores

### Three use cases, three slots

```
consultSlot  →  policy consultation  (sync; game logic blocks on result)
streamSlot   →  government digest    (async streaming; shed if busy)
genomeSlot   →  genome mentoring     (async streaming; queued, not shed)
```

Each slot is `Semaphore(1)`. They are fully independent — none can block another
from checking its own slot. Ollama serialises all requests on CPU anyway; the
semaphores exist to prevent callers from waiting on each other's Java thread, not
to control Ollama concurrency.

### Why three semaphores and not one gate with priority queue

A shared gate with a priority queue requires a drain mechanism — something must
run the genome queue when the gate is released. That drain either runs in the
`finally` block of every LLM call (coupling unrelated use cases) or requires a
dedicated polling thread. Both add complexity for a problem the three-semaphore
pattern already solves cleanly.

The existing proof: splitting `llmSlots` into `consultSlot` + `streamSlot` fixed
digest starvation without any queue or drain logic. The same pattern extends
naturally to a third slot.

### Genome mentoring queue

Genome mentoring must not be shed — it is an earned reward. A
`ConcurrentLinkedQueue<GenomeMentoringRequest>` holds pending mentoring requests.
A virtual thread processes the queue by acquiring `genomeSlot`, running the
request, releasing the slot, and immediately checking for the next item. If the
queue is empty it exits; the next enqueue spins up a new virtual thread to drain.

```java
private final Semaphore consultSlot = new Semaphore(1);   // existing
private final Semaphore streamSlot  = new Semaphore(1);   // existing
private final Semaphore genomeSlot  = new Semaphore(1);   // new

private final ConcurrentLinkedQueue<GenomeMentoringRequest> genomeQueue =
        new ConcurrentLinkedQueue<>();

public void enqueueGenomeMentoring(UUID roomId, UUID botId,
                                   BotLifecycleState state,
                                   List<ResearchPaper> recentPapers) {
    genomeQueue.offer(new GenomeMentoringRequest(roomId, botId, state, recentPapers));
    executor.execute(this::drainGenomeQueue);
}

private void drainGenomeQueue() {
    GenomeMentoringRequest req = genomeQueue.poll();
    if (req == null || !genomeSlot.tryAcquire()) return;
    try {
        runGenomeMentoring(req);
    } finally {
        genomeSlot.release();
        if (!genomeQueue.isEmpty()) executor.execute(this::drainGenomeQueue);
    }
}
```

Policy and digest callers are unchanged. No coupling between use cases.

---

## Database Impact (revised)

| Change | Type |
|---|---|
| `RoomBot.status` column added (`VARCHAR NOT NULL DEFAULT 'ACTIVE'`) | Schema migration |
| `RoomBot.stateJson` extended with lifecycle/genome fields | Non-breaking |
| New `RoomBot` rows created on child spawn (status = ACTIVE) | Additive |
| Elder merge: absorbing bot (B) set to `RETIRED`; surviving bot (A) genome updated | Non-destructive update |
| `Researcher.java` refactored to thin genome wrapper | Code-only |
| `UniversityService` queries `WHERE status = 'ACTIVE'` | Query change |
| `LlmService`: adds `genomeSlot` + `genomeQueue` alongside existing semaphores | Additive |

---

## Implementation Order

1. `BotStatus` enum + `status` column migration on `RoomBot`
2. `BotGenome` + `BotLifecycleState` records; read/write from `stateJson`
3. Age tracking + phase transitions in `maybeTick`
4. Competency test replacing fixed population selection
5. Elder merge logic (deactivate both, create merged child)
6. Dating + child spawn logic
7. `LlmService` refactor: `llmGate` + `genomeQueue` replacing two semaphores
8. Genome mentoring prompt, streaming, and genome application
