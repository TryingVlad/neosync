package dev.neosync.boox.library;

import dev.neosync.boox.kosync.KoSyncClient;

import org.json.JSONException;
import org.json.JSONObject;

/** The latest reading position the sync server holds for one document hash. */
public final class RemotePosition {
    public final String document;
    public final double percentage;
    public final String device;
    public final String deviceId;
    public final long timestampMs;

    public RemotePosition(String document, double percentage, String device, String deviceId,
                          long timestampMs) {
        this.document = document;
        this.percentage = percentage;
        this.device = device;
        this.deviceId = deviceId;
        this.timestampMs = timestampMs;
    }

    public static RemotePosition from(KoSyncClient.Progress p) {
        return new RemotePosition(p.document, p.percentage, p.device, p.deviceId,
                p.timestampSec * 1000L);
    }

    public String toJson() {
        try {
            return new JSONObject()
                    .put("document", document)
                    .put("percentage", percentage)
                    .put("device", device)
                    .put("deviceId", deviceId)
                    .put("timestampMs", timestampMs)
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null when the text isn't a stored position. */
    public static RemotePosition fromJson(String s) {
        if (s == null) {
            return null;
        }
        try {
            JSONObject j = new JSONObject(s);
            return new RemotePosition(j.optString("document", ""), j.optDouble("percentage", 0.0),
                    j.optString("device", ""), j.optString("deviceId", ""),
                    j.optLong("timestampMs", 0));
        } catch (JSONException e) {
            return null;
        }
    }

    /** The one with the larger timestamp; a tie goes to b. Null-safe. */
    public static RemotePosition newer(RemotePosition a, RemotePosition b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.timestampMs > b.timestampMs ? a : b;
    }

    /** CrossPoint devices (and unnamed ones) are the e-reader side of the library. */
    public boolean isCrossPoint() {
        return device == null || device.isEmpty()
                || device.toLowerCase(java.util.Locale.ROOT).contains("crosspoint");
    }
}
