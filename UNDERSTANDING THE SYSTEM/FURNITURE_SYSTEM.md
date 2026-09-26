# Furniture System — How It All Works

This document explains every file involved in placing furniture, how they connect, and exactly what happens step by step from "building footprint exists" to "tile written to the cell file."

---

## The Six Files

| File | Job |
|---|---|
| `GisCells.java` | The pipeline orchestrator. Calls `FurniturePlacer.place()` for each building after carving its rooms. Also owns `appendTile()`, `removeTile()`, and `roomIndexOf()` which FurniturePlacer calls directly. |
| `BuildingPlan.java` | Given a building's footprint size, OCC_CLS, and PRIM_OCC, returns a list of room type strings (`["livingroom","kitchen","bathroom","bedroom"]`) and a list of `Room` objects with x,y,w,h and type. This is the only file that decides what rooms go where. |
| `FurnitureProfile.java` | A lookup table: `forRoom("kitchen", bc)` returns a `FurnitureProfile` describing what strategy to use and what activities the room contains. No tile names here — only roles like `"counter"`, `"oven"`, `"fridge"`. |
| `TilePalette.java` | Loads the sprite atlas and organises tiles into named groups by role. `pal.pickFrom("counter", rng)` returns a verified tile name like `"fixtures_counters_01_53"`. Roles that come in facing variants (shelves_N, toilet_N etc.) are stored as `"shelves_N"`, `"shelves_S"` etc. |
| `FurnitureSet.java` | Pre-measured vanilla room layouts stored as a list of tile+offset records. Used only by the STAMP strategy. Each set has a bounding box (w×h) and a list of `Tile` records: `{dx, dy, tile, wallMounted, onTop, wallDir}`. |
| `FurniturePlacer.java` | The algorithm. Takes a room rect, a FurnitureProfile, a TilePalette, and a density tier. Writes tiles to the CellData via GisCells.appendTile(). |

---

## Step-by-Step: What Happens Per Building

```
GisCells.java (roof pass complete)
  └─► BuildingPlan.recipe(area, occ, primOcc, outbuilding, rng)
        Returns: List<String> room types e.g. ["livingroom","kitchen","bathroom","bedroom"]

  └─► BuildingPlan.plan(x, y, w, h, types, facing, rng)
        Subdivides the footprint into Room rectangles.
        Each Room has: String type, int x, y, w, h, boolean entrance.
        Returns: List<BuildingPlan.Room>

  └─► FurniturePlacer.place(cell, pal, planned, idx, doorSquares, windowSquares, bc, rng)
        Rolls one Density tier for the whole building (SPARSE/NORMAL/DENSE).
        For each room:
          profile = FurnitureProfile.forRoom(room.type(), bc)
          if profile == null → skip (cemetery, closet, etc.)
          fp.furnish(room, roomId, profile, density.jitter(rng))
```

---

## Inside furnish() — One Room

```
furnish(room, roomId, profile, density):
  1. Set rx, ry, rw, rh, roomId from the room rect.
  2. Return early if rw < 3 || rh < 3 (no clearance possible).
  3. Set tinyRoom = (rw * rh < 25).
  4. Create byte[rw][rh] grid — all FREE.
  5. markDoorApproaches() — mark KEEP_CLEAR squares around every door in this room.
  6. Dispatch to strategy:
       PERIMETER → perimeter()
       GRID      → gridLayout()
       ROWS      → rows()
       CENTRE    → centre()
       ZONED     → zoned()
       STALLS    → stalls()
       STAMP     → stampLayout()
       AUTHORED  → authoredLayout()
  7. if (!tinyRoom) decor()
  8. enforceCirculation()
```

---

## The Grid

`grid[lx][ly]` is a byte array in room-local coordinates (0,0 = top-left of the room rect).

```
FREE       = 0   // empty, can place here
TAKEN      = 1   // object placed here, blocks further placement
KEEP_CLEAR = 2   // door approach zone, never fill
SURFACE    = 3   // object placed here, but can accept ON_TOP tiles (counter, desk)
```

`rx, ry` are the room's cell-local origin. To convert: `worldX = rx + lx`.

---

## Door Approaches

`markDoorApproaches()` scans every square in the room rect. If a square contains a door tile (checked against `doorSquares` — a Set<String> of "x,y" keys built by GisCells before calling place()), it marks that square and its neighbours as KEEP_CLEAR.

**Current behaviour:** marks 2 squares in each direction from each door. In tiny rooms this consumes most of the perimeter, pushing furniture into the middle. A `tinyRoom` flag reduces this to 1 square — but as of the session where this was written, that fix was in the output file but the perimeter lines themselves were still starting one tile in from the wall instead of on the wall. Both bugs need to be confirmed fixed.

**The correct wall position:** an object with `attachedN` (leans against north wall) should sit at `ly=0` in room-local coords, NOT `ly=1`. The wall is at `ry-1` in cell-local; the first interior square is `ry+0`. PZ's own tile system confirms this — the `attachedN` flag means "this tile's north edge is the wall."

---

## PERIMETER Strategy — The Main One for Residential

```
perimeter(activities, density):
  lines = four wall lines, each: {startX, startY, stepX, stepY, facing}
  
  N wall: start=(1,0), step=(1,0), facing='S'  ← object on north wall, faces south INTO room
  S wall: start=(1,rh-1), step=(1,0), facing='N'
  W wall: start=(0,1), step=(0,1), facing='E'
  E wall: start=(rw-1,1), step=(0,1), facing='W'
  
  Rotate which wall gets each activity.
  Required (chance==1.0) + not tinyRoom → fillLine() (repeats along wall)
  Optional or tinyRoom → placeAlongLine() (places once)
  tinyRoom: stops after 2 activities total.
```

**NOTE:** The lines above show the INTENDED positions. At the time of writing there was an active bug where the lines were set to `(1,1)` instead of `(1,0)` for the north wall etc. — one tile in from the wall instead of on the wall. Always check the actual values in the source.

### fillLine()
Walks the wall from start in steps of `density.aisle + 1`. At each position, places the anchor and calls `satellites()`. Used for required activities in large rooms so a counter run fills the whole kitchen wall, not just one tile.

### placeAlongLine()
Picks a random position along the wall and tries to place the anchor there. If blocked, tries the next position. Places once.

---

## put() — The Core Placement Call

```
put(lx, ly, tile, surface, role):
  1. if !free(lx, ly) → return false
  2. if role needs approach AND facing square not free → return false
       (facingClear checks the square the object faces into the room)
  3. GisCells.appendTile(cell, rx+lx, ry+ly, tileIndex, roomId)
  4. grid[lx][ly] = surface ? SURFACE : TAKEN
  5. placed.add({lx, ly})   // for circulation check undo
  6. if paired role (couch, bed):
       ext = tile name with index+1
       if ext exists in pal.all AND extension square is free:
         place ext at lx+extDir[0], ly+extDir[1]
       else if ext exists but square is NOT free:
         UNDO the anchor (removeTile, grid[lx][ly]=FREE, placed.remove)
         return false   ← stops half-couches
  7. return true
```

**Wall-attached vs freestanding facing:**
- Wall-attached roles (shelves, toilet, sink, bath, shower, locker, mirror, blower): the `facing` is the WALL they lean on. The approach check flips direction — a shelf on the N wall (facing='N') is approached from the S.
- Freestanding roles (desk, chair, couch, bed, fridge, oven): facing IS the direction the object looks. A desk facing 'N' is approached from the north.

---

## resolve() — Role to Tile Name

```
resolve(role, facing):
  1. Try group "role_facing" in TilePalette (e.g. "shelves_N")
  2. If empty, try group "role" (e.g. "shelves")
  3. If still empty, return null → object silently skipped
  4. Check the lock: if this role's family was placed before in this room,
     try to pick a tile from the same sheet (same furniture family).
     20% drift allows occasional variety.
  5. Return the picked tile name.
```

---

## satellites() — What Comes With an Anchor

Each `Activity` can have `Satellite` objects that place relative to the anchor:

```
ON_TOP      → same square, stacked (microwave on counter, sink on counter)
ABOVE_ON_WALL → the wall square behind the anchor (overhead cabinet above counter)
IN_FRONT    → the square the anchor faces (chair pulled up to a desk)
BESIDE      → perpendicular to the facing direction
ADJACENT    → any free neighbour
```

Required satellites (chance=1.0) always fire. Optional satellites are scaled by `density.optionalScale`.

---

## Density

Rolled once per building, jittered per room (±1 tier).

| Tier | gridUnit | aisle | rowGap | skip | optionalScale |
|---|---|---|---|---|---|
| SPARSE | 6 | 2 | 5 | 0.35 | 0.4 |
| NORMAL | 4 | 1 | 3 | 0.10 | 0.7 |
| DENSE | 3 | 1 | 2 | 0.00 | 1.0 |

`skip` = probability a slot is left empty anyway (randomness without looking random).
`optionalScale` = multiplier on optional activity and satellite chance.

---

## Strategies Summary

| Strategy | Used for | How it works |
|---|---|---|
| PERIMETER | Most residential rooms, small commercial | Objects against walls, middle clear |
| GRID | School classrooms, large offices | Unit block (desk+chair) tiled across floor, perimeter for extras |
| ROWS | Warehouses, storage, church pews | Parallel runs along long axis, alternating facing |
| CENTRE | Dining rooms | One focal object in the middle, perimeter for rest |
| ZONED | Shops, army surplus, breakrooms | Splits room into regions, each with its own strategy |
| STALLS | Commercial bathrooms | Row of toilet cubicles with dividers and doors |
| STAMP | Offices, breakrooms | Places pre-measured vanilla FurnitureSets across the floor |
| AUTHORED | Commercial bathrooms (explicit layout) | Hand-coded: stalls() + hanging sinks + bin + blower |

---

## STAMP Strategy — FurnitureSet

`FurnitureSet.forRoom(roomType, rw, rh)` returns a list of sets measured from vanilla buildings. Each set is a snapshot of a real vanilla room layout:

```java
// Example: a desk-with-chair set, 2 tiles wide x 1 tile deep
new FurnitureSet("office", 2, 1, List.of(
    new Tile(0, 0, "location_business_office_generic_01_8",  false, false, ' '), // desk
    new Tile(1, 0, "location_business_office_generic_01_9",  false, false, ' '), // desk ext
    new Tile(0, 1, "furniture_seating_indoor_02_0",          false, false, ' ')  // chair
))
```

`stampSet(set, ux, uy)` places each tile at `(rx+ux+dx, ry+uy+dy)` in cell coordinates.

Wall-mounted tiles go to the wall square (one step outside the set's bounding box in the wall direction). On-top tiles stack only if the floor tile below is already SURFACE.

---

## ZONED Strategy

Splits the room along its long axis by `floorShare` percentages. Each zone gets its own sub-grid (a slice of the parent grid) and its own strategy. The parent grid is written back after each zone so occupancy is shared — a counter placed in the kitchen zone blocks the adjacent tile in the living zone.

---

## enforceCirculation()

After all furniture is placed, flood-fills from the first KEEP_CLEAR square (a door approach square). Any free square not reachable from the door is "sealed." The most recently placed object that borders a sealed square is removed from the grid (but NOT from the cell file — the tile stays written, only the grid entry is cleared). This means a sealed room gets one object silently removed, which is better than a room zombies can't navigate.

**Limitation:** only removes one object. A badly overcrowded room may still have sealed corners after this pass.

---

## Common Bugs and What Causes Them

| Symptom | Cause | Fix |
|---|---|---|
| Half couch / half bed | Extension square blocked; anchor placed without extension | `put()` now undoes anchor if extension can't place |
| Furniture in room center not walls | perimeter lines start at (1,1) not (0,0) | Fix lines to start ON the wall square |
| Three fridges in tiny kitchen | `fillLine()` repeats required activity across whole wall | `tinyRoom` flag routes to `placeAlongLine()` instead |
| Plants everywhere in tiny room | `decor()` fires on small rooms; every square is "near a window" | `if (!tinyRoom) decor()` guard |
| Commercial bathroom in small house | `commercial = bc == FLAT_ROOF` — small flat-roof houses flagged commercial | Add footprint size check to the `commercial` flag |
| Wrong room type (toilets in livingroom) | BuildingPlan subdivides small footprints into too many rooms | Small building recipe fix: ≤40 tiles → studio, ≤80 → kitchen+bedroom |
| Furniture not against walls | `facingClear()` fails when facing the room interior from wall pos 0 | Wall-attached roles need approach check in OPPOSITE direction |

---

## What to Check When Something Looks Wrong

1. **What room type is it?** Use `RoomScan.java` with the coordinate to get the room name and rect.
2. **What profile does that room type get?** Check `FurnitureProfile.forRoom()`.
3. **What strategy?** PERIMETER, ROWS, STAMP etc. — each has different position logic.
4. **Is it a tinyRoom?** `rw * rh < 25`. Different caps and door clearance apply.
5. **Is `bc == FLAT_ROOF`?** Commercial flag changes bathroom profile to AUTHORED.
6. **Is the extension tile in `pal.all`?** If not, paired objects silently drop the anchor.
7. **Is the perimeter line starting on the wall (ly=0) or inside (ly=1)?** Print the lines array.
