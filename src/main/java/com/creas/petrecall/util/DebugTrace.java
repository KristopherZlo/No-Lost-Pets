package com.creas.petrecall.util;

import com.creas.petrecall.index.PetRecord;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.jetbrains.annotations.Nullable;

public final class DebugTrace {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final Object LOCK = new Object();
    private static final boolean ENABLED = detectEnabled();
    private static volatile boolean sessionStarted;
    private static volatile @Nullable Path logPath;

    private DebugTrace() {
    }

    public static void startSession(String modId) {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            if (sessionStarted) {
                return;
            }

            Path gameDir;
            String version = "unknown";
            try {
                gameDir = FabricLoader.getInstance().getGameDir();
                version = FabricLoader.getInstance()
                        .getModContainer(modId)
                        .map(container -> container.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown");
            } catch (Throwable ignored) {
                gameDir = Path.of(".").toAbsolutePath().normalize();
            }

            logPath = gameDir.resolve("logs").resolve("NoLostPets-debug.log");
            sessionStarted = true;
            writeLine("lifecycle", "============================================================");
            writeLine("lifecycle", "NoLostPets debug session started");
            writeLine("lifecycle", "gameDir=" + gameDir);
            writeLine("lifecycle", "modId=" + modId);
            writeLine("lifecycle", "modVersion=" + version);
            writeLine("lifecycle", "javaVersion=" + System.getProperty("java.version"));
        }
    }

    public static void stopSession(String reason) {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            if (!sessionStarted) {
                return;
            }
            writeLine("lifecycle", "NoLostPets debug session finished: " + reason);
            writeLine("lifecycle", "============================================================");
            sessionStarted = false;
        }
    }

    public static void log(String category, String message) {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            if (!sessionStarted) {
                startSession("pet_recall");
            }
            writeLine(category, message);
        }
    }

    public static void log(String category, String format, Object... args) {
        if (!ENABLED) {
            return;
        }
        String message;
        try {
            message = String.format(Locale.ROOT, format, args);
        } catch (RuntimeException e) {
            message = format + " [format-error: " + e.getMessage() + "]";
        }
        log(category, message);
    }

    public static String describePlayer(@Nullable ServerPlayerEntity player) {
        if (!ENABLED) {
            return "";
        }
        if (player == null) {
            return "player=null";
        }
        String dimensionId = VersionCompat.getDimensionId(player);
        return "player=" + player.getName().getString()
                + " uuid=" + player.getUuid()
                + " dim=" + dimensionId
                + " pos=" + formatPos(player.getX(), player.getY(), player.getZ())
                + " onGround=" + player.isOnGround();
    }

    public static String describeWorld(@Nullable ServerWorld world) {
        if (!ENABLED) {
            return "";
        }
        if (world == null) {
            return "world=null";
        }
        return "world=" + world.getRegistryKey().getValue();
    }

    public static String describeEntity(@Nullable Entity entity) {
        if (!ENABLED) {
            return "";
        }
        if (entity == null) {
            return "entity=null";
        }
        String dimensionId = VersionCompat.getDimensionId(entity);
        return "entityType=" + entity.getType().toString()
                + " uuid=" + entity.getUuid()
                + " dim=" + dimensionId
                + " pos=" + formatPos(entity.getX(), entity.getY(), entity.getZ())
                + " chunk=" + entity.getChunkPos();
    }

    public static String describeRecord(@Nullable PetRecord record) {
        if (!ENABLED) {
            return "";
        }
        if (record == null) {
            return "record=null";
        }
        return "pet=" + record.petUuid()
                + " owner=" + record.ownerUuid()
                + " type=" + record.entityTypeId()
                + " dim=" + record.dimensionId()
                + " chunkLong=" + record.chunkPosLong()
                + " pos=" + formatPos(record.x(), record.y(), record.z())
                + " sitting=" + record.sitting()
                + " health=" + record.health();
    }

    public static String describeChunk(ChunkPos chunkPos) {
        if (!ENABLED) {
            return "";
        }
        return chunkPos + " long=" + chunkPos.toLong();
    }

    public static String describePetUuid(UUID petUuid) {
        if (!ENABLED) {
            return "";
        }
        return "pet=" + petUuid;
    }

    private static String formatPos(double x, double y, double z) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", x, y, z);
    }

    private static void writeLine(String category, String message) {
        Path path = logPath;
        if (path == null) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            )) {
                writer.write(TIMESTAMP.format(LocalDateTime.now()));
                writer.write(" [");
                writer.write(category);
                writer.write("] [");
                writer.write(Thread.currentThread().getName());
                writer.write("] ");
                writer.write(message);
                writer.write(System.lineSeparator());
            }
        } catch (IOException e) {
            System.err.println("NoLostPets debug trace write failed: " + e.getMessage());
        }
    }

    private static boolean detectEnabled() {
        String property = System.getProperty("nolostpets.debug");
        if (property != null) {
            return Boolean.parseBoolean(property);
        }

        try {
            return FabricLoader.getInstance().isDevelopmentEnvironment();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
