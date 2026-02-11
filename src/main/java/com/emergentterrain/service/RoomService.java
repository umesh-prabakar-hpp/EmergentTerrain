package com.emergentterrain.service;

import com.emergentterrain.domain.*;
import com.emergentterrain.repo.*;
import com.emergentterrain.web.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository participantRepository;
    private final RoomStateRepository stateRepository;
    private final RoomProposalRepository proposalRepository;
    private final RoomProposalVoteRepository voteRepository;
    private final RoomBotRepository botRepository;
    private final ProfileRepository profileRepository;
    private final PayloadService payloadService;
    private final RoomUpdateBroadcaster broadcaster;
    private final WorldService worldService;
    private final ObjectMapper objectMapper;
    private final int neighborRadius;
    private final int voteWindowSeconds;
    private final int minVotesToResolve;
    private final boolean botRuleChecksEnabled;
    private final int botProposalsPerMinute;
    private final int botVoteRetentionSeconds;
    private final int minProposalLiveSeconds;
    private final int minNeighbors;
    private final int densityAreaSize;
    private final int maxOccupants;
    private final double botPopulationFraction;
    private final int noParticipationLockSeconds;
    private final ActivityMetrics metrics;
    private final int maxBotsPerRoom;
    private static final int VISIBLE_WIDTH = 48;
    private static final int VISIBLE_HEIGHT = 36;
    private static final List<String> BOT_ARCHETYPES = List.of(
        "STEWARD", "BUILDER", "FLOW_KEEPER", "PRESERVATIONIST",
        "PRAGMATIST", "CONTRARIAN", "NEIGHBOR_MIRROR"
    );
    private final ConcurrentHashMap<UUID, ReentrantLock> botLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> proposalLocks = new ConcurrentHashMap<>();
    private final ReentrantLock proposalTableLock = new ReentrantLock();
    private final ConcurrentHashMap<UUID, CachedMap> mapCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CachedRoomState> roomStateCache = new ConcurrentHashMap<>();

    public RoomService(RoomRepository roomRepository,
                       RoomParticipantRepository participantRepository,
                       RoomStateRepository stateRepository,
                       RoomProposalRepository proposalRepository,
                       RoomProposalVoteRepository voteRepository,
                       RoomBotRepository botRepository,
                       ProfileRepository profileRepository,
                       PayloadService payloadService,
                       RoomUpdateBroadcaster broadcaster,
                       WorldService worldService,
                       ObjectMapper objectMapper,
                       ActivityMetrics metrics,
                       @Value("${app.consensus.neighborRadius:8}") int neighborRadius,
                       @Value("${app.consensus.voteWindowSeconds:15}") int voteWindowSeconds,
                       @Value("${app.consensus.minVotesToResolve:3}") int minVotesToResolve,
                       @Value("${app.consensus.botRuleChecksEnabled:false}") boolean botRuleChecksEnabled,
                       @Value("${app.consensus.botProposalsPerMinute:10}") int botProposalsPerMinute,
                       @Value("${app.consensus.botVoteRetentionSeconds:20}") int botVoteRetentionSeconds,
                       @Value("${app.consensus.minProposalLiveSeconds:3}") int minProposalLiveSeconds,
                       @Value("${app.consensus.minNeighbors:3}") int minNeighbors,
                       @Value("${app.density.areaSize:20}") int densityAreaSize,
                       @Value("${app.density.maxOccupants:8}") int maxOccupants,
                       @Value("${app.bots.populationFraction:0.25}") double botPopulationFraction,
                       @Value("${app.bots.maxPerRoom:200}") int maxBotsPerRoom,
                       @Value("${app.consensus.noParticipationLockSeconds:300}") int noParticipationLockSeconds) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.stateRepository = stateRepository;
        this.proposalRepository = proposalRepository;
        this.voteRepository = voteRepository;
        this.botRepository = botRepository;
        this.profileRepository = profileRepository;
        this.payloadService = payloadService;
        this.broadcaster = broadcaster;
        this.worldService = worldService;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.neighborRadius = neighborRadius;
        this.voteWindowSeconds = voteWindowSeconds;
        this.minVotesToResolve = minVotesToResolve;
        this.botRuleChecksEnabled = botRuleChecksEnabled;
        this.botProposalsPerMinute = Math.max(1, botProposalsPerMinute);
        this.botVoteRetentionSeconds = botVoteRetentionSeconds;
        this.minProposalLiveSeconds = minProposalLiveSeconds;
        this.minNeighbors = minNeighbors;
        this.densityAreaSize = densityAreaSize;
        this.maxOccupants = maxOccupants;
        this.botPopulationFraction = botPopulationFraction;
        this.maxBotsPerRoom = Math.max(1, maxBotsPerRoom);
        this.noParticipationLockSeconds = noParticipationLockSeconds;
    }

    public RoomCreateResponse createRoom(Payload payload, String name) {
        Profile profile = profileFromPayload(payload);
        if (profile == null) {
            throw new IllegalStateException("Profile not found");
        }
        String safeName = (name == null || name.isBlank()) ? "Reflection Room" : name.trim();
        long seed = payloadService.seedFromPayload(payload);
        GameStateResponse world = worldService.generateWorld(payload, seed);

        Room room = new Room();
        room.setName(safeName);
        room.setHostProfile(profile);
        room = roomRepository.save(room);
        profile.setLastRoomId(room.getId());
        profileRepository.save(profile);

        RoomParticipant host = new RoomParticipant();
        host.setRoom(room);
        host.setProfile(profile);
        host.setRole("HOST");
        host.setLens("PATTERN");
        host.setDisplayName(displayName(profile));
        assignSpawnPosition(host, world.getTiles());
        host = participantRepository.save(host);

        room = roomRepository.save(room);

        RoomState state = new RoomState();
        state.setRoom(room);
        state.setTilesJson(toJson(world.getTiles()));
        state.setHighwaysJson(toJson(world.getHighways()));
        state.setSharedTokensJson(toJson(new SharedTokens(0, 0, 0)));
        state.setSharedQuestsJson(toJson(defaultSharedQuests()));
        state.setMemoryMarkersJson(toJson(new ArrayList<>()));
        state.setStateHash(hashTiles(world.getTiles()));
        state = stateRepository.save(state);
        updateCacheFromState(room, state);

        ensureBots(room, world.getTiles());
        ensureNearbyBots(room, world.getTiles(), host.getX(), host.getY());

        RoomCreateResponse response = new RoomCreateResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        response.setParticipantId(host.getId().toString());
        response.setTiles(world.getTiles());
        response.setHighways(world.getHighways());
        response.setSharedTokens(new SharedTokens(0, 0, 0));
        response.setSharedQuests(defaultSharedQuests());
        response.setMemoryMarkers(new ArrayList<>());
        response.setParticipants(buildParticipants(room));
        response.setBots(buildBots(room));
        response.setProposals(buildProposals(room, host));
        response.setLockUntil(room.getLockUntil());
        response.setNeighborRadius(neighborRadius);
        response.setMinNeighbors(minNeighbors);
        response.setDensityAreaSize(densityAreaSize);
        response.setMaxOccupants(maxOccupants);
        return response;
    }

    public RoomJoinResponse joinRoom(Payload payload, UUID roomId) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = participantRepository.findByRoomAndProfile(room, profile)
            .orElseGet(() -> {
                RoomParticipant created = new RoomParticipant();
                created.setRoom(room);
                created.setProfile(profile);
                created.setRole("GUEST");
                created.setLens(assignLens(room));
                created.setDisplayName(displayName(profile));
                RoomState state = stateRepository.findByRoom(room)
                    .orElse(null);
                if (state != null) {
                    assignSpawnPosition(created, getTilesForRoom(room, state));
                }
                return participantRepository.save(created);
            });
        if (participant.getX() == null || participant.getY() == null) {
            RoomState state = stateRepository.findByRoom(room).orElse(null);
            if (state != null) {
                assignSpawnPosition(participant, getTilesForRoom(room, state));
                participantRepository.save(participant);
            }
        }
        RoomState joinState = stateRepository.findByRoom(room).orElse(null);
        if (joinState != null) {
            ensureNearbyBots(room, getTilesForRoom(room, joinState), participant.getX(), participant.getY());
        }
        profile.setLastRoomId(room.getId());
        profileRepository.save(profile);
        RoomJoinResponse response = new RoomJoinResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        response.setParticipantId(participant.getId().toString());
        return response;
    }

    public RoomJoinResponse joinOrCreateLastRoom(Payload payload, String name) {
        Profile profile = profileFromPayload(payload);
        if (profile == null) {
            throw new IllegalStateException("Profile not found");
        }
        UUID lastRoomId = profile.getLastRoomId();
        if (lastRoomId != null) {
            Room room = roomRepository.findById(lastRoomId).orElse(null);
            if (room != null) {
                return joinRoom(payload, room.getId());
            }
        }
        RoomCreateResponse created = createRoom(payload, name);
        RoomJoinResponse response = new RoomJoinResponse();
        response.setRoomId(created.getRoomId());
        response.setRoomName(created.getRoomName());
        response.setParticipantId(created.getParticipantId());
        return response;
    }

    @Transactional
    public RoomStateResponse getRoomState(Payload payload, UUID roomId) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        ensureBots(room, getTilesForRoom(room, state));
        return toRoomStateResponse(room, state, participant);
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void scheduledRoomMaintenance() {
        metrics.increment("room.maintenance");
        long start = System.nanoTime();
        List<Room> rooms = roomRepository.findAll();
        log.debug("maintenance start rooms={}", rooms.size());
        try {
            for (Room room : rooms) {
                RoomState state = stateRepository.findByRoom(room).orElse(null);
                if (state == null) {
                    continue;
                }
                ReentrantLock lock = proposalLocks.computeIfAbsent(room.getId(), id -> new ReentrantLock());
                if (!lock.tryLock()) {
                    continue;
                }
                try {
                    long roomStart = System.nanoTime();
                    log.debug("maintenance step=ensureBots start roomId={}", room.getId());
                    int[][] tiles = getTilesForRoom(room, state);
                    Instant now = Instant.now();
                    if (state.getLastCleanupAt() == null || state.getLastCleanupAt().isBefore(now.minusSeconds(15))) {
                        cleanupOldProposals(room);
                        state.setLastCleanupAt(now);
                    }
                    long botsStart = System.nanoTime();
                    ensureBots(room, tiles);
                    long botsMs = (System.nanoTime() - botsStart) / 1_000_000;
                    log.debug("maintenance step=ensureBots end roomId={} botsMs={}", room.getId(), botsMs);
                    log.debug("maintenance step=maybeCreateBotProposal start roomId={}", room.getId());
                    maybeCreateBotProposal(room, state);
                    log.debug("maintenance step=maybeCreateBotProposal end roomId={}", room.getId());
                    log.debug("maintenance step=tickBots start roomId={}", room.getId());
                    if (state.getLastBotTickAt() == null || state.getLastBotTickAt().isBefore(now.minusSeconds(5))) {
                        tickBots(room, state);
                        state.setLastBotTickAt(now);
                    }
                    log.debug("maintenance step=tickBots end roomId={}", room.getId());
                    log.debug("maintenance step=evaluatePendingProposals start roomId={}", room.getId());
                    evaluatePendingProposals(room);
                    log.debug("maintenance step=evaluatePendingProposals end roomId={}", room.getId());
                    stateRepository.save(state);
                    broadcaster.broadcastRoom(room.getId());
                    long roomMs = (System.nanoTime() - roomStart) / 1_000_000;
                    log.debug("maintenance roomId={} botsMs={} roomMs={}", room.getId(), botsMs, roomMs);
                } catch (Exception e) {
                    log.warn("maintenance error roomId={} msg={}", room.getId(), e.getMessage());
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            long totalMs = (System.nanoTime() - start) / 1_000_000;
            log.debug("maintenance total rooms={} totalMs={}", rooms.size(), totalMs);
        }
    }

    @Scheduled(fixedDelayString = "${app.world.flushIntervalMs:30000}")
    @Transactional
    public void flushRoomStateCache() {
        Instant now = Instant.now();
        for (Map.Entry<UUID, CachedRoomState> entry : roomStateCache.entrySet()) {
            UUID roomId = entry.getKey();
            CachedRoomState cached = entry.getValue();
            if (!cached.dirty) {
                continue;
            }
            ReentrantLock lock = proposalLocks.computeIfAbsent(roomId, id -> new ReentrantLock());
            if (!lock.tryLock()) {
                continue;
            }
            try {
                Room room = roomRepository.findById(roomId).orElse(null);
                if (room == null) {
                    continue;
                }
                RoomState state = stateRepository.findByRoom(room).orElse(null);
                if (state == null) {
                    continue;
                }
                state.setTilesJson(toJson(cached.tiles));
                state.setHighwaysJson(toJson(cached.highways));
                state.setSharedTokensJson(cached.sharedTokensJson);
                state.setSharedQuestsJson(cached.sharedQuestsJson);
                state.setMemoryMarkersJson(cached.memoryMarkersJson);
                state.setPreviousStateHash(cached.previousStateHash);
                state.setStateHash(cached.stateHash == null ? hashTiles(cached.tiles) : cached.stateHash);
                state.setUpdatedAt(now);
                stateRepository.save(state);
                cached.dirty = false;
                cached.lastFlushAt = now;
                log.info("roomStateFlush roomId={} updatedAt={} stateHash={}", roomId, now, state.getStateHash());
            } finally {
                lock.unlock();
            }
        }
    }
    @Transactional
    public RoomStateResponse move(Payload payload, UUID roomId, int x, int y) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        ensurePlayable(participant);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        int[][] tiles = getTilesForRoom(room, state);
        if (x < 0 || y < 0 || y >= tiles.length || x >= tiles[0].length) {
            throw new IllegalStateException("Move out of bounds");
        }
        if (!canOccupy(room, x, y, participant.getId().toString())) {
            throw new IllegalStateException("Area at capacity");
        }
        participant.setX(x);
        participant.setY(y);
        participantRepository.save(participant);
        ensureNearbyBots(room, tiles, participant.getX(), participant.getY());
        RoomStateResponse response = toRoomStateResponse(room, state, participant);
        broadcaster.broadcastRoom(room.getId());
        return response;
    }

    public RoomStateResponse propose(Payload payload, UUID roomId, RoomProposalRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        ReentrantLock lock = proposalLocks.computeIfAbsent(room.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            RoomParticipant participant = ensureParticipant(room, profile);
            ensurePlayable(participant);
            RoomProposal proposal = new RoomProposal();
            proposal.setRoom(room);
            proposal.setProposer(participant);
            RoomState state = stateRepository.findByRoom(room)
                .orElseThrow(() -> new IllegalStateException("Room state missing"));
            int[][] baseTiles = getTilesForRoom(room, state);
            int[] delta = extractAnchor(request.getDelta());
            validateProposalDelta(baseTiles, delta, request.getDelta());
            proposal.setDeltaJson(toJson(request.getDelta()));
            proposal.setTilesJson(toJson(request.getDelta()));
            int[] anchor = delta;
            if (anchor != null) {
                proposal.setAnchorX(anchor[0]);
                proposal.setAnchorY(anchor[1]);
            }
            proposal.setStatus("PENDING");
            List<String> neighbors = findNeighbors(room, participant, request.getTiles());
            if (neighbors.size() < minNeighbors) {
                throw new IllegalStateException("Not enough neighbors nearby to vote");
            }
            proposal.setNeighborIdsJson(toJson(neighbors));
            proposal.setRequiredVotes(neighbors.size() / 2 + 1);
            proposal.setExpiresAt(Instant.now().plusSeconds(voteWindowSeconds));
            proposal.setApprovals(1);
            proposal.setRejections(0);
            proposal = proposalRepository.save(proposal);

            RoomProposalVote vote = new RoomProposalVote();
            vote.setProposal(proposal);
            vote.setParticipant(participant);
            vote.setApprove(true);
            voteRepository.save(vote);

            int[][] proposedTiles = applyDeltaToTiles(baseTiles, proposal);
            applyBotVotes(room, proposal, baseTiles, proposedTiles);
            evaluateProposal(room, proposal);
            if ("PENDING".equals(proposal.getStatus())) {
                proposalRepository.save(proposal);
            }
            RoomStateResponse response = toRoomStateResponse(room, state, participant);
            broadcaster.broadcastRoom(room.getId());
            return response;
        } finally {
            lock.unlock();
        }
    }

    public RoomStateResponse vote(Payload payload, UUID roomId, RoomVoteRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        ReentrantLock lock = proposalLocks.computeIfAbsent(room.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            RoomParticipant participant = ensureParticipant(room, profile);
            ensurePlayable(participant);
            UUID proposalId = UUID.fromString(request.getProposalId());
            RoomProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalStateException("Proposal not found"));
            if (!proposal.getRoom().getId().equals(room.getId())) {
                throw new IllegalStateException("Proposal mismatch");
            }
            if (!canVoteNow(room, proposal, participant)) {
                throw new IllegalStateException("Only nearby neighbors can vote");
            }
            if (voteRepository.findByProposalAndParticipant(proposal, participant).isPresent()) {
                throw new IllegalStateException("Already voted");
            }
            RoomProposalVote vote = new RoomProposalVote();
            vote.setProposal(proposal);
            vote.setParticipant(participant);
            vote.setApprove(request.isApprove());
            voteRepository.save(vote);

            if (request.isApprove()) {
                proposal.setApprovals(proposal.getApprovals() + 1);
            } else {
                proposal.setRejections(proposal.getRejections() + 1);
            }
            proposalRepository.save(proposal);
            evaluateProposal(room, proposal);

            RoomState state = stateRepository.findByRoom(room)
                .orElseThrow(() -> new IllegalStateException("Room state missing"));
            return toRoomStateResponse(room, state, participant);
        } finally {
            lock.unlock();
        }
    }

    public RoomStateResponse apply(Payload payload, UUID roomId, RoomApplyRequest request) {
        throw new IllegalStateException("Proposals apply automatically on majority vote");
    }

    public RoomStateResponse addMemory(Payload payload, UUID roomId, RoomMemoryRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        List<MemoryMarker> markers = readMemoryMarkers(state);
        MemoryMarker marker = new MemoryMarker();
        marker.setId(UUID.randomUUID().toString());
        marker.setX(request.getX());
        marker.setY(request.getY());
        marker.setLabel(request.getLabel() == null ? "Memory" : request.getLabel());
        marker.setCreatedBy(participant.getDisplayName());
        marker.setCreatedAt(Instant.now());
        markers.add(marker);
        state.setMemoryMarkersJson(toJson(markers));
        stateRepository.save(state);
        return toRoomStateResponse(room, state, participant);
    }

    public RoomBotPromptResponse botPrompt(Payload payload, UUID roomId, String type) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        ensureParticipant(room, profile);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        RoomBot bot = botRepository.findByRoomAndType(room, type)
            .orElseGet(() -> {
                RoomBot created = new RoomBot();
                created.setRoom(room);
                created.setType(type);
                return botRepository.save(created);
            });
        String prompt = buildBotPrompt(type, state);
        bot.setLastPrompt(prompt);
        bot.setLastRunAt(Instant.now());
        botRepository.save(bot);
        RoomBotPromptResponse response = new RoomBotPromptResponse();
        response.setType(type);
        response.setPrompt(prompt);
        response.setLastPrompt(bot.getLastPrompt());
        return response;
    }

    public RoomStateResponse dismissPoll(Payload payload, UUID roomId, String proposalId) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        List<String> dismissed = new ArrayList<>(readDismissedPollIds(participant));
        if (proposalId != null && !proposalId.isBlank() && !dismissed.contains(proposalId)) {
            dismissed.add(proposalId);
        }
        if (dismissed.size() > 100) {
            dismissed = dismissed.subList(dismissed.size() - 100, dismissed.size());
        }
        participant.setDismissedPollsJson(toJson(dismissed));
        participantRepository.save(participant);
        RoomStateResponse response = toRoomStateResponse(room, state, participant);
        broadcaster.broadcastRoom(room.getId());
        return response;
    }

    public RoomResonanceResponse resonance(Payload payload, UUID roomId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        long seed = payloadService.seedFromPayload(payload);
        GameStateResponse world = worldService.generateWorld(payload, seed);
        int[][] baseTiles = world.getTiles();
        int[][] roomTiles = getTilesForRoom(room, state);
        boolean[][] overlay = new boolean[roomTiles.length][roomTiles[0].length];
        int matches = 0;
        int total = 0;
        for (int y = 0; y < roomTiles.length; y++) {
            for (int x = 0; x < roomTiles[0].length; x++) {
                boolean match = baseTiles[y][x] == roomTiles[y][x];
                overlay[y][x] = match;
                total++;
                if (match) matches++;
            }
        }
        RoomResonanceResponse response = new RoomResonanceResponse();
        response.setOverlay(overlay);
        response.setMatchRatio(total == 0 ? 0 : (double) matches / total);
        return response;
    }

    private Profile profileFromPayload(Payload payload) {
        if (payload.profileId() == null || payload.profileId().isBlank()) {
            return null;
        }
        return profileRepository.findById(UUID.fromString(payload.profileId()))
            .orElse(null);
    }

    private RoomParticipant ensureParticipant(Room room, Profile profile) {
        RoomParticipant participant = participantRepository.findByRoomAndProfile(room, profile)
            .orElseThrow(() -> new IllegalStateException("Not a participant"));
        if (participant.getX() == null || participant.getY() == null) {
            RoomState state = stateRepository.findByRoom(room).orElse(null);
            if (state != null) {
                assignSpawnPosition(participant, getTilesForRoom(room, state));
                participantRepository.save(participant);
            }
        }
        return participant;
    }

    private String displayName(Profile profile) {
        String email = profile.getUser().getEmail();
        if (email == null || email.isBlank()) {
            return "Traveler";
        }
        String name = email.split("@")[0];
        if (name.length() > 12) {
            name = name.substring(0, 12);
        }
        return name;
    }

    private String assignLens(Room room) {
        List<RoomParticipant> participants = participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room);
        String[] lenses = {"FOG", "MEANING", "PATTERN"};
        return lenses[participants.size() % lenses.length];
    }

    private boolean isTurnAllowed(Room room, RoomParticipant participant) {
        if (room.getTurnOwnerParticipantId() == null) {
            return true;
        }
        if (room.getTurnOwnerParticipantId().equals(participant.getId())) {
            return true;
        }
        Instant expires = room.getTurnExpiresAt();
        return expires != null && expires.isBefore(Instant.now());
    }

    private void advanceTurn(Room room) {
        List<RoomParticipant> participants = participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room);
        if (participants.isEmpty()) {
            return;
        }
        UUID currentId = room.getTurnOwnerParticipantId();
        int index = 0;
        for (int i = 0; i < participants.size(); i++) {
            if (participants.get(i).getId().equals(currentId)) {
                index = i;
                break;
            }
        }
        int next = (index + 1) % participants.size();
        room.setTurnOwnerParticipantId(participants.get(next).getId());
        room.setTurnExpiresAt(Instant.now().plusSeconds(60));
    }

    private int requiredVotes(Room room) {
        int active = participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room).size();
        return active / 2 + 1;
    }

    private void ensureBots(Room room, int[][] tiles) {
        List<RoomBot> existing = botRepository.findByRoom(room);
        int maxOccupantsTotal = Math.max(3, (tiles.length * tiles[0].length * maxOccupants) / (densityAreaSize * densityAreaSize));
        int targetBots = Math.max(40, (int) Math.round(maxOccupantsTotal * botPopulationFraction));
        targetBots = Math.min(targetBots, maxBotsPerRoom);

        Map<String, RoomBot> byType = new HashMap<>();
        for (RoomBot bot : existing) {
            byType.put(bot.getType(), bot);
        }

        List<String> companions = List.of("OBSERVER", "COPILOT", "TENSION", "ECHO", "QUEST");
        for (String type : companions) {
            byType.computeIfAbsent(type, t -> {
                RoomBot bot = new RoomBot();
                bot.setRoom(room);
                bot.setType(t);
                bot.setProfileJson(toJson(randomBotProfile(room.getId().hashCode() ^ t.hashCode())));
                assignBotSpawn(bot, tiles);
                return botRepository.save(bot);
            });
        }

        for (RoomBot bot : botRepository.findByRoom(room)) {
            boolean dirty = false;
            if (bot.getProfileJson() == null || bot.getProfileJson().isBlank()) {
                bot.setProfileJson(toJson(randomBotProfile(room.getId().hashCode() ^ bot.getType().hashCode())));
                dirty = true;
            }
            if (bot.getX() == null || bot.getY() == null) {
                assignBotSpawn(bot, tiles);
                dirty = true;
            }
            if (dirty) {
                botRepository.save(bot);
            }
        }

        int created = botRepository.findByRoom(room).size();
        for (int i = created; i < targetBots; i++) {
            String type = "AGENT-" + (i + 1);
            RoomBot bot = new RoomBot();
            bot.setRoom(room);
            bot.setType(type);
            bot.setProfileJson(toJson(randomBotProfile(room.getId().hashCode() ^ i)));
            assignBotSpawn(bot, tiles);
            botRepository.save(bot);
        }
    }

    private void ensureNearbyBots(Room room, int[][] tiles, Integer cx, Integer cy) {
        if (cx == null || cy == null) {
            return;
        }
        int needed = Math.max(0, minNeighbors - 1);
        if (needed == 0) {
            return;
        }
        int botYes = 0;
        int botNo = 0;
        List<RoomBot> bots = botRepository.findByRoom(room);
        int near = 0;
        for (RoomBot bot : bots) {
            if (bot.getX() == null || bot.getY() == null) {
                continue;
            }
            if (withinRadius(cx, cy, bot.getX(), bot.getY(), neighborRadius)) {
                near++;
            }
        }
        if (near >= needed) {
            return;
        }
        int toMove = Math.min(needed - near, bots.size());
        Random rand = new Random(Objects.hash(room.getId(), cx, cy));
        int attempts = 0;
        for (RoomBot bot : bots) {
            if (toMove <= 0) {
                break;
            }
            if (bot.getX() != null && bot.getY() != null
                && withinRadius(cx, cy, bot.getX(), bot.getY(), neighborRadius)) {
                continue;
            }
            int[] pos = randomNearbySpot(room, tiles, cx, cy, rand);
            if (pos != null) {
                bot.setX(pos[0]);
                bot.setY(pos[1]);
                botRepository.save(bot);
                toMove--;
            }
            attempts++;
            if (attempts > bots.size() * 3) {
                break;
            }
        }
    }

    private int[] randomNearbySpot(Room room, int[][] tiles, int cx, int cy, Random rand) {
        int h = tiles.length;
        int w = tiles[0].length;
        for (int i = 0; i < 200; i++) {
            int dx = rand.nextInt(neighborRadius * 2 + 1) - neighborRadius;
            int dy = rand.nextInt(neighborRadius * 2 + 1) - neighborRadius;
            int x = Math.max(0, Math.min(w - 1, cx + dx));
            int y = Math.max(0, Math.min(h - 1, cy + dy));
            if (canOccupy(room, x, y, "")) {
                return new int[] { x, y };
            }
        }
        return null;
    }

    private List<RoomParticipantInfo> buildParticipants(Room room) {
        List<RoomParticipantInfo> infos = new ArrayList<>();
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            RoomParticipantInfo info = new RoomParticipantInfo();
            info.setId(participant.getId().toString());
            info.setProfileId(participant.getProfile().getId().toString());
            info.setDisplayName(participant.getDisplayName());
            info.setRole(participant.getRole());
            info.setLens(participant.getLens());
            info.setX(participant.getX() == null ? -1 : participant.getX());
            info.setY(participant.getY() == null ? -1 : participant.getY());
            info.setMissedVotes(participant.getMissedVotes());
            info.setLockedUntil(participant.getLockedUntil());
            infos.add(info);
        }
        return infos;
    }

    private List<RoomBotInfo> buildBots(Room room) {
        List<RoomBotInfo> infos = new ArrayList<>();
        int[][] tiles = stateRepository.findByRoom(room)
            .map(s -> fromJsonTiles(s.getTilesJson()))
            .orElse(new int[0][0]);
        boolean[][] highways = stateRepository.findByRoom(room)
            .map(s -> fromJsonHighways(s.getHighwaysJson()))
            .orElse(new boolean[0][0]);
        for (RoomBot bot : botRepository.findByRoom(room)) {
            RoomBotInfo info = new RoomBotInfo();
            info.setId(bot.getId().toString());
            info.setType(bot.getType());
            info.setDisplayName(botDisplayName(bot));
            info.setX(bot.getX() == null ? -1 : bot.getX());
            info.setY(bot.getY() == null ? -1 : bot.getY());
            Map<String, Double> profile = readBotProfile(bot);
            info.setProfile(profile);
            if (bot.getX() != null && bot.getY() != null && tiles.length > 0) {
                LocalStats stats = localStats(tiles, highways, bot.getX(), bot.getY());
                info.setRole(localRole(stats));
            } else {
                info.setRole("UNKNOWN");
            }
            infos.add(info);
        }
        return infos;
    }

    private List<RoomProposalInfo> buildProposals(Room room, RoomParticipant participant) {
        List<RoomProposalInfo> infos = new ArrayList<>();
        String participantKey = participant == null ? null : "P:" + participant.getId();
        for (RoomProposal proposal : proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room)) {
            RoomProposalInfo info = new RoomProposalInfo();
            info.setId(proposal.getId().toString());
            if (proposal.getProposer() != null) {
                info.setProposerName(proposal.getProposer().getDisplayName());
            } else {
                info.setProposerName(proposal.getBotLabel() == null ? "Bot" : proposal.getBotLabel());
            }
            info.setStatus(proposal.getStatus());
            info.setApprovals(proposal.getApprovals());
            info.setRejections(proposal.getRejections());
            info.setCreatedAt(proposal.getCreatedAt());
            info.setRequiredVotes(proposal.getRequiredVotes());
            info.setNeighborCount(readNeighborIds(proposal).size());
            info.setExpiresAt(proposal.getExpiresAt());
            info.setAnchorX(proposal.getAnchorX());
            info.setAnchorY(proposal.getAnchorY());
            info.setBotGenerated(Boolean.TRUE.equals(proposal.isBotGenerated()));
            info.setBotLabel(proposal.getBotLabel());
            if (proposal.getBotProposer() != null) {
                info.setBotId(proposal.getBotProposer().getId().toString());
            }
            Map<String, Integer> summary = readBotVoteSummary(proposal);
            if (summary != null) {
                info.setBotYes(summary.getOrDefault("botYes", 0));
                info.setBotNo(summary.getOrDefault("botNo", 0));
            }
            info.setTileType(proposalDeltaType(proposal));
            if (participantKey != null) {
                info.setCanVote(canVoteNow(room, proposal, participant));
                info.setOwner(proposal.getProposer() != null && proposal.getProposer().getId().equals(participant.getId()));
            }
            infos.add(info);
        }
        return infos;
    }

    @Transactional
    private void cleanupOldProposals(Room room) {
        if (!proposalTableLock.tryLock()) {
            return;
        }
        try {
        Instant now = Instant.now();
        proposalRepository.rejectExpiredPending(room, now);
        // Bot votes are no longer stored as rows.
        } finally {
            proposalTableLock.unlock();
        }
    }

    private RoomStateResponse toRoomStateResponse(Room room, RoomState state, RoomParticipant participant) {
        CachedRoomState cached = roomStateCache.get(room.getId());
        RoomStateResponse response = new RoomStateResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        if (cached != null) {
            response.setTiles(cached.tiles);
            response.setHighways(cached.highways);
            response.setSharedTokens(readSharedTokensJson(cached.sharedTokensJson));
            response.setSharedQuests(readSharedQuestsJson(cached.sharedQuestsJson));
            response.setMemoryMarkers(readMemoryMarkersJson(cached.memoryMarkersJson));
            response.setStateHash(cached.stateHash);
            response.setPreviousStateHash(cached.previousStateHash);
        } else {
            response.setTiles(fromJsonTiles(state.getTilesJson()));
            response.setHighways(fromJsonHighways(state.getHighwaysJson()));
            response.setSharedTokens(readSharedTokens(state));
            response.setSharedQuests(readSharedQuests(state));
            response.setMemoryMarkers(readMemoryMarkers(state));
            response.setStateHash(state.getStateHash());
            response.setPreviousStateHash(state.getPreviousStateHash());
        }
        response.setParticipants(buildParticipants(room));
        response.setBots(buildBots(room));
        response.setProposals(buildProposals(room, participant));
        response.setTurnOwnerParticipantId(room.getTurnOwnerParticipantId() == null ? null : room.getTurnOwnerParticipantId().toString());
        response.setTurnExpiresAt(room.getTurnExpiresAt());
        response.setLockUntil(room.getLockUntil());
        response.setNeighborRadius(neighborRadius);
        response.setMinNeighbors(minNeighbors);
        response.setDensityAreaSize(densityAreaSize);
        response.setMaxOccupants(maxOccupants);
        if (participant != null) {
            response.setDismissedPollIds(readDismissedPollIds(participant));
        }
        return response;
    }

    private List<String> readDismissedPollIds(RoomParticipant participant) {
        if (participant.getDismissedPollsJson() == null || participant.getDismissedPollsJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(participant.getDismissedPollsJson(), new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private List<QuestItem> defaultSharedQuests() {
        List<QuestItem> quests = new ArrayList<>();
        QuestItem q1 = new QuestItem();
        q1.setId("shared-clear-path");
        q1.setTitle("Create a clear path between two forest regions");
        q1.setHint("Open a corridor without fragmenting the forest.");
        q1.setCompleted(false);
        quests.add(q1);
        QuestItem q2 = new QuestItem();
        q2.setId("shared-stabilize-zone");
        q2.setTitle("Stabilize a tense zone");
        q2.setHint("Reduce extreme contrast across tiles.");
        q2.setCompleted(false);
        quests.add(q2);
        QuestItem q3 = new QuestItem();
        q3.setId("shared-coherence");
        q3.setTitle("Increase terrain coherence by 10%");
        q3.setHint("Grow connected clusters together.");
        q3.setCompleted(false);
        quests.add(q3);
        return quests;
    }

    private SharedTokens readSharedTokens(RoomState state) {
        return readSharedTokensJson(state.getSharedTokensJson());
    }

    private void updateSharedTokens(RoomState state, int insight, int stability, int energy) {
        SharedTokens tokens = readSharedTokens(state);
        tokens.setInsight(tokens.getInsight() + insight);
        tokens.setStability(tokens.getStability() + stability);
        tokens.setEnergy(tokens.getEnergy() + energy);
        state.setSharedTokensJson(toJson(tokens));
    }

    private List<QuestItem> readSharedQuests(RoomState state) {
        return readSharedQuestsJson(state.getSharedQuestsJson());
    }

    private List<MemoryMarker> readMemoryMarkers(RoomState state) {
        return readMemoryMarkersJson(state.getMemoryMarkersJson());
    }

    private SharedTokens readSharedTokensJson(String json) {
        if (json == null || json.isBlank()) {
            return new SharedTokens(0, 0, 0);
        }
        try {
            return objectMapper.readValue(json, SharedTokens.class);
        } catch (JsonProcessingException e) {
            return new SharedTokens(0, 0, 0);
        }
    }

    private List<QuestItem> readSharedQuestsJson(String json) {
        if (json == null || json.isBlank()) {
            return defaultSharedQuests();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<QuestItem>>() {});
        } catch (JsonProcessingException e) {
            return defaultSharedQuests();
        }
    }

    private List<MemoryMarker> readMemoryMarkersJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<MemoryMarker>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private void updateSharedQuests(RoomState state, int[][] tiles) {
        List<QuestItem> quests = readSharedQuests(state);
        double forestShare = tileShare(tiles, 1);
        double clearShare = tileShare(tiles, 0);
        double coherence = computeCoherence(tiles);
        quests.forEach(q -> {
            if (q.isCompleted()) {
                return;
            }
            if ("shared-clear-path".equals(q.getId()) && clearShare > 0.32 && forestShare > 0.25) {
                q.setCompleted(true);
            }
            if ("shared-stabilize-zone".equals(q.getId()) && Math.abs(forestShare - clearShare) < 0.12) {
                q.setCompleted(true);
            }
            if ("shared-coherence".equals(q.getId()) && coherence > 0.58) {
                q.setCompleted(true);
            }
        });
        state.setSharedQuestsJson(toJson(quests));
    }

    private double tileShare(int[][] tiles, int target) {
        int total = 0;
        int matches = 0;
        for (int[] row : tiles) {
            for (int val : row) {
                total++;
                if (val == target) {
                    matches++;
                }
            }
        }
        return total == 0 ? 0 : (double) matches / total;
    }

    private double computeCoherence(int[][] tiles) {
        int matches = 0;
        int checks = 0;
        int h = tiles.length;
        int w = tiles[0].length;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x + 1 < w) {
                    checks++;
                    if (tiles[y][x + 1] == tiles[y][x]) {
                        matches++;
                    }
                }
                if (y + 1 < h) {
                    checks++;
                    if (tiles[y + 1][x] == tiles[y][x]) {
                        matches++;
                    }
                }
            }
        }
        return checks == 0 ? 0 : (double) matches / checks;
    }

    private String buildBotPrompt(String type, RoomState state) {
        int[][] tiles = fromJsonTiles(state.getTilesJson());
        double forest = tileShare(tiles, 1);
        double clear = tileShare(tiles, 0);
        double water = tileShare(tiles, 2);
        double industry = tileShare(tiles, 3);
        double coherence = computeCoherence(tiles);

        switch (type) {
            case "OBSERVER":
                if (coherence < 0.5) {
                    return "The Observer notices tension. What feels fragmented here?";
                }
                return "The Observer sees alignment growing. What does that give you?";
            case "COPILOT":
                if (clear < 0.3) {
                    return "Co-Pilot suggests opening a small corridor to reduce pressure.";
                }
                if (forest < 0.3) {
                    return "Co-Pilot suggests anchoring a forest cluster to steady the flow.";
                }
                return "Co-Pilot recommends reinforcing a stable region before expanding.";
            case "TENSION":
                if (forest > clear) {
                    return "Tension Bot nudges: add space where density feels heavy.";
                }
                return "Tension Bot nudges: add depth where openness feels thin.";
            case "ECHO":
                if (state.getPreviousStateHash() != null && !state.getPreviousStateHash().equals(state.getStateHash())) {
                    return "Echo Bot: Your pattern shifted since the last state. What feels different now?";
                }
                return "Echo Bot: This looks familiar. What are you repeating on purpose?";
            case "QUEST":
                if (water < 0.12) {
                    return "Quest Bot: Introduce a water pocket to unlock emotional flow.";
                }
                if (industry < 0.06) {
                    return "Quest Bot: Test a small industry block to energize structure.";
                }
                return "Quest Bot: Strengthen coherence by linking two clearings.";
            default:
                return "The room hums softly. Nothing to add yet.";
        }
    }

    private void assignSpawnPosition(RoomParticipant participant, int[][] tiles) {
        int h = tiles.length;
        int w = tiles[0].length;
        Random rand = new Random(Objects.hash(participant.getDisplayName(), h, w));
        for (int i = 0; i < 1000; i++) {
            int x = rand.nextInt(w);
            int y = rand.nextInt(h);
            if (canOccupy(participant.getRoom(), x, y, participant.getId() == null ? "" : participant.getId().toString())) {
                participant.setX(x);
                participant.setY(y);
                return;
            }
        }
        participant.setX(w / 2);
        participant.setY(h / 2);
    }

    private void assignBotSpawn(RoomBot bot, int[][] tiles) {
        int h = tiles.length;
        int w = tiles[0].length;
        Random rand = new Random(Objects.hash(bot.getType(), h, w));
        for (int i = 0; i < 1000; i++) {
            int x = rand.nextInt(w);
            int y = rand.nextInt(h);
            if (canOccupy(bot.getRoom(), x, y, bot.getId() == null ? "" : bot.getId().toString())) {
                bot.setX(x);
                bot.setY(y);
                return;
            }
        }
        bot.setX(w / 2);
        bot.setY(h / 2);
    }

    private String botDisplayName(RoomBot bot) {
        String type = bot.getType();
        if (type.startsWith("AGENT-")) {
            return "Neighbor " + type.substring("AGENT-".length());
        }
        return type.substring(0, 1) + type.substring(1).toLowerCase();
    }

    private String botArchetype(RoomBot bot) {
        int idx = Math.abs(Objects.hash(bot.getId(), bot.getType())) % BOT_ARCHETYPES.size();
        return BOT_ARCHETYPES.get(idx);
    }

    private Map<String, Double> randomBotProfile(int seed) {
        Random rand = new Random(seed);
        double forest = 0.2 + rand.nextDouble() * 0.6;
        double water = 0.2 + rand.nextDouble() * 0.6;
        double clear = 0.2 + rand.nextDouble() * 0.6;
        double industry = 0.2 + rand.nextDouble() * 0.6;
        double consensus = 0.55 + rand.nextDouble() * 0.4;
        double mobility = 0.3 + rand.nextDouble() * 0.6;
        double risk = 0.1 + rand.nextDouble() * 0.4;
        double abstain = rand.nextDouble() * 0.02;
        double conflict = rand.nextDouble() * 0.02;
        double drift = 0.001 + rand.nextDouble() * 0.004;
        Map<String, Double> profile = new HashMap<>();
        profile.put("forest", forest);
        profile.put("water", water);
        profile.put("clear", clear);
        profile.put("industry", industry);
        profile.put("consensus", consensus);
        profile.put("mobility", mobility);
        profile.put("risk", risk);
        profile.put("abstain", abstain);
        profile.put("conflict", conflict);
        profile.put("drift", drift);
        return profile;
    }

    private static class LocalStats {
        final int total;
        final double forest;
        final double water;
        final double clear;
        final double industry;
        final double road;

        LocalStats(int total, double forest, double water, double clear, double industry, double road) {
            this.total = total;
            this.forest = forest;
            this.water = water;
            this.clear = clear;
            this.industry = industry;
            this.road = road;
        }
    }

    private LocalStats localStats(int[][] tiles, boolean[][] highways, int anchorX, int anchorY) {
        int[] view = computeViewBounds(anchorX, anchorY, tiles);
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

    private double prosperityScore(LocalStats stats, String archetype) {
        double[] targets = targetsFor(archetype);
        double delta =
            Math.abs(stats.forest - targets[0]) +
            Math.abs(stats.water - targets[1]) +
            Math.abs(stats.clear - targets[2]) +
            Math.abs(stats.industry - targets[3]) +
            Math.abs(stats.road - targets[4]);
        return delta;
    }

    private double[] targetsFor(String archetype) {
        switch (archetype) {
            case "STEWARD":
                return new double[] { 0.36, 0.16, 0.32, 0.12, 0.04 };
            case "BUILDER":
                return new double[] { 0.22, 0.10, 0.30, 0.30, 0.08 };
            case "FLOW_KEEPER":
                return new double[] { 0.24, 0.20, 0.36, 0.16, 0.04 };
            case "PRESERVATIONIST":
                return new double[] { 0.42, 0.18, 0.26, 0.10, 0.04 };
            case "PRAGMATIST":
                return new double[] { 0.30, 0.15, 0.34, 0.17, 0.04 };
            case "CONTRARIAN":
                return new double[] { 0.28, 0.16, 0.34, 0.18, 0.04 };
            case "NEIGHBOR_MIRROR":
                return new double[] { 0.30, 0.14, 0.36, 0.16, 0.04 };
            default:
                return new double[] { 0.30, 0.15, 0.34, 0.17, 0.04 };
        }
    }

    private boolean prefersType(String archetype, int type) {
        return switch (archetype) {
            case "STEWARD" -> (type == 1 || type == 2);
            case "BUILDER" -> (type == 3 || type == 0);
            case "FLOW_KEEPER" -> (type == 2 || type == 0);
            case "PRESERVATIONIST" -> (type == 1);
            case "PRAGMATIST" -> true;
            case "CONTRARIAN" -> true;
            case "NEIGHBOR_MIRROR" -> true;
            default -> true;
        };
    }

    private boolean dislikesType(String archetype, int type, LocalStats before) {
        switch (archetype) {
            case "STEWARD":
                return type == 3 && (before.forest < 0.25 || before.water < 0.10);
            case "BUILDER":
                return (type == 1 || type == 2) && before.industry < 0.18;
            case "FLOW_KEEPER":
                return type == 3 && before.water < 0.12;
            case "PRESERVATIONIST":
                return (type == 0 || type == 3) && before.forest < 0.32;
            case "CONTRARIAN":
                return false;
            case "NEIGHBOR_MIRROR":
                return false;
            case "PRAGMATIST":
            default:
                return false;
        }
    }

    private boolean violatesCommunityRules(Room room, LocalStats after, boolean[][] highways, int[][] tiles, int anchorX, int anchorY, double adherence) {
        if (after.total == 0) {
            return false;
        }
        double maxShare = Math.max(Math.max(after.forest, after.water), Math.max(after.clear, after.industry));
        double gate = adherence * 0.6;
        if (maxShare > 0.55 && Math.random() < gate) {
            return true;
        }
        double nature = after.forest + after.water;
        if (nature < 0.18 && Math.random() < gate) {
            return true;
        }
        if (after.industry < 0.04 && Math.random() < gate) {
            return true;
        }
        if (!hasAccessConnectivity(tiles, highways, anchorX, anchorY) && Math.random() < gate) {
            return true;
        }
        if (isRapidOscillation(room) && Math.random() < gate) {
            return true;
        }
        return false;
    }

    private boolean hasAccessConnectivity(int[][] tiles, boolean[][] highways, int anchorX, int anchorY) {
        int[] view = computeViewBounds(anchorX, anchorY, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        boolean[][] visited = new boolean[viewH][viewW];
        int totalAccess = 0;
        int largest = 0;
        for (int y = 0; y < viewH; y++) {
            for (int x = 0; x < viewW; x++) {
                int gx = viewX + x;
                int gy = viewY + y;
                if (!isAccessTile(tiles, highways, gx, gy)) {
                    continue;
                }
                totalAccess++;
                if (visited[y][x]) {
                    continue;
                }
                int size = floodAccess(tiles, highways, viewX, viewY, viewW, viewH, x, y, visited);
                largest = Math.max(largest, size);
            }
        }
        if (totalAccess == 0) {
            return true;
        }
        double ratio = (double) largest / totalAccess;
        return ratio >= 0.25;
    }

    private int floodAccess(int[][] tiles, boolean[][] highways, int viewX, int viewY, int viewW, int viewH, int sx, int sy, boolean[][] visited) {
        int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { sx, sy });
        visited[sy][sx] = true;
        int count = 0;
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            count++;
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (nx < 0 || ny < 0 || nx >= viewW || ny >= viewH) {
                    continue;
                }
                if (visited[ny][nx]) {
                    continue;
                }
                int gx = viewX + nx;
                int gy = viewY + ny;
                if (!isAccessTile(tiles, highways, gx, gy)) {
                    continue;
                }
                visited[ny][nx] = true;
                queue.add(new int[] { nx, ny });
            }
        }
        return count;
    }

    private boolean isAccessTile(int[][] tiles, boolean[][] highways, int x, int y) {
        int v = tiles[y][x];
        return v == 0 || v == 4 || (highways.length > 0 && highways[y][x]);
    }

    private boolean isRapidOscillation(Room room) {
        List<RoomProposal> recent = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
        int found = 0;
        Integer a = null;
        Integer b = null;
        for (RoomProposal proposal : recent) {
            Integer t = proposalDeltaType(proposal);
            if (t == null) continue;
            found++;
            if (a == null) {
                a = t;
            } else {
                b = t;
                break;
            }
        }
        if (a == null || b == null) {
            return false;
        }
        return !a.equals(b);
    }

    private boolean isTrendMajority(Room room, int type) {
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

    private Integer lastHumanProposalType(Room room) {
        List<RoomProposal> recent = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
        for (RoomProposal proposal : recent) {
            if (Boolean.TRUE.equals(proposal.isBotGenerated())) {
                continue;
            }
            return proposalDeltaType(proposal);
        }
        return null;
    }

    private Integer proposalDeltaType(RoomProposal proposal) {
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
                return switch (s) {
                    case "forest" -> 1;
                    case "water" -> 2;
                    case "industry" -> 3;
                    case "highway" -> 4;
                    case "clear" -> 0;
                    default -> null;
                };
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Map<String, Integer> readBotVoteSummary(RoomProposal proposal) {
        if (proposal.getBotVoteSummaryJson() == null || proposal.getBotVoteSummaryJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(proposal.getBotVoteSummaryJson(), new TypeReference<Map<String, Integer>>() {});
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Double> readBotProfile(RoomBot bot) {
        if (bot.getProfileJson() == null || bot.getProfileJson().isBlank()) {
            return randomBotProfile(Objects.hash(bot.getType()));
        }
        try {
            return objectMapper.readValue(bot.getProfileJson(), new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            return randomBotProfile(Objects.hash(bot.getType()));
        }
    }

    private void maybeDriftProfile(RoomBot bot, Map<String, Double> profile, LocalStats stats) {
        double drift = profile.getOrDefault("drift", 0.002);
        if (Math.random() > 0.2) {
            return;
        }
        String role = localRole(stats);
        adjustProfile(profile, role, drift);
        clampProfile(profile);
        bot.setProfileJson(toJson(profile));
        botRepository.save(bot);
    }

    private String localRole(LocalStats stats) {
        double fNeed = Math.max(0, 0.30 - stats.forest);
        double wNeed = Math.max(0, 0.14 - stats.water);
        double cNeed = Math.max(0, 0.34 - stats.clear);
        double iNeed = Math.max(0, 0.14 - stats.industry);
        double max = fNeed;
        String role = "FOREST";
        if (wNeed > max) { max = wNeed; role = "WATER"; }
        if (cNeed > max) { max = cNeed; role = "CLEAR"; }
        if (iNeed > max) { max = iNeed; role = "INDUSTRY"; }
        return role;
    }

    private void adjustProfile(Map<String, Double> profile, String role, double drift) {
        switch (role) {
            case "FOREST" -> profile.put("forest", profile.getOrDefault("forest", 0.5) + drift);
            case "WATER" -> profile.put("water", profile.getOrDefault("water", 0.5) + drift);
            case "CLEAR" -> profile.put("clear", profile.getOrDefault("clear", 0.5) + drift);
            case "INDUSTRY" -> profile.put("industry", profile.getOrDefault("industry", 0.5) + drift);
            default -> { }
        }
    }

    private void clampProfile(Map<String, Double> profile) {
        profile.put("forest", clamp01(profile.getOrDefault("forest", 0.5)));
        profile.put("water", clamp01(profile.getOrDefault("water", 0.5)));
        profile.put("clear", clamp01(profile.getOrDefault("clear", 0.5)));
        profile.put("industry", clamp01(profile.getOrDefault("industry", 0.5)));
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static class IntegralMaps {
        final int[][] forest;
        final int[][] water;
        final int[][] clear;
        final int[][] industry;
        final int[][] road;

        IntegralMaps(int[][] forest, int[][] water, int[][] clear, int[][] industry, int[][] road) {
            this.forest = forest;
            this.water = water;
            this.clear = clear;
            this.industry = industry;
            this.road = road;
        }
    }

    private IntegralMaps buildIntegrals(int[][] tiles, boolean[][] highways) {
        int h = tiles.length;
        int w = tiles[0].length;
        int[][] forest = new int[h + 1][w + 1];
        int[][] water = new int[h + 1][w + 1];
        int[][] clear = new int[h + 1][w + 1];
        int[][] industry = new int[h + 1][w + 1];
        int[][] road = new int[h + 1][w + 1];
        for (int y = 1; y <= h; y++) {
            for (int x = 1; x <= w; x++) {
                int v = tiles[y - 1][x - 1];
                int f = (v == 1) ? 1 : 0;
                int wa = (v == 2) ? 1 : 0;
                int c = (v == 0) ? 1 : 0;
                int i = (v == 3) ? 1 : 0;
                int r = ((highways.length > 0 && highways[y - 1][x - 1]) || v == 4) ? 1 : 0;
                forest[y][x] = forest[y - 1][x] + forest[y][x - 1] - forest[y - 1][x - 1] + f;
                water[y][x] = water[y - 1][x] + water[y][x - 1] - water[y - 1][x - 1] + wa;
                clear[y][x] = clear[y - 1][x] + clear[y][x - 1] - clear[y - 1][x - 1] + c;
                industry[y][x] = industry[y - 1][x] + industry[y][x - 1] - industry[y - 1][x - 1] + i;
                road[y][x] = road[y - 1][x] + road[y][x - 1] - road[y - 1][x - 1] + r;
            }
        }
        return new IntegralMaps(forest, water, clear, industry, road);
    }

    private int rectSum(int[][] integral, int x0, int y0, int x1, int y1) {
        return integral[y1][x1] - integral[y0][x1] - integral[y1][x0] + integral[y0][x0];
    }

    private CachedMap getCachedMap(Room room, RoomState state, int[][] tiles, boolean[][] highways) {
        String key = hashTiles(tiles) + "|" + Arrays.deepHashCode(highways);
        CachedMap cached = mapCache.get(room.getId());
        if (cached != null && key.equals(cached.key)) {
            return cached;
        }
        CachedMap built = buildCachedMap(tiles, highways, key);
        mapCache.put(room.getId(), built);
        return built;
    }

    private CachedMap buildCachedMap(int[][] tiles, boolean[][] highways, String key) {
        int h = tiles.length;
        int w = tiles[0].length;
        IntegralMaps integrals = buildIntegrals(tiles, highways);
        int viewW = Math.min(w, VISIBLE_WIDTH);
        int viewH = Math.min(h, VISIBLE_HEIGHT);
        int step = Math.max(4, Math.min(12, Math.min(w, h) / 12));
        List<CoarseCell> cells = new ArrayList<>();
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int viewX = Math.max(0, Math.min(w - viewW, x - viewW / 2));
                int viewY = Math.max(0, Math.min(h - viewH, y - viewH / 2));
                int x0 = viewX;
                int y0 = viewY;
                int x1 = viewX + viewW;
                int y1 = viewY + viewH;
                int total = viewW * viewH;
                if (total == 0) continue;
                double forest = rectSum(integrals.forest, x0, y0, x1, y1) / (double) total;
                double water = rectSum(integrals.water, x0, y0, x1, y1) / (double) total;
                double clear = rectSum(integrals.clear, x0, y0, x1, y1) / (double) total;
                double industry = rectSum(integrals.industry, x0, y0, x1, y1) / (double) total;
                double road = rectSum(integrals.road, x0, y0, x1, y1) / (double) total;
                cells.add(new CoarseCell(x, y, forest, water, clear, industry, road));
            }
        }
        return new CachedMap(key, integrals, cells, viewW, viewH, step);
    }

    private CachedRoomState getCachedRoomState(Room room, RoomState state) {
        CachedRoomState cached = roomStateCache.get(room.getId());
        if (cached != null) {
            return cached;
        }
        int[][] tiles = fromJsonTiles(state.getTilesJson());
        boolean[][] highways = fromJsonHighways(state.getHighwaysJson());
        CachedRoomState built = new CachedRoomState(
            tiles,
            highways,
            state.getSharedTokensJson(),
            state.getSharedQuestsJson(),
            state.getMemoryMarkersJson(),
            state.getStateHash(),
            state.getPreviousStateHash()
        );
        roomStateCache.put(room.getId(), built);
        return built;
    }

    private int[][] getTilesForRoom(Room room, RoomState state) {
        return getCachedRoomState(room, state).tiles;
    }

    private boolean[][] getHighwaysForRoom(Room room, RoomState state) {
        return getCachedRoomState(room, state).highways;
    }

    private void updateCacheFromState(Room room, RoomState state) {
        CachedRoomState cached = new CachedRoomState(
            fromJsonTiles(state.getTilesJson()),
            fromJsonHighways(state.getHighwaysJson()),
            state.getSharedTokensJson(),
            state.getSharedQuestsJson(),
            state.getMemoryMarkersJson(),
            state.getStateHash(),
            state.getPreviousStateHash()
        );
        cached.dirty = false;
        cached.lastFlushAt = Instant.now();
        roomStateCache.put(room.getId(), cached);
    }

    private static class CoarseCell {
        final int x;
        final int y;
        final double forest;
        final double water;
        final double clear;
        final double industry;
        final double road;

        CoarseCell(int x, int y, double forest, double water, double clear, double industry, double road) {
            this.x = x;
            this.y = y;
            this.forest = forest;
            this.water = water;
            this.clear = clear;
            this.industry = industry;
            this.road = road;
        }
    }

    private static class CachedMap {
        final String key;
        final IntegralMaps integrals;
        final List<CoarseCell> cells;
        final int viewW;
        final int viewH;
        final int step;

        CachedMap(String key, IntegralMaps integrals, List<CoarseCell> cells, int viewW, int viewH, int step) {
            this.key = key;
            this.integrals = integrals;
            this.cells = cells;
            this.viewW = viewW;
            this.viewH = viewH;
            this.step = step;
        }
    }

    private static class CachedRoomState {
        final int[][] tiles;
        final boolean[][] highways;
        String sharedTokensJson;
        String sharedQuestsJson;
        String memoryMarkersJson;
        String stateHash;
        String previousStateHash;
        boolean dirty;
        Instant lastFlushAt;

        CachedRoomState(int[][] tiles,
                        boolean[][] highways,
                        String sharedTokensJson,
                        String sharedQuestsJson,
                        String memoryMarkersJson,
                        String stateHash,
                        String previousStateHash) {
            this.tiles = tiles;
            this.highways = highways;
            this.sharedTokensJson = sharedTokensJson;
            this.sharedQuestsJson = sharedQuestsJson;
            this.memoryMarkersJson = memoryMarkersJson;
            this.stateHash = stateHash;
            this.previousStateHash = previousStateHash;
            this.dirty = false;
            this.lastFlushAt = Instant.now();
        }
    }

    private int[] findBestProfileLocation(RoomBot bot, int[][] tiles, boolean[][] highways, CachedMap cached) {
        Map<String, Double> profile = readBotProfile(bot);
        double tForest = profile.getOrDefault("forest", 0.25);
        double tWater = profile.getOrDefault("water", 0.15);
        double tClear = profile.getOrDefault("clear", 0.35);
        double tIndustry = profile.getOrDefault("industry", 0.15);
        double tRoad = 0.02 + profile.getOrDefault("mobility", 0.5) * 0.15;
        int h = tiles.length;
        int w = tiles[0].length;
        double bestScore = Double.MAX_VALUE;
        int bestX = bot.getX();
        int bestY = bot.getY();
        for (CoarseCell cell : cached.cells) {
            double score =
                Math.abs(cell.forest - tForest) +
                Math.abs(cell.water - tWater) +
                Math.abs(cell.clear - tClear) +
                Math.abs(cell.industry - tIndustry) +
                Math.abs(cell.road - tRoad);
            int dist = Math.abs(cell.x - bot.getX()) + Math.abs(cell.y - bot.getY());
            score += dist * 0.001;
            if (score < bestScore) {
                bestScore = score;
                bestX = cell.x;
                bestY = cell.y;
            }
        }
        int refine = Math.max(4, cached.step);
        int viewW = cached.viewW;
        int viewH = cached.viewH;
        for (int y = Math.max(0, bestY - refine); y <= Math.min(h - 1, bestY + refine); y++) {
            for (int x = Math.max(0, bestX - refine); x <= Math.min(w - 1, bestX + refine); x++) {
                int viewX = Math.max(0, Math.min(w - viewW, x - viewW / 2));
                int viewY = Math.max(0, Math.min(h - viewH, y - viewH / 2));
                int x0 = viewX;
                int y0 = viewY;
                int x1 = viewX + viewW;
                int y1 = viewY + viewH;
                int total = viewW * viewH;
                if (total == 0) continue;
                double forest = rectSum(cached.integrals.forest, x0, y0, x1, y1) / (double) total;
                double water = rectSum(cached.integrals.water, x0, y0, x1, y1) / (double) total;
                double clear = rectSum(cached.integrals.clear, x0, y0, x1, y1) / (double) total;
                double industry = rectSum(cached.integrals.industry, x0, y0, x1, y1) / (double) total;
                double road = rectSum(cached.integrals.road, x0, y0, x1, y1) / (double) total;
                double score =
                    Math.abs(forest - tForest) +
                    Math.abs(water - tWater) +
                    Math.abs(clear - tClear) +
                    Math.abs(industry - tIndustry) +
                    Math.abs(road - tRoad);
                int dist = Math.abs(x - bot.getX()) + Math.abs(y - bot.getY());
                score += dist * 0.001;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[] { bestX, bestY };
    }

    private boolean[][] fromJsonHighways(String json) {
        if (json == null || json.isBlank()) {
            return new boolean[0][0];
        }
        try {
            return objectMapper.readValue(json, boolean[][].class);
        } catch (JsonProcessingException e) {
            return new boolean[0][0];
        }
    }

    private void tickBots(Room room, RoomState state) {
        ReentrantLock lock = botLocks.computeIfAbsent(room.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            int[][] tiles = getTilesForRoom(room, state);
            boolean[][] highways = getHighwaysForRoom(room, state);
            CachedMap cached = getCachedMap(room, state, tiles, highways);
            List<RoomProposal> recentProposals = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
            List<RoomBot> moved = new ArrayList<>();
            for (RoomBot bot : botRepository.findByRoom(room)) {
                Map<String, Double> profile = readBotProfile(bot);
                if (bot.getX() == null || bot.getY() == null) {
                    assignBotSpawn(bot, tiles);
                    moved.add(bot);
                    continue;
                }
                if (shouldRelocate(bot, room, tiles, highways, recentProposals)) {
                    int[] step = stepTowardProfileTarget(bot, tiles, highways, cached);
                    if (step != null && canOccupy(room, step[0], step[1], bot.getId().toString())) {
                        bot.setX(step[0]);
                        bot.setY(step[1]);
                        moved.add(bot);
                    }
                    continue;
                }
                if (Math.random() > profile.getOrDefault("mobility", 0.5)) {
                    continue;
                }
                int[] next = pickBotStep(bot.getX(), bot.getY(), tiles, highways, profile);
                if (next != null && canOccupy(room, next[0], next[1], bot.getId().toString())) {
                    bot.setX(next[0]);
                    bot.setY(next[1]);
                    moved.add(bot);
                }
            }
            if (!moved.isEmpty()) {
                botRepository.saveAll(moved);
            }
        } finally {
            lock.unlock();
        }
    }

    private int[] pickBotStep(int x, int y, int[][] tiles, boolean[][] highways, Map<String, Double> profile) {
        int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
        double best = -Double.MAX_VALUE;
        int[] bestPos = null;
        for (int[] d : dirs) {
            int nx = x + d[0];
            int ny = y + d[1];
            if (ny < 0 || ny >= tiles.length || nx < 0 || nx >= tiles[0].length) {
                continue;
            }
            int type = tiles[ny][nx];
            double score = tilePreferenceScore(type, profile);
            if ((highways.length > 0 && highways[ny][nx]) || type == 4) {
                score += 0.2;
            }
            if (score > best) {
                best = score;
                bestPos = new int[] { nx, ny };
            }
        }
        return bestPos;
    }

    private boolean shouldRelocate(RoomBot bot, Room room, int[][] tiles, List<RoomProposal> recent) {
        int cx = bot.getX();
        int cy = bot.getY();
        return shouldRelocate(bot, room, tiles, new boolean[0][0], recent);
    }

    private boolean shouldRelocate(RoomBot bot, Room room, int[][] tiles, boolean[][] highways, List<RoomProposal> recent) {
        int cx = bot.getX();
        int cy = bot.getY();
        int[] view = computeViewBounds(cx, cy, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        int visibleNeighbors = 0;
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            if (participant.getX() != null && participant.getY() != null
                && inView(viewX, viewY, viewW, viewH, participant.getX(), participant.getY())) {
                visibleNeighbors++;
            }
        }
        for (RoomBot other : botRepository.findByRoom(room)) {
            if (other.getId().equals(bot.getId())) continue;
            if (other.getX() != null && other.getY() != null
                && inView(viewX, viewY, viewW, viewH, other.getX(), other.getY())) {
                visibleNeighbors++;
            }
        }
        if (visibleNeighbors < minNeighbors) {
            return true;
        }
        int rejected = 0;
        for (RoomProposal proposal : recent) {
            if (!"REJECTED".equals(proposal.getStatus())) {
                continue;
            }
            Integer ax = proposal.getAnchorX();
            Integer ay = proposal.getAnchorY();
            if (ax == null || ay == null) continue;
            if (!inView(viewX, viewY, viewW, viewH, ax, ay)) continue;
            if (!readNeighborIds(proposal).contains("B:" + bot.getId())) continue;
            int[][] baseTiles = tiles;
            int[][] proposed = applyDeltaToTiles(baseTiles, proposal);
            BotVote vote = botVoteDecision(room, bot, baseTiles, proposed, Boolean.TRUE.equals(proposal.isBotGenerated()));
            if (vote == BotVote.ABSTAIN) {
                continue;
            }
            if (vote == BotVote.REJECT) {
                rejected++;
            } else {
                return false;
            }
            if (rejected >= 5) {
                return true;
            }
        }
        return false;
    }

    private int[] stepTowardProfileTarget(RoomBot bot, int[][] tiles, boolean[][] highways, CachedMap cached) {
        int[] best = findBestProfileLocation(bot, tiles, highways, cached);
        if (best == null) {
            return null;
        }
        int dx = best[0] - bot.getX();
        int dy = best[1] - bot.getY();
        int stepX = bot.getX();
        int stepY = bot.getY();
        if (dx == 0 && dy == 0) {
            return null;
        }
        List<int[]> candidates = new ArrayList<>();
        if (dx != 0) {
            candidates.add(new int[] { bot.getX() + Integer.signum(dx), bot.getY() });
        }
        if (dy != 0) {
            candidates.add(new int[] { bot.getX(), bot.getY() + Integer.signum(dy) });
        }
        int[] bestStep = null;
        for (int[] c : candidates) {
            if (c[0] < 0 || c[0] >= tiles[0].length || c[1] < 0 || c[1] >= tiles.length) {
                continue;
            }
            if (bestStep == null) {
                bestStep = c;
            }
            if ((highways.length > 0 && highways[c[1]][c[0]]) || tiles[c[1]][c[0]] == 4) {
                bestStep = c;
                break;
            }
        }
        if (bestStep != null) {
            stepX = bestStep[0];
            stepY = bestStep[1];
        }
        return new int[] { stepX, stepY };
    }

    private double tilePreferenceScore(int type, Map<String, Double> profile) {
        switch (type) {
            case 1:
                return profile.getOrDefault("forest", 0.5);
            case 2:
                return profile.getOrDefault("water", 0.5);
            case 3:
                return profile.getOrDefault("industry", 0.5);
            case 4:
                return profile.getOrDefault("clear", 0.5);
            default:
                return profile.getOrDefault("clear", 0.5);
        }
    }

    private void applyBotVotes(Room room, RoomProposal proposal, int[][] baseTiles, int[][] proposedTiles) {
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
            BotVote vote = botVoteDecision(room, bot, baseTiles, proposedTiles, Boolean.TRUE.equals(proposal.isBotGenerated()));
            if (vote == BotVote.ABSTAIN) {
                continue;
            }
            if (vote == BotVote.APPROVE) {
                proposal.setApprovals(proposal.getApprovals() + 1);
            } else {
                proposal.setRejections(proposal.getRejections() + 1);
            }
            if (vote == BotVote.APPROVE) {
                botYes++;
            } else {
                botNo++;
            }
        }
        if (botYes > 0 || botNo > 0) {
            Map<String, Integer> summary = new HashMap<>();
            summary.put("botYes", botYes);
            summary.put("botNo", botNo);
            proposal.setBotVoteSummaryJson(toJson(summary));
        }
    }

    private enum BotVote { APPROVE, REJECT, ABSTAIN }

    private BotVote botVoteDecision(Room room, RoomBot bot, int[][] baseTiles, int[][] proposedTiles, boolean proposalBotGenerated) {
        Map<String, Double> profile = readBotProfile(bot);
        int[] change = findChangeType(baseTiles, proposedTiles);
        if (change == null) {
            return BotVote.ABSTAIN;
        }
        int type = change[2];
        String archetype = botArchetype(bot);
        RoomState state = stateRepository.findByRoom(room).orElse(null);
        boolean[][] highways = new boolean[0][0];
        if (state != null) {
            highways = getHighwaysForRoom(room, state);
        }
        LocalStats before = localStats(baseTiles, highways, change[0], change[1]);
        LocalStats after = localStats(proposedTiles, highways, change[0], change[1]);
        double beforeScore = prosperityScore(before, archetype);
        double afterScore = prosperityScore(after, archetype);
        double improvement = beforeScore - afterScore;
        double risk = profile.getOrDefault("risk", 0.5);
        double abstainProb = profile.getOrDefault("abstain", 0.08);
        double conflictProb = profile.getOrDefault("conflict", 0.08);
        double consensus = profile.getOrDefault("consensus", 0.5);
        double community = Math.min(1.0, Math.max(0.1, consensus * 0.8 + profile.getOrDefault("mobility", 0.5) * 0.2));

        boolean prefers = prefersType(archetype, type);
        boolean dislikes = dislikesType(archetype, type, before);
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
            if (violatesCommunityRules(room, after, highways, proposedTiles, change[0], change[1], community)) {
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

        double preference = tilePreferenceScore(type, profile);
        boolean approve = improvement >= 0 ? preference >= 0.35 * (1 - consensus) : preference >= 0.55;
        if (!approve && improvement > -0.05 && Math.random() < risk) {
            approve = true;
        }
        if (Math.random() < conflictProb) {
            approve = !approve;
        }
        maybeDriftProfile(bot, profile, before);
        return approve ? BotVote.APPROVE : BotVote.REJECT;
    }

    private int[] findChangeType(int[][] baseTiles, int[][] proposedTiles) {
        for (int y = 0; y < baseTiles.length; y++) {
            for (int x = 0; x < baseTiles[0].length; x++) {
                if (baseTiles[y][x] != proposedTiles[y][x]) {
                    return new int[] { x, y, proposedTiles[y][x] };
                }
            }
        }
        return null;
    }

    private void maybeCreateBotProposal(Room room, RoomState state) {
        long totalStart = System.nanoTime();
        int intervalSeconds = Math.max(1, 60 / botProposalsPerMinute);
        Instant now = Instant.now();
        long fetchStart = System.nanoTime();
        List<RoomBot> bots = botRepository.findByRoom(room);
        long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000;
        if (bots.isEmpty()) {
            return;
        }
        CachedRoomState cached = getCachedRoomState(room, state);
        long tilesStart = System.nanoTime();
        int[][] baseTiles = cached.tiles;
        boolean[][] highways = cached.highways;
        long tilesMs = (System.nanoTime() - tilesStart) / 1_000_000;
        long selectionNs = 0;
        long neighborNs = 0;
        long dbNs = 0;
        int skippedNoPos = 0;
        int skippedRate = 0;
        int skippedAnchor = 0;
        int skippedInvalid = 0;
        int skippedNeighbors = 0;
        int proposedCount = 0;
        List<RoomBot> botsToUpdate = new ArrayList<>();
        int processed = 0;
        for (RoomBot bot : bots) {
            long selectionStart = System.nanoTime();
            processed++;
            if (bot.getX() == null || bot.getY() == null) {
                skippedNoPos++;
                selectionNs += System.nanoTime() - selectionStart;
                continue;
            }
            Instant last = bot.getLastProposalAt();
            if (last != null && last.isAfter(now.minusSeconds(intervalSeconds))) {
                skippedRate++;
                selectionNs += System.nanoTime() - selectionStart;
                continue;
            }
            int[] anchor = pickBotProposalAnchor(bot, baseTiles);
            if (anchor == null) {
                skippedAnchor++;
                selectionNs += System.nanoTime() - selectionStart;
                continue;
            }
            int targetType = dominantTileType(readBotProfile(bot));
            int[][] proposed = applyBotChange(baseTiles, anchor[0], anchor[1], targetType);
            try {
                validateProposalChange(baseTiles, proposed);
            } catch (IllegalStateException e) {
                skippedInvalid++;
                selectionNs += System.nanoTime() - selectionStart;
                continue;
            }
            selectionNs += System.nanoTime() - selectionStart;
            long neighborStart = System.nanoTime();
            List<String> neighbors = findNeighborsForAnchor(room, baseTiles, anchor[0], anchor[1]);
            neighborNs += System.nanoTime() - neighborStart;
            if (neighbors.size() < minNeighbors) {
                skippedNeighbors++;
                continue;
            }
            String previousHash = cached.stateHash;
            if (targetType == 4) {
                highways[anchor[1]][anchor[0]] = true;
                baseTiles[anchor[1]][anchor[0]] = 0;
            } else {
                highways[anchor[1]][anchor[0]] = false;
                baseTiles[anchor[1]][anchor[0]] = targetType;
            }
            cached.previousStateHash = previousHash;
            cached.stateHash = hashTiles(baseTiles);
            RoomState tempState = new RoomState();
            tempState.setSharedTokensJson(cached.sharedTokensJson);
            tempState.setSharedQuestsJson(cached.sharedQuestsJson);
            updateSharedTokens(tempState, 2, 1, 1);
            updateSharedQuests(tempState, baseTiles);
            cached.sharedTokensJson = tempState.getSharedTokensJson();
            cached.sharedQuestsJson = tempState.getSharedQuestsJson();
            cached.dirty = true;
            cached.lastFlushAt = Instant.now();
            bot.setLastProposalAt(now);
            botsToUpdate.add(bot);
            proposedCount++;
            if (processed % 50 == 0) {
                long elapsedMs = (System.nanoTime() - totalStart) / 1_000_000;
                log.debug("botProposalsProgress roomId={} processed={} proposed={} elapsedMs={}",
                    room.getId(),
                    processed,
                    proposedCount,
                    elapsedMs
                );
                log.debug("botProposalsProgressTiming roomId={} processed={} selectMs={} neighborsMs={} dbMs={}",
                    room.getId(),
                    processed,
                    selectionNs / 1_000_000,
                    neighborNs / 1_000_000,
                    dbNs / 1_000_000
                );
            }
        }
        long dbStart = System.nanoTime();
        if (!botsToUpdate.isEmpty()) {
            botRepository.saveAll(botsToUpdate);
        }
        dbNs += System.nanoTime() - dbStart;
        log.debug("botProposals roomId={} bots={} proposed={} skippedNoPos={} skippedRate={} skippedAnchor={} skippedInvalid={} skippedNeighbors={}",
            room.getId(),
            bots.size(),
            proposedCount,
            skippedNoPos,
            skippedRate,
            skippedAnchor,
            skippedInvalid,
            skippedNeighbors
        );
        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
        log.debug("botProposalsTiming roomId={} totalMs={} fetchBotsMs={} tilesMs={} selectMs={} neighborsMs={} dbMs={}",
            room.getId(),
            totalMs,
            fetchMs,
            tilesMs,
            selectionNs / 1_000_000,
            neighborNs / 1_000_000,
            dbNs / 1_000_000
        );
    }

    private int[] pickBotProposalAnchor(RoomBot bot, int[][] tiles) {
        if (bot.getX() == null || bot.getY() == null) {
            return null;
        }
        return new int[] { bot.getX(), bot.getY() };
    }

    private int dominantTileType(Map<String, Double> profile) {
        double forest = profile.getOrDefault("forest", 0.0);
        double water = profile.getOrDefault("water", 0.0);
        double clear = profile.getOrDefault("clear", 0.0);
        double industry = profile.getOrDefault("industry", 0.0);
        double max = forest;
        int type = 1;
        if (water > max) { max = water; type = 2; }
        if (clear > max) { max = clear; type = 0; }
        if (industry > max) { max = industry; type = 3; }
        return type;
    }

    private int[][] applyBotChange(int[][] baseTiles, int x0, int y0, int type) {
        int[][] out = new int[baseTiles.length][baseTiles[0].length];
        for (int y = 0; y < baseTiles.length; y++) {
            System.arraycopy(baseTiles[y], 0, out[y], 0, baseTiles[0].length);
        }
        out[y0][x0] = type;
        return out;
    }

    private int[] extractAnchor(Object delta) {
        if (delta == null) {
            return null;
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

    private void evaluatePendingProposals(Room room) {
        for (RoomProposal proposal : proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room)) {
            if ("PENDING".equals(proposal.getStatus())) {
                evaluateProposal(room, proposal);
            }
        }
    }

    private void evaluateProposal(Room room, RoomProposal proposal) {
        if (!"PENDING".equals(proposal.getStatus())) {
            return;
        }
        int approvals = proposal.getApprovals();
        int rejections = proposal.getRejections();
        int totalVotes = approvals + rejections;
        int quorum = Math.max(1, Math.max(minVotesToResolve, proposal.getRequiredVotes()));
        if (totalVotes >= quorum) {
            if (approvals > rejections) {
                applyProposal(room, proposal);
            } else if (rejections > approvals) {
                proposal.setStatus("REJECTED");
                proposalRepository.save(proposal);
            }
            return;
        }
        if (proposal.getCreatedAt() != null) {
            Instant earliest = proposal.getCreatedAt().plusSeconds(minProposalLiveSeconds);
            if (Instant.now().isBefore(earliest)) {
                return;
            }
        }
        if (proposal.getExpiresAt() != null && proposal.getExpiresAt().isBefore(Instant.now())) {
            applyNoParticipation(room, proposal);
        }
    }

    private void applyProposal(Room room, RoomProposal proposal) {
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        state.setPreviousStateHash(state.getStateHash());
        int[][] baseTiles = getTilesForRoom(room, state);
        int[][] tiles = applyDeltaToTiles(baseTiles, proposal);
        boolean[][] highways = getHighwaysForRoom(room, state);
        if (highways.length == 0 && tiles.length > 0) {
            highways = new boolean[tiles.length][tiles[0].length];
        }
        int minX = tiles[0].length;
        int minY = tiles.length;
        int maxX = 0;
        int maxY = 0;
        int type = -1;
        int changed = 0;
        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[0].length; x++) {
                if (baseTiles[y][x] != tiles[y][x]) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    type = tiles[y][x];
                    changed++;
                }
            }
        }
        if (changed > 0 && minX <= maxX && minY <= maxY) {
            int[][] points = new int[1][2];
            int idx = 0;
            int[] anchor = extractAnchor(proposal.getDeltaJson());
            if (anchor != null) {
                points[idx][0] = anchor[0];
                points[idx][1] = anchor[1];
                idx = 1;
            }
            for (int i = 0; i < idx; i++) {
                int x = points[i][0];
                int y = points[i][1];
                if (type == 4) {
                    highways[y][x] = true;
                    tiles[y][x] = 0;
                } else {
                    highways[y][x] = false;
                }
            }
        }
        state.setTilesJson(toJson(tiles));
        state.setHighwaysJson(toJson(highways));
        state.setStateHash(hashTiles(tiles));
        state.setUpdatedAt(Instant.now());
        updateSharedTokens(state, 2, 1, 1);
        updateSharedQuests(state, tiles);
        stateRepository.save(state);
        updateCacheFromState(room, state);
        proposal.setStatus("APPLIED");
        proposalRepository.save(proposal);
        room.setLockUntil(Instant.now().plusSeconds(2));
        roomRepository.save(room);
    }

    private void applyNoParticipation(Room room, RoomProposal proposal) {
        List<String> neighborIds = readNeighborIds(proposal);
        Set<String> voted = new HashSet<>();
        for (RoomProposalVote vote : voteRepository.findByProposal(proposal)) {
            if (vote.getParticipant() != null) {
                voted.add("P:" + vote.getParticipant().getId());
            }
        }
        Integer anchorX = proposal.getAnchorX();
        Integer anchorY = proposal.getAnchorY();
        if (anchorX == null || anchorY == null) {
            return;
        }
        for (String neighbor : neighborIds) {
            if (neighbor.startsWith("P:") && !voted.contains(neighbor)) {
                UUID pid = UUID.fromString(neighbor.substring(2));
                participantRepository.findById(pid).ifPresent(p -> {
                    if (anchorX != null && anchorY != null && p.getX() != null && p.getY() != null) {
                        if (!withinRadius(anchorX, anchorY, p.getX(), p.getY(), neighborRadius)) {
                            return;
                        }
                    }
                    p.setMissedVotes(p.getMissedVotes() + 1);
                    if (p.getMissedVotes() >= 2) {
                        p.setLockedUntil(Instant.now().plusSeconds(noParticipationLockSeconds));
                        p.setMissedVotes(0);
                    }
                    participantRepository.save(p);
                });
            }
        }
        int approvals = proposal.getApprovals();
        int rejections = proposal.getRejections();
        if (approvals > rejections) {
            applyProposal(room, proposal);
        } else {
            proposal.setStatus("REJECTED");
            proposalRepository.save(proposal);
        }
        proposalRepository.save(proposal);
    }

    private boolean canVoteNow(Room room, RoomProposal proposal, RoomParticipant participant) {
        if (proposal.getAnchorX() == null || proposal.getAnchorY() == null) {
            return false;
        }
        if (participant.getX() == null || participant.getY() == null) {
            return false;
        }
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        int[][] tiles = getTilesForRoom(room, state);
        int[] view = computeViewBounds(proposal.getAnchorX(), proposal.getAnchorY(), tiles);
        return inView(view[0], view[1], view[2], view[3], participant.getX(), participant.getY());
    }

    private List<String> readNeighborIds(RoomProposal proposal) {
        if (proposal.getNeighborIdsJson() == null || proposal.getNeighborIdsJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(proposal.getNeighborIdsJson(), new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private void validateProposalChange(int[][] baseTiles, int[][] proposedTiles) {
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

    private void validateProposalDelta(int[][] baseTiles, int[] delta, Object deltaObj) {
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

    private int extractDeltaType(Object deltaObj) {
        if (deltaObj instanceof Map<?, ?> map) {
            Object type = map.get("type");
            if (type instanceof Number) {
                return ((Number) type).intValue();
            }
            if (type instanceof String s) {
                return switch (s) {
                    case "forest" -> 1;
                    case "water" -> 2;
                    case "industry" -> 3;
                    case "highway" -> 4;
                    case "clear" -> 0;
                    default -> -1;
                };
            }
        }
        return -1;
    }

    private int[][] applyDeltaToTiles(int[][] baseTiles, RoomProposal proposal) {
        int[][] out = new int[baseTiles.length][baseTiles[0].length];
        for (int y = 0; y < baseTiles.length; y++) {
            System.arraycopy(baseTiles[y], 0, out[y], 0, baseTiles[0].length);
        }
        int[] anchor = extractAnchor(proposal.getDeltaJson());
        int type = extractDeltaTypeFromProposal(proposal);
        if (anchor != null && type >= 0) {
            out[anchor[1]][anchor[0]] = type;
        }
        return out;
    }

    private int extractDeltaTypeFromProposal(RoomProposal proposal) {
        if (proposal.getDeltaJson() == null) return -1;
        try {
            Map<String, Object> map = objectMapper.readValue(proposal.getDeltaJson(), new TypeReference<Map<String, Object>>() {});
            return extractDeltaType(map);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private List<String> findNeighbors(Room room, RoomParticipant proposer, int[][] proposedTiles) {
        int[][] tiles = fromJsonTiles(stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing")).getTilesJson());
        int cx = proposer != null && proposer.getX() != null ? proposer.getX() : 0;
        int cy = proposer != null && proposer.getY() != null ? proposer.getY() : 0;
        int[] change = findChangeType(tiles, proposedTiles);
        if (change != null) {
            cx = change[0];
            cy = change[1];
        }
        return findNeighborsForAnchor(room, tiles, cx, cy);
    }

    private List<String> findNeighborsForAnchor(Room room, int[][] tiles, int cx, int cy) {
        int[] view = computeViewBounds(cx, cy, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        List<String> neighbors = new ArrayList<>();
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            if (participant.getX() != null && participant.getY() != null
                && inView(viewX, viewY, viewW, viewH, participant.getX(), participant.getY())) {
                neighbors.add("P:" + participant.getId());
            }
        }
        for (RoomBot bot : botRepository.findByRoom(room)) {
            if (bot.getX() != null && bot.getY() != null
                && inView(viewX, viewY, viewW, viewH, bot.getX(), bot.getY())) {
                neighbors.add("B:" + bot.getId());
            }
        }
        return neighbors;
    }

    private int[] computeViewBounds(int cx, int cy, int[][] tiles) {
        int h = tiles.length;
        int w = tiles[0].length;
        int viewW = Math.min(w, VISIBLE_WIDTH);
        int viewH = Math.min(h, VISIBLE_HEIGHT);
        int viewX = Math.max(0, Math.min(w - viewW, cx - viewW / 2));
        int viewY = Math.max(0, Math.min(h - viewH, cy - viewH / 2));
        return new int[] { viewX, viewY, viewW, viewH };
    }

    private boolean inView(int viewX, int viewY, int viewW, int viewH, int x, int y) {
        return x >= viewX && x < viewX + viewW && y >= viewY && y < viewY + viewH;
    }

    private boolean withinRadius(int cx, int cy, int x, int y, int radius) {
        int dx = x - cx;
        int dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    private boolean canOccupy(Room room, int x, int y, String ignoreId) {
        int half = densityAreaSize / 2;
        int minX = x - half;
        int maxX = x + half - 1;
        int minY = y - half;
        int maxY = y + half - 1;
        int count = 0;
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            if (participant.getId() != null && (("P:" + participant.getId()).equals(ignoreId) || participant.getId().toString().equals(ignoreId))) {
                continue;
            }
            if (participant.getX() != null && participant.getY() != null
                && participant.getX() >= minX && participant.getX() <= maxX
                && participant.getY() >= minY && participant.getY() <= maxY) {
                count++;
            }
        }
        for (RoomBot bot : botRepository.findByRoom(room)) {
            if (bot.getId() != null && (("B:" + bot.getId()).equals(ignoreId) || bot.getId().toString().equals(ignoreId))) {
                continue;
            }
            if (bot.getX() != null && bot.getY() != null
                && bot.getX() >= minX && bot.getX() <= maxX
                && bot.getY() >= minY && bot.getY() <= maxY) {
                count++;
            }
        }
        return count < maxOccupants;
    }

    private void ensurePlayable(RoomParticipant participant) {
        if (participant.getLockedUntil() != null && participant.getLockedUntil().isAfter(Instant.now())) {
            throw new IllegalStateException("You are locked from play for a short time.");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize", e);
        }
    }

    private int[][] fromJsonTiles(String json) {
        try {
            return objectMapper.readValue(json, int[][].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read tiles", e);
        }
    }

    private String hashTiles(int[][] tiles) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(tiles);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash tiles", e);
        }
    }
}
