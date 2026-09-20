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
   ground blending, and biome coverage.
3. Placement tool lets the user drag the generated map onto the vanilla world
   and extract cell coordinates.
4. Re-generate at the chosen coordinates.
5. Buildings are furnished appropriately for their room types, giving the engine
   real loot tables and zombie spawn classification.

The secondary product is a **map editor** that knows what tiles mean, can
validate maps, and can undo edits. The format layer (reading/writing every PZ
file byte-perfect) and the semantic layer (what a tile IS rather than which
sprite it draws) are both complete and are shared by both products.

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
| `chunkdata` | shape confirmed on 4065 cells; what value stops generation is unsolved |

The chunk column-major order bug (transposition) was found and fixed. Byte-
identical round-tripping does NOT verify interpretation — that lesson has been
applied throughout.

### Editor layer — DONE, not currently the focus

`CellEditor`: `setFloor`, `setWall(edge, tile)`, `removeWall`, `addObject`,
`clearObjects`, `clearSquare`, `fillFloor`, `outlineRoom`. Grouped undo/redo
that restores byte-identical output. Verified on Muldraugh 42_40: a 6×6
living-room re-floor leaves all walls, doors, windows, and objects intact.

### GIS pipeline — WORKING END TO END

`GisImport.rasterise()` → `GisCells.run()` → loadable B42 mod.

**What it produces, verified in game:**
- Correct roads and ground (grass regions, dither blending at boundaries,
  tuft layer matching vanilla 43.3% rate).
- Buildings placed from GIS footprints with correct wall skins (multiple skin
  variants, chosen per building by seeded RNG).
- Room subdivision via `BuildingPlan`: typed rooms (`livingroom`, `kitchen`,
  `bedroom`, `bathroom`, `barn`, etc.) written into lotheader with correct
  membership — the engine uses these for loot and zombie spawning.
- Interior walls and doors on a spanning-tree so no room can be sealed off.
- Exterior door OPENINGS placed on the road-facing side of eligible rooms
  (livingroom, kitchen, hall, laundry, barn, etc. — never bedroom). The wall
  tile is replaced with a DoorWall tile. Door OBJECTS (the openable door) are
  not yet placed — see §3.
- Biome map (`biomemap_X_Y.png`) — makes terrain continuous across the mod
  boundary; the engine generates matching vegetation on authored cells.
- Spawn points on road squares. `spawnpoints.lua` uses the legacy 300-tile
  grid (not B42's 256) — found the hard way.
- `chunkdata_X_Y.bin` written with correct density per built-up chunk so the
  engine treats those chunks as urban rather than overwriting them.
- **Roof type by building class.** `BuildingClass` classifies every building
  from `OCC_CLS` and `HEIGHT`:
  - `FLAT_ROOF` (Commercial, Government, Assembly, Education, Industrial, or
    HEIGHT ≥ 6 m) → ceiling tile only at z=1, no slopes or gables.
  - `RESIDENTIAL` / `AGRICULTURE` / `OUTBUILDING` → pitched ridge roof.
  - Pitched roof: ceiling tile plus slope tiles (`roofs_30_02_*`) over every
    footprint square, gable ends and trim on the column outside each end of
    the ridge. See §10 for the measured tile formulas.
- **Windows on exterior walls.** `carveWindows` places window wall tiles
  (`WindowN`/`WindowW`) plus window object tiles (`fixtures_windows_01_*`)
  on exterior wall squares, spaced every 3–5 tiles per building (seeded RNG).
  Corners and door squares are skipped. Verified in game 2026-09-20.
- `wipeMapDir()` — clears the whole map directory before each run so stale
  cells from a previous origin don't accumulate. Guard refuses to delete
  unless a `.lotheader`, `.lotpack`, `map.info` or `maps/` is present.

**Cell origin is a parameter.** `GisCells.run()` takes `originCellX`,
`originCellY`. Default 200,200. `Probe giscells` reads them as args[8]/args[9].

**`HEIGHT` field is now read from GeoJSON** into `GisImport.Building.heightM`.
Used by `BuildingClass.of()` to force `FLAT_ROOF` on buildings ≥ 6 m tall
regardless of `OCC_CLS`. Null/empty `HEIGHT` values are handled safely (treated
as 0).

### Placement tool — WORKING

`map-output/html/placement.html` served by `server.py` at port 8880.
- Vanilla PZ world rendered as a pannable/zoomable top-down colour map via
  `pzmap2dzi` + OpenSeadragon.
- GIS mod rendered as a draggable overlay (yellow border) showing actual map
  content (roads, buildings, ground in carto-zed colours).
- Snaps to cell boundaries (256 tiles) on drag. This is correct: the generator
  pins the raster to the cell corner and takes cell coordinates only. Sub-cell
  placement is not implemented.
- Cell X,Y updates live in the toolbar.
- Opacity slider for alignment.
- Apply button emits the complete Java command with the chosen coordinates.
- `pzmap2dzi` is a git dependency, configured for Garuda Linux + B42 +
  `carto-zed` colour mode. Lives at `/pzmap2dzi/` but is gitignored — it is
  a subproject dependency, not source we own.
- End-to-end usage documented in `map-output/html/PLACEMENT_TOOL.md`.

---

## 3. What is NOT done — the honest list

### Buildings: no door objects
`carveEntrances` replaces exterior wall tiles with `DoorWall` tiles, creating
openings. But no door object (`fixtures_doors_*`) is placed on the square.
The opening is walkable but shows no door frame or door. Next step: measure
a vanilla exterior door square with `Probe square`, identify the door object
tile name and its `Facing` direction, then `appendTile` it in `carveEntrances`.

### Buildings: no furniture or containers
Nothing in the codebase places objects inside rooms. `TileIndex.container()`
reads the `container` property, but no caller places objects. Placing
furniture would activate PZ's loot system automatically — loot tables key off
(room name, container type), and room names are already correct.

Measured from vanilla 42_38: container tile names and their `container`
property values:
- `fixtures_counters_01_45` → `container counter` (kitchen counter, Facing S)
- `location_hospitality_sunstarmotel_02_46` → `container overhead`
- `furniture_storage_01_49` → `container sidetable` (bedroom drawers, Facing E)

`StaticModule.prefab` — decompiled. It is a bare record field; `PrefabStructure`
is not in the decompiled output and `WorldGenChunk` does not reference prefabs.
The prefab system is not active in the worldgen path. Furniture will be authored
geometry placed by `appendTile`.

### Buildings: west gable end does not close
2026-09-18. Roofs are done and correct apart from one end.

The EAST gable renders properly — trim runs up both roof edges to the peak and
the triangle is filled. The WEST gable does not, and you can see into the
building from that side.

Strong suspicion: the west gable also belongs OUTSIDE the footprint, at
`bx - 1`, making both ends symmetric. That would be a one-line placement
change. The measurement that settles it: find a vanilla building with an
exposed west gable and scan the column one square left of its footprint, the
same scan that worked for the east (§8).

### Buildings: barn floor is hardwood
When room type is `barn`, `GisCells` still writes `floorInterior` (hardwood).
Should be `floorGrass` or `floorDirt`. One-line fix in `GisCells`.

### Buildings: flat roof uses residential ceiling tile
`FLAT_ROOF` buildings currently write `ceilings_01_0` (the residential ceiling)
at z=1. The correct tile is from `roofs_03_*` (the industrial/flat roof sheet,
which carries the `exterior` flag). `TilePalette.roofFlatNames` already collects
these tiles (92 usable, verified). Not yet measured on a vanilla flat-roofed
building. The pass writes ceiling-only for now; a future pass will add the
correct `roofs_03_*` object once measured.

### Buildings: wall skins are all residential
`TilePalette.SKIN_PREFIXES` covers `house_01`, `house_02`, `wooden_01`,
`wooden_02`, `house_low_01` — all residential. `FLAT_ROOF` buildings
(government, commercial, etc.) still use these skins. Correct skin discovery
for commercial/concrete/brick walls is not yet implemented. `BuildingClass` is
already available as the routing key.

### BuildingPlan self-test failing
`FINDINGS_E13_2026-08-19_for_PZMapCreation.md` records that commit `0247ddc`
added four larger footprints and the suite has failed since: `worst 5.7 NORTH
40x20 bathroom[23,4 17x3]`, exit 1. The test that was 14,680 passing is no
longer passing. Needs a fix before the layout engine is trusted at scale.

### CellRenderer has no cutaway
`CellRenderer` draws every wall at full height with no cutaway, so rendered
PNGs show the exterior only. The engine handles cutaway correctly in game — this
is a renderer-only limitation. Rooms are visible in game; they are not visible
in PNG output. This has been confirmed four separate times. Do not chase it as a
map defect.

### DZI overlay: padding / offset fragility
When the GIS mod is generated at non-default coordinates, `pzmap2dzi` produces
a DZI whose origin is world tile 0,0 (because `x0` is large and negative). The
placement page compensates via `gisImgOffsetX/Y` but the offset is fragile —
it is derived from the DZI's `map_info.json` at load time, which must be
re-rendered after every generation. If coordinates change, the overlay is wrong
until re-rendered.

### Minimap
`Probe mapdir` checks for `worldmap.xml`, `worldmap-forest.xml`, `thumb.png`
and reports them missing. No writer exists yet. The minimap is what appears on
the in-game map screen. Not started.

---

## 4. What comes next, in priority order

### 4.1 Door objects
Measure a vanilla exterior door square with `Probe square`. The door object
tile (`fixtures_doors_*`) needs a `Facing` direction. Then `appendTile` it in
`carveEntrances` after the `replaceTile` call that places the DoorWall.

### 4.2 Furniture and containers
Place objects inside rooms by type. Container tile names are measured (§3).
The measurement pass still needed: `Probe square` on a vanilla kitchen and
bedroom to record placement positions (against wall, centred, etc.) and
additional tile names.

Loot activates automatically from (room name, container type). Room names are
already correct. No separate loot work needed.

### 4.3 Minimap
`worldmap.xml` and `thumb.png` are the files the engine reads for the in-game
map screen. No writer exists. Needs investigation of the XML format.

### 4.4 Fix BuildingPlan self-test
The layout engine self-test is currently failing on 40×20 footprints
(bathroom aspect violation). Fix before adding more furniture logic.

### 4.5 Barn floor
One-line fix: when room type is `barn`, write `pal.floorGrass` instead of
`pal.floorInterior`. Do this in `GisCells` where it stamps interior floor tiles.

### 4.6 Close the west gable
Small and well-characterised. See §3 "west gable end does not close" for the
diagnosis and the exact scan that settles it.

### 4.7 Wall skins for non-residential buildings
`BuildingClass.FLAT_ROOF` buildings should use concrete/brick skin variants.
Needs discovery of what exterior wall tilesets exist beyond the five residential
prefixes in `SKIN_PREFIXES`, and a routing table from `BuildingClass` to skin
prefix list.

### 4.8 Windows: skin matching on government/commercial buildings
Low priority. Window wall tiles currently default to `walls_exterior_house_01_*`
regardless of building class. Once wall skins for flat-roof buildings are done
(4.7), window skin selection follows the same route.

## 5. The pipeline in full (what it is, what's missing)

```
GeoJSON input
    buildings.geojson  (footprints + OCC_CLS + HEIGHT)
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
    ground layer:  solid tile + tuft + blend masks per square      DONE
    road layer:    flat road tile (no corner/junction logic)        NOTE: vanilla roads
                   are also staircase-shaped on diagonals —        are also staircase-shaped
                   this is correct PZ behaviour, not a bug
    building layer: walls (skinned per building)                   DONE
    room subdivision (BuildingPlan)                                DONE
    interior walls + spanning-tree doors                           DONE
    exterior door openings (DoorWall tile)                         DONE
    exterior door objects (fixtures_doors_*)                       MISSING
    exterior windows (WindowN/W wall + fixtures_windows_*)         DONE 2026-09-20
    chunkdata density map                                          DONE
    biome map                                                      DONE
    spawn points                                                   DONE
    furniture / containers                                         MISSING
    roofs (z=1): flat for commercial/govt, pitched for residential DONE 2026-09-20
      pitched: slope tiles + gables (west end open)               DONE (west end open)
      flat: ceiling tile only (roofs_03_* object pass pending)    PARTIAL
        |
        v
Mod output
    <mod>/42/mod.info
    <mod>/common/media/maps/<n>/
        X_Y.lotheader   (rooms, buildings, tile names)
        world_X_Y.lotpack (tile data per square)
        biomemap_X_Y.png
        map.info, spawnpoints.lua, objects.lua
        WorldGenOverride.lua
        |
        v
pzmap2dzi render  (run after each generation)
        |
        v
placement.html
    drag-to-place overlay on vanilla world         DONE
    cell-snap drag                                 DONE
    Apply emits complete java command              DONE
    sub-cell precision                             NOT IMPLEMENTED (by design)
    end-to-end doc                                 DONE (PLACEMENT_TOOL.md)
```

---

## 6. Key facts that have been proven the hard way

These cost sessions to learn. Do not re-derive them.

**Format**
- Chunk column-major order: `index = cx * chunksPerSide + cy`. Row-major is
  wrong. Byte round-tripping cannot catch a transposition bug.
- `SPAN_LEVELS_FULL` is the only correct encoder policy — 100% of 4065 cells.
  All other policies score 75–79%.
- `spawnpoints.lua` uses the legacy 300-tile cell grid. Using 256 silently
  produces spawn points the game ignores.

**Buildings**
- Exterior door squares carry both `Wall` and `DoorWall` in vanilla, but TWO
  wall objects on one edge means the plain wall wins collision. Only one wall
  object per edge. `replaceTile` strips the raster wall before placing the door.
- Full-height exterior walls are correct. The engine does its own cutaway.
  `walls_exterior_house_01_1` is the right tile. Do not chase "walls too tall"
  as a bug — it was the renderer, four separate times.
- "Indoors" comes from the room definition in the lotheader, not from a roof.
  Rooms work without roofs.
- `WindowN`/`WindowW` are the primary flags on window wall tiles — they do NOT
  also carry `WallN`/`WallW` as separate flags. Discovery must use `WindowN`
  alone, not `WindowN && WallN`.
- PZ road tiles (`blends_street_01_*`) are all surface texture variants — there
  are no autotile corner/junction tiles. Diagonal roads are staircase-shaped in
  vanilla too. Road index numbers 48, 53, 54, 55, 80, 85, 86, 87 etc. are all
  texture dither variants of the same flat surface. This was verified by
  scanning cell 13_23 in detail. Do not chase road corner tiles as a missing
  feature.

**Ground**
- Grass outranks road in the engine's precedence table. Grass blend masks belong
  on the road square, not on the adjacent grass square.
- `cleanChunk` strips tufts from Sand squares in game. Tufts on yard squares
  are fine; they simply disappear.
- Every square at z=0 must carry at least one object or `WorldGenChunk` hands
  the whole 8×8 chunk to `genRandomChunk`. Squares outside the raster must be
  filled, not skipped.

**Tools**
- `PropsProbe.find` is hard-capped at 3 hits per cell. A 3-square sample cannot
  produce a run longer than 1. Always check what a tool CAN return before
  believing what it DID return.
- Sample spatial data contiguously. A strided sample aliases.
- pzmap2dzi: `top_view_color_mode: avg` produces greyscale. Use `carto-zed`.
- pzmap2dzi: re-render must delete existing tiles first or it silently skips
  everything (0.17s, "stale tiles: 0") and reuses old output.
- pzmap2dzi: `deploy` unpacks `openseadragon.zip` to `openseadragon.js`
  (not `.min.js`).
- `Probe lotheader` only prints the first 4 and last tile name — grep its
  output for specific tile names will miss everything in between. Use
  `command grep -a -o -e "tilename_prefix_[0-9]*" file.lotheader` on the
  raw binary instead.
- `Probe findprop` searches by property KEY name, not tile name prefix. Road
  tiles have no unique property that isn't shared with grass (`exterior`,
  `solidfloor`). Use binary grep on lotheader files to find cells containing
  specific tile names.
- `server.py` requires flask/waitress from the pzmap2dzi virtualenv. Run it
  with the venv active or it fails with `ModuleNotFoundError: No module named
  'flask'`.

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
| Placement tool | `map-output/html/placement.html` (tracked in repo) |
| Placement doc | `map-output/html/PLACEMENT_TOOL.md` (tracked in repo) |
| pzmap2dzi | `~/Documents/PZMapCreation/pzmap2dzi/` (gitignored, local only) |
| Flask server | `map-output/html/server.py` |
| Placement URL | `http://localhost:8880/placement.html` |

---

## 8. Probe commands reference

```fish
# GIS pipeline
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport 2048 <cellX> <cellY>

# Inspect a specific square (tile + properties)
java -cp out pzformat.Probe square "$PZ/media" "$GISMAP" 200_200 <x> <y> <z>

# Find squares with a given property
java -cp out pzformat.Probe findprop "$PZ/media" "$GISMAP" 200_200 <propName>

# Room distribution (use our cells or vanilla cells)
java -cp out pzformat.RoomCluster "$GISMAP" 200_200 200_201 201_200 201_201

# Door check
java -cp out pzformat.DoorProbe "$PZ/media" "$GISMAP" 200_201 <x1> <y1> <x2> <y2>

# Render a window to PNG
java -cp out pzformat.Probe render "$GISMAP" "$PZ/media/texturepacks" \
    200_201 <x> <y> <size> 0 0 ~/Downloads/render.png

# Room wall geometry (verify wall positions against expected)
java -cp out pzformat.Probe roomgeom "$PZ/media" "$GISMAP" 200_200

# Scan a full column at z=1 — the workhorse for roof measurement.
# Run it over the WHOLE building, never a partial range: a scan cut short
# makes the span wrong and every distance-from-edge formula with it (§10).
for y in (seq 200 209)
    set r (java -cp out pzformat.Probe square "$PZ/media" $MAPS 42_36 33 $y 1 2>/dev/null)
    if not string match -q "*empty square*" $r
        echo "=== (33,$y) ===" && echo $r | head -8
    end
end

# Find cells containing a specific tile name (binary grep on lotheaders)
command grep -rl -e "tilename_prefix" "$PZ/media/maps/Muldraugh, KY" \
    | command grep -e .lotheader

# List all tile name variants in a cell's lotheader (binary grep)
command grep -a -o -e "blends_street_01_[0-9]*" \
    "$PZ/media/maps/Muldraugh, KY/13_23.lotheader" | sort -u

# Vanilla survey (all cells round-trip)
java -cp out pzformat.Probe survey "$MAPS"

# BuildingPlan self-test (standalone, no game files)
java -cp out pzformat.BuildingPlan
```

---

## 9. Decisions that were made and why

**Cell snap only, no sub-cell placement in placement.html.** The generator takes
cell coordinates only — `ox = cx * 256` in `GisCells`. A finer snap control
would let you aim at positions the generator cannot reproduce, which is
dishonest. The right fix is implementing `originTileX/Y` through the generator,
not softening the UI. Left for later.

**Full-wipe not glob-delete for stale cells.** `wipeMapDir` deletes the entire
map directory before each run rather than globbing known file extensions. A glob
list must be updated every time a writer is added — the biome maps under
`maps/` were exactly this failure: added after the cell writers, not in the
original description of the bug. The full wipe is immune to this. A guard
requires recognised artifacts to be present before deleting anything.

**`replaceTile` strips the raster wall, does not append the door.** Vanilla door
squares have both Wall and DoorWall present, but two wall objects on one edge =
solid. Strip first, then place door. Interior doors were always correct (the
interior pass writes one or the other, never both).

**Room names are meaningful (`kitchen`, `bedroom`, not `room`).** PZ's loot
system keys off room name. Generic `room` gives no loot tables. `BuildingPlan`
emits typed names from the first call. No separate loot work is needed beyond
placing containers.

**Road corner tiles are not a thing in PZ.** Investigation 2026-09-20 confirmed
that `blends_street_01_*` tiles are all surface texture variants (80, 85, 86,
87, 48, 53, 54, 55 etc. all appear randomly across a road surface). Diagonal
roads are staircase-shaped in vanilla too. There is nothing to implement here.

**`StaticModule.prefab` is unused.** Decompiled. `PrefabStructure` is not
present in the decompiled output and `WorldGenChunk` does not reference prefabs.
Furniture will be authored geometry via `appendTile`.

**`BuildingClass` centralises roof and layout routing.** Added 2026-09-20.
Maps `OCC_CLS` + `HEIGHT` + `outbuilding` flag to `RESIDENTIAL`, `AGRICULTURE`,
`OUTBUILDING`, or `FLAT_ROOF`. All other code (roof pass, future wall skin
selection, future room recipe) consumes this enum rather than re-implementing
the taxonomy. Height threshold is 6 m (≈ two storeys).

---

## 10. Roofs — the measured tile rules (2026-09-18)

Everything here was read off complete vanilla columns in cell 42_36. Do not
re-derive it by inference; two separate off-by-one bugs in this session came
from exactly that.

**Geometry.** The ridge runs along the building's LONGEST axis, so the slopes
fall across the short axis and the gables sit on the short ends. `bw >= bh`
means an east-west ridge. Facing direction is irrelevant — an earlier version
keyed off `faceTheRoad` and got long buildings wrong.

For a span of N rows across the slope axis, row `i`:
`distFar = i`, `distNear = (N-1) - i`, and the far face owns the ridge row
(`distFar <= distNear`).

**Slope tiles** — `roofs_30_02`, on every footprint square, with the ceiling
tile beneath. Measured at x=33, y=200..209 (ten rows): `29,28,27,26,25` then
`4,3,2,1,0`.

```
far face:   roofs_30_02_(29 - distFar)
near face:  roofs_30_02_(distNear)        <- flat, NO +1
```

**Gable tiles** — `walls_exterior_roofs_30_03`, carrying `WallW`, on the column
one square OUTSIDE the footprint. Measured at x=34, y=200..209: `61,60,59,58,57`
then `52,51,50,49,48`.

```
far face:   walls_exterior_roofs_30_03_(61 - distFar)
near face:  walls_exterior_roofs_30_03_(48 + distNear)   <- base 48, not 49
```

**Gable trim** — `roofs_accents_30_01`, on the SAME square as the gable wall.
Index is the gable wall's index minus 48, confirmed down the whole column
(61→13, 60→12, ... 52→4, 51→3, 50→2, 49→1, 48→0).

**There is no triangular wall tileset.** The building's own last column at z=1
carries only ceiling and slope tiles — no walls at all. The triangle is drawn
entirely by the gable tiles on the outside column.

**Ceiling tile** — `ceilings_01_0`, selected by `attachedFloor + solidfloor +
diamondFloor` WITHOUT `exterior`. The `exterior` flag is what industrial roof
tiles (`roofs_03_*`) carry; excluding it keeps selection on the residential
ceiling sheet.

**Flat roof** — `FLAT_ROOF` buildings write only `ceilings_01_0` at z=1 for
now. `TilePalette.roofFlatNames` collects `roofs_03_*` tiles (92 usable). A
future pass will write these once a vanilla flat-roofed building has been
measured.

### Two traps, both of which cost real time

**Scan the FULL column or the span is wrong.** Both off-by-one bugs came from a
scan cut short at y=208. Symptom: ridge does not meet at the peak.

**Verify tile names against the sprite atlas.** Roof tile names must pass
`sprites.contains()`. A tile can have a `.tiles` definition and no sprite —
it renders as a red question mark. `TilePalette` exposes `roofSlopeNames`,
`roofGableNames`, `roofAccentNames`, and `roofFlatNames` as sprite-verified
sets. **Read those counts before loading the game.**
