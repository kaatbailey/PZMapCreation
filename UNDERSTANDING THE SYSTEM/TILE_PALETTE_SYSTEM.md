# TilePalette System — How Roles Become Tile Names

This document explains how `TilePalette.java` works and how to add or fix roles.

---

## What TilePalette Does

`FurniturePlacer` never mentions a tile name directly. It says `"counter"` or `"shelves_N"` — a **role**. `TilePalette` resolves roles to actual tile names by scanning the sprite atlas and grouping tiles that match each role's predicate.

This indirection means:
- Every shelf in the game doesn't have to be the same shelf
- New tiles added to PZ automatically appear if they match a role's predicate
- Roles that match nothing return null (object silently skipped, room doesn't break)

---

## How TilePalette Is Built

On startup, `TilePalette.load(mediaDir)` scans all `.tiles` packs in the PZ media directory, reads every tile's properties (CustomName, container, Facing, flags like attachedN, IsTableTop etc.), and groups tiles by role. Each group is a `List<String>` of verified tile names. "Verified" means the tile has a sprite — tiles with no sprite image are excluded.

After loading, `TilePalette` has:
- `groups` — a `Map<String, List<String>>` of role → tile names
- `all` — a flat `List<String>` of every verified tile name
- `roofSlopeNames`, `roofGableNames`, `roofAccentNames` — special sets for the roof pass
- Several single-tile fields: `furnitureCounter`, `furnitureDeskN`, etc. for the authored bathroom

---

## Roles and How They're Defined

Each role is defined by a `put(roleName, predicate)` call. The predicate is a Java lambda that takes a tile name and returns true/false. Only tiles matching the predicate AND having a sprite go into that role's group.

### Facing variants
Many roles have directional variants. These are generated in a loop:
```java
for (String d : new String[]{"N", "S", "E", "W"}) {
    put("shelves_" + d, n -> "shelves".equals(prop(ti, n, "container"))
            && n.startsWith("furniture_shelving_")
            && flag(ti, n, "attached" + d));
}
```
This creates roles `shelves_N`, `shelves_S`, `shelves_E`, `shelves_W`. In `FurnitureProfile`, you use the bare name `"shelves_N"` and the placer resolves it to a tile facing the right way.

### Chair variants
Chairs have FOUR role families × FOUR facings = 16 groups:
```
chair_N, chair_S, chair_E, chair_W           (generic chairs)
chair_office_N, chair_office_S, ...           (office/swivel chairs)
chair_dining_N, chair_dining_S, ...           (dining/wooden chairs)
chair_soft_N, chair_soft_S, ...               (armchairs/sofas — single)
```
A couch is NOT a chair role. Couches are separate:
```
couch_N, couch_S, couch_E, couch_W
```

---

## Key Roles Reference

### Seating
| Role | What it picks |
|---|---|
| `chair_dining_N` | Wooden/dining chairs facing N |
| `chair_soft_N` | Armchairs facing N |
| `chair_office_N` | Swivel/office chairs facing N |
| `couch_S` | Couches facing S (backs north wall) — PAIRED TILE |
| `bench` | School bench specifically |

### Beds (all PAIRED TILES)
| Role | What it picks |
|---|---|
| `bed` | Any bed primary tile (includes medical/hospital beds) |
| `bed_home` | Residential beds only (excludes medical sheets) |
| `bed_medical` | Large Medical Bed only (hospital rooms) |

### Kitchen / Bathroom
| Role | What it picks |
|---|---|
| `counter` | Any counter (container=counter) |
| `sink` | Any sink (named "Sink") except metal plates |
| `oven` | Domestic ovens from appliances_cooking_* |
| `fridge` | Any fridge (container=fridge) |
| `microwave` | Named "Microwave" |
| `toaster` | Named "Toaster" |
| `overhead_N` | Wall-mounted cabinet facing N (IsHigh flag) |
| `toilet_N` | Toilet attached to N wall |
| `bath` | Bath primary tile (not extension) |
| `shower` | Shower with waterPiped flag |
| `mirror_N` | Mirror attached to N wall |
| `blower_N` | Hand dryer attached to N wall |

### Storage
| Role | What it picks |
|---|---|
| `shelves_N` | Wall shelf attached to N wall (furniture_shelving_*) |
| `shelves_office` | Freestanding office racking (furniture_shelving_*, no attach) |
| `shelves_retail` | Retail shelving (location_shop_*) |
| `shelves_metal` | Large free-standing metal shelving (metal_shelves container, no attach) |
| `shelves_wall_metal` | Wall-mounted metal shelves (metal_shelves container, attached) |
| `wardrobe` | container=wardrobe |
| `drawers` | container=sidetable (bedside drawers) |
| `locker` | Named "Locker" (non-military) |
| `locker_military` | Military Locker or Green Wall Locker |
| `crate` | Named "Crate" from carpentry_* or crafted_* sheets |
| `crate_military` | Named "Military Crate" |
| `box` | Named "Box" from trashcontainers_* |
| `pallet` | Named "Empty Pallet" or "Pallet" |

### Office
| Role | What it picks |
|---|---|
| `desk` | container=desk |
| `workbench` | Named "Workbench" |
| `table` | Named "Table" with IsTable flag |
| `watercooler` | Named "Dispenser" with IsLow flag |
| `television` | Named "Television" |

### Decor
| Role | What it picks |
|---|---|
| `plant_floor` | Snake Plant or Cast Iron Plant with IsLow |
| `plant_table` | Orange Plant with IsTableTop |
| `rug` | floors_rugs_* with attachedFloor |

### Special / Stall
| Role | What it picks |
|---|---|
| `stall_wall_N` | Stall wall tile (fixtures_bathroom_02_*) facing N |
| `stall_wall_W` | Stall wall tile facing W |
| `stall_doorwall_N` | Stall door wall facing N |
| `stall_doorwall_W` | Stall door wall facing W |
| `stall_door_N` | Stall door facing N |
| `stall_door_W` | Stall door facing W |
| `stall_toilet` | Plastic stall toilet (fixtures_bathroom_02_4/5) |
| `urinal_N` | Urinal attached to N wall |
| `display_stand` | container=grocerstand |
| `lectern` | Named "Lectern Stand" |
| `chair_pew_N` | Dark Wooden Chair facing N (church pews) |

---

## How FurniturePlacer Uses TilePalette

```java
// Pick one tile from a role group at random:
String tile = pal.pickFrom("counter", rng);
// Returns e.g. "fixtures_counters_01_53" or null if group is empty.

// Check if a tile exists in the atlas:
boolean exists = pal.all.contains("fixtures_counters_01_53");
// Used to verify extension tiles before placing paired objects.

// Get all tiles in a group (for the lock system):
List<String> tiles = pal.group("shelves_N");
```

---

## Adding a New Role

In `TilePalette.java`, find the section for the role category and add a `put()` call:

```java
// Example: add a "safe" role for wall safes
put("safe", n -> named(n, "Safe") && flag(ti, n, "attachedW"));
```

Helper methods available inside `put()` predicates:
- `named(n, "CustomName")` — tile's CustomName property equals this
- `flag(ti, n, "FlagName")` — tile has this boolean property
- `prop(ti, n, "PropertyName")` — returns the tile's property value (string)
- `anyAttach(n)` — tile has any attachedN/S/E/W flag

After adding the role, reference it in `FurnitureProfile.forRoom()` as an `Activity.of("safe")` or `Activity.maybe("safe", 0.5)`.

---

## Diagnosing Empty Role Groups

If furniture is silently skipped (a role resolves to null), run:

```fish
java -cp out pzformat.PaletteScan "$PZ/media" <tile_prefix>
```

e.g. `pzformat.PaletteScan "$PZ/media" furniture_shelving_01` to see all tiles on that sheet and their properties.

Then check whether your role's predicate would match any of those tiles. Common mistakes:
- Wrong `CustomName` string (case-sensitive)
- Missing flag (e.g. forgot `IsLow` on watercooler)
- Tile excluded because it has no sprite (`yes` column in PaletteScan output)
- Wrong sheet prefix (e.g. using `furniture_shelving_` when the tile is on `location_shop_`)

---

## The Tile Lock System

`FurniturePlacer` keeps a `lock` map per room: `family → tile name`. Once a role family is used in a room, subsequent placements of the same family prefer tiles from the same sheet (same furniture set). This stops a room having five different shelf styles.

- Lock key = role without facing suffix (`"shelves"` for `"shelves_N"`)
- 20% drift (`LOCK_DRIFT = 0.2`) allows occasional variety
- Lock clears between rooms (`lock.clear()` in `furnish()`)

If you want a room to have deliberately mixed furniture, add more variety within a single role group (more tile names matching the same predicate) rather than using multiple role names — the lock handles variety within a group automatically.
