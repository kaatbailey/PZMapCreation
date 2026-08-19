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

    /**
     * Median areas the room-list budget is spent in — §34/§35, measured.
     * `CORE_MEDIAN` is livingroom 32 + kitchen 21 + bathroom 6 + bedroom 14.
     * A 30-square house overspends it and gets exactly the core, which is
     * what §35 found: all four types appear in houses as small as 30.
     */
    static final int MEDIAN_BEDROOM = 14;
    static final int CORE_MEDIAN = 32 + 21 + 6 + MEDIAN_BEDROOM;

    /**
     * UNVERIFIED, both. `BEDROOM_MAX` stops a large commercial footprint that
     * slipped past the size-to-type mapping becoming a 40-bedroom house;
     * §33 says size predicts type strongly, so this should rarely bind.
     * `SECOND_BATH_AREA` is where a second bathroom appears.
     */
    static final int BEDROOM_MAX = 8;
    static final int SECOND_BATH_AREA = 240;

    /** Smallest a room may be on its short side. A closet is 2 squares (§34). */
    static final int MIN_SIDE = 3;

    /** Corridor width. §34: halls are long and thin, median 40 squares. */
    static final int HALL_W = 1;   // a real hallway is 3-4ft, one tile

    /**
     * Shortest side each core room will accept — §35's p5 short side, measured
     * over 4,065 cells. A livingroom does not go below 4 wide anywhere in
     * vanilla; a kitchen does not go below 3. The band depth is clamped up to
     * these before anything else, because a 30x2 livingroom is not a small
     * livingroom, it is a corridor.
     */
    static final int MIN_LIVING_SIDE = 4;
    static final int MIN_KITCHEN_SIDE = 3;

    /** Aspect above which the core is cut out of its band as a block. */
    static final double CORE_MAX_ASPECT = 2.0;

    /**
     * Rooms an exterior door may open into — measured, §35. Of 229 vanilla
     * exterior doors: livingroom 31.0%, kitchen 26.6%, hall 17.9%, laundry
     * 9.6%, lobby 5.7%, diningroom 2.6%. **`bedroom` is 1 of 229.**
     */
    public static final java.util.Set<String> ENTRANCE = java.util.Set.of(
            "livingroom", "kitchen", "hall", "laundry", "lobby", "diningroom",
            "barn", "garagestorage", "shed");

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

        // Extra rooms are BEDROOMS, added until the space runs out. Owner's
        // rule, 2026-08-14: livingroom, kitchen, bathroom and one bedroom are
        // REQUIRED; further bedrooms fill the remaining area; closets, laundry
        // and storage are the LEFTOVER, not peers with targets.
        //
        // The previous loop added one room per 55 squares and made 32% of them
        // bathrooms, which gave a 341-square house 2.28 bathrooms and a
        // 500-square house 3.19. It also added duplicate livingrooms and
        // kitchens on the strength of §33's counts-when-present (livingroom
        // 2.39, kitchen 1.61, bathroom 2.75). Those counts are inflated by the
        // clustering artifact §35 records: `RoomCluster` merges a terrace into
        // one "house", so three houses' cores are counted as one building's.
        // The same artifact made the core-share numbers unusable. A single
        // dwelling has one of each.
        //
        // The COUNT curve is measured and stays: §33's one extra room per ~55
        // squares beyond the core puts the peak at 5 rooms for a median
        // 80-square building, which is vanilla's peak. Only the TYPE of those
        // extras changes. Spending a bedroom median per extra instead gave a
        // 173-square house 8.2 bedrooms, because a bedroom does not consume
        // only its own floor: it drags hallway, closet and service area with
        // it, and the 55 already has that priced in.
        int extra = Math.max(0, (area - 90) / 55);
        int beds = 1;
        for (int i = 0; i < extra && beds < BEDROOM_MAX; i++) {
            r.add(rng.nextDouble() < 0.24 ? "kidsbedroom" : "bedroom");
            beds++;
        }


        // A second bathroom is a property of the HOUSE, not of the bedroom
        // count. §35's target is about one per house at these sizes; vanilla
        // ensuites are 6 against 171 off-core, so bedrooms do not bring their
        // own. UNVERIFIED: the threshold below is a guess at where a second
        // one appears. The check is `RoomCluster --area<150`.
        if (beds >= 4 && area >= SECOND_BATH_AREA) r.add("bathroom");

        // NO leftover loop. "Closets are the LEFTOVER, not peers with targets"
        // means they get no area target — which they already don't, because
        // `plan` tiles the footprint and a closet takes whatever its weight
        // (2.0, the smallest) leaves it. The optionals block above already
        // adds one at its measured 62%. A loop here that soaked the remaining
        // budget in closets put 12.6 rooms in a 173-square house.
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

        // Under the 10x10ft rule a building narrower than two rooms cannot be
        // a dwelling: a 4x8 has 32 squares but only 13 feet of width, so after
        // the public zone there is nothing to put a bathroom in. It is a shed,
        // and §33 already says small footprints overwhelmingly are. Checked
        // BEFORE the capacity trim, which would otherwise drop the bathroom
        // and the bedroom first and leave a plausible-looking half-house.
        if (types.contains("livingroom") && Math.min(w, h) < MIN_ROOM * 2) {
            out.add(new Room(pick(rng, 0.7, "garagestorage", "shed"), x, y, w, h));
            return out;
        }

        List<String> rooms = new ArrayList<>(types);

        // How many rooms will actually fit? Splitting past that yields slivers.
        int capacity = (w / MIN_SIDE) * (h / MIN_SIDE);
        while (rooms.size() > capacity && rooms.size() > 1)
            rooms.remove(rooms.size() - 1);

        // A single-room building — barn, garage, shed — is the whole rectangle.
        // It gets an entrance so carveEntrances places a door on it.
        if (rooms.size() == 1) {
            out.add(new Room(rooms.get(0), x, y, w, h, ENTRANCE.contains(rooms.get(0))));
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

        // A dwelling is laid out by the owner's rule: public zone against the
        // road, private zone beside it, bedrooms grown until one no longer
        // fits. `recipe`'s secondary list is not consulted — under this rule
        // the room count comes from the GEOMETRY, not from a count fitted to
        // area. Non-dwellings keep the weighted split.
        // A long narrow building cannot hold two zones side by side: a 30x6
        // splits into two 3-wide strips 30 deep, and the livingroom comes out
        // 18x3. Below this proportion the house is a row along its long axis,
        // every room spanning the short side. Checked BEFORE the zone split.
        if (aspect(w, h) >= 3.0) {
            List<String> row = new ArrayList<>();
            row.add("livingroom");
            if (hasKitchen) row.add("kitchen");
            row.addAll(rooms);
            row(out, x, y, w, h, row, facing);
            return out;
        }

        if (Math.min(w, h) >= MIN_ROOM * 2) {
            house(out, x, y, w, h, facing, hasKitchen, rng);
            return out;
        }

        boolean vertical = facing == Facing.NORTH || facing == Facing.SOUTH;
        int span = vertical ? h : w;

        // A footprint too shallow to hold a front band and a back band cannot
        // be banded at all: a 30x6 needs livingroom 4 deep plus kitchen 3 deep
        // and has 6. Banding it anyway produced the 30x2 strips the self-test
        // has been red on. Lay it out as a ROW along the long axis instead —
        // every room spans the full depth, so every room meets both outer
        // faces and the livingroom is at the front whichever way it faces.
        // The test is the footprint's SHORT side, not the front-to-back span.
        // An east-facing 30x6 has span 30 and banded a 19-deep livingroom at
        // aspect 4.8; it is the same building as the north-facing one and wants
        // the same layout. Below this width a room spans the short side, so the
        // house is a row along its long axis whichever way it faces.
        if (Math.min(w, h) <= MIN_LIVING_SIDE + MIN_SIDE) {
            List<String> row = new ArrayList<>();
            row.add("livingroom");
            if (hasKitchen) row.add("kitchen");
            row.addAll(rooms);
            row(out, x, y, w, h, row, facing);
            return out;
        }

        // Front band for the livingroom, back band for the kitchen, middle for
        // everything else. Bands are proportional to the type weights so a
        // livingroom stays the largest room in the house (§34: 1.80x mean).
        double wLiving = WEIGHT.get("livingroom");
        double wKitchen = hasKitchen ? WEIGHT.get("kitchen") : 0;
        double wRest = Math.max(0.5, weightOf(rooms));
        double total = wLiving + wKitchen + wRest;

        // Depth first, and never below the measured minimum short side: a
        // livingroom is 4 or it is not a livingroom.
        //
        // A footprint that can hold the two core bands but not a third band
        // between them gets NO middle. A 24x7 has exactly 4+3 and was left with
        // a 2-deep middle, which is a 24x2 room at aspect 12. The secondary
        // rooms go beside the core in its own band instead.
        // A middle band also needs ROOMS. It is only worth having if it can
        // hold two — one room in a band spans the whole frontage, which is the
        // 16x3 bathroom at aspect 5.3 the self-test caught. Two for the middle
        // plus one for each core's leftover is four; below that, fold the
        // middle into the core bands and let the cores cut blocks instead.
        int bandFloor = MIN_LIVING_SIDE + (hasKitchen ? MIN_KITCHEN_SIDE : MIN_SIDE);

        // NO MIDDLE BAND. The front and back bands cover the whole span, so
        // every remaining room must fit BESIDE a core room — there is nowhere
        // else. The previous version let the cores take their whole bands and
        // dropped whatever was left: a 9x9 house came out as livingroom plus
        // kitchen, with the bathroom and the bedroom discarded in silence.
        //
        // So assign the secondary rooms to the two bands FIRST, then size each
        // band from the total weight it carries, then cut the core out of it.
        if (span < bandFloor + MIN_SIDE || rooms.size() < 4) {
            List<String> fg = new ArrayList<>(), bg = new ArrayList<>();
            for (int i = 0; i < rooms.size(); i++) (i % 2 == 0 ? fg : bg).add(rooms.get(i));
            if (!hasKitchen) { fg.addAll(bg); bg.clear(); }

            double wf = wLiving + weightSum(fg), wb = wKitchen + weightSum(bg);
            int backD = hasKitchen
                    ? clamp((int) Math.round(span * (wb / (wf + wb))),
                            MIN_KITCHEN_SIDE, span - MIN_LIVING_SIDE)
                    : 0;
            int frontD = span - backD;
            int[] b = bandOrigins(x, y, w, h, facing, frontD, 0, backD);

            boolean atMax = facing == Facing.SOUTH || facing == Facing.EAST;
            band(out, "livingroom", b[0], b[1], vertical ? w : frontD,
                    vertical ? frontD : h, vertical, atMax, MIN_LIVING_SIDE, fg, rng);
            if (hasKitchen && backD > 0)
                band(out, "kitchen", b[4], b[5], vertical ? w : backD,
                        vertical ? backD : h, vertical, !atMax, MIN_KITCHEN_SIDE, bg, rng);
            return out;
        }

        int front, back;
        {
            front = clamp((int) Math.round(span * (wLiving / total)),
                    MIN_LIVING_SIDE, span - MIN_SIDE - (hasKitchen ? MIN_KITCHEN_SIDE : 0));
            back = hasKitchen
                    ? clamp((int) Math.round(span * (wKitchen / total)),
                            MIN_KITCHEN_SIDE, span - front - MIN_SIDE)
                    : 0;
        }
        int middle = span - front - back;
        if (middle < 0) { middle = 0; back = Math.max(0, span - front); }

        // Bands run from the FRONT face inward, so the livingroom is always the
        // band the front door opens into.
        int[] bands = bandOrigins(x, y, w, h, facing, front, middle, back);

        // Both core rooms touch an outer face by construction — the livingroom
        // the front, the kitchen the back — so both may hold an exterior door.
        // §35: those two account for 58% of vanilla's entrances.
        //
        // The core is placed as a BLOCK, not a band. Handing the livingroom the
        // full frontage gave a 30x6 footprint a 30x2 livingroom at aspect 15.0.
        // Cutting across the frontage keeps the core against the front face —
        // so the grammar is unchanged — while letting a secondary room sit
        // beside it. Only cut when there is a room to put in the leftover,
        // because an empty region is a gap and a gap is unrepresentable.
        List<int[]> spare = new ArrayList<>();
        int reserve = (middle >= MIN_SIDE && !rooms.isEmpty()) ? 1 : 0;
        int avail = rooms.size() - reserve;

        avail -= placeCore(out, "livingroom", bands[0], bands[1],
                vertical ? w : front, vertical ? front : h,
                vertical, MIN_LIVING_SIDE, avail, spare);
        if (hasKitchen && back > 0)
            avail -= placeCore(out, "kitchen", bands[4], bands[5],
                    vertical ? w : back, vertical ? back : h,
                    vertical, MIN_KITCHEN_SIDE, avail, spare);

        // The spare regions take their share of the room list BY AREA, off the
        // tail, so the middle band keeps the first and most important rooms.
        // One room each is not enough: a 16x3 leftover holding a single room is
        // aspect 5.3, which is the same corridor defect one level down.
        int middleArea = vertical ? w * middle : middle * h;
        int spareArea = 0;
        for (int[] s : spare) spareArea += s[2] * s[3];
        int regionArea = spareArea + (middle >= MIN_SIDE ? middleArea : 0);

        // The middle keeps its own area share, not a token single room. With a
        // reserve of 1 the spares stripped the list and a 16x3 middle band held
        // one room at aspect 5.3.
        // Every spare must still get at least one room — an unfilled region is
        // a gap, and the cut gate only created a spare because one was free.
        if (middle >= MIN_SIDE && regionArea > 0 && !rooms.isEmpty())
            reserve = clamp((int) Math.round(rooms.size() * (double) middleArea / regionArea),
                    1, Math.max(1, rooms.size() - spare.size()));

        // Each region needs a MINIMUM number of rooms or it becomes a strip:
        // one room fills the region, so a 16x3 leftover holding one room is a
        // 16x3 room. Deriving it rather than tuning it — a region `cross` long
        // and `depth` deep needs ceil(cross / (depth * maxAspect)) rooms for
        // the pieces to come out under that aspect. Minimums first, remainder
        // by area.
        int need = middle >= MIN_SIDE ? minRooms(vertical ? w : middle, vertical ? middle : h) : 0;
        for (int[] s : spare) need += minRooms(s[2], s[3]);
        // Against the list size BEFORE the loop starts consuming it.
        boolean minimumsFit = need <= rooms.size();

        if (middle >= MIN_SIDE && !rooms.isEmpty())
            reserve = clamp(minimumsFit
                            ? minRooms(vertical ? w : middle, vertical ? middle : h)
                              + (int) Math.round((rooms.size() - need)
                                    * (double) middleArea / Math.max(1, regionArea))
                            : (int) Math.round(rooms.size() * (double) middleArea / regionArea),
                    1, Math.max(1, rooms.size() - spare.size()));

        for (int[] s : spare) {
            if (rooms.size() <= reserve) break;
            int free = rooms.size() - reserve;
            int n = minimumsFit ? minRooms(s[2], s[3]) : 1;
            n = clamp(n, 1, Math.min(free, Math.max(1, (s[2] / MIN_SIDE) * (s[3] / MIN_SIDE))));
            List<String> mine = new ArrayList<>(
                    rooms.subList(rooms.size() - n, rooms.size()));
            rooms.subList(rooms.size() - n, rooms.size()).clear();
            regionArea -= s[2] * s[3];
            split(out, s[0], s[1], s[2], s[3], mine, rng);
        }

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

    /**
     * Minimum room, in TILES. The owner states it as 10x10 feet; a PZ square
     * is about a metre, so 10 ft is 3 tiles — which is exactly `RoomMinimums`'
     * measured bedroom 3x3. The rule and the vanilla measurement agree.
     * A kitchen may be half, 10x5 ft, matching the measured kitchen short 3.
     */
    static final int MIN_ROOM = 3;
    static final int MIN_CLOSET = 2;

    /** Median areas, §34, in tiles. What a room is built at when it can be. */
    static final int A_LIVING = 32, A_KITCHEN = 21, A_BED = 14, A_BATH = 6;

    /**
     * Lay out a dwelling by the owner's rule, 2026-08-18.
     *
     *   1. The FIRST division splits public (livingroom, kitchen) from private
     *      (bedrooms, bathroom). Everything else is downstream of that cut.
     *   2. The public side puts the livingroom on the road and the kitchen
     *      behind it, so they share the boundary that is open 55.4% of the
     *      time in vanilla.
     *   3. The private side takes a bathroom and one bedroom, then keeps
     *      adding whole bedrooms while a whole bedroom still fits.
     *   4. What is left when a bedroom no longer fits is not a room with a
     *      target — it is closet, laundry, pantry.
     *
     * This replaces the front/middle/back banding. The middle band was where
     * every corridor-shaped room came from, and under this rule it does not
     * exist: both zones run the full depth of the house.
     */
    /**
     * Lay out a dwelling. Owner's rule, drawn 2026-08-18.
     *
     *   1. FIRST DIVISOR, road to back. Placed to meet the MINIMUMS, not to a
     *      proportion — a 50ft house gets the same first cut a 30ft one does,
     *      and the surplus goes to the private side to be subdivided. Sizing
     *      it from the livingroom's target area instead made the public strip
     *      grow with the house, which is not what happens.
     *   2. Livingroom on the road, kitchen behind it.
     *   3. Private side: one bedroom and a bathroom.
     *   4. RE-MEASURE. While the largest bedroom can be halved with both
     *      halves still meeting 10x10ft, halve it and re-measure. The count is
     *      not computed from area — the arithmetic cannot know that a hallway
     *      and two bathroom walls ate the space the next bedroom needed.
     *   5. At three bedrooms or more a HALLWAY appears, one tile wide, down
     *      the middle of the private side lengthwise, rooms either side. At
     *      two the bedrooms open onto the livingroom. UNVERIFIED — the check
     *      is what fraction of vanilla's two-bedroom houses contain a `hall`.
     *   6. What is left that cannot be a bedroom becomes closets and storage,
     *      spread across the house rather than pooled in one corner.
     */
    /** The livingroom will not be stretched past this to span the depth. */
    static final double LIVING_MAX_ASPECT = 2.0;

    /**
     * Lay out a dwelling. Owner's rule, drawn and stated 2026-08-18.
     *
     *   1. FIRST DIVISOR, road to back. Public strip one side, private the
     *      other.
     *   2. The livingroom and kitchen ALWAYS span the full depth. Where they
     *      would have to stretch thin to do it, the strip is made WIDER rather
     *      than the rooms longer — a 14x39 house was getting a 4x24
     *      livingroom, which is a corridor with a sofa in it.
     *   3. A DININGROOM goes between them when the strip is wide enough to
     *      seat a table without robbing either.
     *   4. Surplus — laundry, storage — goes on the KITCHEN side, between the
     *      kitchen and the first bedroom.
     *   5. Bedrooms by halving and re-measuring. A hallway from three
     *      bedrooms up, not two: with no office a five-room dwelling is a
     *      two-bedroom house, and §33 puts the hall rate there at 14.4%.
     */
    static void house(List<Room> out, int x, int y, int w, int h,
                      Facing facing, boolean hasKitchen, Random rng) {
        boolean vertical = facing == Facing.NORTH || facing == Facing.SOUTH;
        int cross = vertical ? w : h;   // along the road
        int depth = vertical ? h : w;   // road to back
        boolean atMax = facing == Facing.SOUTH || facing == Facing.EAST;

        // Wide enough that the livingroom holds its proportions over the whole
        // depth, and never narrower than the minimum.
        double lvShare = hasKitchen ? A_LIVING / (double) (A_LIVING + A_KITCHEN) : 1.0;
        // Floored at the 10x10ft rule, not at §35's measured p5 of 4: that is
        // a fifth percentile, not a minimum, and using it as one left 6-wide
        // houses with 2 squares of private side and no bedroom at all.
        int pubCross = clamp((int) Math.ceil(depth * lvShare / LIVING_MAX_ASPECT),
                MIN_ROOM, cross - MIN_ROOM);

        // Diningroom only when the width can seat one without robbing the pair.
        boolean dining = hasKitchen && pubCross >= MIN_ROOM * 2 + 1
                && depth >= MIN_ROOM * 3;
        int dd = dining ? MIN_ROOM : 0;
        int kd = hasKitchen && depth - dd >= MIN_ROOM * 2
                ? clamp((int) Math.round((depth - dd) * (1 - lvShare)), MIN_ROOM, depth - dd - MIN_ROOM)
                : 0;
        int ld = depth - dd - kd;

        // Ordered from the road inward, then flipped when the road is at the
        // high end of the depth axis.
        int[] depths = {ld, dd, kd};
        String[] names = {"livingroom", "diningroom", "kitchen"};
        int at = 0;
        for (int i = 0; i < 3; i++) {
            int d = depths[i];
            if (d <= 0) continue;
            int pos = atMax ? depth - at - d : at;
            boolean door = !names[i].equals("diningroom");
            if (vertical) out.add(new Room(names[i], x, y + pos, pubCross, d, door));
            else out.add(new Room(names[i], x + pos, y, d, pubCross, door));
            at += d;
        }

        // The private side, and the surplus band that sits against the kitchen.
        int pc = cross - pubCross;
        int px = vertical ? x + pubCross : x;
        int py = vertical ? y : y + pubCross;
        int pw = vertical ? pc : depth;
        int ph = vertical ? depth : pc;

        // Surplus goes between the kitchen and the first bedroom: the kitchen
        // end of the private strip, which is the far end from the road.
        int surplus = 0;
        if (kd > 0 && depth >= MIN_ROOM * 4 && pc >= MIN_ROOM) surplus = MIN_ROOM;
        if (surplus > 0) {
            int pos = atMax ? 0 : depth - surplus;
            int[] band = vertical ? r(px, py + pos, pw, surplus)
                                  : r(px + pos, py, surplus, ph);
            splitLeftover(out, band, rng);
            if (vertical) { py += atMax ? surplus : 0; ph -= surplus; }
            else          { px += atMax ? surplus : 0; pw -= surplus; }
        }

        int lvPos = atMax ? depth - ld : 0;
        privateSide(out, px, py, pw, ph, vertical,
                (vertical ? y : x) + lvPos, ld, (vertical ? y : x) + lvPos, rng);
    }

    /** A rectangle waiting to be typed: {x, y, w, h}. */
    private static int[] r(int x, int y, int w, int h) { return new int[]{x, y, w, h}; }

    /**
     * Bedrooms, bathroom, hallway. Rooms are produced by repeated HALVING and
     * re-measurement, so the count is bounded by the geometry rather than by a
     * formula fitted to area.
     */
    static void privateSide(List<Room> out, int zx, int zy, int zw, int zh,
                            boolean vertical, int lvY, int lvLen, int lvX, Random rng) {
        // The bathroom takes a corner first, so every later measurement sees
        // the space that actually remains.
        boolean bathAlongX = zw >= zh;
        int bathLong = clamp(A_BATH / MIN_ROOM, MIN_ROOM, (bathAlongX ? zw : zh) - MIN_ROOM);
        int[] rest;
        if (bathAlongX) {
            out.add(new Room("bathroom", zx, zy + Math.max(0, zh - MIN_ROOM), bathLong,
                    Math.min(MIN_ROOM, zh)));
            rest = r(zx, zy, zw, Math.max(0, zh - MIN_ROOM));
            if (rest[3] < MIN_ROOM) {   // no room left over: bathroom takes a side instead
                out.remove(out.size() - 1);
                out.add(new Room("bathroom", zx, zy, bathLong, zh));
                rest = r(zx + bathLong, zy, zw - bathLong, zh);
            } else {
                splitLeftover(out, r(zx + bathLong, zy + zh - MIN_ROOM,
                        zw - bathLong, Math.min(MIN_ROOM, zh)), rng);
            }
        } else {
            out.add(new Room("bathroom", zx, zy, Math.min(MIN_ROOM, zw), bathLong));
            rest = r(zx, zy + bathLong, zw, zh - bathLong);
            if (rest[3] < MIN_ROOM) {
                out.remove(out.size() - 1);
                out.add(new Room("bathroom", zx, zy, zw, bathLong));
                rest = r(zx, zy + bathLong, zw, zh - bathLong);
            } else {
                splitLeftover(out, r(zx + Math.min(MIN_ROOM, zw), zy,
                        zw - Math.min(MIN_ROOM, zw), bathLong), rng);
            }
        }
        if (rest[2] < MIN_ROOM || rest[3] < MIN_ROOM) { splitLeftover(out, rest, rng); return; }

        // One bedroom, then halve and re-measure while both halves still meet
        // the 10x10ft minimum.
        List<int[]> beds = new ArrayList<>();
        beds.add(rest);
        boolean progress = true;
        while (progress && beds.size() < BEDROOM_MAX) {
            progress = false;
            int big = 0;
            for (int i = 1; i < beds.size(); i++)
                if (beds.get(i)[2] * beds.get(i)[3] > beds.get(big)[2] * beds.get(big)[3]) big = i;
            int[] b = beds.get(big);
            boolean cutX = b[2] >= b[3];
            int half = (cutX ? b[2] : b[3]) / 2;
            if (half >= MIN_ROOM && (cutX ? b[2] : b[3]) - half >= MIN_ROOM) {
                beds.remove(big);
                if (cutX) {
                    beds.add(r(b[0], b[1], half, b[3]));
                    beds.add(r(b[0] + half, b[1], b[2] - half, b[3]));
                } else {
                    beds.add(r(b[0], b[1], b[2], half));
                    beds.add(r(b[0], b[1] + half, b[2], b[3] - half));
                }
                progress = true;
            }
        }

        // At three or more, a hallway down the middle lengthwise reaches them.
        // At two they open onto the livingroom and there is no hall.
        if (beds.size() >= 3) {
            int[] all = rest;
            boolean hallAlongX = all[2] >= all[3];
            int mid = (hallAlongX ? all[3] : all[2]) / 2;
            if ((hallAlongX ? all[3] : all[2]) - HALL_W >= MIN_ROOM * 2) {
                int limit = (hallAlongX ? all[3] : all[2]) - MIN_ROOM - HALL_W;
                // Clamp into the livingroom's own span so the hall opens onto it.
                boolean variesAlongDepth = vertical == hallAlongX;
                if (variesAlongDepth) {
                    int lo = (vertical ? lvY - all[1] : lvX - all[0]);
                    mid = clamp(mid, Math.max(MIN_ROOM, lo), Math.min(limit, lo + lvLen - HALL_W));
                }
                mid = clamp(mid, MIN_ROOM, Math.max(MIN_ROOM, limit));
                out.add(hallAlongX
                        ? new Room("hall", all[0], all[1] + mid, all[2], HALL_W)
                        : new Room("hall", all[0] + mid, all[1], HALL_W, all[3]));
                beds = new ArrayList<>();
                int[] sideA = hallAlongX ? r(all[0], all[1], all[2], mid)
                                         : r(all[0], all[1], mid, all[3]);
                int[] sideB = hallAlongX
                        ? r(all[0], all[1] + mid + HALL_W, all[2], all[3] - mid - HALL_W)
                        : r(all[0] + mid + HALL_W, all[1], all[2] - mid - HALL_W, all[3]);
                for (int[] side : new int[][]{sideA, sideB}) packBeds(beds, side);
            }
        }

        for (int i = 0; i < beds.size(); i++) {
            int[] b = beds.get(i);
            out.add(new Room(rng.nextDouble() < 0.24 ? "kidsbedroom" : "bedroom",
                    b[0], b[1], b[2], b[3]));
        }
    }

    /**
     * Fill one side of the hallway. Same rule as everywhere else: halve the
     * largest while both halves still meet 10x10ft, then stop. A fixed piece
     * count left a 20-long side as one 20x3 bedroom.
     */
    static void packBeds(List<int[]> beds, int[] region) {
        if (region[2] < MIN_ROOM || region[3] < MIN_ROOM) return;
        List<int[]> mine = new ArrayList<>();
        mine.add(region);
        boolean progress = true;
        while (progress && mine.size() < BEDROOM_MAX) {   // per side, not shared
            progress = false;
            int big = 0;
            for (int i = 1; i < mine.size(); i++)
                if (mine.get(i)[2] * mine.get(i)[3] > mine.get(big)[2] * mine.get(big)[3]) big = i;
            int[] b = mine.get(big);
            boolean cutX = b[2] >= b[3];
            int len = cutX ? b[2] : b[3];
            int half = len / 2;
            if (half >= MIN_ROOM && len - half >= MIN_ROOM) {
                mine.remove(big);
                if (cutX) {
                    mine.add(r(b[0], b[1], half, b[3]));
                    mine.add(r(b[0] + half, b[1], b[2] - half, b[3]));
                } else {
                    mine.add(r(b[0], b[1], b[2], half));
                    mine.add(r(b[0], b[1] + half, b[2], b[3] - half));
                }
                progress = true;
            }
        }
        beds.addAll(mine);
    }

    /** Space too small for a bedroom becomes closet or storage. */
    static void splitLeftover(List<Room> out, int[] rr, Random rng) {
        if (rr[2] <= 0 || rr[3] <= 0) return;
        // Spread across the house, not pooled: a single 37x3 laundry was the
        // whole leftover strip typed as one room. Chop along the long axis.
        boolean cutX = rr[2] >= rr[3];
        int len = cutX ? rr[2] : rr[3], other = cutX ? rr[3] : rr[2];
        int n = clamp(len / Math.max(MIN_ROOM, other * 2), 1, 6);
        int at = 0;
        for (int i = 0; i < n; i++) {
            int size = (len - at) / (n - i);
            String t = rng.nextDouble() < 0.2 ? "laundry" : "closet";
            out.add(cutX ? new Room(t, rr[0] + at, rr[1], size, rr[3])
                         : new Room(t, rr[0], rr[1] + at, rr[2], size));
            at += size;
        }
    }

    static int ceilDiv(int a, int b) { return (a + b - 1) / Math.max(1, b); }

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

    /**
     * Fill one band: the core room as a block against the outer face, the
     * rooms in {@code group} beside it.
     *
     * TWO ways to cut, and it tries both — §35's aspect fix, one level up.
     * Cutting ACROSS the frontage gives the core part of the outer face;
     * cutting ALONG the depth gives it the whole face and a shallower room.
     * Both keep the core touching the face, so the grammar holds either way,
     * and the better worst-case aspect wins. Cutting across only, a 15-deep
     * band 7 wide gave a 15x5 livingroom and a 15x2 strip at aspect 7.5,
     * where cutting along gives 10x7 and 5x7.
     *
     * @param atMax core sits at the high end of the depth axis, not the low
     */
    static void band(List<Room> out, String type, int bx, int by, int bw, int bh,
                     boolean vertical, boolean atMax, int minSide,
                     List<String> group, Random rng) {
        if (bw <= 0 || bh <= 0) return;
        int cross = vertical ? bw : bh;
        int depth = vertical ? bh : bw;

        if (group.isEmpty()) { addBandEntrance(out, type, bx, by, bw, bh); return; }

        double wCore = WEIGHT.getOrDefault(type, WEIGHT_DEFAULT);
        double frac = wCore / (wCore + weightSum(group));

        boolean acrossOk = cross >= minSide + MIN_SIDE;
        boolean alongOk = depth >= MIN_SIDE * 2;
        if (!acrossOk && !alongOk) { addBandEntrance(out, type, bx, by, bw, bh); return; }

        int wantAcross = clamp((int) Math.round(cross * frac), minSide, cross - MIN_SIDE);
        int wantAlong = clamp((int) Math.round(depth * frac), MIN_SIDE, depth - MIN_SIDE);

        double sAcross = acrossOk
                ? Math.max(aspect(wantAcross, depth), aspect(cross - wantAcross, depth))
                : Double.MAX_VALUE;
        double sAlong = alongOk
                ? Math.max(aspect(cross, wantAlong), aspect(cross, depth - wantAlong))
                : Double.MAX_VALUE;

        if (sAlong < sAcross) {
            // Core keeps the full frontage, group sits inward of it.
            int coreD = wantAlong, restD = depth - wantAlong;
            int coreAt = atMax ? depth - coreD : 0;
            int restAt = atMax ? 0 : coreD;
            if (vertical) {
                addBandEntrance(out, type, bx, by + coreAt, bw, coreD);
                split(out, bx, by + restAt, bw, restD, new ArrayList<>(group), rng);
            } else {
                addBandEntrance(out, type, bx + coreAt, by, coreD, bh);
                split(out, bx + restAt, by, restD, bh, new ArrayList<>(group), rng);
            }
        } else if (vertical) {
            addBandEntrance(out, type, bx, by, wantAcross, bh);
            split(out, bx + wantAcross, by, bw - wantAcross, bh, new ArrayList<>(group), rng);
        } else {
            addBandEntrance(out, type, bx, by, bw, wantAcross);
            split(out, bx, by + wantAcross, bw, bh - wantAcross, new ArrayList<>(group), rng);
        }
    }

    /** Total weight, zero for an empty list — unlike {@link #weightOf}. */
    static double weightSum(List<String> rooms) {
        double s = 0;
        for (String r : rooms) s += WEIGHT.getOrDefault(r, WEIGHT_DEFAULT);
        return s;
    }

    static void addBandEntrance(List<Room> out, String type,
                                int x, int y, int w, int h) {
        if (w > 0 && h > 0) out.add(new Room(type, x, y, w, h, true));
    }

    /**
     * Place a core room inside its band, as a block where the band is too
     * elongated to be a room. Cuts ACROSS the frontage, never through the
     * depth, so whatever the cut the core still touches the outer face and the
     * grammar holds.
     *
     * @param vertical  facing is N/S, so the frontage runs along x
     * @param minSide   the type's measured p5 short side
     * @param avail     rooms left to fill a leftover with; 0 means do not cut
     * @param spare     leftover region appended here as {x,y,w,h}
     * @return 1 if a leftover was created and needs a room, else 0
     */
    static int placeCore(List<Room> out, String type, int bx, int by, int bw, int bh,
                         boolean vertical, int minSide, int avail, List<int[]> spare) {
        if (bw <= 0 || bh <= 0) return 0;

        // Depth runs front-to-back; cross runs along the outer face.
        int depth = vertical ? bh : bw;
        int cross = vertical ? bw : bh;

        double target = WEIGHT.getOrDefault(type, WEIGHT_DEFAULT);
        int want = (int) Math.round(target / Math.max(1, depth));
        want = Math.max(want, minSide);

        boolean cut = avail >= 1
                && aspect(bw, bh) > CORE_MAX_ASPECT
                && want <= cross - MIN_SIDE
                && cross - want >= MIN_SIDE;

        if (!cut) {
            addBandEntrance(out, type, bx, by, bw, bh);
            return 0;
        }

        if (vertical) {
            addBandEntrance(out, type, bx, by, want, bh);
            spare.add(new int[]{bx + want, by, bw - want, bh});
        } else {
            addBandEntrance(out, type, bx, by, bw, want);
            spare.add(new int[]{bx, by + want, bw, bh - want});
        }
        return 1;
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

    /**
     * Lay rooms out as a single row along the frontage, each spanning the full
     * depth. Sizes are proportional to the type weights, floored at MIN_SIDE,
     * and the remainder goes to the largest room so the tiling stays exact.
     */
    static void row(List<Room> out, int x, int y, int w, int h,
                    List<String> types, Facing facing) {
        boolean alongX = w >= h;
        int cross = alongX ? w : h;
        int depth = alongX ? h : w;
        if (cross < MIN_SIDE || depth < 1 || types.isEmpty()) return;

        // Every room spans the short side, so every room meets both long faces.
        // Only a facing along the ROW's own axis constrains where the
        // livingroom goes: it must sit at the end the front door is on.
        boolean reverse = (alongX && facing == Facing.EAST)
                || (!alongX && facing == Facing.SOUTH);
        if (reverse) {
            List<String> flipped = new ArrayList<>(types);
            java.util.Collections.reverse(flipped);
            types = flipped;
        }

        List<String> t = new ArrayList<>(types);
        // Floor at the 10-foot minimum, not MIN_SIDE: a row that let rooms
        // fall to 2 wide produced a 13x2 bathroom in a 13x39 house.
        int capacity = Math.max(1, cross / MIN_ROOM);
        while (t.size() > capacity) t.remove(t.size() - 1);

        double total = weightOf(t);
        int[] size = new int[t.size()];
        int used = 0;
        for (int i = 0; i < t.size(); i++) {
            size[i] = Math.max(MIN_ROOM,
                    (int) Math.round(cross * WEIGHT.getOrDefault(t.get(i), WEIGHT_DEFAULT) / total));
            used += size[i];
        }
        // Settle the rounding against the widest room, never below MIN_SIDE.
        for (int guard = 0; used != cross && guard < 4 * size.length + 8; guard++) {
            int pick = 0;
            for (int i = 1; i < size.length; i++)
                if (used > cross ? size[i] > size[pick] : size[i] < size[pick]) pick = i;
            if (used > cross && size[pick] <= MIN_ROOM) break;
            size[pick] += used > cross ? -1 : 1;
            used += used > cross ? -1 : 1;
        }
        if (used != cross) size[0] += cross - used;

        int at = 0;
        for (int i = 0; i < t.size(); i++) {
            boolean door = ENTRANCE.contains(t.get(i));
            if (alongX) out.add(new Room(t.get(i), x + at, y, size[i], h, door));
            else out.add(new Room(t.get(i), x, y + at, w, size[i], door));
            at += size[i];
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

    /**
     * Fewest rooms a region must be divided into for the pieces to come out
     * under {@link #ROOM_MAX_ASPECT}. Cutting a `cross` by `depth` region into
     * n pieces gives each cross/n by depth, so n >= cross / (depth * aspect).
     */
    static int minRooms(int w, int h) {
        int cross = Math.max(w, h), depth = Math.max(1, Math.min(w, h));
        int n = (int) Math.ceil(cross / (depth * ROOM_MAX_ASPECT));
        return clamp(n, 1, Math.max(1, (w / MIN_SIDE) * (h / MIN_SIDE)));
    }

    /** Slightly inside the self-test's 4.5, so the allocation has margin. */
    static final double ROOM_MAX_ASPECT = 4.0;

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
                    if ("hall".equals(rm.type())) continue;  // a hallway IS a corridor
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
