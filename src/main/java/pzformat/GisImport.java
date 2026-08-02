package pzformat;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Turns public-domain GIS data into Project Zomboid map geometry.
 *
 * Buildings are rasterised as POLYGONS, not bounding boxes: each tile is tested
 * for containment, then walls are derived from the boundary — a tile inside the
 * footprint whose north neighbour is outside gets a north wall. That handles
 * arbitrary building shapes, snaps them to the grid for free, and uses exactly
 * the edge convention verified against vanilla rooms (south wall belongs to the
 * next square down, east wall to the next square right).
 *
 * Scale: one PZ tile is treated as one metre, which matches vanilla building
 * proportions closely.
 *
 * Nothing here needs to know where the data came from; it works in local tile
 * coordinates from the first projection step onward.
 */
public final class GisImport {

    /** What occupies a tile. Kept coarse for a first pass. */
    public enum Cover { NONE, ROAD, BUILDING }

    public int width, height;
    public Cover[][] cover;
    public boolean[][] northWall, westWall;
    public String[][] occupancy;      // OCC_CLS per building tile, for tile choice later

    public int buildingsPlaced, roadsPlaced, buildingTiles, roadTiles;
    public final Map<String, Integer> byOccupancy = new TreeMap<>();

    public static void run(Path buildingsFile, Path roadsFile, Path outDir,
                           int maxTiles) throws Exception {
        run(buildingsFile, roadsFile, null, outDir, maxTiles);
    }

    /**
     * @param areaFile the region you actually asked for. Strongly recommended:
     *                 feature services return WHOLE features that intersect a
     *                 bounding box, so a road crossing your area may run for
     *                 kilometres beyond it. Deriving the extent from the data
     *                 then inflates the canvas and squashes the buildings into
     *                 a cluster. Clipping to the requested area fixes that.
     */
    public static void run(Path buildingsFile, Path roadsFile, Path areaFile,
                           Path outDir, int maxTiles) throws Exception {
        GeoJson buildings = GeoJson.read(buildingsFile);
        GeoJson roads = Files.exists(roadsFile) ? GeoJson.read(roadsFile) : new GeoJson();
        System.out.println("features: " + buildings.features.size() + " buildings, "
                + roads.features.size() + " roads");
        if (buildings.features.isEmpty() && roads.features.isEmpty()) {
            System.out.println("nothing to import");
            return;
        }

        double minLon, minLat, maxLon, maxLat;
        double[] dataExtent = extentOf(concat(buildings, roads));

        if (areaFile != null && Files.exists(areaFile)) {
            double[] a = extentOf(GeoJson.read(areaFile).features);
            minLon = a[0]; minLat = a[1]; maxLon = a[2]; maxLat = a[3];
            System.out.println("extent taken from the requested area");
            double dw = dataExtent[2] - dataExtent[0], aw = maxLon - minLon;
            double dh = dataExtent[3] - dataExtent[1], ah = maxLat - minLat;
            if (dw > aw * 1.2 || dh > ah * 1.2)
                System.out.printf("   (returned data spans %.1fx%.1f times the requested"
                        + " area — clipping)%n", dw / aw, dh / ah);
        } else {
            minLon = dataExtent[0]; minLat = dataExtent[1];
            maxLon = dataExtent[2]; maxLat = dataExtent[3];
            System.out.println("WARNING: no area file given, so the extent comes from the"
                    + "\n   returned data. Road segments often run far outside the region"
                    + "\n   you asked for, which inflates the canvas and clusters the"
                    + "\n   buildings. Pass the area file you drew for correct framing.");
        }

        // Equirectangular projection to metres. Exact enough over a few km.
        double midLat = (minLat + maxLat) / 2;
        double mPerLon = 111_320.0 * Math.cos(Math.toRadians(midLat));
        double mPerLat = 110_540.0;

        GisImport g = new GisImport();
        g.width = (int) Math.ceil((maxLon - minLon) * mPerLon);
        g.height = (int) Math.ceil((maxLat - minLat) * mPerLat);
        System.out.println("extent: " + g.width + " x " + g.height + " tiles ("
                + String.format("%.2f", g.width * g.height / 65536.0) + " cells)");

        if (g.width > maxTiles || g.height > maxTiles) {
            System.out.println("clamping to " + maxTiles + " tiles per side for this pass");
            g.width = Math.min(g.width, maxTiles);
            g.height = Math.min(g.height, maxTiles);
        }

        g.cover = new Cover[g.width][g.height];
        g.occupancy = new String[g.width][g.height];
        g.northWall = new boolean[g.width][g.height];
        g.westWall = new boolean[g.width][g.height];
        for (Cover[] col : g.cover) Arrays.fill(col, Cover.NONE);

        // Roads first: buildings should win where they overlap.
        for (GeoJson.Feature f : roads.features) {
            int w = roadWidth(f.prop("MTFCC"));
            for (List<double[]> ring : f.rings) {
                List<int[]> pts = g.project(ring, minLon, maxLat, mPerLon, mPerLat);
                for (int i = 0; i + 1 < pts.size(); i++)
                    g.thickLine(pts.get(i), pts.get(i + 1), w);
            }
            g.roadsPlaced++;
        }

        for (GeoJson.Feature f : buildings.features) {
            String occ = f.prop("OCC_CLS");
            if (occ == null || occ.isEmpty()) occ = "Unknown";
            boolean any = false;
            for (List<double[]> ring : f.rings)
                if (g.fillPolygon(g.project(ring, minLon, maxLat, mPerLon, mPerLat), occ)) any = true;
            if (any) {
                g.buildingsPlaced++;
                g.byOccupancy.merge(occ, 1, Integer::sum);
            }
        }

        g.deriveWalls();

        int outside = 0;
        for (GeoJson.Feature f : buildings.features) {
            boolean any = false;
            for (List<double[]> ring : f.rings)
                for (double[] p : ring)
                    if (p[0] >= minLon && p[0] <= maxLon && p[1] >= minLat && p[1] <= maxLat)
                        any = true;
            if (!any) outside++;
        }
        if (outside > 0)
            System.out.println("note: " + outside + " buildings fall outside the"
                    + " requested area and were clipped");


        System.out.println("\nplaced: " + g.buildingsPlaced + " buildings ("
                + g.buildingTiles + " tiles), " + g.roadsPlaced + " roads ("
                + g.roadTiles + " tiles)");
        int nw = 0, ww = 0;
        for (int x = 0; x < g.width; x++)
            for (int y = 0; y < g.height; y++) {
                if (g.northWall[x][y]) nw++;
                if (g.westWall[x][y]) ww++;
            }
        System.out.println("derived walls: " + nw + " north, " + ww + " west");
        System.out.println("\nby occupancy class:");
        g.byOccupancy.forEach((k, v) -> System.out.printf("   %-24s %d%n", k, v));

        Files.createDirectories(outDir);
        Path png = outDir.resolve("import_schematic.png");
        g.writeSchematic(png);
        System.out.println("\nschematic written to " + png);
        System.out.println("Open it next to the real map. Building shapes and road"
                + " layout should be recognisable.");
    }

    static double[] extentOf(List<GeoJson.Feature> feats) {
        double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        for (GeoJson.Feature f : feats)
            for (List<double[]> ring : f.rings)
                for (double[] p : ring) {
                    minLon = Math.min(minLon, p[0]); maxLon = Math.max(maxLon, p[0]);
                    minLat = Math.min(minLat, p[1]); maxLat = Math.max(maxLat, p[1]);
                }
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    static List<GeoJson.Feature> concat(GeoJson a, GeoJson b) {
        List<GeoJson.Feature> all = new ArrayList<>(a.features);
        all.addAll(b.features);
        return all;
    }

    /** Road half-width in tiles by Census MTFCC class. */
    static int roadWidth(String mtfcc) {
        if (mtfcc == null) return 3;
        return switch (mtfcc) {
            case "S1100" -> 8;    // primary, divided highway
            case "S1200" -> 5;    // secondary
            case "S1400" -> 3;    // local street
            case "S1500", "S1640" -> 2;
            case "S1710", "S1720", "S1730" -> 1;   // walkway, stairway, alley
            default -> 3;
        };
    }

    List<int[]> project(List<double[]> ring, double minLon, double maxLat,
                        double mPerLon, double mPerLat) {
        List<int[]> out = new ArrayList<>(ring.size());
        for (double[] p : ring) {
            int x = (int) Math.round((p[0] - minLon) * mPerLon);
            int y = (int) Math.round((maxLat - p[1]) * mPerLat);   // north is -y
            out.add(new int[]{x, y});
        }
        return out;
    }

    boolean inBounds(int x, int y) { return x >= 0 && y >= 0 && x < width && y < height; }

    /** Scanline fill with even-odd containment. Returns true if anything landed. */
    boolean fillPolygon(List<int[]> ring, String occ) {
        if (ring.size() < 3) return false;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] p : ring) { minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]); }
        minY = Math.max(minY, 0);
        maxY = Math.min(maxY, height - 1);
        boolean any = false;

        for (int y = minY; y <= maxY; y++) {
            List<Integer> xs = new ArrayList<>();
            for (int i = 0; i < ring.size(); i++) {
                int[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
                if (a[1] == b[1]) continue;
                int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
                if (y < y0 || y >= y1) continue;
                double t = (y - a[1]) / (double) (b[1] - a[1]);
                xs.add((int) Math.round(a[0] + t * (b[0] - a[0])));
            }
            Collections.sort(xs);
            for (int i = 0; i + 1 < xs.size(); i += 2)
                for (int x = Math.max(xs.get(i), 0); x <= Math.min(xs.get(i + 1), width - 1); x++) {
                    if (cover[x][y] != Cover.BUILDING) buildingTiles++;
                    cover[x][y] = Cover.BUILDING;
                    occupancy[x][y] = occ;
                    any = true;
                }
        }
        return any;
    }

    void thickLine(int[] a, int[] b, int halfWidth) {
        int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
        if (steps == 0) steps = 1;
        for (int i = 0; i <= steps; i++) {
            int cx = a[0] + (b[0] - a[0]) * i / steps;
            int cy = a[1] + (b[1] - a[1]) * i / steps;
            for (int dx = -halfWidth; dx <= halfWidth; dx++)
                for (int dy = -halfWidth; dy <= halfWidth; dy++) {
                    if (dx * dx + dy * dy > halfWidth * halfWidth) continue;
                    int x = cx + dx, y = cy + dy;
                    if (!inBounds(x, y) || cover[x][y] == Cover.BUILDING) continue;
                    if (cover[x][y] != Cover.ROAD) roadTiles++;
                    cover[x][y] = Cover.ROAD;
                }
        }
    }

    /**
     * Walls from the rasterised footprint boundary, using the edge convention
     * confirmed against vanilla rooms: a wall lives on the north or west edge
     * of a square, so the south face of a building sits on the square BELOW it.
     */
    void deriveWalls() {
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++) {
                if (cover[x][y] != Cover.BUILDING) continue;
                if (!isBuilding(x, y - 1)) northWall[x][y] = true;      // north face
                if (!isBuilding(x - 1, y)) westWall[x][y] = true;       // west face
                if (!isBuilding(x, y + 1) && inBounds(x, y + 1))
                    northWall[x][y + 1] = true;                          // south face
                if (!isBuilding(x + 1, y) && inBounds(x + 1, y))
                    westWall[x + 1][y] = true;                           // east face
            }
    }

    boolean isBuilding(int x, int y) {
        return inBounds(x, y) && cover[x][y] == Cover.BUILDING;
    }

    /** Top-down schematic: the quickest way to see whether the import is sane. */
    void writeSchematic(Path out) throws Exception {
        int scale = width > 1200 || height > 1200 ? 1 : 2;
        BufferedImage img = new BufferedImage(width * scale, height * scale,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(28, 32, 28));
        g.fillRect(0, 0, width * scale, height * scale);

        Color road = new Color(70, 70, 76);
        Color bldg = new Color(150, 120, 90);
        Color wall = new Color(240, 235, 220);
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++) {
                Color c = switch (cover[x][y]) {
                    case ROAD -> road;
                    case BUILDING -> bldg;
                    default -> null;
                };
                if (c == null) continue;
                g.setColor(c);
                g.fillRect(x * scale, y * scale, scale, scale);
            }
        g.setColor(wall);
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++) {
                if (northWall[x][y]) g.fillRect(x * scale, y * scale, scale, Math.max(1, scale / 2));
                if (westWall[x][y]) g.fillRect(x * scale, y * scale, Math.max(1, scale / 2), scale);
            }
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }
}
