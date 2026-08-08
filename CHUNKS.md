# pzformat — Chunk index and prompts

Companion to `CHARTER.md` and `STATE.md`. This file holds the work breakdown and
the ready-to-paste prompt for each chunk.

---

## How to run a chunk

1. Open a new session.
2. Paste, in this order: **`CHARTER.md`**, then **`STATE.md`**, then **the one
   chunk prompt** you are working on, then **the `FINDINGS` block from the
   chunk you just finished** if it is listed as an input.
3. Work the chunk. Do not start the next one.
4. At the end, the session writes a `FINDINGS` block in the format at the bottom
   of this file.
5. You fold the findings into `STATE.md` — adding, never deleting — and tick the
   chunk here.

**Never paste more than one chunk prompt.** A session that can see three chunks
will half-do all three and hand you back something none of them defined as done.

**Why prompts are short and the charter is long:** the charter is the part that
must survive; the prompt is disposable. If a future session only reads one
document, it should be the charter.

---

## Chunk index

Status: `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked

### Track A — Library. Architecture-independent, useful whatever the UI becomes.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[ ]` | **A1** Verify `outlineRoom` wall placement | — | Measurement + fix or confirmation |
| `[ ]` | **A2** Remove superseded vegetation code | — | Deletion + proof nothing changed |
| `[ ]` | **A3** Auto wall-joining | A1 | `WallJoin` + tests |
| `[ ]` | **A4** Validation rule engine | A1, A3 | `Validator` + rule set |
| `[ ]` | **A5** TMX read/write | A1 | Interop, checked against Unjammer corpus |
| `[ ]` | **A6** `.tiles` writer | — | Writer + round-trip |
| `[ ]` | **A7** `objects.lua` read/write | — | Parser, writer, room-type link |

### Track B — Buildings. Evidence first, then a decision, then code.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[ ]` | **B1** Vanilla house anatomy | — | **A document. No code.** |
| `[ ]` | **B2** `StaticModule.prefab` decision gate | B1 | **A written decision.** |
| `[!]` | **B3** Room decomposition | B2 | Blocked — cannot be written until B2 resolves |
| `[!]` | **B4** Openings: doors and windows | B3 | Blocked |
| `[!]` | **B5** Roofs | B3 | Blocked |
| `[ ]` | **B6** Room typing and loot tables | B1, A7 | Named room types in output |

### Track C — Application. Local single-user desktop editor.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[ ]` | **C1** Architecture decision gate | — | **A written decision.** |
| `[!]` | **C2** Working store and project format | C1 | Blocked |
| `[!]` | **C3** Interactive viewport | C1, C2 | Blocked |
| `[!]` | **C4** Tool layer: brushes, selection, undo UI | C3, A3 | Blocked |
| `[!]` | **C5** Shell: panels, tile picker, validation panel | C4, A4 | Blocked |

### Track D — Sustaining.

| | Chunk | Depends on | Deliverable |
|---|---|---|---|
| `[ ]` | **D1** Fast round-trip regression | — | Sub-minute suite |
| `[ ]` | **D2** Publish B42 format documentation | — | Public doc |
| `[ ]` | **D3** GIS licensing review and project licence | — | Decision |

### Suggested order

**A1 → A2 → B1 → B2 → C1**, then the tracks open up in parallel. A1 first
because room geometry is load-bearing for everything in B and C. A2 second
because it is cheap, the evidence is already in hand, and it removes two files
a future session would otherwise have to reason about.

---

## Shared preamble

Every prompt below assumes this, and every prompt written later must include it:

> You have been given `CHARTER.md` and `STATE.md`. Work **only** the chunk
> below. If you find something that belongs to another chunk, record it in
> FINDINGS under "Noticed, out of scope" and do not act on it.
>
> Do not rewrite `CHARTER.md`. Do not delete anything from `STATE.md`.
>
> Before writing code: state your approach, name the check that would prove it
> wrong, and run that check. Patches are delivered as Python scripts that abort
> unless each anchor matches exactly once. The shell is fish — no heredocs. Run
> everything from `~/Documents/PZMapCreation`.

---

# Track A prompts

## A1 — Verify `outlineRoom` wall placement

**Why this is first.** `outlineRoom` places north walls at `y0+h` and west walls
at `x0+w`, the far edges belonging to the *next* square out. That follows from
edge-based walls, but it is **reasoning, not measurement** — and unmeasured
reasoning is what produced the x/y transposition and the `attachedN` bug, two of
the eight bugs that got through the test suite. Every building and every editor
room-drawing tool sits on this.

**Scope.** Take real rooms from vanilla Muldraugh. Read their actual wall
positions off the map data. Compare against what `outlineRoom` generates for the
same rectangle. Fix it or confirm it.

**Method.**
- Use `Probe square` and `Probe findprop` against `"$MAPS/Muldraugh, KY"`.
  `RoomGeometry` already measured wall offsets across 86 rooms — reuse that
  rather than re-deriving it, but treat its conclusion as the hypothesis under
  test, not as the answer.
- Predict the wall coordinates before dumping them.
- Test more than one room shape. A square room can be right by accident where an
  L-shape or a 1-tile-wide room is wrong.
- Test a room on a cell boundary if one exists — that is where an off-by-one
  becomes a cross-cell bug.

**Definition of done.** For at least five vanilla rooms of differing shapes, the
walls `outlineRoom` would generate match the walls actually present, or the
discrepancy is characterised exactly and fixed. A self-test encodes the result.

**Falsification.** If your check cannot distinguish "walls at `y0+h`" from
"walls at `y0+h-1`", it is not a check. Say what output each hypothesis predicts
before you look.

**Non-goals.** Do not build room *creation* UI, do not touch building
generation, do not refactor `RoomGeometry`.

---

## A2 — Remove superseded vegetation code

**Context.** `TreeScatter` and `TreePalette` place ~7,800 trees that
`genMapSquare` deletes on load (STATE §9 — the engine owns TREE/BUSH/PLANT and
replaces them per tile from the biome map). `WorldGenOverride.lua` is still
written and is superseded by the biome map on authored chunks.

**Scope.** Delete both, stop writing `WorldGenOverride.lua`, regenerate, and
**prove in game that nothing changed.**

**Method.** The proof is the point, not the deletion. Before deleting, predict
what the square dump and the in-game view will show afterwards. Then:

- Regenerate the mod and diff the square dumps before/after. Authored trees
  should be the only difference.
- Load in game and check vegetation, the biome gradient, and the cell boundary
  seam. All three should be unchanged.

**Definition of done.** Both classes gone, `WorldGenOverride.lua` no longer
written, self-tests pass, and an in-game observation recorded confirming the
gradient and seam are unchanged.

**Falsification.** If in-game vegetation *does* change, the belief in STATE §9
is wrong and that is a far more valuable result than the cleanup. Record it in
the Corrections table and stop.

**Non-goals.** Do not improve the biome map while you are in there. Do not touch
`GroundPalette`.

---

## A3 — Auto wall-joining

**Depends on A1.** Do not start until A1 has confirmed or fixed wall placement.

**Context.** All the information needed is already present: `TileIndex` and
`Square` resolve walls, and `.tiles` carries facing. What is missing is choosing
the *right variant* when a wall is placed — corner, end, T-junction, straight.
This is a headline feature the official tools handle poorly.

**Scope.** Given a placed wall and its neighbours, select the correct wall tile
variant. Update neighbours when a wall is added or removed.

**Method.** Read vanilla first. Find a house corner, a T-junction and a wall end
in Muldraugh with `Probe square`, and record which tiles vanilla uses in each
case. The tileset's own naming and facing properties are the recipe; do not
infer the variant set from what looks right in a render.

**Definition of done.** Placing a wall run around a rectangle in `CellEditor`
produces the same tile choices vanilla uses for an equivalent structure, and
removing one wall re-joins its neighbours correctly. Undo still restores
byte-identical output.

**Falsification.** Re-derive the variant for a wall you did not use to build the
rule, in a different tileset. If the rule only works on the tileset it was
derived from, it is a lookup table, not a rule.

**Non-goals.** No UI. No doors or windows — fixtures mount in walls and have
their own chunk.

---

## A4 — Validation rule engine

**Depends on A1, A3.** This is the chunk that makes the charter's competitive
claim true, so it should not be rushed to a thin version.

**Scope.** A `Validator` that walks a cell and reports structural problems with
coordinates: doorway with no adjacent floor, room with no exit, wall gap that is
not a door, floor at z>0 with nothing beneath, room rectangle overlapping
another, square with no object at z=0 (the chunk gate from STATE §7).

**Method.**
- **Run it against vanilla Muldraugh first.** Vanilla is hand-authored and
  correct-by-construction; a rule that fires on hundreds of vanilla squares is a
  wrong rule, not a discovery. Tune against the 4065-cell corpus.
- Report rates over the population that can discriminate. A rule that cannot
  fire on most squares will look impressively clean for no reason.
- Each rule needs a severity: error (map will not work) vs warning (probably a
  mistake).

**Definition of done.** The rule set runs clean, or with characterised and
explained exceptions, across a sample of vanilla cells; and it correctly flags
each problem in a deliberately broken test cell.

**Falsification.** For every rule, construct the broken case *and* confirm the
rule fires on it. A rule never observed firing is not known to work.

**Non-goals.** No UI panel. No auto-fix. Report only.

---

## A5 / A6 / A7 — stubs

Write the full prompt when the chunk comes up, using the shape above.

- **A5 TMX read/write.** Interop boundary with the official tools.
  `Unjammer/PZ_Vanilla_map_b42` is the whole vanilla map already decompiled to a
  WorldEd project — an independent regression corpus. Parse a cell, compare
  against their TMX for the same coordinates. Depends on A1 because room
  rectangles cross the boundary.
- **A6 `.tiles` writer.** Reader is confirmed on 73,644 tiles. Writer is needed
  for custom tile properties.
- **A7 `objects.lua` read/write.** Currently `{}`; vanilla's is 4 MB. Likely
  connected to room loot tables, so pair with B6.

---

# Track B prompts

## B1 — Vanilla house anatomy

**Deliverable is a document, not code.** If this chunk produces Java, it has
failed. Three multi-session detours have come from building before reading.

**Scope.** Pick three vanilla Muldraugh houses of different sizes. Using
`Probe square` and `Probe findprop`, write down exactly how a PZ house is
constructed:

- Floor tiles by room type, and how they change between rooms
- Wall tiles: exterior vs interior, material variation, corner handling
- Where doors sit relative to the wall edge; interior vs exterior door tiles
- Windows: tile, placement rules, height
- Roof: which z-levels, which tiles, how overhang works, what happens at eaves
- Room rectangles: how an L-shaped house decomposes; how many rooms per building
- Room *names* actually used, and the `objects.lua` entries alongside them
- Anything at z=-1

**Definition of done.** A markdown document a future session can build from
without opening the game — with tile names, coordinates, and at least one
worked example per structure. Contradictions between the three houses are
recorded as contradictions, not averaged away.

**Falsification.** For each claim, note whether it held in all three houses or
only one. A pattern from a single house is a hypothesis.

**Non-goals.** No generator design. No opinions on how *we* should build houses.

---

## B2 — `StaticModule.prefab` decision gate

**Deliverable is a written decision with evidence attached.** Not code.

**Context.** Building generation has an open fork that has been open for three
sessions and must not be decided on vibes:

- **Room-splitting generator** — we author the geometry. The editor can then
  inspect, validate and undo it. Consistent with the charter's semantic layer.
- **`StaticModule.prefab`** — the engine's own structure placement mechanism.
  Never tried, never read. The engine assembles at load time, which may mean the
  editor cannot inspect or validate what the player will see.

**Scope.** Read `StaticModule` and everything it touches in the decompiler
(`~/Downloads/ZOMBOIDSTUFF/decompiled/`, Vineflower at
`~/Downloads/ZOMBOIDSTUFF/vineflower.jar`; `grep -rl StaticModule
--include='*.class'` finds it). Answer:

1. What does a prefab consist of on disk, and where does the engine read it?
2. Does vanilla use it? For what? Find a call site.
3. Does it run on authored chunks, or only procedural ones? (Compare with the
   `genMapChunk` / `genRandomChunk` split in STATE §7.)
4. Can authored map data reference a prefab, or is it WorldGen-only?
5. Is prefab output inspectable from map data, or only after load?

**Definition of done.** A decision — room generator, prefab, or both with a
stated boundary — with the answers above and their evidence. Question 5 is
decisive for the editor: if prefab output cannot be inspected from map data, the
editor cannot validate it, and that outweighs any convenience.

**Falsification.** Note which answers came from reading code and which from
inference. Any inference gets flagged UNVERIFIED in STATE.

**Non-goals.** Do not implement either path.

---

## B6 — Room typing and loot tables

Depends on B1 and A7. Generic `"room"` gives no loot tables, so a generated map
is unplayable regardless of how good the geometry is. Full prompt when it comes
up.

---

# Track C prompts

## C1 — Architecture decision gate

**Deliverable is a written decision.** Not code, not a prototype.

**Constraints already fixed by the charter — do not re-litigate:**

- Local, single-user. **No multi-user concurrent editing** (decided
  2026-08-08 — the official tools don't support it and there's no demand).
- The library layer stays dependency-free Java 21. The application layer may
  take dependencies.
- Reads assets from the user's PZ install. Ships no TIS art.
- Runs on Linux. Development is Garuda + IntelliJ.

**Decide, with reasons:**

1. **UI toolkit.** Options previously floated but never chosen: a local
   Spring Boot process serving a browser canvas, or native LWJGL / libGDX. With
   multi-user gone, the main argument for the web stack was removed — so this
   should be re-argued from scratch, not inherited. Weigh against: Knox County
   is ~1,300 cells, B42 has negative z-levels, and a naive per-tile draw dies
   immediately. Sprite batching, an atlas cache and viewport streaming are
   needed from day one whatever the choice.
2. **Working store.** SQLite, chunked binary, or the game's own format as the
   live format. Thousands of TMX files is not a working store — TMX stays an
   interop boundary (A5).
3. **Where undo lives.** `CellEditor` has an in-memory grouped journal that
   restores byte-identical output. Does it persist across sessions, and if so,
   in what?
4. **Process boundary.** One process or two, and what crosses.

**Definition of done.** A document giving the choice, the alternatives
considered, and — for each — what would make it the wrong choice. That last part
matters: it is what lets a future session recognise a mistake instead of
inheriting it.

**Falsification.** For the rendering choice specifically, estimate the frame
budget for a full viewport of tiles before choosing, and say what number would
rule the option out. Don't discover it after building the shell.

**Non-goals.** No prototype. No scaffolding. No dependency added to the library.

---

## C2–C5 — stubs

Cannot be written until C1 resolves; their content depends on the toolkit and
store chosen. Shapes only:

- **C2 Working store and project format.** Open, edit, save, reopen without
  loss. Crash safety.
- **C3 Interactive viewport.** Pan, zoom, z-level switching, layer visibility.
  Streaming and atlas caching per C1's numbers.
- **C4 Tool layer.** Brushes, rectangle select, floor fill, wall draw (using
  A3), delete. Undo/redo bound to the UI on top of `CellEditor`'s journal.
- **C5 Shell.** Tile picker driven by the semantic layer — search by *property*,
  not filename. Validation panel from A4, click-to-navigate. Room inspector.

---

# Track D — stubs

- **D1 Fast round-trip regression.** Currently ~14 min, which means it doesn't
  get run. Target under a minute for a representative sample, with the full
  4065-cell run kept as an occasional job.
- **D2 Publish B42 format documentation.** PZwiki documents B41 and is wrong for
  B42 on magic bytes, string terminators, cell size, offset width and chunk
  size. The format work is confirmed byte-identical; publishing it is cheap
  goodwill and invites correction from people who know things we don't.
- **D3 Licensing.** Verify GIS dataset terms per state; choose a project licence.

---

## FINDINGS block format

Every chunk ends by producing this. Paste it into the next session if it is
listed as an input, and fold it into `STATE.md` before starting anything else.

```markdown
## FINDINGS — <chunk id> — <date>

**Status:** complete / partial / blocked

**What was done:**
- …

**Confirmed** (verified against vanilla data, decompiled engine, or in game):
- …

**Unverified** (believed, not tested — say what would test it):
- …

**Corrections** (something in STATE.md is wrong):
- Old claim → what is actually true → evidence

**Files changed:**
- created / modified / deleted, by path

**Commands worth keeping:**
```fish
…
```

**Noticed, out of scope:**
- …

**What the next chunk needs to know:**
- …
```

The **Corrections** and **Unverified** sections are the ones that earn their
keep. Eight bugs got through 224 automated tests, and every one was caught by
comparison against an independent source — a session that records what it
merely *believes*, separately from what it *checked*, is handing the next
session the list of things worth checking.
