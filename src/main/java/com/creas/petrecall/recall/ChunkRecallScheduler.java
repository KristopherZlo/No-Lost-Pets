package com.creas.petrecall.recall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Tick-driven queue, confined to the server thread. Timeouts do not use world time. */
final class ChunkRecallScheduler<K> {
    private final int maxActive;
    private final int timeoutTicks;
    private final Consumer<RuntimeException> onCallbackError;
    private final Map<K, ArrayDeque<Operation>> queued = new LinkedHashMap<>();
    private final Map<K, Active> active = new LinkedHashMap<>();
    private long tick;
    private boolean clearing;

    ChunkRecallScheduler(int maxActive, int timeoutTicks, Consumer<RuntimeException> onCallbackError) {
        if (maxActive < 1 || timeoutTicks < 1) {
            throw new IllegalArgumentException("Capacity and timeout must be positive");
        }
        this.maxActive = maxActive;
        this.timeoutTicks = timeoutTicks;
        this.onCallbackError = onCallbackError;
    }

    void enqueue(K key, Operation operation) {
        if (this.clearing) {
            this.notifyCompletion(operation, new Completion(Result.CANCELLED, null));
            return;
        }
        this.queued.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(operation);
    }

    void tick() {
        this.tick++;
        for (var entry : new ArrayList<>(this.active.entrySet())) {
            Active current = entry.getValue();
            if (this.active.get(entry.getKey()) != current) {
                continue;
            }
            try {
                if (!current.operation.canContinue()) {
                    this.finish(entry.getKey(), current, new Completion(Result.CANCELLED, null));
                } else if (current.operation.isReady()) {
                    this.finish(entry.getKey(), current, new Completion(Result.READY, null));
                } else if (this.tick - current.startedAt >= this.timeoutTicks) {
                    this.finish(entry.getKey(), current, new Completion(Result.TIMED_OUT, null));
                }
            } catch (RuntimeException error) {
                this.finish(entry.getKey(), current, new Completion(Result.FAILED, error));
            }
        }
        // A callback that enqueues more work cannot monopolize this tick.
        for (K key : new ArrayList<>(this.queued.keySet())) {
            if (this.active.size() >= this.maxActive) {
                break;
            }
            if (this.active.containsKey(key)) {
                continue;
            }
            ArrayDeque<Operation> queue = this.queued.get(key);
            if (queue == null) {
                continue;
            }
            Operation operation = queue.removeFirst();
            this.queued.remove(key);
            if (!queue.isEmpty()) {
                this.queued.put(key, queue);
            }
            Active current = new Active(operation, this.tick);
            try {
                if (!operation.canContinue()) {
                    this.notifyCompletion(operation, new Completion(Result.CANCELLED, null));
                    continue;
                }
                this.active.put(key, current);
                operation.acquire();
            } catch (RuntimeException error) {
                if (this.active.get(key) == current) {
                    this.finish(key, current, new Completion(Result.FAILED, error));
                } else {
                    this.notifyCompletion(operation, new Completion(Result.FAILED, error));
                }
            }
        }
    }

    void clear() {
        this.clearing = true;
        var running = new ArrayList<>(this.active.entrySet());
        var waiting = new ArrayList<Operation>();
        this.queued.values().forEach(waiting::addAll);
        this.queued.clear();
        try {
            for (var entry : running) {
                this.finish(entry.getKey(), entry.getValue(), new Completion(Result.CANCELLED, null));
            }
            for (Operation operation : waiting) {
                this.notifyCompletion(operation, new Completion(Result.CANCELLED, null));
            }
        } finally {
            this.clearing = false;
            this.tick = 0;
        }
    }

    private void finish(K key, Active current, Completion completion) {
        if (!this.active.remove(key, current)) {
            return;
        }
        try {
            // Acquisition may have thrown after installing a ticket.
            current.operation.release();
        } catch (RuntimeException error) {
            if (completion.error() != null) {
                error.addSuppressed(completion.error());
            }
            completion = new Completion(Result.FAILED, error);
        }
        this.notifyCompletion(current.operation, completion);
    }

    private void notifyCompletion(Operation operation, Completion completion) {
        try {
            operation.complete(completion);
        } catch (RuntimeException error) {
            this.onCallbackError.accept(error);
        }
    }

    int getActiveKeyCount() {
        return this.active.size();
    }

    int getQueuedTaskCount() {
        return this.queued.values().stream().mapToInt(ArrayDeque::size).sum();
    }

    interface Operation {
        boolean canContinue();
        void acquire();
        boolean isReady();
        void release();
        void complete(Completion completion);
    }

    enum Result { READY, TIMED_OUT, CANCELLED, FAILED }

    record Completion(Result result, RuntimeException error) { }

    private record Active(Operation operation, long startedAt) { }
}
