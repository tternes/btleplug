package com.nonpolynomial.btleplug.android.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nonpolynomial.btleplug.android.impl.GattOperations.Operation;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.gedgygedgy.rust.future.SimpleFuture;

public class GattOperationsTest {

    private static final class Outcome<T> extends SimpleFuture<T> {
        boolean done;
        T result;
        Throwable error;

        @Override
        public void wake(T result) {
            assertFalse("completed twice", this.done);
            this.done = true;
            this.result = result;
            super.wake(result);
        }

        @Override
        public void wakeWithThrowable(Throwable error) {
            assertFalse("completed twice", this.done);
            this.done = true;
            this.error = error;
            super.wakeWithThrowable(error);
        }
    }

    private static final class Timer {
        final Runnable task;
        final long delayMs;
        boolean cancelled;

        Timer(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }
    }

    private final List<Timer> timers = new ArrayList<>();
    private final List<Operation> deadlines = new ArrayList<>();
    private boolean resetOnDeadline;
    private final List<String> started = new ArrayList<>();

    private final GattOperations<Object> operations = new GattOperations<>(this, (task, delayMs) -> {
        Timer timer = new Timer(task, delayMs);
        this.timers.add(timer);
        return () -> timer.cancelled = true;
    }, (operation, deadlineMs, resetLink) -> {
        this.deadlines.add(operation);
        this.resetOnDeadline = resetLink;
        if (resetLink) {
            this.operations.loseLink(new OperationTimeoutException(deadlineMs));
        }
        return null;
    });

    private <T> Outcome<T> enqueueIssuing(Operation operation, String name) {
        Outcome<T> future = new Outcome<>();
        this.operations.enqueue(operation, future, () -> {
            this.started.add(name);
            this.operations.issue(new Object());
        });
        return future;
    }

    private Timer liveTimer() {
        Timer live = null;
        for (Timer timer : this.timers) {
            if (!timer.cancelled) {
                assertNull("more than one live deadline", live);
                live = timer;
            }
        }
        return live;
    }

    private void expire() {
        Timer timer = this.liveTimer();
        timer.cancelled = true;
        timer.task.run();
    }

    @Test
    public void onlyOneOperationIsPendingAtATime() {
        Outcome<byte[]> read = this.enqueueIssuing(Operation.READ, "read");
        Outcome<Void> write = this.enqueueIssuing(Operation.WRITE, "write");

        assertEquals(Arrays.asList("read"), this.started);
        assertFalse(read.done);
        assertFalse(write.done);
    }

    @Test
    public void waitingOperationsStartInOrderAsEachCompletes() {
        Outcome<byte[]> read = this.enqueueIssuing(Operation.READ, "read");
        Outcome<Void> write = this.enqueueIssuing(Operation.WRITE, "write");
        Outcome<Integer> rssi = this.enqueueIssuing(Operation.READ_RSSI, "rssi");

        byte[] value = new byte[] {1};
        this.operations.complete(read, value);
        assertSame(value, read.result);
        assertEquals(Arrays.asList("read", "write"), this.started);

        this.operations.complete(write, null);
        assertEquals(Arrays.asList("read", "write", "rssi"), this.started);

        this.operations.complete(rssi, -60);
        assertEquals(Integer.valueOf(-60), rssi.result);
    }

    @Test
    public void operationsThatCompleteWhileStartingDrainWithoutRecursion() {
        List<Outcome<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            Outcome<Void> future = new Outcome<>();
            futures.add(future);
            this.operations.enqueue(Operation.WRITE, future,
                    () -> this.operations.fail(future, new NotConnectedException()));
        }

        for (Outcome<Void> future : futures) {
            assertTrue(future.error instanceof NotConnectedException);
        }
    }

    @Test
    public void completingAnOperationCancelsItsDeadline() {
        Outcome<byte[]> read = this.enqueueIssuing(Operation.READ, "read");
        this.operations.complete(read, null);

        assertNull(this.liveTimer());
    }

    @Test
    public void connectGetsTheLongerDeadline() {
        this.enqueueIssuing(Operation.CONNECT, "connect");
        assertEquals(GattOperations.CONNECT_DEADLINE_MS, this.liveTimer().delayMs);
    }

    @Test
    public void otherOperationsGetTheOperationDeadline() {
        this.enqueueIssuing(Operation.READ_RSSI, "rssi");
        assertEquals(GattOperations.OPERATION_DEADLINE_MS, this.liveTimer().delayMs);
    }

    @Test
    public void expiredOperationFailsWithTimeoutAndTheNextStarts() {
        Outcome<Integer> rssi = this.enqueueIssuing(Operation.READ_RSSI, "rssi");
        Outcome<byte[]> read = this.enqueueIssuing(Operation.READ, "read");

        this.expire();

        assertEquals(Arrays.asList(Operation.READ_RSSI), this.deadlines);
        assertFalse(this.resetOnDeadline);
        assertTrue(rssi.error instanceof OperationTimeoutException);
        assertEquals(GattOperations.OPERATION_DEADLINE_MS, ((OperationTimeoutException) rssi.error).getTimeoutMs());
        assertEquals(Arrays.asList("rssi", "read"), this.started);
        assertFalse(read.done);
    }

    @Test
    public void expiryResetsTheLinkWhenTheNextOperationHasTheSameType() {
        Outcome<Integer> first = this.enqueueIssuing(Operation.READ_RSSI, "first");
        Outcome<Integer> second = this.enqueueIssuing(Operation.READ_RSSI, "second");

        this.expire();

        assertTrue(this.resetOnDeadline);
        assertTrue(first.error instanceof OperationTimeoutException);
        assertTrue(second.error instanceof NotConnectedException);
        assertEquals(Arrays.asList("first"), this.started);
    }

    @Test
    public void expiredConnectResetsTheLink() {
        Outcome<Void> connect = this.enqueueIssuing(Operation.CONNECT, "connect");

        this.expire();

        assertTrue(this.resetOnDeadline);
        assertTrue(connect.error instanceof OperationTimeoutException);
    }

    @Test
    public void expiredDisconnectResetsTheLinkAndSucceeds() {
        Outcome<Void> disconnect = this.enqueueIssuing(Operation.DISCONNECT, "disconnect");

        this.expire();

        assertTrue(this.resetOnDeadline);
        assertTrue(disconnect.done);
        assertNull(disconnect.error);
    }

    @Test
    public void deadlineOfACompletedOperationDoesNothing() {
        Outcome<byte[]> read = this.enqueueIssuing(Operation.READ, "read");
        Timer stale = this.liveTimer();
        this.operations.complete(read, null);
        Outcome<Void> write = this.enqueueIssuing(Operation.WRITE, "write");

        stale.task.run();

        assertTrue(this.deadlines.isEmpty());
        assertFalse(write.done);
    }

    @Test
    public void deadlineOfAWithdrawnRequestDoesNothing() {
        Outcome<Void> connect = this.enqueueIssuing(Operation.CONNECT, "connect");
        Timer firstAttempt = this.liveTimer();
        this.operations.withdraw();

        firstAttempt.task.run();

        assertTrue(this.deadlines.isEmpty());
        assertFalse(connect.done);
    }

    @Test
    public void reissuingReplacesTheDeadline() {
        Outcome<Void> connect = this.enqueueIssuing(Operation.CONNECT, "connect");
        Timer firstAttempt = this.liveTimer();
        this.operations.withdraw();
        this.operations.issue(new Object());

        assertTrue(firstAttempt.cancelled);
        assertEquals(GattOperations.CONNECT_DEADLINE_MS, this.liveTimer().delayMs);
        assertFalse(connect.done);
    }

    @Test
    public void linkLossFailsPendingAndWaitingButKeepsConnects() {
        Outcome<byte[]> read = this.enqueueIssuing(Operation.READ, "read");
        Outcome<Void> write = this.enqueueIssuing(Operation.WRITE, "write");
        Outcome<Void> connect = this.enqueueIssuing(Operation.CONNECT, "connect");
        Outcome<Integer> rssi = this.enqueueIssuing(Operation.READ_RSSI, "rssi");

        Throwable lost = new NotConnectedException();
        this.operations.loseLink(lost);

        assertSame(lost, read.error);
        assertTrue(write.error instanceof NotConnectedException);
        assertTrue(rssi.error instanceof NotConnectedException);
        assertFalse(connect.done);
        assertEquals(Arrays.asList("read", "connect"), this.started);
        assertEquals(GattOperations.CONNECT_DEADLINE_MS, this.liveTimer().delayMs);
    }

    @Test
    public void linkLossCompletesPendingAndWaitingDisconnects() {
        Outcome<Void> pending = this.enqueueIssuing(Operation.DISCONNECT, "pending");
        Outcome<Void> waiting = this.enqueueIssuing(Operation.DISCONNECT, "waiting");

        this.operations.loseLink(new NotConnectedException());

        assertTrue(pending.done);
        assertNull(pending.error);
        assertTrue(waiting.done);
        assertNull(waiting.error);
    }

    @Test
    public void linkLossClearsTheRequest() {
        this.enqueueIssuing(Operation.READ, "read");
        assertTrue(this.operations.request() != null);

        this.operations.loseLink(new NotConnectedException());

        assertNull(this.operations.request());
        assertNull(this.liveTimer());
    }

    @Test
    public void nothingStartsWhileDrainingIsHeld() {
        Outcome<byte[]> read = this.enqueueIssuing(Operation.READ, "read");
        Outcome<Void> write = this.enqueueIssuing(Operation.WRITE, "write");

        this.operations.withoutDraining(() -> this.operations.complete(read, null));
        assertEquals(Arrays.asList("read"), this.started);

        this.operations.drain();
        assertEquals(Arrays.asList("read", "write"), this.started);
        assertFalse(write.done);
    }
}
