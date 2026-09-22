# Make a New GIS Map

Complete workflow from raw data to in-game playable mod.

---

## Step 1 — Start the pzgis frontend

```fish
cd ~/pzgis
source .venv/bin/activate.fish
python3 frontend.py
```

Uvicorn will start and listen on port 8000. **Leave this terminal running.**

Open your browser and go to:

```
http://localhost:8000
```

Load your source JSON, choose the geographic location, and click **Process**.  
This produces the three GeoJSON files the pipeline needs:
- `~/pzgis/buildings.geojson`
- `~/pzgis/roads.geojson`
- `~/pzgis/area.geojson`

---

## Step 2 — Compile

```fish
cd ~/Documents/PZMapCreation
rm -rf out && mkdir out
javac -encoding UTF-8 -d out (find src/main/java -name '*.java')
```

---

## Step 3 — Generate the mod

```fish
java -cp out pzformat.Probe giscells \
    ~/pzgis/buildings.geojson ~/pzgis/roads.geojson ~/pzgis/area.geojson \
    ~/.local/share/Steam/steamapps/common/ProjectZomboid/projectzomboid/media \
    ~/Zomboid/mods PZGisImport 0 200 200
```

**Trailing numbers:** `0 200 200` = maxTiles, world origin X, world origin Y.
- `0` = auto-size from the area boundary (rounds up to next cell boundary, capped at vanilla world max 19968×16128 tiles). Never change this unless you deliberately want to limit map size.
- `200 200` = world origin cell X, Y. Use `placement.html` to find the right coordinates before regenerating. Change these when placing the map at a specific position on the vanilla world.

---

## Step 4 — Check the render output (optional sanity check)

```fish
cd ~/Documents/PZMapCreation/map-output/html
python server.py
```

Open: `http://localhost:8880/modmap.html` — shows your mod map only.  
Open: `http://localhost:8880/placement.html` — shows your mod overlaid on the vanilla map for placement.

---

## Step 5 — Render the top-down map (~2 seconds, safe to re-run)

```fish
cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/mod_maps/PZGisImport
time python main.py render base_top PZGisImport
```

---

## Step 6 — Test in-game

1. Launch Project Zomboid.
2. Enable the **PZGisImport** mod.
3. Start a **new game** — never resume a save.
4. Pick **PZGisImport** from the location list at character creation.

---

## Notes

- **Never re-render the base layer** — it takes ~167 minutes. Only `base_top PZGisImport` needs re-running after each generation.
- **Cell size is 256 tiles.** Scan scripts divide by 256.
- `grep` is aliased in fish — always use `command grep -e` per pattern.
- The pipeline auto-sizes the map from your area boundary. A large area (e.g. 8.7km × 8.7km) generates ~1200 cells and takes several minutes to write.
