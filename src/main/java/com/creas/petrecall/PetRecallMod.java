package com.creas.petrecall;

import com.creas.petrecall.command.PetRecallCommand;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.runtime.AutoPetRecallController;
import com.creas.petrecall.runtime.PetTracker;
import com.creas.petrecall.selftest.PetRecallSelfTestService;
import com.creas.petrecall.util.DebugTrace;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PetRecallMod implements ModInitializer {
    public static final String MOD_ID = "pet_recall";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final PetTracker TRACKER = new PetTracker();
    private static final PetRecallService RECALL_SERVICE = new PetRecallService(TRACKER);
    private static final AutoPetRecallController AUTO_RECALL = new AutoPetRecallController(TRACKER, RECALL_SERVICE);
    private static final PetRecallSelfTestService SELF_TEST = new PetRecallSelfTestService();

    public static PetTracker getTracker() {
        return TRACKER;
    }

    public static PetRecallService getRecallService() {
        return RECALL_SERVICE;
    }

    public static AutoPetRecallController getAutoRecallController() {
        return AUTO_RECALL;
    }

    public static PetRecallSelfTestService getSelfTestService() {
        return SELF_TEST;
    }

    @Override
    public void onInitialize() {
        DebugTrace.startSession(MOD_ID);
        DebugTrace.log("lifecycle", "Registering NoLostPets event hooks");
        ServerEntityEvents.ENTITY_LOAD.register(TRACKER::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(TRACKER::onEntityUnload);
        ServerTickEvents.END_SERVER_TICK.register(AUTO_RECALL::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(SELF_TEST::onServerTick);
        ServerPlayerEvents.JOIN.register(AUTO_RECALL::scheduleAfterJoin);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> AUTO_RECALL.scheduleImmediate(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> AUTO_RECALL.scheduleImmediate(player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            DebugTrace.log("lifecycle", "Server stopped; indexedPets=%d trackedLoaded=%d", TRACKER.getIndexedPetCount(server), TRACKER.getLoadedPetCount());
            TRACKER.clearRuntime();
            AUTO_RECALL.clearRuntime();
            DebugTrace.stopSession("server stopped");
        });
        CommandRegistrationCallback.EVENT.register(PetRecallCommand::register);

        LOGGER.info("NoLostPets initialized");
        DebugTrace.log("lifecycle", "NoLostPets initialized");
    }
}
