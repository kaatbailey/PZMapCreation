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

**CLOSED 2026-08-10 — the offsets are correct. See §18.** The thread was
already stale when written: `Probe roomgeom` had made the measurement and
§10 recorded it.

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
- **Sample spatial data contiguously.** A strided sample can alias. A 4-tile
  transect through a dithered ground boundary returned four identical
  materials in a row and was read as a region band; the contiguous row is
  `M D M D M M M D D`. Two ground transects were wrong this way (§26).
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

### The two GIS commands

Recovered from shell history three separate times on 2026-08-11 and
guessed wrong twice before that. They are not interchangeable: `gisimport`
writes a schematic PNG for eyeballing, `giscells` writes the actual mod.

```fish
java -cp out pzformat.Probe gisimport \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    ~/pzgis

java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport
```

Current dataset: 7 buildings (6 Residential, 1 Agriculture), 1 road,
495x424 tiles, generating 2x2 cells at 200_200..201_201.

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

### Reading vanilla spawnpoints — the inverse conversion

§7 recorded the forward transform only, which is why finding a known-good
vanilla building has been done by eye. The inverse is the useful direction:
a spawnpoint is a coordinate someone at TIS chose deliberately, and vanilla
spawns are inside buildings.

```
$MAPS/Muldraugh, KY/spawnpoints.lua        plain Lua, readable
```

```java
int worldTileX = worldX * 300 + posX;      // legacy 300-tile cell -> world tile
int cellX      = worldTileX / 256;         // B42 256-tile cell
int localX     = worldTileX % 256;         // square within that cell
```

Same for Y. Feed `cellX_cellY` to `Probe lotheader` and `localX localY` to
`Probe square`. **UNVERIFIED**: that vanilla B42 keeps spawnpoints in this
file and this format at all. Confirm by reading it before relying on it; if
the shape differs, that fact belongs here.

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
| GIS footprints can be rasterized at their real-world bearing | **FALSE.** Room rects are `x, y, w, h` with no rotation field; walls are N/W edges only. Off-axis footprints stair-step and their rooms do not register cleanly (§17). |
| Vanilla `spawnpoints.lua` uses `worldX`/`worldY` plus 300-tile offsets | **FALSE for B42 retail.** Muldraugh's file has `posX`/`posY`/`posZ` only, as absolute world tiles. Our write path uses the legacy form and works, so the reader evidently accepts both (§18). |
| A cell's per-square room id identifies the room | **FALSE.** `-1` on every interior square sampled. Membership comes from lotheader rects only (§18). |
| The trees visible on the generated map are the engine's | **UNPROVEN, and evidence points the other way.** WorldGen skips chunks with no empty squares, and GisCells fills every square; our tree tiles are present in the lotpack. A2 step 1 is blocked until settled (§25). |
| A ground square is one base tile plus at most one overlay | **FALSE.** Vanilla stacks several base tiles per square at region boundaries — one square in 42_40 carries five. That stacking IS the blend mechanism (§24). The verdict stands but **the reason stated here is itself wrong**: a square carries exactly one solid tile. The others are mask tiles with `FloorOverlay` (§26). |
| Ground region choice tracks distance from habitation | **UNSUPPORTED.** Suggested by a town-vs-forest comparison, then not borne out by a fine transect. Region driver is still unknown (§24). **Still unsupported, but the fine transect no longer counts against it** — it was an aliasing artifact (§26). Nothing supports it either. For authored cells the driver is a human painting land use; for us it is GIS land use. |
| Terrain continuity was the remaining boundary problem | **INCOMPLETE.** The biome map made terrain continuous; POPULATION was never addressed. Zombies spawn on vanilla ground and stop dead at our boundary (§22). |
| Ground groups can be selected per square from their measured frequencies | **FALSE.** The 70/21/10 split across Muldraugh is a split BETWEEN regions, not within them. Vanilla shows 16/16 identical `Grass_Dark` in a row; ours changes three times in eight squares (§21). |
| Dropping the dirt groups was the fix for scattered bare diamonds | **SYMPTOM ONLY.** The cause is the missing region layer. Dirt is correct ground for tracks and yards and should return once regions exist (§21). |
| Off-axis rooms exist in vanilla Muldraugh | **UNSUPPORTED — printed by a broken guard, twice.** Both times the alignment test was measuring interior partitions, not skew. Current best answer: no off-axis room found (§19). |
| `alignment()` is a working prototype of A4's "expressible as a rect" rule | **OVERSTATED.** It took four attempts to stop false-positiving on vanilla, and it cannot test 80.3% of rects (§19). |
| Cell 200_200 can test `outlineRoom` | **FALSE.** GIS buildings do not go through `outlineRoom`, and their bbox rects do not match their diagonal wall runs. The measurement is void, not negative (§18). |
| Multi-user editing is a reason to prefer a Spring Boot + WebGL UI | **SUPERSEDED.** CHARTER §3, 2026-08-08: no multi-user concurrent editing. The UI fork stays open on other grounds. |
| A `Grass_Medium` band sits at x=112–124 in 42_40 inside `Grass_Dark`, with Dark on both sides | **FALSE — sampling artifact.** A 4-tile stride aliased a dithered boundary. There is no band (§26). |
| Filtering ground samples on `FloorMaterial` measures regions | **FALSE.** Mask tiles carry `FloorMaterial` too. Filter on `solidfloor`. This flaw is behind three of the four ground transects and behind §21's unexplained "25 `FloorMaterial` lines from 16 probes" (§26). |
| The biome map is what removed the map-edge seam | **INCOMPLETE.** `Blending.changeGround` feathers solid tiles 0–3 squares in from any edge shared with a procedural chunk. A second mechanism is doing visible work there (§26). |
| `GroundSurvey`'s "never more than one overlay, 0 of 257,703" describes ground stacking | **MEASURED THE TUFT LAYER ONLY.** True of `blends_grassoverlays_01`; it never counted mask tiles, which live on the base sheet (§26). |
| The engine will blend our authored ground at load | **FALSE.** `Blending.applyBlending` fires only where a chunk borders a **procedural** chunk, and it replaces solid tiles rather than writing masks. Every mask must be authored (§26). |

Known-stale, not yet cleaned up: `TreeScatter` / `TreePalette` still place
~7,800 trees the engine deletes; `WorldGenOverride.lua` is still written and is
superseded by the biome map.

**Do not re-derive this from a grep.** `TreeScatter` and `TreePalette` have
live callers in `GisCells` and `BiomeMapWriter`, and `treeAt` is genuinely
read and written into the square stack. That does **not** contradict the
line above: "superseded" here means the engine discards the output on load
(§9), not that the code is unreachable. A session on 2026-08-10 ran that
grep, found the callers, and wrongly concluded A2's premise was false. See
§20 for what the callers actually mean for the deletion.

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
| 28 | Spawnpoint -> cell arithmetic, then `square` | Landed inside `livingroom`, cell 42_40. Absolute-tile reading confirmed |
| 29 | `roomgeom` on vanilla 42_40 | 86 rooms; far offsets win 67.0 vs 10.2 and 83.9 vs 3.7. `weldingworkshop` exact on all four corners |
| 30 | `roomgeom` on generated 200_200 | **Void, not negative.** Diagonal walls inside bbox rects; 24.1 vs 22.2 is noise |
| 31 | Guards added, rerun 42_40 and 200_200 | Vanilla unchanged and CORRECT at 6.6x/22.4x; 200_200 refuses to conclude |
| 32 | `PaletteScan --prop Facing` | Objects only: N/S/E/W, ~6,200 tiles, no diagonals. Walls carry orientation as `Wall*` flags instead |
| 33 | `findprop WallSE` on 42_40 | `PaintingType = pillar`. A post, not an edge. `edgeOf` returns NONE for it |
| 34 | Alignment sweep, attempt 1 (interior fraction) | 674 non-aligned. **All false positives** — bathrooms, halls, barns with one partition |
| 35 | Alignment sweep, attempt 2 (slope of per-row mean) | 0 non-aligned, but also cleared the known diagonal. r2 0.227 |
| 36 | Alignment sweep, attempt 3 (concentration) | GIS caught at 0.19/0.20; vanilla down to 53, still false positives |
| 37 | Alignment sweep, attempt 4 (both axes required) | **0 non-aligned / 29,928 tested.** All 3 GIS diagonals still caught. §17 check 1 closed |
| 38 | Load with `WorldGenOverride.lua` removed | **No seam, foliage flows cleanly across the boundary.** The file was doing no work (§21) |
| 39 | 8 adjacent ground squares, generated 200_200 | Dark, Dark, Dark, **Light**, Dark, Dark, **Medium**, Medium — three changes in eight |
| 40 | 16 adjacent ground squares, vanilla 35_35 | `Grass_Dark` 16/16. No alternation at all |
| 41 | 16 samples spaced 16 apart across vanilla 35_35 | Grass region, then road, then a dirt region alternating `Dirt`/`Dirt_Grass` per square |
| 42 | Observed zombie spawns at the map boundary | Zombies on vanilla ground, none on ours, boundary exactly where our road ends |
| 43 | `survey` chunk grid histogram, vanilla | 0..10 present. 96.4% zero; 1/2/3 dominate the rest; 8/9/10 are ~0.005% |
| 44 | `survey` chunk grid histogram, generated | **All 4096 bytes zero across all 4 cells** |
| 45 | `writeChunkDensity`, then regenerate and survey | 0→3935 (96.1%), 1→89, 2→72. Predicted 40-70 twos and 80-150 ones before running |
| 46 | Fresh world, walk to a generated building | **Zombies at the building, none on the way.** First ever seen on the generated map |
| 47 | 6 samples across vanilla town cell 42_40 | `Grass_Medium`, Sand, `Grass_Dark` — regions vary WITHIN a cell, contiguous at 40-tile spacing |
| 48 | Fine transect x=100..140 in 42_40 | **Squares carrying up to five stacked ground tiles.** Not a clean region boundary |

---

## 15. Todo

### Immediate — evidence already in hand

- [x] Verify `outlineRoom` far-side wall placement against a real Muldraugh room
      — **done 2026-08-10, offsets correct (§18). A3, A4, A5 unblocked.**
- [ ] Delete `TreeScatter` and `TreePalette`; the engine deletes their output
      — **not wholesale: `BiomeMapWriter` needs `distanceToStructure` (§20)**
      — **BLOCKED. Do not start. Tree ownership is unresolved (§25).**
- [ ] **Settle tree ownership.** Walk a line of known authored tree
      positions in game and see whether trees stand at exactly those
      coordinates. Unblocks or kills A2 step 1 (§25)
- [x] Stop writing `WorldGenOverride.lua`; the biome map supersedes it
      — **CONFIRMED 2026-08-11 in game: removed, no seam, foliage clean.**
      Remove the write at `GisCells:220` and `writeWorldGenOverride` (§21)
- [ ] **Ground region layer.** Group selection must be spatial, not per
      square. Grass_Dark / Medium / Light are region distinctions, not
      texture (§21)
- [ ] Check whether the biome map already supplies the region signal before
      building a noise field (§21)
- [ ] Restore the dirt groups once regions exist; gate them to tracks and
      yards rather than open country (§21)
- [ ] Explain 25 output lines from 16 ground probes — some vanilla squares
      carry two ground tiles, which the survey's "never more than one
      overlay" result does not obviously allow (§21)
- [ ] Open question surfaced by A2: do authored tree tiles and engine biome
      vegetation target the same squares? (§20)
- [ ] Regenerate and confirm in game that nothing changes — that proves they
      were dead weight rather than assuming it

### Buildings — the next substantial piece

Current output is one bounding box per footprint with derived perimeter walls.
**Read a vanilla house first** (`Probe square` / `findprop` against Muldraugh)
before writing anything.

- [ ] Read `StaticModule.prefab` in the decompiler; decide the fork on evidence
- [ ] Count Muldraugh rooms with `rectCount > 1` forming a diagonal run — is
      the orientation constraint hard or merely dominant? (§17)
- [ ] Scan wall tiles for their declared orientation values (§17)
- [ ] `FootprintSnap` — one module, called by GIS import and by the editor
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
      **The multi-user clause above is superseded** — CHARTER §3 ruled out
      concurrent editing on 2026-08-08 (see §13). The fork itself is still
      open; only that argument for the Spring Boot side is dead.
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

- [ ] Scene rotation pass in GIS import: dominant-grid histogram, then rotate
      buildings, roads and area polygon together before rasterizing (§17)
- [x] `roomgeom` guards: refuse to conclude when the two offsets are within
      a few points, and when wall runs are not axis-aligned (§18)
      — **margin guard done and working. Alignment guard took four attempts
      and still has 53 known false positives; one-token fix pending (§19).**
- [ ] **A3 prerequisite:** `TileIndex.edgeOf` falls back to
      `attachedN`/`attachedW` for tiles with no `Wall*` flag — the exact
      proxy its own comment warns against. Unreachable from `wallOn`, but
      `edgeOf` is public and A3 will call it on neighbours. Split it into a
      separate `decorationEdge()` or return NONE (§19)
- [ ] **A3 prerequisite:** confirm the tileset variant cycle. In
      `walls_exterior_house_01` the pattern is `WallW, WallN, WallNW,
      WallSE` every 4, openings every 16. Wall-joining needs that structure;
      per-tile flags alone do not say corner-vs-end-vs-junction (§19)
- [x] Apply the `&&` fix to `alignment()` and rerun the sweep (§19)
      — **done 2026-08-11. 53 false positives to 0; §17 check 1 closed.**
- [ ] Fix the stale class comment on `LotHeader` — it says the B42 trailer is
      left unparsed; `readB42Meta` parses all of it (§18)
- [x] Test whether `chunkGrid` is zombie density: mean over a town cell vs a
      forest cell should differ sharply (§18)
      — **CONFIRMED 2026-08-11 by three independent lines (§22).**
- [x] **Write zombie density into `chunkGrid`.** Currently all zeros, which
      is why our cells have no zombies. Must follow land use, not the
      vanilla histogram (§22)
      — **done 2026-08-11, confirmed in game (§23). Mechanism proven;
      calibration untested.**
- [ ] Calibrate density values. 2 near buildings is at the low end of
      vanilla's range and one hamlet is not a town (§23)
- [ ] Should density vary by occupancy class? The import distinguishes
      Agriculture from Residential and currently treats them alike (§23)
- [ ] Measure how vanilla's nonzero density correlates with what a place
      is — the recipe, not the histogram (§4, §23)
- [ ] Read the biome map's town/edge/forest/deep classification as the
      region signal for ground groups (§21, §23)
      — **do not start here. The region driver is not known to be distance
      from habitation (§24).**
- [ ] **Ground blending investigation.** How does vanilla stack ground
      tiles, and what decides which pairs blend? Prefer the recipe:
      `blends_natural_01` naming and properties, and the engine code that
      assembles ground squares at load. Measuring more of Muldraugh has
      now produced three complicated hypotheses in one session (§24)
- [ ] Identify what `Sand` represents mid-cell in 42_40 — parking, yard,
      or shore. If land use rather than distance, the region design
      changes again (§24)
- [ ] Extract `BiomeMapWriter`'s distance banding into a reusable method.
      Worth doing regardless: three consumers want a region signal (§24)
- [ ] Room rects must cover interior squares only — a bbox over a
      non-rectangular footprint marks outdoor squares as room members (§18)
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

---

## 17. Building orientation — the grid admits no rotation

Raised 2026-08-10. Partly CONFIRMED from the format already recorded here,
partly UNVERIFIED and needing a vanilla sample.

### CONFIRMED, from §10 and the renderer

A room is `int32 x, y, w, h`. No rotation field, no polygon, no vertex list.
A room can only be expressed as a union of axis-aligned rectangles. Walls are
north/west edges per square, so the geometry has no diagonal wall primitive
whatever sprite art exists. The camera is fixed isometric: a wall sprite is
drawn for one facing and cannot be rotated at render time.

So an off-axis footprint is not a cosmetic problem. Walls are objects on
squares and will happily stair-step, but the room they enclose either fails
to register or registers as a staircase of 1-wide rects with wall runs
between the steps. That breaks room detection, room-type loot tables, and
every validation rule planned for the editor. **The current generated map
has this pattern.**

### UNVERIFIED — the two checks, before any code

1. **Is the constraint hard or merely dominant?** Count Muldraugh rooms with
   `rectCount > 1` whose rects form a diagonal run (successive rects offset
   by ~1 on both axes, with `w` or `h` near 1). **Zero across 90,827 rooms
   means the snap may refuse outright. Nonzero means it is a strong default
   with an override**, and one such room must be read before deciding which.
2. **Which orientations do wall tiles declare?** `PaletteScan "$PZ/media"
   walls_exterior_house_01` and read the discriminators the tiles carry.
   Expectation: north, west, and a corner post; nothing diagonal.
   **Falsifier: any orientation value that is none of those.** If one
   exists, the wall model underneath A3 is wrong before A3 is written.

Note §11: `wall` is a bare flag with no orientation, and `attachedN` /
`attachedW` validated at 99.5% while being wrong. Whatever discriminator
check 2 turns up must be shown to be the tile's own declaration, not another
correlated proxy.

### `FootprintSnap` — one module, two callers

The same invariant serves GIS import and interactive authoring, which is the
§2 test for whether GIS work earns its place. A proposed footprint goes
through the snap and comes out aligned, or is refused.

- **GIS import.** Per-footprint min-area-rectangle alignment is **wrong** —
  it squares each building against itself and randomises them against each
  other and the roads, which looks deliberate and is worse than the zigzag.
  Real towns align to a street grid. Take each footprint's min-area-rect
  angle mod 90 deg (a rectangle's orientation is 90 deg-periodic), histogram
  weighted by footprint area, and rotate the **entire scene** — buildings,
  roads, area polygon — by the dominant mode before rasterizing. Residual
  per-building deviation then snaps to the nearest 90 deg.
  **Prediction to write down before running it:** a US grid town shows one
  mode holding well over half the footprint area within +/-3 deg. Flat or
  bimodal means the area has no single grid, and whole-scene rotation is the
  wrong move for it — that is the case needing per-cluster rotation.
  Roads stair-step fine in vanilla, so rotating them costs nothing.
- **Editor.** Rectangle tools axis-aligned; free rotation in 90 deg
  increments only, because arbitrary rotation is unrepresentable rather than
  discouraged. The snap is the ergonomic face of a rule the validator
  enforces anyway.
- **A4 rule: wall run not expressible as a room rect.** This is the
  enforcement point. It catches hand-painted zigzags whatever tool made
  them, and it catches imported data that skipped the rotation pass. Snap
  without the rule leaves the invariant unenforceable on imported data.

### Consequence to remember

Rotating the scene divorces the map from true north. Nothing in game depends
on that today, but `worldmap.xml` (§15) and any future GIS overlay do — the
rotation angle must be stored with the import, not discarded.

---

## 18. Session 2026-08-10 — A1 closed, and what it turned up

### A1: `outlineRoom` offsets are CORRECT

Two independent lines, neither of which is our own writer checked through
our own reader:

**Vanilla measurement.** `Probe roomgeom "$PZ/media" "$MAPS/Muldraugh, KY"
42_40`, 86 rooms. The discriminating comparison is the same classifier at
two positions: south `ry+rh` 67.0% vs `ry+rh-1` 10.2%; east `rx+rw` 83.9%
vs `rx+rw-1` 3.7%. **Ratio, not rate** — this is what makes it immune to the
§11 `attachedN` failure, where 99.5% absolute was wrong. A miscalibrated
wall classifier would have to be positionally biased to flip those.

The worked example carries more weight than the percentages.
`weldingworkshop [57,169 12x12] z=1`, against four predictions written down
before it was run:

| Predicted | Observed |
|---|---|
| `(rx, ry)` carries both edges | `+` at 57,169 |
| `(rx+rw, ry)` west only | `W` at 69,169 |
| `(rx, ry+rh)` north only | `N` at 57,181 |
| **`(rx+rw, ry+rh)` carries neither** | `.` at 69,181 |
| runs of `rw` and `rh` | 12 and 12 |

48 placements on 47 distinct squares.

**Source inspection.** `CellEditor.outlineRoom` loops north over
`x0 .. x0+w-1` at `y0` and `y0+h`, west over `y0 .. y0+h-1` at `x0` and
`x0+w`, and never touches `(x0+w, y0+h)`. That is `2w + 2h` placements on
`2w + 2h - 1` squares — exactly what vanilla measures.

**The instrument already existed.** `Probe roomgeom` is where §10's
"86 rooms, both far sides confirmed" came from. §2's open thread was stale
when it was written. *Check what this project already does, not only what
vanilla does.*

### Test 30 is void, not negative

`roomgeom` on generated 200_200 printed `outlineRoom's offsets are WRONG`.
Discard that conclusion. Two reasons:

1. **GIS buildings never call `outlineRoom`.** They are a bbox rect with
   walls traced round the footprint polygon — a different code path.
2. **The input is the §17 zigzag.** `room [199,221 26x15]` is a 390-square
   bounding box containing a diagonal parallelogram of walls. Walls sit at
   neither `ry+rh` nor `ry+rh-1`; the diagonal crosses both rows once. 24.1
   vs 22.2, and 10.4 vs 14.6 flipping the other way, are two coin flips.

**`roomgeom` has no margin test and no axis-alignment test, so it stated a
confident conclusion from noise and nearly retired a correct convention.**
A probe that cannot say "I don't know" manufactures findings. Both guards
are in §15.

### Room rects can exceed their building

Independent of orientation: a bbox over a non-rectangular footprint marks
~200 outdoor squares as members of `room`. Membership drives loot spawning
and every A4 rule. The fix is the §17 constraint arriving from the other
side — a room must be a union of rects covering interior squares only.

### Vanilla `spawnpoints.lua` — CONFIRMED, and not what §7 said

`$MAPS/Muldraugh, KY/spawnpoints.lua` is `posX`/`posY`/`posZ` only, no
`worldX`/`worldY`, values in absolute world tiles:

```
cellX = posX / 256      localX = posX % 256
```

Verified: `posX=10770, posY=10271` -> cell 42_40, local 18,31 -> an interior
carpet square inside `room 23 'livingroom' rect [14,31 5x6]`. All 21
spawnpoints fall in cells 41-43 x 36-41, a coherent town block. Sets are
`poor_houses` (10), `medium_houses` (5), `rich_houses` (3),
`doctor_houses` (2), `police_station` (1), merged per profession.

**Our write path emits the legacy `worldX`/`worldY` form and works
(test 15), so the reader accepts both.** Likely `worldX` defaults to 0,
making `posX` absolute. **UNVERIFIED** — confirm in the decompiler before
switching `GisCells` to the absolute form. If it holds, the 300-tile
conversion that caused the three-session spawn bug can be deleted outright.

### The per-square room id is dead

`room id: -1` on every interior square sampled — 3 squares, 3 rooms, 2
cells. Room membership is derivable **only** from lotheader rects. Two
consequences: A4 rules need a spatial index over `RoomDef` rects built per
cell (the same index §17's snap needs), and `CellEditor` should not be
writing a field vanilla ignores. Three samples is not a sweep; widen it
when the rooms dump exists.

### Smaller notes

- **`LotHeader`'s class comment is stale.** It says the B42 trailer is
  "deliberately left unparsed and exposed as raw bytes"; `readB42Meta`
  parses it completely and `Probe lotheader` reports `0 unidentified
  bytes`. Same drift class as the CHARTER problem, one level down.
- **The §10 3-int32 room object records are in better shape than
  UNVERIFIED implies.** The trailer fit `leftover = 1 + buildings +
  roomRefs` across 4064 cells and consumes to zero remainder. That is
  structural evidence for the record width; it still says nothing about
  meaning (§4).
- **`chunkGrid` is probably zombie density.** B41 reads
  `(width/10)*(height/10)` into a field named `zombieDensity`; B42 reads
  `byte[1024]` = 32x32 into `chunkGrid`, values 0..10. Same range, same
  role, resolution moved to per-chunk. Test in §15.
- **`unknown12`** in the B42 trailer is still unnamed.
- **Objects carry four facings, walls two edges.** The chair at 42_40 16,33
  has `Facing W` and a `chairW` flag. When §17 check 2 runs, scan `Facing`
  across wall tiles specifically — if walls only ever take N/W while
  objects take all four, that is the cleanest statement of the constraint
  the data will give.
- **`Probe` argument shapes are undocumented.** `lotheader` takes a file,
  `square` and `roomgeom` take a media dir plus a map dir. Two commands
  were run wrong this session for want of a usage line.
- **`grep` is aliased to `ugrep`**, which is POSIX-strict. GNU `\|`
  alternation matches LITERALLY and returns nothing. Two greps this session
  silently reported "not found" for symbols that were present. Use `-e`
  repeated, or `-E`. Same class as the `ls`/eza gotcha in §5.

---

## 19. Wall vocabulary, and four attempts at an alignment test

### The wall orientation vocabulary — CONFIRMED (§17 check 2 closed)

From `PaletteScan "$PZ/media" walls_exterior_house_01` and
`--prop Facing`:

| Flag | Role | `edgeOf` |
|---|---|---|
| `WallW` | west edge | WEST |
| `WallN` | north edge | NORTH |
| `WallNW` | corner, renders both segments on one square | BOTH |
| `WallSE` | **pillar/post, owns no edge** (`PaintingType = pillar`) | NONE |
| `WindowN/W`, `DoorWallN/W` | openings in a wall | NORTH / WEST |

**No diagonal wall primitive exists in the tile vocabulary.** That is the
strongest available evidence for §17's constraint — stronger than counting
rooms, because it is a property of the art rather than of one town.

`Facing` is an OBJECT property: N/S/E/W across ~6,200 tiles (the chair at
42_40 16,33 has `Facing W`). No wall tile carries it, and no tile of any
kind carries a diagonal facing. Objects have four facings; walls have two
edges plus a corner and a post.

**The A1 classifier audits clean.** `wallOn` requires `isStructuralWall`
AND a matching `edgeOf`. `WallSE` returns NONE so pillars never counted;
`WallNW` returns BOTH, which is correct; overlays are excluded by
`isOverlay`. `edgeOf` reads declared `Wall*` flags rather than inferring,
so it sidesteps the §11 `attachedN` trap by construction.

**But `edgeOf` has a live bug.** Its final block falls back to
`attachedN`/`attachedW` — the proxy the method's own comment warns about.
Unreachable from `wallOn`, but `edgeOf` is public and A3 will call it on
neighbours, where a grime overlay would report `Edge.NORTH`. Listed in §15
as an A3 prerequisite.

### The corpus sweep — `roomgeom --all`, 4065 cells, ~78s

```
rects:        152317 total
   tested:     29928  (19.6%)
   untestable 122384  (80.3%)   under 4 on a side
   off-level:      5

untestable rects by shorter side:   1: 46482   2: 45928   3: 29974
```

**The 80.3% is the important number, not the alignment result.** Vanilla
rooms are decomposed into thin strips — 46,482 rects are 1 square wide.
Any validation rule reasoning about a rect's interior is inapplicable to
four-fifths of the corpus. **This constrains A4's design more than the
orientation question does.** A4 must work at 1xN.

Also: `prisoncells [193,203 5x54]` appears identically at z=0,1,2,3.
Multi-storey rooms repeat per level, so "room with no exit" has to treat
vertical connectivity separately from horizontal.

### Four attempts at the alignment test

Recorded in full so none is retried. The question: **is this rect's wall
geometry expressible as a rectangle at all?**

| # | Approach | Result |
|---|---|---|
| 1 | Fraction of interior rows carrying a wall, exclude above 25% | **674 false positives.** On a 4-wide rect one partition is 33%. Flagged bathrooms, halls, barns and printed "off-axis rooms exist in vanilla" |
| 2 | Least-squares slope of per-row mean wall x; exclude slope~1, r2>=0.9 | **Cleared all 674 — and the known diagonal too.** The rect holds two parallel runs, so the per-row mean jumps (203, 220, 203) and r2 came out 0.227. Averaging destroyed the structure |
| 3 | Concentration: fraction of walls on the two densest lines, exclude below 0.45, either axis | GIS caught at 0.19/0.20. Vanilla down to **53 false positives** |
| 4 | Same, but require BOTH axes low (`&&` not `\|\|`) | **CONFIRMED 2026-08-11.** 0 non-aligned across 29,928 testable rects; all 3 GIS diagonals still caught |

**Attempt 4 is the first version to pass both calibration cases at once** —
the known positive is caught and every known negative is clear. Attempts 1,
2 and 3 each failed one side. The rule it encodes: *a diagonal spreads walls
across every line on both axes; a partition spreads on one axis only.*

Measured concentrations, before attempt 3 was written:

| Shape | north | west |
|---|---|---|
| GIS diagonal `[199,221 26x15]` | 0.192 | 0.200 |
| clean rect `weldingworkshop` | 1.000 | 1.000 |
| rect + one partition (synthetic) | 1.000 | 0.667 |

**Why attempt 4 should work.** Every one of the 53 survivors has one axis
at or near 1.00 and the other low — `prisoncells` 0.30/1.00, `stable`
1.00/0.38, `bathroom` 0.42/1.00. Partitions run parallel to one axis, so
they can only spread walls along one axis. **A diagonal is low on both by
construction** — all three GIS rooms are 0.19/0.20, 0.21/0.23, 0.43/0.29.

**The lesson worth keeping.** Three of four attempts produced a confident
printed conclusion from a broken measure, and two of those conclusions were
about vanilla's properties. §4 says a test that cannot fail proves nothing;
the corollary is that **a test which has never been run against a known
positive AND a known negative has not been calibrated.** Attempt 3 was the
first checked against both before shipping, and it was still wrong — but
wrong in a diagnosable way, because the failures came with numbers.

### Status of §17

Check 2 (vocabulary) is CLOSED and supports the constraint. Check 1 (does
vanilla ever go off-axis) is **still open**: the current best answer is no
off-axis room among 29,928 testable rects, but that is 19.6% of the corpus
and comes from a test that has been wrong three times. `FootprintSnap`
should not be designed on it yet.

**UPDATE 2026-08-11 — check 1 CLOSED, with a stated limit.** Attempt 4
returns 0 non-aligned across 29,928 testable rects while still catching all
three known GIS diagonals, so the test is calibrated on both sides rather
than only one.

The limit is real and should be quoted alongside the result: **the finding
covers 19.6% of rects.** The other 80.3% are untestable because they are
under 4 on a side, not because they are suspect — a 1-wide rect has no
interior for walls to spread across, so "expressible as a rect" is close to
vacuous there.

**Two independent lines now agree**, which is what §4 asks for: no vanilla
room is off-axis among testable rects, and no diagonal wall primitive
exists in the tile vocabulary at all (§19). The second covers the whole
corpus rather than a fifth of it, and is the stronger of the two.

**Conclusion for design: `FootprintSnap` should refuse off-axis footprints,
not warn.** The earlier "warn, not refuse" recommendation came from the
broken guard and is retracted (§13).

---

## 20. A2 scoping — the deletion is not wholesale

A2's premise is intact. `genMapSquare` deletes and replaces TREE/BUSH/PLANT
per tile on load (§9), so the ~7,800 authored trees are discarded whatever
our code does. The chunk is still worth doing, and the proof is still
"delete, regenerate, confirm nothing changes in game".

What the call sites add, from `grep -rn -e TreeScatter -e TreePalette
-e WorldGenOverride src/` on 2026-08-10:

```
GisCells.java:61        TreePalette.pick(ti, sprites)
GisCells.java:63        TreeScatter.place(g, treePal, SEED)   -> treeAt
GisCells.java:150-151   treeAt[gx][gy] pushed onto the square stack
GisCells.java:220       writeWorldGenOverride(mapDir, cellsX, cellsY)
BiomeMapWriter.java:74  TreeScatter.distanceToStructure(g)
```

**`BiomeMapWriter` depends on `TreeScatter.distanceToStructure`.** That is
grid geometry — distance from each square to the nearest structure — not
vegetation placement, and it is load-bearing for the biome map, which is
what removed the visible seam (test 27). Deleting the class outright breaks
the thing that most recently worked.

So A2 is three separable pieces, only one of which is a deletion:

1. **Delete `TreeScatter.place` and `TreePalette`**, and the `treeAt` write
   at GisCells:150. Move `distanceToStructure` to a geometry helper first;
   that move should be byte-identical on its own, which makes it a
   separately provable step.
2. **Stop writing `WorldGenOverride.lua`.** Independent of the above and
   testable with no code change at all: delete the file from the generated
   mod directory, load, compare. If nothing changes, the write goes.
3. **Open question, not cleanup.** Biomes drive vegetation in
   engine-generated terrain; we also write tree tiles into authored cells.
   Do both target the same squares? If the engine populates authored cells
   too, vegetation is being decided twice by different rules, and which one
   wins is a behaviour question worth knowing before the editor authors
   vegetation deliberately.

**Proof mechanism for step 1**, unchanged from the charter: hash every
generated file before and after.

```fish
find ~/Zomboid/mods/PZGisImport -type f | sort | xargs sha256sum > /tmp/before.sha
# delete, rebuild, regenerate
find ~/Zomboid/mods/PZGisImport -type f | sort | xargs sha256sum > /tmp/after.sha
diff /tmp/before.sha /tmp/after.sha
```

**Prediction: the lotpacks differ** — the tree tiles really are written, so
removing them changes our output. The claim under test is not "the bytes
are identical" but "the loaded map is identical", which is why this one
needs the in-game check rather than a hash alone. The hashes are still
worth taking: they say *which* files changed, and anything other than the
lotpacks changing would be a surprise worth chasing.

**The `gisimport` command line is not recorded anywhere in this document.**
It was needed twice this session and guessed wrong both times. Whoever runs
A2 should paste the working invocation into §6.

---

## 21. Ground is regions, not a per-square mix

### A2 step 2 — CONFIRMED, `WorldGenOverride.lua` does nothing

Moved out of the generated mod directory, loaded in game 2026-08-11.
**No seam. Foliage flows cleanly across the boundary between authored cells
and engine-generated land** — dense mixed forest on one side, open grass
with saplings on the other, trees crossing coherently. The biome map is
doing that work. Remove the write.

Incidental confirmation for A2 step 1: the trees look right, and they are
the engine's, not ours. `genMapSquare` deleted our authored trees and
generated its own from the biome map — which is the claim the deletion
rests on.

**RETRACTED, same day. This paragraph is wrong, or at least unproven —
see §25.** It was written before `BiomeMapWriter`'s scope note was read and
before the generated cell was probed for tree tiles. Both point the other
way. A2 step 1 is BLOCKED, not ready.

### The ground defect, and what causes it

Generated ground reads as scattered tan diamonds in green grass. Probing
eight adjacent squares in generated 200_200:

```
Grass_Dark, Grass_Dark, Grass_Dark, Grass_Light,
Grass_Dark, Grass_Dark, Grass_Medium, Grass_Medium
```

Sixteen adjacent squares in vanilla 35_35: **`Grass_Dark`, all sixteen.**
No alternation whatsoever.

Sixteen samples spaced 16 apart across the same vanilla row: a long grass
region, a `Road_04` crossing, then a dirt region alternating `Dirt` and
`Dirt_Grass` square by square.

**So vanilla ground is two-level, and `GroundPalette` collapses it into
one:**

1. **Region** decides the ground TYPE — grass here, dirt there, road there.
   Large-scale, coherent, driven by what the place is.
2. **Within a region**, mix a small set of tiles that belong together:
   `Dirt` with `Dirt_Grass` to make a worn unpaved surface, or the four
   interchangeable variants of one grass colour.

`Grass_Dark`, `Grass_Medium` and `Grass_Light` are **level-1 distinctions.**
We are treating them as level-2 texture. That is backwards, and it is why
generated ground reads as noise.

### Why the survey did not catch it

The Muldraugh survey measured how OFTEN each group appears — 70 / 21 / 10 —
and reproduced those proportions faithfully, per square. It never measured
how they are ARRANGED. A 14.5% share can be 14.5% scattered uniformly or
14.5% in coherent regions, and those look nothing alike.

**Same failure shape as §4's round-trip caveat and the §11 `attachedN`
bug: a measurement that validates in aggregate while being wrong about the
thing that matters.** Frequency is not distribution.

### Consequence: the dirt groups should come back

Test 27 dropped `dirt` (64/69/70/71) and `dirt_grass` (80/85/86/87) because
they read as bare diamonds scattered through forest. That fixed the symptom
correctly, but the cause was the same one-level collapse — and
`GroundPalette`'s own comment already said so: *"Dirt is still the right
floor for a track, a yard or an unpaved road. It just should not be
scattered through open country at random."*

Vanilla confirms it: the dirt region in 35_35 alternates `Dirt` with
`Dirt_Grass` per square, exactly the level-2 mix. Once regions exist, dirt
returns and unpaved tracks and yards become possible — a capability the
current palette gave up.

### Before building a noise field

**Check whether the region signal already exists.** The biome map drives
vegetation and removed the seam; if biome type predicts ground group, the
region layer is already there and unread, and the fix is a lookup rather
than new noise. Falsifier: sample ground material against biome across a
vanilla cell and see whether they correlate.

Only if they do not is a value-noise field over world coordinates the right
approach — selecting the GROUP at a scale of roughly 8-20 squares, with
uniform choice among the four variants within a group, which is genuinely
per-square. Overlays can stay per-square; they read as texture, not colour.
Either way the measured proportions are preserved exactly.

### Loose thread

Sixteen ground probes on vanilla 35_35 produced 25 `FloorMaterial` lines.
Some squares carry two ground tiles. The survey recorded that no square out
of 257,703 had two overlays; a stacked BASE is a different claim and is not
obviously allowed by it. Unexplained.

---

## 22. `chunkGrid` is zombie density — CONFIRMED, and ours is empty

### The evidence, three independent lines

1. **Structural.** B41 reads `(width/10)*(height/10)` bytes into a field
   the legacy format literally names `zombieDensity`. B42 reads
   `byte[1024]` = 32x32 into `chunkGrid`. Same role, resolution moved from
   per-10-tiles to per-chunk.
2. **Value range.** Vanilla uses 0..10, matching the B41 field.
3. **Behavioural, 2026-08-11.** Zombies spawn on vanilla ground and none
   spawn on ours. The boundary sits exactly where our authored road ends.
   **This is the independent source §4 asks for — not more testing of the
   same kind.**

### The distributions

```
vanilla Muldraugh, 4,162,560 chunks:
  0 -> 4,013,741    1 -> 47,276    2 -> 72,702    3 -> 17,993
  4 ->     7,455    5 ->    448    6 ->    595    7 ->  1,579
  8 ->        91    9 ->    140   (10 present)

generated PZGisImport, 4,096 chunks:
  0 -> 4,096        (every byte, all four cells)
```

**96.4% of vanilla chunks are zero, so a zero-heavy map is normal.** The
defect is not that we have zeros; it is that we have nothing else. And the
nonzero values are far from uniform — 1, 2 and 3 carry most of the
population while 8, 9 and 10 together are about 0.005% of chunks. Anything
that writes these must not spread values evenly across the range.

### The trap, which is the same one as §21

That histogram is a **frequency** measurement. Reproducing it by rolling
per chunk would get the numbers right and the arrangement wrong — density
clusters around habitation, and a town cell and a forest cell are not two
samples from one distribution. **Frequency is not distribution**; this is
the third time that error has appeared in this project (§11 `attachedN`,
§21 ground groups, here).

### Three consumers now want the same thing

- **Ground groups** need to know whether a place is grass, dirt or road
  (§21).
- **Zombie density** needs to know whether a place is inhabited (§22).
- **The biome/zone map** already encodes something close to this and is
  written per tile (§6).

All three are asking *what is this place*, and **GIS land use already
answers it** — parks, fields, residential, industrial — from real data
rather than invented noise. That is a strong argument for building the
region layer once, as shared infrastructure, rather than solving ground and
density separately.

It is also the §2 test passing cleanly: a GIS feature that the editor needs
regardless of who authors the tiles.

### Testing a fix

Unusually clean: write plausible density, regenerate, walk the same
boundary, see whether zombies appear on our side. **Use a fresh world** —
a resumed save may have baked spawn data for chunks it has already seen.

### Note on `Probe lotheader`

It stops at tile names and does not print the chunk grid, though
`LotHeader` parses it. The histogram above came from `Probe survey`, which
takes ~90s on Muldraugh and prints nothing until it finishes when piped
through `grep`.

---

## 23. Density written — the mechanism works

`GisCells.writeChunkDensity`, added 2026-08-11. A chunk holding building
tiles gets 2, a chunk orthogonally adjacent gets 1, everything else stays
0. Footprints come from `buildingRects(g, ox, oy)`, already clipped to the
cell, so the density write does not depend on the raster loop.

### Predicted before running, from building geometry alone

1530 building tiles over 8x8 chunks is ~24 chunks packed perfectly,
realistically 40-70 once footprints straddle boundaries, each with up to
four orthogonal neighbours. So: **40-70 twos, 80-150 ones, 95%+ zero.**

```
0 -> 3935  (96.1%)     1 -> 89     2 -> 72
```

**72 and 89, and 96.1% zero against vanilla's 96.4%** — a number not tuned
for, since the rule was written from building geometry rather than from
matching the histogram.

### In game, fresh world

**Zombies at the building, none on the way to it.** First ever seen on the
generated map. Both halves held, including the negative one: they appear
where we wrote 2 and are absent where we wrote 0.

### What this does and does not establish

**Confirmed:** `chunkGrid` gates zombie spawning, and writing nonzero
values makes zombies appear at those chunks. The plumbing works.

**Not established:** whether 2 is the right value. Vanilla's range runs to
10 and seven buildings at density 2 is a hamlet, not a town. Three zombies
is evidence the mechanism fires, not evidence the quantity is right.

**Open, and worth a proper measurement:** how vanilla's nonzero values
correlate with what a place is. §4 prefers the recipe to the output, so
the engine's own spawn code is a better source than measuring Muldraugh —
but Muldraugh at least says whether density tracks building footprints,
road frontage, or something else. And the import already distinguishes
`Agriculture` from `Residential`; a farm outbuilding and six houses
probably should not carry identical density.

### A region signal already exists

From the `giscells` output:

```
town 17374, edge 28242, forest 77031, deep 87233, beyond-raster 52264
```

**The biome map already computes a four-class per-tile classification.**
That is precisely the region signal §21 needs for ground groups and §22
argued should be built once and shared. It exists, it is per tile, and
`GroundPalette` does not read it.

Whether those four classes map usefully onto grass dense / medium / light
is untested — but reading an existing classification is a much smaller
piece of work than building a noise field, and it should be ruled out
first.

---

## 24. Ground stacking — §21's model is incomplete

### Regions vary within a cell

Six samples across vanilla town cell 42_40 at y=200:

```
x= 20   Grass_Medium
x= 60   Sand + Grass_Medium
x=100   Sand
x=140   Grass_Dark
x=180   Grass_Dark
x=220   Grass_Dark
```

Contiguous regions at 40-tile spacing, three materials in one cell. This
kills the fallback that regions might be arbitrary hand-picked fields —
there is structure. Forest cell 35_35 is uniformly `Grass_Dark` across
every sample taken (§21).

### But the driver is NOT distance from habitation

That comparison suggested it: 42_40's houses are in the west (spawnpoints
at local x 18-58) and `Grass_Medium` sits at x=20-60 with `Grass_Dark`
from x=140 out. A fine transect does not bear it out:

```
x=100  Sand
x=104  Sand
x=108  Grass_Dark
x=112  Grass_Medium + Grass_Dark x4      <- FIVE ground tiles
x=116  Grass_Medium + Grass_Dark
x=120  Grass_Medium + Grass_Dark
x=124  Grass_Medium + Grass_Dark
x=128  Grass_Dark
x=132  Grass_Dark
```

The Medium band at 112-124 sits **inside** Dark, with Dark on both sides.
That is not one region meeting another, and it is not a habitation
gradient. It may be a path, a mown verge, or the blend around the Sand
feature. Unknown.

### The finding that matters: vanilla stacks ground tiles

**A square can carry several base ground tiles, not one base plus at most
one overlay.** x=112 carries five. This is the explanation for the
"25 lines from 16 probes" thread left open in §21, and it is the blend
mechanism — overlapping tiles from neighbouring regions soften the
boundary.

**So §21's model is incomplete.** Ground is region, then texture within
the region, **then a blend layer of stacked tiles at boundaries.** We
write exactly one base plus at most one overlay, so even with perfect
regions our transitions would be hard-edged where vanilla's are soft.

### Why the next step is the recipe, not more measurement

Three hypotheses about ground were formed and complicated by data in a
single session: per-square frequency (§21), distance banding, and now a
clean region boundary. Each time the measurement was of **Muldraugh, a
hand-authored town** — which §13 already warns against imitating.

§4: *prefer the recipe to the output.* The blending logic lives in the
engine or in TileZed's authoring behaviour, and reading it will settle in
one pass what four transects have not. Two candidates:

- `blends_natural_01` tile naming and properties — is there a convention
  encoding which pairs blend, and in which direction?
- The engine code that assembles ground squares at load.

**Ground appearance matters** — it is currently the most immersion-breaking
defect in the generated map — so this is a real chunk, not a curiosity.

## 25. Tree ownership — UNRESOLVED, and it blocks A2 step 1

A2 step 1 rests on the claim that the engine discards our authored trees.
**That claim is not established, and two findings point against it.**

### Evidence for (the original basis)

§9: `genMapSquare` deletes and replaces TREE/BUSH/PLANT per tile on load.
§11: the engine substitutes species and appearance for the generic
`vegetation_trees_01_*` tiles, so varied beautiful trees in game are
compatible with our writing generic ones.

### Evidence against

1. **`BiomeMapWriter`'s own scope note**, added when that class was
   written: *"WorldGen only generates chunks where
   `IsoChunk.hasEmptySquaresOnLevelZero()` is true. Since GisCells fills
   every square of every chunk, none of ours are generated, so the BIOME
   band may currently do nothing for us."* If WorldGen never runs on our
   chunks, it never places trees on them either.
2. **Our tree tiles are in the file.** `Probe findprop ... 200_200 tree`
   returns `vegetation_trees_01_8 / _10 / _11` with `tree 2`, at authored
   positions. `TreeScatter` wrote 7,797 trees and they are on disk.

These are not necessarily contradictory — `genMapSquare` and WorldGen
chunk generation are different mechanisms, and one could run while the
other does not. That ambiguity is exactly what `BiomeMapWriter` flags as
UNVERIFIED.

### The test, which was started and not finished

**Positional.** Pick authored tree squares away from the cell edge, convert
to world coordinates, walk that line in game.

- Trees at exactly those coordinates, bare ground between → **positions are
  ours.** `TreeScatter` is live and A2 step 1 must not proceed.
- Trees along the line at unrelated positions → **the engine re-scatters.**
  A2 step 1 stands.
- Dense forest everywhere → the engine is adding on top of ours, and a
  different test is needed.

A first attempt used cell 200_200 local x=0, which is world x=51200 and sits
on the map edge — not usable. Pick interior squares (local x roughly
120-180) instead.

```fish
java -cp out pzformat.Probe findprop "$PZ/media" \
    ~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport 200_200 tree
```

World coordinate for cell 200_200 local (x,y) is (51200 + x, 51200 + y).

### Density in the screenshots is a hint, not evidence

7,797 trees over four cells is about 3% of squares. The forest in the
2026-08-11 screenshots looks considerably denser than 3%, which would
suggest the engine is adding trees rather than only substituting art for
ours. Not conclusive — canopy sprites overlap and hide bare ground — but
worth holding in mind when the positional test is run.

### A fifth mechanism: `Fix2xMap` rewrites tile names at load — CONFIRMED

Found 2026-08-13 while rendering a vanilla reference for E7, not by looking
for it. `IsoChunk` holds a static legacy-name translation table applied to
**every tile as the chunk loads**, before any of the mechanisms above run.
Three kinds of entry:

| kind | example | effect |
|---|---|---|
| direct rename | `vegetation_groundcover_01_0` → `blends_grassoverlays_01_16` | tile becomes a different tile |
| deletion | `vegetation_groundcover_01_6` → `""` | tile vanishes |
| randomised substitution | `vegetation_foliage_01_0..16` → `randBush` | resolved per load to `f_bushes_1_(64 + Rand.Next(16))` |
| randomised substitution | `vegetation_groundcover_01_18..23` → `randPlant` | resolved per load to `d_plants_1_(Rand.Next(4)*16 + Rand.Next(8))` |

Also covers `walls_exterior_house_01_20..31`, `walls_exterior_roofs_01_24..41`
→ `walls_exterior_roofs_03_*`, and several `location_shop_greenes_01_*`.

**Why this matters to §25 directly.** The "Evidence against" above rests on
`Probe findprop` returning `vegetation_trees_01_8 / _10 / _11` at authored
positions. Those names being **in the file** is confirmed and unchanged. But
what the engine renders for them is not necessarily what we wrote, because a
name in `Fix2xMap` is rewritten at load. **This does not resolve tree
ownership either way** — it adds a mechanism that must be ruled in or out
before the positional test's result can be interpreted.

**Check, before running the positional test:** grep the decompiled
`IsoChunk` for the exact tile names `TreeScatter` and `TreePalette` emit. If
any appear as `Fix2xMap` keys, the test is measuring the engine's
substitution, not its scatter, and the three outcomes listed above do not mean
what they say.

```fish
grep -n -e vegetation_trees_01 -e vegetation_foliage_01 \
    ~/Downloads/ZOMBOIDSTUFF/decompiled/IsoChunk.java
```

**Falsifier for the whole concern:** if none of our emitted names are keys in
the table, `Fix2xMap` is irrelevant to us and this note can be closed.

---

### Do not start from the biome bands

§23 suggested reading `town/edge/forest/deep` as the region signal. That
still may be worth doing, but it assumes the region driver is distance
from structures, and this transect does not support that. Extracting the
banding into a reusable method is worth doing regardless — three consumers
want a region signal — but mapping grass groups onto it should wait for
the investigation.

---

## 26. Ground blending — CONFIRMED mechanism, and the rule

E3, 2026-08-13. Full document: `docs/E3_GROUND_BLENDING.md`. Deliverable was a
document; no code was changed. This section is the summary a future session
needs; the document carries the falsifiers and the unrun checks.

### Three layers, and a naming trap

| Layer | Tileset | Flags | Per square |
|---|---|---|---|
| **solid** | `blends_natural_01`, `blends_street_01` | `solidfloor`, `diamondFloor` | exactly 1 |
| **mask** | same sheets | `FloorOverlay`, `IsFloorAttached`, `FloorAttachment{N,S,E,W}` | 0–4 |
| **tuft** | `blends_grassoverlays_01` | `vegitation`, `MoveWithWind` | 0–1 |

`GroundPalette` calls the **tuft** layer "overlay" (`OVERLAY_SHEET`,
`Ground(base, overlay)`, `overlayRate`) and has never written a mask. Use
**solid / mask / tuft**; "overlay" is now ambiguous and will cause a
misimplementation.

Stack order is solid first, masks after, tuft last — CONFIRMED on ~60 squares
across two tilesets. `getFloor()` returns the first floor object and
`cleanChunk` reads `FloorMaterial` off it, so a mask written first would be
mistaken for the floor.

### The 16-tile block contract — CONFIRMED

Source: `PaletteScan "$PZ/media" blends_natural_01`, all 160 indices. Uniform
across all seven materials. For block base **B**:

| offset | role |
|---|---|
| B+0, B+5, B+6, B+7 | solid variants, interchangeable |
| B+1 / B+2 / B+3 / B+4 | corner masks N+W / E+S / S+W / E+N |
| B+8 … B+11 | side masks N / W / E / S |
| B+12 … B+15 | side masks N / W / E / S, second variant |

B = 0 `Sand`, 16 `Grass_Dark`, 32 `Grass_Medium`, 48 `Grass_Light`, 64 `Dirt`,
80 `Dirt_Grass`, 96 `Clay`. Indices 112–127 are a further side-mask set with no
solids; 128–159 have no sprites — UNVERIFIED what they belong to. Block
regularity does **not** continue past 111.

`blends_street_01` follows the same contract: (90,190) is `_53` `Road_04` solid
with `_26` (E) and `_25` (W) `Road_02` masks.

Convention throughout: **+x East, +y South**, matching §10.

### The mask rule — CONFIRMED

Source: contiguous 9×5 rectangle, 42_40, x=110–118 y=198–202. 45 squares,
21 masks, every one checked against its actual neighbours; none unexplained.

A square carries masks drawn from its **neighbour's** block. The mask names the
direction the other material lies in. With S = the set of orthogonal directions
whose neighbour carries the higher-priority material:

| \|S\| | encoding |
|---|---|
| 0 | no mask |
| 1 | one side tile |
| 2, adjacent | **one corner tile** — not two side tiles |
| 2, opposite | two side tiles |
| 3 | two corner tiles, sharing the middle direction |
| 4 | four corner tiles |

Side masks have two interchangeable variants; vanilla uses both on identical
geometry, so pick at random exactly as the four solid variants are picked.

UNVERIFIED: the multi-material case (no square measured bordered two different
higher-priority materials); the |S|=3 case rests on one sample.

### Blending is one-way — there is a precedence table

Not one `Grass_Dark` base square in the rectangle carries a mask. All 21 masks
are Dark-on-Medium, and the relation is not reciprocal. The higher-priority
material is drawn as a mask **onto** its neighbour.

Known: `Grass_Dark` > `Grass_Medium` (21 samples) · `Grass_Medium` > `Sand`
((60,200)) · `Road_02` > `Road_04` ((90,190)).

It is **not** block-index order — Sand is block 0 and loses to Medium at 32 —
so it must be measured. Three of twenty-one `blends_natural_01` pairs known.
UNVERIFIED whether masks cross tilesets at all.

### Region boundaries are dithered

Base materials, 42_40, D = `Grass_Dark`, M = `Grass_Medium`:

```
x:      110 111 112 113 114 115 116 117 118
y=198:   D   D   D   D   D   M   M   M   M
y=199:   M   M   D   D   D   M   M   D   M
y=200:   M   D   M   D   M   M   M   D   D
y=201:   M   M   D   M   M   M   M   D   D
y=202:   M   M   D   M   M   M   M   M   D
```

The two materials interpenetrate per square across 2–4 squares. Interiors stay
pure — §21's 16/16 identical `Grass_Dark` in 35_35 still holds. So the model is
**region → texture (variant choice) → dither (at boundaries) → mask**. Layers 3
and 4 are separate and both are needed: dither without masks is the
scattered-diamond defect at region scale; masks without dither gives a soft but
geometrically straight edge.

UNVERIFIED that dither is a convention rather than a 42_40 hand-painting quirk.
**Check before implementing it:** a contiguous rectangle across a region
boundary in a non-town cell.

### The engine's blending pass touches only the procedural seam

`zombie.iso.worldgen.blending.Blending`, called from `IsoChunk.update()`. The
gate is `!blendingDoneFull && !Arrays.equals(blendingModified, {t,t,t,t})`,
which opens on authored chunks — but the per-direction work is guarded by
`sourceChunk.isBlendingDoneFull()`, set true only in `genRandomChunk`. **So the
neighbour must be a fully procedural chunk.** Between two authored chunks
nothing happens.

`changeGround` replaces the solid floor with `tiles().get(0)` from the
neighbouring biome's GROUND feature, random depth 0–3 inward, along each of the
8 columns of the shared edge — a ragged feathering pass, not masking.
`maxDepth = 4` is a dead constant. `BlendDirection.defaultDepth` (N 7, S 0,
W 7, E 0) are min/max seeds for `genRandomSquare`, not a blend radius.

Consequences: we author every mask ourselves; the
`contains("blends_natural_01")` guard means our roads and building floors are
immune to seam replacement while our natural ground is not.

### WorldGen can only place solid tiles

`grep -rho -e 'blends_natural_01_[0-9][0-9]*' "$PZ/media/lua/" | sort -u`
returns 28 tiles, every one a solid. **No mask tile appears in any Lua file.**
The producers partition cleanly: features place solids, `Blending` replaces
solids, only the authoring tool writes masks.

### `Sand` mid-cell is land use — Q4 answered

(60,200) is Sand solid carrying `fencing_01_59`, inside room 78
`emptyoutside` rect [53,199 8×12]. North of it: `Road_04` at (90,190), and a
`shed` room at [89,177 3×4]. Fenced open ground between a road and a shed — a
yard or lot.

This strengthens §22: land use is what GIS import already knows, and
`emptyoutside` rooms and fence tiles are co-located with it. UNVERIFIED that it
generalises; one parcel is not a rule.

### Dirt can come back — Q5 answered

`dirt` and `dirt_grass` have full 16-tile blocks with the same mask vocabulary.
Vanilla alternating `Dirt`/`Dirt_Grass` per square in 35_35 is the texture layer
behaving normally. The test-27 objection stands as an argument against
*scattering*, not against dirt: gated to tracks, yards and unpaved roads, being
bare is correct.

### What to implement, in dependency order

1. A material **priority table**. Blocks everything; three pairs known.
2. A **region layer** from GIS land use, pure interiors.
3. A **dither pass**, 2–4 squares — after running its falsifier.
4. A **mask pass**, after every square's material is final. It is a pure
   function of the four orthogonal neighbours, which is the same shape as the
   editor's auto wall-joining (Charter §1). Worth writing once for both.
5. Restore **dirt**, gated to yard/track regions.
6. Keep the tuft layer; only its naming needs changing.

Two traps: mask tiles must be declared in the `.lotheader` tile table
(`GroundPalette.all` collects solids and tufts only), and the mask pass cannot
be folded into the per-square roll in `GroundPalette.roll()` because a square's
masks depend on its neighbours.

### Noticed, out of scope

`Blending.removeTrees` is a **fourth** mechanism touching trees: along an edge
shared with a procedural chunk it deletes trees with probability ramping by
distance from the edge (`rnd.nextInt(100) >= y*10` — certain at the edge,
impossible beyond 10 squares) and substitutes `e_newgrass_1_40` or
`e_newgrass_1_42` about 75% of the time. This bears on §25: authored trees near
the map edge may be deleted by this pass rather than by `genMapSquare`, which
would confound the positional test if it is run too close to the boundary.

### `CellRenderer` — two known limitations, neither affecting the game

Found while producing the E7 reference render. **Both are renderer-only. Map
data is correct in both cases and the game is unaffected.** Recorded because
Charter §4 says the renderer is a hypothesis too, and it has now been wrong
three times.

1. **No `Fix2xMap` translation.** `CellRenderer` looks tile names up in the
   sprite atlas literally, so any legacy name the engine would rewrite at load
   is reported missing and skipped. On a 9×9 pure-grass patch of 42_40 this was
   4 of 54 distinct sprites and 13 skipped draws — all vegetation
   (`vegetation_groundcover_01_21`, `vegetation_trees_01_8/9/10`), none of them
   `blends_natural_01`. Applying the table before atlas lookup would fix this
   and every future instance. Note the randomised entries cannot be reproduced
   exactly, only sampled.
2. **Tree sprite vertical anchor.** A tree renders with its canopy sitting flat
   on the grass and, at `zTo` > 0, its trunk hanging below the ground plane.
   The tile is correct — (118,196) carries `vegetation_trees_01_9` with
   `tree 2`, a genuine vanilla tree tile. `Z_STEP = 96` and the `oy` offset
   place tall sprites wrongly relative to the floor diamond. **Owner decision
   2026-08-13: ignore.** It does not affect the game and no chunk depends on it.

**What this does not compromise:** the E7 reference render
(`docs/` or `~/Downloads/vanilla_blend_tight.png`, 42_40 x=110 y=198 size 9).
Every missing sprite was vegetation; no ground tile failed to resolve, and the
ground reads correctly. The mask layer composites properly — `CellRenderer`
draws the full square stack in stored order onto an ARGB canvas, so transparent
mask art blends over the solid beneath it. **The renderer can validate E9.**

### Renderer invocation — two argument-shape traps

`Probe render` reverses the argument order used by `square`, `findprop` and
`roomgeom`, and wants a *texture pack dir* rather than a media dir:

```fish
java -cp out pzformat.Probe render "$MAPS/Muldraugh, KY" \
    "$PZ/media/texturepacks" 42_40 110 198 9 0 0 ~/Downloads/out.png
```

`$PZ/media` yields `packs loaded: 0` and every sprite missing. And in
`Probe.java` the guard on both `zFrom` and `zTo` is `args.length > 8`, so
**passing nine arguments silently ignores `zFrom` and falls back to z 0..2.**
Pass all ten.
