package com.creas.petrecall.gametest;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/** Faults belong to one fixture chunk, so concurrent tests cannot affect each other. */
public final class RecallFaults {
    private record Key(String dimension, long chunk) { }
    private static final Map<Key, Boolean> BLOCKED = new HashMap<>();
    private static final Map<Key, Boolean> ACQUIRE_FAILURES = new HashMap<>();

    private RecallFaults() { }

    private static Key key(ServerWorld world, ChunkPos chunk) {
        return new Key(world.getRegistryKey().getValue().toString(), chunk.toLong());
    }

    public static void blockReadiness(ServerWorld world, ChunkPos chunk) {
        BLOCKED.put(key(world, chunk), true);
    }

    public static boolean isBlocked(ServerWorld world, ChunkPos chunk) {
        return BLOCKED.containsKey(key(world, chunk));
    }

    public static void failNextAcquisition(ServerWorld world, ChunkPos chunk) {
        ACQUIRE_FAILURES.put(key(world, chunk), true);
    }

    public static boolean consumeAcquisitionFailure(ServerWorld world, ChunkPos chunk) {
        return ACQUIRE_FAILURES.remove(key(world, chunk)) != null;
    }

    public static void clear(ServerWorld world, ChunkPos chunk) {
        BLOCKED.remove(key(world, chunk));
        ACQUIRE_FAILURES.remove(key(world, chunk));
    }
}
