package dev.neosync.boox.activity;

import android.os.Handler;
import android.os.Looper;

import dev.neosync.boox.jobs.Job;
import dev.neosync.boox.jobs.JobRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * The long job and what it last reported, kept for the whole process so it
 * outlives the home screen: when the Boox rotates, the new MainActivity
 * attaches and shows the same progress card, and can't start a second job.
 * Main thread only.
 */
final class JobSession {

    /** The home screen currently showing the job. */
    interface Screen {
        void jobProgress();

        void jobRows();

        void jobFinished(String resultSentence);
    }

    /** One job at a time for the whole app; e-ink friendly progress (every 3 s at most). */
    private static final JobRunner RUNNER = new JobRunner(
            new Handler(Looper.getMainLooper())::post, 3000, System::currentTimeMillis);

    /** The running job, or null. */
    static Job job;
    static int done;
    static int total;
    static String key;
    static String state;
    static boolean stopping;
    /** Row states of the running (or just finished) job, by library key. */
    static final Map<String, String> rows = new HashMap<>();
    /** A job ended while no screen was attached; its result is shown on attach. */
    static boolean resultUnseen;
    /** That job's result sentence; null when it stopped without one. */
    static String unseenResult;

    private static Screen screen;

    private JobSession() {
    }

    static void attach(Screen s) {
        screen = s;
    }

    static void detach(Screen s) {
        if (screen == s) {
            screen = null;
        }
    }

    static boolean running() {
        return job != null;
    }

    /** False, and nothing happens, while another job runs. */
    static boolean start(Job newJob) {
        boolean started = RUNNER.start(newJob, new Job.Listener() {
            @Override
            public void progress(int d, int t, String k, String rowState) {
                done = d;
                total = t;
                key = k;
                state = rowState;
                if (screen != null) {
                    screen.jobProgress();
                }
            }

            @Override
            public void rowStates(Map<String, String> states) {
                rows.putAll(states);
                if (screen != null) {
                    screen.jobRows();
                }
            }

            @Override
            public void finished(String resultSentence) {
                job = null;
                stopping = false;
                if (screen != null) {
                    screen.jobFinished(resultSentence);
                } else {
                    resultUnseen = true;
                    unseenResult = resultSentence;
                }
            }
        });
        if (started) {
            job = newJob;
            done = 0;
            total = 0;
            key = null;
            state = null;
            stopping = false;
            resultUnseen = false;
            unseenResult = null;
        }
        return started;
    }

    static void stop() {
        stopping = true;
        RUNNER.stop();
    }
}
