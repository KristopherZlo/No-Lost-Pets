package com.creas.petrecall.gametest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Observes production batch sizes on the server thread without replacing recall logic. */
public final class RecallBatchProbe {
    private static final Map<UUID, List<Integer>> BATCHES = new HashMap<>();

    private RecallBatchProbe() { }

    public static void begin(UUID owner) { BATCHES.put(owner, new ArrayList<>()); }
    public static void record(UUID owner, int size) {
        List<Integer> batches = BATCHES.get(owner);
        if (batches != null) batches.add(size);
    }
    public static List<Integer> batches(UUID owner) {
        return List.copyOf(BATCHES.getOrDefault(owner, List.of()));
    }
    public static void clear(UUID owner) { BATCHES.remove(owner); }
}
