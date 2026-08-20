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
        List<TilePalette.WallSkin> skins = TilePalette.discoverSkins(ti, sprites);
        System.out.println("exterior wall skins: " + skins.size() + " available");
        for (int si = 0; si < skins.size(); si++)
            System.out.println("   [" + si + "] " + skins.get(si).label());

        GroundPalette ground = GroundPalette.pick(ti, sprites);
        System.out.println("ground palette: " + ground);

        TreePalette treePal = TreePalette.pick(ti, sprites);
        System.out.println("tree palette: " + treePal);
        String[][] treeAt = TreeScatter.place(g, treePal, SEED);

        if (!pal.complete()) {
            System.out.println("\nincomplete palette; cannot generate cells");
            return;
        }

        // ---- BUILDING DIRECTORY ----
        // Printed once before the cell loop. Every building on one screen with
        // its classification, so a 332m² Agriculture next to a 127m² Residential
        // jumps out without ground truth. World coords let you walk straight to
        // any entry by the number on screen (x: / y: in the bottom-right corner).
        System.out.println("\nBUILDING DIRECTORY");
        System.out.printf("  %-4s %-16s %6s  %-10s  %-7s  %-6s  %-16s  %s%n",
                "#", "OCC_CLS", "area", "rect", "facing", "rooms", "world pos", "notes");
        for (int di = 0; di < g.buildings.size(); di++) {
            GisImport.Building db = g.buildings.get(di);
            FootprintSnap.Rect dr = db.rect();
            Random drng = new Random(SEED * 131 + di);
            List<String> dtypes = BuildingPlan.recipe(
                    dr.area(), db.occ(), db.outbuilding(), drng);
            BuildingPlan.Facing dfacing = faceTheRoad(g, dr);
            int worldX = ORIGIN_CELL_X * 256 + dr.x();
            int worldY = ORIGIN_CELL_Y * 256 + dr.y();
            String notes = "";
            if ("Agriculture".equals(db.occ()) && dr.area() < 150)
                notes = "small for Agriculture?";
            else if ("Residential".equals(db.occ()) && dr.area() > 280)
                notes = "large for Residential?";
            System.out.printf("  %-4d %-16s %4dm²  %-10s  %-7s  %4d    x:%-6d y:%-6d  %s%n",
                    di, db.occ(), dr.area(), dr.w() + "x" + dr.h(), dfacing,
                    dtypes.size(), worldX, worldY, notes);
        }
        System.out.println();

        int cellsX = (g.width + 255) / 256, cellsY = (g.height + 255) / 256;
        System.out.println("generating " + cellsX + "x" + cellsY + " cells");

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

                // Per-building skin grid: [x][y] -> {wallN, wallW, wallNW} indices.
                // Filled from building footprints so the raster loop knows which
                // skin each wall tile belongs to.
                int[][][] skinGrid = new int[256][256][];
                for (int bi = 0; bi < g.buildings.size(); bi++) {
                    if (skins.isEmpty()) break;
                    GisImport.Building bld = g.buildings.get(bi);
                    FootprintSnap.Rect bfr = bld.rect();
                    int sbx = bfr.x() - ox, sby = bfr.y() - oy;
                    TilePalette.WallSkin sk = skins.get(
                            (int) (Math.abs((long) SEED * 131 + bi) % skins.size()));
                    int skN = cell.tileIndex(sk.wallN());
                    int skW = cell.tileIndex(sk.wallW());
                    int skNW = cell.tileIndex(sk.wallNW());
                    for (int sx = Math.max(0, sbx - 1); sx < Math.min(256, sbx + bfr.w() + 1); sx++)
                        for (int sy = Math.max(0, sby - 1); sy < Math.min(256, sby + bfr.h() + 1); sy++) {
                            int gsx = ox + sx, gsy = oy + sy;
                            if (gsx >= 0 && gsy >= 0 && gsx < g.width && gsy < g.height
                                    && (g.northWall[gsx][gsy] || g.westWall[gsx][gsy]))
                                skinGrid[sx][sy] = new int[]{skN, skW, skNW};
                        }
                }

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
                                case WATER -> {
                                    stack.add(cell.tileIndex(pal.floorWater));
                                }
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
                            // A3 + skins: wall-joining with per-building skin.
                            int[] sk = skinGrid[x][y];
                            if (g.northWall[gx][gy] && g.westWall[gx][gy])
                                stack.add(sk != null ? sk[2] : cell.tileIndex(pal.wallNW));
                            else if (g.northWall[gx][gy])
                                stack.add(sk != null ? sk[0] : wnIdx);
                            else if (g.westWall[gx][gy])
                                stack.add(sk != null ? sk[1] : wwIdx);
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

                // Interiors. Planned once per building from its WORLD rect and
                // clipped to this cell, never re-derived per cell — a building
                // straddling a boundary would otherwise get two different
                // interiors, one either side of the line.
                int roomsHere = 0;
                // Building footprints clipped to this cell, for
                // writeChunkDensity — it marks built-up 8x8 chunks and wants
                // BUILDINGS, not rooms (STATE §23).
                List<int[]> rects = new ArrayList<>();
                for (int bi = 0; bi < g.buildings.size(); bi++) {
                    GisImport.Building b = g.buildings.get(bi);
                    FootprintSnap.Rect fr = b.rect();

                    // Cell-local, possibly partly or wholly outside.
                    int bx = fr.x() - ox, by = fr.y() - oy;
                    if (bx + fr.w() <= 0 || by + fr.h() <= 0
                            || bx >= 256 || by >= 256) continue;

                    // Seeded per building, not from the per-cell rng, so the
                    // same building plans identically from either side.
                    Random brng = new Random(SEED * 131 + bi);
                    List<String> types = BuildingPlan.recipe(
                            fr.area(), b.occ(), b.outbuilding(), brng);
                    BuildingPlan.Facing facing = faceTheRoad(g, fr);
                    List<BuildingPlan.Room> planned =
                            BuildingPlan.plan(bx, by, fr.w(), fr.h(), types, facing, brng);
                    if (planned.isEmpty()) continue;

                    int cx0 = Math.max(bx, 0), cy0 = Math.max(by, 0);
                    int cx1 = Math.min(bx + fr.w(), 256), cy1 = Math.min(by + fr.h(), 256);
                    if (cx1 > cx0 && cy1 > cy0)
                        rects.add(new int[]{cx0, cy0, cx1 - cx0, cy1 - cy0});

                    List<Integer> idx = new ArrayList<>();
                    for (BuildingPlan.Room pr : planned) {
                        int x0 = Math.max(pr.x(), 0), y0 = Math.max(pr.y(), 0);
                        int x1 = Math.min(pr.x() + pr.w(), 256);
                        int y1 = Math.min(pr.y() + pr.h(), 256);
                        if (x1 <= x0 || y1 <= y0) {
                            // Outside this cell. Record a placeholder so `idx`
                            // stays index-aligned with `planned` — carveInterior
                            // looks rooms up by planned position.
                            idx.add(-1);
                            continue;
                        }

                        LotHeader.Room room = new LotHeader.Room();
                        room.name = pr.type();
                        room.floor = 0;
                        room.rects.add(new int[]{x0, y0, x1 - x0, y1 - y0});
                        h.rooms.add(room);
                        int ri = h.rooms.size() - 1;
                        idx.add(ri);
                        roomsHere++;

                        for (int x = x0; x < x1; x++)
                            for (int y = y0; y < y1; y++)
                                if (cell.tilesAt(x, y, 0) != null)
                                    cell.setSquare(x, y, 0, cell.tilesAt(x, y, 0), ri);
                    }

                    carveInterior(cell, pal, planned, idx, brng);
                    TilePalette.WallSkin ceSkin = skins.isEmpty() ? null
                            : skins.get((int) (Math.abs((long) SEED * 131 + bi) % skins.size()));
                    carveEntrances(cell, pal, planned, idx, bx, by,
                            fr.w(), fr.h(), facing,
                            ceSkin != null ? cell.tileIndex(ceSkin.wallN()) : wnIdx,
                            ceSkin != null ? cell.tileIndex(ceSkin.wallW()) : wwIdx,
                            ceSkin);

                    // One building, all its rooms. The format models this and
                    // we were writing one index per entry.
                    if (!idx.isEmpty()) {
                        List<Integer> real = new ArrayList<>();
                        for (int v : idx) if (v >= 0) real.add(v);
                        int[] members = new int[real.size()];
                        for (int k = 0; k < members.length; k++) members[k] = real.get(k);
                        if (members.length > 0) h.buildings.add(members);
                    }
                }

                if (roadSpawn != null && spawns.size() < 8) {
                    spawns.add(new int[]{ORIGIN_CELL_X + cx, ORIGIN_CELL_Y + cy,
                            roadSpawn[0], roadSpawn[1]});
                }

                totalRooms += roomsHere;
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

    /**
     * Interior walls between planned rooms, and doors that guarantee every
     * room is reachable.
     *
     * Walls: a wall lives on the north or west edge of a square (§18), so a
     * square whose north neighbour belongs to a different room gets a north
     * wall. Only fires between two interior rooms — exteriors are handled by
     * the raster pass.
     *
     * Doors: cut along a SPANNING TREE of the room adjacency graph. A spanning
     * tree reaches every node by definition, so no room can be walled in — the
     * guarantee is structural rather than probabilistic, which matters because
     * an unreachable room is invisible until someone walks into the building.
     * Charter §1 names "room with no exit" as a validation rule; this is the
     * same knowledge applied at authoring time.
     *
     * @param planned rooms in cell-local coordinates, possibly extending
     *                outside the cell — a building may straddle a boundary
     * @param idx     lotheader room index per planned room, -1 where the room
     *                fell outside this cell entirely
     */
    static void carveInterior(CellData cell, TilePalette pal,
                              List<BuildingPlan.Room> planned,
                              List<Integer> idx, Random rng) {
        if (planned.size() < 2 || pal.interiorWallNorth == null) return;

        int wallN = cell.tileIndex(pal.interiorWallNorth);
        int wallW = cell.tileIndex(pal.interiorWallWest);
        int doorN = cell.tileIndex(pal.interiorDoorNorth);
        int doorW = cell.tileIndex(pal.interiorDoorWest);

        // Which planned room owns each square, over this building's extent.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (BuildingPlan.Room r : planned) {
            minX = Math.min(minX, r.x()); minY = Math.min(minY, r.y());
            maxX = Math.max(maxX, r.x() + r.w()); maxY = Math.max(maxY, r.y() + r.h());
        }
        int w = maxX - minX, h = maxY - minY;
        if (w <= 0 || h <= 0) return;

        int[][] owner = new int[w][h];
        for (int[] col : owner) java.util.Arrays.fill(col, -1);
        for (int i = 0; i < planned.size(); i++) {
            BuildingPlan.Room r = planned.get(i);
            for (int x = r.x(); x < r.x() + r.w(); x++)
                for (int y = r.y(); y < r.y() + r.h(); y++)
                    owner[x - minX][y - minY] = i;
        }

        // Candidate door positions per adjacent room pair, keyed "a:b" with
        // a < b. Each entry is {x, y, isNorth}.
        java.util.Map<String, List<int[]>> shared = new java.util.LinkedHashMap<>();
        List<int[]> walls = new ArrayList<>();          // x, y, isNorth, roomHere

        // §35 R5: 55.4% of vanilla livingroom/kitchen boundaries are FULLY
        // open and only 8.2% fully walled — they are usually one continuous
        // space. Decide once per pair so the whole boundary agrees.
        boolean[][] open = new boolean[planned.size()][planned.size()];
        for (int a = 0; a < planned.size(); a++)
            for (int b = a + 1; b < planned.size(); b++) {
                boolean o = BuildingPlan.openBetween(
                        planned.get(a).type(), planned.get(b).type(), rng);
                open[a][b] = o;
                open[b][a] = o;
            }

        for (int lx = 0; lx < w; lx++)
            for (int ly = 0; ly < h; ly++) {
                int me = owner[lx][ly];
                if (me < 0) continue;
                if (ly > 0) {
                    int other = owner[lx][ly - 1];
                    if (other >= 0 && other != me && !open[me][other]) {
                        walls.add(new int[]{lx + minX, ly + minY, 1, me});
                        shared.computeIfAbsent(key(me, other), k -> new ArrayList<>())
                              .add(new int[]{lx + minX, ly + minY, 1, me});
                    }
                }
                if (lx > 0) {
                    int other = owner[lx - 1][ly];
                    if (other >= 0 && other != me && !open[me][other]) {
                        walls.add(new int[]{lx + minX, ly + minY, 0, me});
                        shared.computeIfAbsent(key(me, other), k -> new ArrayList<>())
                              .add(new int[]{lx + minX, ly + minY, 0, me});
                    }
                }
            }

        // Spanning tree: one door per edge that first connects a new room.
        int[] parent = new int[planned.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        java.util.Set<String> doorAt = new java.util.HashSet<>();

        List<String> pairs = new ArrayList<>(shared.keySet());
        for (String p : pairs) {
            String[] ab = p.split(":");
            int a = Integer.parseInt(ab[0]), b = Integer.parseInt(ab[1]);
            List<int[]> cand = shared.get(p);
            if (cand.isEmpty()) continue;

            boolean join = find(parent, a) != find(parent, b);
            // Beyond the tree, a modest chance of a second connection — a real
            // house has more than the minimum.
            if (!join && rng.nextDouble() >= 0.25) continue;
            if (join) union(parent, a, b);

            int[] c = cand.get(cand.size() / 2);        // middle of the shared run
            doorAt.add(c[0] + "," + c[1] + "," + c[2]);
        }

        for (int[] wsq : walls) {
            boolean door = doorAt.contains(wsq[0] + "," + wsq[1] + "," + wsq[2]);
            int tile = wsq[2] == 1 ? (door ? doorN : wallN) : (door ? doorW : wallW);
            appendTile(cell, wsq[0], wsq[1], tile, roomIndexOf(idx, wsq[3]));
        }
    }

    /**
     * Which way this building faces: toward the nearest road (§35).
     *
     * Searched outward from the building's centre in world coordinates, so a
     * road in the next cell still counts. Falling back to SOUTH when nothing
     * is near keeps a barn in a field from facing arbitrarily.
     */
    static BuildingPlan.Facing faceTheRoad(GisImport g, FootprintSnap.Rect fr) {
        int cx = fr.x() + fr.w() / 2, cy = fr.y() + fr.h() / 2;
        for (int r = 1; r <= 80; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= g.width || y >= g.height) continue;
                    if (g.cover[x][y] != GisImport.Cover.ROAD) continue;
                    // The dominant axis of the offset is the face it presents.
                    if (Math.abs(dx) >= Math.abs(dy))
                        return dx < 0 ? BuildingPlan.Facing.WEST : BuildingPlan.Facing.EAST;
                    return dy < 0 ? BuildingPlan.Facing.NORTH : BuildingPlan.Facing.SOUTH;
                }
        return BuildingPlan.Facing.SOUTH;
    }

    /**
     * Cut a door in the outer wall of each room marked as an entrance.
     *
     * §35 R3, measured over 229 vanilla exterior doors: livingroom 31.0%,
     * kitchen 26.6%, hall 17.9%, laundry 9.6% — and **bedroom 1 of 229**. So
     * the eligible set is enforced rather than preferred, and a building whose
     * marked rooms are all clipped away simply gets no door rather than one in
     * a bedroom.
     *
     * The door replaces the exterior wall on that square, which is already
     * there from the raster pass — appending would leave a wall and a door
     * stacked on one tile.
     */
    static void carveEntrances(CellData cell, TilePalette pal,
                               List<BuildingPlan.Room> planned, List<Integer> idx,
                               int bx, int by, int bw, int bh,
                               BuildingPlan.Facing facing, int wnIdx, int wwIdx,
                               TilePalette.WallSkin skin) {
        if (pal.doorWallNorth == null) return;
        int doorN = cell.tileIndex(skin != null ? skin.doorN() : pal.doorWallNorth);
        int doorW = cell.tileIndex(skin != null ? skin.doorW() : pal.doorWallWest);

        for (int i = 0; i < planned.size(); i++) {
            BuildingPlan.Room r = planned.get(i);
            if (!r.entrance() || !r.canTakeDoor()) continue;

            // The livingroom opens on the front face; the kitchen on the back.
            BuildingPlan.Facing face = r.type().equals("kitchen")
                    ? facing.opposite() : facing;

            int x, y;
            boolean north;
            switch (face) {
                case NORTH -> { x = r.x() + r.w() / 2; y = r.y();               north = true; }
                case SOUTH -> { x = r.x() + r.w() / 2; y = r.y() + r.h();       north = true; }
                case WEST  -> { x = r.x();             y = r.y() + r.h() / 2;   north = false; }
                default    -> { x = r.x() + r.w();     y = r.y() + r.h() / 2;   north = false; }
            }
            // Only cut where the room genuinely reaches the building's edge —
            // a clipped room may not.
            boolean onEdge = switch (face) {
                case NORTH -> r.y() == by;
                case SOUTH -> r.y() + r.h() == by + bh;
                case WEST  -> r.x() == bx;
                case EAST  -> r.x() + r.w() == bx + bw;
            };
            if (!onEdge) continue;

            replaceTile(cell, x, y, north ? doorN : doorW, north,
                    roomIndexOf(idx, i), north ? wnIdx : wwIdx);
        }
    }

    /**
     * Put a door on this square's north or west edge, REMOVING the plain wall
     * the raster pass already wrote there.
     *
     * The earlier version appended the door beside the wall, on the belief
     * (STATE §35, never tested in game) that a vanilla door square carries both
     * a Wall and a DoorWall and the engine draws the door over the wall. That
     * is false where it counts: two wall objects on one edge leave the plain
     * wall winning for collision, so the square is solid and merely draws a
     * door frame. Charter §4 — byte round-tripping proved the tiles were
     * WRITTEN, not that the engine INTERPRETS them as a door. Removing the
     * matching wall makes the door the only edge object, which is exactly what
     * the interior pass already does (`door ? doorN : wallN`).
     *
     * wallIdx is the exterior wall tile for this edge (wnIdx for north,
     * wwIdx for west); there should be exactly one on the square.
     */
    static void replaceTile(CellData cell, int x, int y, int tile, boolean north,
                            int roomId, int wallIdx) {
        if (x < 0 || y < 0 || x >= 256 || y >= 256) return;
        int[] cur = cell.tilesAt(x, y, 0);
        if (cur == null) return;

        int[] tmp = new int[cur.length + 1];
        int k = 0;
        boolean removed = false;
        for (int v : cur) {
            if (!removed && v == wallIdx) { removed = true; continue; }
            tmp[k++] = v;
        }
        tmp[k++] = tile;
        int[] next = java.util.Arrays.copyOf(tmp, k);
        cell.setSquare(x, y, 0, next, roomId);
    }

    static String key(int a, int b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    static int roomIndexOf(List<Integer> idx, int planned) {
        return planned >= 0 && planned < idx.size() ? idx.get(planned) : -1;
    }

    static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }

    /** Append one tile to a square, keeping its existing stack and room id. */
    static void appendTile(CellData cell, int x, int y, int tile, int roomId) {
        if (x < 0 || y < 0 || x >= 256 || y >= 256) return;
        int[] cur = cell.tilesAt(x, y, 0);
        if (cur == null) return;
        int[] next = new int[cur.length + 1];
        System.arraycopy(cur, 0, next, 0, cur.length);
        next[cur.length] = tile;
        cell.setSquare(x, y, 0, next, roomId);
    }

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
