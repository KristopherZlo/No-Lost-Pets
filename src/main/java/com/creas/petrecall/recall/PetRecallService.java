package com.creas.petrecall.recall;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.DebugTrace;
import com.creas.petrecall.util.PetOwnershipUtil;
import com.creas.petrecall.util.PetOwnershipUtil.OwnedPetData;
import com.creas.petrecall.util.VersionCompat;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class PetRecallService {
    private final PetTracker tracker;
    private final Set<UUID> activeRecalls = new HashSet<>();
    private final Set<UUID> activePetRecalls = new HashSet<>();
    private static final int LOAD_TIMEOUT_TICKS = 200;
    private final Map<UUID, RecallRunner> runners = new HashMap<>();
    private final ChunkRecallScheduler<ChunkOperationKey> chunkScheduler = new ChunkRecallScheduler<>(
            4, LOAD_TIMEOUT_TICKS, error -> PetRecallMod.LOGGER.error("Recall completion failed", error)
    );
    private boolean stopping;
    private final PetRecallQuarantineTracker quarantineTracker = new PetRecallQuarantineTracker();

    public PetRecallService(PetTracker tracker) {
        this.tracker = tracker;
    }

    public boolean isRecallActive(UUID playerUuid) {
        synchronized (this.activeRecalls) {
            return this.activeRecalls.contains(playerUuid);
        }
    }

    public boolean isPetQuarantined(UUID petUuid, long now) {
        return this.quarantineTracker.isQuarantined(petUuid, now);
    }

    public void onPetObserved(UUID petUuid) {
        this.quarantineTracker.clear(petUuid);
    }

    public void onPetRemoved(UUID petUuid) {
        this.quarantineTracker.clear(petUuid);
    }

    public int getActiveRecallCount() {
        synchronized (this.activeRecalls) {
            return this.activeRecalls.size();
        }
    }

    public int getActivePetRecallCount() {
        synchronized (this.activePetRecalls) {
            return this.activePetRecalls.size();
        }
    }

    public DebugStats getDebugStats(long now) {
        return new DebugStats(
                this.getActiveRecallCount(),
                this.getActivePetRecallCount(),
                this.chunkScheduler.getActiveKeyCount(),
                this.chunkScheduler.getQueuedTaskCount(),
                this.quarantineTracker.getTrackedStateCount(),
                this.quarantineTracker.getQuarantinedCount(now)
        );
    }

    public boolean recallAllForPlayerAsync(ServerPlayer player, Consumer<RecallSummary> onComplete) {
        return this.recallForPlayerAsync(player, onComplete, true, true, null, true);
    }

    public boolean recallSpecificPetsForPlayerAsync(
            ServerPlayer player,
            List<PetRecord> records,
            boolean includeLoadedPets,
            Consumer<RecallSummary> onComplete
    ) {
        if (records.isEmpty()) {
            return false;
        }
        return this.recallForPlayerAsync(player, onComplete, includeLoadedPets, false, records, true);
    }

    public boolean recallUnloadedForPlayerAsyncSilent(ServerPlayer player) {
        return this.recallUnloadedForPlayerAsyncSilent(player, null, summary -> {
        });
    }

    public boolean recallUnloadedForPlayerAsyncSilent(ServerPlayer player, List<PetRecord> candidateRecords) {
        return this.recallUnloadedForPlayerAsyncSilent(player, candidateRecords, summary -> {
        });
    }

    public boolean recallUnloadedForPlayerAsyncSilent(
            ServerPlayer player,
            @Nullable List<PetRecord> candidateRecords,
            Consumer<RecallSummary> onComplete
    ) {
        if (candidateRecords != null && candidateRecords.isEmpty()) {
            return false;
        }
        return this.recallForPlayerAsync(player, onComplete, false, false, candidateRecords, false);
    }

    private boolean recallForPlayerAsync(
            ServerPlayer player,
            Consumer<RecallSummary> onComplete,
            boolean includeLoadedPets,
            boolean rescanIfEmpty,
            @Nullable List<PetRecord> presetRecords,
            boolean collectMessages
    ) {
        MinecraftServer server = VersionCompat.getServer(player);
        DebugTrace.log("recall", "Recall request includeLoaded=%s rescanIfEmpty=%s presetRecords=%s collectMessages=%s %s",
                includeLoadedPets, rescanIfEmpty, presetRecords == null ? "null" : presetRecords.size(), collectMessages, DebugTrace.describePlayer(player));
        if (server == null || this.stopping) {
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.messages.add("Server is not available");
            summary.failed = 1;
            DebugTrace.log("recall", "Rejecting recall because server is null %s", DebugTrace.describePlayer(player));
            this.notifyComplete(onComplete, summary);
            return false;
        }

        if (!PetIndexState.isAvailable(server)) {
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.messages.add("Pet index is unavailable; see the server log. No pets were moved.");
            summary.failed = 1;
            this.notifyComplete(onComplete, summary);
            return false;
        }

        UUID playerUuid = player.getUUID();
        synchronized (this.activeRecalls) {
            if (!this.activeRecalls.add(playerUuid)) {
                DebugTrace.log("recall", "Rejecting recall because player already has active recall %s", DebugTrace.describePlayer(player));
                return false;
            }
        }

        if (!isPlayerGroundedForRecall(player)) {
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.messages.add("Stand on the ground before using NoLostPets.");
            summary.failed = 1;
            synchronized (this.activeRecalls) {
                this.activeRecalls.remove(playerUuid);
            }
            DebugTrace.log("recall", "Recall started but immediately failed because player is not on ground %s", DebugTrace.describePlayer(player));
            this.notifyComplete(onComplete, summary);
            return true;
        }

        try {
            List<PetRecord> records = presetRecords == null
                    ? new ArrayList<>(this.tracker.getOwnerRecords(server, playerUuid))
                    : new ArrayList<>(presetRecords);
            if (records.isEmpty() && rescanIfEmpty && presetRecords == null) {
                this.tracker.rescanLoadedPetsForOwner(server, playerUuid);
                records = new ArrayList<>(this.tracker.getOwnerRecords(server, playerUuid));
            }
            Map<UUID, PetRecord> unique = new LinkedHashMap<>();
            records.forEach(record -> unique.putIfAbsent(record.petUuid(), record));
            records = new ArrayList<>(unique.values());
            this.sortRecordsForRecall(player, records, includeLoadedPets);
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.totalKnown = records.size();
            RecallRunner runner = new RecallRunner(player, server, records, summary, onComplete, includeLoadedPets);
            this.runners.put(playerUuid, runner);
            this.advanceRunner(runner);
        } catch (RuntimeException error) {
            this.activeRecalls.remove(playerUuid);
            PetRecallMod.LOGGER.error("Failed preparing recall for {}", playerUuid, error);
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.failed = 1;
            summary.messages.add("Recall preparation failed; no pet was removed.");
            this.notifyComplete(onComplete, summary);
        }
        return true;
    }

    private void sortRecordsForRecall(ServerPlayer player, List<PetRecord> records, boolean includeLoadedPets) {
        String playerDimensionId = VersionCompat.getDimensionId(player);
        Map<UUID, Boolean> loadedCache = new HashMap<>(Math.max(16, records.size()));
        Comparator<PetRecord> comparator = (a, b) -> {
            if (includeLoadedPets) {
                boolean aLoaded = loadedCache.computeIfAbsent(a.petUuid(), uuid -> this.tracker.getLoadedPet(uuid) != null);
                boolean bLoaded = loadedCache.computeIfAbsent(b.petUuid(), uuid -> this.tracker.getLoadedPet(uuid) != null);
                int loadedCompare = Boolean.compare(bLoaded, aLoaded);
                if (loadedCompare != 0) {
                    return loadedCompare;
                }
            }

            boolean aSameDim = playerDimensionId.equals(a.dimensionId());
            boolean bSameDim = playerDimensionId.equals(b.dimensionId());
            int sameDimCompare = Boolean.compare(bSameDim, aSameDim);
            if (sameDimCompare != 0) {
                return sameDimCompare;
            }

            int dimCompare = a.dimensionId().compareTo(b.dimensionId());
            if (dimCompare != 0) {
                return dimCompare;
            }

            int chunkCompare = Long.compare(a.chunkPosLong(), b.chunkPosLong());
            if (chunkCompare != 0) {
                return chunkCompare;
            }

            return a.petUuid().compareTo(b.petUuid());
        };
        records.sort(comparator);
    }

    public int rescanLoadedForPlayer(ServerPlayer player) {
        MinecraftServer server = VersionCompat.getServer(player);
        if (server == null) {
            return 0;
        }
        return this.tracker.rescanLoadedPetsForOwner(server, player.getUUID());
    }

    public void onServerTick(MinecraftServer server) {
        if (!this.stopping) {
            this.chunkScheduler.tick();
        }
    }

    public void clearRuntime() {
        this.stopping = true;
        try {
            for (RecallRunner runner : new ArrayList<>(this.runners.values())) {
                this.failRemaining(runner, "Recall cancelled: server stopping.");
            }
            this.chunkScheduler.clear();
            this.activeRecalls.clear();
            this.activePetRecalls.clear();
            this.quarantineTracker.clearAll();
        } finally {
            this.stopping = false;
        }
    }

    private void advanceRunner(RecallRunner runner) {
        while (!runner.finished) {
            if (runner.index >= runner.records.size()) {
                this.finishRunner(runner);
                return;
            }
            if (!canContinueRecallForPlayer(runner.player)) {
                this.failRemaining(runner, "Recall stopped: player unavailable or airborne.");
                return;
            }
            PetRecord record = runner.records.get(runner.index);
            runner.summary.attempted++;
            if (!this.tryBeginPetRecall(record.petUuid())) {
                runner.summary.messages.add("Pet recall is already in progress for " + record.petUuid());
                runner.summary.failed++;
                runner.index++;
                continue;
            }
            try {
                RecallOutcome outcome = this.tryHandleLoadedIfPresent(
                        runner.player, record.petUuid(), runner.summary, runner.includeLoadedPets);
                if (outcome == null) {
                    if (isCrossDimensionRecall(runner.player, record)) {
                        addCrossDimensionSkipMessage(runner.summary, record.petUuid());
                        outcome = RecallOutcome.SKIPPED;
                    } else {
                        var key = record.dimensionKey();
                        ServerLevel world = key == null ? null : runner.server.getLevel(key);
                        if (world != null) {
                            this.beginUnloadedRecall(runner, record, world);
                            return;
                        }
                        runner.summary.messages.add("Source world unavailable for " + record.petUuid());
                        outcome = RecallOutcome.FAILED;
                    }
                }
                applyOutcome(runner.summary, outcome);
            } catch (RuntimeException error) {
                PetRecallMod.LOGGER.error("Recall failed for pet {}", record.petUuid(), error);
                runner.summary.failed++;
                runner.summary.messages.add("Recall failed for " + record.petUuid());
            }
            this.endPetRecall(record.petUuid());
            runner.index++;
        }
    }

    private void failRemaining(RecallRunner runner, String message) {
        if (runner.finished) {
            return;
        }
        runner.summary.messages.add(message);
        runner.summary.failed += runner.records.size() - runner.index;
        runner.summary.attempted = runner.records.size();
        if (runner.index < runner.records.size()) {
            this.endPetRecall(runner.records.get(runner.index).petUuid());
        }
        this.finishRunner(runner);
    }

    private void finishRunner(RecallRunner runner) {
        if (runner.finished) {
            return;
        }
        runner.finished = true;
        this.runners.remove(runner.player.getUUID(), runner);
        this.activeRecalls.remove(runner.player.getUUID());
        this.notifyComplete(runner.onComplete, runner.summary);
    }

    private void notifyComplete(Consumer<RecallSummary> callback, RecallSummary summary) {
        try {
            callback.accept(summary);
        } catch (RuntimeException error) {
            PetRecallMod.LOGGER.error("Pet recall callback failed", error);
        }
    }

    private void beginUnloadedRecall(RecallRunner runner, PetRecord record, ServerLevel sourceWorld) {
        ChunkOperationKey key = new ChunkOperationKey(record.dimensionId(), record.chunkPosLong());
        this.chunkScheduler.enqueue(key, new ChunkRecallScheduler.Operation() {
            @Override
            public boolean canContinue() {
                return !runner.finished && canContinueRecallForPlayer(runner.player)
                        && !isCrossDimensionRecall(runner.player, record);
            }

            @Override
            public void acquire() {
                VersionCompat.holdRecallChunk(sourceWorld, record.chunkPos());
            }

            @Override
            public boolean isReady() {
                return VersionCompat.areChunkEntitiesLoaded(sourceWorld, record.chunkPos());
            }

            @Override
            public void release() {
                VersionCompat.releaseRecallChunk(sourceWorld, record.chunkPos());
            }

            @Override
            public void complete(ChunkRecallScheduler.Completion completion) {
                if (runner.finished) {
                    return;
                }
                RecallOutcome outcome = RecallOutcome.FAILED;
                try {
                    if (isCrossDimensionRecall(runner.player, record)) {
                        addCrossDimensionSkipMessage(runner.summary, record.petUuid());
                        outcome = RecallOutcome.SKIPPED;
                    } else if (completion.result() == ChunkRecallScheduler.Result.READY) {
                        // This request started unloaded: automatic recalls must include the entity we just loaded.
                        Entity entity = tracker.getLoadedPet(record.petUuid());
                        if (entity == null) {
                            entity = sourceWorld.getEntity(record.petUuid());
                        }
                        if (entity == null) {
                            outcome = handleMissingIndexedPet(runner.server, record, runner.summary,
                                    "Pet missing from loaded chunk " + record.chunkPos() + ": ");
                        } else {
                            outcome = recallLoadedPet(runner.player, sourceWorld, entity, runner.summary);
                        }
                    } else {
                        runner.summary.messages.add("Chunk load " + completion.result() + " for " + record.petUuid());
                        if (completion.error() != null) {
                            PetRecallMod.LOGGER.warn("Failed loading pet chunk {}", record.chunkPos(), completion.error());
                        }
                    }
                } catch (RuntimeException error) {
                    PetRecallMod.LOGGER.error("Recall failed for pet {}", record.petUuid(), error);
                    runner.summary.messages.add("Recall failed for " + record.petUuid());
                } finally {
                    endPetRecall(record.petUuid());
                }
                applyOutcome(runner.summary, outcome);
                runner.index++;
                advanceRunner(runner);
            }
        });
    }

    private static void applyOutcome(RecallSummary summary, RecallOutcome outcome) {
        switch (outcome) {
            case RECALLED -> summary.recalled++;
            case SKIPPED -> summary.skipped++;
            case FAILED -> summary.failed++;
        }
    }

    private RecallOutcome recallLoadedPet(ServerPlayer player, ServerLevel targetWorld, Entity entity, RecallSummary summary) {
        if (!canContinueRecallForPlayer(player)) {
            DebugTrace.log("recall", "Loaded recall rejected because player cannot continue %s", DebugTrace.describePlayer(player));
            return RecallOutcome.FAILED;
        }

        if (!VersionCompat.getDimensionId(player).equals(VersionCompat.getDimensionId(entity))) {
            DebugTrace.log("recall", "Loaded recall skipped because pet is in another dimension %s playerDim=%s entityDim=%s",
                    DebugTrace.describeEntity(entity), VersionCompat.getDimensionId(player), VersionCompat.getDimensionId(entity));
            this.onPetObserved(entity.getUUID());
            addCrossDimensionSkipMessage(summary, entity.getUUID());
            return RecallOutcome.SKIPPED;
        }

        OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(entity);
        if (ownedPet == null) {
            DebugTrace.log("recall", "Loaded entity stopped qualifying as supported pet %s", DebugTrace.describeEntity(entity));
            MinecraftServer server = targetWorld.getServer();
            if (server != null) {
                this.tracker.removeRecord(server, entity.getUUID());
                this.onPetRemoved(entity.getUUID());
            }
            summary.messages.add("Non-following tamed mob skipped " + entity.getUUID());
            return RecallOutcome.SKIPPED;
        }

        if (!ownedPet.ownerUuid().equals(player.getUUID())) {
            this.tracker.upsertRecordFromEntity(targetWorld, entity);
            DebugTrace.log("recall", "Loaded recall ownership mismatch entityOwner=%s player=%s %s", ownedPet.ownerUuid(), player.getUUID(), DebugTrace.describeEntity(entity));
            summary.messages.add("Ownership mismatch for pet " + entity.getUUID());
            return RecallOutcome.FAILED;
        }

        if (ownedPet.sitting()) {
            this.tracker.upsertRecordFromEntity(targetWorld, entity);
            DebugTrace.log("recall", "Loaded recall skipped because pet is sitting %s", DebugTrace.describeEntity(entity));
            this.onPetObserved(entity.getUUID());
            summary.messages.add("Sitting pet skipped " + entity.getUUID());
            return RecallOutcome.SKIPPED;
        }

        if (entity.isPassenger() || entity.isVehicle()) {
            summary.messages.add("Mounted pet skipped " + entity.getUUID());
            return RecallOutcome.SKIPPED;
        }

        SafeRecallSpot safeSpot = findSafeRecallPosition(player, targetWorld, entity, summary);
        if (safeSpot == null) {
            DebugTrace.log("recall", "Loaded recall failed because no safe spot was found %s around %s", DebugTrace.describeEntity(entity), DebugTrace.describePlayer(player));
            summary.messages.add("No safe spot near player for pet " + entity.getUUID());
            return RecallOutcome.FAILED;
        }

        Entity teleported = entity.teleport(new TeleportTransition(
                targetWorld,
                safeSpot.position(),
                Vec3.ZERO,
                entity.getYRot(),
                entity.getXRot(),
                TeleportTransition.DO_NOTHING
        ));

        if (teleported == null) {
            DebugTrace.log("recall", "Loaded recall teleport returned null %s", DebugTrace.describeEntity(entity));
            return RecallOutcome.FAILED;
        }

        DebugTrace.log("recall", "Loaded recall teleported pet successfully %s safeSpot=%s", DebugTrace.describeEntity(teleported), safeSpot.blockPos());
        reserveRecallSpot(summary, safeSpot);
        this.tracker.upsertRecordFromEntity(targetWorld, teleported);
        this.onPetObserved(entity.getUUID());
        return RecallOutcome.RECALLED;
    }

    private RecallOutcome handleMissingIndexedPet(MinecraftServer server, PetRecord record, RecallSummary summary, String messagePrefix) {
        PetRecord current = PetIndexState.get(server).getPet(record.petUuid());
        if (current == null || !current.equals(record)) {
            this.quarantineTracker.clear(record.petUuid());
            summary.messages.add("Pet index changed during recall; retry " + record.petUuid());
            return RecallOutcome.FAILED;
        }
        long now = getCurrentTick(server);
        PetRecallQuarantineTracker.MissResult missResult = this.quarantineTracker.recordMiss(record.petUuid(), now);
        if (missResult.shouldRemoveRecord()) {
            DebugTrace.log("recall", "Removing stale pet record after repeated misses count=%d %s", missResult.missCount(), DebugTrace.describeRecord(record));
            this.tracker.removeRecord(server, record.petUuid());
            this.onPetRemoved(record.petUuid());
            PetRecallMod.LOGGER.warn(
                    "Removing stale pet record after {} misses: pet={} owner={} dimension={} chunk={}",
                    missResult.missCount(),
                    record.petUuid(),
                    record.ownerUuid(),
                    record.dimensionId(),
                    record.chunkPos()
            );
            summary.messages.add(messagePrefix + record.petUuid() + " (stale record removed)");
        } else {
            long backoffTicks = Math.max(0L, missResult.quarantineUntilTick() - now);
            DebugTrace.log("recall", "Quarantining missing indexed pet missCount=%d backoffTicks=%d %s", missResult.missCount(), backoffTicks, DebugTrace.describeRecord(record));
            summary.messages.add(messagePrefix + record.petUuid() + " (retry in " + backoffTicks + " ticks)");
        }
        return RecallOutcome.FAILED;
    }

    @Nullable
    private RecallOutcome tryHandleLoadedIfPresent(ServerPlayer player, UUID petUuid, RecallSummary summary, boolean allowLoadedRecall) {
        Entity loaded = this.tracker.getLoadedPet(petUuid);
        if (loaded == null) {
            return null;
        }

        DebugTrace.log("recall", "Loaded pet found in runtime cache allowLoadedRecall=%s %s", allowLoadedRecall, DebugTrace.describeEntity(loaded));
        this.onPetObserved(petUuid);
        if (!VersionCompat.getDimensionId(player).equals(VersionCompat.getDimensionId(loaded))) {
            addCrossDimensionSkipMessage(summary, petUuid);
            return RecallOutcome.SKIPPED;
        }
        if (!allowLoadedRecall) {
            return RecallOutcome.SKIPPED;
        }

        ServerLevel targetWorld = VersionCompat.getServerWorld(player);
        if (targetWorld == null) {
            return RecallOutcome.FAILED;
        }
        return this.recallLoadedPet(player, targetWorld, loaded, summary);
    }

    private boolean tryBeginPetRecall(UUID petUuid) {
        synchronized (this.activePetRecalls) {
            boolean added = this.activePetRecalls.add(petUuid);
            DebugTrace.log("recall", "tryBeginPetRecall pet=%s added=%s activePetRecalls=%d", petUuid, added, this.activePetRecalls.size());
            return added;
        }
    }

    private void endPetRecall(UUID petUuid) {
        synchronized (this.activePetRecalls) {
            this.activePetRecalls.remove(petUuid);
            DebugTrace.log("recall", "endPetRecall pet=%s activePetRecalls=%d", petUuid, this.activePetRecalls.size());
        }
    }

    public static boolean isPlayerGroundedForRecall(ServerPlayer player) {
        if (player.isRemoved() || !player.isAlive()) {
            return false;
        }
        if (player.onGround()) {
            return true;
        }

        ServerLevel world = VersionCompat.getServerWorld(player);
        if (world == null) {
            return false;
        }

        AABB box = player.getBoundingBox().inflate(-0.05D, 0.0D, -0.05D);
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(box.maxX);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        int y = (int) Math.floor(box.minY - 0.05D);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos footingPos = new BlockPos(x, y, z);
                if (hasCollisionFooting(world, footingPos)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean canContinueRecallForPlayer(ServerPlayer player) {
        return !player.isRemoved() && isPlayerGroundedForRecall(player);
    }

    private static long getCurrentTick(MinecraftServer server) {
        return server.overworld() == null ? 0L : server.overworld().getGameTime();
    }

    @Nullable
    private static SafeRecallSpot findSafeRecallPosition(ServerPlayer player, ServerLevel targetWorld, Entity pet, RecallSummary summary) {
        if (!(pet instanceof Mob mob)) {
            DebugTrace.log("recall", "Cannot find safe recall spot because entity is not a MobEntity %s", DebugTrace.describeEntity(pet));
            return null;
        }

        BlockPos ownerPos = player.blockPosition();
        final int maxRadius = 6;
        final int[] yOffsets = {0, 1, -1};
        SafeRecallSpot bestSpot = null;
        int bestUsage = Integer.MAX_VALUE;

        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int yOffset : yOffsets) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;
                        }

                        BlockPos candidate = new BlockPos(ownerPos.getX() + x, ownerPos.getY() + yOffset, ownerPos.getZ() + z);
                        if (!canTeleportTo(targetWorld, mob, candidate)) {
                            continue;
                        }

                        int usage = summary.recallSpotUsage.getOrDefault(candidate.asLong(), 0);
                        Vec3 position = new Vec3(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D);
                        if (usage < bestUsage) {
                            bestUsage = usage;
                            bestSpot = new SafeRecallSpot(candidate, position);
                            if (bestUsage == 0) {
                                DebugTrace.log("recall", "Safe spot selected immediately for %s spot=%s usage=%d", DebugTrace.describeEntity(pet), candidate, usage);
                                return bestSpot;
                            }
                        }
                    }
                }
            }
        }

        BlockPos underPlayer = ownerPos;
        Vec3 underPlayerPosition = new Vec3(player.getX(), underPlayer.getY(), player.getZ());
        if (canTeleportTo(targetWorld, mob, underPlayer, underPlayerPosition, true)) {
            int usage = summary.recallSpotUsage.getOrDefault(underPlayer.asLong(), 0);
            Vec3 position = underPlayerPosition;
            SafeRecallSpot fallback = new SafeRecallSpot(underPlayer, position);
            if (bestSpot == null || usage <= bestUsage) {
                DebugTrace.log("recall", "Using under-player fallback spot for %s spot=%s usage=%d", DebugTrace.describeEntity(pet), underPlayer, usage);
                return fallback;
            }
        }

        if (bestSpot != null) {
            DebugTrace.log("recall", "Using best safe spot for %s spot=%s", DebugTrace.describeEntity(pet), bestSpot.blockPos());
        } else {
            DebugTrace.log("recall", "No safe spot found for %s around %s", DebugTrace.describeEntity(pet), DebugTrace.describePlayer(player));
        }
        return bestSpot;
    }

    private static boolean canTeleportTo(ServerLevel targetWorld, Mob mob, BlockPos pos) {
        return canTeleportTo(targetWorld, mob, pos,
                new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D), false);
    }

    private static boolean canTeleportTo(ServerLevel targetWorld, Mob mob, BlockPos pos, Vec3 destination, boolean ignoreEntityCollisions) {
        BlockPos belowPos = pos.below();
        BlockState belowState = targetWorld.getBlockState(belowPos);
        if (belowState.getBlock() instanceof LeavesBlock) {
            return false;
        }
        if (!targetWorld.getFluidState(belowPos).isEmpty()) {
            return false;
        }
        if (!belowState.isFaceSturdy(targetWorld, belowPos, Direction.UP)) {
            return false;
        }

        if (!isPassableRecallSpace(targetWorld, pos)) {
            return false;
        }

        AABB targetBox = mob.getBoundingBox().move(
                destination.x - mob.getX(), destination.y - mob.getY(), destination.z - mob.getZ());
        if (targetBox.minY < targetWorld.getMinY()
                || targetBox.maxY > targetWorld.getMinY() + targetWorld.getHeight()
                || !targetWorld.getWorldBorder().isWithinBounds(targetBox)) {
            return false;
        }
        if (boxContainsFluid(targetWorld, targetBox)) {
            return false;
        }

        return ignoreEntityCollisions
                ? targetWorld.noCollision(targetBox)
                : targetWorld.noCollision(mob, targetBox);
    }

    private static boolean isPassableRecallSpace(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean hasCollisionFooting(ServerLevel world, BlockPos pos) {
        if (!world.getFluidState(pos).isEmpty()) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return false;
        }

        return shape.max(Direction.Axis.Y) > 0.0D;
    }

    private static boolean boxContainsFluid(ServerLevel world, AABB box) {
        for (BlockPos blockPos : BlockPos.betweenClosed(box)) {
            if (!world.getFluidState(blockPos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void reserveRecallSpot(RecallSummary summary, SafeRecallSpot safeSpot) {
        long key = safeSpot.blockPos().asLong();
        summary.recallSpotUsage.merge(key, 1, Integer::sum);
    }

    private static boolean isCrossDimensionRecall(ServerPlayer player, PetRecord record) {
        return !record.dimensionId().equals(VersionCompat.getDimensionId(player));
    }

    private static void addCrossDimensionSkipMessage(RecallSummary summary, UUID petUuid) {
        summary.messages.add("Cross-dimension recall skipped " + petUuid);
    }

    public static final class RecallSummary {
        private static final List<String> NO_OP_MESSAGES = new AbstractList<>() {
            @Override
            public String get(int index) {
                throw new IndexOutOfBoundsException(index);
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public boolean add(String element) {
                return true;
            }
        };

        public int totalKnown;
        public int attempted;
        public int recalled;
        public int skipped;
        public int failed;
        public final List<String> messages;
        private final Map<Long, Integer> recallSpotUsage = new HashMap<>();

        public RecallSummary() {
            this(true);
        }

        public RecallSummary(boolean collectMessages) {
            this.messages = collectMessages ? new ArrayList<>() : NO_OP_MESSAGES;
        }
    }

    public record DebugStats(
            int activePlayerRecalls,
            int activePetRecalls,
            int activeChunkOperations,
            int queuedChunkOperations,
            int trackedRuntimeStates,
            int quarantinedPets
    ) {
    }

    private enum RecallOutcome {
        RECALLED,
        SKIPPED,
        FAILED
    }

    private record SafeRecallSpot(BlockPos blockPos, Vec3 position) {
    }

    private record ChunkOperationKey(String dimensionId, long chunkPosLong) {
    }

    private static final class RecallRunner {
        private final ServerPlayer player;
        private final MinecraftServer server;
        private final List<PetRecord> records;
        private final RecallSummary summary;
        private final Consumer<RecallSummary> onComplete;
        private final boolean includeLoadedPets;
        private int index;
        private boolean finished;

        private RecallRunner(
                ServerPlayer player,
                MinecraftServer server,
                List<PetRecord> records,
                RecallSummary summary,
                Consumer<RecallSummary> onComplete,
                boolean includeLoadedPets
        ) {
            this.player = player;
            this.server = server;
            this.records = records;
            this.summary = summary;
            this.onComplete = onComplete;
            this.includeLoadedPets = includeLoadedPets;
        }
    }
}
