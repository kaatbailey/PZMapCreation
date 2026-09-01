package pzformat;

import java.nio.file.*;
import java.util.*;

/**
 * Cross-language oracle for BuildingPlan (Pattern B — canonical text digest).
 *
 * WHY THIS EXISTS ALONGSIDE THE SELF-TEST. BuildingPlan's own main is the
 * primary oracle, but it only prints room COUNTS, gaps and overlaps. Six
 * deliberate mutations of the C++ port — reversing the WEIGHT table, walking
 * findOptionalRoomFromEnd forwards, widening the hall clamp, swapping
 * nextBoolean for nextInt(2), moving the reorder insertion index, and
 * substituting std::round for javaRound — ALL left the self-test output
 * byte-identical, because a wrong layout still tiles the footprint exactly.
 * A test that cannot fail proves nothing (Charter §4). This digest emits
 * every room of every layout, so a divergence localises to one room of one
 * case.
 *
 * CORPUS RULE (STATE §39). The corpus is generated with arithmetic and
 * java.util.Random ONLY. No transcendentals — step 3's first measurement was
 * contaminated because its generator used Math.cos, so the INPUTS differed
 * between trees before the unit under test ran.
 *
 * Field order is fixed and nothing is sorted. Layout order is the contract:
 * per-layout seeding indexes into the sequence.
 */
public final class BuildingPlanOracle {

    /** Escape control characters so a value can never split a digest line. */
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') b.append("\\\\");
            else if (c == '\t') b.append("\\t");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    static String bits(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    static String join(List<String> v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) b.append(',');
            b.append(esc(v.get(i)));
        }
        return b.toString();
    }

    static final String[] OCC = {
        "Residential", "Commercial", "Agriculture", "Industrial",
        "Assembly", "Education", "Unclassified"
    };

    /** Emit every room of one layout, plus a summary line. */
    static void emit(List<String> out, String tag, int idx, String facing,
                     int x, int y, int w, int h, long seed,
                     List<String> types, List<BuildingPlan.Room> rooms) {

        out.add(tag + "L\t" + idx + "\t" + facing + "\t" + x + "\t" + y + "\t"
                + w + "\t" + h + "\t" + seed + "\t" + rooms.size()
                + "\t" + join(types));

        for (int i = 0; i < rooms.size(); i++) {
            BuildingPlan.Room r = rooms.get(i);
            out.add(tag + "R\t" + idx + "\t" + i + "\t" + esc(r.type())
                    + "\t" + r.x() + "\t" + r.y() + "\t" + r.w() + "\t" + r.h()
                    + "\t" + (r.entrance() ? 1 : 0)
                    + "\t" + r.area()
                    + "\t" + (r.canTakeDoor() ? 1 : 0));
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> out = new ArrayList<>();

        // -------------------------------------------------------------
        // Part 1 — the small pure helpers, near the top of the file.
        // PROMPT: get openBetween, hallChance, recipe and pick matching
        // before plan and hubLayout.
        // -------------------------------------------------------------

        for (int n = 0; n <= 20; n++) {
            out.add("HC\t" + n + "\t" + bits(BuildingPlan.hallChance(n)));
        }

        // ORDER MATTERS. openBetween short-circuits: it draws only when the
        // pair is the livingroom/kitchen core. If every non-core pair came
        // last, a port that drew unconditionally would shift the stream
        // without changing any printed value, and the digest would miss it.
        // Core pairs are interleaved AFTER non-core ones so the shift shows.
        String[] pairs = {
            "livingroom", "livingroom", "livingroom", "kitchen",
            "bedroom", "kitchen", "kitchen", "livingroom",
            "kitchen", "bathroom", "livingroom", "kitchen",
            "hall", "livingroom", "kitchen", "livingroom"
        };
        Random ob = new Random(777);
        for (int i = 0; i < pairs.length; i += 2) {
            for (int k = 0; k < 40; k++) {
                out.add("OB\t" + i + "\t" + k + "\t" + esc(pairs[i]) + "\t"
                        + esc(pairs[i + 1]) + "\t"
                        + (BuildingPlan.openBetween(pairs[i], pairs[i + 1], ob) ? 1 : 0));
            }
        }

        Random pk = new Random(31337);
        for (int k = 0; k < 500; k++) {
            out.add("PK\t" + k + "\t" + esc(BuildingPlan.pick(pk, 0.52, "a", "b")));
        }

        // Geometry / arithmetic helpers, exhaustively over a small grid.
        for (int w = 0; w <= 24; w++) {
            for (int h = 0; h <= 24; h++) {
                out.add("AS\t" + w + "\t" + h + "\t" + bits(BuildingPlan.aspect(w, h))
                        + "\t" + BuildingPlan.minRooms(w, h)
                        + "\t" + BuildingPlan.ceilDiv(w, h));
            }
        }
        for (int v = -20; v <= 20; v++) {
            for (int lo = -5; lo <= 5; lo++) {
                for (int hi = -5; hi <= 5; hi++) {
                    out.add("CL\t" + v + "\t" + lo + "\t" + hi + "\t"
                            + BuildingPlan.clamp(v, lo, hi));
                }
            }
        }

        // -------------------------------------------------------------
        // Part 2 — recipe, over a wide area sweep and every occupancy.
        // -------------------------------------------------------------

        int rc = 0;
        for (int oi = 0; oi < OCC.length; oi++) {
            for (int ob2 = 0; ob2 < 2; ob2++) {
                for (int area = 1; area <= 900; area += 7) {
                    Random r = new Random(area * 131L + oi * 17L + ob2);
                    List<String> types =
                            BuildingPlan.recipe(area, OCC[oi], ob2 == 1, r);
                    out.add("RC\t" + rc + "\t" + area + "\t" + esc(OCC[oi])
                            + "\t" + ob2 + "\t" + types.size() + "\t" + join(types));
                    rc++;
                }
            }
        }

        // -------------------------------------------------------------
        // Part 3 — allocateWeightedSizes, the shared allocator under every
        // packing path. Exercised directly so a divergence here is not
        // mistaken for a layout bug.
        // -------------------------------------------------------------

        String[][] lists = {
            {"bedroom"},
            {"bedroom", "bathroom"},
            {"bathroom", "bedroom", "closet"},
            {"livingroom", "kitchen", "bedroom", "bathroom"},
            {"closet", "closet", "closet", "closet", "closet"},
            {"hall", "livingroom", "kitchen", "bedroom", "bedroom", "bathroom"},
            {"unknownroomtype", "bedroom"},
        };
        int ai = 0;
        for (String[] list : lists) {
            for (int length = 0; length <= 60; length++) {
                for (int min = 1; min <= 4; min++) {
                    int[] sizes = BuildingPlan.allocateWeightedSizes(
                            Arrays.asList(list), length, min);
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < sizes.length; i++) {
                        if (i > 0) b.append(',');
                        b.append(sizes[i]);
                    }
                    out.add("AW\t" + ai + "\t" + length + "\t" + min + "\t"
                            + sizes.length + "\t" + b);
                    ai++;
                }
            }
        }

        // -------------------------------------------------------------
        // Part 4 — the trim family, which decides WHICH room is dropped.
        // -------------------------------------------------------------

        int ti = 0;
        for (String[] list : lists) {
            for (int cap = 0; cap <= 8; cap++) {
                out.add("TR\t" + ti + "\t" + cap + "\t"
                        + join(BuildingPlan.trimRowRooms(
                                new ArrayList<>(Arrays.asList(list)), cap)));
                ti++;
            }
            for (int w = 1; w <= 30; w += 3) {
                for (int h = 1; h <= 30; h += 3) {
                    out.add("TD\t" + ti + "\t" + w + "\t" + h + "\t"
                            + join(BuildingPlan.trimDwellingRooms(
                                    w, h, Arrays.asList(list))));
                    List<String> side = new ArrayList<>(Arrays.asList(list));
                    BuildingPlan.trimSideRoomCount(side, h);
                    out.add("TS\t" + ti + "\t" + w + "\t" + h + "\t" + join(side));
                    out.add("TC\t" + ti + "\t" + w + "\t" + h + "\t"
                            + join(BuildingPlan.trimToCapacity(
                                    Arrays.asList(list), w, h)));
                    ti++;
                }
            }
            out.add("FO\t" + ti + "\t"
                    + BuildingPlan.findOptionalRoomFromEnd(Arrays.asList(list))
                    + "\t" + BuildingPlan.findBedroomIndex(Arrays.asList(list))
                    + "\t" + BuildingPlan.countBedrooms(Arrays.asList(list)));
            ti++;
        }

        // -------------------------------------------------------------
        // Part 5 — FIXED layout cases: the self-test's own 13 footprints
        // across all four facings, but emitting every room rather than a
        // count. This is the part the self-test cannot see.
        // -------------------------------------------------------------

        int[][] cases = {
            {12, 10}, {16, 10}, {24, 7}, {15, 14}, {22, 14}, {20, 17}, {5, 4},
            {30, 6}, {4, 3}, {18, 12}, {25, 16}, {30, 20}, {40, 20}
        };
        BuildingPlan.Facing[] facings = BuildingPlan.Facing.values();

        int fi = 0;
        for (BuildingPlan.Facing facing : facings) {
            for (int[] c : cases) {
                long seed = c[0] * 71L + c[1] * 31L;
                Random r = new Random(seed);
                List<String> types = BuildingPlan.recipe(c[0] * c[1], "Residential", false, r);
                List<BuildingPlan.Room> rooms =
                        BuildingPlan.plan(0, 0, c[0], c[1], types, facing, r);
                emit(out, "F", fi, facing.name(), 0, 0, c[0], c[1], seed, types, rooms);
                fi++;
            }
        }

        // -------------------------------------------------------------
        // Part 6 — RANDOMISED corpus, 12,000 layouts.
        //
        // Corpus parameters come from a java.util.Random of their own, using
        // nothing but nextInt/nextDouble/nextLong, so both trees build
        // identical inputs (STATE §39). The unit under test gets a FRESH
        // generator per layout, seeded from that stream, so one divergent
        // layout cannot cascade into every layout after it.
        // -------------------------------------------------------------

        Random gen = new Random(20260901L);
        for (int k = 0; k < 12000; k++) {
            int w = 1 + gen.nextInt(60);
            int h = 1 + gen.nextInt(60);
            int x = gen.nextInt(400) - 200;
            int y = gen.nextInt(400) - 200;
            BuildingPlan.Facing facing = facings[gen.nextInt(facings.length)];
            String occ = OCC[gen.nextInt(OCC.length)];
            boolean outb = gen.nextDouble() < 0.15;
            long seed = gen.nextLong();

            Random r = new Random(seed);
            List<String> types = BuildingPlan.recipe(w * h, occ, outb, r);

            // Occasionally force a hall into the recipe so hubHallLayout is
            // reached on small footprints too, not only on 420+ tile ones.
            if (gen.nextDouble() < 0.20 && types.size() > 1) {
                types = new ArrayList<>(types);
                types.add("hall");
            }

            List<BuildingPlan.Room> rooms =
                    BuildingPlan.plan(x, y, w, h, types, facing, r);
            emit(out, "K", k, facing.name(), x, y, w, h, seed, types, rooms);
        }

        // -------------------------------------------------------------
        // Part 7 — the two-argument plan overload (SOUTH default), so the
        // backwards-compatible entry point is covered too.
        // -------------------------------------------------------------

        Random gen2 = new Random(20260902L);
        for (int k = 0; k < 2000; k++) {
            int w = 1 + gen2.nextInt(40);
            int h = 1 + gen2.nextInt(40);
            long seed = gen2.nextLong();
            Random r = new Random(seed);
            List<String> types = BuildingPlan.recipe(w * h, "Residential", false, r);
            List<BuildingPlan.Room> rooms = BuildingPlan.plan(0, 0, w, h, types, r);
            emit(out, "D", k, "DEFAULT", 0, 0, w, h, seed, types, rooms);
        }

        // -------------------------------------------------------------
        // Part 8 — ARBITRARY type lists.
        //
        // recipe() always emits bathroom before every other secondary room,
        // so reorderPrivateRooms' "move the bathroom forward" branch is
        // unreachable from recipe-generated corpora and a mutation of its
        // insertion index survives. These lists are sampled from the full
        // palette, so orderings recipe never produces are covered, along with
        // unknown room types (WEIGHT_DEFAULT) and hall placement anywhere.
        // -------------------------------------------------------------

        String[] palette = {
            "livingroom", "kitchen", "bathroom", "bedroom", "kidsbedroom",
            "closet", "laundry", "garage", "diningroom", "office", "janitor",
            "hall", "lobby", "barn", "shed", "garagestorage", "empty",
            "unknownroomtype"
        };

        Random gen3 = new Random(20260903L);
        for (int k = 0; k < 6000; k++) {
            int n = 1 + gen3.nextInt(9);
            List<String> types = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                types.add(palette[gen3.nextInt(palette.length)]);
            }
            int w = 1 + gen3.nextInt(45);
            int h = 1 + gen3.nextInt(45);
            int x = gen3.nextInt(100) - 50;
            int y = gen3.nextInt(100) - 50;
            BuildingPlan.Facing facing = facings[gen3.nextInt(facings.length)];
            long seed = gen3.nextLong();

            Random r = new Random(seed);
            List<BuildingPlan.Room> rooms =
                    BuildingPlan.plan(x, y, w, h, types, facing, r);
            emit(out, "P", k, facing.name(), x, y, w, h, seed, types, rooms);
        }

        // reorderPrivateRooms directly — it MUTATES its argument, and the
        // bathroom-forward move plus the 0.35 reverse are both invisible in a
        // room count.
        Random rp = new Random(20260904L);
        Random rpGen = new Random(20260905L);
        for (int k = 0; k < 4000; k++) {
            int n = 1 + rpGen.nextInt(7);
            List<String> types = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                types.add(palette[rpGen.nextInt(palette.length)]);
            }
            BuildingPlan.reorderPrivateRooms(types, rp);
            out.add("RP\t" + k + "\t" + types.size() + "\t" + join(types));
        }

        Files.write(Path.of(args[0]), out);
        System.out.println("java buildingplan: " + out.size() + " lines -> " + args[0]);
    }
}
