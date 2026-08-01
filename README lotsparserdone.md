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
int32    minLevel         0 usually; negative in 72 cells (basements)
int32    unknown          MEANING NOT IDENTIFIED - see below
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

**Unresolved:** the int32 at `+12`. It is nonzero in 997 cells but only 765
cells contain any rooms, so at least 232 cells have it set with no rooms —
it cannot be room-derived. Tested and rejected: distinct room-name count,
max floor + 1. It does not affect parse correctness.

### `world_X_Y.lotpack` — CONFIRMED

```
char[4]  "LOTP"
int32    version        1
int32    chunkCount     1024  (32x32 chunks of 8x8 tiles = 256x256 cell)
int64    offset[chunkCount]      64-bit, unlike B41's 32-bit table
         chunk bodies at those offsets
```

Header size is `12 + 8*chunkCount`, which equals `offset[0]` exactly.

Chunk body, iterating z (16 levels = levelsAbove + levelsBelow), then x, then y
across the 8×8 chunk:

```
int32 count
  count == -1  -> int32 run     that many consecutive empty squares
  otherwise    -> int32 roomId  (-1 = none)
                  int32 tileIndex x (count - 1)   into LotHeader.tileNames
```

Verification: every chunk body ends exactly at the next chunk's offset.

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

1. `.tiles` / `newtiledefinitions.tiles` — tile properties (wall direction,
   door, container, IsoObject type). The semantic layer an editor needs.
2. Identify `+12`.
3. Writers, validated by round-tripping retail cells byte-for-byte.
4. TMX interop with the official tools.
