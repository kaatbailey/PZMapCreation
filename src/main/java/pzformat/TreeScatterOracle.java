package pzformat;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Java side of the port-step-7 oracle for {@link TreePalette} and
 * {@link TreeScatter}. Harness Pattern B (STATE §38): a canonical text digest
 * that must be BYTE-IDENTICAL to treescatter_oracle.cpp's.
 *
 * ----------------------------------------------------------------------
 * CORPUS DESIGN
 * ----------------------------------------------------------------------
 *
 * VIN  Constants and the band table, emitted first. Densities and P_STUMP go
 *      out as RAW DOUBLE BITS, never as formatted text — comparing printed
 *      doubles tests two printf implementations rather than two ports.
 *
 * PI   `Integer.parseInt(v.trim())` acceptance, in isolation. TreePalette
 *      reads the `tree` property through it inside a try/catch that continues
 *      on NumberFormatException, so exactly WHICH strings throw decides which
 *      tiles enter the palette.
 *
 *      MEASURED 2026-09-02, and it is not what a C++ author would guess:
 *      "007" -> 7, "0x2" -> throw, "2\u0000" -> 2 (trim strips every char
 *      <= U+0020, including NUL), "\u000b2" -> 2 (vertical tab likewise),
 *      "1\u00a0" -> throw (NBSP is above U+0020, so trim leaves it).
 *
 *      ONE DIVERGENCE IS KNOWN AND DELIBERATE. parseInt goes through
 *      Character.digit, so "\u0661\u0662" (Arabic-Indic) parses as 12. The C++
 *      side uses std::from_chars and rejects it. Reproducing Character.digit's
 *      full Unicode table to service a case that cannot occur in tile data is
 *      not worth it — but the bound is recorded rather than assumed, and the
 *      ASCII_TREE_VALUES check below is the falsifier: it asserts that no
 *      `tree` value in the corpus is non-ASCII. Run it against the vanilla
 *      tile index too, once, when the PZ install is to hand.
 *
 * TP   Palette construction over synthetic tile sets. The filter is a chain —
 *      sheet name, then Kind.VEGETATION, then a non-empty `tree` property,
 *      then `solid` — and each link gets a tile that fails only at that link.
 *      Kind is itself a chain (`TileIndex.kindOf:54-64`), so a tile carrying
 *      BOTH `tree` and `wall` classifies WALL and is dropped; that tile is in
 *      the corpus because no real sheet would contain it (§40).
 *
 * TN   `tilesNear` over hand-built palettes at sizes -20..20. The fallback
 *      walks d=1..8 checking LOW BEFORE HIGH, so a palette holding both
 *      size-1 and size-3 resolves size-2 to size-1. Asymmetric, easy to
 *      transpose, and invisible in production where only size 2 exists.
 *
 * BF   `bandFor` at every boundary and one either side, plus Integer.MAX_VALUE
 *      — the no-structure case, where every square lands in the last band.
 *
 * DS   `distanceToStructure` over rasters including one with NO structure at
 *      all (every square stays Integer.MAX_VALUE and the BFS returns early)
 *      and rasters that are wider than tall AND taller than wide, because the
 *      queue encodes `x * h + y` and using `w` would transpose silently on any
 *      non-square input.
 *
 * TC   `tooClose` against all four corners and all four edges, where the
 *      SPACING window clamps.
 *
 * PL   `place` end to end: placement grid, the log lines Java prints to stdout,
 *      and an RNG STREAM FINGERPRINT after each pass. The draw count is the
 *      contract — `tp.hasStump && rng.nextDouble() < P_STUMP` short-circuits,
 *      so a palette with no stump draws a different number of times for EVERY
 *      square. A port that emits identical tiles while drawing a different
 *      number of times diverges here and nowhere else.
 *
 * NOT COVERED, AND SAID SO RATHER THAN IMPLIED. `nextInt`'s rejection loop is
 * unreachable from this unit. The bound is the palette size; production uses 8,
 * a power of two, which takes the high-bits fast path. Rejection probability is
 * (2^31 mod bound)/2^31 — for every palette size in this corpus that is below
 * 1e-5, so the expected number of rejections across the whole run is far below
 * one. §41's palettes corpus expected 0.02 rejections, never fired one, and
 * passed on luck; this states the expectation instead of repeating that.
 * The rejection loop is covered by `pz_rng_oracle` (step 2) with adversarial
 * bounds, which is the right place for it.
 *
 *   java -cp out pzformat.TreeScatterOracle /tmp/ts.java.txt
 */
public final class TreeScatterOracle {

    private TreeScatterOracle() { }

    private static final StringBuilder OUT = new StringBuilder();

    private static void line(String s) { OUT.append(s).append('\n'); }

    private static String bits(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    private static long fnv(long h, long v) {
        for (int i = 0; i < 8; i++) { h ^= (v >>> (i * 8)) & 0xffL; h *= 0x100000001b3L; }
        return h;
    }

    private static final long FNV_INIT = 0xcbf29ce484222325L;

    // ------------------------------------------------------------------

    private static TileDefs.Tile tile(String name, String tileset, String... kv) {
        TileDefs.Tile t = new TileDefs.Tile();
        t.name = name;
        t.tileset = tileset;
        for (int i = 0; i + 1 < kv.length; i += 2) t.props.put(kv[i], kv[i + 1]);
        return t;
    }

    private static void vin() {
        line("VIN\tCONST\tCLEAR\t" + TreeScatter.CLEAR);
        line("VIN\tCONST\tSPACING\t" + TreeScatter.SPACING);
        line("VIN\tCONST\tP_STUMP\t" + bits(TreeScatter.P_STUMP));
        line("VIN\tSHEET\t" + TreePalette.SHEET);
        line("VIN\tSTUMP\t" + TreePalette.STUMP);
        for (int i = 0; i < TreeScatter.BANDS.length; i++) {
            TreeScatter.Band b = TreeScatter.BANDS[i];
            line("VIN\tBAND\t" + i + "\t" + b.maxDist() + "\t" + b.size() + "\t"
                 + bits(b.density()) + "\t" + b.label());
        }
    }

    // ------------------------------------------------------------------
    // PI — which `tree` values survive Integer.parseInt
    // ------------------------------------------------------------------

    /** ASCII only. See the class comment: non-ASCII digits are a known bound. */
    private static final String[] TREE_VALUES = {
        "2", " 2 ", "+2", "-2", "007", "0", "1", "3", "12",
        "2.0", "", "   ", "abc", "0x2", "1_0", "2 3",
        "2147483647", "2147483648", "-2147483648", "-2147483649",
        "2\u0000", "\u000b2", "\t\n2\r", "1\u00a0", "  -4  ", "+0",
        // Reaches parseIntStrict's '+' handling on the C++ side: a lone sign,
        // and a sign pair that std::from_chars would otherwise accept.
        "+", "-", "+-3", "-+3", "++2",
    };

    private static void parseSection() {
        for (int i = 0; i < TREE_VALUES.length; i++) {
            String v = TREE_VALUES[i];
            // THE FALSIFIER, and it caught its own author on the first run.
            // The known bound is not "non-ASCII" — "1\u00a0" is non-ASCII and
            // both sides reject it identically, because NBSP is not a digit and
            // trim() does not strip it. The divergence class is narrower: a
            // character that Character.digit accepts and std::from_chars does
            // not, i.e. a NON-ASCII DIGIT such as U+0661. Banning all non-ASCII
            // would throw away the NBSP case, which is worth keeping precisely
            // because it looks like it should diverge and does not.
            for (char c : v.toCharArray())
                if (c > 0x7f && Character.digit(c, 10) >= 0)
                    throw new IllegalStateException(
                        "non-ASCII DIGIT in TREE_VALUES, the known C++ bound: "
                        + String.format("U+%04X", (int) c));
            TileIndex ti = new TileIndex();
            String n = TreePalette.SHEET + "_" + i;
            ti.byName.put(n, tile(n, TreePalette.SHEET, "tree", v, "solid", ""));
            TreePalette p = TreePalette.pick(ti, Set.of());
            StringBuilder sizes = new StringBuilder();
            for (Integer k : p.bySizeKeys()) { if (sizes.length() > 0) sizes.append(','); sizes.append(k); }
            line("PI\t" + i + "\t" + esc(v) + "\t" + p.all.size() + "\t"
                 + (sizes.length() == 0 ? "-" : sizes) + "\t" + p.usable());
        }
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray())
            b.append(c < 0x20 || c > 0x7e ? String.format("<%02x>", (int) c) : c);
        return b.length() == 0 ? "<empty>" : b.toString();
    }

    // ------------------------------------------------------------------
    // TP — palette construction, one tile per filter link
    // ------------------------------------------------------------------

    private static TileIndex tileSet(int kind) {
        TileIndex ti = new TileIndex();
        String S = TreePalette.SHEET;
        switch (kind) {
            case 0:   // empty index
                break;
            case 1:   // the ordinary case: eight size-2 trees, as vanilla has
                for (int i = 8; i < 16; i++)
                    ti.byName.put(S + "_" + i, tile(S + "_" + i, S, "tree", "2", "solid", ""));
                break;
            case 2:   // one tile failing at each link of the filter chain
                ti.byName.put(S + "_1", tile(S + "_1", S, "tree", "2", "solid", ""));
                ti.byName.put("other_01_1", tile("other_01_1", "other_01", "tree", "2", "solid", ""));
                ti.byName.put(S + "_3", tile(S + "_3", S, "solid", ""));            // no `tree`
                ti.byName.put(S + "_4", tile(S + "_4", S, "tree", "", "solid", "")); // empty `tree`
                ti.byName.put(S + "_5", tile(S + "_5", S, "tree", "2"));             // not solid
                ti.byName.put(S + "_6", tile(S + "_6", S, "tree", "x", "solid", "")); // unparseable
                // `tree` AND `wall`: kindOf's chain tests wall FIRST, so this
                // classifies WALL and never reaches the vegetation branch.
                ti.byName.put(S + "_7", tile(S + "_7", S, "tree", "2", "solid", "", "wall", ""));
                // `tree` AND a door property: DOOR wins, dropped likewise.
                ti.byName.put(S + "_9", tile(S + "_9", S, "tree", "2", "solid", "", "DoorWallN", ""));
                // VEGETATION via MoveWithWind with NO `tree` key at all. The
                // only route to the props.get("tree") miss: kindOf's vegetation
                // test accepts tree OR bush OR MoveWithWind, so passing the
                // Kind filter does not imply the property is there.
                ti.byName.put(S + "_2", tile(S + "_2", S, "MoveWithWind", "", "solid", ""));
                break;
            case 3:   // several size classes, so tilesNear has somewhere to fall
                ti.byName.put(S + "_10", tile(S + "_10", S, "tree", "1", "solid", ""));
                ti.byName.put(S + "_11", tile(S + "_11", S, "tree", "3", "solid", ""));
                ti.byName.put(S + "_12", tile(S + "_12", S, "tree", "7", "solid", ""));
                ti.byName.put(S + "_13", tile(S + "_13", S, "tree", "-2", "solid", ""));
                break;
            case 4:   // one size-1 tile only
                ti.byName.put(S + "_20", tile(S + "_20", S, "tree", "1", "solid", ""));
                break;
            case 5:   // three tiles: nextInt bound 3, NOT a power of two
                for (int i = 30; i < 33; i++)
                    ti.byName.put(S + "_" + i, tile(S + "_" + i, S, "tree", "2", "solid", ""));
                break;
            case 6:   // seven tiles: bound 7, also not a power of two
                for (int i = 40; i < 47; i++)
                    ti.byName.put(S + "_" + i, tile(S + "_" + i, S, "tree", "2", "solid", ""));
                break;
            case 8:   // usable(), but size 20 is more than 8 away from any band
                ti.byName.put(S + "_50", tile(S + "_50", S, "tree", "20", "solid", ""));
                break;
            case 7: {  // names deliberately out of insertion order, to prove the sort
                String[] ns = {S + "_9", S + "_10", S + "_8", S + "_11", S + "_100", S + "_2"};
                for (String n : ns) ti.byName.put(n, tile(n, S, "tree", "2", "solid", ""));
                break;
            }
            default:
                break;
        }
        return ti;
    }

    private static final int TILESETS = 9;

    private static void paletteSection() {
        for (int k = 0; k < TILESETS; k++)
            for (int st = 0; st < 2; st++) {
                Set<String> sprites = st == 0 ? Set.of() : Set.of(TreePalette.STUMP);
                TreePalette p = TreePalette.pick(tileSet(k), sprites);
                line("TP\t" + k + "\t" + st + "\tusable=" + p.usable()
                     + "\thasStump=" + p.hasStump + "\tall=" + join(p.all));
                line("TPS\t" + k + "\t" + st + "\t" + p.toString());
                for (Integer size : p.bySizeKeys())
                    line("TPB\t" + k + "\t" + st + "\t" + size + "\t"
                         + join(p.tilesNear(size)));
            }
    }

    private static String join(List<String> xs) {
        if (xs == null) return "<null>";
        if (xs.isEmpty()) return "<empty>";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) { if (i > 0) b.append(','); b.append(xs.get(i)); }
        return b.toString();
    }

    private static void tilesNearSection() {
        for (int k = 0; k < TILESETS; k++) {
            TreePalette p = TreePalette.pick(tileSet(k), Set.of());
            for (int size = -20; size <= 20; size++)
                line("TN\t" + k + "\t" + size + "\t" + join(p.tilesNear(size)));
        }
    }

    // ------------------------------------------------------------------

    private static void bandSection() {
        for (int d = -5; d <= 60; d++) line("BF\t" + d + "\t" + TreeScatter.bandFor(d));
        line("BF\t" + Integer.MAX_VALUE + "\t" + TreeScatter.bandFor(Integer.MAX_VALUE));
        line("BF\t" + Integer.MIN_VALUE + "\t" + TreeScatter.bandFor(Integer.MIN_VALUE));
    }

    // ------------------------------------------------------------------

    /** Non-square on purpose: the BFS queue encodes x*h+y. */
    private static GisImport raster(int kind, int w, int h) {
        GisImport g = new GisImport();
        g.width = w; g.height = h;
        g.cover = new GisImport.Cover[w][h];
        g.northWall = new boolean[w][h];
        g.westWall = new boolean[w][h];
        for (GisImport.Cover[] col : g.cover) Arrays.fill(col, GisImport.Cover.NONE);
        switch (kind) {
            case 0: break;                                   // NO structure at all
            case 1:                                          // one road band
                for (int x = 0; x < w; x++)
                    for (int y = h / 2 - 1; y <= h / 2 + 1; y++)
                        if (y >= 0 && y < h) g.cover[x][y] = GisImport.Cover.ROAD;
                break;
            case 2:                                          // one building block
                for (int x = w / 4; x < w / 4 + 6 && x < w; x++)
                    for (int y = h / 4; y < h / 4 + 6 && y < h; y++)
                        g.cover[x][y] = GisImport.Cover.BUILDING;
                break;
            case 3:                                          // walls only, no cover
                for (int x = 0; x < w; x += 7) for (int y = 0; y < h; y++) g.northWall[x][y] = true;
                for (int y = 0; y < h; y += 11) for (int x = 0; x < w; x++) g.westWall[x][y] = true;
                break;
            case 4:                                          // every square structure
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) g.cover[x][y] = GisImport.Cover.BUILDING;
                break;
            case 5:                                          // water, which is NOT structure
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) g.cover[x][y] = GisImport.Cover.WATER;
                break;
            case 6: {                                        // scattered, JavaRandom only
                java.util.Random r = new java.util.Random(31L);
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) {
                        int v = r.nextInt(50);
                        if (v == 0) g.cover[x][y] = GisImport.Cover.BUILDING;
                        else if (v == 1) g.cover[x][y] = GisImport.Cover.ROAD;
                        else if (v == 2) g.northWall[x][y] = true;
                    }
                break;
            }
            case 7:                                          // a single corner square
                g.cover[0][0] = GisImport.Cover.ROAD;
                break;
            case 8:                                          // the far corner
                g.cover[w - 1][h - 1] = GisImport.Cover.BUILDING;
                break;
            default: break;
        }
        return g;
    }

    private static final int[][] SHAPES = {{40, 40}, {57, 23}, {23, 57}, {1, 30}, {30, 1}, {2, 2}};

    private static void distanceSection() {
        for (int kind = 0; kind <= 8; kind++)
            for (int[] sh : SHAPES) {
                GisImport g = raster(kind, sh[0], sh[1]);
                int[][] d = TreeScatter.distanceToStructure(g);
                long hsh = FNV_INIT, zero = 0, unreached = 0, maxFinite = -1;
                for (int[] col : d)
                    for (int v : col) {
                        hsh = fnv(hsh, v);
                        if (v == 0) zero++;
                        else if (v == Integer.MAX_VALUE) unreached++;
                        if (v != Integer.MAX_VALUE && v > maxFinite) maxFinite = v;
                    }
                line("DS\t" + kind + "\t" + sh[0] + "x" + sh[1] + "\t"
                     + String.format("%016x", hsh) + "\t" + zero + "\t" + unreached
                     + "\t" + maxFinite);
                if (sh[0] == 40 && sh[1] == 40 && kind <= 3)
                    for (int x = 0; x < 40; x++) {
                        StringBuilder row = new StringBuilder();
                        for (int y = 0; y < 40; y++) {
                            int v = d[x][y];
                            row.append(v == Integer.MAX_VALUE ? "  ."
                                       : String.format("%3d", Math.min(v, 999)));
                        }
                        line("DSW\t" + kind + "\t" + x + "\t" + row);
                    }
            }
    }

    // ------------------------------------------------------------------

    private static void tooCloseSection() {
        int w = 9, h = 7;
        for (int mode = 0; mode < 4; mode++) {
            boolean[][] taken = new boolean[w][h];
            switch (mode) {
                case 0: break;                                       // none taken
                case 1: taken[0][0] = true; break;                   // a corner
                case 2: taken[w - 1][h - 1] = true; break;           // the far corner
                default: for (int i = 0; i < w; i++) taken[i][h / 2] = true; break;
            }
            for (int x = 0; x < w; x++) {
                StringBuilder row = new StringBuilder();
                for (int y = 0; y < h; y++)
                    row.append(TreeScatter.tooClose(taken, x, y, w, h) ? 'X' : '.');
                line("TC\t" + mode + "\t" + x + "\t" + row);
            }
        }
    }

    // ------------------------------------------------------------------
    // PL — place(), the whole unit
    // ------------------------------------------------------------------

    private static final long[] SEEDS = {0L, 1L, 0x5EEDL, -1L, Long.MIN_VALUE};

    private static void placeSection() {
        int c = 0;
        for (int kind = 0; kind <= 8; kind++)
            for (int ts = 0; ts < TILESETS; ts++)
                for (int st = 0; st < 2; st++) {
                    long seed = SEEDS[(kind + ts + st) % SEEDS.length];
                    int[] sh = SHAPES[(kind + ts) % SHAPES.length];
                    GisImport g = raster(kind, sh[0], sh[1]);
                    Set<String> sprites = st == 0 ? Set.of() : Set.of(TreePalette.STUMP);
                    TreePalette tp = TreePalette.pick(tileSet(ts), sprites);

                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    java.io.PrintStream old = System.out;
                    System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
                    String[][] out;
                    try {
                        out = TreeScatter.place(g, tp, seed);
                    } finally {
                        System.setOut(old);
                    }

                    long hsh = FNV_INIT, placed = 0, stumps = 0;
                    List<String> names = new ArrayList<>();
                    for (int x = 0; x < sh[0]; x++)
                        for (int y = 0; y < sh[1]; y++) {
                            String n = out[x][y];
                            hsh = fnv(hsh, n == null ? 0 : 1);
                            if (n != null) for (int ci = 0; ci < n.length(); ci++)
                                hsh = fnv(hsh, n.charAt(ci));
                            if (n == null) continue;
                            placed++;
                            if (n.equals(TreePalette.STUMP)) stumps++;
                            names.add(x + ":" + y + ":" + n);
                        }
                    line("PL\t" + c + "\t" + kind + "\t" + ts + "\t" + st + "\t" + seed
                         + "\t" + sh[0] + "x" + sh[1] + "\t" + String.format("%016x", hsh)
                         + "\t" + placed + "\t" + stumps);
                    for (String s : buf.toString(java.nio.charset.StandardCharsets.UTF_8).split("\n", -1))
                        if (!s.isEmpty()) line("PLLOG\t" + c + "\t" + s);
                    for (String s : names) line("PLT\t" + c + "\t" + s);
                    c++;
                }

        // THE DRAW-COUNT CHECK. Same raster and seed, run through a shared
        // stream, fingerprinted after each pass. A port that emits identical
        // tiles while drawing a different number of times diverges only here.
        java.util.Random shared = new java.util.Random(2026L);
        for (int kind = 0; kind <= 8; kind++)
            for (int ts = 0; ts < TILESETS; ts++) {
                GisImport g = raster(kind, 31, 19);
                TreePalette tp = TreePalette.pick(tileSet(ts), Set.of(TreePalette.STUMP));
                long seed = shared.nextLong();
                java.io.PrintStream old = System.out;
                System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
                try { TreeScatter.place(g, tp, seed); } finally { System.setOut(old); }
                line("PLSTREAM\t" + kind + "\t" + ts + "\t" + seed + "\t" + shared.nextInt());
            }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java pzformat.TreeScatterOracle <out-path>");
            System.exit(2);
        }
        vin();
        parseSection();
        paletteSection();
        tilesNearSection();
        bandSection();
        distanceSection();
        tooCloseSection();
        placeSection();

        byte[] b = OUT.toString().getBytes("UTF-8");
        Files.write(Paths.get(args[0]), b);
        System.out.println("TreeScatterOracle java: "
                + OUT.chars().filter(ch -> ch == '\n').count()
                + " lines, " + b.length + " bytes -> " + args[0]);
    }
}
