package com.creas.petrecall.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public final class PetRecallFastRuntimeGameTests {
    @GameTest(maxTicks = 1600, structure = "pet_recall_gametest:empty_32",
            environment = "pet_recall_gametest:isolated/one_production_trigger_recalls_thirty_three_scattered_pets_in_three_batches")
    public void oneProductionTriggerRecallsThirtyThreeScatteredPetsInThreeBatches(GameTestHelper context) {
        PetRecallRuntimeGameTests.runNormal(context);
    }
}
