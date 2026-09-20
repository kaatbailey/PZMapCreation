# pzformat — session handoff

Paste this file at the start of every new session. It is the single source of
truth: what the project is, what the code actually does right now, what is
confirmed versus believed, and what comes next.

Nothing described here as done needs to be rebuilt. Do not rebuild from
STATE.md descriptions — read the code.

---

## 0. Standing environment (paste every session)

```fish
cd ~/Documents/PZMapCreation
set PZ ~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid
set GISMAP ~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport
set MAPS "$PZ/media/maps/Muldraugh, KY"

# Compile
javac -encoding UTF-8 -d out (find src/main/java -name '*.java')

# Generate mod at chosen cell origin
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport 2048 <cellX> <cellY>

# Placement tool — run after each generation to update the GIS overlay
cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/mod_maps/
python main.py render base_top PZGisImport
cd ~/Documents/PZMapCreation/map-output/html && python server.py
# Open http://localhost:8880/placement.html
```

Shell is fish. `grep` is aliased to ugrep — use `command grep` with `-e` per
pattern. `$GISMAP`, `$PZ`, `$MAPS` die between sessions; set them every time.
In-game tests need a NEW GAME, not a resumed save.

**server.py requires the pzmap2dzi virtualenv** — run it from inside
`source .venv/bin/activate.fish` or flask will not be found.

---

## 1. What this project is

A **GIS-to-playable-PZ-map pipeline**, plus the underlying format library that
makes it possible.

The primary goal right now is the full end-to-end machine:
1. User supplies GeoJSON (buildings, roads, area boundary).
2. Pipeline generates a loadable B42 mod with correct rooms, walls, doors,
   windows, furniture, ground blending, and biome coverage.
3. Placement tool lets the user drag the generated map onto the vanilla world
   and extract cell coordinates.
4. Re-generate at the chosen coordinates.
5. Buildings are furnished appropriately for their room types, giving the engine
   real loot tables and zombie spawn classification.

**This pipeline is now working end-to-end. Loot verified in game 2026-09-20.**

---

## 2. What the code actually does — confirmed true

### Format layer — DONE AND VERIFIED

| format | verification |
|---|---|
| `.lotheader` | 4065/4065 cells read and write byte-identical |
| `.lotpack` | 4065/4065 cells, 4,162,560 chunks, byte-identical |
| `.pack` | PZPK and legacy layouts, round-trips byte-identical |
| `.tiles` binary | 73,644 tiles; all 37,060 with a text sibling match 100% |
| `.tiles` text | 33,568 tiles, 216 property keys |
| `chunkdata` | shape confirmed on 4065 cells |

### GIS pipeline — WORKING END TO END, LOOT VERIFIED

`GisImport.rasterise()` → `GisCells.run()` → loadable B42 mod.

**What it produces, verified in game:**
- Correct roads and ground (grass regions, dither blending, tufts).
- Buildings from GIS footprints with correct wall skins (4 skins, seeded RNG).
  Low wall skins (`house_low_01`) excluded — those can be vaulted.
- Room subdivision via `BuildingPlan`: typed rooms written into lotheader.
  Room names are meaningful and keyed to the vanilla loot system.
- **Non-residential room recipes** — `BuildingPlan.recipe()` now takes `primOcc`
  and routes by `OCC_CLS` + `PRIM_OCC`:
  - Commercial/Professional → `office`, `lobby`, `bathroom`, `breakroom`
  - Commercial/Retail → `shop`, `storage`, `bathroom`
  - Commercial/Wholesale → `storage`, `office`, `bathroom`
  - Commercial/Hospital → `medical`, `bathroom`, `office`
  - Government/General → `office`, `lobby`, `hall`, `bathroom`, `breakroom`, `archive`
  - Government/Emergency → `office`, `storage`, `bathroom`, `security`
  - Education → `school`, `bathroom`, `office`, `cafeteriakitchen`
  - Industrial → `storage`, `office`, `bathroom`
  - Assembly/Religious → `church`, `cemetary`, `bathroom`
  - Assembly/Other → `lobby`, `storage`, `office`, `bathroom`
  - Residential/MultiFamily → bedroom units + `bathroom` + `lobby`
  - Residential/TempLodging → bedroom units + `bathroom` + `lobby`
  - Unclassified → `storage`, `office`, `bathroom`
  - Residential/SingleFamily → existing residential recipe
- Interior walls and doors on a spanning-tree (no room sealed off).
  Interior doors have frame + door object tiles placed. 2026-09-20.
- Exterior door openings with DoorWall tile + door frame + door object.
  `carveEntrances` returns door square set; `carveWindows` uses it to avoid
  placing windows on door squares. 2026-09-20.
- Windows on exterior walls (`WindowN`/`W` wall tile + `fixtures_windows_01_*`
  object), spaced every 3–5 tiles, skipping corners and door squares. 2026-09-20.
- **Furniture and loot containers** — `placeFurniture` places containers one
  square inside room walls (north wall at `ry+1`, west wall at `rx+1`).
  Loot verified in game 2026-09-20.
  Container set by room type:
  - kitchen/cafeteriakitchen → counter + fridge
  - breakroom → counter + fridge
  - office → desk
  - storage/shop/etc → shelves (N and W walls)
  - bedroom/kidsbedroom → drawers (sidetable)
  - medical → shelves
- **Roof type by `BuildingClass`:**
  - FLAT_ROOF (Commercial/Government/Assembly/Education/Industrial or HEIGHT≥6m)
    → ceiling tile only at z=1.
  - RESIDENTIAL/AGRICULTURE/OUTBUILDING → pitched ridge roof with slope tiles,
    gable ends and trim. West gable end still open (known issue).
- Biome map, spawn points, chunkdata density, WorldGenOverride all written.
- `wipeMapDir()` clears mod directory before each run.
- `HEIGHT` from GeoJSON promoted into `GisImport.Building.heightM`.
- `BuildingClass` enum centralises roof + recipe routing.
- End-to-end placement doc at `map-output/html/PLACEMENT_TOOL.md`.

### Placement tool — WORKING
`placement.html` + `server.py` at port 8880. See PLACEMENT_TOOL.md.

---

## 3. What is NOT done — the honest list

### Furniture: placement is functional but not natural-looking
Containers are placed every 2nd square along north and west walls. This produces
rows of identical objects. Vanilla buildings vary placement, mix container types,
and orient furniture toward the room centre. The loot system works; the visual
reads as repetitive. Next pass should vary spacing and mix in secondary items
(chairs at desks, bed tiles in bedrooms, etc).

### Buildings: west gable end does not close
Pitched roofs are missing the west-end gable. Strong suspicion: it belongs one
square OUTSIDE the footprint at `bx-1`, same as the east end. One vanilla scan
confirms or denies it. See §10.

### Buildings: barn floor is hardwood
When room type is `barn`, `GisCells` writes `floorInterior` (hardwood).
Should be `floorGrass` or `floorDirt`. One-line fix.

### Buildings: flat roof uses residential ceiling tile
`FLAT_ROOF` buildings write `ceilings_01_0`. The correct tile is `roofs_03_*`
(92 usable tiles in `roofFlatNames`). Not yet measured on a vanilla flat-roofed
building.

### Buildings: wall skins are all residential
All 4 skins are wood/clapboard. Government/commercial buildings should use
concrete or brick. `BuildingClass` is available as the routing key.

### BuildingPlan self-test failing
Failing on 40×20 footprints since commit `0247ddc`. Fix before adding more
layout logic.

### Minimap
`worldmap.xml` and `thumb.png` missing. No writer exists.

### CellRenderer has no cutaway
Renderer-only limitation. Do not chase as a map defect.

### DZI overlay offset fragility
Re-render after every generation or overlay position is wrong.

---

## 4. What comes next, in priority order

### 4.1 Improve furniture naturalness
Vary container spacing, mix secondary items, orient beds in bedrooms, add
chairs to offices. Low risk — furniture pass is additive, wrong placement
just looks odd, doesn't break loot.

### 4.2 Minimap
`worldmap.xml` and `thumb.png`. Investigate XML format from vanilla.

### 4.3 Fix BuildingPlan self-test
40×20 footprint bathroom aspect violation. Fix before adding layout logic.

### 4.4 Barn floor
One-liner: `pal.floorGrass` when room type is `barn`.

### 4.5 Close the west gable
One scan of a vanilla building with exposed west end. Likely one-line fix.

### 4.6 Wall skins for non-residential buildings
Concrete/brick skins for `BuildingClass.FLAT_ROOF`. Needs discovery of
exterior wall tilesets beyond the current 4 residential prefixes.

### 4.7 Flat roof object tiles
Measure `roofs_03_*` on a vanilla flat-roofed building, then write the object
layer. 92 tiles already collected in `roofFlatNames`.

---

## 5. The pipeline in full

```
GeoJSON input
    buildings.geojson  (footprints + OCC_CLS + PRIM_OCC + HEIGHT)
    roads.geojson      (line features)
    area.geojson       (clip boundary)
        |
        v
GisImport.rasterise()
    cover[][] — BUILDING / ROAD / WATER / GROUND
    northWall[][] / westWall[][] — building perimeter edges
    Building record: rect, occ, primOcc, outbuilding, sqMeters, heightM
        |
        v
GisCells.run()
    ground layer                                               DONE
    road layer (flat tile, staircase diagonals = vanilla)      DONE
    building layer: walls (4 residential skins)                DONE
    room subdivision (BuildingPlan, typed by OCC+PRIM_OCC)     DONE
    interior walls + spanning-tree doors + door objects        DONE
    exterior door openings + door frame + door object          DONE
    exterior windows (WindowN/W wall + object)                 DONE
    furniture / containers (loot verified)                     DONE
    chunkdata density map                                      DONE
    biome map                                                  DONE
    spawn points                                               DONE
    roofs z=1: flat (ceiling only) or pitched (slopes+gables)  DONE
      pitched: west gable end open                             KNOWN ISSUE
      flat: ceiling tile only (roofs_03_* pending)             PARTIAL
        |
        v
Mod output → placement tool → re-generate at chosen coords
```

---

## 6. Key facts proven the hard way

**Format**
- Chunk column-major: `index = cx * chunksPerSide + cy`.
- `SPAN_LEVELS_FULL` is the only correct encoder policy.
- `spawnpoints.lua` uses legacy 300-tile cell grid, not 256.

**Buildings**
- Two wall objects on one edge = the plain wall wins collision. `replaceTile`
  strips before placing door. Same rule for windows.
- `WindowN`/`WindowW` are primary flags on window wall tiles — do NOT also
  require `WallN`/`WallW`. Discovery must use `WindowN` alone.
- `carveEntrances` must return door square coords; `carveWindows` must skip
  those squares or windows and doors stack on the same square.
- Furniture goes at `ry+1` / `rx+1` (one inside the wall), not on the wall
  square itself. `attachedN` objects lean against the wall to their north.
- Low wall skins (`house_low_01`) are vaultable — excluded from `SKIN_PREFIXES`.
- `furniture_storage_01_0` (Wardrobe) is a multi-tile object — use
  `furniture_storage_01_49` (Drawers/sidetable) for single-tile bedroom items.

**Roads**
- PZ road tiles are all surface texture variants. No corner/junction autotile
  system exists. Diagonal roads are staircase-shaped in vanilla too. Closed.

**Tools**
- `Probe lotheader` only prints first 4 + last tile name. Binary grep the file.
- `Probe findprop` caps at 3 hits and hits outdoor vegetation first in most
  cells. Use `Probe square` with known coordinates instead.
- `PaletteScan <mediadir> --find <CustomName>` is the right tool for finding
  tile names by their human-readable name.
- `PaletteScan <mediadir> --prop container` lists all container type values
  and counts.
- `server.py` needs the pzmap2dzi venv (flask/waitress). Activate first.

**Room names** — confirmed valid vanilla names (2026-09-20):
`barn`, `bathroom`, `bedroom`, `breakroom`, `cafeteriakitchen`, `cemetary`,
`changeroom`, `church`, `closet`, `diningroom`, `foyer`, `garage`,
`garagestorage`, `hall`, `haystorage`, `janitor`, `kidsbedroom`, `kitchen`,
`laundry`, `livingroom`, `lobby`, `medical`, `office`, `police`, `radio`,
`recreational`, `restaurant`, `school`, `security`, `shed`, `shop`, `storage`,
`theatre`, `tobaccostore`, `toolstore`

**Container property values** — confirmed (2026-09-20):
`counter`(257), `shelves`(192), `overhead`(66), `desk`(62), `fridge`(54),
`wardrobe`(48), `stove`(32), `shelves`(mag/metal variants)

**Furniture tile names** — confirmed with sprites:
- counter: `fixtures_counters_01_0`
- desk: `location_business_office_generic_01_0`
- shelvesN: `furniture_shelving_01_0` (attachedN)
- shelvesW: `furniture_shelving_01_2` (attachedW)
- fridge: `appliances_refrigeration_01_0`
- drawers: `furniture_storage_01_49` (sidetable, single-tile)

---

## 7. File and path reference

| thing | location |
|---|---|
| Project repo | `~/Documents/PZMapCreation` |
| Compiled classes | `~/Documents/PZMapCreation/out` |
| GIS source data | `~/pzgis/buildings.geojson`, `roads.geojson`, `area.geojson` |
| Generated mod | `~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport/` |
| Vanilla PZ | `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/` |
| Vanilla maps | `$PZ/media/maps/Muldraugh, KY/` |
| Decompiled engine | `~/Downloads/ZOMBOIDSTUFF/decompiled/` |
| Placement tool | `map-output/html/placement.html` |
| Placement doc | `map-output/html/PLACEMENT_TOOL.md` |
| pzmap2dzi | `~/Documents/PZMapCreation/pzmap2dzi/` (gitignored) |
| Flask server | `map-output/html/server.py` |
| Placement URL | `http://localhost:8880/placement.html` |

---

## 8. Probe commands reference

```fish
# GIS pipeline
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport 2048 <cellX> <cellY>

# Inspect a specific square
java -cp out pzformat.Probe square "$PZ/media" "$GISMAP" 200_200 <x> <y> <z>

# Find squares with a given property (caps at 3, hits vegetation first outdoors)
java -cp out pzformat.Probe findprop "$PZ/media" "$GISMAP" 200_200 <propName>

# Find tile names by CustomName
java -cp out pzformat.PaletteScan "$PZ/media" --find <name>

# List container property values and counts
java -cp out pzformat.PaletteScan "$PZ/media" --prop container

# Scan a tile prefix
java -cp out pzformat.PaletteScan "$PZ/media" <prefix>

# Find cells containing a tile name (binary grep)
command grep -rl -e "tilename" "$PZ/media/maps/Muldraugh, KY" \
    | command grep -e .lotheader

# List tile variants in a lotheader (binary grep)
command grep -a -o -e "blends_street_01_[0-9]*" \
    "$PZ/media/maps/Muldraugh, KY/13_23.lotheader" | sort -u

# Room wall geometry
java -cp out pzformat.Probe roomgeom "$PZ/media" "$GISMAP" 200_200

# Vanilla survey
java -cp out pzformat.Probe survey "$MAPS"

# BuildingPlan self-test
java -cp out pzformat.BuildingPlan
```

---

## 9. Decisions made and why

**Cell snap only.** Generator takes cell coordinates only. Sub-cell precision
requires implementing `originTileX/Y` through the generator.

**Full-wipe not glob-delete.** `wipeMapDir` deletes everything; glob lists
go stale when new writers are added.

**`replaceTile` strips before placing.** Two wall objects = collision blocked.

**Room names are meaningful.** Generic `room` gives no loot.

**Road corner tiles don't exist.** PZ uses texture variants only. Diagonal
roads are staircases in vanilla too. Closed.

**`StaticModule.prefab` is unused.** Not in decompiled output. Furniture is
authored geometry via `appendTile`.

**`BuildingClass` centralises routing.** OCC_CLS + HEIGHT → enum consumed
by roof pass, recipe, future wall skin selection.

**Low wall skins excluded.** `house_low_01` walls can be vaulted. Only
full-height skins in `SKIN_PREFIXES`.

**Furniture at `ry+1`/`rx+1`.** `attachedN` objects lean against the wall
to their north — they go one square inside, not on the wall row itself.

---

## 10. Roofs — measured tile rules (2026-09-18)

See previous STATE.md for full detail. Summary:

**Pitched:** ridge along longest axis. Slope tiles `roofs_30_02_(29-distFar)`
far face, `roofs_30_02_(distNear)` near face. Gable column one square outside
footprint: `walls_exterior_roofs_30_03_(61-distFar)` far,
`walls_exterior_roofs_30_03_(48+distNear)` near. Gable trim: `roofs_accents_30_01_(tileNum-48)`.
Ceiling: `ceilings_01_0` (attachedFloor + solidfloor + diamondFloor, no exterior).

**Flat:** ceiling tile only for now. `roofFlatNames` has 92 usable `roofs_03_*`
tiles ready for a future object pass once measured.

**Traps:** scan the FULL column or span is wrong. Verify tile names against
sprite atlas — red question marks mean no sprite.
