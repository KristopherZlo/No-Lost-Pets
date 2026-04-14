package com.creas.petrecall.recall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PetRecallQuarantineTrackerTest {
    @Test
    void missesEscalateToRemoval() {
        PetRecallQuarantineTracker tracker = new PetRecallQuarantineTracker();
        UUID petUuid = UUID.randomUUID();

        var first = tracker.recordMiss(petUuid, 100L);
        assertFalse(first.shouldRemoveRecord());
        assertTrue(tracker.isQuarantined(petUuid, 101L));

        var second = tracker.recordMiss(petUuid, first.quarantineUntilTick());
        assertFalse(second.shouldRemoveRecord());
        assertTrue(tracker.isQuarantined(petUuid, second.quarantineUntilTick() - 1L));

        var third = tracker.recordMiss(petUuid, second.quarantineUntilTick());
        assertTrue(third.shouldRemoveRecord());
        assertFalse(tracker.isQuarantined(petUuid, third.quarantineUntilTick()));
    }

    @Test
    void clearRemovesQuarantineState() {
        PetRecallQuarantineTracker tracker = new PetRecallQuarantineTracker();
        UUID petUuid = UUID.randomUUID();

        tracker.recordMiss(petUuid, 0L);
        assertTrue(tracker.isQuarantined(petUuid, 1L));

        tracker.clear(petUuid);
        assertFalse(tracker.isQuarantined(petUuid, 1L));
    }
}
