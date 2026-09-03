package pzformat;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Java side of the port-step-6b oracle for {@link GroundRegions}.
 *
 * Emits a canonical text digest (harness Pattern B, STATE §38) that must be
 * BYTE-IDENTICAL to groundregions_oracle.cpp's.
 *
 * ----------------------------------------------------------------------
 * CORPUS DESIGN — what each section is asking, and why
 * ----------------------------------------------------------------------
 *
 * VIN  Input fingerprints, emitted FIRST and read first: the GroundMaterial
 *      table, the MaskRule direction table, and every constant this unit
 *      reads. GroundRegions calls two already-ported units, so a divergence
 *      here says the failure is upstream and names which unit — the same job
 *      PalettesOracle's VIN lines do (§41).
 *
 * H    hash01, in isolation, as RAW DOUBLE BITS. This is the section that
 *      exists to catch the port's one real undefined-behaviour hazard: Java
 *      computes a SplitMix64 finaliser in `long` where every multiply
 *      overflows and every constant is a negative long. Transcribed into
 *      int64_t that is UB, and -O2 may exploit it — which is exactly the bug
 *      FINDINGS F5 found in JavaRandom.nextInt one step earlier. Positions are
 *      chosen adversarially: 0, +-1, Integer.MIN_VALUE and MAX_VALUE (where
 *      sign extension and the multiply both matter most), and seeds include
 *      Long.MIN_VALUE and the golden-ratio constant itself.
 *
 * HR   The same, at positions drawn through nextInt(720000). GroundRegions
 *      itself never calls nextInt with a bound — the ONLY draw reachable from
 *      this whole unit is MaskRule.side's nextBoolean(), which is next(1) and
 *      cannot enter the rejection loop. That is worth stating rather than
 *      assuming, so the corpus generator uses the bound that FOUND the F5 bug
 *      (rejection probability 2.1e-4) and exercises the loop anyway. Cheap
 *      insurance against the LCG regressing under a later edit.
 *
 * E    edgeDistance on small hand-built grids, digested IN FULL — distances
 *      and the propagated `across` material. This is where DX/DY's ordering
 *      is observable: the seed loop breaks at the first differing neighbour in
 *      N,S,W,E order, and that neighbour's material is what propagates.
 *      Degenerate grids are included deliberately (all-null, uniform with no
 *      edge at all, n=1, 1-wide stripes) because a real caller never produces
 *      them — §40's rule.
 *
 * D    dither on those same grids, at several seeds and origins including
 *      NEGATIVE origins, which is what makes hash01's sign extension reachable
 *      from the real entry point rather than only from section H.
 *
 * C    coverDistance over synthetic rasters, including one with no target
 *      square anywhere (every cell stays Integer.MAX_VALUE) and one that is
 *      entirely target.
 *
 * B    build() end to end. The four-way dispatch is the point: BUILDING (null),
 *      ROAD, the yard/verge/open distances, and Cover.WATER, which has NO
 *      branch of its own and falls through to ground. WATER was added to the
 *      enum on 2026-08-21, after this dispatch was written. The oracle asks
 *      what the code does, not what it should do.
 *
 * M    addMasks, exhaustively over all 16 direction subsets for a single
 *      outranking neighbour (so |S| = 0,1,2-adjacent,2-opposite,3,4 all fire),
 *      then over all 81 two-material assignments for several material triples
 *      (the multi-material concatenation §27 confirmed), at both variantSets
 *      values. Every case ends with a stream fingerprint drawn from the same
 *      Random, so a port that draws a different NUMBER of times diverges even
 *      when the tiles it emits happen to match.
 *
 * Determinism: no floating-point is ever formatted as text. Doubles go out as
 * raw bits. Every string in the digest comes from our own fixed tables, so no
 * escaping is needed (unlike GisRasterOracle, which digests OSM property
 * values).
 *
 *   javac -d out src/main/java/pzformat/GroundRegionsOracle.java
 *   java -cp out pzformat.GroundRegionsOracle /tmp/gr.java.txt
 */
public final class GroundRegionsOracle {

    private GroundRegionsOracle() { }

    private static final StringBuilder OUT = new StringBuilder();

    private static void line(String s) { OUT.append(s).append('\n'); }

    private static String bits(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    /** FNV-1a 64, so a big grid becomes one comparable field. */
    private static long fnv(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (i * 8)) & 0xffL;
            h *= 0x100000001b3L;
        }
        return h;
    }

    private static final long FNV_INIT = 0xcbf29ce484222325L;

    /** '.' for null, 'a'+ordinal otherwise. */
    private static char glyph(GroundMaterial m) {
        return m == null ? '.' : (char) ('a' + m.ordinal());
    }

    private static GroundMaterial mat(char c) {
        return c == '.' ? null : GroundMaterial.values()[c - 'a'];
    }

    private static GroundMaterial[][] grid(String[] rows) {
        // rows[y] is a row; the array is [x][y] to match the Java unit.
        int h = rows.length, w = rows[0].length();
        GroundMaterial[][] g = new GroundMaterial[w][h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                g[x][y] = mat(rows[y].charAt(x));
        return g;
    }

    private static GroundMaterial[][] copy(GroundMaterial[][] g) {
        GroundMaterial[][] c = new GroundMaterial[g.length][];
        for (int x = 0; x < g.length; x++) c[x] = g[x].clone();
        return c;
    }

    // ------------------------------------------------------------------
    // VIN
    // ------------------------------------------------------------------

    private static void vin() {
        for (GroundMaterial m : GroundMaterial.values())
            line("VIN\tMAT\t" + m.ordinal() + "\t" + m.name() + "\t" + m.floorMaterial
                 + "\t" + m.sheet + "\t" + m.block + "\t" + m.variantSets + "\t" + m.rank);
        for (MaskRule.Dir d : MaskRule.Dir.values())
            line("VIN\tDIR\t" + d.name() + "\t" + d.ord + "\t" + d.dx + "\t" + d.dy
                 + "\t" + d.opposite().name());
        line("VIN\tCONST\tYARD\t" + GroundRegions.YARD);
        line("VIN\tCONST\tVERGE\t" + GroundRegions.VERGE);
        line("VIN\tCONST\tMARGIN\t" + GroundRegions.MARGIN);
        line("VIN\tCONST\tPLEN\t" + GroundRegions.P.length);
        for (int i = 0; i < GroundRegions.P.length; i++)
            line("VIN\tP\t" + i + "\t" + bits(GroundRegions.P[i]));
        for (int k = 0; k < 4; k++)
            line("VIN\tDXY\t" + k + "\t" + GroundRegions.DX[k] + "\t" + GroundRegions.DY[k]);
        for (GisImport.Cover c : GisImport.Cover.values())
            line("VIN\tCOVER\t" + c.ordinal() + "\t" + c.name());
    }

    // ------------------------------------------------------------------
    // H / HR — hash01
    // ------------------------------------------------------------------

    private static final int[] H_POS = {
        0, 1, -1, 2, -2, 3, -3, 7, -7, 255, 256, -255, -256,
        51200, 51455, -51200, 65535, -65535, 1 << 20, -(1 << 20),
        1000003, -1000003, Integer.MAX_VALUE, Integer.MIN_VALUE,
        Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1,
    };

    private static final long[] H_SEED = {
        0L, 1L, -1L, 12345L, -12345L,
        Long.MIN_VALUE, Long.MAX_VALUE,
        0x9E3779B97F4A7C15L, 0xC2B2AE3D27D4EB4FL, -8675309L,
    };

    private static void hashSection() {
        for (long s : H_SEED)
            for (int gx : H_POS)
                for (int gy : H_POS)
                    line("H\t" + gx + "\t" + gy + "\t" + s + "\t"
                         + bits(GroundRegions.hash01(gx, gy, s)));

        // Positions drawn through the bound that found the F5 rejection bug.
        // See the class comment: this unit cannot reach that branch itself.
        Random rng = new Random(0x5EEDL);
        for (int i = 0; i < 6000; i++) {
            int gx = rng.nextInt(720000) - 360000;
            int gy = rng.nextInt(720000) - 360000;
            long s = rng.nextLong();
            line("HR\t" + i + "\t" + gx + "\t" + gy + "\t" + s + "\t"
                 + bits(GroundRegions.hash01(gx, gy, s)));
        }
    }

    // ------------------------------------------------------------------
    // Grids shared by E and D
    // ------------------------------------------------------------------

    /**
     * EVERY GRID HERE IS SQUARE, and that is a constraint of the unit rather
     * than a convenience. edgeDistance takes {@code n = m.length} and then
     * bounds BOTH axes with it (GroundRegions.java:231, 244), so a non-square
     * grid throws ArrayIndexOutOfBoundsException. dither inherits the same
     * assumption. The only real caller passes 272x272, so it is never hit —
     * found by the first version of this corpus, which was not square.
     */
    private static final String[][] GRIDS = {
        // 0: entirely null — no seed, no BFS, every distance stays -1.
        {"....", "....", "....", "...."},
        // 1: uniform — non-null everywhere but NO edge anywhere, so still -1.
        {"aaaaa", "aaaaa", "aaaaa", "aaaaa", "aaaaa"},
        // 2: vertical split, the ordinary case. d runs 0..3 and past P.length.
        {"aaaabbbb", "aaaabbbb", "aaaabbbb", "aaaabbbb",
         "aaaabbbb", "aaaabbbb", "aaaabbbb", "aaaabbbb"},
        // 3: one differing square in the middle of a field.
        {"aaaaaaa", "aaaaaaa", "aaaaaaa", "aaabaaa", "aaaaaaa", "aaaaaaa", "aaaaaaa"},
        // 4: checkerboard — every square is a seed, nothing is ever d>0.
        {"ababab", "bababa", "ababab", "bababa", "ababab", "bababa"},
        // 5: null holes punched through a boundary — the BUILDING case.
        {"aaa.bbb", "aa..bbb", "aaa.bbb", "aaaabbb", "aaa..bb", "aaaabbb", "aaaabbb"},
        // 6: a two-square-wide stripe of a third material.
        {"aaaccbbb", "aaaccbbb", "aaaccbbb", "aaaccbbb",
         "aaaccbbb", "aaaccbbb", "aaaccbbb", "aaaccbbb"},
        // 7: n = 1. Degenerate; no neighbour exists in any direction.
        {"a"},
        // 8: n = 2, two materials — every square is a seed with two candidates,
        //    so DX/DY's break order decides `across` on all four.
        {"ab", "ba"},
        // 9: a diagonal boundary.
        {"abbbb", "aabbb", "aaabb", "aaaab", "aaaaa"},
        // 10: road beside grass. isRoad's veto is reachable only from here.
        {"aaahhh", "aaahhh", "aaahhh", "aaahhh", "aaahhh", "aaahhh"},
        // 11: road, grass and a third natural material meeting at a point.
        {"aahh", "aahh", "ddhh", "ddhh"},
        // 12: material pairs that do NOT outrank each other in the same
        //     direction as their block order, so `across` matters.
        {"gggddd", "gggddd", "gggddd", "gggddd", "gggddd", "gggddd"},
        // 13: a lone non-null square in a null field.
        {".....", ".....", "..a..", ".....", "....."},
        // 14: two isolated squares of different materials, not adjacent.
        {"a....", ".....", ".....", ".....", "....b"},
    };

    private static void edgeSection() {
        for (int c = 0; c < GRIDS.length; c++) {
            GroundMaterial[][] m = grid(GRIDS[c]);
            int n = m.length;
            GroundMaterial[][] across = new GroundMaterial[n][m[0].length];
            int[][] d = GroundRegions.edgeDistance(m, across);
            for (int x = 0; x < n; x++) {
                StringBuilder ds = new StringBuilder();
                StringBuilder as = new StringBuilder();
                for (int y = 0; y < m[0].length; y++) {
                    ds.append(d[x][y] < 0 ? "-" : Integer.toString(Math.min(d[x][y], 9)));
                    if (d[x][y] > 9) ds.append('+');
                    as.append(glyph(across[x][y]));
                }
                line("E\t" + c + "\t" + x + "\t" + ds + "\t" + as);
            }
        }
    }

    private static final long[] D_SEED = {0L, 1L, 7L, 0x5EEDL, -1L, Long.MIN_VALUE};
    private static final int[][] D_ORIGIN = {
        {0, 0}, {-8, -8}, {51200, 51200}, {-51200, -51200},
        {Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2},
    };

    private static void ditherSection() {
        int c = 0;
        for (int gi = 0; gi < GRIDS.length; gi++)
            for (long s : D_SEED)
                for (int[] o : D_ORIGIN) {
                    GroundMaterial[][] m = copy(grid(GRIDS[gi]));
                    GroundRegions.dither(m, o[0], o[1], s);
                    StringBuilder sb = new StringBuilder();
                    for (int x = 0; x < m.length; x++)
                        for (int y = 0; y < m[0].length; y++) sb.append(glyph(m[x][y]));
                    line("D\t" + c + "\t" + gi + "\t" + s + "\t" + o[0] + "\t" + o[1]
                         + "\t" + sb);
                    c++;
                }
    }

    // ------------------------------------------------------------------
    // Synthetic rasters for C and B
    // ------------------------------------------------------------------

    /** Deterministic raster: a road band, some building blocks, a water body. */
    private static GisImport raster(int kind, int w, int h) {
        GisImport g = new GisImport();
        g.width = w;
        g.height = h;
        g.cover = new GisImport.Cover[w][h];
        for (GisImport.Cover[] col : g.cover) java.util.Arrays.fill(col, GisImport.Cover.NONE);
        switch (kind) {
            case 0:  // empty — no target of any class
                break;
            case 1:  // entirely BUILDING
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) g.cover[x][y] = GisImport.Cover.BUILDING;
                break;
            case 2:  // one horizontal road band
                for (int x = 0; x < w; x++)
                    for (int y = h / 2 - 3; y <= h / 2 + 3; y++)
                        if (y >= 0 && y < h) g.cover[x][y] = GisImport.Cover.ROAD;
                break;
            case 3:  // scattered buildings on a road grid
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) {
                        if (x % 37 == 0 || y % 41 == 0) g.cover[x][y] = GisImport.Cover.ROAD;
                        if ((x / 13) % 3 == 1 && (y / 11) % 3 == 1)
                            g.cover[x][y] = GisImport.Cover.BUILDING;
                    }
                break;
            case 4:  // WATER only — the branch that does not exist
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) g.cover[x][y] = GisImport.Cover.WATER;
                break;
            case 5:  // water body beside a road and a building
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) {
                        if (x < w / 3) g.cover[x][y] = GisImport.Cover.WATER;
                        else if (x < w / 3 + 5) g.cover[x][y] = GisImport.Cover.ROAD;
                        else if (x > w - 20 && y > h - 20)
                            g.cover[x][y] = GisImport.Cover.BUILDING;
                    }
                break;
            case 6: { // pseudo-random cover, JavaRandom only (no transcendentals)
                Random r = new Random(99L);
                for (int x = 0; x < w; x++)
                    for (int y = 0; y < h; y++) {
                        int v = r.nextInt(20);
                        if (v == 0) g.cover[x][y] = GisImport.Cover.BUILDING;
                        else if (v == 1) g.cover[x][y] = GisImport.Cover.ROAD;
                        else if (v == 2) g.cover[x][y] = GisImport.Cover.WATER;
                    }
                break;
            }
            case 7:  // a single building square, so YARD's ring is isolated
                if (w > 100 && h > 100) g.cover[100][100] = GisImport.Cover.BUILDING;
                break;
            case 8:  // a single road square
                if (w > 100 && h > 100) g.cover[100][100] = GisImport.Cover.ROAD;
                break;
            default:
                break;
        }
        return g;
    }

    private static void coverSection() {
        int[][] origins = {{0, 0}, {-40, -40}, {100, 100}, {200, 300}, {-300, 120}};
        int c = 0;
        for (int kind = 0; kind <= 8; kind++) {
            GisImport g = raster(kind, 320, 300);
            for (int[] o : origins)
                for (GisImport.Cover target :
                        new GisImport.Cover[]{GisImport.Cover.BUILDING, GisImport.Cover.ROAD}) {
                    int[][] d = GroundRegions.coverDistance(g, o[0], o[1],
                                                            GroundRegions.MARGIN, target);
                    long h = FNV_INIT;
                    long zero = 0, unreached = 0, maxFinite = -1;
                    for (int[] row : d)
                        for (int v : row) {
                            h = fnv(h, v);
                            if (v == 0) zero++;
                            else if (v == Integer.MAX_VALUE) unreached++;
                            if (v != Integer.MAX_VALUE && v > maxFinite) maxFinite = v;
                        }
                    line("C\t" + c + "\t" + kind + "\t" + o[0] + "\t" + o[1] + "\t"
                         + target.name() + "\t" + String.format("%016x", h) + "\t"
                         + zero + "\t" + unreached + "\t" + maxFinite);
                    c++;
                }
        }
    }

    private static void buildSection() {
        int[][] origins = {{0, 0}, {64, 64}, {-100, 50}};
        long[] seeds = {0L, 0x5EEDL, -1L};
        int c = 0;
        for (int kind = 0; kind <= 8; kind++) {
            GisImport g = raster(kind, 320, 300);
            int[] o = origins[kind % origins.length];
            long s = seeds[kind % seeds.length];
            GroundMaterial[][] r = GroundRegions.build(g, o[0], o[1], s);
            long h = FNV_INIT;
            long[] hist = new long[15];   // 14 materials + null
            for (int x = 0; x < 258; x++)
                for (int y = 0; y < 258; y++) {
                    GroundMaterial m = r[x][y];
                    int ord = m == null ? 14 : m.ordinal();
                    h = fnv(h, ord);
                    hist[ord]++;
                }
            StringBuilder hs = new StringBuilder();
            for (int i = 0; i < 15; i++) { if (i > 0) hs.append(','); hs.append(hist[i]); }
            line("B\t" + c + "\t" + kind + "\t" + o[0] + "\t" + o[1] + "\t" + s + "\t"
                 + String.format("%016x", h) + "\t" + hs);
            // A readable window, so a divergence can be LOCATED and not merely
            // detected. Rows are the returned array's own indices, 0-based on
            // the bordered 258x258.
            if (kind <= 2 || kind == 5) {
                for (int x = 0; x < 40; x++) {
                    StringBuilder row = new StringBuilder();
                    for (int y = 0; y < 40; y++) row.append(glyph(r[x][y]));
                    line("BW\t" + c + "\t" + x + "\t" + row);
                }
            }
            c++;
        }
    }

    // ------------------------------------------------------------------
    // M — addMasks
    // ------------------------------------------------------------------

    /** A 3x3 region so cell-local (0,0) is region[1][1] with four neighbours. */
    private static GroundMaterial[][] window(GroundMaterial self, GroundMaterial[] nb) {
        GroundMaterial[][] r = new GroundMaterial[3][3];
        r[1][1] = self;
        // MaskRule.Dir order: N, W, E, S.
        MaskRule.Dir[] ds = MaskRule.Dir.values();
        for (int i = 0; i < 4; i++)
            r[1 + ds[i].dx][1 + ds[i].dy] = nb[i];
        return r;
    }

    private static String stackNames(List<Integer> stack, CellData cell) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cell.header.tileNames.get(stack.get(i)));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static void maskSection() {
        CellData cell = CellData.blank(CellData.newHeader(new ArrayList<>(), 0, 0), 1);
        int c = 0;

        // self == null: the guard clause. Nothing may be appended.
        {
            List<Integer> stack = new ArrayList<>();
            Random rng = new Random(1L);
            GroundRegions.addMasks(stack, cell, window(null,
                    new GroundMaterial[]{GroundMaterial.GRASS_DARK, null, null, null}),
                    0, 0, null, rng);
            line("M\t" + c + "\tNULLSELF\t" + stackNames(stack, cell) + "\t" + rng.nextInt());
            c++;
        }

        // Every one of the 16 direction subsets, for a single outranking
        // neighbour. |S| = 0,1,2-adjacent,2-opposite,3,4 all fire here.
        GroundMaterial[][] pairs = {
            {GroundMaterial.GRASS_MEDIUM, GroundMaterial.GRASS_DARK},   // natural, sets=2
            {GroundMaterial.CLAY,         GroundMaterial.SAND},         // natural, sets=2
            {GroundMaterial.ROAD_04,      GroundMaterial.GRASS_DARK},   // cross-sheet
            {GroundMaterial.ROAD_06,      GroundMaterial.ROAD_01},      // street, sets=1
            {GroundMaterial.DIRT,         GroundMaterial.DIRT_GRASS},   // adjacent ranks
        };
        for (GroundMaterial[] pr : pairs)
            for (int bits = 0; bits < 16; bits++)
                for (long seed = 0; seed < 4; seed++) {
                    GroundMaterial[] nb = new GroundMaterial[4];
                    for (int i = 0; i < 4; i++)
                        nb[i] = ((bits >> i) & 1) != 0 ? pr[1] : null;
                    List<Integer> stack = new ArrayList<>();
                    Random rng = new Random(seed * 1000003L + bits);
                    GroundRegions.addMasks(stack, cell, window(pr[0], nb), 0, 0, pr[0], rng);
                    line("M\t" + c + "\tS1\t" + pr[0].name() + "\t" + pr[1].name() + "\t"
                         + bits + "\t" + seed + "\t" + stackNames(stack, cell)
                         + "\t" + rng.nextInt());
                    c++;
                }

        // Two distinct outranking materials over the four directions: all 81
        // assignments of {none, A, B}. This is the multi-material concatenation
        // (§27) and it is where the EnumMap's ordinal iteration order becomes
        // observable — A and B must come out in ordinal order, not in the order
        // the directions were walked.
        GroundMaterial[][] triples = {
            {GroundMaterial.GRASS_LIGHT, GroundMaterial.GRASS_DARK, GroundMaterial.GRASS_MEDIUM},
            {GroundMaterial.CLAY, GroundMaterial.DIRT, GroundMaterial.GRASS_DARK},
            {GroundMaterial.ROAD_04, GroundMaterial.GRASS_DARK, GroundMaterial.ROAD_01},
            // B declared BEFORE A but ranking AFTER it: ordinal order and rank
            // order disagree, so a port that sorts by rank diverges here.
            {GroundMaterial.ROAD_05, GroundMaterial.ROAD_04, GroundMaterial.ROAD_02},
        };
        for (GroundMaterial[] tr : triples)
            for (int a = 0; a < 81; a++) {
                int v = a;
                GroundMaterial[] nb = new GroundMaterial[4];
                for (int i = 0; i < 4; i++) {
                    int k = v % 3; v /= 3;
                    nb[i] = k == 0 ? null : (k == 1 ? tr[1] : tr[2]);
                }
                List<Integer> stack = new ArrayList<>();
                Random rng = new Random(a * 31L + 17L);
                GroundRegions.addMasks(stack, cell, window(tr[0], nb), 0, 0, tr[0], rng);
                line("M\t" + c + "\tS2\t" + tr[0].name() + "\t" + tr[1].name() + "\t"
                     + tr[2].name() + "\t" + a + "\t" + stackNames(stack, cell)
                     + "\t" + rng.nextInt());
                c++;
            }

        // Neighbours that do NOT outrank self, so the set stays empty even
        // though four non-null materials surround the square.
        for (GroundMaterial self : new GroundMaterial[]{GroundMaterial.GRASS_DARK,
                                                        GroundMaterial.ROAD_01}) {
            GroundMaterial[] nb = {GroundMaterial.CLAY, GroundMaterial.DIRT,
                                   GroundMaterial.ROAD_06, self};
            List<Integer> stack = new ArrayList<>();
            Random rng = new Random(5L);
            GroundRegions.addMasks(stack, cell, window(self, nb), 0, 0, self, rng);
            line("M\t" + c + "\tNOOUT\t" + self.name() + "\t" + stackNames(stack, cell)
                 + "\t" + rng.nextInt());
            c++;
        }

        // Against a REAL build() result at the cell's four corners and edges,
        // which is what exercises the bordered-array indexing: x=0 reads
        // region[0][*], x=255 reads region[256][*].
        GisImport g = raster(3, 320, 300);
        GroundMaterial[][] region = GroundRegions.build(g, 0, 0, 0x5EEDL);
        Random rng = new Random(2024L);
        int[][] probes = {{0, 0}, {0, 255}, {255, 0}, {255, 255}, {0, 128}, {255, 128},
                          {128, 0}, {128, 255}, {1, 1}, {254, 254}, {64, 64}, {13, 199}};
        for (int[] p : probes) {
            List<Integer> stack = new ArrayList<>();
            GroundMaterial self = region[p[0] + 1][p[1] + 1];
            GroundRegions.addMasks(stack, cell, region, p[0], p[1], self, rng);
            line("M\t" + c + "\tEDGE\t" + p[0] + "\t" + p[1] + "\t" + glyph(self)
                 + "\t" + stackNames(stack, cell) + "\t" + rng.nextInt());
            c++;
        }

        // Sweep every square of a real cell through addMasks on one shared
        // stream. A draw-count divergence anywhere shows up in the final
        // fingerprint even if every emitted tile matched.
        Random sweep = new Random(7L);
        long tiles = 0;
        for (int x = 0; x < 256; x++)
            for (int y = 0; y < 256; y++) {
                List<Integer> stack = new ArrayList<>();
                GroundRegions.addMasks(stack, cell, region, x, y, region[x + 1][y + 1], sweep);
                tiles += stack.size();
            }
        line("MSWEEP\t" + tiles + "\t" + sweep.nextInt() + "\t"
             + cell.header.tileNames.size());
        for (int i = 0; i < cell.header.tileNames.size(); i++)
            line("MTILE\t" + i + "\t" + cell.header.tileNames.get(i));
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java pzformat.GroundRegionsOracle <out-path>");
            System.exit(2);
        }
        vin();
        hashSection();
        edgeSection();
        ditherSection();
        coverSection();
        buildSection();
        maskSection();

        byte[] b = OUT.toString().getBytes("UTF-8");
        Files.write(Paths.get(args[0]), b);
        long lines = OUT.chars().filter(ch -> ch == '\n').count();
        System.out.println("GroundRegionsOracle java: " + lines + " lines, "
                           + b.length + " bytes -> " + args[0]);
    }
}
