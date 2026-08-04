package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Imports game-owned bot selection snapshots into research-owned durable storage. */
@Service
public class ResearchBotReplicaService {

    private final ResearchBotReplicaRepository replicas;

    public ResearchBotReplicaService(ResearchBotReplicaRepository replicas) {
        this.replicas = replicas;
    }

    @Transactional
    public void apply(UUID roomId, ResearchGameContext context) {
        Map<UUID, ResearchBotReplica> existing = new HashMap<>();
        for (ResearchBotReplica replica : replicas.findByRoomId(roomId)) {
            existing.put(replica.getBotId(), replica);
        }
        Set<UUID> selected = new HashSet<>();
        for (ResearchBotSnapshot snapshot : context.activeBots()) {
            if (snapshot.botId() == null) {
                continue;
            }
            selected.add(snapshot.botId());
            ResearchBotReplica replica = existing.get(snapshot.botId());
            if (replica == null) {
                replicas.save(new ResearchBotReplica(snapshot.botId(), roomId, snapshot.type(),
                        snapshot.lifecycleJson(), context.gameTick(), true));
            } else if (context.gameTick() >= replica.getGameTick()) {
                replica.refresh(snapshot.type(), snapshot.lifecycleJson(), context.gameTick(), true);
                replicas.save(replica);
            }
        }
        for (ResearchBotReplica replica : existing.values()) {
            if (!selected.contains(replica.getBotId()) && context.gameTick() >= replica.getGameTick()) {
                replica.refresh(replica.getBotType(), replica.getLifecycleJson(), context.gameTick(), false);
                replicas.save(replica);
            }
        }
    }
}
