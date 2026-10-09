package com.creas.petrecall.gametest;

import java.util.concurrent.locks.LockSupport;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.gametest.framework.GameTestServer;

public final class PetRecallGameTestBootstrap implements ModInitializer {
    private long nextTickNanos;

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (!(server instanceof GameTestServer)) return;
            // Vanilla GameTest skips tick pacing; real chunk I/O still needs its normal 200-tick window.
            long remaining;
            while ((remaining = nextTickNanos - System.nanoTime()) > 0L) {
                LockSupport.parkNanos(remaining);
            }
            nextTickNanos = System.nanoTime() + 50_000_000L;
        });
    }
}
