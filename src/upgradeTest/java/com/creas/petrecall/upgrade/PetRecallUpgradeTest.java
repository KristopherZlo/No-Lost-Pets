package com.creas.petrecall.upgrade;

import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Blocks;
import net.minecraft.util.DyeColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.world.World;

/** Only packaged in the separate test JAR, never in the mod. */
public final class PetRecallUpgradeTest implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final UUID OWNER = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("11000000-0000-0000-0000-000000000002");
    private final String phase = System.getProperty("nolostpets.upgrade.phase");
    private final List<PetRecord> records = new ArrayList<>();
    private final List<String> checks = new ArrayList<>();
    private String failure;
    private PetRecord[] expected;
    private int ticks;
    private boolean done;

    public static UUID pet(int i) {
        return UUID.fromString("22000000-0000-0000-0000-00000000000" + i);
    }

    @Override
    public void onInitialize() {
        if (!List.of("seed", "verify", "restart").contains(phase)) throw new IllegalArgumentException("Explicit legacy upgrade phase required");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { if ("seed".equals(phase)) seed(server); else loadSnapshot(server); } catch (Throwable error) { fail(server, error); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done) return;
            try {
                if (++ticks > 1800) throw new AssertionError("Remote entities did not unload in 90 seconds");
                if (ticks < 40) return;
                if (!"seed".equals(phase)) {
                    for (PetRecord record : expected) if (server.getWorld(record.dimensionKey()).getEntity(record.petUuid()) == null) return;
                    if (server.getOverworld().getEntity(pet(6)) == null) return;
                    verifySnapshot(server);
                    done = true;
                    server.stop(false);
                    return;
                }
                // These were loaded when created, then genuinely unloaded and written by vanilla.
                for (int i = 1; i < 6; i++) {
                    if (world(server, i).getEntity(pet(i)) != null) return;
                }
                require(server.getOverworld().getEntity(pet(0)) != null, "Loaded fixture must remain loaded at save");
                require(server.getOverworld().getEntity(pet(6)) == null, "Neighbor must also be unloaded");
                PetIndexState index = PetIndexState.get(server);
                require(index.size() == 6, "Old mod must index all six pets, and exclude the pig");
                require(index.getPetsForOwner(OWNER).size() == 5 && index.getPetsForOwner(OTHER).size() == 1,
                        "Old owner buckets must be correct");
                for (int i = 0; i < 6; i++) {
                    PetRecord record = index.getPet(pet(i));
                    require(record != null && record.health() == 7.5F && record.sitting() == (i == 2), "Old record state " + i);
                    require(record.ownerUuid().equals(i == 3 ? OTHER : OWNER), "Old record owner " + i);
                    records.add(record);
                }
                checks.addAll(List.of("old production mod created index", "one loaded and five unloaded pets",
                        "six indexed UUIDs, two owners, two dimensions", "unloaded neighbor excluded"));
                done = true;
                server.stop(false);
            } catch (Throwable error) { fail(server, error); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> report());
    }

    private void seed(MinecraftServer server) {
        require(PetIndexState.get(server).size() == 0, "Seed must start with an empty world");
        for (int i = 0; i < 6; i++) {
            ServerWorld world = world(server, i);
            int x = (i + 1) * 1024;
            int y = i == 4 ? 81 : 65;
            platform(world, x, y);
            TameableEntity animal = i == 5 ? new CatEntity(EntityType.CAT, world) : new WolfEntity(EntityType.WOLF, world);
            animal.setUuid(pet(i));
            animal.refreshPositionAndAngles(x + 0.5D, y, 0.5D, 0, 0);
            animal.setTamed(true, true);
            animal.setOwner(new ServerPlayerEntity(server, world, new GameProfile(i == 3 ? OTHER : OWNER, "OfflineSeedOwner"), SyncedClientOptions.createDefault()));
            if (animal instanceof CatEntity) animal.setComponent(DataComponentTypes.CAT_COLLAR, DyeColor.YELLOW);
            else animal.setComponent(DataComponentTypes.WOLF_COLLAR, DyeColor.BLUE);
            animal.setSitting(i == 2);
            animal.setAiDisabled(true);
            animal.setNoGravity(true);
            animal.setPersistent();
            animal.setHealth(7.5F);
            animal.setCustomName(Text.literal("Upgrade pet " + i));
            animal.addCommandTag("upgrade_pet_" + i);
            require(world.spawnEntity(animal), "Seed spawn " + i);
            if (i == 1) {
                PigEntity pig = new PigEntity(EntityType.PIG, world);
                pig.setUuid(pet(6));
                pig.refreshPositionAndAngles(x + 1.5D, y, 0.5D, 0, 0);
                pig.setAiDisabled(true);
                pig.setNoGravity(true);
                pig.setPersistent();
                pig.setHealth(5.0F);
                pig.setCustomName(Text.literal("Upgrade neighbor"));
                pig.addCommandTag("upgrade_neighbor");
                require(world.spawnEntity(pig), "Seed neighbor spawn");
            }
            if (i != 0) world.setChunkForced(x >> 4, 0, false);
        }
    }

    private static ServerWorld world(MinecraftServer server, int i) {
        return i == 4 ? server.getWorld(World.NETHER) : server.getOverworld();
    }

    private static void platform(ServerWorld world, int x, int y) {
        world.setChunkForced(x >> 4, 0, true);
        world.getChunk(x >> 4, 0);
        for (int dx = 0; dx < 5; dx++) for (int z = 0; z < 5; z++) {
            world.setBlockState(new BlockPos(x + dx, y - 1, z), Blocks.STONE.getDefaultState());
            for (int dy = 0; dy < 4; dy++) world.setBlockState(new BlockPos(x + dx, y + dy, z), Blocks.AIR.getDefaultState());
        }
    }

    private void loadSnapshot(MinecraftServer server) throws Exception {
        JsonObject source = GSON.fromJson(Files.readString(Path.of(System.getProperty("nolostpets.upgrade.expected"))), JsonObject.class);
        require(source.get("passed").getAsBoolean(), "Expected snapshot must be successful");
        expected = GSON.fromJson(source.get("records"), PetRecord[].class);
        PetIndexState index = PetIndexState.get(server);
        require(expected.length == 6 && index.size() == 6, "All six records must survive before any remote entity loads");
        require(index.getPetsForOwner(OWNER).size() == 5 && index.getPetsForOwner(OTHER).size() == 1, "Owner buckets survive legacy update");
        for (PetRecord record : expected) {
            require(record.equals(index.getPet(record.petUuid())), "Persisted record differs before source load: " + record.petUuid());
            ServerWorld target = server.getWorld(record.dimensionKey());
            require(target != null, "Saved dimension must exist");
            target.setChunkForced(record.chunkPos().x, record.chunkPos().z, true);
            target.getChunk(record.chunkPos().x, record.chunkPos().z);
        }
        checks.add("complete persisted index checked before remote entity loading");
    }

    private void verifySnapshot(MinecraftServer server) {
        require(PetIndexState.get(server).size() == 6, "Loading cannot change the number of pets");
        for (int i = 0; i < 6; i++) {
            TameableEntity animal = (TameableEntity) world(server, i).getEntity(pet(i));
            PetRecord expectedRecord = java.util.Arrays.stream(expected).filter(r -> r.petUuid().equals(animal.getUuid())).findFirst().orElseThrow();
            require(i == 5 ? animal instanceof CatEntity : animal instanceof WolfEntity, "Species survives legacy update");
            require(animal.isTamed() && animal.getOwnerReference().getUuid().equals(i == 3 ? OTHER : OWNER), "Owner survives legacy update");
            require(animal.getHealth() == 7.5F && animal.isSitting() == (i == 2) && animal.isAiDisabled() && animal.hasNoGravity(), "Health and flags survive legacy update");
            require(animal.getCustomName() != null && animal.getCustomName().getString().equals("Upgrade pet " + i)
                    && animal.getCommandTags().contains("upgrade_pet_" + i), "Name and custom tags survive legacy update");
            require((animal instanceof CatEntity cat ? cat.getCollarColor() : ((WolfEntity) animal).getCollarColor())
                    == (i == 5 ? DyeColor.YELLOW : DyeColor.BLUE), "Collar survives legacy update");
            require(animal.getX() == expectedRecord.x() && animal.getY() == expectedRecord.y() && animal.getZ() == expectedRecord.z(), "Position survives legacy update");
            int copies = 0;
            for (ServerWorld target : server.getWorlds()) for (net.minecraft.entity.Entity entity : target.iterateEntities()) if (entity.getUuid().equals(pet(i))) copies++;
            require(copies == 1, "A saved UUID cannot be duplicated");
            records.add(PetIndexState.get(server).getPet(pet(i)));
            world(server, i).setChunkForced(expectedRecord.chunkPos().x, expectedRecord.chunkPos().z, false);
        }
        PigEntity neighbor = (PigEntity) server.getOverworld().getEntity(pet(6));
        require(neighbor.getHealth() == 5.0F && neighbor.getX() == 2049.5D && neighbor.getY() == 65.0D && neighbor.getZ() == 0.5D
                && neighbor.getCommandTags().contains("upgrade_neighbor"), "Ordinary neighbor survives unchanged");
        checks.addAll(List.of("six pets retain UUID, owner, species, health, stance, AI, gravity, collar, name and tags", "no duplicated UUID", "ordinary neighbor unchanged"));
    }

    private void fail(MinecraftServer server, Throwable error) {
        failure = error.toString();
        error.printStackTrace();
        done = true;
        server.stop(false);
    }

    private void report() {
        try {
            JsonObject result = new JsonObject();
            result.addProperty("passed", done && failure == null && records.size() == 6);
            result.addProperty("phase", phase);
            result.addProperty("modVersion", FabricLoader.getInstance().getModContainer("pet_recall").orElseThrow().getMetadata().getVersion().getFriendlyString());
            result.addProperty("minecraft", FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
            result.addProperty("failure", failure);
            result.add("checks", GSON.toJsonTree(checks));
            result.add("records", GSON.toJsonTree(records));
            Files.writeString(Path.of(System.getProperty("nolostpets.upgrade.report")), GSON.toJson(result));
        } catch (Exception error) { throw new IllegalStateException("Cannot write upgrade test report", error); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
