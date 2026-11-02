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

## Speech bubbles (CompanionSubtitles + CompanionRenderer)
- Stuck / never fading: the timed (no-audio) clock was clamped at the line's duration, so the last words stayed frozen at a young age.
  It now keeps running; audio mode also falls back to wall time if the "stopped" callback never arrives.
- Too high: rise 64 px -> 20 px and anchor +0.45 -> +0.2 blocks above the hitbox (was ~2.2 blocks above the head). Words now float up
  slowly over their whole life instead of snapping up in 0.15 s. Tune `RISE_PX` and the `+ 0.2f` to taste.
- Fade end no longer flashes opaque (vanilla treats text alpha < 4/255 as fully opaque).
- Lines of 200-240 chars no longer restart their animation every frame (voice queue clips at 200).

## Mining: climbing with blocks, vein stall (CompanionSeekBlockGoal)
- NEW scaffolding: a target above reach (birch logs high in the crown, ore up a cave wall) makes the companion jump-pillar with cheap
  blocks from its bag (dirt family, stone family, cobble, cobbled deepslate, netherrack, planks; never the block being mined, never
  sand/gravel or tile-entity blocks), clearing leaves overhead first, up to 24 blocks. For SURFACE jobs (wood, stone, dirt...) the blocks
  are temporary: when nothing is left to mine up there (or the task is done, or the next target is elsewhere) it climbs down by breaking
  them, top first, and collects them again. For MINING jobs (coal, iron, diamond... anything that descends/branch-mines) every placed
  block stays, and gaps in a tunnel floor are bridged with a permanent block (max 12 in a row, never over liquid).
- Vein "staring" fixed: a vein block that was in reach but behind another block was retried forever, because only `oreTarget` was ever
  marked unreachable. Now: in reach but blocked -> dig out the first blocker (max 8 tries per block, never into liquid), then give up
  cleanly on that block. Walking to a target counts as arrived on distance alone; line of sight is solved by digging.
- Trees: leaves in front of a log are broken like any blocker; `scan` prefers blocks reachable from the ground over logs high in the
  crown; "unreachable" marks are forgotten once after 3 failed wander attempts so a new vantage point retries them.
- The "That's N logs for you" message and task end now happen after the climb down.
- Not persisted: if the chunk unloads mid-climb the placed blocks stay where they are.

## Work behaviour round 3 (SeekBlockGoal, new PlantGoal, entity, planner)
- Freeze then teleport to you: an AI plan step ("seek_block 90 s, then come") timed out while the companion was stuck, then `come`
  warped it. Now: (1) a staged watchdog: after 8 s without progress it drops the block it is fixated on and re-decides, after 25 s it
  re-plans (the final "I'm stuck" line names the state: digging / walking / climbing / clearing / planning); (2) real progress extends the
  plan step's deadline; (3) a `come` that belongs to a plan walks back (FOLLOW) instead of warping when you are within 48 blocks.
- Block-by-block teleporting: tunnel steps used the pathfinder, which returns nothing while the mob is still in the air after a step-up, and
  the fallback then warped it. Steps now drive the move control directly (it jumps up one block itself); the warp is only a last resort after
  3 s. Long walks re-request their path up to 4 times instead of giving up (which also wrongly marked good trees "unreachable"); decisions are
  postponed until the companion has landed.
- Reach: measured from the EYES like a player (about 1.6 blocks more upward reach), so fewer blocks need scaffolding at all.
- Climbing without dirt/cobble: surface jobs may use their own chopped logs as temporary scaffolding (last resort), and if there is nothing
  placeable it says so instead of silently giving up. Taking scaffold back no longer counts as "mined".
- Saplings: "pick up the birch saplings" no longer turns into "mine birch_sapling" (it broke the one you placed). Plants/flowers/crops are
  only ever picked up as drops. NEW `plant` order/plan op/voice action: plants saplings from the bag on nearby soil (3 apart, room to grow,
  respects protection), ends by itself. NEW: after felling a trunk base on soil the companion silently replants a matching sapling from its bag.
- Chatter: no more "Got wood! (3/16)" or "Building..."; start, finish and problem lines remain. Progress ("Wood 6/16", Farming, Planting...)
  is synced to the client and shown in the radial menu hub when nothing is hovered.
