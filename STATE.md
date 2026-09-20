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
- Exterior doors placed on the road-facing side of eligible rooms
  (livingroom, kitchen, hall, laundry, barn, etc. — never bedroom).
- Doors WORK. Walked through them in game 2026-08-19. The earlier bug
  (appending DoorWall beside the raster wall left the plain wall winning for
  collision) is fixed in `replaceTile`.
- Biome map (`biomemap_X_Y.png`) — makes terrain continuous across the mod
  boundary; the engine generates matching vegetation on authored cells.
- Spawn points on road squares. `spawnpoints.lua` uses the legacy 300-tile
  grid (not B42's 256) — found the hard way.
- `chunkdata_X_Y.bin` written with correct density per built-up chunk so the
  engine treats those chunks as urban rather than overwriting them.
- **Pitched roofs at z=1.** Ceiling tile plus slope tiles over every building
  footprint square, gable ends and trim on the column outside each end of the
  ridge. Cells now encode two levels (`newHeader(names, 0, 1)`). See §10 for
  the measured tile formulas — they are the whole of it.
- `wipeMapDir()` — clears the whole map directory before each run so stale
  cells from a previous origin don't accumulate. Guard refuses to delete
  unless a `.lotheader`, `.lotpack`, `map.info` or `maps/` is present.
  Added 2026-09-18.

**Cell origin is a parameter.** `GisCells.run()` takes `originCellX`,
`originCellY`. Default 200,200. `Probe giscells` reads them as args[8]/args[9].

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

---

## 3. What is NOT done — the honest list

### Roads: no turning tiles
Roads are rasterised as `cover == ROAD` squares, all given the same flat road
tile (`floorRoad` from `TilePalette`). PZ has dedicated turning and junction
tiles (T-intersections, corners, etc.) that prevent the jagged diagonal look.
Currently diagonal roads are dithered into roadside foliage instead of using
the correct corner tiles. This is known, visible, and unaddressed.

### Buildings: west gable end does not close
2026-09-18. Roofs are done and correct apart from one end.

The EAST gable renders properly — trim runs up both roof edges to the peak and
the triangle is filled. The WEST gable does not, and you can see into the
building from that side.

The east placement is measured: vanilla 42_36 puts `walls_exterior_roofs_30_03`
tiles on the column one square PAST the footprint (x=34 for a building ending
at x=33). The west placement is **inferred, not measured** — `GisCells` writes
a `WallW` onto the building's own first column (`bx`), reasoning from PZ's
edge-based wall convention. 42_36's west side abutted a neighbour so there was
no west gable in the data to read.

Strong suspicion: the west gable also belongs OUTSIDE the footprint, at
`bx - 1`, making both ends symmetric. That would be a one-line placement
change. The measurement that settles it: find a vanilla building with an
exposed west gable and scan the column one square left of its footprint, the
same scan that worked for the east (§8).

### Buildings: no furniture or containers
Nothing in the codebase places objects inside rooms. `TileIndex.container()`
reads the `container` property, but no caller places objects. Placing
furniture would activate PZ's loot system automatically — loot tables key off
(room name, container type), and room names are already correct.

### Buildings: barn floor is hardwood
When room type is `barn`, `GisCells` still writes `floorInterior` (hardwood).
Should be `floorGrass` or `floorDirt`. One-line fix in `GisCells`.

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

---

## 4. What comes next, in priority order

### 4.1 Road corner and junction tiles
Roads look jagged at turns because every road square gets the same flat tile.
PZ has a full set of road corner, T-junction, and crossroad tiles. The fix:
in `GisCells`'s raster loop, detect each road square's neighbours and select
the appropriate directional tile instead of always using `floorRoad`.

The measurement before coding: use `Probe findprop` or `Probe square` on a
vanilla Muldraugh road corner to confirm which property or tile name
distinguishes corner tiles, and which neighbour configuration maps to which
tile. Read `TilePalette`'s selection logic — corner tiles may need new palette
slots alongside `floorRoad`.

### 4.2 Furniture and containers
Place objects inside rooms by type. `TileIndex.container()` already reads the
`container` property. The measurement pass first: `Probe square` / `findprop`
on a vanilla kitchen and a vanilla bedroom to record what objects are actually
placed there and on which squares (against wall, centred, etc.).

Loot activates automatically from (room name, container type). Room names are
already correct. No separate loot work needed.

`StaticModule.prefab` has never been read in the decompiler. STATE §3 flagged
this as a decision that needed the decompiler before choosing. **Read it before
writing a furniture placer.** A prefab places furnished rooms at load time; the
editor cannot inspect that. A placer authors geometry the editor can validate
and undo. The decision matters.

### 4.3 Fix BuildingPlan self-test
The layout engine self-test is currently failing on 40×20 footprints
(bathroom aspect violation). Fix this before adding furniture — the layout
engine's correctness guarantees are not trustworthy while it fails its own test.

### 4.4 Barn floor
One-line fix: when room type is `barn`, write `pal.floorGrass` instead of
`pal.floorInterior`. Do this in `GisCells` where it stamps interior floor tiles.

### 4.5 Close the west gable
Small and well-characterised. See §3 "west gable end does not close" for the
diagnosis and the exact scan that settles it.

## 5. The pipeline in full (what it is, what's missing)

```
GeoJSON input
    buildings.geojson  (footprints + OCC_CLS)
    roads.geojson      (line features)
    area.geojson       (clip boundary)
        |
        v
GisImport.rasterise()
    cover[][] — BUILDING / ROAD / WATER / GROUND
    northWall[][] / westWall[][] — building perimeter edges
        |
        v
GisCells.run()
    ground layer:  solid tile + tuft + blend masks per square      DONE
    road layer:    flat road tile (no corner/junction logic)        MISSING
    building layer: walls (skinned per building)                   DONE
    room subdivision (BuildingPlan)                                DONE
    interior walls + spanning-tree doors                           DONE
    exterior doors on road-facing face                             DONE
    chunkdata density map                                          DONE
    biome map                                                      DONE
    spawn points                                                   DONE
    furniture / containers                                         MISSING
    roofs (z=1), pitched, with gables                           DONE (west end open)
        |
        v
Mod output
    <mod>/42/mod.info
    <mod>/common/media/maps/<name>/
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
(61→13, 60→12, ... 52→4, 51→3, 50→2, 49→1, 48→0). This is edge trim that runs
along the roof's lower edge; it is NOT the triangular fill.

**There is no triangular wall tileset.** The building's own last column at z=1
carries only ceiling and slope tiles — no walls at all. The triangle is drawn
entirely by the gable tiles on the outside column. Two sessions were nearly
spent looking for a fill tileset that does not exist.

**Ceiling tile** — `ceilings_01_0`, selected by `attachedFloor + solidfloor +
diamondFloor` WITHOUT `exterior`. The `exterior` flag is what industrial roof
tiles (`roofs_03_*`) carry; excluding it keeps selection on the residential
ceiling sheet.

### Two traps, both of which cost real time

**Scan the FULL column or the span is wrong.** Both off-by-one bugs came from a
scan cut short at y=208, which made a 10-deep building look 9 deep and
manufactured a spurious `+1` on both near-face formulas. The symptom was a
ridge that did not meet at the peak and a strip of ceiling visible along the
eave.

**Verify tile names against the sprite atlas.** Roof tile names were originally
built by string concatenation, skipping the `sprites.contains()` check that
every other palette entry goes through. A tile can have a `.tiles` definition
and no sprite — it renders as a red question mark and logs `missing tile
roofs_30_02`. `TilePalette` now exposes `roofSlopeNames`, `roofGableNames` and
`roofAccentNames` as sprite-verified sets, and the palette printout reports the
count of each. **Read those counts before loading the game** — a zero there
explains an empty result in one line instead of a screenshot round trip.

**Beware gable tiles placed on ground squares.** An early attempt wrote gable
walls onto adjacent grass squares and produced giant transparent diagonal slabs
across the map. Gables go on the ridge-end columns only.

**`appendTile` is hardcoded to z=0.** Use `appendTileAt(cell, x, y, z, ...)` for
roof work. The z=0 version is relied on by the interior wall and door passes and
was left alone deliberately.

---

**Ridge along the longest axis, not the facing direction.** An earlier version
derived ridge orientation from `faceTheRoad`, which put the slopes across the
wrong axis on long buildings. The rule is architectural: slopes on the long
sides, gables on the short ends, independent of which way the building faces.

**Roof tiles go through the same sprite verification as everything else.**
They were briefly special-cased, built by string concatenation with no
`sprites.contains()` check, and produced fields of red question marks. Any tile
the generator writes is selected from a sprite-verified set or skipped.
