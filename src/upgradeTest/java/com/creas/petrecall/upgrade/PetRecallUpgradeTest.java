package com.creas.petrecall.upgrade;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.recall.PetRecallService.RecallSummary;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/** Drives a normal dedicated server, so vanilla saves and upgrades the same world. */
public final class PetRecallUpgradeTest implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final UUID OWNER = UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("11000000-0000-0000-0000-000000000002");
    private final String phase = System.getProperty("nolostpets.upgrade.phase");
    private final List<String> checks = new ArrayList<>();
    private List<PetRecord> records = List.of();
    private PetRecord[] expected;
    private RecallSummary recalled;
    private ServerPlayer player;
    private EmbeddedChannel channel;
    private String failure;
    private int ticks;
    private boolean loading;
    private boolean netherRecallStarted;
    private boolean done;

    private static UUID pet(int i) {
        return UUID.fromString("22000000-0000-0000-0000-00000000000" + i);
    }

    @Override
    public void onInitialize() {
        require(List.of("verify", "recall", "restart").contains(phase), "Explicit upgrade test phase required");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { start(server); } catch (Throwable error) { fail(server, error); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done) return;
            try { tick(server); } catch (Throwable error) { fail(server, error); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> report());
    }

    private void start(MinecraftServer server) throws Exception {
        JsonObject source = GSON.fromJson(Files.readString(Path.of(System.getProperty("nolostpets.upgrade.expected"))), JsonObject.class);
        require(source.get("passed").getAsBoolean(), "Expected snapshot must come from a successful save");
        expected = GSON.fromJson(source.get("records"), PetRecord[].class);
        require(expected.length == 6, "Exactly six expected pets");
        // Observe the persisted index before loading the remote entities; rescanning cannot hide lost records.
        PetIndexState index = PetIndexState.get(server);
        require(index.size() == 6, "Migrated index must contain exactly six pets before chunk loads");
        for (PetRecord record : expected) require(record.equals(index.getPet(record.petUuid())), "Full persisted record differs: " + record.petUuid());
        require(index.getPetsForOwner(OWNER).size() == 5 && index.getPetsForOwner(OTHER).size() == 1, "Owner buckets before chunk loads");
        if (!"restart".equals(phase)) {
            for (int i = 1; i < 6; i++) require(originalWorld(server, i).getEntity(pet(i)) == null, "Pet must still be unloaded before migration assertion: " + i);
            checks.add("persisted index and owner buckets match before loading five remote pets");
        } else checks.add("persisted index matches the preceding clean shutdown");
        if ("recall".equals(phase)) recall(server, false);
        else loadForCheck(server);
    }

    private void recall(MinecraftServer server, boolean nether) {
        ServerLevel world = nether ? server.getLevel(Level.NETHER) : server.overworld();
        int groundY = nether ? 80 : 64;
        for (int x = 12; x <= 22; x++) for (int z = 12; z <= 22; z++) {
            world.setChunkForced(x >> 4, z >> 4, true);
            world.getChunk(x >> 4, z >> 4);
            world.setBlock(new BlockPos(x, groundY, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = groundY + 1; y <= groundY + 4; y++) world.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        GameProfile profile = new GameProfile(OWNER, "UpgradeOwner");
        player = new ServerPlayer(server, world, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        if (channel != null) channel.finishAndReleaseAll();
        channel = new EmbeddedChannel(connection);
        // Attach vanilla networking without joining: automatic login recall must not race the explicit call.
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
        player.snapTo(17.5D, groundY + 1.0D, 17.5D, 0, 0);
        player.setOnGround(true);
        recalled = null;
        boolean started = nether
                ? PetRecallMod.getRecallService().recallSpecificPetsForPlayerAsync(player,
                    List.of(PetIndexState.get(server).getPet(pet(4))), true, summary -> recalled = summary)
                : PetRecallMod.getRecallService().recallAllForPlayerAsync(player, summary -> recalled = summary);
        require(started, "Recall must start");
    }

    private void tick(MinecraftServer server) {
        if (++ticks > 1800) throw new AssertionError("Upgrade phase timed out after 90 seconds");
        if ("recall".equals(phase) && !loading) {
            if (recalled == null) return;
            if (!netherRecallStarted) {
                require(recalled.totalKnown == 5 && recalled.recalled == 3 && recalled.skipped == 2 && recalled.failed == 0,
                        "Overworld recall must recover three pets and skip sitting and other-dimension pets: " + GSON.toJson(recalled));
                PetRecord netherPet = Arrays.stream(expected).filter(r -> r.petUuid().equals(pet(4))).findFirst().orElseThrow();
                require(netherPet.equals(PetIndexState.get(server).getPet(pet(4))), "Cross-dimension skip must preserve Nether pet");
                require(PetRecallMod.getRecallService().getActiveRecallCount() == 0, "First recall finished");
                netherRecallStarted = true;
                recall(server, true);
                return;
            }
            require(recalled.totalKnown == 1 && recalled.recalled == 1 && recalled.skipped == 0 && recalled.failed == 0,
                    "Nether recall must recover its pet within the Nether: " + GSON.toJson(recalled));
            require(PetRecallMod.getRecallService().getActiveRecallCount() == 0
                    && PetRecallMod.getRecallService().getActivePetRecallCount() == 0, "Recall must release reservations");
            for (int i : new int[] {0, 1, 4, 5}) {
                PetRecord record = PetIndexState.get(server).getPet(pet(i));
                require(record.dimensionId().equals(i == 4 ? "minecraft:the_nether" : "minecraft:overworld") && Math.abs(record.x() - 17.5D) < 10
                        && Math.abs(record.z() - 17.5D) < 10, "Recalled pet must be near its owner: " + i);
                PetRecord old = Arrays.stream(expected).filter(r -> r.petUuid().equals(pet(i))).findFirst().orElseThrow();
                require(record.ownerUuid().equals(old.ownerUuid()) && record.entityTypeId().equals(old.entityTypeId())
                        && record.health() == old.health() && record.sitting() == old.sitting(), "Recall must preserve indexed state " + i);
            }
            for (int i : new int[] {2, 3}) {
                PetRecord old = Arrays.stream(expected).filter(r -> r.petUuid().equals(pet(i))).findFirst().orElseThrow();
                require(old.equals(PetIndexState.get(server).getPet(pet(i))), "Sitting or other owner's pet must not move: " + i);
            }
            checks.add("actual recalls within Overworld and Nether recovered four pets; sitting, other owner and dimension boundaries preserved");
            loadForCheck(server);
        }
        if (!loading || !entitiesReady(server)) return;
        List<PetRecord> snapshot = snapshot(server);
        verifyEntities(server, snapshot);
        checks.addAll(List.of("six UUIDs and owners survive vanilla entity data fixing", "health, names, tags, collars, AI and sitting preserved",
                "no duplicate UUID in either dimension or original source chunks", "ordinary neighbor survives without moving"));
        records = snapshot;
        // Do not leave forced chunks persisted: the next process must read the index before entities load.
        for (PetRecord record : snapshot) {
            server.getLevel(record.dimensionKey()).setChunkForced(record.chunkPos().x(), record.chunkPos().z(), false);
        }
        for (int i = 0; i < 6; i++) originalWorld(server, i).setChunkForced(((i + 1) * 1024) >> 4, 0, false);
        for (ServerLevel world : List.of(server.overworld(), server.getLevel(Level.NETHER))) {
            for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) world.setChunkForced(x, z, false);
        }
        done = true;
        server.halt(false);
    }

    private void loadForCheck(MinecraftServer server) {
        for (PetRecord record : snapshot(server)) {
            ServerLevel world = server.getLevel(record.dimensionKey());
            require(world != null, "Record dimension exists");
            world.setChunkForced(record.chunkPos().x(), record.chunkPos().z(), true);
            world.getChunk(record.chunkPos().x(), record.chunkPos().z());
        }
        // Load the original source chunks too: a successful recall must not leave another copy behind.
        for (int i = 0; i < 6; i++) {
            ServerLevel world = originalWorld(server, i);
            world.setChunkForced(((i + 1) * 1024) >> 4, 0, true);
            world.getChunk(((i + 1) * 1024) >> 4, 0);
        }
        loading = true;
    }

    private boolean entitiesReady(MinecraftServer server) {
        for (PetRecord record : snapshot(server)) {
            if (server.getLevel(record.dimensionKey()).getEntity(record.petUuid()) == null) return false;
        }
        return server.overworld().getEntity(pet(6)) != null;
    }

    private void verifyEntities(MinecraftServer server, List<PetRecord> snapshot) {
        require(PetIndexState.get(server).size() == 6, "Entity loading must not add or remove pet records");
        for (int i = 0; i < 6; i++) {
            final UUID uuid = pet(i);
            PetRecord record = snapshot.stream().filter(r -> r.petUuid().equals(uuid)).findFirst().orElseThrow();
            Entity entity = server.getLevel(record.dimensionKey()).getEntity(uuid);
            require(i == 5 ? entity instanceof Cat : entity instanceof Wolf, "Vanilla entity type preserved " + i);
            TamableAnimal animal = (TamableAnimal) entity;
            require(animal.isTame() && animal.getOwnerReference() != null
                    && animal.getOwnerReference().getUUID().equals(i == 3 ? OTHER : OWNER), "Entity owner preserved " + i);
            require(animal.isOrderedToSit() == (i == 2) && animal.isNoAi() && animal.isNoGravity(), "Entity flags preserved " + i);
            require(animal.getHealth() == 7.5F && animal.getCustomName() != null
                    && animal.getCustomName().getString().equals("Upgrade pet " + i) && animal.entityTags().contains("upgrade_pet_" + i), "Entity state preserved " + i);
            require((animal instanceof Wolf wolf ? wolf.getCollarColor() : ((Cat) animal).getCollarColor())
                    == (i == 5 ? DyeColor.YELLOW : DyeColor.BLUE), "Custom collar preserved " + i);
            require(animal.getX() == record.x() && animal.getY() == record.y() && animal.getZ() == record.z(), "Entity position matches persisted index " + i);
            require(count(server, uuid) == 1, "Exactly one live copy of UUID " + uuid);
        }
        Entity entity = server.overworld().getEntity(pet(6));
        require(entity instanceof Pig, "Ordinary neighbor type preserved");
        Pig pig = (Pig) entity;
        require(pig.getHealth() == 5.0F && pig.isNoAi() && pig.isNoGravity() && pig.entityTags().contains("upgrade_neighbor")
                && pig.getCustomName() != null && pig.getCustomName().getString().equals("Upgrade neighbor")
                && pig.getX() == 2049.5D && pig.getY() == 65.0D && pig.getZ() == 0.5D && count(server, pet(6)) == 1, "Neighbor state and position preserved");
    }

    private static long count(MinecraftServer server, UUID uuid) {
        long count = 0;
        for (ServerLevel world : server.getAllLevels()) for (Entity entity : world.getAllEntities()) if (entity.getUUID().equals(uuid)) count++;
        return count;
    }

    private static ServerLevel originalWorld(MinecraftServer server, int i) {
        return i == 4 ? server.getLevel(Level.NETHER) : server.overworld();
    }

    private static List<PetRecord> snapshot(MinecraftServer server) {
        List<PetRecord> result = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            PetRecord record = PetIndexState.get(server).getPet(pet(i));
            require(record != null, "Missing pet " + i);
            result.add(record);
        }
        return result;
    }

    private void fail(MinecraftServer server, Throwable error) {
        failure = error.toString();
        error.printStackTrace();
        done = true;
        server.halt(false);
    }

    private void report() {
        try {
            if (channel != null) channel.finishAndReleaseAll();
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
