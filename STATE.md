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
rm -rf out && mkdir out
javac -encoding UTF-8 -d out (find src/main/java -name '*.java')

# Generate mod
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport 2048 <cellX> <cellY>

# Inspect a square
java -cp out pzformat.Probe square "$PZ/media" "$MAPS" <cell> <x> <y> 0

# Find tiles by CustomName
java -cp out pzformat.PaletteScan "$PZ/media" --find "<Name>"

# Scan a tileset
java -cp out pzformat.PaletteScan "$PZ/media" <prefix>

# Placement tool (needs venv)
cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/mod_maps/PZGisImport
time python main.py render base_top PZGisImport   # ~2 seconds, safe to run
# DO NOT run: python main.py render base           # 167 minutes, one-time done
cd ~/Documents/PZMapCreation/map-output/html && python server.py
# Map viewer: http://localhost:8880/pzmap.html?map_type=top   (fast, ground only)
# Full isometric: http://localhost:8880/pzmap.html            (base render, done once)
```

Shell is fish. `grep` is aliased — use `command grep` with `-e` per pattern.
`$GISMAP`, `$PZ`, `$MAPS` die between sessions — set them every time.
In-game tests need a NEW GAME, not a resumed save.
Server.py requires the pzmap2dzi virtualenv.

---

## 1. What this project is

A GIS-to-playable-PZ-map pipeline. User supplies GeoJSON (buildings, roads,
area boundary). Pipeline generates a loadable B42 mod with correct rooms,
walls, doors, windows, furniture, ground blending, and biome coverage.

**End-to-end status: working. Loot verified in game. Furniture placing.**

C++ port (PZMapMaker) is at parity on the format layer but does NOT have
GisCells, BuildingClass, BuildingPlan, FurniturePlacer, or the furniture
profiles. Java is the laboratory; C++ is the eventual product.

---

## 2. What the code does — confirmed

### Format layer
- `.lotheader`, `.lotpack`, `.pack`, `.tiles` binary/text: 4065/4065 cells
  byte-identical round-trip verified against vanilla.

### GIS pipeline (GisCells.java)
- Roads, ground, dither blending, tufts, biome map, spawn points, chunkdata.
- Buildings from GIS footprints with 4 wall skins (seeded RNG).
- Room subdivision via BuildingPlan: typed rooms written into lotheader.
- Non-residential room recipes via BuildingPlan.recipe(primOcc).
- Interior walls + spanning-tree doors + door objects.
- Exterior door openings + frame + door object.
- Windows on exterior walls (WindowN/W wall + object), spaced, skip corners/doors.
- carveWindows() now returns Set<String> windowSquares for decor use.
- Roof type by BuildingClass: FLAT_ROOF → ceiling only; RESIDENTIAL → pitched.
- FurniturePlacer.place() called per building with doorSquares + windowSquares.

### Furniture system (2026-09-20, session 2)
Three files: TilePalette.java (discovery), FurnitureProfile.java (what goes
where), FurniturePlacer.java (algorithm).

**TilePalette.groups** — 67 roles, all non-empty after last session:
- chair_N/S/E/W (all sheets), chair_office_N/S/E/W (furniture_seating_indoor_02/03),
  chair_dining_N/S/E/W (carpentry_01), chair_soft_N/S/E/W (furniture_seating_indoor_01)
- couch_N/S/E/W (pair-low), bed (pair-low), bed_home (furniture_bedding_01 only)
- desk (container=desk), table (IsTable), counter (container=counter)
- sink (Material=Plumbing only — excludes MetalPlates industrial sink)
- fridge, oven (appliances_cooking_01), oven_ind (crafted_05)
- microwave, toaster (IsTableTop — ON_TOP of counter)
- overhead_N/S/E/W (Cabinet, IsHigh, wall-mounted)
- toilet_N/S/E/W (fixtures_bathroom_01, attached variants)
- stall_wall_N/W, stall_doorwall_N/W, stall_door_N/W (fixtures_bathroom_02)
- stall_toilet (fixtures_bathroom_02 _4/_5, no attach flag, pair-low)
- shower (waterPiped), bath (not IsGridExtensionTile)
- blower_N/S/E/W, urinal_N/W/S, mirror_N/W
- shelves_N/S/E/W (furniture_shelving_01, attached — wall shelves)
- shelves_office (furniture_shelving_01, freestanding — office/storage)
- shelves_retail (location_shop_*, freestanding — shops only)
- locker, watercooler (Dispenser, IsLow), television (IsTableTop)
- crate (carpentry_01/crafted_05), box (trashcontainers_01), pallet
- plant_floor (Snake/Cast Iron Plant, IsLow), plant_table (Orange Plant, IsTableTop)
- rug (floors_rugs_01, FLOOR layer), bin (container=bin)
- drawers (container=sidetable), wardrobe

**Per-room palette lock**: one tile family per role locked at room start,
~20% drift. All shelves in a room come from one sheet/colour family.

**Approachability invariant**: every interactive object checks the square it
faces is free before placement. WALL_ATTACHED role set checks opposite
direction (shelf on north wall checks south, toward the room).

**Strategies**: PERIMETER (fills full wall with required activities),
GRID (perimeter first, then clusters in centre for rooms ≥ 8×8),
ROWS (alternating facing so rows never face each other's backs),
CENTRE, ZONED, STALLS (fixtures_bathroom_02 plastic stall kit).

**Density tiers**: SPARSE/NORMAL/DENSE rolled per building, jittered ±1 per
room. Affects grid unit size, aisle width, row gap, skip probability.

**Decor pass**: plants near windows (radius 3, fallback to any free),
rugs on open floor (FLOOR layer, no occupancy), tabletop plants on SURFACE squares.

---

## 3. NEXT TASK — Set-based furniture (the main thing)

### What's wrong with the current approach

The activity-based system produces incoherent rooms: tables without chairs,
shelves scattered randomly, no visual identity per room. The root cause is
that each object is placed independently. A room needs to be ONE or TWO
recognizable arrangements stamped repeatedly, not a random selection.

### The set-based approach (agreed this session)

A **FurnitureSet** is a small template of objects at relative offsets,
extracted directly from vanilla measurements. A room picks 1-2 set types
and stamps them repeatedly across available floor. This is how vanilla rooms
were actually made.

### Measured sets from office_sets.txt (2026-09-20)

All sets extracted from vanilla scan at coordinates given. Tile names and
relative offsets (dx, dy) from scan origin:

**SET A — Conference table** (coord 12924,2044, cell 50_7)
A long table (furniture_tables_high_01_17/18 alternating in a column at dx=+1)
with chairs on both sides (furniture_seating_indoor_01_48/49/50/51).
Layout: column of table tiles at x+1, chairs at x+0 and x+2, runs 6 rows.
Bounding box: 3 wide × 6 tall.

**SET B — L-desk workstation** (coord 12785,1715)
location_business_office_generic_01_40/41/42/43 form an L-shaped desk.
Chair at furniture_seating_indoor_01_52/55 beside it.
Extended desk parts _136-140 for the long arm.
Bounding box: ~4×4.

**SET C — Cubicle with dividers** (coord 12773,1715)
location_business_office_generic_01_44-47 are partition/divider walls.
Desk _18/19 inside. Chair _51/53 beside.
Bounding box: ~4×4.

**SET D — Single desk + chair** (coord 12788,1699)
location_business_office_generic_01_33 (desk, 2 tiles at dx=0,dy=-1 and dy=0).
furniture_seating_indoor_03_58/59 (chair, 2 tiles).
furniture_tables_high_01_30/31 (side table).
Bounding box: ~4×4.

**SET E — Desk + lamp + filing** (coord 12820,1694)
location_business_office_generic_01_42/43/15 (desk parts).
furniture_storage_02_2 (filing cabinet above).
furniture_seating_indoor_01_49 (chair).
location_community_school_01_16/17 (lamp, 2-tile).
Bounding box: ~3×5.

**SET F — Desk row with computers** (coord 12783,1626)
location_business_office_generic_01_40/41/42/43/44/45 (desk components).
furniture_seating_indoor_01_48/49/50 (chairs).
location_shop_mall_01_4 (monitor/computer on desk).
Bounding box: ~6×6 (two back-to-back desks).

**SET G — Office with partition walls** (coord 12772,1535)
location_business_office_generic_01_18/19/44/45/46 (desk + partitions).
furniture_seating_indoor_01_50 (chair).
Bounding box: ~6×4.

**SET H — Back-to-back library shelves** (coord 12568,1471)
furniture_shelving_01_44 (Facing S) paired with _47 (Facing N) one row apart.
Repeating columns: _47 at dy=-2, _44 at dy=-1, across dx=0..3.
This is the standard library/storage row arrangement.
Bounding box: 4 wide × 2 tall per unit, repeat with 1-tile aisle.

### Implementation plan

1. **FurnitureSet.java** — a new file. Each set is a `List<Tile>` where
   `Tile` is `(int dx, int dy, String tileName)`. Sets are static constants.
   A set knows its bounding box (w, h) for stamping logic.

2. **Replace gridLayout in FurniturePlacer** — instead of placing individual
   desk anchors with satellites, pick 1-2 sets appropriate for the room type
   and stamp them repeatedly across the floor with aisle gaps between stamps.
   Density tier controls aisle width and skip probability as before.

3. **Room type → set selection**:
   - office (large) → SET F or SET C (cubicles) repeated
   - office (small) → SET D or SET E (single desk) 1-2 times
   - conference/lobby → SET A (conference table) once or twice
   - library/archive → SET H (back-to-back shelves) repeated
   - storage → rows of shelves_office + crates scattered

4. **Accents remain activity-based**: plants, rugs, water cooler, bin.
   These are sparse and don't need set logic.

---

## 4. Known bugs going into next session

**Toilets face wrong way** — stall_toilet from fixtures_bathroom_02 indices
_4/_5 are a pair. We always pick the low index (_4). The visual orientation
depends on which index is used and which wall the stall runs against. Not yet
fixed. Toilets render facing into the stall wall rather than toward the door.

**Zero sinks in commercial bathrooms** — the sink/counter zone in the ZONED
commercial bathroom is barely firing. Large rooms have the stall zone dominate.
Needs the sink run to be a required activity in a properly sized zone.

**Large empty rooms** — buildings whose single room is large (>30×20) and
classified as FLAT_ROOF still get mostly empty floors. The set-based approach
is the fix; perimeter alone can't fill a large room.

**Tables without chairs (still appearing)** — satellite probability scaling
by density still produces tables alone at SPARSE density. Fix: chair satellite
on table should be chance=1.0 unconditionally, not scaled by density.

**boiler/woodstove appearing in breakrooms** — oven_ind (crafted_05) is
resolving in some rooms that should only get domestic appliances. The building
class check (commercial vs residential) needs to gate oven_ind strictly to
restaurant/cafeteriakitchen rooms, not bleed into breakrooms.

---

## 5. Decisions made

- **Set-based furniture is next.** Extract vanilla templates, stamp repeatedly.
- **base render done once** (167 min). Never re-render. View at pzmap.html.
- **base_top render** (~2 sec) is safe to run after each generation.
- **Java = laboratory, C++ = product.** Port proven passes after stability.
- **Furniture at ry+1 / rx+1 confirmed correct** from vanilla kitchen scan.
- **Per-room palette lock** keeps rooms visually coherent (one shelf family).
- **WALL_ATTACHED role set** routes approach check to opposite direction.
- **fixtures_bathroom_02** is the complete plastic stall kit (not _01).
- **Industrial sink** = Material=MetalPlates in fixtures_sinks_01_32-35, excluded.
- **Urinals are NOT in stalls** — stall_toilet uses fixtures_bathroom_02 exclusively.

---

## 6. File reference

| File | Purpose |
|---|---|
| `src/main/java/pzformat/GisCells.java` | Pipeline orchestrator |
| `src/main/java/pzformat/BuildingPlan.java` | Room recipes by OCC_CLS/primOcc |
| `src/main/java/pzformat/BuildingClass.java` | FLAT_ROOF / RESIDENTIAL / etc |
| `src/main/java/pzformat/FurnitureProfile.java` | Room profiles and strategies |
| `src/main/java/pzformat/FurniturePlacer.java` | Placement algorithm |
| `src/main/java/pzformat/TilePalette.java` | Tile group discovery (67 roles) |
| `src/main/java/pzformat/GisImport.java` | heightM in Building record |
| `map-output/html/PLACEMENT_TOOL.md` | Placement tool doc |

---

## 7. Confirmed tile names

**Office furniture (location_business_office_generic_01)**
- _0 desk body (Facing S, IsLow)
- _15, _33 desk parts
- _18/19 filing cabinet
- _40/41/42/43 L-desk components
- _44/45/46/47 cubicle partition walls
- _53, _136-140 extended desk parts

**Seating**
- furniture_seating_indoor_01_48 chairW, _49 chairE, _50 chairS, _51 chairN
- furniture_seating_indoor_01_52-57 more office chair variants
- furniture_seating_indoor_02_12 chair variant
- furniture_seating_indoor_03_58/59 chair pair
- carpentry_01_36-47 dining chairs (chairS/N/E/W variants)

**Tables**
- furniture_tables_high_01_17/18 conference table pair (Facing N/S)
- furniture_tables_high_01_30/31 side table pair
- furniture_tables_high_01_6 table variant

**Shelving**
- furniture_shelving_01_44 Facing S (back-to-back row, south side)
- furniture_shelving_01_47 Facing N (back-to-back row, north side)
- furniture_shelving_01_1 attachedN (wall shelf, north wall)
- furniture_shelving_01_2 attachedW (wall shelf, west wall)

**Storage**
- furniture_storage_02_2 filing cabinet
- carpentry_01_16/19 wooden crates
- crafted_05_44 crate

**Bathroom stall (fixtures_bathroom_02)**
- _0 WallW, _1 WallN, _2 WallNW, _3 WallSE
- _4/_5 stall toilet (pair, no attach flag)
- _10 DoorWallW, _11 DoorWallN
- _16 doorW, _17 doorN
- _20-23 repeat walls (lit variant)

**Bathroom fixtures (fixtures_bathroom_01)**
- _0 toilet attachedN, _1 attachedW, _2 attachedE, _3 attachedS
- _22/_23 shower (no waterPiped), _32/_33 shower (waterPiped — use these)
- _24/_25 bath primary, _26/_27 bath extension (IsGridExtensionTile)
- _28 cabinet/mirror attachedN, _29 attachedW

**Sinks (fixtures_sinks_01)**
- _0-23 domestic/commercial (Material=Plumbing) — use these
- _32-35 industrial (Material=MetalPlates) — EXCLUDED

**Appliances**
- appliances_cooking_01_0 domestic oven
- appliances_cooking_01_24-29 microwave (IsTableTop)
- appliances_cooking_01_32/33 toaster (IsTableTop)
- appliances_refrigeration_01_0 fridge (44 variants)
- crafted_05_4-7 industrial oven

**Computers/monitors**
- location_shop_mall_01_4 monitor (found on office desks in vanilla scan)
- location_community_school_01_16/17 desk lamp (2-tile)

---

## 8. Pending (not started)

- Multistory buildings (HEIGHT data available, stairwells not implemented)
- Basements
- Roof access (stairs, hutch, railings, roof gardens on flat-roof buildings)
- Minimap (worldmap.xml + thumb.png)
- West gable end on pitched roofs (likely bx-1 placement, one-line fix)
- Barn floor (floorGrass instead of floorInterior)
- Flat roof object tiles (92 usable roofs_03_* tiles, need vanilla measurement)
- Wall skins for non-residential buildings (concrete/brick for FLAT_ROOF)
- BuildingPlan self-test failing on 40×20 footprints
- Port GisCells + furniture system to C++ (PZMapMaker)
