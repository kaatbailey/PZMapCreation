# pzorch — PZMapCreation orchestrator

One page that drives the whole GIS pipeline, so the generate / place / rebuild
loop stops being a hand-typed command with a coordinate pasted back in.

It does not replace anything. It runs **the same commands** as
`make_new_gis_map.md`, in the same order, with the paths filled in — and
streams their output to the browser.

---

## Install

```fish
cd ~/Documents/PZMapCreation
mkdir -p pzorch/static
```

Drop `config.py`, `projects.py`, `runner.py`, `pipeline.py`, `server.py`,
`__init__.py` into `pzorch/`, and `index.html` into `pzorch/static/`.

FastAPI and uvicorn are already in the pzgis venv, so reuse it rather than
installing anything globally:

```fish
source ~/pzgis/.venv/bin/activate.fish
python3 -c "import fastapi, uvicorn; print('ok')"
```

If that fails:

```fish
python3 -m pip install fastapi uvicorn
```

---

## Patched viewers

`placement.html` and `modmap.html` both hardcode `PZGisImport` in their DZI
paths, so a mod under any other name shows the wrong overlay — or none. The
patched copies read a `?mod=` parameter and fall back to `PZGisImport`, so they
behave exactly as before when opened by hand.

They also gain one button each, shown only when the orchestrator opened them:

- **placement.html → "Send to Orchestrator"** — posts the chosen origin cell
  straight to the project. No more copying a command.
- **modmap.html → "Save to project"** — stores your picked spawn points so they
  survive the next rebuild.

```fish
cd ~/Documents/PZMapCreation/map-output/html
cp placement.html placement.html.bak
cp modmap.html    modmap.html.bak
# copy the two patched files in over the originals
```

---

## Run

Two servers. The viewer one you are already running; the orchestrator is new.

```fish
# terminal 1 — the map viewer (unchanged)
cd ~/Documents/PZMapCreation/map-output/html
python server.py

# terminal 2 — the orchestrator
cd ~/Documents/PZMapCreation
source ~/pzgis/.venv/bin/activate.fish
python3 -m uvicorn pzorch.server:app --host 127.0.0.1 --port 8899 --reload
```

Open <http://127.0.0.1:8899/>

The **Environment** panel at the bottom right is the first thing to read. Every
path it resolved, and whether it found it. If something is red, fix it with an
environment variable rather than editing the code:

```fish
set -x PZMC_PZ ~/some/other/ProjectZomboid/projectzomboid
python3 -m uvicorn pzorch.server:app --port 8899 --reload
```

---

## The loop

| # | Step | What it runs |
|---|---|---|
| 0 | Compile | `javac -encoding UTF-8 -d out` over every `.java` |
| 1 | Area | stores your GeoJSON in the project |
| 2 | Fetch GIS | `fetch_gis.py <area> <gis dir>` under the pzgis venv |
| 3 | Generate | `Probe giscells … <mod> 0 200 200` |
| 4 | Render | registers the mod in pzmap2dzi's config, then `main.py -c <conf> render base_top` |
| 5 | Place | opens placement.html; the origin comes back by itself |
| 6 | Build | the same generator call at the real origin, **then renders automatically** |
| 7 | Spawn points | opens modmap.html; pins are saved to the project |

Steps 3 and 6 are the same command with a different origin. That is the whole
reason placement sits between them.

Only one job runs at a time — a second request gets a 409 rather than two JVMs
fighting over the same mod folder. Close the tab and the job keeps running; the
page reattaches to it on reload.

---

## Things it protects you from

**`maxTiles` is always `0`.** There is no field for it anywhere in the UI. A
hardcoded tile count silently clips the map, and that cost about a week once.

**A successful exit that generated nothing.** `GisCells.run()` prints
`incomplete palette; cannot generate cells` and then *returns normally* — exit
code 0, no cells on disk. The orchestrator marks that job **failed**, leaves the
project state where it was, and says why. A second, independent guard catches
`cells written: 0` and a missing map directory, so a silent no-op cannot be
mistaken for a build. Both guards were negative-tested: back either one out and
the other still catches it, with a different message.

**Your spawn points.** Generating wipes the map directory and writes fresh auto
spawns. Anything you saved through modmap.html is copied back over the generated
file after every build. Nothing is captured automatically — auto-capturing the
generator's own spawns would pin stale ones from the provisional origin forever.
Use **Save to project** in modmap.html, or `POST …/spawnpoints/capture` if you
want the current auto set kept deliberately.

**The base render.** The orchestrator only ever runs `render base_top`. The
167-minute base render has no button.

**A render of the wrong map.** pzmap2dzi's CLI is
`main.py render <job> [<job>…]` where each argument is a *render job*
(`base`, `base_top`, `rooms`, …) — **never a map name**. The maps come from
`conf.yaml`: `base_map` plus the `mod_maps` list. So
`main.py render base_top PZGisImport` means "run job `base_top`, then run job
`PZGisImport`", and the second one prints

```
unspported render cmd: PZGisImport
```

…and exits 0. It worked only because `PZGisImport` was already in `mod_maps`.
Point it at any other mod and it silently renders the wrong map.

The orchestrator now registers each project's mod first:

- `conf/mod/pzorch_maps.txt` — the map entry, with an absolute `map_path`
  (the stock B42 template assumes a Workshop mod under `{mod_root}/{steam_id}`,
  which yours are not). This file is entirely ours; `conf.yaml` already globs
  `conf/mod/`.
- `conf/conf.yaml` — one line added to `mod_maps:`. Patched as **text**, not
  through a YAML round-trip, because that file carries ~200 lines of comments
  that a re-serialise would destroy. Idempotent, and it takes a
  `.pzorch-bak` copy the first time it touches the file.

Then it runs `main.py -c <conf> render base_top` with no map name, and fails the
job unless it sees `render [base_top] for map [<your mod>] done` **and** finds
tiles on disk. Another mod being misconfigured in the shared `mod_maps` list is
reported as a warning, not a failure.

**Deletes.** `rmtree` only ever fires on a path whose parent is
`~/Zomboid/mods` or `map_data/mod_maps`, so a mangled mod name cannot point it
somewhere interesting.

---

## The C++ port seam

When PZMapMaker replaces the Java generator, one environment variable changes
and nothing else does:

```fish
set -x PZMC_GENERATOR_KIND native
set -x PZMC_GENERATOR ~/Documents/PZMapMaker/build/pzmapmaker
```

The orchestrator then invokes `<binary> giscells <args…>` instead of
`java -cp out pzformat.Probe giscells <args…>`. Same argument order, same
`maxTiles=0`, same everything downstream. The API, the UI and the viewers do not
know or care which one is running — `/api/doctor` just reports `native`.

That is also why this is a separate process driving a CLI rather than an
in-process integration: an in-process binding would have to be rewritten for the
port, and whatever front end PZMapMaker settles on can drive this same HTTP API
or ignore it entirely.

---

## API

Thin and stack-agnostic, so a future front end in any language can drive it.

```
GET    /api/doctor                              resolved paths + what is missing
GET    /api/defaults                            default mod name, provisional origin
GET    /api/countries                           country -> us | osm

GET    /api/projects                            newest first
POST   /api/projects                            {name, mod_name, country}
GET    /api/projects/{id}
DELETE /api/projects/{id}?remove_mod=false
PUT    /api/projects/{id}/area                  {country, geojson}
PUT    /api/projects/{id}/origin                {origin_x, origin_y}
PUT    /api/projects/{id}/mod-name              {mod_name}

GET    /api/projects/{id}/spawnpoints
PUT    /api/projects/{id}/spawnpoints           {lua}
POST   /api/projects/{id}/spawnpoints/capture

POST   /api/projects/{id}/compile
POST   /api/projects/{id}/fetch
POST   /api/projects/{id}/generate              {origin_x?, origin_y?, final?}
POST   /api/projects/{id}/render

GET    /api/jobs/current
GET    /api/jobs?project_id=&limit=
GET    /api/jobs/{job}
GET    /api/jobs/{job}/log                      full log file
GET    /api/jobs/{job}/stream                   SSE, one event per line
POST   /api/jobs/{job}/cancel
```

CORS is wide open because both servers are loopback-only and the viewers on
`:8880` have to post back to `:8899`.

---

## Workspace

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

Every job's full output is on disk, so a failure from three builds ago is still
readable.

---

## Environment variables

| Variable | Default |
|---|---|
| `PZMC_ROOT` | the parent of `pzorch/` |
| `PZMC_PZGIS` | `~/pzgis` |
| `PZMC_PZMAP2DZI` | `<root>/pzmap2dzi` |
| `PZMC_PZMAP2DZI_CONF` | `<pzmap2dzi>/conf/conf.yaml` |
| `PZMC_RENDER_CMD` | `base_top` |
| `PZMC_MAP_HTML` | `<root>/map-output/html` |
| `PZMC_WORKSPACE` | `<root>/workspace` |
| `PZMC_PZ` | `~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid` |
| `PZMC_MODS` | `~/Zomboid/mods` |
| `PZMC_VIEWER` | `http://localhost:8880` |
| `PZMC_HOST` / `PZMC_PORT` | `127.0.0.1` / `8899` |
| `PZMC_DEFAULT_MOD` | `PZGisImport` |
| `PZMC_JAVA_HEAP` | `4g` |
| `PZMC_GENERATOR_KIND` | `java` (or `native`) |
| `PZMC_GENERATOR` | — (required when kind is `native`) |

`pzgis` defaults to `~/pzgis` because that is where `make_new_gis_map.md` step 1
activates its venv and where the layers land. It is a separate checkout from
PZMapCreation, and it has its own virtualenv — the orchestrator runs
`~/pzgis/.venv/bin/python` directly, which is equivalent to activating it.

---

## Not done

- **Edit map** is not here. This is the create-from-GIS path only.
- The tile atlas, the paint surface and lotpack write-back are the weeks of
  work; none of that is started.
- `compile` shells out to `javac`, not Gradle, because that is what STATE.md
  §0 uses. `build.gradle.kts` sets a Java 21 toolchain, so `javac` must be 21+.
