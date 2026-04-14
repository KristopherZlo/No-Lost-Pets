package com.creas.petrecall.index;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.PersistentStateType;
import org.jetbrains.annotations.Nullable;

public final class PetIndexState extends PersistentState {
    private static final Codec<Map<UUID, PetRecord>> PETS_CODEC = Codec.unboundedMap(net.minecraft.util.Uuids.CODEC, PetRecord.CODEC);
    public static final PersistentStateType<PetIndexState> TYPE = new PersistentStateType<>(
            "pet_recall_index",
            PetIndexState::new,
            PETS_CODEC.xmap(PetIndexState::new, PetIndexState::copyPetMap),
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<UUID, PetRecord> pets;
    private final Map<UUID, Set<UUID>> petsByOwner;

    public PetIndexState() {
        this(new HashMap<>());
    }

    private PetIndexState(Map<UUID, PetRecord> pets) {
        this.pets = new HashMap<>(pets);
        this.petsByOwner = new HashMap<>();
        this.rebuildOwnerIndex();
    }

    private void rebuildOwnerIndex() {
        this.petsByOwner.clear();
        for (PetRecord record : this.pets.values()) {
            this.petsByOwner.computeIfAbsent(record.ownerUuid(), ignored -> new HashSet<>()).add(record.petUuid());
        }
    }

    private Map<UUID, PetRecord> copyPetMap() {
        return new HashMap<>(this.pets);
    }

    public static PetIndexState get(MinecraftServer server) {
        PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
        return manager.getOrCreate(TYPE);
    }

    public void put(PetRecord record) {
        PetRecord previous = this.pets.put(record.petUuid(), record);
        if (previous != null && !previous.ownerUuid().equals(record.ownerUuid())) {
            Set<UUID> oldSet = this.petsByOwner.get(previous.ownerUuid());
            if (oldSet != null) {
                oldSet.remove(previous.petUuid());
                if (oldSet.isEmpty()) {
                    this.petsByOwner.remove(previous.ownerUuid());
                }
            }
        }
        this.petsByOwner.computeIfAbsent(record.ownerUuid(), ignored -> new HashSet<>()).add(record.petUuid());
        this.markDirty();
    }

    public void remove(UUID petUuid) {
        PetRecord removed = this.pets.remove(petUuid);
        if (removed == null) {
            return;
        }
        Set<UUID> ownerSet = this.petsByOwner.get(removed.ownerUuid());
        if (ownerSet != null) {
            ownerSet.remove(petUuid);
            if (ownerSet.isEmpty()) {
                this.petsByOwner.remove(removed.ownerUuid());
            }
        }
        this.markDirty();
    }

    @Nullable
    public PetRecord getPet(UUID petUuid) {
        return this.pets.get(petUuid);
    }

    public Collection<PetRecord> getPetsForOwner(UUID ownerUuid) {
        Set<UUID> ids = this.petsByOwner.get(ownerUuid);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<PetRecord> records = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            PetRecord record = this.pets.get(id);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    public int size() {
        return this.pets.size();
    }
}
