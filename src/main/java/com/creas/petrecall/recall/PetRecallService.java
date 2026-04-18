package com.creas.petrecall.recall;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.mixin.accessor.ServerEntityManagerAccessor;
import com.creas.petrecall.mixin.accessor.ServerWorldAccessor;
import com.creas.petrecall.runtime.PetTracker;
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
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.PathNodeType;
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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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
        if (server == null) {
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.messages.add("Server is not available");
            summary.failed = 1;
            onComplete.accept(summary);
            return false;
        }

        UUID playerUuid = player.getUuid();
        synchronized (this.activeRecalls) {
            if (!this.activeRecalls.add(playerUuid)) {
                return false;
            }
        }

        if (!player.isOnGround()) {
            RecallSummary summary = new RecallSummary(collectMessages);
            summary.messages.add("Stand on the ground before using NoLostPets.");
            summary.failed = 1;
            synchronized (this.activeRecalls) {
                this.activeRecalls.remove(playerUuid);
            }
            onComplete.accept(summary);
            return true;
        }

        List<PetRecord> records = presetRecords == null
                ? new ArrayList<>(this.tracker.getOwnerRecords(server, playerUuid))
                : new ArrayList<>(presetRecords);
        if (records.isEmpty() && rescanIfEmpty && presetRecords == null) {
            this.tracker.rescanLoadedPetsForOwner(server, playerUuid);
            records = new ArrayList<>(this.tracker.getOwnerRecords(server, playerUuid));
        }

        this.sortRecordsForRecall(player, records, includeLoadedPets);

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

            if (!player.isOnGround()) {
                runner.summary.messages.add("Recall stopped: stand on the ground.");
                runner.summary.failed += Math.max(0, runner.records.size() - runner.index);
                runner.summary.attempted += Math.max(0, runner.records.size() - runner.index);
                this.finishRunner(runner);
                return;
            }

            PetRecord record = runner.records.get(runner.index);
            if (!runner.includeLoadedPets && !record.dimensionId().equals(VersionCompat.getDimensionId(player))) {
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
        runner.onComplete.accept(runner.summary);
    }

    private void beginUnloadedRecall(RecallRunner runner, PetRecord record) {
        MinecraftServer server = runner.server;
        ServerPlayerEntity player = runner.player;

        var sourceWorldKey = record.dimensionKey();
        if (sourceWorldKey == null) {
            runner.summary.messages.add("Bad dimension id for pet " + record.petUuid() + ": " + record.dimensionId());
            this.tracker.removeRecord(server, record.petUuid());
            this.onPetRemoved(record.petUuid());
            this.completeRecord(runner, record, RecallOutcome.FAILED);
            return;
        }

        ServerWorld sourceWorld = server.getWorld(sourceWorldKey);
        if (sourceWorld == null) {
            runner.summary.messages.add("Source world missing for pet " + record.petUuid() + ": " + sourceWorldKey.getValue());
            this.completeRecord(runner, record, RecallOutcome.FAILED);
            return;
        }

        if (!runner.includeLoadedPets && !record.dimensionId().equals(VersionCompat.getDimensionId(player))) {
            this.completeRecord(runner, record, RecallOutcome.SKIPPED);
            return;
        }

        ChunkOperationKey chunkKey = new ChunkOperationKey(record.dimensionId(), record.chunkPosLong());
        Runnable task = () -> this.performQueuedUnloadedRecall(runner, record, sourceWorld, chunkKey);
        Runnable toRun = this.chunkScheduler.enqueue(chunkKey, task);
        if (toRun != null) {
            toRun.run();
        }
    }

    private void performQueuedUnloadedRecall(RecallRunner runner, PetRecord record, ServerWorld sourceWorld, ChunkOperationKey chunkKey) {
        try {
            if (!canContinueRecallForPlayer(runner.player)) {
                this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
                return;
            }

            if (!runner.includeLoadedPets && !record.dimensionId().equals(VersionCompat.getDimensionId(runner.player))) {
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
                RecallOutcome fallbackOutcome = this.handleLoadedChunkFallback(runner, record, sourceWorld, chunkPos);
                this.completeQueuedRecord(runner, record, chunkKey, fallbackOutcome);
                return;
            }

            EntityChunkDataAccess dataAccess = getEntityChunkDataAccess(sourceWorld);
            Object storage = VersionCompat.getChunkStorage(dataAccess);
            dataAccess.readChunkData(chunkPos).whenComplete((chunkData, throwable) -> runner.server.execute(() ->
                    this.handleChunkDataLoaded(runner, record, sourceWorld, chunkPos, chunkKey, dataAccess, storage, chunkData, throwable)
            ));
        } catch (RuntimeException e) {
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
            runner.summary.messages.add("Read failed for pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        if (!canContinueRecallForPlayer(runner.player)) {
            runner.summary.messages.add("Recall cancelled while waiting for pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        if (!runner.includeLoadedPets && !record.dimensionId().equals(VersionCompat.getDimensionId(runner.player))) {
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
            RecallOutcome missOutcome = this.handleMissingIndexedPet(runner.server, record, runner.summary, "Entity chunk missing for indexed pet " + chunkPos + ": ");
            this.completeQueuedRecord(runner, record, chunkKey, missOutcome);
            return;
        }

        List<Entity> originalEntities = new ArrayList<>(chunkData.stream().toList());
        Entity sourcePet = null;
        for (Entity entity : originalEntities) {
            if (entity.getUuid().equals(record.petUuid())) {
                sourcePet = entity;
                break;
            }
        }

        if (sourcePet == null) {
            RecallOutcome missOutcome = this.handleMissingIndexedPet(runner.server, record, runner.summary, "Pet not found in indexed chunk " + chunkPos + ": ");
            this.completeQueuedRecord(runner, record, chunkKey, missOutcome);
            return;
        }

        OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(sourcePet);
        if (ownedPet == null) {
            this.tracker.removeRecord(runner.server, record.petUuid());
            this.onPetRemoved(record.petUuid());
            runner.summary.messages.add("Non-following tamed mob skipped " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.SKIPPED);
            return;
        }

        if (!ownedPet.ownerUuid().equals(runner.player.getUuid())) {
            this.tracker.removeRecord(runner.server, record.petUuid());
            this.onPetRemoved(record.petUuid());
            runner.summary.messages.add("Ownership mismatch for pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        if (ownedPet.sitting()) {
            this.onPetObserved(record.petUuid());
            runner.summary.messages.add("Sitting pet skipped " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.SKIPPED);
            return;
        }

        NbtCompound petSnapshot = writeEntityData(sourcePet);
        if (petSnapshot == null) {
            runner.summary.messages.add("Failed to serialize pet " + record.petUuid());
            this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED);
            return;
        }

        List<Entity> updatedEntities = new ArrayList<>(originalEntities);
        updatedEntities.remove(sourcePet);

        ChunkDataList<Entity> updatedChunkData = new ChunkDataList<>(chunkPos, updatedEntities);
        ChunkDataList<Entity> originalChunkData = new ChunkDataList<>(chunkPos, originalEntities);
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

        if (!runner.includeLoadedPets && !record.dimensionId().equals(VersionCompat.getDimensionId(runner.player))) {
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
            runner.summary.messages.add("Player world is unavailable for pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }
        Entity recreated = EntityType.loadEntityWithPassengers(petSnapshot.copy(), targetWorld, SpawnReason.COMMAND, entity -> entity);
        if (recreated == null) {
            runner.summary.messages.add("Failed to recreate pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }

        SafeRecallSpot safeSpot = findSafeRecallPosition(runner.player, targetWorld, recreated, runner.summary);
        if (safeSpot == null) {
            runner.summary.messages.add("No safe spot near player for pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }

        recreated.refreshPositionAndAngles(safeSpot.position(), recreated.getYaw(), recreated.getPitch());
        boolean spawned = targetWorld.spawnNewEntityAndPassengers(recreated);
        if (!spawned) {
            runner.summary.messages.add("Failed to spawn pet " + record.petUuid());
            rollbackChunkWrite(dataAccess, storage, runner.server, originalChunkData, record.petUuid(), () ->
                    this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.FAILED)
            );
            return;
        }

        reserveRecallSpot(runner.summary, safeSpot);
        this.tracker.upsertRecordFromEntity(targetWorld, recreated);
        this.onPetObserved(record.petUuid());
        this.completeQueuedRecord(runner, record, chunkKey, RecallOutcome.RECALLED);
    }

    private void completeRecord(RecallRunner runner, PetRecord record, RecallOutcome outcome) {
        try {
            applyOutcome(runner.summary, outcome);
        } finally {
            this.endPetRecall(record.petUuid());
        }
        runner.index++;
        this.advanceRunner(runner);
    }

    private void completeQueuedRecord(RecallRunner runner, PetRecord record, ChunkOperationKey chunkKey, RecallOutcome outcome) {
        try {
            applyOutcome(runner.summary, outcome);
        } finally {
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
            return RecallOutcome.FAILED;
        }

        OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(entity);
        if (ownedPet == null) {
            MinecraftServer server = targetWorld.getServer();
            if (server != null) {
                this.tracker.removeRecord(server, entity.getUuid());
                this.onPetRemoved(entity.getUuid());
            }
            summary.messages.add("Non-following tamed mob skipped " + entity.getUuid());
            return RecallOutcome.SKIPPED;
        }

        if (!ownedPet.ownerUuid().equals(player.getUuid())) {
            summary.messages.add("Ownership mismatch for pet " + entity.getUuid());
            return RecallOutcome.FAILED;
        }

        if (ownedPet.sitting()) {
            this.onPetObserved(entity.getUuid());
            summary.messages.add("Sitting pet skipped " + entity.getUuid());
            return RecallOutcome.SKIPPED;
        }

        SafeRecallSpot safeSpot = findSafeRecallPosition(player, targetWorld, entity, summary);
        if (safeSpot == null) {
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
            return RecallOutcome.FAILED;
        }

        reserveRecallSpot(summary, safeSpot);
        this.tracker.upsertRecordFromEntity(targetWorld, teleported);
        this.onPetObserved(entity.getUuid());
        return RecallOutcome.RECALLED;
    }

    private RecallOutcome handleLoadedChunkFallback(RecallRunner runner, PetRecord record, ServerWorld sourceWorld, ChunkPos chunkPos) {
        Entity loadedInChunk = findLoadedPetInChunk(sourceWorld, chunkPos, record.petUuid());
        if (loadedInChunk == null) {
            return this.handleMissingIndexedPet(runner.server, record, runner.summary, "Pet missing from loaded chunk " + chunkPos + ": ");
        }

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

        this.onPetObserved(petUuid);
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
            return this.activePetRecalls.add(petUuid);
        }
    }

    private void endPetRecall(UUID petUuid) {
        synchronized (this.activePetRecalls) {
            this.activePetRecalls.remove(petUuid);
        }
    }

    private static boolean canContinueRecallForPlayer(ServerPlayerEntity player) {
        return !player.isRemoved() && player.isOnGround();
    }

    private static long getCurrentTick(MinecraftServer server) {
        return server.getOverworld() == null ? 0L : server.getOverworld().getTime();
    }

    @Nullable
    private static SafeRecallSpot findSafeRecallPosition(ServerPlayerEntity player, ServerWorld targetWorld, Entity pet, RecallSummary summary) {
        if (!(pet instanceof MobEntity mob)) {
            return null;
        }

        BlockPos ownerPos = player.getBlockPos();
        final int maxRadius = 8;
        final int[] yOffsets = {0, 1, -1, 2, -2};
        SafeRecallSpot bestSpot = null;
        int bestUsage = Integer.MAX_VALUE;

        for (int radius = 2; radius <= maxRadius; radius++) {
            for (int yOffset : yOffsets) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;
                        }
                        if (Math.abs(x) < 2 && Math.abs(z) < 2) {
                            continue;
                        }

                        BlockPos candidate = new BlockPos(ownerPos.getX() + x, ownerPos.getY() + yOffset, ownerPos.getZ() + z);
                        if (!canTeleportTo(targetWorld, mob, candidate)) {
                            continue;
                        }
                        if (!hasLineOfSightToCandidate(player, targetWorld, mob, candidate)) {
                            continue;
                        }

                        int usage = summary.recallSpotUsage.getOrDefault(candidate.asLong(), 0);
                        Vec3d position = new Vec3d(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D);
                        if (usage < bestUsage) {
                            bestUsage = usage;
                            bestSpot = new SafeRecallSpot(candidate, position);
                            if (bestUsage == 0) {
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
                return fallback;
            }
        }

        return bestSpot;
    }

    private static boolean canTeleportTo(ServerWorld targetWorld, MobEntity mob, BlockPos pos) {
        return canTeleportTo(targetWorld, mob, pos, false);
    }

    private static boolean canTeleportTo(ServerWorld targetWorld, MobEntity mob, BlockPos pos, boolean ignoreEntityCollisions) {
        PathNodeType nodeType = LandPathNodeMaker.getLandNodeType(mob, pos);
        if (nodeType != PathNodeType.WALKABLE) {
            return false;
        }

        BlockState belowState = targetWorld.getBlockState(pos.down());
        if (belowState.getBlock() instanceof LeavesBlock) {
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

    private static boolean boxContainsFluid(ServerWorld world, Box box) {
        for (BlockPos blockPos : BlockPos.iterate(box)) {
            if (!world.getFluidState(blockPos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLineOfSightToCandidate(ServerPlayerEntity player, ServerWorld world, MobEntity mob, BlockPos candidate) {
        Vec3d start = new Vec3d(player.getX(), player.getEyeY(), player.getZ());
        double targetY = candidate.getY() + Math.min(1.0D, Math.max(0.25D, mob.getHeight() * 0.5D));
        Vec3d end = new Vec3d(candidate.getX() + 0.5D, targetY, candidate.getZ() + 0.5D);
        HitResult hit = world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void reserveRecallSpot(RecallSummary summary, SafeRecallSpot safeSpot) {
        long key = safeSpot.blockPos().asLong();
        summary.recallSpotUsage.merge(key, 1, Integer::sum);
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
        writeChunkDataAsync(dataAccess, storage, originalChunkData).whenComplete((unused, rollbackThrowable) -> server.execute(() -> {
            if (rollbackThrowable != null) {
                PetRecallMod.LOGGER.error("Failed rollback for pet {} in chunk {}", petUuid, originalChunkData.getChunkPos(), rollbackThrowable);
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
