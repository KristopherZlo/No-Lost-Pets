package com.creas.petrecall.gametest;

import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.VersionCompat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class PetRecallBehaviorGameTests {
    private static final AtomicInteger REMOTE_CHUNK_SEQUENCE = new AtomicInteger();
    @GameTest(maxTicks = 40)
    public void duplicateRecordsRecallOneEntityExactlyOnce(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        PetRecord record = f.record(wolf);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        boolean started = f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(record, record, record), true, summary -> {
            callbacks.incrementAndGet();
            result.set(summary);
        });
        check(context, started, "Recall must start");
        check(context, result.get() != null, "Loaded recall must complete");
        context.assertValueEqual(1, callbacks.get(), Component.literal("Completion must be called exactly once"));
        context.assertValueEqual(1, result.get().totalKnown, Component.literal("Duplicate UUIDs count as one pet"));
        context.assertValueEqual(1, result.get().recalled, Component.literal("Pet is recalled once"));
        context.assertValueEqual(1, result.get().attempted, Component.literal("Only one attempt"));
        check(context, f.world.getEntity(wolf.getUUID()) == wolf, "Same entity instance must survive");
        f.assertIdle();
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void throwingCompletionDoesNotKeepPlayerOrPetLocked(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true,
                summary -> { throw new IllegalStateException("Simulated disconnected command consumer"); }), "First recall starts");
        f.assertIdle();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set),
                "Second recall must be accepted after callback failure");
        context.assertValueEqual(1, result.get().recalled, Component.literal("Second recall must succeed"));
        f.assertIdle();
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void currentOwnerOverridesStaleRecordAndPetIsNotMoved(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        PetRecord stale = f.record(wolf);
        ServerPlayer newOwner = createPlayer(context, new BlockPos(6, 2, 6));
        wolf.setOwner(newOwner);
        Vec3 before = position(wolf);
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(stale), true, result::set);
        context.assertValueEqual(1, result.get().failed, Component.literal("Old owner cannot recall"));
        context.assertValueEqual(0, result.get().recalled, Component.literal("No successful recall"));
        context.assertValueEqual(before, position(wolf), Component.literal("Pet must not move"));
        PetRecord current = PetIndexState.get(f.world.getServer()).getPet(wolf.getUUID());
        check(context, current != null && current.ownerUuid().equals(newOwner.getUUID()), "New owner record must survive");
        f.assertIdle();
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void actualLoadedDimensionOverridesStaleIndexDimension(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        PetRecord current = f.record(wolf);
        PetRecord stale = new PetRecord(current.petUuid(), current.ownerUuid(), current.entityTypeId(),
                "minecraft:the_nether", current.chunkPosLong(), current.x(), current.y(), current.z(),
                current.sitting(), current.health());
        PetIndexState.get(f.world.getServer()).put(stale);
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(stale), true, result::set);
        context.assertValueEqual(1, result.get().recalled, Component.literal("Actual loaded pet in owner's world must be recalled"));
        context.assertValueEqual(0, result.get().skipped, Component.literal("Stale index cannot cause a cross-dimension skip"));
        context.assertValueEqual(f.world.dimension().identifier().toString(), f.record(wolf).dimensionId(),
                Component.literal("Index dimension must be repaired"));
        f.assertIdle();
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 40, structure = "pet_recall_gametest:empty_32")
    public void blockedDestinationKeepsOriginalPetAndItsState(GameTestHelper context) {
        Fixture f = new Fixture(context, new BlockPos(16, 2, 16));
        context.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        wolf.setHealth(7.0F);
        wolf.setCustomName(Component.literal("Original pet"));
        Vec3 before = position(wolf);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -1; y <= 2; y++) {
                    context.setBlock(new BlockPos(16 + x, 2 + y, 16 + z), Blocks.STONE);
                }
            }
        }
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set);
        context.assertValueEqual(1, result.get().failed, Component.literal("No safe destination must fail"));
        context.assertValueEqual(0, result.get().recalled, Component.literal("No success may be reported"));
        check(context, f.world.getEntity(wolf.getUUID()) == wolf && !wolf.isRemoved(), "Original pet must survive");
        context.assertValueEqual(before, position(wolf), Component.literal("Original position must survive"));
        context.assertValueEqual(7.0F, wolf.getHealth(), Component.literal("Health must survive"));
        context.assertValueEqual("Original pet", wolf.getCustomName().getString(), Component.literal("Name must survive"));
        check(context, PetIndexState.get(f.world.getServer()).getPet(wolf.getUUID()) != null, "Index must survive");
        f.assertIdle();
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void fractionalSourcePositionDoesNotChangeDestinationCollisionCheck(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        BlockPos source = wolf.blockPosition();
        wolf.snapTo(source.getX() + 0.95D, source.getY(), source.getZ() + 0.95D, 0, 0);
        BlockPos expected = new BlockPos(3, 2, 3);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = new BlockPos(4 + x, 2, 4 + z);
                if (!pos.equals(expected) && !pos.equals(new BlockPos(4, 2, 4))) {
                    context.setBlock(pos, Blocks.STONE);
                }
            }
        }
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set);
        context.assertValueEqual(1, result.get().recalled, Component.literal("Centered destination is safe"));
        context.assertValueEqual(context.absolutePos(expected), wolf.blockPosition(), Component.literal("Must select first safe spot"));
        check(context, f.world.noCollision(wolf, wolf.getBoundingBox()), "Actual destination box must be collision-free");
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void ridingPetIsSkippedWithoutBreakingVehicleRelationship(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        Pig vehicle = context.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 2, 1));
        check(context, wolf.startRiding(vehicle, true, true), "Fixture must establish riding relationship");
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set);
        context.assertValueEqual(1, result.get().skipped, Component.literal("Riding pet must be skipped"));
        context.assertValueEqual(0, result.get().recalled, Component.literal("No recall may be reported"));
        check(context, wolf.getVehicle() == vehicle && vehicle.hasPassenger(wolf), "Vehicle relationship must survive");
        f.assertIdle();
        f.cleanup(wolf, vehicle);
        context.succeed();
    }

    @GameTest(maxTicks = 800)
    public void unloadedPetAndNeighborSurviveRecallAndSecondDiskLoad(GameTestHelper context) {
        unloadedRoundTrip(context, false);
    }

    @GameTest(maxTicks = 40)
    public void cancelledSessionFinishesOnceKeepsIndexAndAllowsNewRecall(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        UUID missingUuid = UUID.randomUUID();
        PetRecord pending = new PetRecord(missingUuid, f.player.getUUID(), "minecraft:wolf",
                f.world.dimension().identifier().toString(), new ChunkPos(1000, 1000).toLong(),
                16000.5D, wolf.getY(), 16000.5D, false, 20.0F);
        PetIndexState.get(f.world.getServer()).put(pending);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(pending), true, summary -> {
            callbacks.incrementAndGet();
            result.set(summary);
        }), "Pending recall starts");
        check(context, f.service.isRecallActive(f.player.getUUID()), "Pending player is locked");
        check(context, !f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(pending), true, summary -> {
            throw new AssertionError("Rejected recall cannot invoke callback");
        }), "Duplicate pending recall must be rejected");
        f.service.clearRuntime();
        f.service.clearRuntime();
        for (int i = 0; i < 10; i++) f.service.onServerTick(f.world.getServer());
        context.assertValueEqual(1, callbacks.get(), Component.literal("Cancelled session completes exactly once"));
        context.assertValueEqual(1, result.get().failed, Component.literal("Cancellation is a failure"));
        context.assertValueEqual(0, result.get().recalled, Component.literal("Cancellation cannot claim success"));
        check(context, pending.equals(PetIndexState.get(f.world.getServer()).getPet(missingUuid)), "Cancellation keeps index");
        f.assertIdle();
        result.set(null);
        check(context, f.service.recallSpecificPetsForPlayerAsync(f.player, List.of(f.record(wolf)), true, result::set),
                "New session can recall the same player's pet");
        context.assertValueEqual(1, result.get().recalled, Component.literal("New session succeeds"));
        f.assertIdle();
        f.tracker.removeRecord(f.world.getServer(), missingUuid);
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 40)
    public void destroyedPetIsRemovedFromIndexButChunkUnloadKeepsRecord(GameTestHelper context) {
        Fixture f = new Fixture(context);
        Wolf wolf = f.wolf(new BlockPos(1, 2, 1));
        UUID uuid = wolf.getUUID();
        // A normal unload is not destruction and must keep the persistent index.
        f.tracker.onEntityUnload(wolf, f.world);
        check(context, PetIndexState.get(f.world.getServer()).getPet(uuid) != null, "Normal unload keeps index");
        f.tracker.observe(wolf, f.world);
        wolf.discard();
        f.tracker.onEntityUnload(wolf, f.world);
        check(context, PetIndexState.get(f.world.getServer()).getPet(uuid) == null, "Destroyed pet cannot remain indexed");
        check(context, f.tracker.getLoadedPet(uuid) == null, "Destroyed pet cannot remain cached");
        f.cleanup(wolf);
        context.succeed();
    }

    @GameTest(maxTicks = 800)
    public void unloadedSittingPetAndNeighborStayAtSourceAfterSecondDiskLoad(GameTestHelper context) {
        unloadedRoundTrip(context, true);
    }

    private static void unloadedRoundTrip(GameTestHelper context, boolean sitting) {
        Fixture f = new Fixture(context);
        ChunkPos chunk = new ChunkPos(4096 + REMOTE_CHUNK_SEQUENCE.getAndIncrement() * 4, 4096);
        BlockPos remote = new BlockPos(chunk.getMinBlockX() + 8, f.player.blockPosition().getY(), chunk.getMinBlockZ() + 8);
        f.world.setChunkForced(chunk.x, chunk.z, true);
        f.world.getChunk(chunk.x, chunk.z);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                f.world.setBlockAndUpdate(remote.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                f.world.setBlockAndUpdate(remote.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                f.world.setBlockAndUpdate(remote.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            }
        }
        AtomicInteger phase = new AtomicInteger();
        AtomicReference<Wolf> original = new AtomicReference<>();
        AtomicReference<Pig> neighbor = new AtomicReference<>();
        AtomicReference<PetRecord> record = new AtomicReference<>();
        AtomicReference<RecallSummary> result = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        context.failIfEver(() -> {
            try {
                f.service.onServerTick(f.world.getServer());
                if (phase.get() == 0) {
                    if (!VersionCompat.areChunkEntitiesLoaded(f.world, chunk)) return;
                    Wolf wolf = EntityType.WOLF.create(f.world, EntitySpawnReason.COMMAND);
                    Pig pig = EntityType.PIG.create(f.world, EntitySpawnReason.COMMAND);
                    check(context, wolf != null && pig != null, "Entities must be created");
                    wolf.snapTo(remote.getX() + 0.5D, remote.getY(), remote.getZ() + 0.5D, 0, 0);
                    pig.snapTo(remote.getX() + 1.5D, remote.getY(), remote.getZ() + 0.5D, 0, 0);
                    wolf.setTame(true, true);
                    wolf.setOwner(f.player);
                    wolf.setOrderedToSit(sitting);
                    wolf.setNoAi(true);
                    wolf.setPersistenceRequired();
                    wolf.setHealth(7.0F);
                    f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BLUE_DYE));
                    wolf.mobInteract(f.player, InteractionHand.MAIN_HAND);
                    context.assertValueEqual(DyeColor.BLUE, wolf.getCollarColor(), Component.literal("Fixture collar must be blue"));
                    wolf.setCustomName(Component.literal("Round-trip wolf"));
                    wolf.addTag("pet_recall_state_marker");
                    pig.setNoAi(true);
                    pig.setPersistenceRequired();
                    pig.setHealth(5.0F);
                    pig.addTag("neighbor_marker");
                    check(context, f.world.addFreshEntity(wolf) && f.world.addFreshEntity(pig), "Both entities must spawn");
                    f.tracker.observe(wolf, f.world);
                    original.set(wolf);
                    neighbor.set(pig);
                    record.set(f.record(wolf));
                    f.world.setChunkForced(chunk.x, chunk.z, false);
                    phase.set(1);
                    return;
                }
                UUID uuid = original.get().getUUID();
                UUID neighborUuid = neighbor.get().getUUID();
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
                    context.assertValueEqual(1, completions.get(), Component.literal("Exactly one completion"));
                    context.assertValueEqual(0, result.get().failed, Component.literal("First attempt must succeed without retries"));
                    context.assertValueEqual(sitting ? 0 : 1, result.get().recalled, Component.literal("Correct recall count"));
                    context.assertValueEqual(sitting ? 1 : 0, result.get().skipped, Component.literal("Correct skip count"));
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
                context.succeed();
            } catch (RuntimeException | AssertionError error) {
                f.world.setChunkForced(chunk.x, chunk.z, false);
                f.service.clearRuntime();
                throw error;
            }
        });
    }

    private static void assertWolf(GameTestHelper context, Fixture f, UUID uuid, BlockPos remote, boolean sitting) {
        Entity entity = f.world.getEntity(uuid);
        check(context, entity instanceof Wolf, "Wolf UUID must exist");
        Wolf wolf = (Wolf) entity;
        context.assertValueEqual(f.player.getUUID(), wolf.getOwnerReference().getUUID(), Component.literal("Owner survives"));
        context.assertValueEqual(7.0F, wolf.getHealth(), Component.literal("Health survives disk round trip"));
        context.assertValueEqual(DyeColor.BLUE, wolf.getCollarColor(), Component.literal("Collar survives"));
        context.assertValueEqual("Round-trip wolf", wolf.getCustomName().getString(), Component.literal("Name survives"));
        check(context, wolf.getTags().contains("pet_recall_state_marker"), "Unknown/custom state survives");
        check(context, wolf.isNoAi() && wolf.isOrderedToSit() == sitting, "AI and sitting state survive");
        if (sitting) {
            context.assertValueEqual(remote, wolf.blockPosition(), Component.literal("Sitting pet must stay at source"));
        } else {
            check(context, wolf.distanceToSqr(f.player) <= 64.0D, "Recalled pet stays near owner");
            check(context, !wolf.chunkPosition().equals(new ChunkPos(remote)), "Source file must not recreate recalled pet");
        }
        check(context, PetIndexState.get(f.world.getServer()).getPet(uuid) != null, "Pet remains indexed");
    }

    private static void assertNeighbor(GameTestHelper context, Fixture f, UUID uuid, BlockPos remote) {
        Entity entity = f.world.getEntity(uuid);
        check(context, entity instanceof Pig, "Neighbor must survive both disk reads");
        Pig pig = (Pig) entity;
        context.assertValueEqual(5.0F, pig.getHealth(), Component.literal("Neighbor health must not change"));
        context.assertValueEqual(remote.offset(1, 0, 0), pig.blockPosition(), Component.literal("Neighbor must not move"));
        check(context, pig.getTags().contains("neighbor_marker"), "Neighbor custom state must survive");
    }

    private static long countUuid(ServerLevel world, UUID uuid) {
        long count = 0;
        for (Entity entity : world.getAllEntities()) {
            if (entity.getUUID().equals(uuid)) count++;
        }
        return count;
    }

    private static Vec3 position(Entity entity) {
        return new Vec3(entity.getX(), entity.getY(), entity.getZ());
    }

    private static void check(GameTestHelper context, boolean value, String message) {
        context.assertTrue(value, Component.literal(message));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer createPlayer(GameTestHelper context, BlockPos pos) {
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        player.snapTo(context.absolutePos(pos), 0, 0);
        player.setOnGround(true);
        return player;
    }

    private static final class Fixture {
        final GameTestHelper context;
        final ServerLevel world;
        final PetTracker tracker = new PetTracker();
        final PetRecallService service = new PetRecallService(tracker);
        final ServerPlayer player;

        Fixture(GameTestHelper context) {
            this(context, new BlockPos(4, 2, 4));
        }

        Fixture(GameTestHelper context, BlockPos ownerPos) {
            this.context = context;
            this.world = context.getLevel();
            for (int x = ownerPos.getX() - 4; x <= ownerPos.getX() + 3; x++) {
                for (int z = ownerPos.getZ() - 4; z <= ownerPos.getZ() + 3; z++) {
                    context.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                }
            }
            this.player = createPlayer(context, ownerPos);
        }

        Wolf wolf(BlockPos pos) {
            Wolf wolf = context.spawnWithNoFreeWill(EntityType.WOLF, pos);
            wolf.setTame(true, true);
            wolf.setOwner(player);
            wolf.setOrderedToSit(false);
            wolf.setNoAi(true);
            tracker.observe(wolf, world);
            return wolf;
        }

        PetRecord record(Entity entity) {
            PetRecord record = PetIndexState.get(world.getServer()).getPet(entity.getUUID());
            check(context, record != null, "Pet must be indexed before recall");
            return record;
        }

        void assertIdle() {
            var stats = service.getDebugStats(world.getGameTime());
            context.assertValueEqual(0, stats.activePlayerRecalls(), Component.literal("Player locks must be released"));
            context.assertValueEqual(0, stats.activePetRecalls(), Component.literal("Pet locks must be released"));
            context.assertValueEqual(0, stats.activeChunkOperations(), Component.literal("Chunk tickets must be released"));
            context.assertValueEqual(0, stats.queuedChunkOperations(), Component.literal("Queue must be drained"));
        }

        void cleanup(Entity... entities) {
            service.clearRuntime();
            for (Entity entity : entities) {
                if (entity != null) {
                    tracker.removeRecord(world.getServer(), entity.getUUID());
                    entity.discard();
                }
            }
        }
    }
}
