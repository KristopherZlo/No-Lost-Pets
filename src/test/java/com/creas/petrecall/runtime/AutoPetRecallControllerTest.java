package com.creas.petrecall.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AutoPetRecallControllerTest {
    @Test
    void joinRepairUsesShortDelay() {
        assertEquals(110L, AutoPetRecallController.computeJoinRepairTick(100L));
    }

    @Test
    void joinRecallUsesLongerWarmupDelay() {
        assertEquals(120L, AutoPetRecallController.computeJoinRecallTick(100L));
    }

    @Test
    void joinRepairRunsOnlyWhenOwnerHasNoIndexedPetsEarlyInServerSession() {
        assertTrue(AutoPetRecallController.shouldRunJoinRepair(0L, 0));
        assertTrue(AutoPetRecallController.shouldRunJoinRepair(600L, 0));
        assertFalse(AutoPetRecallController.shouldRunJoinRepair(601L, 0));
        assertFalse(AutoPetRecallController.shouldRunJoinRepair(100L, 1));
    }
}
