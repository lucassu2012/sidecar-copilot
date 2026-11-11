package local.sidecar.copilot.usbbridge;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic host-test primitives. The deadline detects deadlock, not product timing. */
final class BridgeTestSupport {
    private static final AtomicInteger ASSERTIONS = new AtomicInteger();
    private BridgeTestSupport() {}
    static void check(boolean condition, String message) {
        ASSERTIONS.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }
    static int assertions() { return ASSERTIONS.get(); }
    static void await(CountDownLatch latch, String phase) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("deadlock guard: " + phase);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted: " + phase, interrupted);
        }
    }
    static void rejects(Class<? extends Throwable> type, Runnable action, String phase) {
        Throwable caught = null;
        try { action.run(); } catch (Throwable error) { caught = error; }
        check(caught != null && type.isInstance(caught), phase + ": expected " + type.getSimpleName() + ", got " + caught);
    }
    static final class Gate {
        private final String phase;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        Gate(String phase) { this.phase = phase; }
        void enter() { entered.countDown(); await(release, phase + " release"); }
        void awaitEntered() { await(entered, phase + " entry"); }
        void release() { release.countDown(); }
    }
    static final class Task<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile T value;
        private volatile Throwable failure;
        private final Thread thread;
        Task(String name, final Callable<T> action) {
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    try { value = action.call(); }
                    catch (Throwable caught) { failure = caught; }
                    finally { done.countDown(); }
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
        }
        T finish() {
            await(done, thread.getName() + " completion");
            if (failure != null) throw new AssertionError(thread.getName() + " failed", failure);
            return value;
        }
    }
    static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
        private final AtomicInteger executed = new AtomicInteger();
        @Override public void execute(Runnable command) { queue.add(command); }
        int executed() { return executed.get(); }
        void runAll() {
            Runnable task;
            int count = 0;
            while ((task = queue.poll()) != null) {
                if (++count > 1000) throw new AssertionError("cleanup scheduling did not quiesce");
                executed.incrementAndGet(); task.run();
            }
        }
    }
}
