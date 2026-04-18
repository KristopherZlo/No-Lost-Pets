package com.creas.petrecall.gametest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

public final class PetRecallGameTests {
    private static final BlockPos PLAYER_POS = new BlockPos(4, 2, 4);
    private static final BlockPos PET_POS = new BlockPos(1, 2, 1);
    private static final int PLATFORM_MIN = 0;
    private static final int PLATFORM_MAX = 7;
    private static final int PLATFORM_Y = 1;

    @GameTest(maxTicks = 80)
    public void loadedForceRecallTeleportsOwnedWolf(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayerEntity player = createGroundedPlayer(context, PLAYER_POS);
        WolfEntity wolf = context.spawnMob(EntityType.WOLF, PET_POS);
        wolf.setTamed(true, true);
        wolf.setOwner(player);
        wolf.setSitting(false);
        PetRecallMod.getTracker().observe(wolf, context.getWorld());

        double initialWolfX = wolf.getX();
        double initialWolfY = wolf.getY();
        double initialWolfZ = wolf.getZ();
        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();

        boolean started = PetRecallMod.getRecallService().recallAllForPlayerAsync(player, summaryRef::set);
        context.assertTrue(started, Text.literal("Expected loaded recall to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            Entity recalledPet = PetRecallMod.getTracker().getLoadedPet(wolf.getUuid());
            context.assertTrue(recalledPet != null, Text.literal("Expected wolf to stay tracked after recall"));
            context.assertEquals(1, summary.recalled, Text.literal("Expected one loaded pet to be recalled"));
            context.assertEquals(0, summary.failed, Text.literal("Expected loaded recall to finish without failures"));
            context.assertTrue(
                    squaredDistanceBetween(recalledPet, player) <= 64.0D,
                    Text.literal("Expected recalled wolf to end near the owner")
            );
            context.assertTrue(
                    squaredDistanceTo(recalledPet, initialWolfX, initialWolfY, initialWolfZ) > 1.0D,
                    Text.literal("Expected loaded recall to move the wolf")
            );

            cleanupIndexedPet(context.getWorld(), wolf.getUuid());
            context.killAllEntities();
            context.complete();
        });
    }

    @GameTest(maxTicks = 120)
    public void staleUnloadedRecordIsRemovedAfterThreeFailedRecalls(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerWorld world = context.getWorld();
        ServerPlayerEntity player = createGroundedPlayer(context, PLAYER_POS);
        UUID petUuid = UUID.randomUUID();
        PetRecord record = new PetRecord(
                petUuid,
                player.getUuid(),
                "minecraft:wolf",
                world.getRegistryKey().getValue().toString(),
                new ChunkPos(64, 64).toLong(),
                1024.5D,
                world.getBottomY() + 2.0D,
                1024.5D,
                false,
                20.0F
        );
        PetIndexState.get(world.getServer()).put(record);

        AtomicInteger completedAttempts = new AtomicInteger();
        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();

        boolean started = PetRecallMod.getRecallService().recallUnloadedForPlayerAsyncSilent(player, List.of(record), summaryRef::set);
        context.assertTrue(started, Text.literal("Expected first unloaded recall attempt to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.getAndSet(null);
            if (summary == null) {
                return;
            }

            int attempt = completedAttempts.incrementAndGet();
            context.assertEquals(1, summary.failed, Text.literal("Expected missing indexed pet attempt to fail"));

            if (attempt < 3) {
                context.assertTrue(
                        PetIndexState.get(world.getServer()).getPet(petUuid) != null,
                        Text.literal("Expected stale record to remain indexed before the third miss")
                );
                context.assertTrue(
                        PetRecallMod.getRecallService().isPetQuarantined(petUuid, world.getTime()),
                        Text.literal("Expected stale record to enter quarantine after a miss")
                );

                boolean nextStarted = PetRecallMod.getRecallService().recallUnloadedForPlayerAsyncSilent(player, List.of(record), summaryRef::set);
                context.assertTrue(nextStarted, Text.literal("Expected next unloaded recall attempt to start"));
                return;
            }

            context.assertTrue(
                    PetIndexState.get(world.getServer()).getPet(petUuid) == null,
                    Text.literal("Expected stale record to be removed after the third miss")
            );
            context.assertFalse(
                    PetRecallMod.getRecallService().isPetQuarantined(petUuid, world.getTime()),
                    Text.literal("Expected quarantine state to clear after stale record removal")
            );
            context.complete();
        });
    }

    @GameTest(maxTicks = 60)
    public void crossDimensionRecordIsSkipped(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerWorld world = context.getWorld();
        ServerPlayerEntity player = createGroundedPlayer(context, PLAYER_POS);
        PetRecord record = new PetRecord(
                UUID.randomUUID(),
                player.getUuid(),
                "minecraft:wolf",
                "minecraft:the_nether",
                new ChunkPos(0, 0).toLong(),
                0.5D,
                world.getBottomY() + 2.0D,
                0.5D,
                false,
                20.0F
        );

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Text.literal("Expected cross-dimension targeted recall to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertEquals(1, summary.skipped, Text.literal("Expected cross-dimension record to be skipped"));
            context.assertEquals(0, summary.recalled, Text.literal("Expected cross-dimension record to avoid recall"));
            context.assertEquals(0, summary.failed, Text.literal("Expected cross-dimension record to skip cleanly"));
            context.complete();
        });
    }

    @GameTest(maxTicks = 80)
    public void shortGrassCountsAsSafeRecallSpot(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayerEntity player = createGroundedPlayer(context, PLAYER_POS);
        BlockPos expectedSpot = PLAYER_POS.add(-1, 0, -1);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos ringPos = PLAYER_POS.add(x, 0, z);
                if (ringPos.equals(PLAYER_POS)) {
                    continue;
                }
                context.setBlockState(ringPos, ringPos.equals(expectedSpot) ? Blocks.SHORT_GRASS : Blocks.STONE);
            }
        }

        WolfEntity wolf = context.spawnMob(EntityType.WOLF, PET_POS);
        wolf.setTamed(true, true);
        wolf.setOwner(player);
        wolf.setSitting(false);
        PetRecallMod.getTracker().observe(wolf, context.getWorld());

        PetRecord record = PetIndexState.get(context.getWorld().getServer()).getPet(wolf.getUuid());
        context.assertTrue(record != null, Text.literal("Expected indexed record for grass scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Text.literal("Expected grass scenario recall to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            Entity recalled = PetRecallMod.getTracker().getLoadedPet(wolf.getUuid());
            context.assertTrue(recalled != null, Text.literal("Expected recalled wolf to stay loaded"));
            context.assertEquals(1, summary.recalled, Text.literal("Expected short-grass scenario to recall the wolf"));
            context.assertEquals(context.getAbsolutePos(expectedSpot), recalled.getBlockPos(), Text.literal("Expected wolf on short grass spot"));
            cleanupIndexedPet(context.getWorld(), wolf.getUuid());
            context.killAllEntities();
            context.complete();
        });
    }

    @GameTest(maxTicks = 80)
    public void loadedOwnershipMismatchDoesNotDeleteRecord(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayerEntity owner = createGroundedPlayer(context, PLAYER_POS);
        ServerPlayerEntity otherPlayer = createGroundedPlayer(context, PLAYER_POS.add(2, 0, 0));
        WolfEntity wolf = context.spawnMob(EntityType.WOLF, PET_POS);
        wolf.setTamed(true, true);
        wolf.setOwner(owner);
        wolf.setSitting(false);
        PetRecallMod.getTracker().observe(wolf, context.getWorld());

        PetRecord record = PetIndexState.get(context.getWorld().getServer()).getPet(wolf.getUuid());
        context.assertTrue(record != null, Text.literal("Expected indexed record for ownership scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(otherPlayer, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Text.literal("Expected ownership mismatch recall to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertEquals(1, summary.failed, Text.literal("Expected ownership mismatch to fail"));
            context.assertTrue(
                    PetIndexState.get(context.getWorld().getServer()).getPet(wolf.getUuid()) != null,
                    Text.literal("Expected ownership mismatch to keep the record indexed")
            );
            cleanupIndexedPet(context.getWorld(), wolf.getUuid());
            context.killAllEntities();
            context.complete();
        });
    }

    @SuppressWarnings("removal")
    private static ServerPlayerEntity createGroundedPlayer(TestContext context, BlockPos relativePos) {
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        BlockPos absolutePos = context.getAbsolutePos(relativePos);
        player.refreshPositionAndAngles(absolutePos, 0.0F, 0.0F);
        player.setOnGround(true);
        return player;
    }

    private static void buildPlatform(TestContext context) {
        for (int x = PLATFORM_MIN; x <= PLATFORM_MAX; x++) {
            for (int z = PLATFORM_MIN; z <= PLATFORM_MAX; z++) {
                context.setBlockState(new BlockPos(x, PLATFORM_Y, z), Blocks.STONE);
            }
        }
    }

    private static void cleanupIndexedPet(ServerWorld world, UUID petUuid) {
        PetRecallMod.getTracker().removeRecord(world.getServer(), petUuid);
    }

    private static double squaredDistanceBetween(Entity first, Entity second) {
        return squaredDistanceTo(first, second.getX(), second.getY(), second.getZ());
    }

    private static double squaredDistanceTo(Entity entity, double x, double y, double z) {
        double dx = entity.getX() - x;
        double dy = entity.getY() - y;
        double dz = entity.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
