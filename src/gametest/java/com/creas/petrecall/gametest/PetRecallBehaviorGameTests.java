package com.creas.petrecall.gametest;

import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.VersionCompat;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.WriteView;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

public final class PetRecallBehaviorGameTests {
    private static final AtomicInteger REMOTE_CHUNK_SEQUENCE = new AtomicInteger();
    @GameTest(maxTicks = 40)
    public void duplicateRecordsRecallOneEntityExactlyOnce(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        PetRecord record = f.record(wolf);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        boolean started = f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(record, record, record), true, summary -> {
            callbacks.incrementAndGet();
            result.set(summary);
        });
        check(context, started, "Recall must start");
        check(context, result.get() != null, "Loaded recall must complete");
        context.assertEquals(1, callbacks.get(), Text.literal("Completion must be called exactly once"));
        context.assertEquals(1, result.get().totalKnown, Text.literal("Duplicate UUIDs count as one pet"));
        context.assertEquals(1, result.get().recalled, Text.literal("Pet is recalled once"));
        context.assertEquals(1, result.get().attempted, Text.literal("Only one attempt"));
        check(context, f.world.getEntity(wolf.getUuid()) == wolf, "Same entity instance must survive");
        f.assertIdle();
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void throwingCompletionDoesNotKeepPlayerOrPetLocked(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true,
                summary -> { throw new IllegalStateException("Simulated disconnected command consumer"); }), "First recall starts");
        f.assertIdle();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set),
                "Second recall must be accepted after callback failure");
        context.assertEquals(1, result.get().recalled, Text.literal("Second recall must succeed"));
        f.assertIdle();
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void currentOwnerOverridesStaleRecordAndPetIsNotMoved(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        PetRecord stale = f.record(wolf);
        ServerPlayerEntity newOwner = createPlayer(context, new BlockPos(6, 2, 6));
        wolf.setOwner(newOwner);
        Vec3d before = position(wolf);
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(stale), true, result::set);
        context.assertEquals(1, result.get().failed, Text.literal("Old owner cannot recall"));
        context.assertEquals(0, result.get().recalled, Text.literal("No successful recall"));
        context.assertEquals(before, position(wolf), Text.literal("Pet must not move"));
        PetRecord current = PetIndexState.get(f.world.getServer()).getPet(wolf.getUuid());
        check(context, current != null && current.ownerUuid().equals(newOwner.getUuid()), "New owner record must survive");
        f.assertIdle();
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void actualLoadedDimensionOverridesStaleIndexDimension(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        PetRecord current = f.record(wolf);
        PetRecord stale = new PetRecord(current.petUuid(), current.ownerUuid(), current.entityTypeId(),
                "minecraft:the_nether", current.chunkPosLong(), current.x(), current.y(), current.z(),
                current.sitting(), current.health());
        PetIndexState.get(f.world.getServer()).put(stale);
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(stale), true, result::set);
        context.assertEquals(1, result.get().recalled, Text.literal("Actual loaded pet in owner's world must be recalled"));
        context.assertEquals(0, result.get().skipped, Text.literal("Stale index cannot cause a cross-dimension skip"));
        context.assertEquals(f.world.getRegistryKey().getValue().toString(), f.record(wolf).dimensionId(),
                Text.literal("Index dimension must be repaired"));
        f.assertIdle();
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 40, structure = "pet_recall_gametest:empty_32")
    public void blockedDestinationKeepsOriginalPetAndItsState(TestContext context) {
        Fixture f = new Fixture(context, new BlockPos(16, 2, 16));
        context.setBlockState(new BlockPos(1, 1, 1), Blocks.STONE);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        wolf.setHealth(7.0F);
        wolf.setCustomName(Text.literal("Original pet"));
        Vec3d before = position(wolf);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -1; y <= 2; y++) {
                    context.setBlockState(new BlockPos(16 + x, 2 + y, 16 + z), Blocks.STONE);
                }
            }
        }
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set);
        context.assertEquals(1, result.get().failed, Text.literal("No safe destination must fail"));
        context.assertEquals(0, result.get().recalled, Text.literal("No success may be reported"));
        check(context, f.world.getEntity(wolf.getUuid()) == wolf && !wolf.isRemoved(), "Original pet must survive");
        context.assertEquals(before, position(wolf), Text.literal("Original position must survive"));
        context.assertEquals(7.0F, wolf.getHealth(), Text.literal("Health must survive"));
        context.assertEquals("Original pet", wolf.getCustomName().getString(), Text.literal("Name must survive"));
        check(context, PetIndexState.get(f.world.getServer()).getPet(wolf.getUuid()) != null, "Index must survive");
        f.assertIdle();
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void fractionalSourcePositionDoesNotChangeDestinationCollisionCheck(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        BlockPos source = wolf.getBlockPos();
        wolf.refreshPositionAndAngles(source.getX() + 0.95D, source.getY(), source.getZ() + 0.95D, 0, 0);
        BlockPos expected = new BlockPos(3, 2, 3);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = new BlockPos(4 + x, 2, 4 + z);
                if (!pos.equals(expected) && !pos.equals(new BlockPos(4, 2, 4))) {
                    context.setBlockState(pos, Blocks.STONE);
                }
            }
        }
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set);
        context.assertEquals(1, result.get().recalled, Text.literal("Centered destination is safe"));
        context.assertEquals(context.getAbsolutePos(expected), wolf.getBlockPos(), Text.literal("Must select first safe spot"));
        check(context, f.world.isSpaceEmpty(wolf, wolf.getBoundingBox()), "Actual destination box must be collision-free");
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void ridingPetIsSkippedWithoutBreakingVehicleRelationship(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        PigEntity vehicle = context.spawnMob(EntityType.PIG, new BlockPos(1, 2, 1));
        check(context, wolf.startRiding(vehicle), "Fixture must establish riding relationship");
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set);
        context.assertEquals(1, result.get().skipped, Text.literal("Riding pet must be skipped"));
        context.assertEquals(0, result.get().recalled, Text.literal("No recall may be reported"));
        check(context, wolf.getVehicle() == vehicle && vehicle.hasPassenger(wolf), "Vehicle relationship must survive");
        f.assertIdle();
        f.cleanup(wolf, vehicle);
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void failedOwnerEncodingKeepsModdedPetRecordAndDoesNotTeleport(TestContext context) {
        Fixture f = new Fixture(context);
        NbtCompanion pet = f.companion(new BlockPos(1, 2, 1));
        try {
            PetRecord before = f.record(pet);
            Vec3d originalPosition = position(pet);
            pet.failOwnerEncoding = true;
            f.tracker.observe(pet, f.world);
            context.assertEquals(before, f.record(pet), Text.literal("Encoding failure must keep the complete previous record"));
            check(context, f.tracker.getOwnerRecords(f.world.getServer(), f.player.getUuid()).contains(before),
                    "Encoding failure must keep the owner lookup");
            check(context, f.tracker.getLoadedPet(pet.getUuid()) == pet, "Encoding failure must keep the loaded entity");
            AtomicReference<RecallSummary> result = new AtomicReference<>();
            check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(before), true, result::set),
                    "Recall request must start");
            check(context, result.get() != null, "Loaded recall must complete despite encoding failure");
            context.assertEquals(1, result.get().failed, Text.literal("Unreadable ownership must fail safely"));
            context.assertEquals(0, result.get().recalled, Text.literal("Unreadable pet must not be recalled"));
            context.assertEquals(originalPosition, position(pet), Text.literal("Unreadable pet must not move"));
            context.assertEquals(before, f.record(pet), Text.literal("Recall failure must keep the previous record"));
            check(context, f.world.getEntity(pet.getUuid()) == pet && !pet.isRemoved(), "Original pet must survive");
            f.assertIdle();
        } finally {
            pet.failOwnerEncoding = false;
            f.cleanup(pet);
        }
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void ridingModdedCompanionKeepsOwnershipAndIsSkipped(TestContext context) {
        Fixture f = new Fixture(context);
        NbtCompanion pet = f.companion(new BlockPos(1, 2, 1));
        PigEntity vehicle = context.spawnMob(EntityType.PIG, new BlockPos(1, 2, 1));
        try {
            check(context, pet.startRiding(vehicle), "Fixture must establish riding relationship");
            f.tracker.observe(pet, f.world);
            PetRecord record = f.record(pet);
            context.assertEquals(f.player.getUuid(), record.ownerUuid(), Text.literal("Passenger ownership must remain readable"));
            check(context, f.tracker.getLoadedPet(pet.getUuid()) == pet, "Passenger must remain cached");
            Vec3d originalPosition = position(pet);
            AtomicReference<RecallSummary> result = new AtomicReference<>();
            check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(record), true, result::set),
                    "Recall request must start");
            check(context, result.get() != null, "Loaded recall must complete");
            context.assertEquals(1, result.get().skipped, Text.literal("Modded passenger must be skipped"));
            context.assertEquals(0, result.get().failed, Text.literal("Being a passenger is not an ownership error"));
            context.assertEquals(0, result.get().recalled, Text.literal("Modded passenger must not be recalled"));
            context.assertEquals(originalPosition, position(pet), Text.literal("Passenger must not move"));
            check(context, pet.getVehicle() == vehicle && vehicle.hasPassenger(pet), "Vehicle relationship must survive");
            context.assertEquals(record, f.record(pet), Text.literal("Passenger record must survive"));
            f.assertIdle();
        } finally {
            f.cleanup(pet, vehicle);
        }
        context.complete();
    }

    @GameTest(maxTicks = 800)
    public void unloadedPetAndNeighborSurviveRecallAndSecondDiskLoad(TestContext context) {
        unloadedRoundTrip(context, false);
    }

    @GameTest(maxTicks = 40)
    public void cancelledSessionFinishesOnceKeepsIndexAndAllowsNewRecall(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        UUID missingUuid = UUID.randomUUID();
        PetRecord pending = new PetRecord(missingUuid, f.player.getUuid(), "minecraft:wolf",
                f.world.getRegistryKey().getValue().toString(), new ChunkPos(1000, 1000).toLong(),
                16000.5D, wolf.getY(), 16000.5D, false, 20.0F);
        PetIndexState.get(f.world.getServer()).put(pending);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(pending), true, summary -> {
            callbacks.incrementAndGet();
            result.set(summary);
        }), "Pending recall starts");
        check(context, f.service.isRecallActive(f.player.getUuid()), "Pending player is locked");
        check(context, !f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(pending), true, summary -> {
            throw new AssertionError("Rejected recall cannot invoke callback");
        }), "Duplicate pending recall must be rejected");
        f.service.clearRuntime();
        f.service.clearRuntime();
        for (int i = 0; i < 10; i++) f.service.onServerTick(f.world.getServer());
        context.assertEquals(1, callbacks.get(), Text.literal("Cancelled session completes exactly once"));
        context.assertEquals(1, result.get().failed, Text.literal("Cancellation is a failure"));
        context.assertEquals(0, result.get().recalled, Text.literal("Cancellation cannot claim success"));
        check(context, pending.equals(PetIndexState.get(f.world.getServer()).getPet(missingUuid)), "Cancellation keeps index");
        f.assertIdle();
        result.set(null);
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set),
                "New session can recall the same player's pet");
        context.assertEquals(1, result.get().recalled, Text.literal("New session succeeds"));
        f.assertIdle();
        f.tracker.removeRecord(f.world.getServer(), missingUuid);
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 40)
    public void destroyedPetIsRemovedFromIndexButChunkUnloadKeepsRecord(TestContext context) {
        Fixture f = new Fixture(context);
        WolfEntity wolf = f.wolf(new BlockPos(1, 2, 1));
        UUID uuid = wolf.getUuid();
        // A normal unload is not destruction and must keep the persistent index.
        f.tracker.onEntityUnload(wolf, f.world);
        check(context, PetIndexState.get(f.world.getServer()).getPet(uuid) != null, "Normal unload keeps index");
        f.tracker.observe(wolf, f.world);
        wolf.discard();
        f.tracker.onEntityUnload(wolf, f.world);
        check(context, PetIndexState.get(f.world.getServer()).getPet(uuid) == null, "Destroyed pet cannot remain indexed");
        check(context, f.tracker.getLoadedPet(uuid) == null, "Destroyed pet cannot remain cached");
        f.cleanup(wolf);
        context.complete();
    }

    @GameTest(maxTicks = 800)
    public void unloadedSittingPetAndNeighborStayAtSourceAfterSecondDiskLoad(TestContext context) {
        unloadedRoundTrip(context, true);
    }

    private static void unloadedRoundTrip(TestContext context, boolean sitting) {
        Fixture f = new Fixture(context);
        ChunkPos chunk = new ChunkPos(4096 + REMOTE_CHUNK_SEQUENCE.getAndIncrement() * 4, 4096);
        BlockPos remote = new BlockPos(chunk.getStartX() + 8, f.player.getBlockPos().getY(), chunk.getStartZ() + 8);
        f.world.setChunkForced(chunk.x, chunk.z, true);
        f.world.getChunk(chunk.x, chunk.z);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                f.world.setBlockState(remote.add(x, -1, z), Blocks.STONE.getDefaultState());
                f.world.setBlockState(remote.add(x, 0, z), Blocks.AIR.getDefaultState());
                f.world.setBlockState(remote.add(x, 1, z), Blocks.AIR.getDefaultState());
            }
        }
        AtomicInteger phase = new AtomicInteger();
        AtomicReference<WolfEntity> original = new AtomicReference<>();
        AtomicReference<PigEntity> neighbor = new AtomicReference<>();
        AtomicReference<PetRecord> record = new AtomicReference<>();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        context.runAtEveryTick(() -> {
            try {
                f.service.onServerTick(f.world.getServer());
                if (phase.get() == 0) {
                    if (!VersionCompat.areChunkEntitiesLoaded(f.world, chunk)) return;
                    WolfEntity wolf = EntityType.WOLF.create(f.world, SpawnReason.COMMAND);
                    PigEntity pig = EntityType.PIG.create(f.world, SpawnReason.COMMAND);
                    check(context, wolf != null && pig != null, "Entities must be created");
                    wolf.refreshPositionAndAngles(remote.getX() + 0.5D, remote.getY(), remote.getZ() + 0.5D, 0, 0);
                    pig.refreshPositionAndAngles(remote.getX() + 1.5D, remote.getY(), remote.getZ() + 0.5D, 0, 0);
                    wolf.setTamed(true, true);
                    wolf.setOwner(f.player);
                    wolf.setSitting(sitting);
                    wolf.setAiDisabled(true);
                    wolf.setPersistent();
                    wolf.setHealth(7.0F);
                    f.player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.BLUE_DYE));
                    wolf.interactMob(f.player, Hand.MAIN_HAND);
                    context.assertEquals(DyeColor.BLUE, wolf.getCollarColor(), Text.literal("Fixture collar must be blue"));
                    wolf.setCustomName(Text.literal("Round-trip wolf"));
                    wolf.addCommandTag("pet_recall_state_marker");
                    pig.setAiDisabled(true);
                    pig.setPersistent();
                    pig.setHealth(5.0F);
                    pig.addCommandTag("neighbor_marker");
                    check(context, f.world.spawnEntity(wolf) && f.world.spawnEntity(pig), "Both entities must spawn");
                    f.tracker.observe(wolf, f.world);
                    original.set(wolf);
                    neighbor.set(pig);
                    record.set(f.record(wolf));
                    f.world.setChunkForced(chunk.x, chunk.z, false);
                    phase.set(1);
                    return;
                }
                UUID uuid = original.get().getUuid();
                UUID neighborUuid = neighbor.get().getUuid();
                if (phase.get() == 1) {
                    if (VersionCompat.areChunkEntitiesLoaded(f.world, chunk)) return;
                    check(context, f.world.getEntity(uuid) == null && f.world.getEntity(neighborUuid) == null,
                            "Both fixture entities must genuinely unload before recall");
                    check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(record.get()), true, summary -> {
                        completions.incrementAndGet();
                        result.set(summary);
                    }), "Unloaded recall must start");
                    check(context, !f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(record.get()), true, summary -> {
                        throw new AssertionError("Rejected duplicate request must not invoke its callback");
                    }), "Concurrent duplicate player request must be rejected");
                    phase.set(2);
                    return;
                }
                if (phase.get() == 2) {
                    if (result.get() == null) return;
                    context.assertEquals(1, completions.get(), Text.literal("Exactly one completion"));
                    context.assertEquals(0, result.get().failed, Text.literal("First attempt must succeed without retries"));
                    context.assertEquals(sitting ? 0 : 1, result.get().recalled, Text.literal("Correct recall count"));
                    context.assertEquals(sitting ? 1 : 0, result.get().skipped, Text.literal("Correct skip count"));
                    assertWolf(context, f, uuid, remote, sitting);
                    assertNeighbor(context, f, neighborUuid, remote);
                    f.assertIdle();
                    phase.set(3);
                    return;
                }
                if (phase.get() == 3) {
                    if (VersionCompat.areChunkEntitiesLoaded(f.world, chunk)) return;
                    check(context, f.world.getEntity(neighborUuid) == null, "Neighbor must unload before second disk read");
                    f.world.setChunkForced(chunk.x, chunk.z, true);
                    phase.set(4);
                    return;
                }
                if (!VersionCompat.areChunkEntitiesLoaded(f.world, chunk)) return;
                assertWolf(context, f, uuid, remote, sitting);
                assertNeighbor(context, f, neighborUuid, remote);
                check(context, countUuid(f.world, uuid) == 1, "A second disk read must not duplicate the pet UUID");
                check(context, countUuid(f.world, neighborUuid) == 1, "A second disk read must not duplicate neighbor UUID");
                Entity wolf = f.world.getEntity(uuid);
                Entity pig = f.world.getEntity(neighborUuid);
                f.world.setChunkForced(chunk.x, chunk.z, false);
                f.cleanup(wolf, pig);
                context.complete();
            } catch (RuntimeException | AssertionError error) {
                f.world.setChunkForced(chunk.x, chunk.z, false);
                f.service.clearRuntime();
                throw error;
            }
        });
    }

    private static void assertWolf(TestContext context, Fixture f, UUID uuid, BlockPos remote, boolean sitting) {
        Entity entity = f.world.getEntity(uuid);
        check(context, entity instanceof WolfEntity, "Wolf UUID must exist");
        WolfEntity wolf = (WolfEntity) entity;
        context.assertEquals(f.player.getUuid(), wolf.getOwnerReference().getUuid(), Text.literal("Owner survives"));
        context.assertEquals(7.0F, wolf.getHealth(), Text.literal("Health survives disk round trip"));
        context.assertEquals(DyeColor.BLUE, wolf.getCollarColor(), Text.literal("Collar survives"));
        context.assertEquals("Round-trip wolf", wolf.getCustomName().getString(), Text.literal("Name survives"));
        check(context, wolf.getCommandTags().contains("pet_recall_state_marker"), "Unknown/custom state survives");
        check(context, wolf.isAiDisabled() && wolf.isSitting() == sitting, "AI and sitting state survive");
        if (sitting) {
            context.assertEquals(remote, wolf.getBlockPos(), Text.literal("Sitting pet must stay at source"));
        } else {
            check(context, wolf.squaredDistanceTo(f.player) <= 64.0D, "Recalled pet stays near owner");
            check(context, !wolf.getChunkPos().equals(new ChunkPos(remote)), "Source file must not recreate recalled pet");
        }
        check(context, PetIndexState.get(f.world.getServer()).getPet(uuid) != null, "Pet remains indexed");
    }

    private static void assertNeighbor(TestContext context, Fixture f, UUID uuid, BlockPos remote) {
        Entity entity = f.world.getEntity(uuid);
        check(context, entity instanceof PigEntity, "Neighbor must survive both disk reads");
        PigEntity pig = (PigEntity) entity;
        context.assertEquals(5.0F, pig.getHealth(), Text.literal("Neighbor health must not change"));
        context.assertEquals(remote.add(1, 0, 0), pig.getBlockPos(), Text.literal("Neighbor must not move"));
        check(context, pig.getCommandTags().contains("neighbor_marker"), "Neighbor custom state must survive");
    }

    private static long countUuid(ServerWorld world, UUID uuid) {
        long count = 0;
        for (Entity entity : world.iterateEntities()) {
            if (entity.getUuid().equals(uuid)) count++;
        }
        return count;
    }

    private static Vec3d position(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    private static void check(TestContext context, boolean value, String message) {
        context.assertTrue(value, Text.literal(message));
    }

    @SuppressWarnings("removal")
    private static ServerPlayerEntity createPlayer(TestContext context, BlockPos pos) {
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(context.getAbsolutePos(pos), 0, 0);
        player.setOnGround(true);
        return player;
    }

    private static final class NbtCompanion extends PigEntity {
        private static final Codec<String> FAILING_OWNER_CODEC = Codec.STRING.validate(
                value -> DataResult.error(() -> "Injected owner encoding failure"));
        private final UUID ownerUuid;
        boolean failOwnerEncoding;

        NbtCompanion(ServerWorld world, UUID ownerUuid) {
            super(EntityType.PIG, world);
            this.ownerUuid = ownerUuid;
        }

        @Override
        protected void writeCustomData(WriteView output) {
            super.writeCustomData(output);
            if (failOwnerEncoding) {
                output.put("Owner", FAILING_OWNER_CODEC, ownerUuid.toString());
            } else {
                output.putString("Owner", ownerUuid.toString());
            }
            output.putBoolean("Sitting", false);
        }
    }

    private static final class Fixture {
        final TestContext context;
        final ServerWorld world;
        final PetTracker tracker = new PetTracker();
        final PetRecallService service = new PetRecallService(tracker);
        final ServerPlayerEntity player;

        Fixture(TestContext context) {
            this(context, new BlockPos(4, 2, 4));
        }

        Fixture(TestContext context, BlockPos ownerPos) {
            this.context = context;
            this.world = context.getWorld();
            for (int x = ownerPos.getX() - 4; x <= ownerPos.getX() + 3; x++) {
                for (int z = ownerPos.getZ() - 4; z <= ownerPos.getZ() + 3; z++) {
                    context.setBlockState(new BlockPos(x, 1, z), Blocks.STONE);
                }
            }
            this.player = createPlayer(context, ownerPos);
        }

        WolfEntity wolf(BlockPos pos) {
            WolfEntity wolf = context.spawnMob(EntityType.WOLF, pos);
            wolf.setTamed(true, true);
            wolf.setOwner(player);
            wolf.setSitting(false);
            wolf.setAiDisabled(true);
            tracker.observe(wolf, world);
            return wolf;
        }

        NbtCompanion companion(BlockPos pos) {
            NbtCompanion pet = new NbtCompanion(world, player.getUuid());
            pet.refreshPositionAndAngles(context.getAbsolutePos(pos), 0, 0);
            pet.setAiDisabled(true);
            pet.setPersistent();
            check(context, world.spawnEntity(pet), "Companion must be added to the world");
            tracker.observe(pet, world);
            return pet;
        }

        PetRecord record(Entity entity) {
            PetRecord record = PetIndexState.get(world.getServer()).getPet(entity.getUuid());
            check(context, record != null, "Pet must be indexed before recall");
            return record;
        }

        void assertIdle() {
            var stats = service.getDebugStats(world.getTime());
            context.assertEquals(0, stats.activePlayerRecalls(), Text.literal("Player locks must be released"));
            context.assertEquals(0, stats.activePetRecalls(), Text.literal("Pet locks must be released"));
            context.assertEquals(0, stats.activeChunkOperations(), Text.literal("Chunk tickets must be released"));
            context.assertEquals(0, stats.queuedChunkOperations(), Text.literal("Queue must be drained"));
        }

        void cleanup(Entity... entities) {
            service.clearRuntime();
            for (Entity entity : entities) {
                if (entity != null) {
                    tracker.removeRecord(world.getServer(), entity.getUuid());
                    entity.discard();
                }
            }
        }
    }
}
