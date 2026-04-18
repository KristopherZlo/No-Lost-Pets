package com.creas.petrecall.util;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.EntityChunkDataAccess;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jetbrains.annotations.Nullable;

public final class VersionCompat {
    private static final Supplier<NbtCompound> EMPTY_NBT_SUPPLIER = () -> null;
    private static final @Nullable Method GAME_PROFILE_GET_ID = findMethod(GameProfile.class, "getId");
    private static final @Nullable Method GAME_PROFILE_ID = findMethod(GameProfile.class, "id");
    private static final @Nullable Method GAME_PROFILE_GET_NAME = findMethod(GameProfile.class, "getName");
    private static final @Nullable Method GAME_PROFILE_NAME = findMethod(GameProfile.class, "name");
    private static final @Nullable Method ENTITY_GET_ENTITY_WORLD = findMethod(Entity.class, "getEntityWorld");
    private static final @Nullable Method ENTITY_GET_WORLD = findMethod(Entity.class, "getWorld");
    private static final @Nullable Field ENTITY_CHUNK_STORAGE = findField(EntityChunkDataAccess.class, "storage");
    private static final @Nullable Field ENTITY_CHUNK_EMPTY_CHUNKS = findField(EntityChunkDataAccess.class, "emptyChunks");
    private VersionCompat() {
    }

    @Nullable
    public static ServerWorld getServerWorld(Entity entity) {
        Object world = invokeNoArgs(entity, ENTITY_GET_ENTITY_WORLD);
        if (world == null) {
            world = invokeNoArgs(entity, ENTITY_GET_WORLD);
        }
        if (world instanceof ServerWorld serverWorld) {
            return serverWorld;
        }
        return null;
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
        Method supplierSet = findMethod(storage.getClass(), "set", ChunkPos.class, Supplier.class);
        if (supplierSet != null) {
            return invokeStorage(supplierSet, storage, chunkPos, EMPTY_NBT_SUPPLIER);
        }
        Method directSet = findMethod(storage.getClass(), "set", ChunkPos.class, NbtCompound.class);
        if (directSet != null) {
            return invokeStorage(directSet, storage, chunkPos, (Object) null);
        }
        throw new IllegalStateException("Unsupported chunk storage clear signature: " + storage.getClass().getName());
    }

    public static CompletableFuture<Void> writeChunkData(Object storage, ChunkPos chunkPos, NbtCompound chunkNbt) {
        Method directSetNbt = findMethod(storage.getClass(), "setNbt", ChunkPos.class, NbtCompound.class);
        if (directSetNbt != null) {
            return invokeStorage(directSetNbt, storage, chunkPos, chunkNbt);
        }
        Method supplierSetNbt = findMethod(storage.getClass(), "setNbt", ChunkPos.class, Supplier.class);
        if (supplierSetNbt != null) {
            return invokeStorage(supplierSetNbt, storage, chunkPos, (Supplier<NbtCompound>) () -> chunkNbt);
        }
        Method directSet = findMethod(storage.getClass(), "set", ChunkPos.class, NbtCompound.class);
        if (directSet != null) {
            return invokeStorage(directSet, storage, chunkPos, chunkNbt);
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
