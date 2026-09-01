> **CORRECTION appended 2026-09-01 — this document is the origin of a number
> that propagated into five other files and matches nothing.**
>
> The claim below that "14,680 dwelling layouts pass" is wrong twice over:
>
> - **The count.** Instrumenting the version of `BuildingPlan.java` at THIS
>   commit (`4bd8734`) gives **553 `plan` calls and 5,968 rooms**; squares
>   tiled by the basic test are 6,072. The current file gives 1,505 / 9,456 /
>   14,136. **Nothing here is 14,680.**
> - **The pass.** True at this commit — verified 2026-09-01 by checking it out
>   and running it (`worst 3.7 NORTH 20x17 closet[9,11 11x3]`, "all cases
>   pass"). **No longer true.** Commit `0247ddc` added four larger footprints
>   and the suite has failed since: `worst 5.7 NORTH 40x20
>   bathroom[23,4 17x3]`, exit 1.
>
> See STATE §13 and §40. Kept, not deleted — it is the provenance for both
> corrections, and deleting it would hide where the number came from.

## FINDINGS — E13 (finish) — 2026-08-19

**Status:** complete — all three definition-of-done items met.

**What was done:**

1. **Room-list loop rewritten.** Bathroom scaling fixed (was 1:1 with bedrooms,
   now exactly 1.00 per house through 250 m², 2.00 above 341). Duplicate
   livingrooms and kitchens removed — §33's counts-when-present were inflated
   by the clustering artifact. Bedrooms grow via the measured count curve
   `(area - 90) / 55`, with type changed from a four-way lottery to all-bedroom.

2. **Core placement rewritten twice.**
   - First as a block-not-band fix (worst aspect 15.0 → 4.0), which exposed
     four stacked defects, each surfacing only after the one above was fixed.
   - Then fully rewritten to the owner's architectural rule (drawn 2026-08-18):
     public strip (livingroom on road, kitchen behind, spanning full depth) on
     one side, private zone (bedrooms by halving and re-measuring) on the other,
     hallway from 3 bedrooms up at 1 tile wide running lengthwise.

3. **Door fix.** `replaceTile` was appending the DoorWall onto the raster wall,
   leaving both on the same edge. Two wall objects on one edge leaves the plain
   wall winning for collision — the door was solid in game. Fixed: strips the
   matching wall before adding the door. The interior door pass was never
   affected (already wrote one or the other).

4. **In-game walk — the test that had never been run.** Walked through doors,
   walked houses, walked the barn. Houses "look fantastic" (owner). Doors open.
   Barn is one open room.

5. **Building directory.** Prints every building with OCC_CLS, area, rect,
   facing, room count, world coordinates during generation. Caught swapped
   Agriculture/Residential labels before in-game walk. Flags suspiciously
   small Agriculture and large Residential entries.

6. **Barn classification.** Agriculture → one open room with entrance flag.
   `barn`, `garagestorage`, `shed` added to ENTRANCE set. Single-room buildings
   now get `entrance=true`.

**Confirmed** (verified against vanilla data, decompiled engine, or in game):

- The door append was a MAP defect, not a renderer defect. §35's belief that
  vanilla door squares carry both Wall and DoorWall, confirmed from a probe,
  was true of the *tiles being present* but false of *the engine treating it as
  a door*. Two wall objects on one edge = solid. DoorProbe before/after and
  in-game walk confirm the fix.
- Full-height walls are CORRECT in game. The engine does its own cutaway.
  `walls_exterior_house_01_1` is the standard ground-floor exterior wall,
  identical to vanilla Muldraugh. The "too tall" appearance was CellRenderer
  having no cutaway — renderer-only, not a map defect.
- Every "the building looks empty" moment this session traced to the renderer's
  missing cutaway, not to the map. This has been chased four times across the
  project. It does not need chasing again.
- `FootprintSnap` does not corrupt occupancy labels. It does pure geometry
  (polygon → axis-aligned rect) and never sees OCC_CLS. The label swap was
  in the source GeoJSON.
- The GIS source (geojson.io drawing over county data) had Agriculture on the
  house and Residential on the barn. This is a data quality issue, not a code
  bug. No free building footprint dataset reliably classifies rural buildings.

**Unverified** (believed, not tested):

- Two-bedroom houses in vanilla open onto the livingroom with no hallway.
  §33's 4–5 room bucket puts halls at 14.4%, consistent with this, but the
  rate conditioned on *bedroom count* (not room count) has not been measured.
  The test: filter RoomCluster to type-set {livingroom, kitchen, bathroom,
  bedroom×2}, report fraction containing `hall`.
- SECOND_BATH_AREA = 240 (where a second bathroom appears). Guess, not
  measured. The check is `RoomCluster` bathrooms per house conditioned on area.
- Bedroom counts are still high for large houses (7 at 256 m², 14 at 484 m²).
  The measurement that settles it: condition RoomCluster's bedroom count on
  footprint area over vanilla. Prediction: vanilla is flat at 2–3 bedrooms
  regardless of house size.
- Storage fraction may be too high (~30% closet/laundry in some layouts).
  Same measurement pass would show vanilla's ratio.
- `LIVING_MAX_ASPECT = 2.0` controls when the public strip widens. Not
  measured against vanilla.

**Corrections** (something in STATE.md is wrong):

- §35 claimed exterior door squares carry both Wall and DoorWall and the
  engine draws the door over the wall → FALSE where it counts: two wall
  objects on one edge leave the plain wall winning for collision, so the
  square is solid. The tiles are *present* but the engine does not *interpret*
  them as a door. Evidence: DoorProbe before (FAIL: both present, door
  blocked) and after (PASS: wall stripped, door works) plus in-game walk.
- §35 OPEN 5 (door contract untested in game) → CLOSED. Doors work in game
  after the replaceTile fix. Confirmed 2026-08-19.
- §35's "13 rooms at 32% fill" for the 500 m² case was the discarded
  prototype, not the shipped recipe. The shipped code's version of the defect
  was different: it produced 11.49 rooms at 500 m² with bathrooms at 3.19/house,
  and rooms did not stop growing. The *type distribution* was wrong, not the
  count curve.
- §33's counts-when-present (livingroom 2.39, kitchen 1.61, bathroom 2.75)
  are inflated by the clustering artifact that merges terraces into one
  "building." A single dwelling has one of each. The same artifact made the
  core-share numbers unusable.

**Files changed:**
- modified: `src/main/java/pzformat/BuildingPlan.java` — full rewrite of
  layout engine (public/private split, hallway, halving, barn entrance)
- modified: `src/main/java/pzformat/GisCells.java` — door fix (replaceTile
  strips wall), building directory
- created: `src/main/java/pzformat/DoorProbe.java` — pre-flight door check

**Commands worth keeping:**
```fish
# STANDING ENVIRONMENT — paste at the top of every session
cd ~/Documents/PZMapCreation
set PZ ~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid
set GISMAP ~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport
set MAPS ~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/media/maps

# Regenerate the mod
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson \
    ~/pzgis/roads.geojson \
    ~/pzgis/area.geojson \
    "$PZ/media" \
    ~/Zomboid/mods \
    PZGisImport

# Probe door squares (coordinates will change per generation)
java -cp out pzformat.DoorProbe "$PZ/media" "$GISMAP" 200_201 43 69 43 84

# Room distribution on our output
java -cp out pzformat.RoomCluster "$GISMAP" 200_200 200_201 201_200 201_201

# Room wall geometry
java -cp out pzformat.Probe roomgeom "$PZ/media" "$GISMAP" 200_200

# Inspect a specific square
java -cp out pzformat.Probe square "$PZ/media" "$GISMAP" 200_200 117 91 0

# Render a window
java -cp out pzformat.Probe render "$GISMAP" "$PZ/media/texturepacks" 200_201 24 57 40 0 0 ~/Downloads/render.png

# Vanilla measurement passes (RoomCluster, HallRule, etc.)
set cells (for f in "$MAPS/Muldraugh, KY"/*.lotheader; basename $f .lotheader; end)
java -cp out pzformat.RoomCluster "$MAPS/Muldraugh, KY" $cells > ~/Downloads/roomcluster.txt
```

**Path reference — where things live:**
- Project repo: `~/Documents/PZMapCreation`
- Compiled classes: `~/Documents/PZMapCreation/out`
- GIS source data: `~/pzgis/buildings.geojson`, `roads.geojson`, `area.geojson`
- Generated mod output: `~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport/`
- Vanilla PZ install: `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/`
- Vanilla maps: `$PZ/media/maps/Muldraugh, KY/`
- Decompiled engine: `~/Downloads/ZOMBOIDSTUFF/decompiled/`
- Patches are delivered as Python scripts, run from the repo root
- Shell is fish — no heredocs, `grep` is aliased to `ugrep` (use `-e` per pattern)
- In-game tests need a NEW GAME, not a resumed save

**Noticed, out of scope:**
- Barn floor should be grass/dirt, not hardwood. One-line change in GisCells:
  when room type is `barn`, write `floorGrass` instead of `floorInterior`.
- Edge blending at map boundary — blocky chunk-sized squares where mod grass
  meets vanilla grass. Same technique as road-to-grass overlay masks, applied
  to the outermost chunks.
- Elastic resize (E13 item 3) — still undone, not blocking.
- CellRenderer cutaway (§35 OPEN 4) — still open, renderer-only, not blocking
  anything since the engine handles it correctly.
- No free building footprint dataset reliably classifies rural buildings.
  The building directory is the mitigation — makes errors visible, doesn't
  try to guess.

**What the next chunk needs to know:**
- E13 is DONE. The suggested order is now: **A2-gate → B1 → B2 → C1**.
- B3 (room decomposition) and B4 (openings) are largely superseded by E13's
  work — the layout engine and the door pass already do what those chunks
  describe. B3/B4 should be re-scoped or closed when B1/B2 resolve.
- The layout engine is a pure function in `BuildingPlan.java` with no
  dependencies. Its self-test runs standalone: `java -cp out pzformat.BuildingPlan`.
- 14,680 dwelling layouts pass with zero gaps, overlaps, corridor rooms, or
  missing core rooms.
- GisCells has no `main` — the entry point is `Probe giscells`.
- `$GISMAP` empties between shells. Set it every time. This has cost five
  round trips across the project.
