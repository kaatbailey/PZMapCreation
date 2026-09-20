THIS IS RENDERING THE MAIN VANILLA MAP.

rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/base_top

cd ~/Documents/PZMapCreation/map-output/html
python server.py


cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
python main.py render base_top

http://localhost:8880/pzmap.html?map_type=top


RENDER THE LITTLE MAP TOO

cd ~/Documents/PZMapCreation/pzmap2dzi
source .venv/bin/activate.fish
rm -rf ~/Documents/PZMapCreation/map-output/html/map_data/mod_maps/PZGisImport
python main.py render base_top PZGisImport
echo "exit=$status"


Cell X: 42, Cell Y: 53 (world tile 10752, 13568)
