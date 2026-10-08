package com.creas.petrecall.runtime;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.index.PetIndexState;
import com.creas.petrecall.index.PetRecord;
import com.creas.petrecall.util.DebugTrace;
import com.creas.petrecall.util.PetOwnershipUtil;
import com.creas.petrecall.util.PetOwnershipUtil.OwnedPetData;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

public final class PetTracker {
    private final Map<UUID, Entity> loadedPets = new ConcurrentHashMap<>();

    public void onEntityLoad(Entity entity, ServerLevel world) {
        DebugTrace.log("tracker", "ENTITY_LOAD %s %s", DebugTrace.describeEntity(entity), DebugTrace.describeWorld(world));
        this.observe(entity, world);
    }

    public void onEntityUnload(Entity entity, ServerLevel world) {
        DebugTrace.log("tracker", "ENTITY_UNLOAD %s %s", DebugTrace.describeEntity(entity), DebugTrace.describeWorld(world));
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != null && reason.shouldDestroy()) {
            this.removeRecord(world.getServer(), entity.getUUID());
        } else {
            this.observe(entity, world);
        }
        this.loadedPets.remove(entity.getUUID(), entity);
    }

    public void clearRuntime() {
        DebugTrace.log("tracker", "Clearing runtime loaded pet cache; previousSize=%d", this.loadedPets.size());
        this.loadedPets.clear();
    }

    public void observe(Entity entity, ServerLevel world) {
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != null && reason.shouldDestroy()) {
            this.removeRecord(world.getServer(), entity.getUUID());
            return;
        }
        OwnedPetData ownedPet;
        try {
            ownedPet = PetOwnershipUtil.getOwnedPetData(entity);
        } catch (RuntimeException error) {
            // An unreadable modded entity is not evidence that ownership was removed.
            PetRecallMod.LOGGER.warn("Cannot inspect pet {}; keeping its index record", entity.getUUID(), error);
            return;
        }
        MinecraftServer server = world.getServer();
        if (ownedPet == null) {
            DebugTrace.log("tracker", "Ignoring entity without supported companion ownership: %s", DebugTrace.describeEntity(entity));
            this.loadedPets.remove(entity.getUUID(), entity);
            PetRecallMod.getRecallService().onPetRemoved(entity.getUUID());
            if (server != null) {
                PetIndexState.get(server).remove(entity.getUUID());
            }
            return;
        }

        if (server == null) {
            return;
        }

        this.loadedPets.put(entity.getUUID(), entity);
        PetRecallMod.getRecallService().onPetObserved(entity.getUUID());
        PetIndexState state = PetIndexState.get(server);
        PetRecord record = PetRecord.fromEntity(world, entity, ownedPet.ownerUuid(), ownedPet.sitting(), ownedPet.health());
        DebugTrace.log("tracker", "Observed supported pet: %s", DebugTrace.describeRecord(record));
        state.put(record);
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
        DebugTrace.log("tracker", "Removing indexed pet record manually: %s", DebugTrace.describePetUuid(petUuid));
        this.loadedPets.remove(petUuid);
        PetRecallMod.getRecallService().onPetRemoved(petUuid);
        PetIndexState.get(server).remove(petUuid);
    }

    public void upsertRecordFromEntity(ServerLevel world, Entity entity) {
        this.observe(entity, world);
    }

    public int rescanLoadedPetsForOwner(MinecraftServer server, UUID ownerUuid) {
        DebugTrace.log("tracker", "Starting loaded pet rescan for owner=%s", ownerUuid);
        int found = 0;
        for (ServerLevel world : server.getAllLevels()) {
            for (Entity entity : world.getAllEntities()) {
                OwnedPetData ownedPet;
                try {
                    ownedPet = PetOwnershipUtil.getOwnedPetData(entity);
                } catch (RuntimeException error) {
                    PetRecallMod.LOGGER.warn("Cannot rescan entity {}", entity.getUUID(), error);
                    continue;
                }
                if (ownedPet != null && ownedPet.ownerUuid().equals(ownerUuid)) {
                    this.observe(entity, world);
                    found++;
                }
            }
        }
        DebugTrace.log("tracker", "Finished loaded pet rescan for owner=%s found=%d", ownerUuid, found);
        return found;
    }

    public int getLoadedPetCount() {
        return this.loadedPets.size();
    }

    public int getIndexedPetCount(MinecraftServer server) {
        return PetIndexState.get(server).size();
    }
}
