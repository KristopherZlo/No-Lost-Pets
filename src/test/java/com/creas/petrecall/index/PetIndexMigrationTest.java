package com.creas.petrecall.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PetIndexMigrationTest {
    private static final UUID PET = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    // Independent legacy fixture: do not generate this with the codec being tested.
    private static final String LEGACY = """
            {DataVersion:4671,data:{
              "11111111-1111-4111-8111-111111111111":{
                pet_uuid:"11111111-1111-4111-8111-111111111111",owner_uuid:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                entity_type:"minecraft:wolf",dimension:"minecraft:overworld",chunk_pos:197568495604L,
                x:-184.5d,y:64.25d,z:728.125d,sitting:0b,health:7.5f},
              "22222222-2222-4222-8222-222222222222":{
                pet_uuid:"22222222-2222-4222-8222-222222222222",owner_uuid:"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                entity_type:"minecraft:cat",dimension:"minecraft:the_nether",chunk_pos:-8589934591L,
                x:17.25d,y:-12.5d,z:-31.75d,sitting:1b,health:3.25f}}}
            """;
    @TempDir Path directory;

    @Test void importsEveryFieldAndLeavesLegacyBytesUnchanged() throws Exception {
        Path legacy = legacy(LEGACY);
        byte[] original = Files.readAllBytes(legacy);
        AtomicInteger saves = new AtomicInteger();
        PetIndexState state = load(List.of(legacy), imported -> {
            saves.incrementAndGet();
            save(current(), imported);
        });
        assertEquals(1, saves.get());
        assertEquals(2, state.size());
        assertEquals(new PetRecord(PET, OWNER, "minecraft:wolf", "minecraft:overworld", 197568495604L,
                -184.5, 64.25, 728.125, false, 7.5f), state.getPet(PET));
        assertEquals(new PetRecord(OTHER, OWNER, "minecraft:cat", "minecraft:the_nether", -8589934591L,
                17.25, -12.5, -31.75, true, 3.25f), state.getPet(OTHER));
        assertEquals(2, state.getPetsForOwner(OWNER).size());
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertFalse(state.isDirty());
    }

    @Test void restartDoesNotReimportRemovedPetOrUndoOwnerChange() throws Exception {
        Path legacy = legacy(LEGACY);
        PetIndexState first = load(List.of(legacy), state -> save(current(), state));
        first.remove(OTHER);
        PetRecord old = first.getPet(PET);
        UUID newOwner = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        first.put(new PetRecord(PET, newOwner, old.entityTypeId(), old.dimensionId(), old.chunkPosLong(),
                old.x(), old.y(), old.z(), old.sitting(), old.health()));
        save(current(), first);
        PetIndexState restarted = load(List.of(legacy), ignored -> fail("Existing new index must never reimport"));
        assertEquals(1, restarted.size());
        assertNull(restarted.getPet(OTHER));
        assertEquals(newOwner, restarted.getPet(PET).ownerUuid());
        assertTrue(restarted.getPetsForOwner(OWNER).isEmpty());
        assertEquals(1, restarted.getPetsForOwner(newOwner).size());
    }

    @Test void validNewEmptyIndexWinsEvenOverCorruptLegacy() throws Exception {
        Path legacy = legacy(LEGACY);
        Files.write(legacy, new byte[]{1, 2, 3});
        save(current(), new PetIndexState());
        assertEquals(0, load(List.of(legacy), ignored -> fail("Must not migrate again")).size());
    }

    @Test void corruptNewIndexDoesNotFallBackToValidLegacy() throws Exception {
        Path legacy = legacy(LEGACY);
        Files.createDirectories(current().getParent());
        Files.write(current(), new byte[]{31, (byte) 139, 0});
        byte[] damaged = Files.readAllBytes(current());
        assertThrows(IOException.class, () -> load(List.of(legacy), ignored -> fail("Must not overwrite corrupt new index")));
        assertArrayEquals(damaged, Files.readAllBytes(current()));
    }

    @Test void absentIndexesCreateEmptyStateWithoutWriting() throws Exception {
        assertEquals(0, load(List.of(directory.resolve("absent.dat")), ignored -> fail("No migration needed")).size());
        assertFalse(Files.exists(current()));
    }

    @Test void truncatedLegacyIsNotSilentlyDiscarded() throws Exception {
        Path legacy = legacy(LEGACY);
        byte[] bytes = Files.readAllBytes(legacy);
        Files.write(legacy, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertRejected(legacy);
    }

    @Test void oneInvalidEntryRejectsWholeIndexRatherThanAcceptingPartialMap() throws Exception {
        assertRejected(legacy(LEGACY.replace("minecraft:cat", "cat with spaces").replace("health:3.25f", "health:\"broken\"")));
    }

    @Test void validUuidKeyMustEqualRecordUuid() throws Exception {
        assertRejected(legacy(LEGACY.replace("pet_uuid:\"11111111-1111-4111-8111-111111111111\"",
                "pet_uuid:\"22222222-2222-4222-8222-222222222222\"")));
    }

    @Test void malformedUuidInOtherwiseValidMapRejectsAllRecords() throws Exception {
        assertRejected(legacy(LEGACY.replace("owner_uuid:\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"", "owner_uuid:\"not-a-uuid\"")));
    }

    @Test void malformedDimensionRejectsWholeMap() throws Exception {
        assertRejected(legacy(LEGACY.replace("minecraft:the_nether", "dimension with spaces")));
    }

    @Test void negativeHealthRejectsWholeMap() throws Exception {
        assertRejected(legacy(LEGACY.replace("health:3.25f", "health:-1.0f")));
    }

    @Test void nonfinitePositionRejectsWholeMap() throws Exception {
        Path legacy = legacy(LEGACY);
        CompoundTag root = TagParser.parseCompoundFully(LEGACY);
        ((CompoundTag) ((CompoundTag) root.get("data")).get(PET.toString())).putDouble("x", Double.NaN);
        NbtIo.writeCompressed(root, legacy);
        assertRejected(legacy);
    }

    @Test void infiniteHealthRejectsWholeMap() throws Exception {
        Path legacy = legacy(LEGACY);
        CompoundTag root = TagParser.parseCompoundFully(LEGACY);
        ((CompoundTag) ((CompoundTag) root.get("data")).get(PET.toString())).putFloat("health", Float.POSITIVE_INFINITY);
        NbtIo.writeCompressed(root, legacy);
        assertRejected(legacy);
    }

    @Test void oneCorruptLegacyCopyDoesNotAllowMigrationOfAnother() throws Exception {
        Path good = legacy(LEGACY);
        Path bad = directory.resolve("bad.dat");
        Files.write(bad, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> load(List.of(good, bad), ignored -> fail("Must read all candidate files first")));
        assertFalse(Files.exists(current()));
    }

    @Test void missingRequiredPositionRejectsWholeMap() throws Exception {
        assertRejected(legacy(LEGACY.replace("x:17.25d,", "")));
    }

    @Test void missingDataCompoundIsNotAnEmptyIndex() throws Exception {
        assertRejected(legacy("{DataVersion:4671,other:{}}"));
    }

    @Test void oldOptionalDefaultsArePreserved() throws Exception {
        Path legacy = legacy(LEGACY.replace(",sitting:1b,health:3.25f", ""));
        PetIndexState state = load(List.of(legacy), imported -> save(current(), imported));
        assertFalse(state.getPet(OTHER).sitting());
        assertEquals(0.0f, state.getPet(OTHER).health());
    }

    @Test void acceptsUncompressedLegacyWithoutChangingItsFormat() throws Exception {
        Path legacy = directory.resolve("old.dat");
        NbtIo.write(TagParser.parseCompoundFully(LEGACY), legacy);
        byte[] original = Files.readAllBytes(legacy);
        assertEquals(2, load(List.of(legacy), state -> save(current(), state)).size());
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    @Test void writerReturningWithoutFileIsNotReportedAsSuccess() throws Exception {
        Path legacy = legacy(LEGACY);
        assertThrows(IOException.class, () -> load(List.of(legacy), ignored -> {}));
    }

    @Test void writerDroppingOneRecordIsRejected() throws Exception {
        Path legacy = legacy(LEGACY);
        assertThrows(IOException.class, () -> load(List.of(legacy), state -> {
            PetIndexState incomplete = new PetIndexState();
            incomplete.put(state.getPet(PET));
            save(current(), incomplete);
        }));
        assertTrue(Files.exists(legacy));
    }

    @Test void writerFailureRetainsLegacyAndClearsDirtyState() throws Exception {
        Path legacy = legacy(LEGACY);
        byte[] original = Files.readAllBytes(legacy);
        assertThrows(UncheckedIOException.class, () -> load(List.of(legacy), state -> {
            state.setDirty();
            try {
                throw new UncheckedIOException(new IOException("disk full"));
            } finally {
                // The caller retains a reference just as SavedDataStorage does.
                captured = state;
            }
        }));
        assertNotNull(captured);
        assertFalse(captured.isDirty());
        assertArrayEquals(original, Files.readAllBytes(legacy));
        assertFalse(Files.exists(current()));
    }
    private PetIndexState captured;

    @Test void conflictingLegacyLocationsAreRejectedBeforeWriting() throws Exception {
        Path first = legacy(LEGACY);
        Path second = directory.resolve("other.dat");
        NbtIo.writeCompressed(TagParser.parseCompoundFully(LEGACY.replace("health:7.5f", "health:1.0f")), second);
        assertThrows(IOException.class, () -> load(List.of(first, second), ignored -> fail("Must resolve conflicting files first")));
        assertFalse(Files.exists(current()));
    }

    @Test void equivalentLegacyCopiesMigrateOnce() throws Exception {
        Path first = legacy(LEGACY);
        Path second = directory.resolve("other.dat");
        Files.copy(first, second);
        AtomicInteger saves = new AtomicInteger();
        assertEquals(2, load(List.of(first, second), state -> { saves.incrementAndGet(); save(current(), state); }).size());
        assertEquals(1, saves.get());
    }

    @Test void directoryAtCurrentFilePathMustNotTriggerLegacyOverwrite() throws Exception {
        Path legacy = legacy(LEGACY);
        Files.createDirectories(current());
        assertThrows(IOException.class, () -> load(List.of(legacy), ignored -> fail("Cannot replace a directory")));
    }

    private Path current() { return directory.resolve("data/pet_recall/pet_recall_index.dat"); }

    private Path legacy(String snbt) throws Exception {
        Path file = directory.resolve("pet_recall_index.dat");
        NbtIo.writeCompressed(TagParser.parseCompoundFully(snbt), file);
        return file;
    }

    private PetIndexState load(List<Path> legacy, java.util.function.Consumer<PetIndexState> writer) throws IOException {
        return PetIndexState.loadFromFiles(current(), legacy, writer);
    }

    private void assertRejected(Path legacy) throws IOException {
        byte[] original = Files.readAllBytes(legacy);
        assertThrows(IOException.class, () -> load(List.of(legacy), ignored -> fail("Invalid input must never be saved")));
        assertFalse(Files.exists(current()));
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    private static void save(Path file, PetIndexState state) {
        try {
            Files.createDirectories(file.getParent());
            CompoundTag root = new CompoundTag();
            root.put("data", PetIndexState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow());
            NbtIo.writeCompressed(root, file);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
