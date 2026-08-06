package pzformat;

import java.util.List;
import java.util.Random;

/**
 * Structured tree placement.
 *
 * Uniform scatter reads as noise. This drives density off the distance to the
 * nearest building or road, which the GIS data already gives us, so the result
 * has cleared yards, verges along the road, and thickening woodland further
 * out — the shape a lived-in place actually has.
 *
 * Bands, by BFS distance in tiles from any building or road square:
 *
 *   0  .. CLEAR   nothing. Yards and road verges stay open.
 *   .. YARD       sparse ornamental trees, small size class.
 *   .. MID        moderate.
 *   beyond        woodland.
 *
 * Two touches that matter more than the density curve:
 *
 *   - Species is chosen per GROVE-sized block, not per tile, so a stand of
 *     trees is one species. Per-tile choice mixes ten species evenly and looks
 *     like confetti.
 *   - A minimum spacing stops trunks clumping. Jumbo sprites are up to
 *     192x256, so adjacent trees overlap into an unreadable mass.
 *
 * Deterministic for a given seed: the same GIS input produces the same forest,
 * so a render diff between runs shows real changes rather than reshuffled noise.
 */
public final class TreeScatter {

    public static final int CLEAR = 3;
    public static final int YARD = 9;
    public static final int MID = 22;

    public static final double P_YARD = 0.010;
    public static final double P_MID = 0.035;
    public static final double P_WOOD = 0.080;

    /** Minimum tiles between trunks. */
    public static final int SPACING = 2;
    /** Species stays constant within a block this many tiles across. */
    public static final int GROVE = 32;

    /**
     * @return tile name per raster square, or null where no tree goes.
     */
    public static String[][] place(GisImport g, TreePalette tp, long seed) {
        int w = g.width, h = g.height;
        String[][] out = new String[w][h];

        if (!tp.usable()) {
            System.out.println("trees: no usable tree tiles; skipping scatter");
            return out;
        }

        int[][] dist = distanceToStructure(g);
        Random rng = new Random(seed);
        boolean[][] taken = new boolean[w][h];

        List<String> canopySpecies = tp.species(true);
        List<String> yardSpecies = tp.species(false);

        long yard = 0, mid = 0, wood = 0;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (isStructure(g, x, y)) {
                    continue;
                }

                int d = dist[x][y];
                if (d <= CLEAR) {
                    continue;
                }

                boolean canopy;
                double p;
                if (d <= YARD) {
                    p = P_YARD;
                    canopy = false;
                } else if (d <= MID) {
                    p = P_MID;
                    canopy = true;
                } else {
                    p = P_WOOD;
                    canopy = true;
                }

                if (rng.nextDouble() >= p) {
                    continue;
                }
                if (tooClose(taken, x, y, w, h)) {
                    continue;
                }

                List<String> pool = canopy || yardSpecies.isEmpty()
                        ? canopySpecies : yardSpecies;
                String sp = pool.get(groveHash(x / GROVE, y / GROVE, seed, pool.size()));
                List<String> variants = tp.variants(canopy, sp);
                if (variants == null || variants.isEmpty()) {
                    continue;
                }

                out[x][y] = variants.get(rng.nextInt(variants.size()));
                taken[x][y] = true;
                if (d <= YARD) yard++;
                else if (d <= MID) mid++;
                else wood++;
            }
        }

        System.out.println("trees: " + (yard + mid + wood) + " placed"
                + "  (yard " + yard + ", mid " + mid + ", woodland " + wood + ")");
        return out;
    }

    /**
     * BFS distance from every building or road square. Squares that ARE
     * structure get 0, so the clear band measures outward from them.
     */
    static int[][] distanceToStructure(GisImport g) {
        int w = g.width, h = g.height;
        int[][] dist = new int[w][h];
        int[] queue = new int[w * h];
        int head = 0, tail = 0;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (isStructure(g, x, y)) {
                    dist[x][y] = 0;
                    queue[tail++] = x * h + y;
                } else {
                    dist[x][y] = Integer.MAX_VALUE;
                }
            }
        }

        // No structure anywhere: everything is woodland.
        if (tail == 0) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    dist[x][y] = Integer.MAX_VALUE;
                }
            }
            return dist;
        }

        final int[] dx = {1, -1, 0, 0};
        final int[] dy = {0, 0, 1, -1};
        while (head < tail) {
            int cur = queue[head++];
            int x = cur / h, y = cur % h;
            int nd = dist[x][y] + 1;
            for (int k = 0; k < 4; k++) {
                int nx = x + dx[k], ny = y + dy[k];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    continue;
                }
                if (dist[nx][ny] > nd) {
                    dist[nx][ny] = nd;
                    queue[tail++] = nx * h + ny;
                }
            }
        }
        return dist;
    }

    /** Building, road, or a derived wall — anything trees must keep off. */
    static boolean isStructure(GisImport g, int x, int y) {
        return g.cover[x][y] == GisImport.Cover.BUILDING
                || g.cover[x][y] == GisImport.Cover.ROAD
                || g.northWall[x][y] || g.westWall[x][y];
    }

    static boolean tooClose(boolean[][] taken, int x, int y, int w, int h) {
        int x0 = Math.max(0, x - SPACING), x1 = Math.min(w - 1, x + SPACING);
        int y0 = Math.max(0, y - SPACING), y1 = Math.min(h - 1, y + SPACING);
        for (int i = x0; i <= x1; i++) {
            for (int j = y0; j <= y1; j++) {
                if (taken[i][j]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Stable per-block species choice, independent of iteration order. */
    static int groveHash(int bx, int by, long seed, int n) {
        long v = seed;
        v = v * 0x9E3779B97F4A7C15L + bx * 0xBF58476D1CE4E5B9L;
        v = v * 0x9E3779B97F4A7C15L + by * 0x94D049BB133111EBL;
        v ^= (v >>> 31);
        return (int) Math.floorMod(v, n);
    }
}
