# pzorch — Orchestrator Runbook

Drives the GIS → generate → place → build pipeline. Lives at
`~/Documents/PZMapCreation/pzorch/`.

Does not replace anything. Runs the same commands as `make_new_gis_map.md`,
in the same order, with the paths filled in — and streams their output to
the browser.

---

## Starting the servers

Two terminals, every time.

### Terminal 1 — map viewer

```fish
cd ~/Documents/PZMapCreation/map-output/html
~/Documents/PZMapCreation/pzmap2dzi/.venv/bin/python server.py
```

### Terminal 2 — orchestrator

```fish
cd ~/Documents/PZMapCreation
~/pzgis/.venv/bin/python -m uvicorn pzorch.server:app --host 127.0.0.1 --port 8899 --reload
```

Open **http://127.0.0.1:8899/** — that is the orchestrator UI.

| Port | What |
|---|---|
| `:8880` | Map viewer (placement.html, modmap.html) |
| `:8899` | Orchestrator UI + REST API |

The **Environment** panel at the bottom right shows every resolved path.
Red means something is missing — fix it with an environment variable, never
by editing the code.

Uvicorn runs with `--reload` so most edits to `pzorch/` restart it
automatically. If something seems stale, Ctrl-C terminal 2 and re-run.

---

## Creating a project

Hit **New project**. Fields:

- **Name** — human label for the project
- **Mod name** — letters/digits/underscore, max 64 chars.
  Becomes the folder under `~/Zomboid/mods/`.
- **Country** — routes the GIS fetch. US uses government sources;
  everything else uses OpenStreetMap.

---

## Paste your area GeoJSON

Go to [geojson.io](https://geojson.io), draw your polygon, copy the JSON,
paste it into the Area field. Hit save. Project advances to **AREA_SET**.

---

## Pipeline steps

Steps run in order. Steps 3 and 6 are the same generator call — the only
difference is the origin, which is why placement sits between them.

| # | Step | What it runs |
|---|---|---|
| 0 | Compile | `javac` over every `.java` in `src/main/java/` → `out/`. Run once; re-run after Java edits. |
| 1 | Set area | Stores your GeoJSON in the project. No command runs. |
| 2 | Fetch GIS | `fetch_gis.py <area> <gis dir>` under the pzgis venv. Downloads buildings, roads, water. |
| 3 | Generate (provisional) | `Probe giscells … <mod> 0 200 200` at origin 0,0. Produces tiles for placement. |
| 4 | Render tiles | Registers mod in pzmap2dzi config, then `main.py -c <conf> render base_top`. Opens placement.html when done. |
| 5 | Placement | placement.html opens. Click your origin on the vanilla map. Coordinates post back automatically — no copy-paste. |
| 6 | Build (final) | Same generator call at the real origin, then renders automatically. Spawn point restore runs here too. |
| 7 | Spawn points | modmap.html opens. Pick pins and hit **Save to project**. Stored in `workspace/<id>/spawnpoints.lua`. |

Only one job runs at a time — a second request gets a 409. Close the tab
and the job keeps running; the page reattaches to it on reload.

---

## Placement

After Render tiles completes, the orchestrator opens `placement.html` with
a `?mod=` parameter pointing at your mod's tiles. You'll see your generated
map overlaid on the vanilla PZ world.

Click the spot on the vanilla map where the top-left corner of your map
should anchor. The patched viewer adds a **Send to Orchestrator** button
that posts the origin cell coordinates straight back to the project — no
terminal, no copy-paste. The project advances to **PLACED** and Build
becomes available.

> Coordinates are cell coordinates (0–300), not tile coordinates.
> placement.html reads them off the map grid — you just click.

---

## Spawn points

Every Generate wipes the mod's map directory and writes fresh auto-spawns.
The orchestrator preserves your saved spawn points and copies them back
after every build.

**Nothing is captured automatically** — auto-capturing the generator's
spawns would pin stale ones from the provisional origin forever. Use
**Save to project** in modmap.html, or hit **Capture current** in the
orchestrator's spawn panel if you want the auto set kept deliberately.

Saved spawn points survive new builds, mod renames, and orchestrator
restarts. They live at `workspace/<project-id>/spawnpoints.lua`.

---

## Environment variables

Set these in fish before starting terminal 2 to override any default path.

```fish
# Example: non-default pzgis location
set -x PZMC_PZGIS ~/some/other/pzgis
set -x PZMC_PZ ~/some/other/ProjectZomboid/projectzomboid
```

| Variable | Default | What it controls |
|---|---|---|
| `PZMC_ROOT` | parent of `pzorch/` | PZMapCreation repo root |
| `PZMC_PZGIS` | `~/pzgis` | pzgis checkout (must have `.venv/`) |
| `PZMC_PZMAP2DZI` | `<root>/pzmap2dzi` | pzmap2dzi checkout |
| `PZMC_PZMAP2DZI_CONF` | `<pzmap2dzi>/conf/conf.yaml` | pzmap2dzi config file |
| `PZMC_RENDER_CMD` | `base_top` | Render job name (never change this) |
| `PZMC_MAP_HTML` | `<root>/map-output/html` | Where placement/modmap viewers live |
| `PZMC_WORKSPACE` | `<root>/workspace` | Project storage (auto-created) |
| `PZMC_PZ` | `~/.local/share/Steam/…/projectzomboid` | PZ install dir (needs `media/`) |
| `PZMC_MODS` | `~/Zomboid/mods` | Mod output directory |
| `PZMC_VIEWER` | `http://localhost:8880` | Base URL for map viewer |
| `PZMC_DEFAULT_MOD` | `PZGisImport` | Pre-filled mod name on new projects |
| `PZMC_JAVA_HEAP` | `4g` | `-Xmx` passed to java |

---

## Switching to the C++ generator

When PZMapMaker replaces the Java generator, set two variables. The API,
the UI, and the viewers are unaffected.

```fish
set -x PZMC_GENERATOR_KIND native
set -x PZMC_GENERATOR ~/Documents/PZMapMaker/build/pzmapmaker
```

The orchestrator then runs `<binary> giscells <args…>` instead of
`java -cp out pzformat.Probe giscells <args…>`. Same argument order.
`/api/doctor` (Environment panel) reports **native**.

---

## What it protects you from

**`maxTiles` is always `0`.** No field for it in the UI. Hard-coded in the
pipeline, cannot be overridden. A hardcoded tile count silently clips the
map.

**Silent generate failure.** `GisCells.run()` prints
`incomplete palette; cannot generate cells` and exits 0. The orchestrator
marks that job **failed** and says why. A second guard catches
`cells written: 0` and a missing map directory. Both guards are
independently load-bearing.

**Wrong map rendered.** pzmap2dzi's CLI takes render *job names*, not map
names. Passing your mod name as an argument silently renders whatever was
already in the config. The orchestrator registers your mod in
`conf/mod/pzorch_maps.txt` and `conf/conf.yaml` first, then runs
`main.py -c <conf> render base_top` with no map argument, and fails the
job unless it sees your mod name in the output and finds tiles on disk.

**Spawn point loss.** Generate wipes the map directory. Saved spawn points
are restored from `workspace/<id>/spawnpoints.lua` after every build.

**The base render.** The 167-minute base render has no button. The
orchestrator only ever runs `render base_top`.

---

## Workspace layout

```
workspace/<project-id>/
    project.json          name, mod name, country, origin, state, last stats
    gis/
        area.geojson      what you pasted
        buildings.geojson  from pzgis
        roads.geojson      from pzgis
    spawnpoints.lua       your preserved copy
    logs/<stamp>-<step>.log
```

Every job's full output is on disk, so a failure from three builds ago is
still readable.
