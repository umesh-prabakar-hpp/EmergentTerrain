package com.emergentterrain.service;

/**
 * Phase 2.5 carve: lightweight occupancy entry persisted into
 * {@code RoomState.occupancyJson} so the cached snapshot can survive a
 * restart. Promoted from a private nested class on {@link RoomService} so
 * other extracted services (notably the future {@code BotMovementService}
 * and the existing {@code CachedRoomState}) can reference it without
 * depending on a {@code RoomService} inner class.
 *
 * Jackson reads/writes this through the no-arg constructor + setters; both
 * are intentionally retained.
 */
class OccupantSnapshot {

    private String id;
    private int    x;
    private int    y;

    OccupantSnapshot() {}

    OccupantSnapshot(String id, int x, int y) {
        this.id = id;
        this.x  = x;
        this.y  = y;
    }

    public String getId()           { return id; }
    public void   setId(String id)  { this.id = id; }
    public int    getX()            { return x; }
    public void   setX(int x)       { this.x = x; }
    public int    getY()            { return y; }
    public void   setY(int y)       { this.y = y; }
}
