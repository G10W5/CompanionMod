# companionmod patch — MC 26.1.2 (NeoForge + Fabric)

Copy the folders over your project (paths mirror it). Every .java file was parse-checked with javac; symbols could not be
resolved here (no Minecraft jars), so compile once and check the `26.1-CHECK` comments.

## DELETE these (dead code, unreferenced)
- common/.../world/ShrineSpawner.java, ShrineHandler.java, WorldGenerationHandler.java

## New: single-block 3D shrine
- Block is ONE block, unbreakable in survival, custom hitbox + particles (`CompanionShrineBlock`, `ModContent`).
- Model: `assets/companionmod/models/block/shrine_core.json` (plinth, step, pillar, cup, 4 prongs, 2 crossed floating
  crystals) using vanilla textures only. Also `blockstates/`, `items/` (26.1 item definition), `lang/en_us.json` (MERGE into yours).
- `ShrinePlacer` now places just that block (adds a deepslate-brick footing if the ground is water/air).
- It is a normal JSON model, not a BlockEntityRenderer: no animation, but no loader-specific render code either.
  A spinning crystal needs a BE + renderer (26.1 render-state API) — ask if you want it.

## Downed companion
- Mobs ignore it: `canBeSeenAsEnemy/ByAnyone` false while injured, and every 10 ticks nearby mobs drop it as target / attacker.
- /kill and void damage can now finish a downed body (bag is dropped, nothing is lost or duplicated).

## Fabric
- Removed the duplicate MENU registration (startup crash).
- Added join (shrine), disconnect, server-stop hooks and the creative-tab entry; both loaders now call `ServerHooks`.
- Config dir passed in from each loader (`CompanionMod.configDir()`).

## Bugs fixed (both loaders)
- Wildfire layer + "owner" skin: renderer used the server-only owner UUID; now `syncedOwner()`, and "owner" resolves THIS companion's owner.
- Duplicate companions / lost bag: new `CompanionRegistry` (world JSON). Cross-dimension summon moves name/skin/bag; stale copies drop their bag and remove themselves.
- `BuildGoal` ate the last item of every stack; plan `build`/`attack`/`hunt` steps lost their target (`PlanParser`).
- Appearance packets (name/skin/feminine/revive) paused plans: shared `CompanionCommand.interruptsPlanning()`.
- Push-to-talk race (stuck mic): atomic state, mic open/close off the render thread, recording cap enforced, cap changes apply live.
- Starting iron sword vanished after 1 s: now stored in the sword slot.
- Removed chat spam "Companion body: ..." diagnostic.

## Safety / performance
- Server-side validation: skin names `[A-Za-z0-9_]{1,16}`/owner/default, names max 32 chars, revive needs owner within 7 blocks.
- Mining, farming, building honour spawn protection/world border via `CompanionProtection` (claim mods that only hook player events
  still need a fake player — not done).
- Schematics: read off-thread, 2 MB / 20k blocks / ±96 limit, built bottom-up, skip blocks without an item.
- `findCompanion` is O(1) (registry); client side cached; monster aggro every 2 s, radius 16, max 6, never while passive.
