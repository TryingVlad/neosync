package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import dev.neosync.boox.jobs.Job;
import dev.neosync.boox.jobs.JobRunner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class JobRunnerTest {

    private static final Executor DIRECT = Runnable::run;

    /** Records what reaches the "UI" and lets the test wait for the end. */
    private static class Recorder implements Job.Listener {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void progress(int done, int total, String currentKey, String rowState) {
            calls.add("progress " + done + "/" + total + " " + currentKey + " " + rowState);
        }

        @Override
        public void finished(String resultSentence) {
            calls.add("finished " + resultSentence);
            finished.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("job never finished", finished.await(5, TimeUnit.SECONDS));
        }
    }

    /** A job built from a lambda; the title isn't needed here. */
    private interface Body {
        void run(Job.Listener l) throws InterruptedException;
    }

    private static Job job(Body body) {
        return new Job() {
            @Override
            public String title(Context c) {
                return "test";
            }

            @Override
            public void run(Listener l) throws InterruptedException {
                body.run(l);
            }
        };
    }

    @Test
    public void throttlesProgress() throws Exception {
        AtomicLong now = new AtomicLong(10_000);
        JobRunner runner = new JobRunner(DIRECT, 3000, now::get);
        Recorder ui = new Recorder();
        assertTrue(runner.start(job(l -> {
            for (int i = 0; i < 10; i++) {
                l.progress(i, 10, "book" + i + ".epub", "sending");
                now.addAndGet(100); // 10 calls within 1000 ms
            }
            l.finished("Sent 10 of 10 books to the Picco.");
        }), ui));
        ui.await();
        assertEquals(List.of(
                "progress 0/10 book0.epub sending",
                "progress 9/10 book9.epub sending",
                "finished Sent 10 of 10 books to the Picco."), ui.calls);
    }

    @Test
    public void progressAfterThrottleIsDelivered() throws Exception {
        AtomicLong now = new AtomicLong(0);
        JobRunner runner = new JobRunner(DIRECT, 3000, now::get);
        Recorder ui = new Recorder();
        runner.start(job(l -> {
            l.progress(0, 2, "a.epub", "sending");
            now.addAndGet(3000);
            l.progress(1, 2, "a.epub", "sent");
            l.finished("done");
        }), ui);
        ui.await();
        assertEquals(List.of(
                "progress 0/2 a.epub sending",
                "progress 1/2 a.epub sent",
                "finished done"), ui.calls);
    }

    @Test
    public void heldBackProgressArrivesOnceTheIntervalIsOver() throws Exception {
        JobRunner runner = new JobRunner(DIRECT, 50, () -> 0);
        Recorder ui = new Recorder();
        int[] seenBeforeFinish = new int[1];
        runner.start(job(l -> {
            l.progress(0, 2, "a.epub", "sending");
            l.progress(1, 2, "a.epub", "sent"); // held back
            Thread.sleep(500); // e.g. the next upload takes long
            seenBeforeFinish[0] = ui.calls.size();
            l.finished("done");
        }), ui);
        ui.await();
        assertEquals(2, seenBeforeFinish[0]);
        assertEquals(List.of(
                "progress 0/2 a.epub sending",
                "progress 1/2 a.epub sent",
                "finished done"), ui.calls);
    }

    /** Also records each batch of row states, in delivery order. */
    private static final class RowRecorder extends Recorder {
        final List<Map<String, String>> batches =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void rowStates(Map<String, String> states) {
            batches.add(new LinkedHashMap<>(states));
            calls.add("rows " + states);
        }
    }

    @Test
    public void heldBackRowStatesAreAllDelivered() throws Exception {
        AtomicLong now = new AtomicLong(0);
        JobRunner runner = new JobRunner(DIRECT, 3000, now::get);
        RowRecorder ui = new RowRecorder();
        runner.start(job(l -> {
            l.progress(0, 3, "a.epub", "sending"); // delivered at once
            now.addAndGet(100);
            l.progress(1, 3, "a.epub", "sent");    // held back
            l.progress(1, 3, "b.epub", "sending"); // held back
            l.progress(2, 3, "b.epub", "sent");    // held back: newest for b
            l.progress(2, 3, "c.epub", null);      // no row change
            now.addAndGet(3000);
            l.progress(2, 3, "c.epub", "sending"); // past the interval
            l.finished("done");
        }), ui);
        ui.await();
        assertEquals(List.of(
                "rows {a.epub=sending}",
                "progress 0/3 a.epub sending",
                "rows {a.epub=sent, b.epub=sent, c.epub=sending}",
                "progress 2/3 c.epub sending",
                "finished done"), ui.calls);
    }

    @Test
    public void rowStatesHeldBackAtTheEndArriveBeforeFinished() throws Exception {
        JobRunner runner = new JobRunner(DIRECT, 3000, () -> 0);
        RowRecorder ui = new RowRecorder();
        runner.start(job(l -> {
            l.progress(0, 2, "a.epub", "sending");
            l.progress(1, 2, "a.epub", "sent");
            l.progress(2, 2, "b.epub", "sent");
            l.finished("done");
        }), ui);
        ui.await();
        assertEquals(List.of(
                "rows {a.epub=sending}",
                "progress 0/2 a.epub sending",
                "rows {a.epub=sent, b.epub=sent}",
                "progress 2/2 b.epub sent",
                "finished done"), ui.calls);
    }

    @Test
    public void secondStartRefused() throws Exception {
        JobRunner runner = new JobRunner(DIRECT, 3000, () -> 0);
        CountDownLatch release = new CountDownLatch(1);
        Recorder first = new Recorder();
        assertTrue(runner.start(job(l -> {
            release.await();
            l.finished("first");
        }), first));
        assertTrue(runner.isRunning());

        Recorder second = new Recorder();
        assertFalse(runner.start(job(l -> l.finished("second")), second));

        release.countDown();
        first.await();
        assertEquals(List.of("finished first"), first.calls);
        assertTrue(second.calls.isEmpty());
        assertFalse(runner.isRunning());
        // Free again once the first one is over.
        Recorder third = new Recorder();
        assertTrue(runner.start(job(l -> l.finished("third")), third));
        third.await();
    }

    @Test
    public void stopInterrupts() throws Exception {
        JobRunner runner = new JobRunner(DIRECT, 3000, () -> 0);
        CountDownLatch started = new CountDownLatch(1);
        Recorder ui = new Recorder();
        runner.start(job(l -> {
            started.countDown();
            while (!Thread.interrupted()) {
                Thread.yield();
            }
            throw new InterruptedException();
        }), ui);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        runner.stop();
        ui.await();
        Thread.sleep(50); // a second finished would arrive here
        assertEquals(List.of("finished null"), ui.calls);
        assertFalse(runner.isRunning());
    }

    @Test
    public void finishedOnlyOnceWhenJobReportsAndThenStops() throws Exception {
        JobRunner runner = new JobRunner(DIRECT, 3000, () -> 0);
        Recorder ui = new Recorder();
        runner.start(job(l -> {
            l.finished("Stopped after 2 of 5 books.");
            throw new InterruptedException();
        }), ui);
        ui.await();
        Thread.sleep(50);
        assertEquals(List.of("finished Stopped after 2 of 5 books."), ui.calls);
    }
}
