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
    ~/Zomboid/mods PZGisImport 0 200 200 2>&1 | head -15
```

**Trailing numbers:** `2048 200 200` = world origin X, Y, Z.  
- Z is always `200` (PZ map grid offset, not elevation).  
- `2048` shifts the map away from vanilla Muldraugh so cells don't collide.  
- Change X/Y if you want the map at a different grid position.

---

## Step 4 — Render the top-down map (~2 seconds, safe to re-run)

```fish
cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/mod_maps/PZGisImport
time python main.py render base_top PZGisImport
```

---

## Step 5 — View the map

```fish
cd ~/Documents/PZMapCreation/map-output/html
python server.py
```

Open: `http://localhost:8880/pzmap.html?map_type=top`

---

## Step 6 — Test in-game

1. Launch Project Zomboid.
2. Enable the **PZGisImport** mod.
3. Start a **new game** — never resume a save.
4. Spawn near the world origin coordinates used in Step 3.

---

## Notes

- **Never re-render the base layer** — it takes ~167 minutes. Only `base_top` needs re-running after each generation.
- **Cell size is 256 tiles.** Scan scripts divide by 256.
- `grep` is aliased in fish — always use `command grep -e` per pattern.
