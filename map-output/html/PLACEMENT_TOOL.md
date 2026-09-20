# GIS Map Placement Tool — End-to-End Guide

This document covers the complete workflow: from a freshly generated GIS mod to
a re-generated mod placed at the correct cell coordinates inside the vanilla
Muldraugh world.

---

## Prerequisites

The following must be true before starting:

- The GIS pipeline has run at least once and produced a mod.
  The mod lives at `~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport/`.
- `pzmap2dzi` is installed at `~/Documents/PZMapCreation/pzmap2dzi/` with its
  virtualenv active.
- The vanilla base map has been rendered at least once (one-time setup — see
  below).
- `server.py` is not already running on port 8880.

---

## Step 1 — Set environment variables

Every new terminal session needs these. Add them to your fish config or paste
them at the start of each session.

```fish
cd ~/Documents/PZMapCreation
set PZ ~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid
set GISMAP ~/Zomboid/mods/PZGisImport/common/media/maps/PZGisImport
set MAPS "$PZ/media/maps/Muldraugh, KY"
```

---

## Step 2 — Generate the GIS mod (if not already done)

```fish
cd ~/Documents/PZMapCreation
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport 2048 200 200
```

The last two numbers (`200 200`) are the initial cell origin — a placeholder.
You will replace them with the real coordinates after using the placement tool.

The generator wipes the mod map directory before writing, so re-running at a
different origin does not leave stale cells from the previous run.

---

## Step 3 — Render the vanilla base map (one-time setup)

Only needed once. Skip this if `map-output/html/map_data/base_top/layer0.dzi`
already exists.

```fish
cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
python main.py render base_top
```

This renders all of Muldraugh into a deep-zoom image. It takes several minutes.
The output goes into `map-output/html/map_data/base_top/`.

---

## Step 4 — Render the GIS mod overlay

Run this after every generation — it updates the yellow overlay the placement
tool shows on top of the vanilla map.

```fish
cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/mod_maps/PZGisImport
python main.py render base_top PZGisImport
echo "exit: $status"
```

**The `rm -rf` is required.** pzmap2dzi silently skips rendering if the output
directory already exists (you will see `stale tiles: 0` and a 0.17 s run if
you forget). Always delete first.

The output goes into `map-output/html/map_data/mod_maps/PZGisImport/`.

---

## Step 5 — Start the server

```fish
cd ~/Documents/PZMapCreation/map-output/html
python server.py
```

The server runs on port 8880. Leave this terminal open while using the
placement tool. Stop it with Ctrl+C when done.

---

## Step 6 — Open the placement tool

Navigate to:

```
http://localhost:8880/placement.html
```

The page loads two layers:

- **Vanilla world** — the full Muldraugh map, pannable and zoomable with mouse
  wheel and drag.
- **GIS overlay** — your generated map as a yellow-bordered draggable rectangle
  showing actual roads and buildings in carto-zed colours.

If the GIS overlay shows "GIS map not rendered yet", Step 4 was not completed
or the server cannot find the rendered files.

---

## Step 7 — Align the GIS map

1. **Zoom** the vanilla map with the mouse wheel until you can see roads clearly.
2. **Drag the yellow GIS overlay** to position your map over the desired location.
   - The overlay snaps to cell boundaries (256 tiles). This is intentional —
     the generator takes cell coordinates only.
   - The current cell coordinates update live in the toolbar:
     `Cell: X, Y  |  Tile: TX, TY`
3. **Use the opacity slider** to blend the overlay with the vanilla map for
   easier road alignment.
4. **Toggle the overlay** with "Hide GIS / Show GIS" to compare the two layers
   without the overlay obscuring the vanilla roads.
5. Align GIS roads to vanilla roads as closely as possible. Exact alignment is
   limited by cell snapping — sub-cell precision is not implemented.

---

## Step 8 — Apply and get the command

Click **✓ Apply — Get Coordinates**.

A panel appears showing:
- The cell coordinates: `Cell X: N, Cell Y: M`
- The world tile coordinates: `Tile TX, TY`
- The complete Java generation command with the chosen coordinates filled in.

Click **Copy Command** to copy it to the clipboard, then close the panel.

---

## Step 9 — Re-generate at the chosen coordinates

Paste and run the copied command in your project terminal. Example:

```fish
cd ~/Documents/PZMapCreation
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    "$PZ/media" ~/Zomboid/mods PZGisImport 2048 42 53
```

Replace `42 53` with whatever coordinates the placement tool gave you.

The mod is regenerated at the new origin. The old cells are wiped first.

---

## Step 10 — Re-render the overlay and verify

After regenerating, re-render the GIS overlay (Step 4) to update the
placement tool with the new cell positions. Check that the overlay still aligns
— the overlay position reflects the new origin automatically via `map_info.json`.

If the overlay position is wrong after re-rendering, the `map_info.json` was
not refreshed. Confirm the `rm -rf` in Step 4 ran successfully.

---

## Step 11 — Test in game

**Always start a NEW GAME** — not a resumed save. The engine caches map data
from the previous session; a new game forces a fresh load.

Spawn at your mod's location and verify roads, buildings, and terrain match
the expected layout.

---

## Known limitations

**Cell snap only.** The generator takes cell coordinates (`cellX`, `cellY`),
not tile coordinates. The overlay snaps to 256-tile cell boundaries. If your
GIS roads don't line up perfectly with vanilla roads, the only fix is to
regenerate at a different cell or accept the offset. Sub-cell (`originTileX/Y`)
precision is not yet implemented.

**Re-render after every generation.** The overlay is derived from the rendered
DZI. If you regenerate the mod without re-rendering, the overlay shows the
previous run's content at the previous origin.

**Fragile at non-default coordinates.** When generated at a cell far from
`200,200`, pzmap2dzi produces a DZI whose origin is world tile `0,0` because
`x0` is large and negative. The placement page compensates via
`gisImgOffsetX/Y` derived from `map_info.json`. This works correctly as long
as you re-render after every generation.

---

## Quick-reference checklist

```
[ ] Set PZ, GISMAP, MAPS variables
[ ] java ... Probe giscells ... 200 200   (initial generation)
[ ] rm -rf map_data/mod_maps/PZGisImport
[ ] python main.py render base_top PZGisImport
[ ] python server.py
[ ] Open http://localhost:8880/placement.html
[ ] Drag overlay to align, note Cell X/Y in toolbar
[ ] Click Apply — Copy Command
[ ] java ... Probe giscells ... <cellX> <cellY>   (regenerate at real coords)
[ ] rm -rf map_data/mod_maps/PZGisImport
[ ] python main.py render base_top PZGisImport     (re-render to confirm)
[ ] New game in PZ — verify
```
