package com.creas.petrecall.selftest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.DebugTrace;
import com.creas.petrecall.util.VersionCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class PetRecallSelfTestService {
    private static final AtomicInteger NEXT_REMOTE_SUITE = new AtomicInteger();
    private static final int REMOTE_SUITE_STRIDE = 2048;
    private static final int REMOTE_BASE_OFFSET = 4096;
    private static final int UNLOADED_SETTLE_TICKS = 4;

    @Nullable
    private ActiveSuite activeSuite;

    public void onServerTick(MinecraftServer server) {
        if (this.activeSuite == null) {
            return;
        }
        if (this.activeSuite.server != server) {
            this.activeSuite = null;
            return;
        }
        this.activeSuite.tick();
    }

    public boolean hasActiveSuite() {
        return this.activeSuite != null;
    }

    public boolean startSingleplayer(ServerPlayer player, Consumer<Component> reporter) {
        if (this.activeSuite != null || !PetIndexState.isAvailable(VersionCompat.getServer(player))) {
            return false;
        }
        this.activeSuite = ActiveSuite.singleplayer(player, reporter, this::clearIfFinished);
        return true;
    }

    public boolean startMultiplayer(ServerPlayer owner, ServerPlayer other, Consumer<Component> reporter) {
        if (this.activeSuite != null || !PetIndexState.isAvailable(VersionCompat.getServer(owner))) {
            return false;
        }
        this.activeSuite = ActiveSuite.multiplayer(owner, other, reporter, this::clearIfFinished);
        return true;
    }

    public boolean cancel(String reason) {
        if (this.activeSuite == null) {
            return false;
        }
        this.activeSuite.finish(false, reason);
        return true;
    }

    public Component getStatusText() {
        if (this.activeSuite == null) {
            return Component.literal("NoLostPets self-test is idle.");
        }
        return Component.literal(this.activeSuite.describeStatus());
    }

    private void clearIfFinished(ActiveSuite suite) {
        if (this.activeSuite == suite) {
            this.activeSuite = null;
        }
    }

    private enum SuiteMode {
        SINGLEPLAYER,
        MULTIPLAYER
    }

    private interface Scenario {
        String name();

        int timeoutTicks();

        void start(ActiveSuite suite);

        ScenarioResult tick(ActiveSuite suite);
    }

    private record ScenarioResult(boolean finished, boolean passed, String message) {
        private static ScenarioResult running() {
            return new ScenarioResult(false, false, "");
        }

        private static ScenarioResult passed(String message) {
            return new ScenarioResult(true, true, message);
        }

        private static ScenarioResult failed(String message) {
            return new ScenarioResult(true, false, message);
        }
    }

    private abstract static class BaseScenario implements Scenario {
        private final String name;
        private final int timeoutTicks;
        private boolean started;
        private long startedAtTick;

        protected BaseScenario(String name, int timeoutTicks) {
            this.name = name;
            this.timeoutTicks = timeoutTicks;
        }

        @Override
        public final String name() {
            return this.name;
        }

        @Override
        public final int timeoutTicks() {
            return this.timeoutTicks;
        }

        @Override
        public final void start(ActiveSuite suite) {
            this.started = true;
            this.startedAtTick = suite.now();
            this.onStart(suite);
        }

        @Override
        public final ScenarioResult tick(ActiveSuite suite) {
            if (!this.started) {
                return ScenarioResult.failed("Scenario was not started correctly");
            }
            long elapsed = suite.now() - this.startedAtTick;
            if (elapsed > this.timeoutTicks) {
                return ScenarioResult.failed("Timed out after " + elapsed + " ticks");
            }
            return this.onTick(suite, elapsed);
        }

        protected abstract void onStart(ActiveSuite suite);

        protected abstract ScenarioResult onTick(ActiveSuite suite, long elapsedTicks);
    }

    private static final class ActiveSuite {
        private final MinecraftServer server;
        private final SuiteMode mode;
        private final ServerPlayer owner;
        @Nullable
        private final ServerPlayer otherPlayer;
        private final Consumer<Component> reporter;
        private final Consumer<ActiveSuite> onFinished;
        private final List<Scenario> scenarios;
        private final Snapshot ownerSnapshot;
        @Nullable
        private final Snapshot otherSnapshot;
        private final ServerLevel baseWorld;
        private final BlockPos ownerStandPos;
        private final BlockPos otherStandPos;
        private final int remoteSuiteOffset;
        private final List<UUID> touchedPetUuids = new ArrayList<>();
        private int scenarioIndex;
        private long scenarioStartTick;
        private boolean scenarioStarted;
        private boolean finished;
        @Nullable
        private RecallSummary latestSummary;
        private long suiteStartedTick;

        private ActiveSuite(
                MinecraftServer server,
                SuiteMode mode,
                ServerPlayer owner,
                @Nullable ServerPlayer otherPlayer,
                Consumer<Component> reporter,
                Consumer<ActiveSuite> onFinished,
                List<Scenario> scenarios
        ) {
            this.server = server;
            this.mode = mode;
            this.owner = owner;
            this.otherPlayer = otherPlayer;
            this.reporter = reporter;
            this.onFinished = onFinished;
            this.scenarios = scenarios;
            this.suiteStartedTick = server.overworld() == null ? 0L : server.overworld().getGameTime();
            this.ownerSnapshot = Snapshot.capture(owner);
            this.otherSnapshot = otherPlayer == null ? null : Snapshot.capture(otherPlayer);
            this.baseWorld = server.overworld();

            BlockPos origin = owner.blockPosition();
            int baseY = Math.max(origin.getY() + 24, 160);
            int baseX = origin.getX();
            int baseZ = origin.getZ() + 48;
            this.ownerStandPos = new BlockPos(baseX, baseY, baseZ);
            this.otherStandPos = this.ownerStandPos.offset(3, 0, 0);
            this.remoteSuiteOffset = REMOTE_BASE_OFFSET + NEXT_REMOTE_SUITE.getAndIncrement() * REMOTE_SUITE_STRIDE;

            PetRecallMod.getAutoRecallController().suppressPlayer(owner.getUUID());
            if (otherPlayer != null) {
                PetRecallMod.getAutoRecallController().suppressPlayer(otherPlayer.getUUID());
            }
        }

        private static ActiveSuite singleplayer(ServerPlayer owner, Consumer<Component> reporter, Consumer<ActiveSuite> onFinished) {
            MinecraftServer server = VersionCompat.getServer(owner);
            if (server == null) {
                throw new IllegalStateException("Owner server is unavailable for singleplayer self-test");
            }
            return new ActiveSuite(
                    server,
                    SuiteMode.SINGLEPLAYER,
                    owner,
                    null,
                    reporter,
                    onFinished,
                    List.of(
                            new LoadedRecallScenario(),
                            new UnloadedRecallScenario(),
                            new CrossDimensionBlockedScenario(),
                            new SittingLoadedSkipScenario(),
                            new SittingUnloadedSkipScenario(),
                            new AirbornePlayerBlockedScenario(),
                            new ShortGrassSafeSpotScenario(),
                            new UnsafeSurfaceSafeSpotScenario(),
                            new AutoRecallSpeedScenario(),
                            new BatchRecallScenario(),
                            new StaleRecordCleanupScenario()
                    )
            );
        }

        private static ActiveSuite multiplayer(ServerPlayer owner, ServerPlayer other, Consumer<Component> reporter, Consumer<ActiveSuite> onFinished) {
            MinecraftServer server = VersionCompat.getServer(owner);
            if (server == null) {
                throw new IllegalStateException("Owner server is unavailable for multiplayer self-test");
            }
            return new ActiveSuite(
                    server,
                    SuiteMode.MULTIPLAYER,
                    owner,
                    other,
                    reporter,
                    onFinished,
                    List.of(
                            new OwnershipLoadedScenario(),
                            new OwnershipUnloadedScenario()
                    )
            );
        }

        private void tick() {
            if (this.finished) {
                return;
            }

            if (this.owner.isRemoved() || (this.otherPlayer != null && this.otherPlayer.isRemoved())) {
                this.finish(false, "A participating player disconnected or became unavailable.");
                return;
            }
            this.keepPlayersGrounded();

            try {
                if (!this.scenarioStarted) {
                    if (this.scenarioIndex >= this.scenarios.size()) {
                        this.finish(true, "All " + this.mode.name().toLowerCase(java.util.Locale.ROOT) + " scenarios passed.");
                        return;
                    }

                    this.latestSummary = null;
                    this.resetScenarioEnvironment();
                    Scenario scenario = this.scenarios.get(this.scenarioIndex);
                    this.scenarioStartTick = this.now();
                    this.scenarioStarted = true;
                    this.report("Starting scenario " + (this.scenarioIndex + 1) + "/" + this.scenarios.size() + ": " + scenario.name());
                    scenario.start(this);
                }

                Scenario scenario = this.scenarios.get(this.scenarioIndex);
                ScenarioResult result = scenario.tick(this);
                if (!result.finished()) {
                    return;
                }

                if (!result.passed()) {
                    this.finish(false, "Scenario failed: " + scenario.name() + " -> " + result.message());
                    return;
                }

                this.report("Passed: " + scenario.name() + " -> " + result.message());
                this.cleanupTouchedPets();
                this.scenarioIndex++;
                this.scenarioStarted = false;
            } catch (RuntimeException e) {
                this.finish(false, "Scenario crashed: " + e.getMessage());
            }
        }

        private void finish(boolean passed, String message) {
            if (this.finished) {
                return;
            }
            this.finished = true;
            this.cleanupTouchedPets();
            this.restorePlayers();
            PetRecallMod.getAutoRecallController().resumePlayer(this.owner.getUUID());
            if (this.otherPlayer != null) {
                PetRecallMod.getAutoRecallController().resumePlayer(this.otherPlayer.getUUID());
            }
            this.report((passed ? "Self-test passed: " : "Self-test failed: ") + message);
            this.onFinished.accept(this);
        }

        private void restorePlayers() {
            this.ownerSnapshot.restore(this.owner);
            if (this.otherPlayer != null && this.otherSnapshot != null) {
                this.otherSnapshot.restore(this.otherPlayer);
            }
        }

        private void resetScenarioEnvironment() {
            this.cleanupTouchedPets();
            this.preparePad(this.baseWorld, this.ownerStandPos, 7);
            this.clearArea(this.baseWorld, this.ownerStandPos, 7, 5);
            this.teleportPlayer(this.owner, this.baseWorld, this.ownerStandPos);
            if (this.otherPlayer != null) {
                this.teleportPlayer(this.otherPlayer, this.baseWorld, this.otherStandPos);
            }
        }

        private void preparePad(ServerLevel world, BlockPos standPos, int radius) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos floorPos = standPos.offset(x, -1, z);
                    world.setBlockAndUpdate(floorPos, Blocks.STONE.defaultBlockState());
                    for (int y = 0; y <= 3; y++) {
                        world.setBlockAndUpdate(standPos.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        private void clearArea(ServerLevel world, BlockPos center, int radius, int height) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = 0; y <= height; y++) {
                        world.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        private void teleportPlayer(ServerPlayer player, ServerLevel world, BlockPos standPos) {
            player.teleport(new TeleportTransition(
                    world,
                    new Vec3(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D),
                    Vec3.ZERO,
                    player.getYRot(),
                    player.getXRot(),
                    TeleportTransition.DO_NOTHING
            ));
            player.setOnGround(true);
        }

        private void keepPlayersGrounded() {
            this.owner.setOnGround(true);
            if (this.otherPlayer != null) {
                this.otherPlayer.setOnGround(true);
            }
        }

        private long now() {
            return this.server.overworld() == null ? 0L : this.server.overworld().getGameTime();
        }

        private String describeStatus() {
            if (this.finished) {
                return "NoLostPets self-test has already finished.";
            }
            String scenarioName = this.scenarioIndex < this.scenarios.size() ? this.scenarios.get(this.scenarioIndex).name() : "<done>";
            return "NoLostPets self-test " + this.mode.name().toLowerCase(java.util.Locale.ROOT) +
                    " scenario=" + (this.scenarioIndex + 1) + "/" + this.scenarios.size() +
                    " name=" + scenarioName +
                    " elapsed=" + (this.now() - this.suiteStartedTick) + " ticks";
        }

        private void report(String message) {
            this.reporter.accept(Component.literal("[NoLostPets Self-Test] " + message));
            DebugTrace.log("self-test", "%s", message);
        }

        private BlockPos remoteStandPos(int offsetX) {
            return this.ownerStandPos.offset(this.remoteSuiteOffset + offsetX, 0, 0);
        }

        private void prepareRemotePad(int offsetX) {
            this.preparePad(this.baseWorld, this.remoteStandPos(offsetX), 3);
        }

        @Nullable
        private Wolf spawnOwnedWolf(ServerPlayer owner, BlockPos standPos, String name) {
            return this.spawnOwnedWolf(owner, standPos, name, false);
        }

        @Nullable
        private Wolf spawnOwnedWolf(ServerPlayer owner, BlockPos standPos, String name, boolean sitting) {
            this.preparePad(this.baseWorld, standPos, 2);
            Entity entity = TestEntityTypes.WOLF.spawn(this.baseWorld, standPos, EntitySpawnReason.COMMAND);
            if (!(entity instanceof Wolf wolf)) {
                return null;
            }
            wolf.setTame(true, true);
            wolf.setOwner(owner);
            wolf.setOrderedToSit(sitting);
            wolf.setCustomName(Component.literal(name));
            wolf.setCustomNameVisible(true);
            PetRecallMod.getTracker().observe(wolf, this.baseWorld);
            this.trackPetUuid(wolf.getUUID());
            return wolf;
        }

        private void trackPetUuid(UUID petUuid) {
            this.touchedPetUuids.add(petUuid);
        }

        private void cleanupTouchedPets() {
            if (this.touchedPetUuids.isEmpty()) {
                return;
            }
            PetTracker tracker = PetRecallMod.getTracker();
            for (UUID petUuid : this.touchedPetUuids) {
                Entity loaded = tracker.getLoadedPet(petUuid);
                if (loaded != null) {
                    loaded.discard();
                }
                tracker.removeRecord(this.server, petUuid);
            }
            this.touchedPetUuids.clear();
        }

        @Nullable
        private PetRecord getRecord(Entity entity) {
            return PetIndexState.get(this.server).getPet(entity.getUUID());
        }

        private boolean isPetLoaded(UUID petUuid) {
            return PetRecallMod.getTracker().getLoadedPet(petUuid) != null;
        }

        @Nullable
        private Entity getLoadedPet(UUID petUuid) {
            return PetRecallMod.getTracker().getLoadedPet(petUuid);
        }

        private void startTargetedRecall(ServerPlayer player, List<PetRecord> records, boolean includeLoadedPets) {
            boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, records, includeLoadedPets, summary -> this.latestSummary = summary);
            if (!started) {
                throw new IllegalStateException("Failed to start targeted recall");
            }
        }

        private void startSilentUnloadedRecall(ServerPlayer player, List<PetRecord> records) {
            boolean started = PetRecallMod.getRecallService().recallUnloadedForPlayerAsyncSilent(player, records, summary -> this.latestSummary = summary);
            if (!started) {
                throw new IllegalStateException("Failed to start silent unloaded recall");
            }
        }

        private void startDebugAutoRecall(ServerPlayer player, List<PetRecord> records) {
            boolean started = PetRecallMod.getAutoRecallController().debugRunImmediateCheck(player, records, summary -> this.latestSummary = summary);
            if (!started) {
                throw new IllegalStateException("Failed to start debug auto recall");
            }
        }

        private RecallSummary takeSummary() {
            RecallSummary summary = this.latestSummary;
            this.latestSummary = null;
            return summary;
        }

        private boolean isPetQuarantined(UUID petUuid) {
            return PetRecallMod.getRecallService().isPetQuarantined(petUuid, this.now());
        }

        private void putRecord(PetRecord record) {
            PetIndexState.get(this.server).put(record);
            this.trackPetUuid(record.petUuid());
        }
    }

    private record Snapshot(ServerLevel world, Vec3 position, float yaw, float pitch) {
        private static Snapshot capture(ServerPlayer player) {
            ServerLevel world = VersionCompat.getServerWorld(player);
            if (world == null) {
                throw new IllegalStateException("Player world is unavailable for self-test snapshot");
            }
            return new Snapshot(
                    world,
                    new Vec3(player.getX(), player.getY(), player.getZ()),
                    player.getYRot(),
                    player.getXRot()
            );
        }

        private void restore(ServerPlayer player) {
            player.teleport(new TeleportTransition(
                    this.world,
                    this.position,
                    Vec3.ZERO,
                    this.yaw,
                    this.pitch,
                    TeleportTransition.DO_NOTHING
            ));
            player.setOnGround(true);
        }
    }

    private static final class LoadedRecallScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);

        private LoadedRecallScenario() {
            super("loaded recall in same dimension", 80);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, suite.ownerStandPos.offset(4, 0, 0), "nlp_loaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for loaded wolf");
            }
            suite.startTargetedRecall(suite.owner, List.of(this.record), true);
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || summary.failed != 0 || recalled == null) {
                return ScenarioResult.failed("Expected one successful loaded recall");
            }
            if (recalled.distanceToSqr(suite.owner) > 64.0D) {
                return ScenarioResult.failed("Loaded pet did not end near the owner");
            }
            return ScenarioResult.passed("loaded recall completed in " + elapsedTicks + " ticks");
        }
    }

    private static final class UnloadedRecallScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private int phase;
        private long unloadedAtTick = -1L;

        private UnloadedRecallScenario() {
            super("unloaded recall in same dimension", 220);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos remotePos = suite.remoteStandPos(512);
            suite.prepareRemotePad(512);
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, remotePos, "nlp_unloaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn remote test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for unloaded wolf");
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            this.phase = 0;
            this.unloadedAtTick = -1L;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            if (this.phase == 0) {
                if (suite.isPetLoaded(this.petUuid)) {
                    this.unloadedAtTick = -1L;
                    return ScenarioResult.running();
                }
                if (this.unloadedAtTick < 0L) {
                    this.unloadedAtTick = suite.now();
                    return ScenarioResult.running();
                }
                if (suite.now() - this.unloadedAtTick < UNLOADED_SETTLE_TICKS) {
                    return ScenarioResult.running();
                }
                suite.startTargetedRecall(suite.owner, List.of(this.record), true);
                this.phase = 1;
                return ScenarioResult.running();
            }

            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || summary.failed != 0 || recalled == null) {
                return ScenarioResult.failed("Expected one successful unloaded recall");
            }
            if (recalled.distanceToSqr(suite.owner) > 64.0D) {
                return ScenarioResult.failed("Unloaded pet did not end near the owner");
            }
            return ScenarioResult.passed("unloaded recall completed in " + elapsedTicks + " ticks");
        }
    }

    private static final class CrossDimensionBlockedScenario extends BaseScenario {
        private CrossDimensionBlockedScenario() {
            super("cross-dimension recall is blocked", 40);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            PetRecord record = new PetRecord(
                    UUID.randomUUID(),
                    suite.owner.getUUID(),
                    "minecraft:wolf",
                    "minecraft:the_nether",
                    new ChunkPos(0, 0).pack(),
                    0.5D,
                    suite.ownerStandPos.getY(),
                    0.5D,
                    false,
                    20.0F
            );
            suite.startTargetedRecall(suite.owner, List.of(record), true);
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            if (summary.skipped != 1 || summary.recalled != 0 || summary.failed != 0) {
                return ScenarioResult.failed("Expected cross-dimension record to be skipped");
            }
            return ScenarioResult.passed("cross-dimension recall was skipped in " + elapsedTicks + " ticks");
        }
    }

    private static final class SittingLoadedSkipScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private BlockPos originalPos = BlockPos.ZERO;

        private SittingLoadedSkipScenario() {
            super("loaded sitting pet is skipped", 40);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos spawnPos = suite.ownerStandPos.offset(10, 0, 0);
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, spawnPos, "nlp_sit_loaded", true);
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn sitting loaded test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.originalPos = wolf.blockPosition();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for sitting loaded wolf");
            }
            suite.startTargetedRecall(suite.owner, List.of(this.record), true);
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            Entity loaded = suite.getLoadedPet(this.petUuid);
            if (summary.skipped != 1 || summary.recalled != 0 || summary.failed != 0 || loaded == null) {
                return ScenarioResult.failed("Expected sitting loaded pet to be skipped without failure");
            }
            if (!loaded.blockPosition().equals(this.originalPos)) {
                return ScenarioResult.failed("Sitting loaded pet moved from " + this.originalPos + " to " + loaded.blockPosition());
            }
            return ScenarioResult.passed("sitting loaded pet stayed in place after " + elapsedTicks + " ticks");
        }
    }

    private static final class SittingUnloadedSkipScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private int phase;
        private long unloadedAtTick = -1L;

        private SittingUnloadedSkipScenario() {
            super("unloaded sitting pet is skipped", 160);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos remotePos = suite.remoteStandPos(576);
            suite.prepareRemotePad(576);
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, remotePos, "nlp_sit_unloaded", true);
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn sitting unloaded test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for sitting unloaded wolf");
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            this.phase = 0;
            this.unloadedAtTick = -1L;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            if (this.phase == 0) {
                if (suite.isPetLoaded(this.petUuid)) {
                    this.unloadedAtTick = -1L;
                    return ScenarioResult.running();
                }
                if (this.unloadedAtTick < 0L) {
                    this.unloadedAtTick = suite.now();
                    return ScenarioResult.running();
                }
                if (suite.now() - this.unloadedAtTick < UNLOADED_SETTLE_TICKS) {
                    return ScenarioResult.running();
                }
                suite.startTargetedRecall(suite.owner, List.of(this.record), true);
                this.phase = 1;
                return ScenarioResult.running();
            }

            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            if (summary.skipped != 1 || summary.recalled != 0 || summary.failed != 0) {
                return ScenarioResult.failed("Expected sitting unloaded pet to be skipped without failure");
            }
            if (PetIndexState.get(suite.server).getPet(this.petUuid) == null) {
                return ScenarioResult.failed("Sitting unloaded pet should stay indexed");
            }
            Entity pet = suite.getLoadedPet(this.petUuid);
            if (!(pet instanceof Wolf wolf) || !wolf.isOrderedToSit()
                    || !wolf.getOwnerReference().getUUID().equals(suite.owner.getUUID())
                    || !wolf.blockPosition().equals(BlockPos.containing(this.record.x(), this.record.y(), this.record.z()))) {
                return ScenarioResult.failed("Sitting pet must retain its owner, state and source position");
            }
            return ScenarioResult.passed("sitting unloaded pet stayed skipped after " + elapsedTicks + " ticks");
        }
    }

    private static final class AirbornePlayerBlockedScenario extends BaseScenario {
        @Nullable
        private PetRecord record;

        private AirbornePlayerBlockedScenario() {
            super("player must stand on ground before recall", 40);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, suite.ownerStandPos.offset(4, 0, 0), "nlp_airborne");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn airborne test wolf");
            }
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for airborne test wolf");
            }

            BlockPos airbornePos = suite.ownerStandPos.above(2);
            suite.owner.teleport(new TeleportTransition(
                    suite.baseWorld,
                    new Vec3(airbornePos.getX() + 0.5D, airbornePos.getY(), airbornePos.getZ() + 0.5D),
                    Vec3.ZERO,
                    suite.owner.getYRot(),
                    suite.owner.getXRot(),
                    TeleportTransition.DO_NOTHING
            ));
            suite.owner.setOnGround(false);
            suite.startTargetedRecall(suite.owner, List.of(this.record), true);
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            if (summary.failed != 1 || summary.recalled != 0) {
                return ScenarioResult.failed("Expected airborne recall attempt to fail immediately");
            }
            boolean hasGroundMessage = summary.messages.stream().anyMatch(message -> message.contains("Stand on the ground"));
            if (!hasGroundMessage) {
                return ScenarioResult.failed("Expected airborne recall to explain the ground requirement");
            }
            return ScenarioResult.passed("airborne recall was blocked in " + elapsedTicks + " ticks");
        }
    }

    private static final class ShortGrassSafeSpotScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private BlockPos expectedSpot = BlockPos.ZERO;

        private ShortGrassSafeSpotScenario() {
            super("short grass is treated as safe space", 80);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos center = suite.ownerStandPos;
            BlockPos candidate = center.offset(-1, 0, -1);
            this.expectedSpot = candidate;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos ringPos = center.offset(x, 0, z);
                    if (ringPos.equals(center)) {
                        continue;
                    }
                    if (ringPos.equals(candidate)) {
                        suite.baseWorld.setBlockAndUpdate(ringPos, Blocks.SHORT_GRASS.defaultBlockState());
                    } else {
                        suite.baseWorld.setBlockAndUpdate(ringPos, Blocks.STONE.defaultBlockState());
                    }
                }
            }

            Wolf wolf = suite.spawnOwnedWolf(suite.owner, center.offset(4, 0, 0), "nlp_grass");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn grass test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for grass scenario");
            }
            suite.startTargetedRecall(suite.owner, List.of(this.record), true);
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || recalled == null) {
                return ScenarioResult.failed("Expected pet to recall onto short grass");
            }
            if (!recalled.blockPosition().equals(this.expectedSpot)) {
                return ScenarioResult.failed("Expected pet on " + this.expectedSpot + " but got " + recalled.blockPosition());
            }
            return ScenarioResult.passed("short grass safe spot selected in " + elapsedTicks + " ticks");
        }
    }

    private static final class UnsafeSurfaceSafeSpotScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private BlockPos expectedSpot = BlockPos.ZERO;

        private UnsafeSurfaceSafeSpotScenario() {
            super("water and leaves are treated as unsafe recall spots", 80);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos center = suite.ownerStandPos;
            this.expectedSpot = center.offset(-1, 0, -1);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos ringPos = center.offset(x, 0, z);
                    if (ringPos.equals(center)) {
                        continue;
                    }

                    BlockPos floorPos = ringPos.below();
                    if (ringPos.equals(this.expectedSpot)) {
                        suite.baseWorld.setBlockAndUpdate(floorPos, Blocks.STONE.defaultBlockState());
                        suite.baseWorld.setBlockAndUpdate(ringPos, Blocks.AIR.defaultBlockState());
                        continue;
                    }

                    if (((x + z) & 1) == 0) {
                        suite.baseWorld.setBlockAndUpdate(floorPos, Blocks.OAK_LEAVES.defaultBlockState());
                        suite.baseWorld.setBlockAndUpdate(ringPos, Blocks.AIR.defaultBlockState());
                    } else {
                        suite.baseWorld.setBlockAndUpdate(floorPos, Blocks.STONE.defaultBlockState());
                        suite.baseWorld.setBlockAndUpdate(ringPos, Blocks.WATER.defaultBlockState());
                    }
                }
            }

            Wolf wolf = suite.spawnOwnedWolf(suite.owner, center.offset(4, 0, 0), "nlp_unsafe_surface");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn unsafe-surface test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for unsafe-surface scenario");
            }
            suite.startTargetedRecall(suite.owner, List.of(this.record), true);
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || recalled == null) {
                return ScenarioResult.failed("Expected pet to avoid water and leaves");
            }
            if (!recalled.blockPosition().equals(this.expectedSpot)) {
                return ScenarioResult.failed("Expected pet on " + this.expectedSpot + " but got " + recalled.blockPosition());
            }
            return ScenarioResult.passed("unsafe water/leaves spots were ignored in " + elapsedTicks + " ticks");
        }
    }

    private static final class AutoRecallSpeedScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private int phase;
        private long recallStartedAtTick = -1L;

        private AutoRecallSpeedScenario() {
            super("auto recall path runs quickly", 180);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos remotePos = suite.remoteStandPos(640);
            suite.prepareRemotePad(640);
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, remotePos, "nlp_auto");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn auto-recall test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for auto-recall scenario");
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            this.phase = 0;
            this.recallStartedAtTick = -1L;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            if (this.phase == 0) {
                if (suite.isPetLoaded(this.petUuid)) {
                    return ScenarioResult.running();
                }
                suite.startDebugAutoRecall(suite.owner, List.of(this.record));
                this.recallStartedAtTick = suite.now();
                this.phase = 1;
                return ScenarioResult.running();
            }

            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || recalled == null) {
                return ScenarioResult.failed("Expected debug auto recall to recall exactly one pet");
            }
            long recallTicks = this.recallStartedAtTick >= 0L ? Math.max(0L, suite.now() - this.recallStartedAtTick) : elapsedTicks;
            if (recallTicks > 40L) {
                return ScenarioResult.failed("Debug auto recall took too long: " + recallTicks + " ticks");
            }
            return ScenarioResult.passed("debug auto recall completed in " + recallTicks + " ticks");
        }
    }

    private static final class BatchRecallScenario extends BaseScenario {
        private final List<PetRecord> records = new ArrayList<>();
        private final List<UUID> petUuids = new ArrayList<>();
        private int phase;
        private long recallStartedAtTick = -1L;

        private BatchRecallScenario() {
            super("batch recall has low inter-pet delay", 260);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            int[] offsets = {768, 800, 832, 864};
            for (int i = 0; i < offsets.length; i++) {
                int offset = offsets[i];
                suite.prepareRemotePad(offset);
                Wolf wolf = suite.spawnOwnedWolf(suite.owner, suite.remoteStandPos(offset), "nlp_batch_" + i);
                if (wolf == null) {
                    throw new IllegalStateException("Could not spawn batch test wolf " + i);
                }
                this.petUuids.add(wolf.getUUID());
                PetRecord record = suite.getRecord(wolf);
                if (record == null) {
                    throw new IllegalStateException("Missing indexed record for batch wolf " + i);
                }
                this.records.add(record);
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            this.phase = 0;
            this.recallStartedAtTick = -1L;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            if (this.phase == 0) {
                for (UUID petUuid : this.petUuids) {
                    if (suite.isPetLoaded(petUuid)) {
                        return ScenarioResult.running();
                    }
                }
                suite.startTargetedRecall(suite.owner, this.records, true);
                this.recallStartedAtTick = suite.now();
                this.phase = 1;
                return ScenarioResult.running();
            }

            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }
            if (summary.recalled != this.records.size() || summary.failed != 0) {
                return ScenarioResult.failed("Expected all batch pets to recall successfully");
            }
            long recallTicks = this.recallStartedAtTick >= 0L ? Math.max(0L, suite.now() - this.recallStartedAtTick) : elapsedTicks;
            if (recallTicks > 180L) {
                return ScenarioResult.failed("Batch recall took too long: " + recallTicks + " ticks");
            }
            return ScenarioResult.passed("batch recall completed in " + recallTicks + " ticks");
        }
    }

    private static final class StaleRecordCleanupScenario extends BaseScenario {
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private int phase;

        private StaleRecordCleanupScenario() {
            super("stale unloaded records are removed after repeated misses", 1200);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            this.petUuid = UUID.randomUUID();
            this.record = new PetRecord(
                    this.petUuid,
                    suite.owner.getUUID(),
                    "minecraft:wolf",
                    suite.baseWorld.dimension().identifier().toString(),
                    new ChunkPos(1536, 1536).pack(),
                    24576.5D,
                    suite.ownerStandPos.getY(),
                    24576.5D,
                    false,
                    20.0F
            );
            suite.putRecord(this.record);
            suite.startSilentUnloadedRecall(suite.owner, List.of(this.record));
            this.phase = 0;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary != null) {
                if (this.phase == 0) {
                    if (summary.failed != 1 || PetIndexState.get(suite.server).getPet(this.petUuid) == null || !suite.isPetQuarantined(this.petUuid)) {
                        return ScenarioResult.failed("Expected first stale miss to fail and enter quarantine");
                    }
                    suite.startSilentUnloadedRecall(suite.owner, List.of(this.record));
                    this.phase = 2;
                    return ScenarioResult.running();
                }
                if (this.phase == 2) {
                    if (summary.failed != 1 || PetIndexState.get(suite.server).getPet(this.petUuid) == null || !suite.isPetQuarantined(this.petUuid)) {
                        return ScenarioResult.failed("Expected second stale miss to fail and keep the record quarantined");
                    }
                    suite.startSilentUnloadedRecall(suite.owner, List.of(this.record));
                    this.phase = 4;
                    return ScenarioResult.running();
                }
                if (this.phase == 4) {
                    if (summary.failed != 1) {
                        return ScenarioResult.failed("Expected third stale miss to fail");
                    }
                    if (PetIndexState.get(suite.server).getPet(this.petUuid) != null) {
                        return ScenarioResult.failed("Expected stale record to be removed after the third miss");
                    }
                    if (suite.isPetQuarantined(this.petUuid)) {
                        return ScenarioResult.failed("Expected stale quarantine state to clear after record removal");
                    }
                    return ScenarioResult.passed("stale record removed after repeated misses in " + elapsedTicks + " ticks");
                }
            }

            return ScenarioResult.running();
        }
    }

    private static final class OwnershipLoadedScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private int phase;

        private OwnershipLoadedScenario() {
            super("other player cannot recall a loaded foreign pet", 120);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            if (suite.otherPlayer == null) {
                throw new IllegalStateException("Other player is required for multiplayer suite");
            }
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, suite.ownerStandPos.offset(5, 0, 0), "nlp_owner_loaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn ownership test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for ownership test wolf");
            }
            suite.startTargetedRecall(suite.otherPlayer, List.of(this.record), true);
            this.phase = 0;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }

            if (this.phase == 0) {
                if (summary.failed != 1) {
                    return ScenarioResult.failed("Expected the non-owner to fail loaded recall");
                }
                Entity loaded = suite.getLoadedPet(this.petUuid);
                if (loaded == null || suite.getRecord(loaded) == null) {
                    return ScenarioResult.failed("Ownership failure should not delete the indexed record");
                }
                suite.startTargetedRecall(suite.owner, List.of(this.record), true);
                this.phase = 1;
                return ScenarioResult.running();
            }

            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || recalled == null) {
                return ScenarioResult.failed("Expected the owner to recall the loaded pet");
            }
            return ScenarioResult.passed("ownership was preserved for loaded recall in " + elapsedTicks + " ticks");
        }
    }

    private static final class OwnershipUnloadedScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private int phase;
        private long unloadedAtTick = -1L;

        private OwnershipUnloadedScenario() {
            super("other player cannot recall an unloaded foreign pet", 240);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            if (suite.otherPlayer == null) {
                throw new IllegalStateException("Other player is required for multiplayer suite");
            }
            BlockPos remotePos = suite.remoteStandPos(960);
            suite.prepareRemotePad(960);
            Wolf wolf = suite.spawnOwnedWolf(suite.owner, remotePos, "nlp_owner_unloaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn unloaded ownership test wolf");
            }
            this.petUuid = wolf.getUUID();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for unloaded ownership test wolf");
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            suite.teleportPlayer(suite.otherPlayer, suite.baseWorld, suite.otherStandPos);
            this.phase = 0;
            this.unloadedAtTick = -1L;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            if (this.phase == 0) {
                if (suite.isPetLoaded(this.petUuid)) {
                    this.unloadedAtTick = -1L;
                    return ScenarioResult.running();
                }
                if (this.unloadedAtTick < 0L) {
                    this.unloadedAtTick = suite.now();
                    return ScenarioResult.running();
                }
                if (suite.now() - this.unloadedAtTick < UNLOADED_SETTLE_TICKS) {
                    return ScenarioResult.running();
                }
                suite.startTargetedRecall(suite.otherPlayer, List.of(this.record), true);
                this.phase = 1;
                return ScenarioResult.running();
            }

            RecallSummary summary = suite.takeSummary();
            if (summary == null) {
                return ScenarioResult.running();
            }

            if (this.phase == 1) {
                if (summary.failed != 1) {
                    return ScenarioResult.failed("Expected the non-owner to fail unloaded recall");
                }
                if (PetIndexState.get(suite.server).getPet(this.petUuid) == null) {
                    return ScenarioResult.failed("Ownership failure should keep the indexed record");
                }
                suite.startTargetedRecall(suite.owner, List.of(this.record), true);
                this.phase = 2;
                return ScenarioResult.running();
            }


            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || recalled == null) {
                return ScenarioResult.failed("Expected the owner to recall the unloaded pet");
            }
            return ScenarioResult.passed("ownership was preserved for unloaded recall in " + elapsedTicks + " ticks");
        }
    }
}
