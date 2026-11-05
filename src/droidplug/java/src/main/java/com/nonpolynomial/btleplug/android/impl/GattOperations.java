package com.nonpolynomial.btleplug.android.impl;

import java.util.ArrayDeque;
import java.util.Iterator;

import io.github.gedgygedgy.rust.future.SimpleFuture;

// Android's callbacks carry no request id, so a client has at most one pending operation; the
// rest wait behind it. Android also accepts a request and may never deliver its callback, so
// every pending operation ends at a deadline enforced here. Every method except the deadline
// task must be called with the lock held.
final class GattOperations<R> {
    // A connect attempt's deadline sits above the stack's own direct-connect timeout, so a slow
    // but answered attempt still reports its real status.
    static final long OPERATION_DEADLINE_MS = 5_000;
    static final long CONNECT_DEADLINE_MS = 40_000;

    enum Operation {
        CONNECT, DISCONNECT, REQUEST_MTU, READ, WRITE, DISCOVER_SERVICES, READ_DESCRIPTOR, WRITE_DESCRIPTOR, READ_RSSI
    }

    interface Scheduler {
        /** Runs {@code task} after {@code delayMs}; returns a canceller. */
        Runnable schedule(Runnable task, long delayMs);
    }

    interface Host {
        /**
         * Called with the lock held when a pending operation's deadline passes. When
         * {@code resetLink} is set the host must close the client and call {@link #loseLink};
         * otherwise the operation fails with an {@link OperationTimeoutException} afterwards.
         * Returns work to run once the lock is released, or null.
         */
        Runnable onDeadline(Operation operation, long deadlineMs, boolean resetLink);
    }

    private static final class Waiting {
        final Operation operation;
        final SimpleFuture<?> future;
        final Runnable start;

        Waiting(Operation operation, SimpleFuture<?> future, Runnable start) {
            this.operation = operation;
            this.future = future;
            this.start = start;
        }
    }

    private final Object lock;
    private final Scheduler scheduler;
    private final Host host;
    private final ArrayDeque<Waiting> waiting = new ArrayDeque<>();
    private Operation pendingOperation;
    private SimpleFuture<?> pendingFuture;
    private R request;
    private Runnable cancelDeadline;
    private boolean draining = false;

    GattOperations(Object lock, Scheduler scheduler, Host host) {
        this.lock = lock;
        this.scheduler = scheduler;
        this.host = host;
    }

    void enqueue(Operation operation, SimpleFuture<?> future, Runnable start) {
        this.waiting.add(new Waiting(operation, future, start));
        this.drain();
    }

    // Starts waiting operations until one is left pending. An operation that completes while it
    // starts (a failed request, an already-connected connect) calls back into here; the flag
    // turns that into another turn of this loop instead of recursion.
    void drain() {
        if (this.draining) {
            return;
        }
        this.draining = true;
        try {
            while (this.pendingOperation == null && !this.waiting.isEmpty()) {
                Waiting next = this.waiting.remove();
                this.pendingOperation = next.operation;
                this.pendingFuture = next.future;
                next.start.run();
            }
        } finally {
            this.draining = false;
        }
    }

    void withoutDraining(Runnable step) {
        this.draining = true;
        try {
            step.run();
        } finally {
            this.draining = false;
        }
    }

    R request() {
        return this.request;
    }

    void issue(R request) {
        assert this.request == null;
        this.request = request;
        this.cancelDeadline();
        long deadlineMs = this.pendingOperation == Operation.CONNECT ? CONNECT_DEADLINE_MS : OPERATION_DEADLINE_MS;
        this.cancelDeadline = this.scheduler.schedule(() -> this.onDeadline(request, deadlineMs), deadlineMs);
    }

    // Drops the issued request but keeps its operation pending, for a connect between attempts.
    void withdraw() {
        this.request = null;
    }

    <T> void complete(SimpleFuture<T> future, T result) {
        future.wake(result);
        this.finish();
    }

    void fail(SimpleFuture<?> future, Throwable error) {
        future.wakeWithThrowable(error);
        this.finish();
    }

    private void finish() {
        this.request = null;
        this.pendingOperation = null;
        this.pendingFuture = null;
        this.cancelDeadline();
        this.drain();
    }

    private void cancelDeadline() {
        if (this.cancelDeadline != null) {
            this.cancelDeadline.run();
            this.cancelDeadline = null;
        }
    }

    private void onDeadline(R request, long deadlineMs) {
        Runnable after;
        synchronized (this.lock) {
            if (this.request != request) {
                return;
            }
            Operation operation = this.pendingOperation;
            Waiting next = this.waiting.peek();
            // A late callback is matched by type alone, so it could complete the next operation
            // of the same type. Closing the client is the only way to rule that out; connect and
            // disconnect leave the link in an unknown state, so they close it too.
            boolean resetLink = operation == Operation.CONNECT || operation == Operation.DISCONNECT
                    || (next != null && next.operation == operation);
            after = this.host.onDeadline(operation, deadlineMs, resetLink);
            if (!resetLink) {
                this.fail(this.pendingFuture, new OperationTimeoutException(deadlineMs));
            }
        }
        if (after != null) {
            after.run();
        }
    }

    // Ends everything tied to the current link: fails the pending operation and every waiting
    // one, and leaves only connects to run. A disconnect is complete once the link is gone, so
    // it succeeds instead of failing.
    void loseLink(Throwable pendingError) {
        Operation operation = this.pendingOperation;
        SimpleFuture<?> future = this.pendingFuture;
        this.request = null;
        this.pendingOperation = null;
        this.pendingFuture = null;
        this.cancelDeadline();
        if (future != null) {
            end(operation, future, pendingError);
        }
        Iterator<Waiting> iterator = this.waiting.iterator();
        while (iterator.hasNext()) {
            Waiting next = iterator.next();
            if (next.operation != Operation.CONNECT) {
                iterator.remove();
                end(next.operation, next.future, new NotConnectedException());
            }
        }
        this.drain();
    }

    private static void end(Operation operation, SimpleFuture<?> future, Throwable error) {
        if (operation == Operation.DISCONNECT) {
            future.wake(null);
        } else {
            future.wakeWithThrowable(error);
        }
    }
}
