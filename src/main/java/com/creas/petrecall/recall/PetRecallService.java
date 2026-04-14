package com.creas.petrecall.recall;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.mixin.accessor.EntityChunkDataAccessAccessor;
import com.creas.petrecall.mixin.accessor.ServerEntityManagerAccessor;
import com.creas.petrecall.mixin.accessor.ServerWorldAccessor;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.PetOwnershipUtil;
import com.creas.petrecall.util.PetOwnershipUtil.OwnedPetData;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
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
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Uuids;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.storage.ChunkDataAccess;
import net.minecraft.world.storage.EntityChunkDataAccess;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.jetbrains.annotations.Nullable;

public final class PetRecallService {
    private final PetTracker tracker;
    private final Set<UUID> activeRecalls = new HashSet<>();
    private final Set<UUID> activePetRecalls = new HashSet<>();

    public PetRecallService(PetTracker tracker) {
        this.tracker = tracker;
    }

    public boolean isRecallActive(UUID playerUuid) {
        synchronized (this.activeRecalls) {
            return this.activeRecalls.contains(playerUuid);
        }
    }

    public boolean recallAllForPlayerAsync(ServerPlayerEntity player, Consumer<RecallSummary> onComplete) {
        return this.recallForPlayerAsync(player, onComplete, true, true, null, true);
    }

    public boolean recallUnloadedForPlayerAsyncSilent(ServerPlayerEntity player) {
        return this.recallUnloadedForPlayerAsyncSilent(player, null, summary -> {
            // Silent auto-recall: no chat output, no command-like feedback.
        });
    }

    public boolean recallUnloadedForPlayerAsyncSilent(ServerPlayerEntity player, List<PetRecord> candidateRecords) {
        return this.recallUnloadedForPlayerAsyncSilent(player, candidateRecords, summary -> {
            // Silent auto-recall with prefiltered candidates.
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
        ServerWorld targetWorld = player.getEntityWorld();
        MinecraftServer server = targetWorld.getServer();
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
            summary.messages.add("Stand on the ground before using Pet Recall.");
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

        this.processNextRecord(player, server, records, 0, summary, onComplete, includeLoadedPets);
        return true;
    }

    private void sortRecordsForRecall(ServerPlayerEntity player, List<PetRecord> records, boolean includeLoadedPets) {
        String playerDimensionId = player.getEntityWorld().getRegistryKey().getValue().toString();
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
        ServerWorld targetWorld = player.getEntityWorld();
        MinecraftServer server = targetWorld.getServer();
        if (server == null) {
            return 0;
        }
        return this.tracker.rescanLoadedPetsForOwner(server, player.getUuid());
    }

    private void processNextRecord(
            ServerPlayerEntity player,
            MinecraftServer server,
            List<PetRecord> records,
            int index,
            RecallSummary summary,
            Consumer<RecallSummary> onComplete,
            boolean includeLoadedPets
    ) {
        if (index >= records.size()) {
            synchronized (this.activeRecalls) {
                this.activeRecalls.remove(player.getUuid());
            }
            onComplete.accept(summary);
            return;
        }

        if (player.isRemoved()) {
            summary.messages.add("Player is no longer available");
            summary.failed += Math.max(0, records.size() - index);
            summary.attempted += Math.max(0, records.size() - index);
            synchronized (this.activeRecalls) {
                this.activeRecalls.remove(player.getUuid());
            }
            onComplete.accept(summary);
            return;
        }

        if (!player.isOnGround()) {
            summary.messages.add("Recall stopped: stand on the ground.");
            summary.failed += Math.max(0, records.size() - index);
            summary.attempted += Math.max(0, records.size() - index);
            synchronized (this.activeRecalls) {
                this.activeRecalls.remove(player.getUuid());
            }
            onComplete.accept(summary);
            return;
        }

        PetRecord record = records.get(index);
        if (!this.tryBeginPetRecall(record.petUuid())) {
            summary.messages.add("Pet recall is already in progress for " + record.petUuid());
            summary.failed++;
            this.processNextRecord(player, server, records, index + 1, summary, onComplete, includeLoadedPets);
            return;
        }

        Entity loaded = this.tracker.getLoadedPet(record.petUuid());
        if (loaded != null) {
            if (!includeLoadedPets) {
                this.endPetRecall(record.petUuid());
                this.processNextRecord(player, server, records, index + 1, summary, onComplete, includeLoadedPets);
                return;
            }

            summary.attempted++;
            RecallOutcome outcome;
            try {
                outcome = this.recallLoadedPet(player, player.getEntityWorld(), loaded, summary);
            } finally {
                this.endPetRecall(record.petUuid());
            }
            applyOutcome(summary, outcome);
            this.processNextRecord(player, server, records, index + 1, summary, onComplete, includeLoadedPets);
            return;
        }

        summary.attempted++;
        try {
            this.recallUnloadedPetAsync(player, player.getEntityWorld(), server, record, summary, outcome -> {
                try {
                    applyOutcome(summary, outcome);
                } finally {
                    this.endPetRecall(record.petUuid());
                }
                this.processNextRecord(player, server, records, index + 1, summary, onComplete, includeLoadedPets);
            });
        } catch (RuntimeException e) {
            this.endPetRecall(record.petUuid());
            throw e;
        }
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
            }
            summary.messages.add("Non-following tamed mob skipped " + entity.getUuid());
            return RecallOutcome.SKIPPED;
        }

        if (!ownedPet.ownerUuid().equals(player.getUuid())) {
            return RecallOutcome.FAILED;
        }

        if (ownedPet.sitting()) {
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
        return RecallOutcome.RECALLED;
    }

    private void recallUnloadedPetAsync(ServerPlayerEntity player, ServerWorld targetWorld, MinecraftServer server, PetRecord record, RecallSummary summary, Consumer<RecallOutcome> done) {
        RecallOutcome loadedRaceOutcome = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
        if (loadedRaceOutcome != null) {
            done.accept(loadedRaceOutcome);
            return;
        }

        var sourceWorldKey = record.dimensionKey();
        if (sourceWorldKey == null) {
            summary.messages.add("Bad dimension id for pet " + record.petUuid() + ": " + record.dimensionId());
            this.tracker.removeRecord(server, record.petUuid());
            done.accept(RecallOutcome.FAILED);
            return;
        }

        ServerWorld sourceWorld = server.getWorld(sourceWorldKey);
        if (sourceWorld == null) {
            summary.messages.add("Source world missing for pet " + record.petUuid() + ": " + sourceWorldKey.getValue());
            done.accept(RecallOutcome.FAILED);
            return;
        }

        EntityChunkDataAccess dataAccess = getEntityChunkDataAccess(sourceWorld);
        VersionedChunkStorage storage = ((EntityChunkDataAccessAccessor) dataAccess).pet_recall$getStorage();
        ChunkPos chunkPos = record.chunkPos();
        storage.getNbt(chunkPos).whenComplete((chunkNbtOptional, throwable) -> {
            server.execute(() -> {
                if (throwable != null) {
                    PetRecallMod.LOGGER.warn("Failed reading entity chunk data for {} in {}", chunkPos, sourceWorldKey.getValue(), throwable);
                    summary.messages.add("Read failed for pet " + record.petUuid());
                    done.accept(RecallOutcome.FAILED);
                    return;
                }

                RecallOutcome loadedOutcome = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
                if (loadedOutcome != null) {
                    done.accept(loadedOutcome);
                    return;
                }

                this.finishUnloadedRecallAfterReadRaw(player, targetWorld, server, record, summary, storage, chunkPos, chunkNbtOptional, done);
            });
        });
    }

    private void finishUnloadedRecallAfterReadRaw(
            ServerPlayerEntity player,
            ServerWorld targetWorld,
            MinecraftServer server,
            PetRecord record,
            RecallSummary summary,
            VersionedChunkStorage storage,
            ChunkPos chunkPos,
            Optional<NbtCompound> chunkNbtOptional,
            Consumer<RecallOutcome> done
    ) {
        if (!canContinueRecallForPlayer(player)) {
            summary.messages.add("Recall cancelled while waiting for pet " + record.petUuid());
            done.accept(RecallOutcome.FAILED);
            return;
        }

        RecallOutcome loadedRaceOutcome = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
        if (loadedRaceOutcome != null) {
            done.accept(loadedRaceOutcome);
            return;
        }

        if (chunkNbtOptional.isEmpty()) {
            done.accept(this.handleMissingIndexedPetData(player, targetWorld, server, record, summary, "Pet chunk data missing for "));
            return;
        }

        NbtCompound originalChunkNbt = chunkNbtOptional.get();
        Optional<NbtList> entitiesListOptional = originalChunkNbt.getList("Entities");
        if (entitiesListOptional.isEmpty()) {
            done.accept(this.handleMissingIndexedPetData(player, targetWorld, server, record, summary, "Pet not found in indexed chunk " + chunkPos + ": "));
            return;
        }

        NbtList entitiesList = entitiesListOptional.get();
        int petIndex = -1;
        NbtCompound petEntryNbt = null;
        for (int i = 0; i < entitiesList.size(); i++) {
            Optional<NbtCompound> entryOptional = entitiesList.getCompound(i);
            if (entryOptional.isEmpty()) {
                continue;
            }

            NbtCompound entry = entryOptional.get();
            UUID entityUuid = getEntityUuidFromNbt(entry);
            if (entityUuid == null || !entityUuid.equals(record.petUuid())) {
                continue;
            }

            petIndex = i;
            petEntryNbt = entry.copy();
            break;
        }

        if (petEntryNbt == null) {
            done.accept(this.handleMissingIndexedPetData(player, targetWorld, server, record, summary, "Pet not found in indexed chunk " + chunkPos + ": "));
            return;
        }

        Entity recreated = EntityType.loadEntityWithPassengers(petEntryNbt.copy(), targetWorld, SpawnReason.COMMAND, e -> e);
        if (recreated == null) {
            summary.messages.add("Failed to recreate pet " + record.petUuid());
            done.accept(RecallOutcome.FAILED);
            return;
        }

        OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(recreated);
        if (ownedPet == null) {
            this.tracker.removeRecord(server, record.petUuid());
            summary.messages.add("Non-following tamed mob skipped " + record.petUuid());
            done.accept(RecallOutcome.SKIPPED);
            return;
        }

        if (!ownedPet.ownerUuid().equals(player.getUuid())) {
            summary.messages.add("Ownership mismatch for pet " + record.petUuid());
            done.accept(RecallOutcome.FAILED);
            return;
        }

        if (ownedPet.sitting()) {
            summary.messages.add("Sitting pet skipped " + record.petUuid());
            done.accept(RecallOutcome.SKIPPED);
            return;
        }

        SafeRecallSpot safeSpot = findSafeRecallPosition(player, targetWorld, recreated, summary);
        if (safeSpot == null) {
            summary.messages.add("No safe spot near player for pet " + record.petUuid());
            done.accept(RecallOutcome.FAILED);
            return;
        }

        RecallOutcome loadedOutcomeBeforeWrite = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
        if (loadedOutcomeBeforeWrite != null) {
            done.accept(loadedOutcomeBeforeWrite);
            return;
        }

        NbtCompound updatedChunkNbt = originalChunkNbt.copy();
        Optional<NbtList> updatedEntitiesOptional = updatedChunkNbt.getList("Entities");
        if (updatedEntitiesOptional.isEmpty()) {
            summary.messages.add("Chunk entities list missing during write for pet " + record.petUuid());
            done.accept(RecallOutcome.FAILED);
            return;
        }
        updatedEntitiesOptional.get().remove(petIndex);

        storage.setNbt(chunkPos, updatedChunkNbt).whenComplete((unused, writeThrowable) -> {
            server.execute(() -> {
                if (writeThrowable != null) {
                    PetRecallMod.LOGGER.warn("Failed writing raw entity chunk data after removing pet {}", record.petUuid(), writeThrowable);
                    summary.messages.add("Write failed for pet " + record.petUuid());
                    done.accept(RecallOutcome.FAILED);
                    return;
                }

                if (!canContinueRecallForPlayer(player)) {
                    summary.messages.add("Recall cancelled before spawning pet " + record.petUuid());
                    rollbackRawChunkWrite(storage, server, chunkPos, originalChunkNbt.copy(), record.petUuid(), () -> done.accept(RecallOutcome.FAILED));
                    return;
                }

                RecallOutcome loadedOutcomeAfterWrite = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
                if (loadedOutcomeAfterWrite != null) {
                    if (loadedOutcomeAfterWrite == RecallOutcome.RECALLED) {
                        done.accept(loadedOutcomeAfterWrite);
                    } else {
                        rollbackRawChunkWrite(storage, server, chunkPos, originalChunkNbt.copy(), record.petUuid(), () -> done.accept(loadedOutcomeAfterWrite));
                    }
                    return;
                }

                recreated.refreshPositionAndAngles(safeSpot.position(), recreated.getYaw(), recreated.getPitch());
                boolean spawned = targetWorld.spawnNewEntityAndPassengers(recreated);
                if (!spawned) {
                    RecallOutcome loadedOutcomeOnSpawnFail = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
                    if (loadedOutcomeOnSpawnFail != null) {
                        if (loadedOutcomeOnSpawnFail == RecallOutcome.RECALLED) {
                            done.accept(loadedOutcomeOnSpawnFail);
                        } else {
                            rollbackRawChunkWrite(storage, server, chunkPos, originalChunkNbt.copy(), record.petUuid(), () -> done.accept(loadedOutcomeOnSpawnFail));
                        }
                        return;
                    }

                    rollbackRawChunkWrite(storage, server, chunkPos, originalChunkNbt.copy(), record.petUuid(), () -> done.accept(RecallOutcome.FAILED));
                    summary.messages.add("Failed to spawn pet " + record.petUuid());
                    return;
                }

                reserveRecallSpot(summary, safeSpot);
                this.tracker.upsertRecordFromEntity(targetWorld, recreated);
                done.accept(RecallOutcome.RECALLED);
            });
        });
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

    private RecallOutcome handleMissingIndexedPetData(
            ServerPlayerEntity player,
            ServerWorld targetWorld,
            MinecraftServer server,
            PetRecord record,
            RecallSummary summary,
            String messagePrefix
    ) {
        RecallOutcome loadedRaceOutcome = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
        if (loadedRaceOutcome != null) {
            return loadedRaceOutcome;
        }

        this.tracker.rescanLoadedPetsForOwner(server, player.getUuid());
        RecallOutcome loadedAfterRescan = this.tryRecallLoadedIfPresent(player, targetWorld, record.petUuid(), summary);
        if (loadedAfterRescan != null) {
            return loadedAfterRescan;
        }

        summary.messages.add(messagePrefix + record.petUuid());
        return RecallOutcome.FAILED;
    }

    @Nullable
    private RecallOutcome tryRecallLoadedIfPresent(ServerPlayerEntity player, ServerWorld targetWorld, UUID petUuid, RecallSummary summary) {
        Entity loaded = this.tracker.getLoadedPet(petUuid);
        if (loaded == null) {
            return null;
        }
        return this.recallLoadedPet(player, targetWorld, loaded, summary);
    }

    @Nullable
    private static UUID getEntityUuidFromNbt(NbtCompound entityNbt) {
        return entityNbt.getIntArray("UUID").map(Uuids::toUuid).orElse(null);
    }

    private static void rollbackRawChunkWrite(VersionedChunkStorage storage, MinecraftServer server, ChunkPos chunkPos, NbtCompound originalChunkNbt, UUID petUuid, Runnable afterRollback) {
        storage.setNbt(chunkPos, originalChunkNbt).whenComplete((unused, rollbackThrowable) -> {
            server.execute(() -> {
                if (rollbackThrowable != null) {
                    PetRecallMod.LOGGER.error("Failed raw rollback for pet {} in chunk {}", petUuid, chunkPos, rollbackThrowable);
                }
                afterRollback.run();
            });
        });
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

        // Last fallback: directly under the player (requested), still must be a physically safe spot.
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
        // Check every block cell intersecting the destination hitbox to avoid teleporting into fluids/waterlogged blocks.
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

    private enum RecallOutcome {
        RECALLED,
        SKIPPED,
        FAILED
    }

    private record SafeRecallSpot(BlockPos blockPos, Vec3d position) {
    }
}
