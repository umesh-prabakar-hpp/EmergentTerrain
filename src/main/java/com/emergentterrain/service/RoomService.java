package com.emergentterrain.service;

import com.emergentterrain.domain.*;
import com.emergentterrain.repo.*;
import com.emergentterrain.web.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class RoomService {
    private final RoomRepository roomRepository;
    private final RoomParticipantRepository participantRepository;
    private final RoomStateRepository stateRepository;
    private final RoomProposalRepository proposalRepository;
    private final RoomProposalVoteRepository voteRepository;
    private final RoomBotRepository botRepository;
    private final ProfileRepository profileRepository;
    private final PayloadService payloadService;
    private final WorldService worldService;
    private final ObjectMapper objectMapper;
    private final int neighborRadius;
    private final int voteWindowSeconds;
    private final int minNeighbors;
    private final int densityAreaSize;
    private final int maxOccupants;
    private final double botPopulationFraction;
    private final int noParticipationLockSeconds;

    public RoomService(RoomRepository roomRepository,
                       RoomParticipantRepository participantRepository,
                       RoomStateRepository stateRepository,
                       RoomProposalRepository proposalRepository,
                       RoomProposalVoteRepository voteRepository,
                       RoomBotRepository botRepository,
                       ProfileRepository profileRepository,
                       PayloadService payloadService,
                       WorldService worldService,
                       ObjectMapper objectMapper,
                       @Value("${app.consensus.neighborRadius:8}") int neighborRadius,
                       @Value("${app.consensus.voteWindowSeconds:15}") int voteWindowSeconds,
                       @Value("${app.consensus.minNeighbors:3}") int minNeighbors,
                       @Value("${app.density.areaSize:20}") int densityAreaSize,
                       @Value("${app.density.maxOccupants:8}") int maxOccupants,
                       @Value("${app.bots.populationFraction:0.25}") double botPopulationFraction,
                       @Value("${app.consensus.noParticipationLockSeconds:300}") int noParticipationLockSeconds) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.stateRepository = stateRepository;
        this.proposalRepository = proposalRepository;
        this.voteRepository = voteRepository;
        this.botRepository = botRepository;
        this.profileRepository = profileRepository;
        this.payloadService = payloadService;
        this.worldService = worldService;
        this.objectMapper = objectMapper;
        this.neighborRadius = neighborRadius;
        this.voteWindowSeconds = voteWindowSeconds;
        this.minNeighbors = minNeighbors;
        this.densityAreaSize = densityAreaSize;
        this.maxOccupants = maxOccupants;
        this.botPopulationFraction = botPopulationFraction;
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
        response.setProposals(buildProposals(room));
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
                    assignSpawnPosition(created, fromJsonTiles(state.getTilesJson()));
                }
                return participantRepository.save(created);
            });
        if (participant.getX() == null || participant.getY() == null) {
            RoomState state = stateRepository.findByRoom(room).orElse(null);
            if (state != null) {
                assignSpawnPosition(participant, fromJsonTiles(state.getTilesJson()));
                participantRepository.save(participant);
            }
        }
        RoomState joinState = stateRepository.findByRoom(room).orElse(null);
        if (joinState != null) {
            ensureNearbyBots(room, fromJsonTiles(joinState.getTilesJson()), participant.getX(), participant.getY());
        }
        RoomJoinResponse response = new RoomJoinResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        response.setParticipantId(participant.getId().toString());
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
        cleanupOldProposals(room);
        ensureBots(room, fromJsonTiles(state.getTilesJson()));
        maybeCreateBotProposal(room, state);
        tickBots(room, state);
        evaluatePendingProposals(room);
        return toRoomStateResponse(room, state);
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
        cleanupOldProposals(room);
        maybeCreateBotProposal(room, state);
        int[][] tiles = fromJsonTiles(state.getTilesJson());
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
        tickBots(room, state);
        evaluatePendingProposals(room);
        return toRoomStateResponse(room, state);
    }

    public RoomStateResponse propose(Payload payload, UUID roomId, RoomProposalRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        ensurePlayable(participant);
        RoomProposal proposal = new RoomProposal();
        proposal.setRoom(room);
        proposal.setProposer(participant);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        int[][] baseTiles = fromJsonTiles(state.getTilesJson());
        validateProposalChange(baseTiles, request.getTiles());
        proposal.setTilesJson(toJson(request.getTiles()));
        proposal.setDeltaJson(toJson(request.getDelta()));
        int[] anchor = extractAnchor(request.getDelta());
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

        applyBotVotes(room, proposal, baseTiles, request.getTiles());
        evaluateProposal(room, proposal);
        return toRoomStateResponse(room, state);
    }

    public RoomStateResponse vote(Payload payload, UUID roomId, RoomVoteRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        ensurePlayable(participant);
        UUID proposalId = UUID.fromString(request.getProposalId());
        RoomProposal proposal = proposalRepository.findById(proposalId)
            .orElseThrow(() -> new IllegalStateException("Proposal not found"));
        if (!proposal.getRoom().getId().equals(room.getId())) {
            throw new IllegalStateException("Proposal mismatch");
        }
        if (!isNeighbor(proposal, participant)) {
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
        return toRoomStateResponse(room, state);
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
        return toRoomStateResponse(room, state);
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

    public RoomResonanceResponse resonance(Payload payload, UUID roomId) {
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        long seed = payloadService.seedFromPayload(payload);
        GameStateResponse world = worldService.generateWorld(payload, seed);
        int[][] baseTiles = world.getTiles();
        int[][] roomTiles = fromJsonTiles(state.getTilesJson());
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
                assignSpawnPosition(participant, fromJsonTiles(state.getTilesJson()));
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
        targetBots = Math.min(targetBots, 200);

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
        for (RoomBot bot : botRepository.findByRoom(room)) {
            RoomBotInfo info = new RoomBotInfo();
            info.setId(bot.getId().toString());
            info.setType(bot.getType());
            info.setDisplayName(botDisplayName(bot));
            info.setX(bot.getX() == null ? -1 : bot.getX());
            info.setY(bot.getY() == null ? -1 : bot.getY());
            info.setProfile(readBotProfile(bot));
            infos.add(info);
        }
        return infos;
    }

    private List<RoomProposalInfo> buildProposals(Room room) {
        List<RoomProposalInfo> infos = new ArrayList<>();
        for (RoomProposal proposal : proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room)) {
            RoomProposalInfo info = new RoomProposalInfo();
            info.setId(proposal.getId().toString());
            info.setProposerName(proposal.getProposer().getDisplayName());
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
            infos.add(info);
        }
        return infos;
    }

    @Transactional
    private void cleanupOldProposals(Room room) {
        Instant cutoff = Instant.now().minusSeconds(5);
        voteRepository.deleteByProposalRoomAndProposalCreatedAtBefore(room, cutoff);
        proposalRepository.deleteByRoomAndCreatedAtBefore(room, cutoff);
    }

    private RoomStateResponse toRoomStateResponse(Room room, RoomState state) {
        RoomStateResponse response = new RoomStateResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        response.setTiles(fromJsonTiles(state.getTilesJson()));
        response.setHighways(fromJsonHighways(state.getHighwaysJson()));
        response.setSharedTokens(readSharedTokens(state));
        response.setSharedQuests(readSharedQuests(state));
        response.setMemoryMarkers(readMemoryMarkers(state));
        response.setParticipants(buildParticipants(room));
        response.setBots(buildBots(room));
        response.setProposals(buildProposals(room));
        response.setTurnOwnerParticipantId(room.getTurnOwnerParticipantId() == null ? null : room.getTurnOwnerParticipantId().toString());
        response.setTurnExpiresAt(room.getTurnExpiresAt());
        response.setLockUntil(room.getLockUntil());
        response.setStateHash(state.getStateHash());
        response.setPreviousStateHash(state.getPreviousStateHash());
        response.setNeighborRadius(neighborRadius);
        response.setMinNeighbors(minNeighbors);
        response.setDensityAreaSize(densityAreaSize);
        response.setMaxOccupants(maxOccupants);
        return response;
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
        if (state.getSharedTokensJson() == null || state.getSharedTokensJson().isBlank()) {
            return new SharedTokens(0, 0, 0);
        }
        try {
            return objectMapper.readValue(state.getSharedTokensJson(), SharedTokens.class);
        } catch (JsonProcessingException e) {
            return new SharedTokens(0, 0, 0);
        }
    }

    private void updateSharedTokens(RoomState state, int insight, int stability, int energy) {
        SharedTokens tokens = readSharedTokens(state);
        tokens.setInsight(tokens.getInsight() + insight);
        tokens.setStability(tokens.getStability() + stability);
        tokens.setEnergy(tokens.getEnergy() + energy);
        state.setSharedTokensJson(toJson(tokens));
    }

    private List<QuestItem> readSharedQuests(RoomState state) {
        if (state.getSharedQuestsJson() == null || state.getSharedQuestsJson().isBlank()) {
            return defaultSharedQuests();
        }
        try {
            return objectMapper.readValue(state.getSharedQuestsJson(), new TypeReference<List<QuestItem>>() {});
        } catch (JsonProcessingException e) {
            return defaultSharedQuests();
        }
    }

    private List<MemoryMarker> readMemoryMarkers(RoomState state) {
        if (state.getMemoryMarkersJson() == null || state.getMemoryMarkersJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(state.getMemoryMarkersJson(), new TypeReference<List<MemoryMarker>>() {});
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

    private Map<String, Double> randomBotProfile(int seed) {
        Random rand = new Random(seed);
        double forest = 0.2 + rand.nextDouble() * 0.6;
        double water = 0.2 + rand.nextDouble() * 0.6;
        double clear = 0.2 + rand.nextDouble() * 0.6;
        double industry = 0.2 + rand.nextDouble() * 0.6;
        double consensus = 0.3 + rand.nextDouble() * 0.6;
        double mobility = 0.3 + rand.nextDouble() * 0.6;
        Map<String, Double> profile = new HashMap<>();
        profile.put("forest", forest);
        profile.put("water", water);
        profile.put("clear", clear);
        profile.put("industry", industry);
        profile.put("consensus", consensus);
        profile.put("mobility", mobility);
        return profile;
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
        int[][] tiles = fromJsonTiles(state.getTilesJson());
        boolean[][] highways = fromJsonHighways(state.getHighwaysJson());
        for (RoomBot bot : botRepository.findByRoom(room)) {
            Map<String, Double> profile = readBotProfile(bot);
            if (bot.getX() == null || bot.getY() == null) {
                assignBotSpawn(bot, tiles);
                botRepository.save(bot);
                continue;
            }
            if (Math.random() > profile.getOrDefault("mobility", 0.5)) {
                continue;
            }
            int[] next = pickBotStep(bot.getX(), bot.getY(), tiles, highways, profile);
            if (next != null && canOccupy(room, next[0], next[1], bot.getId().toString())) {
                bot.setX(next[0]);
                bot.setY(next[1]);
                botRepository.save(bot);
            }
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
        List<RoomBot> bots = botRepository.findByRoom(room);
        for (RoomBot bot : bots) {
            String key = "B:" + bot.getId();
            if (!neighborIds.contains(key)) {
                continue;
            }
            boolean approve = botApprovesChange(bot, baseTiles, proposedTiles);
            if (approve) {
                proposal.setApprovals(proposal.getApprovals() + 1);
            } else {
                proposal.setRejections(proposal.getRejections() + 1);
            }
        }
        proposalRepository.save(proposal);
    }

    private boolean botApprovesChange(RoomBot bot, int[][] baseTiles, int[][] proposedTiles) {
        Map<String, Double> profile = readBotProfile(bot);
        int[] change = findChangeType(baseTiles, proposedTiles);
        if (change == null) {
            return false;
        }
        double preference = tilePreferenceScore(change[2], profile);
        return preference >= profile.getOrDefault("consensus", 0.5);
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
        Instant last = state.getLastBotProposalAt();
        if (last != null && last.isAfter(Instant.now().minusSeconds(60))) {
            return;
        }
        if (proposalRepository.findTop10ByRoomOrderByCreatedAtDesc(room).stream()
            .anyMatch(p -> "PENDING".equals(p.getStatus()))) {
            return;
        }
        List<RoomBot> bots = botRepository.findByRoom(room);
        if (bots.isEmpty()) {
            return;
        }
        int[][] baseTiles = fromJsonTiles(state.getTilesJson());
        RoomBot bot = bots.get(new Random().nextInt(bots.size()));
        if (bot.getX() == null || bot.getY() == null) {
            return;
        }
        int[] anchor = pickBotProposalAnchor(bot, baseTiles);
        if (anchor == null) {
            return;
        }
        int targetType = dominantTileType(readBotProfile(bot));
        int[][] proposed = applyBotChange(baseTiles, anchor[0], anchor[1], targetType);
        try {
            validateProposalChange(baseTiles, proposed);
        } catch (IllegalStateException e) {
            return;
        }
        RoomParticipant proposer = participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)
            .stream()
            .findFirst()
            .orElse(null);
        if (proposer == null) {
            return;
        }
        RoomProposal proposal = new RoomProposal();
        proposal.setRoom(room);
        proposal.setProposer(proposer);
        proposal.setTilesJson(toJson(proposed));
        Map<String, Object> delta = new HashMap<>();
        delta.put("x", anchor[0]);
        delta.put("y", anchor[1]);
        delta.put("type", targetType);
        proposal.setDeltaJson(toJson(delta));
        proposal.setAnchorX(anchor[0]);
        proposal.setAnchorY(anchor[1]);
        proposal.setBotGenerated(true);
        proposal.setBotLabel(botDisplayName(bot));
        proposal.setStatus("PENDING");
        List<String> neighbors = findNeighbors(room, proposer, proposed);
        if (neighbors.size() < minNeighbors) {
            return;
        }
        proposal.setNeighborIdsJson(toJson(neighbors));
        proposal.setRequiredVotes(neighbors.size() / 2 + 1);
        proposal.setExpiresAt(Instant.now().plusSeconds(voteWindowSeconds));
        proposal.setApprovals(0);
        proposal.setRejections(0);
        proposalRepository.save(proposal);
        evaluateProposal(room, proposal);
        state.setLastBotProposalAt(Instant.now());
        stateRepository.save(state);
    }

    private int[] pickBotProposalAnchor(RoomBot bot, int[][] tiles) {
        int h = tiles.length;
        int w = tiles[0].length;
        int cx = bot.getX();
        int cy = bot.getY();
        Random rand = new Random(Objects.hash(bot.getId(), Instant.now().getEpochSecond()));
        for (int i = 0; i < 120; i++) {
            int dx = rand.nextInt(5) - 2;
            int dy = rand.nextInt(5) - 2;
            int x = Math.max(0, Math.min(w - 1, cx + dx));
            int y = Math.max(0, Math.min(h - 1, cy + dy));
            return new int[] { x, y };
        }
        return null;
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
        int required = proposal.getRequiredVotes();
        if (proposal.getApprovals() >= required) {
            applyProposal(room, proposal);
            return;
        }
        if (proposal.getRejections() >= required) {
            proposal.setStatus("REJECTED");
            proposalRepository.save(proposal);
            return;
        }
        if (proposal.getExpiresAt() != null && proposal.getExpiresAt().isBefore(Instant.now())) {
            applyNoParticipation(room, proposal);
        }
    }

    private void applyProposal(Room room, RoomProposal proposal) {
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        state.setPreviousStateHash(state.getStateHash());
        int[][] baseTiles = fromJsonTiles(state.getTilesJson());
        int[][] tiles = fromJsonTiles(proposal.getTilesJson());
        boolean[][] highways = fromJsonHighways(state.getHighwaysJson());
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
            for (int y = 0; y < tiles.length; y++) {
                for (int x = 0; x < tiles[0].length; x++) {
                    if (baseTiles[y][x] != tiles[y][x] && idx < 1) {
                        points[idx][0] = x;
                        points[idx][1] = y;
                        idx++;
                    }
                }
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
        proposal.setStatus("APPLIED");
        proposalRepository.save(proposal);
        room.setLockUntil(Instant.now().plusSeconds(2));
        roomRepository.save(room);
    }

    private void applyNoParticipation(Room room, RoomProposal proposal) {
        List<String> neighborIds = readNeighborIds(proposal);
        Set<String> voted = new HashSet<>();
        for (RoomProposalVote vote : voteRepository.findByProposal(proposal)) {
            voted.add("P:" + vote.getParticipant().getId());
        }
        int[] missing = {0};
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
                    missing[0]++;
                    p.setMissedVotes(p.getMissedVotes() + 1);
                    if (p.getMissedVotes() >= 2) {
                        p.setLockedUntil(Instant.now().plusSeconds(noParticipationLockSeconds));
                        p.setMissedVotes(0);
                    }
                    participantRepository.save(p);
                });
            }
        }
        proposal.setRejections(proposal.getRejections() + missing[0]);
        int required = proposal.getRequiredVotes();
        if (proposal.getApprovals() >= required) {
            applyProposal(room, proposal);
            return;
        }
        if (proposal.getRejections() >= required) {
            proposal.setStatus("REJECTED");
        }
        proposalRepository.save(proposal);
    }

    private boolean isNeighbor(RoomProposal proposal, RoomParticipant participant) {
        return readNeighborIds(proposal).contains("P:" + participant.getId());
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

    private List<String> findNeighbors(Room room, RoomParticipant proposer, int[][] proposedTiles) {
        int[] change = findChangeType(fromJsonTiles(stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing")).getTilesJson()), proposedTiles);
        int cx = proposer.getX() == null ? 0 : proposer.getX();
        int cy = proposer.getY() == null ? 0 : proposer.getY();
        if (change != null) {
            cx = change[0];
            cy = change[1];
        }
        List<String> neighbors = new ArrayList<>();
        for (RoomParticipant participant : participantRepository.findByRoomAndActiveTrueOrderByJoinedAt(room)) {
            if (participant.getX() != null && participant.getY() != null
                && withinRadius(cx, cy, participant.getX(), participant.getY(), neighborRadius)) {
                neighbors.add("P:" + participant.getId());
            }
        }
        for (RoomBot bot : botRepository.findByRoom(room)) {
            if (bot.getX() != null && bot.getY() != null
                && withinRadius(cx, cy, bot.getX(), bot.getY(), neighborRadius)) {
                neighbors.add("B:" + bot.getId());
            }
        }
        return neighbors;
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
