# pzformat — AI session handoff

Paste this file, then `pzformat-state.md`, at the start of a new session.

---

## What this is

A Java library and toolset for reading, editing, generating and rendering
Project Zomboid **Build 42** (retail 42.20) map data, plus a pipeline that turns
public-domain GIS data into a playable map mod. No dependencies, Java 21,
224 self-tests. Solo project, no CI, no code review.

The immediate goal is a generated map that looks like it belongs in the game —
correct terrain, sensible buildings, no visible seam against the surrounding
procedurally generated land.

## Where things stand

The pipeline works end to end. A GIS area becomes 4 cells that load in game with
correct roads, buildings, ground and vegetation, and no visible boundary against
the surrounding land.

The last three sessions were mostly spent discovering that several early
assumptions were wrong. The corrections are in §0 of the state document and are
the highest-value thing in it.

Most recently solved: the biome map (`maps/biomemap_X_Y.png`, RED = biome band,
GREEN = zone band, one pixel per tile). Writing it is what made terrain
continuous across the map edge, because the engine then generates vegetation on
our cells from the same rules it uses everywhere else.

**Next up, in order:**

1. **Cleanup with evidence already in hand.** `TreeScatter` / `TreePalette`
   place ~7,800 trees that `genMapSquare` deletes on load, and
   `WorldGenOverride.lua` is superseded by the biome map. Delete both,
   regenerate, and confirm in game that nothing changes — that proves they were
   dead weight rather than assuming it.
2. **Buildings.** The substantial piece, and where the project has been trying
   to get for three sessions. Current output is one bounding box per footprint
   with derived perimeter walls: no roof, no interior subdivision, no doors, no
   windows. There is an open design fork between building a room-splitting
   generator and using `StaticModule.prefab`, the engine's own structure
   placement mechanism, which has never been tried.

## How to work on this project

This matters more than it usually would. Eight real bugs have got through the
automated tests, and every one was caught by comparison against an independent
source rather than by more testing.

- **Check what vanilla does before building anything.** Retail map data and the
  game's own Lua under `media/lua/server/WorldGen/` are readable and
  authoritative. Skipping this step has cost three multi-session detours.
- **Prefer the recipe to the output.** Measuring Muldraugh describes one
  hand-authored town. Reading the generator's Lua describes the generator.
- **Predict the number before running the command.** Turns a run into a test.
- **Ask what would falsify a result.** A test that cannot fail proves nothing.
- **Change one thing per test.**
- **The renderer is a hypothesis too.** It has been wrong twice. When the
  picture looks wrong, the picture may be what is wrong.
- **PZ is Java.** `unzip projectzomboid.jar`, Vineflower, and
  `grep -rl <symbol> --include='*.class'` beat inference from file bytes. Ten
  minutes with a decompiler has repeatedly answered what statistics could not.

Working style that has been productive: propose an approach, name the check that
would prove it wrong, run it, then write code. Patches are delivered as Python
scripts that abort unless each anchor matches exactly once — fish has no
heredocs, so multi-line edits need a file.

## Environment

Garuda Linux, fish shell, Java 21, IntelliJ.

- Repo `~/Documents/PZMapCreation`, source in `src/main/java/pzformat/`
- **Run from the repo root** — `-cp out` is relative and fails silently from `~`
- `$PZ` and `$MAPS` are fish universal vars pointing at the game install
- **`ls` is aliased to eza** and rejects `-t` with a bare `-d`; use `command ls`
  when a glob result matters
- Files are handed over as downloads to `~/Downloads`, then copied into the repo

## The loop

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

Two commands do most of the verification:

```
Probe square   <mediadir> <mapdir> <X_Y> <x> <y> <z>
Probe findprop <mediadir> <mapdir> <X_Y> <prop>
```

Point them at `"$MAPS/Muldraugh, KY"` to see what vanilla does, and at the
generated mod to see what we do. Comparing the two is the single most effective
technique in this project.

## Refresh before starting

```fish
cd ~/Documents/PZMapCreation
find src -name '*.java' | sort
git log --oneline -15
```

## Traps that have already cost time

- `spawnpoints.lua` uses the **legacy 300-tile grid**, not B42's 256. When a map
  "does not load", read the save's `map/` chunk directory numbers first — they
  say where the player actually was.
- Every one of a chunk's 64 squares must carry an object at z=0 or the whole
  chunk is replaced with procedural terrain.
- Tile definitions and sprite atlases are **independent sets**. A tile can have
  every property you filter on and no pixels.
- Authored map data **cannot** specify tree species or mature size, and cannot
  keep authored trees, bushes or grass at all — the engine replaces them per
  tile from the biome map.
- `SpriteAtlas.MAP_PACKS` is a hardcoded list; a pack missing from it is
  invisible to the renderer even if `PackFile` parses it fine.
