package com.creas.petrecall.selftest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.util.DebugTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.jetbrains.annotations.Nullable;

public final class PetRecallSelfTestService {
    private static final AtomicInteger NEXT_REMOTE_SUITE = new AtomicInteger();
    private static final int REMOTE_SUITE_STRIDE = 2048;
    private static final int REMOTE_BASE_OFFSET = 4096;
    private static final int UNLOADED_SETTLE_TICKS = 4;
    private static final int UNLOADED_RETRY_DELAY_TICKS = 4;

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

    public boolean startSingleplayer(ServerPlayerEntity player, Consumer<Text> reporter) {
        if (this.activeSuite != null) {
            return false;
        }
        this.activeSuite = ActiveSuite.singleplayer(player, reporter, this::clearIfFinished);
        return true;
    }

    public boolean startMultiplayer(ServerPlayerEntity owner, ServerPlayerEntity other, Consumer<Text> reporter) {
        if (this.activeSuite != null) {
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

    public Text getStatusText() {
        if (this.activeSuite == null) {
            return Text.literal("NoLostPets self-test is idle.");
        }
        return Text.literal(this.activeSuite.describeStatus());
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
        private final ServerPlayerEntity owner;
        @Nullable
        private final ServerPlayerEntity otherPlayer;
        private final Consumer<Text> reporter;
        private final Consumer<ActiveSuite> onFinished;
        private final List<Scenario> scenarios;
        private final Snapshot ownerSnapshot;
        @Nullable
        private final Snapshot otherSnapshot;
        private final ServerWorld baseWorld;
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
                ServerPlayerEntity owner,
                @Nullable ServerPlayerEntity otherPlayer,
                Consumer<Text> reporter,
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
            this.suiteStartedTick = server.getOverworld() == null ? 0L : server.getOverworld().getTime();
            this.ownerSnapshot = Snapshot.capture(owner);
            this.otherSnapshot = otherPlayer == null ? null : Snapshot.capture(otherPlayer);
            this.baseWorld = server.getOverworld();

            BlockPos origin = owner.getBlockPos();
            int baseY = Math.max(origin.getY() + 24, 160);
            int baseX = origin.getX();
            int baseZ = origin.getZ() + 48;
            this.ownerStandPos = new BlockPos(baseX, baseY, baseZ);
            this.otherStandPos = this.ownerStandPos.add(3, 0, 0);
            this.remoteSuiteOffset = REMOTE_BASE_OFFSET + NEXT_REMOTE_SUITE.getAndIncrement() * REMOTE_SUITE_STRIDE;

            PetRecallMod.getAutoRecallController().suppressPlayer(owner.getUuid());
            if (otherPlayer != null) {
                PetRecallMod.getAutoRecallController().suppressPlayer(otherPlayer.getUuid());
            }
        }

        private static ActiveSuite singleplayer(ServerPlayerEntity owner, Consumer<Text> reporter, Consumer<ActiveSuite> onFinished) {
            return new ActiveSuite(
                    owner.getServer(),
                    SuiteMode.SINGLEPLAYER,
                    owner,
                    null,
                    reporter,
                    onFinished,
                    List.of(
                            new LoadedRecallScenario(),
                            new UnloadedRecallScenario(),
                            new CrossDimensionBlockedScenario(),
                            new ShortGrassSafeSpotScenario(),
                            new AutoRecallSpeedScenario(),
                            new BatchRecallScenario()
                    )
            );
        }

        private static ActiveSuite multiplayer(ServerPlayerEntity owner, ServerPlayerEntity other, Consumer<Text> reporter, Consumer<ActiveSuite> onFinished) {
            return new ActiveSuite(
                    owner.getServer(),
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
            PetRecallMod.getAutoRecallController().resumePlayer(this.owner.getUuid());
            if (this.otherPlayer != null) {
                PetRecallMod.getAutoRecallController().resumePlayer(this.otherPlayer.getUuid());
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

        private void preparePad(ServerWorld world, BlockPos standPos, int radius) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos floorPos = standPos.add(x, -1, z);
                    world.setBlockState(floorPos, Blocks.STONE.getDefaultState());
                    for (int y = 0; y <= 3; y++) {
                        world.setBlockState(standPos.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        private void clearArea(ServerWorld world, BlockPos center, int radius, int height) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = 0; y <= height; y++) {
                        world.setBlockState(center.add(x, y, z), Blocks.AIR.getDefaultState());
                    }
                }
            }
        }

        private void teleportPlayer(ServerPlayerEntity player, ServerWorld world, BlockPos standPos) {
            player.teleportTo(new TeleportTarget(
                    world,
                    new Vec3d(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D),
                    Vec3d.ZERO,
                    player.getYaw(),
                    player.getPitch(),
                    TeleportTarget.NO_OP
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
            return this.server.getOverworld() == null ? 0L : this.server.getOverworld().getTime();
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
            this.reporter.accept(Text.literal("[NoLostPets Self-Test] " + message));
            DebugTrace.log("self-test", "%s", message);
        }

        private BlockPos remoteStandPos(int offsetX) {
            return this.ownerStandPos.add(this.remoteSuiteOffset + offsetX, 0, 0);
        }

        private void prepareRemotePad(int offsetX) {
            this.preparePad(this.baseWorld, this.remoteStandPos(offsetX), 3);
        }

        @Nullable
        private WolfEntity spawnOwnedWolf(ServerPlayerEntity owner, BlockPos standPos, String name) {
            this.preparePad(this.baseWorld, standPos, 2);
            Entity entity = EntityType.WOLF.spawn(this.baseWorld, null, standPos, SpawnReason.COMMAND, true, false);
            if (!(entity instanceof WolfEntity wolf)) {
                return null;
            }
            wolf.setTamed(true, true);
            wolf.setOwner(owner);
            wolf.setSitting(false);
            wolf.setCustomName(Text.literal(name));
            wolf.setCustomNameVisible(true);
            PetRecallMod.getTracker().observe(wolf, this.baseWorld);
            this.touchedPetUuids.add(wolf.getUuid());
            return wolf;
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
            return PetIndexState.get(this.server).getPet(entity.getUuid());
        }

        private boolean isPetLoaded(UUID petUuid) {
            return PetRecallMod.getTracker().getLoadedPet(petUuid) != null;
        }

        @Nullable
        private Entity getLoadedPet(UUID petUuid) {
            return PetRecallMod.getTracker().getLoadedPet(petUuid);
        }

        private void startTargetedRecall(ServerPlayerEntity player, List<PetRecord> records, boolean includeLoadedPets) {
            boolean started = PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player, records, includeLoadedPets, summary -> this.latestSummary = summary);
            if (!started) {
                throw new IllegalStateException("Failed to start targeted recall");
            }
        }

        private void startDebugAutoRecall(ServerPlayerEntity player, List<PetRecord> records) {
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

        private boolean isTransientUnloadedMiss(@Nullable RecallSummary summary, UUID petUuid) {
            if (summary == null || summary.recalled != 0 || summary.failed != 1) {
                return false;
            }

            String petId = petUuid.toString();
            for (String message : summary.messages) {
                if (message.contains(petId) && (message.contains("Pet not found in indexed chunk")
                        || message.contains("Entity chunk missing")
                        || message.contains("retry in"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Snapshot(ServerWorld world, Vec3d position, float yaw, float pitch) {
        private static Snapshot capture(ServerPlayerEntity player) {
            return new Snapshot(
                    (ServerWorld) player.getWorld(),
                    player.getPos(),
                    player.getYaw(),
                    player.getPitch()
            );
        }

        private void restore(ServerPlayerEntity player) {
            player.teleportTo(new TeleportTarget(
                    this.world,
                    this.position,
                    Vec3d.ZERO,
                    this.yaw,
                    this.pitch,
                    TeleportTarget.NO_OP
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
            WolfEntity wolf = suite.spawnOwnedWolf(suite.owner, suite.ownerStandPos.add(4, 0, 0), "nlp_loaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn test wolf");
            }
            this.petUuid = wolf.getUuid();
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
            if (recalled.squaredDistanceTo(suite.owner) > 64.0D) {
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
        private long retryAtTick = -1L;
        private int retries;

        private UnloadedRecallScenario() {
            super("unloaded recall in same dimension", 220);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos remotePos = suite.remoteStandPos(512);
            suite.prepareRemotePad(512);
            WolfEntity wolf = suite.spawnOwnedWolf(suite.owner, remotePos, "nlp_unloaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn remote test wolf");
            }
            this.petUuid = wolf.getUuid();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for unloaded wolf");
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            this.phase = 0;
            this.unloadedAtTick = -1L;
            this.retryAtTick = -1L;
            this.retries = 0;
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

            if (this.phase == 2) {
                if (suite.now() < this.retryAtTick) {
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
            if (suite.isTransientUnloadedMiss(summary, this.petUuid) && this.retries < 2) {
                this.retries++;
                this.retryAtTick = suite.now() + UNLOADED_RETRY_DELAY_TICKS;
                this.phase = 2;
                suite.report("Retrying unloaded recall in same dimension after transient chunk miss (" + this.retries + "/2)");
                return ScenarioResult.running();
            }
            Entity recalled = suite.getLoadedPet(this.petUuid);
            if (summary.recalled != 1 || summary.failed != 0 || recalled == null) {
                return ScenarioResult.failed("Expected one successful unloaded recall");
            }
            if (recalled.squaredDistanceTo(suite.owner) > 64.0D) {
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
                    suite.owner.getUuid(),
                    "minecraft:wolf",
                    "minecraft:the_nether",
                    new ChunkPos(0, 0).toLong(),
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

    private static final class ShortGrassSafeSpotScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private BlockPos expectedSpot = BlockPos.ORIGIN;

        private ShortGrassSafeSpotScenario() {
            super("short grass is treated as safe space", 80);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos center = suite.ownerStandPos;
            BlockPos candidate = center.add(-1, 0, -1);
            this.expectedSpot = candidate;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos ringPos = center.add(x, 0, z);
                    if (ringPos.equals(center)) {
                        continue;
                    }
                    if (ringPos.equals(candidate)) {
                        suite.baseWorld.setBlockState(ringPos, Blocks.SHORT_GRASS.getDefaultState());
                    } else {
                        suite.baseWorld.setBlockState(ringPos, Blocks.STONE.getDefaultState());
                    }
                }
            }

            WolfEntity wolf = suite.spawnOwnedWolf(suite.owner, center.add(4, 0, 0), "nlp_grass");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn grass test wolf");
            }
            this.petUuid = wolf.getUuid();
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
            if (!recalled.getBlockPos().equals(this.expectedSpot)) {
                return ScenarioResult.failed("Expected pet on " + this.expectedSpot + " but got " + recalled.getBlockPos());
            }
            return ScenarioResult.passed("short grass safe spot selected in " + elapsedTicks + " ticks");
        }
    }

    private static final class AutoRecallSpeedScenario extends BaseScenario {
        @Nullable
        private PetRecord record;
        private UUID petUuid = new UUID(0L, 0L);
        private int phase;

        private AutoRecallSpeedScenario() {
            super("auto recall path runs quickly", 180);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            BlockPos remotePos = suite.remoteStandPos(640);
            suite.prepareRemotePad(640);
            WolfEntity wolf = suite.spawnOwnedWolf(suite.owner, remotePos, "nlp_auto");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn auto-recall test wolf");
            }
            this.petUuid = wolf.getUuid();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for auto-recall scenario");
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            this.phase = 0;
        }

        @Override
        protected ScenarioResult onTick(ActiveSuite suite, long elapsedTicks) {
            if (this.phase == 0) {
                if (suite.isPetLoaded(this.petUuid)) {
                    return ScenarioResult.running();
                }
                suite.startDebugAutoRecall(suite.owner, List.of(this.record));
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
            if (elapsedTicks > 30L) {
                return ScenarioResult.failed("Debug auto recall took too long: " + elapsedTicks + " ticks");
            }
            return ScenarioResult.passed("debug auto recall completed in " + elapsedTicks + " ticks");
        }
    }

    private static final class BatchRecallScenario extends BaseScenario {
        private final List<PetRecord> records = new ArrayList<>();
        private final List<UUID> petUuids = new ArrayList<>();
        private int phase;

        private BatchRecallScenario() {
            super("batch recall has low inter-pet delay", 260);
        }

        @Override
        protected void onStart(ActiveSuite suite) {
            int[] offsets = {768, 800, 832, 864};
            for (int i = 0; i < offsets.length; i++) {
                int offset = offsets[i];
                suite.prepareRemotePad(offset);
                WolfEntity wolf = suite.spawnOwnedWolf(suite.owner, suite.remoteStandPos(offset), "nlp_batch_" + i);
                if (wolf == null) {
                    throw new IllegalStateException("Could not spawn batch test wolf " + i);
                }
                this.petUuids.add(wolf.getUuid());
                PetRecord record = suite.getRecord(wolf);
                if (record == null) {
                    throw new IllegalStateException("Missing indexed record for batch wolf " + i);
                }
                this.records.add(record);
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            this.phase = 0;
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
            if (elapsedTicks > 90L) {
                return ScenarioResult.failed("Batch recall took too long: " + elapsedTicks + " ticks");
            }
            return ScenarioResult.passed("batch recall completed in " + elapsedTicks + " ticks");
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
            WolfEntity wolf = suite.spawnOwnedWolf(suite.owner, suite.ownerStandPos.add(5, 0, 0), "nlp_owner_loaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn ownership test wolf");
            }
            this.petUuid = wolf.getUuid();
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
        private long retryAtTick = -1L;
        private int ownerRetries;

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
            WolfEntity wolf = suite.spawnOwnedWolf(suite.owner, remotePos, "nlp_owner_unloaded");
            if (wolf == null) {
                throw new IllegalStateException("Could not spawn unloaded ownership test wolf");
            }
            this.petUuid = wolf.getUuid();
            this.record = suite.getRecord(wolf);
            if (this.record == null) {
                throw new IllegalStateException("Missing indexed record for unloaded ownership test wolf");
            }
            suite.teleportPlayer(suite.owner, suite.baseWorld, suite.ownerStandPos);
            suite.teleportPlayer(suite.otherPlayer, suite.baseWorld, suite.otherStandPos);
            this.phase = 0;
            this.unloadedAtTick = -1L;
            this.retryAtTick = -1L;
            this.ownerRetries = 0;
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

            if (this.phase == 3) {
                if (suite.now() < this.retryAtTick) {
                    return ScenarioResult.running();
                }
                suite.startTargetedRecall(suite.owner, List.of(this.record), true);
                this.phase = 2;
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

            if (suite.isTransientUnloadedMiss(summary, this.petUuid) && this.ownerRetries < 2) {
                this.ownerRetries++;
                this.retryAtTick = suite.now() + UNLOADED_RETRY_DELAY_TICKS;
                this.phase = 3;
                suite.report("Retrying owner unloaded recall after transient chunk miss (" + this.ownerRetries + "/2)");
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
