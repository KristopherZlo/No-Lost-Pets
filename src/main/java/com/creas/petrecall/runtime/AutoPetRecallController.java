package com.creas.petrecall.runtime;

import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.util.DebugTrace;
import com.creas.petrecall.util.VersionCompat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

    public boolean debugRunImmediateCheck(ServerPlayerEntity player, List<PetRecord> records, Consumer<RecallSummary> onComplete) {
        MinecraftServer server = VersionCompat.getServer(player);
        if (records.isEmpty() || server == null || server.getOverworld() == null || player.isRemoved()
                || player.isSpectator() || !PetRecallService.isPlayerGroundedForRecall(player)
                || this.recallService.isRecallActive(player.getUuid())) {
            return false;
        }
        PlayerAutoState state = this.playerStates.computeIfAbsent(player.getUuid(), ignored -> new PlayerAutoState());
        state.pendingPets.clear();
        records.stream().filter(record -> this.tracker.getLoadedPet(record.petUuid()) == null)
                .forEach(state.pendingPets::addLast);
        List<PetRecord> batch = this.takeNextBatch(server, player, state);
        return !batch.isEmpty() && this.startBatch(server, player, state, batch, onComplete);
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
                state.pendingPets.clear();
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
        if ((!continueBatch && !pendingCheck) || !onGround || now < state.nextRecallTick
                || this.recallService.isRecallActive(playerUuid)) {
            return;
        }

        if (state.pendingPets.isEmpty()) {
            // Select the whole unloaded group before our chunk tickets load its neighbours.
            state.pendingPets.addAll(this.collectAutoRecallCandidates(server, player, now));
        }
        state.pendingCheck = false;
        List<PetRecord> batch = this.takeNextBatch(server, player, state);
        if (batch.isEmpty()) {
            state.continueNextBatchTick = -1L;
            return;
        }
        this.startBatch(server, player, state, batch, summary -> { });
    }

    private List<PetRecord> takeNextBatch(MinecraftServer server, ServerPlayerEntity player, PlayerAutoState state) {
        List<PetRecord> batch = new ArrayList<>(MAX_UNLOADED_PETS_PER_AUTO_RUN);
        while (!state.pendingPets.isEmpty() && batch.size() < MAX_UNLOADED_PETS_PER_AUTO_RUN) {
            PetRecord queued = state.pendingPets.removeFirst();
            PetRecord current = PetIndexState.get(server).getPet(queued.petUuid());
            if (current != null && current.ownerUuid().equals(player.getUuid()) && !current.sitting()
                    && current.dimensionId().equals(VersionCompat.getDimensionId(player))
                    && distanceSqTo(current, player.getX(), player.getY(), player.getZ()) >= VANILLA_FOLLOW_TELEPORT_DISTANCE_SQ) {
                batch.add(current);
            }
        }
        return batch;
    }

    private boolean startBatch(MinecraftServer server, ServerPlayerEntity player, PlayerAutoState state,
            List<PetRecord> batch, Consumer<RecallSummary> onComplete) {
        long now = server.getOverworld().getTime();
        DebugTrace.log("auto-recall", "Starting auto batch for %s selected=%d remaining=%d",
                DebugTrace.describePlayer(player), batch.size(), state.pendingPets.size());
        boolean started = this.recallService.recallSelectedPetsForPlayerAsyncSilent(player, batch, summary -> {
            this.handleBatchCompleted(server, player, state, batch, summary);
            onComplete.accept(summary);
        });
        if (started) {
            this.applyBackoff(batch, now + AUTO_PET_BACKOFF_TICKS);
        } else {
            this.restoreBatch(state, batch);
            state.continueNextBatchTick = now + AUTO_RETRY_THROTTLE_TICKS;
            state.nextRecallTick = state.continueNextBatchTick;
        }
        return started;
    }

    private static void restoreBatch(PlayerAutoState state, List<PetRecord> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) state.pendingPets.addFirst(batch.get(i));
    }

    private List<PetRecord> collectAutoRecallCandidates(MinecraftServer server, ServerPlayerEntity player, long now) {
        Collection<PetRecord> records = this.tracker.getOwnerRecords(server, player.getUuid());
        if (records.isEmpty()) {
            return List.of();
        }

        String playerDimensionId = VersionCompat.getDimensionId(player);
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();
        var candidates = new ArrayList<PetRecord>(records.size());
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
            return List.of();
        }

        candidates.sort(Comparator
                .comparing(PetRecord::dimensionId)
                .thenComparingLong(PetRecord::chunkPosLong)
                .thenComparingDouble(record -> -distanceSqTo(record, playerX, playerY, playerZ))
                .thenComparing(PetRecord::petUuid));

        DebugTrace.log("auto-recall", "Candidate scan for %s indexed=%d selected=%d hasMore=%s sitting=%d wrongDim=%d loaded=%d quarantined=%d backoff=%d near=%d",
                DebugTrace.describePlayer(player), records.size(), candidates.size(), candidates.size() > MAX_UNLOADED_PETS_PER_AUTO_RUN, skippedSitting, skippedDimension, skippedLoaded, skippedQuarantined, skippedBackoff, skippedNear);

        return candidates;
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

    private void handleBatchCompleted(MinecraftServer server, ServerPlayerEntity player, PlayerAutoState state,
            List<PetRecord> batch, RecallSummary summary) {
        if (server.getOverworld() == null || this.playerStates.get(player.getUuid()) != state) {
            return;
        }
        // A jump cancels the service's in-flight work; keep that selection until the owner lands.
        if (!PetRecallService.isPlayerGroundedForRecall(player) && player.isAlive() && !player.isRemoved()
                && batch.getFirst().dimensionId().equals(VersionCompat.getDimensionId(player))) {
            this.restoreBatch(state, batch);
        }
        long now = server.getOverworld().getTime();
        state.continueNextBatchTick = state.pendingPets.isEmpty() ? -1L : now + 1L;
        state.nextRecallTick = state.pendingPets.isEmpty() ? now + AUTO_RECALL_COOLDOWN_TICKS : now + 1L;
        DebugTrace.log("auto-recall", "Auto batch finished for %s recalled=%d skipped=%d failed=%d remaining=%d nextTick=%d",
                DebugTrace.describePlayer(player), summary.recalled, summary.skipped, summary.failed,
                state.pendingPets.size(), state.nextRecallTick);
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
        final ArrayDeque<PetRecord> pendingPets = new ArrayDeque<>();
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

}
