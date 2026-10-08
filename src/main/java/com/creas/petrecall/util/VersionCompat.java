package com.creas.petrecall.util;

import com.mojang.authlib.GameProfile;
import com.creas.petrecall.mixin.accessor.ServerWorldAccessor;
import com.creas.petrecall.mixin.accessor.ServerConfigEntryAccessor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class VersionCompat {
    private static final @Nullable Method GAME_PROFILE_GET_ID = findMethod(GameProfile.class, "getId");
    private static final @Nullable Method GAME_PROFILE_ID = findMethod(GameProfile.class, "id");
    private static final @Nullable Method ENTITY_WORLD_METHOD = findEntityWorldMethod();
    private static final @Nullable Field ENTITY_WORLD_FIELD = ENTITY_WORLD_METHOD == null ? findEntityWorldField() : null;
    private static @Nullable TicketType recallTicket;
    private VersionCompat() {
    }

    @Nullable
    public static ServerLevel getServerWorld(Entity entity) {
        Object world = invokeNoArgs(entity, ENTITY_WORLD_METHOD);
        if (world == null) {
            world = readField(entity, ENTITY_WORLD_FIELD);
        }
        return world instanceof ServerLevel serverWorld ? serverWorld : null;
    }

    @Nullable
    public static MinecraftServer getServer(Entity entity) {
        ServerLevel world = getServerWorld(entity);
        return world == null ? null : world.getServer();
    }

    public static String getDimensionId(Entity entity) {
        ServerLevel world = getServerWorld(entity);
        return world == null ? "" : world.dimension().identifier().toString();
    }

    public static boolean hasAdminPermission(CommandSourceStack source) {
        if (source.getPlayer() == null) {
            return true;
        }

        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        GameProfile profile = player.getGameProfile();
        if (matchesProfile(profile, server.getSingleplayerProfile())) {
            return true;
        }

        for (Object entry : server.getPlayerList().getOps().getEntries()) {
            Object key = ((ServerConfigEntryAccessor) entry).pet_recall$getKey();
            if (matchesProfileKey(profile, key)) {
                return true;
            }
        }
        return false;
    }

    public static void initialize() {
        if (ENTITY_WORLD_METHOD == null && ENTITY_WORLD_FIELD == null) {
            throw new IllegalStateException("Cannot identify the entity world on this Minecraft version");
        }
        if (recallTicket == null) {
            recallTicket = Registry.register(BuiltInRegistries.TICKET_TYPE,
                    Identifier.fromNamespaceAndPath("pet_recall", "recall"), createRecallTicket());
        }
    }

    private static TicketType createRecallTicket() {
        // 1.21.8 uses (expiry, persist, use); 1.21.9+ uses (expiry, flags).
        // UNKNOWN is a loading-only, non-persistent ticket on both lines.
        try {
            RecordComponent[] components = TicketType.class.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            int expiryComponents = 0;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = components[i].getAccessor().invoke(TicketType.UNKNOWN);
                if (types[i] == long.class) {
                    values[i] = 240L;
                    expiryComponents++;
                }
            }
            boolean current = types.length == 2 && types[0] == long.class && types[1] == int.class;
            boolean legacy = types.length == 3 && types[0] == long.class
                    && types[1] == boolean.class && types[2].isEnum();
            if (expiryComponents != 1 || (!current && !legacy)) {
                throw new IllegalStateException("Unsupported chunk ticket layout");
            }
            Constructor<TicketType> constructor = TicketType.class.getConstructor(types);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot create recall chunk ticket", error);
        }
    }

    public static void holdRecallChunk(ServerLevel world, ChunkPos pos) {
        if (recallTicket == null) {
            throw new IllegalStateException("Recall chunk ticket was not initialized");
        }
        world.getChunkSource().addTicketWithRadius(recallTicket, pos, 0);
    }

    public static void releaseRecallChunk(ServerLevel world, ChunkPos pos) {
        if (recallTicket != null) {
            world.getChunkSource().removeTicketWithRadius(recallTicket, pos, 0);
        }
    }

    public static boolean areChunkEntitiesLoaded(ServerLevel world, ChunkPos pos) {
        return ((ServerWorldAccessor) world).pet_recall$getEntityManager().areEntitiesLoaded(pos.toLong());
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

        Method match = null;
        for (Method method : Entity.class.getMethods()) {
            if (method.getParameterCount() == 0
                    && Level.class.isAssignableFrom(method.getReturnType())
                    && !Modifier.isStatic(method.getModifiers())) {
                if (match != null) {
                    return null;
                }
                match = method;
            }
        }
        return match;
    }

    @Nullable
    private static Method findWorldMethodByName(String name) {
        for (Method method : Entity.class.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 0
                    && Level.class.isAssignableFrom(method.getReturnType())
                    && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }

    @Nullable
    private static Field findEntityWorldField() {
        Class<?> owner = Entity.class;
        Field match = null;
        while (owner != null) {
            for (Field field : owner.getDeclaredFields()) {
                if (Level.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) {
                    if (match != null) {
                        return null;
                    }
                    match = field;
                }
            }
            owner = owner.getSuperclass();
        }
        if (match != null) {
            match.setAccessible(true);
        }
        return match;
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

    static boolean matchesProfileKey(GameProfile profile, @Nullable Object key) {
        if (key instanceof GameProfile gameProfile) {
            return matchesProfile(profile, gameProfile);
        }
        Object id = key == null ? null : readRecordComponentByType(key, UUID.class);
        UUID profileId = getProfileId(profile);
        return profileId != null && profileId.equals(id);
    }

    private static boolean matchesProfile(GameProfile expected, @Nullable GameProfile actual) {
        UUID expectedId = getProfileId(expected);
        return actual != null && expectedId != null && expectedId.equals(getProfileId(actual));
    }

    @Nullable
    private static Object readRecordComponentByType(Object recordLike, Class<?> type) {
        Class<?> keyClass = recordLike.getClass();
        if (!keyClass.isRecord()) {
            return null;
        }
        try {
            RecordComponent match = null;
            for (RecordComponent component : keyClass.getRecordComponents()) {
                if (component.getType() != type) {
                    continue;
                }
                if (match != null) {
                    return null;
                }
                match = component;
            }
            if (match != null) {
                Method accessor = match.getAccessor();
                accessor.setAccessible(true);
                return accessor.invoke(recordLike);
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

}
