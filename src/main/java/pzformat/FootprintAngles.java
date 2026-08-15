package pzformat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Does this area have a dominant building grid? Read-only; writes nothing.
 *
 * WHY THIS COMES FIRST. STATE §17: a room is int32 x,y,w,h. No rotation field,
 * no polygon. An axis-aligned rectangle maps exactly onto the tile grid, so an
 * aligned building has no steps at all — jaggedness is not a separate defect,
 * it is the SYMPTOM of an off-axis edge. The generated road shows both halves
 * of that: its straight runs are clean and only its diagonal stair-steps.
 *
 * So orientation is the whole problem, and §17 prescribes rotating the ENTIRE
 * scene by the dominant footprint angle before rasterizing, then snapping each
 * building's residual to the nearest 90 degrees. Per-building alignment is
 * explicitly wrong: it squares each building against itself and randomises
 * them against each other and the roads, which looks deliberate and is worse
 * than the zigzag.
 *
 * THE ASSUMPTION THAT HAS TO HOLD. Whole-scene rotation only works if there IS
 * a dominant angle. §17's prediction, written before the run: a US grid town
 * shows one mode holding well over half the footprint area within +/-3 deg.
 * Flat or bimodal means the area has no single grid, whole-scene rotation is
 * the wrong move for it, and per-cluster rotation is needed instead.
 *
 * METHOD. Per footprint: project lon/lat to metres locally (longitude scaled
 * by cos(lat), or every angle is skewed), take the convex hull, and find the
 * minimum-area enclosing rectangle by rotating calipers over hull edges. Its
 * angle mod 90 is the building's orientation — a rectangle's orientation is
 * 90-degree periodic. Histogram those weighted by footprint area, because one
 * large warehouse says more about the town grid than three sheds.
 *
 * Usage:
 *   java -cp out pzformat.FootprintAngles BUILDINGS_GEOJSON
 */
public final class FootprintAngles {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: FootprintAngles BUILDINGS_GEOJSON");
            return;
        }
        GeoJson g = GeoJson.read(Path.of(args[0]));
        System.out.println("features: " + g.features.size());

        double lat0 = 0, lon0 = 0;
        int n = 0;
        for (GeoJson.Feature f : g.features)
            for (List<double[]> ring : f.rings)
                for (double[] p : ring) { lon0 += p[0]; lat0 += p[1]; n++; }
        if (n == 0) { System.out.println("no coordinates"); return; }
        lon0 /= n; lat0 /= n;
        double mPerLon = 111320.0 * Math.cos(Math.toRadians(lat0));
        double mPerLat = 110540.0;
        System.out.printf("centre %.5f, %.5f   scale %.1f m/deg lon, %.1f m/deg lat%n%n",
                lat0, lon0, mPerLon, mPerLat);

        List<double[]> found = new ArrayList<>();   // angle mod 90, area m^2
        double totalArea = 0;

        System.out.println("  #   angle    area m2   w x h m      name");
        for (int i = 0; i < g.features.size(); i++) {
            GeoJson.Feature f = g.features.get(i);
            List<double[]> best = null;
            double bestArea = -1;
            for (List<double[]> ring : f.rings) {
                double a = Math.abs(shoelace(ring, lon0, lat0, mPerLon, mPerLat));
                if (a > bestArea) { bestArea = a; best = ring; }
            }
            if (best == null || best.size() < 4) continue;

            double[][] pts = project(best, lon0, lat0, mPerLon, mPerLat);
            double[][] hull = hull(pts);
            if (hull.length < 3) continue;

            double[] r = minAreaRect(hull);     // angle, area, w, h
            double angle = ((r[0] % 90) + 90) % 90;
            found.add(new double[]{angle, bestArea});
            totalArea += bestArea;

            String name = f.prop("NAME");
            System.out.printf("  %-3d %6.2f  %9.0f   %5.1f x %5.1f   %s%n",
                    i, angle, bestArea, r[2], r[3], name == null ? "" : name);
        }

        if (found.isEmpty()) { System.out.println("\nno usable footprints"); return; }

        // Area-weighted histogram, 1 degree bins over 0..89.
        double[] bins = new double[90];
        for (double[] e : found) bins[(int) e[0] % 90] += e[1];

        System.out.println("\narea-weighted histogram, 1 deg bins (only nonzero):");
        for (int b = 0; b < 90; b++)
            if (bins[b] > 0)
                System.out.printf("  %2d-%2d deg  %9.0f m2  (%5.1f%%)  %s%n", b, b + 1,
                        bins[b], 100 * bins[b] / totalArea,
                        "#".repeat((int) Math.min(50, Math.round(50 * bins[b] / totalArea))));

        // Dominant mode: the 1-degree bin whose +/-3 deg window holds the most
        // area. Wraps at 90 because orientation is 90-degree periodic.
        int bestC = 0;
        double bestW = -1;
        for (int c = 0; c < 90; c++) {
            double s = 0;
            for (int d = -3; d <= 3; d++) s += bins[((c + d) % 90 + 90) % 90];
            if (s > bestW) { bestW = s; bestC = c; }
        }
        double share = 100 * bestW / totalArea;

        System.out.printf("%ndominant mode: %d deg, +/-3 deg window holds %.1f%% of footprint area%n",
                bestC, share);
        System.out.printf("total footprint area: %.0f m2 over %d buildings%n",
                totalArea, found.size());

        System.out.println();
        if (found.size() < 30)
            System.out.println("CAUTION: " + found.size() + " buildings is a thin sample for a"
                    + " mode. Three agreeing by chance looks like a grid.");
        if (share > 50)
            System.out.printf("=> one grid. Rotate the whole scene by -%d deg before"
                    + " rasterizing (§17).%n", bestC);
        else
            System.out.printf("=> NO single grid at %.1f%%. §17's prediction fails here:"
                    + " whole-scene%n   rotation is the wrong move and this area needs"
                    + " per-cluster rotation.%n", share);
    }

    /** lon/lat ring to local metres. Longitude must be scaled by cos(lat). */
    static double[][] project(List<double[]> ring, double lon0, double lat0,
                              double mPerLon, double mPerLat) {
        int n = ring.size();
        // Drop the closing vertex if the ring repeats its first point.
        double[] a = ring.get(0), z = ring.get(n - 1);
        if (n > 1 && a[0] == z[0] && a[1] == z[1]) n--;
        double[][] out = new double[n][2];
        for (int i = 0; i < n; i++) {
            double[] p = ring.get(i);
            out[i][0] = (p[0] - lon0) * mPerLon;
            out[i][1] = (p[1] - lat0) * mPerLat;
        }
        return out;
    }

    static double shoelace(List<double[]> ring, double lon0, double lat0,
                           double mPerLon, double mPerLat) {
        double[][] p = project(ring, lon0, lat0, mPerLon, mPerLat);
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            double[] u = p[i], v = p[(i + 1) % p.length];
            s += u[0] * v[1] - v[0] * u[1];
        }
        return s / 2;
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
     * Minimum-area enclosing rectangle by rotating calipers.
     *
     * The minimum-area rectangle of a convex polygon always has a side flush
     * with one of its edges, so trying every hull edge is exact rather than a
     * search.
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
            for (double[] p : h) {
                double x = p[0] * c - p[1] * s;
                double y = p[0] * s + p[1] * c;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            double w = maxX - minX, ht = maxY - minY, area = w * ht;
            if (area < bestArea) {
                bestArea = area;
                best = new double[]{Math.toDegrees(ang), area, w, ht};
            }
        }
        return best;
    }
}
