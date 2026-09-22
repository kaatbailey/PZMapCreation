# Authoring FurnitureSets

How to add new measured vanilla room layouts to `FurnitureSet.java`.

---

## What a FurnitureSet Is

A `FurnitureSet` is a snapshot of a real vanilla room arrangement — a group of tiles at fixed offsets from a top-left origin point. The stamper in `FurniturePlacer.stampLayout()` picks a set appropriate for the room type and size, then tiles it across the floor with aisle gaps between copies.

Think of it as a rubber stamp. You measure it once from a real vanilla building, author it in Java, and the system places it wherever it fits.

---

## The API

```java
new FurnitureSet.Builder(
        "human_readable_name",   // used in logs and debugging
        width,                   // floor footprint width in tiles (east-west)
        height,                  // floor footprint height in tiles (north-south)
        minRoomDim,              // minimum of both room axes before set is eligible
        "roomType1",             // room type strings from BuildingPlan / FurnitureProfile
        "roomType2"              // add as many as needed
    )
    .floor(dx, dy, "tile_name")  // a normal floor object
    .wallN(dx, dy, "tile_name")  // mounted on the NORTH wall (wall square is one row north of dy)
    .wallS(dx, dy, "tile_name")  // mounted on SOUTH wall
    .wallW(dx, dy, "tile_name")  // mounted on WEST wall
    .wallE(dx, dy, "tile_name")  // mounted on EAST wall
    .top  (dx, dy, "tile_name")  // stacked on a surface tile (IsTableTop appliances)
    .wallRun()                   // optional flag: see Wall-Run Sets below
    .build();
```

### dx / dy coordinates
- `(0, 0)` = top-left corner of the stamp's floor footprint
- `dx` increases east (positive = right)
- `dy` increases south (positive = down)
- This matches the PZ tile system: x increases east, y increases south

### width / height
The bounding box of the **floor** footprint only. Wall-mounted tiles don't count — they land on the wall square outside the bounding box and need no floor clearance.

### minRoomDim
Both room axes must be at least this value before the set is considered. Rule of thumb: `max(width, height) + 2`. That guarantees at least one tile of clearance on every side. For very small rooms use 3 or 4.

---

## Tile Types

| Method | When to use |
|---|---|
| `.floor(dx, dy, tile)` | Most objects: furniture, appliances, storage. Object sits ON the floor square. |
| `.wallN(dx, dy, tile)` | Object leans against the north wall. The tile is placed at `(rx+dx, ry+dy)` on the floor but its art attaches to the wall one row north. Use for counters facing south, shelves on north wall, etc. |
| `.wallS(dx, dy, tile)` | Object leans against the south wall. |
| `.wallW(dx, dy, tile)` | Object leans against the west wall. |
| `.wallE(dx, dy, tile)` | Object leans against the east wall. |
| `.top(dx, dy, tile)` | Object stacks on a surface tile already placed at `(dx, dy)`. Only works if that square is marked SURFACE (counter, desk, table, workbench, drawers). Use for toasters, microwaves, sinks on counters, computers on desks. |

---

## Wall-Run Sets

A regular set tiles across the floor. A **wall-run** set aligns its back edge to one wall and repeats along that wall's length. Use `.wallRun()` for:

- Counter runs (kitchen, commercial kitchen)
- Shelf runs along a warehouse wall
- Sink runs in bathrooms

```java
new Builder("kitchen_counter_oak", 3, 1, 3, "kitchen", "studio")
    .floor(0, 0, "fixtures_counters_01_52")   // corner counter at left end
    .floor(1, 0, "appliances_cooking_01_13")  // oven in middle
    .floor(2, 0, "fixtures_counters_01_53")   // counter at right end
    .wallRun()
    .build()
```

The stamper places this set starting at `lx=1` (one from the west wall corner), repeating eastward every `set.w + aisle` tiles along the north wall. The second copy starts where the first ends.

---

## Reading Scan Output

The `RoomScan.java` tool prints a layout like this:

```
   --- room 427 'kitchen'  floor 0  size 5x4  rects [119,79 5x4]
         01234
       0 abcde
       1 f...i
       2 c...j
       3 k.e..
      objects:  sym (dx,dy) edge  tile                                      props
      a ( 0, 0) NW   fixtures_counters_01_52   "Oak Corner Counter"  Facing=S  container=counter  IsMoveAble
      b ( 1, 0) N    appliances_cooking_01_13  "Modern Oven"         Facing=S  container=stove    IsMoveAble
      c ( 2, 0) N    fixtures_counters_01_53   "Oak Counter"         Facing=S  container=counter  IsMoveAble
      f ( 0, 1) W    fixtures_counters_01_51   "Oak Counter"         Facing=E  container=counter  IsMoveAble
      h ( 0, 1) W    fixtures_sinks_01_8       "Chrome Sink"         Facing=E  IsTableTop         IsMoveAble
      k ( 0, 3) SW   appliances_refrigeration_01_29  "Plain Fridge"  Facing=E                     IsMoveAble
```

**Reading the columns:**
- `sym` — the letter shown in the grid (ignore for authoring, just for reference)
- `(dx,dy)` — offset from room top-left — USE THIS directly as dx, dy in the set
- `edge` — which room edges the tile touches (N=north wall, W=west wall, NW=corner). Tells you which `.wallN()` / `.wallW()` factory to use.
- `tile` — the exact tile name — USE THIS as the tile string
- `props` — `IsTableTop` means it must be a `.top()` tile. `attachedN/W/E/S` means it's wall-mounted.

**Edge → factory mapping:**
```
edge N  → .wallN(dx, dy, tile)   object backs the north wall
edge S  → .wallS(dx, dy, tile)   object backs the south wall
edge W  → .wallW(dx, dy, tile)   object backs the west wall
edge E  → .wallE(dx, dy, tile)   object backs the east wall
edge NW → .wallN(dx, dy, tile)   corner: use whichever wall is primary
edge -  → .floor(dx, dy, tile)   freestanding
IsTableTop in props → .top(dx, dy, tile)
```

**What to include / exclude:**
- Include: furniture, appliances, storage containers
- Exclude: light switches (`lighting_indoor_01_*`), door frames (`fixtures_doors_frames_*`), curtains (`fixtures_windows_curtains_*`), wall clocks decorative only, exit signs
- Paired tiles (e.g. a two-tile couch): include BOTH tiles at their correct dx,dy. The scan shows each tile separately — look for tiles from the same sheet with consecutive indices.

---

## Worked Example: Small Kitchen Counter

From scan `kitchen_2` (size 5×4):

```
a ( 0, 0) NW  fixtures_counters_01_52   "Oak Corner Counter"  Facing=S  attachedN
b ( 1, 0) N   appliances_cooking_01_13  "Modern Oven"         Facing=S
c ( 2, 0) N   fixtures_counters_01_53   "Oak Counter"         Facing=S
h ( 0, 1) W   fixtures_sinks_01_8       "Chrome Sink"         Facing=E  IsTableTop
```

The counter run is 3 tiles wide along the north wall, with a sink stacked on the west counter. Translate to a wall-run set:

```java
public static final FurnitureSet KITCHEN_COUNTER_OAK = new Builder(
        "kitchen_counter_oak", 3, 2, 3,
        "kitchen", "studio")
    // North wall counter run (dy=0, backed against north wall)
    .wallN(0, 0, "fixtures_counters_01_52")   // oak corner counter
    .wallN(1, 0, "appliances_cooking_01_13")  // oven
    .wallN(2, 0, "fixtures_counters_01_53")   // oak counter
    // Sink stacked on west counter (IsTableTop)
    .top  (0, 0, "fixtures_sinks_01_8")
    .wallRun()
    .build();
```

Width=3 (three counter tiles), height=2 (counter at dy=0, sink stacks on it — no extra floor tile needed but bounding box rounds up), minRoomDim=3 (fits in a 3×3 room).

Register it in `forRoom()` at the bottom of `FurnitureSet.java`:

```java
case "kitchen", "studio" -> {
    if (Math.min(rw, rh) >= KITCHEN_COUNTER_OAK.minRoomDim)
        sets.add(KITCHEN_COUNTER_OAK);
    // add more sets here as they're authored
}
```

---

## Worked Example: Small Living Room

A one-couch living area for a 4×4 room. Couch is a paired tile (two tiles side by side):

```java
public static final FurnitureSet COUCH_SMALL = new Builder(
        "couch_small", 2, 1, 3,
        "livingroom", "studio")
    .floor(0, 0, "furniture_seating_indoor_02_30")  // couch primary (FacingS)
    .floor(1, 0, "furniture_seating_indoor_02_31")  // couch extension (east)
    .build();
```

Width=2, height=1, minRoomDim=3. The stamper will place this once in a small room, twice in a larger room.

---

## Worked Example: Small Bedroom

Bed is always a paired tile. From scan `bedroom_1` (size 5×4):

```java
public static final FurnitureSet BED_SMALL = new Builder(
        "bed_small", 2, 1, 3,
        "bedroom", "kidsbedroom")
    .floor(0, 0, "furniture_seating_beds_01_8")   // bed primary
    .floor(1, 0, "furniture_seating_beds_01_9")   // bed extension
    .build();
```

For a bedroom with a wardrobe beside the bed:

```java
public static final FurnitureSet BED_WARDROBE = new Builder(
        "bed_wardrobe", 3, 1, 4,
        "bedroom", "kidsbedroom")
    .floor(0, 0, "furniture_seating_beds_01_8")
    .floor(1, 0, "furniture_seating_beds_01_9")
    .floor(2, 0, "furniture_storage_01_0")   // wardrobe
    .build();
```

---

## Registering Sets in forRoom()

At the bottom of `FurnitureSet.java` is the `forRoom()` method that the stamper calls. Add your sets there:

```java
public static List<FurnitureSet> forRoom(String roomType, int rw, int rh) {
    List<FurnitureSet> sets = new ArrayList<>();
    int minDim = Math.min(rw, rh);

    switch (roomType) {

        case "kitchen", "studio" -> {
            if (minDim >= KITCHEN_COUNTER_OAK.minRoomDim)
                sets.add(KITCHEN_COUNTER_OAK);
            // add more kitchen sets here
        }

        case "bedroom", "kidsbedroom" -> {
            if (minDim >= BED_SMALL.minRoomDim)      sets.add(BED_SMALL);
            if (minDim >= BED_WARDROBE.minRoomDim)   sets.add(BED_WARDROBE);
        }

        case "livingroom" -> {
            if (minDim >= COUCH_SMALL.minRoomDim)    sets.add(COUCH_SMALL);
        }

        // existing commercial sets...
        case "office" -> { ... }
    }

    return sets;
}
```

The stamper uses all returned sets — smaller sets fill small rooms, larger sets fill large rooms. Add both a small and large version of each type so all room sizes get good coverage.

---

## Switching a Room from PERIMETER to STAMP

Once you have sets for a room type, change its strategy in `FurnitureProfile.forRoom()`:

```java
// Before:
case "kitchen" -> perimeter(2, Activity.run("counter", 4, ...), ...);

// After:
case "kitchen" -> stamp("kitchen", 2,
        // Perimeter fallback activities when no set fits (room too small for any set)
        Activity.run("counter", 4,
                Satellite.maybe("sink", Rel.ON_TOP, 0.9)),
        Activity.of("oven"),
        Activity.of("fridge"));
```

The second argument to `stamp()` is the `decorBudget`. The remaining arguments are **perimeter fallback activities** — they run via `perimeter()` after stamps are placed, and also fire alone if no set fit at all (room too small for every set's `minRoomDim`).

This means a large kitchen gets stamped counter runs PLUS a fridge from the perimeter pass. A tiny kitchen that fits no set still gets the fridge and oven from the fallback.

---

## Quick Checklist Before Submitting a New Set

- [ ] Tile names match exactly (copy from scan output, don't type from memory)
- [ ] dx/dy match the scan output offsets
- [ ] Wall-mounted tiles use `.wallN/S/E/W()` not `.floor()`
- [ ] IsTableTop tiles use `.top()` not `.floor()`
- [ ] width × height matches the actual floor footprint (wall tiles don't count)
- [ ] minRoomDim ≥ max(width, height) + 2, or 3 for tiny sets
- [ ] roomTypes include all appropriate room type strings
- [ ] Set is registered in `forRoom()` with the minRoomDim check
- [ ] If it's a counter/shelf run, `.wallRun()` is set
- [ ] Paired tiles (couch, bed) have BOTH tiles in the set at consecutive indices
