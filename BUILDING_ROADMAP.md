# Building mechanics — roadmap

Status: phase 0 shipped; this doc is the design for everything after. The companion can already place
block-by-block schematics; the roadmap covers authoring schematics in-game, anchoring, big palettes,
foundations/scaffolding, and pulling materials from chests.

---

## Phase 0 — shipped (reference)

- Order: `build <name>` (voice, `/companion build <name>`, planner op `build`).
- Executor: `entity/ai/CompanionBuildGoal.java`; order entry in `CompanionEntity.applyCommand` (BUILD case,
  anchor = companion position, name sanitized to `[a-z0-9_\-]`).
- File: `config/companionmod/schematics/<name>.json` (server working dir — project dir for the integrated
  server, server root for dedicated).
- Format (both accepted):
  ```json
  [{"x":0,"y":0,"z":0,"block":"minecraft:cobblestone"}, ...]
  ```
  or `{"blocks":[ ...same... ]}`. Coordinates are **relative to the anchor**.
- Behaviour per step: skip if the cell already holds the target block; skip occupied foreign cells;
  walk within 4.5 blocks; consume 1 of the block's item from the 18 storage slots; `setBlock`; swing.
- Missing material: hold position, say *"I need more <block> to finish the build."* (throttled), retry
  automatically once the bag is refilled — no `continue` needed.
- Restart-safe: already-correct cells are skipped, so a crash/relog continues where it left off.
- Unknown/unreadable file: spoken explanation, job ends.

---

## Phase 1 — authoring schematics in-game (the real blocker)

Hand-writing JSON does not scale. Two options, pick one:

**Option A (recommended): capture screen.** A `CompanionCaptureScreen` opened from the radial (or
`/companion capture <name>`):
1. First click/raycast sets corner 1, second click sets corner 2 (highlight both with a translucent box
   outline — reuse the `submitText`/billboard pattern or a simple `renderHighlight` box).
2. Preview counts per block id ("cobblestone ×64, planks ×20").
3. Save button writes the JSON to `config/companionmod/schematics/<name>.json` via
   `Files.writeString`, relative coords from corner 1.
4. ~150 lines, client-only, no server changes. Vanilla precedent: the structure block's box UI.

**Option B: convert vanilla structure saves.** Structure blocks export `.nbt`; a one-off converter
(`/companion import <file>`) reads the NBT palette+blocks and emits our JSON. More moving parts, but
reuses anything made with structure blocks. Keep as a later convenience.

Also in phase 1:
- **Schematic list**: radial submenu or a screen listing `schematics/*.json` (name + block count) —
  pick to build. Requires a `listSchematics()` helper (`Files.list` + sort by name).
- **Anchor modes**: `build <name> here` (default), `at me` (player feet), `at` + raycast target block.
  Passed as the command target string, e.g. `build house at me` → parse suffix.

---

## Phase 2 — foundations and reach

- **Clear mode**: JSON flag `"clear": true` — before placing, break non-air cells inside the footprint
  (reuse the seek goal's `destroyBlock` + crack animation + tool equip). Dangerous-by-default: only with
  the explicit flag, never inside the player's anchor cell.
- **Reach limit**: a companion places blocks within ~4.5 blocks. Tall builds need the schematic ordered
  floor-by-floor (bottom-up) — the builder naturally stands on finished floors. For walls >3 high the
  schematic should include ladders/scaffolding as normal blocks (it already can — they're just blocks).
- **True scaffolding** (auto-place/break scaffolding under itself while climbing): possible but
  high-complexity; only if asked. Simpler: schematics include their own scaffold column.
- **Post-build report**: spoken summary — placed N blocks, skipped M (already correct), missing K
  (with ids). The goal already tracks these; just speak them at the end instead of only "Build finished!".

---

## Phase 3 — big palettes (the "palette too big for the bag" question)

Current answer (shipped): the builder pauses at the first missing block, tells the player what it
needs, and resumes when the bag is refilled. A palette bigger than the bag is a **refill loop**, not a
failure. Improvements, in order of value:

1. **Pre-flight tally** — before placing anything, count the whole schematic per block id, compare with
   bag contents, and speak the gap: *"This needs 64 cobblestone and 20 planks; I have 40 and 0."* Then
   start anyway (placing what it can) or wait, config toggle `buildPreflightReport`.
2. **Chest pulling** — when a cell's block is missing from the bag, walk to the nearest chest within 16
   blocks, extract exactly what's needed directly from the `ChestBlockEntity` inventory server-side
   (no GUI: `((AbstractChestMenu)...)` not needed — read `BlockEntity` items, `removeItem`, `store`),
   then continue. Gate behind a stance/permission flag (`buildPullFromChests`, default OFF) so the
   builder never silently empties the player's chests. Optionally only from chests the player marks
   (a "builder chest" tag via name lore or a command).
3. **Multiple materials in one trip** — batch the missing list, visit chests once, then resume placing.
   Avoids walk-to-chest-per-block thrash.

---

## Phase 4 — schematic features

- **Rotate / mirror**: transform step coords (`rotate90/180/270`, `mirrorX/Z`) before placing; flags in
  the JSON header. Trivial math, big usability win for repeated walls.
- **Multi-part builds**: `{"parts":["wall.json","roof.json"]}` — the builder executes parts in order,
  re-anchoring each. Enables "house" = foundation + walls + roof composed from captured pieces.
- **Relative anchoring modes**: `bottom-center` (current), `bottom-north`, `center` (for floating
  builds), `against-wall` (y from the raycast face).
- **Substitutions**: `{"minecraft:cobblestone": "minecraft:stone_bricks"}` header map — one schematic,
  different materials. Cheap to add in `loadSchematic`.
- **Blueprint sharing**: schematics are just JSON — zip a folder, share, drop in the folder. Document
  the format (this file + a `schematics/README.txt` the mod writes on first run).

---

## Implementation notes / verified 26.1.2 APIs

- `Level.setBlock(pos, state, 3)`, `BlockState.isAir()`, `Block.defaultBlockState()` — used already.
- `BuiltInRegistries.BLOCK.getOptional(Identifier)` resolves `minecraft:...` ids; `Block.asItem()` maps
  block → bag item. `Identifier.tryParse` for tolerant parsing.
- Chest inventory: `level.getBlockEntity(pos)` → `BlockEntity`; server-side cast to the chest block
  entity type, read its item list directly (no menu needed for extraction).
- Capture screen raycast: `mc.hitResult` (BlockHitResult) on the client game thread.
- Everything stays server-authoritative: the client only authors JSON files; the server validates every
  block id and every bag mutation (same trust model as plans — the model/user supplies data, never code).

## Open questions for you

1. Capture UI as a radial screen entry (box-select with clicks) vs command syntax
   (`/companion capture <name> x1 y1 z1 x2 y2 z2`)? Screen is friendlier; command is ~30 lines.
2. Chest pulling default OFF (opt-in) — agree?
3. Pre-flight tally: speak and start anyway, or speak and wait for "continue"?
4. Should the builder also **break** blocks outside the footprint that are in the way (clear mode), or
   strictly skip and report?
