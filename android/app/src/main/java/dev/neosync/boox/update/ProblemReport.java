package dev.neosync.boox.update;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.neosync.boox.contentprovider.OnyxMetadataRepository;
import dev.neosync.boox.kosync.SyncSettings;

/**
 * A text report for bug reports, pasted by the user wherever they report
 * the problem (Reddit, GitHub): device, firmware, app version, a few
 * settings and NeoSync's own recent log, with private details scrubbed.
 * Never contains the password hash, the username, book titles, file names,
 * paths or document hashes.
 */
public class ProblemReport {

    /** Log tags written by NeoSync, plus crash traces. */
    private static final String[] LOG_FILTER = {
            "KoSyncClient:V", "SyncService:V", "PiccoClient:V", "OnyxMetadataRepo:V",
            "OnyxStatsRepo:V", "MainActivity:V", "UpdateChecker:V", "AndroidRuntime:E", "*:S"};
    private static final int MAX_LOG_LINES = 400;
    /** Reddit comments stop at 10,000 characters; leave room for a message. */
    private static final int MAX_REPORT_CHARS = 9000;

    private ProblemReport() {
    }

    /** Builds the report. Blocking (reads the library and the log). */
    public static String build(Context context, String appVersion) {
        SyncSettings settings = new SyncSettings(context);
        Set<String> secrets = new LinkedHashSet<>();
        if (!settings.getUsername().isEmpty()) {
            secrets.add(settings.getUsername());
        }
        if (!settings.getPasswordMd5().isEmpty()) {
            secrets.add(settings.getPasswordMd5());
        }
        if (!settings.getServerUrl().isEmpty()) {
            secrets.add(settings.getServerUrl());
        }
        if (!settings.getLastBook().isEmpty()) {
            secrets.add(settings.getLastBook());
        }
        try {
            for (OnyxMetadataRepository.Book book : new OnyxMetadataRepository(context).queryAllBooks()) {
                addBookNames(secrets, book.path);
                addBookNames(secrets, book.name);
            }
        } catch (Exception ignored) {
            // Library unreadable: the pattern-based scrubbing still applies.
        }

        StringBuilder sb = new StringBuilder();
        sb.append("NeoSync problem report\n");
        sb.append("What happened: (please describe)\n\n");
        sb.append("--- Device\n");
        sb.append("App: NeoSync ").append(appVersion).append('\n');
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Firmware: ").append(Build.DISPLAY).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("--- Settings\n");
        sb.append("Signed in: ").append(settings.hasCredentials() ? "yes" : "no").append('\n');
        sb.append("Sync server: ").append(settings.getServerUrl().isEmpty() ? "default" : "custom")
                .append('\n');
        sb.append("Automatic sync: ").append(settings.isAutoSyncEnabled() ? "on" : "off").append('\n');
        sb.append("Book matching: ").append(settings.getMatchMethod()).append('\n');
        sb.append("Picco address set: ").append(settings.getPiccoAddress().isEmpty() ? "no" : "yes")
                .append('\n');
        sb.append("All files access: ").append(allFilesAccess() ? "yes" : "no").append('\n');
        long lastSync = settings.getLastSyncMs();
        sb.append("Last sync: ").append(lastSync > 0
                ? new java.util.Date(lastSync).toString() : "never").append('\n');
        sb.append("Last problem: ").append(settings.getLastError().isEmpty()
                ? "none" : settings.getLastError()).append('\n');
        sb.append("--- Log (most recent last)\n");
        String header = redact(sb.toString(), secrets);
        return header + tail(redact(readLog(), secrets), MAX_REPORT_CHARS - header.length());
    }

    /**
     * The last whole lines of text that fit in maxChars, so the report stays
     * under a Reddit comment's 10,000-character limit. The newest lines
     * matter most, so older ones are dropped first.
     */
    public static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String marker = "(older log lines left out)\n";
        int start = text.length() - Math.max(0, maxChars - marker.length());
        int lineStart = text.indexOf('\n', start);
        return marker + (lineStart >= 0 ? text.substring(lineStart + 1) : "");
    }

    private static void addBookNames(Set<String> out, String pathOrName) {
        if (pathOrName == null || pathOrName.isEmpty()) {
            return;
        }
        out.add(pathOrName);
        String file = pathOrName.substring(pathOrName.lastIndexOf('/') + 1);
        out.add(file);
        int dot = file.lastIndexOf('.');
        if (dot > 0) {
            out.add(file.substring(0, dot));
        }
    }

    private static boolean allFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager();
    }

    /** NeoSync's own recent log lines (an app can only read its own). */
    private static String readLog() {
        List<String> command = new ArrayList<>();
        command.add("logcat");
        command.add("-d");
        command.add("-v");
        command.add("time");
        command.add("-t");
        command.add(String.valueOf(MAX_LOG_LINES));
        for (String filter : LOG_FILTER) {
            command.add(filter);
        }
        StringBuilder sb = new StringBuilder();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            process.waitFor();
        } catch (Exception e) {
            sb.append("(log unavailable: ").append(e.getClass().getSimpleName()).append(")\n");
        }
        return sb.length() > 0 ? sb.toString() : "(no log lines)\n";
    }

    /**
     * Replaces every known private string (longest first, so a full path
     * goes before the file name inside it), then anything that still looks
     * like a path, a document hash, an e-mail address or a book file name.
     */
    public static String redact(String text, Collection<String> secrets) {
        List<String> sorted = new ArrayList<>();
        for (String secret : secrets) {
            // Very short strings ("a", "Go") would shred the report.
            if (secret != null && secret.trim().length() >= 4) {
                sorted.add(secret);
            }
        }
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        String out = text;
        for (String secret : sorted) {
            out = out.replace(secret, secret.contains("/") ? "<path>" : "<hidden>");
        }
        out = out.replaceAll("(?:/storage|/sdcard|/mnt|/data/media)/[^\\s\"']*", "<path>");
        out = out.replaceAll("\\b[0-9a-fA-F]{32}\\b", "<hash>");
        out = out.replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "<email>");
        out = out.replaceAll("(?i)[^\\s/\"'<>]+\\.(?:epub|pdf|mobi|azw3?|fb2|cbz|cbr|djvu)\\b",
                "<book>");
        return out;
    }
}
