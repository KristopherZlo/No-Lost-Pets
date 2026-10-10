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
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/** Compiled separately; only registered when a pinned real-mod profile is supplied. */
public final class PopularPetCompatibilityGameTests {
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32")
    public void installedCompanionsKeepOwnershipInventoryAndCustomStateAcrossDiskLoads(TestContext context) throws Exception {
        JsonObject spec = JsonParser.parseString(Files.readString(Path.of(System.getProperty("nolostpets.compat.spec")))).getAsJsonObject();
        for (var id : spec.getAsJsonArray("requiredMods")) require(context, FabricLoader.getInstance().isModLoaded(id.getAsString()), "Pinned real mod must actually be loaded: " + id);
        new Fixture(context, spec).start();
    }

    private static void require(TestContext context, boolean condition, String message) {
        context.assertTrue(condition, Text.literal(message));
    }

    private static NbtCompound data(Entity pet) {
        ErrorReporter.Impl errors = new ErrorReporter.Impl();
        NbtWriteView output = NbtWriteView.create(errors, pet.getRegistryManager());
        pet.writeData(output);
        if (!errors.isEmpty()) throw new AssertionError("Real mod failed to encode pet state: " + errors.getErrorsAsString());
        return output.getNbt();
    }

    private static final class Fixture {
        final TestContext context;
        final JsonObject spec;
        final ServerWorld world;
        final PetRecallService service = PetRecallMod.getRecallService();
        final List<UUID> pets = new ArrayList<>();
        final List<UUID> controls = new ArrayList<>();
        final Map<UUID, PetRecord> records = new HashMap<>();
        final Map<UUID, NbtCompound> state = new HashMap<>();
        final Map<UUID, Integer> numbers = new HashMap<>();
        final ChunkPos source = new ChunkPos(31000, 31000);
        final BlockPos remote;
        ServerPlayerEntity owner;
        ServerPlayerEntity foreign;
        EmbeddedChannel channel;
        RecallSummary result;
        int phase;
        int callbacks;
        boolean cleaned;

        Fixture(TestContext context, JsonObject spec) {
            this.context = context;
            this.spec = spec;
            world = context.getWorld();
            remote = new BlockPos(source.getStartX() + 8, context.getAbsolutePos(new BlockPos(16, 2, 16)).getY(), source.getStartZ() + 8);
            for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) context.setBlockState(new BlockPos(x, 1, z), Blocks.STONE);
            owner = context.createMockCreativeServerPlayerInWorld();
            foreign = context.createMockCreativeServerPlayerInWorld();
            PetRecallMod.getAutoRecallController().suppressPlayer(owner.getUuid());
            PetRecallMod.getAutoRecallController().suppressPlayer(foreign.getUuid());
            placeOwner();
            world.setChunkForced(source.x, source.z, true);
            world.getChunk(source.x, source.z);
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                world.setBlockState(remote.add(x, -1, z), Blocks.STONE.getDefaultState());
                for (int y = 0; y < 4; y++) world.setBlockState(remote.add(x, y, z), Blocks.AIR.getDefaultState());
            }
        }

        void start() { context.runAtEveryTick(this::tick); }
        void placeOwner() {
            owner.refreshPositionAndAngles(context.getAbsolutePos(new BlockPos(16, 2, 16)), 0, 0);
            owner.setNoGravity(true);
            owner.setOnGround(true);
        }

        void spawn(int i) throws Exception {
            var types = spec.getAsJsonArray("entities");
            String type = types.get(i % types.size()).getAsString();
            require(context, Registries.ENTITY_TYPE.containsId(Identifier.of(type)), "The real companion entity type must be registered: " + type);
            Entity entity = Registries.ENTITY_TYPE.get(Identifier.of(type)).create(world, SpawnReason.COMMAND);
            require(context, entity instanceof TameableEntity, "Profile must supply an actual tameable mod entity");
            TameableEntity pet = (TameableEntity) entity;
            pet.refreshPositionAndAngles(remote.getX() + i % 5 - 1.5D, remote.getY(), remote.getZ() + i / 5 - 1.5D, 0, 0);
            pet.setTamed(true, true);
            pet.setOwner(i >= 25 ? foreign : owner);
            pet.setSitting(i >= 20 && i < 25);
            pet.setAiDisabled(true);
            pet.setNoGravity(true);
            pet.setPersistent();
            pet.setHealth(pet.getMaxHealth());
            if ("indypets".equals(spec.get("slug").getAsString())) {
                NbtCompound modified = data(pet);
                modified.putBoolean("AllowedToFollow", false);
                ErrorReporter.Impl errors = new ErrorReporter.Impl();
                pet.readData(NbtReadView.create(errors, pet.getRegistryManager(), modified));
                require(context, errors.isEmpty(), "IndyPets custom state must decode without errors");
                require(context, !data(pet).getBoolean("AllowedToFollow").orElse(true), "IndyPets fixture must really disable following");
            }
            if ("chested-companions".equals(spec.get("slug").getAsString())) {
                pet.getClass().getMethod("chestedCompanions$setChestItemStack", ItemStack.class).invoke(pet, new ItemStack(Items.CHEST));
                pet.getClass().getMethod("chestedCompanions$createInventory").invoke(pet);
                Inventory inventory = (Inventory) pet.getClass().getMethod("chestedCompanions$getInventory").invoke(pet);
                inventory.setStack(0, new ItemStack(Items.DIAMOND, 7));
                inventory.setStack(inventory.size() - 1, new ItemStack(Items.GOLD_INGOT, 13));
            }
            if ("doggy-talents-next".equals(spec.get("slug").getAsString())) {
                NbtCompound modified = data(pet);
                modified.putInt("level_normal", 12);
                modified.putInt("level_kami", 3);
                modified.putString("mode", "docile");
                ErrorReporter.Impl errors = new ErrorReporter.Impl();
                pet.readData(NbtReadView.create(errors, pet.getRegistryManager(), modified));
                require(context, errors.isEmpty() && data(pet).getInt("level_normal").orElse(-1) == 12,
                        "Doggy Talents fixture must really contain non-default saved levels");
            }
            Text name = Text.literal("real mod pet " + i);
            if ("doggy-talents-next".equals(spec.get("slug").getAsString())) {
                pet.getClass().getMethod("setDogCustomName", Text.class).invoke(pet, name);
            } else pet.setCustomName(name);
            pet.addCommandTag("real_mod_state_" + i);
            require(context, pet.getCustomName() != null && name.getString().equals(pet.getCustomName().getString())
                    && pet.getCommandTags().contains("real_mod_state_" + i), "Fixture must really contain its exact custom name and tag before saving");
            pet.setHealth(pet.getMaxHealth());
            require(context, world.spawnEntity(pet), "A real installed companion must spawn");
            PetRecallMod.getTracker().observe(pet, world);
            records.put(pet.getUuid(), PetIndexState.get(world.getServer()).getPet(pet.getUuid()));
            state.put(pet.getUuid(), data(pet));
            numbers.put(pet.getUuid(), i);
            for (var key : spec.getAsJsonArray("preservedKeys")) require(context, state.get(pet.getUuid()).contains(key.getAsString()), "Fixture must contain actual custom data: " + key);
            (i < 20 ? pets : controls).add(pet.getUuid());
        }

        void tick() {
            if (cleaned) return;
            try {
                if (phase == 0) {
                    if (!VersionCompat.areChunkEntitiesLoaded(world, source)) return;
                    for (int i = 0; i < 30; i++) spawn(i);
                    // Companion mods may teleport on unload: keep both owners genuinely offline for this save.
                    world.getServer().getPlayerManager().remove(owner);
                    world.getServer().getPlayerManager().remove(foreign);
                    world.setChunkForced(source.x, source.z, false);
                    phase = 1;
                    return;
                }
                if (phase == 1) {
                    if (VersionCompat.areChunkEntitiesLoaded(world, source)) return;
                    for (UUID id : records.keySet()) require(context, world.getEntity(id) == null, "Real companion must genuinely save and unload");
                    ServerPlayerEntity joined = new ServerPlayerEntity(world.getServer(), world, owner.getGameProfile(), SyncedClientOptions.createDefault());
                    ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
                    channel = new EmbeddedChannel(connection);
                    world.getServer().getPlayerManager().onPlayerConnect(connection, joined, ConnectedClientData.createDefault(owner.getGameProfile(), false));
                    owner = joined;
                    placeOwner();
                    PetRecallMod.getAutoRecallController().suppressPlayer(owner.getUuid());
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
                    world.setChunkForced(source.x, source.z, true);
                    phase = 4;
                    return;
                }
                if (!VersionCompat.areChunkEntitiesLoaded(world, source)) return;
                verify(true);
                cleanup();
                context.complete();
            } catch (Exception | AssertionError failure) {
                cleanup();
                throw new AssertionError("Real installed companion profile failed: " + spec.get("slug"), failure);
            }
        }

        void verify(boolean sourceReloaded) {
            for (UUID id : records.keySet()) {
                if (!sourceReloaded && controls.contains(id)) continue;
                Entity entity = world.getEntity(id);
                require(context, entity instanceof TameableEntity, "Every original mod UUID must still exist");
                TameableEntity pet = (TameableEntity) entity;
                PetRecord original = records.get(id);
                require(context, pet.isAlive() && pet.getHealth() == original.health() && pet.getOwnerReference().getUuid().equals(original.ownerUuid())
                        && pet.isSitting() == original.sitting(), "Owner, health and sitting survive real-mod disk loads");
                NbtCompound current = data(pet);
                for (var key : spec.getAsJsonArray("preservedKeys")) {
                    String field = key.getAsString();
                    require(context, java.util.Objects.equals(state.get(id).get(field), current.get(field)), "Real custom payload must survive byte-for-byte: " + field);
                }
                require(context, pet.getCustomName() != null && pet.getCustomName().getString().equals("real mod pet " + numbers.get(id))
                        && pet.getCommandTags().contains("real_mod_state_" + numbers.get(id)), "Exact custom name and tag survive for the original UUID");
                require(context, pets.contains(id) ? pet.squaredDistanceTo(owner) <= 144.0D
                        : pet.getX() == original.x() && pet.getY() == original.y() && pet.getZ() == original.z(), "Only eligible owner's companions can move");
                int count = 0;
                for (Entity loaded : world.iterateEntities()) if (loaded.getUuid().equals(id)) count++;
                require(context, count == 1, "Second disk load cannot duplicate a real mod entity UUID");
            }
        }

        void cleanup() {
            if (cleaned) return;
            cleaned = true;
            world.setChunkForced(source.x, source.z, false);
            for (UUID id : records.keySet()) {
                Entity entity = world.getEntity(id);
                PetRecallMod.getTracker().removeRecord(world.getServer(), id);
                if (entity != null) entity.discard();
            }
            world.getServer().getPlayerManager().remove(owner);
            PetRecallMod.getAutoRecallController().resumePlayer(owner.getUuid());
            PetRecallMod.getAutoRecallController().resumePlayer(foreign.getUuid());
            if (channel != null) channel.finishAndReleaseAll();
        }
    }
}
