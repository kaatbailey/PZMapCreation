# PZMapCreation — Session Handoff

Paste this file at the start of every new session. It is the single source of
truth: what the project is, what the code actually does, what is confirmed
versus believed, and what comes next.

---

## 0. Standing environment

```fish
cd ~/Documents/PZMapCreation
set PZ ~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid
set GISMAP ~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport
set MAPS "$PZ/media/maps/Muldraugh, KY"

# Compile
cd ~/Documents/PZMapCreation
rm -rf out && mkdir out
javac -encoding UTF-8 -d out (find src/main/java -name '*.java')

# Generate mod (the working command)
# 0 = auto-size from area boundary (rounds up to next cell boundary, capped at vanilla world max)
# 200 200 = world origin cell X, Y — use placement.html to find correct coords
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport 0 200 200 2>&1 | grep -e "EMPTY" -e "roles," -e "stall_toilet" -e "stall_wall"

# Inspect a square
java -cp out pzformat.Probe square "$PZ/media" "$MAPS" <cell> <x> <y> 0

# Find tiles by CustomName
java -cp out pzformat.PaletteScan "$PZ/media" --find "<n>"

# Scan a tileset
java -cp out pzformat.PaletteScan "$PZ/media" <prefix>

# Scan furniture sets from world coordinates (outputs to file)
# Replace coords and output file as needed
set coords "12924,2044" "12785,1715"   # add more as needed
set out ~/Downloads/office_sets.txt
echo "" > $out

for coord in $coords
    set parts (string split "," $coord)
    set x $parts[1]
    set y $parts[2]
    set cx (math --scale=0 "$x / 256")
    set cy (math --scale=0 "$y / 256")
    set lx (math "$x - ($cx * 256)")
    set ly (math "$y - ($cy * 256)")
    set cell {$cx}_{$cy}
    echo "=== COORD $x,$y  CELL $cell  LOCAL $lx,$ly ===" >> $out
    for dx in -3 -2 -1 0 1 2 3
        for dy in -3 -2 -1 0 1 2 3
            set sx (math "$lx + $dx")
            set sy (math "$ly + $dy")
            set r (java -cp out pzformat.Probe square "$PZ/media" "$MAPS" $cell $sx $sy 0 2>/dev/null)
            if string match -q "*OBJECT*" $r; or string match -q "*DOOR*" $r
                echo "  offset($dx,$dy):" >> $out
                echo $r | command grep -e "CustomName" >> $out
                echo $r | command grep -e "classified" | head -1 >> $out
            end
        end
    end
    echo "" >> $out
end
echo "done"
cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/mod_maps/PZGisImport
time python main.py render base_top PZGisImport   # ~seconds, safe to run
# View mod map only:      http://localhost:8880/modmap.html
# Place on vanilla map:   http://localhost:8880/placement.html
# Vanilla top-down map:   http://localhost:8880/pzmap.html?map_type=top
cd ~/Documents/PZMapCreation/map-output/html && python server.py
```

Shell is fish. `grep` is aliased — use `command grep` with `-e` per pattern.
In-game tests need a NEW GAME, not a resumed save. Pick PZGisImport from the location list.
Cell size is **256 tiles**, not 300. Scan scripts divide by 256.

---

## 0a. Standing rules (always follow)

- **Never ask the user to cut and paste code or make edits manually.**
  Deliver all changes as complete replacement files. User uploads original;
  Claude modifies and returns the full file.
- **Always give the exact fish command** for any build, run, scan, or tool
  invocation. Never describe — write it out ready to run.
- **Before chasing a subsidiary bug**, name the tradeoff and let the user decide.

---

## 1. What this project is

A GIS-to-playable-PZ-map pipeline. User supplies GeoJSON (buildings, roads,
area boundary). Pipeline generates a loadable B42 mod with correct rooms,
walls, doors, windows, furniture, ground blending, and biome coverage.

**End-to-end status: working. Loot verified in game.**

---

## 2. What the code does — confirmed

### Format layer
`.lotheader`, `.lotpack`, `.pack`, `.tiles` — 4065/4065 cells byte-identical
round-trip verified against vanilla.

### GIS pipeline (GisCells.java)
Roads, ground, dither blending, tufts, biome map, spawn points, chunkdata.
Buildings from GIS footprints. Room subdivision via BuildingPlan. Interior
walls, spanning-tree doors, exterior doors/windows. Roof by BuildingClass.
FurniturePlacer.place() called per building.

### GisImport.java — auto-sizing (FIXED session 5)
The `maxTiles` parameter is now a true ceiling, not a default size. Pass `0`
to auto-size from the area boundary. The pipeline:
1. Computes extent from area.geojson boundary
2. Rounds up to next 256-tile cell boundary
3. Caps at vanilla world max (19968×16128 tiles)
Previously, passing `2048` was silently clamping the map to the top-left
2048×2048 corner, dropping ~95% of buildings and roads.

### Roofs — MAX_PITCH_SPAN (FIXED session 5, interim)
`roofs_30_02` has only 6 slope steps per face (near `_0.._5`, far `_29.._24`;
`_6 _7 _22 _23` do not exist). A pitched roof peaks at step (span-1)/2, so any
building wider than 12 tiles on its short side ran off the sheet — missing
steps fell back to ceiling-only (the brown strip along the ridge). ~half of
the Whitefish buildings were affected. Interim fix: `MAX_PITCH_SPAN = 12` in
GisCells — wider buildings get the flat-roof path; gables skipped for them.
Generator prints `roofs: N pitched, N flat (too wide), N flat (class)`.
Follow-up: measure a wide vanilla pitched house to see how the slope
continues onto z=2, then replace the flat fallback with a two-level roof.

### Spawn points (FIXED session 5)
`spawnpoints.lua` uses the LEGACY 300-tile grid: `worldX = tileX / 300`,
`posX = tileX % 300` (same for Y). NOT 256. The generator already did this;
hand-written entries using 256 put you in the woods.
`modmap.html` now has a spawn picker: click road squares to drop up to 20 pins,
right-click to remove, "Generate spawnpoints.lua" gives paste-ready Lua.
Paste over `$GISMAP/spawnpoints.lua` — no regenerate needed. New game required.
Note: regenerating the mod OVERWRITES spawnpoints.lua with auto spawns.

### Vanilla room scanner — RoomScan.java (NEW session 5)
One JVM, whole-room scans (all rects), ASCII layout grid + legend + objects with
offsets/edges/Facing. Input `rooms.txt`: plain header lines, then
`x:..,y:..,layer:..` lines. A header containing `[building]` scans every room of
that building. Misses (point on a wall line) fall back to a radius scan.
```fish
java -Xmx4g -cp out pzformat.RoomScan "$PZ/media" "$MAPS" rooms.txt > ~/Downloads/room_scans.txt
```
Findings: vanilla has no `kidsbedroom` (kids rooms are 4x4 `bedroom`s);
residential garages are one-room `garagestorage` buildings; gas station shop
is `gas2go`. 10 coordinates missed a room (all residential bathrooms, both
commercial kitchens, library) — rescan needed with nudged coordinates.

### Furniture system — SESSION 3–4 STATUS

**Four core files:**

| File | Role |
|---|---|
| `TilePalette.java` | Tile group discovery (67 roles). `oven_ind` removed — boilers no longer appear. |
| `FurnitureProfile.java` | Room profiles and strategies. Strategies: PERIMETER, GRID, ROWS, CENTRE, ZONED, STALLS, STAMP, AUTHORED. |
| `FurniturePlacer.java` | Placement algorithm. Required satellites (chance=1.0) now bypass density scaling — desks always get chairs. Paired objects (couch, bed) now place extension tile automatically — no more half-couches. |
| `FurnitureSet.java` | 25 vanilla-measured set templates (Sets A–Y). `BATHROOM_STALL_BLOCK_N` and `_W` authored sets exist for reference but toilet placement is handled by `stalls()` in `authoredLayout()`. |

**Strategy routing:**
- `office` → STAMP("office") with desk+chair perimeter fallback
- `breakroom` → STAMP("breakroom") with table+chair perimeter fallback
- `bathroom` commercial → AUTHORED (see below)
- `bathroom` residential → STAMP (toilet + counter + sink + bath)
- `hall` → PERIMETER (bench, bin, plant)
- All others → unchanged from session 2

**STAMP strategy**: picks 1–2 FurnitureSets for the room, tiles them across
the floor with density-driven aisle gaps. Wall-run sets (counter, kitchen wall,
vending) are flagged `.wallRun()` and placed along one wall instead.

**AUTHORED strategy — commercial bathroom (CONFIRMED WORKING):**
1. `stalls()` called directly — proven code places plastic stall toilets
   (`fixtures_bathroom_02_4`) with dividers + doorwall + door all stacked
   on the correct floor square. Runs along longest wall automatically.
2. Hanging sinks on opposite wall:
   - Wide room (rw≥rh): `fixtures_sinks_01_30` FacingN on S wall,
     mirror `fixtures_bathroom_01_28` on S wall square above each sink.
   - Tall room (rh>rw): `fixtures_sinks_01_31` FacingW on E wall,
     mirror `fixtures_bathroom_01_29` on E wall square beside each sink.
3. Bin (`trashcontainers_01_20`) + water dispenser in SE corner.
4. Blower from perimeter activities.

**Key lessons from bathroom debugging:**
- Stall wall tiles (`_02_0`, `_02_1`, `_02_10`, `_02_11`) are wall-layer tiles.
  Stacking them via `appendTile` on a floor square causes floating geometry.
  The ONLY way to place them correctly is through `stalls()` which uses
  `pal.pickFrom("stall_doorwall_N")` etc. — those flags route to the correct
  PZ rendering layer.
- For N-wall stalls: toilet = `_01_0` (attachedN, FacingS).
  For W-wall stalls: toilet = `_01_1` (attachedW, FacingE).
- Do NOT try to replicate `stalls()` with raw tile indices.

**Per-room palette lock**: one tile family per role locked at room start.
**Approachability invariant**: every interactive object checks approach square.
**Density tiers**: SPARSE/NORMAL/DENSE rolled per building.
**Decor pass**: plants near windows, rugs on open floor, tabletop plants.
**Paired objects**: `put()` auto-places extension tile for couch/bed roles.

---

## 3. NEXT TASK — Fill empty rooms (room type profiles)

### The problem
Several room types assigned by BuildingPlan fall to `default -> null` in
`FurnitureProfile.forRoom()`, producing completely empty rooms. These are
the large blank rooms seen throughout the map.

### Room types with NO profile (produce empty rooms)

| Type | Appears in | Suggested approach |
|---|---|---|
| `hall` | offices, schools, hospitals — corridor between rooms | DONE: minimal PERIMETER (bench, bin, plant). May need tuning. |
| `garage` | residential buildings | PERIMETER: workbench, locker, shelves, crate, maybe old car clutter |
| `garagestorage` | commercial warehouses, farms, garages | ROWS: heavy shelves, crates, pallets — same as `storage` but denser |
| `janitor` | offices, schools, hospitals — small utility closet | PERIMETER: mop bucket, shelves, cleaning supplies (bin, crate) |
| `kidsbedroom` | residential | PERIMETER: small bed, drawers, toy shelves — same as `bedroom` but lighter |
| `shed` | farms, residential backyards | ROWS: shelves, crates, maybe workbench |
| `closet` | residential — tiny room | PERIMETER: wardrobe only (room too small for anything else) |
| `cemetary` | church buildings | PERIMETER: nothing useful — `default -> null` is correct |
| `empty` | explicitly blank placeholder | `default -> null` correct — leave empty |

### Priority order
1. `garage` — appears in residential, large visible room
2. `garagestorage` — appears in warehouses, very large blank rooms
3. `kidsbedroom` — easy, clone of bedroom with minor tweaks
4. `janitor` — small but visually odd when empty
5. `shed` — small, same as garagestorage at smaller scale
6. `closet` — trivial one-liner

### Room types with thin profiles that may need improvement

| Type | Current profile | Issue |
|---|---|---|
| `hall` | bench + bin + plant | May produce too much furniture in wide halls |
| `storage` | rows of shelves_office | `shelves_office` may be wrong type — should be heavy industrial shelves |
| `shop` | ZONED rows+counter | Verify shelves_retail is filling the floor |
| `medical` | PERIMETER bed+counter+sink | Verify bed+gurney is appearing; may need exam table role |
| `school` | GRID desk+chair | Verify chalkboard and shelves are appearing beside desks |
| `church` | (check current state) | Should have pews, altar — needs vanilla measurement |

### How to add a new profile
In `FurnitureProfile.java`, add a `case "typename" ->` inside `forRoom()`.
Use `perimeter()`, `rows()`, `grid()`, or `stamp()` factory. Use `Activity.of()`
for required items and `Activity.maybe()` for optional ones.

Example for `garage`:
```java
case "garage" -> perimeter(1,
        Activity.of("workbench",
                Satellite.maybe("shelves_N", Rel.BESIDE, 0.6)),
        Activity.maybe("locker", 0.7),
        Activity.maybe("crate", 0.8),
        Activity.maybe("bin", 0.5));
```

No new FurnitureSet tiles needed for most of these — all roles already exist
in TilePalette (workbench, locker, crate, shelves_office, wardrobe, bin).
`garagestorage` and `shed` can reuse `LIBRARY_SHELVES` set or just use
`rows(Activity.of("shelves_office"), Activity.maybe("crate", 0.8))`.

---

## 4. Known bugs deferred

Full list as of session 5, roughly by visual impact:

**Roof**
1. Wide buildings (>12 tiles) get flat roofs as an interim — proper two-level
   pitched roof needs a vanilla measurement (see section 2).
2. Gable ends on north-south ridges not measured — those stay open.

**Paired objects**
3. Bed extension tile goes the wrong direction — half beds.
4. Couch extension tile wrong on some orientations — halves don't line up.

**Placement**
5. Small rooms (~4x4 and under) overcrowd — placer needs a size-based cap on activities.
6. Commercial (AUTHORED) bathroom fires in small flat-roof buildings —
   `commercial = bc == FLAT_ROOF` needs a footprint-size check.
7. Stamps: only office/breakroom use STAMP; residential rooms still PERIMETER.
   Vanilla scan data is in hand for livingroom/bedroom/kitchen to build stamps.

**Building generation**
8. Some buildings sit on road centerlines — GIS source data, not pipeline.
9. Some buildings read as one open room / jumbled room assignment.
10. BuildingPlan self-test failing on 40x20 footprints — not investigated.

**Cosmetic**

- **Wall clocks floating** — some sets have `wallN` clock tiles that render
  above the room in isometric view due to PZ's isometric angle. The clock IS
  on the wall; this is a PZ rendering quirk. Low priority cosmetic issue.
- **Couch variety** — lobby/livingroom couches are whole but vary randomly in
  colour and style per room, giving a mismatched showroom look. Needs palette
  locking to apply to paired objects. Medium priority.
- **BuildingPlan self-test failing on 40×20 footprints** — not investigated.
- **`stalls()` places plastic stall toilets (`_02_4`)** not white porcelain
  (`_01_0`). The plastic ones look fine; this is cosmetic. Low priority.

---

## 5. Decisions made

- **No manual code edits** — all changes as complete replacement files.
- **Always give exact fish commands** for builds/runs.
- **Cell size = 256 tiles.** Scan scripts divide by 256.
- **`oven_ind` removed from TilePalette** — boilers/smokers gone from all rooms.
- **`stalls()` is the only correct way to place stall tiles** — do not replicate
  with raw `_02_*` indices via stampSet; they render as floating geometry.
- **Required satellites bypass density scaling** — `Satellite.of()` always places.
- **Paired objects (couch, bed) auto-extend** — `put()` places extension tile.
- **Wall-run sets marked `.wallRun()`** — placed along one wall, not tiled floor.
- **AUTHORED strategy** used for commercial bathrooms only.
- **base render done once** (167 min). Never re-render base.
- **base_top PZGisImport render** (~seconds) safe to run after each generation.
- **Java = laboratory, C++ = product.** Port after stability.
- **MAX_PITCH_SPAN = 12** — wider buildings get flat roofs until two-level roofs are measured.
- **spawnpoints.lua uses the 300-tile legacy grid**, never 256. modmap.html spawn picker uses /300 and %300.
- **Files go directly into src/main/java/pzformat/** — never copy from Downloads.
- **maxTiles = 0** — auto-sizes from area boundary. Never pass a hardcoded tile
  count; it will clip the map silently.

---

## 6. File reference

| File | Purpose |
|---|---|
| `src/main/java/pzformat/GisCells.java` | Pipeline orchestrator |
| `src/main/java/pzformat/GisImport.java` | Rasteriser + auto-sizing from area boundary |
| `src/main/java/pzformat/BuildingPlan.java` | Room recipes by OCC_CLS/primOcc |
| `src/main/java/pzformat/BuildingClass.java` | FLAT_ROOF / RESIDENTIAL / etc |
| `src/main/java/pzformat/FurnitureProfile.java` | Room profiles and strategies |
| `src/main/java/pzformat/FurniturePlacer.java` | Placement algorithm |
| `src/main/java/pzformat/FurnitureSet.java` | 25 vanilla-measured set templates |
| `src/main/java/pzformat/TilePalette.java` | Tile group discovery (67 roles) |
| `map-output/html/modmap.html` | View mod map only + spawn picker (localhost:8880/modmap.html) |
| `src/main/java/pzformat/RoomScan.java` | Batch vanilla room scanner (see section 2) |
| `rooms.txt` | RoomScan input — room-type headers + coordinates |
| `map-output/html/placement.html` | Place mod on vanilla map (localhost:8880/placement.html) |

---

## 7. Confirmed tile names

**Bathroom fixtures (fixtures_bathroom_01)**
- `_0` toilet attachedN FacingS (backs N wall) — use for N-wall stalls
- `_1` toilet attachedW FacingE (backs W wall) — use for W-wall stalls
- `_2` toilet attachedE FacingW, `_3` toilet attachedS FacingN
- `_22/_23` shower (no waterPiped), `_32/_33` shower (waterPiped — prefer these)
- `_24/_25` bath primary, `_26/_27` bath extension (IsGridExtensionTile)
- `_28` mirror attachedN, `_29` mirror attachedW

**Bathroom stalls (fixtures_bathroom_02)**
- `_0` WallW, `_1` WallN, `_2` WallNW corner, `_3` WallSE corner
- `_4/_5` stall toilet (pairLow, no attach — free-standing in stall cell)
- `_10` DoorWallW, `_11` DoorWallN
- `_16` doorW, `_17` doorN
- `_20-23` lit wall variants

**Stall tile placement rule:**
- N-wall stalls (stalls() alongX=true): `stall_doorwall_N` + `stall_door_N`
- W-wall stalls (stalls() alongX=false): `stall_doorwall_W` + `stall_door_W`
- ALL placed via `stalls()` only — never via raw stampSet

**Sinks (fixtures_sinks_01)**
- `_30` White Hanging FacingN (hangs on S wall, approached from N)
- `_31` White Hanging FacingW (hangs on E wall, approached from W)
- `_5` Large Wide FacingS, `_7` Industrial FacingN, `_9` Chrome FacingS
- `_10` Chrome FacingW, `_16` Dark Industrial FacingE
- `_32-35` MetalPlates industrial — EXCLUDED from sink role

**Office furniture (location_business_office_generic_01)**
- `_8/_10` desk pair FacingN, `_28/_29` desk pair FacingW
- `_30/_31` corner/return FacingN, `_33` filing cabinet FacingE (2-tile)
- `_48` water dispenser, `_50/51/52` whiteboard 3-tile FacingE
- `_170/171/172` L-desk set (Office 3 group)

**Appliances**
- `appliances_cooking_01_0` domestic oven — the ONLY oven for residential/breakroom
- `appliances_cooking_01_24-29` microwave (IsTableTop)
- `appliances_refrigeration_01_0` fridge (44 variants)
- `crafted_05_*` industrial ovens — EXCLUDED (oven_ind role removed)

**Furniture (paired objects — always need extension tile)**
- `couch_S/N` → extends east (+dx=1)
- `couch_E/W` → extends south (+dy=1)
- `bed / bed_home` → always extends east (+dx=1)
- `put()` in FurniturePlacer handles this automatically

**Shelving / Storage**
- `furniture_shelving_01_44` FacingS (south-facing, back-to-back row)
- `furniture_shelving_01_47` FacingN (north-facing, back-to-back row)
- `furniture_shelving_01_1` attachedN (wall shelf)
- `furniture_shelving_01_2` attachedW (wall shelf)
- `furniture_storage_02_2` filing cabinet

---

## 8. Pending — after room type profiles

- Multistory buildings (HEIGHT data available, stairwells not implemented)
- Basements
- Roof access (stairs, hutch, railings, roof gardens on flat-roof buildings)
- Minimap (worldmap.xml + thumb.png)
- West gable end on pitched roofs (likely bx-1, one-line fix)
- Barn floor (floorGrass instead of floorInterior)
- Flat roof object tiles (92 usable roofs_03_* tiles, need vanilla measurement)
- Wall skins for non-residential buildings (concrete/brick for FLAT_ROOF)
- Port GisCells + furniture system to C++ (PZMapMaker)
