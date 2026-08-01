package pzformat;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Binary `.tiles` tile definitions.
 *
 * Needed because mods ship the binary with no plaintext sibling; vanilla ships
 * both, which is what lets us validate this parser exactly.
 *
 * Structure confirmed by inspection of jumbo_trees.tiles:
 *
 *   char[4]   "tdef"
 *   int32     version           (1)
 *   int32     tilesetCount
 *   tileset:
 *       string  name            ("e_americanhollyJUMBO_1")
 *       string  imageFile       ("e_americanhollyJUMBO_1.png")
 *       int32   width, height   (in tiles)
 *       int32   id
 *       int32   ???             (8 for a 2x4 sheet = width*height)
 *       int32   tileCount       (tiles carrying properties)
 *       tile:
 *           ??? index/xy, then property count, then key/value string pairs
 *
 * All strings are '\n'-terminated. A valueless property (a boolean flag) is
 * stored as a key followed by an empty value.
 *
 * The per-tile prelude is the one part not readable by eye, so it is searched:
 * several candidate shapes are tried and scored against the text-derived truth.
 */
public final class TileBin {

    public static final String MAGIC = "tdef";

    public int version, tilesetCount;
    public final List<TileDefs.Tileset> tilesets = new ArrayList<>();
    public final Map<String, TileDefs.Tile> byName = new LinkedHashMap<>();

    /** How the fields before each tile's property list are laid out. */
    public enum TileShape {
        /** int32 index, int32 propCount */
        INDEX_COUNT(2),
        /** int32 x, int32 y, int32 propCount */
        XY_COUNT(3),
        /** int32 index, int32 unknown, int32 propCount */
        INDEX_UNK_COUNT(3),
        /** int32 propCount only */
        COUNT_ONLY(1);

        public final int ints;
        TileShape(int n) { ints = n; }
    }


    public static TileBin read(Path file, TileShape shape) throws IOException {
        return read(Files.readAllBytes(file), shape, 0);
    }

    public static TileBin read(Path file, TileShape shape, int extraInts) throws IOException {
        return read(Files.readAllBytes(file), shape, extraInts);
    }

    /**
     * @param extraInts unknown int32s between the tileset id and the tile count.
     *                  Searched rather than assumed; 0 for retail 42.20.
     */
    public static TileBin read(byte[] data, TileShape shape, int extraInts) {
        TileBin tb = new TileBin();
        LE r = new LE(data);
        String magic = new String(r.bytes(4), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!MAGIC.equals(magic))
            throw new LE.ParseException("expected \"tdef\", found \"" + magic + "\"");
        tb.version = r.i32();
        tb.tilesetCount = r.i32();
        if (tb.tilesetCount < 0 || tb.tilesetCount > 100_000)
            throw new LE.ParseException("tilesetCount " + tb.tilesetCount);

        for (int i = 0; i < tb.tilesetCount; i++) {
            TileDefs.Tileset ts = new TileDefs.Tileset();
            ts.file = r.cString();
            String image = r.cString();
            if (ts.file.isEmpty() || !printable(ts.file) || !printable(image))
                throw new LE.ParseException("tileset " + i + " has a non-printable name at " + r.pos());
            ts.width = r.i32();
            ts.height = r.i32();
            ts.id = r.i32();
            for (int k = 0; k < extraInts; k++) r.i32();
            int tileCount = r.i32();
            if (ts.width <= 0 || ts.width > 4096 || ts.height <= 0 || ts.height > 4096)
                throw new LE.ParseException("tileset '" + ts.file + "' size "
                        + ts.width + "x" + ts.height);
            if (tileCount < 0 || tileCount > 1_000_000)
                throw new LE.ParseException("tileset '" + ts.file + "' tileCount " + tileCount);

            for (int t = 0; t < tileCount; t++) {
                TileDefs.Tile tile = new TileDefs.Tile();
                int propCount;
                switch (shape) {
                    case INDEX_COUNT -> {
                        int idx = r.i32();
                        tile.index = idx;
                        tile.x = ts.width == 0 ? 0 : idx % ts.width;
                        tile.y = ts.width == 0 ? 0 : idx / ts.width;
                        propCount = r.i32();
                    }
                    case XY_COUNT -> {
                        tile.x = r.i32();
                        tile.y = r.i32();
                        tile.index = tile.y * ts.width + tile.x;
                        propCount = r.i32();
                    }
                    case INDEX_UNK_COUNT -> {
                        int idx = r.i32();
                        r.i32();
                        tile.index = idx;
                        tile.x = ts.width == 0 ? 0 : idx % ts.width;
                        tile.y = ts.width == 0 ? 0 : idx / ts.width;
                        propCount = r.i32();
                    }
                    default -> {
                        tile.index = t;
                        tile.x = ts.width == 0 ? 0 : t % ts.width;
                        tile.y = ts.width == 0 ? 0 : t / ts.width;
                        propCount = r.i32();
                    }
                }
                if (propCount < 0 || propCount > 500)
                    throw new LE.ParseException("tileset '" + ts.file + "' tile " + t
                            + " propCount " + propCount + " at offset " + (r.pos() - 4));
                for (int p = 0; p < propCount; p++) {
                    String k = r.cString();
                    String v = r.cString();
                    if (k.isEmpty() || !printable(k))
                        throw new LE.ParseException("tileset '" + ts.file + "' tile " + t
                                + " property " + p + " has a bad key at " + r.pos());
                    tile.props.put(k, v);
                }
                tile.tileset = ts.file;
                tile.name = ts.file + "_" + tile.index;
                ts.tiles.add(tile);
                tb.byName.put(tile.name, tile);
            }
            tb.tilesets.add(ts);
        }
        if (!r.eof())
            throw new LE.ParseException("trailing data: " + r.remaining() + " bytes unread");
        return tb;
    }

    static boolean printable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------

    /** Try every tile shape against a binary file, scoring on exact consumption. */
    public static void solve(Path binFile, Path textFile) throws Exception {
        System.out.println("== " + binFile.getFileName() + "  ("
                + Files.size(binFile) + " bytes)");
        TileDefs truth = null;
        if (textFile != null && Files.exists(textFile)) {
            truth = new TileDefs();
            truth.parse(textFile);
            System.out.println("truth: " + truth.tilesets.size() + " tilesets, "
                    + truth.byName.size() + " tiles");
        }

        TileBin best = null;
        TileShape bestShape = null;
        int bestExtra = -1;
        byte[] raw = Files.readAllBytes(binFile);
        for (int extra = 0; extra <= 2; extra++) {
            for (TileShape shape : TileShape.values()) {
                try {
                    TileBin tb = read(raw, shape, extra);
                    System.out.printf("   extra=%d %-16s PARSED to exact end: %d tilesets, %d tiles%n",
                            extra, shape, tb.tilesets.size(), tb.byName.size());
                    if (best == null) { best = tb; bestShape = shape; bestExtra = extra; }
                } catch (LE.ParseException e) {
                    System.out.printf("   extra=%d %-16s rejected: %s%n", extra, shape, e.getMessage());
                }
            }
        }

        if (best == null) { System.out.println("\nNo layout parsed cleanly."); return; }
        System.out.println("\n>>> layout: extraInts=" + bestExtra + ", " + bestShape + " <<<");

        if (truth == null) return;

        // Compare against ground truth, field by field.
        int nameMatch = 0, nameMiss = 0, propMatch = 0, propDiff = 0;
        List<String> diffs = new ArrayList<>();
        for (Map.Entry<String, TileDefs.Tile> e : truth.byName.entrySet()) {
            TileDefs.Tile want = e.getValue();
            TileDefs.Tile got = best.byName.get(e.getKey());
            if (got == null) {
                nameMiss++;
                if (diffs.size() < 8) diffs.add("missing tile " + e.getKey());
                continue;
            }
            nameMatch++;
            if (want.props.equals(got.props)) propMatch++;
            else {
                propDiff++;
                if (diffs.size() < 8)
                    diffs.add(e.getKey() + "\n         text: " + want.props
                            + "\n         bin : " + got.props);
            }
        }
        System.out.println("\n=== binary vs text ===");
        System.out.printf("   tiles matched by name : %d / %d%n", nameMatch, truth.byName.size());
        System.out.println("   tiles missing from binary: " + nameMiss);
        System.out.printf("   property maps identical: %d / %d%n", propMatch, nameMatch);
        System.out.println("   property maps differing : " + propDiff);
        for (String d : diffs) System.out.println("      " + d);
        if (nameMiss == 0 && propDiff == 0 && nameMatch == truth.byName.size())
            System.out.println("   => BINARY PARSER CONFIRMED against the text dump");
    }

    /** Run solve() over every .tiles file in a directory. */
    public static void solveAll(Path mediaDir) throws Exception {
        List<Path> bins = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mediaDir, "*.tiles")) {
            for (Path p : ds) bins.add(p);
        }
        Collections.sort(bins);
        System.out.println("binary .tiles files: " + bins.size() + "\n");

        int ok = 0, failed = 0, verified = 0, noTruth = 0;
        long tiles = 0, textTiles = 0, matchedTiles = 0;
        for (Path b : bins) {
            Path txt = b.resolveSibling(b.getFileName() + ".txt");
            try {
                TileBin tb = read(b, TileShape.COUNT_ONLY, 0);
                ok++;
                tiles += tb.byName.size();
                if (Files.exists(txt)) {
                    TileDefs truth = new TileDefs();
                    truth.parse(txt);
                    // The text dump omits propertyless tiles, so the binary
                    // legitimately holds MORE. Check every text tile appears in
                    // the binary with identical properties; extras are expected.
                    int matched = 0, mismatched = 0, absent = 0;
                    String firstBad = null;
                    for (Map.Entry<String, TileDefs.Tile> e : truth.byName.entrySet()) {
                        TileDefs.Tile g = tb.byName.get(e.getKey());
                        if (g == null) {
                            absent++;
                            if (firstBad == null) firstBad = "absent: " + e.getKey();
                        } else if (!g.props.equals(e.getValue().props)) {
                            mismatched++;
                            if (firstBad == null)
                                firstBad = e.getKey() + " text=" + e.getValue().props
                                        + " bin=" + g.props;
                        } else matched++;
                    }
                    boolean same = absent == 0 && mismatched == 0;
                    if (same) verified++;
                    textTiles += truth.byName.size();
                    matchedTiles += matched;
                    System.out.printf("   %-42s %6d tiles  %5d in text  %s%n",
                            b.getFileName(), tb.byName.size(), truth.byName.size(),
                            same ? "ALL MATCH"
                                 : mismatched + " differ, " + absent + " absent  " + firstBad);
                } else {
                    noTruth++;
                    System.out.printf("   %-42s %6d tiles  %5s  (no text sibling — mod case)%n",
                            b.getFileName(), tb.byName.size(), "-");
                }
            } catch (Exception e) {
                failed++;
                System.out.printf("   %-46s FAILED: %s%n", b.getFileName(), e.getMessage());
            }
        }
        System.out.println("\n   parsed " + ok + " / " + bins.size()
                + "   verified against text: " + verified
                + "   no text sibling: " + noTruth + "   failed: " + failed);
        System.out.println("   total tiles: " + tiles);
        System.out.println("   text tiles cross-checked: " + matchedTiles + " / " + textTiles
                + (matchedTiles == textTiles && textTiles > 0
                   ? "   => BINARY PARSER CONFIRMED across every file with a text sibling" : ""));
    }
}
