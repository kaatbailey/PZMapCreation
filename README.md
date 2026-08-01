# pzformat

Java parsers for Project Zomboid **Build 42** map files, reverse-engineered from
retail 42.20 data and verified against all 4065 cells of Muldraugh, KY.

No dependencies. Java 17+.

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

Chunk body — squares in z, x, y order across the 8×8 chunk, **driven by byte
position, not a fixed level count**. Trailing empty levels are omitted from the
file, so bodies vary in length and a fixed 16-level loop overruns into the next
chunk.

```
int32 count
  count == -1  -> int32 run     that many consecutive empty squares
  otherwise    -> int32 roomId  (-1 = none)
                  int32 tileIndex x (count - 1)   into LotHeader.tileNames
```

Verification: 4065/4065 cells, 4,162,560 chunks, every body ending exactly at
the next chunk's offset. Squares covered per chunk is always a multiple of 64,
confirming the z/x/y ordering.

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

## Commands

```
Probe pack      <file.pack> [--extract <dir>]
Probe lotheader <file.lotheader>
Probe lotpack   <world_X_Y.lotpack> <X_Y.lotheader>
Probe trailer   <file.lotheader>          hypothesis search over trailer layout
Probe survey    <media/maps/MapName>      verify every cell in a map
Probe mapdir    <media/maps/MapName>      folder inventory
```

## Method

Every layout claim here is falsifiable and was tested that way. Parsers throw
with a byte offset rather than returning plausible garbage; hypotheses are
accepted only on exact byte consumption with all fields in range; and claims are
checked against thousands of cells, not one. A layout that merely looks right
fails somewhere in 4065 samples.

## Next

1. Binary `.tiles` parsing. `TileDefs` currently reads the plaintext
   `*.tiles.txt` siblings, which vanilla ships but mods generally do not.
   The 33,568 text-parsed tiles are ground truth to validate a binary parser.
2. TMX interop with the official tools.
3. The 3-int32 room object records are read but not interpreted.

## Writers

`LotHeader.write()` and `LotPack.write(Policy)` re-serialise parsed data.
`Probe roundtrip` reads each cell, writes it back, and byte-compares against
the original — a stronger test than parsing, since it proves nothing was
silently dropped or reordered rather than merely consumed.

Chunk-body encoding has a degree of freedom that reading cannot reveal: whether
runs of empty squares may span level boundaries, and whether a chunk encodes
only the levels it needs or the cell's full level count. All four combinations
are implemented as `LotPack.Policy` and scored against retail bytes; the one
that reproduces every chunk is the encoder's real policy.
