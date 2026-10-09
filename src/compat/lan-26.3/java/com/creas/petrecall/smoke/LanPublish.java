package com.creas.petrecall.smoke;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer.MultiplayerScope;

final class LanPublish {
    private LanPublish() {}

    static boolean open(IntegratedServer server, int port) {
        return server.publishServer(MultiplayerScope.LAN, true, port);
    }
}
