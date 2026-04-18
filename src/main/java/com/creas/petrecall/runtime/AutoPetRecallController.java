package com.creas.petrecall.runtime;

import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.util.DebugTrace;
import com.creas.petrecall.util.VersionCompat;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

public final class AutoPetRecallController {
    private static final long AUTO_RETRY_THROTTLE_TICKS = 4L;
    private static final long AUTO_RECALL_COOLDOWN_TICKS = 10L;
    private static final long AUTO_PET_BACKOFF_TICKS = 10L;
    private static final long JOIN_REPAIR_DELAY_TICKS = 10L;
    private static final long JOIN_RECALL_DELAY_TICKS = 20L;
    private static final long JOIN_REPAIR_BOOT_WINDOW_TICKS = 600L;
    private static final int MAX_UNLOADED_PETS_PER_AUTO_RUN = 16;
    private static final double VANILLA_FOLLOW_TELEPORT_DISTANCE_SQ = 144.0D;
    private static final double LARGE_TELEPORT_DISTANCE_SQ = 144.0D;

    private final PetTracker tracker;
    private final PetRecallService recallService;
    private final Map<UUID, PlayerAutoState> playerStates = new HashMap<>();
    private final Map<UUID, Long> petBackoffUntilTick = new HashMap<>();
    private final Set<UUID> suppressedPlayers = new HashSet<>();
    private long sessionTick;

    public AutoPetRecallController(PetTracker tracker, PetRecallService recallService) {
        this.tracker = tracker;
        this.recallService = recallService;
    }

    public void onServerTick(MinecraftServer server) {
        if (server.getOverworld() == null) {
            return;
        }

        this.sessionTick++;
        long now = server.getOverworld().getTime();
        Set<UUID> onlinePlayers = new HashSet<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            onlinePlayers.add(player.getUuid());
            if (this.suppressedPlayers.contains(player.getUuid())) {
                this.playerStates.remove(player.getUuid());
                continue;
            }
            this.tickPlayer(server, player, now);
        }

        this.playerStates.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
    }

    public void scheduleImmediate(ServerPlayerEntity player) {
        MinecraftServer server = VersionCompat.getServer(player);
        if (server == null || server.getOverworld() == null) {
            return;
        }
        if (this.suppressedPlayers.contains(player.getUuid())) {
            return;
        }

        PlayerAutoState state = this.playerStates.computeIfAbsent(player.getUuid(), ignored -> new PlayerAutoState());
        this.scheduleCheck(state, server.getOverworld().getTime(), "immediate event for " + player.getName().getString());
    }

    public void scheduleAfterJoin(ServerPlayerEntity player) {
        MinecraftServer server = VersionCompat.getServer(player);
        if (server == null || server.getOverworld() == null) {
            return;
        }
        if (this.suppressedPlayers.contains(player.getUuid())) {
            return;
        }

        long now = server.getOverworld().getTime();
        PlayerAutoState state = this.playerStates.computeIfAbsent(player.getUuid(), ignored -> new PlayerAutoState());
        state.joinRepairPending = true;
        state.joinRepairTick = computeJoinRepairTick(now);
        this.scheduleCheck(state, computeJoinRecallTick(now), "join warmup for " + player.getName().getString());
        DebugTrace.log("auto-recall", "Scheduled join warmup for %s repairTick=%d recallTick=%d sessionTick=%d",
                DebugTrace.describePlayer(player), state.joinRepairTick, computeJoinRecallTick(now), this.sessionTick);
    }

    public void clearRuntime() {
        this.playerStates.clear();
        this.petBackoffUntilTick.clear();
        this.suppressedPlayers.clear();
        this.sessionTick = 0L;
    }

    public void suppressPlayer(UUID playerUuid) {
        this.suppressedPlayers.add(playerUuid);
        this.playerStates.remove(playerUuid);
    }

    public void resumePlayer(UUID playerUuid) {
        this.suppressedPlayers.remove(playerUuid);
        this.playerStates.remove(playerUuid);
    }

    public boolean debugRunImmediateCheck(ServerPlayerEntity player, java.util.List<PetRecord> records, Consumer<RecallSummary> onComplete) {
        if (records.isEmpty()) {
            return false;
        }

        MinecraftServer server = VersionCompat.getServer(player);
        if (server == null || server.getOverworld() == null || player.isRemoved() || player.isSpectator()
                || !PetRecallService.isPlayerGroundedForRecall(player)) {
            return false;
        }

        long now = server.getOverworld().getTime();
        UUID playerUuid = player.getUuid();
        PlayerAutoState state = this.playerStates.computeIfAbsent(playerUuid, ignored -> new PlayerAutoState());
        java.util.ArrayList<PetRecord> batchRecords = new java.util.ArrayList<>(records);
        boolean hasMoreCandidates = batchRecords.size() > MAX_UNLOADED_PETS_PER_AUTO_RUN;
        if (hasMoreCandidates) {
            batchRecords.subList(MAX_UNLOADED_PETS_PER_AUTO_RUN, batchRecords.size()).clear();
        }

        if (this.recallService.isRecallActive(playerUuid)) {
            return false;
        }

        AutoRecallBatch batch = new AutoRecallBatch(batchRecords, hasMoreCandidates);
        DebugTrace.log("auto-recall", "Starting debug auto recall batch for %s candidates=%d hasMore=%s",
                DebugTrace.describePlayer(player), batch.records().size(), batch.hasMoreCandidates());
        boolean started = this.recallService.recallUnloadedForPlayerAsyncSilent(player, batch.records(), summary -> {
            this.handleBatchCompleted(server, player, playerUuid, batch, summary);
            onComplete.accept(summary);
        });
        if (started) {
            this.applyBackoff(batch.records(), now + AUTO_PET_BACKOFF_TICKS);
            if (!batch.hasMoreCandidates()) {
                state.nextRecallTick = now + AUTO_RECALL_COOLDOWN_TICKS;
            }
            DebugTrace.log("auto-recall", "Debug auto recall accepted for %s backoffUntil=%d nextRecallTick=%d",
                    DebugTrace.describePlayer(player), now + AUTO_PET_BACKOFF_TICKS, state.nextRecallTick);
        }
        return started;
    }

    private void tickPlayer(MinecraftServer server, ServerPlayerEntity player, long now) {
        if (player.isRemoved() || player.isSpectator()) {
            return;
        }

        UUID playerUuid = player.getUuid();
        PlayerAutoState state = this.playerStates.computeIfAbsent(playerUuid, ignored -> new PlayerAutoState());
        this.maybeRunJoinRepair(server, player, state, now);

        long currentChunk = player.getChunkPos().toLong();
        String currentDimension = VersionCompat.getDimensionId(player);
        boolean onGround = PetRecallService.isPlayerGroundedForRecall(player);
        double currentX = player.getX();
        double currentY = player.getY();
        double currentZ = player.getZ();

        if (!state.initialized) {
            state.initialized = true;
            this.scheduleCheck(state, now, "initial observation");
        } else {
            if (state.lastChunkPosLong != currentChunk) {
                this.scheduleCheck(state, now, "chunk changed from " + state.lastChunkPosLong + " to " + currentChunk);
            }
            if (!state.lastDimensionId.equals(currentDimension)) {
                this.scheduleCheck(state, now, "dimension changed from " + state.lastDimensionId + " to " + currentDimension);
            }
            if (!state.lastOnGround && onGround) {
                this.scheduleCheck(state, now, "player landed on ground");
            }

            double dx = currentX - state.lastX;
            double dy = currentY - state.lastY;
            double dz = currentZ - state.lastZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq >= LARGE_TELEPORT_DISTANCE_SQ) {
                this.scheduleCheck(state, now, String.format(java.util.Locale.ROOT, "large movement distanceSq=%.2f", distanceSq));
            }
        }

        state.lastChunkPosLong = currentChunk;
        state.lastDimensionId = currentDimension;
        state.lastOnGround = onGround;
        state.lastX = currentX;
        state.lastY = currentY;
        state.lastZ = currentZ;

        boolean continueBatch = state.continueNextBatchTick >= 0L && now >= state.continueNextBatchTick;
        boolean pendingCheck = state.pendingCheck && now >= state.pendingCheckTick;
        if (!continueBatch && !pendingCheck) {
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
            DebugTrace.log("auto-recall", "No auto-recall candidates for %s", DebugTrace.describePlayer(player));
            state.pendingCheck = false;
            state.continueNextBatchTick = -1L;
            return;
        }

        state.pendingCheck = false;
        UUID playerUuidFinal = playerUuid;
        DebugTrace.log("auto-recall", "Starting auto recall batch for %s candidates=%d hasMore=%s", DebugTrace.describePlayer(player), batch.records().size(), batch.hasMoreCandidates());
        boolean started = this.recallService.recallUnloadedForPlayerAsyncSilent(player, batch.records(), summary ->
                this.handleBatchCompleted(server, player, playerUuidFinal, batch, summary)
        );
        if (started) {
            this.applyBackoff(batch.records(), now + AUTO_PET_BACKOFF_TICKS);
            if (!batch.hasMoreCandidates()) {
                state.nextRecallTick = now + AUTO_RECALL_COOLDOWN_TICKS;
            }
            DebugTrace.log("auto-recall", "Auto recall accepted for %s backoffUntil=%d nextRecallTick=%d",
                    DebugTrace.describePlayer(player), now + AUTO_PET_BACKOFF_TICKS, state.nextRecallTick);
        } else {
            state.continueNextBatchTick = -1L;
            state.nextRecallTick = now + AUTO_RETRY_THROTTLE_TICKS;
            DebugTrace.log("auto-recall", "Auto recall rejected because recall is already active or could not start for %s retryTick=%d",
                    DebugTrace.describePlayer(player), state.nextRecallTick);
        }
    }

    private AutoRecallBatch collectAutoRecallCandidates(MinecraftServer server, ServerPlayerEntity player, long now) {
        Collection<PetRecord> records = this.tracker.getOwnerRecords(server, player.getUuid());
        if (records.isEmpty()) {
            return AutoRecallBatch.empty();
        }

        String playerDimensionId = VersionCompat.getDimensionId(player);
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        var candidates = new java.util.ArrayList<PetRecord>(Math.min(records.size(), MAX_UNLOADED_PETS_PER_AUTO_RUN + 4));
        int skippedSitting = 0;
        int skippedDimension = 0;
        int skippedLoaded = 0;
        int skippedQuarantined = 0;
        int skippedBackoff = 0;
        int skippedNear = 0;

        for (PetRecord record : records) {
            if (record.sitting()) {
                skippedSitting++;
                continue;
            }

            if (!playerDimensionId.equals(record.dimensionId())) {
                skippedDimension++;
                continue;
            }

            if (this.tracker.getLoadedPet(record.petUuid()) != null) {
                skippedLoaded++;
                continue;
            }

            if (this.recallService.isPetQuarantined(record.petUuid(), now)) {
                skippedQuarantined++;
                continue;
            }

            Long backoffUntil = this.petBackoffUntilTick.get(record.petUuid());
            if (backoffUntil != null) {
                if (now < backoffUntil) {
                    skippedBackoff++;
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
            } else {
                skippedNear++;
            }
        }

        if (candidates.isEmpty()) {
            DebugTrace.log("auto-recall", "Candidate scan empty for %s indexed=%d sitting=%d wrongDim=%d loaded=%d quarantined=%d backoff=%d near=%d",
                    DebugTrace.describePlayer(player), records.size(), skippedSitting, skippedDimension, skippedLoaded, skippedQuarantined, skippedBackoff, skippedNear);
            return AutoRecallBatch.empty();
        }

        candidates.sort(Comparator
                .comparing(PetRecord::dimensionId)
                .thenComparingLong(PetRecord::chunkPosLong)
                .thenComparingDouble(record -> -distanceSqTo(record, playerX, playerY, playerZ))
                .thenComparing(PetRecord::petUuid));

        boolean hasMoreCandidates = candidates.size() > MAX_UNLOADED_PETS_PER_AUTO_RUN;
        if (hasMoreCandidates) {
            candidates.subList(MAX_UNLOADED_PETS_PER_AUTO_RUN, candidates.size()).clear();
        }

        DebugTrace.log("auto-recall", "Candidate scan for %s indexed=%d selected=%d hasMore=%s sitting=%d wrongDim=%d loaded=%d quarantined=%d backoff=%d near=%d",
                DebugTrace.describePlayer(player), records.size(), candidates.size(), hasMoreCandidates, skippedSitting, skippedDimension, skippedLoaded, skippedQuarantined, skippedBackoff, skippedNear);

        return new AutoRecallBatch(candidates, hasMoreCandidates);
    }

    private void scheduleCheck(PlayerAutoState state, long now, String reason) {
        if (!state.pendingCheck || now < state.pendingCheckTick) {
            state.pendingCheck = true;
            state.pendingCheckTick = now;
            DebugTrace.log("auto-recall", "Scheduled auto recall check at tick=%d reason=%s", now, reason);
        }
    }

    private void maybeRunJoinRepair(MinecraftServer server, ServerPlayerEntity player, PlayerAutoState state, long now) {
        if (!state.joinRepairPending || now < state.joinRepairTick) {
            return;
        }

        state.joinRepairPending = false;
        int ownerRecordCount = this.tracker.getOwnerRecords(server, player.getUuid()).size();
        if (!shouldRunJoinRepair(this.sessionTick, ownerRecordCount)) {
            DebugTrace.log("auto-recall", "Skipped join repair for %s indexedRecords=%d sessionTick=%d",
                    DebugTrace.describePlayer(player), ownerRecordCount, this.sessionTick);
            return;
        }

        int found = this.tracker.rescanLoadedPetsForOwner(server, player.getUuid());
        DebugTrace.log("auto-recall", "Join repair completed for %s found=%d indexedBefore=%d",
                DebugTrace.describePlayer(player), found, ownerRecordCount);
        if (found > 0) {
            this.scheduleCheck(state, now + 1L, "post-join repair for " + player.getName().getString());
        }
    }

    private void handleBatchCompleted(MinecraftServer server, ServerPlayerEntity player, UUID playerUuid, AutoRecallBatch batch, RecallSummary summary) {
        if (server.getOverworld() == null) {
            return;
        }

        PlayerAutoState callbackState = this.playerStates.get(playerUuid);
        if (callbackState == null) {
            return;
        }

        long callbackNow = server.getOverworld().getTime();
        if (summary.recalled > 0 && batch.hasMoreCandidates()) {
            DebugTrace.log("auto-recall", "Auto recall batch partial success for %s recalled=%d skipped=%d failed=%d continuingNextTick",
                    DebugTrace.describePlayer(player), summary.recalled, summary.skipped, summary.failed);
            callbackState.continueNextBatchTick = callbackNow + 1L;
            callbackState.nextRecallTick = callbackNow + 1L;
            return;
        }

        callbackState.continueNextBatchTick = -1L;
        if (summary.recalled > 0) {
            callbackState.nextRecallTick = callbackNow + AUTO_RECALL_COOLDOWN_TICKS;
        }
        DebugTrace.log("auto-recall", "Auto recall batch finished for %s recalled=%d skipped=%d failed=%d nextRecallTick=%d",
                DebugTrace.describePlayer(player), summary.recalled, summary.skipped, summary.failed, callbackState.nextRecallTick);
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

    static long computeJoinRepairTick(long now) {
        return now + JOIN_REPAIR_DELAY_TICKS;
    }

    static long computeJoinRecallTick(long now) {
        return now + JOIN_RECALL_DELAY_TICKS;
    }

    static boolean shouldRunJoinRepair(long sessionTick, int ownerRecordCount) {
        return ownerRecordCount == 0 && sessionTick <= JOIN_REPAIR_BOOT_WINDOW_TICKS;
    }

    private static final class PlayerAutoState {
        boolean initialized;
        long lastChunkPosLong;
        String lastDimensionId = "";
        boolean lastOnGround;
        double lastX;
        double lastY;
        double lastZ;
        long nextRecallTick;
        long continueNextBatchTick = -1L;
        boolean pendingCheck;
        long pendingCheckTick;
        boolean joinRepairPending;
        long joinRepairTick;
    }

    private record AutoRecallBatch(java.util.List<PetRecord> records, boolean hasMoreCandidates) {
        private static AutoRecallBatch empty() {
            return new AutoRecallBatch(java.util.List.of(), false);
        }
    }
}
