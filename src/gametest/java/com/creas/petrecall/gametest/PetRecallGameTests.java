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
