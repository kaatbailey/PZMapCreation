# Bug List and Fix Guide

Current known issues as of session 5, with exactly where to fix each one.

---

## Priority 1 — Furniture Not Against Walls (ACTIVE)

**Symptom:** Objects float in room center, not touching walls. Facing wrong direction.

**Root cause:** `perimeter()` in `FurniturePlacer.java` uses wall lines that start one tile INSIDE the wall:
```java
// WRONG (current state in source):
{1, 1, 1, 0, 'N'},      // N wall: starts at ly=1, facing N (toward wall)

// CORRECT (what it should be):
{1, 0, 1, 0, 'S'},      // N wall: starts at ly=0, facing S (into room)
```

**File:** `FurniturePlacer.java`  
**Method:** `perimeter()`  
**Fix:** Change the four `lines` entries:
```java
int[][] lines = {
    {1, 0,      1, 0, 'S'},   // N wall at ly=0, facing south (inward)
    {1, rh - 1, 1, 0, 'N'},   // S wall at ly=rh-1, facing north (inward)
    {0, 1,      0, 1, 'E'},   // W wall at lx=0, facing east (inward)
    {rw - 1, 1, 0, 1, 'W'},   // E wall at lx=rw-1, facing west (inward)
};
```
Also update `fillLine()` and `placeAlongLine()` line-length calculation from `rw-2` to `rw-1` (and `rh-2` to `rh-1`).

**Caveat:** Once objects sit on the wall (ly=0), the `facingClear()` check for wall-attached objects (shelves, toilet, sink) needs to check the square INWARD from the wall, not outward. `WALL_ATTACHED` roles already flip the approach direction via `isWallAttached()` → `opposite(facing)`. Verify this still works after the line position change.

---

## Priority 2 — Half Beds / Half Couches (PARTIAL FIX)

**Symptom:** Paired objects (bed, couch) show only one half of the two-tile sprite.

**Root cause — Part A (FIXED):** Extension tile existence check was wrong. Was:
```java
if (ext != null && cell.tileIndex(ext) >= 0)  // WRONG: always true
```
Now:
```java
if (ext != null && pal.all.contains(ext))  // CORRECT: checks sprite atlas
```

**Root cause — Part B (FIXED):** When extension square was blocked, anchor was placed without extension. Now the anchor is undone via `GisCells.removeTile()` and `put()` returns false.

**Root cause — Part C (REMAINING):** Extension direction may be wrong for some facings. Current logic:
```java
// Beds: always extend east (+1, 0)
// Couches facing S or N: extend east (+1, 0)
// Couches facing E or W: extend south (0, +1)
```
Verify against vanilla scans. A couch facing S (backing N wall) should extend east — but if it's placed on the E wall facing W, extension should go south. Check if the wall-placement facing interacts correctly with `pairedExtDir()`.

**File:** `FurniturePlacer.java`  
**Method:** `pairedExtDir()`, `put()`

---

## Priority 3 — Small Room Overcrowding (PARTIAL FIX)

**Symptom:** 4×4 rooms have too many objects; three fridges, toilet + bath + sink + wardrobe all jammed in.

**Fixes applied:**
- `tinyRoom = (rw * rh < 25)` flag set in `furnish()`
- Required activities use `placeAlongLine()` not `fillLine()` in tiny rooms
- Activity count capped at 2 in tiny rooms
- Decor skipped in tiny rooms
- Door clearance reduced from 2 to 1 square in tiny rooms
- `plantNearWindow()` skips rooms under 30 squares

**Remaining issue:** Door clearance still consuming too much of the perimeter. In a 4×4 room with a centered door, 1-square clearance marks lx=1..3, ly=0..1 as KEEP_CLEAR — that's 6 of the 16 squares gone before any furniture. The perimeter lines then have almost nowhere to go. Consider further reducing to 0 clearance for doors that are on a wall corner (where the approach is along the wall, not into the room).

**File:** `FurniturePlacer.java`  
**Methods:** `markDoorApproaches()`, `furnish()`

---

## Priority 4 — Commercial Bathroom in Small Buildings

**Symptom:** Tiny flat-roof buildings get the full commercial bathroom (stalls, hanging sinks) instead of a simple toilet+sink.

**Root cause:**
```java
boolean commercial = bc == BuildingClass.FLAT_ROOF;
```
Small buildings with flat roofs (e.g. a 6×8 garage) are flagged commercial and get the AUTHORED bathroom layout.

**File:** `FurnitureProfile.java`  
**Method:** `forRoom()`  
**Fix:** Add a footprint area check. Pass building area into `forRoom()`, or add a `BuildingClass.SMALL_FLAT` classification in `BuildingClass.java`:
```java
// Option A: pass area to forRoom() and check it
boolean commercial = bc == BuildingClass.FLAT_ROOF && area > 80;

// Option B: classify small flat-roof buildings differently
// in BuildingClass.of(bld) and check bc here
```

---

## Priority 5 — Roof Ridge Gap on Odd-Span Buildings

**Symptom:** Pitched roofs show a small gap along the ridge line on buildings with an odd number of tiles across their short side.

**Root cause:** The formula `distLow <= distHigh ? 29-distLow : distHigh` assigns the far-face peak tile to the center row. On an odd-span building the center row gets the far-face peak (`29-N`) but the near-face peak (`N`) is skipped — the two slopes don't visually meet.

**Attempted fix:** Stacking both tiles on the center square caused z-fighting and jagged spikes. Reverted.

**The real fix:** Measure a vanilla building with an odd span to find out what PZ actually does at the ridge. Does it use a different tile set? Does it use a thicker ridge cap tile? The `roofs_30_02` sheet has 128 tiles in groups of 6 — there may be ridge-specific tiles in the unused indices (6,7,14,15,22,23 are blank, but groups 5-8 might have variants).

**File:** `GisCells.java`  
**Method:** roof slope pass (~line 444)  
**Scan to run:**
```fish
# Find a vanilla odd-span house and probe its ridge row
java -cp out pzformat.Probe square "$PZ/media" "$MAPS" <cell> <x> <ridgeY> 1
```

---

## Priority 6 — Two-Level Pitched Roof for Wide Buildings (DEFERRED)

**Symptom:** Buildings wider than 12 tiles on their short side get a flat roof instead of a pitched one.

**Root cause:** `roofs_30_02` only has 6 slope steps per face. A 13+ tile wide building needs step 6+ which doesn't exist on this sheet.

**The real fix:** Vanilla wide pitched roofs continue the slope onto z=2 (a second level). Needs measurement of a wide vanilla pitched house.

**Interim:** `MAX_PITCH_SPAN = 12` in `GisCells.java` forces flat roof for wider buildings. This is documented in STATE.md. Do not change this constant until the z=2 slope is implemented.

---

## Priority 7 — Furniture Stamps Not Used in Residential

**Symptom:** Residential rooms use PERIMETER strategy; no measured vanilla layouts appear.

**Root cause:** `FurnitureProfile.forRoom()` routes residential rooms to `perimeter()` not `stamp()`. `FurnitureSet.forRoom()` has no residential sets — all sets are commercial (office, breakroom). Small residential rooms are often too small for any set's `minRoomDim` anyway.

**Fix path:**
1. Author small residential sets in `FurnitureSet.java` (see `AUTHORING_FURNITURE_SETS.md`)
2. Switch residential profiles to `stamp()` in `FurnitureProfile.forRoom()`
3. Keep perimeter activities as the fallback for rooms where no set fits

**Minimum sets needed:**
- `KITCHEN_COUNTER_SMALL` — 3×1 wall-run, minRoomDim=3
- `BED_SINGLE` — 2×1 floor, minRoomDim=3
- `COUCH_SMALL` — 2×1 floor, minRoomDim=3

---

## Priority 8 — spawnpoints.lua Overwritten on Regeneration

**Symptom:** Rebuilding the mod with `pzformat.Probe giscells` overwrites `spawnpoints.lua` with auto-generated woodland spawns.

**Root cause:** `writeSupportFiles()` in `GisCells.java` always writes a new `spawnpoints.lua`.

**Fix:** Check if `spawnpoints.lua` already exists before writing, and skip if it does:
```java
Path spawnFile = mapDir.resolve("spawnpoints.lua");
if (!Files.exists(spawnFile)) {
    Files.writeString(spawnFile, sb.toString());
}
```
**File:** `GisCells.java`  
**Method:** `writeSupportFiles()`

**Workaround until fixed:** Back up before each regeneration:
```fish
cp $GISMAP/spawnpoints.lua ~/Documents/PZMapCreation/my_spawnpoints.lua
# ... regenerate ...
cp ~/Documents/PZMapCreation/my_spawnpoints.lua $GISMAP/spawnpoints.lua
```

---

## Non-Bugs (Intentional or GIS Data)

- **Buildings on road centerlines** — GIS source data places building centroids; some land on roads. Not fixable in pipeline.
- **Wall clocks floating** — PZ isometric rendering quirk, not a pipeline bug.
- **Couch colour variety** — multiple couch styles in one room. By design (tile lock has 20% drift). Could reduce drift or tighten the family predicate.
- **Agricultural buildings empty** — `barn` profile has crates and pallets; if barn rooms appear empty check if `forRoom("barn", bc)` is returning a profile.

---

## Files and Their Bug Relevance

| File | Bugs it controls |
|---|---|
| `GisCells.java` | Roof ridge (#5), roof width cap (#6), spawn overwrite (#8), `appendTile`/`removeTile` helpers |
| `BuildingPlan.java` | Room type assignment, room sizing, small building recipes |
| `FurnitureProfile.java` | Which strategy per room type, commercial flag (#4), residential stamps (#7) |
| `FurniturePlacer.java` | Wall position (#1), paired objects (#2), small room overcrowding (#3) |
| `FurnitureSet.java` | Residential stamp sets (#7) |
| `TilePalette.java` | Role groups — if a role returns null, check predicates here |
| `BuildingClass.java` | Commercial flag source — `FLAT_ROOF` classification (#4) |
