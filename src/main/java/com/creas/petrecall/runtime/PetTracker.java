package com.creas.petrecall.runtime;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.util.PetOwnershipUtil;
import com.creas.petrecall.util.PetOwnershipUtil.OwnedPetData;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;

public final class PetTracker {
    private final Map<UUID, Entity> loadedPets = new ConcurrentHashMap<>();

    public void onEntityLoad(Entity entity, ServerWorld world) {
        this.observe(entity, world);
    }

    public void onEntityUnload(Entity entity, ServerWorld world) {
        this.observe(entity, world);
        this.loadedPets.remove(entity.getUuid(), entity);
    }

    public void clearRuntime() {
        this.loadedPets.clear();
    }

    public void observe(Entity entity, ServerWorld world) {
        OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(entity);
        MinecraftServer server = world.getServer();
        if (ownedPet == null) {
            this.loadedPets.remove(entity.getUuid(), entity);
            if (server != null) {
                PetIndexState.get(server).remove(entity.getUuid());
            }
            return;
        }

        if (server == null) {
            return;
        }

        this.loadedPets.put(entity.getUuid(), entity);
        PetIndexState state = PetIndexState.get(server);
        state.put(PetRecord.fromEntity(world, entity, ownedPet.ownerUuid(), ownedPet.sitting(), ownedPet.health()));
    }

    @Nullable
    public Entity getLoadedPet(UUID petUuid) {
        Entity entity = this.loadedPets.get(petUuid);
        if (entity == null || entity.isRemoved()) {
            this.loadedPets.remove(petUuid);
            return null;
        }
        return entity;
    }

    public Collection<PetRecord> getOwnerRecords(MinecraftServer server, UUID ownerUuid) {
        return PetIndexState.get(server).getPetsForOwner(ownerUuid);
    }

    public void removeRecord(MinecraftServer server, UUID petUuid) {
        this.loadedPets.remove(petUuid);
        PetIndexState.get(server).remove(petUuid);
    }

    public void upsertRecordFromEntity(ServerWorld world, Entity entity) {
        this.observe(entity, world);
    }

    public int rescanLoadedPetsForOwner(MinecraftServer server, UUID ownerUuid) {
        int found = 0;
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                OwnedPetData ownedPet = PetOwnershipUtil.getOwnedPetData(entity);
                if (ownedPet != null && ownedPet.ownerUuid().equals(ownerUuid)) {
                    this.observe(entity, world);
                    found++;
                }
            }
        }
        return found;
    }

    public int getLoadedPetCount() {
        return this.loadedPets.size();
    }
}
