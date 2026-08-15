package pzformat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
 *
 * Three things here are non-obvious and were each found the hard way:
 *
 *   - Every one of a chunk's 64 squares must carry an object at z=0 or
 *     WorldGenChunk hands the whole chunk to genRandomChunk. Squares outside
 *     the raster are therefore FILLED, not skipped.
 *   - spawnpoints.lua uses the legacy 300-tile cell grid, not B42's 256.
 *   - Ground is a weighted mix with a partial tuft layer, not one tile.
 *     See GroundPalette; one flat tile is what made generated land read as a
 *     rectangle against its procedurally generated surroundings.
 */
public final class GisCells {

    /** Where to place generated cells so they cannot collide with vanilla. */
    public static final int ORIGIN_CELL_X = 200, ORIGIN_CELL_Y = 200;

    /** Fixed so regeneration is reproducible and render diffs mean something. */
    public static final long SEED = 20260806L;

    public static void run(Path buildingsFile, Path roadsFile, Path areaFile,
                           Path mediaDir, Path modsDir, String modName,
                           int maxTiles) throws Exception {

        GisImport g = GisImport.rasterise(buildingsFile, roadsFile, areaFile, maxTiles);
        System.out.println();

        TileIndex ti = TileIndex.load(mediaDir);
        Set<String> sprites = SpriteNames.load(mediaDir.resolve("texturepacks"));

        TilePalette pal = TilePalette.pick(ti, sprites);
        pal.verify();
        System.out.println("tile palette:\n   " + pal);

        GroundPalette ground = GroundPalette.pick(ti, sprites);
        System.out.println("ground palette: " + ground);

        TreePalette treePal = TreePalette.pick(ti, sprites);
        System.out.println("tree palette: " + treePal);
        String[][] treeAt = TreeScatter.place(g, treePal, SEED);

        if (!pal.complete()) {
            System.out.println("\nincomplete palette; cannot generate cells");
            return;
        }

        int cellsX = (g.width + 255) / 256, cellsY = (g.height + 255) / 256;
        System.out.println("\ngenerating " + cellsX + "x" + cellsY + " cells");

        // Layout copied from a working B42 map mod (Maplewood):
        //   <mod>/42/mod.info          version-specific metadata only
        //   <mod>/common/media/maps/   the actual content
        // Putting content in the version folder loads the mod but does not
        // register its map.
        Path mapDir = modsDir.resolve(modName).resolve("common/media/maps").resolve(modName);
        Files.createDirectories(mapDir);

        int written = 0;
        long totalRooms = 0, totalSquares = 0, totalEdgeFill = 0, totalTufts = 0;
        List<int[]> spawns = new ArrayList<>();

        for (int cy = 0; cy < cellsY; cy++) {
            for (int cx = 0; cx < cellsX; cx++) {
                int ox = cx * 256, oy = cy * 256;

                List<String> names = new ArrayList<>(pal.all);
                names.addAll(ground.all);
                names.addAll(treePal.all);
                LotHeader h = CellData.newHeader(names, 0, 0);
                CellData cell = CellData.blank(h, 32);

                int roadIdx = cell.tileIndex(pal.floorRoad);
                int intIdx = cell.tileIndex(pal.floorInterior);
                int wnIdx = cell.tileIndex(pal.wallNorth);
                int wwIdx = cell.tileIndex(pal.wallWest);

                // Seeded per cell so a cell regenerates identically whether or
                // not its neighbours are also being written.
                Random rng = new Random(SEED * 31 + (long) cx * 7919 + cy);

                // One ground material per square, regions plus dither. Reads
                // cover at global coordinates and hashes the dither by world
                // position, so a cell is identical whether or not its
                // neighbours are written — same contract as `rng` above.
                GroundMaterial[][] region = GroundRegions.build(g, ox, oy, SEED);

                int squares = 0, edgeFilled = 0, tufts = 0;
                int[] roadSpawn = null;
                long roadBest = Long.MAX_VALUE;

                for (int x = 0; x < 256; x++) {
                    for (int y = 0; y < 256; y++) {
                        int gx = ox + x, gy = oy + y;
                        boolean inRaster = gx < g.width && gy < g.height;

                        List<Integer> stack = new ArrayList<>();

                        if (!inRaster) {
                            // Outside the raster still gets ground. Leaving it
                            // empty would flip the whole 8x8 chunk to
                            // procedural generation, discarding anything
                            // authored in it.
                            GroundMaterial m = region[x + 1][y + 1];
                            if (m == null) m = GroundMaterial.GRASS_DARK;
                            stack.add(cell.tileIndex(m.solid(rng)));
                            GroundRegions.addMasks(stack, cell, region, x, y, m, rng);
                            GroundPalette.Ground gr = ground.roll(rng);
                            if (gr.tuft() != null) {
                                stack.add(cell.tileIndex(gr.tuft()));
                                tufts++;
                            }
                            edgeFilled++;
                        } else {
                            switch (g.cover[gx][gy]) {
                                case BUILDING -> stack.add(intIdx);
                                case ROAD -> {
                                    stack.add(roadIdx);
                                    // Grass outranks road (§27), so the grass
                                    // mask belongs on THIS square, not on the
                                    // grass one. Without this the road edge
                                    // stays hard and the census reports no
                                    // road pairs at all.
                                    GroundRegions.addMasks(stack, cell, region,
                                            x, y, region[x + 1][y + 1], rng);
                                    long d = (long) (x - 128) * (x - 128)
                                            + (long) (y - 128) * (y - 128);
                                    if (d < roadBest) {
                                        roadBest = d;
                                        roadSpawn = new int[]{x, y};
                                    }
                                }
                                default -> {
                                    GroundMaterial m = region[x + 1][y + 1];
                                    if (m == null) m = GroundMaterial.GRASS_DARK;
                                    stack.add(cell.tileIndex(m.solid(rng)));
                                    GroundRegions.addMasks(stack, cell, region,
                                            x, y, m, rng);
                                    // Tufts stay as measured. cleanChunk strips
                                    // them from Sand anyway (STATE §26), so a
                                    // yard square simply loses its tuft in game.
                                    GroundPalette.Ground gr = ground.roll(rng);
                                    if (gr.tuft() != null) {
                                        stack.add(cell.tileIndex(gr.tuft()));
                                        tufts++;
                                    }
                                }
                            }
                            if (g.northWall[gx][gy]) stack.add(wnIdx);
                            if (g.westWall[gx][gy]) stack.add(wwIdx);
                            if (treeAt[gx][gy] != null) {
                                stack.add(cell.tileIndex(treeAt[gx][gy]));
                            }
                        }

                        int[] arr = new int[stack.size()];
                        for (int i = 0; i < arr.length; i++) {
                            arr[i] = stack.get(i);
                        }
                        cell.setSquare(x, y, 0, arr, -1);
                        squares++;
                    }
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
                    for (int x = r[0]; x < r[0] + r[2]; x++) {
                        for (int y = r[1]; y < r[1] + r[3]; y++) {
                            if (x < 256 && y < 256 && cell.tilesAt(x, y, 0) != null) {
                                cell.setSquare(x, y, 0, cell.tilesAt(x, y, 0),
                                        h.rooms.size() - 1);
                            }
                        }
                    }
                }

                if (roadSpawn != null && spawns.size() < 8) {
                    spawns.add(new int[]{ORIGIN_CELL_X + cx, ORIGIN_CELL_Y + cy,
                            roadSpawn[0], roadSpawn[1]});
                }

                totalRooms += rects.size();
                totalSquares += squares;
                totalEdgeFill += edgeFilled;
                totalTufts += tufts;

                String cellName = (ORIGIN_CELL_X + cx) + "_" + (ORIGIN_CELL_Y + cy);
                writeChunkDensity(h, rects);

                Files.write(mapDir.resolve(cellName + ".lotheader"), h.write());
                Files.write(mapDir.resolve("world_" + cellName + ".lotpack"),
                        cell.writeLotPack());
                written++;

                // Read it straight back: a cell we cannot parse, the game cannot either.
                CellData check = CellData.load(
                        mapDir.resolve("world_" + cellName + ".lotpack"),
                        mapDir.resolve(cellName + ".lotheader"));
                if (check.cellSize != 256) {
                    throw new IllegalStateException(
                            "generated cell " + cellName + " reparsed wrong");
                }
                assertNoEmptySquares(check, cellName);
            }
        }

        System.out.println("cells written: " + written
                + "   squares: " + totalSquares + "   rooms: " + totalRooms
                + "   edge-filled: " + totalEdgeFill);
        System.out.printf("ground tufts: %d  (%.1f%% of ground squares;"
                        + " vanilla measures 43.3%%)%n",
                totalTufts, totalSquares == 0 ? 0.0 : 100.0 * totalTufts / totalSquares);

        writeModInfo(modsDir.resolve(modName), modName);
        writeSupportFiles(mapDir, modName, spawns);
        writeWorldGenOverride(mapDir, cellsX, cellsY);
        BiomeMapWriter.write(g, mapDir, cellsX, cellsY, ORIGIN_CELL_X, ORIGIN_CELL_Y);

        System.out.println("\nmod written to " + modsDir.resolve(modName));
        System.out.println("cells occupy " + ORIGIN_CELL_X + "_" + ORIGIN_CELL_Y
                + " to " + (ORIGIN_CELL_X + cellsX - 1) + "_" + (ORIGIN_CELL_Y + cellsY - 1)
                + ", well clear of vanilla Knox County");
        if (!spawns.isEmpty()) {
            int[] s = spawns.get(0);
            System.out.println("spawn point set on a road square at cell "
                    + s[0] + "_" + s[1]
                    + ", world tile " + (s[0] * 256 + s[2]) + "," + (s[1] * 256 + s[3])
                    + " (chunk " + ((s[0] * 256 + s[2]) / 8)
                    + "," + ((s[1] * 256 + s[3]) / 8) + ")");
        }
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
        for (int x = 0; x < 256; x++) {
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
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = p[0] + d[0], ny = p[1] + d[1];
                        if (nx < 0 || ny < 0 || nx >= 256 || ny >= 256) continue;
                        int ngx = ox + nx, ngy = oy + ny;
                        if (ngx >= g.width || ngy >= g.height) continue;
                        if (seen[nx][ny]
                                || g.cover[ngx][ngy] != GisImport.Cover.BUILDING) continue;
                        seen[nx][ny] = true;
                        stack.push(new int[]{nx, ny});
                    }
                }
                if (count >= 6) {
                    out.add(new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1});
                }
            }
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

        if (spawns.isEmpty()) {
            spawns.add(new int[]{ORIGIN_CELL_X, ORIGIN_CELL_Y, 128, 128});
        }

        StringBuilder sb = new StringBuilder("function SpawnPoints()\n    return {\n");
        for (String job : new String[]{"unemployed", "carpenter", "farmer", "fisherman",
                "lumberjack", "mechanics", "electrician", "securityguard", "burgerflipper"}) {
            sb.append("        ").append(job).append(" = {\n");
            for (int i = 0; i < spawns.size(); i++) {
                int[] s = spawns.get(i);
                // spawnpoints.lua is read on the LEGACY 300-tile cell grid, not
                // the B42 256-tile grid. Convert via absolute world tiles or the
                // spawn lands cell*(300-256) tiles away from the actual cells.
                int worldTileX = s[0] * 256 + s[2];
                int worldTileY = s[1] * 256 + s[3];
                sb.append("            { worldX = ").append(worldTileX / 300)
                        .append(", worldY = ").append(worldTileY / 300)
                        .append(", posX = ").append(worldTileX % 300)
                        .append(", posY = ").append(worldTileY % 300)
                        .append(", posZ = 0 }");
                sb.append(i + 1 < spawns.size() ? ",\n" : "\n");
            }
            sb.append("        },\n");
        }
        sb.append("    }\nend\n");
        Files.writeString(mapDir.resolve("spawnpoints.lua"), sb.toString());
        Files.writeString(mapDir.resolve("objects.lua"), "objects = {}\nreturn objects\n");
    }

    /**
     * Tell WorldGen what this land is.
     *
     * Without an override B42 generates terrain procedurally over any area a
     * map does not describe, which buried the first import under forest.
     *
     * `worldgen.biomes` is populated from biomes/worldgen/ — grass_plain,
     * flower_plain, sand_bank, water, and six forest types. NOT the same table
     * as biomes_map (dirt, townhouse), which the override does not read.
     *
     * Confirmed: this changes WHAT generates, not WHETHER generation runs.
     * WorldGenChunk never references StaticModule; suppression comes solely
     * from every z=0 square being occupied. Keep this file — removing it does
     * not stop generation, it only makes the result forest again.
     *
     * OPEN: this rectangle is exactly our cell extent, so the biome changes on
     * a hard straight line at the boundary. Ground variation addresses the
     * texture half of the seam; the biome half would need either a matching
     * biome at the edge or nested modules stepping outward.
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

    /**
     * Zombie density, one byte per 8x8 chunk (LotHeader.GRID_BYTES = 32x32).
     *
     * CellData.blank leaves this zero-filled, and a zero grid means no
     * zombies at all — which is why the generated map had none while vanilla
     * ground right across the boundary did (STATE.md §22).
     *
     * Vanilla Muldraugh over 4,162,560 chunks:
     *
     *   0 -> 4,013,741   1 -> 47,276   2 -> 72,702   3 -> 17,993
     *   4 ->     7,455   5 ->    448   6 ->    595   7 ->  1,579
     *   8 ->        91   9 ->    140  (10 present)
     *
     * So 96.4% zero is NORMAL; the defect was having nothing else. 1, 2 and 3
     * carry nearly all of the nonzero population, and 8/9/10 are about 0.005%
     * of chunks, so this deliberately stays in the low range.
     *
     * Deliberately NOT sampling that histogram per chunk. It is a frequency
     * measurement, and density clusters around habitation — reproducing the
     * numbers while scattering them would be the same error as the ground
     * palette (§21) and attachedN (§11). Frequency is not distribution.
     *
     * Rule: a chunk holding building tiles gets INSIDE, a chunk orthogonally
     * adjacent to one gets ADJACENT, everything else stays 0. Crude, but it
     * has vanilla's SHAPE rather than its histogram, and it is falsifiable in
     * game: zombies should appear around our buildings and not in open
     * country.
     *
     * @param rects building footprints clipped to this cell, cell-local
     */
    static final int DENSITY_INSIDE = 2, DENSITY_ADJACENT = 1;

    static void writeChunkDensity(LotHeader h, List<int[]> rects) {
        final int side = LotHeader.GRID_SIDE;          // 32
        if (h.chunkGrid == null || h.chunkGrid.length != LotHeader.GRID_BYTES)
            h.chunkGrid = new byte[LotHeader.GRID_BYTES];

        boolean[] built = new boolean[LotHeader.GRID_BYTES];
        for (int[] r : rects) {
            int x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]);
            int x1 = Math.min(255, r[0] + r[2] - 1), y1 = Math.min(255, r[1] + r[3] - 1);
            for (int cx = x0 / 8; cx <= x1 / 8; cx++)
                for (int cy = y0 / 8; cy <= y1 / 8; cy++)
                    built[cy * side + cx] = true;
        }

        for (int cy = 0; cy < side; cy++) {
            for (int cx = 0; cx < side; cx++) {
                int i = cy * side + cx;
                if (built[i]) { h.chunkGrid[i] = DENSITY_INSIDE; continue; }
                boolean near = (cx > 0 && built[i - 1])
                        || (cx < side - 1 && built[i + 1])
                        || (cy > 0 && built[i - side])
                        || (cy < side - 1 && built[i + side]);
                if (near) h.chunkGrid[i] = DENSITY_ADJACENT;
            }
        }
    }

}
