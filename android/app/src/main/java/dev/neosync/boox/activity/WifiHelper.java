package dev.neosync.boox.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.net.Inet4Address;

/** The Wi-Fi picker and what the current network looks like, shared by home and Connections. */
final class WifiHelper {

    private static final String TAG = "WifiHelper";

    private WifiHelper() {
    }

    /**
     * Apps can't switch Wi-Fi networks themselves (Android 10+), so this opens
     * the system's Wi-Fi panel over the app; the user picks the network and
     * comes straight back. Falls back to the full Wi-Fi settings screen on
     * firmware without the panel.
     */
    static void openWifiPicker(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                activity.startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No Wi-Fi settings screen: " + e);
        }
    }

    /** {gateway, own IPv4 address} of the current network; either may be null. */
    static String[] localNetwork(Context context) {
        String[] out = new String[2];
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm != null ? cm.getActiveNetwork() : null;
            LinkProperties lp = network != null ? cm.getLinkProperties(network) : null;
            if (lp == null) {
                return out;
            }
            for (RouteInfo route : lp.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                    out[0] = route.getGateway().getHostAddress();
                    break;
                }
            }
            for (LinkAddress address : lp.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    out[1] = address.getAddress().getHostAddress();
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "No network details: " + e);
        }
        return out;
    }

    /**
     * True when the tablet currently has a local-network link (Wi-Fi or
     * Ethernet). A Picco hotspot without internet access still counts, so the
     * check looks at the transport, not at internet reachability.
     */
    static boolean hasLocalNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true; // Cannot tell — fall back to the generic message.
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }
}
