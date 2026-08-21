package pzformat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lays out the inside of a building.
 *
 * Pure:
 *   footprint + occupancy + room recipe -> typed room rectangles
 *
 * No map, no I/O.
 *
 * DWELLING LAYOUT
 * ---------------
 *
 * Dwellings use a HUB MODEL.
 *
 * SMALL / MEDIUM HOUSES
 *
 * The livingroom is the front/public hub.
 *
 *              FRONT / ROAD
 *                    |
 *        +-----------+--------+
 *        |       LIVING       |
 *        |       ROOM     | K  |
 *        |                | I  |
 *        |                | T  |
 *        +----------------+----+
 *        | BEDROOM        |BED |
 *        |                |    |
 *        +---------+------+----+
 *        | BATH    | BEDROOM   |
 *        +---------+-----------+
 *
 * There is deliberately NO HALL.
 *
 * The livingroom and kitchen occupy the valuable public/front portion of
 * the house. Bedrooms and service rooms directly abut that public zone.
 *
 * LARGE HOUSES
 *
 * Once the dwelling genuinely becomes large enough, a hallway/spine can
 * appear. The hall is not used merely because a house has two bedrooms.
 *
 * The important distinction is:
 *
 *   small/medium:
 *       livingroom -> kitchen/private rooms
 *
 *   large:
 *       livingroom/kitchen -> hall -> private rooms
 *
 * The old front/middle/back banding grammar has been removed from the
 * dwelling path.
 *
 * Non-dwellings continue to use weighted recursive splitting.
 */
public final class BuildingPlan {

    private BuildingPlan() {}

    // ---------------------------------------------------------------------
    // ROOM
    // ---------------------------------------------------------------------

    /**
     * A planned room.
     *
     * entrance is a writer hint. It means the room touches an exterior face
     * and may receive an exterior door.
     */
    public record Room(
            String type,
            int x,
            int y,
            int w,
            int h,
            boolean entrance) {

        public Room(
                String type,
                int x,
                int y,
                int w,
                int h) {

            this(
                    type,
                    x,
                    y,
                    w,
                    h,
                    false
            );
        }

        public int area() {
            return w * h;
        }

        public boolean canTakeDoor() {
            return ENTRANCE.contains(type);
        }

        @Override
        public String toString() {
            return type
                    + "["
                    + x
                    + ","
                    + y
                    + " "
                    + w
                    + "x"
                    + h
                    + "]"
                    + (entrance ? "*" : "");
        }
    }

    // ---------------------------------------------------------------------
    // FACING
    // ---------------------------------------------------------------------

    /**
     * Which side of the footprint faces the road/front.
     */
    public enum Facing {

        NORTH,
        SOUTH,
        EAST,
        WEST;

        public Facing opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }
    }

    // ---------------------------------------------------------------------
    // ROOM WEIGHTS
    // ---------------------------------------------------------------------

    static final Map<String, Double> WEIGHT =
            new LinkedHashMap<>();

    static {
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

    static final double WEIGHT_DEFAULT = 15.0;

    // ---------------------------------------------------------------------
    // EXTERIOR DOORS
    // ---------------------------------------------------------------------

    /**
     * Rooms that are allowed to receive an exterior door.
     *
     * Bedrooms deliberately remain excluded.
     */
    public static final java.util.Set<String> ENTRANCE =
            java.util.Set.of(
                    "livingroom",
                    "kitchen",
                    "hall",
                    "laundry",
                    "lobby",
                    "diningroom",
                    "barn",
                    "garagestorage",
                    "shed"
            );

    /**
     * Probability that a livingroom/kitchen boundary can be opened by the
     * writer.
     */
    static final double LK_OPEN = 0.88;

    // ---------------------------------------------------------------------
    // GEOMETRY CONSTANTS
    // ---------------------------------------------------------------------

    static final int MIN_ROOM = 3;

    static final int MIN_BEDROOM = 5;

    static final int MIN_CLOSET = 2;

    static final int MIN_LIVING_SIDE = 4;

    static final int MIN_KITCHEN_SIDE = 3;

    static final int HALL_MIN = 4;

    static final int HALL_MAX = 7;

    static final double ROOM_MAX_ASPECT = 4.0;

    static final int A_LIVING = 32;

    static final int A_KITCHEN = 21;

    static final int A_BED = 14;

    static final int A_BATH = 6;

    static final int BEDROOM_MAX = 8;

    static final int SECOND_BATH_AREA = 240;

    /**
     * A hall is now considered a feature of a genuinely large dwelling.
     *
     * This is intentionally much higher than the old 120/200 thresholds.
     *
     * A 20x15 = 300 tile house therefore does NOT get a hallway.
     */
    static final int LARGE_HOUSE_AREA = 420;

    /**
     * Large houses also need enough private rooms for the hall to make
     * architectural sense.
     */
    static final int LARGE_HOUSE_BEDROOMS = 3;

    // ---------------------------------------------------------------------
    // PUBLIC HELPERS
    // ---------------------------------------------------------------------

    /**
     * True when the livingroom/kitchen boundary may be opened.
     */
    public static boolean openBetween(
            String a,
            String b,
            Random rng) {

        boolean core =
                ("livingroom".equals(a)
                        && "kitchen".equals(b))
                        || ("kitchen".equals(a)
                        && "livingroom".equals(b));

        return core
                && rng.nextDouble() < LK_OPEN;
    }

    /**
     * Legacy-compatible hall probability.
     *
     * The dwelling planner no longer uses this as the primary architectural
     * decision. It is retained because other code may call it.
     */
    static double hallChance(int rooms) {

        if (rooms <= 3) {
            return 0.0;
        }

        if (rooms <= 5) {
            return 0.05;
        }

        if (rooms <= 7) {
            return 0.35;
        }

        if (rooms <= 10) {
            return 0.70;
        }

        return 0.90;
    }

    // ---------------------------------------------------------------------
    // RECIPE
    // ---------------------------------------------------------------------

    /**
     * Build the room recipe before geometry is applied.
     */
    public static List<String> recipe(
            int area,
            String occ,
            boolean outbuilding,
            Random rng) {

        if ("Agriculture".equals(occ)) {
            return List.of("barn");
        }

        if (outbuilding) {
            return List.of(
                    pick(
                            rng,
                            0.70,
                            "garagestorage",
                            "shed"
                    )
            );
        }

        if (area <= 24) {
            return List.of(
                    pick(
                            rng,
                            0.52,
                            "garagestorage",
                            pick(
                                    rng,
                                    0.50,
                                    "empty",
                                    "shed"
                            )
                    )
            );
        }

        /*
         * Every normal dwelling starts with these four core rooms.
         */
        List<String> rooms =
                new ArrayList<>(
                        List.of(
                                "livingroom",
                                "kitchen",
                                "bathroom",
                                "bedroom"
                        )
                );

        if (area > 60
                && rng.nextDouble() < 0.62) {

            rooms.add("closet");
        }

        if (area > 110
                && rng.nextDouble() < 0.16) {

            rooms.add("laundry");
        }

        if (area > 140
                && rng.nextDouble() < 0.08) {

            rooms.add("garage");
        }

        if (area > 130
                && rng.nextDouble() < 0.09) {

            rooms.add("diningroom");
        }

        if (area > 100
                && rng.nextDouble() < 0.20) {

            rooms.add("kidsbedroom");
        }

        /*
         * Additional bedrooms grow slowly.
         */
        int extra =
                Math.max(
                        0,
                        (area - 200) / 100
                );

        int beds = 1;

        for (
                int i = 0;
                i < extra && beds < BEDROOM_MAX;
                i++) {

            rooms.add(
                    rng.nextDouble() < 0.24
                            ? "kidsbedroom"
                            : "bedroom"
            );

            beds++;
        }

        /*
         * Second bathroom belongs to genuinely larger houses.
         */
        if (beds >= 4
                && area >= SECOND_BATH_AREA) {

            rooms.add("bathroom");
        }

        return rooms;
    }

    static String pick(
            Random rng,
            double p,
            String a,
            String b) {

        return rng.nextDouble() < p
                ? a
                : b;
    }

    // ---------------------------------------------------------------------
    // MAIN PLAN ENTRY
    // ---------------------------------------------------------------------

    /**
     * Main layout entry point.
     *
     * Dwellings use the hub grammar.
     * Non-dwellings use recursive splitting.
     */
    public static List<Room> plan(
            int x,
            int y,
            int w,
            int h,
            List<String> types,
            Facing facing,
            Random rng) {

        List<Room> out =
                new ArrayList<>();

        if (w < 1
                || h < 1
                || types == null
                || types.isEmpty()) {

            return out;
        }

        /*
         * A single room owns the entire footprint.
         */
        if (types.size() == 1) {

            String type =
                    types.get(0);

            out.add(
                    new Room(
                            type,
                            x,
                            y,
                            w,
                            h,
                            ENTRANCE.contains(type)
                    )
            );

            return out;
        }

        /*
         * Dwelling detection.
         */
        boolean dwelling =
                types.contains("livingroom");

        /*
         * Non-dwellings retain the generic recursive splitter.
         */
        if (!dwelling) {

            List<String> rooms =
                    trimToCapacity(
                            new ArrayList<>(types),
                            w,
                            h
                    );

            split(
                    out,
                    x,
                    y,
                    w,
                    h,
                    rooms,
                    rng
            );

            return out;
        }

        /*
         * Extremely small footprints cannot support a full dwelling grammar.
         */
        if (Math.min(w, h) < MIN_ROOM * 2) {

            String fallback =
                    pick(
                            rng,
                            0.70,
                            "garagestorage",
                            "shed"
                    );

            out.add(
                    new Room(
                            fallback,
                            x,
                            y,
                            w,
                            h,
                            ENTRANCE.contains(fallback)
                    )
            );

            return out;
        }

        List<String> rooms =
                new ArrayList<>(types);

        boolean requestedHall =
                rooms.remove("hall");

        /*
         * Remove the public anchors. The hub planner places them.
         */
        rooms.remove("livingroom");

        boolean hasKitchen =
                rooms.remove("kitchen");

        /*
         * Protect the four conceptual core rooms by trimming only the
         * secondary list.
         */
        rooms =
                trimDwellingRooms(
                        w,
                        h,
                        rooms
                );

        return hubLayout(
                out,
                x,
                y,
                w,
                h,
                facing,
                hasKitchen,
                rooms,
                requestedHall,
                rng
        );
    }

    /**
     * Backwards-compatible entry point.
     *
     * SOUTH remains the default facing.
     */
    public static List<Room> plan(
            int x,
            int y,
            int w,
            int h,
            List<String> types,
            Random rng) {

        return plan(
                x,
                y,
                w,
                h,
                types,
                Facing.SOUTH,
                rng
        );
    }

    // ---------------------------------------------------------------------
    // HUB LAYOUT
    // ---------------------------------------------------------------------

    /**
     * Authoritative dwelling grammar.
     *
     * No hallway is generated for ordinary small/medium homes.
     *
     * A hallway is only used when:
     *
     *   - explicitly requested, OR
     *   - the house is genuinely large AND has enough bedrooms.
     */
    static List<Room> hubLayout(
            List<Room> out,
            int x,
            int y,
            int w,
            int h,
            Facing facing,
            boolean hasKitchen,
            List<String> secondary,
            boolean requestedHall,
            Random rng) {

        int area =
                w * h;

        int bedroomCount =
                countBedrooms(secondary);

        boolean useHall =
                requestedHall
                        || (
                        area >= LARGE_HOUSE_AREA
                                && bedroomCount
                                >= LARGE_HOUSE_BEDROOMS
                );

        /*
         * Narrow buildings cannot support a hall.
         */
        int cross =
                crossAxis(
                        w,
                        h,
                        facing
                );

        if (useHall
                && cross
                < HALL_MIN + MIN_ROOM * 2) {

            useHall = false;
        }

        /*
         * Long narrow houses use the row grammar.
         */
        if (Math.min(w, h)
                <= MIN_ROOM + MIN_LIVING_SIDE) {

            return hubRowLayout(
                    out,
                    x,
                    y,
                    w,
                    h,
                    facing,
                    hasKitchen,
                    secondary,
                    rng
            );
        }

        if (useHall) {

            return hubHallLayout(
                    out,
                    x,
                    y,
                    w,
                    h,
                    facing,
                    hasKitchen,
                    secondary,
                    rng
            );
        }

        /*
         * This is now the normal path for the majority of houses.
         *
         * No hallway.
         */
        return hubNoHallLayout(
                out,
                x,
                y,
                w,
                h,
                facing,
                hasKitchen,
                secondary,
                rng
        );
    }

    // ---------------------------------------------------------------------
    // NO-HALL HUB
    // ---------------------------------------------------------------------

    /**
     * Small/medium dwelling hub.
     *
     * The front portion is divided into:
     *
     *     LIVINGROOM | KITCHEN
     *
     * rather than:
     *
     *     LIVINGROOM | HALL
     *
     * The back portion is divided directly into private rooms.
     *
     * This means there is no wasted circulation rectangle.
     */
    static List<Room> hubNoHallLayout(
            List<Room> out,
            int x,
            int y,
            int w,
            int h,
            Facing facing,
            boolean hasKitchen,
            List<String> secondary,
            Random rng) {

        boolean frontAlongY =
                facing == Facing.NORTH
                        || facing == Facing.SOUTH;

        int depth =
                frontAlongY
                        ? h
                        : w;

        int cross =
                frontAlongY
                        ? w
                        : h;

        /*
         * If the house is too shallow, use the linear hub.
         */
        if (depth < MIN_LIVING_SIDE + MIN_ROOM) {

            return hubRowLayout(
                    out,
                    x,
                    y,
                    w,
                    h,
                    facing,
                    hasKitchen,
                    secondary,
                    rng
            );
        }

        /*
         * The public zone occupies roughly 44-48% of the depth.
         *
         * This deliberately makes the livingroom substantial rather than
         * turning it into a narrow front strip.
         */
        int livingDepth;

        if (depth <= 8) {

            livingDepth =
                    Math.min(
                            MIN_LIVING_SIDE,
                            depth - MIN_ROOM
                    );

        } else {

            livingDepth =
                    clamp(
                            (int) Math.round(
                                    depth * 0.46
                            ),
                            MIN_LIVING_SIDE,
                            depth - MIN_ROOM
                    );
        }

        Rect front =
                frontRect(
                        x,
                        y,
                        w,
                        h,
                        facing,
                        livingDepth
                );

        Rect back =
                backRect(
                        x,
                        y,
                        w,
                        h,
                        facing,
                        livingDepth
                );

        /*
         * If there is no kitchen, the livingroom owns the entire public zone.
         */
        if (!hasKitchen
                || cross
                < MIN_LIVING_SIDE + MIN_KITCHEN_SIDE) {

            out.add(
                    new Room(
                            "livingroom",
                            front.x,
                            front.y,
                            front.w,
                            front.h,
                            true
                    )
            );

        } else {

            /*
             * The kitchen gets a meaningful portion of the frontage, but the
             * livingroom remains the larger room.
             *
             * Typical 20-wide house:
             *
             *     living 12 | kitchen 8
             *
             * rather than:
             *
             *     living 7 | hall 6 | kitchen 7
             */
            int kitchenCross =
                    clamp(
                            (int) Math.round(
                                    cross * 0.38
                            ),
                            MIN_KITCHEN_SIDE,
                            cross - MIN_LIVING_SIDE
                    );

            int livingCross =
                    cross - kitchenCross;

            /*
             * Randomly choose which side of the public zone the kitchen uses.
             * This prevents every house from having exactly the same visual
             * arrangement while preserving the grammar.
             */
            boolean kitchenFirst =
                    rng.nextBoolean();

            Rect living;
            Rect kitchen;

            if (frontAlongY) {

                if (kitchenFirst) {

                    kitchen =
                            new Rect(
                                    front.x,
                                    front.y,
                                    kitchenCross,
                                    front.h
                            );

                    living =
                            new Rect(
                                    front.x + kitchenCross,
                                    front.y,
                                    livingCross,
                                    front.h
                            );

                } else {

                    living =
                            new Rect(
                                    front.x,
                                    front.y,
                                    livingCross,
                                    front.h
                            );

                    kitchen =
                            new Rect(
                                    front.x + livingCross,
                                    front.y,
                                    kitchenCross,
                                    front.h
                            );
                }

            } else {

                if (kitchenFirst) {

                    kitchen =
                            new Rect(
                                    front.x,
                                    front.y,
                                    front.w,
                                    kitchenCross
                            );

                    living =
                            new Rect(
                                    front.x,
                                    front.y + kitchenCross,
                                    front.w,
                                    livingCross
                            );

                } else {

                    living =
                            new Rect(
                                    front.x,
                                    front.y,
                                    front.w,
                                    livingCross
                            );

                    kitchen =
                            new Rect(
                                    front.x,
                                    front.y + livingCross,
                                    front.w,
                                    kitchenCross
                            );
                }
            }

            /*
             * Livingroom is the preferred exterior entrance.
             *
             * Kitchen remains door-capable but is not automatically marked as
             * the front entrance.
             */
            out.add(
                    new Room(
                            "livingroom",
                            living.x,
                            living.y,
                            living.w,
                            living.h,
                            true
                    )
            );

            out.add(
                    new Room(
                            "kitchen",
                            kitchen.x,
                            kitchen.y,
                            kitchen.w,
                            kitchen.h,
                            false
                    )
            );
        }

        /*
         * No private rooms means the public zone plus remaining footprint
         * cannot be left unfilled.
         */
        if (secondary.isEmpty()) {

            /*
             * This should only occur for unusual externally supplied recipes.
             * Give the remaining zone to the kitchen if possible, otherwise
             * leave the public geometry responsible for it.
             */
            if (back.w > 0 && back.h > 0) {

                String filler =
                        hasKitchen
                                ? "kitchen"
                                : "livingroom";

                out.add(
                        new Room(
                                filler,
                                back.x,
                                back.y,
                                back.w,
                                back.h,
                                false
                        )
                );
            }

            return out;
        }

        /*
         * Private rooms are packed directly across the back of the house.
         *
         * This is important:
         *
         *     living/kitchen
         *     ----------------
         *     bedroom | bath | bedroom
         *
         * Every private room can directly touch the public zone. There is no
         * hidden corridor rectangle consuming tiles.
         */
        packPrivateZone(
                out,
                back,
                secondary,
                frontAlongY,
                rng
        );

        return out;
    }

    /**
     * Pack private rooms across the rear zone.
     *
     * All rooms share the public/private boundary. This deliberately creates
     * direct adjacency instead of building a hallway.
     */
    static void packPrivateZone(
            List<Room> out,
            Rect region,
            List<String> rooms,
            boolean frontAlongY,
            Random rng) {

        if (rooms.isEmpty()
                || region.w <= 0
                || region.h <= 0) {

            return;
        }

        List<String> local =
                new ArrayList<>(rooms);

        int cross =
                frontAlongY
                        ? region.w
                        : region.h;

        int capacity =
                Math.max(
                        1,
                        cross / MIN_ROOM
                );

        local =
                trimRowRooms(
                        local,
                        capacity
                );

        if (local.isEmpty()) {
            return;
        }

        /*
         * Preserve a useful ordering:
         *
         * bathroom/service rooms tend toward the kitchen side,
         * bedrooms occupy the remaining larger pieces.
         *
         * We still add a small amount of variation.
         */
        reorderPrivateRooms(
                local,
                rng
        );

        packAcross(
                out,
                region,
                local,
                frontAlongY
        );
    }

    /**
     * Mild private-room ordering.
     *
     * We do not randomize completely because room topology matters more than
     * visual noise.
     */
    static void reorderPrivateRooms(
            List<String> rooms,
            Random rng) {

        if (rooms.size() <= 1) {
            return;
        }

        /*
         * Find a bathroom and move it near the beginning.
         */
        int bath =
                rooms.indexOf("bathroom");

        if (bath > 0) {

            String value =
                    rooms.remove(bath);

            rooms.add(
                    Math.min(
                            1,
                            rooms.size()
                    ),
                    value
            );
        }

        /*
         * Occasionally reverse the remaining orientation so houses do not
         * all read identically.
         */
        if (rng.nextDouble() < 0.35) {

            Collections.reverse(rooms);
        }
    }

    /**
     * Pack rooms across the width of a rear region.
     *
     * Every room spans the entire depth of the rear zone. This makes each
     * private room directly accessible from the public zone.
     */
    static void packAcross(
            List<Room> out,
            Rect region,
            List<String> rooms,
            boolean splitAlongX) {

        if (rooms.isEmpty()) {
            return;
        }

        int length =
                splitAlongX
                        ? region.w
                        : region.h;

        int n =
                rooms.size();

        if (length < n * MIN_ROOM) {

            rooms =
                    trimRowRooms(
                            new ArrayList<>(rooms),
                            Math.max(
                                    1,
                                    length / MIN_ROOM
                            )
                    );

            n =
                    rooms.size();
        }

        if (n == 0) {
            return;
        }

        int[] sizes =
                allocateWeightedSizes(
                        rooms,
                        length,
                        MIN_ROOM
                );

        int at = 0;

        for (int i = 0; i < n; i++) {

            int size =
                    sizes[i];

            if (splitAlongX) {

                out.add(
                        new Room(
                                rooms.get(i),
                                region.x + at,
                                region.y,
                                size,
                                region.h,
                                false
                        )
                );

            } else {

                out.add(
                        new Room(
                                rooms.get(i),
                                region.x,
                                region.y + at,
                                region.w,
                                size,
                                false
                        )
                );
            }

            at += size;
        }
    }

    /**
     * Allocate a length among rooms.
     */
    static int[] allocateWeightedSizes(
            List<String> rooms,
            int length,
            int minimum) {

        int n =
                rooms.size();

        int[] sizes =
                new int[n];

        int minimumUsed =
                n * minimum;

        if (minimumUsed > length) {

            int capacity =
                    Math.max(
                            1,
                            length / Math.max(1, minimum)
                    );

            n =
                    Math.min(
                            n,
                            capacity
                    );

            sizes =
                    new int[n];

            minimumUsed =
                    n * minimum;
        }

        int extra =
                Math.max(
                        0,
                        length - minimumUsed
                );

        double total =
                weightOf(
                        rooms.subList(
                                0,
                                n
                        )
                );

        int distributed = 0;

        for (int i = 0; i < n; i++) {

            int add;

            if (i == n - 1) {

                add =
                        extra
                                - distributed;

            } else {

                add =
                        (int) Math.round(
                                extra
                                        * WEIGHT.getOrDefault(
                                                rooms.get(i),
                                                WEIGHT_DEFAULT
                                        )
                                        / total
                        );

                add =
                        Math.max(
                                0,
                                add
                        );
            }

            sizes[i] =
                    minimum + add;

            distributed += add;
        }

        int actual = 0;

        for (int size : sizes) {
            actual += size;
        }

        if (n > 0) {

            sizes[n - 1] +=
                    length - actual;
        }

        return sizes;
    }

    // ---------------------------------------------------------------------
    // HALL LAYOUT FOR LARGE HOUSES
    // ---------------------------------------------------------------------

    /**
     * Hall-based layout reserved for genuinely large houses.
     *
     * The hall becomes a spine only when the house has enough scale to justify
     * spending floor area on circulation.
     */
    static List<Room> hubHallLayout(
            List<Room> out,
            int x,
            int y,
            int w,
            int h,
            Facing facing,
            boolean hasKitchen,
            List<String> secondary,
            Random rng) {

        boolean frontAlongY =
                facing == Facing.NORTH
                        || facing == Facing.SOUTH;

        int depth =
                frontAlongY
                        ? h
                        : w;

        int cross =
                frontAlongY
                        ? w
                        : h;

        int hallCross =
                clamp(
                        (int) Math.round(
                                cross * 0.30
                        ),
                        HALL_MIN,
                        HALL_MAX
                );

        hallCross =
                Math.min(
                        hallCross,
                        cross - MIN_ROOM * 2
                );

        if (hallCross < HALL_MIN) {

            return hubNoHallLayout(
                    out,
                    x,
                    y,
                    w,
                    h,
                    facing,
                    hasKitchen,
                    secondary,
                    rng
            );
        }

        int leftCross =
                (cross - hallCross) / 2;

        int rightCross =
                cross - hallCross - leftCross;

        if (leftCross < MIN_ROOM
                || rightCross < MIN_ROOM) {

            return hubNoHallLayout(
                    out,
                    x,
                    y,
                    w,
                    h,
                    facing,
                    hasKitchen,
                    secondary,
                    rng
            );
        }

        Rect hall =
                hallRect(
                        x,
                        y,
                        w,
                        h,
                        facing,
                        hallCross,
                        leftCross
                );

        /*
         * Hall touches the front exterior.
         */
        out.add(
                new Room(
                        "hall",
                        hall.x,
                        hall.y,
                        hall.w,
                        hall.h,
                        true
                )
        );

        Rect sideA;
        Rect sideB;

        if (frontAlongY) {

            sideA =
                    new Rect(
                            x,
                            y,
                            leftCross,
                            h
                    );

            sideB =
                    new Rect(
                            x + leftCross + hallCross,
                            y,
                            rightCross,
                            h
                    );

        } else {

            sideA =
                    new Rect(
                            x,
                            y,
                            w,
                            leftCross
                    );

            sideB =
                    new Rect(
                            x,
                            y + leftCross + hallCross,
                            w,
                            rightCross
                    );
        }

        /*
         * Livingroom is front of side A.
         */
        List<String> sideARooms =
                new ArrayList<>();

        sideARooms.add("livingroom");

        List<String> privateRooms =
                new ArrayList<>(
                        secondary
                );

        /*
         * Kitchen is front of side B.
         */
        List<String> sideBRooms =
                new ArrayList<>();

        if (hasKitchen) {
            sideBRooms.add("kitchen");
        }

        /*
         * Distribute bedrooms between the two wings.
         */
        int desiredA =
                Math.max(
                        1,
                        1
                                + (
                                countBedrooms(
                                        privateRooms
                                ) + 1
                        ) / 2
                );

        for (
                int i = 0;
                i < privateRooms.size()
                        && sideARooms.size()
                        < desiredA;
        ) {

            String type =
                    privateRooms.get(i);

            if (isBedroom(type)) {

                sideARooms.add(type);

                privateRooms.remove(i);

            } else {

                i++;
            }
        }

        sideBRooms.addAll(
                privateRooms
        );

        /*
         * Ensure both wings remain physically packable.
         */
        trimSideRoomCount(
                sideARooms,
                depth
        );

        trimSideRoomCount(
                sideBRooms,
                depth
        );

        packSide(
                out,
                sideA,
                sideARooms,
                facing,
                true,
                rng
        );

        packSide(
                out,
                sideB,
                sideBRooms,
                facing,
                false,
                rng
        );

        return out;
    }

    /**
     * Very narrow dwelling fallback.
     *
     * This preserves the hub sequence without creating a corridor.
     */
    static List<Room> hubRowLayout(
            List<Room> out,
            int x,
            int y,
            int w,
            int h,
            Facing facing,
            boolean hasKitchen,
            List<String> secondary,
            Random rng) {

        boolean alongX =
                w >= h;

        int longSide =
                alongX
                        ? w
                        : h;

        List<String> rooms =
                new ArrayList<>();

        rooms.add("livingroom");

        if (hasKitchen) {
            rooms.add("kitchen");
        }

        rooms.addAll(
                secondary
        );

        int capacity =
                Math.max(
                        1,
                        longSide / MIN_ROOM
                );

        rooms =
                trimRowRooms(
                        rooms,
                        capacity
                );

        boolean reverse =
                (alongX && facing == Facing.EAST)
                        || (
                        !alongX
                                && facing == Facing.SOUTH
                );

        if (reverse) {
            Collections.reverse(rooms);
        }

        packRow(
                out,
                x,
                y,
                w,
                h,
                rooms,
                alongX,
                rng
        );

        return out;
    }

    // ---------------------------------------------------------------------
    // HALL SIDE PACKING
    // ---------------------------------------------------------------------

    static void packSide(
            List<Room> out,
            Rect side,
            List<String> rooms,
            Facing facing,
            boolean livingSide,
            Random rng) {

        if (rooms.isEmpty()) {
            return;
        }

        boolean alongY =
                facing == Facing.NORTH
                        || facing == Facing.SOUTH;

        int length =
                alongY
                        ? side.h
                        : side.w;

        if (length
                < rooms.size() * MIN_ROOM) {

            rooms =
                    trimRowRooms(
                            new ArrayList<>(rooms),
                            Math.max(
                                    1,
                                    length / MIN_ROOM
                            )
                    );
        }

        List<String> ordered =
                new ArrayList<>(
                        rooms
                );

        /*
         * Put the first logical room on the road-facing end.
         */
        boolean reverse =
                facing == Facing.SOUTH
                        || facing == Facing.EAST;

        if (reverse) {
            Collections.reverse(ordered);
        }

        packLinear(
                out,
                side,
                ordered,
                alongY,
                true,
                rng
        );
    }

    /**
     * Linear room packing.
     */
    static void packLinear(
            List<Room> out,
            Rect region,
            List<String> rooms,
            boolean alongY,
            boolean firstRoomMayTakeDoor,
            Random rng) {

        if (rooms.isEmpty()
                || region.w <= 0
                || region.h <= 0) {

            return;
        }

        int length =
                alongY
                        ? region.h
                        : region.w;

        if (length
                < rooms.size() * MIN_ROOM) {

            rooms =
                    trimRowRooms(
                            new ArrayList<>(rooms),
                            Math.max(
                                    1,
                                    length / MIN_ROOM
                            )
                    );
        }

        if (rooms.isEmpty()) {
            return;
        }

        int[] sizes =
                allocateWeightedSizes(
                        rooms,
                        length,
                        MIN_ROOM
                );

        int at = 0;

        for (int i = 0;
             i < rooms.size();
             i++) {

            String type =
                    rooms.get(i);

            int size =
                    sizes[i];

            boolean entrance =
                    firstRoomMayTakeDoor
                            && i == 0
                            && ENTRANCE.contains(type);

            if (alongY) {

                out.add(
                        new Room(
                                type,
                                region.x,
                                region.y + at,
                                region.w,
                                size,
                                entrance
                        )
                );

            } else {

                out.add(
                        new Room(
                                type,
                                region.x + at,
                                region.y,
                                size,
                                region.h,
                                entrance
                        )
                );
            }

            at += size;
        }
    }

    static void packRow(
            List<Room> out,
            int x,
            int y,
            int w,
            int h,
            List<String> rooms,
            boolean alongX,
            Random rng) {

        if (rooms.isEmpty()) {
            return;
        }

        Rect region =
                new Rect(
                        x,
                        y,
                        w,
                        h
                );

        packLinear(
                out,
                region,
                rooms,
                !alongX,
                true,
                rng
        );
    }

    // ---------------------------------------------------------------------
    // RECTANGLE HELPERS
    // ---------------------------------------------------------------------

    static Rect frontRect(
            int x,
            int y,
            int w,
            int h,
            Facing facing,
            int depth) {

        return switch (facing) {

            case NORTH ->
                    new Rect(
                            x,
                            y,
                            w,
                            depth
                    );

            case SOUTH ->
                    new Rect(
                            x,
                            y + h - depth,
                            w,
                            depth
                    );

            case WEST ->
                    new Rect(
                            x,
                            y,
                            depth,
                            h
                    );

            case EAST ->
                    new Rect(
                            x + w - depth,
                            y,
                            depth,
                            h
                    );
        };
    }

    static Rect backRect(
            int x,
            int y,
            int w,
            int h,
            Facing facing,
            int frontDepth) {

        return switch (facing) {

            case NORTH ->
                    new Rect(
                            x,
                            y + frontDepth,
                            w,
                            h - frontDepth
                    );

            case SOUTH ->
                    new Rect(
                            x,
                            y,
                            w,
                            h - frontDepth
                    );

            case WEST ->
                    new Rect(
                            x + frontDepth,
                            y,
                            w - frontDepth,
                            h
                    );

            case EAST ->
                    new Rect(
                            x,
                            y,
                            w - frontDepth,
                            h
                    );
        };
    }

    static Rect hallRect(
            int x,
            int y,
            int w,
            int h,
            Facing facing,
            int hallCross,
            int leftCross) {

        boolean alongY =
                facing == Facing.NORTH
                        || facing == Facing.SOUTH;

        if (alongY) {

            return new Rect(
                    x + leftCross,
                    y,
                    hallCross,
                    h
            );
        }

        return new Rect(
                x,
                y + leftCross,
                w,
                hallCross
        );
    }

    static final class Rect {

        final int x;
        final int y;
        final int w;
        final int h;

        Rect(
                int x,
                int y,
                int w,
                int h) {

            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        int area() {
            return w * h;
        }
    }

    // ---------------------------------------------------------------------
    // ROOM LIST MANAGEMENT
    // ---------------------------------------------------------------------

    static int countBedrooms(
            List<String> rooms) {

        int n = 0;

        for (String type : rooms) {

            if (isBedroom(type)) {
                n++;
            }
        }

        return n;
    }

    static boolean isBedroom(
            String type) {

        return "bedroom".equals(type)
                || "kidsbedroom".equals(type);
    }

    static int findBedroomIndex(
            List<String> rooms) {

        for (int i = 0;
             i < rooms.size();
             i++) {

            if (isBedroom(rooms.get(i))) {
                return i;
            }
        }

        return -1;
    }

    static boolean sideAHasRoom(
            List<String> rooms,
            int depth) {

        return rooms.size() * MIN_ROOM
                < depth;
    }

    static boolean roomCountNeedsMoreCapacity(
            List<String> rooms,
            int depth) {

        return rooms.size() * MIN_ROOM
                > depth;
    }

    /**
     * Remove optional rooms first.
     */
    static void trimSideRoomCount(
            List<String> rooms,
            int depth) {

        int capacity =
                Math.max(
                        1,
                        depth / MIN_ROOM
                );

        while (rooms.size() > capacity) {

            int remove =
                    findOptionalRoomFromEnd(
                            rooms
                    );

            if (remove < 0) {

                remove =
                        rooms.size() - 1;
            }

            rooms.remove(remove);
        }
    }

    /**
     * Trim dwelling secondary rooms.
     *
     * The four core rooms are not passed into this method, so they cannot be
     * accidentally deleted here.
     */
    static List<String> trimDwellingRooms(
            int w,
            int h,
            List<String> secondary) {

        List<String> rooms =
                new ArrayList<>(
                        secondary
                );

        int capacity =
                Math.max(
                        1,
                        (w / MIN_ROOM)
                                * (h / MIN_ROOM)
                );

        int secondaryCapacity =
                Math.max(
                        0,
                        capacity - 4
                );

        while (
                rooms.size()
                        > secondaryCapacity
        ) {

            int remove =
                    findOptionalRoomFromEnd(
                            rooms
                    );

            if (remove < 0) {
                remove =
                        rooms.size() - 1;
            }

            rooms.remove(remove);
        }

        return rooms;
    }

    static int findOptionalRoomFromEnd(
            List<String> rooms) {

        for (int i = rooms.size() - 1;
             i >= 0;
             i--) {

            String type =
                    rooms.get(i);

            if ("closet".equals(type)
                    || "laundry".equals(type)
                    || "garage".equals(type)
                    || "diningroom".equals(type)
                    || "office".equals(type)
                    || "janitor".equals(type)) {

                return i;
            }
        }

        return -1;
    }

    static List<String> trimRowRooms(
            List<String> rooms,
            int capacity) {

        if (capacity <= 0) {
            return new ArrayList<>();
        }

        while (rooms.size() > capacity) {

            int remove =
                    findOptionalRoomFromEnd(
                            rooms
                    );

            if (remove < 0) {
                remove =
                        rooms.size() - 1;
            }

            rooms.remove(remove);
        }

        return rooms;
    }

    static List<String> trimToCapacity(
            List<String> rooms,
            int w,
            int h) {

        int capacity =
                Math.max(
                        1,
                        (w / MIN_ROOM)
                                * (h / MIN_ROOM)
                );

        return trimRowRooms(
                new ArrayList<>(rooms),
                capacity
        );
    }

    // ---------------------------------------------------------------------
    // GENERIC RECURSIVE SPLITTER
    // ---------------------------------------------------------------------

    /**
     * Generic weighted recursive splitter.
     *
     * Used by non-dwellings.
     *
     * The dwelling hub itself is NEVER produced by this method.
     */
    static void split(
            List<Room> out,
            int x,
            int y,
            int w,
            int h,
            List<String> rooms,
            Random rng) {

        if (rooms == null
                || rooms.isEmpty()
                || w <= 0
                || h <= 0) {

            return;
        }

        if (rooms.size() == 1) {

            out.add(
                    new Room(
                            rooms.get(0),
                            x,
                            y,
                            w,
                            h
                    )
            );

            return;
        }

        int capacity =
                Math.max(
                        1,
                        (w / MIN_ROOM)
                                * (h / MIN_ROOM)
                );

        List<String> local =
                new ArrayList<>(
                        rooms
                );

        while (local.size() > capacity) {
            local.remove(
                    local.size() - 1
            );
        }

        if (local.size() == 1) {

            out.add(
                    new Room(
                            local.get(0),
                            x,
                            y,
                            w,
                            h
                    )
            );

            return;
        }

        int half =
                local.size() / 2;

        List<String> a =
                new ArrayList<>(
                        local.subList(
                                0,
                                half
                        )
                );

        List<String> b =
                new ArrayList<>(
                        local.subList(
                                half,
                                local.size()
                        )
                );

        double wa =
                weightOf(a);

        double wb =
                weightOf(b);

        double frac =
                wa / (wa + wb);

        int vCut =
                (int) Math.round(
                        w * frac
                );

        boolean vOk =
                w >= MIN_ROOM * 2
                        && vCut >= MIN_ROOM
                        && w - vCut >= MIN_ROOM;

        int hCut =
                (int) Math.round(
                        h * frac
                );

        boolean hOk =
                h >= MIN_ROOM * 2
                        && hCut >= MIN_ROOM
                        && h - hCut >= MIN_ROOM;

        if (!vOk && !hOk) {

            out.add(
                    new Room(
                            local.get(0),
                            x,
                            y,
                            w,
                            h
                    )
            );

            return;
        }

        if (vOk) {

            vCut =
                    clamp(
                            vCut,
                            MIN_ROOM,
                            w - MIN_ROOM
                    );
        }

        if (hOk) {

            hCut =
                    clamp(
                            hCut,
                            MIN_ROOM,
                            h - MIN_ROOM
                    );
        }

        boolean vertical;

        if (vOk && hOk) {

            double vScore =
                    Math.max(
                            aspect(
                                    vCut,
                                    h
                            ),
                            aspect(
                                    w - vCut,
                                    h
                            )
                    );

            double hScore =
                    Math.max(
                            aspect(
                                    w,
                                    hCut
                            ),
                            aspect(
                                    w,
                                    h - hCut
                            )
                    );

            vertical =
                    vScore <= hScore;

        } else {

            vertical = vOk;
        }

        if (vertical) {

            split(
                    out,
                    x,
                    y,
                    vCut,
                    h,
                    a,
                    rng
            );

            split(
                    out,
                    x + vCut,
                    y,
                    w - vCut,
                    h,
                    b,
                    rng
            );

        } else {

            split(
                    out,
                    x,
                    y,
                    w,
                    hCut,
                    a,
                    rng
            );

            split(
                    out,
                    x,
                    y + hCut,
                    w,
                    h - hCut,
                    b,
                    rng
            );
        }
    }

    // ---------------------------------------------------------------------
    // MATH / GEOMETRY
    // ---------------------------------------------------------------------

    static int crossAxis(
            int w,
            int h,
            Facing facing) {

        return switch (facing) {

            case NORTH, SOUTH ->
                    w;

            case EAST, WEST ->
                    h;
        };
    }

    static int depthAxis(
            int w,
            int h,
            Facing facing) {

        return switch (facing) {

            case NORTH, SOUTH ->
                    h;

            case EAST, WEST ->
                    w;
        };
    }

    static int clamp(
            int v,
            int lo,
            int hi) {

        if (hi < lo) {
            return lo;
        }

        return Math.max(
                lo,
                Math.min(
                        hi,
                        v
                )
        );
    }

    static double aspect(
            int w,
            int h) {

        int shortSide =
                Math.max(
                        1,
                        Math.min(
                                w,
                                h
                        )
                );

        int longSide =
                Math.max(
                        w,
                        h
                );

        return longSide
                / (double) shortSide;
    }

    static double weightOf(
            List<String> rooms) {

        double sum = 0.0;

        for (String room : rooms) {

            sum +=
                    WEIGHT.getOrDefault(
                            room,
                            WEIGHT_DEFAULT
                    );
        }

        return sum <= 0.0
                ? 1.0
                : sum;
    }

    static double weightSum(
            List<String> rooms) {

        double sum = 0.0;

        for (String room : rooms) {

            sum +=
                    WEIGHT.getOrDefault(
                            room,
                            WEIGHT_DEFAULT
                    );
        }

        return sum;
    }

    static int ceilDiv(
            int a,
            int b) {

        return (
                a + b - 1
        ) / Math.max(
                1,
                b
        );
    }

    static int minRooms(
            int w,
            int h) {

        int longSide =
                Math.max(
                        w,
                        h
                );

        int shortSide =
                Math.max(
                        1,
                        Math.min(
                                w,
                                h
                        )
                );

        int n =
                (int) Math.ceil(
                        longSide
                                / (
                                shortSide
                                        * ROOM_MAX_ASPECT
                        )
                );

        return Math.max(
                1,
                n
        );
    }

    // ---------------------------------------------------------------------
    // SELF TEST
    // ---------------------------------------------------------------------

    public static void main(
            String[] args) {

        int fail = 0;

        Random rng =
                new Random(42);

        int[][] cases = {
                {12, 10},
                {16, 10},
                {24, 7},
                {15, 14},
                {22, 14},
                {20, 17},
                {5, 4},
                {30, 6},
                {4, 3},
                {18, 12},
                {25, 16},
                {30, 20},
                {40, 20}
        };

        /*
         * Basic tiling test.
         */
        for (Facing facing :
                Facing.values()) {

            for (int[] c :
                    cases) {

                int w = c[0];
                int h = c[1];

                List<String> types =
                        recipe(
                                w * h,
                                "Residential",
                                false,
                                rng
                        );

                List<Room> rooms =
                        plan(
                                0,
                                0,
                                w,
                                h,
                                types,
                                facing,
                                rng
                        );

                fail +=
                        check(
                                facing
                                        + " "
                                        + w
                                        + "x"
                                        + h,
                                w,
                                h,
                                rooms
                        );
            }
        }

        /*
         * Livingroom must always touch the road/front.
         */
        int badFront = 0;

        for (Facing facing :
                Facing.values()) {

            for (int i = 0;
                 i < 100;
                 i++) {

                Random r =
                        new Random(
                                i + 1000
                        );

                List<String> types =
                        recipe(
                                250,
                                "Residential",
                                false,
                                r
                        );

                List<Room> rooms =
                        plan(
                                0,
                                0,
                                20,
                                15,
                                types,
                                facing,
                                r
                        );

                Room living = null;

                for (Room room : rooms) {

                    if ("livingroom"
                            .equals(room.type())) {

                        living = room;
                        break;
                    }
                }

                if (living == null) {

                    badFront++;
                    continue;
                }

                boolean onFront =
                        switch (facing) {

                            case NORTH ->
                                    living.y() == 0;

                            case SOUTH ->
                                    living.y()
                                            + living.h()
                                            == 15;

                            case WEST ->
                                    living.x() == 0;

                            case EAST ->
                                    living.x()
                                            + living.w()
                                            == 20;
                        };

                if (!onFront) {
                    badFront++;
                }
            }
        }

        System.out.printf(
                "%-35s %s  %d misplaced%n",
                "livingroom at front",
                badFront == 0
                        ? "PASS"
                        : "FAIL",
                badFront
        );

        if (badFront > 0) {
            fail++;
        }

        /*
         * Bedrooms must never be marked as exterior-door rooms.
         */
        int badBedroomDoors = 0;
        int markedDoors = 0;

        for (Facing facing :
                Facing.values()) {

            for (int i = 0;
                 i < 100;
                 i++) {

                Random r =
                        new Random(
                                i + 5000
                        );

                List<String> types =
                        recipe(
                                300,
                                "Residential",
                                false,
                                r
                        );

                List<Room> rooms =
                        plan(
                                0,
                                0,
                                20,
                                15,
                                types,
                                facing,
                                r
                        );

                for (Room room :
                        rooms) {

                    if (!room.entrance()) {
                        continue;
                    }

                    markedDoors++;

                    if (!room.canTakeDoor()) {
                        badBedroomDoors++;
                    }
                }
            }
        }

        System.out.printf(
                "%-35s %s  %d bad of %d marked%n",
                "no bedroom entrances",
                badBedroomDoors == 0
                        ? "PASS"
                        : "FAIL",
                badBedroomDoors,
                markedDoors
        );

        if (badBedroomDoors > 0) {
            fail++;
        }

        /*
         * Agriculture must always produce a barn.
         */
        List<Room> barn =
                plan(
                        0,
                        0,
                        12,
                        11,
                        recipe(
                                132,
                                "Agriculture",
                                false,
                                rng
                        ),
                        Facing.SOUTH,
                        rng
                );

        boolean barnOk =
                barn.size() == 1
                        && "barn".equals(
                                barn.get(0).type()
                        );

        System.out.printf(
                "%-35s %s  %s%n",
                "Agriculture -> barn",
                barnOk
                        ? "PASS"
                        : "FAIL",
                barn
        );

        if (!barnOk) {
            fail++;
        }

        /*
         * Dwelling recipe must always contain a bathroom.
         */
        int noBath = 0;

        for (int i = 0;
             i < 500;
             i++) {

            List<String> types =
                    recipe(
                            100,
                            "Residential",
                            false,
                            new Random(i)
                    );

            if (!types.contains("bathroom")) {
                noBath++;
            }
        }

        System.out.printf(
                "%-35s %s  %d without%n",
                "dwelling always has bath",
                noBath == 0
                        ? "PASS"
                        : "FAIL",
                noBath
        );

        if (noBath > 0) {
            fail++;
        }

        /*
         * -------------------------------------------------------------
         * NEW HUB TEST
         * -------------------------------------------------------------
         *
         * A 300-tile house is deliberately NOT supposed to receive a hall.
         *
         * This is the architectural change from the previous version.
         */
        int unwantedSmallHalls = 0;

        for (int i = 0;
             i < 200;
             i++) {

            Random r =
                    new Random(
                            i + 9000
                    );

            List<String> types =
                    recipe(
                            300,
                            "Residential",
                            false,
                            r
                    );

            List<Room> rooms =
                    plan(
                            0,
                            0,
                            20,
                            15,
                            types,
                            Facing.SOUTH,
                            r
                    );

            for (Room room :
                    rooms) {

                if ("hall".equals(
                        room.type())) {

                    unwantedSmallHalls++;
                }
            }
        }

        System.out.printf(
                "%-35s %s  %d halls%n",
                "300-tile house has NO hall",
                unwantedSmallHalls == 0
                        ? "PASS"
                        : "FAIL",
                unwantedSmallHalls
        );

        if (unwantedSmallHalls > 0) {
            fail++;
        }

        /*
         * A genuinely large house should receive a hall.
         *
         * 500 tiles produces enough bedrooms to justify one.
         */
        int missingLargeHalls = 0;

        for (int i = 0;
             i < 200;
             i++) {

            Random r =
                    new Random(
                            i + 12000
                    );

            List<String> types =
                    recipe(
                            500,
                            "Residential",
                            false,
                            r
                    );

            List<Room> rooms =
                    plan(
                            0,
                            0,
                            25,
                            20,
                            types,
                            Facing.SOUTH,
                            r
                    );

            boolean hall = false;

            for (Room room :
                    rooms) {

                if ("hall".equals(
                        room.type())) {

                    hall = true;
                    break;
                }
            }

            if (!hall) {
                missingLargeHalls++;
            }
        }

        System.out.printf(
                "%-35s %s  %d missing%n",
                "large house gets hall",
                missingLargeHalls == 0
                        ? "PASS"
                        : "FAIL",
                missingLargeHalls
        );

        if (missingLargeHalls > 0) {
            fail++;
        }

        /*
         * Check that the 300-tile no-hall layout actually contains the core
         * rooms.
         */
        int missingCore = 0;

        for (int i = 0;
             i < 200;
             i++) {

            Random r =
                    new Random(
                            i + 15000
                    );

            List<String> types =
                    recipe(
                            300,
                            "Residential",
                            false,
                            r
                    );

            List<Room> rooms =
                    plan(
                            0,
                            0,
                            20,
                            15,
                            types,
                            Facing.SOUTH,
                            r
                    );

            boolean living = false;
            boolean kitchen = false;
            boolean bedroom = false;
            boolean bath = false;

            for (Room room :
                    rooms) {

                switch (room.type()) {

                    case "livingroom" ->
                            living = true;

                    case "kitchen" ->
                            kitchen = true;

                    case "bedroom",
                         "kidsbedroom" ->
                            bedroom = true;

                    case "bathroom" ->
                            bath = true;

                    default -> {
                    }
                }
            }

            if (!living
                    || !kitchen
                    || !bedroom
                    || !bath) {

                missingCore++;
            }
        }

        System.out.printf(
                "%-35s %s  %d missing%n",
                "300-tile house has core rooms",
                missingCore == 0
                        ? "PASS"
                        : "FAIL",
                missingCore
        );

        if (missingCore > 0) {
            fail++;
        }

        /*
         * Aspect check.
         *
         * Halls are intentionally excluded because a hall is allowed to be
         * elongated. The private-room packing should not create extreme
         * corridor-shaped bedrooms/bathrooms.
         */
        double worst = 0.0;
        String worstAt = "";

        for (Facing facing :
                Facing.values()) {

            for (int[] c :
                    cases) {

                int w = c[0];
                int h = c[1];

                Random r =
                        new Random(
                                w * 71L
                                        + h * 31L
                        );

                List<String> types =
                        recipe(
                                w * h,
                                "Residential",
                                false,
                                r
                        );

                List<Room> rooms =
                        plan(
                                0,
                                0,
                                w,
                                h,
                                types,
                                facing,
                                r
                        );

                for (Room room :
                        rooms) {

                    if ("hall".equals(
                            room.type())) {

                        continue;
                    }

                    double a =
                            aspect(
                                    room.w(),
                                    room.h()
                            );

                    if (a > worst) {

                        worst = a;

                        worstAt =
                                facing
                                        + " "
                                        + w
                                        + "x"
                                        + h
                                        + " "
                                        + room;
                    }
                }
            }
        }

        System.out.printf(
                "%-35s %s  worst %.1f  %s%n",
                "no corridor-shaped rooms",
                worst <= 4.5
                        ? "PASS"
                        : "FAIL",
                worst,
                worstAt
        );

        if (worst > 4.5) {
            fail++;
        }

        System.out.println();

        if (fail == 0) {

            System.out.println(
                    "all BuildingPlan hub-layout tests pass"
            );

        } else {

            System.out.println(
                    fail
                            + " BuildingPlan tests FAILED"
            );

            System.exit(1);
        }
    }

    // ---------------------------------------------------------------------
    // FOOTPRINT CHECK
    // ---------------------------------------------------------------------

    /**
     * Exact footprint tiling check.
     */
    static int check(
            String label,
            int w,
            int h,
            List<Room> rooms) {

        int[][] grid =
                new int[w][h];

        int overlaps = 0;

        for (Room room :
                rooms) {

            for (
                    int i = room.x();
                    i < room.x() + room.w();
                    i++
            ) {

                for (
                        int j = room.y();
                        j < room.y() + room.h();
                        j++
                ) {

                    if (
                            i < 0
                                    || j < 0
                                    || i >= w
                                    || j >= h
                    ) {

                        overlaps++;
                        continue;
                    }

                    if (grid[i][j]++ > 0) {
                        overlaps++;
                    }
                }
            }
        }

        int gaps = 0;

        for (int[] column :
                grid) {

            for (int value :
                    column) {

                if (value == 0) {
                    gaps++;
                }
            }
        }

        int invalid = 0;

        for (Room room :
                rooms) {

            if (
                    room.w() < 1
                            || room.h() < 1
            ) {

                invalid++;
            }
        }

        boolean ok =
                !rooms.isEmpty()
                        && gaps == 0
                        && overlaps == 0
                        && invalid == 0;

        System.out.printf(
                "%-35s %s  %d rooms, %d gaps, %d overlaps%n",
                label,
                ok ? "PASS" : "FAIL",
                rooms.size(),
                gaps,
                overlaps
        );

        if (!ok) {

            for (Room room :
                    rooms) {

                System.out.println(
                        "      " + room
                );
            }

            return 1;
        }

        return 0;
    }
}
