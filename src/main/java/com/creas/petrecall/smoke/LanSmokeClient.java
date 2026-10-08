package com.creas.petrecall.smoke;

import com.creas.petrecall.PetRecallMod;
import com.creas.petrecall.util.VersionCompat;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class LanSmokeClient implements ClientModInitializer {
    private static final String PROP_PREFIX = "nolostpets.lanSmoke.";

    @Override
    public void onInitializeClient() {
        String role = System.getProperty(PROP_PREFIX + "role", "").trim();
        if (role.isEmpty()) {
            return;
        }
        new SmokeHarness(role).register();
    }

    private static final class SmokeHarness {
        private final String role;
        private final Path smokeDir;
        private final String worldName;
        private final int requestedPort;
        private final String hostName;
        private final String peerName;
        private final Path portFile;
        private final Path hostResultFile;
        private final Path clientResultFile;
        private final RecordingCommandOutput commandOutput = new RecordingCommandOutput();
        private int phase;
        private long phaseStartedAt;
        private boolean commandQueued;
        private boolean resultWritten;

        private SmokeHarness(String role) {
            this.role = role;
            this.smokeDir = Path.of(System.getProperty(PROP_PREFIX + "dir", "build/tmp/smoke-lan")).toAbsolutePath().normalize();
            this.worldName = System.getProperty(PROP_PREFIX + "world", "Testing");
            this.requestedPort = Integer.getInteger(PROP_PREFIX + "port", 0);
            this.hostName = System.getProperty(PROP_PREFIX + "hostName", "NLPHost");
            this.peerName = System.getProperty(PROP_PREFIX + "peerName", "NLPClient");
            this.portFile = this.smokeDir.resolve("port.txt");
            this.hostResultFile = this.smokeDir.resolve("host.result");
            this.clientResultFile = this.smokeDir.resolve("client.result");
        }

        private void register() {
            ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        }

        private void tick(Minecraft client) {
            try {
                if ("host".equals(this.role)) {
                    this.tickHost(client);
                } else if ("client".equals(this.role)) {
                    this.tickClient(client);
                } else {
                    this.fail(this.hostResultFile, "Unknown LAN smoke role: " + this.role, client);
                }
            } catch (RuntimeException e) {
                Path result = "client".equals(this.role) ? this.clientResultFile : this.hostResultFile;
                this.fail(result, e.getClass().getSimpleName() + ": " + e.getMessage(), client);
            }
        }

        private void tickHost(Minecraft client) {
            if (this.resultWritten) {
                return;
            }

            MinecraftServer server = client.getSingleplayerServer();
            if (this.phase == 0) {
                this.startWorld(client);
                this.phase = 1;
                this.phaseStartedAt = System.currentTimeMillis();
                return;
            }

            if (this.phase == 1) {
                if (!(server instanceof IntegratedServer integratedServer) || client.player == null) {
                    return;
                }
                if (!integratedServer.publishServer(GameType.CREATIVE, true, this.requestedPort)) {
                    this.fail(this.hostResultFile, "Integrated server refused to open to LAN", client);
                    return;
                }
                this.write(this.portFile, Integer.toString(integratedServer.getPort()));
                this.phase = 2;
                this.phaseStartedAt = System.currentTimeMillis();
                PetRecallMod.LOGGER.info("NoLostPets LAN smoke host opened port {}", integratedServer.getPort());
                return;
            }

            if (this.phase == 2) {
                ServerPlayer owner = server == null ? null : server.getPlayerList().getPlayerByName(this.hostName);
                ServerPlayer peer = server == null ? null : server.getPlayerList().getPlayerByName(this.peerName);
                if (owner == null || peer == null) {
                    this.failIfPhaseTakesTooLong(this.hostResultFile, "Timed out waiting for two LAN players", client, 120_000L);
                    return;
                }
                this.queueVerifyCommand(server, owner, peer);
                this.phase = 3;
                this.phaseStartedAt = System.currentTimeMillis();
                return;
            }

            if (this.phase == 3) {
                if (this.commandOutput.contains("Self-test failed:")) {
                    this.fail(this.hostResultFile, "Multiplayer self-test failed: " + this.commandOutput.lastMessage(), client);
                    return;
                }
                if (this.commandOutput.contains("Self-test passed:")) {
                    this.pass(this.hostResultFile, "Multiplayer self-test passed", client);
                    return;
                }
                this.failIfPhaseTakesTooLong(this.hostResultFile, "Timed out waiting for multiplayer self-test result", client, 180_000L);
            }
        }

        private void startWorld(Minecraft client) {
            try {
                if (!client.getLevelSource().levelExists(this.worldName)) {
                    this.fail(this.hostResultFile, "World '" + this.worldName + "' does not exist in this run directory", client);
                    return;
                }
                client.createWorldOpenFlows().openWorld(this.worldName, () -> {
                    this.fail(this.hostResultFile, "Safe mode callback was requested while loading world '" + this.worldName + "'", client);
                });
                PetRecallMod.LOGGER.info("NoLostPets LAN smoke host loading world {}", this.worldName);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start LAN smoke world", e);
            }
        }

        private void queueVerifyCommand(MinecraftServer server, ServerPlayer owner, ServerPlayer peer) {
            if (this.commandQueued) {
                return;
            }
            this.commandQueued = true;
            server.execute(() -> {
                try {
                    ServerLevel world = VersionCompat.getServerWorld(owner);
                    if (world == null) {
                        this.commandOutput.sendSystemMessage(Component.literal("Self-test failed: host world is unavailable"));
                        return;
                    }
                    CommandSourceStack source = server.createCommandSourceStack()
                            .withEntity(owner)
                            .withLevel(world)
                            .withPosition(new Vec3(owner.getX(), owner.getY(), owner.getZ()))
                            .withSource(this.commandOutput);
                    String command = "petrecall verify multiplayer " + peer.getName().getString();
                    int result = server.getCommands().getDispatcher().execute(command, source);
                    if (result != 1) {
                        this.commandOutput.sendSystemMessage(Component.literal("Self-test failed: command returned " + result));
                    }
                } catch (CommandSyntaxException e) {
                    this.commandOutput.sendSystemMessage(Component.literal("Self-test failed: " + e.getMessage()));
                } catch (RuntimeException e) {
                    this.commandOutput.sendSystemMessage(Component.literal("Self-test failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                }
            });
        }

        private void tickClient(Minecraft client) {
            if (this.resultWritten) {
                return;
            }

            if (this.phase == 0) {
                Integer port = this.readPort();
                if (port == null) {
                    return;
                }
                this.connect(client, port);
                this.phase = 1;
                this.phaseStartedAt = System.currentTimeMillis();
                return;
            }

            if (this.phase == 1) {
                if (client.player != null && client.level != null && client.getConnection() != null) {
                    this.pass(this.clientResultFile, "Client joined LAN server", null);
                    return;
                }
                this.failIfPhaseTakesTooLong(this.clientResultFile, "Timed out waiting for client to join LAN server", client, 120_000L);
            }
        }

        private void connect(Minecraft client, int port) {
            ServerAddress address = ServerAddress.parseString("127.0.0.1:" + port);
            ServerData info = new ServerData("NoLostPets LAN Smoke", "127.0.0.1:" + port, ServerData.Type.LAN);
            ConnectScreen.startConnecting(client.screen, client, address, info, false, createCookieStorage());
            PetRecallMod.LOGGER.info("NoLostPets LAN smoke client connecting to 127.0.0.1:{}", port);
        }

        private Integer readPort() {
            if (!Files.exists(this.portFile)) {
                return null;
            }
            try {
                String value = Files.readString(this.portFile).trim();
                if (value.isEmpty()) {
                    return null;
                }
                return Integer.parseInt(value);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read LAN smoke port file", e);
            }
        }

        private void failIfPhaseTakesTooLong(Path resultFile, String message, Minecraft client, long timeoutMillis) {
            if (System.currentTimeMillis() - this.phaseStartedAt > timeoutMillis) {
                this.fail(resultFile, message, client);
            }
        }

        private void pass(Path resultFile, String message, Minecraft client) {
            this.write(resultFile, "PASS " + message);
            this.resultWritten = true;
            if (client != null) {
                client.stop();
            }
        }

        private void fail(Path resultFile, String message, Minecraft client) {
            this.write(resultFile, "FAIL " + message);
            this.resultWritten = true;
            if (client != null) {
                client.stop();
            }
        }

        private void write(Path path, String text) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, text + System.lineSeparator());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to write LAN smoke file " + path, e);
            }
        }
    }

    private static TransferState createCookieStorage() {
        try {
            for (Constructor<?> constructor : TransferState.class.getConstructors()) {
                if (constructor.getParameterCount() == 1) {
                    return (TransferState) constructor.newInstance(Map.of());
                }
                if (constructor.getParameterCount() == 3) {
                    return (TransferState) constructor.newInstance(Map.of(), Map.of(), false);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create cookie storage", e);
        }
        throw new IllegalStateException("Unsupported CookieStorage constructor");
    }

    private static final class RecordingCommandOutput implements CommandSource {
        private final List<String> messages = new ArrayList<>();

        @Override
        public synchronized void sendSystemMessage(Component text) {
            this.messages.add(text.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private synchronized boolean contains(String fragment) {
            for (String message : this.messages) {
                if (message.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        private synchronized String lastMessage() {
            return this.messages.isEmpty() ? "<no messages>" : this.messages.get(this.messages.size() - 1);
        }
    }
}
