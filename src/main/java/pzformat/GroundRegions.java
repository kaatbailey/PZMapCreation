package pzformat;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        // Work over a margin so distances are correct right to the cell edge,
        // then hand back the cell plus a one-square border. Squares in the
        // border belong to the neighbouring cell; because the dither is a
        // position hash rather than a sequential draw, that cell computes the
        // same material for them and the masks either side of a cell boundary
        // agree.
        final int m = MARGIN;
        final int n = 256 + 2 * m;

        int[][] dB = coverDistance(g, ox, oy, m, GisImport.Cover.BUILDING);
        int[][] dR = coverDistance(g, ox, oy, m, GisImport.Cover.ROAD);

        GroundMaterial[][] wide = new GroundMaterial[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                int gx = ox - m + i, gy = oy - m + j;
                GisImport.Cover c = coverAt(g, gx, gy);
                if (c == GisImport.Cover.BUILDING)
                    continue;              // null: interior, never blends
                if (c == GisImport.Cover.ROAD) {
                    // The road joins the array so neighbouring grass can mask
                    // onto it (§27: Grass_Dark > Road_04 at n=284,583).
                    // GisCells still writes the road tile itself; this is only
                    // for the neighbour rule. Must match the tile GisCells
                    // writes — blends_street_01_0 is block 0, i.e. Road_01.
                    wide[i][j] = GroundMaterial.ROAD_01;
                    continue;
                }
                if (dB[i][j] <= YARD) wide[i][j] = GroundMaterial.SAND;
                else if (dR[i][j] <= VERGE) wide[i][j] = GroundMaterial.GRASS_MEDIUM;
                else wide[i][j] = GroundMaterial.GRASS_DARK;
            }

        dither(wide, ox - m, oy - m, seed);

        GroundMaterial[][] out = new GroundMaterial[258][258];
        for (int x = 0; x < 258; x++)
            System.arraycopy(wide[m - 1 + x], m - 1, out[x], 0, 258);
        return out;
    }

    /**
     * Computation margin. Only distances below P.length matter to the dither,
     * so 8 leaves ample headroom for the region assignment and the edge
     * distance transform to be correct across the whole returned window.
     */
    static final int MARGIN = 8;

    /**
     * Append this square's blend masks to its tile stack.
     *
     * For each distinct neighbouring material that OUTRANKS this square's, the
     * mask rule runs independently and the results concatenate — §27 confirmed
     * multi-material squares are common in vanilla, with no interaction
     * observed between the sets.
     *
     * Call AFTER the solid tile and BEFORE the tuft: the solid must be first in
     * the stack or getFloor() and cleanChunk read the wrong tile (§26).
     *
     * @param region bordered array from {@link #build}, so cell-local (x,y) is
     *               {@code region[x+1][y+1]} and a neighbour off the cell edge
     *               resolves to the adjacent cell's material rather than
     *               falling off the array
     * @param x      cell-local x, 0..255
     * @param y      cell-local y, 0..255
     * @param self   this square's material
     */
    public static void addMasks(List<Integer> stack, CellData cell,
                                GroundMaterial[][] region, int x, int y,
                                GroundMaterial self, Random rng) {
        if (self == null) return;
        EnumMap<GroundMaterial, EnumSet<MaskRule.Dir>> byMat =
                new EnumMap<>(GroundMaterial.class);

        for (MaskRule.Dir d : MaskRule.Dir.values()) {
            GroundMaterial other = region[x + 1 + d.dx][y + 1 + d.dy];
            if (other == null || !other.outranks(self)) continue;
            byMat.computeIfAbsent(other, k -> EnumSet.noneOf(MaskRule.Dir.class)).add(d);
        }

        // 2 variant sets: blends_natural_01 carries B+8..11 and B+12..15.
        // blends_street_01 has only one and would take 1 here (§27).
        for (Map.Entry<GroundMaterial, EnumSet<MaskRule.Dir>> e : byMat.entrySet())
            for (int idx : MaskRule.masks(e.getKey().block, e.getValue(),
                                          e.getKey().variantSets, rng))
                stack.add(cell.tileIndex(e.getKey().sheet + idx));
    }

    /**
     * One independent Bernoulli draw per square at P[distance-to-edge], flipping
     * to the material on the other side. Reads from a snapshot so a flipped
     * square cannot seed further flips — dither is a property of the region
     * edge, not a growth process.
     */
    static void dither(GroundMaterial[][] mat, int ox, int oy, long seed) {
        final int n = mat.length;
        GroundMaterial[][] src = new GroundMaterial[n][n];
        for (int x = 0; x < n; x++) System.arraycopy(mat[x], 0, src[x], 0, n);

        GroundMaterial[][] across = new GroundMaterial[n][n];
        int[][] dist = edgeDistance(src, across);

        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                if (src[x][y] == null) continue;
                int d = dist[x][y];
                if (d < 0 || d >= P.length) continue;
                if (hash01(ox + x, oy + y, seed) >= P[d]) continue;
                GroundMaterial other = across[x][y];
                if (other == null || other == src[x][y]) continue;
                // Never dither across a road boundary. Roads are in the array
                // so grass can MASK onto them; interleaving them would put
                // grass squares in the carriageway and road squares in the
                // field. A road edge is a hard edge, softened by masks only.
                if (isRoad(other) || isRoad(src[x][y])) continue;
                mat[x][y] = other;
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
        final int n = m.length;
        int[][] d = new int[n][n];
        for (int[] row : d) java.util.Arrays.fill(row, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++) {
                if (m[x][y] == null) continue;
                for (int k = 0; k < 4; k++) {
                    int nx = x + DX[k], ny = y + DY[k];
                    if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
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
                if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue;
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

    /** Roads take part in masking but never in dither. */
    static boolean isRoad(GroundMaterial m) {
        return m != null && m.sheet.equals(GroundMaterial.STREET);
    }

    static final int[] DX = {0, 0, -1, 1};
    static final int[] DY = {-1, 1, 0, 0};
}
