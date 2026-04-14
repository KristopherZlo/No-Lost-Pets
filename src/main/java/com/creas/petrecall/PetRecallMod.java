package com.creas.petrecall;

import com.creas.petrecall.command.PetRecallCommand;
import com.creas.petrecall.recall.PetRecallService;
import com.creas.petrecall.runtime.AutoPetRecallController;
import com.creas.petrecall.runtime.PetTracker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

    public static PetTracker getTracker() {
        return TRACKER;
    }

    public static PetRecallService getRecallService() {
        return RECALL_SERVICE;
    }

    @Override
    public void onInitialize() {
        ServerEntityEvents.ENTITY_LOAD.register(TRACKER::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(TRACKER::onEntityUnload);
        ServerTickEvents.END_SERVER_TICK.register(AUTO_RECALL::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            TRACKER.clearRuntime();
            AUTO_RECALL.clearRuntime();
        });
        CommandRegistrationCallback.EVENT.register(PetRecallCommand::register);

        LOGGER.info("Pet Recall initialized");
    }
}
