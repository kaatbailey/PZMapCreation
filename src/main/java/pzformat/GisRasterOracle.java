package pzformat;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Cross-language oracle for GisImport's raster core. Track F port step 6,
 * CHUNKS F5. Pattern B — canonical text digest.
 *
 * The oracle §37 specifies is "identical Cover grid compared cell by cell", and
 * that is the GRID section below. The rest exists because a grid comparison
 * alone would not localise a failure: the projection, the extent arithmetic and
 * the per-primitive fills each get their own section so a divergence names a
 * function rather than a map.
 *
 * CORPUS RULE (STATE §39): arithmetic and JavaRandom only. No Math.cos in the
 * CORPUS GENERATOR — step 3's first measurement was contaminated exactly that
 * way and reported 81 divergences where the true number was 122. The unit under
 * test calls cos; the thing that builds its inputs must not.
 *
 * THE COS SECTION IS DELIBERATELY ADVERSARIAL. Math.cos and std::cos disagree
 * by one ulp on ~0.2% of latitudes, 258 of them within |lat| <= 60 (measured,
 * see FINDINGS F5). Those exact latitudes are seeded into LAT_PROBES. The
 * question this oracle answers is not "do they agree" — they do not — but
 * "does one ulp of mPerLon reach the Cover grid". Feeding only well-behaved
 * latitudes would answer neither.
 *
 * Usage:  java pzformat.GisRasterOracle <out-path> [cases]
 */
public final class GisRasterOracle {

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\t') b.append("\\t");
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    /** Raw IEEE-754 bits — a double compared exactly, not via %f. */
    static String bits(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    /**
     * Latitudes where Math.cos and std::cos are KNOWN to differ by one ulp,
     * measured 2026-09-01 over a 257,143-sample sweep, plus ordinary ones for
     * contrast. The divergent values are first so a truncated run still hits
     * them.
     */
    static final double[] LAT_PROBES = {
        -59.6886, -59.2441, -58.7359, -58.5833, -58.5301, -58.3103,
        -58.0219, -57.9904, -57.9729, -56.1942, -56.0374, -55.8855,
        -55.7070, -54.8747, -54.8180, -54.6990, -54.5968, -54.4330,
        // ordinary: Kentucky (the vanilla map), Ohio, Tokyo, equator, poles
        37.7, 38.0, 39.9612, 40.0, 41.5, 35.6895, 0.0, 25.0, 50.0, 60.0,
        -89.9972, 89.9972, 89.9999, -0.0001,
    };

    /** FNV-1a 64 over the whole grid — one line instead of millions. */
    static String gridHash(GisImport g) {
        long h = 0xcbf29ce484222325L;
        for (int y = 0; y < g.height; y++)
            for (int x = 0; x < g.width; x++) {
                h ^= g.cover[x][y].ordinal();       h *= 0x100000001b3L;
                h ^= (g.northWall[x][y] ? 1 : 0);   h *= 0x100000001b3L;
                h ^= (g.westWall[x][y] ? 1 : 0);    h *= 0x100000001b3L;
                String o = g.occupancy[x][y];
                if (o == null) { h ^= 0xff; h *= 0x100000001b3L; }
                else for (byte b : o.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    h ^= (b & 0xffL); h *= 0x100000001b3L;
                }
            }
        return String.format("%016x", h);
    }

    // ------------------------------------------------------------- COS/EXT ---

    /**
     * The projection scalar and the extent it feeds. This is the section that
     * can diverge without any bug in the raster code at all, so it is emitted
     * FIRST and read first — same discipline as the palettes oracle's VIN
     * lines. A divergence here is arithmetic, not logic.
     */
    static void projection(List<String> out) {
        for (double lat : LAT_PROBES) {
            double rad = Math.toRadians(lat);
            double mPerLon = 111_320.0 * Math.cos(rad);
            out.add("COS\t" + bits(lat) + "\t" + bits(rad) + "\t" + bits(mPerLon));
        }

        // Does one ulp of mPerLon reach an INTEGER extent? Sweep spans that put
        // the product near a ceil boundary on purpose.
        for (double lat : LAT_PROBES) {
            for (int k = 1; k <= 40; k++) {
                double span = k * 0.0037;          // arbitrary, arithmetic only
                int w, h;
                double midLat = lat;
                double mPerLon = 111_320.0 * Math.cos(Math.toRadians(midLat));
                w = (int) Math.ceil(span * mPerLon);
                h = (int) Math.ceil(span * 110_540.0);
                out.add("EXT\t" + bits(lat) + "\t" + k + "\t" + w + "\t" + h);
            }
        }
    }

    // ---------------------------------------------------------------- RAST ---

    /**
     * Raster primitives over a synthetic corpus.
     *
     * The corpus is built from JavaRandom and integer arithmetic. Coordinates
     * are driven WELL OUTSIDE the grid on purpose — every primitive clamps, and
     * the clamping is where an off-by-one hides. Negative coordinates matter
     * especially: project() rounds negative values, which is the case STATE §40
     * proved unreachable in step 4 and which IS reachable here.
     */
    static void raster(List<String> out, int cases) {
        for (int c = 0; c < cases; c++) {
            Random rng = new Random(c);
            int mode = c % 6;

            int w = 1 + rng.nextInt(60);
            int h = 1 + rng.nextInt(60);
            GisImport g = new GisImport();
            g.width = w;
            g.height = h;
            g.cover = new GisImport.Cover[w][h];
            g.occupancy = new String[w][h];
            g.northWall = new boolean[w][h];
            g.westWall = new boolean[w][h];
            for (GisImport.Cover[] col : g.cover) java.util.Arrays.fill(col, GisImport.Cover.NONE);

            int ops = 1 + rng.nextInt(8);
            for (int op = 0; op < ops; op++) {
                // Coordinates deliberately range outside [0,w) x [0,h).
                int ax = rng.nextInt(w + 40) - 20, ay = rng.nextInt(h + 40) - 20;
                int bx = rng.nextInt(w + 40) - 20, by = rng.nextInt(h + 40) - 20;
                int hw = rng.nextInt(5);

                switch (mode) {
                    case 0: {
                        FootprintSnap.Rect r = new FootprintSnap.Rect(
                                ax, ay, rng.nextInt(12), rng.nextInt(12));
                        out.add("FRECT\t" + c + "\t" + op + "\t" + r.x() + "," + r.y()
                                + "," + r.w() + "," + r.h() + "\t"
                                + g.fillRect(r, "occ" + (op % 3)));
                        break;
                    }
                    case 1: {
                        // Polygon with a deliberate horizontal edge (a[1]==b[1]),
                        // which the scanline must skip, and a repeated vertex.
                        List<int[]> ring = new ArrayList<>();
                        ring.add(new int[]{ax, ay});
                        ring.add(new int[]{bx, ay});
                        ring.add(new int[]{bx, by});
                        ring.add(new int[]{bx, by});
                        ring.add(new int[]{ax, by});
                        out.add("FPOLY\t" + c + "\t" + op + "\t"
                                + g.fillPolygon(ring, "poly" + (op % 3)));
                        break;
                    }
                    case 2:
                        g.thickLine(new int[]{ax, ay}, new int[]{bx, by}, hw);
                        out.add("TLINE\t" + c + "\t" + op + "\t" + g.roadTiles);
                        break;
                    case 3:
                        g.waterLine(new int[]{ax, ay}, new int[]{bx, by}, hw);
                        out.add("WLINE\t" + c + "\t" + op + "\t" + g.waterTiles);
                        break;
                    case 4: {
                        // Mixed: water then road then building over the same
                        // ground, to exercise the precedence rules.
                        g.waterLine(new int[]{ax, ay}, new int[]{bx, by}, hw);
                        g.thickLine(new int[]{ay, ax}, new int[]{by, bx}, hw);
                        FootprintSnap.Rect r = new FootprintSnap.Rect(ax, ay, 4, 4);
                        g.fillRect(r, null);      // null occ is a real case
                        out.add("MIX\t" + c + "\t" + op + "\t" + g.waterTiles
                                + "\t" + g.roadTiles + "\t" + g.buildingTiles);
                        break;
                    }
                    default: {
                        // Degenerate: zero-length lines, empty rects, 2-point
                        // "polygons" that must be rejected.
                        g.thickLine(new int[]{ax, ay}, new int[]{ax, ay}, hw);
                        List<int[]> two = new ArrayList<>();
                        two.add(new int[]{ax, ay});
                        two.add(new int[]{bx, by});
                        out.add("DEGEN\t" + c + "\t" + op + "\t"
                                + g.fillPolygon(two, "x") + "\t"
                                + g.fillRect(new FootprintSnap.Rect(ax, ay, 0, 0), "z")
                                + "\t" + g.roadTiles);
                    }
                }
            }

            g.deriveWalls();
            out.add("GRID\t" + c + "\t" + w + "\t" + h + "\t" + gridHash(g));
            out.add("CNT\t" + c + "\t" + g.buildingTiles + "\t" + g.roadTiles
                    + "\t" + g.waterTiles);

            // Full cell-by-cell dump for the first few cases — §37 asks for a
            // cell-by-cell comparison, and a hash alone cannot say WHICH cell.
            if (c < 3) {
                for (int y = 0; y < h; y++) {
                    StringBuilder row = new StringBuilder();
                    for (int x = 0; x < w; x++) {
                        row.append(".WRB".charAt(g.cover[x][y].ordinal()));
                        row.append(g.northWall[x][y] ? 'N' : '-');
                        row.append(g.westWall[x][y] ? 'W' : '-');
                    }
                    out.add("CELL\t" + c + "\t" + y + "\t" + row);
                }
            }
        }
    }

    // ---------------------------------------------------------------- PROJ ---

    static void projectionPoints(List<String> out, int cases) {
        for (int c = 0; c < cases; c++) {
            Random rng = new Random(c * 31L + 7);
            double lat = LAT_PROBES[c % LAT_PROBES.length];
            double mPerLon = 111_320.0 * Math.cos(Math.toRadians(lat));
            double minLon = (rng.nextInt(720000) - 360000) / 2000.0;
            double maxLat = (rng.nextInt(340000) - 170000) / 2000.0;

            List<double[]> ring = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                // Deliberately straddle minLon so (p - minLon) goes NEGATIVE:
                // javaRound's negative branch, unreachable in step 4 (§40).
                ring.add(new double[]{
                    minLon + (rng.nextInt(200000) - 100000) / 1000000.0,
                    maxLat - (rng.nextInt(200000) - 100000) / 1000000.0});
            }
            // project/projectExact are instance methods that touch no instance
            // state. Called on a throwaway instance rather than adding a static
            // wrapper to the Java tree — the oracle must not edit its oracle.
            GisImport pg = new GisImport();
            List<int[]> ip = pg.project(ring, minLon, maxLat, mPerLon, 110_540.0);
            double[][] ep = pg.projectExact(ring, minLon, maxLat, mPerLon, 110_540.0);
            for (int i = 0; i < ip.size(); i++) {
                out.add("PROJ\t" + c + "\t" + i + "\t" + ip.get(i)[0] + "," + ip.get(i)[1]
                        + "\t" + bits(ep[i][0]) + "\t" + bits(ep[i][1]));
            }
        }
    }

    // ---------------------------------------------------------------- MISC ---

    static void waterCodes(List<String> out) {
        String[] codes = {null, "46006", "46003", "46000", "55800",
                          "33600", "33601", "33603", "", "46007", "99999", "4600"};
        for (String c : codes) {
            out.add("WW\t" + (c == null ? "NULL" : esc(c)) + "\t" + GisImport.waterWidth(c));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: GisRasterOracle <out-path> [cases]");
            System.exit(2);
        }
        int cases = args.length > 1 ? Integer.parseInt(args[1]) : 5000;

        List<String> out = new ArrayList<>();
        projection(out);
        waterCodes(out);
        projectionPoints(out, cases);
        raster(out, cases);

        StringBuilder b = new StringBuilder();
        for (String line : out) b.append(line).append('\n');
        Files.write(Paths.get(args[0]), b.toString().getBytes("UTF-8"));
        System.out.println("GisRasterOracle java: " + out.size() + " lines, "
                + b.length() + " bytes -> " + args[0]);
    }
}
