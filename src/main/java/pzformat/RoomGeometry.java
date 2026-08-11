package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * Where do a room's walls actually sit relative to its rectangle?
 *
 * CellEditor.outlineRoom assumes edge-based walls mean:
 *
 *   north side -> NORTH wall on (x, ry)
 *   south side -> NORTH wall on (x, ry + rh)      the next square down
 *   west  side -> WEST  wall on (rx, y)
 *   east  side -> WEST  wall on (rx + rw, y)      the next square right
 *
 * Measured against vanilla Muldraugh 42_40 on 2026-08-10 and CONFIRMED:
 * south ry+rh 67.0% vs ry+rh-1 10.2%; east rx+rw 83.9% vs rx+rw-1 3.7%.
 * The worked example 'weldingworkshop' [57,169 12x12] matched all four
 * corners exactly, including the empty far corner at (rx+rw, ry+rh).
 *
 * TWO GUARDS, added after this probe drew a confident wrong conclusion.
 *
 * Run against generated cell 200_200 it printed "outlineRoom's offsets are
 * WRONG" off a 24.1% vs 22.2% difference. That input was GIS bounding boxes
 * containing diagonal wall runs: the walls sat at neither offset, the diagonal
 * crossed both rows once each, and the result was two coin flips. A probe that
 * cannot say "I don't know" manufactures findings.
 *
 *   1. AXIS-ALIGNMENT. A rect whose walls form a diagonal cannot answer the
 *      question at all. Detected per rect and excluded from the statistics
 *      before it can contribute a single count.
 *
 *   2. MARGIN. A ratio test, not a percentage-point gap: the winner must beat
 *      the alternative by MIN_RATIO and clear MIN_HITS absolute. Ratio rather
 *      than rate is the point — the attachedN mistake validated at 99.5%
 *      absolute while being wrong (STATE.md §11).
 *
 * Guard 1 is also a working prototype of the "expressible as a room rect"
 * validation rule the editor needs (STATE.md §17).
 */
public final class RoomGeometry {

    /** Winner must be at least this many times the alternative to conclude. */
    static final double MIN_RATIO = 2.0;
    /** ...and must have at least this many absolute hits. */
    static final int MIN_HITS = 30;
    /** A wall run counts as diagonal only in this slope band, tightly fitted,
     *  over at least this many rows/columns. Narrow on purpose: the job is to
     *  catch runs that advance, not rooms that have partitions. */
    static final double MIN_CONCENTRATION = 0.45;
    static final int MIN_WALLS_TO_JUDGE = 8;
    /** Rects thinner than this have too few interior lines to discriminate. */
    static final int MIN_SIDE_FOR_ALIGN_TEST = 4;

    public static void run(Path mediaDir, Path mapDir, String cellName) throws Exception {
        TileIndex ti = TileIndex.load(mediaDir);
        if ("--all".equals(cellName)) { sweep(ti, mapDir); return; }
        runCell(ti, mapDir, cellName);
    }

    // ================= corpus sweep: STATE.md §17 check 1 =================

    /**
     * Is a diagonal room outline something vanilla ever does, or is
     * axis-alignment a hard property of hand-authored PZ maps?
     *
     * Zero non-aligned rects across the corpus means FootprintSnap can refuse
     * off-axis footprints outright. A nonzero count means it warns and allows
     * an override, and at least one such room must be read before deciding.
     *
     * The headline number is not the interesting one. Cell 42_40 had 86 of 107
     * rects under 4x4 and therefore untestable, so "zero non-aligned" may cover
     * only a minority of the corpus. The size histogram says how much the
     * answer is worth.
     */
    static void sweep(TileIndex ti, Path mapDir) throws Exception {
        List<Path> headers = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> ds =
                     java.nio.file.Files.newDirectoryStream(mapDir, "*.lotheader")) {
            for (Path p : ds) headers.add(p);
        }
        Collections.sort(headers);
        System.out.println("=== alignment sweep: " + mapDir.getFileName() + " ===");
        System.out.println(headers.size() + " lotheaders\n");
        if (headers.isEmpty()) return;
        System.out.println("Expect no output until the summary unless a non-aligned");
        System.out.println("rect is found. Progress every 500 cells.\n");

        long tested = 0, untestable = 0, offLevel = 0, nonAligned = 0;
        int cells = 0, cellsFailed = 0;
        Map<Integer, Integer> smallSides = new TreeMap<>();   // min(rw,rh) for untestable
        List<String> offenders = new ArrayList<>();

        long t0 = System.currentTimeMillis();
        for (Path hf : headers) {
            String name = hf.getFileName().toString().replace(".lotheader", "");
            Path pf = mapDir.resolve("world_" + name + ".lotpack");
            if (!java.nio.file.Files.exists(pf)) continue;
            cells++;
            if (cells % 500 == 0)
                System.out.println("   ... " + cells + " cells, "
                        + (System.currentTimeMillis() - t0) / 1000 + "s");
            try {
                LotHeader h = LotHeader.read(hf);
                CellData c = CellData.load(pf, hf);
                for (LotHeader.Room room : h.rooms) {
                    if (room.floor < c.minLevel || room.floor > c.maxLevel) {
                        offLevel += room.rects.size();
                        continue;
                    }
                    for (int[] r : room.rects) {
                        Align a = alignment(c, ti, r[0], r[1], r[2], r[3], room.floor);
                        if (!a.testable) {
                            untestable++;
                            smallSides.merge(Math.min(r[2], r[3]), 1, Integer::sum);
                            continue;
                        }
                        tested++;
                        if (!a.aligned) {
                            nonAligned++;
                            if (offenders.size() < 20)
                                offenders.add(String.format(
                                        "%s  '%s' [%d,%d %dx%d] z=%d — north %.2f (%d), west %.2f (%d)",
                                        name, room.name, r[0], r[1], r[2], r[3], room.floor,
                                        a.northConc, a.northWalls, a.westConc, a.westWalls));
                        }
                    }
                }
            } catch (Exception e) {
                cellsFailed++;
                if (cellsFailed <= 5)
                    System.out.println("   " + name + ": " + e);
            }
        }

        long total = tested + untestable + offLevel;
        System.out.println("\n--- summary ---");
        System.out.printf("cells:        %d  (%d failed to parse)%n", cells, cellsFailed);
        System.out.printf("rects:        %d total%n", total);
        System.out.printf("   tested:    %d  (%.1f%%)%n", tested, pct100(tested, total));
        System.out.printf("   untestable %d  (%.1f%%)   under %d on a side%n",
                untestable, pct100(untestable, total), MIN_SIDE_FOR_ALIGN_TEST);
        System.out.printf("   off-level: %d%n", offLevel);
        System.out.printf("non-aligned:  %d  (%.2f%% of tested)%n",
                nonAligned, pct100(nonAligned, tested));

        System.out.println("\nuntestable rects by shorter side:");
        for (Map.Entry<Integer, Integer> e : smallSides.entrySet())
            System.out.printf("   %d: %d%n", e.getKey(), e.getValue());

        if (!offenders.isEmpty()) {
            System.out.println("\nnon-aligned examples (first " + offenders.size() + "):");
            for (String s : offenders) System.out.println("   " + s);
            System.out.println("\n=> Off-axis rooms EXIST in vanilla. FootprintSnap should warn,");
            System.out.println("   not refuse. Read one of the above before designing it.");
        } else if (tested > 0) {
            System.out.println("\n=> No off-axis room found among " + tested + " testable rects.");
            System.out.println("   Axis-alignment looks like a hard property, but note the");
            System.out.println("   untestable fraction above before leaning on that.");
        }
        System.out.printf("%nelapsed: %ds%n", (System.currentTimeMillis() - t0) / 1000);
    }

    static double pct100(long a, long b) { return b == 0 ? 0 : 100.0 * a / b; }

    // ================= single cell =================

    static void runCell(TileIndex ti, Path mapDir, String cellName) throws Exception {
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);

        System.out.println("=== room wall geometry: " + cellName + " ===");
        System.out.println(h.rooms.size() + " rooms\n");

        int[] hit = new int[4], tot = new int[4];          // N, S, W, E at the assumed offset
        int[] altHit = new int[2], altTot = new int[2];    // S, E at offset-1
        String[] side = {"north", "south", "west", "east"};

        int rectsUsed = 0, rectsSkewed = 0, rectsUntested = 0, rectsOffLevel = 0;
        List<int[]> usable = new ArrayList<>();            // rx, ry, rw, rh, z, roomIndex
        List<String> skewExamples = new ArrayList<>();

        int roomIndex = -1;
        for (LotHeader.Room room : h.rooms) {
            roomIndex++;
            if (room.floor < c.minLevel || room.floor > c.maxLevel) {
                rectsOffLevel += room.rects.size();
                continue;
            }
            for (int[] r : room.rects) {
                int rx = r[0], ry = r[1], rw = r[2], rh = r[3];
                int z = room.floor;

                // ---- GUARD 1: exclude rects whose walls are not axis-aligned.
                Align a = alignment(c, ti, rx, ry, rw, rh, z);
                if (a.testable && !a.aligned) {
                    rectsSkewed++;
                    if (skewExamples.size() < 5)
                        skewExamples.add(String.format(
                                "'%s' [%d,%d %dx%d] z=%d — north conc %.2f (%d walls), "
                                        + "west conc %.2f (%d walls)",
                                room.name, rx, ry, rw, rh, z,
                                a.northConc, a.northWalls, a.westConc, a.westWalls));
                    continue;
                }
                if (!a.testable) rectsUntested++;
                rectsUsed++;
                usable.add(new int[]{rx, ry, rw, rh, z, roomIndex});

                for (int x = rx; x < rx + rw; x++) {
                    tot[0]++;
                    if (wallOn(c, ti, x, ry, z, TileIndex.Edge.NORTH)) hit[0]++;

                    tot[1]++;
                    if (wallOn(c, ti, x, ry + rh, z, TileIndex.Edge.NORTH)) hit[1]++;
                    altTot[0]++;
                    if (wallOn(c, ti, x, ry + rh - 1, z, TileIndex.Edge.NORTH)) altHit[0]++;
                }
                for (int y = ry; y < ry + rh; y++) {
                    tot[2]++;
                    if (wallOn(c, ti, rx, y, z, TileIndex.Edge.WEST)) hit[2]++;

                    tot[3]++;
                    if (wallOn(c, ti, rx + rw, y, z, TileIndex.Edge.WEST)) hit[3]++;
                    altTot[1]++;
                    if (wallOn(c, ti, rx + rw - 1, y, z, TileIndex.Edge.WEST)) altHit[1]++;
                }
            }
        }

        // ---------------- population actually measured

        System.out.println("rects: " + rectsUsed + " measured, "
                + rectsSkewed + " excluded as not axis-aligned, "
                + rectsOffLevel + " off-level");
        if (rectsUntested > 0)
            System.out.println("   (" + rectsUntested + " of the measured rects were too "
                    + "thin to test for alignment — under "
                    + MIN_SIDE_FOR_ALIGN_TEST + " on a side)");
        if (!skewExamples.isEmpty()) {
            System.out.println("\nexcluded examples:");
            for (String s : skewExamples) System.out.println("   " + s);
        }
        if (rectsUsed == 0) {
            System.out.println("\nNo axis-aligned rects. Nothing can be concluded from this cell.");
            System.out.println("A room whose walls run diagonally is not expressible as a room");
            System.out.println("rect at all — see STATE.md §17.");
            return;
        }

        System.out.println("\nwall present where the model predicts:");
        for (int i = 0; i < 4; i++)
            System.out.printf("   %-6s %5d / %-5d  (%.1f%%)%n",
                    side[i], hit[i], tot[i], pct(hit[i], tot[i]));

        System.out.println("\nfar sides, off-by-one alternative (inside the rect, not beyond it):");
        System.out.printf("   south at ry+rh-1  %5d / %-5d  (%.1f%%)   vs  ry+rh  (%.1f%%)%n",
                altHit[0], altTot[0], pct(altHit[0], altTot[0]), pct(hit[1], tot[1]));
        System.out.printf("   east  at rx+rw-1  %5d / %-5d  (%.1f%%)   vs  rx+rw  (%.1f%%)%n",
                altHit[1], altTot[1], pct(altHit[1], altTot[1]), pct(hit[3], tot[3]));

        // ---------------- GUARD 2: margin

        Verdict south = decide("south", hit[1], tot[1], "ry+rh (next square down)",
                altHit[0], altTot[0], "ry+rh-1 (last row inside)");
        Verdict east = decide("east", hit[3], tot[3], "rx+rw (next square right)",
                altHit[1], altTot[1], "rx+rw-1 (last column inside)");

        System.out.println();
        System.out.println("   => south wall " + south.text);
        System.out.println("   => east  wall " + east.text);

        if (!south.conclusive || !east.conclusive) {
            System.out.println("\n   INCONCLUSIVE — no claim made about outlineRoom.");
            System.out.println("   Requires the winner to beat the alternative by "
                    + MIN_RATIO + "x with at least " + MIN_HITS + " hits.");
        } else if (south.beyond && east.beyond) {
            System.out.println("\n   outlineRoom's offsets are CORRECT");
        } else {
            System.out.println("\n   outlineRoom's offsets are WRONG for at least one side");
        }

        System.out.println("\nNote: none of these reach 100%. Rooms share walls with"
                + "\nneighbours and open onto each other, so a missing wall is often a"
                + "\ndoorway or a shared partition rather than a modelling error. What"
                + "\nmatters is which offset scores higher, not the absolute rate.");

        // ---------------- worked example, from the measured population only

        int[] bestRect = null;
        String bestName = null;
        for (int[] u : usable)
            if (u[2] >= 4 && u[3] >= 4
                    && (bestRect == null || u[2] * u[3] > bestRect[2] * bestRect[3])) {
                bestRect = u;
                bestName = h.rooms.get(u[5]).name;
            }
        if (bestRect == null) {
            System.out.println("\n(no axis-aligned rect at least 4x4 to show as a worked example)");
            return;
        }
        int rx = bestRect[0], ry = bestRect[1], rw = bestRect[2], rh = bestRect[3], z = bestRect[4];
        System.out.println("\n=== worked example: '" + bestName + "' ["
                + rx + "," + ry + " " + rw + "x" + rh + "] z=" + z + " ===");
        System.out.println("   (N = north wall present, W = west wall, + = both, . = none)");
        for (int y = ry - 1; y <= ry + rh + 1; y++) {
            StringBuilder sb = new StringBuilder(String.format("   y=%-4d ", y));
            for (int x = rx - 1; x <= rx + rw + 1; x++) {
                boolean n = wallOn(c, ti, x, y, z, TileIndex.Edge.NORTH);
                boolean w = wallOn(c, ti, x, y, z, TileIndex.Edge.WEST);
                sb.append(n && w ? '+' : n ? 'N' : w ? 'W' : '.');
            }
            boolean inside = y >= ry && y < ry + rh;
            sb.append(inside ? "   <- inside" : "");
            System.out.println(sb);
        }
        System.out.println("   x from " + (rx - 1) + " to " + (rx + rw + 1)
                + "; the rect spans x " + rx + ".." + (rx + rw - 1));
        System.out.println("\n   expect: '+' at " + rx + "," + ry
                + "   'W' at " + (rx + rw) + "," + ry
                + "   'N' at " + rx + "," + (ry + rh)
                + "   '.' at " + (rx + rw) + "," + (ry + rh));
    }

    // ---------------- guard 1: is this rect's wall geometry axis-aligned?

    /**
     * Is this rect's wall geometry expressible as a rectangle at all?
     *
     * A rectangle outline concentrates its walls on exactly two lines: north
     * walls on the top and bottom rows, west walls on the left and right
     * columns. A diagonal run spreads them across every line. So measure the
     * fraction of wall squares lying on the two densest lines.
     *
     * Measured on real data before this was written:
     *
     *   GIS diagonal   [199,221 26x15]   north 0.192   west 0.200
     *   clean rect     weldingworkshop   north 1.000   west 1.000
     *   rect+partition (synthetic)       north 1.000   west 0.667
     *
     * TWO EARLIER APPROACHES FAILED. Recorded so they are not retried:
     *
     *   1. Fraction of interior rows carrying a wall, excluded above 25%.
     *      On a 4-wide rect one legitimate partition is 33%, so it flagged 674
     *      vanilla bathrooms, halls and barns and concluded "off-axis rooms
     *      exist in vanilla". They do not. Fraction conflates "several walls"
     *      with "few columns".
     *   2. Least-squares slope of per-row mean wall x, excluding slope ~1 with
     *      r2 >= 0.9. Cleared all 674 false positives but ALSO cleared the
     *      known diagonal: the rect holds two parallel runs, so the per-row
     *      mean jumps between them (203, 220, 203) and r2 came out 0.227.
     *      Averaging destroyed the structure being looked for.
     */
    static Align alignment(CellData c, TileIndex ti, int rx, int ry, int rw, int rh, int z) {
        Align a = new Align();
        if (rw < MIN_SIDE_FOR_ALIGN_TEST || rh < MIN_SIDE_FOR_ALIGN_TEST) {
            a.testable = false;
            a.aligned = true;
            return a;
        }
        a.testable = true;

        int[] rowCounts = new int[rh + 1];
        for (int y = ry; y <= ry + rh; y++)
            for (int x = rx; x <= rx + rw; x++)
                if (wallOn(c, ti, x, y, z, TileIndex.Edge.NORTH)) rowCounts[y - ry]++;

        int[] colCounts = new int[rw + 1];
        for (int x = rx; x <= rx + rw; x++)
            for (int y = ry; y <= ry + rh; y++)
                if (wallOn(c, ti, x, y, z, TileIndex.Edge.WEST)) colCounts[x - rx]++;

        a.northConc = concentration(rowCounts);
        a.westConc = concentration(colCounts);
        a.northWalls = total(rowCounts);
        a.westWalls = total(colCounts);

        // A diagonal is low on BOTH axes; a partition is low on one. Require both,
        // else prisoncells (0.30/1.00) and stables (1.00/0.38) false-positive.
        boolean nBad = a.northWalls >= MIN_WALLS_TO_JUDGE && a.northConc < MIN_CONCENTRATION;
        boolean wBad = a.westWalls >= MIN_WALLS_TO_JUDGE && a.westConc < MIN_CONCENTRATION;
        a.aligned = !(nBad && wBad);
        return a;
    }

    /** Fraction of walls sitting on the two most populated lines. */
    static double concentration(int[] counts) {
        int tot = total(counts);
        if (tot == 0) return 1.0;
        int a = 0, b = 0;
        for (int v : counts) {
            if (v > a) { b = a; a = v; }
            else if (v > b) { b = v; }
        }
        return (double) (a + b) / tot;
    }

    static int total(int[] counts) {
        int s = 0;
        for (int v : counts) s += v;
        return s;
    }

    static final class Align {
        boolean testable, aligned;
        double northConc, westConc;
        int northWalls, westWalls;
    }

    // ---------------- guard 2: is the margin big enough to conclude?

    static Verdict decide(String name, int hitBeyond, int totBeyond, String labelBeyond,
                          int hitInside, int totInside, String labelInside) {
        double pBeyond = pct(hitBeyond, totBeyond), pInside = pct(hitInside, totInside);
        Verdict v = new Verdict();
        v.beyond = pBeyond > pInside;

        double win = Math.max(pBeyond, pInside), lose = Math.min(pBeyond, pInside);
        int winHits = pBeyond > pInside ? hitBeyond : hitInside;
        double ratio = lose <= 0 ? Double.POSITIVE_INFINITY : win / lose;

        v.conclusive = ratio >= MIN_RATIO && winHits >= MIN_HITS;
        if (v.conclusive) {
            v.text = "sits at " + (v.beyond ? labelBeyond : labelInside)
                    + String.format("   (%.1fx margin)", ratio);
        } else {
            v.text = "INCONCLUSIVE — "
                    + String.format("%.1f%% vs %.1f%%, %.2fx margin, %d hits",
                    pBeyond, pInside, ratio, winHits);
        }
        return v;
    }

    static final class Verdict {
        boolean beyond, conclusive;
        String text;
    }

    // ---------------- unchanged

    static boolean wallOn(CellData c, TileIndex ti, int x, int y, int z, TileIndex.Edge edge) {
        if (x < 0 || y < 0 || x >= c.cellSize || y >= c.cellSize) return false;
        if (z < c.minLevel || z > c.maxLevel) return false;
        String[] names = c.tileNamesAt(x, y, z);
        if (names == null) return false;
        for (String n : names) {
            if (!ti.isStructuralWall(n)) continue;
            TileIndex.Edge e = ti.edgeOf(n);
            if (e == edge || e == TileIndex.Edge.BOTH) return true;
        }
        return false;
    }

    static double pct(int a, int b) { return b == 0 ? 0 : 100.0 * a / b; }
}
