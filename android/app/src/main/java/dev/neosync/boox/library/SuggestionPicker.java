package dev.neosync.boox.library;

import java.util.Set;

/** Decides which single card the home screen shows (spec 5.1). */
public final class SuggestionPicker {

    public static final class Input {
        public boolean jobRunning;
        public boolean autoSyncOn;
        public SyncProblem.Type problem;
        public boolean problemIsAlarm;
        public boolean piccoConfigured;
        public boolean piccoReachable;
        public int missingOnPicco;
        public String missingNames;
        public long missingBytes;
        public int blackCovers;
        public String updateVersion;
        public Set<String> dismissed;
    }

    private SuggestionPicker() {
    }

    /** Returns the card to show, or null for none. */
    public static Suggestion pick(Input in) {
        if (in.jobRunning) {
            return new Suggestion(Suggestion.Kind.JOB, 0, null);
        }
        if (!in.autoSyncOn) {
            return new Suggestion(Suggestion.Kind.PROBLEM_PAUSED, 0, null);
        }
        if (in.problemIsAlarm && in.problem != null) {
            switch (in.problem) {
                case NO_INTERNET:
                    return new Suggestion(Suggestion.Kind.PROBLEM_NO_INTERNET, 0, null);
                case AUTH:
                    return new Suggestion(Suggestion.Kind.PROBLEM_AUTH, 0, null);
                case SERVER_UNREACHABLE:
                case RATE_LIMITED:
                case OTHER:
                    return new Suggestion(Suggestion.Kind.PROBLEM_SERVER, 0, null);
                default: // NONE
                    break;
            }
        }
        Suggestion s;
        if (!in.piccoConfigured) {
            s = new Suggestion(Suggestion.Kind.SETUP_PICCO, 0, null);
            if (!isDismissed(in, s)) {
                return s;
            }
        }
        if (in.piccoConfigured && in.piccoReachable && in.missingOnPicco > 0) {
            s = new Suggestion(Suggestion.Kind.SEND_MISSING, in.missingOnPicco, in.missingNames,
                    in.missingBytes);
            if (!isDismissed(in, s)) {
                return s;
            }
        }
        if (in.blackCovers > 0) {
            s = new Suggestion(Suggestion.Kind.FIX_COVERS, in.blackCovers, null);
            if (!isDismissed(in, s)) {
                return s;
            }
        }
        if (in.updateVersion != null) {
            s = new Suggestion(Suggestion.Kind.UPDATE, 0, in.updateVersion);
            if (!isDismissed(in, s)) {
                return s;
            }
        }
        return null;
    }

    private static boolean isDismissed(Input in, Suggestion s) {
        return in.dismissed != null && in.dismissed.contains(s.dismissKey());
    }
}
