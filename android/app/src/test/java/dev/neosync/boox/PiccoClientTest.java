package dev.neosync.boox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.neosync.boox.transfer.PiccoClient;

/**
 * Runs PiccoClient against a fake CrossPoint file-transfer server
 * (GET /api/files, POST /upload) on localhost.
 */
public class PiccoClientTest {

    private ServerSocket server;
    private Thread serverThread;
    private String address;
    /** Directory path -> JSON listing served for it. */
    private final Map<String, String> listings = new HashMap<>();
    /** Directory paths requested, in order. */
    private final List<String> listed = new ArrayList<>();
    private int uploadCode = 200;
    private String uploadBody = "File uploaded successfully";
    private byte[] uploadedBody;
    private String uploadedContentType;
    private String uploadedLength;

    @Before
    public void startServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        address = "127.0.0.1:" + server.getLocalPort();
        serverThread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (IOException ignored) {
                    // Closed in stopServer, or a client hung up.
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @After
    public void stopServer() throws Exception {
        server.close();
        serverThread.join(2000);
    }

    /** Minimal HTTP/1.1: one request per connection, Content-Length bodies only. */
    private void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String requestLine = readLine(in);
        Map<String, String> headers = new HashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
        }
        String target = requestLine.split(" ")[1];
        int code;
        String body;
        if (target.startsWith("/api/files?path=")) {
            String path = URLDecoder.decode(target.substring("/api/files?path=".length()), "UTF-8");
            listed.add(path);
            body = listings.get(path);
            code = body != null ? 200 : 404;
            if (body == null) body = "not found";
        } else if (target.startsWith("/upload")) {
            uploadedContentType = headers.get("content-type");
            uploadedLength = headers.get("content-length");
            uploadedBody = new byte[Integer.parseInt(uploadedLength)];
            int off = 0;
            while (off < uploadedBody.length) {
                int n = in.read(uploadedBody, off, uploadedBody.length - off);
                if (n < 0) throw new IOException("short body");
                off += n;
            }
            code = uploadCode;
            body = uploadBody;
        } else {
            code = 404;
            body = "no route";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String file(String name) {
        return "{\"name\":\"" + name + "\",\"isDirectory\":false}";
    }

    private static String dir(String name) {
        return "{\"name\":\"" + name + "\",\"isDirectory\":true}";
    }

    private static String listing(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    @Test
    public void hasBook_findsTopLevelFileWithoutDescending() throws IOException {
        listings.put("/", listing(dir("Fiction"), file("Dune.epub")));
        listings.put("/Fiction", listing(file("Other.epub")));

        assertTrue(new PiccoClient(address).hasBook("Dune.epub"));
        assertEquals(Arrays.asList("/"), listed);
    }

    @Test
    public void hasBook_findsFileInSubfolderAndSkipsSystemFolders() throws IOException {
        listings.put("/", listing(dir(".crosspoint"), dir("XTCache"), dir("Fiction")));
        listings.put("/Fiction", listing(dir("Sci-Fi")));
        listings.put("/Fiction/Sci-Fi", listing(file("Dune.epub")));

        assertTrue(new PiccoClient("http://" + address + "/").hasBook("Dune.epub"));
        assertEquals(Arrays.asList("/", "/Fiction", "/Fiction/Sci-Fi"), listed);
    }

    @Test
    public void hasBook_falseWhenAbsent() throws IOException {
        listings.put("/", listing(file("Dune.epub")));
        assertFalse(new PiccoClient(address).hasBook("Emma.epub"));
    }

    @Test
    public void hasBook_doesNotMatchDirectoryWithSameName() throws IOException {
        listings.put("/", listing(dir("Dune.epub")));
        listings.put("/Dune.epub", listing());
        assertFalse(new PiccoClient(address).hasBook("Dune.epub"));
    }

    @Test
    public void listBooks_returnsOnlyBookFilesSorted() throws IOException {
        listings.put("/", listing(file("zeta.EPUB"), file("notes.txt"), dir("Comics"),
                file("Alpha.pdf")));
        listings.put("/Comics", listing(file("batch.cbz"), file("cover.jpg")));

        assertEquals(Arrays.asList("Alpha.pdf", "Comics/batch.cbz", "zeta.EPUB"),
                new PiccoClient(address).listBooks());
    }

    @Test
    public void listing_badStatusOrBodyThrowsIOException() {
        // No listing registered for "/" -> 404.
        try {
            new PiccoClient(address).listBooks();
            fail("expected IOException");
        } catch (IOException expected) {
        }
        listings.put("/", "<html>not json</html>");
        try {
            new PiccoClient(address).hasBook("x.epub");
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void upload_sendsFixedLengthMultipartWithMatchingContentType() throws IOException {
        byte[] content = "%PDF-1.4 fake".getBytes(StandardCharsets.UTF_8);
        File book = writeTemp("Report.pdf", content);

        PiccoClient.UploadResult result = new PiccoClient(address).upload(book);

        assertEquals(PiccoClient.Status.SENT, result.status);
        assertTrue(uploadedContentType.startsWith("multipart/form-data; boundary="));
        assertEquals(String.valueOf(uploadedBody.length), uploadedLength);
        String body = new String(uploadedBody, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("filename=\"Report.pdf\""));
        assertTrue(body.contains("Content-Type: application/pdf\r\n\r\n"));
        int start = body.indexOf("\r\n\r\n") + 4;
        byte[] payload = Arrays.copyOfRange(uploadedBody, start, start + content.length);
        assertArrayEquals(content, payload);
    }

    @Test
    public void upload_mapsAlreadyExistsAndOtherErrors() throws IOException {
        File book = writeTemp("Dune.epub", new byte[] {1, 2, 3});

        uploadCode = 400;
        uploadBody = "File already exists";
        assertEquals(PiccoClient.Status.ALREADY_THERE, new PiccoClient(address).upload(book).status);

        uploadBody = "Invalid path";
        PiccoClient.UploadResult result = new PiccoClient(address).upload(book);
        assertEquals(PiccoClient.Status.SERVER_ERROR, result.status);
        assertEquals(400, result.httpCode);

        uploadCode = 500;
        assertEquals(PiccoClient.Status.SERVER_ERROR, new PiccoClient(address).upload(book).status);
    }

    private static File writeTemp(String name, byte[] content) throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("picco").toFile();
        dir.deleteOnExit();
        File file = new File(dir, name);
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content);
        }
        return file;
    }
}
