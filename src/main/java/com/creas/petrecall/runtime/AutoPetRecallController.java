package com.creas.petrecall.runtime;

import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class AutoPetRecallController {
    private static final long AUTO_RETRY_THROTTLE_TICKS = 10L;
    private static final long AUTO_RECALL_COOLDOWN_TICKS = 40L;
    private static final long AUTO_PET_BACKOFF_TICKS = 40L;
    private static final int MAX_UNLOADED_PETS_PER_AUTO_RUN = 8;
    private static final double VANILLA_FOLLOW_TELEPORT_DISTANCE_SQ = 144.0D;

    private final PetTracker tracker;
    private final PetRecallService recallService;
    private final Map<UUID, PlayerAutoState> playerStates = new HashMap<>();
    private final Map<UUID, Long> petBackoffUntilTick = new HashMap<>();

    public AutoPetRecallController(PetTracker tracker, PetRecallService recallService) {
        this.tracker = tracker;
        this.recallService = recallService;
    }

    public void onServerTick(MinecraftServer server) {
        if (server.getOverworld() == null) {
            return;
        }

        long now = server.getOverworld().getTime();
        Set<UUID> onlinePlayers = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            onlinePlayers.add(player.getUuid());
            this.tickPlayer(server, player, now);
        }

        this.playerStates.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
    }

    public void clearRuntime() {
        this.playerStates.clear();
        this.petBackoffUntilTick.clear();
    }

    private void tickPlayer(MinecraftServer server, ServerPlayerEntity player, long now) {
        if (player.isRemoved() || player.isSpectator()) {
            return;
        }

        UUID playerUuid = player.getUuid();
        PlayerAutoState state = this.playerStates.computeIfAbsent(playerUuid, ignored -> new PlayerAutoState());

        long currentChunk = player.getChunkPos().toLong();
        String currentDimension = player.getEntityWorld().getRegistryKey().getValue().toString();
        boolean onGround = player.isOnGround();

        boolean firstSeen = !state.initialized;
        boolean chunkChanged = state.initialized && state.lastChunkPosLong != currentChunk;
        boolean dimensionChanged = state.initialized && !state.lastDimensionId.equals(currentDimension);
        boolean landed = state.initialized && !state.lastOnGround && onGround;
        boolean continueBatch = state.continueNextBatchTick >= 0L && now >= state.continueNextBatchTick;

        state.initialized = true;
        state.lastChunkPosLong = currentChunk;
        state.lastDimensionId = currentDimension;
        state.lastOnGround = onGround;

        boolean trigger = firstSeen || chunkChanged || dimensionChanged || landed || continueBatch;
        if (!trigger) {
            return;
        }

        if (!onGround) {
            if (continueBatch) {
                state.continueNextBatchTick = -1L;
            }
            return;
        }

        if (now < state.nextRecallTick) {
            return;
        }

        if (this.recallService.isRecallActive(playerUuid)) {
            return;
        }

        AutoRecallBatch batch = this.collectAutoRecallCandidates(server, player, now);
        if (batch.records().isEmpty()) {
            state.continueNextBatchTick = -1L;
            return;
        }

        UUID playerUuidFinal = playerUuid;
        boolean started = this.recallService.recallUnloadedForPlayerAsyncSilent(player, batch.records(), summary -> {
            if (server.getOverworld() == null) {
                return;
            }

            PlayerAutoState callbackState = this.playerStates.get(playerUuidFinal);
            if (callbackState == null) {
                return;
            }

            long callbackNow = server.getOverworld().getTime();
            if (summary.recalled > 0 && batch.hasMoreCandidates()) {
                callbackState.continueNextBatchTick = callbackNow + 1L;
                callbackState.nextRecallTick = callbackNow + 1L;
                return;
            }

            callbackState.continueNextBatchTick = -1L;
            if (summary.recalled > 0) {
                callbackState.nextRecallTick = callbackNow + AUTO_RECALL_COOLDOWN_TICKS;
            }
        });
        if (started) {
            this.applyBackoff(batch.records(), now + AUTO_PET_BACKOFF_TICKS);
            if (!batch.hasMoreCandidates()) {
                state.nextRecallTick = now + AUTO_RECALL_COOLDOWN_TICKS;
            }
        } else {
            state.continueNextBatchTick = -1L;
            state.nextRecallTick = now + AUTO_RETRY_THROTTLE_TICKS;
        }
    }

    private AutoRecallBatch collectAutoRecallCandidates(MinecraftServer server, ServerPlayerEntity player, long now) {
        Collection<PetRecord> records = this.tracker.getOwnerRecords(server, player.getUuid());
        if (records.isEmpty()) {
            return AutoRecallBatch.empty();
        }

        String playerDimensionId = player.getEntityWorld().getRegistryKey().getValue().toString();
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        var candidates = new java.util.ArrayList<PetRecord>(Math.min(records.size(), MAX_UNLOADED_PETS_PER_AUTO_RUN + 4));

        for (PetRecord record : records) {
            if (record.sitting()) {
                continue;
            }

            // Keep auto-recall vanilla-like: only same-dimension companions auto-follow.
            if (!playerDimensionId.equals(record.dimensionId())) {
                continue;
            }

            if (this.tracker.getLoadedPet(record.petUuid()) != null) {
                continue;
            }

            Long backoffUntil = this.petBackoffUntilTick.get(record.petUuid());
            if (backoffUntil != null) {
                if (now < backoffUntil) {
                    continue;
                }
                this.petBackoffUntilTick.remove(record.petUuid());
            }

            double dx = record.x() - playerX;
            double dy = record.y() - playerY;
            double dz = record.z() - playerZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq >= VANILLA_FOLLOW_TELEPORT_DISTANCE_SQ) {
                candidates.add(record);
            }
        }

        if (candidates.isEmpty()) {
            return AutoRecallBatch.empty();
        }

        // Improve locality for unloaded recalls and prefer more distant pets first within the same chunk.
        candidates.sort(Comparator
                .comparing(PetRecord::dimensionId)
                .thenComparingLong(PetRecord::chunkPosLong)
                .thenComparingDouble(record -> -distanceSqTo(record, playerX, playerY, playerZ))
                .thenComparing(PetRecord::petUuid));

        boolean hasMoreCandidates = candidates.size() > MAX_UNLOADED_PETS_PER_AUTO_RUN;
        if (hasMoreCandidates) {
            candidates.subList(MAX_UNLOADED_PETS_PER_AUTO_RUN, candidates.size()).clear();
        }

        return new AutoRecallBatch(candidates, hasMoreCandidates);
    }

    private void applyBackoff(java.util.List<PetRecord> candidates, long backoffUntilTick) {
        for (PetRecord candidate : candidates) {
            this.petBackoffUntilTick.put(candidate.petUuid(), backoffUntilTick);
        }
    }

    private static double distanceSqTo(PetRecord record, double x, double y, double z) {
        double dx = record.x() - x;
        double dy = record.y() - y;
        double dz = record.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class PlayerAutoState {
        boolean initialized;
        long lastChunkPosLong;
        String lastDimensionId = "";
        boolean lastOnGround;
        long nextRecallTick;
        long continueNextBatchTick = -1L;
    }

    private record AutoRecallBatch(java.util.List<PetRecord> records, boolean hasMoreCandidates) {
        private static AutoRecallBatch empty() {
            return new AutoRecallBatch(java.util.List.of(), false);
        }
    }
}
