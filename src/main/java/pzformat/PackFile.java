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
 *   B42 ("PZPK"):  char[4] "PZPK", int32 version, int32 numPages.
 *                  Each page's PNG is length-prefixed.
 *   B41 (legacy):  starts directly at numPages. Each page's PNG has NO length
 *                  prefix — you walk its chunks to IEND — and pages are
 *                  separated by the sentinel 0xDEADBEEF.
 *
 * The entry table is identical in both, AND SO IS the per-page int32 that
 * follows numEntries. An earlier version of this reader believed that int32
 * was PZPK-only; that single wrong assumption is why all 11 retail legacy
 * atlases failed. The reader skipped it, then read its value as the first
 * entry's name length and derailed on byte one.
 *
 * That mattered more than "cosmetic UI art" suggested: JumboTrees1x.pack and
 * JumboTrees2x.pack are in the legacy set, so every tile in
 * vegetation_trees_01 appeared to have no sprite.
 *
 *   [optional] char[4]   "PZPK"
 *   [optional] int32     version
 *   int32                numPages
 *   page * numPages:
 *       lenString        pageName
 *       int32            numEntries
 *       int32            unknown            1 in almost every page observed,
 *                                           but 0 on three pages of UI.pack,
 *                                           so it is NOT a version constant.
 *                                           Carried through opaquely.
 *       entry * numEntries:
 *           lenString    entryName
 *           int32 x, y, w, h, ox, oy, fx, fy
 *       [PZPK]  int32    pngByteLength
 *       byte[]           pngBytes           (a normal PNG, magic 89 50 4E 47)
 *       [legacy] int32   0xDEADBEEF         page separator
 *
 * x,y,w,h  = source rect of the sprite within the page atlas
 * ox,oy    = draw offset (sprite is trimmed; this restores original placement)
 * fx,fy    = full/original untrimmed sprite size
 *
 * The PNG magic check at the end of each page is a strong self-validating
 * anchor: if the page parses and lands exactly on a PNG header, the preceding
 * entry table was read correctly. Use that as your regression assertion.
 *
 * NOTE ON VERIFICATION: round-tripping these byte-identically proves the
 * reader and writer agree, nothing more — the same trap that let the original
 * fixture-only "verification" through. The independent check is the sprite
 * join: after this change SpriteNames should rise well above 45,028 and
 * vegetation_trees_01 tiles should resolve.
 */
public final class PackFile {

    public static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** Sentinel between pages in the legacy layout. */
    public static final int PAGE_SEPARATOR = 0xDEADBEEF;

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
    /** Per-page int32 of unknown meaning. Present in BOTH layouts. */
    public final List<Integer> pageUnknown = new ArrayList<>();
    /**
     * Whether each page was followed by the 0xDEADBEEF separator (legacy only).
     * Recorded rather than derived: the last page of every observed file has
     * none, but a file that terminates with one would otherwise round-trip
     * wrongly, and "absent on the last page" is inference, not measurement.
     */
    public final List<Boolean> pageSeparator = new ArrayList<>();

    public static PackFile read(LE r) {
        PackFile pack = new PackFile();

        // B42 prepends "PZPK" + version. B41 files start straight at the page
        // count. Both still ship in 42.20.
        byte[] lead = r.bytes(4);
        String magic = new String(lead, StandardCharsets.ISO_8859_1);
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
            pack.pageUnknown.add(r.i32());
            if (numEntries < 0 || numEntries > 1_000_000) {
                throw new LE.ParseException("page '" + page.name + "': implausible entry count "
                        + numEntries + " at offset " + (r.pos() - 8));
            }
            for (int j = 0; j < numEntries; j++) {
                Entry e = new Entry();
                e.name = r.lenString();
                e.x = r.i32();  e.y = r.i32();  e.w = r.i32();  e.h = r.i32();
                e.ox = r.i32(); e.oy = r.i32(); e.fx = r.i32(); e.fy = r.i32();
                page.entries.add(e);
            }

            if (pack.pzpk) {
                int pngLen = r.i32();
                int pngStart = r.pos();
                if (pngLen < 0 || pngLen > r.remaining()) {
                    throw new LE.ParseException("page '" + page.name + "': bad PNG length "
                            + pngLen + " at offset " + (pngStart - 4)
                            + " (only " + r.remaining() + " bytes left)");
                }
                page.png = r.bytes(pngLen);
                requirePngMagic(page, pngStart);
                pack.pageSeparator.add(false);
            } else {
                int pngStart = r.pos();
                int pngLen = legacyPngLength(r, pngStart, page.name);
                page.png = r.bytes(pngLen);
                requirePngMagic(page, pngStart);

                // Separator between pages; absent after the last one in every
                // observed file. Consumed only if actually present.
                boolean sep = false;
                if (r.remaining() >= 4) {
                    int save = r.pos();
                    if (r.i32() == PAGE_SEPARATOR) {
                        sep = true;
                    } else {
                        r.seek(save);
                    }
                }
                pack.pageSeparator.add(sep);
            }
            pack.pages.add(page);
        }
        return pack;
    }

    private static void requirePngMagic(Page page, int pngStart) {
        if (!startsWithPngMagic(page.png)) {
            throw new LE.ParseException("page '" + page.name
                    + "': expected PNG magic at offset " + pngStart
                    + " but found " + hexPrefix(page.png)
                    + " -- entry table for this page was misparsed");
        }
    }

    /**
     * Length of the PNG starting at {@code start}, found by walking its chunk
     * headers to IEND. The legacy layout carries no length prefix.
     * Leaves the cursor back at {@code start}.
     */
    private static int legacyPngLength(LE r, int start, String pageName) {
        int p = start + 8;                       // past the 8-byte signature
        while (true) {
            r.seek(p);
            if (r.remaining() < 8) {
                throw new LE.ParseException("page '" + pageName
                        + "': PNG starting at " + start + " has no IEND chunk");
            }
            byte[] hdr = r.bytes(8);
            int len = ((hdr[0] & 0xFF) << 24) | ((hdr[1] & 0xFF) << 16)
                    | ((hdr[2] & 0xFF) << 8) | (hdr[3] & 0xFF);
            String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
            if (len < 0) {
                throw new LE.ParseException("page '" + pageName
                        + "': PNG chunk '" + type + "' at " + p + " has negative length " + len);
            }
            p += 8 + len + 4;                    // header + data + CRC
            if ("IEND".equals(type)) {
                break;
            }
        }
        r.seek(start);
        return p - start;
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
            wI32(out, i < pageUnknown.size() ? pageUnknown.get(i) : 1);
            for (Entry e : page.entries) {
                wStr(out, e.name);
                wI32(out, e.x);  wI32(out, e.y);  wI32(out, e.w);  wI32(out, e.h);
                wI32(out, e.ox); wI32(out, e.oy); wI32(out, e.fx); wI32(out, e.fy);
            }
            if (pzpk) {
                wI32(out, page.png.length);
                out.writeBytes(page.png);
            } else {
                out.writeBytes(page.png);
                boolean sep = i < pageSeparator.size()
                        ? pageSeparator.get(i)
                        : i < pages.size() - 1;
                if (sep) {
                    wI32(out, PAGE_SEPARATOR);
                }
            }
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
