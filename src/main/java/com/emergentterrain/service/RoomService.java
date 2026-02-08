package com.emergentterrain.service;

import com.emergentterrain.domain.*;
import com.emergentterrain.repo.*;
import com.emergentterrain.web.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

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

    public RoomService(RoomRepository roomRepository,
                       RoomParticipantRepository participantRepository,
                       RoomStateRepository stateRepository,
                       RoomProposalRepository proposalRepository,
                       RoomProposalVoteRepository voteRepository,
                       RoomBotRepository botRepository,
                       ProfileRepository profileRepository,
                       PayloadService payloadService,
                       WorldService worldService,
                       ObjectMapper objectMapper) {
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
        host = participantRepository.save(host);

        room.setTurnOwnerParticipantId(host.getId());
        room.setTurnExpiresAt(Instant.now().plusSeconds(60));
        room = roomRepository.save(room);

        RoomState state = new RoomState();
        state.setRoom(room);
        state.setTilesJson(toJson(world.getTiles()));
        state.setSharedTokensJson(toJson(new SharedTokens(0, 0, 0)));
        state.setSharedQuestsJson(toJson(defaultSharedQuests()));
        state.setMemoryMarkersJson(toJson(new ArrayList<>()));
        state.setStateHash(hashTiles(world.getTiles()));
        state = stateRepository.save(state);

        ensureBots(room);

        RoomCreateResponse response = new RoomCreateResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        response.setParticipantId(host.getId().toString());
        response.setTiles(world.getTiles());
        response.setSharedTokens(new SharedTokens(0, 0, 0));
        response.setSharedQuests(defaultSharedQuests());
        response.setMemoryMarkers(new ArrayList<>());
        response.setParticipants(buildParticipants(room));
        response.setProposals(buildProposals(room));
        response.setTurnOwnerParticipantId(room.getTurnOwnerParticipantId().toString());
        response.setTurnExpiresAt(room.getTurnExpiresAt());
        response.setLockUntil(room.getLockUntil());
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
                return participantRepository.save(created);
            });
        if (room.getTurnOwnerParticipantId() == null) {
            room.setTurnOwnerParticipantId(participant.getId());
            room.setTurnExpiresAt(Instant.now().plusSeconds(60));
            roomRepository.save(room);
        }
        RoomJoinResponse response = new RoomJoinResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        response.setParticipantId(participant.getId().toString());
        return response;
    }

    public RoomStateResponse getRoomState(Payload payload, UUID roomId) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        ensureParticipant(room, profile);
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        return toRoomStateResponse(room, state);
    }

    public RoomStateResponse propose(Payload payload, UUID roomId, RoomProposalRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        if (!isTurnAllowed(room, participant)) {
            throw new IllegalStateException("Not your turn");
        }
        RoomProposal proposal = new RoomProposal();
        proposal.setRoom(room);
        proposal.setProposer(participant);
        proposal.setTilesJson(toJson(request.getTiles()));
        proposal.setDeltaJson(toJson(request.getDelta()));
        proposal.setStatus("PENDING");
        proposal.setApprovals(1);
        proposal.setRejections(0);
        proposal = proposalRepository.save(proposal);

        RoomProposalVote vote = new RoomProposalVote();
        vote.setProposal(proposal);
        vote.setParticipant(participant);
        vote.setApprove(true);
        voteRepository.save(vote);

        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        return toRoomStateResponse(room, state);
    }

    public RoomStateResponse vote(Payload payload, UUID roomId, RoomVoteRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        RoomParticipant participant = ensureParticipant(room, profile);
        UUID proposalId = UUID.fromString(request.getProposalId());
        RoomProposal proposal = proposalRepository.findById(proposalId)
            .orElseThrow(() -> new IllegalStateException("Proposal not found"));
        if (!proposal.getRoom().getId().equals(room.getId())) {
            throw new IllegalStateException("Proposal mismatch");
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
        int required = requiredVotes(room);
        if (proposal.getApprovals() >= required) {
            proposal.setStatus("APPROVED");
        } else if (proposal.getRejections() >= required) {
            proposal.setStatus("REJECTED");
        }
        proposalRepository.save(proposal);

        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        return toRoomStateResponse(room, state);
    }

    public RoomStateResponse apply(Payload payload, UUID roomId, RoomApplyRequest request) {
        Profile profile = profileFromPayload(payload);
        Room room = roomRepository.findById(roomId)
            .orElseThrow(() -> new IllegalStateException("Room not found"));
        ensureParticipant(room, profile);
        UUID proposalId = UUID.fromString(request.getProposalId());
        RoomProposal proposal = proposalRepository.findById(proposalId)
            .orElseThrow(() -> new IllegalStateException("Proposal not found"));
        if (!"APPROVED".equals(proposal.getStatus())) {
            throw new IllegalStateException("Proposal not approved");
        }
        RoomState state = stateRepository.findByRoom(room)
            .orElseThrow(() -> new IllegalStateException("Room state missing"));
        state.setPreviousStateHash(state.getStateHash());
        int[][] tiles = fromJsonTiles(proposal.getTilesJson());
        state.setTilesJson(toJson(tiles));
        state.setStateHash(hashTiles(tiles));
        state.setUpdatedAt(Instant.now());
        updateSharedTokens(state, 2, 1, 1);
        updateSharedQuests(state, tiles);
        stateRepository.save(state);

        proposal.setStatus("APPLIED");
        proposalRepository.save(proposal);
        advanceTurn(room);
        room.setLockUntil(Instant.now().plusSeconds(2));
        roomRepository.save(room);

        return toRoomStateResponse(room, state);
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
        return participantRepository.findByRoomAndProfile(room, profile)
            .orElseThrow(() -> new IllegalStateException("Not a participant"));
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

    private void ensureBots(Room room) {
        List<String> bots = List.of("OBSERVER", "COPILOT", "TENSION", "ECHO", "QUEST");
        for (String type : bots) {
            botRepository.findByRoomAndType(room, type).orElseGet(() -> {
                RoomBot bot = new RoomBot();
                bot.setRoom(room);
                bot.setType(type);
                return botRepository.save(bot);
            });
        }
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
            infos.add(info);
        }
        return infos;
    }

    private RoomStateResponse toRoomStateResponse(Room room, RoomState state) {
        RoomStateResponse response = new RoomStateResponse();
        response.setRoomId(room.getId().toString());
        response.setRoomName(room.getName());
        response.setTiles(fromJsonTiles(state.getTilesJson()));
        response.setSharedTokens(readSharedTokens(state));
        response.setSharedQuests(readSharedQuests(state));
        response.setMemoryMarkers(readMemoryMarkers(state));
        response.setParticipants(buildParticipants(room));
        response.setProposals(buildProposals(room));
        response.setTurnOwnerParticipantId(room.getTurnOwnerParticipantId() == null ? null : room.getTurnOwnerParticipantId().toString());
        response.setTurnExpiresAt(room.getTurnExpiresAt());
        response.setLockUntil(room.getLockUntil());
        response.setStateHash(state.getStateHash());
        response.setPreviousStateHash(state.getPreviousStateHash());
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
                return "Quest Bot: Strengthen coherence by linking two clearings.";
            default:
                return "The room hums softly. Nothing to add yet.";
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
