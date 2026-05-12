package com.emergentterrain.service;

import com.emergentterrain.domain.*;
import com.emergentterrain.government.BotCivicState;
import com.emergentterrain.government.GovernmentSimulationService;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.government.GovernmentTimelineService;
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
import com.google.protobuf.ByteString;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private final RoomProposalRepository proposalRepository;
    private final RoomProposalVoteRepository voteRepository;
    private final RoomBotRepository botRepository;
    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final PayloadService payloadService;
    private final RoomUpdateBroadcaster broadcaster;
    private final WorldService worldService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LlmService llmService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PolicyConsultationService policyConsultationService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.UniversityService universityService;
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.Map<String, Double>> roomTileNudges =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final GovernmentSimulationService governmentSimulationService;
    private final GovernmentTimelineService governmentTimelineService;
    private final BotArchetypeBehaviorService archetypes;
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
    private final int botSeedMinimum;
    private final int maxBotsTickedPerMaintenance;
    private final BotMovementService movement;
    private final HighwayEmergenceService highways;
    private final MapIndexService mapIndex;
    private final TileValidatorService validator;
    private final OccupancyService occupancy;
    private final BotSimulationGpuService gpuSimulation;
    private final RoomProposalService proposals;
    private final RoomStateOrchestrator orchestrator;
    private final long flushOverdueLockTimeoutMs;
    private final long broadcastMinIntervalMs;
    private final int maintenanceWorkers;
    private final boolean backgroundMaintenanceEnabled;
    private final String defaultRoomName;
    private final boolean defaultRoomAutoCreate;
    private final boolean multipleRoomsEnabled;
    private final boolean defaultRoomUseWhenAvailable;
    private final String defaultRoomSystemEmail;
    private final String defaultRoomSystemPasswordHash;
    private final ConcurrentHashMap<UUID, ReentrantLock> botLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> proposalLocks = new ConcurrentHashMap<>();
    private final ReentrantLock proposalTableLock = new ReentrantLock();
    private final ConcurrentHashMap<UUID, CachedRoomState> roomStateCache = new ConcurrentHashMap<>();
    private final AtomicInteger maintenanceCursor = new AtomicInteger();
    private final ConcurrentHashMap<UUID, Long> lastBroadcastAtMs = new ConcurrentHashMap<>();
    private final ExecutorService maintenanceExecutor;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate botTransactionTemplate;
    private final ReentrantLock defaultRoomCreationLock = new ReentrantLock();
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
                       RoomProposalRepository proposalRepository,
                       RoomProposalVoteRepository voteRepository,
                       RoomBotRepository botRepository,
                       ProfileRepository profileRepository,
                       UserRepository userRepository,
                       PayloadService payloadService,
                       RoomUpdateBroadcaster broadcaster,
                       WorldService worldService,
                       GovernmentSimulationService governmentSimulationService,
                       GovernmentTimelineService governmentTimelineService,
                       BotArchetypeBehaviorService archetypes,
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
                       @Value("${app.bots.seedMinimum:50}") int botSeedMinimum,
                       @Value("${app.bots.maxTickPerMaintenance:0}") int maxBotsTickedPerMaintenance,
                       BotMovementService movement,
                       HighwayEmergenceService highways,
                       MapIndexService mapIndex,
                       TileValidatorService validator,
                       OccupancyService occupancy,
                       BotSimulationGpuService gpuSimulation,
                       RoomProposalService proposals,
                       RoomStateOrchestrator orchestrator,
                       @Value("${app.world.flushOverdueLockTimeoutMs:10}") long flushOverdueLockTimeoutMs,
                       @Value("${app.consensus.broadcastMinIntervalMs:0}") long broadcastMinIntervalMs,
                       @Value("${app.consensus.maintenanceWorkers:1}") int maintenanceWorkers,
                       @Value("${app.perf.backgroundMaintenanceEnabled:true}") boolean backgroundMaintenanceEnabled,
                       @Value("${app.consensus.noParticipationLockSeconds:300}") int noParticipationLockSeconds,
                       @Value("${app.room.defaultName:}") String defaultRoomName,
                       @Value("${app.room.defaultAutoCreate:false}") boolean defaultRoomAutoCreate,
                       @Value("${app.room.multipleRoomsEnabled:true}") boolean multipleRoomsEnabled,
                       @Value("${app.room.defaultUseWhenAvailable:false}") boolean defaultRoomUseWhenAvailable,
                       @Value("${app.room.defaultSystemEmail:system@emergentterrain.local}") String defaultRoomSystemEmail,
                       @Value("${app.room.defaultSystemPasswordHash:system-hash}") String defaultRoomSystemPasswordHash) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.stateRepository = stateRepository;
        this.proposalRepository = proposalRepository;
        this.voteRepository = voteRepository;
        this.botRepository = botRepository;
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.payloadService = payloadService;
        this.broadcaster = broadcaster;
        this.worldService = worldService;
        this.governmentSimulationService = governmentSimulationService;
        this.governmentTimelineService = governmentTimelineService;
        this.archetypes = archetypes;
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
        this.botSeedMinimum = Math.max(10, botSeedMinimum);
        this.maxBotsTickedPerMaintenance = Math.max(0, maxBotsTickedPerMaintenance);
        this.movement = movement;
        this.highways = highways;
        this.mapIndex = mapIndex;
        this.validator = validator;
        this.occupancy = occupancy;
        this.gpuSimulation = gpuSimulation;
        this.proposals = proposals;
        this.orchestrator = orchestrator;
        this.flushOverdueLockTimeoutMs = Math.max(1, flushOverdueLockTimeoutMs);
        this.broadcastMinIntervalMs = Math.max(0, broadcastMinIntervalMs);
        this.maintenanceWorkers = Math.max(1, maintenanceWorkers);
        this.maintenanceExecutor = Executors.newFixedThreadPool(this.maintenanceWorkers);
        this.backgroundMaintenanceEnabled = backgroundMaintenanceEnabled;
        this.noParticipationLockSeconds = noParticipationLockSeconds;
        this.defaultRoomName = defaultRoomName == null ? "" : defaultRoomName.trim();
        this.defaultRoomAutoCreate = defaultRoomAutoCreate;
        this.multipleRoomsEnabled = multipleRoomsEnabled;
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
        if (!multipleRoomsEnabled) {
            UUID roomId = ensureSingleRoom(payload, name);
            return joinRoomAsCreateResponse(payload, roomId);
        }
        if (defaultRoomUseWhenAvailable) {
            UUID defaultId = ensureDefaultRoom();
            if (defaultId != null) {
                return joinRoomAsCreateResponse(payload, defaultId);
            }
        }
        return createNewRoom(payload, name);
    }

    private RoomCreateResponse createNewRoom(Payload payload, String name) {
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
        state.setRoadPressureJson(toJson(highways.seedRoadPressure(world.getHighways())));
        state.setSharedTokensJson(toJson(new SharedTokens(0, 0, 0)));
        state.setSharedQuestsJson(toJson(defaultSharedQuests()));
        state.setMemoryMarkersJson(toJson(new ArrayList<>()));
        state.setGovernmentStateJson(null);
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
        GovernmentSimulationService.GovernmentMutation governmentInitialization =
            governmentSimulationService.ensureInitialized(room, state, bots, world.getTiles(), this::readBotProfile, Instant.now());
        state.setGovernmentStateJson(governmentSimulationService.serializeGovernmentState(governmentInitialization.governmentState()));
        if (!governmentInitialization.dirtyBots().isEmpty()) {
            botRepository.saveAll(governmentInitialization.dirtyBots());
        }
        state = stateRepository.save(state);
        updateCacheFromState(room, state);
        governmentTimelineService.capture(room, governmentInitialization.governmentState(), Instant.now());
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
        response.setGovernmentSummary(governmentInitialization.summary());
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
        if (!multipleRoomsEnabled) {
            roomId = ensureSingleRoom(payload, defaultRoomName);
        }
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
        if (!multipleRoomsEnabled) {
            return joinRoom(payload, ensureSingleRoom(payload, name));
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
            gpuSimulation.clearCache(roomId);
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
        defaultRoomCreationLock.lock();
        try {
            existing = resolveDefaultRoomId();
            if (existing != null) {
                return existing;
            }
            if (roomRepository.count() > 0) {
                return null;
            }
            Payload payload = buildSystemPayload();
            RoomCreateResponse created = createNewRoom(payload, defaultRoomName);
            UUID createdId = UUID.fromString(created.getRoomId());
            defaultRoomId.set(createdId);
            log.info("default room created id={} name={}", createdId, defaultRoomName);
            return createdId;
        } finally {
            defaultRoomCreationLock.unlock();
        }
    }

    private UUID ensureSingleRoom(Payload payload, String requestedName) {
        UUID existing = resolveSingleRoomId();
        if (existing != null) {
            return existing;
        }
        defaultRoomCreationLock.lock();
        try {
            existing = resolveSingleRoomId();
            if (existing != null) {
                return existing;
            }
            RoomCreateResponse created = createNewRoom(payload, singleRoomCreateName(requestedName));
            UUID createdId = UUID.fromString(created.getRoomId());
            defaultRoomId.set(createdId);
            log.info("single room created id={} name={}", createdId, created.getRoomName());
            return createdId;
        } finally {
            defaultRoomCreationLock.unlock();
        }
    }

    private UUID resolveSingleRoomId() {
        UUID defaultId = resolveDefaultRoomId();
        if (defaultId != null) {
            return defaultId;
        }
        // Phase 3 Iter 6: sandbox rooms are excluded from the default-room
        // picker so research experiments don't pull human players into a
        // sandbox by accident. Sandbox-flagged rooms still receive
        // maintenance and bot lifecycles; they're just not the fallback.
        return roomRepository.findTopBySandboxFalseOrderByCreatedAtAsc()
            .map(Room::getId)
            .orElse(null);
    }

    private String singleRoomCreateName(String requestedName) {
        if (!defaultRoomName.isBlank()) {
            return defaultRoomName;
        }
        return requestedName;
    }

    private RoomCreateResponse joinRoomAsCreateResponse(Payload payload, UUID roomId) {
        RoomJoinResponse joined = joinRoom(payload, roomId);
        RoomStateResponse state = getRoomState(payload, roomId);
        RoomCreateResponse response = new RoomCreateResponse();
        response.setRoomId(state.getRoomId());
        response.setRoomName(state.getRoomName());
        response.setParticipantId(joined.getParticipantId());
        response.setTiles(state.getTiles());
        response.setHighways(state.getHighways());
        response.setSharedTokens(state.getSharedTokens());
        response.setSharedQuests(state.getSharedQuests());
        response.setMemoryMarkers(state.getMemoryMarkers());
        response.setParticipants(state.getParticipants());
        response.setBots(state.getBots());
        response.setProposals(state.getProposals());
        response.setTurnOwnerParticipantId(state.getTurnOwnerParticipantId());
        response.setTurnExpiresAt(state.getTurnExpiresAt());
        response.setLockUntil(state.getLockUntil());
        response.setNeighborRadius(state.getNeighborRadius());
        response.setMinNeighbors(state.getMinNeighbors());
        response.setDensityAreaSize(state.getDensityAreaSize());
        response.setMaxOccupants(state.getMaxOccupants());
        response.setGovernmentSummary(state.getGovernmentSummary());
        return response;
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
        List<RoomBot> bots = ensureBots(room, getTilesForRoom(room, state), false);
        GovernmentSimulationService.GovernmentMutation governmentInitialization =
            governmentSimulationService.ensureInitialized(room, state, bots, getTilesForRoom(room, state), this::readBotProfile, Instant.now());
        if (governmentInitialization.governmentChanged()) {
            state.setGovernmentStateJson(governmentSimulationService.serializeGovernmentState(governmentInitialization.governmentState()));
            stateRepository.save(state);
            if (!governmentInitialization.dirtyBots().isEmpty()) {
                botRepository.saveAll(governmentInitialization.dirtyBots());
            }
            updateCacheFromState(room, state);
        }
        return toRoomStateResponse(room, state, participant);
    }

    @Transactional
    public GovernmentTrendResponse getGovernmentTrend(Payload payload, UUID roomId, Integer windowMinutes) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        ensureParticipant(room, profile);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        int[][] tiles = getTilesForRoom(room, state);
        List<RoomBot> bots = ensureBots(room, tiles, false);
        Instant now = Instant.now();
        GovernmentSimulationService.GovernmentMutation governmentInitialization =
            governmentSimulationService.ensureInitialized(room, state, bots, tiles, this::readBotProfile, now);
        if (governmentInitialization.governmentChanged()) {
            state.setGovernmentStateJson(governmentSimulationService.serializeGovernmentState(governmentInitialization.governmentState()));
            stateRepository.save(state);
            if (!governmentInitialization.dirtyBots().isEmpty()) {
                botRepository.saveAll(governmentInitialization.dirtyBots());
            }
            updateCacheFromState(room, state);
        }
        governmentTimelineService.capture(room, governmentInitialization.governmentState(), now);
        return governmentTimelineService.getTrend(room, windowMinutes, now);
    }

    @Scheduled(fixedDelayString = "${app.consensus.maintenanceIntervalMs:5000}")
    @Transactional
    public void scheduledRoomMaintenance() {
        if (!backgroundMaintenanceEnabled) {
            return;
        }
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
            AtomicReference<List<RoomBot>> maintainedBots = new AtomicReference<>(new ArrayList<>());
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
                        // Seed only to minimum; organic growth via dating fills the rest
                        List<RoomBot> bots = ensureBots(room, tiles, false);
                        maintainedBots.set(new ArrayList<>(bots));
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
                log.error("maintenance bots error roomId={}", room.getId(), e);
            }
            if (tickedBots.get()) {
                state.setLastBotTickAt(now);
                metadataDirty = true;
                if (evolveHighwaysFromDemand(room, state)) {
                    metadataDirty = true;
                }
            }
            List<RoomBot> botsForGovernment = maintainedBots.get();
            if (botsForGovernment == null || botsForGovernment.isEmpty()) {
                botsForGovernment = botRepository.findByRoom(room);
            }
            try {
                GovernmentSimulationService.GovernmentMutation governmentMutation =
                    governmentSimulationService.tick(room, state, botsForGovernment, getTilesForRoom(room, state), this::readBotProfile, now);
                if (governmentMutation.governmentChanged()) {
                    GovernmentState govState = governmentMutation.governmentState();
                    if (policyConsultationService != null) {
                        // Drain any result that arrived from a prior async trigger
                        policyConsultationService.drainAndApply(room.getId(), govState).ifPresent(resp -> {
                            if (!govState.getTileNudges().isEmpty()) {
                                roomTileNudges.put(room.getId(), new java.util.HashMap<>(govState.getTileNudges()));
                            }
                        });
                        // Fire next consultation asynchronously (returns immediately)
                        policyConsultationService.triggerAsync(room.getId(), govState);
                    }
                    updateGovernmentStateCache(room, state, govState);
                    governmentTimelineService.capture(room, govState, now);
                    broadcaster.broadcastGovernmentUpdate(room.getId());
                    if (llmService != null) {
                        triggerLlmGovernmentEvents(room.getId(), govState);
                    }
                    metadataDirty = true;
                }
                if (!governmentMutation.dirtyBots().isEmpty()) {
                    botRepository.saveAll(governmentMutation.dirtyBots());
                }
                if (universityService != null) {
                    GovernmentState govForUni = governmentMutation.governmentState();
                    long gameTick = govForUni != null ? govForUni.getTick() : 0L;
                    // Pass only ACTIVE bots; retired bots must not participate in research
                    List<RoomBot> activeBots = botsForGovernment.stream()
                            .filter(b -> b.getStatus() == com.emergentterrain.domain.RoomBot.BotStatus.ACTIVE)
                            .collect(java.util.stream.Collectors.toList());
                    universityService.maybeTick(room.getId(), activeBots, gameTick, govForUni);
                }
            } catch (Throwable t) {
                log.error("maintenance government tick error roomId={}", room.getId(), t);
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
                boolean flushed = orchestrator.persistIfDirty(room, state, roomStateCache.get(room.getId()));
                log.debug("maintenance step=evaluatePendingProposals start roomId={}", room.getId());
                proposals.evaluatePendingProposals(room, this::applyProposal);
                log.debug("maintenance step=evaluatePendingProposals end roomId={}", room.getId());
                if (metadataDirty && !flushed) {
                    stateRepository.save(state);
                }
                maybeBroadcast(room, state);
                long roomMs = (System.nanoTime() - roomStart) / 1_000_000;
                log.debug("maintenance roomId={} roomMs={}", room.getId(), roomMs);
            } catch (Exception e) {
                log.error("maintenance error roomId={}", room.getId(), e);
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
        List<OccupantSnapshot> snapshot = occupancy.buildOccupancySnapshot(room);
        state.setOccupancyJson(toJson(snapshot));
        state.setOccupancyUpdatedAt(now);
        CachedRoomState cached = roomStateCache.get(room.getId());
        if (cached != null) {
            cached.occupancy = snapshot;
            cached.occupancyUpdatedAt = now;
        }
        return true;
    }

    private boolean evolveHighwaysFromDemand(Room room, RoomState state) {
        CachedRoomState cached = roomStateCache.get(room.getId());
        if (cached == null) return false;
        if (!highways.evolve(cached.tiles, cached.highways, cached.roadPressure)) {
            return false;
        }
        // The pure evolve mutated tiles/highways/roadPressure in place; refresh the
        // cache bookkeeping that lived alongside the inline algorithm before the carve.
        cached.previousStateHash = cached.stateHash;
        cached.stateHash = hashTiles(cached.tiles);
        TileCounts counts = computeTileCounts(cached.tiles, cached.highways);
        cached.tileClearCount = counts.clear;
        cached.tileForestCount = counts.forest;
        cached.tileWaterCount = counts.water;
        cached.tileIndustryCount = counts.industry;
        cached.tileHighwayCount = counts.road;
        cached.totalChangeCount = (cached.totalChangeCount == null ? 0 : cached.totalChangeCount) + 1;
        cached.changesSinceFlush = (cached.changesSinceFlush == null ? 0 : cached.changesSinceFlush) + 1;
        cached.dirty = true;
        return true;
    }

    private void maybeBroadcast(Room room, RoomState state) {
        UUID roomId = room.getId();
        if (broadcastMinIntervalMs <= 0) {
            broadcaster.broadcastRoom(toRoomVisualState(room, state));
            return;
        }
        long nowMs = System.currentTimeMillis();
        Long last = lastBroadcastAtMs.get(roomId);
        if (last == null || nowMs - last >= broadcastMinIntervalMs) {
            lastBroadcastAtMs.put(roomId, nowMs);
            broadcaster.broadcastRoom(toRoomVisualState(room, state));
        }
    }

    @Scheduled(fixedDelayString = "${app.world.flushIntervalMs:30000}")
    @Transactional
    public void flushRoomStateCache() {
        if (!backgroundMaintenanceEnabled) {
            return;
        }
        Instant now = Instant.now();
        for (Map.Entry<UUID, CachedRoomState> entry : roomStateCache.entrySet()) {
            UUID roomId = entry.getKey();
            CachedRoomState cached = entry.getValue();
            if (!cached.dirty) {
                continue;
            }
            int changesSinceFlush = cached.changesSinceFlush == null ? 0 : cached.changesSinceFlush;
            boolean overdue = cached.lastFlushAt == null
                || now.toEpochMilli() - cached.lastFlushAt.toEpochMilli() >= orchestrator.flushMaxIntervalMs();
            if (changesSinceFlush < orchestrator.flushMinChanges() && !overdue) {
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
                orchestrator.persistIfDirty(room, state, cached);
            } finally {
                lock.unlock();
            }
        }
    }

    // Phase 2.5 carve: persistDirtyRoomState moved to RoomStateOrchestrator.
    // Callers above invoke `orchestrator.persistIfDirty(room, state, cached)`.

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
        maybeBroadcast(room, state);
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
            int[] delta = proposals.extractAnchor(request.getDelta());
            proposals.validateProposalDelta(baseTiles, delta, request.getDelta());
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
            proposal.setRequiredVotes(proposals.capQuorum(neighbors.size() / 2 + 1));
            proposal.setExpiresAt(Instant.now().plusSeconds(voteWindowSeconds));
            proposal.setApprovals(1);
            proposal.setRejections(0);
            proposal = proposalRepository.save(proposal);

            RoomProposalVote vote = new RoomProposalVote();
            vote.setProposal(proposal);
            vote.setParticipant(participant);
            vote.setApprove(true);
            voteRepository.save(vote);

            int[][] proposedTiles = proposals.applyDeltaToTiles(baseTiles, proposal);
            boolean[][] highways = getHighwaysForRoom(room, state);
            proposals.applyBotVotes(room, proposal, baseTiles, proposedTiles, highways,
                    this::readBotProfile, this::driftBotProfile);
            proposals.evaluateProposal(room, proposal, this::applyProposal);
            if ("PENDING".equals(proposal.getStatus())) {
                proposalRepository.save(proposal);
            }
            RoomState latestState = state;
            if (!"PENDING".equals(proposal.getStatus())) {
                latestState = stateRepository.findByRoom(room).orElse(state);
            }
            RoomStateResponse response = toRoomStateResponse(room, latestState, participant);
            maybeBroadcast(room, latestState);
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
            RoomState voteState = stateRepository.findByRoom(room)
                .orElseThrow(() -> new IllegalStateException("Room state missing"));
            int[][] voteTiles = getTilesForRoom(room, voteState);
            if (!proposals.canVoteNow(voteTiles, proposal, participant)) {
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
            proposals.evaluateProposal(room, proposal, this::applyProposal);

            RoomState state = stateRepository.findByRoom(room)
                .orElseThrow(() -> new IllegalStateException("Room state missing"));
            RoomStateResponse response = toRoomStateResponse(room, state, participant);
            maybeBroadcast(room, state);
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
        maybeBroadcast(room, state);
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
        maybeBroadcast(room, state);
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
        List<RoomBot> allBots = botRepository.findByRoom(room);
        // Only count and manage ACTIVE bots; retired bots are historical records only
        List<RoomBot> existing = allBots.stream()
                .filter(b -> b.getStatus() == com.emergentterrain.domain.RoomBot.BotStatus.ACTIVE)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        int maxOccupantsTotal = Math.max(3, (tiles.length * tiles[0].length * maxOccupants) / (densityAreaSize * densityAreaSize));
        int targetBots = Math.max(40, (int) Math.round(maxOccupantsTotal * botPopulationFraction));
        targetBots = Math.min(targetBots, maxBotsPerRoom);
        // fullPopulation=true is used only for historical/ad-hoc fills; normal operation
        // seeds to botSeedMinimum and lets dating drive organic growth toward K.
        int desiredBots = fullPopulation ? targetBots : Math.min(targetBots, botSeedMinimum);

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
            if (occupancy.withinRadius(cx, cy, bot.getX(), bot.getY(), neighborRadius)) {
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
                && occupancy.withinRadius(cx, cy, bot.getX(), bot.getY(), neighborRadius)) {
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
            info.setCivicState(governmentSimulationService.readBotCivicState(bot.getCivicStateJson()));
            if (bot.getX() != null && bot.getY() != null && tiles.length > 0) {
                LocalStats stats = proposals.localStats(tiles, highways, bot.getX(), bot.getY());
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
        // Fetch tiles once before the loop — pre-carve canVoteNow fetched per-call inside RoomService.
        int[][] tilesForCanVote = null;
        if (participant != null) {
            RoomState s = stateRepository.findByRoom(room).orElse(null);
            tilesForCanVote = s == null ? null : getTilesForRoom(room, s);
        }
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
            info.setNeighborCount(proposals.readNeighborIds(proposal).size());
            info.setExpiresAt(proposal.getExpiresAt());
            info.setAnchorX(proposal.getAnchorX());
            info.setAnchorY(proposal.getAnchorY());
            info.setBotGenerated(Boolean.TRUE.equals(proposal.isBotGenerated()));
            info.setBotLabel(proposal.getBotLabel());
            if (proposal.getBotProposer() != null) {
                info.setBotId(proposal.getBotProposer().getId().toString());
            }
            Map<String, Integer> summary = proposals.readBotVoteSummary(proposal);
            if (summary != null) {
                info.setBotYes(summary.getOrDefault("botYes", 0));
                info.setBotNo(summary.getOrDefault("botNo", 0));
            }
            info.setTileType(proposals.proposalDeltaType(proposal));
            if (participantKey != null) {
                info.setCanVote(tilesForCanVote != null
                        && proposals.canVoteNow(tilesForCanVote, proposal, participant));
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
            response.setGovernmentSummary(readGovernmentSummaryJson(cached.governmentStateJson));
        } else {
            response.setTiles(fromJsonTiles(state.getTilesJson()));
            response.setHighways(fromJsonHighways(state.getHighwaysJson()));
            response.setSharedTokens(readSharedTokens(state));
            response.setSharedQuests(readSharedQuests(state));
            response.setMemoryMarkers(readMemoryMarkers(state));
            response.setStateHash(state.getStateHash());
            response.setPreviousStateHash(state.getPreviousStateHash());
            response.setGovernmentSummary(readGovernmentSummaryJson(state.getGovernmentStateJson()));
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

    private RoomVisualState toRoomVisualState(Room room, RoomState state) {
        CachedRoomState cached = roomStateCache.get(room.getId());
        RoomVisualState visual = new RoomVisualState();
        visual.setRoomId(room.getId());
        if (cached != null) {
            visual.setTiles(copyTiles(cached.tiles));
            visual.setHighways(copyHighways(cached.highways));
            visual.setStateHash(cached.stateHash);
            visual.setPreviousStateHash(cached.previousStateHash);
        } else {
            visual.setTiles(fromJsonTiles(state.getTilesJson()));
            visual.setHighways(fromJsonHighways(state.getHighwaysJson()));
            visual.setStateHash(state.getStateHash());
            visual.setPreviousStateHash(state.getPreviousStateHash());
        }
        visual.setProposals(buildProposals(room, null));
        return visual;
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

    private GovernmentSummary readGovernmentSummaryJson(String json) {
        GovernmentState governmentState = governmentSimulationService.readGovernmentState(json);
        return governmentSimulationService.buildSummary(governmentState);
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

    // localStats moved to RoomProposalService (Phase 2.5 Phase 2 — bot-vote machinery).

    // violatesCommunityRules, hasAccessConnectivity, floodAccess, isAccessTile and
    // isRapidOscillation moved to TileValidatorService (Phase 2.5). The
    // DB-fetching trend / last-human / recent-delta-types helpers were moved to
    // RoomProposalService (Phase 2.5 Phase 2 — bot-vote machinery).

    // proposalDeltaType and readBotVoteSummary moved to RoomProposalService (Phase 2.5).

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

    private BotMotionState readBotMotionState(RoomBot bot) {
        BotMotionState state = new BotMotionState();
        state.currentX = bot.getX();
        state.currentY = bot.getY();
        if (bot.getStateJson() == null || bot.getStateJson().isBlank()) {
            return state;
        }
        try {
            BotMotionState parsed = objectMapper.readValue(bot.getStateJson(), BotMotionState.class);
            parsed.currentX = bot.getX();
            parsed.currentY = bot.getY();
            parsed.normalize();
            return parsed;
        } catch (Exception ignored) {
            return state;
        }
    }

    private void writeBotMotionState(RoomBot bot, BotMotionState state) {
        try {
            state.normalize();
            bot.setStateJson(objectMapper.writeValueAsString(state));
        } catch (Exception ignored) {
        }
    }

    // encodeBotMotionStateBytes / decodeBotMotionStateBytes moved to
    // BotSimulationGpuService (Phase 2.5). The codec is pure; the caller
    // (trySimulateBotsWithGpu) supplies the seed from readBotMotionState so the
    // service stays free of bot-codec dependencies.

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

    /**
     * BiConsumer-shaped wrapper for {@link #maybeDriftProfile}, passed as
     * the {@code driftCallback} into
     * {@link RoomProposalService#botVoteDecision} so the bot-vote machinery
     * can stay free of the side-effecting DB write. Re-reads the profile to
     * stay in step with what the bot has on disk now (the in-call profile
     * map inside botVoteDecision is about to be discarded).
     */
    private void driftBotProfile(RoomBot bot, LocalStats stats) {
        Map<String, Double> profile = readBotProfile(bot);
        maybeDriftProfile(bot, profile, stats);
    }

    private String localRole(LocalStats stats) {
        double fNeed = Math.max(0, 0.30 - stats.forest());
        double wNeed = Math.max(0, 0.14 - stats.water());
        double cNeed = Math.max(0, 0.34 - stats.clear());
        double iNeed = Math.max(0, 0.14 - stats.industry());
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

    // GpuWorldCache promoted to a top-level package-private class. See
    // GpuWorldCache.java in this package.

    // Phase 2.5 carve: IntegralMaps, CoarseCell, CachedMap promoted to top-level
    // package-private classes; buildIntegrals, rectSum, getCachedMap, buildCachedMap
    // moved to MapIndexService. tickBots above calls mapIndex.getCachedMap(...) and
    // movement.findBestProfileTarget(...) instead of the inline versions that used
    // to live here.

    private CachedRoomState getCachedRoomState(Room room, RoomState state) {
        CachedRoomState cached = roomStateCache.get(room.getId());
        if (cached != null) {
            return cached;
        }
        int[][] tiles = fromJsonTiles(state.getTilesJson());
        boolean[][] highways = fromJsonHighways(state.getHighwaysJson());
        double[][] roadPressure = fromJsonRoadPressure(state.getRoadPressureJson(), highways, tiles.length, tiles.length == 0 ? 0 : tiles[0].length);
        TileCounts counts = computeTileCounts(tiles, highways);
        CachedRoomState built = new CachedRoomState(
            tiles,
            highways,
            roadPressure,
            state.getSharedTokensJson(),
            state.getSharedQuestsJson(),
            state.getMemoryMarkersJson(),
            state.getGovernmentStateJson(),
            state.getStateHash(),
            state.getPreviousStateHash(),
            counts,
            state.getTotalChangeCount(),
            state.getChangesSinceFlush(),
            occupancy.readOccupancyJson(state.getOccupancyJson()),
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
        double[][] roadPressure = fromJsonRoadPressure(state.getRoadPressureJson(), highways, tiles.length, tiles.length == 0 ? 0 : tiles[0].length);
        TileCounts counts = computeTileCounts(tiles, highways);
        CachedRoomState cached = new CachedRoomState(
            tiles,
            highways,
            roadPressure,
            state.getSharedTokensJson(),
            state.getSharedQuestsJson(),
            state.getMemoryMarkersJson(),
            state.getGovernmentStateJson(),
            state.getStateHash(),
            state.getPreviousStateHash(),
            counts,
            totalChanges,
            changesSinceFlush,
            occupancy.readOccupancyJson(state.getOccupancyJson()),
            state.getOccupancyUpdatedAt()
        );
        cached.dirty = false;
        cached.lastFlushAt = Instant.now();
        roomStateCache.put(room.getId(), cached);
    }

    private void updateCacheFromState(Room room, RoomState state) {
        updateCacheFromState(room, state, 0);
    }

    private void updateGovernmentStateCache(Room room, RoomState state, GovernmentState governmentState) {
        String governmentJson = governmentSimulationService.serializeGovernmentState(governmentState);
        state.setGovernmentStateJson(governmentJson);
        CachedRoomState cached = roomStateCache.get(room.getId());
        if (cached != null) {
            cached.governmentStateJson = governmentJson;
            cached.totalChangeCount = (cached.totalChangeCount == null ? 0 : cached.totalChangeCount) + 1;
            cached.changesSinceFlush = (cached.changesSinceFlush == null ? 0 : cached.changesSinceFlush) + 1;
            cached.dirty = true;
        }
    }

    private void triggerLlmGovernmentEvents(java.util.UUID roomId,
                                            GovernmentState governmentState) {
        llmService.generateGovernmentDigestIfAbsent(roomId, governmentState);
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

    // Phase 2.5 carve: OccupantSnapshot, CachedRoomState, TileCounts promoted to
    // top-level package-private classes in this package so the next extracted
    // services (BotMovementService, RoomStateOrchestrator) can reference them
    // without depending on a RoomService inner class. See OccupantSnapshot.java,
    // CachedRoomState.java, TileCounts.java in this package.

    // Phase 2.5 carve: findBestProfileLocation moved to BotMovementService as
    // findBestProfileTarget(currentX, currentY, tiles, profile, cached, mapIndex).
    // Caller in tickBots above invokes the new signature.

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

    private double[][] fromJsonRoadPressure(String json, boolean[][] highwayMap, int height, int width) {
        if (height <= 0 || width <= 0) {
            return new double[0][0];
        }
        if (json == null || json.isBlank()) {
            return highways.seedRoadPressure(highwayMap, height, width);
        }
        try {
            double[][] parsed = objectMapper.readValue(json, double[][].class);
            if (parsed.length != height || parsed.length == 0 || parsed[0].length != width) {
                return highways.seedRoadPressure(highwayMap, height, width);
            }
            double[][] pressure = copyRoadPressure(parsed);
            double seed = highways.seedPressure();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (highwayMap.length > 0 && highwayMap[y][x]) {
                        pressure[y][x] = Math.max(pressure[y][x], seed);
                    }
                }
            }
            return pressure;
        } catch (Exception e) {
            return highways.seedRoadPressure(highwayMap, height, width);
        }
    }

    private void tickBots(Room room, RoomState state) {
        ReentrantLock lock = botLocks.computeIfAbsent(room.getId(), id -> new ReentrantLock());
        lock.lock();
        try {
            if (trySimulateBotsWithGpu(room, state)) {
                return;
            }
            CachedRoomState cachedState = getCachedRoomState(room, state);
            int[][] tiles = cachedState.tiles;
            boolean[][] highways = cachedState.highways;
            CachedMap cached = mapIndex.getCachedMap(room, tiles, highways);
            List<RoomProposal> recentProposals = proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room);
            List<RoomBot> moved = new ArrayList<>();
            List<RoomBot> bots = new ArrayList<>(botRepository.findByRoom(room));
            if (maxBotsTickedPerMaintenance > 0 && bots.size() > maxBotsTickedPerMaintenance) {
                Collections.shuffle(bots, new Random(Objects.hash(room.getId(), Instant.now().getEpochSecond())));
                bots = bots.subList(0, maxBotsTickedPerMaintenance);
            }
            for (RoomBot bot : bots) {
                Map<String, Double> profile = readBotProfile(bot);
                BotMotionState motion = readBotMotionState(bot);
                motion.tick();
                if (bot.getX() == null || bot.getY() == null) {
                    assignBotSpawn(bot, tiles);
                    motion.resetAt(bot.getX(), bot.getY());
                    writeBotMotionState(bot, motion);
                    moved.add(bot);
                    continue;
                }
                if (shouldRelocate(bot, room, tiles, highways, recentProposals)) {
                    int[] bestTarget = movement.findBestProfileTarget(
                            bot.getX(), bot.getY(), tiles, profile, cached, mapIndex);
                    int travelDistance = bestTarget == null ? 0 : Math.abs(bestTarget[0] - bot.getX()) + Math.abs(bestTarget[1] - bot.getY());
                    int[] step = movement.stepTowardTarget(bot, tiles, highways, motion, profile, bestTarget);
                    if (step != null && canOccupy(room, step[0], step[1], bot.getId().toString())) {
                        int currentX = bot.getX();
                        int currentY = bot.getY();
                        applyBotMove(bot, motion, step[0], step[1]);
                        addRoadDemandForMove(cachedState, tiles, highways, profile, currentX, currentY, step[0], step[1], travelDistance, true);
                        moved.add(bot);
                    } else {
                        motion.clearPlan();
                        writeBotMotionState(bot, motion);
                    }
                    continue;
                }
                if (Math.random() > profile.getOrDefault("mobility", 0.5)) {
                    writeBotMotionState(bot, motion);
                    continue;
                }
                int[] next = movement.pickStep(bot, tiles, highways, profile, motion);
                if (next != null && canOccupy(room, next[0], next[1], bot.getId().toString())) {
                    int currentX = bot.getX();
                    int currentY = bot.getY();
                    applyBotMove(bot, motion, next[0], next[1]);
                    int travelDistance = motion.plannedSteps == null ? 0 : motion.plannedSteps.size() + 1;
                    addRoadDemandForMove(cachedState, tiles, highways, profile, currentX, currentY, next[0], next[1], travelDistance, false);
                    moved.add(bot);
                } else {
                    motion.clearPlan();
                    writeBotMotionState(bot, motion);
                }
            }
            if (!moved.isEmpty()) {
                botRepository.saveAll(moved);
            }
        } finally {
            lock.unlock();
        }
    }

    // Phase 2.5 carve: pickBotStep, planBotPath, chooseBestBotNeighbor,
    // antiOscillationAdjustment, stepTowardProfileTarget, tilePreferenceScore,
    // and isInBounds moved to BotMovementService. Callers above delegate via
    // `movement.pickStep / stepTowardTarget / tilePreferenceScore / isInBounds`.

    private boolean shouldRelocate(RoomBot bot, Room room, int[][] tiles, List<RoomProposal> recent) {
        int cx = bot.getX();
        int cy = bot.getY();
        return shouldRelocate(bot, room, tiles, new boolean[0][0], recent);
    }

    private boolean shouldRelocate(RoomBot bot, Room room, int[][] tiles, boolean[][] highways, List<RoomProposal> recent) {
        int cx = bot.getX();
        int cy = bot.getY();
        int[] view = occupancy.computeViewBounds(cx, cy, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        int visibleNeighbors = 0;
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            if (participant.getX() != null && participant.getY() != null
                && occupancy.inView(viewX, viewY, viewW, viewH, participant.getX(), participant.getY())) {
                visibleNeighbors++;
            }
        }
        for (RoomBot other : botRepository.findByRoom(room)) {
            if (other.getId().equals(bot.getId())) continue;
            if (other.getX() != null && other.getY() != null
                && occupancy.inView(viewX, viewY, viewW, viewH, other.getX(), other.getY())) {
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
            if (!occupancy.inView(viewX, viewY, viewW, viewH, ax, ay)) continue;
            if (!proposals.readNeighborIds(proposal).contains("B:" + bot.getId())) continue;
            int[][] baseTiles = tiles;
            int[][] proposed = proposals.applyDeltaToTiles(baseTiles, proposal);
            BotVote vote = proposals.botVoteDecision(room, bot, readBotProfile(bot), baseTiles, proposed, highways,
                    Boolean.TRUE.equals(proposal.isBotGenerated()), this::driftBotProfile);
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

    private void applyBotMove(RoomBot bot, BotMotionState motion, int nextX, int nextY) {
        int currentX = bot.getX() == null ? nextX : bot.getX();
        int currentY = bot.getY() == null ? nextY : bot.getY();
        bot.setX(nextX);
        bot.setY(nextY);
        motion.consumePlannedStep();
        movement.recordMoveOnMotion(motion, currentX, currentY, nextX, nextY);
        writeBotMotionState(bot, motion);
    }

    private void addRoadDemandForMove(CachedRoomState cached,
                                      int[][] tiles,
                                      boolean[][] highwayMap,
                                      Map<String, Double> profile,
                                      int fromX,
                                      int fromY,
                                      int toX,
                                      int toY,
                                      int travelDistance,
                                      boolean relocating) {
        if (cached == null || cached.roadPressure.length == 0) return;
        if (!movement.isInBounds(fromX, fromY, tiles) || !movement.isInBounds(toX, toY, tiles)) return;
        if (tiles[fromY][fromX] == 2 || tiles[toY][toX] == 2) return;
        boolean fromIsHwy = highwayMap.length > 0 && highwayMap[fromY][fromX];
        boolean toIsHwy   = highwayMap.length > 0 && highwayMap[toY][toX];
        HighwayEmergenceService.MoveDeposit deposit = highways.computeMoveDeposit(
                profile, travelDistance, relocating, movement.planLength(), fromIsHwy, toIsHwy);
        if (deposit.isZero()) return;
        cached.roadPressure[fromY][fromX] += deposit.fromDeposit();
        cached.roadPressure[toY][toX]     += deposit.toDeposit();
        cached.dirty = true;
    }


    // applyBotVotes and botVoteDecision moved to RoomProposalService (Phase 2.5
    // Phase 2 — bot-vote machinery). BotVote enum promoted to a top-level public
    // type in BotVote.java. findChangeType is now in RoomProposalService too.

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
            int[][] proposed = proposals.applyBotChange(baseTiles, anchor[0], anchor[1], targetType);
            try {
                proposals.validateProposalChange(baseTiles, proposed);
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
            boolean newHighway = targetType == 4 || oldHighway;
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

    // applyBotChange and extractAnchor moved to RoomProposalService.

    // evaluatePendingProposals and evaluateProposal moved to RoomProposalService
    // (Phase 2.5 Phase 3 — vote/evaluate decisions). The actual apply-to-state side
    // (`applyProposal` below) stays here and is passed through to the carved
    // service as a BiConsumer callback. Same pattern as the Phase 2 drift callback.

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
        int[] anchor = proposals.proposalAnchor(proposal);
        int type = proposals.extractDeltaTypeFromProposal(proposal);
        if (gpuEnabled && anchor != null && type >= 0 && gpuPatchMinArea <= 1) {
            ProposalApplyResult gpuResult = tryApplyProposalPatchWithGpu(room, proposal, baseTiles, normalizedHighways, anchor, type);
            if (gpuResult != null) {
                return gpuResult;
            }
        }
        int[][] tiles = proposals.applyDeltaToTiles(baseTiles, proposal);
        boolean[][] highways = copyHighways(normalizedHighways, tiles.length, tiles[0].length);
        if (anchor != null && type >= 0) {
            int x = anchor[0];
            int y = anchor[1];
            if (type == 4) {
                highways[y][x] = true;
                tiles[y][x] = 0;
            }
        }
        return summarizeProposalApply(baseTiles, normalizedHighways, tiles, highways);
    }

    // proposalAnchor moved to RoomProposalService.

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
            highways[anchor[1]][anchor[0]] = highways[anchor[1]][anchor[0]] || patchHighways[0][0];
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

    // applyNoParticipation, capQuorum, and canVoteNow moved to RoomProposalService
    // (Phase 2.5 Phase 3). canVoteNow now takes the tiles array as input — the
    // sole caller below in buildProposals fetches once before the loop.

    // readNeighborIds, validateProposalChange, validateProposalDelta, extractDeltaType,
    // applyDeltaToTiles, extractDeltaTypeFromProposal all moved to RoomProposalService.

    private List<String> findNeighbors(Room room, RoomParticipant proposer, int[][] proposedTiles) {
        int[][] tiles = fromJsonTiles(stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing")).getTilesJson());
        int cx = proposer != null && proposer.getX() != null ? proposer.getX() : 0;
        int cy = proposer != null && proposer.getY() != null ? proposer.getY() : 0;
        int[] change = proposals.findChangeType(tiles, proposedTiles);
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
        int[] view = occupancy.computeViewBounds(cx, cy, tiles);
        int viewX = view[0];
        int viewY = view[1];
        int viewW = view[2];
        int viewH = view[3];
        List<String> neighbors = new ArrayList<>();
        for (RoomParticipant participant : participants) {
            if (participant.getX() != null && participant.getY() != null
                && occupancy.inView(viewX, viewY, viewW, viewH, participant.getX(), participant.getY())) {
                neighbors.add("P:" + participant.getId());
            }
        }
        for (RoomBot bot : bots) {
            if (bot.getX() != null && bot.getY() != null
                && occupancy.inView(viewX, viewY, viewW, viewH, bot.getX(), bot.getY())) {
                neighbors.add("B:" + bot.getId());
            }
        }
        return neighbors;
    }

    // computeViewBounds, inView, withinRadius, buildOccupancySnapshot, readOccupancyJson and
    // the canOccupy rule check moved to OccupancyService (Phase 2.5). The cache-coupled
    // getCachedOccupancy and refreshOccupancySnapshot stay here because they touch
    // roomStateCache (which also holds tiles/highways/road pressure/hash/dirty bookkeeping
    // unrelated to occupancy). Same scope-reduction pattern as RoomStateOrchestrator.
    private boolean canOccupy(Room room, int x, int y, String ignoreId) {
        return occupancy.canOccupy(x, y, ignoreId, getCachedOccupancy(room), densityAreaSize, maxOccupants);
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
        List<OccupantSnapshot> snapshot = occupancy.readOccupancyJson(state.getOccupancyJson());
        if (cached != null) {
            cached.occupancy = snapshot;
            cached.occupancyUpdatedAt = state.getOccupancyUpdatedAt();
        }
        return snapshot;
    }

    private boolean trySimulateBotsWithGpu(Room room, RoomState state) {
        if (!gpuEnabled || !gpuBotsEnabled || gpuEngineAdapter == null) {
            return false;
        }
        try {
            CachedRoomState cached = getCachedRoomState(room, state);
            int[][] tiles = cached.tiles;
            if (tiles.length == 0 || tiles[0].length == 0) {
                return false;
            }
            boolean[][] highways = cached.highways;
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

            GpuWorldCache cache = gpuSimulation.getOrCreateCache(room.getId());
            boolean sendFullWorld = !gpuBotsCacheWorld || cache.tiles == null || cache.highways == null
                || cache.tiles.length != height || cache.tiles.length == 0 || cache.tiles[0].length != width;
            List<TileEdit> tileDiffs = Collections.emptyList();
            if (gpuBotsCacheWorld && !sendFullWorld && gpuBotsDiffMaxEdits > 0) {
                tileDiffs = gpuSimulation.collectGpuTileDiffs(cache.tiles, cache.highways, tiles, highways, gpuBotsDiffMaxEdits);
                if (tileDiffs == null) {
                    sendFullWorld = true;
                    tileDiffs = Collections.emptyList();
                }
            }

            SimulateBotsRequest.Builder builder = SimulateBotsRequest.newBuilder()
                .setRequestId(UUID.randomUUID().toString())
                .setParamsJson(gpuSimulation.buildGpuBotParams(room.getId(), width, height, neighborRadius, minNeighbors, densityAreaSize, maxOccupants))
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
                gpuSimulation.applyTileEditsToCache(cache.tiles, cache.highways, tileDiffs);
            }

            java.util.Map<String, Double> tileNudges = roomTileNudges.get(room.getId());
            for (RoomBot bot : bots) {
                if (bot.getId() == null || bot.getX() == null || bot.getY() == null) {
                    continue;
                }
                BotMotionState motion = readBotMotionState(bot);
                BotState.Builder botState = BotState.newBuilder()
                    .setId(bot.getId().toString())
                    .setX(bot.getX())
                    .setY(bot.getY())
                    .setState(ByteString.copyFrom(gpuSimulation.encodeBotMotionStateBytes(motion)));
                Map<String, Double> profile = readBotProfile(bot);
                gpuSimulation.applyGpuBotProfile(botState, profile, tileNudges);
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
                int oldX = bot.getX() == null ? botState.getX() : bot.getX();
                int oldY = bot.getY() == null ? botState.getY() : bot.getY();
                BotMotionState motion = gpuSimulation.decodeBotMotionStateBytes(
                    botState.getState().toByteArray(), readBotMotionState(bot), bot.getX(), bot.getY());
                if (bot.getX() == null || bot.getY() == null
                    || bot.getX() != botState.getX() || bot.getY() != botState.getY()) {
                    bot.setX(botState.getX());
                    bot.setY(botState.getY());
                    addRoadDemandForMove(cached, tiles, highways, readBotProfile(bot), oldX, oldY, botState.getX(), botState.getY(), motion.plannedSteps == null ? 0 : motion.plannedSteps.size() + 1, false);
                }
                motion.currentX = bot.getX();
                motion.currentY = bot.getY();
                writeBotMotionState(bot, motion);
                moved.add(bot);
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

    // buildGpuBotParams, applyGpuBotProfile (+ nudgedWeight helper), collectGpuTileDiffs,
    // and applyTileEditsToCache moved to BotSimulationGpuService (Phase 2.5). The
    // orchestrator trySimulateBotsWithGpu stays here because of its cross-cutting
    // deps on getCachedRoomState, addRoadDemandForMove, the bot codec, and
    // applyGpuEditsToCachedState. Same scope-reduction pattern as RoomStateOrchestrator.

    private int[][] copyTiles(int[][] tiles) {
        int[][] copy = new int[tiles.length][];
        for (int y = 0; y < tiles.length; y++) {
            copy[y] = Arrays.copyOf(tiles[y], tiles[y].length);
        }
        return copy;
    }

    private double[][] copyRoadPressure(double[][] pressure) {
        double[][] copy = new double[pressure.length][];
        for (int y = 0; y < pressure.length; y++) {
            copy[y] = Arrays.copyOf(pressure[y], pressure[y].length);
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
            boolean oldHighway = highways.length > 0 && highways[y][x];
            boolean newHighway = oldHighway;
            if (newTile == 4) {
                newHighway = true;
                newTile = 0;
            }
            int oldTile = tiles[y][x];
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

    // Phase 2.5 carve: BotMotionState promoted to a top-level package-private
    // class. See BotMotionState.java in this package.

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
