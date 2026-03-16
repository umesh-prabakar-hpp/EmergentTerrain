package com.emergentterrain.service;

import com.emergentterrain.domain.*;
import com.emergentterrain.gpu.GpuEngineAdapter;
import com.emergentterrain.gpu.GpuEngineCodec;
import com.emergentterrain.gpu.v1.BotState;
import com.emergentterrain.gpu.v1.RebuildPatchRequest;
import com.emergentterrain.gpu.v1.RebuildPatchResponse;
import com.emergentterrain.gpu.v1.Rect;
import com.emergentterrain.gpu.v1.Region;
import com.emergentterrain.gpu.v1.SimulateBotsRequest;
import com.emergentterrain.gpu.v1.SimulateBotsResponse;
import com.emergentterrain.gpu.v1.TileEdit;
import com.emergentterrain.repo.*;
import com.emergentterrain.web.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class RoomService {
    private static final Logger log = LoggerFactory.getLogger(RoomService.class);
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository participantRepository;
    private final RoomStateRepository stateRepository;
    private final RoomStateSnapshotRepository snapshotRepository;
    private final RoomProposalRepository proposalRepository;
    private final RoomProposalVoteRepository voteRepository;
    private final RoomBotRepository botRepository;
    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final PayloadService payloadService;
    private final RoomUpdateBroadcaster broadcaster;
    private final WorldService worldService;
    private final GpuEngineAdapter gpuEngineAdapter;
    private final ObjectMapper objectMapper;
    private final boolean gpuEnabled;
    private final boolean gpuBotsEnabled;
    private final boolean gpuBotsApplyEdits;
    private final boolean gpuBotsCacheWorld;
    private final int gpuBotsSteps;
    private final int gpuBotsDiffMaxEdits;
    private final int gpuBotsMinBotCount;
    private final int gpuBotsMinWorkItems;
    private final int gpuPatchMinArea;
    private final int neighborRadius;
    private final int voteWindowSeconds;
    private final int minVotesToResolve;
    private final int quorumCap;
    private final boolean botRuleChecksEnabled;
    private final int botProposalsPerMinute;
    private final int maxBotProposalsPerMaintenance;
    private final int maxBotsInspectedPerMaintenance;
    private final double botApproveBias;
    private final boolean approveOnTieAfterExpiry;
    private final int botVoteRetentionSeconds;
    private final int minProposalLiveSeconds;
    private final int minNeighbors;
    private final int densityAreaSize;
    private final int maxOccupants;
    private final long occupancyRefreshMs;
    private final int maxMoveSteps;
    private final double botPopulationFraction;
    private final int noParticipationLockSeconds;
    private final ActivityMetrics metrics;
    private final int maxBotsPerRoom;
    private final int initialRoomCreateTarget;
    private final int maxBotsTickedPerMaintenance;
    private final int flushMinChanges;
    private final long flushMaxIntervalMs;
    private final long flushOverdueLockTimeoutMs;
    private final long broadcastMinIntervalMs;
    private final int maintenanceWorkers;
    private final String defaultRoomName;
    private final boolean defaultRoomAutoCreate;
    private final boolean defaultRoomUseWhenAvailable;
    private final String defaultRoomSystemEmail;
    private final String defaultRoomSystemPasswordHash;
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
    private final ConcurrentHashMap<UUID, GpuWorldCache> gpuWorldCache = new ConcurrentHashMap<>();
    private final AtomicInteger maintenanceCursor = new AtomicInteger();
    private final ConcurrentHashMap<UUID, Long> lastBroadcastAtMs = new ConcurrentHashMap<>();
    private final ExecutorService maintenanceExecutor;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate botTransactionTemplate;
    private final ReentrantLock botWriteLock = new ReentrantLock();
    private final AtomicReference<UUID> defaultRoomId = new AtomicReference<>();
    private volatile CachedSystemToken cachedSystemToken;

    public record PerfBotSimulationResult(
        int iterations,
        int botCount,
        long totalMs,
        double averageMs,
        long minMs,
        long maxMs
    ) {
    }

    public RoomService(RoomRepository roomRepository,
                       RoomParticipantRepository participantRepository,
                       RoomStateRepository stateRepository,
                       RoomStateSnapshotRepository snapshotRepository,
                       RoomProposalRepository proposalRepository,
                       RoomProposalVoteRepository voteRepository,
                       RoomBotRepository botRepository,
                       ProfileRepository profileRepository,
                       UserRepository userRepository,
                       PayloadService payloadService,
                       RoomUpdateBroadcaster broadcaster,
                       WorldService worldService,
                       GpuEngineAdapter gpuEngineAdapter,
                       ObjectMapper objectMapper,
                       ActivityMetrics metrics,
                       PlatformTransactionManager transactionManager,
                       @Value("${app.gpu.enabled:false}") boolean gpuEnabled,
                       @Value("${app.gpu.bots.enabled:false}") boolean gpuBotsEnabled,
                       @Value("${app.gpu.bots.applyEdits:false}") boolean gpuBotsApplyEdits,
                       @Value("${app.gpu.bots.cacheWorld:true}") boolean gpuBotsCacheWorld,
                       @Value("${app.gpu.bots.steps:1}") int gpuBotsSteps,
                       @Value("${app.gpu.bots.diffMaxEdits:2000}") int gpuBotsDiffMaxEdits,
                       @Value("${app.gpu.bots.minBotCount:512}") int gpuBotsMinBotCount,
                       @Value("${app.gpu.bots.minWorkItems:2000}") int gpuBotsMinWorkItems,
                       @Value("${app.gpu.patch.minArea:4}") int gpuPatchMinArea,
                       @Value("${app.consensus.neighborRadius:8}") int neighborRadius,
                       @Value("${app.consensus.voteWindowSeconds:15}") int voteWindowSeconds,
                       @Value("${app.consensus.minVotesToResolve:3}") int minVotesToResolve,
                       @Value("${app.consensus.quorumCap:0}") int quorumCap,
                       @Value("${app.consensus.botRuleChecksEnabled:false}") boolean botRuleChecksEnabled,
                       @Value("${app.consensus.botProposalsPerMinute:10}") int botProposalsPerMinute,
                       @Value("${app.consensus.maxBotProposalsPerMaintenance:12}") int maxBotProposalsPerMaintenance,
                       @Value("${app.consensus.maxBotsInspectedPerMaintenance:250}") int maxBotsInspectedPerMaintenance,
                       @Value("${app.consensus.botApproveBias:0.0}") double botApproveBias,
                       @Value("${app.consensus.approveOnTieAfterExpiry:false}") boolean approveOnTieAfterExpiry,
                       @Value("${app.consensus.botVoteRetentionSeconds:20}") int botVoteRetentionSeconds,
                       @Value("${app.consensus.minProposalLiveSeconds:3}") int minProposalLiveSeconds,
                       @Value("${app.consensus.minNeighbors:3}") int minNeighbors,
                       @Value("${app.density.areaSize:20}") int densityAreaSize,
                       @Value("${app.density.maxOccupants:8}") int maxOccupants,
                       @Value("${app.density.occupancyRefreshMs:2000}") long occupancyRefreshMs,
                       @Value("${app.move.maxSteps:6}") int maxMoveSteps,
                       @Value("${app.bots.populationFraction:0.25}") double botPopulationFraction,
                       @Value("${app.bots.maxPerRoom:200}") int maxBotsPerRoom,
                       @Value("${app.bots.initialRoomCreateTarget:200}") int initialRoomCreateTarget,
                       @Value("${app.bots.maxTickPerMaintenance:0}") int maxBotsTickedPerMaintenance,
                       @Value("${app.world.flushMinChanges:1}") int flushMinChanges,
                       @Value("${app.world.flushMaxIntervalMs:30000}") long flushMaxIntervalMs,
                       @Value("${app.world.flushOverdueLockTimeoutMs:10}") long flushOverdueLockTimeoutMs,
                       @Value("${app.consensus.broadcastMinIntervalMs:0}") long broadcastMinIntervalMs,
                       @Value("${app.consensus.maintenanceWorkers:1}") int maintenanceWorkers,
                       @Value("${app.consensus.noParticipationLockSeconds:300}") int noParticipationLockSeconds,
                       @Value("${app.room.defaultName:}") String defaultRoomName,
                       @Value("${app.room.defaultAutoCreate:false}") boolean defaultRoomAutoCreate,
                       @Value("${app.room.defaultUseWhenAvailable:false}") boolean defaultRoomUseWhenAvailable,
                       @Value("${app.room.defaultSystemEmail:system@emergentterrain.local}") String defaultRoomSystemEmail,
                       @Value("${app.room.defaultSystemPasswordHash:system-hash}") String defaultRoomSystemPasswordHash) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.stateRepository = stateRepository;
        this.snapshotRepository = snapshotRepository;
        this.proposalRepository = proposalRepository;
        this.voteRepository = voteRepository;
        this.botRepository = botRepository;
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.payloadService = payloadService;
        this.broadcaster = broadcaster;
        this.worldService = worldService;
        this.gpuEngineAdapter = gpuEngineAdapter;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.gpuEnabled = gpuEnabled;
        this.gpuBotsEnabled = gpuBotsEnabled;
        this.gpuBotsApplyEdits = gpuBotsApplyEdits;
        this.gpuBotsCacheWorld = gpuBotsCacheWorld;
        this.gpuBotsSteps = Math.max(1, gpuBotsSteps);
        this.gpuBotsDiffMaxEdits = Math.max(0, gpuBotsDiffMaxEdits);
        this.gpuBotsMinBotCount = Math.max(0, gpuBotsMinBotCount);
        this.gpuBotsMinWorkItems = Math.max(0, gpuBotsMinWorkItems);
        this.gpuPatchMinArea = Math.max(1, gpuPatchMinArea);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.botTransactionTemplate = new TransactionTemplate(transactionManager);
        this.botTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.neighborRadius = neighborRadius;
        this.voteWindowSeconds = voteWindowSeconds;
        this.minVotesToResolve = minVotesToResolve;
        this.quorumCap = Math.max(0, quorumCap);
        this.botRuleChecksEnabled = botRuleChecksEnabled;
        this.botProposalsPerMinute = Math.max(1, botProposalsPerMinute);
        this.maxBotProposalsPerMaintenance = Math.max(1, maxBotProposalsPerMaintenance);
        this.maxBotsInspectedPerMaintenance = Math.max(this.maxBotProposalsPerMaintenance, maxBotsInspectedPerMaintenance);
        this.botApproveBias = Math.max(0.0, Math.min(0.95, botApproveBias));
        this.approveOnTieAfterExpiry = approveOnTieAfterExpiry;
        this.botVoteRetentionSeconds = botVoteRetentionSeconds;
        this.minProposalLiveSeconds = minProposalLiveSeconds;
        this.minNeighbors = minNeighbors;
        this.densityAreaSize = densityAreaSize;
        this.maxOccupants = maxOccupants;
        this.occupancyRefreshMs = Math.max(250, occupancyRefreshMs);
        this.maxMoveSteps = Math.max(1, maxMoveSteps);
        this.botPopulationFraction = botPopulationFraction;
        this.maxBotsPerRoom = Math.max(1, maxBotsPerRoom);
        this.initialRoomCreateTarget = Math.max(minNeighbors, initialRoomCreateTarget);
        this.maxBotsTickedPerMaintenance = Math.max(0, maxBotsTickedPerMaintenance);
        this.flushMinChanges = Math.max(1, flushMinChanges);
        this.flushMaxIntervalMs = Math.max(250, flushMaxIntervalMs);
        this.flushOverdueLockTimeoutMs = Math.max(1, flushOverdueLockTimeoutMs);
        this.broadcastMinIntervalMs = Math.max(0, broadcastMinIntervalMs);
        this.maintenanceWorkers = Math.max(1, maintenanceWorkers);
        this.maintenanceExecutor = Executors.newFixedThreadPool(this.maintenanceWorkers);
        this.noParticipationLockSeconds = noParticipationLockSeconds;
        this.defaultRoomName = defaultRoomName == null ? "" : defaultRoomName.trim();
        this.defaultRoomAutoCreate = defaultRoomAutoCreate;
        this.defaultRoomUseWhenAvailable = defaultRoomUseWhenAvailable;
        this.defaultRoomSystemEmail = (defaultRoomSystemEmail == null || defaultRoomSystemEmail.isBlank())
            ? "system@emergentterrain.local"
            : defaultRoomSystemEmail.toLowerCase(Locale.ROOT).trim();
        this.defaultRoomSystemPasswordHash = (defaultRoomSystemPasswordHash == null || defaultRoomSystemPasswordHash.isBlank())
            ? "system-hash"
            : defaultRoomSystemPasswordHash.trim();
    }

    @PostConstruct
    public void initializeDefaultRoom() {
        if (!defaultRoomAutoCreate || defaultRoomName.isBlank()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> ensureDefaultRoom());
        } catch (Exception e) {
            log.warn("default room bootstrap failed name={} error={}", defaultRoomName, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdownMaintenanceExecutor() {
        maintenanceExecutor.shutdown();
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
        TileCounts counts = computeTileCounts(world.getTiles(), world.getHighways());
        state.setTileClearCount(counts.clear);
        state.setTileForestCount(counts.forest);
        state.setTileWaterCount(counts.water);
        state.setTileIndustryCount(counts.industry);
        state.setTileHighwayCount(counts.road);
        state.setTotalChangeCount(0L);
        state.setChangesSinceFlush(0);
        state = stateRepository.save(state);
        updateCacheFromState(room, state);

        List<RoomBot> bots = ensureBots(room, world.getTiles(), false);
        ensureNearbyBots(room, world.getTiles(), host.getX(), host.getY(), bots);
        if (refreshOccupancySnapshot(room, state, Instant.now())) {
            stateRepository.save(state);
            updateCacheFromState(room, state);
        }
        CachedRoomState cachedState = getCachedRoomState(room, state);

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
        response.setBots(buildBots(room, cachedState, bots));
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
            List<RoomBot> bots = ensureBots(room, getTilesForRoom(room, joinState), false);
            ensureNearbyBots(room, getTilesForRoom(room, joinState), participant.getX(), participant.getY(), bots);
            if (refreshOccupancySnapshot(room, joinState, Instant.now())) {
                stateRepository.save(joinState);
                updateCacheFromState(room, joinState);
            }
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
        if (defaultRoomUseWhenAvailable) {
            UUID defaultId = ensureDefaultRoom();
            if (defaultId != null) {
                return joinRoom(payload, defaultId);
            }
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

    public int populateRoomForPerf(UUID roomId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        return transactionTemplate.execute(status -> {
            RoomState state = stateRepository.findByRoom(room)
                .orElseThrow(() -> new IllegalStateException("Room state missing"));
            int[][] tiles = getTilesForRoom(room, state);
            List<RoomBot> bots = ensureBots(room, tiles, true);
            if (refreshOccupancySnapshot(room, state, Instant.now())) {
                stateRepository.save(state);
                updateCacheFromState(room, state);
            }
            return bots.size();
        });
    }

    public PerfBotSimulationResult runBotSimulationForPerf(UUID roomId, int iterations, boolean clearGpuWorldCache) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        int safeIterations = Math.max(1, iterations);
        if (clearGpuWorldCache) {
            gpuWorldCache.remove(roomId);
        }
        List<Long> runTimes = new ArrayList<>(safeIterations);
        for (int i = 0; i < safeIterations; i++) {
            long start = System.nanoTime();
            transactionTemplate.executeWithoutResult(status -> {
                RoomState state = stateRepository.findByRoom(room)
                    .orElseThrow(() -> new IllegalStateException("Room state missing"));
                int[][] tiles = getTilesForRoom(room, state);
                ensureBots(room, tiles, true);
                tickBots(room, state);
            });
            runTimes.add((System.nanoTime() - start) / 1_000_000);
        }
        List<RoomBot> bots = botRepository.findByRoom(room);
        long totalMs = 0L;
        long minMs = Long.MAX_VALUE;
        long maxMs = Long.MIN_VALUE;
        for (Long runTime : runTimes) {
            totalMs += runTime;
            minMs = Math.min(minMs, runTime);
            maxMs = Math.max(maxMs, runTime);
        }
        return new PerfBotSimulationResult(
            safeIterations,
            bots.size(),
            totalMs,
            (double) totalMs / (double) safeIterations,
            runTimes.isEmpty() ? 0L : minMs,
            runTimes.isEmpty() ? 0L : maxMs
        );
    }

    private UUID ensureDefaultRoom() {
        UUID existing = resolveDefaultRoomId();
        if (existing != null) {
            return existing;
        }
        if (!defaultRoomAutoCreate || defaultRoomName.isBlank()) {
            return null;
        }
        if (roomRepository.count() > 0) {
            return null;
        }
        Payload payload = buildSystemPayload();
        RoomCreateResponse created = createRoom(payload, defaultRoomName);
        UUID createdId = UUID.fromString(created.getRoomId());
        defaultRoomId.set(createdId);
        log.info("default room created id={} name={}", createdId, defaultRoomName);
        return createdId;
    }

    private UUID resolveDefaultRoomId() {
        UUID cached = defaultRoomId.get();
        if (cached != null) {
            return cached;
        }
        if (defaultRoomName.isBlank()) {
            return null;
        }
        roomRepository.findTopByNameOrderByCreatedAtAsc(defaultRoomName)
            .map(Room::getId)
            .ifPresent(defaultRoomId::set);
        return defaultRoomId.get();
    }

    private Payload buildSystemPayload() {
        Profile profile = ensureSystemProfile();
        Map<String, Boolean> answers = readAnswers(profile.getAnswersJson());
        HiddenProperties hidden = readHidden(profile.getHiddenPropsJson());
        return new Payload(
            1,
            profile.getId().toString(),
            profile.getAge(),
            profile.getSex(),
            profile.getLocation(),
            answers,
            hidden,
            payloadService.issuedAtNow()
        );
    }

    public String buildSystemToken() {
        CachedSystemToken cached = cachedSystemToken;
        if (cached != null) {
            return cached.token;
        }
        Payload payload = buildSystemPayload();
        String token = payloadService.buildToken(payload);
        cachedSystemToken = new CachedSystemToken(token);
        return token;
    }

    private Profile ensureSystemProfile() {
        User user = userRepository.findByEmail(defaultRoomSystemEmail)
            .orElseGet(() -> {
                User created = new User();
                created.setEmail(defaultRoomSystemEmail);
                created.setPasswordHash(defaultRoomSystemPasswordHash);
                created.setRole("SYSTEM");
                return userRepository.save(created);
            });
        List<Profile> profiles = profileRepository.findByUser(user);
        if (!profiles.isEmpty()) {
            return profiles.get(0);
        }
        Profile profile = new Profile();
        profile.setId(UUID.randomUUID());
        profile.setUser(user);
        profile.setAge(30);
        profile.setSex("N");
        profile.setLocation("SYSTEM");
        Map<String, Boolean> answers = defaultAnswers();
        HiddenProperties hidden = defaultHidden();
        profile.setAnswersJson(toJson(answers));
        profile.setHiddenPropsJson(toJson(hidden));
        Payload payload = new Payload(
            1,
            profile.getId().toString(),
            profile.getAge(),
            profile.getSex(),
            profile.getLocation(),
            answers,
            hidden,
            payloadService.issuedAtNow()
        );
        profile.setPayloadHash(payloadService.hashPayload(payload));
        return profileRepository.save(profile);
    }

    private Map<String, Boolean> readAnswers(String json) {
        if (json == null || json.isBlank()) {
            return defaultAnswers();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Boolean>>() {});
        } catch (JsonProcessingException e) {
            return defaultAnswers();
        }
    }

    private HiddenProperties readHidden(String json) {
        if (json == null || json.isBlank()) {
            return defaultHidden();
        }
        try {
            return objectMapper.readValue(json, HiddenProperties.class);
        } catch (JsonProcessingException e) {
            return defaultHidden();
        }
    }

    private Map<String, Boolean> defaultAnswers() {
        return Map.of(
            "likes_forests", true,
            "likes_open_fields", true,
            "likes_water", true,
            "likes_industry", false
        );
    }

    private HiddenProperties defaultHidden() {
        return new HiddenProperties(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
    }

    @Transactional
    public RoomStateResponse getRoomState(Payload payload, UUID roomId) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        ensureBots(room, getTilesForRoom(room, state), false);
        return toRoomStateResponse(room, state, participant);
    }

    @Scheduled(fixedDelayString = "${app.consensus.maintenanceIntervalMs:5000}")
    @Transactional
    public void scheduledRoomMaintenance() {
        metrics.increment("room.maintenance");
        long start = System.nanoTime();
        List<Room> rooms = new ArrayList<>(roomRepository.findAll());
        if (!rooms.isEmpty()) {
            int startIndex = Math.floorMod(maintenanceCursor.getAndIncrement(), rooms.size());
            Collections.rotate(rooms, -startIndex);
        }
        log.debug("maintenance start rooms={}", rooms.size());
        try {
            if (maintenanceWorkers <= 1 || rooms.size() <= 1) {
                for (Room room : rooms) {
                    runRoomMaintenance(room);
                }
            } else {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (Room room : rooms) {
                    futures.add(CompletableFuture.runAsync(() -> runRoomMaintenance(room), maintenanceExecutor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        } finally {
            long totalMs = (System.nanoTime() - start) / 1_000_000;
            log.debug("maintenance total rooms={} totalMs={}", rooms.size(), totalMs);
        }
    }

    private void runRoomMaintenance(Room room) {
        transactionTemplate.executeWithoutResult(status -> {
            RoomState state = stateRepository.findByRoom(room).orElse(null);
            if (state == null) {
                return;
            }
            long roomStart = System.nanoTime();
            Instant now = Instant.now();
            boolean metadataDirty = false;
            boolean shouldTickBots = state.getLastBotTickAt() == null || state.getLastBotTickAt().isBefore(now.minusSeconds(5));
            AtomicBoolean tickedBots = new AtomicBoolean(false);
            try {
                botTransactionTemplate.executeWithoutResult(botStatus -> {
                    botWriteLock.lock();
                    try {
                        RoomState botState = stateRepository.findByRoom(room).orElse(null);
                        if (botState == null) {
                            return;
                        }
                        int[][] tiles = getTilesForRoom(room, botState);
                        long botsStart = System.nanoTime();
                        ensureBots(room, tiles, true);
                        long botsMs = (System.nanoTime() - botsStart) / 1_000_000;
                        log.debug("maintenance step=ensureBots end roomId={} botsMs={}", room.getId(), botsMs);
                        if (shouldTickBots) {
                            log.debug("maintenance step=tickBots start roomId={}", room.getId());
                            tickBots(room, botState);
                            log.debug("maintenance step=tickBots end roomId={}", room.getId());
                            tickedBots.set(true);
                        }
                    } finally {
                        botWriteLock.unlock();
                    }
                });
            } catch (Exception e) {
                log.warn("maintenance bots error roomId={} msg={}", room.getId(), e.getMessage());
            }
            if (tickedBots.get()) {
                state.setLastBotTickAt(now);
                metadataDirty = true;
            }
            if (refreshOccupancySnapshot(room, state, now)) {
                metadataDirty = true;
            }

            ReentrantLock lock = proposalLocks.computeIfAbsent(room.getId(), id -> new ReentrantLock());
            if (!lock.tryLock()) {
                if (metadataDirty) {
                    stateRepository.save(state);
                }
                return;
            }
            try {
                if (state.getLastCleanupAt() == null || state.getLastCleanupAt().isBefore(now.minusSeconds(15))) {
                    cleanupOldProposals(room);
                    state.setLastCleanupAt(now);
                    metadataDirty = true;
                }
                log.debug("maintenance step=maybeCreateBotProposal start roomId={}", room.getId());
                maybeCreateBotProposal(room, state);
                log.debug("maintenance step=maybeCreateBotProposal end roomId={}", room.getId());
                boolean flushed = persistDirtyRoomState(room, state, roomStateCache.get(room.getId()));
                log.debug("maintenance step=evaluatePendingProposals start roomId={}", room.getId());
                evaluatePendingProposals(room);
                log.debug("maintenance step=evaluatePendingProposals end roomId={}", room.getId());
                if (metadataDirty && !flushed) {
                    stateRepository.save(state);
                }
                maybeBroadcast(room.getId());
                long roomMs = (System.nanoTime() - roomStart) / 1_000_000;
                log.debug("maintenance roomId={} roomMs={}", room.getId(), roomMs);
            } catch (Exception e) {
                log.warn("maintenance error roomId={} msg={}", room.getId(), e.getMessage());
            } finally {
                lock.unlock();
            }
        });
    }

    private boolean refreshOccupancySnapshot(Room room, RoomState state, Instant now) {
        if (state == null) {
            return false;
        }
        Instant last = state.getOccupancyUpdatedAt();
        boolean due = last == null
            || now.toEpochMilli() - last.toEpochMilli() >= occupancyRefreshMs;
        if (!due) {
            return false;
        }
        List<OccupantSnapshot> occupancy = buildOccupancySnapshot(room);
        state.setOccupancyJson(toJson(occupancy));
        state.setOccupancyUpdatedAt(now);
        CachedRoomState cached = roomStateCache.get(room.getId());
        if (cached != null) {
            cached.occupancy = occupancy;
            cached.occupancyUpdatedAt = now;
        }
        return true;
    }

    private void maybeBroadcast(UUID roomId) {
        if (broadcastMinIntervalMs <= 0) {
            broadcaster.broadcastRoom(roomId);
            return;
        }
        long nowMs = System.currentTimeMillis();
        Long last = lastBroadcastAtMs.get(roomId);
        if (last == null || nowMs - last >= broadcastMinIntervalMs) {
            lastBroadcastAtMs.put(roomId, nowMs);
            broadcaster.broadcastRoom(roomId);
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
            int changesSinceFlush = cached.changesSinceFlush == null ? 0 : cached.changesSinceFlush;
            boolean overdue = cached.lastFlushAt == null
                || now.toEpochMilli() - cached.lastFlushAt.toEpochMilli() >= flushMaxIntervalMs;
            if (changesSinceFlush < flushMinChanges && !overdue) {
                continue;
            }
            ReentrantLock lock = proposalLocks.computeIfAbsent(roomId, id -> new ReentrantLock());
            boolean locked = lock.tryLock();
            if (!locked && !overdue) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                locked = lock.tryLock();
            }
            if (!locked && overdue) {
                try {
                    locked = lock.tryLock(flushOverdueLockTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!locked) {
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
                persistDirtyRoomState(room, state, cached);
            } finally {
                lock.unlock();
            }
        }
    }

    private boolean persistDirtyRoomState(Room room, RoomState state, CachedRoomState cached) {
        if (cached == null || !cached.dirty) {
            return false;
        }
        Instant now = Instant.now();
        int changesSinceFlush = cached.changesSinceFlush == null ? 0 : cached.changesSinceFlush;
        boolean overdue = cached.lastFlushAt == null
            || now.toEpochMilli() - cached.lastFlushAt.toEpochMilli() >= flushMaxIntervalMs;
        boolean noMaterialChange = changesSinceFlush <= 0
            && Objects.equals(cached.stateHash, cached.previousStateHash);
        if (noMaterialChange) {
            cached.dirty = false;
            cached.lastFlushAt = now;
            cached.changesSinceFlush = 0;
            return false;
        }
        if (changesSinceFlush < flushMinChanges && !overdue) {
            return false;
        }
        state.setTilesJson(toJson(cached.tiles));
        state.setHighwaysJson(toJson(cached.highways));
        state.setSharedTokensJson(cached.sharedTokensJson);
        state.setSharedQuestsJson(cached.sharedQuestsJson);
        state.setMemoryMarkersJson(cached.memoryMarkersJson);
        if (cached.occupancy != null) {
            state.setOccupancyJson(toJson(cached.occupancy));
            state.setOccupancyUpdatedAt(cached.occupancyUpdatedAt);
        }
        state.setPreviousStateHash(cached.previousStateHash);
        state.setStateHash(cached.stateHash == null ? hashTiles(cached.tiles) : cached.stateHash);
        state.setTotalChangeCount(cached.totalChangeCount == null ? 0 : cached.totalChangeCount);
        state.setChangesSinceFlush(changesSinceFlush);
        state.setTileClearCount(cached.tileClearCount);
        state.setTileForestCount(cached.tileForestCount);
        state.setTileWaterCount(cached.tileWaterCount);
        state.setTileIndustryCount(cached.tileIndustryCount);
        state.setTileHighwayCount(cached.tileHighwayCount);
        state.setUpdatedAt(now);
        stateRepository.save(state);
        RoomStateSnapshot snapshot = new RoomStateSnapshot();
        snapshot.setRoom(room);
        snapshot.setSnapshotAt(now);
        snapshot.setTotalChangeCount(state.getTotalChangeCount());
        snapshot.setChangesSinceFlush(state.getChangesSinceFlush());
        snapshot.setTileClearCount(state.getTileClearCount());
        snapshot.setTileForestCount(state.getTileForestCount());
        snapshot.setTileWaterCount(state.getTileWaterCount());
        snapshot.setTileIndustryCount(state.getTileIndustryCount());
        snapshot.setTileHighwayCount(state.getTileHighwayCount());
        snapshotRepository.save(snapshot);
        cached.dirty = false;
        cached.lastFlushAt = now;
        cached.changesSinceFlush = 0;
        log.info("roomStateFlush roomId={} updatedAt={} stateHash={}", room.getId(), now, state.getStateHash());
        return true;
    }
    @Transactional
    public RoomStateResponse move(Payload payload, UUID roomId, int x, int y) {
        RoomMoveRequest request = new RoomMoveRequest();
        request.setX(x);
        request.setY(y);
        return moveWithTimings(payload, roomId, request).response();
    }

    public MoveResult moveWithTimings(Payload payload, UUID roomId, int x, int y) {
        RoomMoveRequest request = new RoomMoveRequest();
        request.setX(x);
        request.setY(y);
        return moveWithTimings(payload, roomId, request);
    }

    public MoveResult moveWithTimings(Payload payload, UUID roomId, RoomMoveRequest request) {
        long t0 = System.nanoTime();
        Profile profile = profileFromPayload(payload);
        long t1 = System.nanoTime();
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        long t2 = System.nanoTime();
        RoomParticipant participant = ensureParticipant(room, profile);
        long t3 = System.nanoTime();
        ensurePlayable(participant);
        long t4 = System.nanoTime();
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        long t5 = System.nanoTime();
        int[][] tiles = getTilesForRoom(room, state);
        long t6 = System.nanoTime();
        List<int[]> steps = extractMoveSteps(request);
        if (steps.isEmpty()) {
            throw new IllegalStateException("No move steps provided");
        }
        int nextX = participant.getX() == null ? steps.get(0)[0] : participant.getX();
        int nextY = participant.getY() == null ? steps.get(0)[1] : participant.getY();
        boolean moved = false;
        for (int[] step : steps) {
            if (step == null || step.length < 2) {
                continue;
            }
            int x = step[0];
            int y = step[1];
            if (x == nextX && y == nextY) {
                continue;
            }
            if (x < 0 || y < 0 || y >= tiles.length || x >= tiles[0].length) {
                if (!moved) {
                    throw new IllegalStateException("Move out of bounds");
                }
                break;
            }
            if (!canOccupy(room, x, y, participant.getId().toString())) {
                if (!moved) {
                    throw new IllegalStateException("Area at capacity");
                }
                break;
            }
            nextX = x;
            nextY = y;
            moved = true;
        }
        long t7 = System.nanoTime();
        if (!moved && participant.getX() != null && participant.getY() != null) {
            nextX = participant.getX();
            nextY = participant.getY();
        }
        participant.setX(nextX);
        participant.setY(nextY);
        participantRepository.save(participant);
        long t8 = System.nanoTime();
        ensureNearbyBots(room, tiles, participant.getX(), participant.getY());
        long t9 = System.nanoTime();
        RoomStateResponse response = toRoomStateResponse(room, state, participant);
        long t10 = System.nanoTime();
        maybeBroadcast(room.getId());
        long t11 = System.nanoTime();

        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("profile", nanosToMs(t1 - t0));
        timings.put("room", nanosToMs(t2 - t1));
        timings.put("participant", nanosToMs(t3 - t2));
        timings.put("playable", nanosToMs(t4 - t3));
        timings.put("state", nanosToMs(t5 - t4));
        timings.put("tiles", nanosToMs(t6 - t5));
        timings.put("checks", nanosToMs(t7 - t6));
        timings.put("saveParticipant", nanosToMs(t8 - t7));
        timings.put("nearbyBots", nanosToMs(t9 - t8));
        timings.put("response", nanosToMs(t10 - t9));
        timings.put("broadcast", nanosToMs(t11 - t10));
        timings.put("total", nanosToMs(t11 - t0));
        return new MoveResult(response, timings);
    }

    private List<int[]> extractMoveSteps(RoomMoveRequest request) {
        List<int[]> steps = new ArrayList<>();
        if (request == null) {
            return steps;
        }
        int[][] path = request.getPath();
        if (path != null && path.length > 0) {
            for (int i = 0; i < path.length && steps.size() < maxMoveSteps; i++) {
                int[] step = path[i];
                if (step == null || step.length < 2) {
                    continue;
                }
                steps.add(new int[] { step[0], step[1] });
            }
        } else {
            steps.add(new int[] { request.getX(), request.getY() });
        }
        if (steps.size() > maxMoveSteps) {
            steps = steps.subList(0, maxMoveSteps);
        }
        return steps;
    }

    public static class MoveResult {
        private final RoomStateResponse response;
        private final Map<String, Double> timings;

        public MoveResult(RoomStateResponse response, Map<String, Double> timings) {
            this.response = response;
            this.timings = timings;
        }

        public RoomStateResponse response() {
            return response;
        }

        public Map<String, Double> timings() {
            return timings;
        }
    }

    private double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    @Transactional
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
            proposal.setRequiredVotes(capQuorum(neighbors.size() / 2 + 1));
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
            RoomState latestState = state;
            if (!"PENDING".equals(proposal.getStatus())) {
                latestState = stateRepository.findByRoom(room).orElse(state);
            }
            RoomStateResponse response = toRoomStateResponse(room, latestState, participant);
            maybeBroadcast(room.getId());
            return response;
        } finally {
            lock.unlock();
        }
    }

    @Transactional
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
            RoomStateResponse response = toRoomStateResponse(room, state, participant);
            maybeBroadcast(room.getId());
            return response;
        } finally {
            lock.unlock();
        }
    }

    public RoomStateResponse apply(Payload payload, UUID roomId, RoomApplyRequest request) {
        throw new IllegalStateException("Proposals apply automatically on majority vote");
    }

    @Transactional
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
        RoomStateResponse response = toRoomStateResponse(room, state, participant);
        maybeBroadcast(room.getId());
        return response;
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
        maybeBroadcast(room.getId());
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

    private List<RoomBot> ensureBots(Room room, int[][] tiles, boolean fullPopulation) {
        List<RoomBot> existing = botRepository.findByRoom(room);
        int maxOccupantsTotal = Math.max(3, (tiles.length * tiles[0].length * maxOccupants) / (densityAreaSize * densityAreaSize));
        int targetBots = Math.max(40, (int) Math.round(maxOccupantsTotal * botPopulationFraction));
        targetBots = Math.min(targetBots, maxBotsPerRoom);
        int desiredBots = fullPopulation ? targetBots : Math.min(targetBots, initialRoomCreateTarget);

        Map<String, RoomBot> byType = new HashMap<>();
        for (RoomBot bot : existing) {
            byType.put(bot.getType(), bot);
        }

        List<String> companions = List.of("OBSERVER", "COPILOT", "TENSION", "ECHO", "QUEST");
        List<RoomBot> createdCompanions = new ArrayList<>();
        for (String type : companions) {
            if (!byType.containsKey(type)) {
                RoomBot bot = new RoomBot();
                bot.setRoom(room);
                bot.setType(type);
                bot.setProfileJson(toJson(randomBotProfile(room.getId().hashCode() ^ type.hashCode())));
                assignBotSpawn(bot, tiles);
                createdCompanions.add(bot);
                byType.put(type, bot);
            }
        }
        if (!createdCompanions.isEmpty()) {
            existing.addAll(botRepository.saveAll(createdCompanions));
        }

        List<RoomBot> dirtyBots = new ArrayList<>();
        for (RoomBot bot : existing) {
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
                dirtyBots.add(bot);
            }
        }
        if (!dirtyBots.isEmpty()) {
            botRepository.saveAll(dirtyBots);
        }

        List<RoomBot> newBots = new ArrayList<>();
        for (int i = existing.size(); i < desiredBots; i++) {
            String type = "AGENT-" + (i + 1);
            RoomBot bot = new RoomBot();
            bot.setRoom(room);
            bot.setType(type);
            bot.setProfileJson(toJson(randomBotProfile(room.getId().hashCode() ^ i)));
            assignBotSpawn(bot, tiles);
            newBots.add(bot);
        }
        if (!newBots.isEmpty()) {
            existing.addAll(botRepository.saveAll(newBots));
        }
        return existing;
    }

    private List<RoomBot> ensureBots(Room room, int[][] tiles) {
        return ensureBots(room, tiles, true);
    }

    private void ensureNearbyBots(Room room, int[][] tiles, Integer cx, Integer cy) {
        ensureNearbyBots(room, tiles, cx, cy, null);
    }

    private void ensureNearbyBots(Room room, int[][] tiles, Integer cx, Integer cy, List<RoomBot> existingBots) {
        if (cx == null || cy == null) {
            return;
        }
        int needed = Math.max(0, minNeighbors - 1);
        if (needed == 0) {
            return;
        }
        List<RoomBot> bots = existingBots != null ? existingBots : botRepository.findByRoom(room);
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
        List<RoomBot> movedBots = new ArrayList<>();
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
                movedBots.add(bot);
                toMove--;
            }
            attempts++;
            if (attempts > bots.size() * 3) {
                break;
            }
        }
        if (!movedBots.isEmpty()) {
            botRepository.saveAll(movedBots);
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
        CachedRoomState cached = roomStateCache.get(room.getId());
        return buildBots(room, cached, null);
    }

    private List<RoomBotInfo> buildBots(Room room, CachedRoomState cached, List<RoomBot> existingBots) {
        List<RoomBotInfo> infos = new ArrayList<>();
        int[][] tiles = cached == null ? new int[0][0] : cached.tiles;
        boolean[][] highways = cached == null ? new boolean[0][0] : cached.highways;
        List<RoomBot> bots = existingBots != null ? existingBots : botRepository.findByRoom(room);
        for (RoomBot bot : bots) {
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
        UUID roomId = bot.getRoom() == null ? null : bot.getRoom().getId();
        Random rand = new Random(Objects.hash(roomId, bot.getType(), h, w));
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

    private static class GpuWorldCache {
        private final String worldId;
        private int[][] tiles;
        private boolean[][] highways;

        private GpuWorldCache(String worldId) {
            this.worldId = worldId;
        }
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
        TileCounts counts = computeTileCounts(tiles, highways);
        CachedRoomState built = new CachedRoomState(
            tiles,
            highways,
            state.getSharedTokensJson(),
            state.getSharedQuestsJson(),
            state.getMemoryMarkersJson(),
            state.getStateHash(),
            state.getPreviousStateHash(),
            counts,
            state.getTotalChangeCount(),
            state.getChangesSinceFlush(),
            readOccupancyJson(state.getOccupancyJson()),
            state.getOccupancyUpdatedAt()
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

    private void updateCacheFromState(Room room, RoomState state, int changeDelta) {
        CachedRoomState existing = roomStateCache.get(room.getId());
        long totalChanges = existing != null && existing.totalChangeCount != null
            ? existing.totalChangeCount
            : (state.getTotalChangeCount() == null ? 0 : state.getTotalChangeCount());
        int changesSinceFlush = existing != null && existing.changesSinceFlush != null
            ? existing.changesSinceFlush
            : (state.getChangesSinceFlush() == null ? 0 : state.getChangesSinceFlush());
        totalChanges += Math.max(0, changeDelta);
        changesSinceFlush += Math.max(0, changeDelta);
        int[][] tiles = fromJsonTiles(state.getTilesJson());
        boolean[][] highways = fromJsonHighways(state.getHighwaysJson());
        TileCounts counts = computeTileCounts(tiles, highways);
        CachedRoomState cached = new CachedRoomState(
            tiles,
            highways,
            state.getSharedTokensJson(),
            state.getSharedQuestsJson(),
            state.getMemoryMarkersJson(),
            state.getStateHash(),
            state.getPreviousStateHash(),
            counts,
            totalChanges,
            changesSinceFlush,
            readOccupancyJson(state.getOccupancyJson()),
            state.getOccupancyUpdatedAt()
        );
        cached.dirty = false;
        cached.lastFlushAt = Instant.now();
        roomStateCache.put(room.getId(), cached);
    }

    private void updateCacheFromState(Room room, RoomState state) {
        updateCacheFromState(room, state, 0);
    }

    private TileCounts computeTileCounts(int[][] tiles, boolean[][] highways) {
        int clear = 0;
        int forest = 0;
        int water = 0;
        int industry = 0;
        int road = 0;
        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[0].length; x++) {
                int v = tiles[y][x];
                if (v == 0) clear++;
                else if (v == 1) forest++;
                else if (v == 2) water++;
                else if (v == 3) industry++;
                if (highways.length > 0 && highways[y][x]) {
                    road++;
                }
            }
        }
        return new TileCounts(clear, forest, water, industry, road);
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

    private static class ProposalApplyResult {
        final int[][] tiles;
        final boolean[][] highways;
        final int changed;

        ProposalApplyResult(int[][] tiles, boolean[][] highways, int changed) {
            this.tiles = tiles;
            this.highways = highways;
            this.changed = changed;
        }
    }

    private static class OccupantSnapshot {
        private String id;
        private int x;
        private int y;

        OccupantSnapshot() {
        }

        OccupantSnapshot(String id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
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
        int tileClearCount;
        int tileForestCount;
        int tileWaterCount;
        int tileIndustryCount;
        int tileHighwayCount;
        Long totalChangeCount;
        Integer changesSinceFlush;
        List<OccupantSnapshot> occupancy;
        Instant occupancyUpdatedAt;
        boolean dirty;
        Instant lastFlushAt;

        CachedRoomState(int[][] tiles,
                        boolean[][] highways,
                        String sharedTokensJson,
                        String sharedQuestsJson,
                        String memoryMarkersJson,
                        String stateHash,
                        String previousStateHash,
                        TileCounts counts,
                        Long totalChangeCount,
                        Integer changesSinceFlush,
                        List<OccupantSnapshot> occupancy,
                        Instant occupancyUpdatedAt) {
            this.tiles = tiles;
            this.highways = highways;
            this.sharedTokensJson = sharedTokensJson;
            this.sharedQuestsJson = sharedQuestsJson;
            this.memoryMarkersJson = memoryMarkersJson;
            this.stateHash = stateHash;
            this.previousStateHash = previousStateHash;
            this.tileClearCount = counts.clear;
            this.tileForestCount = counts.forest;
            this.tileWaterCount = counts.water;
            this.tileIndustryCount = counts.industry;
            this.tileHighwayCount = counts.road;
            this.totalChangeCount = totalChangeCount == null ? 0 : totalChangeCount;
            this.changesSinceFlush = changesSinceFlush == null ? 0 : changesSinceFlush;
            this.occupancy = occupancy == null ? new ArrayList<>() : occupancy;
            this.occupancyUpdatedAt = occupancyUpdatedAt;
            this.dirty = false;
            this.lastFlushAt = Instant.now();
        }
    }

    private static class TileCounts {
        final int clear;
        final int forest;
        final int water;
        final int industry;
        final int road;

        TileCounts(int clear, int forest, int water, int industry, int road) {
            this.clear = clear;
            this.forest = forest;
            this.water = water;
            this.industry = industry;
            this.road = road;
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
            if (trySimulateBotsWithGpu(room, state)) {
                return;
            }
            int[][] tiles = getTilesForRoom(room, state);
            boolean[][] highways = getHighwaysForRoom(room, state);
            CachedMap cached = getCachedMap(room, state, tiles, highways);
            List<RoomProposal> recentProposals = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
            List<RoomBot> moved = new ArrayList<>();
            List<RoomBot> bots = new ArrayList<>(botRepository.findByRoom(room));
            if (maxBotsTickedPerMaintenance > 0 && bots.size() > maxBotsTickedPerMaintenance) {
                Collections.shuffle(bots, new Random(Objects.hash(room.getId(), Instant.now().getEpochSecond())));
                bots = bots.subList(0, maxBotsTickedPerMaintenance);
            }
            for (RoomBot bot : bots) {
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
            if (vote == BotVote.REJECT && botApproveBias > 0 && Math.random() < botApproveBias) {
                vote = BotVote.APPROVE;
            }
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
        long intervalMs = Math.max(200L, 60000L / Math.max(1, botProposalsPerMinute));
        Instant now = Instant.now();
        long fetchStart = System.nanoTime();
        List<RoomBot> bots = new ArrayList<>(botRepository.findByRoom(room));
        List<RoomParticipant> participants = participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room);
        long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000;
        if (bots.isEmpty()) {
            return;
        }
        Collections.shuffle(bots);
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
            if (processed >= maxBotsInspectedPerMaintenance || proposedCount >= maxBotProposalsPerMaintenance) {
                break;
            }
            long selectionStart = System.nanoTime();
            processed++;
            if (bot.getX() == null || bot.getY() == null) {
                skippedNoPos++;
                selectionNs += System.nanoTime() - selectionStart;
                continue;
            }
            Instant last = bot.getLastProposalAt();
            if (last != null && last.isAfter(now.minusMillis(intervalMs))) {
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
            List<String> neighbors = findNeighborsForAnchor(baseTiles, anchor[0], anchor[1], participants, bots);
            neighborNs += System.nanoTime() - neighborStart;
            if (neighbors.size() < minNeighbors) {
                skippedNeighbors++;
                continue;
            }
            String previousHash = cached.stateHash;
            int x = anchor[0];
            int y = anchor[1];
            int oldTile = baseTiles[y][x];
            boolean oldHighway = highways.length > 0 && highways[y][x];
            int newTile = targetType == 4 ? 0 : targetType;
            boolean newHighway = targetType == 4;
            if (oldTile != newTile) {
                if (oldTile == 0) cached.tileClearCount--;
                else if (oldTile == 1) cached.tileForestCount--;
                else if (oldTile == 2) cached.tileWaterCount--;
                else if (oldTile == 3) cached.tileIndustryCount--;
                if (newTile == 0) cached.tileClearCount++;
                else if (newTile == 1) cached.tileForestCount++;
                else if (newTile == 2) cached.tileWaterCount++;
                else if (newTile == 3) cached.tileIndustryCount++;
            }
            if (oldHighway != newHighway) {
                cached.tileHighwayCount += newHighway ? 1 : -1;
            }
            highways[y][x] = newHighway;
            baseTiles[y][x] = newTile;
            cached.previousStateHash = previousHash;
            cached.stateHash = hashTiles(baseTiles);
            RoomState tempState = new RoomState();
            tempState.setSharedTokensJson(cached.sharedTokensJson);
            tempState.setSharedQuestsJson(cached.sharedQuestsJson);
            updateSharedTokens(tempState, 2, 1, 1);
            updateSharedQuests(tempState, baseTiles);
            cached.sharedTokensJson = tempState.getSharedTokensJson();
            cached.sharedQuestsJson = tempState.getSharedQuestsJson();
            cached.totalChangeCount = cached.totalChangeCount == null ? 1 : cached.totalChangeCount + 1;
            cached.changesSinceFlush = cached.changesSinceFlush == null ? 1 : cached.changesSinceFlush + 1;
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
        log.debug("botProposals roomId={} bots={} inspected={} proposed={} maxProposalsPerMaintenance={} maxBotsInspectedPerMaintenance={} skippedNoPos={} skippedRate={} skippedAnchor={} skippedInvalid={} skippedNeighbors={}",
            room.getId(),
            bots.size(),
            processed,
            proposedCount,
            maxBotProposalsPerMaintenance,
            maxBotsInspectedPerMaintenance,
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

    private void evaluatePendingProposals(Room room) {
        for (RoomProposal proposal : proposalRepository.findTop200ByRoomAndStatusOrderByCreatedAtDesc(room, "PENDING")) {
            evaluateProposal(room, proposal);
        }
    }

    private void evaluateProposal(Room room, RoomProposal proposal) {
        if (!"PENDING".equals(proposal.getStatus())) {
            return;
        }
        int approvals = proposal.getApprovals();
        int rejections = proposal.getRejections();
        int totalVotes = approvals + rejections;
        int quorum = capQuorum(Math.max(minVotesToResolve, proposal.getRequiredVotes()));
        if (totalVotes >= quorum) {
            if (approvals > rejections) {
                applyProposal(room, proposal);
                return;
            }
            if (rejections > approvals) {
                proposal.setStatus("REJECTED");
                proposalRepository.save(proposal);
                return;
            }
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
        boolean[][] highways = getHighwaysForRoom(room, state);
        ProposalApplyResult applied = applyProposalPatch(room, proposal, baseTiles, highways);
        int[][] tiles = applied.tiles;
        highways = applied.highways;
        int changed = applied.changed;
        state.setTilesJson(toJson(tiles));
        state.setHighwaysJson(toJson(highways));
        state.setStateHash(hashTiles(tiles));
        TileCounts counts = computeTileCounts(tiles, highways);
        state.setTileClearCount(counts.clear);
        state.setTileForestCount(counts.forest);
        state.setTileWaterCount(counts.water);
        state.setTileIndustryCount(counts.industry);
        state.setTileHighwayCount(counts.road);
        CachedRoomState cached = roomStateCache.get(room.getId());
        long totalChanges = cached != null && cached.totalChangeCount != null
            ? cached.totalChangeCount
            : (state.getTotalChangeCount() == null ? 0 : state.getTotalChangeCount());
        int changesSinceFlush = cached != null && cached.changesSinceFlush != null
            ? cached.changesSinceFlush
            : (state.getChangesSinceFlush() == null ? 0 : state.getChangesSinceFlush());
        totalChanges += changed;
        changesSinceFlush += changed;
        state.setTotalChangeCount(totalChanges);
        state.setChangesSinceFlush(changesSinceFlush);
        state.setUpdatedAt(Instant.now());
        updateSharedTokens(state, 2, 1, 1);
        updateSharedQuests(state, tiles);
        stateRepository.save(state);
        updateCacheFromState(room, state, changed);
        proposal.setStatus("APPLIED");
        proposalRepository.save(proposal);
        room.setLockUntil(Instant.now().plusSeconds(2));
        roomRepository.save(room);
    }

    private ProposalApplyResult applyProposalPatch(Room room,
                                                   RoomProposal proposal,
                                                   int[][] baseTiles,
                                                   boolean[][] baseHighways) {
        boolean[][] normalizedHighways = baseHighways;
        if (normalizedHighways.length == 0 && baseTiles.length > 0) {
            normalizedHighways = new boolean[baseTiles.length][baseTiles[0].length];
        }
        int[] anchor = proposalAnchor(proposal);
        int type = extractDeltaTypeFromProposal(proposal);
        if (gpuEnabled && anchor != null && type >= 0 && gpuPatchMinArea <= 1) {
            ProposalApplyResult gpuResult = tryApplyProposalPatchWithGpu(room, proposal, baseTiles, normalizedHighways, anchor, type);
            if (gpuResult != null) {
                return gpuResult;
            }
        }
        int[][] tiles = applyDeltaToTiles(baseTiles, proposal);
        boolean[][] highways = copyHighways(normalizedHighways, tiles.length, tiles[0].length);
        if (anchor != null && type >= 0) {
            int x = anchor[0];
            int y = anchor[1];
            if (type == 4) {
                highways[y][x] = true;
                tiles[y][x] = 0;
            } else {
                highways[y][x] = false;
            }
        }
        return summarizeProposalApply(baseTiles, normalizedHighways, tiles, highways);
    }

    private int[] proposalAnchor(RoomProposal proposal) {
        if (proposal.getAnchorX() != null && proposal.getAnchorY() != null) {
            return new int[] { proposal.getAnchorX(), proposal.getAnchorY() };
        }
        return extractAnchor(proposal.getDeltaJson());
    }

    private ProposalApplyResult tryApplyProposalPatchWithGpu(Room room,
                                                             RoomProposal proposal,
                                                             int[][] baseTiles,
                                                             boolean[][] baseHighways,
                                                             int[] anchor,
                                                             int type) {
        try {
            int width = baseTiles[0].length;
            int height = baseTiles.length;
            RebuildPatchRequest request = RebuildPatchRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setTiles(GpuEngineCodec.toTileGrid(baseTiles))
                .setHighways(GpuEngineCodec.toBoolGrid(baseHighways, width, height))
                .setRegion(Region.newBuilder().setX(0).setY(0).setWidth(width).setHeight(height))
                .setPatch(Rect.newBuilder().setX(anchor[0]).setY(anchor[1]).setWidth(1).setHeight(1))
                .addAppliedEdits(TileEdit.newBuilder().setX(anchor[0]).setY(anchor[1]).setType(type))
                .build();
            RebuildPatchResponse response = gpuEngineAdapter.rebuildPatch(request);
            if (!response.hasPatchTiles() || !response.hasPatchHighways()) {
                throw new IllegalStateException("GPU patch rebuild response missing patch grids");
            }
            int[][] patchTiles = GpuEngineCodec.fromTileGrid(response.getPatchTiles());
            boolean[][] patchHighways = GpuEngineCodec.fromBoolGrid(response.getPatchHighways(), 1, 1);
            if (patchTiles.length != 1 || patchTiles[0].length != 1 || patchHighways.length != 1 || patchHighways[0].length != 1) {
                throw new IllegalStateException("GPU patch rebuild returned unexpected patch size");
            }
            int[][] tiles = copyTiles(baseTiles);
            boolean[][] highways = copyHighways(baseHighways, baseTiles.length, baseTiles[0].length);
            tiles[anchor[1]][anchor[0]] = patchTiles[0][0];
            highways[anchor[1]][anchor[0]] = patchHighways[0][0];
            return summarizeProposalApply(baseTiles, baseHighways, tiles, highways);
        } catch (Exception e) {
            log.warn("gpu patch rebuild failed roomId={} proposalId={} error={}",
                room.getId(),
                proposal.getId(),
                e.getMessage());
            return null;
        }
    }

    private ProposalApplyResult summarizeProposalApply(int[][] baseTiles,
                                                       boolean[][] baseHighways,
                                                       int[][] tiles,
                                                       boolean[][] highways) {
        int changed = 0;
        for (int y = 0; y < tiles.length; y++) {
            for (int x = 0; x < tiles[0].length; x++) {
                if (baseTiles[y][x] != tiles[y][x] || baseHighways[y][x] != highways[y][x]) {
                    changed++;
                }
            }
        }
        return new ProposalApplyResult(tiles, highways, changed);
    }

    private boolean[][] copyHighways(boolean[][] source, int height, int width) {
        boolean[][] out = new boolean[height][width];
        for (int y = 0; y < Math.min(source.length, height); y++) {
            if (source[y] == null) {
                continue;
            }
            System.arraycopy(source[y], 0, out[y], 0, Math.min(source[y].length, width));
        }
        return out;
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
        boolean approve = approvals > rejections
            || (approveOnTieAfterExpiry && approvals == rejections && approvals > 0);
        if (approve) {
            applyProposal(room, proposal);
        } else {
            proposal.setStatus("REJECTED");
            proposalRepository.save(proposal);
        }
        proposalRepository.save(proposal);
    }

    private int capQuorum(int rawQuorum) {
        int normalized = Math.max(1, rawQuorum);
        if (quorumCap <= 0) {
            return normalized;
        }
        return Math.min(quorumCap, normalized);
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
        int[] anchor = proposalAnchor(proposal);
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
        return findNeighborsForAnchor(
            tiles,
            cx,
            cy,
            participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room),
            botRepository.findByRoom(room)
        );
    }

    private List<String> findNeighborsForAnchor(int[][] tiles,
                                                int cx,
                                                int cy,
                                                List<RoomParticipant> participants,
                                                List<RoomBot> bots) {
        int[] view = computeViewBounds(cx, cy, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        List<String> neighbors = new ArrayList<>();
        for (RoomParticipant participant : participants) {
            if (participant.getX() != null && participant.getY() != null
                && inView(viewX, viewY, viewW, viewH, participant.getX(), participant.getY())) {
                neighbors.add("P:" + participant.getId());
            }
        }
        for (RoomBot bot : bots) {
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
        List<OccupantSnapshot> occupants = getCachedOccupancy(room);
        int half = densityAreaSize / 2;
        int minX = x - half;
        int maxX = x + half - 1;
        int minY = y - half;
        int maxY = y + half - 1;
        int count = 0;
        for (OccupantSnapshot occ : occupants) {
            if (occ == null) {
                continue;
            }
            String id = occ.getId();
            if (id != null && ignoreId != null && !ignoreId.isBlank()) {
                if (id.equals(ignoreId) || id.endsWith(ignoreId)) {
                    continue;
                }
            }
            int ox = occ.getX();
            int oy = occ.getY();
            if (ox >= minX && ox <= maxX && oy >= minY && oy <= maxY) {
                count++;
            }
        }
        return count < maxOccupants;
    }

    private List<OccupantSnapshot> getCachedOccupancy(Room room) {
        CachedRoomState cached = roomStateCache.get(room.getId());
        if (cached != null && cached.occupancy != null) {
            return cached.occupancy;
        }
        RoomState state = stateRepository.findByRoom(room).orElse(null);
        if (state == null) {
            return new ArrayList<>();
        }
        List<OccupantSnapshot> occupancy = readOccupancyJson(state.getOccupancyJson());
        if (cached != null) {
            cached.occupancy = occupancy;
            cached.occupancyUpdatedAt = state.getOccupancyUpdatedAt();
        }
        return occupancy;
    }

    private List<OccupantSnapshot> buildOccupancySnapshot(Room room) {
        List<OccupantSnapshot> occupants = new ArrayList<>();
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            if (participant.getId() == null || participant.getX() == null || participant.getY() == null) {
                continue;
            }
            if (participant.getX() < 0 || participant.getY() < 0) {
                continue;
            }
            occupants.add(new OccupantSnapshot("P:" + participant.getId(), participant.getX(), participant.getY()));
        }
        for (RoomBot bot : botRepository.findByRoom(room)) {
            if (bot.getId() == null || bot.getX() == null || bot.getY() == null) {
                continue;
            }
            if (bot.getX() < 0 || bot.getY() < 0) {
                continue;
            }
            occupants.add(new OccupantSnapshot("B:" + bot.getId(), bot.getX(), bot.getY()));
        }
        return occupants;
    }

    private List<OccupantSnapshot> readOccupancyJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<OccupantSnapshot>>() {});
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private boolean trySimulateBotsWithGpu(Room room, RoomState state) {
        if (!gpuEnabled || !gpuBotsEnabled || gpuEngineAdapter == null) {
            return false;
        }
        try {
            int[][] tiles = getTilesForRoom(room, state);
            if (tiles.length == 0 || tiles[0].length == 0) {
                return false;
            }
            boolean[][] highways = getHighwaysForRoom(room, state);
            int width = tiles[0].length;
            int height = tiles.length;
            List<RoomBot> bots = new ArrayList<>(botRepository.findByRoom(room));
            if (bots.isEmpty()) {
                return true;
            }
            if (bots.size() < gpuBotsMinBotCount) {
                return false;
            }
            long botWorkItems = (long) bots.size() * (long) gpuBotsSteps;
            if (botWorkItems < gpuBotsMinWorkItems) {
                return false;
            }

            List<RoomBot> spawned = new ArrayList<>();
            for (RoomBot bot : bots) {
                if (bot.getX() == null || bot.getY() == null) {
                    assignBotSpawn(bot, tiles);
                    spawned.add(bot);
                }
            }
            if (!spawned.isEmpty()) {
                botRepository.saveAll(spawned);
            }

            GpuWorldCache cache = gpuWorldCache.computeIfAbsent(room.getId(),
                ignored -> new GpuWorldCache(UUID.randomUUID().toString()));
            boolean sendFullWorld = !gpuBotsCacheWorld || cache.tiles == null || cache.highways == null
                || cache.tiles.length != height || cache.tiles.length == 0 || cache.tiles[0].length != width;
            List<TileEdit> tileDiffs = Collections.emptyList();
            if (gpuBotsCacheWorld && !sendFullWorld && gpuBotsDiffMaxEdits > 0) {
                tileDiffs = collectGpuTileDiffs(cache.tiles, cache.highways, tiles, highways, gpuBotsDiffMaxEdits);
                if (tileDiffs == null) {
                    sendFullWorld = true;
                    tileDiffs = Collections.emptyList();
                }
            }

            SimulateBotsRequest.Builder builder = SimulateBotsRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setParamsJson(buildGpuBotParams(room, width, height))
                .setSteps(gpuBotsSteps);
            if (gpuBotsCacheWorld) {
                builder.setWorldId(cache.worldId);
            }
            if (sendFullWorld) {
                builder
                    .setTiles(GpuEngineCodec.toTileGrid(tiles))
                    .setHighways(GpuEngineCodec.toBoolGrid(highways, width, height));
                cache.tiles = copyTiles(tiles);
                cache.highways = copyHighways(highways);
            } else if (!tileDiffs.isEmpty()) {
                builder.addAllTileEdits(tileDiffs);
                applyTileEditsToCache(cache.tiles, cache.highways, tileDiffs);
            }

            for (RoomBot bot : bots) {
                if (bot.getId() == null || bot.getX() == null || bot.getY() == null) {
                    continue;
                }
                BotState.Builder botState = BotState.newBuilder()
                    .setId(bot.getId().toString())
                    .setX(bot.getX())
                    .setY(bot.getY());
                Map<String, Double> profile = readBotProfile(bot);
                applyGpuBotProfile(botState, profile);
                builder.addBots(botState);
            }

            SimulateBotsResponse response = gpuEngineAdapter.simulateBots(builder.build());
            Map<String, RoomBot> byId = new HashMap<>();
            for (RoomBot bot : bots) {
                if (bot.getId() != null) {
                    byId.put(bot.getId().toString(), bot);
                }
            }
            List<RoomBot> moved = new ArrayList<>();
            for (BotState botState : response.getBotsList()) {
                RoomBot bot = byId.get(botState.getId());
                if (bot == null) {
                    continue;
                }
                if (bot.getX() == null || bot.getY() == null
                    || bot.getX() != botState.getX() || bot.getY() != botState.getY()) {
                    bot.setX(botState.getX());
                    bot.setY(botState.getY());
                    moved.add(bot);
                }
            }
            if (!moved.isEmpty()) {
                botRepository.saveAll(moved);
            }

            if (response.getEditsCount() > 0) {
                if (gpuBotsApplyEdits) {
                    int changed = applyGpuEditsToCachedState(room, state, response.getEditsList());
                    if (changed > 0) {
                        log.debug("gpu bot edits applied roomId={} edits={} changed={}", room.getId(), response.getEditsCount(), changed);
                    }
                } else {
                    log.debug("gpu bot edits ignored roomId={} edits={}", room.getId(), response.getEditsCount());
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("gpu bot simulation failed roomId={} msg={}", room.getId(), e.getMessage());
            return false;
        }
    }

    private String buildGpuBotParams(Room room, int width, int height) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("roomId", room.getId().toString());
            params.put("width", width);
            params.put("height", height);
            params.put("neighborRadius", neighborRadius);
            params.put("minNeighbors", minNeighbors);
            params.put("densityAreaSize", densityAreaSize);
            params.put("maxOccupants", maxOccupants);
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void applyGpuBotProfile(BotState.Builder botState, Map<String, Double> profile) {
        botState
            .setForest(profile.getOrDefault("forest", 0.5).floatValue())
            .setWater(profile.getOrDefault("water", 0.5).floatValue())
            .setIndustry(profile.getOrDefault("industry", 0.5).floatValue())
            .setClear(profile.getOrDefault("clear", 0.5).floatValue())
            .setMobility(profile.getOrDefault("mobility", 0.5).floatValue());
    }

    private List<TileEdit> collectGpuTileDiffs(int[][] oldTiles,
                                               boolean[][] oldHighways,
                                               int[][] newTiles,
                                               boolean[][] newHighways,
                                               int maxEdits) {
        int height = newTiles.length;
        int width = height == 0 ? 0 : newTiles[0].length;
        if (width == 0 || height == 0) {
            return Collections.emptyList();
        }
        List<TileEdit> edits = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int oldTile = oldTiles[y][x];
                int newTile = newTiles[y][x];
                boolean oldHighway = oldHighways.length > 0 && oldHighways[y][x];
                boolean newHighway = newHighways.length > 0 && newHighways[y][x];
                if (oldTile == newTile && oldHighway == newHighway) {
                    continue;
                }
                int type = newHighway ? 4 : newTile;
                edits.add(TileEdit.newBuilder().setX(x).setY(y).setType(type).build());
                if (maxEdits > 0 && edits.size() > maxEdits) {
                    return null;
                }
            }
        }
        return edits;
    }

    private void applyTileEditsToCache(int[][] tiles, boolean[][] highways, List<TileEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return;
        }
        int height = tiles.length;
        int width = height == 0 ? 0 : tiles[0].length;
        for (TileEdit edit : edits) {
            int x = edit.getX();
            int y = edit.getY();
            if (x < 0 || y < 0 || y >= height || x >= width) {
                continue;
            }
            int type = edit.getType();
            if (type == 4) {
                tiles[y][x] = 0;
                if (highways.length > 0) {
                    highways[y][x] = true;
                }
            } else {
                tiles[y][x] = type;
                if (highways.length > 0) {
                    highways[y][x] = false;
                }
            }
        }
    }

    private int[][] copyTiles(int[][] tiles) {
        int[][] copy = new int[tiles.length][];
        for (int y = 0; y < tiles.length; y++) {
            copy[y] = Arrays.copyOf(tiles[y], tiles[y].length);
        }
        return copy;
    }

    private boolean[][] copyHighways(boolean[][] highways) {
        boolean[][] copy = new boolean[highways.length][];
        for (int y = 0; y < highways.length; y++) {
            copy[y] = Arrays.copyOf(highways[y], highways[y].length);
        }
        return copy;
    }

    private int applyGpuEditsToCachedState(Room room, RoomState state, List<TileEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return 0;
        }
        CachedRoomState cached = getCachedRoomState(room, state);
        int[][] tiles = cached.tiles;
        boolean[][] highways = cached.highways;
        int height = tiles.length;
        int width = height == 0 ? 0 : tiles[0].length;
        if (width == 0 || height == 0) {
            return 0;
        }
        int changed = 0;
        for (TileEdit edit : edits) {
            if (edit == null) {
                continue;
            }
            int x = edit.getX();
            int y = edit.getY();
            if (x < 0 || y < 0 || y >= height || x >= width) {
                continue;
            }
            int newTile = edit.getType();
            boolean newHighway = false;
            if (newTile == 4) {
                newHighway = true;
                newTile = 0;
            }
            int oldTile = tiles[y][x];
            boolean oldHighway = highways.length > 0 && highways[y][x];
            if (oldTile == newTile && oldHighway == newHighway) {
                continue;
            }
            tiles[y][x] = newTile;
            if (highways.length > 0) {
                highways[y][x] = newHighway;
            }
            changed++;
        }
        if (changed == 0) {
            return 0;
        }
        cached.previousStateHash = cached.stateHash;
        cached.stateHash = hashTiles(tiles);
        TileCounts counts = computeTileCounts(tiles, highways);
        cached.tileClearCount = counts.clear;
        cached.tileForestCount = counts.forest;
        cached.tileWaterCount = counts.water;
        cached.tileIndustryCount = counts.industry;
        cached.tileHighwayCount = counts.road;
        cached.totalChangeCount = (cached.totalChangeCount == null ? 0 : cached.totalChangeCount) + changed;
        cached.changesSinceFlush = (cached.changesSinceFlush == null ? 0 : cached.changesSinceFlush) + changed;

        RoomState tempState = new RoomState();
        tempState.setSharedTokensJson(cached.sharedTokensJson);
        tempState.setSharedQuestsJson(cached.sharedQuestsJson);
        updateSharedTokens(tempState, 2, 1, 1);
        updateSharedQuests(tempState, tiles);
        cached.sharedTokensJson = tempState.getSharedTokensJson();
        cached.sharedQuestsJson = tempState.getSharedQuestsJson();

        cached.dirty = true;
        cached.lastFlushAt = Instant.now();
        return changed;
    }

    private void ensurePlayable(RoomParticipant participant) {
        if (participant.getLockedUntil() != null && participant.getLockedUntil().isAfter(Instant.now())) {
            throw new IllegalStateException("You are locked from play for a short time.");
        }
    }

    private record CachedSystemToken(String token) {
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
