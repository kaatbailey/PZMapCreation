package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Turns an imported GIS raster into loadable Project Zomboid cells.
 *
 * Generates cells from nothing — header, tile table, rooms, buildings, chunk
 * data — rather than editing existing ones. Rooms matter: without a RoomDef the
 * game does not consider a space indoors, so loot and zombie spawning behave
 * wrongly even if the walls look right.
 *
 * One building becomes one room (its bounding box within the cell). That is
 * crude for L-shaped footprints and is the obvious thing to improve, but it is
 * enough to make interiors behave as interiors.
 *
 * Output goes to a mod folder, never to the game install.
 */
public final class GisCells {

    /** Where to place generated cells so they cannot collide with vanilla. */
    public static final int ORIGIN_CELL_X = 200, ORIGIN_CELL_Y = 200;

    public static void run(Path buildingsFile, Path roadsFile, Path areaFile,
                           Path mediaDir, Path modsDir, String modName,
                           int maxTiles) throws Exception {

        GisImport g = GisImport.rasterise(buildingsFile, roadsFile, areaFile, maxTiles);
        System.out.println();

        TileIndex ti = TileIndex.load(mediaDir);
        TilePalette pal = TilePalette.pick(ti, SpriteNames.load(mediaDir.resolve("texturepacks")));
        pal.verify();
        System.out.println("tile palette:\n   " + pal);
        if (!pal.complete()) {
            System.out.println("\nincomplete palette; cannot generate cells");
            return;
        }

        int cellsX = (g.width + 255) / 256, cellsY = (g.height + 255) / 256;
        System.out.println("\ngenerating " + cellsX + "x" + cellsY + " cells");

        // Layout copied from a working B42 map mod (Maplewood):
        //   <mod>/42/mod.info          version-specific metadata only
        //   <mod>/common/media/maps/   the actual content
        // We previously used "42.0" and put content in the version folder,
        // which loads the mod but does not register its map.
        Path mapDir = modsDir.resolve(modName).resolve("common/media/maps").resolve(modName);
        Files.createDirectories(mapDir);

        int written = 0;
        long totalRooms = 0, totalSquares = 0, totalEdgeFill = 0;
        List<int[]> spawns = new ArrayList<>();

        for (int cy = 0; cy < cellsY; cy++)
            for (int cx = 0; cx < cellsX; cx++) {
                int ox = cx * 256, oy = cy * 256;

                List<String> names = new ArrayList<>(pal.all);
                LotHeader h = CellData.newHeader(names, 0, 0);
                CellData cell = CellData.blank(h, 32);

                int floorIdx = cell.tileIndex(pal.floorGrass);
                int roadIdx = cell.tileIndex(pal.floorRoad);
                int intIdx = cell.tileIndex(pal.floorInterior);
                int wnIdx = cell.tileIndex(pal.wallNorth);
                int wwIdx = cell.tileIndex(pal.wallWest);

                int squares = 0, edgeFilled = 0;
                for (int x = 0; x < 256; x++)
                    for (int y = 0; y < 256; y++) {
                        int gx = ox + x, gy = oy + y;
                        boolean inRaster = gx < g.width && gy < g.height;

                    List<Integer> stack = new ArrayList<>();
                    if (!inRaster) {
                        // Previously `continue`, which left the square empty.
                        // IsoChunk.hasEmptySquaresOnLevelZero() returns true if
                        // even one of a chunk's 64 squares has no object at z=0,
                        // and WorldGenChunk.generateChunks then hands the whole
                        // chunk to genRandomChunk. One gap discards the chunk.
                        stack.add(floorIdx);
                        edgeFilled++;
                    } else {
                        switch (g.cover[gx][gy]) {
                            case BUILDING -> stack.add(intIdx);
                            case ROAD -> stack.add(roadIdx);
                            default -> stack.add(floorIdx);
                        }
                        if (g.northWall[gx][gy]) stack.add(wnIdx);
                        if (g.westWall[gx][gy]) stack.add(wwIdx);
                    }

                        int[] arr = new int[stack.size()];
                        for (int i = 0; i < arr.length; i++) arr[i] = stack.get(i);
                        cell.setSquare(x, y, 0, arr, -1);
                        squares++;
                    }

                // Rooms from building footprints clipped to this cell.
                List<int[]> rects = buildingRects(g, ox, oy);
                for (int[] r : rects) {
                    LotHeader.Room room = new LotHeader.Room();
                    room.name = "room";
                    room.floor = 0;
                    room.rects.add(r);
                    h.rooms.add(room);
                    h.buildings.add(new int[]{h.rooms.size() - 1});
                    for (int x = r[0]; x < r[0] + r[2]; x++)
                        for (int y = r[1]; y < r[1] + r[3]; y++)
                            if (x < 256 && y < 256 && cell.tilesAt(x, y, 0) != null)
                                cell.setSquare(x, y, 0, cell.tilesAt(x, y, 0), h.rooms.size() - 1);
                    if (spawns.size() < 8)
                        spawns.add(new int[]{ORIGIN_CELL_X + cx, ORIGIN_CELL_Y + cy,
                                r[0] + r[2] / 2, r[1] + r[3] / 2});
                }
                totalRooms += rects.size();
                totalSquares += squares;
                totalEdgeFill += edgeFilled;

                String cellName = (ORIGIN_CELL_X + cx) + "_" + (ORIGIN_CELL_Y + cy);
                Files.write(mapDir.resolve(cellName + ".lotheader"), h.write());
                Files.write(mapDir.resolve("world_" + cellName + ".lotpack"),
                        cell.writeLotPack());
                written++;

                // Read it straight back: a cell we cannot parse, the game cannot either.
                CellData check = CellData.load(mapDir.resolve("world_" + cellName + ".lotpack"),
                        mapDir.resolve(cellName + ".lotheader"));
                if (check.cellSize != 256)
                    throw new IllegalStateException("generated cell " + cellName + " reparsed wrong");

                assertNoEmptySquares(check, cellName);
            }

        System.out.println("cells written: " + written
                + "   squares: " + totalSquares + "   rooms: " + totalRooms
                + "   edge-filled: " + totalEdgeFill);

        writeModInfo(modsDir.resolve(modName), modName);
        writeSupportFiles(mapDir, modName, spawns);
        writeWorldGenOverride(mapDir, cellsX, cellsY);

        System.out.println("\nmod written to " + modsDir.resolve(modName));
        System.out.println("cells occupy " + ORIGIN_CELL_X + "_" + ORIGIN_CELL_Y
                + " to " + (ORIGIN_CELL_X + cellsX - 1) + "_" + (ORIGIN_CELL_Y + cellsY - 1)
                + ", well clear of vanilla Knox County");
        if (!spawns.isEmpty())
            System.out.println("spawn point set inside a generated building at "
                    + spawns.get(0)[0] + ", " + spawns.get(0)[1]);
        System.out.println("\nWhen starting a new game, pick \"" + modName
                + "\" from the location list.\nIf it is absent the map is not"
                + " registered and the cells will never be read.");
    }

    /**
     * Mirror of IsoChunk.hasEmptySquaresOnLevelZero(), run against the REPARSED
     * cell.
     *
     * WorldGenChunk.generateChunks calls that predicate per 8x8 chunk. If it
     * returns true, the chunk goes to genRandomChunk and everything authored in
     * it is discarded. It returns true if even one of the 64 squares has no
     * object at z=0.
     *
     * Reparsed deliberately: asserting against the in-memory cell would only
     * prove the writer agrees with itself.
     */
    static void assertNoEmptySquares(CellData c, String cellName) {
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int[] t = c.tilesAt(x, y, 0);
                if (t == null || t.length == 0) {
                    throw new IllegalStateException(
                            "cell " + cellName + " square " + x + "," + y
                                    + " (chunk " + (x / 8) + "," + (y / 8) + ")"
                                    + " has no object at z=0; WorldGen would replace"
                                    + " that entire chunk with procedural terrain");
                }
            }
        }
    }

    /** Bounding boxes of connected building regions, clipped to one cell. */
    static List<int[]> buildingRects(GisImport g, int ox, int oy) {
        boolean[][] seen = new boolean[256][256];
        List<int[]> out = new ArrayList<>();
        for (int x = 0; x < 256; x++)
            for (int y = 0; y < 256; y++) {
                int gx = ox + x, gy = oy + y;
                if (gx >= g.width || gy >= g.height) continue;
                if (seen[x][y] || g.cover[gx][gy] != GisImport.Cover.BUILDING) continue;

                int minX = x, maxX = x, minY = y, maxY = y, count = 0;
                Deque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[]{x, y});
                seen[x][y] = true;
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    count++;
                    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                    for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        int nx = p[0] + d[0], ny = p[1] + d[1];
                        if (nx < 0 || ny < 0 || nx >= 256 || ny >= 256) continue;
                        int ngx = ox + nx, ngy = oy + ny;
                        if (ngx >= g.width || ngy >= g.height) continue;
                        if (seen[nx][ny] || g.cover[ngx][ngy] != GisImport.Cover.BUILDING) continue;
                        seen[nx][ny] = true;
                        stack.push(new int[]{nx, ny});
                    }
                }
                if (count >= 6)
                    out.add(new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1});
            }
        return out;
    }

    static void writeSupportFiles(Path mapDir, String modName, List<int[]> spawns)
            throws Exception {
        Files.writeString(mapDir.resolve("map.info"),
                "title=" + modName + "\n"
              + "description=Generated from public-domain GIS data by pzformat\n"
              + "lots=Muldraugh, KY\n"
                + "fixed2x=true\n");

        // No spawnregions.lua: a working B42 map mod ships none. That file is
        // a multiplayer server config, not a single-player registration hook.

        // Format taken from a working map mod: worldX/worldY are CELL
        // coordinates and posX/posY are cell-local (0-255). Vanilla maps use
        // absolute world coordinates instead, which is what we emitted before
        // — and a spawnpoints file the game cannot use appears to make it skip
        // the map entirely, without logging anything.
        if (spawns.isEmpty())
            spawns.add(new int[]{ORIGIN_CELL_X, ORIGIN_CELL_Y, 128, 128});

        StringBuilder sb = new StringBuilder("function SpawnPoints()\n    return {\n");
        for (String job : new String[]{"unemployed", "carpenter", "farmer", "fisherman",
                "lumberjack", "mechanics", "electrician", "securityguard", "burgerflipper"}) {
            sb.append("        ").append(job).append(" = {\n");
            for (int i = 0; i < spawns.size(); i++) {
                int[] s = spawns.get(i);
                sb.append("            { worldX = ").append(s[0])
                  .append(", worldY = ").append(s[1])
                  .append(", posX = ").append(s[2])
                  .append(", posY = ").append(s[3])
                  .append(", posZ = 0 }");
                sb.append(i + 1 < spawns.size() ? ",\n" : "\n");
            }
            sb.append("        },\n");
        }
        sb.append("    }\nend\n");
        Files.writeString(mapDir.resolve("spawnpoints.lua"), sb.toString());
        Files.writeString(mapDir.resolve("objects.lua"),
                "objects = {}\nreturn objects\n");
    }

    /**
     * Tell WorldGen what this land is.
     *
     * Without an override B42 generates terrain procedurally over any area a
     * map does not describe, which buried the first import under forest.
     * Vanilla ships 65 MB of explicit forest polygons; the override is the
     * lightweight alternative.
     *
     * `worldgen.biomes` is populated from biomes/worldgen/ — grass_plain,
     * flower_plain, sand_bank, water, and six forest types. NOT the same table
     * as biomes_map (dirt, townhouse), which the override does not read.
     *
     * grass_plain gives grass with bushes at 1% and each tree type at roughly
     * 0.1%: scattered cover rather than woodland.
     *
     * Confirmed: this changes WHAT generates, not WHETHER generation runs.
     * WorldGenChunk never references StaticModule; suppression comes solely
     * from every z=0 square being occupied. Keep this file — removing it does
     * not stop generation, it only makes the result forest again.
     *
     * chunkdata_*.bin is not a fallback route. It is zombie population data
     * and has no effect on WorldGen.
     */
    static void writeWorldGenOverride(Path mapDir, int cellsX, int cellsY) throws Exception {
        int xmin = ORIGIN_CELL_X * 256, ymin = ORIGIN_CELL_Y * 256;
        int xmax = xmin + cellsX * 256 - 1, ymax = ymin + cellsY * 256 - 1;
        String lua = "worldgen[\"static_modules\"] = {\n"
                + "    {\n"
                + "        position = { xmin = " + xmin + ", xmax = " + xmax
                + ", ymin = " + ymin + ", ymax = " + ymax + " },\n"
                + "        biome = worldgen.biomes.grass_plain\n"
                + "    }\n"
                + "}\n";
        Files.writeString(mapDir.resolve("WorldGenOverride.lua"), lua);
        System.out.println("worldgen override: grass_plain over world x " + xmin + ".."
                + xmax + ", y " + ymin + ".." + ymax);
    }

    static void writeModInfo(Path modRoot, String modName) throws Exception {
        // Keys and placement copied from a working B42 map mod: mod.info lives
        // ONLY in the version folder, and carries versionMin.
        Path versioned = modRoot.resolve("42");
        Files.createDirectories(versioned);
        Files.createDirectories(modRoot.resolve("common/media/maps"));
        String info = "name=" + modName + "\n"
                + "id=" + modName + "\n"
                + "description=Map generated from public-domain GIS data by pzformat.\n"
                + "author=pzformat\n"
                + "versionMin=42.0\n";
        Files.writeString(versioned.resolve("mod.info"), info);
        Files.deleteIfExists(modRoot.resolve("mod.info"));
    }
}
