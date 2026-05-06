package com.creas.petrecall.util;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.EntityChunkDataAccess;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jetbrains.annotations.Nullable;

public final class VersionCompat {
    private static final Supplier<NbtCompound> EMPTY_NBT_SUPPLIER = () -> null;
    private static final @Nullable Method GAME_PROFILE_GET_ID = findMethod(GameProfile.class, "getId");
    private static final @Nullable Method GAME_PROFILE_ID = findMethod(GameProfile.class, "id");
    private static final @Nullable Method GAME_PROFILE_GET_NAME = findMethod(GameProfile.class, "getName");
    private static final @Nullable Method GAME_PROFILE_NAME = findMethod(GameProfile.class, "name");
    private static final @Nullable Method ENTITY_WORLD_METHOD = findEntityWorldMethod();
    private static final @Nullable Field ENTITY_WORLD_FIELD = findEntityWorldField();
    private static final @Nullable Field ENTITY_CHUNK_STORAGE = findEntityChunkStorageField();
    private static final @Nullable Field ENTITY_CHUNK_EMPTY_CHUNKS = findEntityChunkEmptyChunksField();
    private VersionCompat() {
    }

    @Nullable
    public static ServerWorld getServerWorld(Entity entity) {
        Object world = invokeNoArgs(entity, ENTITY_WORLD_METHOD);
        if (world == null) {
            world = readField(entity, ENTITY_WORLD_FIELD);
        }
        return world instanceof ServerWorld serverWorld ? serverWorld : null;
    }

    @Nullable
    public static MinecraftServer getServer(Entity entity) {
        ServerWorld world = getServerWorld(entity);
        return world == null ? null : world.getServer();
    }

    public static String getDimensionId(Entity entity) {
        ServerWorld world = getServerWorld(entity);
        return world == null ? "" : world.getRegistryKey().getValue().toString();
    }

    public static boolean hasAdminPermission(ServerCommandSource source) {
        if (source.getPlayer() == null) {
            return true;
        }

        MinecraftServer server = source.getServer();
        ServerPlayerEntity player = source.getPlayer();
        GameProfile profile = player.getGameProfile();
        if (matchesProfile(profile, server.getHostProfile())) {
            return true;
        }

        for (String operatorName : server.getPlayerManager().getOpList().getNames()) {
            if (namesMatch(getProfileName(profile), operatorName)) {
                return true;
            }
        }
        return false;
    }

    public static Object getChunkStorage(EntityChunkDataAccess dataAccess) {
        Object storage = readField(dataAccess, ENTITY_CHUNK_STORAGE);
        if (storage == null) {
            throw new IllegalStateException("EntityChunkDataAccess storage field is unavailable");
        }
        return storage;
    }

    public static LongSet getEmptyChunks(EntityChunkDataAccess dataAccess) {
        Object emptyChunks = readField(dataAccess, ENTITY_CHUNK_EMPTY_CHUNKS);
        if (emptyChunks instanceof LongSet longSet) {
            return longSet;
        }
        throw new IllegalStateException("EntityChunkDataAccess emptyChunks field is unavailable");
    }

    public static CompletableFuture<Void> clearChunkData(Object storage, ChunkPos chunkPos) {
        Method supplierWrite = findStorageMethod(storage.getClass(), ChunkPos.class, Supplier.class);
        if (supplierWrite != null) {
            return invokeStorage(supplierWrite, storage, chunkPos, EMPTY_NBT_SUPPLIER);
        }
        Method directNbtWrite = findStorageMethod(storage.getClass(), ChunkPos.class, NbtCompound.class);
        if (directNbtWrite != null) {
            return invokeStorage(directNbtWrite, storage, chunkPos, (NbtCompound) null);
        }
        throw new IllegalStateException("Unsupported chunk storage clear signature: " + storage.getClass().getName());
    }

    public static CompletableFuture<Void> writeChunkData(Object storage, ChunkPos chunkPos, NbtCompound chunkNbt) {
        Method directNbtWrite = findStorageMethod(storage.getClass(), ChunkPos.class, NbtCompound.class);
        if (directNbtWrite != null) {
            return invokeStorage(directNbtWrite, storage, chunkPos, chunkNbt);
        }
        Method supplierNbtWrite = findStorageMethod(storage.getClass(), ChunkPos.class, Supplier.class);
        if (supplierNbtWrite != null) {
            return invokeStorage(supplierNbtWrite, storage, chunkPos, (Supplier<NbtCompound>) () -> chunkNbt);
        }
        throw new IllegalStateException("Unsupported chunk storage write signature: " + storage.getClass().getName());
    }

    @Nullable
    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findEntityWorldMethod() {
        String[] preferredNames = {"getEntityWorld", "getWorld"};
        for (String name : preferredNames) {
            Method method = findWorldMethodByName(name);
            if (method != null) {
                return method;
            }
        }

        for (Method method : Entity.class.getMethods()) {
            if (method.getParameterCount() == 0
                    && World.class.isAssignableFrom(method.getReturnType())
                    && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Method findWorldMethodByName(String name) {
        for (Method method : Entity.class.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 0
                    && World.class.isAssignableFrom(method.getReturnType())
                    && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Field findEntityWorldField() {
        Class<?> owner = Entity.class;
        while (owner != null) {
            for (Field field : owner.getDeclaredFields()) {
                if (World.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            }
            owner = owner.getSuperclass();
        }
        return null;
    }

    @Nullable
    private static Field findEntityChunkStorageField() {
        Field mapped = findMappedMinecraftField(
                EntityChunkDataAccess.class,
                "net.minecraft.world.storage.EntityChunkDataAccess",
                "storage",
                "Lnet/minecraft/world/storage/VersionedChunkStorage;",
                "Lnet/minecraft/world/storage/ChunkPosKeyedStorage;"
        );
        if (mapped != null) {
            return mapped;
        }

        for (Field field : EntityChunkDataAccess.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (findStorageMethod(fieldType, ChunkPos.class, Supplier.class) != null
                    || findStorageMethod(fieldType, ChunkPos.class, NbtCompound.class) != null) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    @Nullable
    private static Field findEntityChunkEmptyChunksField() {
        Field mapped = findMappedMinecraftField(
                EntityChunkDataAccess.class,
                "net.minecraft.world.storage.EntityChunkDataAccess",
                "emptyChunks",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"
        );
        return mapped != null ? mapped : findFieldByType(EntityChunkDataAccess.class, LongSet.class);
    }

    @Nullable
    private static Field findMappedMinecraftField(Class<?> owner, String ownerNamedName, String namedField, String... descriptors) {
        Field direct = findField(owner, namedField);
        if (direct != null) {
            return direct;
        }

        for (String descriptor : descriptors) {
            try {
                String runtimeName = FabricLoader.getInstance()
                        .getMappingResolver()
                        .mapFieldName("named", ownerNamedName, namedField, descriptor);
                if (!runtimeName.equals(namedField)) {
                    Field mapped = findField(owner, runtimeName);
                    if (mapped != null) {
                        return mapped;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Fabric mappings may be unavailable in isolated unit tests.
            }
        }
        return null;
    }

    @Nullable
    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static Field findFieldByType(Class<?> owner, Class<?> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    @Nullable
    private static Method findStorageMethod(Class<?> owner, Class<?> firstParameterType, Class<?> secondParameterType) {
        for (Method method : owner.getMethods()) {
            if (!CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 2
                    && parameterTypes[0] == firstParameterType
                    && parameterTypes[1] == secondParameterType) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Object invokeNoArgs(Object target, @Nullable Method method) {
        if (method == null) {
            return null;
        }
        return invoke(target, method);
    }

    @Nullable
    private static Object invoke(Object target, Method method, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke " + method.getName(), e);
        }
    }

    @Nullable
    private static Object readField(Object target, @Nullable Field field) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read field " + field.getName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> invokeStorage(Method method, Object storage, Object... args) {
        try {
            return (CompletableFuture<Void>) method.invoke(storage, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke storage method " + method.getName(), e);
        }
    }

    static boolean matchesProfileKey(GameProfile profile, @Nullable Object key) {
        if (key instanceof GameProfile gameProfile) {
            return matchesProfile(profile, gameProfile);
        }
        if (key == null) {
            return false;
        }

        UUID keyUuid = extractRecordUuid(key);
        UUID profileUuid = getProfileId(profile);
        if (keyUuid != null && profileUuid != null && profileUuid.equals(keyUuid)) {
            return true;
        }

        String keyName = extractRecordName(key);
        return namesMatch(getProfileName(profile), keyName);
    }

    private static boolean matchesProfile(GameProfile expected, @Nullable GameProfile actual) {
        if (actual == null) {
            return false;
        }
        UUID expectedId = getProfileId(expected);
        UUID actualId = getProfileId(actual);
        if (expectedId != null && actualId != null && expectedId.equals(actualId)) {
            return true;
        }
        return namesMatch(getProfileName(expected), getProfileName(actual));
    }

    private static boolean namesMatch(@Nullable String first, @Nullable String second) {
        if (first == null || second == null) {
            return false;
        }
        return first.toLowerCase(Locale.ROOT).equals(second.toLowerCase(Locale.ROOT));
    }

    @Nullable
    private static UUID extractRecordUuid(Object key) {
        Object value = readRecordComponentByType(key, UUID.class);
        return value instanceof UUID uuid ? uuid : null;
    }

    @Nullable
    private static String extractRecordName(Object key) {
        Object value = readRecordComponentByType(key, String.class);
        return value instanceof String string ? string : null;
    }

    @Nullable
    private static Object readRecordComponentByType(Object recordLike, Class<?> type) {
        Class<?> keyClass = recordLike.getClass();
        if (!keyClass.isRecord()) {
            return null;
        }
        try {
            for (RecordComponent component : keyClass.getRecordComponents()) {
                if (component.getType() != type) {
                    continue;
                }
                return component.getAccessor().invoke(recordLike);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inspect record key " + keyClass.getName(), e);
        }
        return null;
    }

    @Nullable
    private static UUID getProfileId(GameProfile profile) {
        Object value = invokeNoArgs(profile, GAME_PROFILE_GET_ID);
        if (value == null) {
            value = invokeNoArgs(profile, GAME_PROFILE_ID);
        }
        return value instanceof UUID uuid ? uuid : null;
    }

    @Nullable
    private static String getProfileName(GameProfile profile) {
        Object value = invokeNoArgs(profile, GAME_PROFILE_GET_NAME);
        if (value == null) {
            value = invokeNoArgs(profile, GAME_PROFILE_NAME);
        }
        return value instanceof String string ? string : null;
    }
}
