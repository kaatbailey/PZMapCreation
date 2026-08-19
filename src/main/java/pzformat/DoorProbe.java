package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pre-flight check for the exterior-door fix. Reads back squares that should
 * carry an exterior door and asserts the plain wall is GONE from that edge, so
 * the door is the only edge object. A square carrying both is the bug that made
 * doors solid in game (STATE §35, replaceTile append).
 *
 * This is the test the door work never had: §35 recorded doors as CONFIRMED on
 * the strength of the tiles being WRITTEN, never on the engine accepting them.
 * What we assert is the STACK SHAPE the engine needs: the DoorWall tile on the
 * edge, and NOT the plain Wall tile of the same orientation beside it.
 *
 * Works by index, not by name: it rebuilds the palette exactly as GisCells does
 * (TileIndex + SpriteNames + TilePalette.pick), so the wall and door tiles it
 * looks for are the same concrete tiles the generator writes. Depends only on
 * API the other tools already use.
 *
 * Predict before running (Charter §4):
 *   - On the map ON DISK NOW: both squares FAIL — each carries wall AND door.
 *   - After the fix + regenerate: both PASS — door present, matching wall gone.
 *
 * Usage:
 *   java -cp out pzformat.DoorProbe MEDIA_DIR MAP_DIR CELL X Y [X Y ...]
 *   java -cp out pzformat.DoorProbe "$PZ/media" "$MAPS/Muldraugh, KY" 200_201 43 69 43 84
 */
public final class DoorProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || (args.length - 3) % 2 != 0) {
            System.out.println("usage: DoorProbe MEDIA_DIR MAP_DIR CELL X Y [X Y ...]");
            return;
        }
        Path mediaDir = Path.of(args[0]);
        Path mapDir = Path.of(args[1]);
        String cell = args[2];

        TileIndex ti = TileIndex.load(mediaDir);
        Set<String> sprites = SpriteNames.load(mediaDir.resolve("texturepacks"));
        TilePalette pal = TilePalette.pick(ti, sprites);

        Path lh = mapDir.resolve(cell + ".lotheader");
        Path lp = mapDir.resolve("world_" + cell + ".lotpack");
        LotHeader h = LotHeader.read(lh);
        CellData c = CellData.load(lp, lh);

        // The concrete tiles GisCells writes, as names. We match by NAME
        // against the stack (resolved per square below), so we never depend on
        // whether tileIndex mutates the reloaded cell's table.
        String[] want = {pal.wallNorth, pal.wallWest, pal.doorWallNorth, pal.doorWallWest};
        System.out.println("wallN=" + pal.wallNorth);
        System.out.println("wallW=" + pal.wallWest);
        System.out.println("doorN=" + pal.doorWallNorth);
        System.out.println("doorW=" + pal.doorWallWest);

        int wnIdx = c.tileIndex(pal.wallNorth);
        int wwIdx = c.tileIndex(pal.wallWest);
        int doorN = c.tileIndex(pal.doorWallNorth);
        int doorW = c.tileIndex(pal.doorWallWest);
        System.out.println("resolved indices in this cell: wallN#" + wnIdx + " wallW#" + wwIdx
                + " doorN#" + doorN + " doorW#" + doorW);

        List<int[]> pts = new ArrayList<>();
        for (int i = 3; i + 1 < args.length; i += 2)
            pts.add(new int[]{Integer.parseInt(args[i]), Integer.parseInt(args[i + 1])});

        int pass = 0, fail = 0;
        for (int[] p : pts) {
            int x = p[0], y = p[1];
            int[] stack = c.tilesAt(x, y, 0);
            System.out.println("\n(" + x + "," + y + ")  stack=" + str(stack));

            if (stack == null) { System.out.println("  FAIL  no tiles"); fail++; continue; }

            boolean hasDoorN = has(stack, doorN), hasDoorW = has(stack, doorW);
            boolean hasWallN = has(stack, wnIdx), hasWallW = has(stack, wwIdx);

            boolean isDoor = hasDoorN || hasDoorW;
            boolean shadowedN = hasDoorN && hasWallN;   // door + matching wall = bug
            boolean shadowedW = hasDoorW && hasWallW;

            if (!isDoor) {
                System.out.println("  FAIL  no exterior DoorWall tile on this square");
                fail++;
            } else if (shadowedN || shadowedW) {
                System.out.println("  FAIL  door present but the plain wall is on the SAME"
                        + " edge — engine treats this as solid (the append bug)");
                fail++;
            } else {
                System.out.println("  PASS  door present, matching wall gone");
                pass++;
            }
        }

        System.out.println("\n" + pass + " pass, " + fail + " fail");
        if (fail > 0)
            System.out.println("A FAIL on the CURRENT map is expected — it predates the fix."
                    + " After patching and regenerating, re-run: both should PASS.");
    }

    static boolean has(int[] stack, int idx) {
        if (idx < 0 || stack == null) return false;
        for (int v : stack) if (v == idx) return true;
        return false;
    }

    static String str(int[] a) {
        if (a == null) return "(null)";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { sb.append(a[i]); if (i < a.length - 1) sb.append(", "); }
        return sb.append("]").toString();
    }
}
