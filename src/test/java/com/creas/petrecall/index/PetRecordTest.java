package com.creas.petrecall.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PetRecordTest {
    @Test
    void storesRawChunkPositionLongWithoutMutation() {
        long packed = packedChunkPos(-12, 45);
        PetRecord record = record("minecraft:overworld", packed);
        assertEquals(packed, record.chunkPosLong());
    }

    @Test
    void validDimensionIdResolvesToWorldKey() {
        PetRecord record = record("minecraft:the_nether", 0L);

        assertNotNull(record.dimensionKey());
        assertEquals("minecraft:the_nether", record.dimensionKey().getValue().toString());
    }

    @Test
    void invalidDimensionIdReturnsNull() {
        PetRecord record = record("not a valid identifier", 0L);
        assertNull(record.dimensionKey());
    }

    private static PetRecord record(String dimensionId, long chunkPosLong) {
        return new PetRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:wolf",
                dimensionId,
                chunkPosLong,
                0.0D,
                64.0D,
                0.0D,
                false,
                20.0F
        );
    }

    private static long packedChunkPos(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }
}
