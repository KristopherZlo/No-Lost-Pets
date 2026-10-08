package com.creas.petrecall.util;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

public final class VersionCompat {
    private static @Nullable TicketType recallTicket;

    private VersionCompat() { }

    @Nullable
    public static ServerLevel getServerWorld(Entity entity) {
        return entity.level() instanceof ServerLevel level ? level : null;
    }

    @Nullable
    public static MinecraftServer getServer(Entity entity) {
        ServerLevel level = getServerWorld(entity);
        return level == null ? null : level.getServer();
    }

    public static String getDimensionId(Entity entity) {
        ServerLevel level = getServerWorld(entity);
        return level == null ? "" : level.dimension().identifier().toString();
    }

    public static boolean hasAdminPermission(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) {
            return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        }
        var server = source.getServer();
        var profile = player.getGameProfile();
        if (matchesProfileKey(profile, server.getSingleplayerProfile())) {
            return true;
        }
        return server.getPlayerList().getOps().getEntries().stream()
                .anyMatch(entry -> matchesProfileKey(profile, entry.getUser()));
    }

    static boolean matchesProfileKey(GameProfile profile, @Nullable Object key) {
        UUID id = switch (key) {
            case GameProfile other -> other.id();
            case NameAndId other -> other.id();
            case null, default -> null;
        };
        return profile.id() != null && profile.id().equals(id);
    }

    public static void initialize() {
        if (recallTicket == null) {
            recallTicket = Registry.register(BuiltInRegistries.TICKET_TYPE,
                    Identifier.fromNamespaceAndPath("pet_recall", "recall"),
                    new TicketType(240L, TicketType.FLAG_LOADING));
        }
    }

    public static void holdRecallChunk(ServerLevel level, ChunkPos pos) {
        if (recallTicket == null) {
            throw new IllegalStateException("Recall chunk ticket was not initialized");
        }
        level.getChunkSource().addTicketWithRadius(recallTicket, pos, 0);
    }

    public static void releaseRecallChunk(ServerLevel level, ChunkPos pos) {
        if (recallTicket != null) {
            level.getChunkSource().removeTicketWithRadius(recallTicket, pos, 0);
        }
    }

    public static boolean areChunkEntitiesLoaded(ServerLevel level, ChunkPos pos) {
        return level.areEntitiesLoaded(pos.pack());
    }
}
