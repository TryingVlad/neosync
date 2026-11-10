package dev.neosync.boox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import dev.neosync.boox.epub.EpubSpine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Locks the spine byte table: NeoReader's chapterStartPosition values and
 * CrossPoint's sync percentage are both defined by it, so a change here
 * silently moves every synced position.
 */
public class EpubSpineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String CH1 = "<html><body><p>abc</p></body></html>";          // 36 bytes
    private static final String CH2 = "<html><body><p>héè l’été</p></body></html>"; // multi-byte
    private static final String CH3 = "<html><body><p>0123456789</p></body></html>";   // 43 bytes

    private File epub() throws Exception {
        File file = tmp.newFile("book.epub");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml",
                    "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                            + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>");
            put(zip, "OEBPS/content.opf",
                    "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\"><manifest>"
                            + "<item id=\"c2\" href=\"Text/two%20b.xhtml\"/>"
                            + "<item id=\"c1\" href=\"Text/one.xhtml\"/>"
                            + "<item id=\"c3\" href=\"../OEBPS/Text/three.xhtml\"/>"
                            + "<item id=\"css\" href=\"style.css\"/>"
                            + "</manifest><spine><itemref idref=\"c1\"/><itemref idref=\"c2\"/>"
                            + "<itemref idref=\"c3\"/></spine></package>");
            put(zip, "OEBPS/Text/one.xhtml", CH1);
            put(zip, "OEBPS/Text/two b.xhtml", CH2);
            put(zip, "OEBPS/Text/three.xhtml", CH3);
            put(zip, "OEBPS/style.css", "p { margin: 0 }");
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    public void spineIsCumulativeUncompressedBytesInReadingOrder() throws Exception {
        EpubSpine spine = EpubSpine.read(epub());
        assertNotNull(spine);
        long b1 = CH1.getBytes(StandardCharsets.UTF_8).length;
        long b2 = CH2.getBytes(StandardCharsets.UTF_8).length;
        long b3 = CH3.getBytes(StandardCharsets.UTF_8).length;

        assertEquals(3, spine.items.size());
        assertEquals("c1", spine.items.get(0).id);
        assertEquals(0, spine.items.get(0).start);
        assertEquals(b1, spine.items.get(0).end);
        assertEquals("c2", spine.items.get(1).id);
        assertEquals(b1, spine.items.get(1).start);
        assertEquals(b1 + b2, spine.items.get(2).start);
        assertEquals(b1 + b2 + b3, spine.totalBytes);
    }

    @Test
    public void percentageRoundTripsThroughChapterAndOffset() throws Exception {
        EpubSpine spine = EpubSpine.read(epub());
        double pct = spine.percentage(2, 10);
        EpubSpine.Item item = spine.itemAt(pct);
        assertEquals(2, item.index);
        assertEquals(10, spine.globalOffset(pct) - item.start);

        assertEquals(0, spine.itemAt(0.0).index);
        assertEquals(2, spine.itemAt(1.0).index);
    }

    @Test
    public void characterAndByteOffsetsConvertAcrossMultiByteText() throws Exception {
        EpubSpine spine = EpubSpine.read(epub());
        EpubSpine.Item two = spine.items.get(1);
        // "<html><body><p>h" is 16 single-byte chars; each of é è ’ é é is multi-byte.
        String prefix = "<html><body><p>héè l’";
        long chars = prefix.length();
        long bytes = prefix.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(bytes, spine.charToByte(two, chars));
        assertEquals(chars, spine.byteToChar(two, bytes));
        // ASCII chapter: identical.
        assertEquals(20, spine.charToByte(spine.items.get(2), 20));
    }

    @Test
    public void nonEpubReturnsNull() throws Exception {
        File file = tmp.newFile("not.epub");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(EpubSpine.read(file));
    }
}
