package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import dev.neosync.boox.library.BookMeta;
import dev.neosync.boox.library.BookMetaCache;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A book NeoSync couldn't open (no all-files access yet) must not be
 * remembered as coverless, nor counted as a black tile on the Picco.
 */
public class BookMetaCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Map<String, String> map = new HashMap<>();

    private BookMetaCache cache() {
        return new BookMetaCache(new BookMetaCache.Store() {
            @Override
            public String get(String key) {
                return map.get(key);
            }

            @Override
            public void put(String key, String value) {
                map.put(key, value);
            }
        }, new File(tmp.getRoot(), "thumbs"));
    }

    /** An EPUB whose cover the Picco finds (a meta cover pointing at a JPEG). */
    private File bookWithCover() throws Exception {
        File file = tmp.newFile("book.epub");
        String opf = "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Real Title</dc:title>"
                + "<dc:creator>Someone</dc:creator><meta name=\"cover\" content=\"img\"/></metadata>"
                + "<manifest><item id=\"img\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>"
                + "<item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                + "<spine><itemref idref=\"c1\"/></spine></package>";
        String container = "<?xml version=\"1.0\"?><container version=\"1.0\" "
                + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles></container>";
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            put(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            put(zip, "META-INF/container.xml", container.getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/cover.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1});
            put(zip, "OEBPS/c1.xhtml", "<html><body><p>Hello there.</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** Like a book without all-files access: its size and date are known, its content isn't. */
    private File unreadable(File file) {
        assumeFalse("root can read anything", "root".equals(System.getProperty("user.name")));
        assertTrue(file.setReadable(false, false));
        return file;
    }

    @Test
    public void unreadableBookIsNotBlack() throws Exception {
        BookMeta meta = cache().get(unreadable(bookWithCover()));
        assertFalse(meta.wouldShowBlack);
        assertFalse(meta.hasCover);
    }

    @Test
    public void unreadableResultIsNotCached() throws Exception {
        File book = unreadable(bookWithCover());
        cache().get(book);
        assertTrue(map.isEmpty());

        assertTrue(book.setReadable(true, false));
        BookMeta meta = cache().get(book);
        assertEquals("Real Title", meta.title);
        assertEquals("Someone", meta.author);
        assertTrue(meta.hasCover);
        assertFalse(meta.wouldShowBlack);
    }

    @Test
    public void oldSchemaEntriesAreRecomputed() throws Exception {
        File book = bookWithCover();
        cache().get(book);
        assertEquals(1, map.size());
        // What an older NeoSync stored for a book it couldn't open: no schema field.
        String key = map.keySet().iterator().next();
        JSONObject stale = new JSONObject(map.get(key));
        stale.remove("s");
        stale.put("title", "book");
        stale.put("author", "");
        stale.put("cover", false);
        stale.put("black", true);
        map.put(key, stale.toString());

        BookMeta meta = cache().get(book);
        assertEquals("Real Title", meta.title);
        assertTrue(meta.hasCover);
        assertFalse(meta.wouldShowBlack);
    }

    @Test
    public void readableResultIsCached() throws Exception {
        File book = bookWithCover();
        cache().get(book);
        String key = map.keySet().iterator().next();
        JSONObject o = new JSONObject(map.get(key));
        o.put("title", "From cache");
        map.put(key, o.toString());
        assertEquals("From cache", cache().get(book).title);
    }
}
