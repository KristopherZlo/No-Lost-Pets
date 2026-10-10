package com.creas.petrecall.gametest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.util.VersionCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

/** A real client sends jump/movement packets; no ground flag or player gravity is faked. */
@SuppressWarnings("UnstableApiUsage")
public final class PetRecallClientGameTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        PetRecallMod.LOGGER.info("Starting NoLostPets client behavior tests");
        jumpAndLand(context, false);
        jumpAndLand(context, true);
        PetRecallMod.LOGGER.info("NoLostPets client behavior tests passed: 2 scenarios, 40 pets each");
    }

    private static void jumpAndLand(ClientGameTestContext context, boolean interruptLoading) {
        try (TestSingleplayerContext session = context.worldBuilder().create()) {
            State state = new State();
            session.getServer().runOnServer(server -> {
                state.world = server.getOverworld();
                for (int x = 960 >> 4; x <= 1000 >> 4; x++) for (int z = 960 >> 4; z <= 1000 >> 4; z++) {
                    state.world.setChunkForced(x, z, true);
                    state.world.getChunk(x, z);
                }
                state.floorY = state.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, 980, 980) - 1;
            });
            session.getServer().runCommand("gamemode survival @a");
            session.getServer().runCommand("fill 960 " + state.floorY + " 960 1000 " + state.floorY + " 1000 minecraft:stone");
            session.getServer().runCommand("fill 960 " + (state.floorY + 1) + " 960 1000 " + (state.floorY + 5) + " 1000 minecraft:air");
            session.getServer().runOnServer(server -> require(state.world.getBlockState(new BlockPos(980, state.floorY, 980)).isOf(Blocks.STONE),
                    "The destination floor must exist before teleporting the real player"));
            session.getServer().runCommand("tp @a 980.5 " + (state.floorY + 1) + " 980.5");
            context.waitFor(client -> client.player != null && client.player.isAlive() && client.player.isOnGround());
            session.getServer().runOnServer(server -> {
                state.world = server.getOverworld();
                state.owner = server.getPlayerManager().getPlayerList().getFirst();
                for (int x = 960 >> 4; x <= 1000 >> 4; x++) for (int z = 960 >> 4; z <= 1000 >> 4; z++) {
                    state.world.setChunkForced(x, z, false);
                }
                PetRecallMod.getAutoRecallController().suppressPlayer(state.owner.getUuid());
                for (int i = 0; i < 5; i++) {
                    ChunkPos chunk = new ChunkPos(15000 + i * 37, 15000 + i * 53);
                    state.chunks.add(chunk);
                    state.world.setChunkForced(chunk.x, chunk.z, true);
                    state.world.getChunk(chunk.x, chunk.z);
                    BlockPos pad = new BlockPos(chunk.getStartX() + 8, state.floorY + 2 + i * 5, chunk.getStartZ() + 8);
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                        state.world.setBlockState(pad.add(x, -1, z), Blocks.STONE.getDefaultState());
                        for (int y = 0; y <= 2; y++) state.world.setBlockState(pad.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                    for (int j = 0; j < 8; j++) {
                        WolfEntity pet = new WolfEntity(EntityType.WOLF, state.world);
                        pet.refreshPositionAndAngles(pad.getX() + j % 3 - 0.5D, pad.getY(), pad.getZ() + j / 3 - 0.5D, 0, 0);
                        pet.setTamed(true, true);
                        pet.setOwner(state.owner);
                        pet.setAiDisabled(true);
                        pet.setHealth(7.0F);
                        pet.setPersistent();
                        require(state.world.spawnEntity(pet), "Physical fixture must spawn");
                        PetRecallMod.getTracker().observe(pet, state.world);
                        state.pets.add(pet.getUuid());
                    }
                }
            });
            await(context, session, () -> state.chunks.stream().allMatch(c -> VersionCompat.areChunkEntitiesLoaded(state.world, c)), 200,
                    "Source chunks must load before release");
            session.getServer().runOnServer(server -> {
                for (ChunkPos chunk : state.chunks) state.world.setChunkForced(chunk.x, chunk.z, false);
            });
            await(context, session, () -> state.chunks.stream().noneMatch(c -> VersionCompat.areChunkEntitiesLoaded(state.world, c))
                    && state.pets.stream().allMatch(id -> state.world.getEntity(id) == null), 600, "All forty pets must genuinely unload");
            if (interruptLoading) {
                session.getServer().runOnServer(server -> {
                    for (ChunkPos chunk : state.chunks) RecallFaults.blockReadiness(state.world, chunk);
                    PetRecallMod.getAutoRecallController().resumePlayer(state.owner.getUuid());
                });
                await(context, session, () -> PetRecallMod.getRecallService().isRecallActive(state.owner.getUuid()), 80,
                        "Jump must interrupt an actual in-flight automatic batch");
            }
            context.getInput().holdKey(32);
            try {
                context.waitFor(client -> !client.player.isOnGround());
            } finally {
                context.getInput().releaseKey(32);
            }
            session.getServer().runOnServer(server -> {
                if (!interruptLoading) PetRecallMod.getAutoRecallController().resumePlayer(state.owner.getUuid());
                for (ChunkPos chunk : state.chunks) RecallFaults.clear(state.world, chunk);
            });
            context.waitTicks(2);
            session.getServer().runOnServer(server -> {
                require(!state.owner.isOnGround(), "Server must receive the real airborne movement state");
                require(state.near() == 0, "Pets cannot teleport while the actual client is airborne");
            });
            context.waitFor(client -> client.player.isOnGround());
            await(context, session, () -> state.near() == 40 && !PetRecallMod.getRecallService().isRecallActive(state.owner.getUuid()),
                    700, "One real landing must recover every pet without another input");
            session.getServer().runOnServer(server -> {
                for (UUID id : state.pets) ((WolfEntity) state.world.getEntity(id)).setAiDisabled(false);
            });
            double[] beforeWalking = context.computeOnClient(client -> new double[] {client.player.getX(), client.player.getZ()});
            context.getInput().holdKeyFor(87, 70);
            boolean walked = context.computeOnClient(client -> {
                double dx = client.player.getX() - beforeWalking[0];
                double dz = client.player.getZ() - beforeWalking[1];
                return dx * dx + dz * dz >= 100;
            });
            require(walked, "The real client must walk at least ten blocks so stationary pets cannot pass");
            context.waitTicks(100);
            session.getServer().runOnServer(server -> {
                require(state.near() == 40, "Live pets must remain with the moving real client");
                for (UUID id : state.pets) {
                    WolfEntity pet = (WolfEntity) state.world.getEntity(id);
                    require(pet.isAlive() && pet.getHealth() == 7.0F && !pet.isAiDisabled() && !pet.hasNoGravity(),
                            "Real AI and physics cannot kill or damage a recalled pet");
                    require(PetIndexState.get(server).getPet(id) != null, "Physical recall must preserve the index");
                }
            });
        }
    }

    private static void await(ClientGameTestContext context, TestSingleplayerContext session,
            java.util.function.BooleanSupplier condition, int timeout, String message) {
        for (int i = 0; i < timeout; i++) {
            if (session.getServer().computeOnServer(server -> condition.getAsBoolean())) return;
            context.waitTick();
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class State {
        ServerWorld world;
        ServerPlayerEntity owner;
        int floorY;
        final List<ChunkPos> chunks = new ArrayList<>();
        final List<UUID> pets = new ArrayList<>();
        int near() {
            int count = 0;
            for (UUID id : pets) {
                Entity entity = world.getEntity(id);
                if (entity != null && entity.squaredDistanceTo(owner) <= 144.0D) count++;
            }
            return count;
        }
    }
}
