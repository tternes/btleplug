package com.nonpolynomial.btleplug.android.impl;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Runs a peripheral's GATT commands one at a time, as Android allows only one outstanding GATT
 * operation per client.
 *
 * A command holds the queue until it calls {@link #finish()} or its timeout expires. Android
 * occasionally never delivers a command's callback while the link stays up; without the timeout
 * every later command for that peripheral would wait behind it until the link drops.
 *
 * Commands that finish while being started (e.g. every queued command failing at once on a
 * disconnect) are run in a loop rather than recursively, so a long queue drains in constant
 * stack depth.
 *
 * Not thread-safe: every call must hold {@code lock}, which the timeout also takes.
 */
final class CommandQueue {
    interface Scheduler {
        /** Runs {@code task} after {@code delayMs}; the returned runnable cancels it. */
        Runnable schedule(Runnable task, long delayMs);
    }

    private static final class Command {
        final Runnable start;
        final long timeoutMs;

        Command(Runnable start, long timeoutMs) {
            this.start = start;
            this.timeoutMs = timeoutMs;
        }
    }

    private final Object lock;
    private final Scheduler scheduler;
    private final Runnable onTimeout;
    private final Queue<Command> pending = new ArrayDeque<>();
    private boolean executing = false;
    private boolean draining = false;
    // Identifies the command in flight, so a timeout scheduled for an earlier command is inert.
    private long running = 0;
    private Runnable cancelTimeout = null;

    /**
     * @param onTimeout called, holding {@code lock}, when the command in flight times out; it
     *                  fails that command. The next command starts once it returns.
     */
    CommandQueue(Object lock, Scheduler scheduler, Runnable onTimeout) {
        this.lock = lock;
        this.scheduler = scheduler;
        this.onTimeout = onTimeout;
    }

    /** @param timeoutMs how long the command may hold the queue; 0 for no limit. */
    void enqueue(Runnable start, long timeoutMs) {
        this.pending.add(new Command(start, timeoutMs));
        this.runPending();
    }

    /** Ends the command in flight. A no-op if it already timed out. */
    void finish() {
        if (!this.executing) {
            return;
        }
        this.endRunning();
        this.runPending();
    }

    boolean isExecuting() {
        return this.executing;
    }

    int pendingCount() {
        return this.pending.size();
    }

    private void endRunning() {
        this.executing = false;
        if (this.cancelTimeout != null) {
            this.cancelTimeout.run();
            this.cancelTimeout = null;
        }
    }

    private void runPending() {
        if (this.draining) {
            return;
        }
        this.draining = true;
        try {
            while (!this.executing && !this.pending.isEmpty()) {
                Command command = this.pending.remove();
                this.executing = true;
                long id = ++this.running;
                if (command.timeoutMs > 0) {
                    this.cancelTimeout = this.scheduler.schedule(() -> this.timeOut(id), command.timeoutMs);
                }
                command.start.run();
            }
        } finally {
            this.draining = false;
        }
    }

    private void timeOut(long id) {
        synchronized (this.lock) {
            if (!this.executing || this.running != id) {
                return;
            }
            this.cancelTimeout = null;
            this.executing = false;
            this.onTimeout.run();
            this.runPending();
        }
    }
}
