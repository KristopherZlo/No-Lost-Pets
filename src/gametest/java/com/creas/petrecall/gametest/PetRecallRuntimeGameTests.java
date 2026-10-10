package com.creas.petrecall.gametest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.runtime.AutoPetRecallController;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.VersionCompat;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

/** Uses the production event listeners, controller and shared service; never ticks private copies. */
public final class PetRecallRuntimeGameTests {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private enum Case {
        NORMAL, BEFORE_UNLOAD, LATE_GROUP, LOGOUT_LOADING, LOGOUT_BETWEEN, DEATH_LOADING, DEATH_BETWEEN,
        PORTAL_LOADING, PORTAL_BETWEEN, BLOCKED_DESTINATION, TIMEOUT, ACQUIRE_FAILURE,
        CUSTOM_DIMENSION, MOVING_DESTINATION, MULTIPLAYER, MANUAL_CONCURRENT, LIVE_AI, MIXED_SPECIES, TERRAIN, STRESS
    }

    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/movement_before_source_unload_still_recalls_without_another_trigger")
    public void movementBeforeSourceUnloadStillRecallsWithoutAnotherTrigger(TestContext c) { run(c, Case.BEFORE_UNLOAD); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/pets_unloading_after_the_first_batch_join_the_same_recall_burst")
    public void petsUnloadingAfterTheFirstBatchJoinTheSameRecallBurst(TestContext c) { run(c, Case.LATE_GROUP); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/logout_while_chunk_loads_then_rejoin_recovers_every_pet")
    public void logoutWhileChunkLoadsThenRejoinRecoversEveryPet(TestContext c) { run(c, Case.LOGOUT_LOADING); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/logout_between_batches_then_rejoin_recovers_every_pet")
    public void logoutBetweenBatchesThenRejoinRecoversEveryPet(TestContext c) { run(c, Case.LOGOUT_BETWEEN); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/death_while_chunk_loads_then_respawn_recovers_every_pet")
    public void deathWhileChunkLoadsThenRespawnRecoversEveryPet(TestContext c) { run(c, Case.DEATH_LOADING); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/death_between_batches_then_respawn_recovers_every_pet")
    public void deathBetweenBatchesThenRespawnRecoversEveryPet(TestContext c) { run(c, Case.DEATH_BETWEEN); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/portal_while_chunk_loads_never_transfers_pets_across_dimensions")
    public void portalWhileChunkLoadsNeverTransfersPetsAcrossDimensions(TestContext c) { run(c, Case.PORTAL_LOADING); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/portal_between_batches_preserves_the_remaining_queue")
    public void portalBetweenBatchesPreservesTheRemainingQueue(TestContext c) { run(c, Case.PORTAL_BETWEEN); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/temporarily_blocked_destination_retries_the_same_pets_without_movement")
    public void temporarilyBlockedDestinationRetriesTheSamePetsWithoutMovement(TestContext c) { run(c, Case.BLOCKED_DESTINATION); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/chunk_timeout_preserves_and_automatically_retries_the_same_living_pets")
    public void chunkTimeoutPreservesAndAutomaticallyRetriesTheSameLivingPets(TestContext c) { run(c, Case.TIMEOUT); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/transient_acquisition_failure_does_not_strand_the_affected_pets")
    public void transientAcquisitionFailureDoesNotStrandTheAffectedPets(TestContext c) { run(c, Case.ACQUIRE_FAILURE); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/owner_moving_during_loading_uses_the_current_destination")
    public void ownerMovingDuringLoadingUsesTheCurrentDestination(TestContext c) { run(c, Case.MOVING_DESTINATION); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/two_owners_and_concurrent_manual_recall_share_the_production_scheduler")
    public void twoOwnersAndConcurrentManualRecallShareTheProductionScheduler(TestContext c) { run(c, Case.MULTIPLAYER); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/automatic_and_manual_owners_load_shared_chunks_concurrently")
    public void automaticAndManualOwnersLoadSharedChunksConcurrently(TestContext c) { run(c, Case.MANUAL_CONCURRENT); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/recalled_pets_with_live_ai_and_gravity_remain_alive_and_follow_their_owner")
    public void recalledPetsWithLiveAiAndGravityRemainAliveAndFollowTheirOwner(TestContext c) { run(c, Case.LIVE_AI); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/mixed_cats_wolves_parrots_and_babies_keep_their_state")
    public void mixedCatsWolvesParrotsAndBabiesKeepTheirState(TestContext c) { run(c, Case.MIXED_SPECIES); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/gravity_and_irregular_terrain_cannot_drop_pets_into_hazards")
    public void gravityAndIrregularTerrainCannotDropPetsIntoHazards(TestContext c) { run(c, Case.TERRAIN); }

    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/real_datapack_dimension_supports_recall_and_source_reload")
    public void realDatapackDimensionSupportsRecallAndSourceReload(TestContext c) { run(c, Case.CUSTOM_DIMENSION); }

    static void runNormal(TestContext c) { run(c, Case.NORMAL); }
    static void runStress(TestContext c) { run(c, Case.STRESS); }

    private static void run(TestContext c, Case scenario) {
        Fixture fixture = new Fixture(c, scenario);
        c.runAtEveryTick(fixture::tick);
    }

    private static void check(TestContext c, boolean condition, String message) {
        c.assertTrue(condition, Text.literal(message));
    }

    private static final class Fixture {
        final TestContext context;
        final Case scenario;
        final ServerWorld world;
        final PetTracker tracker = PetRecallMod.getTracker();
        final PetRecallService service = PetRecallMod.getRecallService();
        final AutoPetRecallController auto = PetRecallMod.getAutoRecallController();
        final BlockPos destination;
        final List<ChunkPos> sources = new ArrayList<>();
        final List<BlockPos> pads = new ArrayList<>();
        final List<ChunkPos> destinationChunks = new ArrayList<>();
        final List<UUID> recalled = new ArrayList<>();
        final List<UUID> controls = new ArrayList<>();
        final Map<UUID, PetRecord> before = new HashMap<>();
        final Map<UUID, Integer> petNumbers = new HashMap<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();
        ServerPlayerEntity player;
        ServerPlayerEntity other;
        ServerWorld portalWorld;
        int phase;
        int ticks;
        int elapsed;
        int settled;
        int rounds;
        int recoveryAt = -1;
        int nearAtInterrupt;
        boolean interrupted;
        boolean manualStarted;

        Fixture(TestContext context, Case scenario) {
            this.context = context;
            this.scenario = scenario;
            this.world = scenario == Case.CUSTOM_DIMENSION ? context.getWorld().getServer().getWorld(
                    RegistryKey.of(RegistryKeys.WORLD, Identifier.of("pet_recall_gametest", "companions"))) : context.getWorld();
            check(context, world != null, "The actual datapack dimension must load into the server registry");
            this.destination = context.getAbsolutePos(new BlockPos(16, 2, 16));
            floor(false);
            player = context.createMockCreativeServerPlayerInWorld();
            other = context.createMockCreativeServerPlayerInWorld();
            auto.suppressPlayer(player.getUuid());
            auto.suppressPlayer(other.getUuid());
            RecallBatchProbe.begin(player.getUuid());
            RecallBatchProbe.begin(other.getUuid());
            if (scenario == Case.CUSTOM_DIMENSION) {
                var target = new TeleportTarget(world, new Vec3d(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D), Vec3d.ZERO, 0, 0, TeleportTarget.NO_OP);
                player.teleportTo(target);
                other.teleportTo(target);
            }
            placeOwner(player, destination);
            placeOwner(other, destination.add(8, 0, 0));
            for (int x : new int[] {0, 31}) for (int z : new int[] {0, 31}) {
                BlockPos absolute = context.getAbsolutePos(new BlockPos(x, 2, z));
                ChunkPos chunk = new ChunkPos(absolute);
                if (!destinationChunks.contains(chunk)) destinationChunks.add(chunk);
                world.setChunkForced(chunk.x, chunk.z, true);
            }
            int serial = SEQUENCE.getAndIncrement();
            for (int i = 0; i < 5; i++) {
                ChunkPos chunk = new ChunkPos(20000 + serial * 211 + i * 23, 22000 + i * 31);
                BlockPos pad = new BlockPos(chunk.getStartX() + 8, destination.getY() + i * 5, chunk.getStartZ() + 8);
                sources.add(chunk);
                pads.add(pad);
                world.setChunkForced(chunk.x, chunk.z, true);
                world.getChunk(chunk.x, chunk.z);
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    world.setBlockState(pad.add(x, -1, z), Blocks.STONE.getDefaultState());
                    for (int y = 0; y <= 3; y++) world.setBlockState(pad.add(x, y, z), Blocks.AIR.getDefaultState());
                }
            }
        }

        void floor(boolean blocked) {
            for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
                world.setBlockState(context.getAbsolutePos(new BlockPos(x, 1, z)), Blocks.STONE.getDefaultState());
                for (int y = 2; y <= 5; y++) world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)),
                        (blocked && Math.abs(x - 16) <= 6 && Math.abs(z - 16) <= 6 ? Blocks.STONE : Blocks.AIR).getDefaultState());
            }
        }

        void placeOwner(ServerPlayerEntity owner, BlockPos position) {
            owner.refreshPositionAndAngles(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, 0, 0);
            owner.setNoGravity(true);
            owner.setOnGround(true);
        }

        TameableEntity spawn(int i, ServerPlayerEntity owner, boolean sitting) {
            BlockPos pad = pads.get(i % pads.size());
            String species = scenario == Case.MIXED_SPECIES && i % 3 == 1 ? "cat"
                    : scenario == Case.MIXED_SPECIES && i % 3 == 2 ? "parrot" : "wolf";
            TameableEntity pet = (TameableEntity) Registries.ENTITY_TYPE.get(Identifier.of("minecraft", species)).create(world, SpawnReason.COMMAND);
            check(context, pet != null, "Registered vanilla pet type must create a real entity");
            int slot = i / pads.size();
            pet.refreshPositionAndAngles(pad.getX() + slot % 7 - 2.5D, pad.getY(), pad.getZ() + slot / 7 % 7 - 2.5D, 0, 0);
            pet.setTamed(true, true);
            pet.setOwner(owner);
            pet.setSitting(sitting);
            pet.setAiDisabled(true);
            pet.setNoGravity(true);
            if (scenario == Case.MIXED_SPECIES && i % 4 == 0) pet.setBreedingAge(-24000);
            pet.setPersistent();
            pet.setHealth(Math.min(7.0F, pet.getMaxHealth()));
            pet.setCustomName(Text.literal("runtime pet " + i));
            pet.addCommandTag("runtime_state_" + i);
            check(context, world.spawnEntity(pet), "Real fixture pet must spawn");
            tracker.observe(pet, world);
            before.put(pet.getUuid(), PetIndexState.get(world.getServer()).getPet(pet.getUuid()));
            petNumbers.put(pet.getUuid(), i);
            return pet;
        }

        void forceSources(boolean value) {
            for (ChunkPos chunk : sources) world.setChunkForced(chunk.x, chunk.z, value);
        }

        boolean sourcesReady() { return sources.stream().allMatch(c -> VersionCompat.areChunkEntitiesLoaded(world, c)); }
        boolean sourcesUnloaded() {
            return sources.stream().noneMatch(c -> VersionCompat.areChunkEntitiesLoaded(world, c))
                    && recalled.stream().allMatch(id -> tracker.getLoadedPet(id) == null);
        }
        int nearCount() {
            int count = 0;
            for (UUID id : recalled) {
                Entity pet = world.getEntity(id);
                ServerPlayerEntity owner = before.get(id).ownerUuid().equals(player.getUuid()) ? player : other;
                if (pet != null && pet.squaredDistanceTo(owner) <= 144.0D) count++;
            }
            return count;
        }

        void tick() {
            try {
                ticks++;
                int deadline = switch (scenario) {
                    case STRESS -> 4980;
                    case TIMEOUT, MULTIPLAYER, MANUAL_CONCURRENT, LIVE_AI, MIXED_SPECIES, TERRAIN, CUSTOM_DIMENSION -> 1780;
                    default -> 1580;
                };
                check(context, ticks < deadline,
                        "Fixture deadline reached: " + progress());
                if (phase == 0) {
                    if (!sourcesReady()) return;
                    int count = scenario == Case.STRESS ? 256 : scenario == Case.NORMAL ? 33 : 40;
                    for (int i = 0; i < count; i++) recalled.add(spawn(i,
                            (scenario == Case.MULTIPLAYER || scenario == Case.MANUAL_CONCURRENT) && i % 2 == 1 ? other : player, false).getUuid());
                    for (int i = 0; i < 5; i++) controls.add(spawn(count + i, player, true).getUuid());
                    controls.add(spawn(count + 5, other, scenario == Case.MULTIPLAYER || scenario == Case.MANUAL_CONCURRENT).getUuid());
                    if (scenario == Case.BEFORE_UNLOAD) {
                        auto.resumePlayer(player.getUuid());
                        recoveryAt = ticks + 30;
                        phase = 2;
                    } else {
                        forceSources(false);
                        if (scenario == Case.LATE_GROUP) {
                            world.setChunkForced(sources.get(4).x, sources.get(4).z, true);
                        }
                        phase = 1;
                    }
                    return;
                }
                if (phase == 1) {
                    if (scenario == Case.LATE_GROUP) {
                        if (sources.subList(0, 4).stream().anyMatch(c -> VersionCompat.areChunkEntitiesLoaded(world, c))) return;
                    } else if (!sourcesUnloaded()) return;
                    check(context, nearCount() == 0, "No fixture pet may arrive before the production controller starts");
                    if (scenario == Case.BLOCKED_DESTINATION) floor(true);
                    if (scenario == Case.TERRAIN) hazards();
                    if (loadingInterruption() || scenario == Case.TIMEOUT || scenario == Case.MOVING_DESTINATION) {
                        for (ChunkPos chunk : sources) RecallFaults.blockReadiness(world, chunk);
                    }
                    if (scenario == Case.ACQUIRE_FAILURE) {
                        for (ChunkPos chunk : sources) RecallFaults.failNextAcquisition(world, chunk);
                    }
                    auto.resumePlayer(player.getUuid());
                    if (scenario == Case.MULTIPLAYER) auto.resumePlayer(other.getUuid());
                    phase = 2;
                    return;
                }
                if (phase == 3) {
                    if (sources.stream().anyMatch(c -> VersionCompat.areChunkEntitiesLoaded(world, c))) return;
                    check(context, !service.isRecallActive(player.getUuid()) && !service.isRecallActive(other.getUuid()),
                            "Finished owners cannot retain active recall reservations");
                    forceSources(true);
                    phase = 4;
                    return;
                }
                if (phase == 4) {
                    if (!sourcesReady()) return;
                    verify();
                    if (scenario == Case.STRESS && ++rounds < 8) {
                        for (int i = 0; i < recalled.size(); i++) {
                            TameableEntity pet = (TameableEntity) world.getEntity(recalled.get(i));
                            PetRecord saved = before.get(pet.getUuid());
                            pet.refreshPositionAndAngles(saved.x(), saved.y(), saved.z(), 0, 0);
                            tracker.observe(pet, world);
                        }
                        auto.suppressPlayer(player.getUuid());
                        forceSources(false);
                        elapsed = settled = 0;
                        phase = 1;
                        return;
                    }
                    cleanup();
                    context.complete();
                    return;
                }
                elapsed++;
                assertIndex();
                assertLoadedState();
                check(context, service.getDebugStats(world.getTime()).activeChunkOperations() <= 4,
                        "All production owners together must respect the four-chunk concurrency limit");
                int near = nearCount();
                if (scenario == Case.BEFORE_UNLOAD && ticks == recoveryAt) forceSources(false);
                if (scenario == Case.LATE_GROUP && !interrupted && near > 0) {
                    interrupted = true;
                    world.setChunkForced(sources.get(4).x, sources.get(4).z, false);
                }
                if (scenario == Case.BLOCKED_DESTINATION && elapsed == 40) {
                    check(context, near == 0, "Blocked destination must not move or destroy pets");
                    assertIndex();
                    floor(false);
                    interrupted = true;
                }
                if (scenario == Case.TIMEOUT && elapsed == 240) {
                    check(context, near < recalled.size(), "Blocked chunk readiness must prevent completion past the 200-tick timeout");
                    assertIndex();
                    clearFaults();
                    interrupted = true;
                }
                if (scenario == Case.ACQUIRE_FAILURE && elapsed == 40) {
                    assertIndex();
                    clearFaults();
                    interrupted = true;
                }
                boolean between = scenario == Case.LOGOUT_BETWEEN || scenario == Case.DEATH_BETWEEN || scenario == Case.PORTAL_BETWEEN;
                if (!interrupted && (loadingInterruption() && service.isRecallActive(player.getUuid())
                        || between && near >= 16 && near < recalled.size() && !service.isRecallActive(player.getUuid()))) {
                    interrupted = true;
                    nearAtInterrupt = near;
                    recoveryAt = ticks + 20;
                    interruptOwner();
                }
                if (interrupted && recoveryAt > 0 && ticks < recoveryAt) {
                    check(context, nearCount() <= nearAtInterrupt, "Recall must stop while the owner is unavailable or in another world");
                }
                if (interrupted && ticks == recoveryAt) recoverOwner();
                if (scenario == Case.MOVING_DESTINATION && !interrupted && service.isRecallActive(player.getUuid())) {
                    placeOwner(player, destination.add(10, 0, 0));
                    clearFaults();
                    interrupted = true;
                }
                if ((scenario == Case.MULTIPLAYER || scenario == Case.MANUAL_CONCURRENT) && !manualStarted && service.isRecallActive(player.getUuid())) {
                    manualStarted = true;
                    check(context, !service.recallAllForPlayerAsync(player, summary -> {
                        throw new AssertionError("Rejected same-owner manual recall cannot complete");
                    }), "Manual request for an already active owner must be rejected");
                    // The other owner uses the same production service, including the same source chunks.
                    if (scenario == Case.MANUAL_CONCURRENT) {
                        check(context, service.recallAllForPlayerAsync(other, summary -> {
                            check(context, summary.failed == 0 && summary.recalled == 20,
                                    "Concurrent second-owner manual recall must recover its entire group");
                        }), "A different owner must be accepted while automatic recall is active");
                        auto.resumePlayer(other.getUuid());
                    }
                }
                check(context, elapsed < (scenario == Case.STRESS ? 450 : 700),
                        "Every eligible pet must eventually arrive without another jump, movement or command: " + progress());
                if (nearCount() != recalled.size() || service.isRecallActive(player.getUuid()) || service.isRecallActive(other.getUuid())) return;
                if (scenario == Case.LIVE_AI || scenario == Case.MIXED_SPECIES || scenario == Case.TERRAIN) {
                    // Keep live parrots as world entities; shoulder mounting has its own vanilla lifecycle.
                    if (scenario == Case.MIXED_SPECIES) player.getAbilities().flying = true;
                    if (settled == 0) for (UUID id : recalled) {
                        TameableEntity pet = (TameableEntity) world.getEntity(id);
                        pet.setNoGravity(false);
                        pet.setAiDisabled(scenario == Case.TERRAIN);
                    }
                    if (scenario == Case.LIVE_AI && settled == 20) placeOwner(player, destination.add(8, 0, 0));
                }
                if ((scenario == Case.LIVE_AI || scenario == Case.MIXED_SPECIES || scenario == Case.TERRAIN) && ++settled < 100) return;
                if (scenario != Case.NORMAL && scenario != Case.STRESS && scenario != Case.LIVE_AI && scenario != Case.MIXED_SPECIES && scenario != Case.TERRAIN
                        && scenario != Case.CUSTOM_DIMENSION && scenario != Case.MULTIPLAYER && scenario != Case.MANUAL_CONCURRENT && scenario != Case.BEFORE_UNLOAD) {
                    check(context, interrupted, "The test must actually exercise its interruption or failure");
                }
                if (scenario == Case.MULTIPLAYER || scenario == Case.MANUAL_CONCURRENT) check(context, manualStarted, "Concurrent command path must be exercised");
                forceSources(false);
                phase = 3;
            } catch (RuntimeException | AssertionError error) {
                cleanup();
                throw error;
            }
        }

        boolean loadingInterruption() {
            return scenario == Case.LOGOUT_LOADING || scenario == Case.DEATH_LOADING || scenario == Case.PORTAL_LOADING;
        }

        void interruptOwner() {
            if (scenario == Case.LOGOUT_LOADING || scenario == Case.LOGOUT_BETWEEN) world.getServer().getPlayerManager().remove(player);
            else if (scenario == Case.DEATH_LOADING || scenario == Case.DEATH_BETWEEN) player.setHealth(0);
            else {
                portalWorld = world.getServer().getWorld(World.NETHER);
                check(context, portalWorld != null, "Portal test requires the vanilla Nether");
                BlockPos pad = new BlockPos(destination.getX(), 80, destination.getZ());
                portalWorld.setChunkForced(pad.getX() >> 4, pad.getZ() >> 4, true);
                portalWorld.getChunk(pad.getX() >> 4, pad.getZ() >> 4);
                player = player.teleportTo(new TeleportTarget(portalWorld,
                        new Vec3d(pad.getX() + 0.5D, 81, pad.getZ() + 0.5D), Vec3d.ZERO, 0, 0, TeleportTarget.NO_OP));
                check(context, player != null, "Vanilla dimension transition must succeed");
                player.setOnGround(true);
            }
        }

        void recoverOwner() {
            UUID oldUuid = player.getUuid();
            if (scenario == Case.LOGOUT_LOADING || scenario == Case.LOGOUT_BETWEEN) {
                ServerPlayerEntity joined = new ServerPlayerEntity(world.getServer(), world, player.getGameProfile(), SyncedClientOptions.createDefault());
                ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
                channels.add(new EmbeddedChannel(connection));
                world.getServer().getPlayerManager().onPlayerConnect(connection, joined,
                        ConnectedClientData.createDefault(player.getGameProfile(), false));
                player = joined;
            } else if (scenario == Case.DEATH_LOADING || scenario == Case.DEATH_BETWEEN) {
                player = world.getServer().getPlayerManager().respawnPlayer(player, false, Entity.RemovalReason.KILLED);
            } else {
                for (UUID id : recalled) check(context, portalWorld.getEntity(id) == null, "Pets cannot follow across a portal");
                player = player.teleportTo(new TeleportTarget(world,
                        new Vec3d(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D),
                        Vec3d.ZERO, 0, 0, TeleportTarget.NO_OP));
            }
            check(context, player != null && player.getUuid().equals(oldUuid), "Vanilla lifecycle must keep the owner's UUID");
            placeOwner(player, destination);
            clearFaults();
            assertIndex();
        }

        void hazards() {
            for (int x = 10; x <= 22; x++) for (int z = 10; z <= 22; z++) {
                if (x <= 12) context.setBlockState(new BlockPos(x, 1, z), Blocks.LAVA);
                else if (x == 13) context.setBlockState(new BlockPos(x, 2, z), Blocks.OAK_FENCE);
                else if (x == 14) context.setBlockState(new BlockPos(x, 1, z), Blocks.STONE_STAIRS);
                else if (x == 15) context.setBlockState(new BlockPos(x, 1, z), Blocks.STONE_SLAB);
                else if (x == 20) context.setBlockState(new BlockPos(x, 2, z), Blocks.WHITE_CARPET);
                else if (x >= 21) context.setBlockState(new BlockPos(x, 1, z), Blocks.AIR);
            }
        }

        void assertIndex() {
            for (UUID id : before.keySet()) check(context, PetIndexState.get(world.getServer()).getPet(id) != null,
                    "Transient failures and owner lifecycle events cannot erase a living pet's index entry");
        }

        void verify() {
            assertIndex();
            assertLoadedState();
            if (scenario == Case.NORMAL) {
                check(context, RecallBatchProbe.batches(player.getUuid()).equals(List.of(16, 16, 1)),
                        "Thirty-three pets must use exactly three production batches: " + progress());
            }
            for (UUID id : recalled) {
                Entity entity = world.getEntity(id);
                check(context, entity instanceof TameableEntity, "Every original pet must survive source reload");
                TameableEntity pet = (TameableEntity) entity;
                PetRecord saved = before.get(id);
                check(context, pet.isAlive() && pet.getHealth() == saved.health(), "Pet cannot lose health after teleport or falling");
                check(context, pet.getOwnerReference().getUuid().equals(saved.ownerUuid()) && !pet.isSitting(), "Ownership and stance must survive");
                check(context, pet.getCustomName() != null && pet.getCustomName().getString().equals("runtime pet " + petNumbers.get(id))
                        && pet.getCommandTags().contains("runtime_state_" + petNumbers.get(id)), "Name and custom tags must survive");
                ServerPlayerEntity owner = saved.ownerUuid().equals(player.getUuid()) ? player : other;
                check(context, pet.squaredDistanceTo(owner) <= 144.0D && world.isSpaceEmpty(pet, pet.getBoundingBox()),
                        "Pet must remain near the current owner, outside solid blocks");
                long copies = 0;
                for (Entity loaded : world.iterateEntities()) if (loaded.getUuid().equals(id)) copies++;
                check(context, copies == 1, "Reloading the source cannot create a second UUID");
                if (scenario == Case.MIXED_SPECIES) {
                    int i = recalled.indexOf(id);
                    String expectedType = i % 3 == 1 ? "minecraft:cat" : i % 3 == 2 ? "minecraft:parrot" : "minecraft:wolf";
                    check(context, Registries.ENTITY_TYPE.getId(pet.getType()).toString().equals(expectedType), "Species cannot change during recall");
                    check(context, (pet.getBreedingAge() < 0) == (i % 4 == 0), "Baby/adult state must survive");
                }
            }
            for (UUID id : controls) {
                Entity entity = world.getEntity(id);
                PetRecord saved = before.get(id);
                check(context, entity instanceof TameableEntity && entity.getX() == saved.x() && entity.getY() == saved.y() && entity.getZ() == saved.z(),
                        "Sitting and uninvolved foreign pets must stay at their original locations");
            }
        }

        // Structural checks fail immediately; asynchronous I/O retains its original deadlines.
        void assertLoadedState() {
            for (var entry : before.entrySet()) {
                UUID id = entry.getKey();
                Entity entity = world.getEntity(id);
                if (entity == null) continue;
                check(context, entity instanceof TameableEntity, "Loaded fixture UUID changed type: " + id);
                var pet = (TameableEntity) entity;
                PetRecord saved = entry.getValue();
                check(context, pet.isAlive() && pet.getHealth() == saved.health(), "Living pet lost health: " + id);
                check(context, pet.getOwnerReference() != null && pet.getOwnerReference().getUuid().equals(saved.ownerUuid()), "Pet owner changed: " + id);
                check(context, pet.isSitting() == saved.sitting(), "Pet sitting state changed: " + id);
                if (settled == 0 || controls.contains(id) || scenario != Case.LIVE_AI && scenario != Case.MIXED_SPECIES && scenario != Case.TERRAIN) {
                    check(context, pet.isAiDisabled() && pet.hasNoGravity(), "Recall changed fixture AI or gravity: " + id);
                }
                check(context, pet.getCustomName() != null && pet.getCustomName().getString().equals("runtime pet " + petNumbers.get(id))
                        && pet.getCommandTags().contains("runtime_state_" + petNumbers.get(id)), "Pet name or tags changed: " + id);
            }
        }

        String progress() {
            return "scenario=" + scenario + ", phase=" + phase + ", tick=" + ticks + ", arrived=" + nearCount()
                    + "/" + recalled.size() + ", batchSizes=" + RecallBatchProbe.batches(player.getUuid())
                    + ", scheduler=" + service.getDebugStats(world.getTime());
        }

        void clearFaults() { for (ChunkPos chunk : sources) RecallFaults.clear(world, chunk); }

        void cleanup() {
            clearFaults();
            RecallBatchProbe.clear(player.getUuid());
            RecallBatchProbe.clear(other.getUuid());
            auto.suppressPlayer(player.getUuid());
            auto.suppressPlayer(other.getUuid());
            forceSources(false);
            for (ChunkPos chunk : destinationChunks) world.setChunkForced(chunk.x, chunk.z, false);
            if (portalWorld != null) portalWorld.setChunkForced(destination.getX() >> 4, destination.getZ() >> 4, false);
            for (UUID id : before.keySet()) {
                Entity entity = world.getEntity(id);
                tracker.removeRecord(world.getServer(), id);
                if (entity != null) entity.discard();
            }
            world.getServer().getPlayerManager().remove(player);
            world.getServer().getPlayerManager().remove(other);
            auto.resumePlayer(player.getUuid());
            auto.resumePlayer(other.getUuid());
            for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
        }
    }
}
