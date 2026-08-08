# pzformat — AI session handoff

Paste this whole file at the start of a new session. It is the single source of
truth for what this project is, what already exists, what has been proven, and
what comes next. Nothing described here as existing needs to be rebuilt.

---

## 1. What this project is

**The goal is a Project Zomboid map editor that is better than the official
tools** (TileZed / WorldEd / the new WorldZed). Build 42, retail 42.20.

It is a Java library plus toolset — no dependencies, Java 21, 224 self-tests.
Solo project, no CI, no code review. Built in three layers, in this order:

1. **Format layer** — read and write every file PZ uses for map data, verified
   byte-for-byte against the full vanilla dataset. **Done and trustworthy.**
2. **Semantic layer** — know what a tile *means* (wall, door, window, container,
   facing) rather than just which sprite it draws. **Done for the properties
   that matter so far.**
3. **Application layer** — edit maps safely, render them, validate them, and
   generate them. **In progress. This is where all remaining work is.**

Why the semantic layer is the whole competitive argument: `.tiles` property data
(`IsWall`, `IsDoor`, facing, container type, NorthWall/WestWall pairing) is what
makes an editor smarter than a paint program — auto wall-joining, room
detection, and validation rules like *doorway with no adjacent floor*, *room
with no exit*, *wall gap that isn't a door*. That is exactly where the official
tools are weakest, and where a new editor earns its place. TileZed lets you
paint an invalid map; this should not.

### The GIS pipeline is a side project, not the goal

`GisImport` / `GisCells` turn public-domain GIS data into a playable map mod.
It exists for two reasons:

- **A fast, honest exercise of the whole stack.** It authors cells from nothing
  — headers, tile tables, floors, walls, rooms, buildings, chunk grid, spawn
  points, biome maps — so every layer gets used end to end rather than
  unit-tested in isolation.
- **It is how the engine's mod contract was learned.** Almost everything now
  known about how B42 accepts an authored map (§5) was discovered by making a
  generated map load and then diagnosing why it didn't look right. An editor
  needs that contract regardless of who authors the tiles.

It is also a genuinely useful quick-start path — real town in, playable map out
— so it stays. But when the two tracks conflict, the editor wins, and GIS
features are only worth building when they teach something the editor needs.

---

## 2. Where things stand

### Editor track

Working today, verified:

- Read any vanilla or mod cell, modify it, write it back; the game accepts it.
- **Layer-aware editing** (`CellEditor`): `setFloor`, `setWall(edge, tile)`,
  `removeWall(edge)`, `addObject`, `clearObjects`, `clearSquare`, `fillFloor`,
  `outlineRoom`. Replacing a floor leaves walls, overlays and objects intact;
  removing a wall takes its door leaf or window pane with it.
- **Grouped undo/redo** that restores byte-identical output. A 36-square fill
  undoes in one step.
- Isometric rendering to PNG — correct projection, trimmed-sprite offsets,
  mixed 1×/2× atlases, z-stepping.
- Square-level semantic resolution: floor, north/west walls, doorways, windows,
  fixtures, containers, movement blocking, room membership.

The proof, on Muldraugh cell 42_40 — re-flooring a 6×6 living room:

```
before:      2 north walls, 12 west walls, 1 door, 1 window, 24 objects
after:       2 north walls, 12 west walls, 1 door, 1 window, 24 objects
destructive: 0              0              0        0         0
diff after undo: none
lotpack bytes identical to the original file: true
```

Not built yet: TMX/PZW interop, `.tiles` writers, auto wall-joining, validation
rules, any interactive UI, live rendering. Rendering is offline PNG only.

**One open thread flagged and never closed.** `outlineRoom` places north walls
at `y0+h` and west walls at `x0+w` — the far edges belonging to the *next*
square out. That follows from edge-based walls, but it is **reasoning, not
measurement**, and reasoning is what produced the x/y transposition and the
`attachedN` bug. The check is cheap: read a real Muldraugh room's actual wall
positions and compare against what `outlineRoom` generates for the same
rectangle. Do this before building anything on top of room creation.

### GIS track

The pipeline works end to end. A GIS area becomes 4 cells that load in game
with correct roads, buildings, ground and vegetation, and **no visible boundary
against the surrounding procedurally generated land**. The most recent win was
the biome map (§6), which is what made terrain continuous across the map edge —
the engine now generates vegetation on our cells from the same rules it uses
everywhere else.

Buildings are the weak point on both tracks: currently one bounding box per
footprint with derived perimeter walls. No roof, no interior subdivision, no
doors, no windows.

---

## 3. What is next, in order

1. **Verify `outlineRoom` against a real room.** Cheap, and everything about
   building generation and room authoring sits on it. Editor track.

2. **Cleanup with evidence already in hand.** `TreeScatter` / `TreePalette`
   place ~7,800 trees that `genMapSquare` deletes on load, and
   `WorldGenOverride.lua` is superseded by the biome map. Delete both,
   regenerate, and confirm in game that nothing changes — that *proves* they
   were dead weight rather than assuming it.

3. **Buildings.** The substantial piece. Read a vanilla house first
   (`Probe square` / `findprop` against Muldraugh) before writing anything.
   There is an open design fork:
   - a room-splitting generator that decomposes footprints into rectangles,
     places roofs, doors on street-facing walls, windows, interior doorways; or
   - `StaticModule.prefab`, the engine's own structure placement mechanism,
     **never tried, not yet read in the decompiler.**

   For the editor these are not equivalent. A room-splitting generator produces
   authored geometry the editor can then edit, validate and undo. `prefab`
   produces something the engine assembles at load time, which the editor cannot
   inspect the same way. **Read `StaticModule` in the decompiler before choosing** —
   the decision should not be made from the name alone.

4. **Room type names.** Generic `"room"` gives no loot tables. Meaningful types
   are needed for a generated map to be playable and for the editor to validate.

---

## 4. How to work on this project

This matters more than it usually would. **Eight real bugs got through 224
automated tests. All eight were caught by comparison against an independent
source, never by more testing of the same kind.**

- **Check what vanilla does before building anything.** Retail map data and the
  game's own Lua under `media/lua/server/WorldGen/` are readable and
  authoritative. Skipping this has cost three multi-session detours.
- **Prefer the recipe to the output.** Measuring Muldraugh describes one
  hand-authored town. Reading the generator's Lua describes the generator, works
  anywhere, and survives a game update.
- **Predict the number before running the command.** "squares should be
  3,145,728 and edge-filled 671,718" turns a run into a test.
- **Ask what would falsify a result.** A test that cannot fail proves nothing.
  An underdetermined linear system is trivially consistent.
- **Report rates over the population that can discriminate.** A 67.63% floor
  from trivially-empty files made noise look like signal for two sessions.
- **Change one thing per test.**
- **The renderer is a hypothesis too.** It has been wrong twice. When the
  picture looks wrong, the picture may be what is wrong.
- **PZ is Java.** `unzip projectzomboid.jar`, Vineflower, and
  `grep -rl <symbol> --include='*.class'` beat inference from file bytes. Ten
  minutes with a decompiler has repeatedly answered what statistics could not.
- **Byte-identical round-tripping proves a format was read and written
  faithfully. It says nothing about whether it was *interpreted* correctly.**

Working style that has been productive: propose an approach, name the check that
would prove it wrong, run it, then write code.

**Patch delivery:** patches come as Python scripts that abort unless each anchor
matches exactly once. Fish has no heredocs, so multi-line edits need a file.
Files are handed over as downloads to `~/Downloads`, then copied into the repo.

---

## 5. Environment and machine layout

Garuda Linux, **fish shell**, Java 21 (toolchain targets 21; library is Java 17+
compatible), IntelliJ.

| What | Path |
|---|---|
| Git repo | `~/Documents/PZMapCreation` (source in `src/main/java/pzformat/`) |
| Scratch / probes | `~/Downloads/ZOMBOIDSTUFF` |
| Extracted game jar | `~/Downloads/ZOMBOIDSTUFF/pzjar/` |
| Decompiled classes | `~/Downloads/ZOMBOIDSTUFF/decompiled/` |
| Vineflower | `~/Downloads/ZOMBOIDSTUFF/vineflower.jar` (1.12.0, fat jar) |
| Game install (`$PZ`) | `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid` |
| Vanilla maps (`$MAPS`) | `$PZ/media/maps` — Muldraugh, KY is the 4065-cell reference |
| GIS inputs | `~/pzgis/` — `area.geojson`, `buildings.geojson`, `roads.geojson`, `fetch_gis.py` |
| Generated mod | `~/Zomboid/mods/PZGisImport/` |
| Renders | `~/pzrender/` |
| Saves | `~/Zomboid/Saves/<mode>/<timestamp>/` |

```fish
set -U PZ   "/home/kaatlev/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid"
set -U MAPS "$PZ/media/maps"
```

If `$MAPS` expands empty, a *global* shadows the universal. Diagnose with
`set -S MAPS`; clear with repeated `set -e MAPS`, then re-`set -U`.

### Gotchas that have each cost real time

- **Run from the repo root.** `-cp out` is relative; from `~` it fails with
  `ClassNotFoundException` and nothing else.
- **`ls` is aliased to eza**, which rejects `-t` combined with a bare `-d`. Use
  `command ls` when a glob result matters. A wrong `ls` once hid 33 of 34 saves
  and derailed an investigation for several exchanges.
- **fish has no heredocs.** Multi-line scripts must go in a file.
- **Patch backups**: `.gitignore` carries `*bak`. Several `.treebak` /
  `.spawnbak` files got committed before that was broadened.

### Mod layout that works (copied from Maplewood)

```
~/Zomboid/mods/PZGisImport/
├── 42/mod.info                         version metadata ONLY, versionMin=42.0
└── common/media/maps/PZGisImport/
    ├── map.info                        needs fixed2x=true; lots=Muldraugh, KY
    ├── <x>_<y>.lotheader
    ├── world_<x>_<y>.lotpack           NOTE the different naming convention
    ├── maps/biomemap_<x>_<y>.png       biome + zone, one pixel per tile
    ├── spawnpoints.lua
    ├── objects.lua                     currently {}; vanilla's is 4 MB
    └── WorldGenOverride.lua            superseded; remove
```

Two naming conventions in one directory, and a mismatch fails silently.

---

## 6. What already exists — do not rebuild

### Java source, `src/main/java/pzformat/`

Refresh the real list with `find src -name '*.java' | sort` — this is the
inventory as of the last session.

**Format layer**: `LE`, `LEW`, `LotHeader`, `LotPack`, `LotPackAnalysis`,
`CellData`, `PackFile`, `PackAnalysis`, `TileDefs`, `TileBin`, `TileBinAnalysis`,
`TrailerAnalysis`, `Json`

**Semantics / rendering**: `TileIndex`, `Square`, `SpriteAtlas`, `SpriteJoin`,
`SpriteNames`, `CellRenderer`, `RoomGeometry`, `PropsProbe`

**Editing**: `CellEditor`, `EditDemo`, `MakeTestMod`, `Locate`, `SpawnMark`

**GIS pipeline**: `GisImport`, `GisCells`, `TilePalette`, `GroundPalette`,
`TreePalette`, `TreeScatter`, `BiomeMapWriter`

**WorldGen data**: `WorldGenFeatures`, `WorldGenBiomes`, `BiomePalette`

**Probes**: `Probe` (CLI), `SelfTest`, `Survey`, `RoundTrip`, `PaletteScan`,
`TreeSurvey`, `GroundSurvey`, `LegacyPackProbe`

Notable internals worth knowing before touching them:

- `LE.java` tracks read position and dumps hex on failure — every parse error
  names a byte offset. `LE.hexDump()` at that offset is the debugging loop.
- `CellData.fill` replaces a square's **entire** tile stack. That is correct for
  "clear" and wrong for everything else — it is why an early in-game test
  punched holes through houses. `CellEditor` exists because of this.
- `CellEditor` routes every mutation through `apply()`, so undo is uniform:
  an edit is the before/after state of the squares it touched.
- `GisCells.assertNoEmptySquares` reimplements the engine's chunk gate (§7)
  against the **reparsed** cell, not the in-memory one.

### Python on the machine

- `~/pzgis/fetch_gis.py` — the only permanent Python. Fetches buildings and
  roads for an area polygon. **Already fixed** to probe all nine TIGERweb layers
  and deduplicate on geometry (§11). Do not rewrite it from scratch.
- `~/Downloads/patch_*.py` — transient patch scripts, one per edit, safe to
  delete after applying.

### Probe commands

Standalone (run directly, not via `Probe`):

| Command | Purpose |
|---|---|
| `PaletteScan <media> <prefix>` | Tiles under a prefix: sprite, kind, CustomName, Material, flags |
| `PaletteScan <media> --prop <key>` | Distinct values of a property across all tiles |
| `PaletteScan <media> --find <text>` | Tiles whose CustomName contains text |
| `TreeSurvey <media>` | `tree` size classes mapped to tilesets and species |
| `GroundSurvey <mapdir> <cell>...` | How vanilla composes ground squares |
| `WorldGenFeatures <media> [CAT]` | Parsed feature tile lists |
| `WorldGenBiomes <media> [biome]` | Parsed biome feature references with `p` |
| `BiomePalette <media> [biome] [n]` | Composed sample squares for a biome |
| `LegacyPackProbe <file \| dir>` | Walk legacy `.pack`, verify against PNG offsets |

Via `Probe`: `survey`, `mapdir`, `lotheader`, `lotpack`, `roundtrip`, `pack`,
`packinfo`, `sprites`, `props`, `square`, `findprop`, `gisimport`, `giscells`,
`render`, `editdemo`.

**The two that matter most for verification:**

```
square   <mediadir> <mapdir> <X_Y> <x> <y> <z>     dump every tile + properties
findprop <mediadir> <mapdir> <X_Y> <prop>          find squares having a property
```

Point them at `"$MAPS/Muldraugh, KY"` to see what vanilla does, and at the
generated mod to see what we do. **Comparing the two is the single most
effective technique in this project.**

### The loop

```fish
cd ~/Documents/PZMapCreation
python3 ~/Downloads/patch_x.py            # if patching
javac -d out (find src -name '*.java')
java -cp out pzformat.SelfTest 2>&1 | tail -3
rm -rf ~/Zomboid/mods/PZGisImport
java -cp out pzformat.Probe giscells ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson "$PZ/media" ~/Zomboid/mods PZGisImport
java -Xmx4g -cp out pzformat.Probe render ~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport "$PZ/media/texturepacks" 200_200 80 157 64 0 0 ~/pzrender/x.png
```

The render is fast feedback but **only shows authored data**. Anything WorldGen
generates — trees, bushes, grass, ore — is invisible to it and must be checked
in game.

Refresh before starting:

```fish
cd ~/Documents/PZMapCreation
find src -name '*.java' | sort
git log --oneline -15
```

---

## 7. Engine behaviour — CONFIRMED in the decompiler

### What gates generation

In `zombie/iso/worldgen/WorldGenChunk.generateChunks`:

```java
if (ch.hasEmptySquaresOnLevelZero()) {
    genRandomChunk(...);      // full procedural generation
} else {
    genMapChunk(...);         // treated as authored — still reads the biome map
    cleanChunk(ch, "Sand",   "vegetation_groundcover_01");
    cleanChunk(ch, "Road_*", "vegetation_groundcover_01");
}
```

`IsoChunk.hasEmptySquaresOnLevelZero()` returns true if **any** of a chunk's 64
squares has no object at z=0 or below. One gap flips the whole chunk to
procedural. The engine treats a `null` square as empty, so a mirror assertion
must check a square object **exists**, not merely that it is non-empty.

**`cleanChunk` is harmless to us — CONFIRMED.** It matches on the floor's
`FloorMaterial` property, not the sprite name, and removes only objects whose
sprite name starts with `vegetation_groundcover_01`:

```java
String floorMaterial = floor.getSprite().getProperties().get("FloorMaterial");
if (floorMaterial != null && floorMaterial.matches("^Road.*")) { ... }
```

Our grass carries `FloorMaterial = Grass_Dark`. Authored roads are never
touched. This closed the oldest open question in the project.

### Registration as an authored cell

`MapFiles.load()`: `*.lotheader` → `createLotHeader()`, the only thing that
expands bounds and registers into `infoHeaders`. `*.lotpack` → `infoFileNames`
only. `chunkdata_*` → no bounds effect, no `bgHasCell`.

`IsoMetaGrid.CreateStep1` merges `MapFiles` backwards with `putAll`, so for a
contested cell the *earlier* map directory wins. Scanning starts at
`Core.gameMap` and recurses through `getLotDirectories()`. Our `map.info` has
`lots=Muldraugh, KY`, so vanilla is scanned alongside us and bounds are the
union. **CONFIRMED working** — `console.txt` shows `PZGisImport` followed by the
Knox County chain.

### Geometry

Chunk = 8×8 tiles. Cell = 256×256 tiles = 32×32 chunks. **PZwiki's File Formats
page documents B41 and is wrong for B42** on magic bytes, string terminators,
cell size, offset width and chunk size.

### Coordinate systems — the bug that cost three sessions

`spawnpoints.lua` `worldX`/`worldY` are **legacy 300-tile cell coordinates**.
`posX`/`posY` are offsets within that 300-tile cell.

Emitting `worldX = 200` for cell 200_200 put the player at world tile
200 × 300 = 60000 while the cells occupy 51200..51711 — about 8,800 tiles east,
in pure procedural terrain. That looked exactly like "WorldGen destroyed my map"
and produced a recorded blocker that never existed.

```java
int worldTileX = cellX * 256 + localX;
worldX = worldTileX / 300;
posX   = worldTileX % 300;
```

**Diagnostic that found it:** the save's `map/` subdirectories are named by
chunk. They were 7530–7570 (world tile ~60240 ≈ 200.8 × 300) instead of the
expected 6400–6463. **When a map "does not load", read the chunk directory
numbers first.** They say where the player actually was.

The same 300-vs-256 legacy compatibility appears in `MapFiles.postLoad`, which
converts bounds with `minX * 256.0F / 300.0F`.

---

## 8. The WorldGen data model

All plain Lua under `$PZ/media/lua/server/WorldGen/`, and all parseable —
`WorldGenFeatures` and `WorldGenBiomes` do it.

### Features — `features/<CATEGORY>/<n>.lua`

Categories: `BUSH`, `GROUND`, `NONE`, `ORE`, `PLANT`, `TREE`. 89 features across
90 files. Each is a list of tile names:

```lua
local medium_grass = {
    main = { "blends_natural_01_32", "blends_natural_01_37",
             "blends_natural_01_38", "blends_natural_01_39" },
}
worldgen.features.GROUND["medium_grass"] = medium_grass
```

**The nine GROUND features are exactly the four-tile groups** that a statistical
survey of Muldraugh independently found — a good cross-check of both:

| feature | tiles (`blends_natural_01_*` unless noted) |
|---|---|
| `sand` | 0, 5, 6, 7 |
| `dark_grass` | 16, 21, 22, 23 |
| `medium_grass` | 32, 37, 38, 39 |
| `light_grass` | 48, 53, 54, 55 |
| `dirt` | 64, 69, 70, 71 |
| `dirt_grass` | 80, 85, 86, 87 |
| `clay` | 96, **101**, 102, 103 |
| `water` | `blends_natural_02_0, 5, 6, 7` |
| `burnt` | `floors_burnt_01_8, 13, 14, 15` |

`blends_natural_01_101` is **clay** — which is why an early palette that picked
it produced brown ground.

### Biomes — two separate tables

- `biomes/worldgen/<n>.lua` → `worldgen.biomes[...]`. 76 entries (10 files plus
  ore-level variants using `parent`). **Have a GROUND feature.** These are what
  `WorldGenOverride.lua` selects.
- `biomes/map/<n>.lua` → `worldgen.biomes_map[...]`. **No GROUND feature** —
  they are applied to authored cells, where the floor already exists. These are
  what the biome map selects.
- `biomes/subbiomes/<n>.lua` → shared components (`grass`, `bushes`,
  `small_trees`, `no_tree`, …).

A biome entry:

```lua
features = {
    GROUND = { { f = worldgen.features.GROUND.medium_grass, p = 1.0 } },
    PLANT  = { { f = worldgen.features.PLANT.grass_medium,  p = 0.3 } },
    BUSH   = { { f = worldgen.features.BUSH.bush_regular,   p = 0.01 } },
    TREE   = { { f = worldgen.features.TREE.maple_jumbo_xxl, p = 0.00125 }, ... },
}
```

`params.placements` decides which **floor tiles** a feature category may sit on,
as globs with `!` exclusions:

```lua
placements = {
    GENERIC = { "blends_natural_01_*" },
    PLANT   = { "!blends_natural_01_0", "!_5", "!_6", "!_7",
                "!blends_natural_01_64", "!_69", "!_70", "!_71" },
}
```

That exclusion list is `sand` and `dirt`. It is why dirt tiles end up bare.

---

## 9. The biome map — how terrain is really assigned

**CONFIRMED** by decompiling `zombie.iso.worldgen.maps.BiomeMap`, `BiomeRaster`,
and `WorldGenChunk`.

`<map>/maps/biomemap_<cellX>_<cellY>.png`, 256×256, one pixel per tile.

```java
private static final int NUM_BANDS = 2;
for (int i = 0; i < 2; i++)
    this.pixels[x * 2 + i + y * span] = (byte) pixel[i];
```

**RED = biome band, GREEN = zone band, BLUE ignored.** `BiomeMap.Type` is
`BIOME(0)`, `ZONE(1)` — a band selector, not a flag. Both bands index the same
`biome_map_config` table in `media/lua/server/metazones/BiomeMapConfig.lua`, via
`getBiomeName(index)` and `getZoneName(index)`.

That explains vanilla's colour families: `(153,153,153)` is one entry used for
both bands; `(254,141,254)` is biome 254 `dirt` inside zone 141 `FarmLand`;
`(179,64,64)` is biome 179 `pr_forest` inside zone 64 `ForagingNav`.

`getRaster` searches every map in `IsoWorld.getMap()` (semicolon separated) and
takes the first file that exists. **A missing file logs a debug line and returns
null** — safe and incremental.

Config values worth knowing: 0 Water, 64 ForagingNav, 96 `$random`/DeepForest,
115 `townhouse`/TownZone, 128 `farmmix_forest`/Farm, 141 …/FarmLand,
153 `ph_forest`/PHForest, 179 `pr_forest`/PRForest, 204 `farm_forest`/FarmForest,
217 `birch_forest`/BirchForest, 243 `organic_forest`/OrganicForest,
254 `dirt`/ForagingNav, 255 `primary_forest`/DeepForest.

### What it drives — and what it takes away from us

`genMapChunk` runs on **authored** chunks and reads the biome map:

```java
int[] biomes = map.getZones(ch.wx, ch.wy, Type.BIOME);
BiomeMapEntry lookup = map.getEntry(biomes[tileY * 8 + tileX]);
```

Then per tile, for each of TREE, BUSH, PLANT:

```java
if (!canPlace(biome.placements().get(type), floorName))
    square.DeleteTileObject(currentTiles.get(type));      // wrong floor -> delete
else
    retval = applyBiome(biome, type, ...);                // else replace with biome's own
// on SUCCESS, delete every other tree/bush/grass-like object on the square
```

So:

- **We author**: floors, roads, walls, buildings, rooms. The engine does not
  replace floors — map biomes have no GROUND feature.
- **The engine owns**: trees, bushes, grass — deleted and replaced per tile from
  the biome map.

**OBSERVED in game:** with biome maps written, walking outward from the road
passes through the authored gradient (town → farm_forest → ph_forest →
primary_forest), with correct species, boulders from ORE features, and **no seam
at the cell boundary**. The biome map takes precedence over
`WorldGenOverride.lua` on authored chunks.

`replaceSquare` is narrower: only `biome.getReplacements()`, mapping specific
sprite names to alternatives. Not a wholesale floor swap.

**Consequence for ground choice:** because `placements` excludes `sand` and
`dirt` from PLANT and BUSH, any dirt tile we author ends up **bare**, with
surrounding grass tufts stripped off it. Scattering dirt at 14% (a figure
measured from hand-authored Muldraugh) produced exactly that: bare diamonds
through otherwise mixed forest, stopping dead at the cell boundary.
`GroundPalette` now uses grass groups only; dirt is retained as named constants
for deliberate use — tracks, yards, unpaved roads.

---

## 10. Format layer — CONFIRMED

| Format | Verification |
|---|---|
| `.lotheader` | 4065 / 4065 cells; byte-identical |
| `.lotpack` | 4065 / 4065 cells, 4,162,560 chunks; byte-identical |
| `.pack` | **24 / 24 retail atlases**, both layouts; byte-identical |
| `.tiles` binary | 73,644 tiles; all 37,060 with a text sibling match 100% |
| `.tiles` text | 61,418 tiles from 7 files, 616 tilesets |
| `biomemap_X_Y.png` | Format read in the engine; writer produces loadable maps |
| Room geometry | Wall offsets across 86 rooms, both far sides confirmed |

### `.lotheader`

```
char[4]  "LOTH"
int32    version          1
int32    tileCount
         tileName '\n'    x tileCount
int32    levelsAbove      8 in all 4065 cells
int32    levelsBelow      8
int32    minLevel         actual z of chunk index 0; negative for basements
int32    maxLevel
int32    roomCount
  room:  name '\n'; int32 floor; int32 rectCount; int32 x,y,w,h x rectCount;
         int32 objectCount; int32 a,b,c x objectCount
int32    buildingCount
  building: int32 roomCount; int32 roomIndex x roomCount
byte[1024]  32x32 per-chunk grid, values 0..10
```

90,827 rooms and 90,827 room references — every room belongs to exactly one
building. **Wall convention: south wall belongs to the next square down, east
wall to the next square right.**

### `.pack` — both layouts

The entry table is identical in both, **and so is the per-page `int32` after
`numEntries`**. Believing that field was PZPK-only was the entire bug: the
reader skipped it, read its value as the first entry's name length, and derailed
on byte one.

```
[optional] char[4]   "PZPK"
[optional] int32     version
int32                numPages
page * numPages:
    lenString        pageName
    int32            numEntries
    int32            unknown            1 almost always, but 0 on three pages of
                                        UI.pack — NOT a version constant
    entry * numEntries:
        lenString    entryName
        int32 x, y, w, h, ox, oy, fx, fy
    [PZPK]   int32   pngByteLength
    byte[]           pngBytes
    [legacy] int32   0xDEADBEEF         page separator
```

Legacy PNGs have **no length prefix** — walk chunk headers to IEND. Validation
that can fail: after walking `numEntries` entries the offset must land
**exactly** on a PNG magic. All 20 pages across the 11 legacy files did.

`0xDEADBEEF` absence after the final page is **UNVERIFIED** — an IEND walk
landing on EOF is indistinguishable from one landing 4 bytes short then
consuming a separator. `pageSeparator` is recorded per page, not derived.

### Sprite scale is a property of the pack, not the sprite

`scale = packName.contains("2x") ? 0.5 : 1.0`. The old `fx >= 128 ? 0.5 : 1.0`
heuristic held only while the pack list was `Tiles1x` (64px) and `Tiles2x`
(128px); jumbo tree art is 1× at 192×256 and got shrunk to ground-level blobs.

**`SpriteAtlas.MAP_PACKS` is a hardcoded list.** A pack absent from it is
invisible to the renderer regardless of whether `PackFile` can parse it.

Still open in the format layer: the 3-int32 room object records; `.tiles`
writers; TMX interop; `objects.lua` / `roomtones.lua` / `streets.xml` parsing.

### `chunkdata_X_Y.bin` — closed as a question, not a format

Zombie population data. **No influence on WorldGen.** `natives/libPZPopMan64.so`
exports `..._n_1saveCell`, `..._n_1loadChunk`, `..._n_1addZombie`;
`LoadGameScreen.lua:231` offers a debug item `DeleteChunkDataXYBin`;
`MapFiles.load()` registers it with no bounds or `bgHasCell` effect. Four probe
generations killed every structural hypothesis. If ever needed, **decompile
`zombie.popman.*`** rather than probing bytes again.

Trap that wasted two sessions: 2749 of 4065 files have N=0, so naive success
rates carry a **67.63% floor that means nothing**. Report over N>0 files. Second
trap: "fits `2 + 1024 + N*64`" is arithmetically identical to "fits `2 + M*64`",
since 1024 = 16×64.

---

## 11. Tiles, sprites, trees

**Tile definitions and sprite atlases are independent sets.** 61,418 tiles carry
properties; 46,540 sprite names exist. A tile can pass every property filter and
have no pixels — it writes correctly, round-trips byte-identically, satisfies
`hasEmptySquaresOnLevelZero()`, and renders as a checkerboard. No existing test
catches that. `SpriteNames.load()` builds the sprite set; `TilePalette` requires
membership.

### Authored data cannot choose tree species — CONFIRMED

Vanilla Muldraugh 35_35 authors exactly `vegetation_trees_01_8` … `_11`. No
species tile appears in any vanilla lotheader. Those generic tiles carry `tree`,
`solid`, `attachedFloor`, `BlocksPlacement`, `vegitation` — and **have no sprite
in any atlas**. The engine substitutes species and mature size at runtime.

The `e_redmapleJUMBO_1` / `e_virginiapineJUMBOXXL_1` sheets (11 species × 8 size
classes) are **render-time art**. Authoring them produces canopies lying on the
grass, because a 192×256 full frame is 1× art that overhangs its square, not 2×
art to be halved.

Given §9, authoring trees at all is pointless — `genMapSquare` deletes them.

### Useful discriminating properties

| Need | Discriminator |
|---|---|
| Grass vs dirt | `grassFloor` bare flag |
| Standalone ground vs edge blend | `solidfloor` present, `FloorOverlay` absent |
| House interior floor | `Material = Wood`; `Brick` is bathroom/kitchen tiling |
| Trunk vs ground cover | `solid` on the `vegetation_trees_01` sheet |

`CustomName` exists on interior floors but is **absent on all natural ground and
tree tiles**, so it cannot be the general selector.

Wall encoding: `wall` is a bare flag with no orientation. An earlier version
keyed off `attachedN`/`attachedW` and validated at 99.5% against room geometry
**while being wrong** — decoration hangs on walls, so it occupies the same
squares. A correlated proxy can pass a test for the wrong reason.

---

## 12. The GIS pipeline

**Step 1 — draw the area** at <https://geojson.io>, save to `~/pzgis/area.geojson`.
One 256×256 cell ≈ **0.0023° lat × 0.0029° lon** around 38°N.

**Step 2 — fetch:** `python3 ~/pzgis/fetch_gis.py ~/pzgis/area.geojson ~/pzgis`

- **Buildings** — USA Structures (FEMA / Oak Ridge / USGS). Public domain.
  Carries `OCC_CLS` / `PRIM_OCC`. Only structures over 450 sq ft; machine
  extracted, so footprints can be a metre or two off.
- **Roads** — Census TIGER/Line. Public domain.

⚠️ **TIGERweb splits roads across layers 0–8** by class and scale. The original
script queried layer 2 only and silently returned zero features for anything
else — indistinguishable from "there is no road here". Pondlick Rd is in
**layer 7**, registered as `Co Hwy 26`. `fetch_gis.py` now probes all nine and
merges, deduplicating **on geometry, not `LINEARID`** (a road crossing the box
in two segments shares an ID).

**Step 3 — always pass `area.geojson`.** Feature services return whole features
intersecting the bbox, so one road can run for kilometres beyond your area.

**Step 4 — preview** with `gisimport`; a typical house should be **10–15 tiles
across**.

**Step 5 — `giscells`.** Cells are placed at origin **200_200**, clear of Knox
County.

Licensing is still open: verify GIS dataset terms per state and choose a licence
before publishing. Separately, **never redistribute extracted PZ tilesheets or
art** — read from the user's install. Format reverse-engineering is well
tolerated in this community; shipping TIS's assets is not.

Last generation run:

```
7 buildings (1530 tiles), 1 road (2808 tiles), extent 495 x 424
ground palette: 3 base groups, 54 overlays
cells written: 4   squares: 262144   rooms: 8   edge-filled: 52264
ground overlays: 132395 (50.5%)
biome maps: 4   town 17374, edge 28242, forest 77031, deep 87233
spawn: cell 200_200, world tile 51312,51389 (chunk 6414,6423)
```

---

## 13. Corrections — beliefs that turned out wrong

Acting on any of these wastes real time.

| Old claim | Status |
|---|---|
| "WorldGen paves over authored terrain" is the project blocker | **FALSE.** Never existed. It was the spawn coordinate bug (§7). |
| `spawnpoints.lua` uses B42 256-tile cells | **FALSE.** Legacy **300-tile** grid. |
| The 11 unparsed `.pack` atlases are cosmetic UI art | **FALSE.** Two are the tree atlases. All 24 now parse. |
| Interior floor renders as a missing-texture checkerboard | **FALSE.** It was `"Grey Diagonal Tiles"`, rendering correctly. |
| Map data can specify tree species and mature size | **FALSE.** Generic tiles only; the engine substitutes (§11). |
| Authoring vegetation into the lotpack works | **FALSE.** `genMapSquare` deletes and replaces TREE/BUSH/PLANT per tile (§9). |
| Ground should imitate Muldraugh's measured tile mix | **FALSE.** Muldraugh is hand-authored. Drive from biome definitions (§8, §9). |

Known-stale, not yet cleaned up: `TreeScatter` / `TreePalette` still place
~7,800 trees the engine deletes; `WorldGenOverride.lua` is still written and is
superseded by the biome map.

---

## 14. Test log

| # | Test | Result |
|---|---|---|
| 1 | Round-trip 4065 vanilla cells | Byte-identical — **but** x/y were transposed and it passed anyway |
| 2 | Edit a vanilla cell, load in game | Rendered the intended change only |
| 3–4 | GIS import with/without area clipping | Clipping required; projection sound |
| 5–6 | Mod structure | Maplewood layout registers the map |
| 7 | Play generated map | Buried in forest |
| 8 | Render cell 201_200 | Buildings correct |
| 9 | `WorldGenOverride.lua` grass_plain | Open grassland; no buildings seen |
| 10 | chunkdata probes ×4 | All hypotheses killed |
| 11 | Decompile the engine | Found the real gate |
| 12 | Edge fill + assertion | 2,474,010 → **3,145,728** squares; assertion passes |
| 13 | Palette sprite requirement | 167 candidates dropped |
| 14 | Load after edge fill | Still nothing — chunk dirs 7530–7570 |
| 15 | **Spawn coordinate fix** | Chunk dirs **6396+**. On the map |
| 16 | Spawn on a road square | **Road and building correct in game.** Blocker disproved |
| 17 | Palette by semantics | Grass green, floor `"Hardwood Floor"` |
| 18 | Legacy `.pack` | **24/24**, byte-identical, 224 tests pass |
| 19 | `SpriteAtlas.MAP_PACKS` extended | `sprites not found: 0 / 50` |
| 20 | Tree species machinery ×3 | All wrong |
| 21 | `findprop` vs vanilla 35_35 | Generic `vegetation_trees_01_*` |
| 22 | Ground survey, 262,144 squares | Four-tile groups, 43.3% overlay, never >1 |
| 23 | Ground variation authored | Patchwork — groups rolled per square, not per region |
| 24 | Parse WorldGen Lua | GROUND features === the surveyed groups |
| 25 | Decompile `BiomeMap` / `BiomeRaster` | R=biome, G=zone, one pixel per tile |
| 26 | Write biome maps, load in game | **Gradient visible, no seam, ore veins generating** |
| 27 | Drop dirt groups | Bare diamonds gone; forest floor continuous |

---

## 15. Todo

### Immediate — evidence already in hand

- [ ] Verify `outlineRoom` far-side wall placement against a real Muldraugh room
- [ ] Delete `TreeScatter` and `TreePalette`; the engine deletes their output
- [ ] Stop writing `WorldGenOverride.lua`; the biome map supersedes it
- [ ] Regenerate and confirm in game that nothing changes — that proves they
      were dead weight rather than assuming it

### Buildings — the next substantial piece

Current output is one bounding box per footprint with derived perimeter walls.
**Read a vanilla house first** (`Probe square` / `findprop` against Muldraugh)
before writing anything.

- [ ] Read `StaticModule.prefab` in the decompiler; decide the fork on evidence
- [ ] Decompose footprints into multiple room rectangles
- [ ] Roofs; exterior doors on street-facing walls; windows
- [ ] Interior subdivision and doorways
- [ ] Meaningful room *type* names — generic `"room"` gives no loot tables
- [ ] Vary wall and floor materials by occupancy class

### Editor — the actual goal

- [ ] Auto wall-joining: pick the right variant from neighbours (corner, end,
      junction). All the information needed is already present.
- [ ] Validation rules: doorway with no adjacent floor, room with no exit, wall
      gap that isn't a door. **This is where the editor earns its place.**
- [ ] TMX read/write for interop with the official tools
- [ ] `.tiles` writers
- [ ] Interactive rendering — pan, zoom, live edit. Lower risk now that the PNG
      renderer proved the geometry. Knox County is ~1,300 cells and B42 added
      negative z-levels, so this needs sprite batching, an atlas cache and
      viewport streaming from day one; a naive per-tile draw dies immediately.
- [ ] **UI architecture is undecided.** Options discussed but never chosen:
      Spring Boot backend + WebGL canvas (gets multi-user editing, the one thing
      WorldEd can't do; costs atlas transfer bandwidth), or native LWJGL/libGDX.
      A real working store (SQLite or chunked binary) rather than thousands of
      TMX files, with TMX/PZW kept purely as an interop boundary.

### Biome map quality

- [ ] Drive the **floor** from the same biomemap pixel, via `biomes_map`
      `placements`, so ground and vegetation come from one source
- [ ] Use `OCC_CLS` / `PRIM_OCC` to choose biome per parcel (Agriculture → Farm)
      rather than distance bands alone
- [ ] Dither or band the biome transition rather than hard distance thresholds
- [ ] Consider the ZONE band properly — it drives foraging

### Other

- [ ] Road auto-tiling — corner, T-junction, end, edge by neighbour bitmask
- [ ] Populate `objects.lua` (currently `{}`; vanilla's is 4 MB) — likely
      related to room loot tables and worth checking alongside room type names
- [ ] `worldmap.xml` — `mapdir` reports it missing; imported areas do not appear
      on the in-game map without it
- [ ] Fast round-trip regression (currently ~14 min)
- [ ] Publish B42 format documentation (PZwiki is B41 and wrong)
- [ ] Verify GIS dataset licensing per-state; choose a licence

---

## 16. Independent sources available for checking

These are the things that have caught every real bug. Use them before writing
code, not after.

- **Retail map data** — `"$MAPS/Muldraugh, KY"`, 4065 cells, via `Probe square`
  and `Probe findprop`.
- **The game's own Lua** — `$PZ/media/lua/server/WorldGen/` and
  `$PZ/media/lua/server/metazones/BiomeMapConfig.lua`. Plain text.
- **The decompiled engine** — `~/Downloads/ZOMBOIDSTUFF/decompiled/`, Vineflower
  at `~/Downloads/ZOMBOIDSTUFF/vineflower.jar`.
  `grep -rl <symbol> --include='*.class'` finds the class first.
- **Artefacts the engine writes** — save `map/` chunk directory numbers, and
  `console.txt` for map registration order.
- **`Unjammer/PZ_Vanilla_map_b42`** — the whole vanilla map decompiled to a
  WorldEd project. A free regression corpus for TMX work when that starts.
- **PZ Reverse Mapper** (Nexus mod 337) — reads `.lotheader` and `.lotpack`,
  handles both 300 and 256 cells, rebuilds biomemaps. A second independent
  opinion on any cell.

### The eight bugs, and what caught each

1. **x/y transposition** — 4065 cells round-tripped byte-identical while every
   coordinate was mirrored. Caught by checking room rectangles against the tiles
   beneath them.
2. **`.pack` "verified"** — checked only against fixtures this project
   generated, which proves the reader agrees with the writer and nothing else.
3. **Wall encoding** — keying off `attachedN`/`attachedW` validated at 99.5%
   while being wrong. Decoration hangs on walls.
4. **chunkdata** — thousands of hypothesis tests on zombie population data.
5. **Spawn coordinates** — three sessions of in-game tests all measured a
   location 8,800 tiles from the map. Caught by reading the save's chunk
   directory numbers.
6. **Tree species art** — three iterations of species machinery built on the
   assumption that maps author species tiles. One `findprop` against a vanilla
   cell would have shown otherwise immediately.
7. **Ground tile weights** — measured from hand-authored Muldraugh and applied
   to procedurally generated land. The generator's actual recipe was sitting in
   readable Lua the whole time.
8. **Renderer scale heuristic** — showed trees as ground-level shrubs for two
   rounds, and 43 sprites as missing, because of a width heuristic and a
   hardcoded pack list.
