package dev.neosync.boox.library;

/** The single card the home screen shows. */
public final class Suggestion {

    public enum Kind {
        JOB, PROBLEM_NO_INTERNET, PROBLEM_AUTH, PROBLEM_SERVER, PROBLEM_PAUSED,
        SETUP_PICCO, SEND_MISSING, FIX_COVERS, UPDATE
    }

    public final Kind kind;
    public final int count;
    public final String detail;

    public Suggestion(Kind kind, int count, String detail) {
        this.kind = kind;
        this.count = count;
        this.detail = detail;
    }

    /** Identifies this card for "not now"; changes when the count (or update version) changes. */
    public String dismissKey() {
        if (kind == Kind.UPDATE) {
            return kind + ":" + detail;
        }
        return kind + ":" + count;
    }
}
