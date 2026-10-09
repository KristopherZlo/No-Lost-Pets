package com.creas.petrecall.index;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.util.DebugTrace;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

public final class PetIndexState extends SavedData {
    private static final Codec<Map<UUID, PetRecord>> PETS_CODEC = Codec.unboundedMap(net.minecraft.core.UUIDUtil.AUTHLIB_CODEC, PetRecord.CODEC);
    private static final Map<MinecraftServer, Optional<PetIndexState>> SESSIONS = new IdentityHashMap<>();
    private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;
    public static final SavedDataType<PetIndexState> TYPE = new SavedDataType<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("pet_recall", "pet_recall_index"),
            PetIndexState::new,
            PETS_CODEC.flatXmap(PetIndexState::validate, state -> DataResult.success(state.copyPetMap())),
            null
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
        DebugTrace.log("index", "Pet index state created; loadedRecords=%d ownerBuckets=%d", this.pets.size(), this.petsByOwner.size());
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
        Optional<PetIndexState> state = SESSIONS.computeIfAbsent(server, PetIndexState::load);
        return state.orElseThrow(() -> new IllegalStateException("NoLostPets index is unavailable; see the server log and restart after repairing the file."));
    }

    public static boolean isAvailable(MinecraftServer server) {
        return SESSIONS.computeIfAbsent(server, PetIndexState::load).isPresent();
    }

    public static void clearSession(MinecraftServer server) {
        SESSIONS.remove(server);
    }

    private static Optional<PetIndexState> load(MinecraftServer server) {
        try {
            SavedDataStorage manager = server.overworld().getDataStorage();
            Path root = server.getWorldPath(LevelResource.ROOT);
            Path dataFolder = DimensionType.getStorageFolder(Level.OVERWORLD, root).resolve("data");
            Path current = TYPE.id().withSuffix(".dat").resolveAgainst(dataFolder);
            PetIndexState state = loadFromFiles(current, List.of(
                    root.resolve("data/pet_recall_index.dat"),
                    dataFolder.resolve("pet_recall_index.dat"),
                    dataFolder.resolve("minecraft/pet_recall_index.dat")
            ).stream().distinct().toList(), imported -> {
                manager.set(TYPE, imported);
                manager.saveAndJoin();
            });
            manager.set(TYPE, state);
            state.setDirty(false);
            return Optional.of(state);
        } catch (IOException | RuntimeException error) {
            PetRecallMod.LOGGER.error("NoLostPets index disabled until restart: cannot safely read or migrate pet records. Original files were retained.", error);
            return Optional.empty();
        }
    }

    // Read before attaching to SavedDataStorage: its normal reader can turn a corrupt file into an empty state.
    static PetIndexState loadFromFiles(Path current, List<Path> legacyFiles, Consumer<PetIndexState> saveMigration) throws IOException {
        if (isFilePresent(current)) {
            return readIndex(current);
        }
        PetIndexState imported = null;
        for (Path legacy : legacyFiles) {
            if (!isFilePresent(legacy)) continue;
            PetIndexState candidate = readIndex(legacy);
            if (imported != null && !imported.pets.equals(candidate.pets)) {
                throw new IOException("Conflicting legacy pet indexes; refusing to choose one: " + legacy);
            }
            imported = candidate;
        }
        if (imported == null) return new PetIndexState();
        try {
            saveMigration.accept(imported);
            PetIndexState persisted = readIndex(current);
            if (!persisted.pets.equals(imported.pets)) {
                throw new IOException("Saved pet index does not match the complete legacy index: " + current);
            }
            return imported;
        } finally {
            // A failed migration must not later overwrite the file from a cached SavedData instance.
            imported.setDirty(false);
        }
    }

    private static boolean isFilePresent(Path file) throws IOException {
        try {
            if (!Files.readAttributes(file, BasicFileAttributes.class).isRegularFile()) {
                throw new IOException("Expected a regular pet index file: " + file);
            }
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static PetIndexState readIndex(Path file) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            input.mark(2);
            int first = input.read();
            int second = input.read();
            input.reset();
            NbtAccounter limit = NbtAccounter.create(MAX_NBT_BYTES);
            CompoundTag root = first == 0x1f && second == 0x8b
                    ? NbtIo.readCompressed(input, limit)
                    : NbtIo.read(new DataInputStream(input), limit);
            if (root == null || !(root.get("data") instanceof CompoundTag data)) {
                throw new IOException("Pet index is missing its data compound: " + file);
            }
            DataResult<PetIndexState> decoded = TYPE.codec().parse(NbtOps.INSTANCE, data);
            return decoded.result().orElseThrow(() -> new IOException("Invalid pet index " + file + ": "
                    + decoded.error().map(DataResult.Error::message).orElse("unknown codec error")));
        } catch (RuntimeException error) {
            throw new IOException("Cannot decode complete pet index: " + file, error);
        }
    }

    private static DataResult<PetIndexState> validate(Map<UUID, PetRecord> pets) {
        for (Map.Entry<UUID, PetRecord> entry : pets.entrySet()) {
            PetRecord record = entry.getValue();
            if (!isValid(entry.getKey(), record)) {
                return DataResult.error(() -> "Invalid pet record: " + entry.getKey());
            }
        }
        return DataResult.success(new PetIndexState(pets));
    }

    private static boolean isValid(UUID key, PetRecord record) {
        return key != null && record != null && key.equals(record.petUuid()) && record.ownerUuid() != null
                && record.entityTypeId() != null && Identifier.tryParse(record.entityTypeId()) != null
                && record.dimensionId() != null && Identifier.tryParse(record.dimensionId()) != null
                && Double.isFinite(record.x()) && Double.isFinite(record.y()) && Double.isFinite(record.z())
                && Float.isFinite(record.health()) && record.health() >= 0.0F;
    }

    public void put(PetRecord record) {
        if (!isValid(record == null ? null : record.petUuid(), record)) {
            throw new IllegalArgumentException("Invalid pet record; existing index was not changed.");
        }
        PetRecord previous = this.pets.put(record.petUuid(), record);
        DebugTrace.log("index", "PUT %s previous=%s", DebugTrace.describeRecord(record), DebugTrace.describeRecord(previous));
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
        this.setDirty();
    }

    public void remove(UUID petUuid) {
        PetRecord removed = this.pets.remove(petUuid);
        if (removed == null) {
            DebugTrace.log("index", "REMOVE ignored missing pet=%s", petUuid);
            return;
        }
        DebugTrace.log("index", "REMOVE %s", DebugTrace.describeRecord(removed));
        Set<UUID> ownerSet = this.petsByOwner.get(removed.ownerUuid());
        if (ownerSet != null) {
            ownerSet.remove(petUuid);
            if (ownerSet.isEmpty()) {
                this.petsByOwner.remove(removed.ownerUuid());
            }
        }
        this.setDirty();
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
