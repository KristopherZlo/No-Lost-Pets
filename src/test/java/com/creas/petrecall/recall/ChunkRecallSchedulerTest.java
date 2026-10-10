package com.creas.petrecall.recall;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.creas.petrecall.recall.ChunkRecallScheduler.Completion;
import com.creas.petrecall.recall.ChunkRecallScheduler.Result;

class ChunkRecallSchedulerTest {
    private final List<RuntimeException> callbackErrors = new ArrayList<>();

    private ChunkRecallScheduler<String> queue(int capacity, int timeout) {
        return new ChunkRecallScheduler<>(capacity, timeout, callbackErrors::add);
    }

    @Test
    void sameChunkIsSerializedWhileDifferentChunksLoadConcurrently() {
        var queue = queue(2, 10);
        Probe first = new Probe(), sameChunk = new Probe(), otherChunk = new Probe();
        queue.enqueue("a", first);
        queue.enqueue("a", sameChunk);
        queue.enqueue("b", otherChunk);
        queue.tick();
        assertEquals(1, first.acquired);
        assertEquals(0, sameChunk.acquired);
        assertEquals(1, otherChunk.acquired);
        assertEquals(2, queue.getActiveKeyCount());
        first.ready = true;
        queue.tick();
        assertEquals(List.of(Result.READY), first.results());
        assertEquals(1, first.released);
        assertEquals(1, sameChunk.acquired);
        assertTrue(otherChunk.completions.isEmpty());
    }

    @Test
    void capacityIsBoundedAndBusyChunkCannotStarveAnotherChunk() {
        var queue = queue(1, 10);
        Probe first = new Probe(), second = new Probe(), other = new Probe();
        queue.enqueue("a", first);
        queue.enqueue("a", second);
        queue.enqueue("b", other);
        queue.tick();
        assertEquals(0, second.acquired);
        assertEquals(0, other.acquired);
        first.ready = true;
        queue.tick();
        assertEquals(1, other.acquired);
        assertEquals(0, second.acquired);
        other.ready = true;
        queue.tick();
        assertEquals(1, second.acquired);
        assertEquals(1, queue.getActiveKeyCount());
    }

    @Test
    void timeoutReleasesTicketExactlyOnceAndUnblocksNextRequest() {
        var queue = queue(1, 3);
        Probe hung = new Probe(), next = new Probe();
        queue.enqueue("a", hung);
        queue.enqueue("a", next);
        queue.tick();
        queue.tick();
        queue.tick();
        assertTrue(hung.completions.isEmpty(), "Cannot time out before the deadline");
        queue.tick();
        assertEquals(List.of(Result.TIMED_OUT), hung.results());
        assertEquals(1, hung.released);
        assertEquals(1, next.acquired);
        hung.ready = true; // A late disk completion must not resurrect a finished operation.
        next.ready = true;
        queue.tick();
        queue.tick();
        assertEquals(1, hung.completions.size());
        assertEquals(1, hung.released);
        assertEquals(List.of(Result.READY), next.results());
        assertEquals(0, queue.getActiveKeyCount());
    }

    @Test
    void timeoutStartsOnAcquisitionRatherThanTimeSpentWaiting() {
        var queue = queue(1, 2);
        Probe first = new Probe(), second = new Probe();
        queue.enqueue("a", first);
        queue.enqueue("b", second);
        queue.tick();
        queue.tick();
        queue.tick();
        assertEquals(1, second.acquired);
        assertTrue(second.completions.isEmpty());
        queue.tick();
        assertTrue(second.completions.isEmpty());
        second.ready = true; // Readiness on the deadline wins over a timeout.
        queue.tick();
        assertEquals(List.of(Result.READY), second.results());
    }

    @Test
    void cancellationBeforeAcquisitionNeverInstallsOrReleasesTicket() {
        var queue = queue(1, 5);
        Probe cancelled = new Probe();
        cancelled.allowed = false;
        queue.enqueue("a", cancelled);
        queue.tick();
        assertEquals(List.of(Result.CANCELLED), cancelled.results());
        assertEquals(0, cancelled.acquired);
        assertEquals(0, cancelled.released);
    }

    @Test
    void cancellationWinsEvenIfDiskReadIsAlreadyReady() {
        var queue = queue(1, 5);
        Probe cancelled = new Probe();
        queue.enqueue("a", cancelled);
        queue.tick();
        cancelled.allowed = false;
        cancelled.ready = true;
        queue.tick();
        assertEquals(List.of(Result.CANCELLED), cancelled.results());
        assertEquals(1, cancelled.released);
    }

    @Test
    void partiallyFailedAcquisitionStillReleasesTicketAndPreservesError() {
        var queue = queue(1, 5);
        Probe broken = new Probe(), healthy = new Probe();
        broken.acquireError = new IllegalStateException("Installed ticket, then failed");
        queue.enqueue("a", broken);
        queue.enqueue("b", healthy);
        queue.tick();
        assertEquals(1, broken.released);
        assertEquals(List.of(Result.FAILED), broken.results());
        assertSame(broken.acquireError, broken.completions.getFirst().error());
        assertEquals(1, healthy.acquired);
    }

    @Test
    void readFailureReleasesTicketWithoutReportingMissingPet() {
        var queue = queue(1, 5);
        Probe broken = new Probe();
        broken.readError = new IllegalStateException("Corrupt chunk");
        queue.enqueue("a", broken);
        queue.tick();
        queue.tick();
        assertEquals(List.of(Result.FAILED), broken.results());
        assertSame(broken.readError, broken.completions.getFirst().error());
        assertEquals(1, broken.released);
        assertEquals(0, queue.getActiveKeyCount());
    }

    @Test
    void releaseFailureCannotReportRecallReadyAndRetainsOriginalCause() {
        var queue = queue(1, 5);
        Probe broken = new Probe();
        broken.readError = new IllegalStateException("Read failed");
        broken.releaseError = new IllegalStateException("Release failed");
        queue.enqueue("a", broken);
        queue.tick();
        queue.tick();
        assertEquals(List.of(Result.FAILED), broken.results());
        assertSame(broken.releaseError, broken.completions.getFirst().error());
        assertArrayEquals(new Throwable[]{broken.readError}, broken.releaseError.getSuppressed());
        assertEquals(0, queue.getActiveKeyCount());
    }

    @Test
    void throwingCompletionDoesNotLeaveLockOrBlockOtherPlayers() {
        var queue = queue(1, 5);
        Probe broken = new Probe(), next = new Probe();
        broken.ready = true;
        broken.callbackError = new IllegalStateException("Command disconnected");
        queue.enqueue("a", broken);
        queue.enqueue("a", next);
        queue.tick();
        queue.tick();
        assertEquals(List.of(broken.callbackError), callbackErrors);
        assertEquals(1, broken.released);
        assertEquals(1, next.acquired);
        assertEquals(1, broken.completions.size());
    }

    @Test
    void serverStopCancelsActiveAndQueuedRequestsAndNextSessionCanStart() {
        var queue = queue(1, 5);
        Probe active = new Probe(), queued = new Probe(), nextSession = new Probe();
        queue.enqueue("a", active);
        queue.enqueue("a", queued);
        queue.tick();
        queue.clear();
        queue.clear();
        queue.tick();
        assertEquals(List.of(Result.CANCELLED), active.results());
        assertEquals(List.of(Result.CANCELLED), queued.results());
        assertEquals(1, active.released);
        assertEquals(0, queued.acquired);
        assertEquals(0, queued.released);
        assertEquals(0, queue.getActiveKeyCount());
        assertEquals(0, queue.getQueuedTaskCount());
        queue.enqueue("a", nextSession);
        queue.tick();
        assertEquals(1, nextSession.acquired);
    }

    @Test
    void completionCanEnqueueNextRequestWithoutLosingIt() {
        var queue = queue(1, 5);
        Probe first = new Probe(), next = new Probe();
        first.ready = true;
        first.afterComplete = () -> queue.enqueue("a", next);
        queue.enqueue("a", first);
        queue.tick();
        queue.tick();
        assertEquals(1, first.completions.size());
        assertEquals(1, next.acquired);
        next.ready = true;
        queue.tick();
        assertEquals(List.of(Result.READY), next.results());
    }

    @Test
    void stopInsideCallbackCannotCompleteSnapshotRequestsTwice() {
        var queue = queue(2, 5);
        Probe first = new Probe(), second = new Probe();
        first.ready = true;
        first.afterComplete = queue::clear;
        queue.enqueue("a", first);
        queue.enqueue("b", second);
        queue.tick();
        second.ready = true;
        queue.tick();
        assertEquals(List.of(Result.READY), first.results());
        assertEquals(List.of(Result.CANCELLED), second.results());
        assertEquals(1, second.released);
        assertEquals(0, queue.getActiveKeyCount());
    }

    @Test
    void rejectsUnboundedOrNonExpiringQueueConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> queue(0, 1));
        assertThrows(IllegalArgumentException.class, () -> queue(1, 0));
    }

    @Test
    void productionTimeoutBoundaryAndLateReadinessCompleteOnlyOnce() {
        var queue = queue(4, 200);
        Probe hung = new Probe(), onDeadline = new Probe(), waiting = new Probe();
        queue.enqueue("a", hung);
        queue.enqueue("b", onDeadline);
        queue.enqueue("a", waiting);
        queue.tick();
        for (int tick = 1; tick < 200; tick++) queue.tick();
        assertTrue(hung.completions.isEmpty(), "The real 200-tick budget cannot expire early");
        assertTrue(onDeadline.completions.isEmpty());
        assertEquals(0, waiting.acquired, "Shared chunks must stay serialized throughout the wait");
        onDeadline.ready = true;
        queue.tick();
        assertEquals(List.of(Result.TIMED_OUT), hung.results());
        assertEquals(List.of(Result.READY), onDeadline.results(), "Readiness at the deadline must win");
        assertEquals(1, waiting.acquired);
        hung.ready = waiting.ready = true;
        for (int tick = 0; tick < 200; tick++) queue.tick();
        for (Probe probe : List.of(hung, onDeadline, waiting)) {
            assertEquals(1, probe.acquired);
            assertEquals(1, probe.released);
            assertEquals(1, probe.completions.size(), "Late readiness cannot repeat a callback");
        }
        assertEquals(List.of(Result.READY), waiting.results());
        assertEquals(0, queue.getQueuedTaskCount());
        assertEquals(0, queue.getActiveKeyCount());
        assertTrue(callbackErrors.isEmpty());
    }

    @Test
    void boundarySizedAndLargeQueuesDrainWithoutLeaksOrStarvingSharedChunks() {
        for (int size : new int[] {15, 16, 17, 32, 33, 40, 256}) {
            var queue = queue(4, 200);
            var probes = new ArrayList<Probe>();
            for (int i = 0; i < size; i++) {
                Probe probe = new Probe();
                probes.add(probe);
                queue.enqueue("chunk-" + i % 5, probe);
            }
            int tick = 0;
            do {
                for (int i = 0; i < probes.size(); i++) {
                    Probe probe = probes.get(i);
                    if (probe.acquired == 1 && tick % (2 + i % 4) == 0) probe.ready = true;
                }
                queue.tick();
                assertTrue(queue.getActiveKeyCount() <= 4, "Global capacity exceeded for size " + size);
                for (int chunk = 0; chunk < 5; chunk++) {
                    int active = 0;
                    for (int i = chunk; i < probes.size(); i += 5) {
                        Probe probe = probes.get(i);
                        if (probe.acquired > probe.released) active++;
                        assertTrue(probe.acquired <= 1 && probe.released <= 1);
                    }
                    assertTrue(active <= 1, "Two operations held the same chunk for size " + size);
                }
                assertTrue(++tick <= size * 6, "A queued chunk was starved for size " + size);
            } while (queue.getQueuedTaskCount() > 0 || queue.getActiveKeyCount() > 0);
            for (Probe probe : probes) {
                assertEquals(1, probe.acquired);
                assertEquals(1, probe.released);
                assertEquals(List.of(Result.READY), probe.results(), "Lost or repeated callback for size " + size);
            }
        }
        assertTrue(callbackErrors.isEmpty());
    }

    private static final class Probe implements ChunkRecallScheduler.Operation {
        boolean allowed = true;
        boolean ready;
        int acquired;
        int released;
        RuntimeException acquireError, readError, releaseError, callbackError;
        Runnable afterComplete = () -> {};
        final List<Completion> completions = new ArrayList<>();
        List<Result> results() { return completions.stream().map(Completion::result).toList(); }
        @Override public boolean canContinue() { return allowed; }
        @Override public void acquire() {
            acquired++;
            if (acquireError != null) throw acquireError;
        }
        @Override public boolean isReady() {
            if (readError != null) throw readError;
            return ready;
        }
        @Override public void release() {
            released++;
            if (releaseError != null) throw releaseError;
        }
        @Override public void complete(Completion completion) {
            completions.add(completion);
            afterComplete.run();
            if (callbackError != null) throw callbackError;
        }
    }
}
