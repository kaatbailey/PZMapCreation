package pzformat;

import java.util.List;
import java.util.Random;

/**
 * Tree placement driven by distance from habitation.
 *
 * Distance to the nearest building or road is a good proxy for how long ground
 * has gone undisturbed, so it drives how DENSE the trees are and whether they
 * start as saplings or full trees. Yards and verges stay open, regrowth
 * thickens outward.
 *
 * What it deliberately does NOT do: choose species or mature size. Authored
 * map data cannot express those — vanilla writes only generic
 * vegetation_trees_01 tiles carrying a `tree` size class, and the engine picks
 * the actual pine or maple at runtime from the biome. Attempting to author
 * species art directly produces canopies lying on the grass. If you want
 * conifers dominating the far ground, that goes in WorldGenOverride.lua.
 *
 * Bands, by BFS distance in tiles from any building or road square:
 *
 *   0 .. CLEAR   nothing. Yards and road verges stay open.
 *   .. 9         sparse saplings. Planted, kept small.
 *   .. 22        regrowth.
 *   .. 45        woodland.
 *   beyond       dense.
 *
 * Stumps are scattered thinly beyond the clear band. A stump is a tree that
 * was there and isn't.
 *
 * A minimum spacing keeps trunks off adjacent squares.
 *
 * Deterministic for a given seed, so a diff between runs shows real changes
 * rather than reshuffled noise.
 */
public final class TreeScatter {

    public static final int CLEAR = 3;

    /** Minimum tiles between trunks. */
    public static final int SPACING = 2;

    /** Chance per eligible square of a stump instead of a tree. */
    public static final double P_STUMP = 0.0010;

    /**
     * @param maxDist upper bound of the band
     * @param size    `tree` size class to author (1 sapling, 2 tree)
     * @param density chance per eligible square
     */
    record Band(int maxDist, int size, double density, String label) { }

    static final Band[] BANDS = {
            new Band(9,  1, 0.020, "roadside"),
            new Band(22, 2, 0.035, "regrowth"),
            new Band(45, 2, 0.070, "woodland"),
            new Band(Integer.MAX_VALUE, 2, 0.080, "dense"),
    };

    /**
     * @return tile name per raster square, or null where nothing goes.
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

        long[] perBand = new long[BANDS.length];
        long stumps = 0;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (isStructure(g, x, y)) {
                    continue;
                }
                int d = dist[x][y];
                if (d <= CLEAR) {
                    continue;
                }

                int bi = bandFor(d);
                Band band = BANDS[bi];

                // A stump is a tree that used to be here, so it competes for
                // the same square and respects the same spacing.
                if (tp.hasStump && rng.nextDouble() < P_STUMP) {
                    if (!tooClose(taken, x, y, w, h)) {
                        out[x][y] = TreePalette.STUMP;
                        taken[x][y] = true;
                        stumps++;
                    }
                    continue;
                }

                if (rng.nextDouble() >= band.density()) {
                    continue;
                }
                if (tooClose(taken, x, y, w, h)) {
                    continue;
                }

                List<String> variants = tp.tilesNear(band.size());
                if (variants == null || variants.isEmpty()) {
                    continue;
                }

                out[x][y] = variants.get(rng.nextInt(variants.size()));
                taken[x][y] = true;
                perBand[bi]++;
            }
        }

        long total = 0;
        for (long n : perBand) {
            total += n;
        }
        System.out.println("trees: " + total + " placed, " + stumps + " stumps");
        for (int i = 0; i < BANDS.length; i++) {
            System.out.printf("   %-10s size %d  %6d%n",
                    BANDS[i].label(), BANDS[i].size(), perBand[i]);
        }
        System.out.println("   (species and mature size are chosen by the engine"
                + " at runtime; the renderer cannot preview these)");
        return out;
    }

    static int bandFor(int d) {
        for (int i = 0; i < BANDS.length; i++) {
            if (d <= BANDS[i].maxDist()) {
                return i;
            }
        }
        return BANDS.length - 1;
    }

    /**
     * BFS distance from every building or road square. Squares that ARE
     * structure get 0, so bands measure outward from them.
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

        // No structure anywhere: everything is the densest band.
        if (tail == 0) {
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
}
