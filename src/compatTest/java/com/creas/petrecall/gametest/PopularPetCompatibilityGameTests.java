package com.creas.petrecall.gametest;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.creas.petrecall.util.VersionCompat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Compiled separately; only registered when a pinned real-mod profile is supplied. */
public final class PopularPetCompatibilityGameTests {
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32")
    public void installedCompanionsKeepOwnershipInventoryAndCustomStateAcrossDiskLoads(GameTestHelper context) throws Exception {
        JsonObject spec = JsonParser.parseString(Files.readString(Path.of(System.getProperty("nolostpets.compat.spec")))).getAsJsonObject();
        for (var id : spec.getAsJsonArray("requiredMods")) require(context, FabricLoader.getInstance().isModLoaded(id.getAsString()), "Pinned real mod must actually be loaded: " + id);
        new Fixture(context, spec).start();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        context.assertTrue(condition, Component.literal(message));
    }

    private static CompoundTag data(Entity pet) {
        ProblemReporter.Collector errors = new ProblemReporter.Collector();
        TagValueOutput output = TagValueOutput.createWithContext(errors, pet.registryAccess());
        pet.saveWithoutId(output);
        if (!errors.isEmpty()) throw new AssertionError("Real mod failed to encode pet state: " + errors.getReport());
        return output.buildResult();
    }

    private static final class Fixture {
        final GameTestHelper context;
        final JsonObject spec;
        final ServerLevel world;
        final PetRecallService service = PetRecallMod.getRecallService();
        final List<UUID> pets = new ArrayList<>();
        final List<UUID> controls = new ArrayList<>();
        final Map<UUID, PetRecord> records = new HashMap<>();
        final Map<UUID, CompoundTag> state = new HashMap<>();
        final ChunkPos source = new ChunkPos(31000, 31000);
        final BlockPos remote;
        ServerPlayer owner;
        ServerPlayer foreign;
        EmbeddedChannel channel;
        RecallSummary result;
        int phase;
        int callbacks;

        Fixture(GameTestHelper context, JsonObject spec) {
            this.context = context;
            this.spec = spec;
            world = context.getLevel();
            remote = new BlockPos(source.getMinBlockX() + 8, context.absolutePos(new BlockPos(16, 2, 16)).getY(), source.getMinBlockZ() + 8);
            for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) context.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            owner = context.makeMockServerPlayerInLevel();
            foreign = context.makeMockServerPlayerInLevel();
            PetRecallMod.getAutoRecallController().suppressPlayer(owner.getUUID());
            PetRecallMod.getAutoRecallController().suppressPlayer(foreign.getUUID());
            placeOwner();
            world.setChunkForced(source.x(), source.z(), true);
            world.getChunk(source.x(), source.z());
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                world.setBlockAndUpdate(remote.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) world.setBlockAndUpdate(remote.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }

        void start() { context.onEachTick(this::tick); }
        void placeOwner() {
            owner.snapTo(context.absolutePos(new BlockPos(16, 2, 16)), 0, 0);
            owner.setNoGravity(true);
            owner.setOnGround(true);
        }

        void spawn(int i) throws Exception {
            var types = spec.getAsJsonArray("entities");
            String type = types.get(i % types.size()).getAsString();
            require(context, BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(type)), "The real companion entity type must be registered: " + type);
            Entity entity = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type)).create(world, EntitySpawnReason.COMMAND);
            require(context, entity instanceof TamableAnimal, "Profile must supply an actual tameable mod entity");
            TamableAnimal pet = (TamableAnimal) entity;
            pet.snapTo(remote.getX() + i % 5 - 1.5D, remote.getY(), remote.getZ() + i / 5 - 1.5D, 0, 0);
            pet.setTame(true, true);
            pet.setOwner(i >= 25 ? foreign : owner);
            pet.setOrderedToSit(i >= 20 && i < 25);
            pet.setNoAi(true);
            pet.setNoGravity(true);
            pet.setPersistenceRequired();
            pet.setHealth(pet.getMaxHealth());
            pet.setCustomName(Component.literal("real mod pet " + i));
            pet.addTag("real_mod_state_" + i);
            if ("indypets".equals(spec.get("slug").getAsString())) {
                CompoundTag modified = data(pet);
                modified.putBoolean("AllowedToFollow", false);
                ProblemReporter.Collector errors = new ProblemReporter.Collector();
                pet.load(TagValueInput.create(errors, pet.registryAccess(), modified));
                require(context, errors.isEmpty(), "IndyPets custom state must decode without errors");
                require(context, !data(pet).getBoolean("AllowedToFollow").orElse(true), "IndyPets fixture must really disable following");
            }
            if ("chested-companions".equals(spec.get("slug").getAsString())) {
                pet.getClass().getMethod("chestedCompanions$setChestItemStack", ItemStack.class).invoke(pet, new ItemStack(Items.CHEST));
                pet.getClass().getMethod("chestedCompanions$createInventory").invoke(pet);
                Container inventory = (Container) pet.getClass().getMethod("chestedCompanions$getInventory").invoke(pet);
                inventory.setItem(0, new ItemStack(Items.DIAMOND, 7));
                inventory.setItem(inventory.getContainerSize() - 1, new ItemStack(Items.GOLD_INGOT, 13));
            }
            if ("doggy-talents-next".equals(spec.get("slug").getAsString())) {
                CompoundTag modified = data(pet);
                modified.putInt("level_normal", 12);
                modified.putInt("level_kami", 3);
                modified.putString("mode", "docile");
                ProblemReporter.Collector errors = new ProblemReporter.Collector();
                pet.load(TagValueInput.create(errors, pet.registryAccess(), modified));
                require(context, errors.isEmpty() && data(pet).getInt("level_normal").orElse(-1) == 12,
                        "Doggy Talents fixture must really contain non-default saved levels");
            }
            pet.setHealth(pet.getMaxHealth());
            require(context, world.addFreshEntity(pet), "A real installed companion must spawn");
            PetRecallMod.getTracker().observe(pet, world);
            records.put(pet.getUUID(), PetIndexState.get(world.getServer()).getPet(pet.getUUID()));
            state.put(pet.getUUID(), data(pet));
            for (var key : spec.getAsJsonArray("preservedKeys")) require(context, state.get(pet.getUUID()).contains(key.getAsString()), "Fixture must contain actual custom data: " + key);
            (i < 20 ? pets : controls).add(pet.getUUID());
        }

        void tick() {
            try {
                if (phase == 0) {
                    if (!VersionCompat.areChunkEntitiesLoaded(world, source)) return;
                    for (int i = 0; i < 30; i++) spawn(i);
                    // Companion mods may teleport on unload: keep both owners genuinely offline for this save.
                    world.getServer().getPlayerList().remove(owner);
                    world.getServer().getPlayerList().remove(foreign);
                    world.setChunkForced(source.x(), source.z(), false);
                    phase = 1;
                    return;
                }
                if (phase == 1) {
                    if (VersionCompat.areChunkEntitiesLoaded(world, source)) return;
                    for (UUID id : records.keySet()) require(context, world.getEntity(id) == null, "Real companion must genuinely save and unload");
                    ServerPlayer joined = new ServerPlayer(world.getServer(), world, owner.getGameProfile(), ClientInformation.createDefault());
                    Connection connection = new Connection(PacketFlow.SERVERBOUND);
                    channel = new EmbeddedChannel(connection);
                    world.getServer().getPlayerList().placeNewPlayer(connection, joined, CommonListenerCookie.createInitial(owner.getGameProfile(), false));
                    owner = joined;
                    placeOwner();
                    PetRecallMod.getAutoRecallController().suppressPlayer(owner.getUUID());
                    require(context, service.recallAllForPlayerAsync(owner, summary -> { callbacks++; result = summary; }), "Real-mod unloaded recall must start");
                    phase = 2;
                    return;
                }
                if (phase == 2) {
                    if (result == null) return;
                    require(context, callbacks == 1 && result.recalled == 20 && result.skipped == 5 && result.failed == 0,
                            "All standing real companions must arrive once; sitting pets must stay");
                    verify(false);
                    phase = 3;
                    return;
                }
                if (phase == 3) {
                    if (VersionCompat.areChunkEntitiesLoaded(world, source)) return;
                    world.setChunkForced(source.x(), source.z(), true);
                    phase = 4;
                    return;
                }
                if (!VersionCompat.areChunkEntitiesLoaded(world, source)) return;
                verify(true);
                cleanup();
                context.succeed();
            } catch (Exception | AssertionError failure) {
                cleanup();
                throw new AssertionError("Real installed companion profile failed: " + spec.get("slug"), failure);
            }
        }

        void verify(boolean sourceReloaded) {
            for (UUID id : records.keySet()) {
                if (!sourceReloaded && controls.contains(id)) continue;
                Entity entity = world.getEntity(id);
                require(context, entity instanceof TamableAnimal, "Every original mod UUID must still exist");
                TamableAnimal pet = (TamableAnimal) entity;
                PetRecord original = records.get(id);
                require(context, pet.isAlive() && pet.getHealth() == original.health() && pet.getOwnerReference().getUUID().equals(original.ownerUuid())
                        && pet.isOrderedToSit() == original.sitting(), "Owner, health and sitting survive real-mod disk loads");
                CompoundTag current = data(pet);
                for (var key : spec.getAsJsonArray("preservedKeys")) {
                    String field = key.getAsString();
                    require(context, java.util.Objects.equals(state.get(id).get(field), current.get(field)), "Real custom payload must survive byte-for-byte: " + field);
                }
                require(context, pet.getCustomName() != null && pet.getCustomName().getString().startsWith("real mod pet ")
                        && pet.entityTags().stream().anyMatch(t -> t.startsWith("real_mod_state_")), "Custom name and tags survive");
                require(context, pets.contains(id) ? pet.distanceToSqr(owner) <= 144.0D
                        : pet.getX() == original.x() && pet.getY() == original.y() && pet.getZ() == original.z(), "Only eligible owner's companions can move");
                int count = 0;
                for (Entity loaded : world.getAllEntities()) if (loaded.getUUID().equals(id)) count++;
                require(context, count == 1, "Second disk load cannot duplicate a real mod entity UUID");
            }
        }

        void cleanup() {
            world.setChunkForced(source.x(), source.z(), false);
            for (UUID id : records.keySet()) {
                Entity entity = world.getEntity(id);
                PetRecallMod.getTracker().removeRecord(world.getServer(), id);
                if (entity != null) entity.discard();
            }
            world.getServer().getPlayerList().remove(owner);
            PetRecallMod.getAutoRecallController().resumePlayer(owner.getUUID());
            PetRecallMod.getAutoRecallController().resumePlayer(foreign.getUUID());
            if (channel != null) channel.finishAndReleaseAll();
        }
    }
}
