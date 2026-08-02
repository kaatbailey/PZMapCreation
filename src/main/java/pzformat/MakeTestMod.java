package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * End-to-end proof: make one deliberate change to a cell, prove the change is
 * surgical, and package it as a mod the game can load.
 *
 * Round-tripping proves the bytes are right. It does NOT prove the game is
 * happy with them. This is the step that closes that gap, and it is worth doing
 * before building anything on top.
 *
 * B42 mod layout differs from B41 — mods need a versioned subfolder or the game
 * silently ignores them (no error, they just never appear in the mod list):
 *
 *   ~/Zomboid/mods/<ModName>/
 *       mod.info
 *       common/
 *       42.0/
 *           mod.info
 *           media/maps/<MapName>/
 *               map.info
 *               X_Y.lotheader
 *               world_X_Y.lotpack
 *
 * Nothing here writes to the game install. Source files are read only.
 */
public final class MakeTestMod {

    /** A floor tile that is hard to mistake for terrain when you walk onto it. */
    public static final String MARKER_TILE = "carpentry_02_56";

    /**
     * Target a WORLD coordinate instead of a cell. Far easier to verify: aim at
     * a spawn point from spawnpoints.lua and the marker is under your feet when
     * the game starts, with no debug tooling needed.
     */
    public static void runAtWorld(Path mapDir, Path mediaDir, Path modsDir, String modName,
                                  int worldX, int worldY, int size) throws Exception {
        int cellX = Math.floorDiv(worldX, 256), cellY = Math.floorDiv(worldY, 256);
        int localX = Math.floorMod(worldX, 256), localY = Math.floorMod(worldY, 256);
        String cell = cellX + "_" + cellY;
        System.out.println("world (" + worldX + "," + worldY + ")  ->  cell " + cell
                + "  local (" + localX + "," + localY + ")");

        // Centre the patch on the target, clamped inside the cell.
        int x0 = Math.max(0, Math.min(256 - size, localX - size / 2));
        int y0 = Math.max(0, Math.min(256 - size, localY - size / 2));

        if (mediaDir != null) verifyMarkerExists(mediaDir);
        run(mapDir, cell, modsDir, modName, x0, y0, size);
    }

    /**
     * Confirm the marker is a real tile with a real sprite. An invisible tile
     * would be indistinguishable from a failed write, so rule that out first.
     */
    static void verifyMarkerExists(Path mediaDir) {
        try {
            TileDefs td = TileDefs.readAll(mediaDir);
            TileDefs.Tile t = td.byName.get(MARKER_TILE);
            if (t == null) {
                System.out.println("WARNING: marker tile '" + MARKER_TILE
                        + "' is not in any tile definition; it may render as nothing.");
                List<String> alts = new ArrayList<>();
                for (String n : td.byName.keySet())
                    if (n.startsWith("carpentry_02_") && alts.size() < 8) alts.add(n);
                if (!alts.isEmpty()) System.out.println("   nearby names: " + alts);
            } else {
                System.out.println("marker tile '" + MARKER_TILE + "' found: " + t.props);
            }
        } catch (Exception e) {
            System.out.println("(could not verify marker tile: " + e.getMessage() + ")");
        }
    }

    public static void run(Path mapDir, String cellName, Path modsDir, String modName,
                           int x0, int y0, int size) throws Exception {
        Path lotheader = mapDir.resolve(cellName + ".lotheader");
        Path lotpack = mapDir.resolve("world_" + cellName + ".lotpack");
        if (!Files.exists(lotheader) || !Files.exists(lotpack))
            throw new IllegalArgumentException("cell " + cellName + " not found in " + mapDir);

        System.out.println("== building test mod from cell " + cellName + " ==");

        // 1. Load twice: one to edit, one kept pristine for comparison.
        CellData before = CellData.load(lotpack, lotheader);
        CellData after = CellData.load(lotpack, lotheader);
        System.out.println("cell: " + before.cellSize + "x" + before.cellSize
                + "  z " + before.minLevel + ".." + before.maxLevel
                + "  (" + before.levelCount + " levels)");
        System.out.println("non-empty squares: " + before.nonEmptySquares());
        System.out.println("tile names: " + before.header.tileNames.size());

        // 2. Make the edit at ground level.
        int changed = after.fill(MARKER_TILE, x0, y0, size, size, 0);
        System.out.println("\npainted " + size + "x" + size + " of '" + MARKER_TILE
                + "' at (" + x0 + "," + y0 + ") z=0  -> " + changed + " squares changed");
        System.out.println("world coordinates: x " + worldCoord(cellName, true, x0)
                + ".." + (worldCoord(cellName, true, x0) + size - 1)
                + ", y " + worldCoord(cellName, false, y0)
                + ".." + (worldCoord(cellName, false, y0) + size - 1));

        // 3. Prove the edit is surgical.
        CellData.Diff d = CellData.diff(before, after);
        System.out.println("\ndiff: " + d);
        for (String s : d.samples) System.out.println("   " + s);
        boolean surgical = d.squaresChanged == changed
                && d.squaresAdded + d.squaresRemoved == 0;
        System.out.println("only the painted squares differ: " + surgical);

        // 4. Serialise, then re-read and confirm the change survived.
        byte[] newPack = after.writeLotPack();
        byte[] newHeader = after.writeLotHeader();
        System.out.println("\nwritten: lotpack " + newPack.length + " B (was "
                + Files.size(lotpack) + " B), lotheader " + newHeader.length
                + " B (was " + Files.size(lotheader) + " B)");

        Path tmp = Files.createTempDirectory("pzverify");
        Path tp = tmp.resolve("world_" + cellName + ".lotpack");
        Path th = tmp.resolve(cellName + ".lotheader");
        Files.write(tp, newPack);
        Files.write(th, newHeader);

        CellData reread = CellData.load(tp, th);
        CellData.Diff d2 = CellData.diff(after, reread);
        System.out.println("re-read matches what we wrote: " + d2.isEmpty()
                + (d2.isEmpty() ? "" : "  (" + d2 + ")"));
        String[] spot = reread.tileNamesAt(x0, y0, 0);
        System.out.println("spot check at (" + x0 + "," + y0 + "): "
                + (spot == null ? "EMPTY" : Arrays.toString(spot)));

        CellData.Diff d3 = CellData.diff(before, reread);
        System.out.println("re-read vs original: " + d3
                + (d3.squaresChanged == changed && d3.squaresAdded + d3.squaresRemoved == 0
                   ? "   <-- exactly the intended edit, nothing else" : "   <-- UNEXPECTED"));

        if (!surgical || !d2.isEmpty()) {
            System.out.println("\nverification failed; not writing the mod.");
            return;
        }

        // 5. Package. Both a versioned folder and common/, per B42 layout.
        Path modRoot = modsDir.resolve(modName);
        Path versioned = modRoot.resolve("42.0");
        Path mapOut = versioned.resolve("media/maps").resolve(modName);
        Files.createDirectories(mapOut);
        Files.createDirectories(modRoot.resolve("common"));

        Files.write(mapOut.resolve(cellName + ".lotheader"), newHeader);
        Files.write(mapOut.resolve("world_" + cellName + ".lotpack"), newPack);

        Path srcMapInfo = mapDir.resolve("map.info");
        if (Files.exists(srcMapInfo))
            Files.copy(srcMapInfo, mapOut.resolve("map.info"), StandardCopyOption.REPLACE_EXISTING);
        else
            Files.writeString(mapOut.resolve("map.info"),
                    "title=" + modName + "\nlots=Muldraugh, KY\n");

        String info = "name=" + modName + "\n"
                + "id=" + modName + "\n"
                + "description=Single-cell edit written by pzformat, to verify that\n"
                + "description=generated map files load correctly in Build 42.\n"
                + "modversion=0.1\n";
        Files.writeString(modRoot.resolve("mod.info"), info);
        Files.writeString(versioned.resolve("mod.info"), info);

        System.out.println("\nmod written to " + modRoot);
        System.out.println("""

            Next:
              1. Launch Project Zomboid.
              2. Mods -> enable "%s". If it is not listed, the folder layout is
                 wrong for your build; check what an existing mod in that folder
                 looks like and compare.
              3. New save on Muldraugh, KY. Debug-teleport to the world
                 coordinates printed above, or walk there.
              4. Look for the painted square.

            If the marker is there and the area around it looks normal, the
            writers are proven end to end. If the game refuses the map or the
            terrain is corrupted, that is a real finding and worth chasing.
            """.formatted(modName));
    }

    /** Cell "49_6" plus a local offset -> world coordinate. */
    static int worldCoord(String cellName, boolean xAxis, int local) {
        String[] parts = cellName.split("_");
        int cell = Integer.parseInt(parts[xAxis ? 0 : 1]);
        return cell * 256 + local;
    }
}
