package com.creas.petrecall.gametest;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Faults belong to one fixture chunk, so concurrent tests cannot affect each other. */
public final class RecallFaults {
    private record Key(String dimension, long chunk) { }
    private static final Map<Key, Boolean> BLOCKED = new HashMap<>();
    private static final Map<Key, Boolean> ACQUIRE_FAILURES = new HashMap<>();

    private RecallFaults() { }

    private static Key key(ServerLevel world, ChunkPos chunk) {
        return new Key(world.dimension().identifier().toString(), chunk.pack());
    }

    public static void blockReadiness(ServerLevel world, ChunkPos chunk) {
        BLOCKED.put(key(world, chunk), true);
    }

    public static boolean isBlocked(ServerLevel world, ChunkPos chunk) {
        return BLOCKED.containsKey(key(world, chunk));
    }

    public static void failNextAcquisition(ServerLevel world, ChunkPos chunk) {
        ACQUIRE_FAILURES.put(key(world, chunk), true);
    }

    public static boolean consumeAcquisitionFailure(ServerLevel world, ChunkPos chunk) {
        return ACQUIRE_FAILURES.remove(key(world, chunk)) != null;
    }

    public static void clear(ServerLevel world, ChunkPos chunk) {
        BLOCKED.remove(key(world, chunk));
        ACQUIRE_FAILURES.remove(key(world, chunk));
    }
}
