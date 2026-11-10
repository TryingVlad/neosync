package dev.neosync.boox.epub;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The reading-order table of an EPUB: for each spine item its manifest id and
 * the cumulative uncompressed byte range it covers.
 *
 * This is the coordinate system both readers share. NeoReader (firmware 4.2)
 * stores chapterStartPosition/chapterEndPosition as exactly these cumulative
 * byte offsets, and CrossPoint computes its sync percentage as
 * (cumulative spine bytes before the position) / (total spine bytes). Mapping
 * through it avoids page counts, which differ per device and layout.
 */
public final class EpubSpine {

    public static final class Item {
        public final int index;
        public final String id;
        public final long start; // cumulative bytes before this item
        public final long end;   // start + uncompressed size
        final String entryName;  // zip entry, null when missing

        Item(int index, String id, long start, long end, String entryName) {
            this.index = index;
            this.id = id;
            this.entryName = entryName;
            this.start = start;
            this.end = end;
        }

        public long size() {
            return end - start;
        }
    }

    public final List<Item> items;
    public final long totalBytes;

    private final File file;

    private EpubSpine(File file, List<Item> items, long totalBytes) {
        this.file = file;
        this.items = items;
        this.totalBytes = totalBytes;
    }

    /** Returns null when the file is not a readable EPUB. */
    public static EpubSpine read(File epub) {
        try (ZipFile zip = new ZipFile(epub)) {
            Document container = parse(zip, "META-INF/container.xml");
            if (container == null) return null;
            NodeList rootfiles = container.getElementsByTagNameNS("*", "rootfile");
            if (rootfiles.getLength() == 0) return null;
            String opfPath = ((Element) rootfiles.item(0)).getAttribute("full-path");
            Document opf = parse(zip, opfPath);
            if (opf == null) return null;
            String base = opfPath.contains("/")
                    ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

            Map<String, String> manifest = new HashMap<>();
            NodeList manifestItems = opf.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < manifestItems.getLength(); i++) {
                Element e = (Element) manifestItems.item(i);
                manifest.put(e.getAttribute("id"), e.getAttribute("href"));
            }

            List<Item> items = new ArrayList<>();
            long offset = 0;
            NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < refs.getLength(); i++) {
                String id = ((Element) refs.item(i)).getAttribute("idref");
                String href = manifest.get(id);
                long size = 0;
                String entryName = null;
                if (href != null) {
                    ZipEntry entry = zip.getEntry(normalize(base + URLDecoder.decode(href, "UTF-8")));
                    if (entry != null) {
                        size = entrySize(zip, entry);
                        entryName = entry.getName();
                    }
                }
                items.add(new Item(i, id, offset, offset + size, entryName));
                offset += size;
            }
            if (items.isEmpty() || offset <= 0) return null;
            return new EpubSpine(epub, items, offset);
        } catch (Exception e) {
            return null;
        }
    }

    /** Fraction 0..1 of the book for a byte offset inside a spine item. */
    public double percentage(int itemIndex, long offsetInItem) {
        if (itemIndex < 0 || itemIndex >= items.size()) return 0;
        Item item = items.get(itemIndex);
        long within = Math.max(0, Math.min(item.size(), offsetInItem));
        return (double) (item.start + within) / totalBytes;
    }

    /** The spine item containing the given fraction of the book. */
    public Item itemAt(double percentage) {
        long target = globalOffset(percentage);
        for (Item item : items) {
            if (target < item.end) return item;
        }
        return items.get(items.size() - 1);
    }

    public long globalOffset(double percentage) {
        double pct = Math.max(0.0, Math.min(1.0, percentage));
        return Math.min(totalBytes - 1, (long) (pct * totalBytes));
    }

    /**
     * NeoReader addresses text inside a chapter by character offset (UTF-8
     * decoded code points), CrossPoint by byte offset. Converts a character
     * offset in the item to a byte offset; returns the input unchanged when
     * the chapter cannot be read (identical for ASCII text).
     */
    public long charToByte(Item item, long charOffset) {
        byte[] data = readItem(item);
        if (data == null) return charOffset;
        return charToByte(data, charOffset);
    }

    /** Inverse of {@link #charToByte(Item, long)}. */
    public long byteToChar(Item item, long byteOffset) {
        byte[] data = readItem(item);
        if (data == null) return byteOffset;
        return byteToChar(data, byteOffset);
    }

    static long charToByte(byte[] utf8, long charOffset) {
        long chars = 0;
        for (int i = 0; i < utf8.length; i++) {
            if ((utf8[i] & 0xC0) != 0x80) { // first byte of a code point
                if (chars == charOffset) return i;
                chars++;
            }
        }
        return utf8.length;
    }

    static long byteToChar(byte[] utf8, long byteOffset) {
        long chars = 0;
        long limit = Math.min(utf8.length, Math.max(0, byteOffset));
        for (int i = 0; i < limit; i++) {
            if ((utf8[i] & 0xC0) != 0x80) chars++;
        }
        return chars;
    }

    private byte[] readItem(Item item) {
        if (item.entryName == null) return null;
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry(item.entryName);
            if (entry == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = zip.getInputStream(entry)) {
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static long entrySize(ZipFile zip, ZipEntry entry) throws Exception {
        long size = entry.getSize();
        if (size >= 0) return size;
        long n = 0;
        byte[] buf = new byte[8192];
        try (InputStream in = zip.getInputStream(entry)) {
            int r;
            while ((r = in.read(buf)) > 0) n += r;
        }
        return n;
    }

    private static Document parse(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (Exception ignored) {
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    /** Resolves "a/b/../c" and "./" segments; zip entry names never contain them. */
    static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else {
                out.add(part);
            }
        }
        return String.join("/", out);
    }
}
