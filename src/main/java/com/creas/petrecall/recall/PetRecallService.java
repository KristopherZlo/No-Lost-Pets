package com.creas.petrecall.recall;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.mixin.accessor.ServerEntityManagerAccessor;
import com.creas.petrecall.mixin.accessor.ServerWorldAccessor;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.DebugTrace;
import com.creas.petrecall.util.PetOwnershipUtil;
import com.creas.petrecall.util.PetOwnershipUtil.OwnedPetData;
import com.creas.petrecall.util.VersionCompat;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.storage.ChunkDataAccess;
import net.minecraft.world.storage.ChunkDataList;
import net.minecraft.world.storage.EntityChunkDataAccess;
import org.jetbrains.annotations.Nullable;

public final class PetRecallService {
    private final PetTracker tracker;
    private final Set<UUID> activeRecalls = new HashSet<>();
    private final Set<UUID> activePetRecalls = new HashSet<>();
    private final ChunkRecallScheduler<ChunkOperationKey> chunkScheduler = new ChunkRecallScheduler<>();
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

    public boolean recallAllForPlayerAsync(ServerPlayerEntity player, Consumer<RecallSummary> onComplete) {
        return this.recallForPlayerAsync(player, onComplete, true, true, null, true);
    }

    public boolean recallSpecificPetsForPlayerAsync(
            ServerPlayerEntity player,
            List<PetRecord> records,
            boolean includeLoadedPets,
            Consumer<RecallSummary> onComplete
    ) {
        if (records.isEmpty()) {
            return false;
        }
        return this.recallForPlayerAsync(player, onComplete, includeLoadedPets, false, records, true);
    }

    public boolean recallUnloadedForPlayerAsyncSilent(ServerPlayerEntity player) {
        return this.recallUnloadedForPlayerAsyncSilent(player, null, summary -> {
        });
    }

    public boolean recallUnloadedForPlayerAsyncSilent(ServerPlayerEntity player, List<PetRecord> candidateRecords) {
        return this.recallUnloadedForPlayerAsyncSilent(player, candidateRecords, summary -> {
        });
    }

    public boolean recallUnloadedForPlayerAsyncSilent(
            ServerPlayerEntity player,
            @Nullable List<PetRecord> candidateRecords,
            Consumer<RecallSummary> onComplete
    ) {
        if (candidateRecords != null && candidateRecords.isEmpty()) {
            return false;
        }
        return this.recallForPlayerAsync(player, onComplete, false, false, candidateRecords, false);
    }

    private boolean recallForPlayerAsync(
            ServerPlayerEntity player,
            Consumer<RecallSummary> onComplete,
            boolean includeLoadedPets,
            boolean rescanIfEmpty,
            @Nullable List<PetRecord> presetRecords,
            boolean collectMessages
    ) {
        MinecraftServer server = VersionCompat.getServer(player);
        DebugTrace.log("recall", "Recall request includeLoaded=%s rescanIfEmpty=%s presetRecords=%s collectMessages=%s %s",
                includeLoadedPets, rescanIfEmpty, presetRecords == null ? "null" : presetRecords.size(), collectMessages, DebugTrace.describePlayer(player));
        if (server == null) {
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.messages.add("Server is not available");
            summary.failed = 1;
            DebugTrace.log("recall", "Rejecting recall because server is null %s", DebugTrace.describePlayer(player));
            onComplete.accept(summary);
            return false;
        }

        UUID playerUuid = player.getUuid();
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
            onComplete.accept(summary);
            return true;
        }

        List<PetRecord> records = presetRecords == null
                ? new ArrayList<>(this.tracker.getOwnerRecords(server, playerUuid))
                : new ArrayList<>(presetRecords);
        if (records.isEmpty() && rescanIfEmpty && presetRecords == null) {
            DebugTrace.log("recall", "No indexed records for %s, running rescan", DebugTrace.describePlayer(player));
            this.tracker.rescanLoadedPetsForOwner(server, playerUuid);
            records = new ArrayList<>(this.tracker.getOwnerRecords(server, playerUuid));
        }

        this.sortRecordsForRecall(player, records, includeLoadedPets);
        DebugTrace.log("recall", "Prepared recall list for %s records=%d includeLoaded=%s", DebugTrace.describePlayer(player), records.size(), includeLoadedPets);
        for (PetRecord record : records) {
            DebugTrace.log("recall", "  candidate %s", DebugTrace.describeRecord(record));
        }

        RecallSummary summary = new RecallSummary(collectMessages);
        summary.totalKnown = records.size();

        RecallRunner runner = new RecallRunner(player, server, records, summary, onComplete, includeLoadedPets);
        this.advanceRunner(runner);
        return true;
    }

    private void sortRecordsForRecall(ServerPlayerEntity player, List<PetRecord> records, boolean includeLoadedPets) {
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

    public int rescanLoadedForPlayer(ServerPlayerEntity player) {
        MinecraftServer server = VersionCompat.getServer(player);
        if (server == null) {
            return 0;
        }
        return this.tracker.rescanLoadedPetsForOwner(server, player.getUuid());
    }

    private void advanceRunner(RecallRunner runner) {
        while (true) {
            if (runner.index >= runner.records.size()) {
                this.finishRunner(runner);
                return;
            }

            ServerPlayerEntity player = runner.player;
            if (player.isRemoved()) {
                runner.summary.messages.add("Player is no longer available");
                runner.summary.failed += Math.max(0, runner.records.size() - runner.index);
                runner.summary.attempted += Math.max(0, runner.records.size() - runner.index);
                this.finishRunner(runner);
                return;
            }

            if (!isPlayerGroundedForRecall(player)) {
                runner.summary.messages.add("Recall stopped: stand on the ground.");
                runner.summary.failed += Math.max(0, runner.records.size() - runner.index);
                runner.summary.attempted += Math.max(0, runner.records.size() - runner.index);
                this.finishRunner(runner);
                return;
            }

            PetRecord record = runner.records.get(runner.index);
            if (isCrossDimensionRecall(player, record)) {
                addCrossDimensionSkipMessage(runner.summary, record.petUuid());
                runner.summary.skipped++;
                runner.index++;
                continue;
            }

            if (!this.tryBeginPetRecall(record.petUuid())) {
                runner.summary.messages.add("Pet recall is already in progress for " + record.petUuid());
                runner.summary.failed++;
                runner.index++;
                continue;
            }

            RecallOutcome loadedOutcome = this.tryHandleLoadedIfPresent(player, record.petUuid(), runner.summary, runner.includeLoadedPets);
            if (loadedOutcome != null) {
                try {
                    runner.summary.attempted++;
                    applyOutcome(runner.summary, loadedOutcome);
                } finally {
                    this.endPetRecall(record.petUuid());
                }
                runner.index++;
                continue;
            }

            runner.summary.attempted++;
            this.beginUnloadedRecall(runner, record);
            return;
        }
    }

    private void finishRunner(RecallRunner runner) {
        synchronized (this.activeRecalls) {
            this.activeRecalls.remove(runner.player.getUuid());
        }
        DebugTrace.log("recall", "Runner finished for %s totalKnown=%d attempted=%d recalled=%d skipped=%d failed=%d",
                DebugTrace.describePlayer(runner.player), runner.summary.totalKnown, runner.summary.attempted, runner.summary.recalled, runner.summary.skipped, runner.summary.failed);
        runner.onComplete.accept(runner.summary);
    }

    private void beginUnloadedRecall(RecallRunner runner, PetRecord record) {
        MinecraftServer server = runner.server;
        ServerPlayerEntity player = runner.player;
        DebugTrace.log("recall", "Beginning unloaded recall path %s for %s", DebugTrace.describeRecord(record), DebugTrace.describePlayer(player));

        var sourceWorldKey = record.dimensionKey();
        if (sourceWorldKey == null) {
            runner.summary.messages.add("Bad dimension id for pet " + record.petUuid() + ": " + record.dimensionId());
            DebugTrace.log("recall", "Bad dimension key for %s", DebugTrace.describeRecord(record));
            this.tracker.removeRecord(server, record.petUuid());
            this.onPetRemoved(record.petUuid());
            this.completeRecord(runner, record, RecallOutcome.FAILED);
            return;
        }

        ServerWorld sourceWorld = server.getWorld(sourceWorldKey);
        if (sourceWorld == null) {
            runner.summary.messages.add("Source world missing for pet " + record.petUuid() + ": " + sourceWorldKey.getValue());
            DebugTrace.log("recall", "Source world missing for %s", DebugTrace.describeRecord(record));
            this.completeRecord(runner, record, RecallOutcome.FAILED);
            return;
        }

        if (isCrossDimensionRecall(player, record)) {
            this.completeRecord(runner, record, RecallOutcome.SKIPPED);
            return;
        }

        ChunkOperationKey chunkKey = new ChunkOperationKey(record.dimensionId(), record.chunkPosLong());
        DebugTrace.log("recall", "Queueing chunk operation dim=%s chunk=%s for %s", record.dimensionId(), record.chunkPos(), DebugTrace.describePetUuid(record.petUuid()));
        Runnable task = () -> this.performQueuedUnloadedRecall(runner, record, sourceWorld, chunkKey);
        Runnable toRun = this.chunkScheduler.enqueue(chunkKey, task);
        if (toRun != null) {
            toRun.run();
        }
    }

    private void performQueuedUnloadedRecall(RecallRunner runner, PetRecord record, ServerWorld sourceWorld, ChunkOperationKey chunkKey) {
        try {
            DebugTrace.log("recall", "Executing queued unload recall dim=%s chunk=%s %s", record.dimensionId(), record.chunkPos(), DebugTrace.describeRecord(record));
            if (!canContinueRecallForPlayer(runner.player)) {
                DebugTrace.log("recall", "Cancelling queued recall because player can no longer continue %s", DebugTrace.describePlayer(runner.player));
                this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
                return;
            }

            if (isCrossDimensionRecall(runner.player, record)) {
                this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.SKIPPED);
                return;
            }

            RecallOutcome loadedOutcome = this.tryHandleLoadedIfPresent(runner.player, record.petUuid(), runner.summary, runner.includeLoadedPets);
            if (loadedOutcome != null) {
                this.completeQueuedRecord(runner, record, chunkKey, loadedOutcome);
                return;
            }

            ChunkPos chunkPos = record.chunkPos();
            if (isEntityChunkLoaded(sourceWorld, chunkPos)) {
                DebugTrace.log("recall", "Source entity chunk is already loaded; switching to loaded chunk fallback %s chunk=%s", DebugTrace.describeRecord(record), DebugTrace.describeChunk(chunkPos));
                RecallOutcome fallbackOutcome = this.handleLoadedChunkFallback(runner, record, sourceWorld, chunkPos);
                this.completeQueuedRecord(runner, record, chunkKey, fallbackOutcome);
                return;
            }

            EntityChunkDataAccess dataAccess = getEntityChunkDataAccess(sourceWorld);
            Object storage = VersionCompat.getChunkStorage(dataAccess);
            DebugTrace.log("recall", "Reading entity chunk data for %s chunk=%s sourceWorld=%s", DebugTrace.describePetUuid(record.petUuid()), DebugTrace.describeChunk(chunkPos), DebugTrace.describeWorld(sourceWorld));
            dataAccess.readChunkData(chunkPos).whenComplete((chunkData, throwable) -> runner.server.execute(() ->
                    this.handleChunkDataLoaded(runner, record, sourceWorld, chunkPos, chunkKey, dataAccess, storage, chunkData, throwable)
            ));
        } catch (RuntimeException e) {
            DebugTrace.log("recall", "Queued unload recall crashed for %s error=%s", DebugTrace.describeRecord(record), e.getMessage());
            this.endPetRecall(record.petUuid());
            Runnable next = this.chunkScheduler.complete(chunkKey);
            if (next != null) {
                runner.server.execute(next);
            }
            throw e;
        }
    }

    private void handleChunkDataLoaded(
            RecallRunner runner,
            PetRecord record,
            ServerWorld sourceWorld,
            ChunkPos chunkPos,
            ChunkOperationKey chunkKey,
            EntityChunkDataAccess dataAccess,
            Object storage,
            @Nullable ChunkDataList<Entity> chunkData,
            @Nullable Throwable throwable
    ) {
        if (throwable != null) {
            PetRecallMod.LOGGER.warn("Failed reading entity chunk data for {} in {}", chunkPos, sourceWorld.getRegistryKey().getValue(), throwable);
            DebugTrace.log("recall", "Chunk read failed for %s chunk=%s error=%s", DebugTrace.describeRecord(record), DebugTrace.describeChunk(chunkPos), throwable.getMessage());
            runner.summary.messages.add("Read failed for pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        if (!canContinueRecallForPlayer(runner.player)) {
            runner.summary.messages.add("Recall cancelled while waiting for pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        if (isCrossDimensionRecall(runner.player, record)) {
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.SKIPPED);
            return;
        }

        RecallOutcome loadedOutcome = this.tryHandleLoadedIfPresent(runner.player, record.petUuid(), runner.summary, runner.includeLoadedPets);
        if (loadedOutcome != null) {
            this.completeQueuedRecord(runner, record, chunkKey, loadedOutcome);
            return;
        }

        if (isEntityChunkLoaded(sourceWorld, chunkPos)) {
            RecallOutcome fallbackOutcome = this.handleLoadedChunkFallback(runner, record, sourceWorld, chunkPos);
            this.completeQueuedRecord(runner, record, chunkKey, fallbackOutcome);
            return;
        }

        if (chunkData == null) {
            DebugTrace.log("recall", "Chunk data was null for %s chunk=%s", DebugTrace.describeRecord(record), DebugTrace.describeChunk(chunkPos));
            RecallOutcome missOutcome = this.handleMissingIndexedPet(runner.server, record, runner.summary, "Entity chunk missing for indexed pet " + chunkPos + ": ");
            this.completeQueuedRecord(runner, record, chunkKey, missOutcome);
            return;
        }

        DebugTrace.log("recall", "Chunk data loaded for %s chunk=%s entityCount=%d",
                DebugTrace.describeRecord(record), DebugTrace.describeChunk(chunkPos), chunkData.stream().toList().size());
        List<Entity> originalEntities = new ArrayList<>(chunkData.stream().toList());
        Entity sourcePet = null;
        for (Entity entity : originalEntities) {
            if (entity.getUuid().equals(record.petUuid())) {
                sourcePet = entity;
                break;
            }
        }

        if (sourcePet == null) {
            DebugTrace.log("recall", "Indexed pet not found inside loaded chunk data for %s chunk=%s", DebugTrace.describeRecord(record), DebugTrace.describeChunk(chunkPos));
            RecallOutcome missOutcome = this.handleMissingIndexedPet(runner.server, record, runner.summary, "Pet not found in indexed chunk " + chunkPos + ": ");
            this.completeQueuedRecord(runner, record, chunkKey, missOutcome);
            return;
        }

        OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(sourcePet);
        if (ownedPet == null) {
            DebugTrace.log("recall", "Source pet no longer matches supported companion rules %s", DebugTrace.describeEntity(sourcePet));
            this.tracker.removeRecord(runner.server, record.petUuid());
            this.onPetRemoved(record.petUuid());
            runner.summary.messages.add("Non-following tamed mob skipped " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.SKIPPED);
            return;
        }

        if (!ownedPet.ownerUuid().equals(runner.player.getUuid())) {
            DebugTrace.log("recall", "Ownership mismatch during unloaded recall sourceOwner=%s player=%s %s", ownedPet.ownerUuid(), runner.player.getUuid(), DebugTrace.describeRecord(record));
            runner.summary.messages.add("Ownership mismatch for pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        if (ownedPet.sitting()) {
            DebugTrace.log("recall", "Skipping sitting pet during unloaded recall %s", DebugTrace.describeRecord(record));
            this.onPetObserved(record.petUuid());
            runner.summary.messages.add("Sitting pet skipped " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.SKIPPED);
            return;
        }

        NbtCompound petSnapshot = writeEntityData(sourcePet);
        if (petSnapshot == null) {
            DebugTrace.log("recall", "Failed to serialize source pet snapshot %s", DebugTrace.describeEntity(sourcePet));
            runner.summary.messages.add("Failed to serialize pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        List<Entity> updatedEntities = new ArrayList<>(originalEntities);
        updatedEntities.remove(sourcePet);

        ChunkDataList<Entity> updatedChunkData = new ChunkDataList<>(chunkPos, updatedEntities);
        ChunkDataList<Entity> originalChunkData = new ChunkDataList<>(chunkPos, originalEntities);
        DebugTrace.log("recall", "Writing updated chunk data after removing pet %s chunk=%s remainingEntities=%d",
                DebugTrace.describePetUuid(record.petUuid()), DebugTrace.describeChunk(chunkPos), updatedEntities.size());
        writeChunkDataAsync(dataAccess, storage, updatedChunkData).whenComplete((unused, writeThrowable) -> runner.server.execute(() ->
                this.handleChunkWriteCompleted(runner, record, chunkKey, dataAccess, storage, originalChunkData, petSnapshot, writeThrowable)
        ));
    }

    private void handleChunkWriteCompleted(
            RecallRunner runner,
            PetRecord record,
            ChunkOperationKey chunkKey,
            EntityChunkDataAccess dataAccess,
            Object storage,
            ChunkDataList<Entity> originalChunkData,
            NbtCompound petSnapshot,
            @Nullable Throwable writeThrowable
    ) {
        if (writeThrowable != null) {
            PetRecallMod.LOGGER.warn("Failed writing entity chunk data after removing pet {}", record.petUuid(), writeThrowable);
            DebugTrace.log("recall", "Chunk write failed after removing pet %s error=%s", DebugTrace.describeRecord(record), writeThrowable.getMessage());
            runner.summary.messages.add("Write failed for pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        if (!canContinueRecallForPlayer(runner.player)) {
            runner.summary.messages.add("Recall cancelled before spawning pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }

        if (isCrossDimensionRecall(runner.player, record)) {
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.SKIPPED)
            );
            return;
        }

        RecallOutcome loadedOutcome = this.tryHandleLoadedIfPresent(runner.player, record.petUuid(), runner.summary, runner.includeLoadedPets);
        if (loadedOutcome != null) {
            this.completeQueuedRecord(runner, record, chunkKey, loadedOutcome);
            return;
        }

        ServerWorld targetWorld = VersionCompat.getServerWorld(runner.player);
        if (targetWorld == null) {
            DebugTrace.log("recall", "Target world missing while recreating unloaded pet %s", DebugTrace.describeRecord(record));
            runner.summary.messages.add("Player world is unavailable for pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }
        Entity recreated = EntityType.loadEntityWithPassengers(petSnapshot.copy(), targetWorld, SpawnReason.COMMAND, entity -> entity);
        if (recreated == null) {
            DebugTrace.log("recall", "Failed to recreate entity from snapshot for %s", DebugTrace.describeRecord(record));
            runner.summary.messages.add("Failed to recreate pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }

        SafeRecallSpot safeSpot = findSafeRecallPosition(runner.player, targetWorld, recreated, runner.summary);
        if (safeSpot == null) {
            DebugTrace.log("recall", "No safe spot found for recreated pet %s around %s", DebugTrace.describeEntity(recreated), DebugTrace.describePlayer(runner.player));
            runner.summary.messages.add("No safe spot near player for pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }

        recreated.refreshPositionAndAngles(safeSpot.position(), recreated.getYaw(), recreated.getPitch());
        boolean spawned = targetWorld.spawnNewEntityAndPassengers(recreated);
        if (!spawned) {
            DebugTrace.log("recall", "Failed to spawn recreated pet into target world %s safeSpot=%s", DebugTrace.describeEntity(recreated), safeSpot.blockPos());
            runner.summary.messages.add("Failed to spawn pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }

        DebugTrace.log("recall", "Spawned recreated pet successfully %s safeSpot=%s", DebugTrace.describeEntity(recreated), safeSpot.blockPos());
        reserveRecallSpot(runner.summary, safeSpot);
        this.tracker.upsertRecordFromEntity(targetWorld, recreated);
        this.onPetObserved(record.petUuid());
        this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.RECALLED);
    }

    private void completeRecord(RecallRunner runner, PetRecord record, RecallOutcome outcome) {
        try {
            applyOutcome(runner.summary, outcome);
        } finally {
            DebugTrace.log("recall", "Completed immediate record outcome=%s %s", outcome, DebugTrace.describeRecord(record));
            this.endPetRecall(record.petUuid());
        }
        runner.index++;
        this.advanceRunner(runner);
    }

    private void completeQueuedRecord(RecallRunner runner, PetRecord record, ChunkOperationKey chunkKey, RecallOutcome outcome) {
        try {
            applyOutcome(runner.summary, outcome);
        } finally {
            DebugTrace.log("recall", "Completed queued record outcome=%s dim=%s chunkLong=%d %s", outcome, chunkKey.dimensionId(), chunkKey.chunkPosLong(), DebugTrace.describeRecord(record));
            this.endPetRecall(record.petUuid());
            Runnable next = this.chunkScheduler.complete(chunkKey);
            if (next != null) {
                runner.server.execute(next);
            }
        }
        runner.index++;
        this.advanceRunner(runner);
    }

    private static void applyOutcome(RecallSummary summary, RecallOutcome outcome) {
        switch (outcome) {
            case RECALLED -> summary.recalled++;
            case SKIPPED -> summary.skipped++;
            case FAILED -> summary.failed++;
        }
    }

    private RecallOutcome recallLoadedPet(ServerPlayerEntity player, ServerWorld targetWorld, Entity entity, RecallSummary summary) {
        if (!canContinueRecallForPlayer(player)) {
            DebugTrace.log("recall", "Loaded recall rejected because player cannot continue %s", DebugTrace.describePlayer(player));
            return RecallOutcome.FAILED;
        }

        if (!VersionCompat.getDimensionId(player).equals(VersionCompat.getDimensionId(entity))) {
            DebugTrace.log("recall", "Loaded recall skipped because pet is in another dimension %s playerDim=%s entityDim=%s",
                    DebugTrace.describeEntity(entity), VersionCompat.getDimensionId(player), VersionCompat.getDimensionId(entity));
            this.onPetObserved(entity.getUuid());
            addCrossDimensionSkipMessage(summary, entity.getUuid());
            return RecallOutcome.SKIPPED;
        }

        OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(entity);
        if (ownedPet == null) {
            DebugTrace.log("recall", "Loaded entity stopped qualifying as supported pet %s", DebugTrace.describeEntity(entity));
            MinecraftServer server = targetWorld.getServer();
            if (server != null) {
                this.tracker.removeRecord(server, entity.getUuid());
                this.onPetRemoved(entity.getUuid());
            }
            summary.messages.add("Non-following tamed mob skipped " + entity.getUuid());
            return RecallOutcome.SKIPPED;
        }

        if (!ownedPet.ownerUuid().equals(player.getUuid())) {
            DebugTrace.log("recall", "Loaded recall ownership mismatch entityOwner=%s player=%s %s", ownedPet.ownerUuid(), player.getUuid(), DebugTrace.describeEntity(entity));
            summary.messages.add("Ownership mismatch for pet " + entity.getUuid());
            return RecallOutcome.FAILED;
        }

        if (ownedPet.sitting()) {
            DebugTrace.log("recall", "Loaded recall skipped because pet is sitting %s", DebugTrace.describeEntity(entity));
            this.onPetObserved(entity.getUuid());
            summary.messages.add("Sitting pet skipped " + entity.getUuid());
            return RecallOutcome.SKIPPED;
        }

        SafeRecallSpot safeSpot = findSafeRecallPosition(player, targetWorld, entity, summary);
        if (safeSpot == null) {
            DebugTrace.log("recall", "Loaded recall failed because no safe spot was found %s around %s", DebugTrace.describeEntity(entity), DebugTrace.describePlayer(player));
            summary.messages.add("No safe spot near player for pet " + entity.getUuid());
            return RecallOutcome.FAILED;
        }

        Entity teleported = entity.teleportTo(new TeleportTarget(
                targetWorld,
                safeSpot.position(),
                Vec3d.ZERO,
                entity.getYaw(),
                entity.getPitch(),
                TeleportTarget.NO_OP
        ));

        if (teleported == null) {
            DebugTrace.log("recall", "Loaded recall teleport returned null %s", DebugTrace.describeEntity(entity));
            return RecallOutcome.FAILED;
        }

        DebugTrace.log("recall", "Loaded recall teleported pet successfully %s safeSpot=%s", DebugTrace.describeEntity(teleported), safeSpot.blockPos());
        reserveRecallSpot(summary, safeSpot);
        this.tracker.upsertRecordFromEntity(targetWorld, teleported);
        this.onPetObserved(entity.getUuid());
        return RecallOutcome.RECALLED;
    }

    private RecallOutcome handleLoadedChunkFallback(RecallRunner runner, PetRecord record, ServerWorld sourceWorld, ChunkPos chunkPos) {
        Entity loadedInChunk = findLoadedPetInChunk(sourceWorld, chunkPos, record.petUuid());
        if (loadedInChunk == null) {
            DebugTrace.log("recall", "Loaded chunk fallback could not find pet in chunk %s %s", DebugTrace.describeChunk(chunkPos), DebugTrace.describeRecord(record));
            return this.handleMissingIndexedPet(runner.server, record, runner.summary, "Pet missing from loaded chunk " + chunkPos + ": ");
        }

        DebugTrace.log("recall", "Loaded chunk fallback found pet %s", DebugTrace.describeEntity(loadedInChunk));
        this.tracker.upsertRecordFromEntity(sourceWorld, loadedInChunk);
        this.onPetObserved(record.petUuid());
        if (!runner.includeLoadedPets) {
            return RecallOutcome.SKIPPED;
        }

        ServerWorld targetWorld = VersionCompat.getServerWorld(runner.player);
        if (targetWorld == null) {
            return RecallOutcome.FAILED;
        }
        return this.recallLoadedPet(runner.player, targetWorld, loadedInChunk, runner.summary);
    }

    private RecallOutcome handleMissingIndexedPet(MinecraftServer server, PetRecord record, RecallSummary summary, String messagePrefix) {
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
    private RecallOutcome tryHandleLoadedIfPresent(ServerPlayerEntity player, UUID petUuid, RecallSummary summary, boolean allowLoadedRecall) {
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

        ServerWorld targetWorld = VersionCompat.getServerWorld(player);
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

    public static boolean isPlayerGroundedForRecall(ServerPlayerEntity player) {
        if (player.isRemoved()) {
            return false;
        }
        if (player.isOnGround()) {
            return true;
        }

        ServerWorld world = VersionCompat.getServerWorld(player);
        if (world == null) {
            return false;
        }

        Box box = player.getBoundingBox().expand(-0.05D, 0.0D, -0.05D);
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

    private static boolean canContinueRecallForPlayer(ServerPlayerEntity player) {
        return !player.isRemoved() && isPlayerGroundedForRecall(player);
    }

    private static long getCurrentTick(MinecraftServer server) {
        return server.getOverworld() == null ? 0L : server.getOverworld().getTime();
    }

    @Nullable
    private static SafeRecallSpot findSafeRecallPosition(ServerPlayerEntity player, ServerWorld targetWorld, Entity pet, RecallSummary summary) {
        if (!(pet instanceof MobEntity mob)) {
            DebugTrace.log("recall", "Cannot find safe recall spot because entity is not a MobEntity %s", DebugTrace.describeEntity(pet));
            return null;
        }

        BlockPos ownerPos = player.getBlockPos();
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
                        Vec3d position = new Vec3d(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D);
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
        if (canTeleportTo(targetWorld, mob, underPlayer, true)) {
            int usage = summary.recallSpotUsage.getOrDefault(underPlayer.asLong(), 0);
            Vec3d position = new Vec3d(player.getX(), underPlayer.getY(), player.getZ());
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

    private static boolean canTeleportTo(ServerWorld targetWorld, MobEntity mob, BlockPos pos) {
        return canTeleportTo(targetWorld, mob, pos, false);
    }

    private static boolean canTeleportTo(ServerWorld targetWorld, MobEntity mob, BlockPos pos, boolean ignoreEntityCollisions) {
        BlockPos belowPos = pos.down();
        BlockState belowState = targetWorld.getBlockState(belowPos);
        if (belowState.getBlock() instanceof LeavesBlock) {
            return false;
        }
        if (!targetWorld.getFluidState(belowPos).isEmpty()) {
            return false;
        }
        if (!belowState.isSideSolidFullSquare(targetWorld, belowPos, Direction.UP)) {
            return false;
        }

        if (!isPassableRecallSpace(targetWorld, pos)) {
            return false;
        }

        BlockPos relative = pos.subtract(mob.getBlockPos());
        Box targetBox = mob.getBoundingBox().offset(relative);
        if (boxContainsFluid(targetWorld, targetBox)) {
            return false;
        }

        return ignoreEntityCollisions
                ? targetWorld.isSpaceEmpty(targetBox)
                : targetWorld.isSpaceEmpty(mob, targetBox);
    }

    private static boolean isPassableRecallSpace(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean hasCollisionFooting(ServerWorld world, BlockPos pos) {
        if (!world.getFluidState(pos).isEmpty()) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return false;
        }

        return shape.getMax(Direction.Axis.Y) > 0.0D;
    }

    private static boolean boxContainsFluid(ServerWorld world, Box box) {
        for (BlockPos blockPos : BlockPos.iterate(box)) {
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

    private static boolean isCrossDimensionRecall(ServerPlayerEntity player, PetRecord record) {
        return !record.dimensionId().equals(VersionCompat.getDimensionId(player));
    }

    private static void addCrossDimensionSkipMessage(RecallSummary summary, UUID petUuid) {
        summary.messages.add("Cross-dimension recall skipped " + petUuid);
    }

    private static EntityChunkDataAccess getEntityChunkDataAccess(ServerWorld world) {
        ServerEntityManager<Entity> entityManager = ((ServerWorldAccessor) world).pet_recall$getEntityManager();
        @SuppressWarnings("unchecked")
        ChunkDataAccess<Entity> dataAccess = ((ServerEntityManagerAccessor<Entity>) (Object) entityManager).pet_recall$getDataAccess();
        if (dataAccess instanceof EntityChunkDataAccess entityChunkDataAccess) {
            return entityChunkDataAccess;
        }
        throw new IllegalStateException("Unexpected entity data access: " + dataAccess.getClass().getName());
    }

    private static boolean isEntityChunkLoaded(ServerWorld world, ChunkPos chunkPos) {
        ServerEntityManager<Entity> entityManager = ((ServerWorldAccessor) world).pet_recall$getEntityManager();
        return entityManager.isLoaded(chunkPos.toLong());
    }

    @Nullable
    private static Entity findLoadedPetInChunk(ServerWorld world, ChunkPos chunkPos, UUID petUuid) {
        int minX = chunkPos.getStartX();
        int minZ = chunkPos.getStartZ();
        int maxY = world.getBottomY() + world.getHeight();
        Box chunkBox = new Box(minX, world.getBottomY(), minZ, minX + 16, maxY, minZ + 16);
        List<Entity> entities = world.getOtherEntities(null, chunkBox, entity -> entity.getUuid().equals(petUuid));
        return entities.isEmpty() ? null : entities.get(0);
    }

    @Nullable
    private static NbtCompound writeEntityData(Entity entity) {
        try {
            NbtWriteView view = NbtWriteView.create(ErrorReporter.EMPTY, entity.getRegistryManager());
            if (!entity.saveData(view)) {
                return null;
            }
            return view.getNbt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static CompletableFuture<Void> writeChunkDataAsync(EntityChunkDataAccess dataAccess, Object storage, ChunkDataList<Entity> chunkData) {
        ChunkPos chunkPos = chunkData.getChunkPos();
        LongSet emptyChunks = VersionCompat.getEmptyChunks(dataAccess);
        if (chunkData.isEmpty()) {
            emptyChunks.add(chunkPos.toLong());
            return VersionCompat.clearChunkData(storage, chunkPos);
        }

        NbtCompound chunkNbt = serializeChunkData(chunkData);
        emptyChunks.remove(chunkPos.toLong());
        return VersionCompat.writeChunkData(storage, chunkPos, chunkNbt);
    }

    private static NbtCompound serializeChunkData(ChunkDataList<Entity> chunkData) {
        ChunkPos chunkPos = chunkData.getChunkPos();
        ErrorReporter.Logging logging = new ErrorReporter.Logging(Chunk.createErrorReporterContext(chunkPos), PetRecallMod.LOGGER);
        try {
            NbtList entitiesNbt = new NbtList();
            chunkData.stream().forEach(entity -> {
                NbtWriteView view = NbtWriteView.create(logging.makeChild(entity.getErrorReporterContext()), entity.getRegistryManager());
                if (entity.saveData(view)) {
                    entitiesNbt.add(view.getNbt());
                }
            });

            NbtCompound chunkNbt = NbtHelper.putDataVersion(new NbtCompound());
            chunkNbt.put("Entities", entitiesNbt);
            chunkNbt.put("Position", ChunkPos.CODEC, chunkPos);
            return chunkNbt;
        } finally {
            logging.close();
        }
    }

    private static void rollbackChunkWrite(
            EntityChunkDataAccess dataAccess,
            Object storage,
            MinecraftServer server,
            ChunkDataList<Entity> originalChunkData,
            UUID petUuid,
            Runnable afterRollback
    ) {
        DebugTrace.log("recall", "Rolling back chunk write for pet=%s chunk=%s", petUuid, DebugTrace.describeChunk(originalChunkData.getChunkPos()));
        writeChunkDataAsync(dataAccess, storage, originalChunkData).whenComplete((unused, rollbackThrowable) -> server.execute(() -> {
            if (rollbackThrowable != null) {
                PetRecallMod.LOGGER.error("Failed rollback for pet {} in chunk {}", petUuid, originalChunkData.getChunkPos(), rollbackThrowable);
                DebugTrace.log("recall", "Rollback failed for pet=%s chunk=%s error=%s", petUuid, DebugTrace.describeChunk(originalChunkData.getChunkPos()), rollbackThrowable.getMessage());
            } else {
                DebugTrace.log("recall", "Rollback completed for pet=%s chunk=%s", petUuid, DebugTrace.describeChunk(originalChunkData.getChunkPos()));
            }
            afterRollback.run();
        }));
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

    private record SafeRecallSpot(BlockPos blockPos, Vec3d position) {
    }

    private record ChunkOperationKey(String dimensionId, long chunkPosLong) {
    }

    private static final class RecallRunner {
        private final ServerPlayerEntity player;
        private final MinecraftServer server;
        private final List<PetRecord> records;
        private final RecallSummary summary;
        private final Consumer<RecallSummary> onComplete;
        private final boolean includeLoadedPets;
        private int index;

        private RecallRunner(
                ServerPlayerEntity player,
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
