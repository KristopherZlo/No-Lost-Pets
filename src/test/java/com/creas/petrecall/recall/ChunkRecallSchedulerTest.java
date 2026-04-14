package com.creas.petrecall.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ChunkRecallSchedulerTest {
    @Test
    void serializesTasksForSameKey() {
        ChunkRecallScheduler<String> scheduler = new ChunkRecallScheduler<>();
        Runnable first = () -> {
        };
        Runnable second = () -> {
        };

        assertSame(first, scheduler.enqueue("chunk-a", first));
        assertNull(scheduler.enqueue("chunk-a", second));
        assertEquals(1, scheduler.getActiveKeyCount());
        assertEquals(1, scheduler.getQueuedTaskCount());

        Runnable released = scheduler.complete("chunk-a");
        assertSame(second, released);
        assertEquals(1, scheduler.getActiveKeyCount());
        assertEquals(0, scheduler.getQueuedTaskCount());

        assertNull(scheduler.complete("chunk-a"));
        assertEquals(0, scheduler.getActiveKeyCount());
    }

    @Test
    void differentKeysCanRunIndependently() {
        ChunkRecallScheduler<String> scheduler = new ChunkRecallScheduler<>();
        Runnable first = () -> {
        };
        Runnable second = () -> {
        };

        assertNotNull(scheduler.enqueue("chunk-a", first));
        assertNotNull(scheduler.enqueue("chunk-b", second));
        assertEquals(2, scheduler.getActiveKeyCount());
        assertEquals(0, scheduler.getQueuedTaskCount());
    }
}
