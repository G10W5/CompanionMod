# Companion Mod (Minecraft 26.1.2)

A voice-controlled player companion for Fabric and NeoForge. Summon it at a shrine, talk to it with
push-to-talk (local Ollama), order it around with an ARK-style radial wheel, dress it in any
Minecraft player's skin, and let it fight, patrol, wander, mine ore and pick things up.

## Features

### Talking to it
- **Hold `V`** to talk (push-to-talk). Release to send the recording to your local Ollama.
- The model hears the audio, answers in character, and picks an action.
- Replies appear as **AAA floating subtitles above the companion's head**: each line is built "by
  parts" — a coloured speaker badge slides in first, then the message lines fade in one after another
  — with a background pane and a crisp 8× outline, floating up and fading out over ~5 seconds (synced
  to every client, never chat).
- The **name tag only shows while your crosshair is on the companion**.
- Chat history (12 turns), world context and long-term memory are all included in the prompt.

### Personalization (who your companion is)
- **Radial → Personalize** or **Settings → Personalize Companion** opens the profile editor:
  name, who they are (backstory/role), personality, and comma-separated traits.
- Everything is editable at runtime; the name is synced to the server, the rest shapes every reply.

### What it knows about the world
Every request sends a compact snapshot: dimension, time, weather, biome, your health/hunger/held
item/position, the companion's own mode/stance/health/skin, whether it is downed, what it is holding
and how full its bag is, what you are looking at, nearby entities with distance and compass
direction, notable nearby blocks, and hostile mob count.

### Memory
- `config/companionmod-memory.json` stores **facts** you asked it to remember and a rolling log of
  **observed events** ("player attacked Zombie", "night fell", "player picked up iron ore").
- Facts and recent events are appended to the prompt, so it remembers across sessions.
- Toggle with the `Memory` button in settings.

### Orders it can take
Movement: `follow`, `stay`, `sit`, `stand`, `come`, `stop`, `patrol`, `wander`, `explore`.
Combat: `attack`, `guard` (aggressive), `defensive`, `passive`, `fight`, `flee`, `retreat`, `defend`.
Items: `pickup` (walks to the dropped item and stores it — **never opens the inventory**),
`loot`, `drop`, `give`, `take`, `inventory` (opens the bag).
Mining: `seek_block` — walks to the nearest matching block and actually breaks it
(`diamond_ore`, `coal_ore`, `iron_ore`, `gold_ore`, `redstone_ore`, `lapis_ore`, `emerald_ore`,
`copper_ore`, `ancient_debris`, plus aliases like "diamonds"/"coal"). Deep ores trigger a safe
1×1 dig shaft straight down (stops at lava/water/bedrock), then it teleports back to you.
Appearance: `set_skin`, `feminine`, `masculine`, `set_name`.
Downed: `revive` (the player also revives by **holding left click** on it for 3 s).

### "Attack this creeper" / "pick up this bone" / "find diamonds"
The model puts the mob, item or block name in `target`, and the companion resolves it:
- **attack** → nearest matching living entity by registry id, display name or custom name;
  with no target it picks the enemy you are aiming at, then the nearest one.
- **pickup** → walks to the nearest matching dropped item on the ground and stores it
  (filter by item id or name, e.g. "bone").
- **seek_block** → walks to the nearest matching block and mines it (see above).

### Companion inventory (armor + tools + storage)
- 26-slot bag: **4 armor slots** (auto-worn, so they render and give armor toughness/hearts),
  **4 tool slots** (the first is held in the companion's hand), and **18 storage slots**.
- Open it with the **Inventory** radial entry or `/companion do inventory`. Armor/tools you place
  there are worn automatically; items you drop in storage stay in the bag (and persist with the
  entity). The menu also shows your own inventory for convenience.

### Downed / revive
- Any lethal hit (even `/kill`) instead **knocks the companion down**: it plays Minecraft's crawl
  animation (the swimming pose on land), can't move or fight, and a red countdown appears above it.
- **Hold left click on it for 3 seconds** to revive it; the countdown pauses while you hold.
- If it isn't revived within **60 seconds**, it bleeds out and drops the bag contents.

### Being a real pal (personality)
- The default `temperature` is now 0.7 and the prompt tells the model to "talk like a loyal friend"
  (warm, casual, reactive) instead of stating dry facts. Tune temperature in Settings → AI Connection.
- Your name/backstory/personality/traits (Personalize screen) are part of every prompt.
- If the default `lfm3-audio` model still sounds too clipped, pick a chattier model in AI Connection
  (e.g. a qwen3 / llama / deepseek text model) and set an STT model, or any audio-native model.

### AI plans & autonomy (smarter brain)
- **Give Goal** (radial → AI → Give Goal, or `O` → AI Connection) lets you type any open-ended objective
  ("gather wood, then come back"); the model turns it into a validated plan (`follow`, `seek_block`,
  `pickup`, `attack`, `say`, ...) that the server re-runs safely, one step at a time.
- **Dynamic Goals**: when you chat with it and it's not a direct order, it quietly asks the model whether a plan
  is warranted (no extra chatter if there isn't).
- **Autonomy**: toggles on-by-default. Every ~30 s (configurable) the companion looks at the situation on its own
  and may start a small, safe plan (pick up nearby items, follow you, guard if hostiles appear) — it never mines
  or starts fights without being asked. A manual order always cancels it and blocks autonomy for 60 s.
- **Think Now**: makes the companion reason about the current situation immediately.
- Plans use the same `chatModel` as conversation; set an `sttModel` to drive them with any text LLM.

### ARK-style radial wheel (`R`) with submenus
Root wheel: Follow, Stay, Sit, Movement ▸, Behavior ▸, Work ▸, AI ▸, More ▸.
Submenus open a second wheel (e.g. **Behavior → Aggressive / Defensive / Passive / Attack**,
**Work → Pick up / Find & Mine / Inventory / Drop**, **Appearance → Skin Library / Feminine /
Masculine / Body Type**). Wedges are drawn as true annular sectors with hover highlight, icons and a
hint line. Click a segment, centre/right-click/Backspace to go back, `R`/`ESC` to close.

### Skin library (Minecraft Comes Alive style)
- Type any Minecraft player name; the skin is resolved through Mojang + the vanilla `SkinManager` via
  the **`PlayerSkinRenderCache`** (the same path Minecraft uses for player heads), so skins actually
  download and stick. The bundled texture is the fallback until it loads.
- `owner` copies your own skin.
- The companion uses the **player model**, so 64×64 skins work in **Classic (wide) or Slim (Alex)
  form**: the slim model is auto-detected from the skin, or forced with the **Body Type** toggle
  (Auto / Slim / Classic) in settings, the skin library, or the radial Appearance submenu.

### Patrol, wander, pickup
- `PATROL` walks a ring of 8 waypoints around the anchor (set where you gave the order).
- `WANDER` picks random nearby points around the anchor and strolls between them.
- `PICKUP` with a target walks to that dropped item; the general pickup toggle hoovers up every
  nearby drop. Contents persist with the entity (saved in the entity NBT).

### Female Gender Mod (Wildfire)
- Detected at runtime by mod id `female_gender_mod` (legacy `wildfire_gender`) — no hard dependency.
- When present, the companion's built-in **feminine chest overlay** is enabled by default; the toggle
  in settings / skin library / radial Appearance overrides it.
- Note: Wildfire's public API only attaches breast rendering to players, mannequins and armor stands
  (`WildfireClientAPI.getConfig` returns null for other entities), so the companion renders its own
  overlay on the vanilla player model using the companion's skin texture.

### Connecting a smarter AI model
Settings → **AI Connection** (or `config/companionmod-client.json`):
- `host` — Ollama server (default `http://127.0.0.1:11434`).
- `chatModel` — the brain. Any Ollama model works in one of two ways:
  1. **Audio-native model** (e.g. `lfm3-audio`): receives the mic recording directly as `input_audio`
     and answers with the JSON order object.
  2. **Text LLM + STT**: set `sttModel` (e.g. `whisper`, or any OpenAI-compatible
     `/v1/audio/transcriptions` server — `sttHost` overrides the base URL). The recording is
     transcribed first, then the text model decides. This is the most compatible path and works with
     any smart text model (qwen3, llama, deepseek, …).
- `speechModel` — spoken replies via `/v1/audio/speech` (auto-detected from model capabilities;
  `fallbackSpeechModel` = `phonon`). `speechVoice` picks the voice, `speechVolume` the loudness.
  **Detect voices** (AI Connection) asks the server which voices the selected speech model supports and
  lets you pick one from the list instead of typing the id.
- `temperature` (default 0.7), `historyTurns`, `timeoutSeconds` tune the conversation.

The mod is model-agnostic: as long as Ollama reports the model (or the STT+text pair) works, the
companion hears, thinks, talks and acts. The startup probe prints exactly which path it took.

## Keys

| Key | Action |
| --- | --- |
| `V` | hold to talk (real, rebindable `KeyMapping` on NeoForge and Fabric) |
| `R` | radial menu |
| `O` | settings |

`R`/`O` are polled through GLFW and ignored while any screen is open; `V` is ignored while typing
in chat or a screen.

## Commands

```
/companion shrine                 place a shrine at your feet
/companion summon                 summon your companion
/companion do <action> [target]   e.g. /companion do seek_block diamond_ore
/companion inventory              open your companion's bag
/companion skin <playerName>      wear that player's skin
/companion feminine <true|false>  toggle the feminine chest model
/companion name <name>            rename the companion
/companion find <block>           go and mine that block (e.g. /companion find coal)
/companion revive                 revive a downed companion (testing)
```

## Config (`config/companionmod-client.json`)

Key fields: `host`, `chatModel`, `sttModel`, `sttHost`, `speechModel`, `speechVoice`,
`speechVolume`, `speakReplies`, `temperature`, `companionName`, `companionDescription`,
`companionPersonality`, `companionTraits`, `companionSkin`, `feminineModel`, `autoFeminine`,
`companionBody`, `memoryEnabled`, `memoryMaxEvents`, `historyTurns`, `maxRecordSeconds`,
`silenceGate`.

## Build

```
.\gradlew.bat :fabric:build :neoforge:build --console=plain
```

- Java 25 (`gradle.properties` pins a JDK 25 install).
- Outputs: `fabric/build/libs/companionmod-0.1.1.jar`, `neoforge/build/libs/companionmod-0.1.1.jar`.
- Fabric client classes live in `fabric/src/client/java` (split source sets) and are folded into
  the jar by `jar`/`shadowJar` in `fabric/build.gradle`.
