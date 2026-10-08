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
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class PetRecallGameTests {
    private static final BlockPos PLAYER_POS = new BlockPos(4, 2, 4);
    private static final BlockPos PET_POS = new BlockPos(1, 2, 1);
    private static final int PLATFORM_MIN = 0;
    private static final int PLATFORM_MAX = 7;
    private static final int PLATFORM_Y = 1;

    @GameTest(maxTicks = 80)
    public void loadedForceRecallTeleportsOwnedWolf(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS);
        Wolf wolf = context.spawnWithNoFreeWill(EntityType.WOLF, PET_POS);
        wolf.setTame(true, true);
        wolf.setOwner(player);
        wolf.setOrderedToSit(false);
        PetRecallMod.getTracker().observe(wolf, context.getLevel());

        double initialWolfX = wolf.getX();
        double initialWolfY = wolf.getY();
        double initialWolfZ = wolf.getZ();
        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();

        boolean started = PetRecallMod.getRecallService().recallAllForPlayerAsync(player, summaryRef::set);
        context.assertTrue(started, Component.literal("Expected loaded recall to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            Entity recalledPet = PetRecallMod.getTracker().getLoadedPet(wolf.getUUID());
            context.assertTrue(recalledPet != null, Component.literal("Expected wolf to stay tracked after recall"));
            context.assertValueEqual(1, summary.recalled, Component.literal("Expected one loaded pet to be recalled"));
            context.assertValueEqual(0, summary.failed, Component.literal("Expected loaded recall to finish without failures"));
            context.assertTrue(
                    squaredDistanceBetween(recalledPet, player) <= 64.0D,
                    Component.literal("Expected recalled wolf to end near the owner")
            );
            context.assertTrue(
                    squaredDistanceTo(recalledPet, initialWolfX, initialWolfY, initialWolfZ) > 1.0D,
                    Component.literal("Expected loaded recall to move the wolf")
            );

            cleanupIndexedPet(context.getLevel(), wolf.getUUID());
            context.killAllEntities();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 700)
    public void staleUnloadedRecordIsRemovedAfterThreeFailedRecalls(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerLevel world = context.getLevel();
        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS);
        UUID petUuid = UUID.randomUUID();
        PetRecord record = new PetRecord(
                petUuid,
                player.getUUID(),
                "minecraft:wolf",
                world.dimension().identifier().toString(),
                new ChunkPos(64, 64).pack(),
                1024.5D,
                world.getMinY() + 2.0D,
                1024.5D,
                false,
                20.0F
        );
        PetIndexState.get(world.getServer()).put(record);

        AtomicInteger completedAttempts = new AtomicInteger();
        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();

        boolean started = PetRecallMod.getRecallService().recallUnloadedForPlayerAsyncSilent(player, List.of(record), summaryRef::set);
        context.assertTrue(started, Component.literal("Expected first unloaded recall attempt to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.getAndSet(null);
            if (summary == null) {
                return;
            }

            int attempt = completedAttempts.incrementAndGet();
            context.assertValueEqual(1, summary.failed, Component.literal("Expected missing indexed pet attempt to fail"));

            if (attempt < 3) {
                context.assertTrue(
                        PetIndexState.get(world.getServer()).getPet(petUuid) != null,
                        Component.literal("Expected stale record to remain indexed before the third miss")
                );
                context.assertTrue(
                        PetRecallMod.getRecallService().isPetQuarantined(petUuid, world.getGameTime()),
                        Component.literal("Expected stale record to enter quarantine after a miss")
                );

                boolean nextStarted = PetRecallMod.getRecallService().recallUnloadedForPlayerAsyncSilent(player, List.of(record), summaryRef::set);
                context.assertTrue(nextStarted, Component.literal("Expected next unloaded recall attempt to start"));
                return;
            }

            context.assertTrue(
                    PetIndexState.get(world.getServer()).getPet(petUuid) == null,
                    Component.literal("Expected stale record to be removed after the third miss")
            );
            context.assertFalse(
                    PetRecallMod.getRecallService().isPetQuarantined(petUuid, world.getGameTime()),
                    Component.literal("Expected quarantine state to clear after stale record removal")
            );
            context.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void crossDimensionRecordIsSkipped(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerLevel world = context.getLevel();
        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS);
        PetRecord record = new PetRecord(
                UUID.randomUUID(),
                player.getUUID(),
                "minecraft:wolf",
                "minecraft:the_nether",
                new ChunkPos(0, 0).pack(),
                0.5D,
                world.getMinY() + 2.0D,
                0.5D,
                false,
                20.0F
        );

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Component.literal("Expected cross-dimension targeted recall to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertValueEqual(1, summary.skipped, Component.literal("Expected cross-dimension record to be skipped"));
            context.assertValueEqual(0, summary.recalled, Component.literal("Expected cross-dimension record to avoid recall"));
            context.assertValueEqual(0, summary.failed, Component.literal("Expected cross-dimension record to skip cleanly"));
            context.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void sittingLoadedPetIsSkipped(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS);
        Wolf wolf = context.spawnWithNoFreeWill(EntityType.WOLF, PET_POS);
        wolf.setTame(true, true);
        wolf.setOwner(player);
        wolf.setOrderedToSit(true);
        PetRecallMod.getTracker().observe(wolf, context.getLevel());

        PetRecord record = PetIndexState.get(context.getLevel().getServer()).getPet(wolf.getUUID());
        context.assertTrue(record != null, Component.literal("Expected indexed record for sitting scenario"));
        BlockPos originalPos = wolf.blockPosition();

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Component.literal("Expected sitting recall attempt to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertValueEqual(1, summary.skipped, Component.literal("Expected sitting pet to be skipped"));
            context.assertValueEqual(0, summary.recalled, Component.literal("Expected sitting pet to avoid recall"));
            context.assertValueEqual(0, summary.failed, Component.literal("Expected sitting pet skip without failure"));
            context.assertValueEqual(originalPos, wolf.blockPosition(), Component.literal("Expected sitting wolf to stay in place"));
            cleanupIndexedPet(context.getLevel(), wolf.getUUID());
            context.killAllEntities();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void airbornePlayerCannotStartRecall(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS.above(2));
        player.setOnGround(false);

        Wolf wolf = context.spawnWithNoFreeWill(EntityType.WOLF, PET_POS);
        wolf.setTame(true, true);
        wolf.setOwner(player);
        wolf.setOrderedToSit(false);
        PetRecallMod.getTracker().observe(wolf, context.getLevel());

        PetRecord record = PetIndexState.get(context.getLevel().getServer()).getPet(wolf.getUUID());
        context.assertTrue(record != null, Component.literal("Expected indexed record for airborne scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Component.literal("Expected airborne recall attempt to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertValueEqual(1, summary.failed, Component.literal("Expected airborne recall attempt to fail"));
            context.assertValueEqual(0, summary.recalled, Component.literal("Expected airborne recall attempt to avoid recall"));
            context.assertTrue(
                    summary.messages.stream().anyMatch(message -> message.contains("Stand on the ground")),
                    Component.literal("Expected airborne recall to explain the ground requirement")
            );
            cleanupIndexedPet(context.getLevel(), wolf.getUUID());
            context.killAllEntities();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void shortGrassCountsAsSafeRecallSpot(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS);
        BlockPos expectedSpot = PLAYER_POS.offset(-1, 0, -1);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos ringPos = PLAYER_POS.offset(x, 0, z);
                if (ringPos.equals(PLAYER_POS)) {
                    continue;
                }
                context.setBlock(ringPos, ringPos.equals(expectedSpot) ? Blocks.SHORT_GRASS : Blocks.STONE);
            }
        }

        Wolf wolf = context.spawnWithNoFreeWill(EntityType.WOLF, PET_POS);
        wolf.setTame(true, true);
        wolf.setOwner(player);
        wolf.setOrderedToSit(false);
        PetRecallMod.getTracker().observe(wolf, context.getLevel());

        PetRecord record = PetIndexState.get(context.getLevel().getServer()).getPet(wolf.getUUID());
        context.assertTrue(record != null, Component.literal("Expected indexed record for grass scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Component.literal("Expected grass scenario recall to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            Entity recalled = PetRecallMod.getTracker().getLoadedPet(wolf.getUUID());
            context.assertTrue(recalled != null, Component.literal("Expected recalled wolf to stay loaded"));
            context.assertValueEqual(1, summary.recalled, Component.literal("Expected short-grass scenario to recall the wolf"));
            context.assertValueEqual(context.absolutePos(expectedSpot), recalled.blockPosition(), Component.literal("Expected wolf on short grass spot"));
            cleanupIndexedPet(context.getLevel(), wolf.getUUID());
            context.killAllEntities();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void waterAndLeavesAreRejectedAsRecallSpots(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS);
        BlockPos expectedSpot = PLAYER_POS.offset(-1, 0, -1);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos ringPos = PLAYER_POS.offset(x, 0, z);
                if (ringPos.equals(PLAYER_POS)) {
                    continue;
                }

                BlockPos floorPos = ringPos.below();
                if (ringPos.equals(expectedSpot)) {
                    context.setBlock(floorPos, Blocks.STONE);
                    context.setBlock(ringPos, Blocks.AIR);
                } else if (((x + z) & 1) == 0) {
                    context.setBlock(floorPos, Blocks.OAK_LEAVES);
                    context.setBlock(ringPos, Blocks.AIR);
                } else {
                    context.setBlock(floorPos, Blocks.STONE);
                    context.setBlock(ringPos, Blocks.WATER);
                }
            }
        }

        Wolf wolf = context.spawnWithNoFreeWill(EntityType.WOLF, PET_POS);
        wolf.setTame(true, true);
        wolf.setOwner(player);
        wolf.setOrderedToSit(false);
        PetRecallMod.getTracker().observe(wolf, context.getLevel());

        PetRecord record = PetIndexState.get(context.getLevel().getServer()).getPet(wolf.getUUID());
        context.assertTrue(record != null, Component.literal("Expected indexed record for unsafe-surface scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Component.literal("Expected unsafe-surface recall to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            Entity recalled = PetRecallMod.getTracker().getLoadedPet(wolf.getUUID());
            context.assertTrue(recalled != null, Component.literal("Expected recalled wolf to stay loaded"));
            context.assertValueEqual(1, summary.recalled, Component.literal("Expected unsafe-surface scenario to recall the wolf"));
            context.assertValueEqual(context.absolutePos(expectedSpot), recalled.blockPosition(), Component.literal("Expected wolf on safe stone spot"));
            cleanupIndexedPet(context.getLevel(), wolf.getUUID());
            context.killAllEntities();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void loadedOwnershipMismatchDoesNotDeleteRecord(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayer owner = createGroundedPlayer(context, PLAYER_POS);
        ServerPlayer otherPlayer = createGroundedPlayer(context, PLAYER_POS.offset(2, 0, 0));
        Wolf wolf = context.spawnWithNoFreeWill(EntityType.WOLF, PET_POS);
        wolf.setTame(true, true);
        wolf.setOwner(owner);
        wolf.setOrderedToSit(false);
        PetRecallMod.getTracker().observe(wolf, context.getLevel());

        PetRecord record = PetIndexState.get(context.getLevel().getServer()).getPet(wolf.getUUID());
        context.assertTrue(record != null, Component.literal("Expected indexed record for ownership scenario"));

        AtomicReference<RecallSummary> summaryRef = new AtomicReference<>();
        boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(otherPlayer, List.of(record), true, summaryRef::set);
        context.assertTrue(started, Component.literal("Expected ownership mismatch recall to start"));

        context.failIfEver(() -> {
            RecallSummary summary = summaryRef.get();
            if (summary == null) {
                return;
            }

            context.assertValueEqual(1, summary.failed, Component.literal("Expected ownership mismatch to fail"));
            context.assertTrue(
                    PetIndexState.get(context.getLevel().getServer()).getPet(wolf.getUUID()) != null,
                    Component.literal("Expected ownership mismatch to keep the record indexed")
            );
            cleanupIndexedPet(context.getLevel(), wolf.getUUID());
            context.killAllEntities();
            context.succeed();
        });
    }

    @GameTest(maxTicks = 900)
    public void verifyCommandsPassSequentially(GameTestHelper context) {
        PetRecallMod.getTracker().clearRuntime();
        buildPlatform(context);

        ServerPlayer player = createGroundedPlayer(context, PLAYER_POS);
        ServerPlayer otherPlayer = createGroundedPlayer(context, PLAYER_POS.offset(6, 0, 0));
        RecordingCommandOutput singleplayerOutput = new RecordingCommandOutput();
        RecordingCommandOutput multiplayerOutput = new RecordingCommandOutput();

        int singleplayerResult;
        try {
            singleplayerResult = executePlayerCommand(player, "petrecall verify singleplayer", singleplayerOutput);
        } catch (CommandSyntaxException e) {
            throw new AssertionError("verify singleplayer command should parse and execute", e);
        }

        context.assertValueEqual(1, singleplayerResult, Component.literal("Expected verify singleplayer command to return success"));

        AtomicInteger phase = new AtomicInteger(0);
        context.failIfEver(() -> {
            if (phase.get() == 0) {
                if (singleplayerOutput.contains("Self-test failed:")) {
                    context.assertTrue(false, Component.literal("verify singleplayer failed: " + singleplayerOutput.lastMessage()));
                    return;
                }
                if (!singleplayerOutput.contains("Self-test passed:")) {
                    return;
                }

                context.assertFalse(PetRecallMod.getSelfTestService().hasActiveSuite(), Component.literal("Expected self-test suite to be idle after singleplayer success"));
                int multiplayerResult;
                try {
                    multiplayerResult = executePlayerCommand(player, "petrecall verify multiplayer " + playerSelectorFor(otherPlayer), multiplayerOutput);
                } catch (CommandSyntaxException e) {
                    throw new AssertionError("verify multiplayer command should parse and execute", e);
                }
                context.assertValueEqual(1, multiplayerResult, Component.literal("Expected verify multiplayer command to return success"));
                phase.set(1);
                return;
            }

            if (multiplayerOutput.contains("Self-test failed:")) {
                context.assertTrue(false, Component.literal("verify multiplayer failed: " + multiplayerOutput.lastMessage()));
                return;
            }
            if (multiplayerOutput.contains("Self-test passed:")) {
                context.assertFalse(PetRecallMod.getSelfTestService().hasActiveSuite(), Component.literal("Expected self-test suite to be idle after multiplayer success"));
                context.succeed();
            }
        });
    }

    @SuppressWarnings("removal")
    private static ServerPlayer createGroundedPlayer(GameTestHelper context, BlockPos relativePos) {
        ServerPlayer player = context.makeMockServerPlayerInLevel();
        BlockPos absolutePos = context.absolutePos(relativePos);
        player.snapTo(absolutePos, 0.0F, 0.0F);
        player.setOnGround(true);
        return player;
    }

    private static void buildPlatform(GameTestHelper context) {
        for (int x = PLATFORM_MIN; x <= PLATFORM_MAX; x++) {
            for (int z = PLATFORM_MIN; z <= PLATFORM_MAX; z++) {
                context.setBlock(new BlockPos(x, PLATFORM_Y, z), Blocks.STONE);
            }
        }
    }

    private static void cleanupIndexedPet(ServerLevel world, UUID petUuid) {
        PetRecallMod.getTracker().removeRecord(world.getServer(), petUuid);
    }

    private static int executePlayerCommand(ServerPlayer player, String command, RecordingCommandOutput output) throws CommandSyntaxException {
        MinecraftServer server = VersionCompat.getServer(player);
        ServerLevel world = VersionCompat.getServerWorld(player);
        if (server == null || world == null) {
            throw new IllegalStateException("Expected player to be attached to a server world");
        }
        CommandSourceStack source = server.createCommandSourceStack()
                .withLevel(world)
                .withPosition(new Vec3(player.getX(), player.getY(), player.getZ()))
                .withSource(output);
        String wrappedCommand = "execute as " + playerSelectorFor(player) + " at @s run " + command;
        return server.getCommands().getDispatcher().execute(wrappedCommand, source);
    }

    private static String playerSelectorFor(ServerPlayer player) {
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

    private static final class RecordingCommandOutput implements CommandSource {
        private final java.util.List<String> messages = new java.util.ArrayList<>();

        @Override
        public void sendSystemMessage(Component text) {
            this.messages.add(text.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
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
