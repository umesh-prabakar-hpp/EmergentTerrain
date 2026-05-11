package com.emergentterrain.service;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.domain.RoomProposal;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomProposalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Phase 2.5 carve (Phase 1 — codec + tile-math helpers): pure helpers that
 * understand the shape of a {@link RoomProposal} record and produce / mutate
 * tile arrays.
 *
 * Surface:
 * <ul>
 *   <li>JSON readers: {@link #proposalDeltaType}, {@link #readBotVoteSummary},
 *       {@link #readNeighborIds}. Each returns a defensive default (null,
 *       null, empty list) on missing / malformed JSON — matching the
 *       pre-carve behaviour of "treat bad JSON as 'unknown' and move on."</li>
 *   <li>Anchor + delta-type extraction: {@link #proposalAnchor},
 *       {@link #extractDeltaType}, {@link #extractDeltaTypeFromProposal}.</li>
 *   <li>Tile-array math: {@link #applyDeltaToTiles}, {@link #applyBotChange},
 *       {@link #findChangeType}, {@link #validateProposalChange},
 *       {@link #validateProposalDelta}.</li>
 * </ul>
 *
 * Scope deliberately narrow — this is the first wave of the design doc's
 * {@code RoomProposalService} carve. The behavioural pipeline
 * ({@code propose}, {@code votePropose}, {@code evaluateProposal},
 * {@code applyProposal}, {@code applyBotVotes}, {@code botVoteDecision},
 * {@code tryProposeFromBots}, {@code applyNoParticipation},
 * {@code canVoteNow}, {@code buildProposals}, plus the DB-fetching
 * variants of {@code isTrendMajority} and {@code lastHumanProposalType})
 * stays in {@link RoomService} because it threads many room-service
 * internals — bot lifecycle, GPU adapter, websocket broadcast, the LLM
 * services. Future Phase 2.5 sessions can pull the bot-vote machinery and
 * the evaluate/apply pipeline on top of this codec, splitting the
 * extraction further. Same scope-reduction pattern as the
 * {@code RoomStateOrchestrator}, {@code OccupancyService}, and
 * {@code BotSimulationGpuService} carves.
 *
 * The service is stateless with a single injected dependency
 * ({@link ObjectMapper}). All methods are deterministic on their inputs;
 * no I/O.
 */
@Service
public class RoomProposalService {

    private final ObjectMapper objectMapper;
    private final RoomProposalRepository proposalRepository;
    private final RoomBotRepository botRepository;
    private final BotArchetypeBehaviorService archetypes;
    private final TileValidatorService validator;
    private final BotMovementService movement;
    private final OccupancyService occupancy;
    private final boolean botRuleChecksEnabled;
    private final double botApproveBias;

    public RoomProposalService(ObjectMapper objectMapper,
                               RoomProposalRepository proposalRepository,
                               RoomBotRepository botRepository,
                               BotArchetypeBehaviorService archetypes,
                               TileValidatorService validator,
                               BotMovementService movement,
                               OccupancyService occupancy,
                               @Value("${app.consensus.botRuleChecksEnabled:false}") boolean botRuleChecksEnabled,
                               @Value("${app.consensus.botApproveBias:0.0}") double botApproveBias) {
        this.objectMapper = objectMapper;
        this.proposalRepository = proposalRepository;
        this.botRepository = botRepository;
        this.archetypes = archetypes;
        this.validator = validator;
        this.movement = movement;
        this.occupancy = occupancy;
        this.botRuleChecksEnabled = botRuleChecksEnabled;
        this.botApproveBias = Math.max(0.0, Math.min(0.95, botApproveBias));
    }

    /**
     * Returns the tile type that a proposal's delta encodes, or null when
     * the delta is absent or unparsable. Accepts numeric types (used by
     * bot-generated proposals) and the canonical string names
     * (forest / water / industry / highway / clear) used by human proposals.
     */
    public Integer proposalDeltaType(RoomProposal proposal) {
        if (proposal.getDeltaJson() == null || proposal.getDeltaJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(proposal.getDeltaJson(), new TypeReference<Map<String, Object>>() {});
            Object type = map.get("type");
            if (type instanceof Number) {
                return ((Number) type).intValue();
            }
            if (type instanceof String s) {
                return decodeStringType(s);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Decodes the persisted {@code botVoteSummaryJson} into a
     * {@code vote → count} map, or null when missing / malformed. The map
     * shape is left to the caller — it's used both for serialised counts
     * (APPROVE/REJECT/ABSTAIN/CONFLICT) and other tally schemes.
     */
    public Map<String, Integer> readBotVoteSummary(RoomProposal proposal) {
        if (proposal.getBotVoteSummaryJson() == null || proposal.getBotVoteSummaryJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(proposal.getBotVoteSummaryJson(), new TypeReference<Map<String, Integer>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Decodes the persisted {@code neighborIdsJson} into the participant /
     * bot id list. Empty list on missing or malformed JSON — matches the
     * pre-carve "no known neighbours yet" treatment.
     */
    public List<String> readNeighborIds(RoomProposal proposal) {
        if (proposal.getNeighborIdsJson() == null || proposal.getNeighborIdsJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(proposal.getNeighborIdsJson(), new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Returns the proposal's anchor point in tile coordinates. Prefers the
     * persisted {@code anchorX} / {@code anchorY} fields when both are
     * non-null; falls back to parsing the delta JSON for {@code x} / {@code y}.
     */
    public int[] proposalAnchor(RoomProposal proposal) {
        if (proposal.getAnchorX() != null && proposal.getAnchorY() != null) {
            return new int[] { proposal.getAnchorX(), proposal.getAnchorY() };
        }
        return extractAnchor(proposal.getDeltaJson());
    }

    /**
     * Best-effort {@code (x, y)} extraction from a delta object — string
     * JSON, decoded {@code Map}, or arbitrary POJO via Jackson's
     * {@code convertValue}. Returns null when no usable coordinates are
     * found. Public so the {@code propose} HTTP entry point can call it on
     * the raw request delta before persistence.
     */
    public int[] extractAnchor(Object delta) {
        if (delta == null) {
            return null;
        }
        if (delta instanceof String json && !json.isBlank()) {
            try {
                Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                return extractAnchor(map);
            } catch (Exception ignored) {
            }
        }
        if (delta instanceof Map<?, ?> map) {
            Object x = map.get("x");
            Object y = map.get("y");
            if (x instanceof Number && y instanceof Number) {
                return new int[] { ((Number) x).intValue(), ((Number) y).intValue() };
            }
        }
        try {
            Map<String, Object> map = objectMapper.convertValue(delta, new TypeReference<Map<String, Object>>() {});
            Object x = map.get("x");
            Object y = map.get("y");
            if (x instanceof Number && y instanceof Number) {
                return new int[] { ((Number) x).intValue(), ((Number) y).intValue() };
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    /**
     * Extracts the tile type from a {@code delta} object (typically a
     * decoded {@code Map}). Accepts the same numeric / string-name forms
     * as {@link #proposalDeltaType}. Returns -1 when the type is missing
     * or unrecognised so callers can guard with a single comparison.
     */
    public int extractDeltaType(Object deltaObj) {
        if (deltaObj instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof Number) {
                return ((Number) type).intValue();
            }
            if (type instanceof String s) {
                Integer decoded = decodeStringType(s);
                return decoded == null ? -1 : decoded;
            }
        }
        return -1;
    }

    /** Parses {@code proposal.deltaJson} and forwards to {@link #extractDeltaType}. */
    public int extractDeltaTypeFromProposal(RoomProposal proposal) {
        if (proposal.getDeltaJson() == null) return -1;
        try {
            Map<String, Object> map = objectMapper.readValue(proposal.getDeltaJson(), new TypeReference<Map<String, Object>>() {});
            return extractDeltaType(map);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /**
     * Returns a fresh tile array that applies the proposal's single-tile
     * delta to {@code baseTiles}. When the proposal has no anchor or
     * unrecognised type, returns a verbatim copy of the base tiles —
     * matching the pre-carve behaviour where an invalid delta produces no
     * change but still allocates a new array.
     */
    public int[][] applyDeltaToTiles(int[][] baseTiles, RoomProposal proposal) {
        int[][] out = new int[baseTiles.length][baseTiles[0].length];
        for (int y = 0; y < baseTiles.length; y++) {
            System.arraycopy(baseTiles[y], 0, out[y], 0, baseTiles[0].length);
        }
        int[] anchor = proposalAnchor(proposal);
        int type = extractDeltaTypeFromProposal(proposal);
        if (anchor != null && type >= 0) {
            out[anchor[1]][anchor[0]] = type;
        }
        return out;
    }

    /**
     * Returns a fresh tile array with {@code (x0, y0)} set to {@code type}.
     * Used by the bot-proposal builder to score candidate changes without
     * mutating the cached snapshot.
     */
    public int[][] applyBotChange(int[][] baseTiles, int x0, int y0, int type) {
        int[][] out = new int[baseTiles.length][baseTiles[0].length];
        for (int y = 0; y < baseTiles.length; y++) {
            System.arraycopy(baseTiles[y], 0, out[y], 0, baseTiles[0].length);
        }
        out[y0][x0] = type;
        return out;
    }

    /**
     * Linear scan for the first tile that differs between {@code baseTiles}
     * and {@code proposedTiles}. Returns {@code {x, y, newType}} or null
     * when the arrays match. Used by bot-vote machinery to recover the
     * change anchor without trusting the proposal's persisted fields.
     */
    public int[] findChangeType(int[][] baseTiles, int[][] proposedTiles) {
        for (int y = 0; y < baseTiles.length; y++) {
            for (int x = 0; x < baseTiles[0].length; x++) {
                if (baseTiles[y][x] != proposedTiles[y][x]) {
                    return new int[] { x, y, proposedTiles[y][x] };
                }
            }
        }
        return null;
    }

    /**
     * Asserts that a bot's proposed change is exactly one tile of a single
     * type. Throws {@link IllegalStateException} otherwise. The pre-carve
     * implementation in RoomService enforced the same three invariants —
     * matching grid shape, exactly one differing tile, and a single new
     * type — so existing bot-proposal callsites are guarded identically.
     */
    public void validateProposalChange(int[][] baseTiles, int[][] proposedTiles) {
        if (baseTiles.length != proposedTiles.length || baseTiles[0].length != proposedTiles[0].length) {
            throw new IllegalStateException("Invalid change shape");
        }
        List<int[]> changed = new ArrayList<>();
        for (int y = 0; y < baseTiles.length; y++) {
            for (int x = 0; x < baseTiles[0].length; x++) {
                if (baseTiles[y][x] != proposedTiles[y][x]) {
                    changed.add(new int[] { x, y, proposedTiles[y][x] });
                }
            }
        }
        if (changed.size() != 1) {
            throw new IllegalStateException("Change must be exactly 1x1 tile");
        }
        int minX = changed.stream().mapToInt(c -> c[0]).min().orElse(0);
        int minY = changed.stream().mapToInt(c -> c[1]).min().orElse(0);
        int maxX = changed.stream().mapToInt(c -> c[0]).max().orElse(0);
        int maxY = changed.stream().mapToInt(c -> c[1]).max().orElse(0);
        if (maxX != minX || maxY != minY) {
            throw new IllegalStateException("Change must be a contiguous 1x1 block");
        }
        int type = changed.get(0)[2];
        for (int[] c : changed) {
            if (c[2] != type) {
                throw new IllegalStateException("1x1 tile must be a single tile type");
            }
        }
    }

    /**
     * Asserts that a human-proposed delta is in-bounds and references a
     * known tile type ({@code 0..4}). Throws {@link IllegalStateException}
     * on any violation. Called from the {@code propose} HTTP entry point
     * after the request is decoded but before the candidate tiles are
     * applied to the cached snapshot.
     */
    public void validateProposalDelta(int[][] baseTiles, int[] delta, Object deltaObj) {
        if (delta == null || deltaObj == null) {
            throw new IllegalStateException("No tiles specified");
        }
        int x = delta[0];
        int y = delta[1];
        if (x < 0 || y < 0 || y >= baseTiles.length || x >= baseTiles[0].length) {
            throw new IllegalStateException("Change out of bounds");
        }
        int type = extractDeltaType(deltaObj);
        if (type < 0 || type > 4) {
            throw new IllegalStateException("Invalid tile type");
        }
    }

    private static Integer decodeStringType(String s) {
        return switch (s) {
            case "forest" -> 1;
            case "water" -> 2;
            case "industry" -> 3;
            case "highway" -> 4;
            case "clear" -> 0;
            default -> null;
        };
    }

    // ---------------------------------------------------------------------
    // Phase 2 — bot-vote machinery (lifted from RoomService in a follow-up).
    // ---------------------------------------------------------------------

    /**
     * Aggregate tile-share statistics over the viewport around an anchor.
     * Pure; reads only from the supplied {@code tiles} / {@code highways}
     * arrays and the {@link OccupancyService} viewport math. Empty viewport
     * (no tiles) returns an all-zeros snapshot with {@code total == 0}.
     */
    public LocalStats localStats(int[][] tiles, boolean[][] highways, int anchorX, int anchorY) {
        int[] view = occupancy.computeViewBounds(anchorX, anchorY, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        int total = 0;
        int forest = 0;
        int water = 0;
        int industry = 0;
        int clear = 0;
        int road = 0;
        for (int y = viewY; y < viewY + viewH; y++) {
            for (int x = viewX; x < viewX + viewW; x++) {
                int v = tiles[y][x];
                total++;
                if (v == 1) forest++;
                else if (v == 2) water++;
                else if (v == 3) industry++;
                else clear++;
                if ((highways.length > 0 && highways[y][x]) || v == 4) {
                    road++;
                }
            }
        }
        if (total == 0) {
            return new LocalStats(0, 0, 0, 0, 0, 0);
        }
        return new LocalStats(
            total,
            (double) forest / total,
            (double) water / total,
            (double) clear / total,
            (double) industry / total,
            (double) road / total
        );
    }

    /**
     * Reads the room's most recent proposals (top 10 by createdAt) and
     * returns the resolved delta types (in the same order; nulls preserved
     * for proposals with absent or unparsable delta types). Used by the
     * rapid-oscillation rule inside {@code violatesCommunityRules}.
     */
    public List<Integer> recentProposalDeltaTypes(Room room) {
        List<RoomProposal> recent = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
        List<Integer> types = new ArrayList<>(recent.size());
        for (RoomProposal proposal : recent) {
            types.add(proposalDeltaType(proposal));
        }
        return types;
    }

    /**
     * True when at least 2 of the 3 most recent typed proposals in the room
     * propose the same tile {@code type}. The 3-sample cap matches the
     * pre-carve heuristic: enough for a "trend" signal, short enough that a
     * single counterexample resets it. Used to flip CONTRARIAN bots into
     * always-reject mode when the herd is moving in one direction.
     */
    public boolean isTrendMajority(Room room, int type) {
        List<RoomProposal> recent = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
        if (recent.isEmpty()) {
            return false;
        }
        int same = 0;
        int total = 0;
        for (RoomProposal proposal : recent) {
            Integer t = proposalDeltaType(proposal);
            if (t == null) continue;
            total++;
            if (t == type) same++;
            if (total >= 3) break;
        }
        return total > 0 && same >= 2;
    }

    /**
     * Returns the tile type of the most recent human (non-bot) proposal in
     * the room, or null when no human has proposed in the last 10. Used by
     * NEIGHBOR_MIRROR bots to mirror the most recent human direction.
     */
    public Integer lastHumanProposalType(Room room) {
        List<RoomProposal> recent = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
        for (RoomProposal proposal : recent) {
            if (Boolean.TRUE.equals(proposal.isBotGenerated())) {
                continue;
            }
            return proposalDeltaType(proposal);
        }
        return null;
    }

    /**
     * Decides how a single bot would vote on a proposed change, given the
     * bot's pre-resolved profile + the (base, proposed) tile arrays
     * straddling exactly one tile change. The {@code driftCallback} is
     * invoked (with the bot and the pre-change {@code LocalStats}) so the
     * caller can perform the side-effecting profile drift + DB save that
     * follows a vote — when null, no drift fires.
     *
     * The pre-carve {@code Math.random()} call ordering is preserved
     * verbatim. Five archetypes have special-case logic that runs before
     * the general-rule fan-out: NEIGHBOR_MIRROR mirrors the most recent
     * human, CONTRARIAN rejects when the herd is moving, the
     * archetype-dislikes-this-type rule rejects unconditionally, the
     * community-rules gate rejects probabilistically, and the
     * archetype-prefers-this-type rule approves on improvement. Everything
     * else falls through to {@link BotMovementService#tilePreferenceScore}
     * with a risk-flip and conflict-flip on top.
     */
    public BotVote botVoteDecision(Room room,
                                   RoomBot bot,
                                   Map<String, Double> profile,
                                   int[][] baseTiles,
                                   int[][] proposedTiles,
                                   boolean[][] highways,
                                   boolean proposalBotGenerated,
                                   BiConsumer<RoomBot, LocalStats> driftCallback) {
        int[] change = findChangeType(baseTiles, proposedTiles);
        if (change == null) {
            return BotVote.ABSTAIN;
        }
        int type = change[2];
        String archetype = archetypes.archetypeFor(bot);
        LocalStats before = localStats(baseTiles, highways, change[0], change[1]);
        LocalStats after = localStats(proposedTiles, highways, change[0], change[1]);
        double beforeScore = archetypes.prosperityScore(before, archetype);
        double afterScore = archetypes.prosperityScore(after, archetype);
        double improvement = beforeScore - afterScore;
        double risk = profile.getOrDefault("risk", 0.5);
        double abstainProb = profile.getOrDefault("abstain", 0.08);
        double conflictProb = profile.getOrDefault("conflict", 0.08);
        double consensus = profile.getOrDefault("consensus", 0.5);
        double community = Math.min(1.0, Math.max(0.1, consensus * 0.8 + profile.getOrDefault("mobility", 0.5) * 0.2));

        boolean prefers = archetypes.prefersType(archetype, type);
        boolean dislikes = archetypes.dislikesType(archetype, type, before);
        boolean contrarian = "CONTRARIAN".equals(archetype) && isTrendMajority(room, type);
        boolean mirror = "NEIGHBOR_MIRROR".equals(archetype);

        if (mirror) {
            Integer lastHuman = lastHumanProposalType(room);
            if (lastHuman != null) {
                return lastHuman == type ? BotVote.APPROVE : BotVote.REJECT;
            }
        }

        if (contrarian) {
            return BotVote.REJECT;
        }

        if (dislikes) {
            return BotVote.REJECT;
        }

        if (!proposalBotGenerated || botRuleChecksEnabled) {
            if (validator.violatesCommunityRules(after, highways, proposedTiles, change[0], change[1], community,
                    () -> recentProposalDeltaTypes(room))) {
                return BotVote.REJECT;
            }
        }

        if (Math.abs(improvement) < 0.01 && Math.random() < abstainProb) {
            return BotVote.ABSTAIN;
        }

        if (prefers && improvement >= -0.02) {
            return BotVote.APPROVE;
        }

        if ("PRAGMATIST".equals(archetype)) {
            if (improvement > 0.02) return BotVote.APPROVE;
            if (improvement < -0.02) return BotVote.REJECT;
        }

        double preference = movement.tilePreferenceScore(type, profile);
        boolean approve = improvement >= 0 ? preference >= 0.35 * (1 - consensus) : preference >= 0.55;
        if (!approve && improvement > -0.05 && Math.random() < risk) {
            approve = true;
        }
        if (Math.random() < conflictProb) {
            approve = !approve;
        }
        if (driftCallback != null) {
            driftCallback.accept(bot, before);
        }
        return approve ? BotVote.APPROVE : BotVote.REJECT;
    }

    /**
     * Tallies bot votes on a proposal: fetches the room's bots, restricts
     * to the proposal's neighbour set, calls {@link #botVoteDecision} for
     * each, and writes the running totals onto {@code proposal} (the bean
     * setters update {@code approvals} / {@code rejections} /
     * {@code botVoteSummaryJson}). {@code botApproveBias} (a configurable
     * fraction in {@code [0, 0.95]}) flips a REJECT to APPROVE
     * probabilistically — the lever that lets the bot population act more
     * cooperatively without changing the underlying decision logic.
     */
    public void applyBotVotes(Room room,
                              RoomProposal proposal,
                              int[][] baseTiles,
                              int[][] proposedTiles,
                              boolean[][] highways,
                              Function<RoomBot, Map<String, Double>> profileReader,
                              BiConsumer<RoomBot, LocalStats> driftCallback) {
        Set<String> neighborIds = new HashSet<>(readNeighborIds(proposal));
        if (neighborIds.isEmpty()) {
            return;
        }
        int botYes = 0;
        int botNo = 0;
        List<RoomBot> bots = botRepository.findByRoom(room);
        for (RoomBot bot : bots) {
            String key = "B:" + bot.getId();
            if (!neighborIds.contains(key)) {
                continue;
            }
            Map<String, Double> profile = profileReader.apply(bot);
            BotVote vote = botVoteDecision(room, bot, profile, baseTiles, proposedTiles, highways,
                    Boolean.TRUE.equals(proposal.isBotGenerated()), driftCallback);
            if (vote == BotVote.REJECT && botApproveBias > 0 && Math.random() < botApproveBias) {
                vote = BotVote.APPROVE;
            }
            if (vote == BotVote.ABSTAIN) {
                continue;
            }
            if (vote == BotVote.APPROVE) {
                proposal.setApprovals(proposal.getApprovals() + 1);
                botYes++;
            } else {
                proposal.setRejections(proposal.getRejections() + 1);
                botNo++;
            }
        }
        if (botYes > 0 || botNo > 0) {
            Map<String, Integer> summary = new HashMap<>();
            summary.put("botYes", botYes);
            summary.put("botNo", botNo);
            try {
                proposal.setBotVoteSummaryJson(objectMapper.writeValueAsString(summary));
            } catch (JsonProcessingException e) {
                // Summary is advisory; persisting a missing summary is fine.
            }
        }
    }
}
