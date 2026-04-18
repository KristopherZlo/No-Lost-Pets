package com.creas.petrecall.gametest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.util.VersionCompat;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

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

    @GameTest(maxTicks = 60)
    public void sittingLoadedPetIsSkipped(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayerEntity player = createGroundedPlayer(context, PLAYER_POS);
        WolfEntity wolf = context.spawnMob(EntityType.WOLF, PET_POS);
        wolf.setTamed(true, true);
        wolf.setOwner(player);
        wolf.setSitting(true);
        PetRecallMod.getTracker().observe(wolf, context.getWorld());

        PetRecord record = PetIndexState.get(context.getWorld().getServer()).getPet(wolf.getUuid());
        context.assertTrue(record != null, Text.literal("Expected indexed record for sitting scenario"));
        BlockPos originalPos = wolf.getBlockPos();

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Text.literal("Expected sitting recall attempt to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertEquals(1, summary.skipped, Text.literal("Expected sitting pet to be skipped"));
            context.assertEquals(0, summary.recalled, Text.literal("Expected sitting pet to avoid recall"));
            context.assertEquals(0, summary.failed, Text.literal("Expected sitting pet skip without failure"));
            context.assertEquals(originalPos, wolf.getBlockPos(), Text.literal("Expected sitting wolf to stay in place"));
            cleanupIndexedPet(context.getWorld(), wolf.getUuid());
            context.killAllEntities();
            context.complete();
        });
    }

    @GameTest(maxTicks = 60)
    public void airbornePlayerCannotStartRecall(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayerEntity player = createGroundedPlayer(context, PLAYER_POS.up(2));
        player.setOnGround(false);

        WolfEntity wolf = context.spawnMob(EntityType.WOLF, PET_POS);
        wolf.setTamed(true, true);
        wolf.setOwner(player);
        wolf.setSitting(false);
        PetRecallMod.getTracker().observe(wolf, context.getWorld());

        PetRecord record = PetIndexState.get(context.getWorld().getServer()).getPet(wolf.getUuid());
        context.assertTrue(record != null, Text.literal("Expected indexed record for airborne scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Text.literal("Expected airborne recall attempt to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertEquals(1, summary.failed, Text.literal("Expected airborne recall attempt to fail"));
            context.assertEquals(0, summary.recalled, Text.literal("Expected airborne recall attempt to avoid recall"));
            context.assertTrue(
                    summary.messages.stream().anyMatch(message -> message.contains("Stand on the ground")),
                    Text.literal("Expected airborne recall to explain the ground requirement")
            );
            cleanupIndexedPet(context.getWorld(), wolf.getUuid());
            context.killAllEntities();
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
    public void waterAndLeavesAreRejectedAsRecallSpots(TestContext context) {
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

                BlockPos floorPos = ringPos.down();
                if (ringPos.equals(expectedSpot)) {
                    context.setBlockState(floorPos, Blocks.STONE);
                    context.setBlockState(ringPos, Blocks.AIR);
                } else if (((x + z) & 1) == 0) {
                    context.setBlockState(floorPos, Blocks.OAK_LEAVES);
                    context.setBlockState(ringPos, Blocks.AIR);
                } else {
                    context.setBlockState(floorPos, Blocks.STONE);
                    context.setBlockState(ringPos, Blocks.WATER);
                }
            }
        }

        WolfEntity wolf = context.spawnMob(EntityType.WOLF, PET_POS);
        wolf.setTamed(true, true);
        wolf.setOwner(player);
        wolf.setSitting(false);
        PetRecallMod.getTracker().observe(wolf, context.getWorld());

        PetRecord record = PetIndexState.get(context.getWorld().getServer()).getPet(wolf.getUuid());
        context.assertTrue(record != null, Text.literal("Expected indexed record for unsafe-surface scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Text.literal("Expected unsafe-surface recall to start"));

        context.runAtEveryTick(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            Entity recalled = PetRecallMod.getTracker().getLoadedPet(wolf.getUuid());
            context.assertTrue(recalled != null, Text.literal("Expected recalled wolf to stay loaded"));
            context.assertEquals(1, summary.recalled, Text.literal("Expected unsafe-surface scenario to recall the wolf"));
            context.assertEquals(context.getAbsolutePos(expectedSpot), recalled.getBlockPos(), Text.literal("Expected wolf on safe stone spot"));
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

    @GameTest(maxTicks = 900)
    public void verifyCommandsPassSequentially(TestContext context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayerEntity player = createGroundedPlayer(context, PLAYER_POS);
        ServerPlayerEntity otherPlayer = createGroundedPlayer(context, PLAYER_POS.add(6, 0, 0));
        RecordingCommandOutput singleplayerOutput = new RecordingCommandOutput();
        RecordingCommandOutput multiplayerOutput = new RecordingCommandOutput();

        int singleplayerResult;
        try {
            singleplayerResult = executePlayerCommand(player, "petrecall verify singleplayer", singleplayerOutput);
        } catch (CommandSyntaxException e) {
            throw new AssertionError("verify singleplayer command should parse and execute", e);
        }

        context.assertEquals(1, singleplayerResult, Text.literal("Expected verify singleplayer command to return success"));

        AtomicInteger phase = new AtomicInteger(0);
        context.runAtEveryTick(() -> {
            if (phase.get() == 0) {
                if (singleplayerOutput.contains("Self-test failed:")) {
                    context.assertTrue(false, Text.literal("verify singleplayer failed: " + singleplayerOutput.lastMessage()));
                    return;
                }
                if (!singleplayerOutput.contains("Self-test passed:")) {
                    return;
                }

                context.assertFalse(PetRecallMod.getSelfTestService().hasActiveSuite(), Text.literal("Expected self-test suite to be idle after singleplayer success"));
                int multiplayerResult;
                try {
                    multiplayerResult = executePlayerCommand(player, "petrecall verify multiplayer " + playerSelectorFor(otherPlayer), multiplayerOutput);
                } catch (CommandSyntaxException e) {
                    throw new AssertionError("verify multiplayer command should parse and execute", e);
                }
                context.assertEquals(1, multiplayerResult, Text.literal("Expected verify multiplayer command to return success"));
                phase.set(1);
                return;
            }

            if (multiplayerOutput.contains("Self-test failed:")) {
                context.assertTrue(false, Text.literal("verify multiplayer failed: " + multiplayerOutput.lastMessage()));
                return;
            }
            if (multiplayerOutput.contains("Self-test passed:")) {
                context.assertFalse(PetRecallMod.getSelfTestService().hasActiveSuite(), Text.literal("Expected self-test suite to be idle after multiplayer success"));
                context.complete();
            }
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

    private static int executePlayerCommand(ServerPlayerEntity player, String command, RecordingCommandOutput output) throws CommandSyntaxException {
        MinecraftServer server = VersionCompat.getServer(player);
        ServerWorld world = VersionCompat.getServerWorld(player);
        if (server == null || world == null) {
            throw new IllegalStateException("Expected player to be attached to a server world");
        }
        ServerCommandSource source = server.getCommandSource()
                .withWorld(world)
                .withPosition(new Vec3d(player.getX(), player.getY(), player.getZ()))
                .withOutput(output);
        String wrappedCommand = "execute as " + playerSelectorFor(player) + " at @s run " + command;
        return server.getCommandManager().getDispatcher().execute(wrappedCommand, source);
    }

    private static String playerSelectorFor(ServerPlayerEntity player) {
        return String.format(
                Locale.ROOT,
                "@p[x=%.1f,y=%.1f,z=%.1f,distance=..1]",
                player.getX(),
                player.getY(),
                player.getZ()
        );
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

    private static final class RecordingCommandOutput implements CommandOutput {
        private final java.util.List<String> messages = new java.util.ArrayList<>();

        @Override
        public void sendMessage(Text text) {
            this.messages.add(text.getString());
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return true;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }

        private boolean contains(String fragment) {
            for (String message : this.messages) {
                if (message.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        private String lastMessage() {
            return this.messages.isEmpty() ? "<no messages>" : this.messages.get(this.messages.size() - 1);
        }
    }
}
