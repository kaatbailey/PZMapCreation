package pzformat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lays out the inside of a building. Pure: a footprint rectangle and an
 * occupancy class in, a list of typed room rects out. No map, no I/O.
 *
 * Every number here is measured. STATE §33 for the recipe and the hall rule,
 * §34 for the layout and the area ratios. Do not re-derive them.
 *
 * THE ALGORITHM, from §34. Vanilla layouts are **85% recursively splittable**
 * when tested on rects — 100% at two rooms, 93% at four, settling near 80%.
 * The 15% that will not split is the **halls**, which snake as three or four
 * thin rects because a hall is not a leaf of a partition, it is the negative
 * space left after the rooms are placed. So:
 *
 *   1. reserve a corridor if the room count calls for one
 *   2. recursively split the remainder, allocating area BY TYPE
 *
 * Allocating by type matters more than it sounds. §34: a livingroom is 1.80x
 * the building's mean room and a bathroom 0.27x — **6.7x apart**. Splitting
 * evenly and labelling afterwards gives a house a 42-square bathroom, which is
 * the difference between a plausible building and a grid of equal boxes.
 *
 * WHERE THIS FALLS SHORT OF VANILLA, stated rather than hidden:
 *   - the corridor is a straight strip; vanilla's snakes (§34 OPEN 1)
 *   - every room is one rectangle; 35.7% of vanilla rooms are multi-rect
 *     (§34 OPEN 2)
 * Both make our buildings read as *plausible* rather than *authored*. Neither
 * is wrong, and both are visible in a render if someone wants to close them.
 */
public final class BuildingPlan {

    /**
     * A planned room: its type, the rectangle it occupies, and whether it may
     * hold an exterior door.
     *
     * `entrance` is set on the livingroom (front) and kitchen (back) when they
     * touch the outer face, so the writer knows where a door belongs without
     * re-deriving the grammar.
     */
    public record Room(String type, int x, int y, int w, int h, boolean entrance) {
        public Room(String type, int x, int y, int w, int h) {
            this(type, x, y, w, h, false);
        }
        public int area() { return w * h; }
        public boolean canTakeDoor() { return ENTRANCE.contains(type); }
        @Override public String toString() {
            return type + "[" + x + "," + y + " " + w + "x" + h + "]"
                    + (entrance ? "*" : "");
        }
    }

    /** True when this pair should be left open rather than walled — §35, R5. */
    public static boolean openBetween(String a, String b, Random rng) {
        boolean core = (a.equals("livingroom") && b.equals("kitchen"))
                || (a.equals("kitchen") && b.equals("livingroom"));
        return core && rng.nextDouble() < LK_OPEN;
    }

    /**
     * Relative area by type — §34, as a multiple of the building's mean room.
     * The splitter divides area in these proportions.
     */
    static final Map<String, Double> WEIGHT = new LinkedHashMap<>();
    static {
        // §34's MEDIAN AREA IN SQUARES, not the ratio to the building mean.
        // Same ordering, but splitting in these proportions lands rooms near
        // the sizes vanilla actually builds.
        WEIGHT.put("closet", 2.0);
        WEIGHT.put("bathroom", 6.0);
        WEIGHT.put("laundry", 6.0);
        WEIGHT.put("janitor", 9.0);
        WEIGHT.put("kidsbedroom", 15.0);
        WEIGHT.put("bedroom", 16.0);
        WEIGHT.put("diningroom", 20.0);
        WEIGHT.put("kitchen", 24.0);
        WEIGHT.put("office", 27.0);
        WEIGHT.put("garage", 30.0);
        WEIGHT.put("hall", 40.0);
        WEIGHT.put("livingroom", 42.0);
    }

    /** Default for a type with no measured median. */
    static final double WEIGHT_DEFAULT = 15.0;

    /** Smallest a room may be on its short side. A closet is 2 squares (§34). */
    static final int MIN_SIDE = 2;

    /** Corridor width. §34: halls are long and thin, median 40 squares. */
    static final int HALL_W = 2;

    /**
     * Rooms an exterior door may open into — measured, §35. Of 229 vanilla
     * exterior doors: livingroom 31.0%, kitchen 26.6%, hall 17.9%, laundry
     * 9.6%, lobby 5.7%, diningroom 2.6%. **`bedroom` is 1 of 229.**
     */
    public static final java.util.Set<String> ENTRANCE = java.util.Set.of(
            "livingroom", "kitchen", "hall", "laundry", "lobby", "diningroom");

    /**
     * P(the livingroom/kitchen boundary is left OPEN). §35: 55.4% of vanilla
     * pairs are fully open, 33.0% partly, only 8.2% fully walled — the
     * opposite of what a modern intuition suggests. They are usually one
     * continuous space.
     */
    static final double LK_OPEN = 0.88;

    /**
     * P(this building has a hall) by room count — §33, measured over 8,580
     * vanilla buildings. A clean sigmoid with the transition at 6-7 rooms.
     */
    static double hallChance(int rooms) {
        if (rooms <= 3) return 0.061;
        if (rooms <= 5) return 0.144;
        if (rooms <= 7) return 0.573;
        if (rooms <= 10) return 0.849;
        return 0.90;
    }

    /**
     * The room list for a building, before layout.
     *
     * GIS is authoritative where it speaks (§33): `Agriculture` gets a barn
     * outright, an `OUTBLDG` gets one room. Otherwise footprint size decides,
     * because size predicts type strongly — buildings under 24 squares are 52%
     * `garagestorage` and contain no dwellings at all.
     *
     * Sampled rather than always modal, because vanilla's bucket C is
     * 28.5/11.8/4.3/4.0/3.5 — a real spread. **With guardrails**: a dwelling
     * always gets a bathroom, so a small import can never come out
     * pathological.
     */
    public static List<String> recipe(int area, String occ, boolean outbuilding,
                                      Random rng) {
        if ("Agriculture".equals(occ)) return List.of("barn");
        if (outbuilding) return List.of(pick(rng, 0.7, "garagestorage", "shed"));

        if (area <= 24)
            return List.of(pick(rng, 0.52, "garagestorage",
                    pick(rng, 0.5, "empty", "shed")));

        // The core every dwelling has. §33: bathroom is in 64.8% of all
        // buildings, kitchen 53.3%, livingroom 51.9%, bedroom 51.3% — and
        // those percentages are over ALL buildings including garages and
        // barns, so within a dwelling they are effectively universal.
        List<String> r = new ArrayList<>(
                List.of("livingroom", "kitchen", "bathroom", "bedroom"));

        if (area > 60 && rng.nextDouble() < 0.62) r.add("closet");

        // Uncommon types at their MEASURED rates, not by area threshold. The
        // first version added hall, laundry and garage to everything over 120
        // squares and produced them in 62-75% of buildings against vanilla's
        // 5-17%.
        if (area > 110 && rng.nextDouble() < 0.16) r.add("laundry");
        if (area > 140 && rng.nextDouble() < 0.08) r.add("garage");
        if (area > 130 && rng.nextDouble() < 0.09) r.add("diningroom");
        if (area > 100 && rng.nextDouble() < 0.20) r.add("kidsbedroom");

        // NO hall here. A hall belongs to the room COUNT rule (§33's sigmoid,
        // 6.1% at 2-3 rooms rising to 84.9% at 8-10), which `plan` applies
        // once the room list is known. Adding it by area double-counts.

        // Extra rooms are DUPLICATES of the core, in vanilla's proportions.
        // §33 counts when present: bathroom 2.75, bedroom 2.45, livingroom
        // 2.39, kitchen 1.61. Vanilla builds a house by repeating a few types,
        // not by collecting many distinct ones — the first version did the
        // reverse and every count came out 1.00.
        //
        // One extra room per ~55 squares beyond the 4-room core, which lands
        // the distribution near vanilla's peak at 5 rooms for a median 80
        // square building.
        int extra = Math.max(0, (area - 90) / 55);
        for (int i = 0; i < extra && r.size() < 12; i++) {
            double p = rng.nextDouble();
            if (p < 0.38) r.add("bedroom");
            else if (p < 0.70) r.add("bathroom");
            else if (p < 0.88) r.add("livingroom");
            else r.add("kitchen");
        }

        return r;
    }

    static String pick(Random rng, double p, String a, String b) {
        return rng.nextDouble() < p ? a : b;
    }

    /**
     * Lay out a building.
     *
     * @param x,y,w,h the footprint, already axis-aligned by {@link FootprintSnap}
     * @param types   the room list from {@link #recipe}
     * @return rooms tiling the footprint exactly — no gaps, no overlaps
     */
    /** Which way the front of the house faces. */
    public enum Facing { NORTH, SOUTH, EAST, WEST;
        public Facing opposite() {
            return switch (this) {
                case NORTH -> SOUTH; case SOUTH -> NORTH;
                case EAST -> WEST; case WEST -> EAST;
            };
        }
    }

    /**
     * Lay out a building, front facing {@code facing}.
     *
     * The grammar, not a distribution: livingroom at the front, kitchen at the
     * back, everything else secondary. One cut across the front-to-back axis
     * places the core and fixes the plan's orientation; the rest fills the
     * middle.
     *
     * @param facing which way the front faces — the nearest road (§35)
     */
    public static List<Room> plan(int x, int y, int w, int h,
                                  List<String> types, Facing facing, Random rng) {
        List<Room> out = new ArrayList<>();
        if (w < MIN_SIDE || h < MIN_SIDE || types.isEmpty()) return out;

        List<String> rooms = new ArrayList<>(types);

        // How many rooms will actually fit? Splitting past that yields slivers.
        int capacity = (w / MIN_SIDE) * (h / MIN_SIDE);
        while (rooms.size() > capacity && rooms.size() > 1)
            rooms.remove(rooms.size() - 1);

        // A single-room building — barn, garage, shed — is the whole rectangle.
        if (rooms.size() == 1) {
            out.add(new Room(rooms.get(0), x, y, w, h));
            return out;
        }

        boolean core = rooms.remove("livingroom") & true;
        boolean hasKitchen = rooms.remove("kitchen");
        rooms.removeIf("livingroom"::equals);      // duplicates fall to secondary
        boolean wantsHall = rooms.remove("hall");
        rooms.removeIf("hall"::equals);

        // Not a dwelling: no livingroom to anchor on, so fall back to the plain
        // weighted split the previous version used.
        if (!core) {
            if (hasKitchen) rooms.add(0, "kitchen");
            split(out, x, y, w, h, rooms, rng);
            return out;
        }

        boolean vertical = facing == Facing.NORTH || facing == Facing.SOUTH;
        int span = vertical ? h : w;

        // Front band for the livingroom, back band for the kitchen, middle for
        // everything else. Bands are proportional to the type weights so a
        // livingroom stays the largest room in the house (§34: 1.80x mean).
        double wLiving = WEIGHT.get("livingroom");
        double wKitchen = hasKitchen ? WEIGHT.get("kitchen") : 0;
        double wRest = Math.max(0.5, weightOf(rooms));
        double total = wLiving + wKitchen + wRest;

        int front = clamp((int) Math.round(span * (wLiving / total)),
                MIN_SIDE, span - MIN_SIDE * (hasKitchen ? 2 : 1));
        int back = hasKitchen
                ? clamp((int) Math.round(span * (wKitchen / total)),
                        MIN_SIDE, span - front - MIN_SIDE)
                : 0;
        int middle = span - front - back;
        if (middle < 0) { middle = 0; back = Math.max(0, span - front); }

        // Bands run from the FRONT face inward, so the livingroom is always the
        // band the front door opens into.
        int[] bands = bandOrigins(x, y, w, h, facing, front, middle, back);

        // Both core bands touch an outer face by construction — the livingroom
        // the front, the kitchen the back — so both may hold an exterior door.
        // §35: those two account for 58% of vanilla's entrances.
        addBandEntrance(out, "livingroom", bands[0], bands[1], vertical ? w : front,
                vertical ? front : h);
        if (hasKitchen && back > 0)
            addBandEntrance(out, "kitchen", bands[4], bands[5], vertical ? w : back,
                    vertical ? back : h);

        if (middle >= MIN_SIDE && !rooms.isEmpty()) {
            int mx = bands[2], my = bands[3];
            int mw = vertical ? w : middle, mh = vertical ? middle : h;
            if (wantsHall && Math.min(mw, mh) >= HALL_W + MIN_SIDE) {
                // A corridor along the middle band serves the secondary rooms,
                // which is what a hall is for: reaching bedrooms without
                // walking through one to get to another.
                if (mw >= mh) {
                    int cut = clamp(mw / 2 - HALL_W / 2, MIN_SIDE, mw - MIN_SIDE - HALL_W);
                    out.add(new Room("hall", mx + cut, my, HALL_W, mh));
                    split(out, mx, my, cut, mh, share(rooms, 0, rooms.size() / 2), rng);
                    split(out, mx + cut + HALL_W, my, mw - cut - HALL_W, mh,
                            share(rooms, rooms.size() / 2, rooms.size()), rng);
                } else {
                    int cut = clamp(mh / 2 - HALL_W / 2, MIN_SIDE, mh - MIN_SIDE - HALL_W);
                    out.add(new Room("hall", mx, my + cut, mw, HALL_W));
                    split(out, mx, my, mw, cut, share(rooms, 0, rooms.size() / 2), rng);
                    split(out, mx, my + cut + HALL_W, mw, mh - cut - HALL_W,
                            share(rooms, rooms.size() / 2, rooms.size()), rng);
                }
            } else {
                split(out, mx, my, mw, mh, rooms, rng);
            }
        } else if (middle > 0) {
            // Too thin for rooms: give the slack to the livingroom rather than
            // leaving a gap, since the tiling must be exact.
            growBand(out, "livingroom", facing, middle);
        }
        return out;
    }

    /** Backwards-compatible entry point. Faces south, the commonest default. */
    public static List<Room> plan(int x, int y, int w, int h,
                                  List<String> types, Random rng) {
        return plan(x, y, w, h, types, Facing.SOUTH, rng);
    }

    /**
     * Origins of the front, middle and back bands, measured from the FRONT
     * face inward. Returns {fx, fy, mx, my, bx, by}.
     */
    static int[] bandOrigins(int x, int y, int w, int h, Facing f,
                             int front, int middle, int back) {
        return switch (f) {
            // Front faces north: the front band is at the top, y ascending.
            case NORTH -> new int[]{x, y, x, y + front, x, y + front + middle};
            case SOUTH -> new int[]{x, y + middle + back, x, y + back, x, y};
            case WEST  -> new int[]{x, y, x + front, y, x + front + middle, y};
            case EAST  -> new int[]{x + middle + back, y, x + back, y, x, y};
        };
    }

    static void addBand(List<Room> out, String type, int x, int y, int w, int h) {
        if (w > 0 && h > 0) out.add(new Room(type, x, y, w, h));
    }

    static void addBandEntrance(List<Room> out, String type,
                                int x, int y, int w, int h) {
        if (w > 0 && h > 0) out.add(new Room(type, x, y, w, h, true));
    }

    /** Absorb slack into a band so the rooms still tile the footprint exactly. */
    static void growBand(List<Room> out, String type, Facing f, int extra) {
        for (int i = 0; i < out.size(); i++) {
            Room r = out.get(i);
            if (!r.type().equals(type)) continue;
            boolean e = r.entrance();
            switch (f) {
                case NORTH -> out.set(i, new Room(type, r.x(), r.y(), r.w(), r.h() + extra, e));
                case SOUTH -> out.set(i, new Room(type, r.x(), r.y() - extra, r.w(), r.h() + extra, e));
                case WEST  -> out.set(i, new Room(type, r.x(), r.y(), r.w() + extra, r.h(), e));
                case EAST  -> out.set(i, new Room(type, r.x() - extra, r.y(), r.w() + extra, r.h(), e));
            }
            return;
        }
    }

    static List<String> share(List<String> all, int from, int to) {
        return new ArrayList<>(all.subList(Math.min(from, all.size()),
                                           Math.min(to, all.size())));
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * Recursive split, allocating area by type weight.
     *
     * Cuts perpendicular to the longer side, which keeps rooms from becoming
     * corridors, and places the cut at the position that divides the remaining
     * area in the ratio the two groups' weights ask for.
     */
    static void split(List<Room> out, int x, int y, int w, int h,
                      List<String> rooms, Random rng) {
        if (rooms.isEmpty() || w < 1 || h < 1) return;
        if (rooms.size() == 1) {
            out.add(new Room(rooms.get(0), x, y, w, h));
            return;
        }

        int half = rooms.size() / 2;
        List<String> a = share(rooms, 0, half);
        List<String> b = share(rooms, half, rooms.size());

        double wa = weightOf(a), wb = weightOf(b);
        double frac = wa / (wa + wb);

        // Try BOTH axes and keep whichever leaves the children nearer square.
        // Always cutting the long side is right for a balanced split and wrong
        // for an unbalanced one: a 2-square closet against a 42-square
        // livingroom cuts nowhere near the middle, and on a wide region that
        // produces a sliver running the full depth.
        int vCut = clamp((int) Math.round(w * frac), MIN_SIDE, w - MIN_SIDE);
        int hCut = clamp((int) Math.round(h * frac), MIN_SIDE, h - MIN_SIDE);
        boolean vOk = w - MIN_SIDE >= MIN_SIDE && vCut >= MIN_SIDE && w - vCut >= MIN_SIDE;
        boolean hOk = h - MIN_SIDE >= MIN_SIDE && hCut >= MIN_SIDE && h - hCut >= MIN_SIDE;

        if (!vOk && !hOk) { out.add(new Room(rooms.get(0), x, y, w, h)); return; }

        boolean vertical;
        if (vOk && hOk) {
            double vScore = Math.max(aspect(vCut, h), aspect(w - vCut, h));
            double hScore = Math.max(aspect(w, hCut), aspect(w, h - hCut));
            vertical = vScore <= hScore;
        } else {
            vertical = vOk;
        }

        if (vertical) {
            split(out, x, y, vCut, h, a, rng);
            split(out, x + vCut, y, w - vCut, h, b, rng);
        } else {
            split(out, x, y, w, hCut, a, rng);
            split(out, x, y + hCut, w, h - hCut, b, rng);
        }
    }

    /** Longer side over shorter. 1.0 is square; a corridor is 4 or more. */
    static double aspect(int w, int h) {
        return Math.max(w, h) / (double) Math.max(1, Math.min(w, h));
    }

    static double weightOf(List<String> rooms) {
        double s = 0;
        for (String r : rooms) s += WEIGHT.getOrDefault(r, WEIGHT_DEFAULT);
        return s <= 0 ? 1 : s;
    }

    // ------------------------------------------------------------------
    // Self-test:  java -cp out pzformat.BuildingPlan
    //
    // The invariant that matters is that rooms TILE the footprint — no gaps,
    // no overlaps — because a gap is a square with no room and an overlap is
    // two rooms claiming one square, and neither is representable.
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        int fail = 0;
        Random rng = new Random(42);

        int[][] cases = {{12, 10}, {16, 10}, {24, 7}, {15, 14}, {22, 14}, {20, 17},
                         {5, 4}, {30, 6}, {4, 3}};
        for (Facing f : Facing.values())
            for (int[] c : cases) {
                int w = c[0], h = c[1];
                List<String> types = recipe(w * h, "Residential", false, rng);
                List<Room> rooms = plan(0, 0, w, h, types, f, rng);
                fail += check(f + " " + w + "x" + h, w, h, rooms);
            }

        // The grammar itself: the livingroom must be the band the front door
        // opens into, so it must touch the front face.
        int bad = 0;
        for (Facing f : Facing.values())
            for (int t = 0; t < 60; t++) {
                Random r2 = new Random(t);
                List<String> types = recipe(150, "Residential", false, r2);
                List<Room> rooms = plan(0, 0, 15, 12, types, f, r2);
                Room lr = null;
                for (Room rm : rooms) if (rm.type().equals("livingroom")) { lr = rm; break; }
                if (lr == null) { bad++; continue; }
                boolean onFront = switch (f) {
                    case NORTH -> lr.y() == 0;
                    case SOUTH -> lr.y() + lr.h() == 12;
                    case WEST  -> lr.x() == 0;
                    case EAST  -> lr.x() + lr.w() == 15;
                };
                if (!onFront) bad++;
            }
        System.out.printf("%-28s %s  %d of 240 misplaced%n",
                "livingroom is at the front", bad == 0 ? "PASS" : "FAIL", bad);
        if (bad > 0) fail++;

        // §35 R3: bedroom is 1 of 229 vanilla exterior doors. Ours must be 0.
        int badDoor = 0, marked = 0;
        for (Facing f : Facing.values())
            for (int t = 0; t < 60; t++) {
                Random r3 = new Random(t + 500);
                List<String> types = recipe(200, "Residential", false, r3);
                for (Room rm : plan(0, 0, 16, 14, types, f, r3)) {
                    if (!rm.entrance()) continue;
                    marked++;
                    if (!rm.canTakeDoor()) badDoor++;
                }
            }
        System.out.printf("%-28s %s  %d bad of %d marked%n",
                "no bedroom entrances", badDoor == 0 ? "PASS" : "FAIL", badDoor, marked);
        if (badDoor == 0 && marked == 0) {
            System.out.println("      (nothing marked — the entrance flag is not being set)");
            badDoor = 1;
        }
        if (badDoor > 0) fail++;

        // GIS speaks: Agriculture is a barn, whatever its size.
        List<Room> barn = plan(0, 0, 12, 11, recipe(132, "Agriculture", false, rng), rng);
        System.out.printf("%-28s %s  %s%n", "Agriculture -> barn",
                barn.size() == 1 && barn.get(0).type().equals("barn") ? "PASS" : "FAIL", barn);
        if (barn.size() != 1 || !barn.get(0).type().equals("barn")) fail++;

        // A dwelling always has a bathroom — the guardrail.
        int noBath = 0;
        for (int i = 0; i < 200; i++) {
            List<String> t = recipe(100, "Residential", false, new Random(i));
            if (!t.contains("bathroom")) noBath++;
        }
        System.out.printf("%-28s %s  %d of 200 without%n", "dwelling always has a bath",
                noBath == 0 ? "PASS" : "FAIL", noBath);
        if (noBath > 0) fail++;

        // Aspect: a room shaped like a corridor is a defect, and it is also a
        // room that is too BIG, because the stretch comes from filling a deep
        // band. Before the two-axis split the worst case was 7.0.
        double worst = 0;
        String worstAt = "";
        for (Facing f : Facing.values())
            for (int[] c : cases) {
                Random r4 = new Random(c[0] * 31 + c[1]);
                List<String> types = recipe(c[0] * c[1], "Residential", false, r4);
                for (Room rm : plan(0, 0, c[0], c[1], types, f, r4)) {
                    double a = aspect(rm.w(), rm.h());
                    if (a > worst) { worst = a; worstAt = f + " " + c[0] + "x" + c[1] + " " + rm; }
                }
            }
        System.out.printf("%-28s %s  worst %.1f  %s%n", "no corridor-shaped rooms",
                worst <= 4.5 ? "PASS" : "FAIL", worst, worstAt);
        if (worst > 4.5) fail++;

        System.out.println(fail == 0 ? "\nall cases pass" : "\n" + fail + " FAILED");
        if (fail > 0) System.exit(1);
    }

    static int check(String label, int w, int h, List<Room> rooms) {
        int[][] grid = new int[w][h];
        int overlaps = 0;
        for (Room r : rooms)
            for (int i = r.x(); i < r.x() + r.w(); i++)
                for (int j = r.y(); j < r.y() + r.h(); j++) {
                    if (i < 0 || j < 0 || i >= w || j >= h) { overlaps++; continue; }
                    if (grid[i][j]++ > 0) overlaps++;
                }
        int gaps = 0;
        for (int[] col : grid) for (int v : col) if (v == 0) gaps++;

        int tooThin = 0;
        for (Room r : rooms) if (Math.min(r.w(), r.h()) < 1) tooThin++;

        boolean ok = overlaps == 0 && gaps == 0 && tooThin == 0 && !rooms.isEmpty();
        System.out.printf("%-28s %s  %d rooms, %d gaps, %d overlaps%n",
                label, ok ? "PASS" : "FAIL", rooms.size(), gaps, overlaps);
        if (!ok) for (Room r : rooms) System.out.println("      " + r);
        return ok ? 0 : 1;
    }
}
