package pzformat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Project Zomboid .pack texture atlas.
 *
 * TWO LAYOUTS ship in 42.20 and both are handled:
 *
 *   B42 ("PZPK"):  char[4] "PZPK", int32 version, int32 numPages,
 *                  and one extra int32 per page after its entry count.
 *   B41 (legacy):  starts directly at numPages, no per-page extra.
 *
 * The entry layout is identical in both.
 *
 * NOTE: an earlier version of this reader handled only the legacy layout and
 * was "verified" solely against fixtures this project generated itself — which
 * proves the reader matches the writer and nothing else. Every retail 42.20
 * atlas failed. Fixtures cannot substitute for real files.
 *
 *   [optional] char[4]   "PZPK"
 *   [optional] int32     version
 *   int32                numPages
 *   page * numPages:
 *       lenString        pageName
 *       int32            numEntries
 *       [PZPK] int32     unknown (1 in every page observed)
 *       entry * numEntries:
 *           lenString    entryName
 *           int32 x, y, w, h, ox, oy, fx, fy
 *       int32            pngByteLength
 *       byte[]           pngBytes           (a normal PNG, magic 89 50 4E 47)
 *
 * x,y,w,h  = source rect of the sprite within the page atlas
 * ox,oy    = draw offset (sprite is trimmed; this restores original placement)
 * fx,fy    = full/original untrimmed sprite size
 *
 * The PNG magic check at the end of each page is a strong self-validating
 * anchor: if the page parses and lands exactly on a PNG header, the preceding
 * entry table was read correctly. Use that as your regression assertion.
 */
public final class PackFile {

    public static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    public final List<Page> pages = new ArrayList<>();

    public static final class Page {
        public String name;
        public final List<Entry> entries = new ArrayList<>();
        public byte[] png;

        public int pngWidth()  { return png == null ? -1 : beInt(png, 16); }
        public int pngHeight() { return png == null ? -1 : beInt(png, 20); }

        private static int beInt(byte[] a, int o) {
            return ((a[o] & 0xFF) << 24) | ((a[o + 1] & 0xFF) << 16)
                 | ((a[o + 2] & 0xFF) << 8) | (a[o + 3] & 0xFF);
        }
    }

    public static final class Entry {
        public String name;
        public int x, y, w, h, ox, oy, fx, fy;

        @Override public String toString() {
            return String.format("%s [%d,%d %dx%d] off(%d,%d) full(%dx%d)",
                    name, x, y, w, h, ox, oy, fx, fy);
        }
    }

    public static PackFile read(Path file) throws IOException {
        return read(LE.of(file));
    }

    public static final String MAGIC = "PZPK";

    /** True when the file carried the B42 "PZPK" header. */
    public boolean pzpk;
    public int version;
    /** Per-page int32 of unknown meaning, present only in PZPK files. */
    public final List<Integer> pageUnknown = new ArrayList<>();

    public static PackFile read(LE r) {
        PackFile pack = new PackFile();

        // B42 prepends "PZPK" + version and adds one int32 per page. B41 files
        // start straight at the page count. Both still ship in 42.20.
        byte[] lead = r.bytes(4);
        String magic = new String(lead, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (MAGIC.equals(magic)) {
            pack.pzpk = true;
            pack.version = r.i32();
        } else {
            r.seek(0);
        }

        int numPages = r.i32();
        if (numPages < 0 || numPages > 100_000) {
            throw new LE.ParseException("implausible page count " + numPages
                    + " -- this is probably not a .pack file, or the layout changed");
        }
        for (int i = 0; i < numPages; i++) {
            Page page = new Page();
            page.name = r.lenString();
            int numEntries = r.i32();
            if (pack.pzpk) pack.pageUnknown.add(r.i32());
            if (numEntries < 0 || numEntries > 1_000_000) {
                throw new LE.ParseException("page '" + page.name + "': implausible entry count "
                        + numEntries + " at offset " + (r.pos() - 4));
            }
            for (int j = 0; j < numEntries; j++) {
                Entry e = new Entry();
                e.name = r.lenString();
                e.x = r.i32();  e.y = r.i32();  e.w = r.i32();  e.h = r.i32();
                e.ox = r.i32(); e.oy = r.i32(); e.fx = r.i32(); e.fy = r.i32();
                page.entries.add(e);
            }
            int pngLen = r.i32();
            int pngStart = r.pos();
            if (pngLen < 0 || pngLen > r.remaining()) {
                throw new LE.ParseException("page '" + page.name + "': bad PNG length "
                        + pngLen + " at offset " + (pngStart - 4)
                        + " (only " + r.remaining() + " bytes left)");
            }
            page.png = r.bytes(pngLen);
            if (!startsWithPngMagic(page.png)) {
                throw new LE.ParseException("page '" + page.name + "': expected PNG magic at offset "
                        + pngStart + " but found " + hexPrefix(page.png)
                        + " -- entry table for this page was misparsed");
            }
            pack.pages.add(page);
        }
        return pack;
    }

    public byte[] write() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (pzpk) {
            out.writeBytes(MAGIC.getBytes(StandardCharsets.ISO_8859_1));
            wI32(out, version);
        }
        wI32(out, pages.size());
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            wStr(out, page.name);
            wI32(out, page.entries.size());
            if (pzpk) wI32(out, i < pageUnknown.size() ? pageUnknown.get(i) : 1);
            for (Entry e : page.entries) {
                wStr(out, e.name);
                wI32(out, e.x);  wI32(out, e.y);  wI32(out, e.w);  wI32(out, e.h);
                wI32(out, e.ox); wI32(out, e.oy); wI32(out, e.fx); wI32(out, e.fy);
            }
            wI32(out, page.png.length);
            out.writeBytes(page.png);
        }
        return out.toByteArray();
    }

    /** Extract every page atlas as PageName.png into dir. */
    public void extractPages(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (Page p : pages) {
            Files.write(dir.resolve(sanitize(p.name) + ".png"), p.png);
        }
    }

    private static boolean startsWithPngMagic(byte[] a) {
        if (a.length < PNG_MAGIC.length) return false;
        for (int i = 0; i < PNG_MAGIC.length; i++) if (a[i] != PNG_MAGIC[i]) return false;
        return true;
    }

    private static String hexPrefix(byte[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, a.length); i++) sb.append(String.format("%02X ", a[i]));
        return sb.toString().trim();
    }

    static String sanitize(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static void wI32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >> 8) & 0xFF); o.write((v >> 16) & 0xFF); o.write((v >> 24) & 0xFF);
    }

    private static void wStr(ByteArrayOutputStream o, String s) {
        byte[] raw = s.getBytes(StandardCharsets.ISO_8859_1);
        wI32(o, raw.length);
        o.writeBytes(raw);
    }
}
