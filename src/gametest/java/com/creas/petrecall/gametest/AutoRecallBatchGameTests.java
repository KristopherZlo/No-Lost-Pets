package com.creas.petrecall.gametest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.selftest.TestEntityTypes;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.runtime.AutoPetRecallController;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.VersionCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class AutoRecallBatchGameTests {
    private static final AtomicInteger REMOTE_CHUNKS = new AtomicInteger();

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void oneTriggerRecallsFortyUnloadedPetsInBatches(GameTestHelper context) {
        run(context, false, false, false, false);
    }

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void failedFirstBatchDoesNotStrandHealthyPets(GameTestHelper context) {
        run(context, true, false, false, false);
    }

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void jumpPausesQueueAndCurrentPetStateIsRechecked(GameTestHelper context) {
        run(context, false, true, false, false);
    }

    @GameTest(maxTicks = 1000, structure = "pet_recall_gametest:empty_32")
    public void scatteredStandingPetsRecallButSittingPetsStayInTheirChunks(GameTestHelper context) {
        run(context, false, false, true, false);
    }

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void jumpDuringChunkLoadingDoesNotDropTheInFlightBatch(GameTestHelper context) {
        run(context, false, true, false, true);
    }

    private static void run(GameTestHelper context, boolean missingFirstBatch, boolean jump, boolean scattered, boolean interruptLoading) {
        Fixture f = new Fixture(context, missingFirstBatch, jump, scattered, interruptLoading);
        context.failIfEver(f::tick);
    }

    private static void check(GameTestHelper context, boolean condition, String message) {
        context.assertTrue(condition, Component.literal(message));
    }

    private static final class Fixture {
        final GameTestHelper context;
        final ServerLevel world;
        final PetTracker tracker = PetRecallMod.getTracker();
        final PetRecallService service = new PetRecallService(tracker);
        final AutoPetRecallController auto = new AutoPetRecallController(tracker, service);
        final ServerPlayer player;
        final ServerPlayer other;
        final BlockPos stand;
        final ChunkPos chunk;
        final BlockPos remote;
        final boolean missingFirstBatch;
        final boolean jump;
        final boolean scattered;
        final boolean interruptLoading;
        final List<ChunkPos> sourceChunks = new ArrayList<>();
        final List<ChunkPos> destinationChunks = new ArrayList<>();
        final List<BlockPos> sourcePositions = new ArrayList<>();
        final List<PetRecord> controlRecords = new ArrayList<>();
        final List<UUID> pets = new ArrayList<>();
        final List<UUID> controls = new ArrayList<>();
        final List<UUID> missing = new ArrayList<>();
        final List<UUID> changed = new ArrayList<>();
        int phase;
        int recallTicks;
        int pauseTicks;
        int pauseNear;
        int settledTicks;
        int previousNear;
        int batches;
        boolean jumped;

        @SuppressWarnings("removal")
        Fixture(GameTestHelper context, boolean missingFirstBatch, boolean jump, boolean scattered, boolean interruptLoading) {
            this.context = context;
            this.world = context.getLevel();
            this.missingFirstBatch = missingFirstBatch;
            this.jump = jump;
            this.scattered = scattered;
            this.interruptLoading = interruptLoading;
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) context.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
            this.stand = context.absolutePos(new BlockPos(16, 2, 16));
            this.player = context.makeMockServerPlayerInLevel();
            this.other = context.makeMockServerPlayerInLevel();
            player.snapTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 0, 0);
            other.snapTo(context.absolutePos(new BlockPos(2, 2, 2)), 0, 0);
            player.setNoGravity(true);
            player.setOnGround(true);
            PetRecallMod.getAutoRecallController().suppressPlayer(player.getUUID());
            PetRecallMod.getAutoRecallController().suppressPlayer(other.getUUID());
            for (int x : new int[] {0, 31}) {
                for (int z : new int[] {0, 31}) {
                    BlockPos absolute = context.absolutePos(new BlockPos(x, 2, z));
                    ChunkPos destination = new ChunkPos(absolute.getX() >> 4, absolute.getZ() >> 4);
                    if (!destinationChunks.contains(destination)) destinationChunks.add(destination);
                    world.setChunkForced(destination.x(), destination.z(), true);
                }
            }
            addControl(wolf(context.absolutePos(new BlockPos(2, 2, 2)), player, false, "already loaded"));
            this.chunk = new ChunkPos(8192 + REMOTE_CHUNKS.getAndIncrement() * 256, 8192);
            this.remote = new BlockPos(chunk.getMinBlockX() + 8, stand.getY(), chunk.getMinBlockZ() + 8);
            int sourceCount = scattered ? 5 : 1;
            for (int i = 0; i < sourceCount; i++) {
                ChunkPos sourceChunk = new ChunkPos(chunk.x() + i * 37, chunk.z() + i * 53);
                BlockPos pos = new BlockPos(sourceChunk.getMinBlockX() + 8, stand.getY() + i * 12, sourceChunk.getMinBlockZ() + 8);
                sourceChunks.add(sourceChunk);
                sourcePositions.add(pos);
                world.setChunkForced(sourceChunk.x(), sourceChunk.z(), true);
                world.getChunk(sourceChunk.x(), sourceChunk.z());
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        world.setBlockAndUpdate(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                        world.setBlockAndUpdate(pos.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                        world.setBlockAndUpdate(pos.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        void addControl(Wolf wolf) {
            controls.add(wolf.getUUID());
            controlRecords.add(PetIndexState.get(world.getServer()).getPet(wolf.getUUID()));
        }

        boolean allSourcesLoaded() {
            return sourceChunks.stream().allMatch(pos -> VersionCompat.areChunkEntitiesLoaded(world, pos));
        }

        void forceSources(boolean forced) {
            for (ChunkPos pos : sourceChunks) world.setChunkForced(pos.x(), pos.z(), forced);
        }

        Wolf wolf(BlockPos pos, ServerPlayer owner, boolean sitting, String name) {
            Wolf wolf = TestEntityTypes.WOLF.create(world, EntitySpawnReason.COMMAND);
            check(context, wolf != null, "Fixture wolf must be created");
            wolf.snapTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0, 0);
            wolf.setTame(true, true);
            wolf.setOwner(owner);
            wolf.setOrderedToSit(sitting);
            wolf.setNoAi(true);
            wolf.setNoGravity(true);
            wolf.setPersistenceRequired();
            wolf.setHealth(7.0F);
            wolf.setCustomName(Component.literal(name));
            wolf.addTag("auto_batch_state");
            check(context, world.addFreshEntity(wolf), "Fixture wolf must spawn");
            tracker.observe(wolf, world);
            return wolf;
        }

        void tick() {
            try {
                if (interruptLoading && phase == 2 && !jumped && service.isRecallActive(player.getUUID())) {
                    jumped = true;
                    player.snapTo(stand.getX() + 0.5D, stand.getY() + 4, stand.getZ() + 0.5D, 0, 0);
                    player.setOnGround(false);
                    pauseTicks = 6;
                    pauseNear = previousNear;
                }
                service.onServerTick(world.getServer());
                if (phase == 0) {
                    if (!allSourcesLoaded()) return;
                    int count = missingFirstBatch ? 20 : (scattered ? 60 : 40);
                    for (int i = 0; i < count; i++) {
                        BlockPos source = sourcePositions.get(i % sourcePositions.size());
                        pets.add(wolf(source, player, false, "batch pet " + i).getUUID());
                    }
                    for (BlockPos source : sourcePositions) {
                        for (int i = 0; i < (scattered ? 4 : 1); i++) {
                            addControl(wolf(source.offset(1, 0, 0), player, true, "sitting " + i));
                        }
                    }
                    addControl(wolf(remote.offset(2, 0, 0), other, false, "foreign"));
                    if (missingFirstBatch) {
                        ChunkPos absent = new ChunkPos(chunk.x() - 1, chunk.z());
                        for (int i = 0; i < 16; i++) {
                            UUID uuid = UUID.randomUUID();
                            missing.add(uuid);
                            PetIndexState.get(world.getServer()).put(new PetRecord(uuid, player.getUUID(),
                                    "minecraft:wolf", VersionCompat.getDimensionId(player), absent.pack(),
                                    absent.getMinBlockX() + 8, stand.getY(), absent.getMinBlockZ() + 8, false, 7.0F));
                        }
                    }
                    forceSources(false);
                    phase = 1;
                    return;
                }
                if (phase == 1) {
                    if (sourceChunks.stream().anyMatch(pos -> VersionCompat.areChunkEntitiesLoaded(world, pos))) return;
                    for (UUID uuid : pets) check(context, tracker.getLoadedPet(uuid) == null && world.getEntity(uuid) == null,
                            "Every pet must genuinely unload before the sole automatic trigger");
                    phase = 2;
                }
                if (phase == 3) {
                    if (sourceChunks.stream().anyMatch(pos -> VersionCompat.areChunkEntitiesLoaded(world, pos))) return;
                    forceSources(true);
                    phase = 4;
                    return;
                }
                if (phase == 4) {
                    if (!allSourcesLoaded()) return;
                    verify();
                    cleanup();
                    context.succeed();
                    return;
                }
                int near = nearCount();
                int delta = near - previousNear;
                check(context, delta >= 0 && delta <= 16, "Automatic recall must move at most 16 pets per server tick");
                if (delta > 0) batches++;
                previousNear = near;
                check(context, service.getDebugStats(world.getGameTime()).activeChunkOperations() <= 4,
                        "Chunk loading must respect the existing concurrency limit");
                if (jump && !jumped && near == 16 && !service.isRecallActive(player.getUUID())) {
                    jumped = true;
                    for (UUID uuid : pets) {
                        Wolf wolf = (Wolf) world.getEntity(uuid);
                        if (wolf.distanceToSqr(player) <= 64.0D) continue;
                        if (changed.isEmpty()) wolf.setOrderedToSit(true);
                        else wolf.setOwner(other);
                        changed.add(uuid);
                        if (changed.size() == 2) break;
                    }
                    player.snapTo(stand.getX() + 0.5D, stand.getY() + 4, stand.getZ() + 0.5D, 0, 0);
                    player.setOnGround(false);
                    pauseTicks = 6;
                    pauseNear = near;
                }
                if (pauseTicks > 0) {
                    check(context, near == pauseNear, "No more pets may teleport while the owner is airborne");
                    pauseTicks--;
                    if (pauseTicks == 0) {
                        player.snapTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 0, 0);
                        player.setOnGround(true);
                    }
                }
                for (ServerPlayer online : world.getServer().getPlayerList().getPlayers()) {
                    if (online != player) auto.suppressPlayer(online.getUUID());
                }
                auto.onServerTick(world.getServer());
                recallTicks++;
                check(context, recallTicks <= 200, "All eligible pets must arrive without another jump, chunk change or command");
                int expected = pets.size() - changed.size();
                if (near == expected && !service.isRecallActive(player.getUUID())) {
                    if (++settledTicks < 20) return;
                    check(context, batches >= (missingFirstBatch ? 2 : 3), "The group must span multiple automatic batches");
                    if (jump) check(context, jumped, "Fixture must exercise an airborne pause between batches");
                    var stats = service.getDebugStats(world.getGameTime());
                    check(context, stats.activePlayerRecalls() == 0 && stats.activePetRecalls() == 0
                            && stats.activeChunkOperations() == 0 && stats.queuedChunkOperations() == 0,
                            "All recall locks and chunk tickets must be released");
                    phase = 3;
                }
            } catch (RuntimeException | AssertionError error) {
                cleanup();
                throw error;
            }
        }

        int nearCount() {
            int count = 0;
            for (UUID uuid : pets) {
                Entity entity = world.getEntity(uuid);
                if (entity != null && entity.distanceToSqr(player) <= 64.0D) count++;
            }
            return count;
        }

        void verify() {
            for (UUID uuid : pets) {
                Entity entity = world.getEntity(uuid);
                check(context, entity instanceof Wolf, "Every original UUID must still exist after reloading the source");
                Wolf wolf = (Wolf) entity;
                check(context, wolf.getHealth() == 7.0F && wolf.entityTags().contains("auto_batch_state")
                        && wolf.getCustomName() != null && wolf.getCustomName().getString().startsWith("batch pet "),
                        "Pet health, name and tags must survive");
                check(context, changed.contains(uuid) ? wolf.distanceToSqr(player) > 144.0D
                        : wolf.distanceToSqr(player) <= 64.0D, "Only currently eligible queued pets may move");
                int copies = 0;
                for (Entity loaded : world.getAllEntities()) if (loaded.getUUID().equals(uuid)) copies++;
                check(context, copies == 1, "Source reload must not duplicate a pet UUID");
            }
            for (PetRecord before : controlRecords) {
                Entity entity = world.getEntity(before.petUuid());
                check(context, entity instanceof Wolf && entity.distanceToSqr(player) > 144.0D,
                        "Initially loaded, sitting and foreign pets must not be recalled");
                Wolf wolf = (Wolf) entity;
                check(context, wolf.getOwnerReference().getUUID().equals(before.ownerUuid())
                        && wolf.isOrderedToSit() == before.sitting() && wolf.blockPosition().equals(new BlockPos(
                                (int) Math.floor(before.x()), (int) Math.floor(before.y()), (int) Math.floor(before.z()))),
                        "Control pets must keep their owners, sitting state and original locations");
            }
            for (UUID uuid : missing) check(context, world.getEntity(uuid) == null, "Missing records must not manufacture pets");
        }

        void cleanup() {
            service.clearRuntime();
            auto.clearRuntime();
            forceSources(false);
            for (ChunkPos pos : destinationChunks) world.setChunkForced(pos.x(), pos.z(), false);
            for (List<UUID> group : List.of(pets, controls, missing)) {
                for (UUID uuid : group) {
                    Entity entity = world.getEntity(uuid);
                    tracker.removeRecord(world.getServer(), uuid);
                    if (entity != null) entity.discard();
                }
            }
            PetRecallMod.getAutoRecallController().resumePlayer(player.getUUID());
            PetRecallMod.getAutoRecallController().resumePlayer(other.getUUID());
        }
    }
}
