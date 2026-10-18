package com.nonpolynomial.btleplug.android.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class CommandQueueTest {
    private static final long TIMEOUT_MS = 5_000;

    /** Holds scheduled timeouts until a test fires them. */
    private static final class ManualScheduler implements CommandQueue.Scheduler {
        final List<Runnable> scheduled = new ArrayList<>();
        int cancelled = 0;

        @Override
        public Runnable schedule(Runnable task, long delayMs) {
            this.scheduled.add(task);
            return () -> {
                this.cancelled++;
                this.scheduled.remove(task);
            };
        }

        void fireAll() {
            for (Runnable task : new ArrayList<>(this.scheduled)) {
                task.run();
            }
        }
    }

    private final Object lock = new Object();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<String> log = new ArrayList<>();
    private final CommandQueue queue = new CommandQueue(this.lock, this.scheduler, () -> this.log.add("timeout"));

    @Test
    public void runsOneCommandAtATimeInOrder() {
        this.queue.enqueue(() -> this.log.add("a"), TIMEOUT_MS);
        this.queue.enqueue(() -> this.log.add("b"), TIMEOUT_MS);
        assertEquals(List.of("a"), this.log);

        this.queue.finish();
        assertEquals(List.of("a", "b"), this.log);

        this.queue.finish();
        assertFalse(this.queue.isExecuting());
    }

    @Test
    public void aCommandWithNoCallbackTimesOutAndTheNextRuns() {
        this.queue.enqueue(() -> this.log.add("unanswered"), TIMEOUT_MS);
        this.queue.enqueue(() -> this.log.add("next"), TIMEOUT_MS);

        this.scheduler.fireAll();

        assertEquals(List.of("unanswered", "timeout", "next"), this.log);
        assertTrue(this.queue.isExecuting());
    }

    @Test
    public void finishingCancelsTheTimeout() {
        this.queue.enqueue(() -> this.log.add("a"), TIMEOUT_MS);
        this.queue.finish();

        this.scheduler.fireAll();

        assertEquals(List.of("a"), this.log);
        assertEquals(1, this.scheduler.cancelled);
    }

    /** A timeout that lost the race with finish() must not end the command after it. */
    @Test
    public void aStaleTimeoutDoesNotEndTheNextCommand() {
        List<Runnable> raced = new ArrayList<>();
        CommandQueue.Scheduler keepsRunning = (task, delayMs) -> {
            raced.add(task);
            return () -> { };
        };
        CommandQueue queue = new CommandQueue(this.lock, keepsRunning, () -> this.log.add("timeout"));
        queue.enqueue(() -> this.log.add("a"), TIMEOUT_MS);
        queue.enqueue(() -> this.log.add("b"), TIMEOUT_MS);
        queue.finish();

        raced.get(0).run();

        assertEquals(List.of("a", "b"), this.log);
        assertTrue(queue.isExecuting());
    }

    @Test
    public void aLateFinishAfterATimeoutIsIgnored() {
        this.queue.enqueue(() -> this.log.add("unanswered"), TIMEOUT_MS);
        this.scheduler.fireAll();
        assertFalse(this.queue.isExecuting());

        this.queue.finish();

        assertFalse(this.queue.isExecuting());
        assertEquals(List.of("unanswered", "timeout"), this.log);
    }

    @Test
    public void aZeroTimeoutIsNeverScheduled() {
        this.queue.enqueue(() -> this.log.add("connect"), 0);

        assertTrue(this.scheduler.scheduled.isEmpty());
        assertTrue(this.queue.isExecuting());
    }

    /**
     * On a disconnect every queued command fails as it starts. That must drain in constant stack
     * depth: each failure throwing at a deeper stack is what exhausted the heap on a long queue.
     */
    @Test
    public void commandsThatFinishAsTheyStartDrainWithoutRecursion() {
        int commands = 100_000;
        int[] depths = new int[2];
        depths[0] = Integer.MAX_VALUE;
        this.queue.enqueue(() -> { }, TIMEOUT_MS);
        for (int i = 0; i < commands; i++) {
            this.queue.enqueue(() -> {
                int depth = Thread.currentThread().getStackTrace().length;
                depths[0] = Math.min(depths[0], depth);
                depths[1] = Math.max(depths[1], depth);
                this.queue.finish();
            }, TIMEOUT_MS);
        }

        this.queue.finish();

        assertFalse(this.queue.isExecuting());
        assertEquals(0, this.queue.pendingCount());
        assertEquals("stack depth must not grow with the queue", depths[0], depths[1]);
    }
}
