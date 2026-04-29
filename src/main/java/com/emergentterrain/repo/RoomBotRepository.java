package com.emergentterrain.repo;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomBotRepository extends JpaRepository<RoomBot, UUID> {
    List<RoomBot> findByRoom(Room room);
    List<RoomBot> findByRoom_IdAndStatus(UUID roomId, RoomBot.BotStatus status);
    int countByRoom_IdAndStatus(UUID roomId, RoomBot.BotStatus status);
    Optional<RoomBot> findByRoomAndType(Room room, String type);
}
