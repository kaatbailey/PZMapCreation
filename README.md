# pzformat

Java parsers and writers for Project Zomboid **Build 42** map files, reverse
engineered from retail 42.20 data and verified against the full vanilla dataset.

No dependencies. Java 17+.

| format | verification |
|---|---|
| `.lotheader` | 4065 / 4065 cells, read and write byte-identical |
| `.lotpack` | 4065 / 4065 cells, 4,162,560 chunks, read and write byte-identical |
| `.pack` | round-trips byte-identical |
| `.tiles` binary | 73,644 tiles; all 37,060 with a text sibling match 100% |
| `.tiles` text | 33,568 tiles, 216 property keys |

```bash
javac -d out $(find src -name '*.java')
java -cp out pzformat.SelfTest                    # fixture tests, no game files needed
java -cp out pzformat.Probe survey "$PZ/media/maps/Muldraugh, KY"
```

## Formats

### `X_Y.lotheader` — CONFIRMED

Build 42 replaced the B41 layout entirely: a `LOTH` magic was added, tile names
became newline-separated instead of NUL-terminated, and cell width/height/levels
are **no longer stored in the file** (geometry is global: 256×256 cells).

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
  room:  name '\n'
         int32 floor
         int32 rectCount
         int32 x, y, w, h    x rectCount
         int32 objectCount
         int32 a, b, c       x objectCount
int32    buildingCount
  building: int32 roomCount
            int32 roomIndex  x roomCount
byte[1024]  32x32 per-chunk grid, values 0..10
```

Verification: 4065/4065 cells parse with exact byte consumption, every room
index in range, and exactly 1024 grid bytes remaining. Across the map that is
90,827 rooms and 90,827 room references — **every room belongs to exactly one
building**, in every cell.

The layout was found by fitting `leftover = 1 + buildings + roomRefs` across all
cells rather than by guessing record shapes; see `TrailerAnalysis`.

#### z-levels

```
actualZ       = chunkIndex + minLevel
levelsEncoded = maxLevel - minLevel + 1
```

Both identities hold for 4065/4065 cells. `levelsAbove`/`levelsBelow` are
constant at 8 and are *not* the encoded level count — 4 cells encode more
(49_5 has 30 levels, 21_48 has 26 with `minLevel = -17`), and 3068 wilderness
cells encode just one.

### `world_X_Y.lotpack` — CONFIRMED

```
char[4]  "LOTP"
int32    version        1
int32    chunkCount     1024  (32x32 chunks of 8x8 tiles = 256x256 cell)
int64    offset[chunkCount]      64-bit, unlike B41's 32-bit table
         chunk bodies at those offsets
```

Header size is `12 + 8*chunkCount`, which equals `offset[0]` exactly.

Chunk body — squares in z, x, y order across the 8×8 chunk. **Every chunk
encodes the cell's full `levelCount`** (`maxLevel - minLevel + 1`), so all
chunks in a cell cover the same number of squares. Body *lengths* still vary,
because runs of empty squares compress; and `levelCount` varies per cell, which
is why a hardcoded 16-level loop overruns on cells that encode fewer or more.

```
int32 count
  count == -1  -> int32 run     that many consecutive empty squares
  otherwise    -> int32 roomId  (-1 = none)
                  int32 tileIndex x (count - 1)   into LotHeader.tileNames
```

Verification: 4065/4065 cells, 4,162,560 chunks, every body ending exactly at
the next chunk's offset. Squares covered per chunk is always a multiple of 64,
confirming the z/x/y ordering. Re-encoding reproduces every chunk byte for
byte — see Writers.

### `.pack` — texture atlases

```
int32  numPages
page:  lenString name
       int32 numEntries
       entry: lenString name, int32 x, y, w, h, ox, oy, fx, fy
       int32 pngLength
       byte[] png
```

Self-validating: each page's entry table is followed by real PNG bytes, so
landing on PNG magic proves the table parsed correctly. `Probe pack` also
re-serialises and byte-compares, confirming the writer.

### `.tiles` — tile properties (binary) — CONFIRMED

The semantic layer: what a tile *is*, as opposed to which sprite it draws.
Vanilla ships both a binary `.tiles` and a plaintext `.tiles.txt`; mods
generally ship only the binary, so the binary parser is what makes modded maps
workable.

```
char[4]   "tdef"
int32     version          (1)
int32     tilesetCount
tileset:
    string  name            "e_americanhollyJUMBO_1"
    string  imageFile       "e_americanhollyJUMBO_1.png"
    int32   width, height   in tiles
    int32   id
    int32   tileCount       = width * height
    tile:                   one per sheet position, in index order
        int32   propCount
        propCount x (string key, string value)
```

All strings are `'\n'`-terminated. A **valueless key is a boolean flag** —
stored as a key followed by an empty value (`solid` → `""`).

There is no index or xy field per tile: records appear in index order and cover
every sheet position, so `index = y * width + x` and the tile name is
`tilesetName + "_" + index`.

**The binary holds roughly twice as many tiles as its `.txt` sibling** — the
text dump omits propertyless tiles, the binary does not. This is expected, not
a parse error.

Verification: 12 candidate layouts were tried (tileset prelude length 0–2 ×
four per-tile shapes); exactly one consumes files to the exact byte. Across the
7 retail binaries — 73,644 tiles — all 37,060 tiles present in a text sibling
match property-for-property, and the 2 files with no sibling parse cleanly.

### `.tiles.txt` — tile properties (text)

Human-readable sibling, parsed by `TileDefs`. Nested `tileset { ... tile { ... } }`
blocks, `key = value` lines, and a `//` comment naming each tile — which was
used to confirm the index formula against all 33,568 entries.

Joins to map data at 99.39% on a dense cell; the remainder are tiles that exist
in the atlas but carry no properties.

Useful keys for an editor: `wall`, `WallOverlay`, `Facing`, `attachedN`,
`attachedW`, `DoorWallN`, `DoorWallW`, `WindowShape`, `container`,
`ContainerCapacity`, `solid`, `solidtrans`, `BlocksPlacement`, `attachedFloor`.

## Commands

```
Probe pack      <file.pack> [--extract <dir>]
Probe lotheader <file.lotheader>
Probe lotpack   <world_X_Y.lotpack> <X_Y.lotheader>
Probe trailer   <file.lotheader>            hypothesis search over trailer layout
Probe survey    <media/maps/MapName>        verify every cell in a map
Probe roundtrip <media/maps/MapName> [n]    read -> write -> byte-compare
Probe tiles     <media dir> [lotheader]     text tile properties, join to a cell
Probe tilebin   <file.tiles> [.txt]         structural analysis of a binary
Probe tilesolve <file.tiles> [.txt]         solve + verify one binary
Probe tileall   <media dir>                 parse every binary .tiles
Probe mapdir    <media/maps/MapName>        folder inventory
```

## A bug byte-comparison could not catch

The chunk offset table is **column-major** (`index = cx * chunksPerSide + cy`).
It was first implemented row-major, which transposed every coordinate in every
cell — the map was mirrored about its diagonal.

Round-tripping did not catch this. Read and write shared the same wrong formula,
so all 4065 cells reproduced byte for byte while every tile sat in the wrong
place. Nor did parsing: the data was internally consistent.

It surfaced only against an independent source. Room rectangles live in the
lotheader, not the lotpack, so they are unaffected by chunk indexing — and rooms
are indoors by definition. Checking every room rectangle against the tiles
beneath it:

```
as (x,y):   85 / 1563 room squares on an interior floor   ( 5.4%)
as (y,x):  478 / 1563 room squares on an interior floor   (30.6%)
```

The lesson worth keeping: byte-identical round-tripping proves a format was read
and written faithfully. It says nothing about whether it was *interpreted*
correctly. Those need separate evidence — ideally from a part of the format that
does not share the suspect code path.

`Probe locate <mapdir> <worldX> <worldY>` runs this check on demand.

## Method

Every layout claim here is falsifiable and was tested that way. Parsers throw
with a byte offset rather than returning plausible garbage; hypotheses are
accepted only on exact byte consumption with all fields in range; and claims are
checked against thousands of cells, not one. A layout that merely looks right
fails somewhere in 4065 samples.

## Next

The read and write path is complete and verified. What remains is the editor
rather than the formats.

1. A `Cell` model with an undo journal, decoupled from the file formats, with
   tile properties indexed by the join keys above. This is where auto
   wall-joining and room validation live.
2. A fast round-trip regression check. `Probe roundtrip` re-parses every chunk
   once per policy and takes ~14 minutes on the full map; now that the policy
   is known, a single-policy version reusing parsed chunks should run in
   seconds and serve as the post-change regression test.
3. TMX interop with the official tools.
4. The 3-int32 room object records are read but not interpreted.
5. `.tiles` writers, for emitting modded tile definitions.

## Writers

`LotHeader.write()` and `LotPack.write(Policy)` re-serialise parsed data.
`Probe roundtrip` reads each cell, writes it back, and byte-compares against
the original — a stronger test than parsing, since it proves nothing was
silently dropped or reordered rather than merely consumed.

**Verified byte-identical against the full retail dataset:**

| | result |
|---|---|
| `.lotheader` | 4065 / 4065 cells |
| `.lotpack` | 4065 / 4065 cells, 4,162,560 chunks |

Chunk-body encoding has a degree of freedom that reading cannot reveal: whether
runs of empty squares may span level boundaries, and whether a chunk encodes
only the levels it needs or the cell's full level count. All four combinations
are implemented as `LotPack.Policy` and scored against retail bytes.

**`SPAN_LEVELS_FULL` is the encoder's policy** — every chunk encodes the cell's
full `levelCount`, and runs of empty squares span level boundaries freely:

```
SPAN_LEVELS_MINIMAL       76.01%   3068/4065 files
BREAK_AT_LEVEL_MINIMAL    75.65%   3068/4065 files
SPAN_LEVELS_FULL         100.00%   4065/4065 files   <-- confirmed
BREAK_AT_LEVEL_FULL       79.34%   3214/4065 files
```

The alternatives scoring 76–79% is what makes this conclusive: a merely
plausible policy scores high, not perfect. Only the true one reproduces every
chunk, including the outlier cells (`21_48` at `minLevel = -17`, `49_5` at 30
levels).

An earlier draft of this document claimed trailing empty levels are omitted.
That was wrong — it was inferred from variable body lengths, and the round-trip
disproved it.
