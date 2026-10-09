package com.creas.petrecall.smoke;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;

final class LanPublish {
    private LanPublish() {}

    static boolean open(IntegratedServer server, int port) {
        return server.publishServer(GameType.CREATIVE, true, port);
    }
}
