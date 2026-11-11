package local.sidecar.copilot.uvcadapter;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Project-owned deterministic host-test primitives; deadlines only detect deadlock. */
final class TestSupport {
    private static final long DEADLOCK_SECONDS = 8;
    private static final AtomicInteger ASSERTIONS = new AtomicInteger();

    private TestSupport() {}

    static void check(boolean value, String message) {
        ASSERTIONS.incrementAndGet();
        if (!value) throw new AssertionError(message);
    }

    static int assertions() { return ASSERTIONS.get(); }

    static void await(CountDownLatch latch, String phase) {
        try {
            if (!latch.await(DEADLOCK_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("deadlock guard: " + phase);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted: " + phase, interrupted);
        }
    }

    static void rejects(Class<? extends Throwable> type, Runnable action, String phase) {
        Throwable caught = null;
        try { action.run(); }
        catch (Throwable failure) { caught = failure; }
        check(caught != null && type.isInstance(caught),
              phase + ": expected " + type.getSimpleName() + ", got " + caught);
    }

    static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final String name;

        Gate(String name) { this.name = name; }
        void enter() {
            entered.countDown();
            await(release, name + " release");
        }
        void awaitEntered() { await(entered, name + " entry"); }
        void release() { release.countDown(); }
    }

    static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
        private final AtomicInteger executed = new AtomicInteger();

        @Override public void execute(Runnable command) { queue.add(command); }
        int queued() { return queue.size(); }
        int executed() { return executed.get(); }
        void runAll() {
            Runnable command;
            int iterations = 0;
            while ((command = queue.poll()) != null) {
                if (++iterations > 1000) throw new AssertionError("cleanup scheduling did not quiesce");
                executed.incrementAndGet();
                command.run();
            }
        }
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
}
