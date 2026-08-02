package pzformat;

import java.nio.file.Path;
import java.util.*;

/**
 * Reports the tile property vocabulary, then tests the classification against
 * real map data.
 *
 * The prediction: a room's PERIMETER squares should carry wall tiles far more
 * often than its INTERIOR squares, and interiors should be floored. If
 * classification is meaningful those two rates diverge sharply; if it is
 * guesswork they will look similar. Room rectangles come from the lotheader and
 * are independent of tile properties, so this is a genuine external check —
 * the same shape of test that exposed the x/y transposition.
 */
public final class PropsProbe {

    public static void run(Path mediaDir, Path mapDir, String cellName) throws Exception {
        TileIndex ti = TileIndex.load(mediaDir);
        System.out.println("tile definitions: " + ti.byName.size() + " tiles from "
                + ti.fileCount + " files, " + ti.tilesetCount + " tilesets\n");

        System.out.println("=== property vocabulary ===");
        ti.reportVocabulary("wall", "WallN", "WallW", "WallNW",
                "DoorWallN", "DoorWallW", "WindowN", "WindowW",
                "doorN", "doorW", "windowN", "windowW",
                "attachedN", "attachedW", "WallOverlay", "FloorOverlay",
                "WindowShape", "container", "solid", "solidtrans", "attachedFloor");

        Map<TileIndex.Kind, Integer> kinds = new EnumMap<>(TileIndex.Kind.class);
        for (String n : ti.byName.keySet()) kinds.merge(ti.kindOf(n), 1, Integer::sum);
        System.out.println("\n=== classification over all tiles ===");
        kinds.forEach((k, v) -> System.out.printf("   %-12s %d%n", k, v));

        if (mapDir == null || cellName == null) return;

        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);

        System.out.println("\n=== does classification match room geometry? (" + cellName + ") ===");
        System.out.println("prediction: perimeter squares carry walls, interiors carry floors\n");

        int perimTotal = 0, perimWall = 0, interTotal = 0, interWall = 0;
        int interFloor = 0, perimFloor = 0;

        for (LotHeader.Room room : h.rooms) {
            for (int[] rect : room.rects) {
                int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
                if (room.floor < c.minLevel || room.floor > c.maxLevel) continue;
                for (int x = rx; x < rx + rw; x++)
                    for (int y = ry; y < ry + rh; y++) {
                        if (x < 0 || y < 0 || x >= c.cellSize || y >= c.cellSize) continue;
                        String[] names = c.tileNamesAt(x, y, room.floor);
                        boolean wall = false, floor = false;
                        if (names != null)
                            for (String n : names) {
                                TileIndex.Kind k = ti.kindOf(n);
                                if (k == TileIndex.Kind.WALL || k == TileIndex.Kind.DOOR
                                        || k == TileIndex.Kind.WINDOW) wall = true;
                                if (k == TileIndex.Kind.FLOOR) floor = true;
                            }
                        boolean edge = x == rx || y == ry || x == rx + rw - 1 || y == ry + rh - 1;
                        if (edge) {
                            perimTotal++;
                            if (wall) perimWall++;
                            if (floor) perimFloor++;
                        } else {
                            interTotal++;
                            if (wall) interWall++;
                            if (floor) interFloor++;
                        }
                    }
            }
        }

        System.out.printf("   perimeter squares : %5d   with wall/door/window %5d  (%.1f%%)   with floor %.1f%%%n",
                perimTotal, perimWall, pct(perimWall, perimTotal), pct(perimFloor, perimTotal));
        System.out.printf("   interior squares  : %5d   with wall/door/window %5d  (%.1f%%)   with floor %.1f%%%n",
                interTotal, interWall, pct(interWall, interTotal), pct(interFloor, interTotal));

        double pw = pct(perimWall, perimTotal), iw = pct(interWall, interTotal);
        if (pw > iw * 2)
            System.out.println("\n   => walls concentrate on room perimeters: classification is meaningful");
        else if (interTotal == 0)
            System.out.println("\n   => no interior squares to compare (rooms are all thin)");
        else
            System.out.println("\n   => walls are NOT concentrated on perimeters: the wall"
                    + " classification is wrong, or rects do not mean what we assume");

        // Wall edge orientation: north walls should cluster at low y within a room,
        // west walls at low x. Another prediction the data can refute.
        int nAtTop = 0, nElsewhere = 0, wAtLeft = 0, wElsewhere = 0;
        for (LotHeader.Room room : h.rooms) {
            if (room.floor < c.minLevel || room.floor > c.maxLevel) continue;
            for (int[] rect : room.rects) {
                int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
                for (int x = rx; x < rx + rw; x++)
                    for (int y = ry; y < ry + rh; y++) {
                        if (x < 0 || y < 0 || x >= c.cellSize || y >= c.cellSize) continue;
                        String[] names = c.tileNamesAt(x, y, room.floor);
                        if (names == null) continue;
                        for (String n : names) {
                            TileIndex.Kind k = ti.kindOf(n);
                            if (k != TileIndex.Kind.WALL && k != TileIndex.Kind.DOOR
                                    && k != TileIndex.Kind.WINDOW) continue;
                            TileIndex.Edge e = ti.edgeOf(n);
                            if (e == TileIndex.Edge.NORTH || e == TileIndex.Edge.BOTH) {
                                if (y == ry) nAtTop++; else nElsewhere++;
                            }
                            if (e == TileIndex.Edge.WEST || e == TileIndex.Edge.BOTH) {
                                if (x == rx) wAtLeft++; else wElsewhere++;
                            }
                        }
                    }
            }
        }
        System.out.println("\n   wall edge orientation:");
        System.out.printf("      NORTH walls on the room's north row : %d of %d  (%.1f%%)%n",
                nAtTop, nAtTop + nElsewhere, pct(nAtTop, nAtTop + nElsewhere));
        System.out.printf("      WEST  walls on the room's west column: %d of %d  (%.1f%%)%n",
                wAtLeft, wAtLeft + wElsewhere, pct(wAtLeft, wAtLeft + wElsewhere));
        System.out.println("      (a room's north row is its lowest y, west column its lowest x)");

        squareModel(ti, c, h);
    }

    /** Show the Square view, and check wall continuity around a building. */
    static void squareModel(TileIndex ti, CellData c, LotHeader h) {
        System.out.println("\n=== Square model ===");

        // A room with a decent interior makes the clearest example.
        LotHeader.Room best = null;
        int[] bestRect = null;
        for (LotHeader.Room room : h.rooms)
            for (int[] r : room.rects)
                if (r[2] >= 5 && r[3] >= 5 && (bestRect == null || r[2] * r[3] > bestRect[2] * bestRect[3])) {
                    best = room; bestRect = r;
                }
        if (best == null) { System.out.println("   no room large enough to sample"); return; }

        int rx = bestRect[0], ry = bestRect[1], rw = bestRect[2], rh = bestRect[3];
        System.out.println("   sampling room '" + best.name + "' rect ["
                + rx + "," + ry + " " + rw + "x" + rh + "] z=" + best.floor);

        int shown = 0;
        for (int y = ry; y < ry + rh && shown < 8; y++)
            for (int x = rx; x < rx + rw && shown < 8; x++) {
                Square sq = Square.at(c, ti, x, y, best.floor);
                if (sq.isEmpty()) continue;
                System.out.println("      " + sq);
                shown++;
            }

        // Wall continuity: the room's north row should carry north walls along
        // its whole width, the west column west walls along its height.
        int northRow = 0, westCol = 0;
        for (int x = rx; x < rx + rw; x++)
            if (Square.at(c, ti, x, ry, best.floor).northWall != null) northRow++;
        for (int y = ry; y < ry + rh; y++)
            if (Square.at(c, ti, rx, y, best.floor).westWall != null) westCol++;
        System.out.printf("%n   north wall runs %d / %d squares of the north row%n", northRow, rw);
        System.out.printf("   west  wall runs %d / %d squares of the west column%n", westCol, rh);
        System.out.println("   (gaps are normally doorways)");

        // Whole-cell tallies.
        int floors = 0, nWalls = 0, wWalls = 0, doors = 0, windows = 0,
            containers = 0, blocked = 0, occupied = 0;
        for (int z = c.minLevel; z <= c.maxLevel; z++)
            for (int x = 0; x < c.cellSize; x++)
                for (int y = 0; y < c.cellSize; y++) {
                    Square sq = Square.at(c, ti, x, y, z);
                    if (sq.isEmpty()) continue;
                    occupied++;
                    if (sq.floor != null) floors++;
                    if (sq.northWall != null) nWalls++;
                    if (sq.westWall != null) wWalls++;
                    if (sq.hasDoor) doors++;
                    if (sq.hasWindow) windows++;
                    if (sq.containerType != null) containers++;
                    if (sq.blocksMovement) blocked++;
                }
        System.out.println("\n   whole cell:");
        System.out.printf("      occupied squares %d   with floor %d   north walls %d   west walls %d%n",
                occupied, floors, nWalls, wWalls);
        System.out.printf("      doors %d   windows %d   containers %d   blocking %d%n",
                doors, windows, containers, blocked);
    }

    static double pct(int a, int b) { return b == 0 ? 0 : 100.0 * a / b; }

    /**
     * Find the first square in a cell holding a tile with the given property,
     * then dump it. Saves hunting for an example by hand — and hand-hunting is
     * how assumptions creep back in.
     */
    public static void find(Path mediaDir, Path mapDir, String cellName,
                            String property) throws Exception {
        TileIndex ti = TileIndex.load(mediaDir);
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        CellData c = CellData.load(lp, lh);

        System.out.println("searching cell " + cellName + " for a tile with '"
                + property + "'\n");
        int found = 0;
        for (int z = c.minLevel; z <= c.maxLevel && found < 3; z++)
            for (int x = 0; x < c.cellSize && found < 3; x++)
                for (int y = 0; y < c.cellSize && found < 3; y++) {
                    String[] names = c.tileNamesAt(x, y, z);
                    if (names == null) continue;
                    boolean hit = false;
                    for (String n : names) {
                        TileDefs.Tile t = ti.get(n);
                        if (t != null && t.props.containsKey(property)) { hit = true; break; }
                    }
                    if (!hit) continue;
                    found++;
                    dump(mediaDir, mapDir, cellName, x, y, z);
                    System.out.println();
                }
        if (found == 0) System.out.println("no square in this cell carries that property");
    }

    /**
     * Dump every tile on a square with its full property set.
     *
     * Classification kept being written from assumptions about what the
     * vocabulary means. This reads it instead: point at a square known to sit
     * on a room's wall line and see exactly what the wall tile carries.
     */
    public static void dump(Path mediaDir, Path mapDir, String cellName,
                            int x, int y, int z) throws Exception {
        TileIndex ti = TileIndex.load(mediaDir);
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");
        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);

        System.out.println("=== square (" + x + "," + y + ") z=" + z
                + " in cell " + cellName + " ===");
        String[] names = c.tileNamesAt(x, y, z);
        System.out.println("room id: " + c.roomAt(x, y, z));
        if (names == null) { System.out.println("(empty square)"); return; }

        for (String n : names) {
            TileDefs.Tile t = ti.get(n);
            System.out.println("\n   " + n);
            System.out.println("      classified: " + ti.kindOf(n)
                    + "   edge: " + ti.edgeOf(n)
                    + "   overlay: " + ti.isOverlay(n));
            if (t == null) { System.out.println("      NO TILE DEFINITION"); continue; }
            if (t.props.isEmpty()) { System.out.println("      (no properties)"); continue; }
            for (Map.Entry<String, String> e : t.props.entrySet())
                System.out.printf("      %-22s %s%n", e.getKey(),
                        e.getValue().isEmpty() ? "(flag)" : e.getValue());
        }

        // Which rooms claim this square, for context.
        for (int i = 0; i < h.rooms.size(); i++) {
            LotHeader.Room r = h.rooms.get(i);
            for (int[] rect : r.rects)
                if (x >= rect[0] && x < rect[0] + rect[2]
                        && y >= rect[1] && y < rect[1] + rect[3])
                    System.out.println("\n   inside room " + i + " '" + r.name
                            + "' floor=" + r.floor + " rect [" + rect[0] + "," + rect[1]
                            + " " + rect[2] + "x" + rect[3] + "]");
        }
    }
}
