package com.creas.petrecall.recall;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class ChunkRecallScheduler<K> {
    private final Set<K> activeKeys = new HashSet<>();
    private final Map<K, ArrayDeque<Runnable>> queuedTasks = new HashMap<>();

    @Nullable
    public synchronized Runnable enqueue(K key, Runnable task) {
        if (this.activeKeys.add(key)) {
            return task;
        }

        this.queuedTasks.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(task);
        return null;
    }

    @Nullable
    public synchronized Runnable complete(K key) {
        ArrayDeque<Runnable> queue = this.queuedTasks.get(key);
        if (queue == null || queue.isEmpty()) {
            this.activeKeys.remove(key);
            this.queuedTasks.remove(key);
            return null;
        }

        Runnable next = queue.poll();
        if (queue.isEmpty()) {
            this.queuedTasks.remove(key);
        }
        return next;
    }

    public synchronized int getActiveKeyCount() {
        return this.activeKeys.size();
    }

    public synchronized int getQueuedTaskCount() {
        int total = 0;
        for (ArrayDeque<Runnable> queue : this.queuedTasks.values()) {
            total += queue.size();
        }
        return total;
    }
}
