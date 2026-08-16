package pzformat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a GIS footprint into an axis-aligned rectangle. One module, two
 * callers: GIS import and interactive authoring.
 *
 * WHY THIS EXISTS, and why it is not a rotation.
 *
 * STATE §10: a room is a union of `int32 x, y, w, h` rectangles. No rotation
 * field, no polygon, no vertex list. **So every wall runs due north-south or
 * east-west, and the target orientation is 0° — fixed by the format, not
 * discoverable from data.** An axis-aligned edge lands exactly on the tile
 * grid: no rounding, no steps. Jaggedness is not a defect in its own right, it
 * is the SYMPTOM of an off-axis edge. The generated road shows both halves —
 * its straight runs are clean and only its diagonals stair-step.
 *
 * §17 proposed finding a dominant building grid and rotating the whole scene
 * onto it. STATE §30 retires that twice over: `FootprintAngles` measured 7
 * rural footprints at 37/61/65/71/76/80°, best ±3° window holding 33.3% against
 * a predicted "well over half" — scattered farmsteads each square to their own
 * driveway. And it was never needed, because there is nothing to rotate *to*.
 *
 * WHY THE OUTLINE IS DISCARDED. STATE §30, over 90,827 Muldraugh rooms: 64.5%
 * are exactly one rectangle, 93% are three or fewer, fill ratio median 1.000.
 * **Tracing a real GIS polygon would produce buildings MORE complex than
 * vanilla's.** The bounding rectangle is closer to what vanilla does than the
 * truth is. The footprint is a position and an area, not a shape.
 *
 * WHY REFUSING IS SAFE. §17 check 1, answered 2026-08-14: across 90,827 rooms
 * there are 107 runs of three-plus rects stepping the same way (0.12%), and
 * every one is a *widening* room — rows 4, 6, 8 squares wide — not a diagonal.
 * Not one off-axis wall exists in vanilla, because the format cannot express
 * one. So {@link #isAxisAligned} may refuse rather than warn.
 */
public final class FootprintSnap {

    private FootprintSnap() { }

    /**
     * The axis-aligned rectangle standing in for a footprint.
     *
     * @param x left edge in tiles
     * @param y top edge in tiles
     * @param w width, at least 1
     * @param h height, at least 1
     */
    public record Rect(int x, int y, int w, int h) {
        public int area() { return w * h; }
        public int cx() { return x + w / 2; }
        public int cy() { return y + h / 2; }
        @Override public String toString() {
            return "[" + x + "," + y + " " + w + "x" + h + "]";
        }
    }

    /**
     * Snap a projected footprint ring to an axis-aligned rectangle.
     *
     * Preserves the footprint's **area** and **centroid**, and takes its aspect
     * ratio from the minimum-area enclosing rectangle — so a long thin barn
     * stays long and thin, it simply stops being rotated. The rectangle is then
     * scaled so its area matches the polygon's rather than the bounding box's,
     * which would otherwise inflate every building by roughly 1/cos of its
     * angle.
     *
     * @param ring projected tile coordinates, as {@code GisImport.project}
     *             returns them
     * @return the rectangle, or null if the ring is degenerate
     */
    public static Rect snap(List<int[]> ring) {
        return snap(dedupe(ring));
    }

    /**
     * Snap from EXACT projected coordinates.
     *
     * Prefer this. Integer input has already lost 2-7% of the footprint's area
     * to vertex quantisation before this method can measure it, and the loss is
     * always downward — every one of the seven test footprints measured below
     * its recorded SQMETERS.
     */
    public static Rect snap(double[][] pts) {
        double[][] p = dedupeExact(pts);
        if (p.length < 3) return null;

        double area = Math.abs(shoelace(p));
        if (area < 1) return null;

        double[] c = centroid(p, area);

        double[][] h = hull(p);
        double[] mar = minAreaRect(h);          // {angle, area, w, h}
        double w = mar[2], ht = mar[3];
        if (w <= 0 || ht <= 0) return null;

        // Match the polygon's area, not the enclosing rectangle's. A rotated
        // building's min-area rect is close to its true area, but the two are
        // not identical and the error compounds across a town.
        double scale = Math.sqrt(area / (w * ht));
        double ew = w * scale, eh = ht * scale;

        // Round the LONGER side, then derive the shorter from the area.
        // Rounding both independently discards the one quantity we care about,
        // and on a small building one square is a large fraction of a side.
        int rw, rh;
        if (ew >= eh) {
            rw = Math.max(1, (int) Math.round(ew));
            rh = Math.max(1, (int) Math.round(area / rw));
        } else {
            rh = Math.max(1, (int) Math.round(eh));
            rw = Math.max(1, (int) Math.round(area / rh));
        }

        return new Rect((int) Math.round(c[0] - rw / 2.0),
                        (int) Math.round(c[1] - rh / 2.0), rw, rh);
    }

    /** Polygon area in square tiles, for callers comparing against a dataset. */
    public static double area(double[][] pts) {
        double[][] p = dedupeExact(pts);
        return p.length < 3 ? 0 : Math.abs(shoelace(p));
    }

    /** Drop the closing vertex if the ring repeats its first point. */
    static double[][] dedupeExact(double[][] p) {
        int n = p.length;
        if (n > 1 && p[0][0] == p[n - 1][0] && p[0][1] == p[n - 1][1]) n--;
        return Arrays.copyOf(p, Math.max(0, n));
    }

    /**
     * Is every edge of this ring axis-parallel? The editor-facing half.
     *
     * A4's enforcement point is "wall run not expressible as a room rect", and
     * this is the same question asked of a proposed outline before it is
     * committed. Vanilla contains no counterexample (§17 check 1), so a caller
     * may refuse on false rather than merely warn.
     */
    public static boolean isAxisAligned(List<int[]> ring) {
        double[][] p = dedupe(ring);
        if (p.length < 3) return false;
        for (int i = 0; i < p.length; i++) {
            double[] a = p[i], b = p[(i + 1) % p.length];
            if (a[0] != b[0] && a[1] != b[1]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------

    /** Drop the closing vertex if the ring repeats its first point. */
    static double[][] dedupe(List<int[]> ring) {
        int n = ring.size();
        if (n > 1) {
            int[] a = ring.get(0), z = ring.get(n - 1);
            if (a[0] == z[0] && a[1] == z[1]) n--;
        }
        double[][] out = new double[Math.max(0, n)][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = ring.get(i)[0];
            out[i][1] = ring.get(i)[1];
        }
        return out;
    }

    static double shoelace(double[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            double[] u = p[i], v = p[(i + 1) % p.length];
            s += u[0] * v[1] - v[0] * u[1];
        }
        return s / 2;
    }

    /** Area centroid, not the vertex mean — a dense corner would skew that. */
    static double[] centroid(double[][] p, double area) {
        double cx = 0, cy = 0;
        for (int i = 0; i < p.length; i++) {
            double[] u = p[i], v = p[(i + 1) % p.length];
            double cr = u[0] * v[1] - v[0] * u[1];
            cx += (u[0] + v[0]) * cr;
            cy += (u[1] + v[1]) * cr;
        }
        double denom = 6 * shoelace(p);
        if (Math.abs(denom) < 1e-9) {           // degenerate: fall back to mean
            for (double[] q : p) { cx += q[0]; cy += q[1]; }
            return new double[]{cx / p.length, cy / p.length};
        }
        return new double[]{cx / denom, cy / denom};
    }

    /** Andrew's monotone chain. */
    static double[][] hull(double[][] p) {
        if (p.length < 3) return p;
        double[][] s = p.clone();
        Arrays.sort(s, (u, v) -> u[0] != v[0] ? Double.compare(u[0], v[0])
                                              : Double.compare(u[1], v[1]));
        double[][] h = new double[2 * s.length][];
        int k = 0;
        for (double[] q : s) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], q) <= 0) k--;
            h[k++] = q;
        }
        int lower = k + 1;
        for (int i = s.length - 2; i >= 0; i--) {
            double[] q = s[i];
            while (k >= lower && cross(h[k - 2], h[k - 1], q) <= 0) k--;
            h[k++] = q;
        }
        return Arrays.copyOf(h, Math.max(k - 1, 1));
    }

    static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * Minimum-area enclosing rectangle by rotating calipers. The minimum-area
     * rectangle of a convex polygon always has a side flush with one of its
     * edges, so trying every hull edge is exact rather than a search.
     *
     * @return {angle degrees, area, width, height}
     */
    static double[] minAreaRect(double[][] h) {
        double bestArea = Double.MAX_VALUE;
        double[] best = {0, 0, 0, 0};
        for (int i = 0; i < h.length; i++) {
            double[] a = h[i], b = h[(i + 1) % h.length];
            double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
            double c = Math.cos(-ang), s = Math.sin(-ang);
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] q : h) {
                double x = q[0] * c - q[1] * s;
                double y = q[0] * s + q[1] * c;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            double w = maxX - minX, ht = maxY - minY, ar = w * ht;
            if (ar < bestArea) {
                bestArea = ar;
                best = new double[]{Math.toDegrees(ang), ar, w, ht};
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Self-test:  java -cp out pzformat.FootprintSnap
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        int fail = 0;

        // An axis-aligned 10x6 rectangle must come back unchanged.
        List<int[]> square = ring(new int[][]{{10, 10}, {20, 10}, {20, 16}, {10, 16}});
        Rect r = snap(square);
        fail += expect("axis-aligned 10x6 unchanged", r != null
                && r.w() == 10 && r.h() == 6 && r.x() == 10 && r.y() == 10, r);
        fail += expect("axis-aligned validates", isAxisAligned(square), null);

        // A 45-degree diamond of area 200: aspect 1:1, so a ~14x14 square, and
        // the centroid must not move.
        List<int[]> diamond = ring(new int[][]{{100, 90}, {110, 100}, {100, 110}, {90, 100}});
        r = snap(diamond);
        fail += expect("diamond keeps area and centre", r != null
                && Math.abs(r.area() - 200) <= 30
                && Math.abs(r.cx() - 100) <= 1 && Math.abs(r.cy() - 100) <= 1, r);
        fail += expect("diamond refuses validation", !isAxisAligned(diamond), null);

        // A long thin barn at an angle stays long and thin.
        List<int[]> barn = ring(new int[][]{{0, 0}, {30, 15}, {26, 23}, {-4, 8}});
        r = snap(barn);
        fail += expect("thin barn stays thin", r != null
                && Math.max(r.w(), r.h()) >= 3 * Math.min(r.w(), r.h()), r);

        // Degenerate input must not throw.
        fail += expect("two points -> null", snap(ring(new int[][]{{0, 0}, {1, 1}})) == null, null);

        System.out.println(fail == 0 ? "\nall cases pass" : "\n" + fail + " FAILED");
        if (fail > 0) System.exit(1);
    }

    static List<int[]> ring(int[][] pts) {
        List<int[]> l = new ArrayList<>();
        for (int[] p : pts) l.add(p);
        return l;
    }

    static int expect(String label, boolean ok, Rect r) {
        System.out.printf("%-32s %s%s%n", label, ok ? "PASS" : "FAIL",
                r == null ? "" : "  got " + r + " area " + r.area());
        return ok ? 0 : 1;
    }
}
