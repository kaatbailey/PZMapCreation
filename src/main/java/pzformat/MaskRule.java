package pzformat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Which mask tiles a square carries, given what lies on its four sides.
 *
 * Deliberately general. This is a pure function from a direction set to tile
 * offsets within a block — it knows nothing about ground, GIS, or cells. Auto
 * wall-joining (A3) is the same problem shape and should use this rather than
 * grow a second copy; `floors_burnt_01` is a third consumer.
 *
 * THE RULE — STATE §26, measured on a contiguous 9x5 rectangle of Muldraugh
 * 42_40 where 21 of 21 masks were explained, and confirmed at corpus scale in
 * §27.
 *
 * A square carries masks drawn from a NEIGHBOUR's block, never its own. The
 * mask names the direction the other material lies in. With S = the set of
 * orthogonal directions holding that material:
 *
 *   |S| = 0              nothing
 *   |S| = 1              one side tile
 *   |S| = 2, adjacent    ONE CORNER TILE — not two side tiles
 *   |S| = 2, opposite    two side tiles
 *   |S| = 3              two corner tiles, sharing the middle direction
 *   |S| = 4              four corner tiles
 *
 * Offsets from block base B:
 *
 *   B+1 N+W    B+2 E+S    B+3 S+W    B+4 E+N          corners
 *   B+8 N   B+9 W   B+10 E   B+11 S                   sides, variant set 1
 *   B+12 N  B+13 W  B+14 E   B+15 S                   sides, variant set 2
 *
 * VARIANT SETS ARE NOT UNIVERSAL. `blends_natural_01` has two and vanilla uses
 * both on identical geometry, so pick at random. `blends_street_01` has ONE —
 * code assuming the natural shape emits `blends_street_01_12`..`_15`, which are
 * not road masks (§27).
 *
 * Grid convention: +x East, +y South, matching STATE §10.
 */
public final class MaskRule {

    public enum Dir {
        // ord, dx, dy. Grid convention: +x East, +y South (STATE §10).
        // N and W were transposed here once; the self-test below now asserts
        // this table rather than trusting it.
        N(0, 0, -1), W(1, -1, 0), E(2, 1, 0), S(3, 0, 1);

        /** Offset within a variant set: B+8+ord for set 1, B+12+ord for set 2. */
        public final int ord;
        public final int dx, dy;

        Dir(int ord, int dx, int dy) { this.ord = ord; this.dx = dx; this.dy = dy; }

        public Dir opposite() {
            return switch (this) { case N -> S; case S -> N; case W -> E; case E -> W; };
        }
    }

    private static final int[] EMPTY = new int[0];

    private MaskRule() { }

    /**
     * Mask offsets for one neighbouring material.
     *
     * @param block        that material's block base
     * @param dirs         directions in which that material lies
     * @param variantSets  2 for blends_natural_01, 1 for blends_street_01
     * @param rng          used only to pick between variant sets
     */
    public static int[] masks(int block, Set<Dir> dirs, int variantSets, Random rng) {
        if (dirs.isEmpty()) return EMPTY;
        Dir[] d = dirs.toArray(new Dir[0]);

        switch (dirs.size()) {
            case 1:
                return new int[]{side(block, d[0], variantSets, rng)};

            case 2:
                if (d[0].opposite() == d[1])
                    return new int[]{side(block, d[0], variantSets, rng),
                                     side(block, d[1], variantSets, rng)};
                return new int[]{corner(block, d[0], d[1])};

            case 3: {
                // The missing direction's OPPOSITE is the one adjacent to both
                // others, so it appears in both corner tiles.
                Dir missing = null;
                for (Dir k : Dir.values()) if (!dirs.contains(k)) missing = k;
                Dir mid = missing.opposite();
                List<Dir> others = new ArrayList<>();
                for (Dir k : dirs) if (k != mid) others.add(k);
                return new int[]{corner(block, mid, others.get(0)),
                                 corner(block, mid, others.get(1))};
            }

            case 4:
                // NW, NE, SW, SE — the order vanilla writes them in at
                // 42_40 (112,200), an isolated square with all four sides
                // differing. Nothing suggests the order is load-bearing.
                return new int[]{block + 1, block + 4, block + 3, block + 2};

            default:
                throw new IllegalArgumentException("impossible direction set: " + dirs);
        }
    }

    /** Corner tile covering two adjacent sides. Unordered. */
    static int corner(int block, Dir a, Dir b) {
        EnumSet<Dir> s = EnumSet.of(a, b);
        if (s.equals(EnumSet.of(Dir.N, Dir.W))) return block + 1;
        if (s.equals(EnumSet.of(Dir.E, Dir.S))) return block + 2;
        if (s.equals(EnumSet.of(Dir.S, Dir.W))) return block + 3;
        if (s.equals(EnumSet.of(Dir.E, Dir.N))) return block + 4;
        throw new IllegalArgumentException("not an adjacent pair: " + a + "," + b);
    }

    /** Side tile, choosing uniformly between variant sets where two exist. */
    static int side(int block, Dir d, int variantSets, Random rng) {
        int set = (variantSets > 1 && rng.nextBoolean()) ? 12 : 8;
        return block + set + d.ord;
    }

    // ------------------------------------------------------------------
    // Self-test: reproduce vanilla from neighbour materials alone.
    //
    // Every case below is a real square of Muldraugh 42_40 recorded in STATE
    // §26, with the mask tiles actually present in the lotpack. If the rule
    // cannot regenerate them it is wrong, and this catches it in a second
    // without touching map data.
    //
    //   java -cp out pzformat.MaskRule
    // ------------------------------------------------------------------

    static final int DARK = 16;     // Grass_Dark block base

    public static void main(String[] args) {
        int pass = 0, fail = 0;

        // Direction vectors, asserted against the grid convention. The
        // set-to-offset cases below all passed while N and W were transposed,
        // because they never exercise the neighbour lookup. This does.
        int[][] want = {{0, -1}, {-1, 0}, {1, 0}, {0, 1}};   // N, W, E, S
        Dir[] order = {Dir.N, Dir.W, Dir.E, Dir.S};
        boolean dirsOk = true;
        for (int i = 0; i < 4; i++) {
            Dir d = order[i];
            boolean ok = d.dx == want[i][0] && d.dy == want[i][1] && d.ord == i;
            if (!ok) dirsOk = false;
            System.out.printf("%-22s %s  dx=%d dy=%d ord=%d%n",
                    "dir " + d, ok ? "PASS" : "FAIL", d.dx, d.dy, d.ord);
        }
        for (Dir d : Dir.values()) {
            if (d.opposite().dx != -d.dx || d.opposite().dy != -d.dy) {
                dirsOk = false;
                System.out.println("dir opposite            FAIL " + d + " vs " + d.opposite());
            }
        }
        if (!dirsOk) fail++;
        pass++;

        // (116,200) Grass_Medium base, Grass_Dark to the E only.
        // Vanilla wrote _26 at y=199,200 and _30 at y=201 — both variants of
        // the E side tile, which is why variant choice is random.
        fail += check("single side E", EnumSet.of(Dir.E), new int[]{26}, new int[]{30}) ? 0 : 1;
        pass++;

        // (114,200) and (113,201): Grass_Dark to the N and W. One corner tile.
        fail += check("adjacent N+W", EnumSet.of(Dir.N, Dir.W), new int[]{17}) ? 0 : 1;
        pass++;

        // (118,199): Grass_Dark to the S and W.
        fail += check("adjacent S+W", EnumSet.of(Dir.S, Dir.W), new int[]{19}) ? 0 : 1;
        pass++;

        // (111,201) and (117,202): Grass_Dark to the E and N.
        fail += check("adjacent E+N", EnumSet.of(Dir.E, Dir.N), new int[]{20}) ? 0 : 1;
        pass++;

        // (117,198): Grass_Dark to the N and S — OPPOSITE, so two side tiles,
        // never a corner.
        fail += check("opposite N+S", EnumSet.of(Dir.N, Dir.S),
                new int[]{24, 27}, new int[]{28, 31},
                new int[]{24, 31}, new int[]{27, 28}) ? 0 : 1;
        pass++;

        // (111,199): Grass_Dark to the E, N and S. Vanilla wrote _20 (E+N) and
        // _18 (E+S) — two corners sharing E, not a corner plus a side.
        fail += check("three E+N+S", EnumSet.of(Dir.E, Dir.N, Dir.S),
                new int[]{20, 18}) ? 0 : 1;
        pass++;

        // (112,200): an isolated Grass_Medium square, Grass_Dark on all four
        // sides. Vanilla wrote _17, _20, _19, _18 — all four corners.
        fail += check("all four", EnumSet.of(Dir.N, Dir.W, Dir.E, Dir.S),
                new int[]{17, 20, 19, 18}) ? 0 : 1;
        pass++;

        // Street blocks have ONE variant set. Road_02 is block 16 in
        // blends_street_01; vanilla (90,190) carried _26 (E) and _25 (W).
        int[] roadE = masks(16, EnumSet.of(Dir.E), 1, new Random(1));
        boolean roadOk = Arrays.equals(roadE, new int[]{26});
        System.out.printf("%-22s %s  got %s%n", "street single set",
                roadOk ? "PASS" : "FAIL", Arrays.toString(roadE));
        if (!roadOk) fail++;
        pass++;

        System.out.println("\n" + (pass - fail) + " / " + pass + " cases pass");
        if (fail > 0) {
            System.out.println("The rule does not reproduce vanilla. Do not wire it in.");
            System.exit(1);
        }
        System.out.println("Rule reproduces every recorded vanilla square.");
    }

    /**
     * True when 400 draws all land in {@code accepted} and every option
     * appears at least once.
     *
     * Compares SORTED, because the direction set has no defined iteration
     * order and the tile order within a square is not load-bearing. An earlier
     * version compared exact sequences and failed on correct output — the
     * checker was wrong, not the rule.
     */
    @SafeVarargs
    static boolean check(String label, Set<Dir> dirs, int[]... accepted) {
        Random rng = new Random(12345);
        List<String> want = new ArrayList<>();
        for (int[] a : accepted) want.add(sorted(a));
        Set<String> seen = new java.util.HashSet<>();
        int[] bad = null;

        for (int i = 0; i < 400 && bad == null; i++) {
            int[] got = masks(DARK, dirs, 2, rng);
            String key = sorted(got);
            if (want.contains(key)) seen.add(key); else bad = got;
        }

        boolean allSeen = seen.size() == new java.util.HashSet<>(want).size();
        String verdict = bad != null ? "FAIL unexpected " + Arrays.toString(bad)
                : allSeen ? "PASS" : "FAIL a variant never appeared";
        System.out.printf("%-22s %s%n", label, verdict);
        return bad == null && allSeen;
    }

    static String sorted(int[] a) {
        int[] c = a.clone();
        Arrays.sort(c);
        return Arrays.toString(c);
    }
}
