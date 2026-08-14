package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * E8 Part 1 — the dither spatial law. Read-only; writes nothing.
 *
 * STATE §27 confirmed dither exists (19.97% mean single-square-island share
 * over 4,065 cells) but not its law. Three things are unknown:
 *
 *   1. band width   — how far the transition extends
 *   2. profile      — how P(minority material) varies across the band
 *   3. INDEPENDENCE — are squares independent coin flips, or spatially
 *                     correlated noise?
 *
 * (3) is the one that decides the implementation. Per-square random and a
 * coherent noise field produce IDENTICAL island counts, so §27's measurement
 * cannot separate them — but they look completely different on screen. Getting
 * it wrong yields ground that is statistically right and visibly wrong.
 *
 * The test: for a Bernoulli process at probability p, run lengths of minority
 * squares are geometric, P(L=k) = p^(k-1)(1-p). Spatially correlated noise
 * gives runs LONGER and RARER than geometric. This measures both and prints
 * them side by side.
 *
 * CIRCULARITY GUARD. "Distance to the boundary" needs the boundary defined
 * without reference to the dither, or the measurement measures itself. A
 * majority filter over a window is used. If the answer moves materially with
 * window size, the method is measuring the filter rather than the map — so
 * every window size is reported rather than one being chosen.
 *
 * Usage:
 *   java -cp out pzformat.DitherLaw MEDIA_DIR MAP_DIR CELL [CELL...]
 *   java -cp out pzformat.DitherLaw --windows 5,9,15 MEDIA_DIR MAP_DIR CELL...
 */
public final class DitherLaw {

    /** Signed distance beyond which squares are treated as region interior. */
    static final int BAND = 8;

    /** Material pairs worth measuring, from STATE §27's counts. */
    static final String[][] PAIRS = {
            {"Grass_Dark", "Grass_Medium"},
            {"Grass_Medium", "Grass_Light"},
            {"Dirt_Grass", "Dirt"},
            {"Grass_Light", "Dirt"},
            {"Grass_Dark", "Grass_Light"},
            {"Grass_Light", "Sand"},
    };

    /** Accumulated across every cell, per (pair, window). */
    static final Map<String, Acc> acc = new LinkedHashMap<>();

    static final class Acc {
        long[] total = new long[2 * BAND + 3];      // indexed by sd + BAND + 1
        long[] minority = new long[2 * BAND + 3];
        Map<Integer, Long> runLen = new TreeMap<>(); // observed minority run lengths
        long bandCells, bandMinority;

        // Matched-distance adjacency, indexed by sd + BAND + 1.
        // pairs[i]   adjacent pairs where both squares sit at signed distance sd
        // both[i]    of those, how many had BOTH squares minority
        // single[i]  total minority endpoints seen across those pairs (of 2*pairs)
        long[] pairsX = new long[2 * BAND + 3], bothX = new long[2 * BAND + 3],
              singleX = new long[2 * BAND + 3];
        long[] pairsY = new long[2 * BAND + 3], bothY = new long[2 * BAND + 3],
              singleY = new long[2 * BAND + 3];

        // Minority connected components, bucketed by the mean |d| of their
        // member squares. Dither gives singletons; genuine smoothed-away
        // regions give compact blobs.
        long[] compCount = new long[BAND + 2];
        long[] compCells = new long[BAND + 2];
        long[] compSingles = new long[BAND + 2];
        long[] compBig = new long[BAND + 2];      // size >= 5
    }

    public static void main(String[] args) throws Exception {
        int[] windows = {5, 9, 15};
        int a = 0;
        if (args.length > 1 && args[0].equals("--windows")) {
            String[] parts = args[1].split(",");
            windows = new int[parts.length];
            for (int i = 0; i < parts.length; i++) windows[i] = Integer.parseInt(parts[i].trim());
            a = 2;
        }
        if (args.length - a < 3) {
            System.out.println("usage: DitherLaw [--windows 5,9,15] MEDIA_DIR MAP_DIR CELL [CELL...]");
            return;
        }
        Path mediaDir = Path.of(args[a]);
        Path mapDir = Path.of(args[a + 1]);

        TileIndex ti = TileIndex.load(mediaDir);
        System.out.println("tile definitions: " + ti.byName.size());
        System.out.println("windows: " + Arrays.toString(windows)
                + "   band: +/-" + BAND + " squares");
        System.out.println("cells: " + (args.length - a - 2) + "\n");

        int cells = 0;
        for (int ci = a + 2; ci < args.length; ci++) {
            String cellName = args[ci];
            String[][] grid;
            try {
                grid = loadGrid(ti, mapDir, cellName);
            } catch (Exception e) {
                System.out.println("  skipped " + cellName + ": " + e.getMessage());
                continue;
            }
            for (String[] pair : PAIRS)
                for (int w : windows)
                    measure(grid, pair[0], pair[1], w);
            if (++cells % 250 == 0) System.out.println("  ... " + cells + " cells");
        }

        System.out.println("\ncells measured: " + cells);
        report(windows);
    }

    /** Solid ground material per square at z=0. Same rule as GroundCensus. */
    static String[][] loadGrid(TileIndex ti, Path mapDir, String cellName) throws Exception {
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        CellData c = CellData.load(lp, lh);
        int n = c.cellSize;
        String[][] grid = new String[n][n];
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                String[] names = c.tileNamesAt(x, y, 0);
                if (names == null) continue;
                for (String name : names) {
                    TileDefs.Tile t = ti.get(name);
                    if (t == null) continue;
                    Map<String, String> p = t.props;
                    if (p.containsKey("FloorOverlay")) continue;
                    if (!p.containsKey("solidfloor")) continue;
                    String m = p.get("FloorMaterial");
                    if (m != null && !m.isEmpty()) grid[x][y] = m;
                    break;
                }
            }
        return grid;
    }

    /**
     * One pair, one window. Majority-filter to find the true edge, distance
     * transform from it, then accumulate the profile and the run lengths.
     */
    static void measure(String[][] grid, String matA, String matB, int win) {
        int n = grid.length;
        int r = win / 2;

        // v: 1 = A, 0 = B, -1 = neither
        int[][] v = new int[n][n];
        int nA = 0, nB = 0;
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                String m = grid[x][y];
                if (matA.equals(m)) { v[x][y] = 1; nA++; }
                else if (matB.equals(m)) { v[x][y] = 0; nB++; }
                else v[x][y] = -1;
            }
        if (nA < 200 || nB < 200) return;   // too little of this pair here

        // Summed-area tables so the majority filter is O(1) per square.
        int[][] csA = new int[n + 1][n + 1], csN = new int[n + 1][n + 1];
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                int isA = v[x][y] == 1 ? 1 : 0;
                int isN = v[x][y] >= 0 ? 1 : 0;
                csA[x + 1][y + 1] = csA[x][y + 1] + csA[x + 1][y] - csA[x][y] + isA;
                csN[x + 1][y + 1] = csN[x][y + 1] + csN[x + 1][y] - csN[x][y] + isN;
            }

        // maj: majority label in the window, -1 where undefined
        int[][] maj = new int[n][n];
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                if (v[x][y] < 0) { maj[x][y] = -1; continue; }
                int x0 = Math.max(0, x - r), x1 = Math.min(n - 1, x + r);
                int y0 = Math.max(0, y - r), y1 = Math.min(n - 1, y + r);
                int sa = rect(csA, x0, y0, x1, y1);
                int sn = rect(csN, x0, y0, x1, y1);
                int sb = sn - sa;
                maj[x][y] = sa > sb ? 1 : sb > sa ? 0 : v[x][y];
            }

        // Multi-source BFS from squares whose majority differs from a neighbour's.
        int[][] dist = new int[n][n];
        for (int[] row : dist) Arrays.fill(row, Integer.MAX_VALUE);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                if (maj[x][y] < 0) continue;
                boolean edge = false;
                for (int d = 0; d < 4 && !edge; d++) {
                    int nx = x + DX[d], ny = y + DY[d];
                    if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                    if (maj[nx][ny] >= 0 && maj[nx][ny] != maj[x][y]) edge = true;
                }
                if (edge) { dist[x][y] = 0; q.add(new int[]{x, y}); }
            }
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int d = 0; d < 4; d++) {
                int nx = p[0] + DX[d], ny = p[1] + DY[d];
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                if (maj[nx][ny] < 0 || dist[nx][ny] != Integer.MAX_VALUE) continue;
                dist[nx][ny] = dist[p[0]][p[1]] + 1;
                q.add(new int[]{nx, ny});
            }
        }

        Acc ac = acc.computeIfAbsent(key(matA, matB, win), k -> new Acc());

        // Profile: signed distance, positive into the A-majority side.
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                if (maj[x][y] < 0 || dist[x][y] == Integer.MAX_VALUE) continue;
                int d = Math.min(dist[x][y], BAND + 1);
                int sd = maj[x][y] == 1 ? d : -d;
                int i = sd + BAND + 1;
                ac.total[i]++;
                if (v[x][y] != maj[x][y]) ac.minority[i]++;
            }

        // Independence, matched distance: adjacent squares at the SAME signed
        // distance, so p is constant within each comparison and a mixture of
        // p values cannot manufacture a correlation.
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                if (maj[x][y] < 0 || dist[x][y] == Integer.MAX_VALUE) continue;
                int d = Math.min(dist[x][y], BAND + 1);
                int sd = maj[x][y] == 1 ? d : -d;
                int i = sd + BAND + 1;
                boolean m0 = v[x][y] != maj[x][y];

                if (x + 1 < n && maj[x + 1][y] >= 0 && dist[x + 1][y] != Integer.MAX_VALUE) {
                    int d1 = Math.min(dist[x + 1][y], BAND + 1);
                    int sd1 = maj[x + 1][y] == 1 ? d1 : -d1;
                    if (sd1 == sd) {
                        boolean m1 = v[x + 1][y] != maj[x + 1][y];
                        ac.pairsX[i]++;
                        if (m0) ac.singleX[i]++;
                        if (m1) ac.singleX[i]++;
                        if (m0 && m1) ac.bothX[i]++;
                    }
                }
                if (y + 1 < n && maj[x][y + 1] >= 0 && dist[x][y + 1] != Integer.MAX_VALUE) {
                    int d1 = Math.min(dist[x][y + 1], BAND + 1);
                    int sd1 = maj[x][y + 1] == 1 ? d1 : -d1;
                    if (sd1 == sd) {
                        boolean m1 = v[x][y + 1] != maj[x][y + 1];
                        ac.pairsY[i]++;
                        if (m0) ac.singleY[i]++;
                        if (m1) ac.singleY[i]++;
                        if (m0 && m1) ac.bothY[i]++;
                    }
                }
            }

        // Minority connected components, 4-connected, bucketed by mean |d|.
        boolean[][] seenM = new boolean[n][n];
        ArrayDeque<int[]> cq = new ArrayDeque<>();
        for (int sx = 0; sx < n; sx++)
            for (int sy = 0; sy < n; sy++) {
                if (seenM[sx][sy]) continue;
                if (maj[sx][sy] < 0 || dist[sx][sy] == Integer.MAX_VALUE) continue;
                if (v[sx][sy] == maj[sx][sy]) continue;
                seenM[sx][sy] = true;
                cq.clear();
                cq.add(new int[]{sx, sy});
                int size = 0;
                long dsum = 0;
                while (!cq.isEmpty()) {
                    int[] p = cq.poll();
                    size++;
                    dsum += Math.min(dist[p[0]][p[1]], BAND + 1);
                    for (int dd = 0; dd < 4; dd++) {
                        int nx = p[0] + DX[dd], ny = p[1] + DY[dd];
                        if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
                        if (seenM[nx][ny]) continue;
                        if (maj[nx][ny] < 0 || dist[nx][ny] == Integer.MAX_VALUE) continue;
                        if (v[nx][ny] == maj[nx][ny]) continue;
                        seenM[nx][ny] = true;
                        cq.add(new int[]{nx, ny});
                    }
                }
                int bucket = (int) Math.min(BAND + 1, Math.round((double) dsum / size));
                ac.compCount[bucket]++;
                ac.compCells[bucket] += size;
                if (size == 1) ac.compSingles[bucket]++;
                if (size >= 5) ac.compBig[bucket]++;
            }

        // Retained as a diagnostic only. CONFOUNDED across d — see the class
        // note. Do not draw an independence conclusion from it.
        // Independence: run lengths of minority squares along x, inside the band.
        for (int y = 0; y < n; y++) {
            int run = 0;
            for (int x = 0; x < n; x++) {
                boolean usable = maj[x][y] >= 0 && dist[x][y] <= BAND;
                if (!usable) { if (run > 0) { bump(ac, run); run = 0; } continue; }
                ac.bandCells++;
                boolean minority = v[x][y] != maj[x][y];
                if (minority) { ac.bandMinority++; run++; }
                else if (run > 0) { bump(ac, run); run = 0; }
            }
            if (run > 0) bump(ac, run);
        }
    }

    static void report(int[] windows) {
        for (String[] pair : PAIRS) {
            boolean printed = false;
            for (int w : windows) {
                Acc ac = acc.get(key(pair[0], pair[1], w));
                if (ac == null || ac.bandCells < 5000) continue;
                if (!printed) {
                    System.out.println("\n" + "=".repeat(66));
                    System.out.println(pair[0] + "  /  " + pair[1]);
                    System.out.println("=".repeat(66));
                    printed = true;
                }
                System.out.println("\n  window " + w + "   band cells " + ac.bandCells);

                System.out.println("  PROFILE  P(minority) by signed distance"
                        + "  (+ into " + pair[0] + ")");
                StringBuilder l1 = new StringBuilder("    d   ");
                StringBuilder l2 = new StringBuilder("    P   ");
                for (int sd = -BAND - 1; sd <= BAND + 1; sd++) {
                    int i = sd + BAND + 1;
                    if (ac.total[i] < 50) continue;
                    l1.append(String.format("%6d", sd));
                    l2.append(String.format("%6.3f", (double) ac.minority[i] / ac.total[i]));
                }
                System.out.println(l1);
                System.out.println(l2);

                double p = (double) ac.bandMinority / ac.bandCells;
                long runs = 0, weighted = 0;
                for (Map.Entry<Integer, Long> e : ac.runLen.entrySet()) {
                    runs += e.getValue();
                    weighted += (long) e.getKey() * e.getValue();
                }
                System.out.printf("%n  INDEPENDENCE  marginal p=%.4f   runs=%d"
                        + "   mean run %.3f   Bernoulli predicts %.3f%n",
                        p, runs, (double) weighted / runs, 1.0 / (1.0 - p));
                System.out.println("    k    observed   obs frac   geometric   ratio");
                for (int k = 1; k <= 8; k++) {
                    long obs = ac.runLen.getOrDefault(k, 0L);
                    double of = (double) obs / runs;
                    double geo = Math.pow(p, k - 1) * (1 - p);
                    System.out.printf("    %-4d %9d %10.4f %11.4f %7.2fx%n",
                            k, obs, of, geo, geo == 0 ? 0 : of / geo);
                }
                System.out.println("    (CONFOUNDED across d — diagnostic only)");

                System.out.println("\n  MATCHED-DISTANCE LIFT   P(both minority) / P(minority)^2");
                System.out.println("    at fixed d, so varying p cannot manufacture a lift");
                System.out.println("    d      pairsX   p(d)    liftX      pairsY   liftY");
                double sumLX = 0, sumLY = 0; int nL = 0;
                for (int sd = -BAND; sd <= BAND; sd++) {
                    int i = sd + BAND + 1;
                    if (ac.pairsX[i] < 200 && ac.pairsY[i] < 200) continue;
                    double px = ac.pairsX[i] == 0 ? 0 : (double) ac.singleX[i] / (2 * ac.pairsX[i]);
                    double lx = (px == 0 || ac.pairsX[i] == 0) ? Double.NaN
                            : ((double) ac.bothX[i] / ac.pairsX[i]) / (px * px);
                    double py = ac.pairsY[i] == 0 ? 0 : (double) ac.singleY[i] / (2 * ac.pairsY[i]);
                    double ly = (py == 0 || ac.pairsY[i] == 0) ? Double.NaN
                            : ((double) ac.bothY[i] / ac.pairsY[i]) / (py * py);
                    System.out.printf("    %-5d %8d  %.3f  %7.3f    %8d  %7.3f%n",
                            sd, ac.pairsX[i], px, lx, ac.pairsY[i], ly);
                    if (!Double.isNaN(lx) && !Double.isNaN(ly) && Math.abs(sd) <= 4) {
                        sumLX += lx; sumLY += ly; nL++;
                    }
                }
                if (nL > 0)
                    System.out.printf("    mean lift over |d|<=4 :  x %.3f   y %.3f%n",
                            sumLX / nL, sumLY / nL);
                System.out.println("    lift ~1.00 => INDEPENDENT per-square random");
                System.out.println("    lift > 1.2 => spatially correlated, needs a noise field");

                System.out.println("\n  MINORITY COMPONENTS by mean distance from the edge");
                System.out.println("    |d|    comps    cells   mean size   singletons   size>=5");
                for (int b = 0; b <= BAND + 1; b++) {
                    if (ac.compCount[b] < 30) continue;
                    System.out.printf("    %-5d %8d %8d %11.2f %10.1f%% %8.1f%%%n",
                            b, ac.compCount[b], ac.compCells[b],
                            (double) ac.compCells[b] / ac.compCount[b],
                            100.0 * ac.compSingles[b] / ac.compCount[b],
                            100.0 * ac.compBig[b] / ac.compCount[b]);
                }
                System.out.println("    small mean size at every |d| => all dither");
                System.out.println("    mean size rising with |d|    => tails are genuine");
                System.out.println("                                    regions, not dither");
                System.out.println("    x and y should agree; a large gap means a scan artifact");
                System.out.println("    ratio rising with k  => spatially correlated, noise field");
            }
        }
        System.out.println("\nIf the profile or the ratios move materially between window"
                + " sizes,\nthe majority filter is being measured rather than the map.");
    }

    static void bump(Acc ac, int run) { ac.runLen.merge(run, 1L, Long::sum); }

    static int rect(int[][] cs, int x0, int y0, int x1, int y1) {
        return cs[x1 + 1][y1 + 1] - cs[x0][y1 + 1] - cs[x1 + 1][y0] + cs[x0][y0];
    }

    static String key(String a, String b, int w) { return a + "|" + b + "|" + w; }

    static final int[] DX = {0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0};
}
