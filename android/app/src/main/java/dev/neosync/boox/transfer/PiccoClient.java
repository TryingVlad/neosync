package dev.neosync.boox.transfer;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Talks to the CrossPoint file-transfer server that runs on the Picco while
 * its "File Transfer" screen is open (crosspoint-reader,
 * docs/webserver-endpoints.md). Purely local Wi-Fi traffic — this never goes
 * near the kosync server, so no pacing or cooldown applies here.
 *
 *   GET  /api/files?path=<dir>  -> JSON listing of one directory
 *   POST /upload?path=<dir>     -> multipart upload; 400 when the name exists
 *                                  (unless &overwrite=1)
 *   GET  /download?path=<file>  -> the file
 */
public class PiccoClient {

    private static final String TAG = "PiccoClient";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int LIST_READ_TIMEOUT_MS = 15000;
    // The Picco answers an upload only after the whole file is written to its
    // SD card, which can take a while for a large book.
    private static final int UPLOAD_READ_TIMEOUT_MS = 180_000;
    private static final int MAX_SCAN_DEPTH = 4;

    public enum Status { SENT, ALREADY_THERE, SERVER_ERROR }

    public static class UploadResult {
        public final Status status;
        public final int httpCode;
        public final String serverMessage;

        UploadResult(Status status, int httpCode, String serverMessage) {
            this.status = status;
            this.httpCode = httpCode;
            this.serverMessage = serverMessage;
        }
    }

    private final String baseUrl;

    /** Accepts "192.168.1.50" or "crosspoint.local", with or without a scheme. */
    public PiccoClient(String address) {
        String url = address.trim();
        if (!url.contains("://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
    }

    /** True when a file with this exact name exists anywhere on the Picco. */
    public boolean hasBook(String fileName) throws IOException {
        return scan("/", fileName, 0);
    }

    /**
     * Every book file on the Picco, as paths relative to its root
     * ("Fiction/Title.epub"), sorted case-insensitively.
     */
    public List<String> listBooks() throws IOException {
        List<String> books = new ArrayList<>();
        collectBooks("/", 0, books);
        Collections.sort(books, String.CASE_INSENSITIVE_ORDER);
        return books;
    }

    /**
     * Looks at every file of a folder before descending into its subfolders:
     * NeoSync uploads to the top level, so the common case is answered by the
     * first request instead of a walk over the whole library.
     */
    private boolean scan(String path, String fileName, int depth) throws IOException {
        JSONArray entries = listDir(path);
        List<String> subdirs = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String name = entry.optString("name", "");
            if (entry.optBoolean("isDirectory", false)) {
                if (isDescendable(name, depth)) {
                    subdirs.add("/".equals(path) ? "/" + name : path + "/" + name);
                }
            } else if (name.equals(fileName)) {
                return true;
            }
        }
        for (String child : subdirs) {
            if (scan(child, fileName, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private void collectBooks(String path, int depth, List<String> out) throws IOException {
        JSONArray entries = listDir(path);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String name = entry.optString("name", "");
            boolean isDirectory = entry.optBoolean("isDirectory", false);
            String child = "/".equals(path) ? "/" + name : path + "/" + name;
            if (isDirectory) {
                if (isDescendable(name, depth)) {
                    collectBooks(child, depth + 1, out);
                }
            } else if (isBookFile(name)) {
                // Drop the leading slash: paths are shown relative to the root.
                out.add(child.startsWith("/") ? child.substring(1) : child);
            }
        }
    }

    /** Directories worth descending into: no hidden/system folders, bounded depth. */
    private static boolean isDescendable(String name, int depth) {
        return depth < MAX_SCAN_DEPTH && !name.startsWith(".")
                && !name.equals("XTCache") && !name.equals("System Volume Information");
    }

    private static boolean isBookFile(String name) {
        return dev.neosync.boox.library.LibraryModel.isBookFile(name);
    }

    /**
     * Quick check that a Picco answers at this address: GET /api/files?path=/
     * with the given connect/read timeout. True only on HTTP 200 with a JSON
     * array body; any failure is false.
     */
    public boolean probe(int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = open("/api/files?path=" + URLEncoder.encode("/", "UTF-8"), "GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            if (conn.getResponseCode() != 200) {
                return false;
            }
            String body = readBody(conn);
            if (body == null) {
                return false;
            }
            new JSONArray(body);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Raw JSON listing of one directory; throws on any non-200 or bad body. */
    private JSONArray listDir(String path) throws IOException {
        HttpURLConnection conn = open("/api/files?path=" + URLEncoder.encode(path, "UTF-8"), "GET");
        int code;
        String body;
        try {
            code = conn.getResponseCode();
            body = readBody(conn);
        } finally {
            conn.disconnect();
        }
        if (code != 200 || body == null) {
            throw new IOException("Picco file listing failed: HTTP " + code);
        }
        try {
            return new JSONArray(body);
        } catch (Exception e) {
            throw new IOException("Picco file listing unreadable: " + e.getMessage());
        }
    }

    /**
     * The Picco's top-level folders ("/books", …), for choosing where books
     * go. Hidden ones (".crosspoint") are left out.
     */
    public List<String> listFolders() throws IOException {
        List<String> folders = new ArrayList<>();
        JSONArray entries = listDir("/");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !entry.optBoolean("isDirectory", false)) {
                continue;
            }
            String name = entry.optString("name", "");
            if (!name.isEmpty() && !name.startsWith(".")) {
                folders.add("/" + name);
            }
        }
        return folders;
    }

    /**
     * The folder to suggest first: one called "books" (any case), which is
     * where CrossPoint users usually keep them, else the top level.
     */
    public static String suggestedFolder(List<String> folders) {
        for (String folder : folders) {
            if (folder.equalsIgnoreCase("/books")) {
                return folder;
            }
        }
        return "/";
    }

    /**
     * Uploads the file into the given Picco folder ("/" or "/books") under
     * its current name. CrossPoint indexes uploaded book files into its
     * library on its own. Uses a fixed content length: the Picco's tiny HTTP
     * server does not handle chunked bodies reliably.
     */
    public UploadResult upload(File file, String folder) throws IOException {
        return upload(file, folder, file.getName(), false);
    }

    /**
     * Overwrites a book on the Picco with file, keeping the Picco's name and
     * folder. piccoPath is relative to its root ("books/Dune.epub"), as
     * {@link #listBooks()} returns it. CrossPoint drops its cached copy of
     * the book, including its own reading position.
     */
    public UploadResult replace(File file, String piccoPath) throws IOException {
        String path = piccoPath.startsWith("/") ? piccoPath.substring(1) : piccoPath;
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "/" : "/" + path.substring(0, slash);
        return upload(file, folder, path.substring(slash + 1), true);
    }

    /** Saves a Picco book (path as listBooks returns it) to dest. */
    public void download(String piccoPath, File dest) throws IOException {
        String path = piccoPath.startsWith("/") ? piccoPath : "/" + piccoPath;
        HttpURLConnection conn = open("/download?path=" + URLEncoder.encode(path, "UTF-8"), "GET");
        conn.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
        File part = new File(dest.getPath() + ".part");
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("Picco download failed: HTTP " + code);
            }
            long expected = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new java.io.FileOutputStream(part)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (expected >= 0 && part.length() != expected) {
                throw new IOException("Picco download cut short");
            }
            if (!part.renameTo(dest)) {
                throw new IOException("Could not save " + dest.getName());
            }
        } finally {
            conn.disconnect();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private UploadResult upload(File file, String folder, String remoteName, boolean overwrite)
            throws IOException {
        String boundary = "NeoSync" + Long.toHexString(System.currentTimeMillis());
        String fileName = remoteName.replace('"', '_');
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType(fileName) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        String target = folder == null || folder.isEmpty() ? "/" : folder;
        HttpURLConnection conn = open("/upload?path=" + URLEncoder.encode(target, "UTF-8")
                + (overwrite ? "&overwrite=1" : ""), "POST");
        conn.setDoOutput(true);
        conn.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setFixedLengthStreamingMode(head.length + file.length() + tail.length);
        try {
            try (OutputStream out = conn.getOutputStream();
                 InputStream in = new FileInputStream(file)) {
                out.write(head);
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.write(tail);
            }
            int code = conn.getResponseCode();
            String body = readBody(conn);
            Log.d(TAG, "upload " + fileName + " -> " + code);
            if (code >= 200 && code < 300) {
                return new UploadResult(Status.SENT, code, body);
            }
            if (code == 400 && body != null && body.contains("already exists")) {
                return new UploadResult(Status.ALREADY_THERE, code, body);
            }
            return new UploadResult(Status.SERVER_ERROR, code, body);
        } finally {
            conn.disconnect();
        }
    }

    /** MIME type for the multipart part, from the file extension. */
    static String contentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        switch (ext) {
            case "epub": return "application/epub+zip";
            case "pdf": return "application/pdf";
            case "mobi": return "application/x-mobipocket-ebook";
            case "azw":
            case "azw3": return "application/vnd.amazon.ebook";
            case "fb2": return "application/x-fictionbook+xml";
            case "cbz": return "application/vnd.comicbook+zip";
            case "cbr": return "application/vnd.comicbook-rar";
            case "djvu": return "image/vnd.djvu";
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    private HttpURLConnection open(String pathAndQuery, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + pathAndQuery).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(LIST_READ_TIMEOUT_MS);
        return conn;
    }

    private static String readBody(HttpURLConnection conn) {
        try {
            InputStream stream = conn.getResponseCode() >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
