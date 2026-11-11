package dev.neosync.boox.epub;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Whether the Picco (CrossPoint) can show an EPUB's cover, and fixing it when
 * it can't: CrossPoint draws a black tile for a book without one.
 *
 * The detection mirrors CrossPoint's rules (lib/Epub/Epub.cpp and
 * ContentOpfParser.cpp): a {@code <meta name="cover">} pointing at an image
 * item, else an item with the EPUB 3 {@code cover-image} property, else the
 * first JPG/PNG/GIF on the guide's cover page. Only JPG, PNG and GIF decode.
 *
 * A fix only edits the OPF and may add one image entry: every chapter stays
 * byte for byte the same, so Neo Reader's positions and annotations (chapter
 * offsets) and NeoSync's spine mapping are unaffected.
 */
public final class EpubCover {

    public enum Kind {
        /** The Picco already finds a cover. */
        HAS_COVER,
        /** A picture in the book is the cover but isn't tagged as one. */
        TAG_IMAGE,
        /** No usable picture: a picture of the first page has to be added. */
        NEEDS_SNAPSHOT,
        /** Not a readable EPUB; leave it alone. */
        UNREADABLE
    }

    public static final class Check {
        public final Kind kind;
        /** TAG_IMAGE: manifest id of the picture to tag. */
        public final String imageId;
        /** NEEDS_SNAPSHOT: zip entry of the first page in reading order. */
        public final String firstPageEntry;

        public Check(Kind kind, String imageId, String firstPageEntry) {
            this.kind = kind;
            this.imageId = imageId;
            this.firstPageEntry = firstPageEntry;
        }
    }

    /** A first page with at most this much text is a cover page, not a title page. */
    private static final int COVER_PAGE_MAX_TEXT = 40;
    private static final String ADDED_ID = "neosync-cover";
    private static final String ADDED_NAME = "neosync-cover";

    private EpubCover() {
    }

    private static final class Item {
        String id;
        String href;      // as written in the OPF
        String entry;     // resolved zip entry name
        String mediaType;
        String properties;
    }

    public static Check check(File epub) {
        try (ZipFile zip = new ZipFile(epub)) {
            String opfPath = opfPath(zip);
            if (opfPath == null) return unreadable();
            Document opf = parse(read(zip, opfPath));
            if (opf == null) return unreadable();
            String base = baseOf(opfPath);

            List<Item> manifest = new ArrayList<>();
            NodeList items = opf.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element e = (Element) items.item(i);
                Item item = new Item();
                item.id = e.getAttribute("id");
                item.href = e.getAttribute("href");
                item.entry = resolve(base, item.href);
                item.mediaType = e.getAttribute("media-type");
                item.properties = e.getAttribute("properties");
                manifest.add(item);
            }

            // 1 + 2: what CrossPoint reads from the OPF.
            String coverId = null;
            NodeList metas = opf.getElementsByTagNameNS("*", "meta");
            for (int i = 0; i < metas.getLength(); i++) {
                Element e = (Element) metas.item(i);
                if ("cover".equalsIgnoreCase(e.getAttribute("name"))) {
                    coverId = e.getAttribute("content");
                }
            }
            String coverEntry = null;
            for (Item item : manifest) {
                if (coverId != null && item.id.equals(coverId)
                        && item.mediaType.startsWith("image/")) {
                    coverEntry = item.entry;
                }
            }
            if (coverEntry == null) {
                for (Item item : manifest) {
                    if ((" " + item.properties + " ").contains(" cover-image ")) {
                        coverEntry = item.entry;
                        break;
                    }
                }
            }
            // 3: the guide's cover page, only when the OPF named nothing.
            if (coverEntry == null) {
                NodeList refs = opf.getElementsByTagNameNS("*", "reference");
                for (int i = 0; i < refs.getLength() && coverEntry == null; i++) {
                    Element e = (Element) refs.item(i);
                    String type = e.getAttribute("type");
                    if (!type.equals("cover") && !type.equals("cover-page")) continue;
                    String page = resolve(base, e.getAttribute("href"));
                    byte[] html = read(zip, page);
                    if (html == null) break;
                    String ref = firstImageRef(new String(html, StandardCharsets.UTF_8), true);
                    if (ref != null) coverEntry = resolve(baseOf(page), ref);
                }
            }
            if (coverEntry != null && decodable(coverEntry) && zip.getEntry(coverEntry) != null) {
                return new Check(Kind.HAS_COVER, null, null);
            }

            // The first page in reading order.
            String firstPage = null;
            NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < refs.getLength() && firstPage == null; i++) {
                String idref = ((Element) refs.item(i)).getAttribute("idref");
                for (Item item : manifest) {
                    if (item.id.equals(idref) && zip.getEntry(item.entry) != null) {
                        firstPage = item.entry;
                        break;
                    }
                }
            }

            // A first page that is just a picture: that picture is the cover.
            if (firstPage != null) {
                String html = new String(read(zip, firstPage), StandardCharsets.UTF_8);
                if (visibleTextLength(html) <= COVER_PAGE_MAX_TEXT) {
                    String ref = firstImageRef(html, false);
                    Item item = ref == null ? null
                            : itemForEntry(manifest, resolve(baseOf(firstPage), ref));
                    if (item != null && usableImage(zip, item)) {
                        return new Check(Kind.TAG_IMAGE, item.id, null);
                    }
                }
            }
            // A picture called "cover" somewhere in the book.
            for (Item item : manifest) {
                String name = item.entry.substring(item.entry.lastIndexOf('/') + 1);
                if ((name.toLowerCase(Locale.ROOT).contains("cover")
                        || item.id.toLowerCase(Locale.ROOT).contains("cover"))
                        && usableImage(zip, item)) {
                    return new Check(Kind.TAG_IMAGE, item.id, null);
                }
            }
            return firstPage != null ? new Check(Kind.NEEDS_SNAPSHOT, null, firstPage) : unreadable();
        } catch (Exception e) {
            return unreadable();
        }
    }

    /** {title, author} from the OPF, either may be empty; null when unreadable. */
    public static String[] titleAndAuthor(File epub) {
        try (ZipFile zip = new ZipFile(epub)) {
            String opfPath = opfPath(zip);
            Document opf = opfPath == null ? null : parse(read(zip, opfPath));
            if (opf == null) return null;
            return new String[]{firstText(opf, "title"), firstText(opf, "creator")};
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(Document doc, String dcName) {
        NodeList list = doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", dcName);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    /** Writes a copy of src to dst with check.imageId tagged as the cover. */
    public static void tagExisting(File src, File dst, Check check) throws IOException {
        if (check.kind != Kind.TAG_IMAGE) throw new IllegalArgumentException("nothing to tag");
        rewrite(src, dst, check.imageId, null);
    }

    /** Writes a copy of src to dst with this JPEG added as the cover. */
    public static void addImage(File src, File dst, byte[] jpeg) throws IOException {
        rewrite(src, dst, null, jpeg);
    }

    // --- Writing ----------------------------------------------------------------

    private static void rewrite(File src, File dst, String tagId, byte[] newJpeg) throws IOException {
        try (ZipFile zip = new ZipFile(src)) {
            String opfPath = opfPath(zip);
            byte[] opfBytes = opfPath == null ? null : read(zip, opfPath);
            if (opfBytes == null) throw new IOException("no OPF");
            String opf = new String(opfBytes, StandardCharsets.UTF_8);

            String imageEntry = null;
            if (newJpeg != null) {
                String base = baseOf(opfPath);
                String id = ADDED_ID;
                String name = ADDED_NAME + ".jpg";
                for (int n = 2; opf.contains("id=\"" + id + "\"") || zip.getEntry(base + name) != null; n++) {
                    id = ADDED_ID + "-" + n;
                    name = ADDED_NAME + "-" + n + ".jpg";
                }
                opf = insertBeforeClose(opf, "manifest",
                        "item id=\"" + id + "\" href=\"" + name + "\" media-type=\"image/jpeg\"/>");
                imageEntry = base + name;
                tagId = id;
            }
            opf = setCoverMeta(opf, tagId);

            File tmp = new File(dst.getPath() + ".part");
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(tmp))) {
                writeStored(out, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
                Enumeration<? extends ZipEntry> entries = zip.entries();
                byte[] buf = new byte[32768];
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().equals("mimetype")) continue;
                    out.putNextEntry(new ZipEntry(e.getName()));
                    if (e.getName().equals(opfPath)) {
                        out.write(opf.getBytes(StandardCharsets.UTF_8));
                    } else if (!e.isDirectory()) {
                        try (InputStream in = zip.getInputStream(e)) {
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                    out.closeEntry();
                }
                if (imageEntry != null) {
                    writeStored(out, imageEntry, newJpeg);
                }
            }
            if (!tmp.renameTo(dst)) {
                copy(tmp, dst);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static final Pattern COVER_META = Pattern.compile(
            "<([A-Za-z][\\w.-]*:)?meta\\b(?=[^>]*\\bname\\s*=\\s*[\"']cover[\"'])[^>]*?(/>|>\\s*</\\1?meta\\s*>|>)",
            Pattern.CASE_INSENSITIVE);

    /** Replaces any cover meta by one pointing at id. */
    static String setCoverMeta(String opf, String id) {
        String cleaned = COVER_META.matcher(opf).replaceAll("");
        return insertBeforeClose(cleaned, "metadata",
                "meta name=\"cover\" content=\"" + id + "\"/>");
    }

    /** Inserts "<prefix:" + element before the closing tag, reusing its namespace prefix. */
    static String insertBeforeClose(String opf, String parent, String elementAfterLt) {
        Matcher m = Pattern.compile("</([A-Za-z][\\w.-]*:)?" + parent + "\\s*>").matcher(opf);
        if (!m.find()) throw new IllegalStateException("no </" + parent + ">");
        String prefix = m.group(1) == null ? "" : m.group(1);
        return opf.substring(0, m.start()) + "<" + prefix + elementAfterLt + opf.substring(m.start());
    }

    private static void writeStored(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }

    public static void copy(File from, File to) throws IOException {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // --- Reading ----------------------------------------------------------------

    private static Check unreadable() {
        return new Check(Kind.UNREADABLE, null, null);
    }

    private static boolean decodable(String entry) {
        String e = entry.toLowerCase(Locale.ROOT);
        return e.endsWith(".jpg") || e.endsWith(".jpeg") || e.endsWith(".png") || e.endsWith(".gif");
    }

    private static boolean usableImage(ZipFile zip, Item item) {
        return item.mediaType.startsWith("image/") && decodable(item.entry)
                && zip.getEntry(item.entry) != null;
    }

    private static Item itemForEntry(List<Item> manifest, String entry) {
        for (Item item : manifest) {
            if (item.entry.equals(entry)) return item;
        }
        return null;
    }

    private static final Pattern IMG_SRC = Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XLINK = Pattern.compile("xlink:href\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    /**
     * The first JPG/PNG/GIF the page shows. crossPointOrder looks at SVG
     * xlink:href first, as CrossPoint's guide fallback does.
     */
    static String firstImageRef(String html, boolean crossPointOrder) {
        if (crossPointOrder) {
            for (Pattern p : new Pattern[]{XLINK, ANY_SRC}) {
                Matcher m = p.matcher(html);
                while (m.find()) {
                    if (decodable(m.group(1))) return m.group(1);
                }
            }
            return null;
        }
        // Document order: whichever picture comes first on the page.
        int best = Integer.MAX_VALUE;
        String found = null;
        for (Pattern p : new Pattern[]{IMG_SRC, XLINK}) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                if (decodable(m.group(1))) {
                    if (m.start() < best) {
                        best = m.start();
                        found = m.group(1);
                    }
                    break;
                }
            }
        }
        return found;
    }

    private static final Pattern ANY_SRC = Pattern.compile("\\bsrc\\s*=\\s*\"([^\"]+)\"");

    /** How many characters of text a reader would see on the page. */
    static int visibleTextLength(String html) {
        String s = html.replaceAll("(?is)<head\\b.*?</head>", " ")
                .replaceAll("(?is)<(script|style)\\b.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replaceAll("&nbsp;|&#160;|&#xa0;", " ")
                .replaceAll("&[#\\w]+;", "x")
                .replaceAll("\\s+", "");
        return s.codePointCount(0, s.length());
    }

    private static String opfPath(ZipFile zip) throws IOException {
        Document container;
        try {
            container = parse(read(zip, "META-INF/container.xml"));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("bad container.xml", e);
        }
        if (container == null) return null;
        NodeList rootfiles = container.getElementsByTagNameNS("*", "rootfile");
        if (rootfiles.getLength() == 0) return null;
        return ((Element) rootfiles.item(0)).getAttribute("full-path");
    }

    private static String baseOf(String entry) {
        return entry.contains("/") ? entry.substring(0, entry.lastIndexOf('/') + 1) : "";
    }

    static String resolve(String base, String href) {
        String path = href.split("#")[0];
        try {
            path = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        return EpubSpine.normalize(base + path);
    }

    public static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (InputStream in = zip.getInputStream(entry)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Document parse(byte[] xml) throws Exception {
        if (xml == null) return null;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
        }
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
