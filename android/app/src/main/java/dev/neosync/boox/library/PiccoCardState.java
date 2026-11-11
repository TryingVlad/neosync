package dev.neosync.boox.library;

/** What the Connections screen's Picco card says (spec 5.3); pure. */
public final class PiccoCardState {

    public enum State { CONNECTED, CHECKING, LOOKING, NOT_FOUND, NOT_SET_UP, UNREACHABLE }

    private PiccoCardState() {
    }

    /**
     * A running search wins (its result is authoritative); then the last
     * answer for the saved address; then a probe still waiting for its
     * first answer; then a failed search; then no address / no answer.
     *
     * @param reachable the last check's answer for the saved address, null when unknown
     */
    public static State of(boolean hasAddress, Boolean reachable, boolean checking,
                           boolean looking, boolean lookFailed) {
        if (looking) {
            return State.LOOKING;
        }
        if (hasAddress && Boolean.TRUE.equals(reachable)) {
            return State.CONNECTED;
        }
        if (hasAddress && checking && reachable == null && !lookFailed) {
            return State.CHECKING;
        }
        if (lookFailed) {
            return State.NOT_FOUND;
        }
        return hasAddress ? State.UNREACHABLE : State.NOT_SET_UP;
    }

    /** The three steps and the Look / Wi-Fi buttons. */
    public static boolean showsSteps(State s) {
        return s != State.CONNECTED && s != State.CHECKING;
    }

    public static boolean isConnected(State s) {
        return s == State.CONNECTED;
    }
}
