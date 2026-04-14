package com.creas.petrecall.recall;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class PetRecallQuarantineTracker {
    static final long FIRST_MISS_BACKOFF_TICKS = 20L * 10L;
    static final long SECOND_MISS_BACKOFF_TICKS = 20L * 30L;
    static final int REMOVE_AFTER_MISSES = 3;

    private final Map<UUID, PetRuntimeState> states = new HashMap<>();

    public synchronized void clear(UUID petUuid) {
        this.states.remove(petUuid);
    }

    public synchronized boolean isQuarantined(UUID petUuid, long now) {
        PetRuntimeState state = this.states.get(petUuid);
        return state != null && now < state.quarantineUntilTick;
    }

    public synchronized MissResult recordMiss(UUID petUuid, long now) {
        PetRuntimeState state = this.states.computeIfAbsent(petUuid, ignored -> new PetRuntimeState());
        state.consecutiveMisses++;

        if (state.consecutiveMisses >= REMOVE_AFTER_MISSES) {
            this.states.remove(petUuid);
            return new MissResult(state.consecutiveMisses, now, true);
        }

        long backoff = state.consecutiveMisses == 1 ? FIRST_MISS_BACKOFF_TICKS : SECOND_MISS_BACKOFF_TICKS;
        state.quarantineUntilTick = now + backoff;
        return new MissResult(state.consecutiveMisses, state.quarantineUntilTick, false);
    }

    public synchronized int getTrackedStateCount() {
        return this.states.size();
    }

    public synchronized int getQuarantinedCount(long now) {
        int quarantined = 0;
        for (PetRuntimeState state : this.states.values()) {
            if (now < state.quarantineUntilTick) {
                quarantined++;
            }
        }
        return quarantined;
    }

    record MissResult(int missCount, long quarantineUntilTick, boolean shouldRemoveRecord) {
    }

    private static final class PetRuntimeState {
        int consecutiveMisses;
        long quarantineUntilTick;
    }
}
