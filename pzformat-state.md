# pzformat — Consolidated State

Java library and toolset for reading, editing, generating and rendering Project
Zomboid **Build 42** (retail 42.20) map data, plus a pipeline that builds
playable maps from public-domain GIS data.

No dependencies. Java 17+ (toolchain targets 21). 224 self-tests.

This document merges three working sessions: the original build-out, the
engine-decompilation session that overturned the roadmap's central assumption,
and the session that got a generated map loading correctly in game with
palette, foliage and a working render loop.

Claims are tagged **CONFIRMED** (verified against retail data or read in the
decompiled engine), **OBSERVED** (seen in game, single data point), or
**UNVERIFIED** (assumption, not yet tested).

---

## 0. What changed in session 3 — read this first

Four beliefs recorded in earlier versions of this document were **wrong**. They
are corrected in place below, but listed here because acting on any of them
wastes real time.

| Old claim | Status |
|---|---|
| "WorldGen paves over authored terrain" is the project blocker (§9) | **FALSE.** The blocker never existed. Every "no buildings" observation was the spawn-point bug below. |
| `spawnpoints.lua` `worldX`/`worldY` are B42 256-tile cell coordinates | **FALSE.** They are on the legacy **300-tile** grid. This put the spawn ~8,800 tiles from the map. |
| The 11 unparsed `.pack` atlases are "UI/effects art only, cosmetic" | **FALSE.** Two of them are `JumboTrees1x` / `JumboTrees2x`. All 24 now parse. |
| Interior floor "renders as missing-texture checkerboard" | **FALSE.** It was `"Grey Diagonal Tiles"`, a real bathroom tile, rendering correctly and simply wrong for a house. |

And one new finding that is easy to get wrong and expensive to relearn:

**Species tree art is render-time only.** Authored map data can say only "a tree
of size N here". See §7.

---

## 1. Machine layout

Everything below is on Garuda Linux, fish shell, Java 21.

| What | Path |
|---|---|
| Git repo | `~/Documents/PZMapCreation` (`src/main/java/pzformat/`, `out/`) |
| Scratch / probes | `~/Downloads/ZOMBOIDSTUFF` |
| Extracted game jar | `~/Downloads/ZOMBOIDSTUFF/pzjar/` |
| Decompiled classes | `~/Downloads/ZOMBOIDSTUFF/decompiled/` |
| Vineflower decompiler | `~/Downloads/ZOMBOIDSTUFF/vineflower.jar` (1.12.0, fat jar) |
| Game install (`$PZ`) | `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid` |
| Vanilla maps (`$MAPS`) | `$PZ/media/maps` — Muldraugh, KY is the 4065-cell reference set |
| GIS inputs | `~/pzgis/` — `area.geojson`, `buildings.geojson`, `roads.geojson`, `fetch_gis.py` |
| Generated mod | `~/Zomboid/mods/PZGisImport/` |
| Renders | `~/pzrender/` |
| Saves | `~/Zomboid/Saves/<mode>/<timestamp>/` |

Note the source root is `src/main/java/pzformat/`, not `src/`.

Fish universal vars (persist across shells):

```fish
set -U PZ   "/home/kaatlev/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid"
set -U MAPS "$PZ/media/maps"
```

If `$MAPS` expands empty, a *global* is shadowing the universal. Diagnose with
`set -S MAPS`; clear with repeated `set -e MAPS` until only the universal
remains, then re-`set -U`.

`ls` is aliased to eza, which rejects `-t` with a bare `-d`. Use
`command ls` when a glob result matters — a wrong `ls` once hid 33 of 34 saves
and sent an investigation down the wrong path for several exchanges.

Mod layout that works (copied from Maplewood, a working B42 map mod):

```
~/Zomboid/mods/PZGisImport/
├── 42/mod.info                         version metadata ONLY, carries versionMin=42.0
└── common/media/maps/PZGisImport/      the actual content
    ├── map.info
    ├── <x>_<y>.lotheader               cells 200..201 x 200..201
    ├── world_<x>_<y>.lotpack           NOTE the different naming convention
    ├── spawnpoints.lua
    ├── objects.lua
    └── WorldGenOverride.lua
```

Two naming conventions in one directory, and a mismatch fails silently:
lotheaders are `<x>_<y>.lotheader`, lotpacks are `world_<x>_<y>.lotpack`.

---

## 2. Build and run

```fish
cd ~/Documents/PZMapCreation
javac -d out (find src -name '*.java')
java -cp out pzformat.SelfTest          # fixtures only, no game files needed
```

Commands must run from the repo root — `-cp out` is relative and fails silently
from `~`.

`pzformat.Probe` is the CLI. Selected commands:

| Command | Purpose |
|---|---|
| `survey <mapdir>` | Sweep a whole map directory |
| `mapdir <mapdir>` | Summarise a map folder, flag missing support files |
| `lotheader <file> [--scan]` | Parse one header |
| `lotpack <world_X_Y.lotpack> <X_Y.lotheader>` | Parse one cell |
| `roundtrip <mapdir> [n]` | Read/write byte-identity check |
| `pack <file.pack> [--extract <dir>]` | Parse atlas, verify PNG anchors |
| `packinfo <file.pack \| dir>` | Structural analysis of atlases |
| `sprites <texturepacks> [lotheader]` | Do tile names resolve to sprites? |
| `props <mediadir> [mapdir] [X_Y]` | Tile semantics + validation |
| `square <mediadir> <mapdir> <X_Y> <x> <y> <z>` | Dump every tile + properties |
| `findprop <mediadir> <mapdir> <X_Y> <prop>` | Find squares having a property |
| `gisimport <buildings> <roads> [area] <outdir>` | Rasterise only, schematic PNG |
| `giscells <buildings> <roads> <area> <media> <modsdir> [name]` | Full cell generation |
| `render <mapdir> <texturepacks> <X_Y> <x> <y> <size> [zFrom zTo] [out.png]` | Isometric render |

Standalone probes added in session 3, run directly rather than through `Probe`:

| Class | Purpose |
|---|---|
| `pzformat.PaletteScan <media> <prefix>` | Table of tiles under a prefix: sprite present, kind, CustomName, Material, flags |
| `pzformat.PaletteScan <media> --prop <key>` | Distinct values of a property across all tiles |
| `pzformat.PaletteScan <media> --find <text>` | Tiles whose CustomName contains text |
| `pzformat.TreeSurvey <media>` | Map `tree` size classes to tilesets and species |
| `pzformat.LegacyPackProbe <file \| dir>` | Walk the legacy `.pack` layout, verify against PNG offsets |

Full regeneration (fish, one line):

```fish
cd ~/Documents/PZMapCreation; and javac -d out (find src -name '*.java'); and rm -rf ~/Zomboid/mods/PZGisImport; and java -cp out pzformat.Probe giscells ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson "$PZ/media" ~/Zomboid/mods PZGisImport
```

---

## 3. THE KEY FINDING — how WorldGen decides to overwrite

**CONFIRMED**, read directly in the decompiled engine.

`zombie/iso/worldgen/WorldGenChunk.generateChunks`:

```java
IsoChunk ch = chunks.get(new ChunkCoord(x, y));
if (ch.hasEmptySquaresOnLevelZero()) {
    genRandomChunk(...);      // full procedural generation; tags WORLDGEN
} else {
    genMapChunk(...);         // treated as authored
    cleanChunk(ch, "Sand",   "vegetation_groundcover_01");
    cleanChunk(ch, "Road_*", "vegetation_groundcover_01");
}
```

`zombie/iso/IsoChunk`:

```java
public boolean hasEmptySquaresOnLevelZero() {
    for (int y = 0; y < 8; y++)
        for (int x = 0; x < 8; x++) {
            IsoGridSquare square = this.getGridSquare(x, y, 0);
            if ((square == null || square.getObjects().isEmpty())
                    && !this.hasNonEmptySquareBelow(x, y, 0))
                return true;
        }
    return false;
}
```

**Every one of the 64 squares in an 8×8 chunk must carry at least one object at
z=0** (or on some level below). One gap flips the whole chunk to procedural
generation. Not a threshold, not a majority — one square.

Nothing else gates it. Not chunkdata, not lotheader flags, not static modules.

Note the engine treats a `null` square as empty, so a mirror assertion must
check that a square object exists, not merely that it is non-empty.

**`cleanChunk` does not destroy authored roads — OBSERVED.** A generated map
with 2,808 road tiles loaded in game with the road intact and correct. The
source of `cleanChunk` still has not been read; the match may be against a
tiledef category rather than a sprite-name prefix, and our roads are
`blends_street_01_0`, which does not match a literal `Road_` prefix. Treat the
mechanism as unread but the outcome as benign.

### How a cell is registered as authored at all

`zombie/iso/MapFiles.load()` walks each map directory listing:

- `*.lotheader` → `createLotHeader()`, the **only** thing that expands
  `minX/minY/maxX/maxY`, and registers into `infoFileNames` + `infoHeaders`
- `*.lotpack` → registered in `infoFileNames` only
- `chunkdata_*` → registered in `infoFileNames` only, **no bounds effect,
  no `bgHasCell` entry**

`postLoad()` builds `bgHasCell` from `infoHeaders.containsKey("%d_%d.lotheader")`.
`hasCell(cellX, cellY)` is the engine's authored-or-not predicate.

`IsoMetaGrid.CreateStep1` merges all `MapFiles` **backwards** with `putAll`, so
for a contested cell the *earlier* map directory wins. Then:

```java
minNonProceduralX/Y = minX/minY;   maxNonProceduralX/Y = maxX/maxY;   // authored extent
minX = min(minX, WorldGenParams.getMinXCell());                       // widened to whole world
```

Directory scanning starts from `Core.gameMap` and recurses through
`ChooseGameInfo.getMapDetails(name).getLotDirectories()`. Our `map.info` has
`lots=Muldraugh, KY`, so vanilla Muldraugh is scanned alongside us and the
metagrid bounds are the union. **CONFIRMED working** — `console.txt` shows
`PZGisImport` followed by the whole Knox County chain in the MapGroup list.

### Geometry, confirmed from source

- Chunk = **8×8 tiles**
- Cell = **256×256 tiles** = 32×32 chunks
- PZwiki's File Formats page still documents B41 and is wrong for B42 on magic
  bytes, string terminators, cell size, offset width and chunk size

### `map.info` keys

`fixed2x` is read by `MapFiles.createLotHeader` into **every** LotHeader. Ours
omitted it; **now fixed** — `fixed2x=true` is written.

---

## 4. Coordinate systems — the bug that cost three sessions

**CONFIRMED** by prediction and measurement.

`spawnpoints.lua` `worldX`/`worldY` are **legacy 300-tile cell coordinates**,
not B42 256-tile cells. `posX`/`posY` are offsets within that 300-tile cell.

Emitting `worldX = 200` for cell 200_200 put the player at world tile
200 × 300 = 60000, while the cells occupy 51200..51711. The player landed
~8,800 tiles east, in pure procedural terrain — which looked exactly like
"WorldGen destroyed my map".

Conversion, via absolute world tiles:

```java
int worldTileX = cellX * 256 + localX;
worldX = worldTileX / 300;
posX   = worldTileX % 300;
```

Diagnostic that found it: the save's `map/` subdirectories are named by chunk,
and they were 7530–7570 (world tile ~60240 ≈ 200.8 × 300) instead of the
expected 6400–6463. **When a map "does not load", read the chunk directory
numbers first.** They say where the player actually was.

This is the same 300-vs-256 legacy compatibility seen in `MapFiles.postLoad`,
which converts bounds with `minX * 256.0F / 300.0F`.

---

## 5. `WorldGenOverride.lua`

**CONFIRMED** by source and by in-game test.

Parsed by `WorldGenReader.loadStaticModules`. Schema:

```lua
worldgen["static_modules"] = {
    {
        position = { xmin = ..., xmax = ..., ymin = ..., ymax = ... },
        biome = worldgen.biomes.grass_plain      -- or prefab = {...}
    }
}
```

`StaticModule` is a record `(Biome biome, PrefabStructure prefab, int xmin,
int xmax, int ymin, int ymax)`. At least one of `biome` or `prefab` is required.

It changes **what** generates, not **whether** generation happens:

- **OBSERVED:** adding it changed the in-game result from dense forest to open
  grassland with scattered trees.
- **CONFIRMED:** `WorldGenChunk` does not import or reference `StaticModule`;
  suppression is `hasEmptySquaresOnLevelZero()` alone.

**Keep this file.** Removing it does not stop generation — it just makes the
generated terrain forest again.

`worldgen.biomes` comes from `biomes/worldgen/` — `grass_plain`, `flower_plain`,
`sand_bank`, `water`, and six forest types. It is **not** `biomes_map`.

**This is where species mix belongs.** Per §7, authored data cannot select tree
species. If the far ground should be conifer-dominant, that is a biome
selection question, not a tile placement one. Not yet attempted.

**Untried:** the `prefab` field. `WorldGenReader.loadPrefab` parses it, and it
is the engine's own "place this structure here" mechanism — a plausible route
for placing generated buildings without writing them into the lotpack. The
`media/lua/server/WorldGen/features/` tree confirms the engine has a whole
named-feature vocabulary (`bush`, `ground`, `none`, `ore`, `plant`, `tree`),
each file being a small Lua table of tile names registered into
`worldgen.features.<CATEGORY>`.

---

## 6. `chunkdata_X_Y.bin` — solved as a question, not as a format

The old roadmap called this the blocker. **It is not.** It is zombie population
data and has no influence on WorldGen.

Evidence:
- `natives/libPZPopMan64.so` exports `Java_zombie_popman_ZombiePopulationManager_n_1saveCell`,
  `..._n_1loadChunk`, `..._n_1addZombie`
- `LoadGameScreen.lua:231` offers a debug menu item `DeleteChunkDataXYBin`
- `MapFiles.load()` registers `chunkdata_*` with no bounds or `bgHasCell` effect
- Save-vs-map diff of cell `41_40`: grid 99.1% identical but only 50 of 399
  blocks match. Runtime state, not static authoring data.

### Dead ends, so nobody repeats them

Four probe generations tested and killed:

| Hypothesis | Result |
|---|---|
| Any fixed per-value block cost | **Proven impossible.** Exact rational solve over 1191 equations, rank 22/22, 1160 contradictions |
| Recursive subdivision, BFS and DFS | Best 491/1316 non-trivial files; all 825 failures were overruns |
| Depth-capped recursion, `(v & mask) == cmp` predicate pairs | <0.7% |
| Grid-level predicate producing enough blocks | best still 111,540 blocks short |
| Palette / distinct-value count | 0.59% |
| Length-prefixed records | 47.31% determinism |

**Methodological trap:** 2749 of 4065 files have N=0, so every naive success
rate carries a **67.63% floor that means nothing**. Always report rates over
N>0 files.

**Second trap:** "fits `2 + 1024 + N*64`" is arithmetically identical to
"fits `2 + M*64`", since 1024 = 16×64. That check never distinguished anything.

If anyone ever needs this format: **decompile `zombie.popman.*` rather than
running more statistics.**

---

## 7. Tiles, sprites and the two-set problem

The single most useful structural fact learned in session 3:

**Tile definitions and sprite atlases are independent sets.** 61,418 tiles carry
properties; 46,540 sprite names exist across the atlases. A tile can have every
property you filter on and still have no pixels — it writes into the lotpack
correctly, round-trips byte-identically, satisfies
`hasEmptySquaresOnLevelZero()`, and renders as a missing-texture checkerboard.
No existing test catches that.

`SpriteNames.load(mediaDir.resolve("texturepacks"))` builds the sprite name set;
`TilePalette` requires membership before selecting a tile.

### Trees: authored data cannot choose species — CONFIRMED

This took three wrong iterations. Recorded in full so it is not repeated.

Vanilla Muldraugh cell 35_35 authors exactly four tree tiles:
`vegetation_trees_01_8` … `_11`. No species tile appears in any vanilla
lotheader. Those generic tiles carry only:

```
tree            2
solid           (flag)
attachedFloor   (flag)
BlocksPlacement (flag)
vegitation      (flag)
```

…and **have no sprite in any atlas**. The engine substitutes species and mature
size at runtime from the biome.

The `e_redmapleJUMBO_1` / `e_virginiapineJUMBOXXL_1` sheets — 11 species × 8
size classes, full sprite coverage, real tree semantics — are **render-time art**.
Authoring them directly produces canopies lying flat on the grass, because a
sprite whose full frame is 192×256 is 1× art that overhangs its square, not a
2× sprite to be halved.

Practical consequences:

- Placement controls **where** trees are and their `tree` size class. Nothing else.
- Do **not** require a sprite when selecting tree tiles. The correct tiles have none.
- The renderer cannot preview trees. Verify with `Probe square` against a
  vanilla square instead.
- Species mix goes in `WorldGenOverride.lua` (§5).

`crafted_02_86` is the stump tile (`CustomName: Stump`), taken from
`media/lua/server/WorldGen/features/tree/stumps.lua`. It **does** have a sprite.

### Useful discriminating properties

Found by property survey rather than by guessing names:

| Need | Discriminator |
|---|---|
| Grass vs dirt | `grassFloor` bare flag. `blends_natural_01_16` has it; `_101` does not, which is why the ground rendered brown |
| Standalone ground vs edge blend | `solidfloor` present, `FloorOverlay` absent |
| House interior floor | `Material = Wood` (hardwood); `Material = Brick` is bathroom/kitchen tiling |
| Trunk vs ground cover | `solid` on the `vegetation_trees_01` sheet |

`CustomName` is present on interior floors (`"Grey Diagonal Tiles"`,
`"Hardwood Floor"`) but **absent on all natural ground and tree tiles**, so it
cannot be the general selector.

---

## 8. Format layer — CONFIRMED

| Format | Verification |
|---|---|
| `.lotheader` | 4065 / 4065 cells; read and write byte-identical |
| `.lotpack` | 4065 / 4065 cells, 4,162,560 chunks; byte-identical |
| `.pack` | **24 / 24 retail atlases**, both layouts; round-trips byte-identical |
| `.tiles` binary | 73,644 tiles; all 37,060 with a text sibling match 100% |
| `.tiles` text | 61,418 tiles from 7 files, 616 tilesets |
| Sprite join | 46,540 distinct sprite names indexed |
| Room geometry | Wall offsets across 86 rooms, both far sides confirmed |
| Tile semantics | Wall/door/window encoding confirmed by direct inspection |

### `.lotheader` layout

```
char[4]  "LOTH"
int32    version          1
int32    tileCount
         tileName '\n'    x tileCount
int32    levelsAbove      8 in all 4065 cells
int32    levelsBelow      8 in all 4065 cells
int32    minLevel         actual z of chunk index 0; negative for basements
int32    maxLevel         highest actual z containing data
int32    roomCount
  room:  name '\n'; int32 floor; int32 rectCount; int32 x,y,w,h x rectCount;
         int32 objectCount; int32 a,b,c x objectCount
int32    buildingCount
  building: int32 roomCount; int32 roomIndex x roomCount
byte[1024]  32x32 per-chunk grid, values 0..10
```

Across the map: 90,827 rooms and 90,827 room references. Found by fitting
`leftover = 1 + buildings + roomRefs` across all cells, not by guessing.

Wall edge convention: **south wall belongs to the next square down, east wall to
the next square right.**

### `.pack` — BOTH layouts, solved session 3

Previously only the 13 PZPK files parsed. The entry table is identical in both
layouts, **and so is the per-page `int32` after `numEntries`** — believing that
field was PZPK-only was the entire bug. The reader skipped it, read its value as
the first entry's name length, and derailed on byte one.

```
[optional] char[4]   "PZPK"
[optional] int32     version
int32                numPages
page * numPages:
    lenString        pageName
    int32            numEntries
    int32            unknown            1 in almost every page, but 0 on three
                                        pages of UI.pack, so NOT a version
                                        constant. Carried through opaquely.
    entry * numEntries:
        lenString    entryName
        int32 x, y, w, h, ox, oy, fx, fy
    [PZPK]   int32   pngByteLength
    byte[]           pngBytes
    [legacy] int32   0xDEADBEEF         page separator, absent after last page
```

Legacy PNGs have **no length prefix** — walk chunk headers to IEND.

Validation that can fail: after walking `numEntries` entries the offset must
land **exactly** on a PNG magic. All 20 pages across the 11 legacy files landed
exactly, and every file ended on its final byte.

`0xDEADBEEF` absence after the final page is **UNVERIFIED** — an IEND walk that
lands on EOF is indistinguishable from one that lands 4 bytes short and then
consumes a separator. `pageSeparator` is recorded per page rather than derived,
so round-trips are exact either way.

### Sprite scale is a property of the pack, not the sprite

`SpriteAtlas` previously inferred `scale = fx >= 128 ? 0.5 : 1.0`. That held only
while the pack list was `Tiles1x` (64px) and `Tiles2x` (128px). Jumbo tree art is
1× at 192×256 — large, not double-resolution — and the heuristic shrank it to
ground-level blobs. Now `scale = packName.contains("2x") ? 0.5 : 1.0`.

`SpriteAtlas.MAP_PACKS` is a hardcoded list. **A pack absent from it is invisible
to the renderer regardless of whether `PackFile` can parse it.** `JumboTrees1x`
and `JumboTrees2x` were missing, which is why the renderer showed 43 of 50
sprites as not-found while `SpriteNames` reported them present.

Still open: the 3-int32 room object records; `.tiles` writers; TMX interop;
`objects.lua` / `roomtones.lua` / `streets.xml` parsing.

---

## 9. GIS pipeline

Both sources are public domain and need no account.

**Step 1 — draw the area.** Go to <https://geojson.io>, draw a rectangle, copy
the JSON into `/home/kaatlev/pzgis/area.geojson`.

One 256×256 cell ≈ **0.0023° latitude × 0.0029° longitude** around 38°N. Keep
the box small enough that a single render shows something interpretable.

**Step 2 — fetch.**

```fish
python3 ~/pzgis/fetch_gis.py ~/pzgis/area.geojson ~/pzgis
```

- **Buildings** — USA Structures (FEMA / Oak Ridge / USGS). Public domain.
  Carries `OCC_CLS` / `PRIM_OCC` occupancy classes. Only covers structures over
  450 sq ft and was machine-extracted, so sheds and detached garages may be
  missing and footprints can be a metre or two off.
- **Roads** — Census TIGER/Line. Public domain.

⚠️ **TIGERweb splits roads across layers 0–8** by class and scale. The original
script queried **layer 2 only**, which silently returned zero features for any
road not in that class — indistinguishable from "there is no road here".
Pondlick Rd turned out to live in **layer 7**, registered as `Co Hwy 26`.
`fetch_gis.py` now probes all nine layers and merges, deduplicating on geometry.

Dedupe on **geometry, not `LINEARID`** — a road crossing the box in two
segments shares a LINEARID and one segment gets dropped.

**Step 3 — always pass `area.geojson` to the importer.** Feature services
return *whole features* that intersect the bbox, so one road can run for
kilometres beyond your area. Deriving the extent from returned data inflates the
canvas and squashes the buildings into a cluster.

**Step 4 — preview before generating cells:**

```fish
java -cp out pzformat.Probe gisimport ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson ~/pzgis
```

Sanity check on absolute scale: **a typical house should be 10–15 tiles across**
at 1 tile = 1 metre.

**Step 5 — generate cells** (`giscells`, §2).

Generated cells are placed at origin **200_200**, well clear of vanilla Knox
County.

### Tree placement

`TreeScatter` drives density from a BFS distance field to the nearest building
or road square — the same data that gives cleared yards and road verges for
free. Bands: nothing within 3 tiles, then sparse, regrowth, woodland, dense.
Stumps scattered at 0.1%. Minimum 2-tile spacing between trunks.

Species and mature size are **not** controllable here (§7). The elaborate
species/succession machinery built in session 3 was deleted; what survives is
the distance field, the density curve, spacing and stumps.

---

## 10. Test log

| # | Test | Result |
|---|---|---|
| 1 | Round-trip all 4065 vanilla cells | Byte-identical. **But** x/y were transposed and the test passed anyway |
| 2 | Edit a vanilla cell, load in game | Rendered the intended change and nothing else |
| 3 | First GIS import | Buildings clustered — extent taken from returned data |
| 4 | Import with `area.geojson` clipping | Projection and rasterisation sound |
| 5 | First mod load | Map absent from location list |
| 6 | Mod restructured to match Maplewood | Map appears in location list |
| 7 | Play the generated map | Buried in dense forest |
| 8 | Render cell 201_200 | **Buildings correct** — walls on all four sides, interior floors |
| 9 | Add `WorldGenOverride.lua` (grass_plain) | Open grassland with scattered trees. Did not see buildings |
| 10 | chunkdata probes ×4 | All hypotheses killed (§6) |
| 11 | Decompile the engine | Found the real gate (§3) |
| 12 | Edge fill + `assertNoEmptySquares` | 2,474,010 → **3,145,728** squares; 671,718 previously empty; assertion passes on all cells |
| 13 | Palette sprite requirement | 167 candidates dropped for having properties but no sprite |
| 14 | Load in game after edge fill | Still no buildings — chunk dirs 7530–7570, not 6400s |
| 15 | **Spawn coordinate fix** (§4) | Chunk dirs **6396+**. On the map. |
| 16 | Spawn moved to a road square | **Road and building both correct in game.** Blocker disproved |
| 17 | Palette by semantics | Grass green, floor `"Hardwood Floor"` |
| 18 | Legacy `.pack` parse | **24/24 packs**, byte-identical round-trip, 224 self-tests still pass |
| 19 | `SpriteAtlas.MAP_PACKS` extended | `sprites not found: 0 / 50`, `skipped: 0` |
| 20 | Tree placement, three species iterations | All wrong (§7) |
| 21 | `findprop` against vanilla Muldraugh 35_35 | Generic `vegetation_trees_01_*`. Rewrote to match |
| 22 | Generated square vs vanilla square | Same tile family, same properties |

Generation stats from the last run:

```
features: 7 buildings, 1 roads
extent: 495 x 424 tiles (3.20 cells)
placed: 7 buildings (1530 tiles), 1 roads (2808 tiles)
palette: floor=floors_interior_tilesandwood_01_40 ("Hardwood Floor", Wood)
         road=blends_street_01_0   grass=blends_natural_01_16 [grassFloor]
         wallN=walls_exterior_house_01_1   wallW=walls_exterior_house_01_0
trees: 7797 placed, 118 stumps
cells written: 4   squares: 262144   rooms: 8   edge-filled: 52264
spawn: cell 200_200, world tile 51312,51389 (chunk 6414,6423)
```

---

## 11. Todo

### Buildings — the next substantial piece

Current output is one bounding box per footprint with derived perimeter walls.
No roof, no interior subdivision, no doors, no windows.

**Decide the approach before writing code**, and read a vanilla house first
(`Probe square` / `findprop` against Muldraugh) rather than inferring:

- [ ] Decompose footprints into multiple room rectangles, not one bounding box
- [ ] Template-stamped houses on footprints (hook: `GisCells.buildingRects`)
- [ ] Roofs; exterior doors on street-facing walls; windows
- [ ] Interior subdivision and doorways
- [ ] Meaningful room *type* names — generic `"room"` gives no loot tables
- [ ] Investigate `StaticModule.prefab` as an engine-native alternative (§5)
- [ ] Use `OCC_CLS` / `PRIM_OCC` to vary wall and floor materials

### GIS import quality

- [ ] Road auto-tiling — corner, T-junction, end, edge by neighbour bitmask
      (current edges are hard stair-steps with no blending)
- [ ] Narrow road widths per road class
- [ ] Populate `objects.lua` (currently `objects = {}`; vanilla's is 4 MB)
- [ ] `worldmap.xml` so imported areas appear on the in-game map — `mapdir`
      reports it missing
- [ ] Biome selection in `WorldGenOverride.lua` for species mix (§5, §7)
- [ ] Consider USGS 3DEP elevation for basement placement

### Format completeness

- [ ] Read `WorldGenChunk.cleanChunk` — outcome is benign but mechanism unread
- [ ] 3-int32 room object records in `.lotheader`
- [ ] `.tiles` writers; TMX import/export
- [ ] Parse `objects.lua`, `roomtones.lua`, `streets.xml`
- [ ] `pyramid.zip` / `forest.pyramid.zip`
- [ ] Verify the `0xDEADBEEF` separator is genuinely absent after the last page

### Editor

- [ ] Auto wall-joining from neighbours
- [ ] Validation: doorway with no adjacent floor, room with no exit, wall gap
      that is not a door
- [ ] Live renderer with pan and zoom; tile palette UI
- [ ] Layer visibility, z-level switching, multi-cell streaming
- [ ] Fast round-trip regression (current re-parses per policy, ~14 min)

### Project

- [ ] Publish B42 format documentation (PZwiki is B41 and wrong)
- [ ] Verify GIS dataset licensing per-state before distributing maps
- [ ] Choose a licence

---

## 12. Method notes

Six real bugs got through automated testing. **All six** were caught by
comparison against an *independent* source, never by more testing of the same
kind.

- **x/y transposition** — 4065 cells round-tripped byte-identical while every
  coordinate was mirrored, because read and write shared the same wrong
  formula. Caught by checking lotheader room rectangles against the tiles
  beneath them.
- **`.pack` "verified"** — the reader was checked only against fixtures this
  project generated, which proves the reader agrees with the writer and nothing
  else. Every retail atlas failed on first contact.
- **Wall encoding** — keying off `attachedN`/`attachedW` validated at 99.5%
  against room geometry while being wrong. Decoration hangs on walls, so it
  occupies the same squares. A correlated proxy can pass a test for the wrong
  reason.
- **chunkdata** — four probe generations and thousands of hypothesis tests on a
  file that turned out to be zombie population data. Ten minutes with a
  decompiler answered what statistics could not.
- **Spawn coordinates** — three sessions of in-game tests all measured a
  location 8,800 tiles from the map, and produced a recorded "project blocker"
  that never existed. Nothing about the cells was wrong. Caught by reading the
  save's chunk directory numbers — an artefact the engine wrote, not one we
  produced.
- **Tree species art** — three full iterations of species/succession machinery
  built on the assumption that maps author species tiles. One `findprop` against
  a vanilla Muldraugh cell would have shown generic `vegetation_trees_01_*`
  immediately. The same lesson as chunkdata, not applied.

Byte-identical round-tripping proves a format was read and written faithfully.
It says nothing about whether it was *interpreted* correctly.

Corollaries earned the hard way:

- **Check what vanilla does before building the thing.** Retail data is the
  independent source and it is sitting right there. This has now cost two
  multi-session detours.
- **Report rates over the population that can discriminate.** A 67.63% floor
  from trivially-empty files made noise look like signal for two sessions.
- **Check that a test can fail.** An underdetermined linear system is
  *trivially* consistent; reporting "CONSISTENT" from 4 equations and 17
  unknowns is meaningless. When a hypothesis "confirms", ask what output would
  have falsified it — the `.pack` PNG-anchor check is the good example.
- **Predict the number before running the command.** "squares should be
  3,145,728 and edge-filled 671,718" turns a run into a test.
- **The source is right there.** PZ is Java. `unzip projectzomboid.jar`,
  Vineflower, and `grep -rl <symbol> --include='*.class'` beats inference from
  file bytes every time. The Lua under `media/lua/server/WorldGen/` is plain
  text and equally readable.
- **Change one thing per test.**
- **A renderer is a hypothesis too.** It showed trees as ground-level shrubs for
  two rounds because of a scale heuristic, and showed 43 sprites missing because
  of a hardcoded pack list. When the picture looks wrong, the picture may be
  what is wrong.
