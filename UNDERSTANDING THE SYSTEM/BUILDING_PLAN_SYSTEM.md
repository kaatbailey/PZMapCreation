# BuildingPlan System — How Buildings Get Their Rooms

This document explains how a building footprint becomes a set of typed room rectangles, which the furniture system then fills.

---

## Overview: Two Stages

```
GisCells calls:
  1. BuildingPlan.recipe(area, occ, primOcc, outbuilding, rng)
       → List<String> room type names  e.g. ["livingroom","kitchen","bathroom","bedroom"]

  2. BuildingPlan.plan(x, y, w, h, types, facing, rng)
       → List<BuildingPlan.Room>  each with type, x, y, w, h, entrance flag
```

These two stages are separate by design: `recipe()` decides WHAT rooms exist, `plan()` decides WHERE they go.

---

## Stage 1: recipe() — What Rooms Exist

### Input
- `area` — footprint in tiles (w × h)
- `occ` — OCC_CLS from GIS data ("Residential", "Commercial", "Assembly" etc.)
- `primOcc` — PRIM_OCC sub-classification ("Religious", "Hospital", "Retail Trade" etc.)
- `outbuilding` — true if the OUTBLDG flag is set in GIS data

### Special cases (checked in order)
```
Agriculture occ          → ["barn"]
outbuilding flag         → ["garagestorage"] or ["shed"] (70/30)
area <= 24               → ["garagestorage"] or ["empty"] or ["shed"]
area <= 40 (residential) → ["studio"]        ← small building fix
area <= 80 (residential) → ["kitchen","bedroom"]
```

### Non-residential by OCC_CLS + PRIM_OCC
Checked before the residential default:
```
Assembly + Religious     → ["church","cemetary"] + bathroom if area>60
Assembly (other)         → ["lobby","storage","office","bathroom"]
Commercial + RetailTrade → ["shop","storage","bathroom"] + extras by area
Commercial + Wholesale   → ["storage","storage","office","bathroom"]
Commercial + Hospital    → ["medical","medical","bathroom","office"]
Commercial + Parking     → ["garagestorage"] (single room)
Government               → ["office","office","lobby","bathroom","hall"]
Education                → ["school","school","bathroom","office"] + cafeteriakitchen if large
Industrial               → ["storage","storage","office","bathroom"]
Unclassified/unknown     → ["storage","office","bathroom"]
```

### Residential default (area > 80)
```
Base recipe: ["livingroom","kitchen","bathroom","bedroom"]

Area > 60, 62% chance:   + "closet"
Area > 110, 16% chance:  + "laundry"
Area > 130, 9% chance:   + "diningroom"
Area > 140, 8% chance:   + "garage"
Area > 100, 20% chance:  + "kidsbedroom"
Area > 200:              + extra bedrooms/kidbedrooms (1 per 100 tiles, max 8 bedrooms)
Area >= 240, 4+ bedrooms: + second "bathroom"
```

### Key room type strings (must match FurnitureProfile cases exactly)
```
Residential:  livingroom, kitchen, bathroom, bedroom, kidsbedroom,
              closet, laundry, diningroom, studio, garage, garagestorage
Commercial:   office, lobby, storage, shop, medical, hospitalroom, medclinic,
              school, cafeteriakitchen, breakroom, bathroom, hall, security
Specialty:    church, cemetary, barn, shed, storageunit, warehouse, factory,
              grocery, gas2go, armysurplus, gunstore, carsupply
```

---

## Stage 2: plan() — Where Rooms Go

### Input
- `x, y, w, h` — the building footprint in cell-local coordinates
- `types` — the recipe list from stage 1
- `facing` — which side faces the road (NORTH/SOUTH/EAST/WEST, default SOUTH)
- `rng` — seeded per building

### Routing

```
Single room type?
  → The whole footprint becomes one Room of that type.

Contains "livingroom"?  (dwelling)
  → Hub layout (see below)
  → But if min(w,h) < MIN_ROOM*2 (6 tiles): fallback to garagestorage/shed

No "livingroom"?  (commercial/institutional)
  → Recursive splitter: split() cuts the footprint into strips,
    assigns rooms by weight, recurses until rooms are small enough.
```

---

## The Hub Layout (Dwellings)

Dwellings use a specific grammar that matches how real houses work: a public front zone (livingroom + kitchen) and a private back zone (bedrooms, bathroom).

```
FRONT (road side)
+------------------+--------+
|   LIVINGROOM     |KITCHEN |
|                  |        |
+--------+---------+--------+
|BEDROOM |BATHROOM |BEDROOM |
|        |         |        |
+--------+---------+--------+
BACK
```

### Layout selection
```
min(w,h) <= MIN_ROOM + MIN_LIVING_SIDE (7 tiles):
  → hubRowLayout: everything in a single row, rooms side by side

area >= LARGE_HOUSE_AREA (420 tiles) AND bedrooms >= 3:
  → hubHallLayout: adds a corridor spine separating public from private

Otherwise:
  → hubNoHallLayout: front/back split, no corridor
```

### hubNoHallLayout (most common)
1. **Front zone depth** = ~46% of building depth (clamped to `MIN_LIVING_SIDE..depth-MIN_ROOM`)
2. **Front zone** split laterally: livingroom gets ~62% of width, kitchen gets ~38%
   - If no kitchen or too narrow for both: livingroom owns the whole front
3. **Back zone** = remaining depth, split recursively among secondary rooms (bedrooms, bathroom etc.)

### hubRowLayout (narrow buildings)
Everything laid out in a single row along the building's long axis. Livingroom at one end, kitchen beside it, private rooms behind. Used when the building is too narrow for a front/back split.

### hubHallLayout (large buildings only)
Adds a hall corridor separating the public zone from the private zone. Hall width = HALL_MIN(4) to HALL_MAX(7) tiles. Used only when area >= 420 AND bedrooms >= 3.

---

## The Recursive Splitter (Non-Dwellings)

`split()` divides the footprint into two halves and assigns rooms:

```
split(out, x, y, w, h, rooms, rng):
  1. Sort rooms by WEIGHT (heaviest = largest room gets the most space)
  2. Pick a split axis (prefer to cut the long dimension)
  3. Assign heavier rooms to one half, lighter to the other
  4. Recurse on each half
  5. Stop when only one room remains (assign the whole remaining rect)
     or when remaining rect is too small for further splitting
```

### Room weights (larger weight = larger room)
```
closet     2    bathroom   6    laundry    6
janitor    9    kidsbedroom 15  bedroom    16
diningroom 20   kitchen    24  office     27
garage     30   hall       40  livingroom 42
```

Rooms not in the weight table default to 15.0.

### Geometry guards
- `MIN_ROOM = 3` — no room smaller than 3×3
- `ROOM_MAX_ASPECT = 4.0` — no room longer than 4× its width (prevents slivers)
- `trimToCapacity()` removes rooms from the recipe if the footprint can't fit them all at MIN_ROOM size

---

## Room Record

```java
record Room(String type, int x, int y, int w, int h, boolean entrance)
```

- `x, y` — cell-local coordinates (NOT world tile, NOT room-local)
- `w, h` — room dimensions in tiles
- `entrance` — true if this room type is in the ENTRANCE set and may receive an exterior door
- `area()` — convenience method: w * h

### ENTRANCE set (rooms that can have exterior doors)
```
livingroom, kitchen, hall, laundry, lobby, diningroom, barn, garagestorage, shed
```
Bedrooms deliberately excluded — you don't walk into a bedroom from outside.

---

## Constants You Can Tune

All in `BuildingPlan.java`:

| Constant | Value | Effect |
|---|---|---|
| `MIN_ROOM` | 3 | Smallest any room can be on either axis |
| `MIN_BEDROOM` | 5 | Minimum bedroom size (one axis) |
| `MIN_LIVING_SIDE` | 4 | Minimum livingroom short side |
| `MIN_KITCHEN_SIDE` | 3 | Minimum kitchen short side |
| `HALL_MIN / HALL_MAX` | 4 / 7 | Hall corridor width range |
| `ROOM_MAX_ASPECT` | 4.0 | Maximum long:short ratio before a room is split |
| `LARGE_HOUSE_AREA` | 420 | Area threshold for hall layout |
| `LARGE_HOUSE_BEDROOMS` | 3 | Bedroom count threshold for hall layout |
| `SECOND_BATH_AREA` | 240 | Area needed before a second bathroom is added |
| `BEDROOM_MAX` | 8 | Maximum bedrooms in any building |
| `LK_OPEN` | 0.88 | Probability the livingroom/kitchen wall has an opening |
| `A_LIVING` | 32 | Minimum area for a livingroom to be comfortable |
| `A_KITCHEN` | 21 | Minimum area for a kitchen |
| `A_BED` | 14 | Minimum area for a bedroom |
| `A_BATH` | 6 | Minimum area for a bathroom |

---

## Common Problems and Where to Fix Them

| Symptom | Where | Fix |
|---|---|---|
| Wrong room count for building size | `recipe()` | Adjust area thresholds in the residential default section |
| Rooms too small (cramming) | `plan()` / `split()` | Increase `MIN_ROOM` or adjust `ROOM_MAX_ASPECT` |
| Livingroom too narrow | `hubNoHallLayout()` | Adjust the `0.38` kitchen fraction or `MIN_LIVING_SIDE` |
| Hall appearing in small houses | `hubLayout()` | Increase `LARGE_HOUSE_AREA` threshold |
| Wrong room types for commercial | `recipe()` | Edit the OCC_CLS + PRIM_OCC switch cases |
| Studio rooms too large or too small | `recipe()` | Change the `area <= 40` and `area <= 80` thresholds |
| Outbuilding getting wrong type | `recipe()` | Change the 70/30 split in the outbuilding branch |
| GarageStorage showing in residential | `recipe()` | Check the `area <= 24` branch picks right |

---

## How GisCells Calls This

```java
// In GisCells.java, per building:
List<String> types = BuildingPlan.recipe(area, occ, primOcc, outbuilding, rng);
List<BuildingPlan.Room> rooms = BuildingPlan.plan(bx, by, bw, bh, types, facing, rng);

// Then for each room, furniture:
FurniturePlacer.place(cell, pal, rooms, roomIndices, doorSquares, windowSquares, bc, rng);
```

The `facing` passed to `plan()` comes from `bld.facing()` on the GIS building object — it's derived from the road the building faces in the GIS data.

---

## What RoomScan Tells You About This System

When you run RoomScan on a coordinate and it reports `'livingroom' size 6x7`, that's the Room record that `plan()` produced. If the room type is wrong (e.g. `'bathroom'` in a house that should have a `'livingroom'`), the bug is in `recipe()` producing the wrong list. If the room type is right but the size is wrong (too small), the bug is in `plan()` / `split()` not respecting `MIN_ROOM` or the weight ratios.
