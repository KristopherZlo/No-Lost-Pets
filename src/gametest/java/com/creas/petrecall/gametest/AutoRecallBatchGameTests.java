package com.creas.petrecall.gametest;

import com.creas.petrecall.PetRecallMod;
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
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

public final class AutoRecallBatchGameTests {
    private static final AtomicInteger REMOTE_CHUNKS = new AtomicInteger();

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void oneTriggerRecallsFortyUnloadedPetsInBatches(TestContext context) {
        run(context, false, false, false, false);
    }

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void failedFirstBatchDoesNotStrandHealthyPets(TestContext context) {
        run(context, true, false, false, false);
    }

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void jumpPausesQueueAndCurrentPetStateIsRechecked(TestContext context) {
        run(context, false, true, false, false);
    }

    @GameTest(maxTicks = 1000, structure = "pet_recall_gametest:empty_32")
    public void scatteredStandingPetsRecallButSittingPetsStayInTheirChunks(TestContext context) {
        run(context, false, false, true, false);
    }

    @GameTest(maxTicks = 800, structure = "pet_recall_gametest:empty_32")
    public void jumpDuringChunkLoadingDoesNotDropTheInFlightBatch(TestContext context) {
        run(context, false, true, false, true);
    }

    private static void run(TestContext context, boolean missingFirstBatch, boolean jump, boolean scattered, boolean interruptLoading) {
        Fixture f = new Fixture(context, missingFirstBatch, jump, scattered, interruptLoading);
        context.runAtEveryTick(f::tick);
    }

    private static void check(TestContext context, boolean condition, String message) {
        context.assertTrue(condition, Text.literal(message));
    }

    private static final class Fixture {
        final TestContext context;
        final ServerWorld world;
        final PetTracker tracker = PetRecallMod.getTracker();
        final PetRecallService service = new PetRecallService(tracker);
        final AutoPetRecallController auto = new AutoPetRecallController(tracker, service);
        final ServerPlayerEntity player;
        final ServerPlayerEntity other;
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
        Fixture(TestContext context, boolean missingFirstBatch, boolean jump, boolean scattered, boolean interruptLoading) {
            this.context = context;
            this.world = context.getWorld();
            this.missingFirstBatch = missingFirstBatch;
            this.jump = jump;
            this.scattered = scattered;
            this.interruptLoading = interruptLoading;
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) context.setBlockState(new BlockPos(x, 1, z), Blocks.STONE);
            }
            this.stand = context.getAbsolutePos(new BlockPos(16, 2, 16));
            this.player = context.createMockCreativeServerPlayerInWorld();
            this.other = context.createMockCreativeServerPlayerInWorld();
            player.refreshPositionAndAngles(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 0, 0);
            other.refreshPositionAndAngles(context.getAbsolutePos(new BlockPos(2, 2, 2)), 0, 0);
            player.setNoGravity(true);
            player.setOnGround(true);
            PetRecallMod.getAutoRecallController().suppressPlayer(player.getUuid());
            PetRecallMod.getAutoRecallController().suppressPlayer(other.getUuid());
            for (int x : new int[] {0, 31}) {
                for (int z : new int[] {0, 31}) {
                    BlockPos absolute = context.getAbsolutePos(new BlockPos(x, 2, z));
                    ChunkPos destination = new ChunkPos(absolute.getX() >> 4, absolute.getZ() >> 4);
                    if (!destinationChunks.contains(destination)) destinationChunks.add(destination);
                    world.setChunkForced(destination.x, destination.z, true);
                }
            }
            addControl(wolf(context.getAbsolutePos(new BlockPos(2, 2, 2)), player, false, "already loaded"));
            this.chunk = new ChunkPos(8192 + REMOTE_CHUNKS.getAndIncrement() * 256, 8192);
            this.remote = new BlockPos(chunk.getStartX() + 8, stand.getY(), chunk.getStartZ() + 8);
            int sourceCount = scattered ? 5 : 1;
            for (int i = 0; i < sourceCount; i++) {
                ChunkPos sourceChunk = new ChunkPos(chunk.x + i * 37, chunk.z + i * 53);
                BlockPos pos = new BlockPos(sourceChunk.getStartX() + 8, stand.getY() + i * 12, sourceChunk.getStartZ() + 8);
                sourceChunks.add(sourceChunk);
                sourcePositions.add(pos);
                world.setChunkForced(sourceChunk.x, sourceChunk.z, true);
                world.getChunk(sourceChunk.x, sourceChunk.z);
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        world.setBlockState(pos.add(x, -1, z), Blocks.STONE.getDefaultState());
                        world.setBlockState(pos.add(x, 0, z), Blocks.AIR.getDefaultState());
                        world.setBlockState(pos.add(x, 1, z), Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        void addControl(WolfEntity wolf) {
            controls.add(wolf.getUuid());
            controlRecords.add(PetIndexState.get(world.getServer()).getPet(wolf.getUuid()));
        }

        boolean allSourcesLoaded() {
            return sourceChunks.stream().allMatch(pos -> VersionCompat.areChunkEntitiesLoaded(world, pos));
        }

        void forceSources(boolean forced) {
            for (ChunkPos pos : sourceChunks) world.setChunkForced(pos.x, pos.z, forced);
        }

        WolfEntity wolf(BlockPos pos, ServerPlayerEntity owner, boolean sitting, String name) {
            WolfEntity wolf = EntityType.WOLF.create(world, SpawnReason.COMMAND);
            check(context, wolf != null, "Fixture wolf must be created");
            wolf.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0, 0);
            wolf.setTamed(true, true);
            wolf.setOwner(owner);
            wolf.setSitting(sitting);
            wolf.setAiDisabled(true);
            wolf.setNoGravity(true);
            wolf.setPersistent();
            wolf.setHealth(7.0F);
            wolf.setCustomName(Text.literal(name));
            wolf.addCommandTag("auto_batch_state");
            check(context, world.spawnEntity(wolf), "Fixture wolf must spawn");
            tracker.observe(wolf, world);
            return wolf;
        }

        void tick() {
            try {
                if (interruptLoading && phase == 2 && !jumped && service.isRecallActive(player.getUuid())) {
                    jumped = true;
                    player.refreshPositionAndAngles(stand.getX() + 0.5D, stand.getY() + 4, stand.getZ() + 0.5D, 0, 0);
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
                        pets.add(wolf(source, player, false, "batch pet " + i).getUuid());
                    }
                    for (BlockPos source : sourcePositions) {
                        for (int i = 0; i < (scattered ? 4 : 1); i++) {
                            addControl(wolf(source.add(1, 0, 0), player, true, "sitting " + i));
                        }
                    }
                    addControl(wolf(remote.add(2, 0, 0), other, false, "foreign"));
                    if (missingFirstBatch) {
                        ChunkPos absent = new ChunkPos(chunk.x - 1, chunk.z);
                        for (int i = 0; i < 16; i++) {
                            UUID uuid = UUID.randomUUID();
                            missing.add(uuid);
                            PetIndexState.get(world.getServer()).put(new PetRecord(uuid, player.getUuid(),
                                    "minecraft:wolf", VersionCompat.getDimensionId(player), absent.toLong(),
                                    absent.getStartX() + 8, stand.getY(), absent.getStartZ() + 8, false, 7.0F));
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
                    context.complete();
                    return;
                }
                int near = nearCount();
                int delta = near - previousNear;
                check(context, delta >= 0 && delta <= 16, "Automatic recall must move at most 16 pets per server tick");
                if (delta > 0) batches++;
                previousNear = near;
                check(context, service.getDebugStats(world.getTime()).activeChunkOperations() <= 4,
                        "Chunk loading must respect the existing concurrency limit");
                if (jump && !jumped && near == 16 && !service.isRecallActive(player.getUuid())) {
                    jumped = true;
                    for (UUID uuid : pets) {
                        WolfEntity wolf = (WolfEntity) world.getEntity(uuid);
                        if (wolf.squaredDistanceTo(player) <= 64.0D) continue;
                        if (changed.isEmpty()) wolf.setSitting(true);
                        else wolf.setOwner(other);
                        changed.add(uuid);
                        if (changed.size() == 2) break;
                    }
                    player.refreshPositionAndAngles(stand.getX() + 0.5D, stand.getY() + 4, stand.getZ() + 0.5D, 0, 0);
                    player.setOnGround(false);
                    pauseTicks = 6;
                    pauseNear = near;
                }
                if (pauseTicks > 0) {
                    check(context, near == pauseNear, "No more pets may teleport while the owner is airborne");
                    pauseTicks--;
                    if (pauseTicks == 0) {
                        player.refreshPositionAndAngles(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 0, 0);
                        player.setOnGround(true);
                    }
                }
                for (ServerPlayerEntity online : world.getServer().getPlayerManager().getPlayerList()) {
                    if (online != player) auto.suppressPlayer(online.getUuid());
                }
                auto.onServerTick(world.getServer());
                recallTicks++;
                check(context, recallTicks <= 200, "All eligible pets must arrive without another jump, chunk change or command");
                int expected = pets.size() - changed.size();
                if (near == expected && !service.isRecallActive(player.getUuid())) {
                    if (++settledTicks < 20) return;
                    check(context, batches >= (missingFirstBatch ? 2 : 3), "The group must span multiple automatic batches");
                    if (jump) check(context, jumped, "Fixture must exercise an airborne pause between batches");
                    var stats = service.getDebugStats(world.getTime());
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
                if (entity != null && entity.squaredDistanceTo(player) <= 64.0D) count++;
            }
            return count;
        }

        void verify() {
            for (UUID uuid : pets) {
                Entity entity = world.getEntity(uuid);
                check(context, entity instanceof WolfEntity, "Every original UUID must still exist after reloading the source");
                WolfEntity wolf = (WolfEntity) entity;
                check(context, wolf.getHealth() == 7.0F && wolf.getCommandTags().contains("auto_batch_state")
                        && wolf.getCustomName() != null && wolf.getCustomName().getString().startsWith("batch pet "),
                        "Pet health, name and tags must survive");
                check(context, changed.contains(uuid) ? wolf.squaredDistanceTo(player) > 144.0D
                        : wolf.squaredDistanceTo(player) <= 64.0D, "Only currently eligible queued pets may move");
                int copies = 0;
                for (Entity loaded : world.iterateEntities()) if (loaded.getUuid().equals(uuid)) copies++;
                check(context, copies == 1, "Source reload must not duplicate a pet UUID");
            }
            for (PetRecord before : controlRecords) {
                Entity entity = world.getEntity(before.petUuid());
                check(context, entity instanceof WolfEntity && entity.squaredDistanceTo(player) > 144.0D,
                        "Initially loaded, sitting and foreign pets must not be recalled");
                WolfEntity wolf = (WolfEntity) entity;
                check(context, wolf.getOwnerReference().getUuid().equals(before.ownerUuid())
                        && wolf.isSitting() == before.sitting() && wolf.getBlockPos().equals(new BlockPos(
                                (int) Math.floor(before.x()), (int) Math.floor(before.y()), (int) Math.floor(before.z()))),
                        "Control pets must keep their owners, sitting state and original locations");
            }
            for (UUID uuid : missing) check(context, world.getEntity(uuid) == null, "Missing records must not manufacture pets");
        }

        void cleanup() {
            service.clearRuntime();
            auto.clearRuntime();
            forceSources(false);
            for (ChunkPos pos : destinationChunks) world.setChunkForced(pos.x, pos.z, false);
            for (List<UUID> group : List.of(pets, controls, missing)) {
                for (UUID uuid : group) {
                    Entity entity = world.getEntity(uuid);
                    tracker.removeRecord(world.getServer(), uuid);
                    if (entity != null) entity.discard();
                }
            }
            PetRecallMod.getAutoRecallController().resumePlayer(player.getUuid());
            PetRecallMod.getAutoRecallController().resumePlayer(other.getUuid());
        }
    }
}
