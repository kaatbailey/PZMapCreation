package pzformat;

import java.nio.file.*;

/**
 * Layer-aware editing on a real cell, contrasted with the destructive fill.
 *
 * The in-game test used CellData.fill, which replaces a square's whole tile
 * stack — that is why the marked house lost its walls and furniture along with
 * its floor. The point of the semantics layer is that this is now a choice
 * rather than the only option.
 */
public final class EditDemo {

    public static void run(Path mediaDir, Path mapDir, String cellName,
                           int x0, int y0, int size, Path outDir) throws Exception {
        TileIndex ti = TileIndex.load(mediaDir);
        Path lh = mapDir.resolve(cellName + ".lotheader");
        Path lp = mapDir.resolve("world_" + cellName + ".lotpack");

        CellData original = CellData.load(lp, lh);
        CellData working = CellData.load(lp, lh);
        CellEditor ed = new CellEditor(working, ti);

        System.out.println("=== before ===");
        summarise(original, ti, x0, y0, size);

        // Pick a floor tile already used in this cell, so it is certain to render.
        String floorTile = null;
        for (String n : original.header.tileNames)
            if (ti.kindOf(n) == TileIndex.Kind.FLOOR && !ti.isOverlay(n)
                    && n.startsWith("floors_interior")) { floorTile = n; break; }
        if (floorTile == null) floorTile = "floors_interior_carpet_01_44";
        System.out.println("\nre-flooring " + size + "x" + size + " at (" + x0 + "," + y0
                + ") with '" + floorTile + "'");

        CellEditor.Edit edit = ed.fillFloor(x0, y0, size, size, 0, floorTile);
        System.out.println("squares touched: " + edit.squaresTouched()
                + "   undo depth: " + ed.undoDepth());

        System.out.println("\n=== after ===");
        summarise(working, ti, x0, y0, size);

        // The comparison that matters: what a destructive fill would have done.
        CellData destructive = CellData.load(lp, lh);
        destructive.fill(floorTile, x0, y0, size, size, 0);
        System.out.println("\n=== what the old destructive fill would have done ===");
        summarise(destructive, ti, x0, y0, size);

        System.out.println("\nundo, then verify we are back to the original byte for byte");
        ed.undo();
        CellData.Diff d = CellData.diff(original, working);
        System.out.println("   diff after undo: " + (d.isEmpty() ? "none" : d.toString()));
        boolean bytesMatch = java.util.Arrays.equals(
                Files.readAllBytes(lp), working.writeLotPack());
        System.out.println("   lotpack bytes identical to the original file: " + bytesMatch);

        if (outDir != null) {
            ed.redo();
            Files.createDirectories(outDir);
            Files.write(outDir.resolve(cellName + ".lotheader"), working.writeLotHeader());
            Files.write(outDir.resolve("world_" + cellName + ".lotpack"), working.writeLotPack());
            System.out.println("\nedited cell written to " + outDir);
        }
    }

    static void summarise(CellData c, TileIndex ti, int x0, int y0, int size) {
        int floors = 0, nWalls = 0, wWalls = 0, objects = 0, doors = 0, windows = 0;
        for (int x = x0; x < x0 + size; x++)
            for (int y = y0; y < y0 + size; y++) {
                if (x >= c.cellSize || y >= c.cellSize) continue;
                Square s = Square.at(c, ti, x, y, 0);
                if (s.floor != null) floors++;
                if (s.northWall != null) nWalls++;
                if (s.westWall != null) wWalls++;
                if (s.hasDoor) doors++;
                if (s.hasWindow) windows++;
                objects += s.objects.size();
            }
        System.out.printf("   floors %d   north walls %d   west walls %d   "
                + "doors %d   windows %d   objects %d%n",
                floors, nWalls, wWalls, doors, windows, objects);
    }
}
