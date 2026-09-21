# Furniture Set Analysis — office_sets2.txt
# Ready for FurnitureSet.java implementation

---

## SMALL OFFICE SETS

---

### SET I — Desk row with whiteboard + chairs (small_office_chair_desk_table)
**Origin**: 12754,1516 cell 49_5 local 210,236
**Description**: A row of desks facing W along one axis, with a 3-tile whiteboard on the west wall,
chairs on the north side, and a 2-tile table (high_02) at the south end. Classic shared desk row.
**Bounding box**: ~6 wide × 6 tall

Key tiles (relative to scan origin 0,0):
- (-2,1..3)  location_business_office_generic_01_50/51/52  Whiteboard (3-tile vertical, FacingE = on W wall)
- ( 0,-1)    furniture_tables_high_01_29   Desk FacingW
- ( 0, 0)    furniture_tables_high_01_28   Desk FacingW  ← origin
- ( 0, 1)    furniture_tables_high_01_29   Desk FacingW
- ( 0, 2)    furniture_tables_high_01_28   Desk FacingW
- ( 0, 3)    furniture_tables_high_01_29   Desk FacingW
- ( 1,-1)    furniture_tables_high_01_30   Desk FacingN  (side/corner)
- ( 2,-1)    furniture_tables_high_01_31   Desk FacingN
- (-1,-1)    furniture_seating_indoor_01_54  Chair FacingS  (behind desk north end)
- (-1, 1)    furniture_seating_indoor_01_53  Chair FacingE
- ( 2,-2)    furniture_seating_indoor_01_54  Chair FacingS
- ( 3,-2)    furniture_seating_indoor_01_54  Chair FacingS
- ( 3, 2)    furniture_tables_high_02_23  Table FacingS  (accent table at south)
- ( 3, 3)    furniture_tables_high_02_22  Table FacingS

**Notes**: Whiteboard is wall-mounted (MoveType=WallObject), attach to W wall.
Desk tiles 28/29 alternate in a column; 30/31 are the corner/return.

---

### SET J — Single posh office (small_office_full_setup)
**Origin**: 12736,1524 cell 49_5 local 192,244
**Description**: A private office with couch seating area, L-desk with computer, shelves, mirror,
a potted plant, and a lazy chair. High-end single occupant office.
**Bounding box**: ~7 wide × 7 tall  (extends to -3 which is restaurant bar floor — ignore those)

Key tiles:
- ( 2, 2)    location_business_office_generic_01_171  Desk FacingE  (Office 3 group)
- ( 2, 3)    location_business_office_generic_01_170  Desk FacingE
- ( 3, 2)    location_business_office_generic_01_172  Desk FacingE  + appliances_com_01_72 Computer FacingS on top
- ( 3, 3)    furniture_seating_indoor_01_39  Chair FacingN  (Fancy White)
- ( 3,-3)    furniture_seating_indoor_02_48  Couch FacingS  (Brown Lazy)
- ( 2,-2)    furniture_seating_indoor_02_45  Chair FacingE  (Brown Lazy)
- ( 3, 0)    furniture_tables_low_01_3   Low Table FacingS  (Fancy — coffee table)
- (-1, 2)    furniture_shelving_01_41    Shelves FacingE  (Oakwood)
- (-1, 3)    furniture_tables_high_01_5  Mirror FacingE  (Long)
- ( 2,-3)    vegetation_indoor_01_7      Chinese Evergreen (plant)
- (-1, 0)    lighting_indoor_01_5        Light Switch

**Notes**: Computer (appliances_com_01_72) sits ON the desk tile at (3,2) — IsTableTop.
The couch+chair+low table is a reception/sitting zone; the desk+computer+chair is the work zone.
Ignore bar floor tiles at (-3..-2, all y) — those are the adjacent building.

---

### SET K — Filing cabinet + vintage lamp desk (small_office_filing_lamp_rug_clock)
**Origin**: 12635,1453 cell 49_5 local 91,173 (note: cell 42_4 in header was wrong — script uses 256 so 12635/256=49, 1453/256=5 → 49_5)
**Description**: Compact single-person office: filing cabinets on one wall, desk with vintage lamp,
chair, wall clock, plant, bin.
**Bounding box**: ~7 wide × 5 tall

Key tiles:
- (-3,-2)    location_community_school_01_32  Clock FacingS  (wall-mounted)
- ( 3,-2)    location_community_school_01_32  Clock FacingS  (second clock — treat as accent)
- (-2,-2)    trashcontainers_01_20            Round Bin
- (-1,-2)    location_business_office_generic_01_33  Cabinet FacingE  (White File)
- (-1,-1)    location_business_office_generic_01_33  Cabinet FacingE  (2-tile filing cabinet)
- (-1, 0)    vegetation_indoor_01_7           Chinese Evergreen (plant)
- ( 1,-1)    furniture_seating_indoor_01_50   Chair FacingS  (Black Office)
- ( 1, 0)    location_business_office_generic_01_8   Desk FacingN
- ( 2, 0)    location_business_office_generic_01_10  Desk FacingN  + lighting_indoor_02_35 Lamp (Green Vintage) on top

**Notes**: _01_33 is a 2-tile filing cabinet (pair at dy=-2 and dy=-1). The lamp (lighting_indoor_02_35)
is IsTableTop — sits on desk tile at (2,0). Clock is WallObject on N wall.
_01_8 and _01_10 form the desk pair.

---

### SET L — Office artwork (small_office_artwork)
**Origin**: 12543,1415 cell 48_5 local 255,135
**Description**: A 2-tile painting for wall decoration. Minimal — accent only.
**Bounding box**: 2 wide × 1 tall

Key tiles:
- (-3, 0)    location_entertainment_gallery_01_24  Painting FacingS
- (-2, 0)    location_entertainment_gallery_01_25  Painting FacingS

**Notes**: _24/_25 form a 2-tile painting pair. FacingS = hangs on N wall.
Use as a wall accent in any office room, not a stamped set.

---

### SET M — Posh medium office lobby/breakroom area (medium_office_breakroom_posh)
**Origin**: 12309,1275 cell 48_4 local 21,251
**Description**: A lobby/lounge area with Victorian couches, glass coffee table, industrial fridges,
freezers, and a bar counter. This is the high-end "executive lounge" zone.
**Bounding box**: ~7 wide × 7 tall (scan boundary hit — large)

Key tiles (furniture only, ignoring stairs/railings/roof):
- ( 0,-3)    furniture_tables_low_01_8   Glass Coffee Table FacingS  (Fancy Low Glass)
- ( 1,-3)    furniture_tables_low_01_9   Glass Coffee Table FacingS  (pair)
- ( 2,-3)    furniture_seating_indoor_03_116  Couch FacingN  (Victorian C4)
- ( 3,-3)    furniture_seating_indoor_03_117  Couch FacingN  (Victorian pair tile)
- ( 0, 0)    appliances_refrigeration_01_41  Fridge FacingE  (White Industrial)
- ( 0, 1)    appliances_refrigeration_01_9   Fridge FacingE  (Steel)
- ( 2, 0)    appliances_refrigeration_01_48  Freezer FacingS  (Chest)
- ( 3, 0)    appliances_refrigeration_01_48  Freezer FacingS  (Chest, second unit)
- ( 0, 2..3) location_restaurant_bar_01_37   Bar FacingE  (Left Bar Wall, 2-tile)
- ( 2, 3)    location_restaurant_bar_01_25   Blue Bar Stool FacingS
- ( 3, 3)    furniture_storage_02_19         Cartbox

**Notes**: The couch pair _116/_117 is a 2-tile set (both FacingN). Glass table _8/_9 is a 2-tile pair.
The fridges/freezers cluster is the "executive kitchen" zone.
Ignore: stairs (fixtures_stairs_01), railings, AC unit — structural, not stamped.

---

## BREAKROOM SETS

---

### SET N — Breakroom folding chairs + tables + counter + clock (breakroom_chairs_tables_clock)
**Origin**: 12834,1688 cell 50_6 local 34,152
**Description**: Standard office breakroom: birchwood counter with sink and fridge along one wall,
round/rectangular tables with folding chairs, wall clock.
**Bounding box**: ~7 wide × 7 tall

Key tiles:
- (-3, 1)    fixtures_counters_01_41  Counter FacingN  (Birchwood corner)
- (-2,-3)    appliances_refrigeration_01_3  Fridge FacingW  (White)
- (-2,-2)    fixtures_counters_01_47  Counter FacingW  (Birchwood)
- (-2,-1)    fixtures_counters_01_47  Counter FacingW  + fixtures_sinks_01_10 Sink FacingW (Chrome)
- (-2, 0)    fixtures_counters_01_47  Counter FacingW
- (-2, 1)    fixtures_counters_01_40  Counter FacingN  (Birchwood Corner end cap)
- (-1,-1)    furniture_seating_indoor_01_61  Chair FacingE  (Folding) + location_community_school_01_33 Clock FacingE (wall)
- (-1, 0)    furniture_seating_indoor_01_61  Chair FacingE  (Folding)
- (-1, 1)    furniture_tables_high_01_49  Table FacingE  (Large Smooth)
- (-1, 2)    furniture_tables_high_01_48  Table FacingE
- (-1, 3)    furniture_tables_high_01_49  Table FacingE
- (-1,-2)    furniture_seating_indoor_01_60  Chair FacingS  (Folding)
- ( 0, 3)    furniture_seating_indoor_01_63  Chair FacingW  (Folding)
- ( 2, 3)    furniture_seating_indoor_01_60  Chair FacingS  (Folding)

**Notes**: Counter run is: fridge → counter → counter+sink → counter → corner-cap.
Folding chairs _60/_61/_63 are the folding set (S/E/W variants).
Table _48/_49 alternate in a column (3 tiles long).
Clock is wall-mounted, attach to whatever wall chair at (-1,-1) faces.

---

### SET O — Round tables + chalkboard + shelves + clock (breakroom_chairs_tables_2)
**Origin**: 12822,1689 cell 50_6 local 22,153
**Description**: Casual breakroom with oak round tables, blue plastic chairs, chalkboard on wall,
corner shelves, and a wall clock. More informal / classroom-adjacent feel.
**Bounding box**: ~6 wide × 7 tall

Key tiles:
- (-2,-3)    furniture_shelving_01_21  Shelves FacingE  (Middle) + location_community_school_01_33 Clock
- (-2,-2)    furniture_shelving_01_20  Shelves FacingE  (Corner A)
- (-2, 1)    location_community_school_01_18  Chalkboard FacingE  (Chalk)
- (-2, 2)    location_community_school_01_17  Chalkboard FacingE
- (-2, 3)    location_community_school_01_16  Chalkboard FacingE  (3-tile chalkboard)
- ( 0,-1)    furniture_tables_high_01_6   Oak Round Table  (no facing — round)
- ( 0, 0)    furniture_seating_indoor_02_15  Chair FacingN  (Blue Plastic)
- ( 0, 1)    furniture_seating_indoor_02_13  Chair FacingS  (Blue Plastic)
- ( 0, 2)    furniture_tables_high_01_6   Oak Round Table
- ( 1, 1)    furniture_seating_indoor_02_12  Chair FacingE
- ( 2,-1)    furniture_tables_high_01_6   Oak Round Table
- ( 2, 1)    furniture_tables_high_01_6   Oak Round Table
- ( 3,-1)    furniture_seating_indoor_02_14  Chair FacingW
- ( 3, 1)    furniture_seating_indoor_02_14  Chair FacingW
- (-1, 2)    furniture_seating_indoor_02_12  Chair FacingE

**Notes**: _01_6 is a single-tile round table — no pair needed. Blue plastic chair set:
_12 FacingE, _13 FacingS, _14 FacingW, _15 FacingN.
Chalkboard _16/17/18 is a 3-tile vertical (bottom to top).

---

### SET P — Vending machines + bench (breakroom_vending_machines)
**Origin**: 12872,1701 cell 50_6 local 72,165
**Description**: Vending machine alcove: large soda machine, snack machine, metal shelves,
wooden bench, bin.
**Bounding box**: ~7 wide × 5 tall

Key tiles:
- ( 0,-3)    furniture_shelving_01_25  Shelves FacingE  (Large Metal, top)
- ( 0,-2)    furniture_shelving_01_24  Shelves FacingE  (Large Metal, bottom)
- ( 2,-2)    furniture_shelving_01_26  Shelves FacingS  (Large Metal)
- ( 3,-2)    furniture_shelving_01_27  Shelves FacingS
- ( 1,-1)    location_shop_accessories_01_19  Machine FacingS  (Small Soda)
- ( 2,-1)    location_shop_accessories_01_17  Machine FacingS  (Large vending)
- ( 3,-1)    trashcontainers_01_17  Green Garbage Bin
- (-3,-1)    furniture_seating_indoor_03_67  Bench FacingN  (Wooden)

**Notes**: Shelving _25/_24 is a 2-tile vertical unit (FacingE). _26/_27 is a 2-tile unit FacingS.
Vending machines _17 (large) and _19 (small soda) are single-tile.
Use as a wall-aligned accent cluster, not a repeating stamp.

---

### SET Q — Rectangular tables + chairs (large breakroom) (breakroom_tables_chairs_3)
**Origin**: 12775,1631 cell 49_6 local 231,95
**Description**: Large breakroom table arrangement: 2-tile rectangular tables (high_02) with
simple white chairs, arranged in a grid. The core repeating unit for large breakrooms.
**Bounding box**: ~7 wide × 5 tall  (2 table groups of 2×2 tiles each, with chair row between)

Key tiles — TABLE UNIT (high_02, 2-tile pair column):
- furniture_tables_high_02_22  FacingS  (tile 0)
- furniture_tables_high_02_23  FacingS  (tile 1, above)
- furniture_tables_high_02_30  FacingS  (wide tile 0)
- furniture_tables_high_02_31  FacingS  (wide tile 1)

Layout as observed:
- (-3,-2)/(-3,-1)   _23/_22  Table pair (col A)
- (-2,-2)/(-2,-1)   _31/_30  Table pair (col B)  ← these form a 4-tile 2×2 table block
- (-1,-2)            Chair FacingS  (White Simple _57)
- (-3, 1)/(-3, 2)   _23/_22  Table pair (col A repeat)
- (-2, 1)/(-2, 2)   _31/_30  Table pair (col B repeat)
- (-1, 2)            Chair FacingW  (_58)
- ( 2,-1)/( 1,-1)   _31/_23  another group
- ( 2, 1)/( 2, 0)   _31/_30
- ( 2,-2)            Chair FacingS  (_57)
- ( 2, 1)            Chair FacingN  (_59)

**Repeating unit**: 2-wide × 2-tall table block (_22+_23 and _30+_31), 1-tile aisle, chairs on both ends.
Chairs: furniture_seating_indoor_03_57 FacingS, _58 FacingW, _59 FacingN.

---

### SET R — Counter + sink + fridge + coffee machine + water dispenser (breakroom_fridge_sink_counter_coffee)
**Origin**: 12766,1539 cell 49_6 local 222,3
**Description**: Breakroom kitchen wall: two sinks, counter, fridge, espresso machine, water dispenser.
The standard breakroom appliance run.
**Bounding box**: ~5 wide × 3 tall

Key tiles:
- (-3,-2)/(-2,-2)  fixtures_sinks_01_28  Sink FacingN  (Small Wide, 2 units)
- (-1,-1)          location_restaurant_seahorse_01_44  Counter FacingS  (Seahorse Coffee Corner)
- ( 0,-1)          location_restaurant_seahorse_01_45  Counter FacingS  + fixtures_sinks_01_9 Sink (Chrome)
- ( 1,-1)          location_restaurant_seahorse_01_45  Counter FacingS  + appliances_cooking_01_56 Espresso Machine
- (-1, 0)          appliances_refrigeration_01_1  Fridge FacingE  (White)
- (-1, 2)          location_business_office_generic_01_48  Dispenser FacingE  (Water)

**Notes**: The Seahorse Coffee counter (_44/_45) is the decorative coffee counter set.
_01_56 is the coffee/espresso machine (IsTableTop on counter).
_01_48 is the water cooler/dispenser. This is the standard breakroom appliance wall — use as
a perimeter run, not a standalone stamp. The sinks at (-3,-2)/(-2,-2) are separate from the
counter and appear to be in a different zone (bathroom-adjacent in this scan).
Ignore mall benches at (-2,-1..3) — adjacent building bleed.

---

### SET S — Large appliance bank (breakroom_large_appliances)
**Origin**: 12431,1374 cell 48_5 local 143,94
**Description**: Industrial appliance zone: chest freezers, mini fridges, full fridges, microwaves on
tables, soda fountain, industrial ovens, red ovens. For large cafeteria/restaurant kitchens.
**Bounding box**: ~7 wide × 7 tall

Key tiles:
- (-1,-3)/( 0,-3)   appliances_refrigeration_01_32  Fridge FacingS  (Red, 2 units)
- ( 1,-3)/( 2,-3)   appliances_refrigeration_01_25  Fridge FacingS  (Mini, 2 units)
- ( 3,-3)            appliances_cooking_01_21  Oven FacingS  (Industrial)
- ( 0,-1)            appliances_refrigeration_01_51  Freezer FacingW  (Chest)
- ( 1,-1)            appliances_refrigeration_01_49  Freezer FacingE  (Chest pair)
- ( 3, 0)/( 3, 1)   appliances_cooking_01_10  Oven FacingW  (Red, 2 units stacked)
- ( 3, 3)            appliances_cooking_01_6   Oven FacingW  (Grey)
- (-1, 1)/( 0, 1)   furniture_tables_high_01_16 + appliances_cooking_01_27  Microwave on table FacingN
- (-1, 3)/( 0, 3)   furniture_tables_high_01_16 + appliances_cooking_01_28  Microwave on table FacingS
- ( 0, 2)            furniture_tables_high_01_16 + location_shop_accessories_01_9  Soda Fountain on table

**Notes**: Microwaves (_27/_28) are IsTableTop — placed ON furniture_tables_high_01_16.
Soda fountain _01_9 is also IsTableTop. Chest freezer is a 2-tile pair (_49/_51).
This is a wall-run cluster, not a stamp.

---

### SET T — Entertainment / arcade (breakroom_entertainment)
**Origin**: 12382,1319 cell 48_5 local 94,39
**Description**: Breakroom entertainment zone: arcade machines, pinball machines. 
**Bounding box**: ~6 wide × 6 tall

Key tiles:
- (-2,-2)/(-2,-1)  recreational_01_17  Arcade Machine FacingE  (Dr. Oids, 2-tile vertical)
- (-2, 1)/(-2, 3)  recreational_01_26  Pinball Machine FacingE  (PAWS, top tile)
- (-1, 1)/(-1, 3)  recreational_01_27  Pinball Machine FacingE  (PAWS, bottom tile)
- ( 1,-1)/( 2,-1)  recreational_01_19  Arcade Machine FacingN  (Dr. Oids variant)
- ( 1, 0)/( 2, 0)  recreational_01_20  Arcade Machine FacingS  (Kaboom)

**Notes**: Arcade machine _17 is a 2-tile vertical pair (_17 at top, same index repeated = same tile both rows).
Pinball is a 2-tile vertical: _26 (top) + _27 (bottom), both FacingE.
_19 (FacingN) + _20 (FacingS) are back-to-back arcade units.
Use as accent cluster in large breakrooms/lounges.

---

### SET U — Heavy duty kitchen (kitchen_heavy_duty)
**Origin**: 12330,1257 cell 48_4 local 42,233
**Description**: Professional restaurant/cafeteria kitchen: steel counters, industrial sinks,
steel fridges, large modern ovens, espresso machine, industrial bake-o-matic oven.
**Bounding box**: ~7 wide × 7 tall (hits scan boundary — likely larger)

Key tiles (furniture/appliances only):
- (-2, 1)/(-2, 2)  appliances_refrigeration_01_9  Fridge FacingE  (Steel, 2 units)
- (-2, 3)           fixtures_counters_01_35 + fixtures_sinks_01_16  Counter+Sink FacingE (Steel/Dark Industrial)
- (-1,-1)           appliances_cooking_01_65  Industrial Oven FacingS  (Bake-O-Matic)
- ( 0,-1)           fixtures_counters_01_37  Counter FacingS  (Steel)
- ( 1,-1)           fixtures_counters_01_37 + appliances_cooking_01_61  Counter + Espresso Deluxe FacingS
- ( 2,-1)           appliances_cooking_01_42  Oven FacingS  (Large Modern)
- ( 3,-1)           appliances_cooking_01_43  Oven FacingS  (Large Modern, pair tile)
- ( 1, 1)/( 2, 1)/( 3, 1)  fixtures_counters_01_33  Counter FacingN  (Steel, 3-tile run)
- ( 1, 2)/( 2, 2)/( 3, 2)  fixtures_counters_01_37  Counter FacingS  (Steel, 3-tile run)
- (-3,-1)           trashcontainers_01_20  Round Bin

**Notes**: Large Modern Oven is a 2-tile pair (_42/_43, both FacingS).
Bake-O-Matic _65 is single-tile industrial oven. Steel counter _33 FacingN and _37 FacingS
form a back-to-back counter island. Fridge _9 (Steel) matches the one seen in breakroom_chairs_tables_clock.

---

## BATHROOM SETS

---

### SET V — Sink + mirror run (bathroom_sink_mirrors)
**Origin**: 12861,1715 cell 50_6 local 61,179
**Description**: Commercial bathroom sink vanity: repeated sink+mirror pairs along a wall.
4 units across (each unit = 1 sink below + 1 mirror above).
**Bounding box**: 4 wide × 2 tall

Repeating unit (dx = 0,1,2,3):
- (dx,-1)   location_hospitality_sunstarmotel_02_20  Counter FacingN  (Low Motel)
             + fixtures_sinks_01_7  Sink FacingN  (Industrial)
- (dx, 0)   location_hospitality_sunstarmotel_02_22  Mirror FacingS  (Large Wide)
             + fixtures_sinks_01_5  Counter FacingS  (Low Motel)  [note: _5 is labeled "Sink" but grouped "Large Wide"]

**Notes**: Each column has the sink base at y-1 (FacingN) and mirror at y+0 (FacingS, wall-mounted above).
_02_20 is the vanity counter/base. _02_22 is the mirror above. _01_7 and _01_5 are the actual sink fixtures
(both labeled Sink — _7 Industrial, _5 Large Wide). Stamp this unit 2-4 times along a N wall.
Also present: fixtures_bathroom_01_31 Shower FacingE at (-2,3) — separate accent.

---

### SET W — Open toilet run (bathroom_toilet_setup)
**Origin**: 12851,1713 cell 50_6 local 51,177
**Description**: Row of toilets (no stalls) with hanging wall sinks on both sides, hand dryers, bin.
Open commercial bathroom layout.
**Bounding box**: 6 wide × 6 tall

Key tiles:
- (-1,-1..3)  fixtures_bathroom_01_1  Toilet FacingE  (Fancy, 5 units in column — attached W wall)

  Wait — FacingE + attached = these toilets are on the WEST wall facing into the room (east).

- (-2,-1..2)  fixtures_sinks_01_31  Sink FacingW  (White Hanging, 4 units)
- ( 3,-1..2)  fixtures_sinks_01_31  Sink FacingW  (White Hanging, 4 units — opposite wall)
- (-2, 3)/( 3, 3)  trashcontainers_01_19 + fixtures_bathroom_01_15  Bin + Hand Dryer/Blower FacingW (Air)

**Notes**: _01_1 is attachedW toilet (fixtures_bathroom_01 index 1 = FacingE, attached to W wall).
_sinks_01_31 is a hanging wall sink (FacingW = mounted on E wall). 
fixtures_bathroom_01_15 is "Blower" (hand dryer), FacingW = on E wall.
The toilet column goes 5 deep — repeating stamp unit is 1 tile wide.

---

### SET X — Toilet stall run with sinks (bathroom_toilet_urinal)
**Origin**: 12766,1611 cell 49_6 local 222,75
**Description**: Row of toilet stalls with stall doors, hanging sinks, bin. Uses fixtures_bathroom_01
door/wall system (not the fixtures_bathroom_02 plastic stall kit).
**Bounding box**: ~5 wide × 6 tall

Key tiles — STALL UNIT (repeating at dx = -1, 0, 1, 2):
- (dx, 1)   fixtures_bathroom_01_4   Toilet FacingS  (Low) ← toilet inside stall
- (dx, 2)   fixtures_bathroom_01_65 + fixtures_bathroom_01_58  [DOOR pair] ← stall door

Sinks along south wall:
- ( 0, 3)/( 1, 3)/( 2, 3)  fixtures_sinks_01_30  Sink FacingN  (White Hanging)

Also:
- ( 3, 1)   fixtures_bathroom_01_8  Toilet FacingS  (Hanging — different group, may be urinal area)
- (-1, 3)   trashcontainers_01_18   Bin FacingS

**Notes**: _01_4 is "Low" toilet FacingS (attached to N wall of stall). _01_65 + _01_58 are the stall door pair.
_01_30 is hanging sink FacingN. This is the standard open-plan toilet stall row for commercial bathrooms.
Repeating unit: 1 tile wide, toilet at y+1, door at y+2, sink at y+3 (shared).

---

### SET Y — Full commercial bathroom (bathroom_stalls_urinals_storage_sink)
**Origin**: 12443,1381 cell 48_5 local 155,101
**Description**: Full commercial bathroom: steel counter+sink, metal shelving for storage,
toilet stalls (fixtures_bathroom_01 door system), urinal-type toilets on N wall.
**Bounding box**: ~7 wide × 7 tall

Key tiles:
- (-3, 0)    fixtures_counters_01_35 + fixtures_sinks_01_16  Counter+Sink FacingE  (Steel/Dark Industrial)
- (-3, 1)    fixtures_counters_01_35  Counter FacingE  (Steel)
- (-3, 2)    furniture_shelving_01_25  Shelves FacingE  (Large Metal, top)
- (-3, 3)    furniture_shelving_01_24  Shelves FacingE  (Large Metal, bottom)
- ( 0, 2)/( 0, 3)  fixtures_bathroom_01_5  Toilet FacingE  (Low, 2 units)
- ( 1,-3)/( 2,-3)/( 3,-3)  fixtures_bathroom_01_8  Toilet FacingS  (Hanging — urinals on N wall)
- ( 1, 2)/(1, 3)  fixtures_bathroom_01_64 + fixtures_bathroom_01_62  Stall door pair
- ( 3, 2)    fixtures_bathroom_01_59  [DOOR — single stall door variant]

**Notes**: _01_5 FacingE = toilet attached to W wall. _01_8 FacingS = toilet attached to N wall (urinal row).
_01_64 + _01_62 is the stall door pair (different from _65/_58 in SET X — both are valid door variants).
Shelving _24/_25 is the 2-tile metal shelving unit (also seen in vending SET P).
Counter+sink combo matches the heavy duty kitchen SET U (same tiles: _counters_01_35 + _sinks_01_16).

---

## SUMMARY: NEW TILE NAMES CONFIRMED

### Wall decor
- location_business_office_generic_01_50/51/52  Whiteboard (3-tile, FacingE = on W wall)
- location_entertainment_gallery_01_24/25       Painting pair (FacingS = on N wall)
- location_community_school_01_32               Wall Clock (FacingS)
- location_community_school_01_16/17/18         Chalkboard (3-tile vertical, FacingE)

### Seating
- furniture_seating_indoor_02_45  Lazy Chair FacingE  (Brown)
- furniture_seating_indoor_02_48  Couch FacingS  (Brown Lazy)
- furniture_seating_indoor_03_116/117  Victorian Couch pair FacingN
- furniture_seating_indoor_01_60/61/63  Folding Chair S/E/W
- furniture_seating_indoor_02_12/13/14/15  Blue Plastic Chair E/S/W/N
- furniture_seating_indoor_03_57/58/59  White Simple Chair S/W/N
- furniture_seating_indoor_03_67  Wooden Bench FacingN

### Desks / Tables
- location_business_office_generic_01_170/171/172  L-desk set (Office 3 group)
- location_business_office_generic_01_8/10  Desk pair FacingN
- location_business_office_generic_01_28/29  Desk pair FacingW  (30/31 corner return)
- furniture_tables_high_01_6   Oak Round Table (no facing)
- furniture_tables_high_01_48/49  Rectangular table pair FacingE  (Large Smooth)
- furniture_tables_high_02_22/23  Table pair FacingS  (Large Light)
- furniture_tables_high_02_30/31  Table pair FacingS  (wide variant)
- furniture_tables_low_01_3   Coffee Table FacingS  (Fancy)
- furniture_tables_low_01_8/9  Glass Coffee Table pair FacingS  (Fancy Low Glass)

### Filing / Storage
- location_business_office_generic_01_33  Filing Cabinet FacingE  (White File, 2-tile)
- furniture_shelving_01_20/21  Corner shelves FacingE
- furniture_shelving_01_24/25  Metal Shelves vertical pair FacingE  (Large Metal)
- furniture_shelving_01_26/27  Metal Shelves horizontal pair FacingS
- furniture_storage_02_19  Cartbox

### Appliances
- appliances_com_01_72  Desktop Computer FacingS  (IsTableTop)
- appliances_cooking_01_56  Espresso Machine  (IsTableTop, Coffee group)
- appliances_cooking_01_61  Deluxe Espresso FacingS  (IsTableTop)
- appliances_cooking_01_65  Industrial Bake-O-Matic Oven FacingS
- appliances_cooking_01_42/43  Large Modern Oven pair FacingS
- appliances_cooking_01_10  Red Oven FacingW
- appliances_cooking_01_6   Grey Oven FacingW
- appliances_cooking_01_21  Industrial Oven FacingS
- appliances_cooking_01_27/28  Microwave FacingN/S  (IsTableTop)
- appliances_refrigeration_01_1  Fridge FacingE  (White)
- appliances_refrigeration_01_3  Fridge FacingW  (White)
- appliances_refrigeration_01_9  Fridge FacingE  (Steel)
- appliances_refrigeration_01_25  Mini Fridge FacingS
- appliances_refrigeration_01_32  Red Fridge FacingS
- appliances_refrigeration_01_41  White Industrial Fridge FacingE
- appliances_refrigeration_01_48  Chest Freezer FacingS
- appliances_refrigeration_01_49/51  Chest Freezer pair FacingE/W

### Counters / Sinks
- fixtures_counters_01_33  Steel Counter FacingN
- fixtures_counters_01_35  Steel Counter FacingE
- fixtures_counters_01_37  Steel Counter FacingS
- fixtures_counters_01_40  Birchwood Corner Counter FacingN
- fixtures_counters_01_41  Birchwood Counter FacingN
- fixtures_counters_01_47  Birchwood Counter FacingW
- fixtures_sinks_01_5   Sink FacingS  (Large Wide)
- fixtures_sinks_01_7   Sink FacingN  (Industrial)
- fixtures_sinks_01_9   Sink FacingS  (Chrome)
- fixtures_sinks_01_10  Sink FacingW  (Chrome)
- fixtures_sinks_01_16  Sink FacingE  (Dark Industrial)
- fixtures_sinks_01_28  Sink FacingN  (Small Wide standing)
- fixtures_sinks_01_30  Sink FacingN  (White Hanging)
- fixtures_sinks_01_31  Sink FacingW  (White Hanging)
- location_restaurant_seahorse_01_44/45  Seahorse Coffee Counter FacingS
- location_hospitality_sunstarmotel_02_20  Vanity Counter FacingN  (Low Motel)
- location_hospitality_sunstarmotel_02_22  Mirror FacingS  (Large Wide)

### Bathroom fixtures
- fixtures_bathroom_01_1   Toilet FacingE  (Fancy, attachedW)
- fixtures_bathroom_01_4   Toilet FacingS  (Low, attachedN)
- fixtures_bathroom_01_5   Toilet FacingE  (Low, attachedW)
- fixtures_bathroom_01_8   Toilet FacingS  (Hanging, attachedN) ← urinal row
- fixtures_bathroom_01_15  Hand Dryer/Blower FacingW  (Air)
- fixtures_bathroom_01_46/48  Stall door pair variant A
- fixtures_bathroom_01_58/65  Stall door pair variant B
- fixtures_bathroom_01_62/64  Stall door pair variant C
- fixtures_bathroom_01_59   Single stall door

### Vending / Recreation
- location_shop_accessories_01_9   Soda Fountain (IsTableTop)
- location_shop_accessories_01_17  Large Vending Machine FacingS
- location_shop_accessories_01_19  Small Soda Machine FacingS
- recreational_01_17              Arcade Machine FacingE  (Dr. Oids, 2-tile)
- recreational_01_19              Arcade Machine FacingN
- recreational_01_20              Arcade Machine FacingS  (Kaboom)
- recreational_01_26/27           Pinball Machine FacingE  (PAWS, 2-tile)

### Misc / Decor
- vegetation_indoor_01_7          Chinese Evergreen (plant, floor)
- furniture_tables_high_01_16     Generic table surface (for placing tabletop appliances)
- location_restaurant_bar_01_37   Bar Counter FacingE
- location_restaurant_bar_01_25   Blue Bar Stool FacingS
- trashcontainers_01_17           Green Garbage Bin
- trashcontainers_01_18           Bin FacingS
- trashcontainers_01_19           Bin FacingE
- trashcontainers_01_20           Round Bin
- lighting_indoor_02_35           Green Vintage Desk Lamp (IsTableTop)
