package pzformat;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Dump every authored tree in a RECTANGLE, as an ASCII map with world
 * coordinates, so the in-game walk is a comparison rather than an impression.
 *
 * WHY THIS EXISTS. `Probe findprop` is hard-capped at 3 hits per cell by
 * `PropsProbe.find` (STATE §27) and scans from the origin outward, so on a map
 * whose x=0 column carries trees it can only ever return x=0 — which is exactly
 * the region the A2-gate must avoid. §25 rules out the map edge, and §26's
 * `Blending.removeTrees` deletes trees within 10 squares of any edge shared
 * with a procedural chunk (`rnd.nextInt(100) >= y*10`). A tool that can only
 * report the unusable region cannot run this test.
 *
 * This is the same failure §27 recorded: check what a tool CAN return before
 * believing what it did return.
 *
 * WHAT THE A2-GATE ASKS. Not "are there trees" — there are. It asks whether the
 * engine keeps the POSITIONS we authored or discards them and scatters its own.
 * That needs the authored positions written down before you look at the game.
 *
 *   T  a tile with a `tree` property
 *   .  a square with no tree
 *   ?  an empty square (none expected: GisCells fills every one, and a gap
 *      would flip the whole 8x8 chunk to procedural generation, STATE §7)
 *
 * Usage:
 *   java -cp out pzformat.TreeGrid &lt;mediaDir&gt; &lt;mapDir&gt; &lt;X_Y&gt; &lt;x0&gt; &lt;y0&gt; &lt;size&gt;
 *
 * Pick x0, y0 so the whole window sits in local 150..255 on both axes: far from
 * the block's outer perimeter, and the internal boundaries of a 2x2 cell block
 * are authored-to-authored, so `Blending` never fires on them.
 *
 *   java -cp out pzformat.TreeGrid "$PZ/media" "$GISMAP" 200_200 180 180 40
 */
public final class TreeGrid {

    private TreeGrid() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: TreeGrid <mediaDir> <mapDir> <X_Y> <x0> <y0> <size>");
            System.exit(2);
        }
        Path mediaDir = Paths.get(args[0]);
        Path mapDir = Paths.get(args[1]);
        String cellName = args[2];
        int x0 = Integer.parseInt(args[3]);
        int y0 = Integer.parseInt(args[4]);
        int size = Integer.parseInt(args[5]);

        String[] parts = cellName.split("_");
        int cellX = Integer.parseInt(parts[0]);
        int cellY = Integer.parseInt(parts[1]);

        TileIndex ti = TileIndex.load(mediaDir);
        CellData c = CellData.load(mapDir.resolve("world_" + cellName + ".lotpack"),
                                   mapDir.resolve(cellName + ".lotheader"));

        int x1 = Math.min(x0 + size, c.cellSize);
        int y1 = Math.min(y0 + size, c.cellSize);

        System.out.println("cell " + cellName + "  local x " + x0 + ".." + (x1 - 1)
                + "  y " + y0 + ".." + (y1 - 1));
        System.out.println("world tile origin: " + (cellX * 256 + x0) + ","
                + (cellY * 256 + y0));
        System.out.println();

        // Column header: the last two digits of each world x.
        StringBuilder h1 = new StringBuilder("        ");
        for (int x = x0; x < x1; x++) h1.append(((cellX * 256 + x) / 10) % 10);
        System.out.println(h1);
        StringBuilder h2 = new StringBuilder("        ");
        for (int x = x0; x < x1; x++) h2.append((cellX * 256 + x) % 10);
        System.out.println(h2);

        int trees = 0, empty = 0;
        StringBuilder list = new StringBuilder();
        for (int y = y0; y < y1; y++) {
            StringBuilder row = new StringBuilder();
            row.append(String.format("%7d ", cellY * 256 + y));
            for (int x = x0; x < x1; x++) {
                String[] names = c.tileNamesAt(x, y, 0);
                if (names == null || names.length == 0) { row.append('?'); empty++; continue; }
                String tree = null;
                for (String n : names) {
                    TileDefs.Tile t = ti.get(n);
                    if (t != null && t.props.containsKey("tree")) { tree = n; break; }
                }
                if (tree == null) { row.append('.'); continue; }
                row.append('T');
                trees++;
                list.append("  world ").append(cellX * 256 + x).append(',')
                    .append(cellY * 256 + y)
                    .append("   local ").append(x).append(',').append(y)
                    .append("   ").append(tree).append('\n');
            }
            System.out.println(row);
        }

        int squares = (x1 - x0) * (y1 - y0);
        System.out.println();
        System.out.printf("%d trees in %d squares (%.2f%%), %d empty squares%n",
                trees, squares, 100.0 * trees / squares, empty);
        if (empty > 0)
            System.out.println("WARNING: empty squares present. One gap flips the whole "
                    + "8x8 chunk to procedural generation (STATE §7), which would "
                    + "invalidate this test for that chunk.");
        System.out.println();
        System.out.println("authored tree positions:");
        System.out.print(list);
        System.out.println();
        System.out.println("Stand at the world tile printed above and compare. "
                + "Trees ON these tiles with bare ground between = positions are OURS. "
                + "Trees nearby at OTHER tiles = the engine re-scattered.");
    }
}
