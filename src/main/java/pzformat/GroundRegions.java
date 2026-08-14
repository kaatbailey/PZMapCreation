package pzformat;

import java.util.ArrayDeque;

/**
 * Assigns one ground material per square, then dithers the boundaries.
 *
 * WHY THIS EXISTS. Generated ground read as scattered tan diamonds because
 * GroundPalette rolled Grass_Dark / Grass_Medium / Grass_Light per square from
 * measured frequencies. STATE §26: those three are REGION distinctions being
 * used as texture. Vanilla runs 16 identical squares in a row; we changed three
 * times in eight. The fix is regions, not a different mix.
 *
 * WHAT THE DATA SUPPORTS. GisImport.Cover is {NONE, ROAD, BUILDING} — there is
 * no landcover import, so there is no evidence for multiple grass regions in
 * open country. Inventing one would be a noise field, which E3 ruled out and
 * §27 left unsupported. So:
 *
 *   NONE far from anything   -> GRASS_DARK    one region, the 58.6% majority
 *   ring around BUILDING     -> SAND          §26 Q4: vanilla's mid-cell Sand
 *                                             is a fenced yard between a road
 *                                             and a shed, in an `emptyoutside`
 *                                             room. Footprints are evidence.
 *   verge along ROAD         -> GRASS_MEDIUM  §26: Medium appears as a verge at
 *                                             42_40 (60,200), blending onto the
 *                                             Sand lot and again east of it.
 *
 * Multiple grass regions across open country stay unbuilt until a landcover
 * source exists. That is a data gap, not a design choice.
 *
 * THE DITHER LAW — MEASURED, E8 Part 1.
 *
 * Region boundaries in vanilla interleave per square. STATE §27 measured a
 * 19.97% single-square-island share over 4,065 cells. E8 Part 1 measured the
 * law itself and found dither is INDEPENDENT PER SQUARE, not a noise field:
 *
 *   - matched-distance lift P(both minority)/P(minority)^2 is 0.95-1.14 on the
 *     boundary contour, across every material pair and every filter window,
 *     on 8,000+ pairs — independent.
 *   - two thirds of contour minority components are singletons.
 *   - the 5-10x lift further out is a DIFFERENT population: genuine small
 *     regions the majority filter smoothed away, mean component size 4 to 165
 *     against 2.0 on the contour. A single correlated field cannot give rho~0
 *     at p=0.46 and rho~0.7 at p=0.085.
 *
 * So: distance transform from the region edge, then one Bernoulli draw per
 * square at P[d]. No noise field, no correlation length.
 *
 * P below is the window-5 profile with the ~0.05 small-regions floor
 * subtracted, from Grass_Dark/Grass_Medium and Grass_Medium/Grass_Light which
 * agreed closely (0.318/0.332 at d=0, 0.187/0.213 at d=1, 0.093/0.095 at d=2).
 *
 * SEEDING. GisCells seeds its Random per cell so a cell regenerates identically
 * whether or not its neighbours are written. The dither flip must therefore be
 * driven by a position hash, not by that sequential Random — otherwise the same
 * world square dithers differently depending on which cell is being written,
 * and every cell boundary becomes a visible seam.
 */
public final class GroundRegions {

    /** P(square flips to the neighbouring material) by distance from the edge. */
    public static final double[] P = {0.06, 0.03, 0.01, 0.005};

    /** How far a yard extends from a building footprint, in squares. */
    public static final int YARD = 3;

    /** How far a verge extends from a road, in squares. */
    public static final int VERGE = 2;

    private GroundRegions() { }

    /**
     * Material per square for one 256x256 cell, dithered.
     *
     * Reads cover at GLOBAL coordinates so regions are continuous across cell
     * borders. Squares outside the raster are treated as open country rather
     * than left null — leaving them empty flips the whole 8x8 chunk to
     * procedural generation (STATE §7).
     *
     * @param g    the imported raster
     * @param ox   this cell's global x origin
     * @param oy   this cell's global y origin
     * @param seed world seed, hashed with position so the result is independent
     *             of which cell is being written
     */
    public static GroundMaterial[][] build(GisImport g, int ox, int oy, long seed) {
        GroundMaterial[][] mat = new GroundMaterial[256][256];

        // Distance to the nearest BUILDING and nearest ROAD, computed on a
        // margin so squares near this cell's edge see structures in the next
        // cell. Without the margin every cell border grows a false region edge.
        int margin = Math.max(YARD, VERGE) + P.length + 1;
        int[][] dB = coverDistance(g, ox, oy, margin, GisImport.Cover.BUILDING);
        int[][] dR = coverDistance(g, ox, oy, margin, GisImport.Cover.ROAD);

        for (int x = 0; x < 256; x++)
            for (int y = 0; y < 256; y++) {
                int gx = ox + x, gy = oy + y;
                GisImport.Cover c = coverAt(g, gx, gy);
                if (c == GisImport.Cover.BUILDING || c == GisImport.Cover.ROAD) {
                    mat[x][y] = null;      // GisCells writes its own tile here
                    continue;
                }
                int b = dB[x + margin][y + margin];
                int r = dR[x + margin][y + margin];
                if (b <= YARD) mat[x][y] = GroundMaterial.SAND;
                else if (r <= VERGE) mat[x][y] = GroundMaterial.GRASS_MEDIUM;
                else mat[x][y] = GroundMaterial.GRASS_DARK;
            }

        dither(mat, ox, oy, seed);
        return mat;
    }

    /**
     * One independent Bernoulli draw per square at P[distance-to-edge], flipping
     * to the material on the other side. Reads from a snapshot so a flipped
     * square cannot seed further flips — dither is a property of the region
     * edge, not a growth process.
     */
    static void dither(GroundMaterial[][] mat, int ox, int oy, long seed) {
        GroundMaterial[][] src = new GroundMaterial[256][256];
        for (int x = 0; x < 256; x++) System.arraycopy(mat[x], 0, src[x], 0, 256);

        GroundMaterial[][] across = new GroundMaterial[256][256];
        int[][] dist = edgeDistance(src, across);

        for (int x = 0; x < 256; x++)
            for (int y = 0; y < 256; y++) {
                if (src[x][y] == null) continue;
                int d = dist[x][y];
                if (d < 0 || d >= P.length) continue;
                if (hash01(ox + x, oy + y, seed) >= P[d]) continue;
                GroundMaterial other = across[x][y];
                if (other != null && other != src[x][y]) mat[x][y] = other;
            }
    }

    /**
     * 4-connected BFS distance to the nearest square of a different material,
     * propagating WHICH material that is into {@code across}.
     *
     * A seed square sits at d=0 because some orthogonal neighbour differs; that
     * neighbour's material is what lies across the nearest edge. Carrying it
     * along the BFS gives every square the correct opposite material without a
     * second search, and without the diagonal-before-orthogonal error a ring
     * scan can make once regions are narrow.
     */
    static int[][] edgeDistance(GroundMaterial[][] m, GroundMaterial[][] across) {
        int[][] d = new int[256][256];
        for (int[] row : d) java.util.Arrays.fill(row, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = 0; x < 256; x++)
            for (int y = 0; y < 256; y++) {
                if (m[x][y] == null) continue;
                for (int k = 0; k < 4; k++) {
                    int nx = x + DX[k], ny = y + DY[k];
                    if (nx < 0 || ny < 0 || nx > 255 || ny > 255) continue;
                    if (m[nx][ny] != null && m[nx][ny] != m[x][y]) {
                        d[x][y] = 0;
                        across[x][y] = m[nx][ny];
                        q.add(new int[]{x, y});
                        break;
                    }
                }
            }
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int k = 0; k < 4; k++) {
                int nx = p[0] + DX[k], ny = p[1] + DY[k];
                if (nx < 0 || ny < 0 || nx > 255 || ny > 255) continue;
                if (m[nx][ny] == null || d[nx][ny] >= 0) continue;
                d[nx][ny] = d[p[0]][p[1]] + 1;
                across[nx][ny] = across[p[0]][p[1]];
                q.add(new int[]{nx, ny});
            }
        }
        return d;
    }

    /** BFS distance to a cover class, over a margin-extended window. */
    static int[][] coverDistance(GisImport g, int ox, int oy, int margin,
                                 GisImport.Cover target) {
        int n = 256 + 2 * margin;
        int[][] d = new int[n][n];
        for (int[] row : d) java.util.Arrays.fill(row, Integer.MAX_VALUE);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                if (coverAt(g, ox - margin + i, oy - margin + j) == target) {
                    d[i][j] = 0;
                    q.add(new int[]{i, j});
                }
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int k = 0; k < 4; k++) {
                int ni = p[0] + DX[k], nj = p[1] + DY[k];
                if (ni < 0 || nj < 0 || ni >= n || nj >= n) continue;
                if (d[ni][nj] != Integer.MAX_VALUE) continue;
                d[ni][nj] = d[p[0]][p[1]] + 1;
                q.add(new int[]{ni, nj});
            }
        }
        return d;
    }

    static GisImport.Cover coverAt(GisImport g, int gx, int gy) {
        if (gx < 0 || gy < 0 || gx >= g.width || gy >= g.height)
            return GisImport.Cover.NONE;
        return g.cover[gx][gy];
    }

    /**
     * Position-hashed uniform in [0,1). Depends only on world position and the
     * world seed, never on iteration order, so a square dithers the same way
     * regardless of which cell is being written.
     */
    static double hash01(int gx, int gy, long seed) {
        long h = seed;
        h ^= (long) gx * 0x9E3779B97F4A7C15L;
        h ^= (long) gy * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    static final int[] DX = {0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0};
}
