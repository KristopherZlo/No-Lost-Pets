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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.Level;

/** Uses the production event listeners, controller and shared service; never ticks private copies. */
public final class PetRecallRuntimeGameTests {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private enum Case {
        NORMAL, BEFORE_UNLOAD, LATE_GROUP, LOGOUT_LOADING, LOGOUT_BETWEEN, DEATH_LOADING, DEATH_BETWEEN,
        PORTAL_LOADING, PORTAL_BETWEEN, BLOCKED_DESTINATION, TIMEOUT, ACQUIRE_FAILURE,
        CUSTOM_DIMENSION, MOVING_DESTINATION, MULTIPLAYER, MANUAL_CONCURRENT, LIVE_AI, MIXED_SPECIES, TERRAIN, STRESS
    }

    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/movement_before_source_unload_still_recalls_without_another_trigger")
    public void movementBeforeSourceUnloadStillRecallsWithoutAnotherTrigger(GameTestHelper c) { run(c, Case.BEFORE_UNLOAD); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/pets_unloading_after_the_first_batch_join_the_same_recall_burst")
    public void petsUnloadingAfterTheFirstBatchJoinTheSameRecallBurst(GameTestHelper c) { run(c, Case.LATE_GROUP); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/logout_while_chunk_loads_then_rejoin_recovers_every_pet")
    public void logoutWhileChunkLoadsThenRejoinRecoversEveryPet(GameTestHelper c) { run(c, Case.LOGOUT_LOADING); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/logout_between_batches_then_rejoin_recovers_every_pet")
    public void logoutBetweenBatchesThenRejoinRecoversEveryPet(GameTestHelper c) { run(c, Case.LOGOUT_BETWEEN); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/death_while_chunk_loads_then_respawn_recovers_every_pet")
    public void deathWhileChunkLoadsThenRespawnRecoversEveryPet(GameTestHelper c) { run(c, Case.DEATH_LOADING); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/death_between_batches_then_respawn_recovers_every_pet")
    public void deathBetweenBatchesThenRespawnRecoversEveryPet(GameTestHelper c) { run(c, Case.DEATH_BETWEEN); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/portal_while_chunk_loads_never_transfers_pets_across_dimensions")
    public void portalWhileChunkLoadsNeverTransfersPetsAcrossDimensions(GameTestHelper c) { run(c, Case.PORTAL_LOADING); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/portal_between_batches_preserves_the_remaining_queue")
    public void portalBetweenBatchesPreservesTheRemainingQueue(GameTestHelper c) { run(c, Case.PORTAL_BETWEEN); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/temporarily_blocked_destination_retries_the_same_pets_without_movement")
    public void temporarilyBlockedDestinationRetriesTheSamePetsWithoutMovement(GameTestHelper c) { run(c, Case.BLOCKED_DESTINATION); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/chunk_timeout_preserves_and_automatically_retries_the_same_living_pets")
    public void chunkTimeoutPreservesAndAutomaticallyRetriesTheSameLivingPets(GameTestHelper c) { run(c, Case.TIMEOUT); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/transient_acquisition_failure_does_not_strand_the_affected_pets")
    public void transientAcquisitionFailureDoesNotStrandTheAffectedPets(GameTestHelper c) { run(c, Case.ACQUIRE_FAILURE); }
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/owner_moving_during_loading_uses_the_current_destination")
    public void ownerMovingDuringLoadingUsesTheCurrentDestination(GameTestHelper c) { run(c, Case.MOVING_DESTINATION); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/two_owners_and_concurrent_manual_recall_share_the_production_scheduler")
    public void twoOwnersAndConcurrentManualRecallShareTheProductionScheduler(GameTestHelper c) { run(c, Case.MULTIPLAYER); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/automatic_and_manual_owners_load_shared_chunks_concurrently")
    public void automaticAndManualOwnersLoadSharedChunksConcurrently(GameTestHelper c) { run(c, Case.MANUAL_CONCURRENT); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/recalled_pets_with_live_ai_and_gravity_remain_alive_and_follow_their_owner")
    public void recalledPetsWithLiveAiAndGravityRemainAliveAndFollowTheirOwner(GameTestHelper c) { run(c, Case.LIVE_AI); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/mixed_cats_wolves_parrots_and_babies_keep_their_state")
    public void mixedCatsWolvesParrotsAndBabiesKeepTheirState(GameTestHelper c) { run(c, Case.MIXED_SPECIES); }
    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/gravity_and_irregular_terrain_cannot_drop_pets_into_hazards")
    public void gravityAndIrregularTerrainCannotDropPetsIntoHazards(GameTestHelper c) { run(c, Case.TERRAIN); }

    @GameTest(maxTicks = 1800, structure = "pet_recall_gametest:empty_32", environment = "pet_recall_gametest:isolated/real_datapack_dimension_supports_recall_and_source_reload")
    public void realDatapackDimensionSupportsRecallAndSourceReload(GameTestHelper c) { run(c, Case.CUSTOM_DIMENSION); }

    static void runNormal(GameTestHelper c) { run(c, Case.NORMAL); }
    static void runStress(GameTestHelper c) { run(c, Case.STRESS); }

    private static void run(GameTestHelper c, Case scenario) {
        Fixture fixture = new Fixture(c, scenario);
        c.onEachTick(fixture::tick);
    }

    private static void check(GameTestHelper c, boolean condition, String message) {
        c.assertTrue(condition, Component.literal(message));
    }

    private static final class Fixture {
        final GameTestHelper context;
        final Case scenario;
        final ServerLevel world;
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
        ServerPlayer player;
        ServerPlayer other;
        ServerLevel portalWorld;
        int phase;
        int ticks;
        int elapsed;
        int settled;
        int rounds;
        int recoveryAt = -1;
        int nearAtInterrupt;
        boolean interrupted;
        boolean manualStarted;

        Fixture(GameTestHelper context, Case scenario) {
            this.context = context;
            this.scenario = scenario;
            this.world = scenario == Case.CUSTOM_DIMENSION ? context.getLevel().getServer().getLevel(
                    ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("pet_recall_gametest", "companions"))) : context.getLevel();
            check(context, world != null, "The actual datapack dimension must load into the server registry");
            this.destination = context.absolutePos(new BlockPos(16, 2, 16));
            floor(false);
            player = context.makeMockServerPlayerInLevel();
            other = context.makeMockServerPlayerInLevel();
            auto.suppressPlayer(player.getUUID());
            auto.suppressPlayer(other.getUUID());
            RecallBatchProbe.begin(player.getUUID());
            RecallBatchProbe.begin(other.getUUID());
            if (scenario == Case.CUSTOM_DIMENSION) {
                var target = new TeleportTransition(world, new Vec3(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
                player.teleport(target);
                other.teleport(target);
            }
            placeOwner(player, destination);
            placeOwner(other, destination.offset(8, 0, 0));
            for (int x : new int[] {0, 31}) for (int z : new int[] {0, 31}) {
                BlockPos absolute = context.absolutePos(new BlockPos(x, 2, z));
                ChunkPos chunk = ChunkPos.containing(absolute);
                if (!destinationChunks.contains(chunk)) destinationChunks.add(chunk);
                world.setChunkForced(chunk.x(), chunk.z(), true);
            }
            int serial = SEQUENCE.getAndIncrement();
            for (int i = 0; i < 5; i++) {
                ChunkPos chunk = new ChunkPos(20000 + serial * 211 + i * 23, 22000 + i * 31);
                BlockPos pad = new BlockPos(chunk.getMinBlockX() + 8, destination.getY() + i * 5, chunk.getMinBlockZ() + 8);
                sources.add(chunk);
                pads.add(pad);
                world.setChunkForced(chunk.x(), chunk.z(), true);
                world.getChunk(chunk.x(), chunk.z());
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                    world.setBlockAndUpdate(pad.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    for (int y = 0; y <= 3; y++) world.setBlockAndUpdate(pad.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }

        void floor(boolean blocked) {
            for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
                world.setBlockAndUpdate(context.absolutePos(new BlockPos(x, 1, z)), Blocks.STONE.defaultBlockState());
                for (int y = 2; y <= 5; y++) world.setBlockAndUpdate(context.absolutePos(new BlockPos(x, y, z)),
                        (blocked && Math.abs(x - 16) <= 6 && Math.abs(z - 16) <= 6 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
        }

        void placeOwner(ServerPlayer owner, BlockPos position) {
            owner.snapTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, 0, 0);
            owner.setNoGravity(true);
            owner.setOnGround(true);
        }

        TamableAnimal spawn(int i, ServerPlayer owner, boolean sitting) {
            BlockPos pad = pads.get(i % pads.size());
            String species = scenario == Case.MIXED_SPECIES && i % 3 == 1 ? "cat"
                    : scenario == Case.MIXED_SPECIES && i % 3 == 2 ? "parrot" : "wolf";
            TamableAnimal pet = (TamableAnimal) BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", species)).create(world, EntitySpawnReason.COMMAND);
            check(context, pet != null, "Registered vanilla pet type must create a real entity");
            int slot = i / pads.size();
            pet.snapTo(pad.getX() + slot % 7 - 2.5D, pad.getY(), pad.getZ() + slot / 7 % 7 - 2.5D, 0, 0);
            pet.setTame(true, true);
            pet.setOwner(owner);
            pet.setOrderedToSit(sitting);
            pet.setNoAi(true);
            pet.setNoGravity(true);
            if (scenario == Case.MIXED_SPECIES && i % 4 == 0) pet.setAge(-24000);
            pet.setPersistenceRequired();
            pet.setHealth(Math.min(7.0F, pet.getMaxHealth()));
            pet.setCustomName(Component.literal("runtime pet " + i));
            pet.addTag("runtime_state_" + i);
            check(context, world.addFreshEntity(pet), "Real fixture pet must spawn");
            tracker.observe(pet, world);
            before.put(pet.getUUID(), PetIndexState.get(world.getServer()).getPet(pet.getUUID()));
            petNumbers.put(pet.getUUID(), i);
            return pet;
        }

        void forceSources(boolean value) {
            for (ChunkPos chunk : sources) world.setChunkForced(chunk.x(), chunk.z(), value);
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
                ServerPlayer owner = before.get(id).ownerUuid().equals(player.getUUID()) ? player : other;
                if (pet != null && pet.distanceToSqr(owner) <= 144.0D) count++;
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
                            (scenario == Case.MULTIPLAYER || scenario == Case.MANUAL_CONCURRENT) && i % 2 == 1 ? other : player, false).getUUID());
                    for (int i = 0; i < 5; i++) controls.add(spawn(count + i, player, true).getUUID());
                    controls.add(spawn(count + 5, other, scenario == Case.MULTIPLAYER || scenario == Case.MANUAL_CONCURRENT).getUUID());
                    if (scenario == Case.BEFORE_UNLOAD) {
                        auto.resumePlayer(player.getUUID());
                        recoveryAt = ticks + 30;
                        phase = 2;
                    } else {
                        forceSources(false);
                        if (scenario == Case.LATE_GROUP) {
                            world.setChunkForced(sources.get(4).x(), sources.get(4).z(), true);
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
                    auto.resumePlayer(player.getUUID());
                    if (scenario == Case.MULTIPLAYER) auto.resumePlayer(other.getUUID());
                    phase = 2;
                    return;
                }
                if (phase == 3) {
                    if (sources.stream().anyMatch(c -> VersionCompat.areChunkEntitiesLoaded(world, c))) return;
                    check(context, !service.isRecallActive(player.getUUID()) && !service.isRecallActive(other.getUUID()),
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
                            TamableAnimal pet = (TamableAnimal) world.getEntity(recalled.get(i));
                            PetRecord saved = before.get(pet.getUUID());
                            pet.snapTo(saved.x(), saved.y(), saved.z(), 0, 0);
                            tracker.observe(pet, world);
                        }
                        auto.suppressPlayer(player.getUUID());
                        forceSources(false);
                        elapsed = settled = 0;
                        phase = 1;
                        return;
                    }
                    cleanup();
                    context.succeed();
                    return;
                }
                elapsed++;
                assertIndex();
                assertLoadedState();
                check(context, service.getDebugStats(world.getGameTime()).activeChunkOperations() <= 4,
                        "All production owners together must respect the four-chunk concurrency limit");
                int near = nearCount();
                if (scenario == Case.BEFORE_UNLOAD && ticks == recoveryAt) forceSources(false);
                if (scenario == Case.LATE_GROUP && !interrupted && near > 0) {
                    interrupted = true;
                    world.setChunkForced(sources.get(4).x(), sources.get(4).z(), false);
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
                if (!interrupted && (loadingInterruption() && service.isRecallActive(player.getUUID())
                        || between && near >= 16 && near < recalled.size() && !service.isRecallActive(player.getUUID()))) {
                    interrupted = true;
                    nearAtInterrupt = near;
                    recoveryAt = ticks + 20;
                    interruptOwner();
                }
                if (interrupted && recoveryAt > 0 && ticks < recoveryAt) {
                    check(context, nearCount() <= nearAtInterrupt, "Recall must stop while the owner is unavailable or in another world");
                }
                if (interrupted && ticks == recoveryAt) recoverOwner();
                if (scenario == Case.MOVING_DESTINATION && !interrupted && service.isRecallActive(player.getUUID())) {
                    placeOwner(player, destination.offset(10, 0, 0));
                    clearFaults();
                    interrupted = true;
                }
                if ((scenario == Case.MULTIPLAYER || scenario == Case.MANUAL_CONCURRENT) && !manualStarted && service.isRecallActive(player.getUUID())) {
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
                        auto.resumePlayer(other.getUUID());
                    }
                }
                check(context, elapsed < (scenario == Case.STRESS ? 450 : 700),
                        "Every eligible pet must eventually arrive without another jump, movement or command: " + progress());
                if (nearCount() != recalled.size() || service.isRecallActive(player.getUUID()) || service.isRecallActive(other.getUUID())) return;
                if (scenario == Case.LIVE_AI || scenario == Case.MIXED_SPECIES || scenario == Case.TERRAIN) {
                    // Keep live parrots as world entities; shoulder mounting has its own vanilla lifecycle.
                    if (scenario == Case.MIXED_SPECIES) player.getAbilities().flying = true;
                    if (settled == 0) for (UUID id : recalled) {
                        TamableAnimal pet = (TamableAnimal) world.getEntity(id);
                        pet.setNoGravity(false);
                        pet.setNoAi(scenario == Case.TERRAIN);
                    }
                    if (scenario == Case.LIVE_AI && settled == 20) placeOwner(player, destination.offset(8, 0, 0));
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
            if (scenario == Case.LOGOUT_LOADING || scenario == Case.LOGOUT_BETWEEN) world.getServer().getPlayerList().remove(player);
            else if (scenario == Case.DEATH_LOADING || scenario == Case.DEATH_BETWEEN) player.setHealth(0);
            else {
                portalWorld = world.getServer().getLevel(Level.NETHER);
                check(context, portalWorld != null, "Portal test requires the vanilla Nether");
                BlockPos pad = new BlockPos(destination.getX(), 80, destination.getZ());
                portalWorld.setChunkForced(pad.getX() >> 4, pad.getZ() >> 4, true);
                portalWorld.getChunk(pad.getX() >> 4, pad.getZ() >> 4);
                player = player.teleport(new TeleportTransition(portalWorld,
                        new Vec3(pad.getX() + 0.5D, 81, pad.getZ() + 0.5D), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
                check(context, player != null, "Vanilla dimension transition must succeed");
                player.setOnGround(true);
            }
        }

        void recoverOwner() {
            UUID oldUuid = player.getUUID();
            if (scenario == Case.LOGOUT_LOADING || scenario == Case.LOGOUT_BETWEEN) {
                ServerPlayer joined = new ServerPlayer(world.getServer(), world, player.getGameProfile(), ClientInformation.createDefault());
                Connection connection = new Connection(PacketFlow.SERVERBOUND);
                channels.add(new EmbeddedChannel(connection));
                world.getServer().getPlayerList().placeNewPlayer(connection, joined,
                        CommonListenerCookie.createInitial(player.getGameProfile(), false));
                player = joined;
            } else if (scenario == Case.DEATH_LOADING || scenario == Case.DEATH_BETWEEN) {
                player = world.getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            } else {
                for (UUID id : recalled) check(context, portalWorld.getEntity(id) == null, "Pets cannot follow across a portal");
                player = player.teleport(new TeleportTransition(world,
                        new Vec3(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D),
                        Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING));
            }
            check(context, player != null && player.getUUID().equals(oldUuid), "Vanilla lifecycle must keep the owner's UUID");
            placeOwner(player, destination);
            clearFaults();
            assertIndex();
        }

        void hazards() {
            for (int x = 10; x <= 22; x++) for (int z = 10; z <= 22; z++) {
                if (x <= 12) context.setBlock(new BlockPos(x, 1, z), Blocks.LAVA);
                else if (x == 13) context.setBlock(new BlockPos(x, 2, z), Blocks.OAK_FENCE);
                else if (x == 14) context.setBlock(new BlockPos(x, 1, z), Blocks.STONE_STAIRS);
                else if (x == 15) context.setBlock(new BlockPos(x, 1, z), Blocks.STONE_SLAB);
                else if (x == 20) context.setBlock(new BlockPos(x, 2, z), BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("minecraft", "white_carpet")));
                else if (x >= 21) context.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
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
                check(context, RecallBatchProbe.batches(player.getUUID()).equals(List.of(16, 16, 1)),
                        "Thirty-three pets must use exactly three production batches: " + progress());
            }
            for (UUID id : recalled) {
                Entity entity = world.getEntity(id);
                check(context, entity instanceof TamableAnimal, "Every original pet must survive source reload");
                TamableAnimal pet = (TamableAnimal) entity;
                PetRecord saved = before.get(id);
                check(context, pet.isAlive() && pet.getHealth() == saved.health(), "Pet cannot lose health after teleport or falling");
                check(context, pet.getOwnerReference().getUUID().equals(saved.ownerUuid()) && !pet.isOrderedToSit(), "Ownership and stance must survive");
                check(context, pet.getCustomName() != null && pet.getCustomName().getString().equals("runtime pet " + petNumbers.get(id))
                        && pet.entityTags().contains("runtime_state_" + petNumbers.get(id)), "Name and custom tags must survive");
                ServerPlayer owner = saved.ownerUuid().equals(player.getUUID()) ? player : other;
                check(context, pet.distanceToSqr(owner) <= 144.0D && world.noCollision(pet, pet.getBoundingBox()),
                        "Pet must remain near the current owner, outside solid blocks");
                long copies = 0;
                for (Entity loaded : world.getAllEntities()) if (loaded.getUUID().equals(id)) copies++;
                check(context, copies == 1, "Reloading the source cannot create a second UUID");
                if (scenario == Case.MIXED_SPECIES) {
                    int i = recalled.indexOf(id);
                    String expectedType = i % 3 == 1 ? "minecraft:cat" : i % 3 == 2 ? "minecraft:parrot" : "minecraft:wolf";
                    check(context, BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString().equals(expectedType), "Species cannot change during recall");
                    check(context, (pet.getAge() < 0) == (i % 4 == 0), "Baby/adult state must survive");
                }
            }
            for (UUID id : controls) {
                Entity entity = world.getEntity(id);
                PetRecord saved = before.get(id);
                check(context, entity instanceof TamableAnimal && entity.getX() == saved.x() && entity.getY() == saved.y() && entity.getZ() == saved.z(),
                        "Sitting and uninvolved foreign pets must stay at their original locations");
            }
        }

        // Structural checks fail immediately; asynchronous I/O retains its original deadlines.
        void assertLoadedState() {
            for (var entry : before.entrySet()) {
                UUID id = entry.getKey();
                Entity entity = world.getEntity(id);
                if (entity == null) continue;
                check(context, entity instanceof TamableAnimal, "Loaded fixture UUID changed type: " + id);
                var pet = (TamableAnimal) entity;
                PetRecord saved = entry.getValue();
                check(context, pet.isAlive() && pet.getHealth() == saved.health(), "Living pet lost health: " + id);
                check(context, pet.getOwnerReference() != null && pet.getOwnerReference().getUUID().equals(saved.ownerUuid()), "Pet owner changed: " + id);
                check(context, pet.isOrderedToSit() == saved.sitting(), "Pet sitting state changed: " + id);
                if (settled == 0 || controls.contains(id) || scenario != Case.LIVE_AI && scenario != Case.MIXED_SPECIES && scenario != Case.TERRAIN) {
                    check(context, pet.isNoAi() && pet.isNoGravity(), "Recall changed fixture AI or gravity: " + id);
                }
                check(context, pet.getCustomName() != null && pet.getCustomName().getString().equals("runtime pet " + petNumbers.get(id))
                        && pet.entityTags().contains("runtime_state_" + petNumbers.get(id)), "Pet name or tags changed: " + id);
            }
        }

        String progress() {
            return "scenario=" + scenario + ", phase=" + phase + ", tick=" + ticks + ", arrived=" + nearCount()
                    + "/" + recalled.size() + ", batchSizes=" + RecallBatchProbe.batches(player.getUUID())
                    + ", scheduler=" + service.getDebugStats(world.getGameTime());
        }

        void clearFaults() { for (ChunkPos chunk : sources) RecallFaults.clear(world, chunk); }

        void cleanup() {
            clearFaults();
            RecallBatchProbe.clear(player.getUUID());
            RecallBatchProbe.clear(other.getUUID());
            auto.suppressPlayer(player.getUUID());
            auto.suppressPlayer(other.getUUID());
            forceSources(false);
            for (ChunkPos chunk : destinationChunks) world.setChunkForced(chunk.x(), chunk.z(), false);
            if (portalWorld != null) portalWorld.setChunkForced(destination.getX() >> 4, destination.getZ() >> 4, false);
            for (UUID id : before.keySet()) {
                Entity entity = world.getEntity(id);
                tracker.removeRecord(world.getServer(), id);
                if (entity != null) entity.discard();
            }
            world.getServer().getPlayerList().remove(player);
            world.getServer().getPlayerList().remove(other);
            auto.resumePlayer(player.getUUID());
            auto.resumePlayer(other.getUUID());
            for (EmbeddedChannel channel : channels) channel.finishAndReleaseAll();
        }
    }
}
