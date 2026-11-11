package dev.neosync.boox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import dev.neosync.boox.epub.EpubCover;
import dev.neosync.boox.epub.EpubSpine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The cover rules mirror CrossPoint's (lib/Epub/Epub.cpp, ContentOpfParser):
 * a book it would show black must be detected, and a fixed book must pass
 * the same rules without any chapter changing (positions and Neo Reader
 * annotations are chapter offsets).
 */
public class EpubCoverTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String TEXT_PAGE =
            "<html><head><title>t</title></head><body><p>Chapitre premier. Il était une fois "
                    + "un livre sans couverture.</p></body></html>";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

    private static String opf(String metadata, String manifest, String spine, String guide) {
        return "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title>"
                + metadata + "</metadata><manifest>" + manifest + "</manifest><spine>" + spine
                + "</spine>" + guide + "</package>";
    }

    private File epub(String name, String opf, Map<String, byte[]> files) throws Exception {
        File file = tmp.newFile(name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            ZipEntry mt = new ZipEntry("mimetype");
            byte[] mtBytes = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            mt.setMethod(ZipEntry.STORED);
            mt.setSize(mtBytes.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(mtBytes);
            mt.setCrc(crc.getValue());
            zip.putNextEntry(mt);
            zip.write(mtBytes);
            zip.closeEntry();
            put(zip, "META-INF/container.xml", ("<?xml version=\"1.0\"?><container "
                    + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                    + "<rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>")
                    .getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                put(zip, e.getKey(), e.getValue());
            }
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static Map<String, byte[]> files(String... nameThenText) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenText.length; i += 2) {
            m.put(nameThenText[i], nameThenText[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    private static Map<String, byte[]> read(File file) throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                m.put(e.getName(), out.toByteArray());
            }
        }
        return m;
    }

    /** Every entry of the original is still there, byte for byte, except the OPF. */
    private static void assertChaptersUntouched(File before, File after) throws Exception {
        Map<String, byte[]> a = read(before);
        Map<String, byte[]> b = read(after);
        for (Map.Entry<String, byte[]> e : a.entrySet()) {
            if (e.getKey().endsWith(".opf")) continue;
            assertArrayEquals(e.getKey(), e.getValue(), b.get(e.getKey()));
        }
        try (ZipFile zip = new ZipFile(after)) {
            ZipEntry first = zip.entries().nextElement();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
        }
        assertEquals(EpubSpine.read(before).totalBytes, EpubSpine.read(after).totalBytes);
    }

    @Test
    public void metaCoverPointingAtAnImageCounts() throws Exception {
        File f = epub("a.epub", opf("<meta name=\"cover\" content=\"c\"/>",
                "<item id=\"c\" href=\"img/c.jpg\" media-type=\"image/jpeg\"/>"
                        + "<item id=\"p1\" href=\"p1.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"p1\"/>", ""), files("OEBPS/p1.xhtml", TEXT_PAGE, "OEBPS/img/c.jpg", "j"));
        assertEquals(EpubCover.Kind.HAS_COVER, EpubCover.check(f).kind);
    }

    @Test
    public void epub3CoverImagePropertyCounts() throws Exception {
        File f = epub("a.epub", opf("",
                "<item id=\"x\" href=\"c.png\" media-type=\"image/png\" properties=\"cover-image\"/>"
                        + "<item id=\"p1\" href=\"p1.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"p1\"/>", ""), files("OEBPS/p1.xhtml", TEXT_PAGE, "OEBPS/c.png", "p"));
        assertEquals(EpubCover.Kind.HAS_COVER, EpubCover.check(f).kind);
    }

    @Test
    public void guideCoverPageWithAnImageCounts() throws Exception {
        File f = epub("a.epub", opf("",
                "<item id=\"cp\" href=\"cover.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"p1\" href=\"p1.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"cp\"/><itemref idref=\"p1\"/>",
                "<guide><reference type=\"cover\" href=\"cover.xhtml\"/></guide>"),
                files("OEBPS/cover.xhtml", "<html><body><img src=\"i/front.jpeg\"/></body></html>",
                        "OEBPS/p1.xhtml", TEXT_PAGE, "OEBPS/i/front.jpeg", "j"));
        assertEquals(EpubCover.Kind.HAS_COVER, EpubCover.check(f).kind);
    }

    @Test
    public void coverImageMissingFromTheZipDoesNotCount() throws Exception {
        File f = epub("a.epub", opf("<meta name=\"cover\" content=\"c\"/>",
                "<item id=\"c\" href=\"img/c.jpg\" media-type=\"image/jpeg\"/>"
                        + "<item id=\"p1\" href=\"p1.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"p1\"/>", ""), files("OEBPS/p1.xhtml", TEXT_PAGE));
        assertEquals(EpubCover.Kind.NEEDS_SNAPSHOT, EpubCover.check(f).kind);
    }

    @Test
    public void webpCoverIsNotReadableByThePicco() throws Exception {
        File f = epub("a.epub", opf("<meta name=\"cover\" content=\"c\"/>",
                "<item id=\"c\" href=\"c.webp\" media-type=\"image/webp\"/>"
                        + "<item id=\"p1\" href=\"p1.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"p1\"/>", ""), files("OEBPS/p1.xhtml", TEXT_PAGE));
        assertEquals(EpubCover.Kind.NEEDS_SNAPSHOT, EpubCover.check(f).kind);
    }

    /** "Sufism": meta points at a missing id, the first page is just cover.jpg. */
    @Test
    public void untaggedCoverPageImageIsTaggedInPlace() throws Exception {
        File f = epub("sufism.epub", opf("<meta content=\"cover-image\" name=\"cover\"/>",
                "<item href=\"00_cover.html\" id=\"body01\" media-type=\"application/xhtml+xml\"/>"
                        + "<item href=\"image/cover.jpg\" id=\"img3\" media-type=\"image/jpeg\"/>"
                        + "<item href=\"p1.xhtml\" id=\"p1\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"body01\"/><itemref idref=\"p1\"/>", ""),
                files("OEBPS/00_cover.html", "<html><head><title>Sufism</title></head><body>"
                                + "<p class=\"cover\"><img alt=\"\" src=\"image/cover.jpg\"/></p></body></html>",
                        "OEBPS/image/cover.jpg", "jpeg", "OEBPS/p1.xhtml", TEXT_PAGE));
        EpubCover.Check check = EpubCover.check(f);
        assertEquals(EpubCover.Kind.TAG_IMAGE, check.kind);
        assertEquals("img3", check.imageId);

        File out = new File(tmp.getRoot(), "sufism-fixed.epub");
        EpubCover.tagExisting(f, out, check);
        assertEquals(EpubCover.Kind.HAS_COVER, EpubCover.check(out).kind);
        assertChaptersUntouched(f, out);
        String fixedOpf = new String(read(out).get("OEBPS/content.opf"), StandardCharsets.UTF_8);
        assertTrue(fixedOpf, !fixedOpf.contains("content=\"cover-image\""));
    }

    /** "Le Banquet": the first page has a picture but also a lot of text. */
    @Test
    public void titlePageWithTextGetsASnapshotNotItsIllustration() throws Exception {
        File f = epub("banquet.epub", opf("",
                "<item id=\"id001\" href=\"001.html\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"img1\" href=\"images/img1.png\" media-type=\"image/png\"/>",
                "<itemref idref=\"id001\"/>", ""),
                files("OEBPS/001.html", "<html><head><title>Page titre</title></head><body>"
                                + "<p><b>Platon</b></p><p>&nbsp;</p><p><img src=\"images/img1.png\"/></p>"
                                + "<p><b>Le Banquet</b></p><p>Ou&nbsp;: De l&rsquo;amour, genre moral</p>"
                                + "<p>Traduction et notice par Émile Chambry</p></body></html>",
                        "OEBPS/images/img1.png", "png"));
        EpubCover.Check check = EpubCover.check(f);
        assertEquals(EpubCover.Kind.NEEDS_SNAPSHOT, check.kind);
        assertEquals("OEBPS/001.html", check.firstPageEntry);
    }

    @Test
    public void manifestImageNamedCoverIsTagged() throws Exception {
        File f = epub("a.epub", opf("",
                "<item id=\"p1\" href=\"p1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"pic\" href=\"Images/Cover.JPG\" media-type=\"image/jpeg\"/>",
                "<itemref idref=\"p1\"/>", ""),
                files("OEBPS/p1.xhtml", TEXT_PAGE, "OEBPS/Images/Cover.JPG", "jpeg"));
        EpubCover.Check check = EpubCover.check(f);
        assertEquals(EpubCover.Kind.TAG_IMAGE, check.kind);
        assertEquals("pic", check.imageId);
    }

    /** Freud / the manuscript: text only, so a picture of page one is added. */
    @Test
    public void textOnlyBookGetsAnAddedCover() throws Exception {
        File f = epub("freud.epub", opf("<meta name=\"generator\" content=\"x\"/>",
                "<item id=\"p1\" href=\"text/p1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                        + "<item id=\"p2\" href=\"text/p2.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"p1\"/><itemref idref=\"p2\"/>", ""),
                files("OEBPS/text/p1.xhtml", TEXT_PAGE, "OEBPS/text/p2.xhtml", TEXT_PAGE));
        EpubCover.Check check = EpubCover.check(f);
        assertEquals(EpubCover.Kind.NEEDS_SNAPSHOT, check.kind);
        assertEquals("OEBPS/text/p1.xhtml", check.firstPageEntry);

        File out = new File(tmp.getRoot(), "freud-fixed.epub");
        EpubCover.addImage(f, out, JPEG);
        EpubCover.Check after = EpubCover.check(out);
        assertEquals(EpubCover.Kind.HAS_COVER, after.kind);
        assertChaptersUntouched(f, out);
        assertArrayEquals(JPEG, read(out).get("OEBPS/neosync-cover.jpg"));
    }

    @Test
    public void prefixedOpfElementsAreHandled() throws Exception {
        String opf = "<?xml version=\"1.0\"?><opf:package xmlns:opf=\"http://www.idpf.org/2007/opf\" "
                + "version=\"2.0\"><opf:metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>T</dc:title></opf:metadata><opf:manifest>"
                + "<opf:item id=\"p1\" href=\"p1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "</opf:manifest><opf:spine><opf:itemref idref=\"p1\"/></opf:spine></opf:package>";
        File f = epub("a.epub", opf, files("OEBPS/p1.xhtml", TEXT_PAGE));
        File out = new File(tmp.getRoot(), "a-fixed.epub");
        EpubCover.addImage(f, out, JPEG);
        assertEquals(EpubCover.Kind.HAS_COVER, EpubCover.check(out).kind);
    }

    @Test
    public void notAnEpubIsUnreadable() throws Exception {
        File f = tmp.newFile("x.epub");
        assertNotNull(EpubCover.check(f));
        assertEquals(EpubCover.Kind.UNREADABLE, EpubCover.check(f).kind);
    }
}
