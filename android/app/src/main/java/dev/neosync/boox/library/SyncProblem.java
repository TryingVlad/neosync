package dev.neosync.boox.library;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import dev.neosync.boox.kosync.KoSyncClient;

/** What kind of sync problem is going on, and when it counts as an alarm. */
public final class SyncProblem {

    public enum Type { NONE, NO_INTERNET, AUTH, SERVER_UNREACHABLE, RATE_LIMITED, OTHER }

    private static final long ALARM_AFTER_MS = 300000;
    private static final int ALARM_AFTER_FAILURES = 2;

    private SyncProblem() {
    }

    public static Type classify(KoSyncClient.Error e, boolean wifiHasInternet) {
        switch (e) {
            case OK:
                return Type.NONE;
            case AUTH_FAILED:
            case NO_CREDENTIALS:
                return Type.AUTH;
            case NETWORK_ERROR:
                return wifiHasInternet ? Type.SERVER_UNREACHABLE : Type.NO_INTERNET;
            case SERVER_ERROR:
                return Type.SERVER_UNREACHABLE;
            case RATE_LIMITED:
                return Type.RATE_LIMITED;
            default:
                return Type.OTHER;
        }
    }

    /** True once a problem has lasted five minutes or failed twice in a row. */
    public static boolean isAlarm(long firstFailureMs, int consecutiveFailures, long nowMs) {
        return firstFailureMs > 0
                && (nowMs - firstFailureMs >= ALARM_AFTER_MS
                || consecutiveFailures >= ALARM_AFTER_FAILURES);
    }

    /** True when Android has confirmed the current network reaches the internet. */
    public static boolean wifiHasInternet(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true; // Cannot tell: treat as reachable.
        }
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
