package dev.neosync.boox.jobs;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Runs one Job at a time on its own background thread and hands its calls to
 * the UI through the given executor. Progress reaches the UI at most every
 * throttleMs (an e-ink screen can't redraw faster): in between, only the
 * newest update is kept and delivered when the interval is over, and the
 * last one always arrives before finished.
 */
public final class JobRunner {

    private final Executor ui;
    private final long throttleMs;
    private final LongSupplier clock;
    private final Object lock = new Object();
    /** Delivers an update held back by the throttle once its interval is over. */
    private ScheduledExecutorService timer;
    private Run current;

    /** @param clock milliseconds; production passes System::currentTimeMillis */
    public JobRunner(Executor ui, long throttleMs, LongSupplier clock) {
        this.ui = ui;
        this.throttleMs = throttleMs;
        this.clock = clock;
    }

    /** False, and nothing happens, while another job is running. */
    public boolean start(Job job, Job.Listener uiListener) {
        synchronized (lock) {
            if (current != null) {
                return false;
            }
            Run run = new Run(uiListener);
            current = run;
            run.thread = new Thread(() -> {
                try {
                    job.run(run);
                } catch (InterruptedException e) {
                    // Stopped; the job may not have said how far it got.
                } finally {
                    run.finished(null); // no-op when the job already finished
                }
            }, "NeoSync job");
            run.thread.start();
            return true;
        }
    }

    /** Asks the running job to stop after the book it is on. */
    public void stop() {
        synchronized (lock) {
            if (current != null) {
                current.thread.interrupt();
            }
        }
    }

    /** True from start until finished has been handed to the UI. */
    public boolean isRunning() {
        synchronized (lock) {
            return current != null;
        }
    }

    private ScheduledExecutorService timer() {
        if (timer == null) {
            ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "NeoSync job progress");
                thread.setDaemon(true);
                return thread;
            });
            t.setRemoveOnCancelPolicy(true);
            // No thread left behind between jobs.
            t.setKeepAliveTime(10, TimeUnit.SECONDS);
            t.allowCoreThreadTimeOut(true);
            timer = t;
        }
        return timer;
    }

    /** One job's run: the throttle state, and the listener the job calls. */
    private final class Run implements Job.Listener {
        final Job.Listener target;
        Thread thread;
        boolean anyDelivered;
        long lastDeliveredMs;
        /** The newest update the throttle held back, or null. */
        Runnable pending;
        ScheduledFuture<?> flush;
        boolean finished;

        Run(Job.Listener target) {
            this.target = target;
        }

        @Override
        public void progress(int done, int total, String currentKey, String rowState) {
            Runnable update = () -> target.progress(done, total, currentKey, rowState);
            synchronized (lock) {
                if (finished) {
                    return;
                }
                long now = clock.getAsLong();
                if (!anyDelivered || now - lastDeliveredMs >= throttleMs) {
                    cancelFlush();
                    pending = null;
                    deliver(update, now);
                    return;
                }
                pending = update;
                if (flush == null) {
                    long wait = throttleMs - (now - lastDeliveredMs);
                    flush = timer().schedule(this::flushPending, wait, TimeUnit.MILLISECONDS);
                }
            }
        }

        private void flushPending() {
            synchronized (lock) {
                flush = null;
                if (finished || pending == null) {
                    return;
                }
                Runnable update = pending;
                pending = null;
                deliver(update, clock.getAsLong());
            }
        }

        @Override
        public void finished(String resultSentence) {
            synchronized (lock) {
                if (finished) {
                    return;
                }
                finished = true;
                cancelFlush();
                if (pending != null) {
                    ui.execute(pending);
                    pending = null;
                }
                // Free the runner before the UI hears about the end, so the
                // UI can start the next job from finished().
                ui.execute(() -> {
                    synchronized (lock) {
                        if (current == this) {
                            current = null;
                        }
                    }
                    target.finished(resultSentence);
                });
            }
        }

        /** Called with lock held. */
        private void deliver(Runnable update, long now) {
            anyDelivered = true;
            lastDeliveredMs = now;
            ui.execute(update);
        }

        /** Called with lock held. */
        private void cancelFlush() {
            if (flush != null) {
                flush.cancel(false);
                flush = null;
            }
        }
    }
}
