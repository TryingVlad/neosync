package dev.neosync.boox.library;

/** The single card the home screen shows. */
public final class Suggestion {

    public enum Kind {
        JOB, FILE_ACCESS, PROBLEM_NO_INTERNET, PROBLEM_AUTH, PROBLEM_SERVER, PROBLEM_PAUSED,
        SETUP_PICCO, SEND_MISSING, FIX_COVERS, UPDATE
    }

    public final Kind kind;
    public final int count;
    public final String detail;
    /** Total size in bytes; only SEND_MISSING sets it, 0 otherwise. */
    public final long bytes;

    public Suggestion(Kind kind, int count, String detail) {
        this(kind, count, detail, 0L);
    }

    public Suggestion(Kind kind, int count, String detail, long bytes) {
        this.kind = kind;
        this.count = count;
        this.detail = detail;
        this.bytes = bytes;
    }

    /** Identifies this card for "not now"; changes when the count (or update version) changes. */
    public String dismissKey() {
        if (kind == Kind.UPDATE) {
            return kind + ":" + detail;
        }
        return kind + ":" + count;
    }
}
