package pzformat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes `maps/biomemap_X_Y.png`, the per-tile biome and zone map.
 *
 * CONFIRMED by decompiling zombie.iso.worldgen.maps.BiomeRaster:
 *
 *     private static final int NUM_BANDS = 2;
 *     for (int i = 0; i < 2; i++)
 *         this.pixels[x * 2 + i + y * span] = (byte) pixel[i];
 *
 * So per pixel: RED = biome index, GREEN = zone index, BLUE ignored. Both
 * indices are looked up in the same `biome_map_config` table
 * (media/lua/server/metazones/BiomeMapConfig.lua), via BiomeMap.getBiomeName()
 * and getZoneName(). BiomeMap.Type is BIOME(0), ZONE(1) — a band selector, not
 * a flag.
 *
 * That explains vanilla's colour families: (153,153,153) is one config entry
 * used for both; (254,141,254) is biome 254 `dirt` inside zone 141 `FarmLand`;
 * (179,64,64) is biome 179 `pr_forest` inside zone 64 `ForagingNav`.
 *
 * One 256x256 image per cell, one pixel per tile. BiomeMap.getRaster searches
 * every map named in IsoWorld.getMap() (semicolon separated) and takes the
 * first file that exists; a missing file logs a debug line and returns null,
 * so shipping these is safe and incremental.
 *
 * ⚠️ SCOPE. WorldGen only generates chunks where
 * IsoChunk.hasEmptySquaresOnLevelZero() is true. Since GisCells fills every
 * square of every chunk, none of ours are generated, so the BIOME band may
 * currently do nothing for us. The ZONE band still matters — it drives
 * foraging zones. Whether biome also feeds the authored path (genMapChunk)
 * is UNVERIFIED; vanilla both authors every square and ships these files,
 * which suggests it does something, but that has not been read.
 */
public final class BiomeMapWriter {

    /**
     * A biome_map_config entry. Both bands index the same table, so a pair of
     * values is a (what grows here, what kind of place this is) choice.
     *
     * These particular assignments are a DESIGN CHOICE, not a measurement —
     * unlike the tile data elsewhere in this project, nothing in the game says
     * a GIS building footprint should be a TownZone. Change them freely.
     */
    public static final int TOWN = 115;            // townhouse   / TownZone
    public static final int FARM = 128;            // farmmix_forest / Farm
    public static final int FARMLAND = 141;        // farmmix_forest / FarmLand
    public static final int FARM_FOREST = 204;     // farm_forest / FarmForest
    public static final int PH_FOREST = 153;       // ph_forest   / PHForest
    public static final int BIRCH_FOREST = 217;    // birch_forest / BirchForest
    public static final int DEEP_FOREST = 255;     // primary_forest / DeepForest
    public static final int DIRT = 254;            // dirt        / ForagingNav

    /** Distance bands out from any building or road, in tiles. */
    public static final int TOWN_RADIUS = 10;
    public static final int EDGE_RADIUS = 28;
    public static final int FOREST_RADIUS = 70;

    /**
     * @return number of images written
     */
    public static int write(GisImport g, Path mapDir,
                            int cellsX, int cellsY,
                            int originCellX, int originCellY) throws Exception {

        Path outDir = mapDir.resolve("maps");
        Files.createDirectories(outDir);

        int[][] dist = TreeScatter.distanceToStructure(g);
        long town = 0, edge = 0, forest = 0, deep = 0, outside = 0;
        int written = 0;

        for (int cy = 0; cy < cellsY; cy++) {
            for (int cx = 0; cx < cellsX; cx++) {
                BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
                int ox = cx * 256, oy = cy * 256;

                for (int x = 0; x < 256; x++) {
                    for (int y = 0; y < 256; y++) {
                        int gx = ox + x, gy = oy + y;
                        int value;

                        if (gx >= g.width || gy >= g.height) {
                            // Beyond the imported area. Treat as the outermost
                            // band so the edge of the mod matches open country
                            // rather than cutting to something else.
                            value = DEEP_FOREST;
                            outside++;
                        } else {
                            int d = dist[gx][gy];
                            if (d <= TOWN_RADIUS) {
                                value = TOWN;
                                town++;
                            } else if (d <= EDGE_RADIUS) {
                                value = FARM_FOREST;
                                edge++;
                            } else if (d <= FOREST_RADIUS) {
                                value = PH_FOREST;
                                forest++;
                            } else {
                                value = DEEP_FOREST;
                                deep++;
                            }
                        }

                        // RED = biome band, GREEN = zone band, BLUE unread.
                        int rgb = (value << 16) | (value << 8) | value;
                        img.setRGB(x, y, rgb);
                    }
                }

                String name = "biomemap_" + (originCellX + cx) + "_" + (originCellY + cy) + ".png";
                ImageIO.write(img, "png", outDir.resolve(name).toFile());
                written++;
            }
        }

        System.out.println("biome maps: " + written + " written to " + outDir.getFileName()
                + "/");
        System.out.printf("   town %d, edge %d, forest %d, deep %d, beyond-raster %d%n",
                town, edge, forest, deep, outside);
        System.out.println("   R=biome G=zone, both indexed into biome_map_config");
        return written;
    }

    private BiomeMapWriter() { }
}
