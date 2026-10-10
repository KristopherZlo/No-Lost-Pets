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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** A real client sends jump/movement packets; no ground flag or player gravity is faked. */
@SuppressWarnings("UnstableApiUsage")
public final class PetRecallClientGameTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        jumpAndLand(context, false);
        jumpAndLand(context, true);
    }

    private static void jumpAndLand(ClientGameTestContext context, boolean interruptLoading) {
        try (TestSingleplayerContext session = context.worldBuilder().create()) {
            State state = new State();
            session.getServer().runCommand("gamemode survival @a");
            session.getServer().runCommand("fill 960 63 960 1000 63 1000 minecraft:stone");
            session.getServer().runCommand("fill 960 64 960 1000 68 1000 minecraft:air");
            session.getServer().runCommand("tp @a 980.5 64 980.5");
            context.waitFor(client -> client.player != null && client.player.onGround());
            session.getServer().runOnServer(server -> {
                state.world = server.overworld();
                state.owner = server.getPlayerList().getPlayers().getFirst();
                PetRecallMod.getAutoRecallController().suppressPlayer(state.owner.getUUID());
                for (int i = 0; i < 5; i++) {
                    ChunkPos chunk = new ChunkPos(15000 + i * 37, 15000 + i * 53);
                    state.chunks.add(chunk);
                    state.world.setChunkForced(chunk.x(), chunk.z(), true);
                    state.world.getChunk(chunk.x(), chunk.z());
                    BlockPos pad = new BlockPos(chunk.getMinBlockX() + 8, 65 + i * 5, chunk.getMinBlockZ() + 8);
                    for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                        state.world.setBlockAndUpdate(pad.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                        for (int y = 0; y <= 2; y++) state.world.setBlockAndUpdate(pad.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                    for (int j = 0; j < 8; j++) {
                        Wolf pet = new Wolf(com.creas.petrecall.selftest.TestEntityTypes.WOLF, state.world);
                        pet.snapTo(pad.getX() + j % 3 - 0.5D, pad.getY(), pad.getZ() + j / 3 - 0.5D, 0, 0);
                        pet.setTame(true, true);
                        pet.setOwner(state.owner);
                        pet.setNoAi(true);
                        pet.setHealth(7.0F);
                        pet.setPersistenceRequired();
                        require(state.world.addFreshEntity(pet), "Physical fixture must spawn");
                        PetRecallMod.getTracker().observe(pet, state.world);
                        state.pets.add(pet.getUUID());
                    }
                }
            });
            await(context, session, () -> state.chunks.stream().allMatch(c -> VersionCompat.areChunkEntitiesLoaded(state.world, c)), 200,
                    "Source chunks must load before release");
            session.getServer().runOnServer(server -> {
                for (ChunkPos chunk : state.chunks) state.world.setChunkForced(chunk.x(), chunk.z(), false);
            });
            await(context, session, () -> state.chunks.stream().noneMatch(c -> VersionCompat.areChunkEntitiesLoaded(state.world, c))
                    && state.pets.stream().allMatch(id -> state.world.getEntity(id) == null), 600, "All forty pets must genuinely unload");
            if (interruptLoading) {
                session.getServer().runOnServer(server -> {
                    for (ChunkPos chunk : state.chunks) RecallFaults.blockReadiness(state.world, chunk);
                    PetRecallMod.getAutoRecallController().resumePlayer(state.owner.getUUID());
                });
                await(context, session, () -> PetRecallMod.getRecallService().isRecallActive(state.owner.getUUID()), 80,
                        "Jump must interrupt an actual in-flight automatic batch");
            }
            context.getInput().pressKey(32);
            context.waitFor(client -> !client.player.onGround());
            session.getServer().runOnServer(server -> {
                if (!interruptLoading) PetRecallMod.getAutoRecallController().resumePlayer(state.owner.getUUID());
                for (ChunkPos chunk : state.chunks) RecallFaults.clear(state.world, chunk);
            });
            context.waitTicks(2);
            session.getServer().runOnServer(server -> {
                require(!state.owner.onGround(), "Server must receive the real airborne movement state");
                require(state.near() == 0, "Pets cannot teleport while the actual client is airborne");
            });
            context.waitFor(client -> client.player.onGround());
            await(context, session, () -> state.near() == 40 && !PetRecallMod.getRecallService().isRecallActive(state.owner.getUUID()),
                    700, "One real landing must recover every pet without another input");
            session.getServer().runOnServer(server -> {
                for (UUID id : state.pets) ((Wolf) state.world.getEntity(id)).setNoAi(false);
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
                    Wolf pet = (Wolf) state.world.getEntity(id);
                    require(pet.isAlive() && pet.getHealth() == 7.0F && !pet.isNoAi() && !pet.isNoGravity(),
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
        ServerLevel world;
        ServerPlayer owner;
        final List<ChunkPos> chunks = new ArrayList<>();
        final List<UUID> pets = new ArrayList<>();
        int near() {
            int count = 0;
            for (UUID id : pets) {
                Entity entity = world.getEntity(id);
                if (entity != null && entity.distanceToSqr(owner) <= 144.0D) count++;
            }
            return count;
        }
    }
}
