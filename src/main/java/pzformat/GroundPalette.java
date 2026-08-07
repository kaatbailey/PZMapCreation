package pzformat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Outdoor ground composed the way vanilla composes it.
 *
 * MEASURED over 262,144 squares across Muldraugh cells 27_27, 35_35, 30_30 and
 * 40_40 (see pzformat.GroundSurvey). Authoring one flat tile everywhere is what
 * made generated ground read as a hard rectangle against procedurally generated
 * neighbours; vanilla ground is a weighted mix with a partial overlay layer.
 *
 * What the survey showed:
 *
 *   - Base tiles fall into groups of four sharing an overlay rate. Within a
 *     group the four variants occur in near-equal proportion (14.5-14.8% of the
 *     total for the largest group), so the variant is picked uniformly at
 *     random per square.
 *   - Overlay rate is a property of the GROUP, not a global constant: 60.6%,
 *     37%, 15%, then two groups that are never overlaid at all.
 *   - Overall 43.3% of ground squares carry an overlay, and NEVER more than
 *     one — 0 squares out of 257,703 had two.
 *   - The overlay sheet is 8 wide with only columns 0-5 usable; columns 6 and 7
 *     are FLOOR-classified with no sprite. Row frequency falls off sharply.
 *
 * The two never-overlaid groups (64/69/70/71 and 80/85/86/87) are 14% of
 * vanilla ground. They were on the project's old hand-written exclusion list,
 * which is why generated ground previously had no access to them.
 */
public final class GroundPalette {

    public static final String BASE_SHEET = "blends_natural_01_";
    public static final String OVERLAY_SHEET = "blends_grassoverlays_01_";

    /**
     * @param indices     the four variants, chosen between uniformly
     * @param weight      share of ground squares, as measured
     * @param overlayRate probability a square of this group carries an overlay
     */
    record BaseGroup(int[] indices, double weight, double overlayRate, String label) { }

    static final BaseGroup[] GROUPS = {
            new BaseGroup(new int[]{16, 21, 22, 23}, 58.6, 0.606, "grass, dense"),
            new BaseGroup(new int[]{32, 37, 38, 39}, 17.4, 0.370, "grass, medium"),
            new BaseGroup(new int[]{48, 53, 54, 55},  8.1, 0.152, "grass, light"),
            new BaseGroup(new int[]{64, 69, 70, 71},  7.7, 0.000, "sparse, dark"),
            new BaseGroup(new int[]{80, 85, 86, 87},  6.2, 0.000, "sparse, light"),
    };

    /** Per-tile weight for each row of the overlay sheet, rows 0..8. */
    static final double[] OVERLAY_ROW_WEIGHT = {
            6.20, 4.05, 3.48, 1.07, 0.74, 0.68, 0.20, 0.16, 0.16
    };
    static final int OVERLAY_ROW_WIDTH = 8;
    static final int OVERLAY_USABLE_COLS = 6;

    private final List<BaseGroup> groups = new ArrayList<>();
    private final double[] groupCumulative;
    private final List<String> overlays = new ArrayList<>();
    private final double[] overlayCumulative;

    /** Every tile name used, so the cell header can declare them. */
    public final List<String> all = new ArrayList<>();

    private GroundPalette(List<BaseGroup> keptGroups, List<String> keptOverlays) {
        groups.addAll(keptGroups);
        overlays.addAll(keptOverlays);

        groupCumulative = new double[groups.size()];
        double run = 0;
        for (int i = 0; i < groups.size(); i++) {
            run += groups.get(i).weight();
            groupCumulative[i] = run;
        }

        overlayCumulative = new double[overlays.size()];
        run = 0;
        for (int i = 0; i < overlays.size(); i++) {
            run += rowWeightOf(overlays.get(i));
            overlayCumulative[i] = run;
        }

        for (BaseGroup g : groups) {
            for (int idx : g.indices()) {
                all.add(BASE_SHEET + idx);
            }
        }
        all.addAll(overlays);
    }

    /**
     * Keeps only tiles that both exist in the tiledefs and have a sprite. A
     * group missing any variant is dropped whole rather than silently skewed.
     */
    public static GroundPalette pick(TileIndex ti, Set<String> sprites) {
        List<BaseGroup> keptGroups = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        for (BaseGroup g : GROUPS) {
            boolean ok = true;
            for (int idx : g.indices()) {
                String n = BASE_SHEET + idx;
                if (ti.get(n) == null || !sprites.contains(n)) {
                    ok = false;
                    dropped.add(n);
                }
            }
            if (ok) {
                keptGroups.add(g);
            }
        }

        List<String> keptOverlays = new ArrayList<>();
        for (int row = 0; row < OVERLAY_ROW_WEIGHT.length; row++) {
            for (int col = 0; col < OVERLAY_USABLE_COLS; col++) {
                String n = OVERLAY_SHEET + (row * OVERLAY_ROW_WIDTH + col);
                if (ti.get(n) != null && sprites.contains(n)) {
                    keptOverlays.add(n);
                }
            }
        }

        if (keptGroups.isEmpty()) {
            throw new IllegalStateException(
                    "GroundPalette: no usable base ground group. Missing: " + dropped);
        }
        if (!dropped.isEmpty()) {
            System.out.println("ground palette: dropped " + dropped.size()
                    + " unusable base tiles");
        }
        return new GroundPalette(keptGroups, keptOverlays);
    }

    static double rowWeightOf(String overlayName) {
        int idx = Integer.parseInt(overlayName.substring(OVERLAY_SHEET.length()));
        int row = idx / OVERLAY_ROW_WIDTH;
        return row < OVERLAY_ROW_WEIGHT.length ? OVERLAY_ROW_WEIGHT[row] : 0.1;
    }

    /** Base tile plus an optional overlay, or null when the group is never overlaid. */
    public record Ground(String base, String overlay) { }

    public Ground roll(Random rng) {
        double r = rng.nextDouble() * groupCumulative[groupCumulative.length - 1];
        int gi = 0;
        while (gi < groupCumulative.length - 1 && r > groupCumulative[gi]) {
            gi++;
        }
        BaseGroup g = groups.get(gi);
        int[] variants = g.indices();
        String base = BASE_SHEET + variants[rng.nextInt(variants.length)];

        String overlay = null;
        if (!overlays.isEmpty() && rng.nextDouble() < g.overlayRate()) {
            double o = rng.nextDouble() * overlayCumulative[overlayCumulative.length - 1];
            int oi = 0;
            while (oi < overlayCumulative.length - 1 && o > overlayCumulative[oi]) {
                oi++;
            }
            overlay = overlays.get(oi);
        }
        return new Ground(base, overlay);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(groups.size()).append(" base groups, ")
                .append(overlays.size()).append(" overlays");
        for (BaseGroup g : groups) {
            sb.append("\n      ").append(String.format("%-14s", g.label()))
                    .append(String.format("%5.1f%% of ground, %5.1f%% overlaid",
                            g.weight(), g.overlayRate() * 100));
        }
        return sb.toString();
    }
}
