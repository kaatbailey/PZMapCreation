package pzformat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Fast top-down geographic overview of a Project Zomboid B42 world.
 *
 * This is NOT a sprite renderer.
 *
 * Purpose:
 *   - Produce a clean reference map for aligning a mod map.
 *   - Show roads and intersections clearly.
 *   - Show buildings / developed areas.
 *   - Distinguish water, forest, grass and dirt.
 *   - Preserve exact PZ world X/Y alignment.
 *   - Avoid the extremely expensive isometric sprite compositor.
 *
 * Coordinate model:
 *
 *     world tile X/Y
 *             |
 *             v
 *     top-down overview X/Y
 *
 * There is NO isometric transformation.
 *
 * At TILES_PER_PIXEL = 8:
 *
 *     8x8 PZ tiles = 1 output pixel
 *
 * The output is deliberately stylized rather than photorealistic.
 */
public final class WorldMapExporter {

    /*
     * =============================================================
     * OUTPUT SCALE
     * =============================================================
     *
     * 8 is a good starting point for a large world.
     *
     * 4 = more detail, 4x the image pixels
     * 8 = balanced
     * 16 = very small / coarse
     */
    private static final int TILES_PER_PIXEL = 8;

    private static final int CELL_SIZE = 256;

    /*
     * =============================================================
     * MAP COLORS
     * =============================================================
     *
     * Muted colors intentionally chosen to look more like a
     * cartographic reference layer than game artwork.
     */

    private static final int COLOR_BACKGROUND =
            rgb(45, 50, 45);

    private static final int COLOR_GRASS =
            rgb(96, 119, 62);

    private static final int COLOR_GRASS_ALT =
            rgb(90, 112, 58);

    private static final int COLOR_FOREST =
            rgb(57, 81, 43);

    private static final int COLOR_FOREST_ALT =
            rgb(52, 74, 40);

    private static final int COLOR_DIRT =
            rgb(139, 112, 75);

    private static final int COLOR_WATER =
            rgb(62, 108, 137);

    private static final int COLOR_WATER_ALT =
            rgb(58, 100, 127);

    private static final int COLOR_BUILDING =
            rgb(184, 174, 151);

    private static final int COLOR_BUILDING_ALT =
            rgb(171, 162, 142);

    private static final int COLOR_SIDEWALK =
            rgb(181, 178, 164);

    private static final int COLOR_ROAD =
            rgb(67, 68, 66);

    private static final int COLOR_ROAD_DARK =
            rgb(61, 62, 60);

    private static final int COLOR_PARKING =
            rgb(82, 83, 80);

    private static final int COLOR_UNKNOWN =
            rgb(84, 88, 81);

    /*
     * =============================================================
     * OPTIONAL GRID
     * =============================================================
     *
     * Keep OFF for the actual overlay map.
     *
     * Turn on temporarily if you need to verify cell boundaries.
     */
    private static final boolean DEBUG_CELL_GRID = false;

    private static final int COLOR_GRID =
            rgb(115, 115, 110);

    /*
     * =============================================================
     * ROAD EMPHASIS
     * =============================================================
     *
     * Roads are drawn from the actual road tiles, but at low
     * resolution they can become extremely thin.
     *
     * A small amount of neighborhood reinforcement keeps them
     * visually continuous without inventing road geometry.
     */
    private static final boolean EMPHASIZE_ROADS = true;

    private WorldMapExporter() {
    }

    public static void run(
            Path mapDir,
            Path texturePackDir,
            Path outputDir
    ) throws Exception {

        Files.createDirectories(outputDir);

        System.out.println(
                "Scanning map: " + mapDir
        );

        List<CellRef> cells =
                findCells(mapDir);

        if (cells.isEmpty()) {
            throw new IOException(
                    "No .lotheader files found in "
                            + mapDir
            );
        }

        int minX =
                cells.stream()
                        .mapToInt(c -> c.x)
                        .min()
                        .orElseThrow();

        int maxX =
                cells.stream()
                        .mapToInt(c -> c.x)
                        .max()
                        .orElseThrow();

        int minY =
                cells.stream()
                        .mapToInt(c -> c.y)
                        .min()
                        .orElseThrow();

        int maxY =
                cells.stream()
                        .mapToInt(c -> c.y)
                        .max()
                        .orElseThrow();

        int cellColumns =
                maxX - minX + 1;

        int cellRows =
                maxY - minY + 1;

        int pixelsPerCell =
                CELL_SIZE / TILES_PER_PIXEL;

        int width =
                cellColumns * pixelsPerCell;

        int height =
                cellRows * pixelsPerCell;

        System.out.println(
                "World cell bounds: "
                        + minX + "," + minY
                        + " -> "
                        + maxX + "," + maxY
        );

        System.out.println(
                "Overview size: "
                        + width + "x" + height
        );

        System.out.println(
                "Top-down scale: 1 pixel = "
                        + TILES_PER_PIXEL
                        + " PZ tiles"
        );

        /*
         * texturePackDir is intentionally unused.
         *
         * We keep it in the method signature so existing callers
         * don't have to change.
         */
        if (texturePackDir != null) {
            // Intentionally unused.
        }

        /*
         * =============================================================
         * OUTPUT BUFFERS
         * =============================================================
         *
         * We work directly on primitive arrays.
         *
         * No per-cell BufferedImage.
         * No Graphics2D.
         * No sprite PNGs.
         */
        int pixelCount =
                width * height;

        int[] pixels =
                new int[pixelCount];

        Arrays.fill(
                pixels,
                COLOR_BACKGROUND
        );

        /*
         * Each output pixel accumulates the presence of features.
         *
         * These arrays are byte-sized because we only need counts
         * relative to each other, not exact tile counts.
         */
        byte[] grass =
                new byte[pixelCount];

        byte[] forest =
                new byte[pixelCount];

        byte[] dirt =
                new byte[pixelCount];

        byte[] water =
                new byte[pixelCount];

        byte[] building =
                new byte[pixelCount];

        byte[] sidewalk =
                new byte[pixelCount];

        byte[] parking =
                new byte[pixelCount];

        byte[] road =
                new byte[pixelCount];

        long renderedCells = 0;
        long missingCells = 0;

        /*
         * =============================================================
         * READ WORLD
         * =============================================================
         */
        for (CellRef cell : cells) {

            int imageX =
                    (cell.x - minX)
                            * pixelsPerCell;

            int imageY =
                    (cell.y - minY)
                            * pixelsPerCell;

            Path lotHeader =
                    cell.mapDir.resolve(
                            cell.name + ".lotheader"
                    );

            Path lotPack =
                    cell.mapDir.resolve(
                            "world_" + cell.name + ".lotpack"
                    );

            if (!Files.exists(lotPack)) {

                missingCells++;

                continue;
            }

            CellData data =
                    CellData.load(
                            lotPack,
                            lotHeader
                    );

            accumulateCell(
                    data,
                    imageX,
                    imageY,
                    width,
                    grass,
                    forest,
                    dirt,
                    water,
                    building,
                    sidewalk,
                    parking,
                    road
            );

            renderedCells++;

            if (
                    renderedCells % 100 == 0
            ) {

                System.out.println(
                        "Processed cells: "
                                + renderedCells
                                + " / "
                                + cells.size()
                );
            }
        }

        /*
         * =============================================================
         * TURN ACCUMULATED FEATURES INTO COLORS
         * =============================================================
         */
        buildImage(
                pixels,
                width,
                height,
                grass,
                forest,
                dirt,
                water,
                building,
                sidewalk,
                parking,
                road
        );

        BufferedImage overview =
                new BufferedImage(
                        width,
                        height,
                        BufferedImage.TYPE_INT_RGB
                );

        overview.setRGB(
                0,
                0,
                width,
                height,
                pixels,
                0,
                width
        );

        /*
         * =============================================================
         * ROAD CLEANUP
         * =============================================================
         *
         * This works on the already tiny overview image.
         * It is extremely cheap.
         */
        if (EMPHASIZE_ROADS) {

            emphasizeRoadNetwork(
                    overview,
                    road
            );
        }

        /*
         * Optional native PZ cell grid.
         */
        if (DEBUG_CELL_GRID) {

            drawCellGrid(
                    overview,
                    cellColumns,
                    cellRows,
                    pixelsPerCell
            );
        }

        System.out.println(
                "Processed cells: "
                        + renderedCells
                        + " / "
                        + cells.size()
        );

        if (missingCells > 0) {

            System.out.println(
                    "Cells without lotpack: "
                            + missingCells
            );
        }

        Path png =
                outputDir.resolve(
                        "vanilla-world.png"
                );

        ImageIO.write(
                overview,
                "png",
                png.toFile()
        );

        overview.flush();

        writeMetadata(
                outputDir,
                minX,
                minY,
                maxX,
                maxY,
                width,
                height
        );

        System.out.println(
                "Wrote "
                        + png.toAbsolutePath()
        );
    }

    /**
     * Accumulate all tile information from one cell.
     *
     * This is intentionally top-down.
     */
    private static void accumulateCell(
            CellData cell,
            int imageX,
            int imageY,
            int overviewWidth,
            byte[] grass,
            byte[] forest,
            byte[] dirt,
            byte[] water,
            byte[] building,
            byte[] sidewalk,
            byte[] parking,
            byte[] road
    ) {

        for (
                int z = cell.minLevel;
                z <= cell.maxLevel;
                z++
        ) {

            for (
                    int y = 0;
                    y < cell.cellSize;
                    y++
            ) {

                for (
                        int x = 0;
                        x < cell.cellSize;
                        x++
                ) {

                    String[] names =
                            cell.tileNamesAt(
                                    x,
                                    y,
                                    z
                            );

                    if (
                            names == null
                                    || names.length == 0
                    ) {
                        continue;
                    }

                    MapFeature feature =
                            classifyTile(names);

                    if (feature == null) {
                        continue;
                    }

                    /*
                     * Direct top-down geographic mapping.
                     */
                    int px =
                            imageX
                                    + x
                                    / TILES_PER_PIXEL;

                    int py =
                            imageY
                                    + y
                                    / TILES_PER_PIXEL;

                    int pixelIndex =
                            py * overviewWidth
                                    + px;

                    /*
                     * We cap counts at 255.
                     */
                    switch (feature) {

                        case ROAD ->
                                increment(
                                        road,
                                        pixelIndex
                                );

                        case PARKING ->
                                increment(
                                        parking,
                                        pixelIndex
                                );

                        case SIDEWALK ->
                                increment(
                                        sidewalk,
                                        pixelIndex
                                );

                        case BUILDING ->
                                increment(
                                        building,
                                        pixelIndex
                                );

                        case WATER ->
                                increment(
                                        water,
                                        pixelIndex
                                );

                        case DIRT ->
                                increment(
                                        dirt,
                                        pixelIndex
                                );

                        case FOREST ->
                                increment(
                                        forest,
                                        pixelIndex
                                );

                        case GRASS ->
                                increment(
                                        grass,
                                        pixelIndex
                                );

                        case UNKNOWN -> {
                            // Nothing.
                        }
                    }
                }
            }
        }
    }

    private static void increment(
            byte[] array,
            int index
    ) {

        if (
                (array[index] & 0xff)
                        < 255
        ) {

            array[index]++;
        }
    }

    /**
     * Convert accumulated feature counts into the final map.
     *
     * Roads get special treatment because they are the most important
     * alignment feature.
     */
    private static void buildImage(
            int[] pixels,
            int width,
            int height,
            byte[] grass,
            byte[] forest,
            byte[] dirt,
            byte[] water,
            byte[] building,
            byte[] sidewalk,
            byte[] parking,
            byte[] road
    ) {

        for (
                int i = 0;
                i < pixels.length;
                i++
        ) {

            int r =
                    road[i] & 0xff;

            int p =
                    parking[i] & 0xff;

            int s =
                    sidewalk[i] & 0xff;

            int b =
                    building[i] & 0xff;

            int w =
                    water[i] & 0xff;

            int d =
                    dirt[i] & 0xff;

            int f =
                    forest[i] & 0xff;

            int g =
                    grass[i] & 0xff;

            /*
             * ---------------------------------------------------------
             * ROAD
             * ---------------------------------------------------------
             *
             * Even one road tile inside the block is enough to
             * preserve the road.
             */
            if (r > 0) {

                /*
                 * Slight variation according to road density.
                 */
                pixels[i] =
                        r >= 8
                                ? COLOR_ROAD_DARK
                                : COLOR_ROAD;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * PARKING
             * ---------------------------------------------------------
             */
            if (p > 0) {

                pixels[i] =
                        COLOR_PARKING;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * SIDEWALK
             * ---------------------------------------------------------
             */
            if (s > 0) {

                pixels[i] =
                        COLOR_SIDEWALK;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * BUILDING
             * ---------------------------------------------------------
             */
            if (b > 0) {

                pixels[i] =
                        b >= 8
                                ? COLOR_BUILDING_ALT
                                : COLOR_BUILDING;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * WATER
             * ---------------------------------------------------------
             */
            if (w > 0) {

                pixels[i] =
                        w >= 8
                                ? COLOR_WATER_ALT
                                : COLOR_WATER;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * DIRT
             * ---------------------------------------------------------
             */
            if (d > 0) {

                pixels[i] =
                        COLOR_DIRT;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * FOREST
             * ---------------------------------------------------------
             */
            if (f > g) {

                pixels[i] =
                        f >= 8
                                ? COLOR_FOREST_ALT
                                : COLOR_FOREST;

                continue;
            }

            /*
             * ---------------------------------------------------------
             * GRASS
             * ---------------------------------------------------------
             */
            if (g > 0) {

                pixels[i] =
                        g >= 8
                                ? COLOR_GRASS
                                : COLOR_GRASS_ALT;

                continue;
            }

            pixels[i] =
                    COLOR_BACKGROUND;
        }
    }

    /**
     * Very small road smoothing pass.
     *
     * We ONLY operate on pixels that already contain a road.
     *
     * This does not fabricate roads; it keeps a thin road from looking
     * broken when crossing the 8x8 aggregation boundary.
     */
    private static void emphasizeRoadNetwork(
            BufferedImage image,
            byte[] road
    ) {

        int width =
                image.getWidth();

        int height =
                image.getHeight();

        int[] source =
                image.getRGB(
                        0,
                        0,
                        width,
                        height,
                        null,
                        0,
                        width
                );

        int[] result =
                source.clone();

        for (
                int y = 1;
                y < height - 1;
                y++
        ) {

            for (
                    int x = 1;
                    x < width - 1;
                    x++
            ) {

                int index =
                        y * width + x;

                if (
                        (road[index] & 0xff) == 0
                ) {
                    continue;
                }

                /*
                 * Count neighboring road pixels.
                 */
                int neighbours = 0;

                if (
                        (road[index - 1] & 0xff) > 0
                ) {
                    neighbours++;
                }

                if (
                        (road[index + 1] & 0xff) > 0
                ) {
                    neighbours++;
                }

                if (
                        (road[index - width] & 0xff) > 0
                ) {
                    neighbours++;
                }

                if (
                        (road[index + width] & 0xff) > 0
                ) {
                    neighbours++;
                }

                /*
                 * Keep the road dark.
                 *
                 * We intentionally do not paint neighboring grass
                 * pixels here. That would alter the actual geometry.
                 */
                if (neighbours >= 2) {

                    result[index] =
                            COLOR_ROAD_DARK;
                }
            }
        }

        image.setRGB(
                0,
                0,
                width,
                height,
                result,
                0,
                width
        );
    }

    /**
     * Classify a PZ tile based on its sprite names.
     *
     * This is deliberately broad and easy to tune.
     *
     * We inspect ALL names on the tile and choose the most useful
     * cartographic feature.
     */
    private static MapFeature classifyTile(
            String[] names
    ) {

        MapFeature best =
                null;

        int bestPriority =
                Integer.MIN_VALUE;

        for (String raw : names) {

            if (
                    raw == null
                            || raw.isBlank()
            ) {
                continue;
            }

            String name =
                    raw.toLowerCase(
                            Locale.ROOT
                    );

            MapFeature feature =
                    classifyName(name);

            if (feature == null) {
                continue;
            }

            if (
                    feature.priority
                            > bestPriority
            ) {

                best =
                        feature;

                bestPriority =
                        feature.priority;
            }
        }

        return best;
    }

    /**
     * Sprite-name classification.
     *
     * This is the one method I would expect to tune after seeing
     * the first generated map.
     */
    private static MapFeature classifyName(
            String name
    ) {

        /*
         * =============================================================
         * WATER
         * =============================================================
         */
        if (
                containsAny(
                        name,
                        "water",
                        "river",
                        "lake",
                        "pond",
                        "ocean",
                        "shore"
                )
        ) {

            return MapFeature.WATER;
        }

        /*
         * =============================================================
         * ROAD
         * =============================================================
         *
         * Check roads BEFORE generic ground.
         */
        if (
                containsAny(
                        name,
                        "street",
                        "road",
                        "asphalt",
                        "highway",
                        "driveway",
                        "roadside",
                        "median"
                )
        ) {

            /*
             * Avoid turning street furniture into an entire road.
             */
            if (
                    !containsAny(
                            name,
                            "street_decoration",
                            "streetlight",
                            "street_lamp",
                            "streetlamp",
                            "signpost"
                    )
            ) {

                return MapFeature.ROAD;
            }
        }

        /*
         * Known PZ blend naming families.
         */
        if (
                name.contains(
                        "blends_street"
                )
                        ||
                        name.contains(
                                "blends_road"
                        )
        ) {

            return MapFeature.ROAD;
        }

        /*
         * =============================================================
         * PARKING
         * =============================================================
         */
        if (
                containsAny(
                        name,
                        "parkingstall",
                        "parking_stall",
                        "parking_space"
                )
        ) {

            return MapFeature.PARKING;
        }

        /*
         * =============================================================
         * SIDEWALK
         * =============================================================
         */
        if (
                containsAny(
                        name,
                        "sidewalk",
                        "pavement",
                        "paving",
                        "curb",
                        "kerb"
                )
        ) {

            return MapFeature.SIDEWALK;
        }

        /*
         * =============================================================
         * BUILDINGS
         * =============================================================
         *
         * Structural tile families.
         *
         * Furniture is included because a building containing
         * furniture should still register as developed space.
         */
        if (
                containsAny(
                        name,
                        "walls_",
                        "wall_",
                        "wall",
                        "floors_",
                        "floor_",
                        "roof",
                        "roofs_",
                        "door",
                        "window",
                        "stairs",
                        "stair",
                        "building",
                        "construction_",
                        "interior",
                        "furniture_",
                        "fixtures_",
                        "appliances_"
                )
        ) {

            return MapFeature.BUILDING;
        }

        /*
         * =============================================================
         * DIRT
         * =============================================================
         */
        if (
                containsAny(
                        name,
                        "dirt",
                        "gravel",
                        "mud",
                        "sand",
                        "earth"
                )
        ) {

            return MapFeature.DIRT;
        }

        /*
         * =============================================================
         * FOREST / VEGETATION
         * =============================================================
         */
        if (
                containsAny(
                        name,
                        "vegetation",
                        "vegitation",
                        "tree",
                        "trees",
                        "forest",
                        "bush",
                        "bushes",
                        "shrub",
                        "foliage",
                        "grass_tall"
                )
        ) {

            return MapFeature.FOREST;
        }

        /*
         * =============================================================
         * GRASS / NATURAL GROUND
         * =============================================================
         */
        if (
                containsAny(
                        name,
                        "grass",
                        "natural",
                        "ground",
                        "meadow",
                        "field",
                        "farm",
                        "blends_natural"
                )
        ) {

            return MapFeature.GRASS;
        }

        return MapFeature.UNKNOWN;
    }

    private enum MapFeature {

        UNKNOWN(1),

        GRASS(10),

        FOREST(20),

        DIRT(30),

        WATER(40),

        BUILDING(60),

        SIDEWALK(75),

        PARKING(80),

        ROAD(100);

        private final int priority;

        MapFeature(
                int priority
        ) {

            this.priority =
                    priority;
        }
    }

    private static boolean containsAny(
            String value,
            String... needles
    ) {

        for (String needle : needles) {

            if (value.contains(needle)) {
                return true;
            }
        }

        return false;
    }

    private static int rgb(
            int r,
            int g,
            int b
    ) {

        return (
                (r << 16)
                        | (g << 8)
                        | b
        );
    }

    /**
     * Draw native PZ cell boundaries for debugging.
     */
    private static void drawCellGrid(
            BufferedImage image,
            int cellColumns,
            int cellRows,
            int pixelsPerCell
    ) {

        int width =
                image.getWidth();

        int height =
                image.getHeight();

        int[] pixels =
                image.getRGB(
                        0,
                        0,
                        width,
                        height,
                        null,
                        0,
                        width
                );

        for (
                int x = 0;
                x <= cellColumns;
                x++
        ) {

            int px =
                    x * pixelsPerCell;

            if (
                    px < 0
                            || px >= width
            ) {
                continue;
            }

            for (
                    int y = 0;
                    y < height;
                    y++
            ) {

                pixels[
                        y * width + px
                        ] = COLOR_GRID;
            }
        }

        for (
                int y = 0;
                y <= cellRows;
                y++
        ) {

            int py =
                    y * pixelsPerCell;

            if (
                    py < 0
                            || py >= height
            ) {
                continue;
            }

            int offset =
                    py * width;

            Arrays.fill(
                    pixels,
                    offset,
                    offset + width,
                    COLOR_GRID
            );
        }

        image.setRGB(
                0,
                0,
                width,
                height,
                pixels,
                0,
                width
        );
    }

    private static void writeMetadata(
            Path outputDir,
            int minCellX,
            int minCellY,
            int maxCellX,
            int maxCellY,
            int width,
            int height
    ) throws IOException {

        long originX =
                (long) minCellX
                        * CELL_SIZE;

        long originY =
                (long) minCellY
                        * CELL_SIZE;

        String json =
                String.format(
                        Locale.ROOT,
                        """
                        {
                          "image": "generated/vanilla-world.png",
                          "projection": "top-down",
                          "cellSize": %d,
                          "tilesPerPixel": %d,
                          "pixelsPerTile": %.8f,
                          "originX": %d,
                          "originY": %d,
                          "minCellX": %d,
                          "minCellY": %d,
                          "maxCellX": %d,
                          "maxCellY": %d,
                          "width": %d,
                          "height": %d
                        }
                        """,
                        CELL_SIZE,
                        TILES_PER_PIXEL,
                        1.0 / TILES_PER_PIXEL,
                        originX,
                        originY,
                        minCellX,
                        minCellY,
                        maxCellX,
                        maxCellY,
                        width,
                        height
                );

        Files.writeString(
                outputDir.resolve(
                        "vanilla-world.json"
                ),
                json
        );
    }

    private static List<CellRef> findCells(
            Path mapDir
    ) throws IOException {

        List<CellRef> result =
                new ArrayList<>();

        try (
                var stream =
                        Files.list(mapDir)
        ) {

            stream
                    .filter(
                            p -> p.getFileName()
                                    .toString()
                                    .endsWith(
                                            ".lotheader"
                                    )
                    )
                    .forEach(
                            p -> {

                                String name =
                                        p.getFileName()
                                                .toString()
                                                .replace(
                                                        ".lotheader",
                                                        ""
                                                );

                                String[] parts =
                                        name.split("_");

                                if (
                                        parts.length
                                                != 2
                                ) {
                                    return;
                                }

                                try {

                                    int x =
                                            Integer.parseInt(
                                                    parts[0]
                                            );

                                    int y =
                                            Integer.parseInt(
                                                    parts[1]
                                            );

                                    result.add(
                                            new CellRef(
                                                    mapDir,
                                                    name,
                                                    x,
                                                    y
                                            )
                                    );

                                } catch (
                                        NumberFormatException ignored
                                ) {
                                    // Ignore non-cell files.
                                }
                            }
                    );
        }

        result.sort(
                Comparator
                        .comparingInt(
                                (CellRef c) -> c.y
                        )
                        .thenComparingInt(
                                c -> c.x
                        )
        );

        return result;
    }

    private record CellRef(
            Path mapDir,
            String name,
            int x,
            int y
    ) {
    }
}