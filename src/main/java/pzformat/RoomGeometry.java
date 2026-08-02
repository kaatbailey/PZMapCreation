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
 * That follows from walls belonging to a square's north and west edges, but it
 * is reasoning rather than measurement — and reasoning is what produced both
 * the x/y transposition and the attachedN mistake. So measure it: for thousands
 * of real room edges, is there a wall where the model predicts?
 *
 * Each side is scored separately, and the far sides are also scored against the
 * off-by-one alternative (ry + rh - 1, rx + rw - 1). Whichever wins is right.
 */
public final class RoomGeometry {

    public static void run(Path mediaDir, Path mapDir, String cellName) throws Exception {
        TileIndex ti = TileIndex.load(mediaDir);
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);

        System.out.println("=== room wall geometry: " + cellName + " ===");
        System.out.println(h.rooms.size() + " rooms\n");

        int[] hit = new int[4], tot = new int[4];          // N, S, W, E at the assumed offset
        int[] altHit = new int[2], altTot = new int[2];    // S, E at offset-1
        String[] side = {"north", "south", "west", "east"};

        for (LotHeader.Room room : h.rooms) {
            if (room.floor < c.minLevel || room.floor > c.maxLevel) continue;
            for (int[] r : room.rects) {
                int rx = r[0], ry = r[1], rw = r[2], rh = r[3];
                int z = room.floor;

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

        System.out.println("wall present where the model predicts:");
        for (int i = 0; i < 4; i++)
            System.out.printf("   %-6s %5d / %-5d  (%.1f%%)%n",
                    side[i], hit[i], tot[i], pct(hit[i], tot[i]));

        System.out.println("\nfar sides, off-by-one alternative (inside the rect, not beyond it):");
        System.out.printf("   south at ry+rh-1  %5d / %-5d  (%.1f%%)   vs  ry+rh  (%.1f%%)%n",
                altHit[0], altTot[0], pct(altHit[0], altTot[0]), pct(hit[1], tot[1]));
        System.out.printf("   east  at rx+rw-1  %5d / %-5d  (%.1f%%)   vs  rx+rw  (%.1f%%)%n",
                altHit[1], altTot[1], pct(altHit[1], altTot[1]), pct(hit[3], tot[3]));

        boolean southBeyond = pct(hit[1], tot[1]) > pct(altHit[0], altTot[0]);
        boolean eastBeyond = pct(hit[3], tot[3]) > pct(altHit[1], altTot[1]);
        System.out.println("\n   => south wall sits at " + (southBeyond ? "ry+rh (next square down)"
                : "ry+rh-1 (last row inside)"));
        System.out.println("   => east  wall sits at " + (eastBeyond ? "rx+rw (next square right)"
                : "rx+rw-1 (last column inside)"));
        if (southBeyond && eastBeyond)
            System.out.println("   outlineRoom's offsets are CORRECT");
        else
            System.out.println("   outlineRoom's offsets are WRONG for at least one side");

        System.out.println("\nNote: none of these reach 100%. Rooms share walls with"
                + "\nneighbours and open onto each other, so a missing wall is often a"
                + "\ndoorway or a shared partition rather than a modelling error. What"
                + "\nmatters is which offset scores higher, not the absolute rate.");

        // Worked example, so the numbers can be checked by eye.
        LotHeader.Room best = null; int[] bestRect = null;
        for (LotHeader.Room room : h.rooms)
            for (int[] r : room.rects)
                if (r[2] >= 4 && r[3] >= 4 && (bestRect == null || r[2] * r[3] > bestRect[2] * bestRect[3])) {
                    best = room; bestRect = r;
                }
        if (best == null) return;
        int rx = bestRect[0], ry = bestRect[1], rw = bestRect[2], rh = bestRect[3], z = best.floor;
        System.out.println("\n=== worked example: '" + best.name + "' ["
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
    }

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
