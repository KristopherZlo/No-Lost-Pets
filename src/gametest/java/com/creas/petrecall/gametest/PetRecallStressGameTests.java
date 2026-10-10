package com.creas.petrecall.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;

public final class PetRecallStressGameTests {
    @GameTest(maxTicks = 5000, structure = "pet_recall_gametest:empty_32",
            environment = "pet_recall_gametest:isolated/repeated_recall_of_two_hundred_fifty_six_pets_releases_source_chunks")
    public void repeatedRecallOfTwoHundredFiftySixPetsReleasesSourceChunks(TestContext context) {
        PetRecallRuntimeGameTests.runStress(context);
    }
}
