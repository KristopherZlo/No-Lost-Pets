package com.creas.petrecall.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PetIndexStateTest {
    @Test
    void putAndGetPetKeepsOwnerIndexInSync() {
        PetIndexState state = new PetIndexState();
        UUID owner = UUID.randomUUID();
        PetRecord record = record(UUID.randomUUID(), owner, 4, 7);

        state.put(record);

        assertEquals(1, state.size());
        assertEquals(record, state.getPet(record.petUuid()));
        Collection<PetRecord> ownerPets = state.getPetsForOwner(owner);
        assertEquals(1, ownerPets.size());
        assertEquals(record, ownerPets.iterator().next());
    }

    @Test
    void replacingPetWithNewOwnerMovesItBetweenOwnerIndexes() {
        PetIndexState state = new PetIndexState();
        UUID pet = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();

        state.put(record(pet, ownerA, 1, 1));
        state.put(record(pet, ownerB, 2, 2));

        assertEquals(1, state.size());
        assertEquals(0, state.getPetsForOwner(ownerA).size());
        assertEquals(1, state.getPetsForOwner(ownerB).size());
        assertEquals(ownerB, state.getPet(pet).ownerUuid());
    }

    @Test
    void removeDeletesPetAndCleansOwnerBucket() {
        PetIndexState state = new PetIndexState();
        UUID owner = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        state.put(record(pet, owner, 0, 0));
        state.put(record(UUID.randomUUID(), owner, 1, 0));

        state.remove(pet);

        assertNull(state.getPet(pet));
        assertEquals(1, state.size());
        assertEquals(1, state.getPetsForOwner(owner).size());
    }

    @Test
    void removingUnknownPetIsNoOp() {
        PetIndexState state = new PetIndexState();
        state.remove(UUID.randomUUID());
        assertEquals(0, state.size());
    }

    @Test
    void getPetReturnsStoredRecord() {
        PetIndexState state = new PetIndexState();
        PetRecord record = record(UUID.randomUUID(), UUID.randomUUID(), 3, -2);
        state.put(record);

        PetRecord loaded = state.getPet(record.petUuid());
        assertNotNull(loaded);
        assertEquals(record.chunkPosLong(), loaded.chunkPosLong());
    }

    private static PetRecord record(UUID petUuid, UUID ownerUuid, int chunkX, int chunkZ) {
        return new PetRecord(
                petUuid,
                ownerUuid,
                "minecraft:wolf",
                "minecraft:overworld",
                packedChunkPos(chunkX, chunkZ),
                1.0D,
                64.0D,
                1.0D,
                false,
                20.0F
        );
    }

    private static long packedChunkPos(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }
}
