# All Java Sources

Generated from 72 files. Paths are relative to the project root.

## common\src\main\java\com\example\companionmod\block\CompanionShrineBlock.java

```java
package com.example.companionmod.block;

import com.example.companionmod.world.ShrineSummoner;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shrine: ONE block with a 3D model (plinth, step, pillar, cup with four prongs, floating crystal).
 * Right-click to summon your companion, or call it back if it is already out there.
 * The hitbox below follows the model in models/block/shrine_core.json.
 */
public class CompanionShrineBlock extends Block {
    public static final MapCodec<CompanionShrineBlock> CODEC = simpleCodec(CompanionShrineBlock::new);

    private static final VoxelShape SHAPE = Shapes.or(
            Block.box(1, 0, 1, 15, 2, 15),     // plinth
            Block.box(3, 2, 3, 13, 4, 13),     // step
            Block.box(5, 4, 5, 11, 10, 11),    // pillar
            Block.box(4, 10, 4, 12, 11, 12),   // cup
            Block.box(6, 11, 6, 10, 15, 10));  // crystal + prongs (rounded up to one box)

    public CompanionShrineBlock(Properties properties) { super(properties); }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) ShrineSummoner.summon(sl, pos, sp);
        return InteractionResult.SUCCESS;
    }

    /** Little motes rising off the crystal (client side only; this method is only ever called by the client). */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(5) != 0) return;
        level.addParticle(ParticleTypes.END_ROD,
                pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.3,
                pos.getY() + 0.95,
                pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.3,
                0.0, 0.02, 0.0);
    }
}
```

## common\src\main\java\com\example\companionmod\client\ClientVoice.java

```java
package com.example.companionmod.client;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.net.CommandPayload;
import com.example.companionmod.voice.OllamaVoiceBackend;
import com.example.companionmod.voice.PushToTalkController;
import com.example.companionmod.voice.ReplyParser;
import com.example.companionmod.voice.SpeechPlayer;
import com.example.companionmod.voice.VoiceConfig;
import com.example.companionmod.voice.VoiceReply;
import com.example.companionmod.voice.VoiceState;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Client glue shared by both loaders. Loaders only need to: call setSender, register talkKey(), call tick() every client tick,
 * and call renderHud() from their HUD hook.
 */
public final class ClientVoice {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static KeyMapping talkKey;
    private static KeyMapping radialKey;
    private static KeyMapping settingsKey;
    private static KeyMapping.Category category;
    private static PushToTalkController controller;
    private static VoiceConfig cfg;
    private static Path configFile;
    private static boolean wasDown;
    private static boolean radialWasDown;
    private static boolean settingsWasDown;
    private static int syncedEntity = -1;
    private static int cachedCompanionId = -1;
    private static long nextCompanionScan;
    private static int lastSpeechId = -1;
    private static int pendingSpeechId = -1;
    private static String pendingSpeechLine = "";
    private static String syncedSkin = "";
    private static String syncedName = "";
    private static boolean syncedFeminine;
    private static Consumer<CommandPayload> sender = p -> { };

    static volatile String subtitle = "";
    static volatile long subtitleUntil;

    private static final SpeechPlayer.Listener SPEECH_LISTENER = new SpeechPlayer.Listener() {
        @Override public void onStart(String text, long durationMillis) {
            CompanionSubtitles.audioStarted(text, durationMillis);
        }
        @Override public void onProgress(String text, float progress) {
            CompanionSubtitles.audioProgress(text, progress);
        }
        @Override public void onStop(String text) { CompanionSubtitles.audioStopped(text); }
        @Override public void onUnavailable(String text) { CompanionSubtitles.fallback(text); }
    };

    private ClientVoice() {}

    public static void setSender(Consumer<CommandPayload> s) { sender = s; }

    /** One shared Controls category for all companionmod keys (registered lazily, exactly once). */
    private static KeyMapping.Category category() {
        if (category == null) category = KeyMapping.Category.register(CompanionMod.id("main"));
        return category;
    }

    public static KeyMapping talkKey() {
        if (talkKey == null) {
            // 26.1-CHECK: KeyMapping needs a Category since 1.21.9
            talkKey = new KeyMapping("key.companionmod.talk", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category());
        }
        return talkKey;
    }

    /** Opens the radial wheel (was hardcoded GLFW_KEY_R). Registered + rebindable in Controls. */
    public static KeyMapping radialKey() {
        if (radialKey == null) {
            radialKey = new KeyMapping("key.companionmod.radial", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category());
        }
        return radialKey;
    }

    /** Opens the settings screen (was hardcoded GLFW_KEY_O). Registered + rebindable in Controls. */
    public static KeyMapping settingsKey() {
        if (settingsKey == null) {
            settingsKey = new KeyMapping("key.companionmod.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category());
        }
        return settingsKey;
    }

    public static VoiceConfig config() {
        if (cfg == null) init(Minecraft.getInstance());
        return cfg;
    }

    public static Path configFile() {
        if (configFile == null) init(Minecraft.getInstance());
        return configFile;
    }

    public static void send(String action, String say, String target) {
        CompanionCommand command = CompanionCommand.parse(action);
        if (command.interruptsPlanning() || command == CompanionCommand.CONTINUE) {
            // Invalidate in-flight model requests before the server applies the player's newer intent.
            CompanionAutonomy.onManualOrder();
        }
        // Clamp to the packet field limits so a long model/user string can never break encoding.
        sender.accept(new CommandPayload(
                ReplyParser.truncate(action == null ? "" : action, 32),
                ReplyParser.truncate(say == null ? "" : say, 500),
                ReplyParser.truncate(target == null ? "" : target, 1024)));
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            if (controller != null) { if (wasDown) controller.onRelease(); controller.shutdown(); controller = null; }
            wasDown = false;
            radialWasDown = false;
            settingsWasDown = false;
            syncedEntity = -1;
            lastSpeechId = -1;
            cachedCompanionId = -1;
            CompanionSubtitles.clear();
            CompanionAutonomy.reset();
            return;
        }
        if (controller == null) init(mc);
        boolean down = mc.screen == null && talkKey().isDown();       // typing "v" in chat must not open the mic
        if (down && wasDown && controller.state() == VoiceState.LISTENING
                && controller.listeningMillis() > cfg.maxRecordSeconds * 1000L) {
            controller.onRelease();             // recording cap reached: send what we have (release and press again to talk more)
        }
        if (down && !wasDown) controller.onPress();
        else if (!down && wasDown) controller.onRelease();
        wasDown = down;

        CompanionEntity companion = findCompanion(mc);
        if (companion != null) {
            com.example.companionmod.voice.FemaleGenderCompat.tickPhysics(companion);   // our holder isn't ticked by WGM
            CompanionMemory.tick(mc, companion);
            syncEntitySettings(mc, companion);
            handleReviveInput(mc, companion);
            CompanionAutonomy.tick(mc, controller == null || controller.state() == VoiceState.IDLE);
            int sid = companion.speechId();                       // every synced line is spoken, not just subtitled
            if (sid != lastSpeechId) {
                lastSpeechId = sid;
                String line = companion.speech();
                if (!line.isBlank() && controller != null) {
                    if (controller.state() == VoiceState.IDLE) controller.speakText(line);
                    else { pendingSpeechId = sid; pendingSpeechLine = line; }   // don't talk over the mic
                }
            }
            if (pendingSpeechId != -1 && controller != null && controller.state() == VoiceState.IDLE) {
                controller.speakText(pendingSpeechLine);
                pendingSpeechId = -1;
                pendingSpeechLine = "";
            }
        }

        if (mc.screen == null) {
            boolean radial = radialKey().isDown();                    // 26.1: real KeyMapping, rebindable in Controls
            if (radial && !radialWasDown && companion != null) mc.setScreen(new CompanionRadialScreen());
            radialWasDown = radial;

            boolean settings = settingsKey().isDown();
            if (settings && !settingsWasDown) mc.setScreen(new CompanionSettingsScreen(config(), configFile()));
            settingsWasDown = settings;
        } else {
            radialWasDown = true;       // don't re-open as soon as the screen closes
            settingsWasDown = true;
        }
    }

    private static void init(Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve("config");
        configFile = dir.resolve("companionmod-client.json");
        cfg = VoiceConfig.load(configFile);
        CompanionMemory.configure(cfg, dir.resolve("companionmod-memory.json"));
        controller = new PushToTalkController(new OllamaVoiceBackend(cfg), cfg, () -> buildContext(mc),
                ClientVoice::onReply, ClientVoice::status, SPEECH_LISTENER);
        CompanionAutonomy.init(cfg);
    }

    /** Worker thread -> hop to the game thread. */
    public static void onReply(VoiceReply r) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            LOG.info("[companionmod] voice reply: heard='{}' jsonCmd={} target='{}'",
                    r.heard(), r.command(), r.target());
            if (!r.heard().isBlank()) { subtitle = "You: " + r.heard(); subtitleUntil = System.currentTimeMillis() + 4000; }
            // Hard-stop phrases are deterministic. Safety/control must not depend on the LLM's JSON choice.
            CompanionCommand command = isHardStopPhrase(r.heard()) ? CompanionCommand.STOP : gateCommand(r.command(), r.heard());
            String target = r.target();
            if (command == CompanionCommand.NONE) {
                // Model talked but forgot the JSON action: plain order words still work. Mining / pickup orders
                // also carry their material ("find me some coal" -> seek_block coal_ore / pickup filter "coal").
                command = heardOrder(r.heard());
                if ((command == CompanionCommand.SEEK_BLOCK || command == CompanionCommand.PICKUP) && target.isBlank()) {
                    String mat = heardMaterial(r.heard());
                    if (mat == null) mat = heardBlockId(r.heard());   // any registered block id, modded included ("find me zanite ore")
                    if (mat != null) target = mat;
                    if (command == CompanionCommand.SEEK_BLOCK && mat == null) command = CompanionCommand.NONE;   // no material named: not a mine order
                }
            }
            // "dig up to the surface" must never become a blind mining order: the model loves to invent an ore
            // target for it. Surface request with no material named -> the real SURFACE order (staircase up).
            if (command == CompanionCommand.SEEK_BLOCK && surfaceRequest(r.heard())) command = CompanionCommand.SURFACE;
            // "Get wood" used to arrive with the model's oak_leaves choice: what the player actually NAMED in
            // the sentence wins over the model's concrete block pick, so a family word stays a family order
            // ("wood" -> any log) and "find me zanite ore" keeps the real id.
            if (command == CompanionCommand.SEEK_BLOCK) {
                String named = heardMaterial(r.heard());
                if (named == null || named.isBlank()) named = heardBlockId(r.heard());
                if (named != null && !named.isBlank()) target = named;
            }
            LOG.info("[companionmod] voice decision: cmd={} target='{}'", command, target);
            if (!r.memory().isBlank()) CompanionMemory.remember(r.memory());
            if (command == CompanionCommand.REMEMBER)
                CompanionMemory.remember(target.isBlank() ? r.say() : target);
            if (command == CompanionCommand.FORGET)
                CompanionMemory.forget(target.isBlank() ? r.say() : target);
            if (command != CompanionCommand.NONE || !r.say().isBlank() || !target.isBlank())
                send(command.name(), r.say(), target == null ? "" : target);
            if (cfg.dynamicGoals && command == CompanionCommand.NONE && !r.heard().isBlank())
                CompanionAutonomy.onVoiceHeard(r.heard());
        });
    }

    private static boolean isHardStopPhrase(String heard) {
        if (heard == null) return false;
        String s = heard.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", " ").trim();
        return switch (s) {
            case "stop", "stop everything", "stop working", "stop mining", "cancel", "cancel that",
                    "cancel the task", "never mind", "nevermind", "halt", "abort" -> true;
            default -> false;
        };
    }

    /**
     * Last-resort deterministic order detection. A chatty or non-compliant model sometimes answers in prose
     * and forgets the JSON action object, so "kill the zombie" would otherwise stay just talk. Only unambiguous
     * order words (whole words, not substrings) are mapped here; anything fuzzy stays NONE and the companion
     * just replies. The server still resolves targets (attack with no target = what you aim at, then nearest).
     */
    private static CompanionCommand heardOrder(String heard) {
        if (heard == null || heard.isBlank()) return CompanionCommand.NONE;
        String s = heard.toLowerCase(java.util.Locale.ROOT);
        if (words(s, "follow", "come along", "come with me")) return CompanionCommand.FOLLOW;
        if (words(s, "sit")) return CompanionCommand.SIT;
        if (words(s, "stay", "hold position", "wait here", "wait")) return CompanionCommand.STAY;
        if (words(s, "stand", "stand up", "get up")) return CompanionCommand.STAND;
        if (words(s, "to the surface", "the surface", "surface", "daylight", "to the top", "reach the top")) return CompanionCommand.SURFACE;
        if (words(s, "come", "come here", "to me", "over here")) return CompanionCommand.COME;
        if (words(s, "attack", "kill", "fight", "hit", "smash", "destroy")) return CompanionCommand.ATTACK;
        if (words(s, "guard", "protect", "defend")) return CompanionCommand.GUARD;
        if (words(s, "patrol")) return CompanionCommand.PATROL;
        if (words(s, "wander", "roam")) return CompanionCommand.WANDER;
        if (words(s, "pick up", "pickup", "grab", "collect")) return CompanionCommand.PICKUP;
        if (words(s, "find", "mine", "dig", "get", "gather", "seek", "chop", "search", "look for")) return CompanionCommand.SEEK_BLOCK;
        if (words(s, "drop")) return CompanionCommand.DROP;
        if (words(s, "inventory", "bag")) return CompanionCommand.INVENTORY;
        if (words(s, "heal", "bandage")) return CompanionCommand.HEAL;
        return CompanionCommand.NONE;
    }

    /** True when the heard sentence asks for the surface/daylight and names no material ("dig up to the surface"). */
    private static boolean surfaceRequest(String heard) {
        if (heard == null || heard.isBlank()) return false;
        String s = heard.toLowerCase(java.util.Locale.ROOT);
        if (!(s.contains("surface") || s.contains("daylight"))) return false;
        return heardMaterial(heard) == null && heardBlockId(heard) == null;   // a named block ("dig up zanite ore") stays a mining order
    }

    /** Whole-word match for any of the phrases (padded so "sit" never matches "situation"). */
    private static boolean words(String text, String... phrases) {
        for (String p : phrases) {
            if (java.util.regex.Pattern.compile("(?<![a-z])" + java.util.regex.Pattern.quote(p) + "(?![a-z])")
                    .matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * First block / material word in what the player said, as a block id or family id
     * ("find me some coal" -> "coal_ore", "get wood" -> "wood", "some diamonds" -> "diamond_ore").
     * Null when nothing in the sentence names a block: a bare "find something" must not become a blind mine order.
     */
    private static String heardMaterial(String heard) {
        if (heard == null || heard.isBlank()) return null;
        for (String tok : heard.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]+", " ").split(" ")) {
            if (tok.isBlank()) continue;
            String id = CompanionEntity.normalizeBlockId(tok);   // fillers/unknown words come back blank
            if (!id.isBlank()) return id;
        }
        return null;
    }

    /**
     * Longest registered block path contained in what the player said - modded ids included ("find me zanite ore"
     * -> "zanite_ore"). Only runs when the vanilla material vocabulary missed, so the registry scan is rare.
     */
    private static String heardBlockId(String heard) {
        if (heard == null || heard.isBlank()) return null;
        String s = heard.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        String best = null;
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            String p = id.getPath();
            if (p.length() < 3) continue;
            if (s.contains(p) && (best == null || p.length() > best.length())) best = p;
        }
        return best;
    }

    /**
     * Deterministic backstop against a chatty small model: it sometimes invents chores ("dig diamonds",
     * "pick up stone bricks") from greetings or garbled STT. A gameplay command only reaches the server when
     * the player's actual words support it. Movement, modes, identity and memory commands are always allowed;
     * anything else needs a hint word from the heard text.
     */
    private static CompanionCommand gateCommand(CompanionCommand command, String heard) {
        if (command == null || command == CompanionCommand.NONE) return command;
        String s = heard == null ? "" : heard.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9 ]+", " ").replaceAll(" +", " ").trim();
        if (s.isEmpty()) return CompanionCommand.NONE;
        String[] hints = hints(command);
        if (hints == null) return command;                      // unlisted: trust the model
        for (String hint : hints) if (s.contains(hint)) return command;
        return CompanionCommand.NONE;                           // unsupported -> just talk, no action
    }

    /** Words that must appear in what the player said for the command to be real. Null = always allowed. */
    private static String[] hints(CompanionCommand c) {
        return switch (c) {
            case NONE, FOLLOW, STAY, SIT, STAND, GUARD, DEFENSIVE, PASSIVE, DEFEND, STOP, CONTINUE,
                    INVENTORY, SET_NAME, SET_SKIN, FEMININE, MASCULINE, REMEMBER, FORGET, REVIVE, PLAN, WAIT -> null;
            case ATTACK -> new String[]{"attack", "kill", "fight", "hit", "smash", "destroy", "creeper", "zombie",
                    "skeleton", "spider", "enderman", "witch", "slime", "enemy", "monster", "mob", "pillager",
                    "ravager", "boss", "dragon", "wither"};
            case COME -> new String[]{"come", "here", "teleport", "to me"};
            case MINE -> new String[]{"mine", "dig", "break", "chop"};
            case BUILD -> new String[]{"build", "construct", "place", "house", "wall", "tower", "bridge", "schematic"};
            case FARM -> new String[]{"farm", "harvest", "crops", "plant", "wheat", "carrot", "potato", "pumpkin", "melon"};
            case PLANT -> new String[]{"plant", "replant", "sapling", "saplings", "tree", "trees", "forest", "propagule"};
            case SURFACE -> new String[]{"surface", "daylight", "to the surface", "dig up", "up to the top"};
            case PATROL -> new String[]{"patrol", "scout", "scouting", "lookout"};
            case HEAL -> new String[]{"heal", "healing", "health", "bandage"};
            case EQUIP -> new String[]{"equip", "wear", "switch", "pickaxe", "axe", "sword", "shovel", "hoe",
                    "tool", "weapon", "armor", "armour"};
            case TRADE -> new String[]{"trade", "barter", "villager", "merchant", "buy", "sell"};
            case LOOT -> new String[]{"loot", "collect", "gather", "sweep"};
            case WANDER -> new String[]{"wander", "roam", "walk around"};
            case EXPLORE -> new String[]{"explore", "exploration", "check out", "go look"};
            case RETREAT -> new String[]{"retreat", "run away", "escape", "fall back"};
            case SNEAK -> new String[]{"sneak", "stealth", "sneaking"};
            case JUMP -> new String[]{"jump", "leap"};
            case DANCE -> new String[]{"dance", "celebrate", "party"};
            case SLEEP -> new String[]{"sleep", "nap", "bed"};
            case WAKE -> new String[]{"wake", "awake", "wake up"};
            case GIVE -> new String[]{"give", "hand over", "hand me"};
            case TAKE -> new String[]{"take", "grab", "hold"};
            case DROP -> new String[]{"drop", "throw", "toss"};
            case PICKUP -> new String[]{"pick up", "pickup", "grab", "collect"};
            case OPEN -> new String[]{"open"};
            case CLOSE -> new String[]{"close", "shut"};
            case CRAFT -> new String[]{"craft", "crafting", "recipe", "make"};
            case SMELT -> new String[]{"smelt", "smelting", "furnace"};
            case BREW -> new String[]{"brew", "brewing", "potion"};
            case ENCHANT -> new String[]{"enchant", "enchanting", "enchantment"};
            case FISH -> new String[]{"fish", "fishing", "catch fish", "rod"};
            case COOK -> new String[]{"cook", "cooking", "food", "eat"};
            case FIGHT -> new String[]{"fight", "fighting", "combat"};
            case FLEE -> new String[]{"flee", "fleeing", "run away"};
            case HIDE -> new String[]{"hide", "hiding", "conceal"};
            case SEEK -> new String[]{"seek", "look for"};
            case FIND -> new String[]{"find", "locate"};
            case SEARCH -> new String[]{"search", "scan"};
            case SEEK_BLOCK -> new String[]{"mine", "dig", "find", "seek", "search", "look for", "get",
                    "collect", "gather", "wood", "log", "stone", "ore", "diamond", "coal", "iron", "gold",
                    "dirt", "sand", "gravel", "clay", "brick", "block", "plank", "chop"};
            case TRACK -> new String[]{"track", "tracks", "trail"};
            case HUNT -> new String[]{"hunt", "hunting", "stalk", "chase"};
            default -> null;
        };
    }

    public static void status(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Companion] " + message)); });
    }

    /** Find the player's own companion in the currently loaded level (cached: called every tick and every HUD frame). */
    public static CompanionEntity findCompanion(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        String me = mc.player.getStringUUID();
        if (cachedCompanionId != -1) {
            if (mc.level.getEntity(cachedCompanionId) instanceof CompanionEntity c && !c.isRemoved() && c.isOwnedBy(me)) return c;
            cachedCompanionId = -1;
        }
        long now = mc.level.getGameTime();
        if (now < nextCompanionScan) return null;
        nextCompanionScan = now + 10;
        for (Entity e : mc.level.entitiesForRendering()) {                 // 26.1-CHECK: ClientLevel.entitiesForRendering()
            if (e instanceof CompanionEntity c && c.isOwnedBy(me)) { cachedCompanionId = c.getId(); return c; }
        }
        return null;
    }

    /** Push config-driven appearance/identity settings to the server once per entity. */
    private static void syncEntitySettings(Minecraft mc, CompanionEntity companion) {
        String wanted = cfg == null ? "" : cfg.companionSkin;   // "owner" stays symbolic: the renderer resolves it synchronously
        boolean feminine = cfg != null && cfg.feminineModel;
        String name = cfg == null || cfg.companionName == null || cfg.companionName.isBlank() ? "" : cfg.companionName;
        if (companion.getId() == syncedEntity && wanted.equals(syncedSkin) && feminine == syncedFeminine
                && name.equals(syncedName)) return;
        syncedEntity = companion.getId();
        syncedSkin = wanted;
        syncedFeminine = feminine;
        syncedName = name;
        if (!wanted.equalsIgnoreCase(companion.skin())) send("set_skin", "", wanted);
        if (feminine != companion.isFeminine()) send(feminine ? "feminine" : "masculine", "", "");
        if (!name.isBlank() && !name.equals(companion.getName().getString())) send("set_name", "", name);
    }

    private static long lastReviveSend;

    /** Holding right click (interact, like doors) on a downed companion sends revive pulses to the server. */
    private static void handleReviveInput(Minecraft mc, CompanionEntity companion) {
        if (!companion.isInjured() || companion.isDeadOrDying() || mc.screen != null || mc.player == null) return;
        boolean holdingUse = mc.options.keyUse.isDown();
        boolean onIt = mc.crosshairPickEntity == companion || mc.player.distanceToSqr(companion) <= 9.0;
        if (holdingUse && onIt && System.currentTimeMillis() - lastReviveSend > 200) {
            lastReviveSend = System.currentTimeMillis();
            ClientVoice.send("revive", "", "");
        }
    }

    /** Game-thread snapshot handed to the model so replies match the situation. */
    private static String buildContext(Minecraft mc) {
        String context = CompanionWorldContext.build(mc, findCompanion(mc));
        String memory = CompanionMemory.summary();
        return memory.isBlank() ? context : context + "\nPlayer preferences and past events: " + memory;
    }

    public static void renderHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        VoiceHud.render(g, controller, findCompanion(Minecraft.getInstance()));
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionAutonomy.java

```java
package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.PlanParser;
import com.example.companionmod.voice.PlanningBackend;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side goal planner with generation-based cancellation.
 *
 * <p>Every manual gameplay order advances {@link #GENERATION}. A model response is delivered only if
 * its generation is still current, so a slow response can never restart work after STOP or replace a
 * newer order. HTTP calls may finish in the background, but their results become harmless.</p>
 */
public final class CompanionAutonomy {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "companionmod-planner");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong GENERATION = new AtomicLong();

    private static VoiceConfig cfg;
    private static PlanningBackend backend;
    private static volatile long nextThinkAt;
    private static volatile boolean busy;
    private static volatile String goal = "";
    private static volatile long goalUntil;

    private CompanionAutonomy() {}

    public static void init(VoiceConfig config) {
        cfg = config;
        backend = new PlanningBackend(config);
        reset();
    }

    /** Label of the plan currently running (empty when none). For the wheel hub / HUD. */
    public static String currentGoal() {
        return System.currentTimeMillis() < goalUntil ? goal : "";
    }

    /** Call every client tick. {@code voiceIdle} = push-to-talk pipeline is idle. */
    public static void tick(Minecraft mc, boolean voiceIdle) {
        if (cfg == null || backend == null || !cfg.autonomyEnabled || busy || !voiceIdle) return;
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        long now = System.currentTimeMillis();
        if (now < nextThinkAt) return;
        nextThinkAt = now + Math.max(15, cfg.autonomyIntervalSeconds) * 1000L
                + (long) (Math.random() * 5000L);
        final String ctx = context(mc);
        final long ticket = GENERATION.get();
        busy = true;
        WORKER.execute(() -> {
            try {
                deliver(backend.think(ctx), true, ticket);
            } catch (Exception ignored) {
                // Autonomy is best effort and silent.
            } finally {
                finish(ticket);
            }
        });
    }

    /** Spoken request that was not a direct order. Greetings, small talk and bare orders are NOT goals. */
    public static void onVoiceHeard(String heard) {
        if (cfg == null) return;
        if (!cfg.dynamicGoals) {
            LOG.info("[companionmod] goals: spoken goal ignored - Dynamic Goals is OFF in settings");
            return;
        }
        if (heard == null || heard.isBlank()) return;
        if (!plannable(heard)) {
            LOG.info("[companionmod] goals: not a goal: '{}'", heard);
            return;
        }
        LOG.info("[companionmod] goals: planning from voice: '{}'", heard);
        requestGoal(heard, true);
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private static final Set<String> GREETINGS = Set.of(
            "hello", "hi", "hey", "yo", "hiya", "howdy", "thanks", "thank you", "bye", "goodbye",
            "good night", "good morning", "good evening", "welcome", "nice to meet you",
            "how are you", "how are u", "whats up", "what is up", "sup");

    /** Words the command parser already owns: a bare one is an order, not a request to plan around. */
    private static final Set<String> ORDER_WORDS = Set.of(
            "stay", "stop", "come", "follow", "sit", "stand", "wait", "hold", "defend", "guard",
            "attack", "fight", "hunt", "wander", "patrol", "explore", "scout", "loot", "mine", "dig",
            "farm", "fish", "build", "equip", "drop", "give", "store", "heal", "revive", "continue",
            "cancel", "halt", "abort", "leave", "dismiss", "call", "summon", "pick", "get", "bring",
            "take", "collect", "seek", "find", "search");

    /** Particles that glue bare orders together: "come to me", "stay with me", "follow me". */
    private static final Set<String> ORDER_PARTICLES = Set.of(
            "me", "my", "with", "to", "here", "there", "up", "down", "out", "it", "please", "now", "back", "along");

    /** Cheap client-side intent filter so chitchat and misheard orders never reach the planner. */
    private static boolean plannable(String heard) {
        String s = heard.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9 ]+", " ").replaceAll(" +", " ").trim();
        if (s.length() < 5) return false;                                  // "hi", "ok", "no"
        if (GREETINGS.contains(s)) return false;
        String[] words = s.split(" ");
        boolean allOrderish = true;
        for (String w : words) {
            if (!ORDER_WORDS.contains(w) && !ORDER_PARTICLES.contains(w)) { allOrderish = false; break; }
        }
        if (allOrderish) return false;                                     // "come to me" is an order, not a goal
        if (words.length <= 2 && ORDER_WORDS.contains(words[0])) return false;  // bare "stay", "come here"
        return true;
    }

    public static void requestGoal(String text) {
        requestGoal(text, false);
    }

    public static void requestGoal(String text, boolean quiet) {
        Minecraft mc = Minecraft.getInstance();
        if (cfg == null || backend == null || mc.player == null || mc.level == null) return;
        if (busy) {
            if (!quiet) chat(mc, "Still thinking about the last one.");
            return;
        }
        final String ctx = context(mc);
        final long ticket = GENERATION.incrementAndGet();
        busy = true;
        nextThinkAt = System.currentTimeMillis() + 60_000L;
        WORKER.execute(() -> {
            try {
                PlanParser.Plan plan = backend.planGoal(text, ctx);
                if (plan.isEmpty()) {
                    // Spoken goals (quiet) used to fail SILENTLY - "voice goals never trigger" with no clue why.
                    if (isCurrent(ticket)) {
                        mc.execute(() -> {
                            if (isCurrent(ticket)) chat(mc, "I'm not sure how to do that.");
                        });
                    }
                } else {
                    LOG.info("[companionmod] goals: plan accepted ({} steps)", plan.steps().size());
                    deliver(plan, false, ticket);
                }
            } catch (Exception e) {
                String message = e.getMessage() == null ? "unknown error" : e.getMessage();
                LOG.warn("[companionmod] goals: planning FAILED: {}", message);
                if (isCurrent(ticket)) {
                    mc.execute(() -> {
                        if (isCurrent(ticket)) chat(mc, "Planning failed: " + message);
                    });
                }
            } finally {
                finish(ticket);
            }
        });
    }

    /**
     * Invalidate pending requests and suppress autonomous thinking after a direct player order.
     * Called centrally by ClientVoice.send for gameplay commands, including radial-menu orders.
     */
    public static void onManualOrder() {
        GENERATION.incrementAndGet();
        busy = false;
        goal = "";
        goalUntil = 0;
        nextThinkAt = System.currentTimeMillis() + 60_000L;
    }

    /** The wheel's Cancel Plan action is a real stop, not merely an empty planner packet. */
    public static void cancelPlan() {
        ClientVoice.send("stop", "", "");
    }

    /** Drop all client planning state on logout/world change. */
    public static void reset() {
        GENERATION.incrementAndGet();
        busy = false;
        goal = "";
        goalUntil = 0;
        nextThinkAt = System.currentTimeMillis() + 20_000L;
    }

    private static boolean isCurrent(long ticket) {
        return GENERATION.get() == ticket;
    }

    private static void finish(long ticket) {
        // A stale worker must not clear the busy flag belonging to a newer request.
        if (isCurrent(ticket)) busy = false;
    }

    private static void deliver(PlanParser.Plan plan, boolean auto, long ticket) {
        if (plan.isEmpty() || !isCurrent(ticket)) return;
        Minecraft mc = Minecraft.getInstance();
        final String json = PlanParser.toJson(plan, auto);
        mc.execute(() -> {
            // Check again on the game thread: STOP may have arrived while this task was queued.
            if (!isCurrent(ticket) || mc.player == null || mc.level == null) return;
            goal = plan.goal();
            goalUntil = System.currentTimeMillis() + PlanParser.totalSeconds(plan) * 1000L + 4000L;
            ClientVoice.send("plan", "", json);
            if (!auto && !plan.goal().isBlank()) {
                mc.player.sendSystemMessage(Component.literal("[Companion] Goal: " + plan.goal()));
            }
        });
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[Companion] " + msg));
    }

    /** Game-thread snapshot for the model. */
    private static String context(Minecraft mc) {
        CompanionEntity mine = ClientVoice.findCompanion(mc);
        return CompanionWorldContext.build(mc, mine) + "\nMemory: " + CompanionMemory.summary();
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionDropdowns.java

```java
package com.example.companionmod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Dropdown menus built from vanilla {@link Button}s plus one overlay list drawn on top of everything.
 *
 * <p>The screen owns the header buttons (so they get vanilla styling, hover, narration and keyboard focus);
 * this class only keeps the option lists, draws the open list and swallows the clicks / scrolling that belong to it.
 * Wire it up in the screen with: {@link #extract} at the END of extractRenderState, {@link #mouseClicked} /
 * {@link #mouseScrolled} / {@link #escape} at the START of the matching overrides.</p>
 */
final class CompanionDropdowns {
    static final int ROW_H = 16;
    static final int MAX_ROWS = 7;

    record Option(String value, String label) {}

    static final class Dropdown {
        final String title;
        final Supplier<String> getter;
        final Consumer<String> setter;
        Button button;
        List<Option> options = List.of();
        String emptyText = "Nothing found";
        boolean loading;
        int scroll;
        // optional free-text mode (pencil button): type any value the server list does not contain
        EditBox editor;
        Button editButton;
        boolean editing;

        Dropdown(String title, Supplier<String> getter, Consumer<String> setter) {
            this.title = title;
            this.getter = getter;
            this.setter = setter;
        }
    }

    private record Layout(int x, int y, int w, int h, int rows, int total) {}

    private final Font font;
    private final IntSupplier screenHeight;
    private final List<Dropdown> all = new ArrayList<>();
    private Dropdown open;

    CompanionDropdowns(Font font, IntSupplier screenHeight) {
        this.font = font;
        this.screenHeight = screenHeight;
    }

    /** Forget every dropdown (call at the start of init(): the header buttons are recreated each time). */
    void clear() {
        all.clear();
        open = null;
    }

    void register(Dropdown d) {
        all.add(d);
        refresh(d);
    }

    boolean isOpen() { return open != null; }

    void toggle(Dropdown d) {
        Dropdown previous = open;
        open = null;
        if (previous != null) refresh(previous);
        if (previous != d) {
            open = d;
            List<Option> opts = shown(d);
            int rows = Math.min(Math.max(1, opts.size()), MAX_ROWS);
            int sel = indexOfCurrent(d, opts);
            d.scroll = Math.max(0, Math.min(Math.max(0, opts.size() - rows), sel - rows / 2));
        }
        refresh(d);
    }

    void close() {
        Dropdown d = open;
        open = null;
        if (d != null) refresh(d);
    }

    /** ESC closes an open list first instead of the whole screen. */
    boolean escape() {
        if (open == null) return false;
        close();
        return true;
    }

    void refreshAll() {
        for (Dropdown d : all) refresh(d);
    }

    // ---- labels -------------------------------------------------------------------------------------------------

    /** Options as displayed: the stored value is always selectable even when the server list does not contain it. */
    private List<Option> shown(Dropdown d) {
        String cur = current(d);
        if (cur.isEmpty()) return d.options;
        for (Option o : d.options) if (o.value().equals(cur)) return d.options;
        List<Option> withCur = new ArrayList<>(d.options.size() + 1);
        withCur.add(new Option(cur, cur));
        withCur.addAll(d.options);
        return withCur;
    }

    private static String current(Dropdown d) {
        String v = d.getter.get();
        return v == null ? "" : v;
    }

    private int indexOfCurrent(Dropdown d, List<Option> opts) {
        String cur = current(d);
        for (int i = 0; i < opts.size(); i++) if (opts.get(i).value().equals(cur)) return i;
        return 0;
    }

    void refresh(Dropdown d) {
        if (d.button == null) return;
        String cur = current(d);
        String value = null;
        for (Option o : shown(d)) if (o.value().equals(cur)) { value = o.label(); break; }
        if (value == null) value = d.loading ? "Loading..." : (cur.isEmpty() ? "-" : cur);
        String prefix = d.title + ": ";
        String arrow = open == d ? " \u25B2" : " \u25BC";
        int room = d.button.getWidth() - 12 - font.width(prefix) - font.width(arrow);
        if (room > 12 && font.width(value) > room) {
            value = font.plainSubstrByWidth(value, room - font.width("...")) + "...";
        }
        d.button.setMessage(Component.literal(prefix + value + arrow));
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    private Layout layout(Dropdown d) {
        Button b = d.button;
        int total = Math.max(1, shown(d).size());
        int below = screenHeight.getAsInt() - (b.getY() + b.getHeight()) - 4;
        int above = b.getY() - 4;
        boolean up = total * ROW_H + 2 > below && above > below;
        int room = Math.max(1, ((up ? above : below) - 2) / ROW_H);
        int rows = Math.max(1, Math.min(Math.min(total, MAX_ROWS), room));
        int h = rows * ROW_H + 2;
        int y = up ? b.getY() - h : b.getY() + b.getHeight();
        return new Layout(b.getX(), y, b.getWidth(), h, rows, total);
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    void extract(GuiGraphicsExtractor g, int mx, int my) {
        if (open == null || open.button == null) return;
        g.nextStratum();                                   // 26.1-CHECK: draw above widget text; delete this line if it does not exist
        Dropdown d = open;
        Layout L = layout(d);
        List<Option> opts = shown(d);
        d.scroll = Math.max(0, Math.min(d.scroll, Math.max(0, L.total() - L.rows())));
        int sbw = L.total() > L.rows() ? 6 : 0;

        g.fill(L.x() - 1, L.y() - 1, L.x() + L.w() + 1, L.y() + L.h() + 1, 0xFF000000);
        g.fill(L.x(), L.y(), L.x() + L.w(), L.y() + L.h(), 0xFF1B1B1B);

        if (opts.isEmpty()) {
            g.text(font, d.loading ? "Loading..." : d.emptyText, L.x() + 6, L.y() + 5, 0xFF9A9A9A, true);
        }
        String cur = current(d);
        for (int i = 0; i < L.rows(); i++) {
            int idx = d.scroll + i;
            if (idx >= opts.size()) break;
            Option o = opts.get(idx);
            int ry = L.y() + 1 + i * ROW_H;
            boolean hover = mx >= L.x() && mx < L.x() + L.w() - sbw && my >= ry && my < ry + ROW_H;
            boolean selected = o.value().equals(cur);
            if (hover) g.fill(L.x() + 1, ry, L.x() + L.w() - 1 - sbw, ry + ROW_H, 0xFF505050);
            if (selected) g.fill(L.x() + 1, ry + 2, L.x() + 3, ry + ROW_H - 2, 0xFF55FF55);
            String label = font.plainSubstrByWidth(o.label(), L.w() - 14 - sbw);
            g.text(font, label, L.x() + 7, ry + 4, selected ? 0xFFFFFF55 : 0xFFFFFFFF, true);
        }
        if (sbw > 0) {
            int tx = L.x() + L.w() - sbw, ty = L.y() + 1, th = L.h() - 2;
            g.fill(tx, ty, L.x() + L.w() - 1, ty + th, 0xFF000000);
            int thumb = Math.max(8, th * L.rows() / L.total());
            int maxScroll = Math.max(1, L.total() - L.rows());
            int thumbY = ty + (th - thumb) * d.scroll / maxScroll;
            g.fill(tx + 1, thumbY, L.x() + L.w() - 2, thumbY + thumb, 0xFF9A9A9A);
        }
    }

    // ---- input --------------------------------------------------------------------------------------------------

    /** @return true when the click was consumed by an open list (or closed it). */
    boolean mouseClicked(double mx, double my, int button) {
        if (open == null || open.button == null) return false;
        Dropdown d = open;
        Layout L = layout(d);
        boolean inList = mx >= L.x() && mx < L.x() + L.w() && my >= L.y() && my < L.y() + L.h();
        if (inList) {
            int sbw = L.total() > L.rows() ? 6 : 0;
            if (button == 0 && mx < L.x() + L.w() - sbw) {
                int i = (int) ((my - L.y() - 1) / ROW_H);
                int idx = d.scroll + i;
                List<Option> opts = shown(d);
                if (i >= 0 && i < L.rows() && idx >= 0 && idx < opts.size()) {
                    d.setter.accept(opts.get(idx).value());
                    close();
                }
            }
            return true;
        }
        Button b = d.button;
        if (mx >= b.getX() && mx < b.getX() + b.getWidth() && my >= b.getY() && my < b.getY() + b.getHeight()) {
            return false;                                  // let the header button run its own press handler (toggles closed)
        }
        close();
        return true;
    }

    boolean mouseScrolled(double mx, double my, double scrollY) {
        if (open == null || open.button == null) return false;
        Layout L = layout(open);
        int max = Math.max(0, L.total() - L.rows());
        open.scroll = Math.max(0, Math.min(max, open.scroll - (int) Math.signum(scrollY)));
        return true;
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionFindBlockScreen.java

```java
package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/** Small chooser for "go and find/mine this block" orders. */
public class CompanionFindBlockScreen extends Screen {
    private static final String[][] QUICK = {
            {"Diamond", "diamond_ore"}, {"Coal", "coal_ore"}, {"Iron", "iron_ore"},
            {"Gold", "gold_ore"}, {"Redstone", "redstone_ore"}, {"Lapis", "lapis_ore"},
            {"Emerald", "emerald_ore"}, {"Copper", "copper_ore"}, {"Ancient Debris", "ancient_debris"},
            {"Wood", "log"}, {"Stone", "stone"}, {"Dirt", "dirt"}, {"Planks", "oak_planks"},
    };

    private final VoiceConfig config;
    private final Path configFile;
    private EditBox blockField;
    private String status = "";

    public CompanionFindBlockScreen(VoiceConfig config, Path configFile) {
        super(Component.literal("Find & Mine"));
        this.config = config;
        this.configFile = configFile;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int y = 72;

        this.blockField = new EditBox(this.font, cx - 150, y, 300, 20, Component.literal("Block"));
        this.blockField.setHint(Component.literal("Block id or name (diamond_ore, coal, iron...)"));
        this.blockField.setMaxLength(60);
        this.addRenderableWidget(this.blockField);
        y += 26;

        int col = 0;
        for (String[] entry : QUICK) {
            int bw = 96;
            int bx = cx - 150 + col * 102;
            this.addRenderableWidget(Button.builder(Component.literal(entry[0]), b -> {
                blockField.setValue(entry[1]);
                status = "";
            }).pos(bx, y).size(bw, 20).build());
            col++;
            if (col == 3) { col = 0; y += 24; }
        }
        y += 8;

        this.addRenderableWidget(Button.builder(Component.literal("Find & Mine"), b -> find())
                .pos(cx - 150, y).size(300, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(new CompanionRadialScreen());
        }).pos(cx - 150, y).size(300, 20).build());
    }

    private void find() {
        String id = CompanionEntity.normalizeBlockId(blockField.getValue());
        if (id.isBlank()) {
            status = "Unknown block. Try diamond, coal, iron, gold, emerald...";
            return;
        }
        ClientVoice.send("seek_block", "", id);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        int cx = this.width / 2;
        g.fill(cx - 162, 34, cx + 162, this.height - 28, 0xCC101216);
        g.fill(cx - 162, 34, cx + 162, 35, 0xFF3D7BB8);
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, "Find & Mine", cx, 42, 0xFFFFFFFF);
        g.centeredText(this.font, "The companion walks to the nearest matching block and mines it.", cx, 54, 0xFF9AA6B2);
        if (!status.isBlank()) g.centeredText(this.font, status, cx, this.height - 46, 0xFFFF7777);
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionGoalScreen.java

```java
package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Type any goal in plain words; the AI turns it into a plan. Vanilla layout: title, one field, Done/Cancel in the footer. */
public class CompanionGoalScreen extends Screen {
    private static final String[] IDEAS = {"Collect some wood", "Guard this area", "Find coal and come back", "Look around nearby",
            "Continue what you were doing", "Farm the crops", "Go fishing"};
    private final Screen parent;
    private EditBox field;

    public CompanionGoalScreen(Screen parent) {
        super(Component.literal("Give a Goal"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        field = new EditBox(this.font, cx - 154, 58, 308, 20, Component.literal("Goal"));
        field.setHint(Component.literal("e.g. gather 2 stacks of wood, then come back"));
        field.setMaxLength(160);
        this.addRenderableWidget(field);
        this.setInitialFocus(field);

        for (int i = 0; i < IDEAS.length; i++) {
            final String idea = IDEAS[i];
            this.addRenderableWidget(Button.builder(Component.literal(idea), b -> field.setValue(idea))
                    .pos(cx - 154 + (i % 2) * 158, 88 + (i / 2) * 24).size(150, 20).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Plan It"), b -> submit())
                .pos(cx - 154, this.height - 27).size(150, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .pos(cx + 4, this.height - 27).size(150, 20).build());
    }

    private void submit() {
        String text = field.getValue().trim();
        if (!text.isEmpty()) CompanionAutonomy.requestGoal(text);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        this.extractMenuBackground(g);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        g.centeredText(this.font, "Describe what you want. The AI picks the steps.", this.width / 2, 24, 0xFFA0A0A0);
        g.fill(0, 32, this.width, 33, 0xFF000000);
        g.fill(0, 33, this.width, 34, 0xFF373737);
        g.fill(0, this.height - 33, this.width, this.height - 32, 0xFF000000);
        g.fill(0, this.height - 32, this.width, this.height - 31, 0xFF373737);
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionHumanoidModel.java

```java
package com.example.companionmod.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;

/**
 * Player-shaped model with <b>both</b> arm widths baked in, so classic/slim can be switched per frame
 * without replacing the renderer's model instance.
 *
 * <p>The mesh is built from {@link PlayerModel#createMesh} (not {@link HumanoidModel#createMesh}) so the
 * skin's second layer is complete: hat (head child), jacket (body child), sleeves (arm children) and pants
 * (leg children) all render with the skin texture. HumanoidModel's mesh only carries the hat - which is why
 * skins used to show on the head while the body kept the default look.</p>
 *
 * <p>Why not two models and a swap? {@code AgeableMobRenderer.submit()} overwrites
 * {@code this.model = state.isBaby ? babyModel : adultModel} right before {@code LivingEntityRenderer.submit()},
 * which silently undid every slim swap (the render state said SLIM but the wide model was drawn). Keeping one
 * model instance and hiding the inactive arm pair is immune to that, and it is also correct with 26.1's deferred
 * model submission because {@code setupAnim(state)} runs per submitted entity at draw time.</p>
 *
 * <p>The slim arm boxes, sleeves and pivots are identical to {@code PlayerModel.createMesh(..., true)}:
 * right arm {@code texOffs(40,16) box(-2,-2,-2, 3,12,4)} at {@code (-5,2,0)} with a {@code texOffs(40,32)}
 * 3-wide sleeve, left arm {@code texOffs(32,48) box(-1,-2,-2, 3,12,4)} at {@code (5,2,0)} with a
 * {@code texOffs(48,48)} sleeve. The overlays extend 0.25, exactly like vanilla.</p>
 */
public final class CompanionHumanoidModel extends HumanoidModel<CompanionRenderState> {
    private final ModelPart slimRightArm;
    private final ModelPart slimLeftArm;

    public CompanionHumanoidModel() {
        this(buildRoot());
    }

    private CompanionHumanoidModel(ModelPart root) {
        super(root);
        this.slimRightArm = root.getChild("right_arm_slim");
        this.slimLeftArm = root.getChild("left_arm_slim");
    }

    private static ModelPart buildRoot() {
        // Wide base straight from the player model: head+hat, body+jacket, wide arms+sleeves, legs+pants.
        MeshDefinition mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        PartDefinition root = mesh.getRoot();

        // Slim pair (manually added so one baked mesh holds both widths; the wide pair is hidden per frame).
        PartDefinition rightSlim = root.addOrReplaceChild("right_arm_slim", CubeListBuilder.create()
                        .texOffs(40, 16).addBox(-2.0f, -2.0f, -2.0f, 3.0f, 12.0f, 4.0f),
                PartPose.offset(-5.0f, 2.0f, 0.0f));
        rightSlim.addOrReplaceChild("right_sleeve_slim", CubeListBuilder.create()
                .texOffs(40, 32).addBox(-2.0f, -2.0f, -2.0f, 3.0f, 12.0f, 4.0f, new CubeDeformation(0.25f)),
                PartPose.ZERO);

        PartDefinition leftSlim = root.addOrReplaceChild("left_arm_slim", CubeListBuilder.create()
                        .texOffs(32, 48).addBox(-1.0f, -2.0f, -2.0f, 3.0f, 12.0f, 4.0f),
                PartPose.offset(5.0f, 2.0f, 0.0f));
        leftSlim.addOrReplaceChild("left_sleeve_slim", CubeListBuilder.create()
                .texOffs(48, 48).addBox(-1.0f, -2.0f, -2.0f, 3.0f, 12.0f, 4.0f, new CubeDeformation(0.25f)),
                PartPose.ZERO);

        return root.bake(64, 64);
    }

    @Override
    public void setupAnim(CompanionRenderState state) {
        super.setupAnim(state);
        boolean slim = state.slim;
        this.rightArm.visible = !slim;
        this.leftArm.visible = !slim;
        this.slimRightArm.visible = slim;
        this.slimLeftArm.visible = slim;
        copyPose(this.rightArm, this.slimRightArm);
        copyPose(this.leftArm, this.slimLeftArm);
    }

    private static void copyPose(ModelPart from, ModelPart to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionInventoryScreen.java

```java
package com.example.companionmod.client;

import com.example.companionmod.inventory.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The companion's bag in the grey panel style of the settings screens: armor row, tool row, 2x9 storage, then the
 * player's own inventory. Slot rendering, tooltips and click handling come from {@link AbstractContainerScreen};
 * this class paints the panel, a sunken well behind every slot, and the section labels.
 */
public class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, 176, 196);
        this.titleLabelX = 8;
        this.titleLabelY = 6;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 102;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);   // 26.1-CHECK: vanilla blur/dim; remove if it draws twice
        CompanionUi.panel(g, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight);
        for (Slot s : this.menu.slots) slotWell(g, leftPos + s.x - 1, topPos + s.y - 1);
    }

    /** Vanilla-style sunken 18x18 slot: dark top/left edge, light bottom/right edge, mid-grey fill. */
    private static void slotWell(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF373737);
        g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(this.font, this.title, this.titleLabelX, this.titleLabelY, CompanionUi.TEXT_DARK, false);
        g.text(this.font, Component.literal("Armor"), 8, 17, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, Component.literal("Tools"), 98, 17, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, Component.literal("Storage"), 8, 44, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, CompanionUi.TEXT_DARK, false);
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionMemory.java

```java
package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.VoiceConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight long-term memory + world observer for the companion.
 * Remembers facts the player asks it to keep and a rolling log of things the companion "saw" the
 * player do (attacks, pickups, damage, dimension changes). Stored in config/companionmod-memory.json.
 */
public final class CompanionMemory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final List<String> FACTS = new ArrayList<>();
    private static final Map<String, Integer> LAST_ITEMS = new HashMap<>();

    private static Path file;
    private static VoiceConfig cfg;
    private static int tickCounter;
    private static String lastDimension = "";
    private static float lastHealth = 20f;
    private static int lastFood = 20;
    private static String lastHurtMob = "";
    private static boolean lastDead;
    private static boolean lastNight;

    private CompanionMemory() {}

    public static void configure(VoiceConfig config, Path memoryFile) {
        cfg = config;
        file = memoryFile;
        load();
    }

    public static void tick(Minecraft mc, CompanionEntity companion) {
        if (cfg == null || !cfg.memoryEnabled || mc.player == null || mc.level == null) return;
        if (++tickCounter % 20 != 0) return;

        String dimension = mc.level.dimension().identifier().toString();
        if (!dimension.equals(lastDimension)) {
            if (!lastDimension.isEmpty()) addEvent("entered " + dimension);
            lastDimension = dimension;
            LAST_ITEMS.clear();
            lastHealth = mc.player.getHealth();
            lastFood = mc.player.getFoodData().getFoodLevel();
        }

        if (mc.player.getHealth() + 0.5f < lastHealth)
            addEvent("player took damage (health " + Math.round(mc.player.getHealth()) + "/20)");
        lastHealth = mc.player.getHealth();

        int food = mc.player.getFoodData().getFoodLevel();
        if (food + 1 < lastFood) addEvent("player is getting hungry");
        lastFood = food;

        LivingEntity hurt = mc.player.getLastHurtMob();
        if (hurt != null && hurt.isAlive() && mc.player.tickCount - mc.player.getLastHurtMobTimestamp() < 30) {
            String name = hurt.getName().getString();
            if (!name.equals(lastHurtMob)) {
                addEvent("player attacked " + name);
                lastHurtMob = name;
            }
        }

        boolean dead = mc.player.isDeadOrDying();
        if (dead && !lastDead) addEvent("player died");
        lastDead = dead;

        long time = mc.level.getOverworldClockTime() % 24000;
        boolean night = time >= 13000 && time < 23000;
        if (night != lastNight) {
            if (night) addEvent("night fell");
            lastNight = night;
        }

        diffInventory(mc);

        if (companion != null) {
            // keep the memory file fresh without writing every tick
            if (tickCounter % 600 == 0) save();
        }
    }

    public static void remember(String fact) {
        if (fact == null) return;
        String clean = fact.trim();
        if (clean.isEmpty() || FACTS.stream().anyMatch(f -> f.equalsIgnoreCase(clean))) return;
        FACTS.add(clean);
        while (FACTS.size() > 40) FACTS.remove(0);
        save();
    }

    public static void forget(String fact) {
        if (fact == null || fact.isBlank()) return;
        FACTS.removeIf(f -> f.toLowerCase().contains(fact.toLowerCase().trim()));
        save();
    }

    public static List<String> facts() {
        return List.copyOf(FACTS);
    }

    public static String summary() {
        if (cfg == null || !cfg.memoryEnabled) return "";
        StringBuilder sb = new StringBuilder();
        if (!FACTS.isEmpty()) {
            sb.append("Remembered: ");
            int start = Math.max(0, FACTS.size() - 8);
            for (int i = start; i < FACTS.size(); i++) {
                if (i > start) sb.append("; ");
                sb.append(FACTS.get(i));
            }
            sb.append(". ");
        }
        if (!EVENTS.isEmpty()) {
            sb.append("Recent events: ");
            int count = 0;
            for (String event : EVENTS) {
                if (count++ > 0) sb.append("; ");
                sb.append(event);
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private static void addEvent(String event) {
        EVENTS.addLast(event);
        int max = cfg == null ? 30 : Math.max(5, cfg.memoryMaxEvents);
        while (EVENTS.size() > max) EVENTS.removeFirst();
    }

    private static void diffInventory(Minecraft mc) {
        Map<String, Integer> now = new HashMap<>();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            now.merge(id, stack.getCount(), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : now.entrySet()) {
            int before = LAST_ITEMS.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > before) {
                String name = entry.getKey().substring(entry.getKey().indexOf(':') + 1).replace('_', ' ');
                addEvent("player picked up " + name);
            }
        }
        LAST_ITEMS.clear();
        LAST_ITEMS.putAll(now);
    }

    // ---------------------------------------------------------------- persistence

    private static final class Data {
        List<String> facts = new ArrayList<>();
        List<String> events = new ArrayList<>();
    }

    private static void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            Data data = GSON.fromJson(Files.readString(file), Data.class);
            if (data == null) return;
            FACTS.clear();
            EVENTS.clear();
            if (data.facts != null) FACTS.addAll(data.facts);
            if (data.events != null) EVENTS.addAll(data.events);
        } catch (Exception ignored) { }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Data data = new Data();
            data.facts = new ArrayList<>(FACTS);
            data.events = new ArrayList<>(EVENTS);
            Files.writeString(file, GSON.toJson(data));
        } catch (IOException ignored) { }
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionProfileScreen.java

```java
package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * "Who is my companion?" editor (name, backstory, personality, traits) in the same grey panel style as the
 * settings screen. Save writes the config and sends the name to the server; Cancel discards. Both return to
 * {@code parent} (or close the screen when it was opened without one, e.g. from the radial wheel).
 */
public class CompanionProfileScreen extends Screen {
    private static final int PANEL_W = 300, PAD = 12, HEAD_H = 44, PITCH = 32;

    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;

    private EditBox nameField, descriptionField, personalityField, traitsField;
    private int px, py, panelW, panelH, ix, iw;

    public CompanionProfileScreen(VoiceConfig config, Path configFile, Screen parent) {
        super(Component.literal("Personalize Companion"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + 4 * PITCH + 4 + 24 + 20 + 12;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;

        int y = py + HEAD_H;
        nameField = field(y, config.companionName, "Name (e.g. Aria)");
        y += PITCH;
        descriptionField = field(y, config.companionDescription, "Who are they? Backstory, role, quirks...");
        y += PITCH;
        personalityField = field(y, config.companionPersonality, "Friendly, sarcastic, brave...");
        y += PITCH;
        traitsField = field(y, config.companionTraits, "Comma separated: loyal, curious");
        y += PITCH + 4;

        int colW = (iw - 4) / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .pos(ix, y).size(colW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Reset to Defaults"), b -> {
            nameField.setValue("Companion");
            descriptionField.setValue("A loyal companion fighting beside you.");
            personalityField.setValue("Friendly and helpful");
            traitsField.setValue("loyal, curious, protective");
        }).pos(ix + colW + 4, y).size(colW, 20).build());
        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> back())
                .pos(ix, y).size(iw, 20).build());
    }

    private EditBox field(int y, String value, String hint) {
        EditBox box = new EditBox(this.font, ix, y, iw, 20, Component.literal(hint));
        box.setMaxLength(240);
        box.setValue(value == null ? "" : value);
        box.setHint(Component.literal(hint));
        this.addRenderableWidget(box);
        return box;
    }

    private void save() {
        config.companionName = blankTo(nameField.getValue(), "Companion");
        config.companionDescription = blankTo(descriptionField.getValue(), "A loyal companion fighting beside you.");
        config.companionPersonality = blankTo(personalityField.getValue(), "Friendly and helpful");
        config.companionTraits = blankTo(traitsField.getValue(), "loyal, curious, protective");
        config.save(configFile);
        ClientVoice.send("set_name", "", config.companionName);
        back();
    }

    private void back() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);   // null parent = close
    }

    @Override
    public void onClose() { back(); }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        centered(g, "Personalize Companion", cx, py + 8, CompanionUi.TEXT_DARK);
        centered(g, "Who your companion is. It shapes every reply.", cx, py + 20, CompanionUi.TEXT_MUTED);
        int y = py + HEAD_H - 11;
        label(g, "Name", y);
        label(g, "Who they are", y + PITCH);
        label(g, "Personality", y + 2 * PITCH);
        label(g, "Traits", y + 3 * PITCH);
    }

    private void label(GuiGraphicsExtractor g, String s, int y) {
        g.text(this.font, s, ix, y, CompanionUi.TEXT_DARK, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionRadialScreen.java

```java
package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Radial wheel, overhaul.
 *  - Smooth ring segments with gaps, a vanilla-grey palette and a white outline + pop-out on the hovered segment.
 *  - Hold R and release on a segment to pick it (ARK style), or tap R and click. Right-click / Backspace = back, 1-8 = quick select.
 *  - Centre hub shows what is hovered, the breadcrumb and the AI's current goal.
 *  - Fixes the old half-segment offset between what was drawn and what was hit-tested.
 *  Geometry is rasterised once per segment count into horizontal runs, so drawing is just a few thousand fills.
 */
public final class CompanionRadialScreen extends Screen {
    private static final int INNER = 40, OUTER = 122, POP = 7, GAP = 3;
    private static final int C = OUTER + POP, SIZE = 2 * C + 1;
    private static final double ICON_R = (INNER + OUTER) / 2.0;

    private static final int FILL = 0xB4101010, EDGE = 0xC8383838, HOT_FILL = 0xDC5C5C5C, HOT_EDGE = 0xFFFFFFFF;

    // ------------------------------------------------------------------ geometry cache

    private record Run(int x, int y, int len, int seg, int flags) {}   // flags: 1 = pop-out zone, 2 = segment edge, 4 = base rim
    private static int cachedN = -1;
    private static List<Run> cachedRuns = List.of();

    private static List<Run> runsFor(int n) {
        if (n == cachedN) return cachedRuns;
        double step = 360.0 / n;
        int[] seg = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                double dx = x - C, dy = y - C, r = Math.hypot(dx, dy);
                int i = y * SIZE + x;
                if (r < INNER || r > OUTER + POP) { seg[i] = -1; continue; }
                double a = (Math.toDegrees(Math.atan2(dy, dx)) + 450.0) % 360.0;   // 0 = up, clockwise
                int idx = Math.min(n - 1, (int) (a / step));
                double off = a - idx * step;
                seg[i] = Math.toRadians(Math.min(off, step - off)) * r < GAP / 2.0 ? -1 : idx;
            }
        }
        List<Run> runs = new ArrayList<>();
        for (int y = 0; y < SIZE; y++) {
            int runStart = -1, runKey = -1;
            for (int x = 0; x <= SIZE; x++) {
                int key = -1;
                if (x < SIZE) {
                    int s = seg[y * SIZE + x];
                    if (s >= 0) {
                        double r = Math.hypot(x - C, y - C);
                        int f = 0;
                        if (r > OUTER) f |= 1;
                        if (x == 0 || y == 0 || x == SIZE - 1 || y == SIZE - 1
                                || seg[y * SIZE + x - 1] != s || seg[y * SIZE + x + 1] != s
                                || seg[(y - 1) * SIZE + x] != s || seg[(y + 1) * SIZE + x] != s) f |= 2;
                        if (r > OUTER - 1.5 && r <= OUTER) f |= 4;
                        key = (s << 3) | f;
                    }
                }
                if (key != runKey) {
                    if (runKey >= 0) runs.add(new Run(runStart, y, x - runStart, runKey >> 3, runKey & 7));
                    runStart = x;
                    runKey = key;
                }
            }
        }
        cachedN = n;
        cachedRuns = runs;
        return runs;
    }

    // ------------------------------------------------------------------ entries

    private static final class Entry {
        final String label, hint, action, target;
        final ItemStack icon;
        final List<Entry> children;
        final Runnable custom;

        Entry(String label, String hint, String action, String target, ItemStack icon, List<Entry> children, Runnable custom) {
            this.label = label; this.hint = hint; this.action = action; this.target = target;
            this.icon = icon; this.children = children; this.custom = custom;
        }
        boolean isSubmenu() { return children != null && !children.isEmpty(); }
    }

    private record Frame(List<Entry> entries, String title) {}

    private final Deque<Frame> stack = new ArrayDeque<>();
    private List<Entry> current;
    private String currentTitle = "Companion";
    private int hovered = -1;
    private int lastHovered = -1;
    private double lastDistance = Double.MAX_VALUE;
    private final long openedAt = System.currentTimeMillis();
    private boolean holdMode;

    public CompanionRadialScreen() {
        super(Component.literal("Companion"));
        this.current = buildRoot();
        this.holdMode = rDown();
    }

    @Override public boolean isPauseScreen() { return false; }

    private static boolean rDown() {
        return ClientVoice.radialKey().isDown();    // rebindable (was hardcoded GLFW_KEY_R); updates even while this screen is open
    }

    private List<Entry> buildRoot() {
        VoiceConfig cfg = ClientVoice.config();
        List<Entry> e = new ArrayList<>();
        e.add(order("Follow", "Walk with me", "follow", Items.COMPASS));
        e.add(order("Stay", "Hold this spot", "stay", Items.SHIELD));
        e.add(order("Sit", "Rest here", "sit", Items.OAK_STAIRS));
        e.add(sub("Movement", "Come, patrol, wander", Items.ENDER_PEARL, List.of(
                order("Come", "Teleport to me", "come", Items.ENDER_PEARL),
                order("Patrol", "Walk a patrol ring", "patrol", Items.SPYGLASS),
                order("Wander", "Roam nearby", "wander", Items.OAK_SAPLING),
                order("Explore", "Explore the area", "explore", Items.MAP),
                order("Dig Up", "Staircase to the surface", "surface", Items.LADDER))));
        e.add(sub("Behavior", "How to fight", Items.DIAMOND_SWORD, List.of(
                order("Aggressive", "Attack nearby hostiles", "guard", Items.DIAMOND_SWORD),
                order("Defensive", "Only fight back", "defensive", Items.SHIELD),
                order("Passive", "Never start a fight", "passive", Items.WHITE_WOOL),
                order("Attack", "Fight what I aim at", "attack", Items.IRON_SWORD))));
        e.add(sub("Work", "Items and mining", Items.CHEST, List.of(
                order("Pick up", "Grab a dropped item", "pickup", Items.HOPPER),
                custom("Find & Mine", "Go mine a block", Items.DIAMOND_PICKAXE,
                        () -> open(new CompanionFindBlockScreen(cfg, ClientVoice.configFile()))),
                order("Inventory", "Open its bag", "inventory", Items.CHEST),
                order("Drop item", "Drop one item", "drop", Items.BONE),
                order("Farm", "Harvest crops for 30s", "farm", Items.WHEAT),
                order("Fish", "Fish for 30s", "fish", Items.FISHING_ROD))));
        e.add(sub("AI", "Goals and autonomy", Items.ENDER_EYE, List.of(
                custom("Give Goal", "Describe a goal in words", Items.WRITABLE_BOOK, () -> open(new CompanionGoalScreen(null))),
                custom("Think Now", "Let the AI decide", Items.EXPERIENCE_BOTTLE,
                        () -> CompanionAutonomy.requestGoal("Decide something useful to do right now.")),
                order("Continue", "Resume interrupted work", "continue", Items.CLOCK),
                custom("Auto: " + (cfg.autonomyEnabled ? "ON" : "OFF"), "Act without being asked",
                        cfg.autonomyEnabled ? Items.LIME_DYE : Items.GRAY_DYE, () -> {
                            cfg.autonomyEnabled = !cfg.autonomyEnabled;
                            cfg.save(ClientVoice.configFile());
                        }),
                custom("Cancel Plan", "Stop the current goal", Items.BARRIER, CompanionAutonomy::cancelPlan))));
        e.add(sub("More", "Skin, persona, settings", Items.NAME_TAG, List.of(
                custom("Skin Library", "Wear a player skin", Items.LEATHER_HELMET,
                        () -> open(new CompanionSkinScreen(cfg, ClientVoice.configFile(), this))),
                custom("Personalize", "Who your companion is", Items.NAME_TAG,
                        () -> open(new CompanionProfileScreen(cfg, ClientVoice.configFile(), this))),
                custom("Settings", "Voice, memory, AI", Items.REDSTONE,
                        () -> open(new CompanionSettingsScreen(cfg, ClientVoice.configFile()))),
                order("Inventory", "Open its bag", "inventory", Items.CHEST))));
        return List.copyOf(e);
    }

    private static Entry order(String label, String hint, String action, Item icon) {
        return new Entry(label, hint, action, "", new ItemStack(icon), null, null);
    }
    private static Entry sub(String label, String hint, Item icon, List<Entry> children) {
        return new Entry(label, hint, "", "", new ItemStack(icon), children, null);
    }
    private static Entry custom(String label, String hint, Item icon, Runnable run) {
        return new Entry(label, hint, "", "", new ItemStack(icon), null, run);
    }
    private void open(Screen s) { if (this.minecraft != null) this.minecraft.setScreen(s); }

    // ------------------------------------------------------------------ rendering

    private static int fade(int argb, float alpha) {
        int a = (int) (((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2, cy = this.height / 2;
        int n = current.size();
        double step = 360.0 / n;
        float t = Math.min(1f, (System.currentTimeMillis() - openedAt) / 140f);
        float alpha = 1f - (1f - t) * (1f - t) * (1f - t);

        g.fill(0, 0, this.width, this.height, fade(0x78000000, alpha));

        double dx = mouseX - cx, dy = mouseY - cy;
        lastDistance = Math.hypot(dx, dy);
        hovered = -1;
        if (lastDistance >= INNER && lastDistance <= OUTER + POP) {
            double a = (Math.toDegrees(Math.atan2(dy, dx)) + 450.0) % 360.0;
            int idx = Math.min(n - 1, (int) (a / step));
            if (lastDistance <= OUTER || idx == lastHovered) hovered = idx;
        }
        lastHovered = hovered;

        int ox = cx - C, oy = cy - C;
        List<Run> runs = runsFor(n);
        for (int pass = 0; pass < 2; pass++) {                  // idle segments first, hovered one on top
            for (Run r : runs) {
                boolean hot = r.seg() == hovered;
                if ((pass == 1) != hot) continue;
                int f = r.flags();
                if ((f & 1) != 0 && !hot) continue;             // pop-out zone belongs to the hovered segment only
                boolean edge = (f & 2) != 0 || ((f & 4) != 0 && !hot);
                int col = hot ? (edge ? HOT_EDGE : HOT_FILL) : (edge ? EDGE : FILL);
                g.fill(ox + r.x(), oy + r.y(), ox + r.x() + r.len(), oy + r.y() + 1, fade(col, alpha));
            }
        }

        for (int i = 0; i < n; i++) {
            Entry en = current.get(i);
            boolean hot = i == hovered;
            double ang = Math.toRadians((i + 0.5) * step);
            double rad = ICON_R + (hot ? 4 : 0);
            int ix = cx + (int) Math.round(Math.sin(ang) * rad);
            int iy = cy - (int) Math.round(Math.cos(ang) * rad);
            g.item(en.icon, ix - 8, iy - 12);
            String label = en.label + (en.isSubmenu() ? " >" : "");
            g.centeredText(this.font, label, ix, iy + 7, fade(hot ? 0xFFFFFFFF : 0xFFC8C8C8, alpha));
        }

        for (int y = -34; y <= 34; y++) {                       // centre hub
            int w = (int) Math.sqrt(34.0 * 34.0 - y * y);
            g.fill(cx - w, cy + y, cx + w, cy + y + 1, fade(0xE6101010, alpha));
        }
        String title, hint;
        if (hovered >= 0) { title = current.get(hovered).label; hint = current.get(hovered).hint; }
        else if (!stack.isEmpty() && lastDistance < INNER) { title = "Back"; hint = "Click the centre"; }
        else {
            title = currentTitle;
            com.example.companionmod.entity.CompanionEntity mine = ClientVoice.findCompanion(this.minecraft);
            String task = mine == null ? "" : mine.taskText();
            hint = task.isBlank() ? "Choose an order" : task;       // live progress lives here, not in chat
        }
        g.centeredText(this.font, title, cx, cy - 12, fade(0xFFFFFFFF, alpha));
        // Long hints (the live task text "Iron 6/16 - digging" and friends) are scaled down, not cut off.
        String hintText = hint.length() > 26 ? hint.substring(0, 25) + "…" : hint;
        if (hintText.length() > 16) {
            g.pose().pushMatrix();
            g.pose().translate(cx, cy - 1);
            g.pose().scale(0.65f, 0.65f);
            g.centeredText(this.font, hintText, 0, 0, fade(0xFFA0A0A0, alpha));
            g.pose().popMatrix();
        } else {
            g.centeredText(this.font, hintText, cx, cy - 1, fade(0xFFA0A0A0, alpha));
        }
        String goal = CompanionAutonomy.currentGoal();
        if (!goal.isBlank()) {
            String s = "Goal: " + (goal.length() > 14 ? goal.substring(0, 13) + "…" : goal);
            g.centeredText(this.font, s, cx, cy + 12, fade(0xFF7FD7FF, alpha));
        }

        StringBuilder crumb = new StringBuilder("Companion");
        List<Frame> frames = new ArrayList<>(stack);
        for (int i = frames.size() - 2; i >= 0; i--) crumb.append("  >  ").append(frames.get(i).title());
        if (!stack.isEmpty()) crumb.append("  >  ").append(currentTitle);
        g.centeredText(this.font, crumb.toString(), cx, cy - OUTER - POP - 14, fade(0xFFE0E0E0, alpha));
        g.centeredText(this.font, "Hold R and release to pick  •  click  •  1-8 quick select  •  right-click back",
                cx, this.height - 20, fade(0xFFA0A0A0, alpha));
    }

    // ------------------------------------------------------------------ input

    @Override
    public void tick() {
        if (holdMode && !rDown()) {                            // released R: pick what is under the cursor
            holdMode = false;
            if (System.currentTimeMillis() - openedAt > 220 && hovered >= 0) activate(current.get(hovered));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        holdMode = false;
        if (event.button() == 1) { backOrClose(); return true; }
        if (event.button() != 0) { onClose(); return true; }
        if (hovered >= 0) { activate(current.get(hovered)); return true; }
        if (lastDistance < INNER) backOrClose(); else onClose();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (ClientVoice.radialKey().matches(event)) {              // ignore key-repeat while the open key is still held
            if (!holdMode && System.currentTimeMillis() - openedAt > 300) onClose();
            return true;
        }
        if (k == GLFW.GLFW_KEY_BACKSPACE) { backOrClose(); return true; }
        if (k >= GLFW.GLFW_KEY_1 && k <= GLFW.GLFW_KEY_9 && k - GLFW.GLFW_KEY_1 < current.size()) {
            holdMode = false;
            activate(current.get(k - GLFW.GLFW_KEY_1));
            return true;
        }
        return super.keyPressed(event);
    }

    private void activate(Entry en) {
        if (en.isSubmenu()) {
            stack.push(new Frame(current, currentTitle));
            current = en.children;
            currentTitle = en.label;
            hovered = lastHovered = -1;
            return;
        }
        if (en.custom != null) {
            en.custom.run();
            if (this.minecraft != null && this.minecraft.screen == this) this.minecraft.setScreen(null);
            return;
        }
        ClientVoice.send(en.action, "", en.target);
        if (this.minecraft != null) this.minecraft.setScreen(null);
    }

    private void backOrClose() {
        if (stack.isEmpty()) { onClose(); return; }
        Frame f = stack.pop();
        current = f.entries();
        currentTitle = f.title();
        hovered = lastHovered = -1;
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionRenderer.java

```java
package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.FemaleGenderCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/**
 * Draws the companion at the same height as a player (the {@code 0.9375} body scale the avatar
 * renderer uses), with the classic or slim model resolved from the skin, armor + held tool layered
 * on top, a floating stream of word bubbles, and the downed crawl (Mojang's swim pose, which
 * only the player renderer flattens — we replicate that transform here).
 *
 * <p>Slim/classic is a per-frame toggle inside {@link CompanionHumanoidModel}, never a model swap:
 * {@code AgeableMobRenderer.submit()} resets {@code this.model} to the adult model before every draw,
 * which silently discarded swapped models.</p>
 */
public class CompanionRenderer extends HumanoidMobRenderer<CompanionEntity, CompanionRenderState, HumanoidModel<CompanionRenderState>> {
    private static final float AVATAR_SCALE = 0.9375f;   // matches AvatarRenderer so the companion is player-height
    private static final float SUBTITLE_SCALE = 0.025f;

    private final PlayerSkinRenderCache skinCache;
    private final EquipmentLayerRenderer equipmentRenderer;   // for WGM's breast-shaped armor layer
    private boolean genderLayerTried;           // Wildfire layer is attached lazily on the first draw

    public CompanionRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new CompanionHumanoidModel(), 0.5f);
        this.skinCache = ctx.getPlayerSkinRenderCache();
        this.equipmentRenderer = ctx.getEquipmentRenderer();
        ArmorModelSet<HumanoidModel<CompanionRenderState>> wideArmor =
                ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, ctx.getModelSet(), HumanoidModel::new);
        ArmorModelSet<HumanoidModel<CompanionRenderState>> slimArmor =
                ArmorModelSet.bake(ModelLayers.PLAYER_SLIM_ARMOR, ctx.getModelSet(), HumanoidModel::new);
        // Both armor layers are registered, but only the one matching the current body model submits,
        // so slim arms get slim armor sleeves instead of wide sleeves clipping through.
        addLayer(new SelectableArmorLayer(this, wideArmor, ctx, false));
        addLayer(new SelectableArmorLayer(this, slimArmor, ctx, true));
        // No chest overlay of our own: when Wildfire's Female Gender Mod is installed its real GenderLayer
        // draws the chest (fed by FemaleGenderCompat.populateRenderState), otherwise the companion is flat.
    }

    /**
     * Attaches Wildfire's real chest layer on the first draw instead of in the constructor, so the mod's
     * client services (render-state key, player config cache) are guaranteed to be initialised.
     * Also attaches GenderArmorLayer so a worn chestplate renders over the female chest.
     */
    private void attachGenderLayer() {
        if (genderLayerTried) return;
        genderLayerTried = true;
        // Wildfire's 5.x GenderLayer is generic over HumanoidRenderState, so it fits this renderer.
        RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>> gender =
                FemaleGenderCompat.createLayer(this);
        if (gender != null) {
            addLayer(gender);
            RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>> armor =
                    FemaleGenderCompat.createArmorLayer(this, equipmentRenderer);
            if (armor != null) addLayer(armor);
        }
    }

    /** Armor layer that only submits when the companion's body model matches its geometry (wide vs slim arms). */
    private static final class SelectableArmorLayer extends HumanoidArmorLayer<
            CompanionRenderState, HumanoidModel<CompanionRenderState>, HumanoidModel<CompanionRenderState>> {
        private final boolean slim;

        private SelectableArmorLayer(CompanionRenderer parent,
                                     ArmorModelSet<HumanoidModel<CompanionRenderState>> models,
                                     EntityRendererProvider.Context ctx, boolean slim) {
            super(parent, models, ctx.getEquipmentRenderer());
            this.slim = slim;
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light,
                           CompanionRenderState state, float limbSwing, float limbSwingAmount) {
            if (state.slim == slim) super.submit(poseStack, collector, light, state, limbSwing, limbSwingAmount);
        }
    }

    @Override
    public CompanionRenderState createRenderState() { return new CompanionRenderState(); }

    @Override
    public Identifier getTextureLocation(CompanionRenderState state) { return state.texture; }

    @Override
    protected RenderType getRenderType(CompanionRenderState state, boolean visible, boolean visibleToSpectator, boolean glowing) {
        if (state.renderType != null) return state.renderType;
        return super.getRenderType(state, visible, visibleToSpectator, glowing);
    }

    @Override
    public void extractRenderState(CompanionEntity entity, CompanionRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.isPassenger = entity.isSitting();
        state.skin = entity.skin();
        state.speech = entity.speech();
        state.speechId = entity.speechId();
        state.speechStart = CompanionSubtitles.startFor(entity.getId(), state.speechId, state.speech);
        state.entityId = entity.getId();
        state.speechSpeaker = entity.getName().getString();
        state.injured = entity.isInjured();
        state.reviveProgress = entity.reviveProgress();
        state.injuredLeft = entity.injuredLeftTicks();
        resolveSkin(state, entity.skin(), entity.syncedOwner());
        // Wildfire's layer reads its data from the render state. The mod only fills it for players,
        // so we build it from the companion's own holder (owner gender irrelevant), gated by the feminine toggle.
        FemaleGenderCompat.populateRenderState(state, entity, partialTick);
    }

    private void resolveSkin(CompanionRenderState state, String name, UUID owner) {
        // 1) Resolve the skin texture. "owner" goes through the local player's RESOLVED profile:
        //    synchronous, and it picks up Quick Skin local skins with the real model type. A raw name
        //    resolves asynchronously and shows Steve (wide) until it completes, which is what made
        //    slim auto-detection unreliable.
        if (name == null || name.isBlank() || "default".equalsIgnoreCase(name)) {
            applyInfo(state, null);
        } else if ("owner".equalsIgnoreCase(name)) {
            applyInfo(state, ownerSkin(owner));       // THIS companion's owner, not whoever is looking at it
        } else {
            applyInfo(state, skinCache.getOrDefault(ResolvableProfile.createUnresolved(name)));
        }
        // 2) Body model: explicit slim/classic config wins; "auto" follows the resolved skin (Alex = slim).
        String body = ClientVoice.config().companionBody;
        if ("slim".equals(body)) state.slim = true;
        else if ("classic".equals(body)) state.slim = false;
        else state.slim = state.slimSkin;
    }

    /** Skin of the companion's owner (the local player for your own companion, the other player in multiplayer). */
    private PlayerSkinRenderCache.RenderInfo ownerSkin(UUID owner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        Player p = owner == null || owner.equals(mc.player.getUUID()) ? mc.player
                : (mc.level == null ? null : mc.level.getPlayerByUUID(owner));
        return p == null ? null : skinCache.getOrDefault(ResolvableProfile.createResolved(p.getGameProfile()));
    }

    private static void applyInfo(CompanionRenderState state, PlayerSkinRenderCache.RenderInfo info) {
        if (info == null) {
            state.texture = CompanionRenderState.DEFAULT;
            state.renderType = null;
            state.slimSkin = false;
        } else {
            state.renderType = info.renderType();
            state.texture = info.playerSkin().body().texturePath();
            state.slimSkin = info.playerSkin().model() == PlayerModelType.SLIM;
        }
    }

    /** Lays the companion flat while it is crawling (downed), exactly like AvatarRenderer does for swimming players. */
    @Override
    protected void setupRotations(CompanionRenderState state, PoseStack poseStack, float age, float yHeadRot) {
        super.setupRotations(state, poseStack, age, yHeadRot);
        float swim = state.swimAmount;
        if (swim > 0.001f) {
            poseStack.mulPose(Axis.XP.rotationDegrees(Mth.lerp(swim, 0f, -90f)));
            if (state.isVisuallySwimming) poseStack.translate(0.0f, -1.0f, 0.3f);
        }
    }

    @Override
    public void submit(CompanionRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        attachGenderLayer();
        FemaleGenderCompat.logLayersOnce(this);
        FemaleGenderCompat.applyAtSubmit(state);
        FemaleGenderCompat.verifyAtSubmit(state);
        super.submit(state, poseStack, collector, camera);
        if (state.injured) submitInjured(state, poseStack, collector, camera);
        else { downedAt.remove(state.entityId); submitSubtitle(state, poseStack, collector, camera); }
    }

    @Override
    protected void scale(CompanionRenderState state, PoseStack poseStack) {
        poseStack.scale(AVATAR_SCALE, AVATAR_SCALE, AVATAR_SCALE);     // match player height
        if (state.isPassenger) poseStack.translate(0.0f, 0.5f, 0.0f); // sit pose offset (already scaled)
    }

    /** Name tag only while the player is aiming at the companion - never over a downed body. */
    @Override
    protected boolean shouldShowName(CompanionEntity entity, double distance) {
        if (entity.isInjured()) return false;
        return this.entityRenderDispatcher.crosshairPickEntity == entity;
    }

    // ------------------------------------------------------------------ floating subtitle (each word its own bubble)

    private void submitSubtitle(CompanionRenderState state, PoseStack poseStack,
                                SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.speech == null || state.speech.isBlank()) return;
        Font font = this.getFont();
        List<CompanionSubtitles.Word> words = CompanionSubtitles.layout(
                state.entityId, state.speechId, state.speech);
        if (words.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(0.0, state.boundingBoxHeight + 0.2f, 0.0);    // starts right above the head (was +0.45)
        poseStack.mulPose(camera.orientation);
        poseStack.scale(SUBTITLE_SCALE, -SUBTITLE_SCALE, SUBTITLE_SCALE);

        for (CompanionSubtitles.Word w : words) {
            FormattedCharSequence seq = font.split(Component.literal(w.text()), 220).get(0);
            float wordW = font.width(seq);
            float s = w.scale();
            int a = (w.argb() >>> 24) & 0xFF;
            int bubble = (Math.min(255, a * 200 / 255) << 24) | 0x14181E;   // each word gets its own bubble
            poseStack.pushPose();
            poseStack.translate(w.x(), -w.y(), 0.0);                       // positive lift = upward
            if (Math.abs(w.rotDeg()) > 0.01f) poseStack.mulPose(Axis.ZP.rotationDegrees(w.rotDeg()));
            poseStack.scale(s, s, 1.0f);
            collector.submitText(poseStack, -wordW / 2.0f, 0.0f, seq, false,
                    Font.DisplayMode.SEE_THROUGH, state.lightCoords, w.argb(), bubble, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    /**
     * Downed text: the same playful per-word bubbles as the chat (pastel color, tilt, wobble, pop-in with
     * overshoot) but persistent - words never fade, they breathe, bob and wiggle forever until revived.
     * One line: "REVIVE ME <n>s". No green "Reviving..." line: the bar above the hotbar owns progress.
     */
    private static final java.util.Map<Integer, Long> downedAt = new java.util.concurrent.ConcurrentHashMap<>();

    private void submitInjured(CompanionRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (!state.injured) { downedAt.remove(state.entityId); return; }
        if (state.injuredLeft <= 0) return;
        int seconds = (state.injuredLeft + 19) / 20;
        long now = System.currentTimeMillis();
        long since = downedAt.computeIfAbsent(state.entityId, id -> now);

        String[] words = {"REVIVE", "ME", seconds + "s"};
        long[] reveal = {0, 300, 600};               // one-time sequential pop; afterwards perpetual bounce

        Font font = getFont();
        poseStack.pushPose();
        poseStack.translate(0.0, state.boundingBoxHeight + 0.45f, 0.0);
        poseStack.mulPose(camera.orientation);
        poseStack.scale(SUBTITLE_SCALE, -SUBTITLE_SCALE, SUBTITLE_SCALE);

        float[] widths = new float[words.length];
        float total = 0;
        for (int i = 0; i < words.length; i++) {
            widths[i] = font.width(font.split(Component.literal(words[i]), 220).get(0));
            total += widths[i] + 4;
        }
        total -= 4;
        float cursor = -total / 2;

        for (int i = 0; i < words.length; i++) {
            FormattedCharSequence seq = font.split(Component.literal(words[i]), 220).get(0);
            java.util.Random rng = new java.util.Random((long) state.entityId * 31 + i);
            float tilt = (rng.nextBoolean() ? 1 : -1) * (3f + rng.nextFloat() * 7f);
            float phase = rng.nextFloat() * 6.283f;
            float sizeMul = 0.9f + rng.nextFloat() * 0.45f;

            float age = now - since - reveal[i];
            float cx = cursor + widths[i] / 2f;
            cursor += widths[i] + 4;
            if (age < 0) continue;

            float pop = Math.min(1, age / 300f);
            float q = pop - 1;
            float popIn = 1 + 2.70158f * q * q * q + 1.70158f * q * q;   // easeOutBack: overshoot bounce
            float breathe = 1 + (float) Math.sin(now * 0.005 + phase) * 0.06f;
            float bob = (float) Math.sin(now * 0.003 + phase) * 2.5f;
            float wobble = (float) Math.sin(now * 0.0045 + phase) * 4f;
            float scale = Math.max(0.16f, (0.2f + 0.8f * popIn) * sizeMul * (pop >= 1 ? breathe : 1));
            float flash = Math.max(0, 1 - age / 150f);                    // white burst on the pop, then pastel
            int argb = CompanionSubtitles.blend(0xFF000000
                    | (CompanionSubtitles.PALETTE[i % CompanionSubtitles.PALETTE.length] & 0xFFFFFF), flash);

            poseStack.pushPose();
            poseStack.translate(cx, -bob, 0);
            poseStack.mulPose(Axis.ZP.rotationDegrees(tilt + wobble));
            poseStack.scale(scale, scale, 1f);
            collector.submitText(poseStack, -widths[i] / 2f, 0f, seq, false,
                    Font.DisplayMode.SEE_THROUGH, state.lightCoords, argb, 0xC814181E, 0);
            poseStack.popPose();
        }
        poseStack.popPose();
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionRenderState.java

```java
package com.example.companionmod.client;

import com.example.companionmod.CompanionMod;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Extra render data carried from the entity to the renderer (skin + slim model +
 * injury state + subtitle). The resolved texture/render-type lives here, not in the renderer,
 * because 26.1 extracts every entity before submitting any of them — a field on the renderer would be
 * shared between companions.
 */
public class CompanionRenderState extends HumanoidRenderState {
    public static final Identifier DEFAULT = CompanionMod.id("textures/entity/companion.png");

    public String skin = "";
    public boolean slim;
    public boolean slimSkin;             // auto-detected from the resolved skin (Alex model)
    public Identifier texture = DEFAULT;
    public RenderType renderType;         // null = use the model texture (bundled skin), non-null = live player skin
    public Object wgmData;               // Wildfire GenderRenderState built at extract; re-applied at submit (something clears render data between the two phases)

    /** Current floating line and the local animation clock (milliseconds). */
    public String speech = "";
    public String speechSpeaker = "";
    public int speechId;
    public long speechStart;

    /** Downed state + synced revive progress / timer for the world HUD. */
    public int entityId;
    public boolean injured;
    public float reviveProgress;
    public int injuredLeft;
}
```

## common\src\main\java\com\example\companionmod\client\CompanionSettingsScreen.java

```java
package com.example.companionmod.client;

import com.example.companionmod.voice.AudioDevices;
import com.example.companionmod.voice.LevelMeter;
import com.example.companionmod.voice.MicCapture;
import com.example.companionmod.voice.OllamaCatalog;
import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.example.companionmod.client.CompanionDropdowns.Dropdown;
import com.example.companionmod.client.CompanionDropdowns.Option;

/**
 * Voice-Chat-mod style settings: one grey bevelled panel with four tabs (General / AI / Mic / Voice).
 * Models, voices, microphones and speakers are dropdowns filled from the server / audio system; the Mic tab has a
 * live level bar. Replaces the old CompanionConnectionScreen, CompanionModelScreen, CompanionVoiceScreen and
 * CompanionAudioDeviceScreen. Changes apply live and are saved when the screen closes (Done, Esc, or opening a sub-screen).
 */
public class CompanionSettingsScreen extends Screen {
    public enum Tab {
        GENERAL("General"), AI("AI"), MIC("Mic"), VOICE("Voice");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private static Tab lastTab = Tab.GENERAL;

    private static final int PANEL_W = 300, PAD = 12, ROW = 24;
    private static final int HEAD_H = 52, CONTENT_H = 132, FOOT_H = 32;

    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;
    private final boolean initialFeminine;
    private boolean feminine;
    private Tab tab;

    // geometry (recomputed in init)
    private int px, py, panelW, panelH, ix, iw, cy;

    private CompanionDropdowns dropdowns;
    private Dropdown chatDd, sttDd, ttsDd, voiceDd, micDd, spkDd;
    private EditBox hostBox;
    private Button femButton;
    private final List<Dropdown> editables = new ArrayList<>();

    // server catalog (survives tab switches and resizes)
    private boolean catalogRequested;
    private List<String> modelNames = List.of();
    private List<String> voiceNames = List.of();
    private boolean modelsLoading, modelsFailed, voicesLoading;
    private int modelGen, voiceGen;
    private List<String> inputDevices, outputDevices;

    // mic test (only runs while the Mic tab is showing)
    private final LevelMeter meter = new LevelMeter();
    private volatile MicCapture mic;
    private volatile String micError = "";

    public CompanionSettingsScreen(VoiceConfig config, Path configFile) { this(config, configFile, null); }

    public CompanionSettingsScreen(VoiceConfig config, Path configFile, Screen parent) { this(config, configFile, parent, lastTab); }

    public CompanionSettingsScreen(VoiceConfig config, Path configFile, Screen parent, Tab tab) {
        super(Component.literal("Companion Settings"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
        this.initialFeminine = config.feminineModel;
        this.feminine = initialFeminine;
        this.tab = tab == null ? Tab.GENERAL : tab;
    }

    // ---- layout -------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        dropdowns = new CompanionDropdowns(this.font, () -> this.height);
        chatDd = sttDd = ttsDd = voiceDd = micDd = spkDd = null;
        hostBox = null;
        editables.clear();

        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + CONTENT_H + FOOT_H;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;
        cy = py + HEAD_H;

        int gap = 2, tw = (iw - gap * (Tab.values().length - 1)) / Tab.values().length;
        for (int i = 0; i < Tab.values().length; i++) {
            final Tab t = Tab.values()[i];
            Button b = Button.builder(Component.literal(t.label), x -> switchTab(t))
                    .pos(ix + i * (tw + gap), py + 22).size(tw, 20).build();
            b.active = t != tab;
            this.addRenderableWidget(b);
        }

        switch (tab) {
            case GENERAL -> buildGeneral();
            case AI -> buildAi();
            case MIC -> buildMic();
            case VOICE -> buildVoice();
        }

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .pos(ix, py + panelH - 26).size(iw, 20).build());

        if (tab == Tab.MIC) startMic(); else stopMic();
        if (!catalogRequested) { catalogRequested = true; loadModels(); loadVoices(); }
        applyOptions();
    }

    private void switchTab(Tab t) {
        lastTab = t;
        this.tab = t;
        this.inputDevices = null;                     // re-enumerate: picks up hot-plugged devices
        this.outputDevices = null;
        this.rebuildWidgets();
    }

    private void buildGeneral() {
        int colW = (iw - 4) / 2, lx = ix, rx = ix + colW + 4, y = cy;

        nav(lx, y, colW, "Personalize...", () -> new CompanionProfileScreen(config, configFile, this));
        nav(rx, y, colW, "Appearance...", () -> new CompanionSkinScreen(config, configFile, this));
        y += ROW;

        toggle(lx, y, colW, "Dynamic Goals", config.dynamicGoals, v -> config.dynamicGoals = v);
        toggle(rx, y, colW, "Autonomy", config.autonomyEnabled, v -> config.autonomyEnabled = v);
        y += ROW;

        toggle(lx, y, iw, "Memory", config.memoryEnabled, v -> config.memoryEnabled = v);
        y += ROW;

        // 26.1-CHECK: CycleButton.builder signature (copied unchanged from the old screen)
        this.addRenderableWidget(CycleButton.<String>builder(CompanionSettingsScreen::bodyName, config.companionBody == null ? "auto" : config.companionBody)
                .withValues("auto", "slim", "classic")
                .create(lx, y, colW - 24, 20, Component.literal("Body Type"), (b, v) -> config.companionBody = v));
        if (com.example.companionmod.voice.FemaleGenderCompat.isPresent()) {   // only meaningful with Wildfire's mod
            femButton = Button.builder(Component.empty(), b -> {
                feminine = !feminine;
                updateFemButton();
            }).pos(lx + colW - 20, y).size(20, 20).build();
            updateFemButton();
            this.addRenderableWidget(femButton);
        }
        this.addRenderableWidget(new Slider(rx, y, colW, "Think Every", 15, 180, config.autonomyIntervalSeconds, 0, "s",
                v -> config.autonomyIntervalSeconds = Math.round(v)));
    }

    /** Circle icon: filled pink = on, hollow grey = off. The state is only named in the hover tooltip. */
    private void updateFemButton() {
        if (femButton == null) return;                          // hidden when Wildfire's mod is not installed
        femButton.setMessage(feminine
                ? Component.literal("\u25CF").withStyle(ChatFormatting.LIGHT_PURPLE)
                : Component.literal("\u25CB").withStyle(ChatFormatting.GRAY));
        femButton.setTooltip(Tooltip.create(Component.literal(feminine ? "Feminine: On" : "Feminine: Off")));
    }

    private void buildAi() {
        int y = cy + 11;                               // "Server URL" label sits above the box
        hostBox = new EditBox(this.font, ix, y, iw - 24, 20, Component.literal("Server URL"));
        hostBox.setMaxLength(240);
        hostBox.setHint(Component.literal(OllamaCatalog.DEFAULT_HOST));
        hostBox.setValue(config.host == null ? "" : config.host);
        hostBox.setResponder(v -> config.host = v);
        this.addRenderableWidget(hostBox);
        this.addRenderableWidget(Button.builder(Component.literal("\u21BB"), b -> reloadCatalog())
                .pos(ix + iw - 20, y).size(20, 20).build());
        y += ROW;

        chatDd = dd(ix, y, iw, "Chat Model", () -> config.chatModel, v -> config.chatModel = v, true);
        y += ROW;
        sttDd = dd(ix, y, iw, "STT Model", () -> config.sttModel, v -> config.sttModel = v, true);
        y += ROW;
        this.addRenderableWidget(new Slider(ix, y, iw, "Temperature", 0f, 1.5f, config.temperature, 2, "",
                v -> config.temperature = Math.round(v * 100f) / 100f));
    }

    private void buildMic() {
        if (inputDevices == null) inputDevices = new ArrayList<>(AudioDevices.inputs());
        micDd = dd(ix, cy, iw, "Microphone", () -> config.micDevice, v -> { config.micDevice = v; restartMic(); });
        List<Option> opts = new ArrayList<>();
        opts.add(new Option("", "System default"));
        for (String n : inputDevices) opts.add(new Option(n, n));
        micDd.options = opts;

        int y = cy + 68;
        this.addRenderableWidget(new Slider(ix, y, iw, "Silence Gate", 0, 50, config.silenceGate * 100f, 0, "%",
                v -> config.silenceGate = Math.max(0f, Math.min(0.5f, v / 100f))));
        y += ROW;
        this.addRenderableWidget(new Slider(ix, y, iw, "Max Recording", 5, 60, config.maxRecordSeconds, 0, "s",
                v -> config.maxRecordSeconds = Math.round(v)));
    }

    private void buildVoice() {
        int y = cy;
        toggle(ix, y, iw, "Spoken Replies", config.speakReplies, v -> config.speakReplies = v);
        y += ROW;

        if (outputDevices == null) outputDevices = new ArrayList<>(AudioDevices.outputs());
        spkDd = dd(ix, y, iw, "Speaker", () -> config.outputDevice, v -> config.outputDevice = v);
        List<Option> opts = new ArrayList<>();
        opts.add(new Option("", "System default (Minecraft)"));
        for (String n : outputDevices) opts.add(new Option(n, n));
        spkDd.options = opts;
        y += ROW;

        ttsDd = dd(ix, y, iw, "Speech Model", () -> config.speechModel, v -> { config.speechModel = v; loadVoices(); }, true);
        y += ROW;
        voiceDd = dd(ix, y, iw, "Voice", () -> config.speechVoice, v -> config.speechVoice = v, true);
        y += ROW;

        this.addRenderableWidget(new Slider(ix, y, iw, "Reply Volume", 0, 100, config.speechVolume * 100f, 0, "%",
                v -> config.speechVolume = Math.max(0f, Math.min(1f, v / 100f))));
    }

    // ---- widget helpers -----------------------------------------------------------------------------------------

    private Dropdown dd(int x, int y, int w, String title, Supplier<String> get, Consumer<String> set) {
        return dd(x, y, w, title, get, set, false);
    }

    /** {@code editable}: adds a pencil button that swaps the dropdown for a text box, so any value can be typed. */
    private Dropdown dd(int x, int y, int w, String title, Supplier<String> get, Consumer<String> set, boolean editable) {
        Dropdown d = new Dropdown(title, get, set);
        int hw = editable ? w - 22 : w;
        d.button = this.addRenderableWidget(Button.builder(Component.literal(title), b -> dropdowns.toggle(d))
                .pos(x, y).size(hw, 20).build());
        if (editable) {
            EditBox box = new EditBox(this.font, x, y, hw, 20, Component.literal(title));
            box.setMaxLength(120);
            box.setHint(Component.literal("Type a name, Enter to confirm"));
            box.visible = false;
            d.editor = this.addRenderableWidget(box);
            d.editButton = this.addRenderableWidget(Button.builder(Component.literal("\u270E"), b -> toggleEdit(d))
                    .pos(x + w - 20, y).size(20, 20).build());
            editables.add(d);
        }
        dropdowns.register(d);
        return d;
    }

    private void toggleEdit(Dropdown d) {
        if (d.editing) { commitEdit(d); return; }
        dropdowns.close();
        d.editing = true;
        d.button.visible = false;
        String cur = d.getter.get();
        d.editor.setValue(cur == null ? "" : cur);
        d.editor.visible = true;
        this.setFocused(d.editor);
        d.editButton.setMessage(Component.literal("\u2714"));
    }

    private void commitEdit(Dropdown d) {
        String v = d.editor.getValue() == null ? "" : d.editor.getValue().trim();
        endEdit(d);
        d.setter.accept(v);
        dropdowns.refresh(d);
    }

    private void cancelEdit(Dropdown d) {
        endEdit(d);
        dropdowns.refresh(d);
    }

    private void endEdit(Dropdown d) {
        d.editing = false;
        d.editor.visible = false;
        d.button.visible = true;
        d.editButton.setMessage(Component.literal("\u270E"));
    }

    private void nav(int x, int y, int w, String label, Supplier<Screen> target) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(target.get());
        }).pos(x, y).size(w, 20).build());
    }

    private void toggle(int x, int y, int w, String name, boolean value, Consumer<Boolean> apply) {
        this.addRenderableWidget(CycleButton.onOffBuilder(value)
                .create(x, y, w, 20, Component.literal(name), (b, v) -> apply.accept(v)));
    }

    private static Component bodyName(String v) {
        return Component.literal(switch (v) { case "slim" -> "Slim"; case "classic" -> "Classic"; default -> "Auto"; });
    }

    /** Vanilla slider with a "Name: value" label. */
    private static final class Slider extends AbstractSliderButton {
        private final String label, unit;
        private final float min, max;
        private final int decimals;
        private final Consumer<Float> apply;

        Slider(int x, int y, int w, String label, float min, float max, float cur, int decimals, String unit, Consumer<Float> apply) {
            super(x, y, w, 20, Component.empty(), max > min ? Math.max(0, Math.min(1, (cur - min) / (max - min))) : 0);
            this.label = label; this.unit = unit; this.min = min; this.max = max; this.decimals = decimals; this.apply = apply;
            updateMessage();
        }

        private float val() { return min + (float) this.value * (max - min); }

        @Override protected void updateMessage() {
            if (label == null) return;                       // super() calls this before our fields exist
            String s = decimals == 0 ? String.valueOf(Math.round(val())) : String.format(java.util.Locale.ROOT, "%." + decimals + "f", val());
            setMessage(Component.literal(label + ": " + s + unit));
        }

        @Override protected void applyValue() { apply.accept(val()); }
    }

    // ---- server catalog -----------------------------------------------------------------------------------------

    private void reloadCatalog() {
        loadModels();
        loadVoices();
    }

    private void loadModels() {
        final int gen = ++modelGen;
        modelsLoading = true;
        modelsFailed = false;
        applyOptions();
        final String host = config.host;
        final Minecraft mc = this.minecraft;
        Thread t = new Thread(() -> {
            List<String> r = OllamaCatalog.models(host);
            mc.execute(() -> {
                if (gen != modelGen) return;
                modelsLoading = false;
                modelsFailed = r == null;
                modelNames = r == null ? List.of() : r;
                applyOptions();
            });
        }, "companionmod-models");
        t.setDaemon(true);
        t.start();
    }

    private void loadVoices() {
        final int gen = ++voiceGen;
        voicesLoading = true;
        applyOptions();
        final String host = config.host, model = config.speechModel;
        final Minecraft mc = this.minecraft;
        Thread t = new Thread(() -> {
            List<String> r = OllamaCatalog.voices(host, model);
            mc.execute(() -> {
                if (gen != voiceGen) return;
                voicesLoading = false;
                voiceNames = r;
                applyOptions();
            });
        }, "companionmod-voices");
        t.setDaemon(true);
        t.start();
    }

    private List<Option> modelOptions(String noneLabel) {
        List<Option> out = new ArrayList<>();
        if (noneLabel != null) out.add(new Option("", noneLabel));
        for (String m : modelNames) out.add(new Option(m, m));
        return out;
    }

    /** Pushes the freshest lists into whichever dropdowns the current tab has. */
    private void applyOptions() {
        if (dropdowns == null) return;
        String modelEmpty = modelsFailed ? "Server unreachable - use the pencil" : "None found - use the pencil";
        if (chatDd != null) { chatDd.options = modelOptions(null); chatDd.loading = modelsLoading; chatDd.emptyText = modelEmpty; }
        if (sttDd != null) { sttDd.options = modelOptions("None (audio model)"); sttDd.loading = modelsLoading; sttDd.emptyText = modelEmpty; }
        if (ttsDd != null) { ttsDd.options = modelOptions("Auto"); ttsDd.loading = modelsLoading; ttsDd.emptyText = modelEmpty; }
        if (voiceDd != null) {
            List<Option> v = new ArrayList<>();
            v.add(new Option("", "Server default"));
            for (String n : voiceNames) v.add(new Option(n, n));
            voiceDd.options = v;
            voiceDd.loading = voicesLoading;
            voiceDd.emptyText = "No voices - ollama pull "
                    + ((config.speechModel == null || config.speechModel.isBlank()) ? "phonon" : config.speechModel)
                    + " (or use the pencil)";
        }
        dropdowns.refreshAll();
    }

    // ---- mic test -----------------------------------------------------------------------------------------------

    private void startMic() {
        if (mic != null) return;
        beginMic(null);
    }

    private void restartMic() {
        beginMic(mic);
    }

    /** Opens a fresh capture on a worker thread, after closing {@code old}, so two lines never fight over one device. */
    private void beginMic(MicCapture old) {
        final MicCapture m = new MicCapture(meter, () -> 1, () -> config.micDevice);
        mic = m;
        micError = "";
        Thread t = new Thread(() -> {
            if (old != null) old.stop();
            meter.reset();
            if (mic != m) return;
            if (!m.start()) { micError = m.lastError(); return; }
            if (mic != m) m.stop();                      // tab was left while the line was still opening
        }, "companionmod-mictest");
        t.setDaemon(true);
        t.start();
    }

    private void stopMic() {
        final MicCapture m = mic;
        mic = null;
        if (m == null) return;
        Thread t = new Thread(() -> { m.stop(); meter.reset(); }, "companionmod-mictest-stop");
        t.setDaemon(true);
        t.start();
    }

    // ---- input --------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (dropdowns.mouseClicked(event.x(), event.y(), event.button())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (dropdowns.mouseScrolled(mouseX, mouseY, scrollY)) return true;
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        for (Dropdown d : editables) {
            if (!d.editing) continue;
            if (k == GLFW.GLFW_KEY_ESCAPE) { cancelEdit(d); return true; }
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { commitEdit(d); return true; }
        }
        if (k == GLFW.GLFW_KEY_ESCAPE && dropdowns.escape()) return true;
        if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && hostBox != null && hostBox.isFocused()) {
            reloadCatalog();
            return true;
        }
        return super.keyPressed(event);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    /** Runs on Done, Esc and when a sub-screen opens: persist and push changes to the server. */
    @Override
    public void removed() {
        stopMic();
        config.host = OllamaCatalog.normalize(config.host);
        if (config.chatModel == null || config.chatModel.isBlank()) config.chatModel = "lfm3-audio:latest";
        if (config.sttModel == null) config.sttModel = "";
        if (config.speechModel == null) config.speechModel = "";
        if (config.speechVoice == null) config.speechVoice = "";
        if (config.micDevice == null) config.micDevice = "";
        if (config.outputDevice == null) config.outputDevice = "";
        config.temperature = Math.max(0f, Math.min(1.5f, config.temperature));
        config.feminineModel = feminine;
        config.speechVolume = Math.max(0f, Math.min(1f, config.speechVolume));
        config.save(configFile);
        ClientVoice.send("set_name", "", config.companionName);
        if (config.companionSkin != null && !config.companionSkin.isBlank()) ClientVoice.send("set_skin", "", config.companionSkin);
        if (feminine != initialFeminine) ClientVoice.send(feminine ? "feminine" : "masculine", "", "");
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ---- rendering ----------------------------------------------------------------------------------------------

    /** The panel is drawn as part of the background so the vanilla blur / darkening can never paint over it. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);       // background + panel + widgets

        int cx = this.width / 2;
        centered(g, this.title.getString(), cx, py + 8, CompanionUi.TEXT_DARK);

        switch (tab) {
            case GENERAL -> centered(g, "V = talk   R = wheel   O = settings", cx, cy + 4 * ROW + 6, CompanionUi.TEXT_MUTED);
            case AI -> {
                text(g, "Server URL (Ollama or OpenAI-compatible)", ix, cy, CompanionUi.TEXT_DARK);
                int sy = cy + 108;
                if (modelsLoading) text(g, "Contacting server...", ix, sy, CompanionUi.TEXT_MUTED);
                else if (modelsFailed) text(g, "Could not reach the server. Press the refresh button.", ix, sy, CompanionUi.TEXT_ERROR);
                else text(g, modelNames.size() + " model(s) on the server", ix, sy, CompanionUi.TEXT_MUTED);
            }
            case MIC -> {
                text(g, "Input level - speak to test", ix, cy + 27, CompanionUi.TEXT_DARK);
                CompanionUi.levelBar(g, ix, cy + 40, iw, 10, meter.level(), meter.peak(), config.silenceGate);
                String err = micError;
                if (err != null && !err.isEmpty()) text(g, this.font.plainSubstrByWidth(err, iw), ix, cy + 55, CompanionUi.TEXT_ERROR);
                else text(g, "Red mark = silence gate (quieter is ignored)", ix, cy + 55, CompanionUi.TEXT_MUTED);
            }
            case VOICE -> { }
        }

        dropdowns.extract(g, mouseX, mouseY);                           // open list goes on top of everything
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int color) {
        g.text(this.font, s, x, y, color, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionSkinScreen.java

```java
package com.example.companionmod.client;

import com.example.companionmod.voice.VoiceConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Skin library in the grey panel style: type any Minecraft player name and the companion wears that skin
 * (downloaded and cached through the vanilla SkinManager). "owner" copies your own skin.
 *
 * <p>Body type and the feminine model live only in {@link CompanionSettingsScreen}.</p>
 * <p>Back / Apply return to {@code parent}; with no parent (opened from the radial wheel) they close the screen.</p>
 */
public final class CompanionSkinScreen extends Screen {
    private static final int PANEL_W = 300, PAD = 12, HEAD_H = 44;

    private final VoiceConfig config;
    private final Path configFile;
    private final Screen parent;
    private EditBox nameField;
    private int px, py, panelW, panelH, ix, iw;

    public CompanionSkinScreen(VoiceConfig config, Path configFile) { this(config, configFile, null); }

    public CompanionSkinScreen(VoiceConfig config, Path configFile, Screen parent) {
        super(Component.literal("Appearance & Skins"));
        this.config = config;
        this.configFile = configFile;
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_W, this.width - 8);
        panelH = HEAD_H + 26 + 24 + 30 + 20 + 12;
        px = (this.width - panelW) / 2;
        py = Math.max(4, (this.height - panelH) / 2);
        ix = px + PAD;
        iw = panelW - 2 * PAD;

        int y = py + HEAD_H;
        nameField = new EditBox(this.font, ix, y, iw, 20, Component.literal("Player name"));
        nameField.setMaxLength(64);
        nameField.setValue(config.companionSkin == null ? "" : config.companionSkin);
        nameField.setHint(Component.literal("Minecraft player name (e.g. Notch)"));
        this.addRenderableWidget(nameField);
        y += 26;

        int colW = (iw - 4) / 2;
        // "owner" resolves synchronously from the local player (and picks up Quick Skin local skins plus the real
        // slim/classic model); a raw name resolves asynchronously via Mojang and shows Steve until it arrives.
        this.addRenderableWidget(Button.builder(Component.literal("Use My Skin"), b -> nameField.setValue("owner"))
                .pos(ix, y).size(colW, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
                .pos(ix + colW + 4, y).size(colW, 20).build());
        y += 24 + 30;                                     // room for the two tip lines

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> back())
                .pos(ix, y).size(iw, 20).build());
    }

    private void apply() {
        String name = nameField.getValue() == null ? "" : nameField.getValue().trim();
        config.companionSkin = name;
        config.save(configFile);
        if (!name.isBlank()) ClientVoice.send("set_skin", "", name);
        back();
    }

    private void back() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);   // null parent = close
    }

    @Override
    public void onClose() { back(); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        CompanionUi.panel(g, px, py, px + panelW, py + panelH);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        String cur = config.companionSkin == null || config.companionSkin.isBlank() ? "default" : config.companionSkin;
        centered(g, "Appearance & Skins", cx, py + 8, CompanionUi.TEXT_DARK);
        centered(g, "Current skin: " + this.font.plainSubstrByWidth(cur, iw - 90), cx, py + 20, CompanionUi.TEXT_MUTED);
        g.text(this.font, "Skin (Minecraft player name)", ix, py + HEAD_H - 11, CompanionUi.TEXT_DARK, false);
        int ty = py + HEAD_H + 26 + 24 + 4;
        g.text(this.font, "Downloaded once, then cached.", ix, ty, CompanionUi.TEXT_MUTED, false);
        g.text(this.font, "Type \"owner\" to copy your own skin.", ix, ty + 12, CompanionUi.TEXT_MUTED, false);
    }

    private void centered(GuiGraphicsExtractor g, String s, int cx, int y, int color) {
        g.text(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionSubtitles.java

```java
package com.example.companionmod.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Audio-clocked, independent word bubbles with a playful feel. Every word is revealed at the same anchor
 * above the speaker and then floats up on its own, but each word gets its own personality: a random size,
 * a slight tilt, sideways drift (left or right), and a pastel color of its own. Words pop in from tiny to
 * full size with a little overshoot bounce. The audio clock arrives in coarse chunks, so it is smoothed
 * exponentially every rendered frame to keep the motion fluid at screen refresh rate.
 */
final class CompanionSubtitles {
    /** {@code y} is the upward lift in local pixels (the renderer translates by {@code -y}). */
    record Word(String text, float x, float y, float scale, float rotDeg, int argb) {}

    private static final long LIFE_MS = 1500;
    private static final long POP_MS = 260;
    private static final float RISE_PX = 20f;     // was 64: bubbles climbed ~1.6 blocks above an already high anchor
    private static final long FADE_MS = 450;
    /** Extra smoothing factor: how much of the audio-clock's coarse jump we accept per rendered frame. */
    private static final float CLOCK_EASE = 0.45f;

    /** Pastel bubbles: one color per word, cycling. Shared with the downed text (same playful style). */
    static final int[] PALETTE = {
            0xFFC9DBFF,   // sky blue
            0xFFFFC2E0,   // pink
            0xFFFFE3B3,   // amber
            0xFFC9F5C8,   // mint
            0xFFE5C8FF,   // lavender
            0xFFFFBFBD,   // rose
            0xFFBFF2F4,   // cyan
            0xFFF6F3B8,   // lemon
    };

    private static final class Anim {
        final int id;
        final String text;
        String[] words;
        float[] reveal;
        float[] sizeMul;     // playful: some words big, some small
        float[] rotDeg;      // per-word tilt
        float[] driftX;      // sideways drift over the lifetime
        float[] liftMul;     // vertical pace variance
        float[] phase;       // wobble phase (rotation over time)
        Anim(int id, String text) { this.id = id; this.text = text; }
    }

    private static final Map<Integer, Anim> ANIMS = new ConcurrentHashMap<>();
    private static String clockText = "";
    private static long durationMs, startedAt, finishedAt;
    private static float progress;
    private static boolean fallback;
    private static long smoothedMs;      // per-frame eased view of the (chunky) audio clock

    private CompanionSubtitles() {}

    static synchronized void audioStarted(String text, long duration) {
        if (text == null || text.isBlank()) return;
        clockText = text;
        durationMs = Math.max(1, duration);
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        progress = 0;
        fallback = false;
        smoothedMs = 0;
    }

    static synchronized void audioProgress(String text, float value) {
        if (text != null && text.equals(clockText)) progress = Math.max(0, Math.min(1, value));
    }

    static synchronized void audioStopped(String text) {
        if (text == null || !text.equals(clockText)) return;
        progress = 1;
        finishedAt = System.currentTimeMillis();
    }

    static synchronized void fallback(String text) {
        if (text == null || text.isBlank()) return;
        clockText = text;
        durationMs = estimateDuration(text);
        startedAt = System.currentTimeMillis();
        finishedAt = 0;
        progress = 0;
        fallback = true;
        smoothedMs = 0;
    }

    /** Text arrival no longer starts an animation; audioStarted/fallback does. */
    static long startFor(int entityId, int speechId, String text) { return 0L; }

    static List<Word> layout(int entityId, int speechId, String text) {
        if (text == null || text.isBlank()) {
            ANIMS.remove(entityId);
            return List.of();
        }
        long raw = elapsed(text);
        if (raw < 0) {                        // no audio clock for this line on this client (not the owner,
            fallback(text);                   // or TTS off): reveal on a timed estimate so bubbles still show
            raw = elapsed(text);
        }
        long elapsed = smoothClock(raw);      // coarse audio ticks -> fluid motion between frames
        Anim a = ANIMS.computeIfAbsent(entityId, ignored -> new Anim(speechId, text));
        if (a.id != speechId || !a.text.equals(text)) {
            a = new Anim(speechId, text);
            ANIMS.put(entityId, a);
        }
        if (a.words == null) build(entityId, a);

        List<Word> out = new ArrayList<>(a.words.length);
        for (int i = 0; i < a.words.length; i++) {
            long age = elapsed - (long) (a.reveal[i] * durationMs);
            if (age < 0) break;
            if (age >= LIFE_MS) continue;
            float life = age / (float) LIFE_MS;
            float travel = Math.min(1, age / (float) LIFE_MS);              // float up slowly for the word's whole life
            float walk = (1 - (1 - travel) * (1 - travel));                 // ease-out rise/drift fraction
            float lift = RISE_PX * a.liftMul[i] * walk;
            float pop = Math.min(1, age / (float) POP_MS);
            float scale = (0.2f + 0.8f * popIn(pop)) * a.sizeMul[i];        // small -> big with a bounce
            scale = Math.max(0.16f, scale);
            float wobble = (float) Math.sin(age * 0.0045 + a.phase[i]) * 1.6f;
            float fade = age > LIFE_MS - FADE_MS
                    ? 1 - (age - (LIFE_MS - FADE_MS)) / (float) FADE_MS : 1;
            float alpha = Math.min(1, age / 70f) * fade;
            if (alpha < 0.03f) continue;     // vanilla text treats alpha < 4/255 as fully OPAQUE: skip instead of flashing at the end of the fade
            float flash = Math.max(0, 1 - age / 150f);                      // bright right at the pop
            int argb = blend(((int) (alpha * 255) << 24) | (PALETTE[i % PALETTE.length] & 0xFFFFFF), flash);
            float rot = a.rotDeg[i] + wobble;
            out.add(new Word(a.words[i], a.driftX[i] * walk, lift, scale, rot, argb));
        }
        return out;
    }

    /** easeOutBack: starts tiny, shoots past full size and settles - the playful pop. */
    private static float popIn(float p) {
        float c = 1.70158f, c3 = c + 1;
        float q = p - 1;
        return 1 + c3 * q * q * q + c * q * q;
    }

    /** Pushes the base color toward white during the first moments of a word. */
    static int blend(int base, float flash) {
        if (flash <= 0) return base;
        int r = (base >> 16) & 0xFF, g = (base >> 8) & 0xFF, b = base & 0xFF;
        r += (int) ((255 - r) * flash);
        g += (int) ((255 - g) * flash);
        b += (int) ((255 - b) * flash);
        return (base & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static synchronized void clear() {
        ANIMS.clear();
        clockText = "";
        finishedAt = 0;
        smoothedMs = 0;
    }

    private static synchronized long elapsed(String text) {
        if (text == null || !sameLine(text, clockText) || clockText.isBlank()) return -1;
        long now = System.currentTimeMillis();
        if (finishedAt != 0) return durationMs + now - finishedAt;
        long wall = now - startedAt;
        // The timed estimate used to be clamped at durationMs, which froze the last words at a young age forever
        // (no fade, stuck above the head). Time now keeps running so every word ages out and fades.
        if (fallback) return wall;
        // Audio mode: if the "stopped" callback never arrives, fall back to wall time after a grace period.
        return wall > durationMs + 2000 ? wall : (long) (progress * durationMs);
    }

    /** The voice queue clips lines to 200 chars, so the audio clock may carry a prefix of the synced line. */
    private static boolean sameLine(String text, String clock) {
        return text.equals(clock) || (clock.length() >= 200 && text.startsWith(clock));
    }

    /** Exponential approach to the raw audio clock; snaps on any backward jump (new line / rewind). */
    private static synchronized long smoothClock(long target) {
        if (target < smoothedMs) {
            smoothedMs = target;
        } else {
            smoothedMs = Math.round(smoothedMs + (target - smoothedMs) * CLOCK_EASE);
        }
        return smoothedMs;
    }

    private static long estimateDuration(String text) {
        long total = 250;
        for (String word : text.trim().split("\\s+")) total += weight(word) * 28;
        return Math.max(800, total);
    }

    private static float weight(String word) {
        float value = Math.max(2, word.length());
        if (word.matches(".*[.!?]$")) value += 5;
        else if (word.matches(".*[,;:]$")) value += 2.5f;
        return value;
    }

    /** Splits the line into words, spreads reveal times across the duration and rolls per-word personality. */
    private static void build(int entityId, Anim a) {
        Random rng = new Random(a.id * 88172645463325252L + entityId + 1);
        String[] words = a.text.trim().split("\\s+");
        int n = words.length;
        a.words = words;
        a.reveal = new float[n];
        a.sizeMul = new float[n];
        a.rotDeg = new float[n];
        a.driftX = new float[n];
        a.liftMul = new float[n];
        a.phase = new float[n];
        float totalWeight = 0;
        for (String word : words) totalWeight += weight(word);
        float at = 0;
        for (int i = 0; i < n; i++) {
            a.reveal[i] = totalWeight == 0 ? 0 : at / totalWeight;
            at += weight(words[i]);
            float kind = rng.nextFloat();                 // short words get to be bouncy and big more often
            a.sizeMul[i] = words[i].length() <= 3 && kind < 0.55f
                    ? 1.15f + rng.nextFloat() * 0.5f      // 1.15 - 1.65
                    : 0.75f + rng.nextFloat() * 0.5f;     // 0.75 - 1.25
            a.rotDeg[i] = (rng.nextBoolean() ? 1 : -1) * (2.5f + rng.nextFloat() * 9.5f);  // 2.5..x tilt
            a.driftX[i] = (rng.nextBoolean() ? 1 : -1) * (7f + rng.nextFloat() * 16f);     // sideways drift
            a.liftMul[i] = 0.72f + rng.nextFloat() * 0.4f;
            a.phase[i] = rng.nextFloat() * 6.283f;
        }
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionUi.java

```java
package com.example.companionmod.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers for the vanilla-grey "Voice Chat mod" look: bevelled panel and the green-to-orange level bar. */
final class CompanionUi {
    static final int TEXT_DARK = 0xFF404040;   // title / labels on the grey panel
    static final int TEXT_MUTED = 0xFF6B6B6B;
    static final int TEXT_ERROR = 0xFFAA0000;

    private CompanionUi() {}

    /** Light-grey bevelled container, like vanilla inventory screens and the Voice Chat settings. */
    static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, 0xFF000000);                         // outer border
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFFC6C6C6);         // body
        g.fill(x0 + 1, y0 + 1, x1 - 2, y0 + 3, 0xFFFFFFFF);         // top highlight
        g.fill(x0 + 1, y0 + 1, x0 + 3, y1 - 2, 0xFFFFFFFF);         // left highlight
        g.fill(x0 + 2, y1 - 3, x1 - 1, y1 - 1, 0xFF555555);         // bottom shadow
        g.fill(x1 - 3, y0 + 2, x1 - 1, y1 - 1, 0xFF555555);         // right shadow
    }

    /** Horizontal level bar. level/peak are 0..1; gate is the silence-threshold marker (0..1, or negative to hide). */
    static void levelBar(GuiGraphicsExtractor g, int x, int y, int w, int h, float level, float peak, float gate) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        g.fill(x, y, x + w, y + h, 0xFF2B2B2B);
        int lit = Math.round(Math.max(0f, Math.min(1f, level)) * w);
        for (int i = 0; i < lit; i += 2) {                          // 2px columns: cheap horizontal gradient
            g.fill(x + i, y, x + Math.min(i + 2, lit), y + h, gradient((i + 1f) / w));
        }
        int p = Math.round(Math.max(0f, Math.min(1f, peak)) * w);
        if (p > 1) g.fill(x + p - 1, y, x + p, y + h, 0xFFFFFFFF);
        if (gate >= 0f) {
            int gx = x + Math.round(Math.min(1f, gate) * w);
            g.fill(gx, y - 2, gx + 1, y + h + 2, 0xFFFF3030);        // threshold tick
        }
    }

    /** Green -> yellow -> orange. */
    private static int gradient(float f) {
        int r, gr;
        if (f < 0.6f) { float t = f / 0.6f; r = lerp(0x20, 0xE8, t); gr = lerp(0xC8, 0xE0, t); }
        else { float t = (f - 0.6f) / 0.4f; r = lerp(0xE8, 0xFF, t); gr = lerp(0xE0, 0x7A, t); }
        return 0xFF000000 | (r << 16) | (gr << 8);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * Math.max(0f, Math.min(1f, t)));
    }
}
```

## common\src\main\java\com\example\companionmod\client\CompanionWorldContext.java

```java
package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/** A bounded, real client-world observation snapshot for the language model. */
final class CompanionWorldContext {
    private CompanionWorldContext() {}

    static String build(Minecraft mc, CompanionEntity companion) {
        if (mc.player == null || mc.level == null) return "World unavailable.";
        StringBuilder out = new StringBuilder(5000);
        out.append("LIVE MINECRAFT OBSERVATIONS (not suggestions or memories).\n");
        out.append("Dimension=").append(mc.level.dimension())
                .append(" dayTime=").append(mc.level.getOverworldClockTime() % 24000)
                .append(" night=").append(mc.level.isDarkOutside())
                .append(" raining=").append(mc.level.isRaining()).append('\n');
        out.append("Owner name=").append(mc.player.getName().getString())
                .append(" pos=").append(pos(mc.player.blockPosition()))
                .append(" health=").append(Math.round(mc.player.getHealth()))
                .append('/').append(Math.round(mc.player.getMaxHealth()))
                .append(" hunger=").append(mc.player.getFoodData().getFoodLevel())
                .append(" held=").append(item(mc.player.getMainHandItem())).append('\n');
        if (companion == null) {
            out.append("No owned companion is currently loaded.\n");
            return out.toString();
        }
        out.append("Companion name=").append(companion.getName().getString())
                .append(" pos=").append(pos(companion.blockPosition()))
                .append(" health=").append(Math.round(companion.getHealth()))
                .append('/').append(Math.round(companion.getMaxHealth()))
                .append(" moveMode=").append(companion.moveMode())
                .append(" stance=").append(companion.stance())
                .append(" injured=").append(companion.isInjured())
                .append(" sitting=").append(companion.isSitting())
                .append(" light=").append(mc.level.getMaxLocalRawBrightness(companion.blockPosition()))
                .append(" held=").append(item(companion.getMainHandItem())).append('\n');
        out.append("Current tasks: mine=").append(companion.seekBlock())
                .append(" pickupRequested=").append(companion.isPickupRequested())
                .append(" pickupEnabled=").append(companion.isPickupEnabled())
                .append(" farmUntil=").append(companion.farmUntil())
                .append(" fishUntil=").append(companion.fishUntil())
                .append(" build=").append(companion.buildSchematic())
                .append(" gameTime=").append(mc.level.getGameTime()).append('\n');

        out.append("Companion inventory:\n");
        for (int i = 0; i < companion.storage().getContainerSize(); i++) {
            ItemStack stack = companion.storage().getItem(i);
            if (!stack.isEmpty()) out.append(" slot ").append(i).append(' ').append(item(stack)).append('\n');
        }

        out.append("Nearby entities (absolute positions):\n");
        int entityCount = 0;
        for (Entity entity : mc.level.getEntitiesOfClass(Entity.class,
                companion.getBoundingBox().inflate(24), e -> e != companion && e != mc.player)) {
            if (++entityCount > 28) break;
            String category = entity instanceof Monster ? "hostile"
                    : entity instanceof Animal ? "animal"
                    : entity instanceof ItemEntity ? "drop" : "other";
            out.append(' ').append(category).append(' ')
                    .append(EntityType.getKey(entity.getType()))
                    .append(" pos=").append(pos(entity.blockPosition()));
            if (entity instanceof ItemEntity drop) out.append(" item=").append(item(drop.getItem()));
            out.append('\n');
        }
        if (entityCount == 0) out.append(" none within 24 blocks\n");

        out.append("Useful nearby blocks (absolute positions):\n");
        int blockCount = 0;
        BlockPos centre = companion.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-16, -7, -16), centre.offset(16, 7, 16))) {
            BlockState state = mc.level.getBlockState(p);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!interesting(id)) continue;
            out.append(' ').append(id).append(" pos=").append(pos(p)).append('\n');
            if (++blockCount >= 36) {
                out.append(" block list truncated; explore or move for more observations\n");
                break;
            }
        }
        if (blockCount == 0) out.append(" none detected in the 33x15x33 scan\n");
        out.append("Use only observed IDs and supported plan operations. Never invent items, blocks, entities or capabilities.\n");
        return out.toString();
    }

    private static boolean interesting(String id) {
        String path = id.toLowerCase(Locale.ROOT);
        return path.contains("_ore") || path.endsWith("_log") || path.endsWith("_wood")
                || path.contains("leaves") || path.contains("farmland")
                || path.equals("minecraft:wheat") || path.equals("minecraft:carrots")
                || path.equals("minecraft:potatoes") || path.equals("minecraft:beetroots")
                || path.equals("minecraft:water") || path.equals("minecraft:lava")
                || path.contains("chest") || path.contains("furnace")
                || path.equals("minecraft:crafting_table");
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    private static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
```

## common\src\main\java\com\example\companionmod\client\PcmStreamSound.java

```java
package com.example.companionmod.client;

import com.example.companionmod.CompanionMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Plays decoded TTS PCM through Minecraft's own sound engine (OpenAL) instead of a raw Java
 * {@code SourceDataLine}. Because the audio runs on real engine channels, it obeys the game's audio
 * sliders, automatically pauses with the game (Esc), and mods like Sound Physics Remastered can add
 * their room echo/reverb to the companion's voice like any other in-world sound.
 *
 * <p>The instance claims an invented sound event id and resolves it by handing back a single synthetic
 * {@link Sound} marked {@code stream=true}. NeoForge's patched {@link SoundInstance} interface declares
 * {@code getStream(SoundBufferLibrary, Sound, boolean)}; when that hook is present (NeoForge runtime) the
 * engine calls it and we feed the pre-decoded 16-bit little-endian PCM straight from memory. On a vanilla
 * runtime the hook is absent (detected by reflection), so the caller falls back to the {@code SourceDataLine}
 * path instead. Playback is relative and un-attenuated so the voice is always audible at the player, while
 * still flowing through an engine channel that mods can process.</p>
 */
public class PcmStreamSound extends AbstractSoundInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger("CompanionMod/Tts");
    private static final Identifier EVENT_ID = CompanionMod.id("spoken_voice");

    /** True on runtimes whose sound engine streams per-instance PCM (NeoForge's SoundInstance patch). */
    private static final boolean ENGINE_STREAM_HOOK = probeStreamHook();

    /** Bridges engine-side playback events back to SpeechPlayer (called from engine threads). */
    public interface Listener {
        void onProgress(float progress);                       // 0..1 through the PCM data
        void onLevel(byte[] data, int offset, int length);     // raw chunk for the loudness meter
        void onStop();                                         // exactly once, whatever the exit path
    }

    private final byte[] pcm;                                  // 16-bit LE interleaved frames
    private final int sampleRate, channels;
    private final BooleanSupplier stopFlag;
    private final Listener listen;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final AtomicBoolean stopSent = new AtomicBoolean();
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private volatile boolean reading;                          // engine has consumed at least one chunk
    private int consumed;

    private PcmStreamSound(byte[] pcm, int sampleRate, int channels, float volume,
                           BooleanSupplier stopFlag, Listener listen) {
        super(EVENT_ID, SoundSource.VOICE, SoundInstance.createUnseededRandom());
        this.pcm = pcm;
        this.sampleRate = Math.max(4000, sampleRate);
        this.channels = Math.max(1, Math.min(2, channels));
        this.stopFlag = stopFlag;
        this.listen = listen;
        this.volume = Math.max(0.01f, Math.min(1f, volume));   // engine multiplies instance volume; keep Sound at 1.0
        this.pitch = 1.0f;
        this.relative = true;                                  // voice follows the listener; always audible
        this.attenuation = SoundInstance.Attenuation.NONE;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {                               // anchor at the player so SPR hears an in-room source
            this.x = mc.player.getX();
            this.y = mc.player.getY();
            this.z = mc.player.getZ();
        }
        this.sound = new Sound(EVENT_ID, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1,
                Sound.Type.FILE, true /* stream */, false /* preload */, 16);
    }

    private static boolean probeStreamHook() {
        try {
            Method m = SoundInstance.class.getMethod("getStream", SoundBufferLibrary.class, Sound.class, boolean.class);
            return m != null;
        } catch (Throwable t) {
            return false;                                      // vanilla runtime: engine streams by file location only
        }
    }

    /** Whether this runtime can stream PCM through the engine at all. */
    public static boolean engineStreamingSupported() { return ENGINE_STREAM_HOOK; }

    /**
     * Tries the engine; returns false (nothing played, caller should fall back) if the runtime lacks the
     * hook, the engine wouldn't start, or the engine never actually reads the stream. Blocks the calling
     * thread until the line finishes, is stopped, or times out.
     */
    public static boolean play(byte[] pcm, int sampleRate, int channels, float volume, long durationMs,
                               BooleanSupplier stopFlag, Listener listen) {
        if (!ENGINE_STREAM_HOOK) return false;
        Minecraft mc = Minecraft.getInstance();
        SoundManager manager = mc == null ? null : mc.getSoundManager();
        if (manager == null) return false;
        PcmStreamSound instance = new PcmStreamSound(pcm, sampleRate, channels, volume, stopFlag, listen);
        SoundEngine.PlayResult result;
        try {
            result = manager.play(instance);
        } catch (Throwable t) {
            LOGGER.debug("Engine playback rejected TTS: {}", t.toString());
            return false;
        }
        if (result != SoundEngine.PlayResult.STARTED) {
            LOGGER.debug("Engine playback NOT started ({})", result);
            return false;
        }
        // Watchdog: if the engine never consumes a single chunk (bad device, unsupported format, vanilla
        // runtime without the hook), give up quickly so the caller can fall back to the SourceDataLine path.
        long noDataDeadline = System.currentTimeMillis() + 2500;
        while (!instance.reading && !instance.done.isDone() && System.currentTimeMillis() < noDataDeadline) {
            if (stopFlag.getAsBoolean()) { instance.requestEngineStop(); return false; }
            try {
                instance.done.get(100, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) { }
        }
        if (!instance.reading) {
            instance.requestEngineStop();
            instance.reportStop();
            return false;
        }
        long deadline = System.currentTimeMillis() + durationMs + 8000;
        while (!instance.done.isDone() && System.currentTimeMillis() < deadline) {
            try {
                instance.done.get(100, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) { }
            if (stopFlag.getAsBoolean()) instance.requestEngineStop();
        }
        instance.reportStop();                                 // idempotent: subtitle clock finishes either way
        return true;                                           // started and handled; no fallback replay
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        // The engine needs a resolved event before it will play; provide our synthetic single sound.
        WeighedSoundEvents event = new WeighedSoundEvents(getIdentifier(), null);
        event.addSound(this.sound);
        return event;
    }

    // No @Override: the method only exists on NeoForge's patched SoundInstance. It compiles against the
    // vanilla interface as a plain method and is dispatched virtually at runtime on NeoForge.
    @SuppressWarnings("unused")
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new MemoryAudioStream());
    }

    /** Reads the held PCM in engine-sized chunks, reporting progress and loudness as it goes. */
    private class MemoryAudioStream implements AudioStream {
        private final AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);

        @Override
        public AudioFormat getFormat() { return format; }

        @Override
        public ByteBuffer read(int capacity) throws IOException {
            reading = true;
            if (stopFlag.getAsBoolean()) requestEngineStop();
            if (consumed >= pcm.length) {
                reportStop();                                  // engine stops the channel and closes the stream
                return null;                                   // null = EOF, exactly what pumpBuffers checks for
            }
            int len = (int) Math.min(capacity <= 0 ? 4096 : capacity, (long) pcm.length - consumed);
            int offset = consumed;
            consumed += len;
            try {
                listen.onProgress(consumed / (float) pcm.length);
                listen.onLevel(pcm, offset, len);
            } catch (Throwable ignored) { }
            // Direct buffer, like every vanilla stream: heap buffers crash alBufferData on some OpenAL builds.
            ByteBuffer out = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN);
            out.put(pcm, offset, len).flip();
            return out;
        }

        @Override
        public void close() { reportStop(); }
    }

    /** Asks the game thread to stop the channel; the engine then closes the stream -> reportStop(). */
    private void requestEngineStop() {
        if (stopSent.compareAndSet(false, true)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.execute(() -> mc.getSoundManager().stop(this));
        }
    }

    /** Exactly-once tail of playback: informs the subtitle clock and releases the blocking wait. */
    private void reportStop() {
        if (!reported.compareAndSet(false, true)) return;
        try {
            listen.onStop();
        } catch (Throwable ignored) { }
        done.complete(null);
    }
}
```

## common\src\main\java\com\example\companionmod\client\VoiceHud.java

```java
package com.example.companionmod.client;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.PushToTalkController;
import com.example.companionmod.voice.VoiceState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Microphone panel + a small downed-companion revive panel. Only draws when relevant. */
final class VoiceHud {
    private static final int W = 184, H = 36, SEGS = 24, SEG_W = 5, SEG_GAP = 1;
    private static float fade;                                           // 0..1, eased so the panel slides in and out
    private static VoiceState lastState = VoiceState.IDLE;

    private VoiceHud() {}

    static void render(GuiGraphicsExtractor g, PushToTalkController c, CompanionEntity companion) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (c == null) { fade = 0f; lastState = VoiceState.IDLE; }         // left the world while the panel was showing
        VoiceState state = c == null ? VoiceState.IDLE : c.state();
        if (state != VoiceState.IDLE) lastState = state;
        fade += ((state == VoiceState.IDLE ? 0f : 1f) - fade) * 0.25f;
        boolean subtitleOn = System.currentTimeMillis() < ClientVoice.subtitleUntil;
        boolean injured = companion != null && companion.isInjured();
        boolean reviveHint = injured || (companion != null && companion.reviveProgress() > 0);
        if (fade < 0.02f && !subtitleOn && !reviveHint) return;

        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int x = (sw - W) / 2;

        if (subtitleOn) {
            String s = ClientVoice.subtitle;
            text(g, font, s, (sw - font.width(s)) / 2, sh - 84 - H, argb(230, 255, 255, 255));
        }
        if (injured) renderInjured(g, font, sw, sh, companion);
        if (c == null || fade < 0.02f) return;

        int y = sh - 84;
        int a = (int) (fade * 255);
        int accent = switch (lastState) {
            case LISTENING -> argb(a, 235, 70, 70);
            case THINKING -> argb(a, 240, 200, 80);
            default -> argb(a, 240, 240, 240);
        };
        long t = System.currentTimeMillis();

        g.fill(x, y, x + W, y + H, argb((int) (fade * 196), 16, 16, 16));
        g.fill(x, y, x + W, y + 1, accent);
        g.fill(x, y + H - 1, x + W, y + H, argb((int) (fade * 200), 56, 56, 56));
        g.fill(x, y, x + 1, y + H, argb((int) (fade * 200), 56, 56, 56));
        g.fill(x + W - 1, y, x + W, y + H, argb((int) (fade * 200), 56, 56, 56));

        // microphone icon, pulsing while recording
        int ix = x + 10, iy = y + 9;
        int pulse = lastState == VoiceState.LISTENING ? (int) (35 * (0.5 + 0.5 * Math.sin(t / 180.0))) : 0;
        int ic = argb(a, 225 + Math.min(30, pulse), 225 - pulse, 225 - pulse);
        g.fill(ix + 3, iy, ix + 7, iy + 8, ic);
        g.fill(ix + 1, iy + 5, ix + 2, iy + 10, ic);
        g.fill(ix + 8, iy + 5, ix + 9, iy + 10, ic);
        g.fill(ix + 1, iy + 10, ix + 9, iy + 11, ic);
        g.fill(ix + 4, iy + 11, ix + 6, iy + 14, ic);

        String label = switch (lastState) {
            case LISTENING -> String.format("Listening  %.1fs", c.listeningMillis() / 1000.0);
            case THINKING -> "Thinking" + ".".repeat((int) (t / 300 % 4));
            default -> "Speaking";
        };
        text(g, font, label, x + 28, y + 7, argb(a, 235, 235, 240));

        float level = c == null ? 0f : c.level(), peak = c == null ? 0f : c.peak();
        int bx = x + 28, by = y + 21;
        int peakIdx = Math.min(SEGS - 1, (int) (peak * SEGS));
        int scan = (int) (t / 70 % (SEGS + 6));                                       // "thinking" scanner position
        for (int i = 0; i < SEGS; i++) {
            int sx = bx + i * (SEG_W + SEG_GAP);
            boolean lit;
            if (lastState == VoiceState.THINKING) lit = Math.abs(i - (scan - 3)) <= 1;
            else lit = (i + 0.5f) / SEGS <= level;
            int col;
            if (lastState == VoiceState.SPEAKING) col = argb(a, 235, 235, 235);
            else if (lastState == VoiceState.THINKING) col = argb(a, 240, 200, 80);
            else col = i < 14 ? argb(a, 80, 220, 120) : i < 20 ? argb(a, 240, 210, 80) : argb(a, 240, 80, 70);
            g.fill(sx, by, sx + SEG_W, by + 9, lit ? col : argb((int) (fade * 40), 255, 255, 255));
            if (lastState != VoiceState.THINKING && i == peakIdx && peak > level + 0.03f)
                g.fill(sx, by, sx + SEG_W, by + 2, argb(a, 255, 255, 255));            // peak-hold tick
        }
    }

    private static void renderInjured(GuiGraphicsExtractor g, Font font, int sw, int sh, CompanionEntity companion) {
        int seconds = (companion.injuredLeftTicks() + 19) / 20;
        String hint = companion.reviveProgress() > 0.02f
                ? "Reviving... " + Math.round(companion.reviveProgress() * 100) + "%"
                : "Your companion is down — hold LEFT CLICK on it to revive (" + seconds + "s)";
        int w = font.width(hint);
        int x = (sw - w) / 2, y = sh - 108;
        g.fill(x - 8, y - 4, x + w + 8, y + 12, 0xC4101010);
        g.fill(x - 8, y - 4, x + w + 8, y - 3, 0xFFC85A5A);
        g.fill(x - 8, y + 11, x + w + 8, y + 12, 0xC8383838);
        g.fill(x - 8, y - 4, x - 7, y + 12, 0xC8383838);
        g.fill(x + w + 7, y - 4, x + w + 8, y + 12, 0xC8383838);
        g.text(font, hint, x, y, 0xFFFFD0D0, true);
    }

    private static int argb(int a, int r, int gr, int b) {
        return (Math.max(0, Math.min(255, a)) << 24) | (clamp(r) << 16) | (clamp(gr) << 8) | clamp(b);
    }
    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** Single text call site. 26.1-CHECK: GuiGraphics.drawString became GuiGraphicsExtractor.text(...) in the 26.1 rendering rewrite. */
    private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color) {
        g.text(font, s, x, y, color, true);
    }
}
```

## common\src\main\java\com\example\companionmod\command\CompanionCommand.java

```java
package com.example.companionmod.command;

import java.util.Locale;

/** Everything the player can order the companion to do. Minecraft-free so the voice layer can use it. */
public enum CompanionCommand {
    NONE, FOLLOW, STAY, SIT, STAND, ATTACK, GUARD, DEFENSIVE, PASSIVE, COME, STOP, CONTINUE,
    INVENTORY, MINE, BUILD, FARM, PATROL, HEAL, EQUIP, TRADE, LOOT, SCOUT,
    WANDER, EXPLORE, DEFEND, RETREAT, WAIT, SNEAK, JUMP, DANCE, SLEEP, WAKE,
    GIVE, TAKE, DROP, PICKUP, OPEN, CLOSE, CRAFT, SMELT, BREW, ENCHANT,
    FISH, COOK, FIGHT, FLEE, HIDE, SEEK, FIND, SEARCH, TRACK, HUNT,
    SEEK_BLOCK, SET_NAME, REVIVE, PLAN,
    REMEMBER, FORGET, SET_SKIN, FEMININE, MASCULINE, PLANT, SURFACE;

    /** False for appearance/UI/memory/revive packets: they are not a new gameplay intent and must not pause or cancel a plan. */
    public boolean interruptsPlanning() {
        return switch (this) {
            case NONE, PLAN, CONTINUE, INVENTORY, SET_NAME, SET_SKIN, FEMININE, MASCULINE,
                    REMEMBER, FORGET, REVIVE -> false;
            default -> true;
        };
    }

    /** Lenient parser: accepts the enum name plus common synonyms the model might produce. */
    public static CompanionCommand parse(String raw) {
        if (raw == null) return NONE;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (s) {
            case "follow": case "follow_me": case "come_along": return FOLLOW;
            case "stay": case "wait": case "hold": case "hold_position": case "stand_ground": return STAY;
            case "sit": case "rest": case "sit_down": return SIT;
            case "stand": case "stand_up": case "get_up": return STAND;
            case "attack": case "kill": case "engage": return ATTACK;
            case "guard": case "protect": case "aggressive": return GUARD;
            case "defensive": case "defend": case "defend_me": case "protect_me": return DEFENSIVE;
            case "passive": case "peaceful": case "dont_fight": case "stand_down": return PASSIVE;
            case "come": case "come_here": case "teleport": case "here": return COME;
            case "stop": case "cancel": case "cease": case "abort": case "halt":
            case "nevermind": case "never_mind": case "stop_everything": case "stop_working":
            case "stop_mining": case "cancel_task": case "cancel_plan": case "stop_attacking": return STOP;
            case "continue": case "resume": case "keep_going": case "carry_on": case "unpause": return CONTINUE;
            case "inventory": case "inv": case "items": case "bag": case "open_inventory": return INVENTORY;
            case "mine": case "dig": case "break": case "harvest": return MINE;
            case "build": case "construct": case "place": case "build_structure": return BUILD;
            case "farm": case "farming": case "harvest_crops": return FARM;
            case "plant": case "replant": case "plant_sapling": case "plant_saplings": case "plant_trees": case "reforest": return PLANT;
            case "surface": case "dig_up": case "come_up": case "to_the_surface": case "reach_the_surface": case "daylight": return SURFACE;
            case "patrol": case "scout": case "scouting": case "lookout": return PATROL;
            case "heal": case "healing": case "rest_heal": return HEAL;
            case "equip": case "equipment": case "gear": case "armor": return EQUIP;
            case "trade": case "trading": case "barter": return TRADE;
            case "loot": case "collect": case "gather": return LOOT;
            case "explore": case "explore_area": return EXPLORE;
            case "wander": case "wander_around": case "roam": return WANDER;
            case "defense": return DEFEND;
            case "retreat": case "run_away": case "escape": return RETREAT;
            case "hold_on": case "pause": return WAIT;
            case "sneak": case "sneaking": case "stealth": return SNEAK;
            case "jump": case "jumping": case "leap": return JUMP;
            case "dance": case "celebrate": case "party": return DANCE;
            case "sleep": case "sleeping": case "rest_sleep": return SLEEP;
            case "wake": case "wake_up": case "awake": return WAKE;
            case "give": case "hand": case "hand_over": return GIVE;
            case "take": case "grab": case "hold_item": return TAKE;
            case "drop": case "drop_item": case "throw": return DROP;
            case "pickup": case "pick_up": case "grab_item": return PICKUP;
            case "open": case "open_door": case "open_chest": return OPEN;
            case "close": case "close_door": case "close_chest": return CLOSE;
            case "craft": case "crafting": case "make": return CRAFT;
            case "smelt": case "smelting": return SMELT;
            case "brew": case "brewing": case "potion": return BREW;
            case "enchant": case "enchanting": case "enchant_item": return ENCHANT;
            case "fish": case "fishing": case "catch_fish": return FISH;
            case "cook": case "cooking": case "prepare_food": return COOK;
            case "fight": case "fighting": case "combat": return FIGHT;
            case "flee": case "fleeing": case "run": return FLEE;
            case "hide": case "hiding": case "conceal": return HIDE;
            case "seek": case "seeking": case "look_for": return SEEK;
            case "find": case "finding": case "locate": return FIND;
            case "search": case "searching": case "scan": return SEARCH;
            case "seek_block": case "find_block": case "look_for_block": case "go_find": case "dig_for": return SEEK_BLOCK;
            case "track": case "tracking": case "follow_track": return TRACK;
            case "hunt": case "hunting": case "stalk": return HUNT;
            case "remember": case "memorize": case "remember_that": return REMEMBER;
            case "forget": case "forget_that": return FORGET;
            case "set_name": case "rename": case "name": case "call_you": return SET_NAME;
            case "set_skin": case "skin": case "change_skin": case "appearance": return SET_SKIN;
            case "feminine": case "female": case "feminine_model": return FEMININE;
            case "masculine": case "male": case "masculine_model": return MASCULINE;
            case "revive": case "revive_me": case "rescue": case "help_me": return REVIVE;
            case "plan": case "goal": case "autonomy": return PLAN;
            default: return NONE;
        }
    }
}
```

## common\src\main\java\com\example\companionmod\CompanionMod.java

```java
package com.example.companionmod;

import net.minecraft.resources.Identifier;

import java.nio.file.Path;

public final class CompanionMod {
    public static final String MOD_ID = "companionmod";
    private static volatile Path configDir = Path.of("config");
    private CompanionMod() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path); // 26.1-CHECK: was ResourceLocation before 26.1
    }

    /** The loader's real config directory (set from each loader's entrypoint; never rely on the working directory). */
    public static Path configDir() { return configDir; }

    public static void setConfigDir(Path dir) { if (dir != null) configDir = dir; }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionBuildGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Build &lt;name&gt;": places a schematic from {@code <config>/companionmod/schematics/<n>.json} one block
 * at a time, consuming matching items from the bag, bottom layer first. Steps are relative to the anchor.
 * When a needed block is not in the bag the companion waits and says what it needs; refilling the bag resumes the build.
 *
 * <p>File format: {@code [{"x":0,"y":0,"z":0,"block":"minecraft:cobblestone"}, ...]} (a bare array, or an
 * object with a "blocks" array). The file is parsed off the server thread, limited to 2 MB / 20 000 blocks /
 * +-96 offset, and spots the owner may not edit (spawn protection) are skipped.</p>
 */
public class CompanionBuildGoal extends Goal {
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_STEPS = 20_000, MAX_OFFSET = 96;

    private record Step(int dx, int dy, int dz, Block block) {}
    private record Loaded(List<Step> steps, String error) {}

    private final CompanionEntity mob;
    private final List<Step> steps = new ArrayList<>();
    private BlockPos base;
    private int index, blocked;
    private int speakCooldown;
    private boolean loaded;
    private CompletableFuture<Loaded> loading;

    public CompanionBuildGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        return !mob.buildSchematic().isBlank() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        steps.clear();
        index = 0;
        blocked = 0;
        base = mob.anchor().immutable();
        loaded = false;
        loading = null;
        speakCooldown = 0;
        mob.speak("Starting the build.");
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (!loaded) {
            if (!pollSchematic()) return;
            loaded = true;
            if (steps.isEmpty()) { mob.clearBuild(); return; }   // pollSchematic already explained why
        }
        while (index < steps.size()) {
            Step s = steps.get(index);
            BlockPos pos = base.offset(s.dx, s.dy, s.dz);
            BlockState there = level.getBlockState(pos);
            if (there.getBlock() == s.block() || !there.isAir()) { index++; continue; }   // done, or occupied: skip
            if (pos.closerToCenterThan(mob.position(), 0.9)) { index++; continue; }  // never entomb ourselves
            if (!CompanionProtection.canModify(level, mob, pos)) { blocked++; index++; continue; }
            if (mob.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 4.5 * 4.5) {
                mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.1);
                return;
            }
            if (takeFromBag(s.block().asItem()).isEmpty()) {
                // Out of material: hold position, tell the player (throttled), retry once the bag is refilled.
                mob.getNavigation().stop();
                if (--speakCooldown <= 0) {
                    speakCooldown = 120;
                    mob.speak("I need more " + s.block().getName().getString() + " to finish the build.");
                }
                return;
            }
            mob.getNavigation().stop();
            mob.swing(InteractionHand.MAIN_HAND);
            level.setBlock(pos, s.block().defaultBlockState(), 3);
            index++;
        }
        mob.speak(blocked > 0 ? "Build finished, but " + blocked + " spots are protected." : "Build finished!");
        mob.clearBuild();
    }

    /** Starts / polls the background parse. Returns true once the result (possibly empty) is in {@link #steps}. */
    private boolean pollSchematic() {
        if (loading == null) {
            String name = mob.buildSchematic();
            Path file = CompanionMod.configDir().resolve("companionmod").resolve("schematics").resolve(name + ".json");
            if (!Files.isRegularFile(file)) {
                if (--speakCooldown <= 0) {
                    speakCooldown = 120;
                    mob.speak("I cannot find a schematic called " + name
                            + ". Put a JSON file in config/companionmod/schematics.");
                }
                return false;
            }
            loading = CompletableFuture.supplyAsync(() -> parse(file));
            return false;
        }
        if (!loading.isDone()) return false;
        Loaded result;
        try {
            result = loading.join();
        } catch (RuntimeException e) {
            result = new Loaded(List.of(), "I could not read that schematic file.");
        }
        loading = null;
        steps.addAll(result.steps());
        if (!result.error().isBlank()) mob.speak(result.error());
        return true;
    }

    /** Runs on a worker thread: touches no world state. */
    private static Loaded parse(Path file) {
        List<Step> out = new ArrayList<>();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) return new Loaded(out, "That schematic file is too big.");
            JsonElement el = JsonParser.parseString(Files.readString(file));
            JsonArray arr = null;
            if (el.isJsonArray()) arr = el.getAsJsonArray();
            else if (el.isJsonObject() && el.getAsJsonObject().has("blocks")) arr = el.getAsJsonObject().getAsJsonArray("blocks");
            if (arr == null) return new Loaded(out, "That schematic file has no blocks in it.");
            for (JsonElement e : arr) {
                if (out.size() >= MAX_STEPS) break;
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                int dx = o.has("x") ? o.get("x").getAsInt() : 0;
                int dy = o.has("y") ? o.get("y").getAsInt() : 0;
                int dz = o.has("z") ? o.get("z").getAsInt() : 0;
                if (Math.abs(dx) > MAX_OFFSET || Math.abs(dy) > MAX_OFFSET || Math.abs(dz) > MAX_OFFSET) continue;
                String id = o.has("block") ? o.get("block").getAsString() : "";
                Identifier loc = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
                Block b = loc == null ? null : BuiltInRegistries.BLOCK.getOptional(loc).orElse(null);
                if (b == null || b == Blocks.AIR || b.asItem() == Items.AIR) continue;   // no item = nothing to take from the bag
                out.add(new Step(dx, dy, dz, b));
            }
            out.sort(Comparator.comparingInt(Step::dy).thenComparingInt(Step::dx).thenComparingInt(Step::dz));   // bottom layer first
            if (out.isEmpty()) return new Loaded(out, "That schematic has no placeable blocks.");
            return new Loaded(out, "");
        } catch (IOException | RuntimeException ex) {
            return new Loaded(new ArrayList<>(), "I could not read that schematic file.");
        }
    }

    /** Removes exactly one matching item from the bag and returns it (EMPTY when there is none). */
    private ItemStack takeFromBag(Item item) {
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return mob.storage().removeItem(i, 1);
        }
        return ItemStack.EMPTY;
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionFarmGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Map;

/**
 * "Farm for a while": harvests mature crops near the anchor, sweeps the produce into the bag and replants
 * from the bag when seeds are available. Time-boxed by {@code farmUntil} so it never runs forever, and it
 * yields to combat and mining (they are higher-priority goals).
 */
public class CompanionFarmGoal extends Goal {
    private static final int RADIUS = 14;

    /** Crop block -> what replants it (wheat needs seeds, carrots replant with carrots...). */
    private static final Map<Block, Item> SEEDS = Map.of(
            Blocks.WHEAT, Items.WHEAT_SEEDS,
            Blocks.CARROTS, Items.CARROT,
            Blocks.POTATOES, Items.POTATO,
            Blocks.BEETROOTS, Items.BEETROOT_SEEDS,
            Blocks.NETHER_WART, Items.NETHER_WART);

    private final CompanionEntity mob;
    private BlockPos target;
    private int scanCooldown, speakCooldown;
    private boolean reportedDone;

    public CompanionFarmGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean jobRunning() {
        return mob.farmUntil() > mob.level().getGameTime();
    }

    @Override public boolean canUse() {
        return jobRunning() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        scanCooldown = 0;
        speakCooldown = 0;
        reportedDone = false;
        target = null;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (!reportedDone && !jobRunning()) {
            reportedDone = true;
            mob.speak("Done farming.");
        }
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (target == null || !mature(level, target)) {
            if (--scanCooldown > 0) return;
            scanCooldown = 20;
            target = findCrop(level);
            if (target == null) {
                if (--speakCooldown <= 0) {
                    speakCooldown = 100;
                    mob.speak("No ripe crops around here.");
                }
                return;
            }
        }
        if (mob.distanceToSqr(Vec3.atCenterOf(target)) > 4.5 * 4.5) {
            mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.1);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 30f, mob.getMaxHeadXRot());
        BlockState state = level.getBlockState(target);
        BlockPos pos = target;
        target = null;
        if (!(state.getBlock() instanceof CropBlock)) return;
        if (!CompanionProtection.canModify(level, mob, pos)) return;     // spawn protection / protected area
        mob.swing(InteractionHand.MAIN_HAND);
        level.destroyBlock(pos, true, mob, 512);
        mob.collectNearbyItems();
        replant(level, pos, state);
        if (--speakCooldown <= 0) {
            speakCooldown = 60;
            mob.speak(new String[]{"Harvesting crops.", "These fields are looking good.", "Nice and ripe."}[mob.getRandom().nextInt(3)]);
        }
    }

    private boolean mature(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getBlock() instanceof CropBlock c && c.isMaxAge(s);
    }

    private BlockPos findCrop(ServerLevel level) {
        BlockPos centre = mob.anchor();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-RADIUS, -3, -RADIUS), centre.offset(RADIUS, 3, RADIUS))) {
            if (!mature(level, p)) continue;
            double d = mob.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    /** Replants a just-harvested crop when the matching seed is in the bag. */
    private void replant(ServerLevel level, BlockPos pos, BlockState oldState) {
        Item seed = SEEDS.get(oldState.getBlock());
        if (seed == null) return;
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            if (mob.storage().getItem(i).getItem() == seed) {
                mob.storage().removeItem(i, 1);
                level.setBlock(pos, oldState.getBlock().defaultBlockState(), 3);
                return;
            }
        }
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionFishGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FluidState;

import java.util.EnumSet;

/**
 * "Go fishing": walks to the nearest water edge, casts, waits, retrieves and stores the catch.
 * Time-boxed by {@code fishUntil}. There is no real bobber entity - cast/retrieve are sounds plus timing,
 * which keeps it deterministic and multiplayer-safe.
 */
public class CompanionFishGoal extends Goal {
    private static final int CAST_TICKS = 30, MIN_WAIT = 50, MAX_WAIT = 140;

    private final CompanionEntity mob;
    private BlockPos standAt, waterAt;
    private int phase;          // 0 idle, 1 cast, 2 waiting for a bite, 3 retrieve
    private int phaseTicks, scanCooldown, speakCooldown;

    public CompanionFishGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean jobRunning() {
        return mob.fishUntil() > mob.level().getGameTime();
    }

    @Override public boolean canUse() {
        return jobRunning() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        phase = 0;
        phaseTicks = 20;
        standAt = waterAt = null;
        scanCooldown = 0;
        speakCooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        if (!jobRunning()) mob.speak("Done fishing.");
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        if (waterAt == null || level.getFluidState(waterAt).isEmpty()) {
            if (--scanCooldown > 0) return;
            scanCooldown = 40;
            waterAt = findWater(level);
            if (waterAt == null) {
                if (--speakCooldown <= 0) {
                    speakCooldown = 120;
                    mob.speak("I need to be near water to fish.");
                }
                return;
            }
            standAt = findStand(level, waterAt);
            if (standAt == null) { waterAt = null; return; }
        }
        if (mob.distanceToSqr(standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5) > 2.5 * 2.5) {
            mob.getNavigation().moveTo(standAt.getX() + 0.5, standAt.getY(), standAt.getZ() + 0.5, 1.1);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(waterAt.getX() + 0.5, waterAt.getY() + 0.5, waterAt.getZ() + 0.5, 30f, mob.getMaxHeadXRot());

        if (--phaseTicks > 0) return;
        switch (phase) {
            case 0 -> {                                   // idle between casts
                phase = 1;
                phaseTicks = CAST_TICKS;
                mob.swing(InteractionHand.MAIN_HAND);
                mob.playSound(SoundEvents.FISHING_BOBBER_THROW, 1.0f, 1.0f);
            }
            case 1 -> {                                   // bobber is out there
                phase = 2;
                phaseTicks = MIN_WAIT + mob.getRandom().nextInt(MAX_WAIT - MIN_WAIT);
            }
            case 2 -> {                                   // bite!
                phase = 3;
                phaseTicks = 1;
            }
            default -> {                                  // retrieve + catch
                phase = 0;
                phaseTicks = 25 + mob.getRandom().nextInt(20);
                mob.playSound(SoundEvents.FISHING_BOBBER_RETRIEVE, 1.0f, 1.0f);
                catchSomething(level);
            }
        }
    }

    /** A standing cell next to surface water: air here and above, solid floor, water beside. */
    private BlockPos findWater(ServerLevel level) {
        BlockPos centre = mob.anchor();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos s : BlockPos.betweenClosed(centre.offset(-16, -4, -16), centre.offset(16, 4, 16))) {
            if (!level.getBlockState(s).isAir() || !level.getBlockState(s.above()).isAir()) continue;
            if (!level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)) continue;
            if (!nextToSurfaceWater(level, s)) continue;
            double d = mob.distanceToSqr(s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
            if (d < bestD) { bestD = d; best = s.immutable(); }
        }
        return best;
    }

    private boolean nextToSurfaceWater(ServerLevel level, BlockPos s) {
        for (Direction d : Direction.values()) {
            if (!d.getAxis().isHorizontal()) continue;
            BlockPos w = s.relative(d);
            FluidState f = level.getFluidState(w);
            if (!f.isEmpty() && f.typeHolder().is(FluidTags.WATER) && level.getFluidState(w.above()).isEmpty()) {
                waterAt = w.immutable();
                return true;
            }
        }
        return false;
    }

    private BlockPos findStand(ServerLevel level, BlockPos water) {
        for (Direction d : Direction.values()) {
            if (!d.getAxis().isHorizontal()) continue;
            BlockPos s = water.relative(d);
            if (level.getBlockState(s).isAir() && level.getBlockState(s.above()).isAir()
                    && level.getBlockState(s.below()).isFaceSturdy(level, s.below(), Direction.UP)) {
                return s.immutable();
            }
        }
        return null;
    }

    private void catchSomething(ServerLevel level) {
        ItemStack caught;
        String what;
        float r = mob.getRandom().nextFloat();
        if (r < 0.06) { caught = new ItemStack(Items.NAME_TAG); what = "a name tag"; }
        else if (r < 0.10) { caught = new ItemStack(Items.SADDLE); what = "a saddle"; }
        else if (r < 0.13) { caught = new ItemStack(Items.BOWL); what = "an old bowl"; }
        else if (r < 0.48) { caught = new ItemStack(Items.COD); what = "a cod"; }
        else if (r < 0.78) { caught = new ItemStack(Items.SALMON); what = "a salmon"; }
        else if (r < 0.92) { caught = new ItemStack(Items.TROPICAL_FISH); what = "a tropical fish"; }
        else { caught = new ItemStack(Items.PUFFERFISH); what = "a pufferfish"; }
        mob.storeItem(new ItemEntity(level, mob.getX(), mob.getY() + 0.5, mob.getZ(), caught));
        if (--speakCooldown <= 0) {
            speakCooldown = 40;
            String[] lines = {"Caught " + what + "!", "Nice - " + what + "!", "The fish are biting!", "Got " + what + "."};
            mob.speak(lines[mob.getRandom().nextInt(lines.length)]);
        }
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionFollowGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/** Walk to the owner when farther than startDist, stop at stopDist, sprint when far. Never teleports: only an explicit "come" order does. */
public class CompanionFollowGoal extends Goal {
    private final CompanionEntity mob;
    private final double speed, startSq, stopSq;
    private Player owner;
    private int recalc, stuck;

    public CompanionFollowGoal(CompanionEntity mob, double speed, double startDist, double stopDist, double teleportDist) {
        this.mob = mob;
        this.speed = speed;
        this.startSq = startDist * startDist;
        this.stopSq = stopDist * stopDist;   // teleportDist kept for callers; teleporting on follow is disabled
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.isInjured() || mob.moveMode() != CompanionEntity.MoveMode.FOLLOW) return false;
        Player o = mob.getOwner();
        if (o == null || o.isSpectator() || o.level() != mob.level()) return false; // cross-dimension follow: phase 2
        owner = o;
        return mob.distanceToSqr(o) >= startSq;
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.isInjured() && mob.moveMode() == CompanionEntity.MoveMode.FOLLOW && owner != null && owner.isAlive()
                && owner.level() == mob.level() && mob.distanceToSqr(owner) > stopSq;
    }

    @Override public void start() { recalc = 0; stuck = 0; }
    @Override public void stop() { mob.getNavigation().stop(); owner = null; }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(owner, 10.0f, mob.getMaxHeadXRot());
        if (--recalc > 0) return;
        recalc = adjustedTickDelay(5);              // repath more often; it keeps walking, never warps
        double d = mob.distanceToSqr(owner);
        mob.getNavigation().moveTo(owner, d > 144 ? speed * 1.35 : speed);
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionMeleeGoal.java

```java
package com.example.companionmod.entity.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

/**
 * Melee with creeper hit-and-run: while the target's fuse is lit the companion backs off out of blast
 * range instead of trading hits (vanilla creepers defuse when their target gets away), then closes back
 * in once the fuse is out - the classic attack - retreat - attack loop. Every other mob fights normally.
 */
public class CompanionMeleeGoal extends MeleeAttackGoal {
    private static final double RETREAT_DIST = 7.0;    // well past the 3.5 block creeper blast radius

    public CompanionMeleeGoal(PathfinderMob mob, double speed, boolean followingTarget) {
        super(mob, speed, followingTarget);
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target instanceof Creeper creeper && creeper.isAlive() && creeper.getSwellDir() > 0) {
            Vec3 away = this.mob.position().subtract(creeper.position());
            away = new Vec3(away.x, 0, away.z);
            if (away.lengthSqr() < 1.0e-4) away = new Vec3(0, 0, 1);   // standing on top of it: pick a direction
            away = away.normalize().scale(RETREAT_DIST);
            this.mob.getNavigation().moveTo(this.mob.getX() + away.x, this.mob.getY(), this.mob.getZ() + away.z, 1.35);
            this.mob.getLookControl().setLookAt(creeper.getX(), creeper.getY() + creeper.getEyeHeight(), creeper.getZ(), 30.0f, 30.0f);
            return;                               // never walk into a lit fuse
        }
        super.tick();
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionPatrolGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** Walks a ring of waypoints around the anchor while in PATROL mode. */
public class CompanionPatrolGoal extends Goal {
    private final CompanionEntity mob;
    private int cooldown;

    public CompanionPatrolGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.moveMode() == CompanionEntity.MoveMode.PATROL && !mob.isSitting() && !mob.isInjured();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        mob.setPatrolIndex(0);
        cooldown = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (--cooldown > 0) return;
        cooldown = 10;
        BlockPos anchor = mob.anchor();
        int index = mob.patrolIndex();
        double angle = index * Math.PI / 4.0;
        int x = anchor.getX() + (int) Math.round(Math.cos(angle) * 9.0);
        int z = anchor.getZ() + (int) Math.round(Math.sin(angle) * 9.0);
        int y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        boolean moved = mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, 1.0);
        if (!moved || mob.getNavigation().isDone()) {
            mob.setPatrolIndex((index + 1) % 8);
        } else if (mob.distanceToSqr(x + 0.5, y, z + 0.5) < 6.0) {
            mob.setPatrolIndex((index + 1) % 8);
        }
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionPickupGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.EnumSet;
import java.util.List;

/**
 * Walks to dropped items and stores them in the companion inventory.
 *
 * <p>An explicit "pick up this bone" order sets a filter and {@code pickupRequested}. The goal then
 * targets that exact item and walks to it — it never opens the inventory screen. With the general
 * pickup toggle on, it hoovers up every nearby drop instead.</p>
 */
public class CompanionPickupGoal extends Goal {
    private final CompanionEntity mob;
    private ItemEntity item;
    private int cooldown;
    private boolean storedAny;
    private double lastDistSq = Double.MAX_VALUE;   // approach progress: unreachable items must not be chased forever
    private int noApproachTicks;

    public CompanionPickupGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.isInjured() || mob.isSitting()) return false;
        if (!mob.isPickupEnabled() && !mob.isPickupRequested()) return false;
        if (--cooldown > 0) return false;
        cooldown = 10;
        item = nearestItem();
        if (item != null) { lastDistSq = Double.MAX_VALUE; noApproachTicks = 0; }
        return item != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.isInjured() || mob.isSitting()) return false;
        if (!mob.isPickupEnabled() && !mob.isPickupRequested()) return false;
        if (item == null || !item.isAlive() || item.getItem().isEmpty()) {
            item = nearestItem();
        }
        return item != null;
    }

    @Override
    public void start() {
        storedAny = false;
        lastDistSq = Double.MAX_VALUE;
        noApproachTicks = 0;
        if (item != null) mob.getNavigation().moveTo(item, 1.15);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        item = null;
        if (mob.isPickupRequested()) mob.finishPickup(storedAny);
    }

    @Override
    public void tick() {
        if (item == null || !item.isAlive() || item.getItem().isEmpty()) {
            item = nearestItem();
            if (item == null) return;
            lastDistSq = Double.MAX_VALUE;
            noApproachTicks = 0;
            mob.getNavigation().moveTo(item, 1.15);
            return;
        }

        double distSq = mob.distanceToSqr(item);
        if (distSq <= 4.0) {
            boolean stored = mob.storeItem(item);
            if (stored) {
                storedAny = true;
                mob.collectNearbyItems();          // grab the rest of the same drop pile
            } else {
                mob.speak("My bag is full.");
                mob.finishPickup(true);
                item = null;
                return;
            }
            item = null;
            lastDistSq = Double.MAX_VALUE;
            noApproachTicks = 0;
            if (mob.isPickupRequested() && nearestItem() == null) mob.finishPickup(storedAny);
            return;
        }
        // No closing in for 6 s (across water, against a wall, on a ledge): stop chasing THIS item -
        // the old code re-pathed it forever while holding the MOVE goal flag and blocking every other job.
        if (distSq + 0.05 < lastDistSq) {
            lastDistSq = distSq;
            noApproachTicks = 0;
        } else if (++noApproachTicks > 120) {
            item = null;
            lastDistSq = Double.MAX_VALUE;
            noApproachTicks = 0;
            cooldown = 100;                 //3 s of hands off: other goals (follow, mine...) get to run
            return;
        }
        if (mob.getNavigation().isDone()) mob.getNavigation().moveTo(item, 1.15);
        mob.getLookControl().setLookAt(item, 10f, mob.getMaxHeadXRot());
    }

    private ItemEntity nearestItem() {
        double radius = mob.isPickupRequested() ? 12.0 : 10.0;
        List<ItemEntity> items = mob.level().getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(radius),
                e -> e.isAlive() && !e.getItem().isEmpty() && mob.matchesPickup(e));
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = mob.distanceToSqr(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionPlantGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * "Plant the saplings": walks around the anchor and plants saplings from the bag on soil, 3 blocks apart and with
 * room to grow. Ends by itself when the bag has no (matching) sapling left or no good spot exists. Never breaks anything.
 */
public class CompanionPlantGoal extends Goal {
    private static final int RADIUS = 12;

    private final CompanionEntity mob;
    private final Set<BlockPos> badSpots = new HashSet<>();
    private BlockPos spot;
    private int scanCooldown, walkTicks, planted;

    public CompanionPlantGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean jobRunning() { return mob.plantUntil() > mob.level().getGameTime(); }

    @Override public boolean canUse() { return jobRunning() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null; }
    @Override public boolean canContinueToUse() { return canUse(); }

    @Override
    public void start() {
        spot = null;
        scanCooldown = 0;
        walkTicks = 0;
        planted = 0;
        badSpots.clear();
    }

    @Override public void stop() { mob.getNavigation().stop(); }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        int slot = saplingSlot();
        if (slot < 0) {
            finish(planted > 0 ? "Planted " + planted + (planted == 1 ? " sapling." : " saplings.")
                    : (mob.plantFilter().isBlank() ? "I don't have any saplings to plant." : "I don't have any " + mob.plantFilter().replace('_', ' ') + " to plant."));
            return;
        }
        BlockState sapling = ((BlockItem) mob.storage().getItem(slot).getItem()).getBlock().defaultBlockState();
        if (spot == null || !valid(level, spot, sapling)) {
            if (--scanCooldown > 0) return;
            scanCooldown = 10;
            spot = findSpot(level, sapling);
            walkTicks = 0;
            if (spot == null) {
                finish(planted > 0 ? "Planted " + planted + (planted == 1 ? " sapling." : " saplings.") : "I can't find a good spot to plant here.");
                return;
            }
        }
        Vec3 c = Vec3.atBottomCenterOf(spot);
        if (mob.distanceToSqr(c) > 6.25) {
            if (++walkTicks > 160) { badSpots.add(spot); spot = null; return; }
            if (mob.getNavigation().isDone() && mob.onGround()) mob.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(c.x, c.y, c.z, 30f, mob.getMaxHeadXRot());
        ItemStack taken = mob.storage().removeItem(slot, 1);
        if (taken.isEmpty()) return;
        level.setBlock(spot, ((BlockItem) taken.getItem()).getBlock().defaultBlockState(), 3);
        mob.swing(InteractionHand.MAIN_HAND);
        planted++;
        spot = null;
    }

    private void finish(String msg) {
        mob.speak(msg);
        mob.endPlant();
    }

    private int saplingSlot() {
        String f = mob.plantFilter();
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            if (!bi.getBlock().defaultBlockState().is(BlockTags.SAPLINGS)) continue;
            if (f.isBlank() || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains(f)) return i;
        }
        return -1;
    }

    private BlockPos findSpot(ServerLevel level, BlockState sapling) {
        BlockPos centre = mob.anchor();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-RADIUS, -3, -RADIUS), centre.offset(RADIUS, 3, RADIUS))) {
            if (!level.getBlockState(p.below()).is(BlockTags.DIRT)) continue;        // cheap test first
            double d = mob.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            if (d >= bestD || !valid(level, p, sapling)) continue;
            bestD = d;
            best = p.immutable();
        }
        return best;
    }

    private boolean valid(ServerLevel level, BlockPos p, BlockState sapling) {
        if (badSpots.contains(p)) return false;
        BlockState here = level.getBlockState(p);
        if (!here.isAir() && !here.canBeReplaced()) return false;                   // air, grass, ferns, snow layers
        if (!level.getBlockState(p.below()).is(BlockTags.DIRT) || !sapling.canSurvive(level, p)) return false;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 1; dy++) {
            BlockState n = level.getBlockState(p.offset(dx, dy, dz));
            if (n.is(BlockTags.SAPLINGS) || n.is(BlockTags.LOGS)) return false;     // keep trees 3 apart
        }
        for (int dy = 1; dy <= 5; dy++) {                                            // room to grow
            BlockState a = level.getBlockState(p.above(dy));
            if (!a.isAir() && !a.is(BlockTags.LEAVES) && !a.canBeReplaced()) return false;
        }
        return CompanionProtection.canModify(level, mob, p);
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionSeekBlockGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionProtection;
import com.example.companionmod.entity.task.MineTask;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Player-style "find and mine X".
 *
 * <p>Surface: look for exposed matches, walk there if a path exists, otherwise tunnel to them.
 * Deep ores: walk down a 2-high staircase (never a bare shaft) to the best Y level, then strip-mine in parallel
 * branches, spotting exposed ore (or any ore within 2 blocks of the tunnel) and vein-mining whatever touches it.
 * Every block is broken the way a player does: look at it, swing the arm, crack animation, time based on hardness and tool.
 * Tunnels refuse lava, water, falling blocks overhead and holes underfoot and turn away instead.
 *
 * <p>All progress lives in {@link MineTask} (saved with the entity), so interruptions never reset anything; only
 * the transient per-block state in this goal is rebuilt. No global timeout: if no progress is made for 25 s the
 * companion re-plans, and after four failed re-plans it pauses and asks to be told to continue.</p>
 */
public class CompanionSeekBlockGoal extends Goal {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final double REACH_SQ = 4.5 * 4.5;
    private static final int LEG_LEN = 36, SIDE_LEN = 3, MAX_LEGS = 8;
    private static final int WALK_STAND = 0, WALK_REACH = 1, WALK_FREE = 2, WALK_EXPLORE = 3;
    private static final int MAX_EXPLORE_LEGS = 30, SIGHT_RADIUS = 56, SIGHT_MIN = 16;

    private final CompanionEntity mob;
    private final Deque<BlockPos> pending = new ArrayDeque<>();   // cells to clear before the next step
    private final Deque<BlockPos> vein = new ArrayDeque<>();
    private final Set<BlockPos> unreachable = new HashSet<>();
    private BlockPos breaking, walkTo, oreTarget, stepStand;
    private int walkMode, breakProgress, breakNeed, walkTicks, stuck, wanderTries, torchCooldown;
    private long lastProgress, lastSpeak;

    // --- surface search: time-based rescans, long-range heightmap "sight", exploration memory
    private long nextScanAt, nextSightAt, nextExploreAt, lastUnreachableClear;
    private final Deque<BlockPos> explored = new ArrayDeque<>();   // where we already looked (explore memory)

    // cache: does Block X match the current task? (replaces a registry lookup + string compare per scanned block)
    private static final Map<Block, Boolean> MATCH_CACHE = new HashMap<>();
    private static String matchCacheId = "";

    // --- scaffolding: climb with blocks from the bag, take them back afterwards; plus per-target bookkeeping
    private static final int MAX_SCAFFOLD = 24;
    private final Deque<BlockPos> scaffold = new ArrayDeque<>();      // blocks WE placed, bottom first (kept across goal restarts)
    private final Map<BlockPos, Integer> approachTries = new HashMap<>();
    private BlockPos approachTarget, pillarTarget, jumpCell;
    private int pillarAir, cleanupWait, pillarPlaced, bridgeRun;
    private BlockPos bridgeCell;
    private long softRecoverAt;
    private int walkRetries;
    private int branchBoxed;         // consecutive fully-blocked branch steps: rotate the leg before ending it
    private long lastDiagLog;
    private int lastTaskSerial;      // MineTask.serial at the last tick: a new order drops stale state same-tick
    private Vec3 lastHeartbeatPos = new Vec3(0, -10000, 0);   // movement-gated progress heartbeat
    private Vec3 standLast = new Vec3(0, -10000, 0);   // tunnel-step stall detector (see WALK_STAND)
    private int standStall;
    private boolean selected;        // goal selector has us running (see selected())
    private int lastDescendY = Integer.MIN_VALUE;   // DESCEND: only trust slope-walks while height is actually lost
    private long lastDescendDrop;
    private String lastDiag = "";    // change-gated diagnostic lines (one per real state change, never per frame)

    public CompanionSeekBlockGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ------------------------------------------------------------------ lifecycle

    private boolean ready() {
        return mob.mineTask().running() && !mob.isSitting() && !mob.isInjured() && mob.getTarget() == null;
    }

    @Override public boolean canUse() { return ready(); }
    @Override public boolean canContinueToUse() { return ready(); }

    @Override
    public void start() {
        selected = true;
        resetTransient();
        lastProgress = mob.level().getGameTime();
        lastHeartbeatPos = mob.position();
    }

    @Override
    public void stop() {
        selected = false;
        if (mob.level() instanceof ServerLevel level) cancelBreak(level);
        resetTransient();
        mob.getNavigation().stop();
    }

    /** True while this goal is actually selected and ticking - the entity uses it to spot "task assigned but goal dormant" stalls. */
    public boolean selected() { return selected; }

    private void resetTransient() {
        pending.clear();
        vein.clear();
        breaking = walkTo = oreTarget = stepStand = null;
        breakProgress = walkTicks = stuck = wanderTries = 0;
        nextScanAt = nextSightAt = nextExploreAt = 0;
        explored.clear();
        approachTries.clear();
        approachTarget = pillarTarget = jumpCell = bridgeCell = null;
        pillarAir = 0;
        branchBoxed = 0;
        lastDescendY = Integer.MIN_VALUE;
        lastDiag = "";
        unreachable.clear();       // a fresh task retries every block: old "unreachable" marks must not leak into it
    }

    /** One diagnostic line per real state change - latest.log shows the movement state without flooding. */
    private void diag(String s) {
        if (!s.equals(lastDiag)) { lastDiag = s; LOG.info("[companionmod] seek: {}", s); }
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level)) return;
        MineTask t = mob.mineTask();
        long now = level.getGameTime();
        if (t.serial != lastTaskSerial) {          // a NEW order arrived while we were selected: drop stale walk/break/pillar state this tick
            lastTaskSerial = t.serial;
            resetTransient();
        }

        long idle = now - lastProgress;
        if (idle > 200 && now - lastDiagLog > 200) {      // diagnostics FIRST: the watchdog branches below return early
            lastDiagLog = now;
            LOG.warn("[companionmod] mining stall ({}s idle): phase={} ore={} walk={} mode={} breaking={} pending={} pillar={} ground={} marked={}",
                    idle / 20, t.phase, oreTarget, walkTo, walkMode, breaking, pending.size(), pillarTarget, mob.onGround(), unreachable.size());
        }
        if (idle > 160 && idle <= 500 && softRecoverAt != lastProgress) {   // 8 s without progress: drop what we are fixated on, re-decide
            softRecoverAt = lastProgress;
            LOG.warn("[companionmod] soft recovery after {}s idle, state={}", idle / 20, stateName());
            cancelBreak(level);
            if (approachTarget != null) giveUp(approachTarget);
            pending.clear();
            stepStand = walkTo = null;
            nextScanAt = 0;                // re-decide from scratch: rescan immediately
            endPillar();
            bridgeCell = null;
            mob.getNavigation().stop();      // else it keeps steering the old route while we re-decide
            return;
        }
        if (idle > 500) {                                         // 25 s without progress: re-plan, then give up politely
            lastProgress = now;
            LOG.warn("[companionmod] hard re-plan after {}s idle (stuck={}), state={}", idle / 20, stuck, stateName());
            cancelBreak(level);
            pending.clear();
            stepStand = walkTo = oreTarget = null;
            mob.getNavigation().stop();
            if (++stuck >= 4) {
                t.pause();
                say(level, "I'm stuck (" + stateName() + "). Say 'continue' and I'll try again.", true);
                return;
            }
            t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
            return;
        }
        if (now - lastUnreachableClear >= 600) { lastUnreachableClear = now; unreachable.clear(); }   // "unreachable" marks are retried every 30 s: never a permanent blacklist
        // Underground it gets dark: drop a torch from the bag every so often so the tunnels stay lit.
        if (t.phase != MineTask.Phase.SEARCH && --torchCooldown <= 0) {
            torchCooldown = 60;
            if (level.getMaxLocalRawBrightness(mob.blockPosition()) < 8) mob.placeTorch();
        }
        if (breaking != null) { keepBreaking(level, t, now); return; }
        if (walkTo != null) { walk(level, now); return; }
        if (!pending.isEmpty()) { startBreak(level, pending.poll()); return; }
        if (pillarTarget != null) { pillarStep(level, t, now); return; }
        if (!mob.onGround() && !mob.isInWater()) return;        // wait for the landing: path requests fail in mid-air and used to mark good targets "unreachable"
        if (stepStand != null) {
            if (bridgeCell != null) {                                   // mining: lay a floor over an air gap first (stays there)
                BlockPos b = bridgeCell;
                bridgeCell = null;
                if (passable(level, b)) {
                    ItemStack taken = CompanionProtection.canModify(level, mob, b) ? takeScaffoldBlock(t) : ItemStack.EMPTY;
                    if (taken.isEmpty()) { stepStand = null; return; }
                    level.setBlock(b, ((BlockItem) taken.getItem()).getBlock().defaultBlockState(), 3);
                    mob.swing(InteractionHand.MAIN_HAND, true);
                    lastProgress = now;
                }
            }
            walkTo = stepStand;
            walkMode = WALK_STAND;
            walkTicks = 0;
            stepStand = null;
            mob.getNavigation().moveTo(walkTo.getX() + 0.5, walkTo.getY(), walkTo.getZ() + 0.5, 1.0);
            return;
        }
        decide(level, t);
    }

    private void decide(ServerLevel level, MineTask t) {
        if (t.mined >= t.want) {                    // done (also when a fight interrupted it): climb down first, then report
            if (cleanupScaffold(level)) return;
            String msg = "That's " + t.mined + " " + CompanionEntity.prettyBlockId(t.blockId) + " for you.";
            t.finish();
            mob.speak(msg);
            resetTransient();                       // no warp home: the follow goal walks the companion back
            return;
        }
        while (!vein.isEmpty() && !matches(t, level.getBlockState(vein.peek()))) vein.poll();
        if (!vein.isEmpty()) { approach(level, t, vein.peek()); return; }

        long now = level.getGameTime();
        if (oreTarget != null && !matches(t, level.getBlockState(oreTarget))) { oreTarget = null; nextScanAt = 0; }
        if (oreTarget == null && now >= nextScanAt) {        // time based, and reset after every break/arrival
            nextScanAt = now + 10;
            oreTarget = scan(level, t);
            if (oreTarget != null) wanderTries = 0;
        }
        if (oreTarget != null) { approach(level, t, oreTarget); return; }

        if (cleanupScaffold(level)) return;         // nothing left to mine from up here: climb down, take our blocks back

        int y = mob.blockPosition().getY();
        switch (t.phase) {
            case SEARCH -> {
                if (t.deep() && y > t.ty) {
                    t.phase = MineTask.Phase.DESCEND;
                    say(level, "Nothing on the surface. Heading down to y=" + t.ty + ".", true);
                } else if (t.deep()) {
                    t.phase = MineTask.Phase.BRANCH;
                } else searchSurface(level, t);
            }
            case DESCEND -> {
                if (y <= t.ty || y <= level.getMinY() + 6) {            // 26.1-CHECK: LevelReader.getMinY()
                    t.phase = MineTask.Phase.BRANCH;
                    t.leg = 0;
                    t.legLen = 0;
                    lastDescendY = Integer.MIN_VALUE;
                    say(level, "This depth looks right. Branch mining.", true);
                } else {
                    if (lastDescendY == Integer.MIN_VALUE || y < lastDescendY) {
                        lastDescendY = y;
                        lastDescendDrop = now;                          // height lost recently -> walkable slopes are fine
                    }
                    // Slope-walks are only trusted while they actually lose height. A path that walks
                    // without descending used to refresh the progress heartbeat forever - the old
                    // "stuck on planning and walking" loop. After 5 s without losing height: dig stairs.
                    if (now - lastDescendDrop < 100 && walkDownSlope(level)) {
                        diag("descending by walking (y=" + y + " -> ty=" + t.ty + ")");
                    } else if (!planStep(level, t, Direction.from2DDataValue(t.dirData), -1, true)) {
                        diag("cannot dig down at y=" + y + " dir=" + t.dirData + " stuck=" + stuck);
                        t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
                        if (++stuck >= 4) { t.pause(); say(level, "I can't dig down safely here. Move me and say 'continue'.", true); }
                    } else {
                        diag("digging stairs down (y=" + y + " -> ty=" + t.ty + ")");
                    }
                }
            }
            case BRANCH -> branch(level, t);
            case ASCEND -> ascend(level, t);
        }
    }

    /** "Dig up to the surface": staircase up until the terrain heightmap is at or below us, then done. */
    private void ascend(ServerLevel level, MineTask t) {
        if (atSurface(level)) {
            t.finish();
            mob.speak("I'm up.");
            resetTransient();                       // the follow goal walks the companion back, no warp
            return;
        }
        if (!planStep(level, t, Direction.from2DDataValue(t.dirData), 1, true)) {
            t.dirData = Direction.from2DDataValue(t.dirData).getClockWise().get2DDataValue();
            if (++stuck >= 4) { t.pause(); say(level, "I can't dig up safely here. Move me and say 'continue'.", true); }
        }
    }

    /** At the terrain surface: the no-leaves heightmap at our feet is at or below us, or the sky is visible. */
    private boolean atSurface(ServerLevel level) {
        BlockPos p = mob.blockPosition();
        int surf = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return p.getY() >= surf - 1 || level.canSeeSky(p.above());
    }

    private void branch(ServerLevel level, MineTask t) {
        Direction d = Direction.from2DDataValue(t.dirData);
        int len = (t.leg % 2 == 0) ? LEG_LEN : SIDE_LEN;
        if (t.legLen >= len) {                                          // finished a tunnel leg: shift sideways, come back parallel
            t.leg++;
            t.legLen = 0;
            if (t.leg >= MAX_LEGS) {
                t.leg = 0;
                t.pause();
                say(level, "I've covered this level. Say 'continue' to keep looking.", true);
                return;
            }
            t.dirData = d.getClockWise().get2DDataValue();
            return;
        }
        if (planStep(level, t, d, 0, false)) { t.legLen++; branchBoxed = 0; return; }
        // Blocked ahead (lava pocket, bedrock bump): mine at a slight angle or step over it before ending the leg.
        // Without this one blocked cell ended the leg instantly, all 8 legs burned in seconds and the task paused.
        if (planStep(level, t, d.getClockWise(), 0, false)
                || planStep(level, t, d.getCounterClockWise(), 0, false)
                || planStep(level, t, d, -1, false) || planStep(level, t, d, 1, false)) {
            t.legLen++;
            branchBoxed = 0;
            return;
        }
        // Open space ahead (a cavern crossing the leg line): the tunnel logic refuses holes in the floor, so
        // continue the leg ON the cave floor like a walker instead of burning the leg and pausing the task.
        BlockPos ahead = mob.blockPosition().relative(d, 8);
        Path p = mob.getNavigation().createPath(ahead, 2);
        if (p != null && p.canReach()) {
            walkTo = ahead;
            walkMode = WALK_FREE;
            walkTicks = 0;
            mob.getNavigation().moveTo(p, 1.0);
            t.legLen += 4;                          // credit progress: the leg keeps going, it does not exhaust
            branchBoxed = 0;
            return;
        }
        // Boxed in (lava pocket, bedrock wall): rotate the leg and mine at an angle to escape the pocket
        // instead of ending the leg - two rotations (90 then 180) before the leg really ends.
        if (branchBoxed++ < 2) {
            t.dirData = d.getClockWise().get2DDataValue();
            return;
        }
        branchBoxed = 0;
        t.legLen = len;                                                 // truly boxed in: end this leg early
    }

    /** Surface search: first look far with the heightmap (trees show up as log columns), else explore somewhere new. */
    private void searchSurface(ServerLevel level, MineTask t) {
        long now = level.getGameTime();
        if (now >= nextSightAt) {
            nextSightAt = now + 40;
            BlockPos seen = farSight(level, t);
            if (seen != null) {
                BlockPos me = mob.blockPosition();
                BlockPos foot = groundBelow(level, t, seen).above();
                double dx = foot.getX() - me.getX(), dz = foot.getZ() - me.getZ();
                double hd = Math.sqrt(dx * dx + dz * dz);
                BlockPos wp = foot;
                if (hd > 24) {   // the pathfinder is limited by follow range: go in hops, the near scan takes over on arrival
                    int wx = me.getX() + (int) (dx / hd * 24), wz = me.getZ() + (int) (dz / hd * 24);
                    if (level.hasChunk(wx >> 4, wz >> 4)) {
                        wp = new BlockPos(wx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz), wz);
                    }
                }
                Path p = mob.getNavigation().createPath(wp, 2);
                if (p != null && p.canReach()) { startExplore(wp, p); return; }
                unreachable.add(seen.immutable());   // cannot walk there (water, cliff): look at the next candidate next time
            }
        }
        exploreSomewhereNew(level, t);
    }

    /** Nearest matching block at the top of a loaded column (trees are the highest non-leaf block of their column). */
    private BlockPos farSight(ServerLevel level, MineTask t) {
        BlockPos o = mob.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE, minSq = SIGHT_MIN * SIGHT_MIN;
        for (int x = o.getX() - SIGHT_RADIUS; x <= o.getX() + SIGHT_RADIUS; x++) {
            for (int z = o.getZ() - SIGHT_RADIUS; z <= o.getZ() + SIGHT_RADIUS; z++) {
                double d = (double) (x - o.getX()) * (x - o.getX()) + (double) (z - o.getZ()) * (z - o.getZ());
                if (d < minSq || d >= bestD) continue;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;          // never force-load chunks // 26.1-CHECK: LevelReader.hasChunk(int,int)
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                m.set(x, y, z);
                if (!matches(t, level.getBlockState(m)) || unreachable.contains(m)) continue;
                bestD = d;
                best = m.immutable();
            }
        }
        return best;
    }

    private void exploreSomewhereNew(ServerLevel level, MineTask t) {
        long now = level.getGameTime();
        if (now < nextExploreAt) return;
        if (wanderTries == 4) unreachable.clear();               // new vantage point: retry old "unreachable" marks
        if (++wanderTries > MAX_EXPLORE_LEGS) {
            t.pause();
            say(level, "I can't find any " + CompanionEntity.prettyBlockId(t.blockId) + " around here.", true);
            return;
        }
        BlockPos me = mob.blockPosition();
        remember(me);

        record Cand(BlockPos pos, double score) { }
        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            double ang = mob.getRandom().nextDouble() * Math.PI * 2;
            double dist = 20 + mob.getRandom().nextDouble() * 16 + Math.min(wanderTries, 10);
            int x = me.getX() + (int) (Math.cos(ang) * dist), z = me.getZ() + (int) (Math.sin(ang) * dist);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos dest = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!level.getFluidState(dest).isEmpty() || !level.getFluidState(dest.below()).isEmpty()) continue;   // no lakes / lava
            double nearest = 48;
            for (BlockPos e : explored) nearest = Math.min(nearest, Math.sqrt(e.distSqr(dest)));
            cands.add(new Cand(dest, nearest + mob.getRandom().nextDouble() * 6));   // prefer spots far from everywhere we looked
        }
        cands.sort((a, b) -> Double.compare(b.score(), a.score()));
        for (int i = 0; i < Math.min(4, cands.size()); i++) {
            Path p = mob.getNavigation().createPath(cands.get(i).pos(), 2);
            if (p != null && p.canReach()) { startExplore(cands.get(i).pos(), p); return; }
        }
        nextExploreAt = now + 20;                                  // nothing reachable this time: pause a second, retry
    }

    private void startExplore(BlockPos dest, Path p) {
        remember(dest);
        walkTo = dest;
        walkMode = WALK_EXPLORE;
        walkTicks = 0;
        mob.getNavigation().moveTo(p, 1.0);
    }

    private void remember(BlockPos p) {
        explored.addLast(p.immutable());
        while (explored.size() > 24) explored.pollFirst();
    }

    // ------------------------------------------------------------------ approaching and tunnelling

    private void approach(ServerLevel level, MineTask t, BlockPos target) {
        approachTarget = target;
        if (inReach(Vec3.atCenterOf(target))) {
            if (lineClear(level, target)) { startBreak(level, target); return; }
            // Close enough but something is in the way (stone, leaves...): dig it out the way a player would.
            BlockPos blocker = firstBlocker(level, target);
            if (blocker == null) { startBreak(level, target); return; }
            if (approachTries.merge(target.immutable(), 1, Integer::sum) <= 12 && safeToBreak(level, blocker)) {   // big canopies can have several leaf layers in front of a log
                pending.add(blocker);
            } else {
                giveUp(target);
            }
            return;
        }
        // Far away (or above us). Unless we are climbing toward this block, get off our tower first.
        BlockPos me = mob.blockPosition();
        boolean climbing = target.getY() - me.getY() >= 2
                && Math.max(Math.abs(target.getX() - me.getX()), Math.abs(target.getZ() - me.getZ())) <= 3;
        if (!climbing && cleanupScaffold(level)) return;
        // Walk there whenever a path exists - surface, open caves and finished tunnels are crossed like a player
        // crosses them. Only solid rock (no path through) is tunnelled; this is what froze it in caves before,
        // where every tunnel step was "unsafe" (open space below) and the task paused itself.
        Path p = mob.getNavigation().createPath(target, 1);
        if (p != null && p.canReach()) {
            walkTo = target;
            walkMode = WALK_REACH;
            walkTicks = 0;
            mob.getNavigation().moveTo(p, 1.1);
            return;
        }
        if (tryScaffold(level, t, target)) return;                   // high in a tree / on a cave ceiling: build up to it
        // Surface target the pathfinder won't commit to (top of a cactus, a ledge): walk to a cell BESIDE
        // its column first instead of tunnelling a trench to it - digging toward it produced one-block
        // trenches with block-by-block teleports where a simple walk-around would do.
        if (!t.deep() && target.getY() > mob.blockPosition().getY()) {
            BlockPos base = groundBelow(level, t, target);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos stand = base.relative(dir).above();
                if (!passable(level, stand)) continue;
                Path fp = mob.getNavigation().createPath(stand, 1);
                if (fp != null && fp.canReach()) {
                    walkTo = stand;
                    walkMode = WALK_FREE;
                    walkTicks = 0;
                    mob.getNavigation().moveTo(fp, 1.0);
                    diag("walk-around to " + stand + " for target " + target);
                    return;
                }
            }
        }
        tunnelToward(level, t, target);
    }

    private void giveUp(BlockPos target) {
        unreachable.add(target.immutable());
        vein.remove(target);
        if (target.equals(oreTarget)) oreTarget = null;
        if (target.equals(pillarTarget)) { pillarTarget = null; jumpCell = null; }
        pending.clear();
        stepStand = null;
    }

    /** First solid cell between our eyes and the target (the thing we must dig out), or null. */
    private BlockPos firstBlocker(ServerLevel level, BlockPos target) {
        Vec3 eye = mob.getEyePosition(), goal = Vec3.atCenterOf(target), delta = goal.subtract(eye);
        for (int k = 1; k <= 8; k++) {
            BlockPos bp = BlockPos.containing(eye.add(delta.scale(k / 9.0)));
            if (bp.equals(target)) break;
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) return bp.immutable();
        }
        return null;
    }

    /** Never open a wall into liquid, and never break unbreakable blocks. */
    private boolean safeToBreak(ServerLevel level, BlockPos c) {
        BlockState s = level.getBlockState(c);
        if (s.getDestroySpeed(level, c) < 0 || !s.getFluidState().isEmpty()) return false;
        for (Direction dd : Direction.values()) {
            if (!level.getBlockState(c.relative(dd)).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ scaffolding

    /** Cheap, solid, non-falling blocks only (dirt, stone family, cobble, netherrack, planks), never the block being mined. */
    private static boolean usableScaffold(ItemStack s, MineTask t) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        if (b instanceof FallingBlock || b instanceof EntityBlock) return false;
        BlockState st = b.defaultBlockState();
        if (matches(t, st)) return !t.deep();            // surface job: the logs we just chopped are fine (temporary) scaffolding
        return st.is(BlockTags.DIRT) || st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.BASE_STONE_NETHER)
                || st.is(BlockTags.PLANKS) || b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.NETHERRACK
                || (!t.deep() && st.is(BlockTags.LOGS));
    }

    private int scaffoldSlot(MineTask t) {
        int best = -1, bestScore = Integer.MAX_VALUE;
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (!usableScaffold(s, t)) continue;
            Block b = ((BlockItem) s.getItem()).getBlock();
            int score = (b == Blocks.DIRT || b == Blocks.COBBLESTONE || b == Blocks.COBBLED_DEEPSLATE || b == Blocks.NETHERRACK) ? 0
                    : (matches(t, b.defaultBlockState()) ? 2 : 1);      // our own logs only as a last resort
            if (score < bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    private boolean hasScaffoldBlock(MineTask t) { return scaffoldSlot(t) >= 0; }

    private ItemStack takeScaffoldBlock(MineTask t) {
        int i = scaffoldSlot(t);
        return i < 0 ? ItemStack.EMPTY : mob.storage().removeItem(i, 1);
    }

    /** The target is above us: walk to the foot of its column if needed, then start pillaring up. */
    private boolean tryScaffold(ServerLevel level, MineTask t, BlockPos target) {
        BlockPos me = mob.blockPosition();
        if (target.getY() - me.getY() < 2) return false;
        if (!hasScaffoldBlock(t)) {
            say(level, "I can't reach that high. Give me some dirt or cobblestone to climb with.", false);
            return false;
        }
        if (approachTries.merge(target.immutable(), 1, Integer::sum) > 8) return false;
        int h = Math.max(Math.abs(target.getX() - me.getX()), Math.abs(target.getZ() - me.getZ()));
        if (h <= 2) {
            if (pillarTarget == null) pillarPlaced = 0;
            pillarTarget = target.immutable();
            jumpCell = null;
            pillarAir = 0;
            return true;
        }
        BlockPos foot = groundBelow(level, t, target).above();
        Path p = mob.getNavigation().createPath(foot, 2);
        if (p == null || !p.canReach()) return false;
        walkTo = foot;
        walkMode = WALK_FREE;
        walkTicks = 0;
        mob.getNavigation().moveTo(p, 1.0);
        return true;
    }

    /** Solid ground under the target's column (skips air, leaves and the mined material itself, e.g. the trunk). */
    private BlockPos groundBelow(ServerLevel level, MineTask t, BlockPos target) {
        BlockPos g = target;
        while (g.getY() > level.getMinY()) {
            BlockState st = level.getBlockState(g);
            if (st.getCollisionShape(level, g).isEmpty() || matches(t, st) || st.is(BlockTags.LEAVES)) g = g.below();
            else break;
        }
        return g;
    }

    /** Jump, and while the feet are above the cell we left, put a block into it; repeat until the target is in reach. */
    private void pillarStep(ServerLevel level, MineTask t, long now) {
        BlockPos target = pillarTarget;
        if (!matches(t, level.getBlockState(target)) || unreachable.contains(target)) { endPillar(); return; }
        if (inReach(Vec3.atCenterOf(target))) { endPillar(); return; }   // in reach: normal mining (clears leaves in the way)
        if (target.getY() <= mob.blockPosition().getY() + 1) { endPillar(); return; }          // no longer above us
        mob.getNavigation().stop();
        if (jumpCell == null) {
            if (!mob.onGround()) return;                                  // wait until we have landed
            if (scaffold.size() >= MAX_SCAFFOLD || pillarPlaced >= MAX_SCAFFOLD) {
                say(level, "That's too high to build up to.", true);
                giveUp(target);
                return;
            }
            BlockPos feet = mob.blockPosition();
            for (int up = 2; up <= 3; up++) {                             // headroom for the jump: leaves, branches...
                BlockPos h = feet.above(up);
                if (passable(level, h)) continue;
                if (safeToBreak(level, h)) pending.add(h); else giveUp(target);
                return;
            }
            if (!hasScaffoldBlock(t)) {
                say(level, "I need dirt or cobblestone to climb up there.", true);
                giveUp(target);
                return;
            }
            jumpCell = feet.immutable();
            pillarAir = 0;
            diag("tower jump #" + pillarPlaced + " from y=" + String.format(java.util.Locale.ROOT, "%.1f", mob.getY())
                    + " to cell y=" + jumpCell.getY());
            mob.getJumpControl().jump();
            return;
        }
        pillarAir++;
        if (mob.getY() >= jumpCell.getY() + 1.05) {                       // feet are above the cell we left
            if (passable(level, jumpCell)) {
                if (!CompanionProtection.canModify(level, mob, jumpCell)) { giveUp(target); return; }
                ItemStack taken = takeScaffoldBlock(t);
                if (taken.isEmpty()) { giveUp(target); return; }
                level.setBlock(jumpCell, ((BlockItem) taken.getItem()).getBlock().defaultBlockState(), 3);
                pillarPlaced++;
                if (!t.deep()) scaffold.addLast(jumpCell);        // surface jobs (trees...): temporary, taken back afterwards. Mining: the blocks stay.
                mob.swing(InteractionHand.MAIN_HAND, true);
                lastProgress = now;
                mob.noteMiningProgress();
            }
            jumpCell = null;
        } else if (pillarAir > 25) {
            diag("TOWER JUMP DID NOT LIFT: y=" + String.format(java.util.Locale.ROOT, "%.2f", mob.getY())
                    + " cell=" + jumpCell.getY() + " ground=" + mob.onGround() + " water=" + mob.isInWater());
            jumpCell = null;                                              // the jump did not happen (head bump, water): retry
        }
    }

    private void endPillar() {
        pillarTarget = null;
        jumpCell = null;
        pillarAir = 0;
    }

    /** Climbs down by breaking the top block we placed (we stand on it) and takes the blocks back. true = still busy. */
    private boolean cleanupScaffold(ServerLevel level) {
        while (!scaffold.isEmpty() && level.getBlockState(scaffold.peekLast()).isAir()) scaffold.pollLast();
        if (scaffold.isEmpty()) { cleanupWait = 0; return false; }
        BlockPos top = scaffold.peekLast();
        if (!inReach(Vec3.atCenterOf(top))) {
            if (++cleanupWait > 120) { scaffold.clear(); cleanupWait = 0; return false; }   // cannot get back to the tower: leave it
            mob.getNavigation().moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 1.0);
            return true;
        }
        cleanupWait = 0;
        startBreak(level, top);
        if (breaking == null) scaffold.pollLast();                        // refused (protected / unbreakable): forget it
        return true;
    }

    /** True when a walkable way down exists (cave slope, natural staircase, pit edge) - navigation walks it, no digging. */
    private boolean walkDownSlope(ServerLevel level) {
        BlockPos down = mob.blockPosition().below(6);
        Path p = mob.getNavigation().createPath(down, 2);
        if (p == null || !p.canReach()) return false;
        walkTo = p.getTarget();
        walkMode = WALK_FREE;
        walkTicks = 0;
        mob.getNavigation().moveTo(p, 1.0);
        lastProgress = level.getGameTime();
        return true;
    }

    private void tunnelToward(ServerLevel level, MineTask t, BlockPos target) {
        BlockPos me = mob.blockPosition();
        int dx = target.getX() - me.getX(), dy = target.getY() - me.getY(), dz = target.getZ() - me.getZ();
        if (!t.deep() && dy >= 2) {          // surface job, target high above (top of a tall trunk): pillar or skip, never dig stairs into the ground
            if (!tryScaffold(level, t, target)) giveUp(target);
            return;
        }
        Direction d;
        if (dx == 0 && dz == 0) d = Direction.from2DDataValue(t.dirData);
        else if (Math.abs(dx) >= Math.abs(dz)) d = dx > 0 ? Direction.EAST : Direction.WEST;
        else d = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        int dyStep = dy < -1 ? -1 : dy > 1 ? 1 : 0;
        if (!planStep(level, t, d, dyStep, true)) {
            if (tryScaffold(level, t, target)) return;     // open air / cave: climb with blocks from the bag
            giveUp(target);
        }
    }

    /** Plans one tunnel step (clear cells, then walk onto {@code stand}). dyStep: -1 stairs down, 0 level, +1 stairs up. */
    private boolean planStep(ServerLevel level, MineTask t, Direction first, int dyStep, boolean allowTurn) {
        Direction[] order = allowTurn
                ? new Direction[]{first, first.getClockWise(), first.getCounterClockWise(), first.getOpposite()}
                : new Direction[]{first};
        BlockPos me = mob.blockPosition();
        for (Direction d : order) {
            BlockPos stand = me.relative(d).above(dyStep);
            List<BlockPos> cells = new ArrayList<>(3);
            cells.add(stand);
            cells.add(stand.above());
            if (dyStep < 0) cells.add(stand.above(2));                  // headroom to walk in before dropping
            boolean bridge = dyStep == 0 && t.deep() && bridgeRun < 12 && hasScaffoldBlock(t)
                    && level.getBlockState(stand.below()).isAir();      // gap in the floor while mining: bridge it
            if (!safe(level, stand, cells, bridge)) continue;
            for (BlockPos c : cells) if (!passable(level, c)) pending.add(c);
            bridgeCell = bridge ? stand.below().immutable() : null;
            bridgeRun = bridge ? bridgeRun + 1 : 0;
            stepStand = stand;
            t.dirData = d.get2DDataValue();
            return true;
        }
        return false;
    }

    private boolean safe(ServerLevel level, BlockPos stand, List<BlockPos> cells) {
        return safe(level, stand, cells, false);
    }

    private boolean safe(ServerLevel level, BlockPos stand, List<BlockPos> cells, boolean bridge) {
        BlockState floor = level.getBlockState(stand.below());
        if (!floor.getFluidState().isEmpty()) return false;                         // never into liquid
        if (floor.isAir() && !bridge) return false;                                 // would walk into a hole (unless we bridge it)
        int topY = Integer.MIN_VALUE;
        for (BlockPos c : cells) {
            BlockState s = level.getBlockState(c);
            if (!s.getFluidState().isEmpty() || s.getDestroySpeed(level, c) < 0) return false;
            topY = Math.max(topY, c.getY());
            for (Direction dd : Direction.values()) {
                BlockPos n = c.relative(dd);
                if (cells.contains(n)) continue;
                var f = level.getBlockState(n).getFluidState();
                if (f.is(FluidTags.LAVA) || !f.isEmpty()) return false;            // lava or water behind the wall
            }
        }
        BlockPos ceil = new BlockPos(stand.getX(), topY + 1, stand.getZ());
        BlockState cs = level.getBlockState(ceil);
        return !(cs.getBlock() instanceof FallingBlock) && cs.getFluidState().isEmpty();
    }

    private boolean passable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------ breaking and walking

    private void startBreak(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return;
        if (!CompanionProtection.canModify(level, mob, pos)) {
            unreachable.add(pos); oreTarget = null; pending.clear(); stepStand = null;
            say(level, "I'm not allowed to break blocks here.", false);
            return;
        }
        mob.equipBestToolFor(st);                                 // hold the right tool: visible swings + correct speed
        float h = st.getDestroySpeed(level, pos);
        if (h < 0) { unreachable.add(pos); oreTarget = null; pending.clear(); stepStand = null; return; }
        float speed = Math.max(1f, mob.getMainHandItem().getDestroySpeed(st));
        breakNeed = Math.max(3, Math.min(90, (int) (h * 30f / speed)));
        breakProgress = 0;
        breaking = pos;
        // Start only after the server equipment sync, so clients see the correct tool move.
        mob.swing(InteractionHand.MAIN_HAND, true);
    }

    private void keepBreaking(ServerLevel level, MineTask t, long now) {
        BlockState st = level.getBlockState(breaking);
        if (st.isAir()) { cancelBreak(level); lastProgress = now; return; }
        Vec3 c = Vec3.atCenterOf(breaking);
        if (mob.getEyePosition().distanceToSqr(c) > REACH_SQ + 2) {
            mob.getNavigation().moveTo(c.x, breaking.getY(), c.z, 1.0);
            return;
        }
        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(c.x, c.y, c.z, 30f, mob.getMaxHeadXRot());
        breakProgress++;
        // Re-broadcast repeatedly; a short three-tick break still gets a visible swing.
        if (breakProgress % 4 == 1) mob.swing(InteractionHand.MAIN_HAND, true);
        level.destroyBlockProgress(mob.getId(), breaking, Math.min(9, breakProgress * 10 / breakNeed));
        if (breakProgress < breakNeed) return;

        boolean hit = matches(t, st) && !scaffold.contains(breaking);   // taking our own scaffold back is not a harvest
        BlockPos pos = breaking;
        level.destroyBlockProgress(mob.getId(), pos, -1);
        level.destroyBlock(pos, true, mob, 512);
        breaking = null;
        nextScanAt = 0;      // always look for the next block immediately: this is what stops the "wander after the first tree" bug
        lastProgress = now;
        stuck = 0;
        mob.collectNearbyItems();
        if (hit && !t.deep()) replantAfterChop(level, pos, st);
        if (hit) onMined(level, t, pos);
    }

    private void onMined(ServerLevel level, MineTask t, BlockPos pos) {
        t.mined++;
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos n = pos.offset(dx, dy, dz);
            if (vein.size() < 24 && !vein.contains(n) && matches(t, level.getBlockState(n))) vein.add(n);
        }
        mob.noteMiningProgress();                 // keeps an AI plan step alive; progress is shown in the radial menu, not spoken
        // finishing (and climbing back down) is handled in decide()
    }

    private void walk(ServerLevel level, long now) {
        walkTicks++;
        if (walkTicks == 1) walkRetries = 0;
        // Progress heartbeat, but only for REAL movement: a mob pushing a wall in place (jam) must not keep
        // refreshing the idle timers, or the watchdogs can never fire and a step-loop stalls silently forever.
        if (walkTicks % 40 == 0 && mob.position().distanceToSqr(lastHeartbeatPos) > 0.25) {
            lastProgress = now;
            stuck = 0;
            lastHeartbeatPos = mob.position();
        }
        BlockPos w = walkTo;
        boolean arrived = switch (walkMode) {
            case WALK_REACH -> inReach(Vec3.atCenterOf(w));     // line of sight is handled by approach(): it digs the blocker out
            case WALK_FREE, WALK_EXPLORE -> mob.distanceToSqr(Vec3.atCenterOf(w)) < 4.0;
            // Must be standing IN the cell: the old 1.2 vertical tolerance let a mob pushing the wall BELOW a
            // 1-block step-up count as "arrived" (dy=1.0) without ever mounting it -> infinite plan-walk loop.
            default -> Math.abs(mob.getX() - (w.getX() + 0.5)) < 0.7 && Math.abs(mob.getZ() - (w.getZ() + 0.5)) < 0.7
                    && Math.abs(mob.getY() - w.getY()) < 0.5;
        };
        if (arrived) { walkTo = null; nextScanAt = 0; lastProgress = now; mob.getNavigation().stop(); return; }
        PathNavigation nav = mob.getNavigation();
        // Slope/step stalls: the path finished (or keeps ending) while the body stands still against a ledge.
        // One log per distinct stall state so latest.log shows exactly where and why it stopped moving.
        if (walkTicks > 60 && walkTicks % 40 == 0 && mob.position().distanceToSqr(lastHeartbeatPos) <= 0.25) {
            diag("STUCK walking mode=" + walkMode + " retries=" + walkRetries + " navDone=" + nav.isDone()
                    + " ground=" + mob.onGround() + " dy=" + (w.getY() - mob.getY())
                    + " target=" + w + " at=" + mob.blockPosition());
        }
        if (walkMode == WALK_EXPLORE) {
            // Look around while walking: the moment something matching is in sight, stop exploring and go for it.
            if (walkTicks % 10 == 0) {
                BlockPos found = scan(level, mob.mineTask());
                if (found != null) {
                    oreTarget = found;
                    wanderTries = 0;
                    walkTo = null;
                    nav.stop();
                    lastProgress = now;
                    return;
                }
            }
            if ((walkTicks > 10 && nav.isDone()) || walkTicks > 400) {   // end of path (even a partial one) = this leg is done
                walkTo = null;
                nextScanAt = 0;
            }
            return;
        }
        if (walkMode == WALK_STAND) {
            // Tunnel steps: NATURAL movement only. The step cell is the adjacent one WE just cleared, so the
            // block-by-block teleport ladder is removed: the pathfinder walks the cell like a player, the body
            // HOPS onto a step-up it stalls against, and if it still cannot get in the walk FAILS (planning
            // re-clears the step or turns the tunnel) instead of teleporting.
            double sdx = w.getX() + 0.5 - mob.getX(), sdz = w.getZ() + 0.5 - mob.getZ();
            double sdy = w.getY() - mob.getY();
            boolean adjacent = Math.abs(sdx) <= 1.6 && Math.abs(sdz) <= 1.6;
            if (mob.position().distanceToSqr(standLast) > 0.01) standStall = 0; else standStall++;
            standLast = mob.position();
            if (adjacent && standStall > 8 && sdy > 0.3 && sdy <= 1.6 && mob.onGround()) {
                standStall = 0;                                     // one hop per stall, never a hop-spam ladder
                diag("stand: hop onto step " + w);
                mob.getLookControl().setLookAt(w.getX() + 0.5, w.getY() + 1.0, w.getZ() + 0.5);
                mob.getJumpControl().jump();
            }
            if (walkTicks % 20 == 0) nav.moveTo(w.getX() + 0.5, w.getY(), w.getZ() + 0.5, 1.15);
            if (walkTicks > 240) {
                failWalk();
                stuck++;
                if (stuck >= 4) { mob.mineTask().pause(); say(level, "I can't get through here. Move me and say 'continue'.", true); }
            }
            return;
        }
        // Long walks use the pathfinder: re-request the path when it finished early or could not be made (airborne).
        if (walkTicks % 10 == 1 && nav.isDone() && mob.onGround()) {
            if (++walkRetries > 4) { failWalk(); return; }
            // Ledge hop: the pathfinder marks a 1-block step as reachable but the body just stands against
            // it ("stuck on slopes, can't jump"). One hop on the first retry, then normal re-pathing.
            if (walkRetries == 1) {
                double dx = w.getX() + 0.5 - mob.getX(), dz = w.getZ() + 0.5 - mob.getZ();
                double dy = w.getY() - mob.getY();
                if (dx * dx + dz * dz < 3.0 && dy > 0.3 && dy <= 1.6) {
                    diag("ledge hop toward " + w + " (dy=" + String.format(java.util.Locale.ROOT, "%.2f", dy) + ")");
                    mob.getJumpControl().jump();
                }
            }
            nav.moveTo(w.getX() + 0.5, w.getY(), w.getZ() + 0.5, walkMode == WALK_FREE ? 1.0 : 1.1);
        }
        if (walkTicks > 160) failWalk();
    }

    private void failWalk() {
        LOG.warn("[companionmod] walk failed after {}t: mode={} target={} at={} ground={}",
                walkTicks, walkMode, walkTo, mob.blockPosition(), mob.onGround());
        if (walkMode == WALK_REACH && approachTarget != null) giveUp(approachTarget);
        walkTo = null;
        nextScanAt = 0;
    }

    /** Block reach is measured from the eyes (like a player), not from the feet: 1.6 blocks more upward reach. */
    private boolean inReach(Vec3 c) { return mob.getEyePosition().distanceToSqr(c) <= REACH_SQ; }

    /** What the goal is doing right now ("digging", "walking", ...) - shown in the radial hub next to the task. */
    public String stateName() {
        return breaking != null ? "digging" : walkTo != null ? "walking" : pillarTarget != null ? "climbing"
                : !pending.isEmpty() ? "clearing" : "planning";
    }

    /** After felling a trunk base on soil: put a matching sapling from the bag back (silently). */
    private void replantAfterChop(ServerLevel level, BlockPos pos, BlockState chopped) {
        if (!chopped.is(BlockTags.LOGS) || !level.getBlockState(pos.below()).is(BlockTags.DIRT)) return;
        if (!level.getBlockState(pos).isAir()) return;
        String path = BuiltInRegistries.BLOCK.getKey(chopped.getBlock()).getPath();
        int cut = path.indexOf("_log");
        if (cut <= 0) cut = path.indexOf("_wood");
        if (cut <= 0) return;
        String wood = path.substring(0, cut);
        String saplingId = wood.equals("mangrove") ? "mangrove_propagule" : wood + "_sapling";
        for (int i = CompanionEntity.MAIN_START; i < CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE; i++) {
            ItemStack s = mob.storage().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            if (!BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(saplingId)) continue;
            BlockState sap = bi.getBlock().defaultBlockState();
            if (!sap.canSurvive(level, pos) || !CompanionProtection.canModify(level, mob, pos)) return;
            mob.storage().removeItem(i, 1);
            level.setBlock(pos, sap, 3);
            return;
        }
    }

    private void cancelBreak(ServerLevel level) {
        if (breaking != null) level.destroyBlockProgress(mob.getId(), breaking, -1);
        breaking = null;
    }

    // ------------------------------------------------------------------ sensing

    /**
     * Nearest match a player could actually see. Exposed blocks (air on at least one face) are preferred, but
     * the bonus is capped at ~12 blocks of distance so a seeable ore far away never hijacks the job from buried
     * ore right next to the companion. Buried blocks only count when close, and the crown height penalty keeps
     * high-up blocks from stealing the target. Columns outside loaded chunks are skipped, never force-loaded.
     */
    private BlockPos scan(ServerLevel level, MineTask t) {
        int r = t.phase == MineTask.Phase.SEARCH ? 22 : 10;
        int down = Math.min(r, 12), up = Math.min(r, 8);
        BlockPos o = mob.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = o.getX() - r; x <= o.getX() + r; x++) {
            for (int z = o.getZ() - r; z <= o.getZ() + r; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;         // never force-load/generate chunks from a scan
                for (int y = o.getY() - down; y <= o.getY() + up; y++) {
                    m.set(x, y, z);
                    if (!matches(t, level.getBlockState(m)) || unreachable.contains(m)) continue;
                    double d = mob.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
                    boolean exp = exposed(level, m);
                    if (d > 9.0 && !exp) continue;                      // buried blocks only count when close
                    double score = d + Math.max(0, y - (o.getY() + 2)) * 6.0 - (exp ? Math.min(12.0, d) : 0);
                    if (score >= bestD) continue;
                    bestD = score;
                    best = m.immutable();
                }
            }
        }
        return best;
    }

    private boolean exposed(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
        }
        return false;
    }

    private boolean lineClear(ServerLevel level, BlockPos target) {
        Vec3 eye = mob.getEyePosition(), goal = Vec3.atCenterOf(target), delta = goal.subtract(eye);
        for (int k = 1; k <= 8; k++) {
            BlockPos bp = BlockPos.containing(eye.add(delta.scale(k / 9.0)));
            if (bp.equals(target)) break;
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) return false;
        }
        return true;
    }

    /** Cached per-block match (the cache is only touched from the server thread, so a plain HashMap is fine). */
    private static boolean matches(MineTask t, BlockState state) {
        if (state.isAir()) return false;
        if (!t.blockId.equals(matchCacheId)) { MATCH_CACHE.clear(); matchCacheId = t.blockId; }
        return MATCH_CACHE.computeIfAbsent(state.getBlock(), b -> matchesSlow(t.blockId, b));
    }

    private static boolean matchesSlow(String bid, Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (path.equals(bid)) return true;
        if (bid.endsWith("_ore") && path.endsWith(bid)) return true;          // diamond_ore also matches deepslate_diamond_ore
        return switch (bid) {                                                 // family ids from "wood"/"planks"/... orders
            case "log", "wood" -> path.endsWith("_log") || path.endsWith("_wood");
            case "planks", "plank" -> path.endsWith("_planks");
            case "leaves" -> path.endsWith("_leaves");
            case "wool" -> path.endsWith("_wool");
            default -> false;
        };
    }

    private void say(ServerLevel level, String msg, boolean force) {
        long now = level.getGameTime();
        if (force || now - lastSpeak > 100) { mob.speak(msg); lastSpeak = now; }
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionSitGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Holds the companion still while in SIT mode. Higher priority than everything that moves. */
public class CompanionSitGoal extends Goal {
    private final CompanionEntity mob;

    public CompanionSitGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override public boolean canUse() { return mob.isSitting(); }
    @Override public boolean canContinueToUse() { return mob.isSitting(); }
    @Override public void start() { mob.getNavigation().stop(); mob.setTarget(null); }
    @Override public boolean isInterruptable() { return false; }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionTargetGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Thin wrapper: the decision logic lives in {@link CompanionEntity#chooseTarget()} so commands and AI share one source of truth. */
public class CompanionTargetGoal extends Goal {
    private final CompanionEntity mob;
    private LivingEntity pending;
    private int cooldown;

    public CompanionTargetGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (mob.isInjured()) return false;
        if (--cooldown > 0) return false;
        cooldown = 8;
        pending = mob.chooseTarget();
        return pending != null;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive() || mob.isSitting() || mob.isInjured() || mob.stance() == CompanionEntity.Stance.PASSIVE) return false;
        var owner = mob.getOwner();
        // No wall hacking: drop hidden targets unless they are fighting us/owner, or the player ordered them explicitly.
        if (!mob.isForced(t) && !mob.getSensing().hasLineOfSight(t)) {
            boolean aggro = t instanceof net.minecraft.world.entity.Mob m
                    && (m.getTarget() == mob || (owner != null && m.getTarget() == owner));
            if (!aggro) return false;
        }
        double leash = mob.moveMode() == CompanionEntity.MoveMode.STAY ? 12 : 28;
        var anchor = (owner != null && mob.moveMode() == CompanionEntity.MoveMode.FOLLOW) ? owner : mob;
        return anchor.distanceToSqr(t) < leash * leash;
    }

    @Override public void start() { mob.setTarget(pending); }

    @Override
    public void stop() {
        mob.setTarget(null);
        mob.getNavigation().stop();
    }
}
```

## common\src\main\java\com\example\companionmod\entity\ai\CompanionWanderGoal.java

```java
package com.example.companionmod.entity.ai;

import com.example.companionmod.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/** Relaxed wandering around the anchor (and the owner when following is not active). */
public class CompanionWanderGoal extends Goal {
    private final CompanionEntity mob;
    private int cooldown;
    private BlockPos target;

    public CompanionWanderGoal(CompanionEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.moveMode() == CompanionEntity.MoveMode.WANDER && !mob.isSitting() && !mob.isInjured();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        cooldown = 0;
        target = null;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        target = null;
    }

    @Override
    public void tick() {
        if (target != null && !mob.getNavigation().isDone()) {
            mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 10.0f, mob.getMaxHeadXRot());
            return;
        }
        if (--cooldown > 0) return;
        cooldown = 40;
        BlockPos anchor = mob.anchor();
        int dx = mob.getRandom().nextIntBetweenInclusive(-11, 11);
        int dz = mob.getRandom().nextIntBetweenInclusive(-11, 11);
        if (Math.abs(dx) < 3 && Math.abs(dz) < 3) dx += 3;
        int x = anchor.getX() + dx;
        int z = anchor.getZ() + dz;
        int y = mob.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        target = new BlockPos(x, y, z);
        if (!mob.getNavigation().moveTo(x + 0.5, y, z + 0.5, 0.85)) target = null;
    }
}
```

## common\src\main\java\com\example\companionmod\entity\CompanionEntity.java

```java
package com.example.companionmod.entity;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.ai.CompanionBuildGoal;
import com.example.companionmod.entity.ai.CompanionFarmGoal;
import com.example.companionmod.entity.ai.CompanionFollowGoal;
import com.example.companionmod.entity.ai.CompanionMeleeGoal;
import com.example.companionmod.entity.ai.CompanionFishGoal;
import com.example.companionmod.entity.ai.CompanionPatrolGoal;
import com.example.companionmod.entity.ai.CompanionPickupGoal;
import com.example.companionmod.entity.ai.CompanionPlantGoal;
import com.example.companionmod.entity.ai.CompanionSeekBlockGoal;
import com.example.companionmod.entity.ai.CompanionSitGoal;
import com.example.companionmod.entity.ai.CompanionTargetGoal;
import com.example.companionmod.entity.ai.CompanionWanderGoal;
import com.example.companionmod.entity.plan.CompanionPlanner;
import com.example.companionmod.entity.task.MineTask;
import com.example.companionmod.inventory.CompanionInventoryMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import com.example.companionmod.world.CompanionRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A player-shaped companion owned by one player.
 * State: MoveMode (what its legs do) x Stance (what it fights). Orders arrive through {@link #applyCommand}.
 * Everything gameplay-related runs server-side; synced data (MoveMode/Stance/Owner/Skin/Feminine/Speech/Injured)
 * is read by the client renderer, HUD and goals.
 *
 * <p>Inventory: 26 slots (4 armor, 4 tools, 18 storage) in one {@link SimpleContainer}. Armor and the first
 * tool are mirrored onto the equipment slots so they render and actually protect/heal... protect the companion.
 *
 * <p>Downed state: lethal damage converts into an injured crawl (Mojang's swimming pose). The companion cannot
 * act; the player revives it by holding left mouse button on it for 3 seconds. It bleeds out after 60 s.
 */
public class CompanionEntity extends PathfinderMob implements com.example.companionmod.entity.plan.PlanActions {
    public enum MoveMode { FOLLOW, STAY, SIT, PATROL, WANDER }
    /** DEFENSIVE: fight what threatens owner/self. GUARD: also attack any hostile near the owner. PASSIVE: never start a fight. */
    public enum Stance { DEFENSIVE, GUARD, PASSIVE }

    private static final EntityDataAccessor<Integer> DATA_MOVE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STANCE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_OWNER = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SKIN = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_FEMININE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PICKUP = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    /** Short progress text for the radial menu ("Wood 6/16"); empty when idle. Updated by the server every half second. */
    private static final EntityDataAccessor<String> DATA_TASK = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_SPEECH = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_SPEECH_ID = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_INJURED = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_REVIVE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_INJURED_LEFT = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private LivingEntity lastTargetSeen;      // combat chaining: our target as of the previous tick
    private int lastKillAt = -1000;           // when it died: chooseTarget chains to the next enemy for ~10 s

    /** Aliases the language model is likely to produce -> real block ids or family ids. */
    private static final Map<String, String> BLOCK_ALIASES = Map.ofEntries(
            Map.entry("diamond", "diamond_ore"), Map.entry("diamonds", "diamond_ore"),
            Map.entry("coal", "coal_ore"), Map.entry("iron", "iron_ore"),
            Map.entry("gold", "gold_ore"), Map.entry("copper", "copper_ore"),
            Map.entry("redstone", "redstone_ore"), Map.entry("lapis", "lapis_ore"),
            Map.entry("emerald", "emerald_ore"), Map.entry("quartz", "nether_quartz_ore"),
            Map.entry("netherite", "ancient_debris"), Map.entry("debris", "ancient_debris"),
            Map.entry("ancient_debris", "ancient_debris"), Map.entry("diamond_block", "diamond_block"),
            Map.entry("iron_block", "iron_block"), Map.entry("gold_block", "gold_block"),
            Map.entry("rock", "stone"), Map.entry("rocks", "stone"), Map.entry("cobble", "cobblestone"),
            Map.entry("soil", "dirt"), Map.entry("timber", "log"), Map.entry("lumber", "log"),
            Map.entry("tree", "log"), Map.entry("trees", "log"), Map.entry("trunk", "log"),
            Map.entry("wood_planks", "planks"), Map.entry("wooden_planks", "planks"));
    /** Not one block but a whole family: any log/wood, any planks, any leaves, any wool. */
    private static final Set<String> FAMILY_IDS = Set.of("log", "wood", "planks", "plank", "leaves", "wool");

    public static final EquipmentSlot[] ARMOR_SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    // Storage layout: 0-3 armor, 4-7 tools, 8-25 main (18 slots).
    public static final int SIZE = 26, ARMOR_START = 0, TOOL_START = 4, MAIN_START = 8, MAIN_SIZE = 18;

    private UUID ownerUuid;
    private LivingEntity forcedTarget;
    private int forcedTargetUntil;
    private String lastSpokenText = "";
    private long lastSpokenTime = 0;
    private int lastSpokenTick = -10000;

    private BlockPos anchor;                 // patrol / wander centre
    private int patrolIndex;

    // "go and find/mine this block" job (server side, saved with the entity)
    private final MineTask mineTask = new MineTask();
    // Assigned in registerGoals() - NOT a field initializer: registerGoals runs from the Mob constructor,
    // before this subclass's field initializers, so an initialized field would still be null there (this-escape).
    private com.example.companionmod.entity.ai.CompanionSeekBlockGoal seekGoal;
    // "pick this up from the ground" order (server side)
    private String pickupFilter = "";
    private boolean pickupWasEnabled;   // transient: was the user's pickup toggle on before the current order
    private boolean pickupRequested;

    // Inventory: armor row, tool row, main storage.
    private final SimpleContainer storage = new SimpleContainer(SIZE);

    // Downed / revive state.
    private boolean forceDeath;              // set when the revive timer runs out: this death is real
    private DamageSource injurySource;
    private long lastRevivePulse;
    private float reviveProgress;
    private int injuredLeftTicks;
    private static final int REVIVE_SECONDS = 60;
    private static final float REVIVE_TICKS = 3.0f * 20.0f;   // 3 s of holding left mouse

    // AI plan executor (the model never runs anything itself; it only returns validated plan data).
    private final CompanionPlanner planner = new CompanionPlanner();
    private boolean applyingPlanStep;
    private boolean registryChecked;

    // job state (time-boxed / schematic-driven behaviours)
    private long farmUntil, fishUntil, plantUntil;
    private String plantFilter = "";
    private String buildSchematic = "";
    private int selectedToolSlot = 0;          // which tool slot is held in the main hand
    private int targetSinceTick;               // when the current automatic target was acquired
    private long lastOuchTick;                 // hurt chatter throttle
    private long lastNoToolNudge;              // "give me a tool" throttle
    private long lastGoalInactiveLog;          // "task assigned but goal dormant" warning throttle
    private String lastPlanJson = "";          // last plan, so "continue" can resume it

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanFloat(true);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 2.0)   // weapon modifiers add on top
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 0.0);
    }

    // ------------------------------------------------------------------ goals

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new CompanionSitGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));   // villager-style: open wooden doors on the path, close behind
        goalSelector.addGoal(2, new CompanionMeleeGoal(this, 1.25, true));   // + creeper hit-and-run (attack - retreat - attack)
        seekGoal = new com.example.companionmod.entity.ai.CompanionSeekBlockGoal(this);
        goalSelector.addGoal(2, seekGoal);                           // explicit "find diamonds" pre-empts walking
        goalSelector.addGoal(3, new CompanionPickupGoal(this));      // explicit "pick this up" pre-empts walking
        goalSelector.addGoal(4, new CompanionFarmGoal(this));
        goalSelector.addGoal(4, new CompanionPlantGoal(this));        // time-boxed jobs (farm / fish / build)
        goalSelector.addGoal(4, new CompanionFishGoal(this));
        goalSelector.addGoal(4, new CompanionBuildGoal(this));
        goalSelector.addGoal(4, new CompanionPatrolGoal(this));
        goalSelector.addGoal(4, new CompanionWanderGoal(this));
        goalSelector.addGoal(5, new CompanionFollowGoal(this, 1.1, 4.0, 2.0, 24.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new CompanionTargetGoal(this));
    }

    // ------------------------------------------------------------------ synced + saved state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_MOVE, MoveMode.FOLLOW.ordinal());
        builder.define(DATA_STANCE, Stance.DEFENSIVE.ordinal());
        builder.define(DATA_OWNER, "");
        builder.define(DATA_SKIN, "");
        builder.define(DATA_FEMININE, 0);
        builder.define(DATA_PICKUP, 0);
        builder.define(DATA_TASK, "");
        builder.define(DATA_SPEECH, "");
        builder.define(DATA_SPEECH_ID, 0);
        builder.define(DATA_INJURED, 0);
        builder.define(DATA_REVIVE, 0.0f);
        builder.define(DATA_INJURED_LEFT, 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) { // 26.1-CHECK: ValueOutput/ValueInput replaced CompoundTag in 1.21.6
        super.addAdditionalSaveData(out);
        out.putInt("MoveMode", moveMode().ordinal());
        out.putInt("Stance", stance().ordinal());
        if (ownerUuid != null) out.putString("Owner", ownerUuid.toString());
        out.putString("Skin", skin());
        out.putBoolean("Feminine", isFeminine());
        out.putBoolean("Pickup", isPickupEnabled());
        out.putString("PickupFilter", pickupFilter);
        mineTask.save(out);
        out.store("Storage", ItemStack.OPTIONAL_CODEC.listOf(), storage.getItems());
        out.putBoolean("Injured", isInjured());
        out.putInt("InjuredLeft", injuredLeftTicks);
        out.putFloat("Revive", reviveProgress);
        if (anchor != null) out.putIntArray("Anchor", new int[]{anchor.getX(), anchor.getY(), anchor.getZ()});
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        setMoveMode(MoveMode.values()[Math.floorMod(in.getIntOr("MoveMode", 0), MoveMode.values().length)]);
        setStance(Stance.values()[Math.floorMod(in.getIntOr("Stance", 0), Stance.values().length)]);
        String o = in.getStringOr("Owner", "");
        if (!o.isEmpty()) {
            try { setOwner(UUID.fromString(o)); } catch (IllegalArgumentException ignored) { }
        }
        setSkin(in.getStringOr("Skin", ""));
        setFeminine(in.getBooleanOr("Feminine", false));
        setPickupEnabled(in.getBooleanOr("Pickup", false));
        pickupFilter = in.getStringOr("PickupFilter", "");
        mineTask.load(in);
        in.read("Storage", ItemStack.OPTIONAL_CODEC.listOf()).ifPresent(list -> {
            for (int i = 0; i < Math.min(list.size(), storage.getContainerSize()); i++) {
                storage.setItem(i, list.get(i));
            }
        });
        boolean injured = in.getBooleanOr("Injured", false);
        injuredLeftTicks = in.getIntOr("InjuredLeft", 0);
        reviveProgress = in.getFloatOr("Revive", 0.0f);
        if (injured) {
            entityData.set(DATA_INJURED, 1);
            entityData.set(DATA_REVIVE, reviveProgress);
            entityData.set(DATA_INJURED_LEFT, injuredLeftTicks);
            setPose(Pose.SWIMMING);
        }
        int[] a = in.getIntArray("Anchor").orElse(new int[0]);
        if (a.length == 3) anchor = new BlockPos(a[0], a[1], a[2]);
        refreshEquipment();
    }

    public MoveMode moveMode() { return MoveMode.values()[entityData.get(DATA_MOVE)]; }
    public Stance stance() { return Stance.values()[entityData.get(DATA_STANCE)]; }
    public void setMoveMode(MoveMode m) { entityData.set(DATA_MOVE, m.ordinal()); }
    public void setStance(Stance s) { entityData.set(DATA_STANCE, s.ordinal()); }
    public boolean isSitting() { return moveMode() == MoveMode.SIT; }

    public String skin() { return entityData.get(DATA_SKIN); }
    /** Accepts "", "owner", "default" or a plain Minecraft name; anything else is ignored (every nearby client would look it up). */
    public void setSkin(String skin) {
        String s = skin == null ? "" : skin.trim();
        boolean ok = s.isEmpty() || s.equalsIgnoreCase("owner") || s.equalsIgnoreCase("default") || s.matches("[A-Za-z0-9_]{1,16}");
        if (ok) entityData.set(DATA_SKIN, s);
    }
    public boolean isFeminine() { return entityData.get(DATA_FEMININE) != 0; }
    public void setFeminine(boolean value) { entityData.set(DATA_FEMININE, value ? 1 : 0); }
    public boolean isPickupEnabled() { return entityData.get(DATA_PICKUP) != 0; }
    public void setPickupEnabled(boolean value) { entityData.set(DATA_PICKUP, value ? 1 : 0); }

    public String speech() { return entityData.get(DATA_SPEECH); }
    public int speechId() { return entityData.get(DATA_SPEECH_ID); }

    public boolean isInjured() { return entityData.get(DATA_INJURED) != 0; }
    public float reviveProgress() { return entityData.get(DATA_REVIVE); }
    public int injuredLeftTicks() { return entityData.get(DATA_INJURED_LEFT); }

    public MineTask mineTask() { return mineTask; }

    public long farmUntil() { return farmUntil; }
    public long plantUntil() { return plantUntil; }
    public String plantFilter() { return plantFilter; }
    public void endPlant() { plantUntil = 0; plantFilter = ""; }
    public String taskText() { return entityData.get(DATA_TASK); }
    /** Called by work goals whenever they make real progress, so a timed AI plan step is not cut off mid-job. */
    public void noteMiningProgress() { planner.extendDeadline(tickCount, 600); }
    public long fishUntil() { return fishUntil; }
    public String buildSchematic() { return buildSchematic; }
    /** Clears the current build job (called by the build goal when it finishes). */
    public void clearBuild() { buildSchematic = ""; }

    public String seekBlock() { return mineTask.exists() ? mineTask.blockId : ""; }
    /** Starts (or restarts) a mining job; a blank id cancels it. Re-issuing the SAME id never resets progress. */
    public void setSeekBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            mineTask.finish();
        } else if (mineTask.exists() && mineTask.blockId.equals(blockId)) {
            // The model/plans re-issue the same order constantly; restarting reset mined to 0 every time,
            // so the job could never finish and the companion looked obsessed (always iron, never done).
            mineTask.resume();
        } else {
            boolean nether = level().dimension() == net.minecraft.world.level.Level.NETHER;
            mineTask.begin(blockId, nether, getDirection().get2DDataValue());
        }
    }
    public void clearSeekBlock() { mineTask.finish(); }

    public String pickupFilter() { return pickupFilter == null ? "" : pickupFilter; }
    public boolean isPickupRequested() { return pickupRequested; }

    public BlockPos anchor() { return anchor == null ? blockPosition() : anchor; }
    public void setAnchor(BlockPos pos) { anchor = pos; }
    public int patrolIndex() { return patrolIndex; }
    public void setPatrolIndex(int i) { patrolIndex = i; }

    public SimpleContainer storage() { return storage; }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwner(UUID uuid) { ownerUuid = uuid; entityData.set(DATA_OWNER, uuid == null ? "" : uuid.toString()); }
    /** Works on both sides (the synced string), used by the client to find "my" companion. */
    public boolean isOwnedBy(String uuidString) { return !uuidString.isEmpty() && uuidString.equals(entityData.get(DATA_OWNER)); }
    public Player getOwner() { return ownerUuid == null ? null : level().getPlayerByUUID(ownerUuid); }
    public boolean isOwner(Player p) { return ownerUuid != null && ownerUuid.equals(p.getUUID()); }

    // ------------------------------------------------------------------ orders

    public void applyCommand(CompanionCommand cmd, ServerPlayer from) {
        applyCommand(cmd, from, "");
    }

    public void applyCommand(CompanionCommand cmd, ServerPlayer from, String target) {
        if (!applyingPlanStep && cmd.interruptsPlanning()) {
            // One explicit intent at a time: a manual order pauses the AI plan (STOP forgets it entirely).
            planner.notePlayerOrder(tickCount);
        }
        switch (cmd) {
            case FOLLOW -> { clearSeekBlock(); setMoveMode(MoveMode.FOLLOW); }
            case STAY, WAIT -> { clearSeekBlock(); setMoveMode(MoveMode.STAY); setAnchor(blockPosition()); getNavigation().stop(); }
            case SIT -> { clearSeekBlock(); setMoveMode(MoveMode.SIT); getNavigation().stop(); setTarget(null); }
            case STAND -> { if (isSitting()) setMoveMode(MoveMode.FOLLOW); }
            case PATROL -> {
                clearSeekBlock();
                setMoveMode(MoveMode.PATROL);
                setAnchor(from == null ? blockPosition() : from.blockPosition());
                setPatrolIndex(0);
            }
            case WANDER, EXPLORE, SCOUT -> {
                clearSeekBlock();
                setMoveMode(MoveMode.WANDER);
                setAnchor(from == null ? blockPosition() : from.blockPosition());
            }
            case ATTACK, FIGHT, HUNT -> {
                clearSeekBlock();
                if (isSitting()) setMoveMode(MoveMode.FOLLOW);
                if (stance() == Stance.PASSIVE) setStance(Stance.DEFENSIVE);
                LivingEntity t = findTarget(from, target);
                if (t == null) t = from == null ? null : pickAttackTarget(from);
                if (t != null) { forcedTarget = t; forcedTargetUntil = tickCount + 600; setTarget(t); }
            }
            case GUARD, DEFEND -> { if (isSitting()) setMoveMode(MoveMode.FOLLOW); setStance(Stance.GUARD); }
            case DEFENSIVE -> { setStance(Stance.DEFENSIVE); forcedTarget = null; setTarget(null); }
            case PASSIVE -> { setStance(Stance.PASSIVE); forcedTarget = null; setTarget(null); getNavigation().stop(); }
            case COME -> {
                clearSeekBlock();
                if (isSitting()) setMoveMode(MoveMode.FOLLOW);
                if (from != null) {
                    // A "come" that is part of a plan (bringing the loot back) walks like a person; only a spoken "come" warps.
                    if (applyingPlanStep && from.level() == level() && distanceToSqr(from) < 48 * 48) setMoveMode(MoveMode.FOLLOW);
                    else teleportNear(from);
                }
            }
            case RETREAT, FLEE -> {
                clearSeekBlock();
                setStance(Stance.PASSIVE);
                forcedTarget = null;
                setTarget(null);
                if (from != null) teleportNear(from);
            }
            case STOP -> { hardStop(); speak("Stopped."); }
            case INVENTORY -> { if (from != null) openInventory(from); }
            case HEAL -> {
                heal(6.0f);
                Player owner = getOwner();
                if (owner != null && owner.distanceToSqr(this) < 144) owner.heal(4.0f);
            }
            case EQUIP -> equipByName(target == null ? "" : target);
            // "pick up this bone" -> walk to the dropped item and store it. "collect wood" (a block, and nothing
            // matching is lying around) -> go mine it instead. Never opens the inventory.
            case PICKUP, LOOT, TAKE -> {
                String filter = target == null ? "" : target.trim();
                String id = normalizeBlockId(filter);
                if (!id.isBlank() && !isPlantId(id) && !anyMatchingDrops(filter)) {   // saplings/flowers are items to pick up, never blocks to break
                    clearSeekBlock();
                    setSeekBlock(id);
                    setAnchor(blockPosition());
                    getNavigation().stop();
                    speak("Looking for " + prettyBlockId(id) + ".");
                } else requestPickup(filter);
            }
            case DROP, GIVE -> dropOneItem();
            // "find diamonds / coal" -> walk to the nearest matching block and mine it.
            case SEEK_BLOCK, MINE, FIND, SEEK, SEARCH -> {
                String id = normalizeBlockId(target);
                if (id.isBlank()) {
                    // Not a block (or no target): fall back to scouting around instead of doing nothing.
                    setMoveMode(MoveMode.WANDER);
                    setAnchor(blockPosition());
                    speak(target == null || target.isBlank()
                            ? "What should I look for?"
                            : "I don't know where to find " + target + ". I'll scout around.");
                } else {
                    clearSeekBlock();
                    setSeekBlock(id);
                    setAnchor(blockPosition());
                    getNavigation().stop();
                    speak("Looking for " + prettyBlockId(id) + ".");
                }
            }
            case SET_NAME -> {
                String n = sanitizeName(target);
                if (!n.isBlank()) {
                    setCustomName(Component.literal(n));
                    setCustomNameVisible(false);
                }
            }
            case SET_SKIN -> setSkin(target == null ? "" : target.trim());
            case FEMININE -> setFeminine(true);
            case MASCULINE -> setFeminine(false);
            case REVIVE -> {
                // Must be the owner and actually next to the body (the client sends pulses while holding right click).
                if (from != null && isOwner(from) && from.distanceToSqr(this) <= 49.0) revivePulse();
            }
            case PLAN -> { lastPlanJson = target == null ? "" : target; planner.accept(target, this, tickCount); }
            case CONTINUE -> {
                // Resume whatever was interrupted: a paused AI plan first, then a stuck/paused mining job,
                // then (last resort) the last plan JSON the model gave us.
                if (planner.resume(tickCount)) speak("Continuing the plan.");
                else if (mineTask().running() || mineTask().resume()) speak("Back to work.");
                else if (!lastPlanJson.isBlank() && planner.accept(lastPlanJson, this, tickCount)) speak("Restarting the plan.");
                else speak("There is nothing to continue.");
            }
            case PLANT -> {
                clearSeekBlock();
                plantFilter = target == null ? "" : target.toLowerCase(Locale.ROOT).trim().replace("minecraft:", "")
                        .replace(' ', '_').replace("saplings", "sapling");
                plantUntil = level().getGameTime() + 1200;
                setAnchor(blockPosition());
                getNavigation().stop();
            }
            case SURFACE -> {
                clearSeekBlock();
                mineTask.beginSurface(getDirection().get2DDataValue());
                setAnchor(blockPosition());
                getNavigation().stop();
                speak("Digging up.");
            }
            case FARM -> {
                clearSeekBlock();      // a running mine task has HIGHER goal priority: without this it starved every job order
                farmUntil = level().getGameTime() + 600; setAnchor(blockPosition()); speak("Farming.");
            }
            case FISH -> {
                clearSeekBlock();
                fishUntil = level().getGameTime() + 600; setAnchor(blockPosition()); speak("Going fishing.");
            }
            case BUILD -> {
                String name = target == null ? "" : target.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
                if (name.isEmpty()) speak("Build what? Give me a schematic name.");
                else { clearSeekBlock(); buildSchematic = name; setAnchor(blockPosition()); speak("Building " + name + "."); }
            }
            case REMEMBER, FORGET -> { /* handled client-side by CompanionMemory */ }
            default -> { }
        }
    }

    /** Opens the companion's 26-slot inventory (armor / tools / storage) in a custom screen. */
    public void openInventory(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new CompanionInventoryMenu(id, inv, this),
                Component.literal(getName().getString() + "'s Inventory")));
    }

    /**
     * Emergency stop: cancel every kind of queued or time-boxed work, clear combat, and hold this spot.
     * Unlike STAY, this also forgets the last plan, so CONTINUE cannot bring the cancelled work back.
     */
    public void hardStop() {
        planner.cancel();
        lastPlanJson = "";
        clearSeekBlock();
        finishPickup(false);
        farmUntil = 0;
        plantUntil = 0;
        fishUntil = 0;
        clearBuild();
        forcedTarget = null;
        forcedTargetUntil = 0;
        setStance(Stance.PASSIVE);
        setTarget(null);
        getNavigation().stop();
        setMoveMode(MoveMode.STAY);
        setAnchor(blockPosition());
    }

    /**
     * Floating subtitle above the companion's head. The text is synced (never the entity's real name),
     * so the client renderer can animate it rising and fading while the name tag stays untouched.
     */
    public void speak(String text) {
        if (!(level() instanceof ServerLevel)) return;
        if (text == null || text.isBlank()) return;
        this.lastSpokenText = text;
        this.lastSpokenTime = System.currentTimeMillis();
        this.lastSpokenTick = tickCount;
        entityData.set(DATA_SPEECH, text);
        entityData.set(DATA_SPEECH_ID, entityData.get(DATA_SPEECH_ID) + 1);
    }

    public String lastSpokenText() { return lastSpokenText; }

    // ------------------------------------------------------------------ inventory

    /** True when the dropped item is what the player asked for (empty filter = anything). */
    public boolean matchesPickup(ItemEntity item) {
        return item != null && itemMatchesFilter(pickupFilter(), item.getItem());
    }

    /** Explicit "pick this up" order: walk to the matching dropped item instead of opening the inventory. */
    public void requestPickup(String filter) {
        pickupWasEnabled = isPickupEnabled();          // remember the user's own toggle: an order must not clobber it
        pickupFilter = filter == null ? "" : filter.trim();
        pickupRequested = true;
        setPickupEnabled(true);
        getNavigation().stop();
    }

    /** Ends an explicit pickup order; {@code storedAny} controls whether the companion confirms out loud. */
    public void finishPickup(boolean storedAny) {
        if (pickupRequested && storedAny) {
            speak(pickupFilter.isBlank() ? "Picked up the items."
                    : "Picked up " + pickupFilter.replace('_', ' ') + ".");
        }
        pickupRequested = false;
        pickupFilter = "";
        // Old behaviour force-disabled pickup after EVERY order - so one explicit "pick this up" silently
        // killed the player's standing pickup toggle and items stopped being collected ("sometimes doesn't
        // pick up items"). Only turn it off when it was off before the order.
        if (!pickupWasEnabled) setPickupEnabled(false);
        pickupWasEnabled = false;
    }

    /** True when a dropped item matching the words is on the ground nearby (so "collect" should loot, not mine). */
    private boolean anyMatchingDrops(String filter) {
        if (filter == null || filter.isBlank()) return true;      // bare "pick up" = ground sweep
        for (ItemEntity e : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(16),
                x -> x.isAlive() && !x.getItem().isEmpty())) {
            if (itemMatchesFilter(filter, e.getItem())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ lenient item matching (voice = wheel)

    /**
     * One shared, STT-forgiving matcher for every item filter so a voice order ("pick up golden carrots")
     * finds exactly what the radial wheel's unfiltered pick finds. Lowercase, spaces/dashes become
     * separators, filler words ("pick up some the...") are dropped, plural endings are relaxed, and each
     * remaining word must appear somewhere in the item id ("golden_carrot") or hover name ("Golden Carrot").
     */
    public static boolean itemMatchesFilter(String filter, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (filter == null || filter.isBlank()) return true;
        List<String> tokens = filterTokens(filter);
        if (tokens.isEmpty()) return true;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (!wordIn(id, token) && !wordIn(name, token)) return false;
        }
        return true;
    }

    /** Lowercase word tokens of a filter, with filler/command words stripped wherever they appear. */
    private static List<String> filterTokens(String filter) {
        List<String> out = new ArrayList<>();
        for (String raw : filter.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.isBlank() || FILLER_WORDS.contains(raw)) continue;   // "pick up some golden carrots" -> "golden carrots"
            out.add(raw);
        }
        return out;
    }

    private static final Set<String> FILLER_WORDS = Set.of(
            "a", "an", "the", "some", "any", "all", "that", "this", "those", "these", "it", "its", "my", "me",
            "pick", "up", "please", "get", "got", "grab", "take", "takes", "bring", "collect", "loot");

    /** Words that mean "anything usable" when they stand alone. */
    private static final Set<String> FAMILY_WORDS = Set.of(
            "weapon", "tool", "gear", "equipment", "something", "item");

    /** Whole-word or contained match, tolerant of plural endings ("carrots" finds "golden_carrot"). */
    private static boolean wordIn(String haystack, String token) {
        if (haystack.isBlank() || token.isBlank()) return false;
        if (haystack.contains(token)) return true;
        if (token.endsWith("ies") && token.length() > 4
                && haystack.contains(token.substring(0, token.length() - 3) + "y")) return true; // berries -> berry
        if (token.endsWith("s") && token.length() > 3
                && haystack.contains(token.substring(0, token.length() - 1))) return true;       // carrots -> carrot
        return false;
    }

    /**
     * Stores a stack in its real category: matching armor, then the one matching
     * pickaxe/axe/shovel/sword slot, then general storage. Never displaces gear.
     */
    public ItemStack store(ItemStack stack) {
        if (stack.isEmpty()) return stack;
        ItemStack rest = stack.copy();
        EquipmentSlot armorSlot = armorSlotFor(rest);
        if (armorSlot != null) {
            int idx = ARMOR_START + armorSlotIndex(armorSlot);
            if (storage.getItem(idx).isEmpty()) {
                storage.setItem(idx, rest.split(rest.getCount()));
                storage.setChanged();
                refreshEquipment();
                return ItemStack.EMPTY;
            }
        }
        int toolSlot = CompanionTools.dedicatedSlot(rest);
        if (toolSlot >= 0 && storage.getItem(toolSlot).isEmpty()) {
            storage.setItem(toolSlot, rest.split(rest.getCount()));
            storage.setChanged();
            refreshEquipment();
            return ItemStack.EMPTY;
        }
        ItemStack left = insertMain(rest);
        storage.setChanged();
        refreshEquipment();
        return left;
    }

    private ItemStack insertMain(ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int i = MAIN_START; i < MAIN_START + MAIN_SIZE && !rest.isEmpty(); i++) {
            ItemStack in = storage.getItem(i);
            if (in.isEmpty()) {
                int count = Math.min(rest.getCount(), rest.getMaxStackSize());
                storage.setItem(i, rest.split(count));
            } else if (ItemStack.isSameItemSameComponents(in, rest)) {
                int space = Math.min(in.getMaxStackSize(), storage.getMaxStackSize()) - in.getCount();
                if (space > 0) {
                    int count = Math.min(space, rest.getCount());
                    in.grow(count);
                    rest.shrink(count);
                }
            }
        }
        return rest;
    }

    /** Sweeps up dropped items around the companion (used by LOOT and the seek goal's mining). */
    public void collectNearbyItems() {
        if (!(level() instanceof ServerLevel)) return;
        List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(10),
                e -> e.isAlive() && !e.getItem().isEmpty() && matchesPickup(e));
        for (ItemEntity item : items) storeItem(item);
    }

    /** Stores a single dropped item; returns true when the whole stack fitted. */
    public boolean storeItem(ItemEntity item) {
        if (item == null || item.getItem().isEmpty()) return false;
        ItemStack rest = store(item.getItem());
        if (rest.isEmpty()) { item.discard(); return true; }
        item.setItem(rest);
        return false;
    }

    private void dropOneItem() {
        for (int i = 0; i < storage.getContainerSize(); i++) {
            ItemStack stack = storage.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack one = storage.removeItem(i, 1);
            ItemEntity drop = new ItemEntity(level(), getX(), getY() + 1.1, getZ(), one);
            drop.setDeltaMovement(random.nextGaussian() * 0.08, 0.25, random.nextGaussian() * 0.08);
            level().addFreshEntity(drop);
            return;
        }
    }

    /** Migrates legacy/misplaced items without deleting, dropping or overwriting anything. */
    private void normalizeToolSlots() {
        for (int offset = 0; offset < 4; offset++) {
            int slot = TOOL_START + offset;
            ItemStack stack = storage.getItem(slot);
            if (stack.isEmpty() || CompanionTools.dedicatedSlot(stack) == slot) continue;
            int correct = CompanionTools.dedicatedSlot(stack);
            if (correct >= 0 && storage.getItem(correct).isEmpty()) {
                storage.setItem(slot, ItemStack.EMPTY);   // move into its free dedicated slot
                storage.setItem(correct, stack);
            }
            // otherwise leave it where the player put it: tool slots are universal now
        }
        storage.setChanged();
    }

    /** Mirrors armor and the deliberately selected tool onto real equipment slots. */
    public void refreshEquipment() {
        if (level().isClientSide()) return;
        normalizeToolSlots();
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            EquipmentSlot slot = ARMOR_SLOTS[i];
            ItemStack want = storage.getItem(ARMOR_START + i);
            ItemStack current = getItemBySlot(slot);
            if (!want.isEmpty() && isValidArmor(want, slot)) {
                if (!ItemStack.matches(current, want)) setItemSlot(slot, want.copy());
            } else if (!current.isEmpty()) setItemSlot(slot, ItemStack.EMPTY);
        }
        // Tool slots are universal now: hold whatever the player put there, regardless of item family.
        if (selectedToolSlot >= 0 && selectedToolSlot < 4 && storage.getItem(TOOL_START + selectedToolSlot).isEmpty()) {
            selectedToolSlot = -1;
        }
        if (selectedToolSlot < 0) {
            for (int i = 0; i < 4; i++) {
                if (!storage.getItem(TOOL_START + i).isEmpty()) { selectedToolSlot = i; break; }
            }
        }
        ItemStack tool = selectedToolSlot >= 0 ? storage.getItem(TOOL_START + selectedToolSlot) : ItemStack.EMPTY;
        if (!ItemStack.matches(getMainHandItem(), tool)) setItemSlot(EquipmentSlot.MAINHAND, tool.copy());
    }

    private static int armorSlotIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    private static EquipmentSlot armorSlotFor(ItemStack stack) {
        Equippable e = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        if (e == null) return null;
        return switch (e.slot()) {
            case HEAD, CHEST, LEGS, FEET -> e.slot();
            default -> null;
        };
    }

    private static boolean isValidArmor(ItemStack stack, EquipmentSlot slot) {
        Equippable e = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return e != null && e.slot() == slot;
    }

    private static boolean isTool(ItemStack stack) {
        return CompanionTools.isDedicatedTool(stack);
    }

    // ------------------------------------------------------------------ injured / revive

    /** Lethal damage converts into the downed state instead of death (first time only). */
    private void injure(DamageSource source) {
        this.injurySource = source;
        this.injuredLeftTicks = 20 * REVIVE_SECONDS;
        this.reviveProgress = 0f;
        this.lastRevivePulse = 0;
        entityData.set(DATA_INJURED, 1);
        entityData.set(DATA_REVIVE, 0f);
        entityData.set(DATA_INJURED_LEFT, injuredLeftTicks);
        setHealth(1.0f);
        forcedTarget = null;
        setTarget(null);
        clearSeekBlock();
        pickupRequested = false;
        pickupFilter = "";
        setPickupEnabled(false);
        if (isSitting()) setMoveMode(MoveMode.STAY);
        getNavigation().stop();
        speak("I'm down! Hold right click on me to revive me!");
        makeMobsForgetMe();
    }

    /** Called from the REVIVE order while the player holds right mouse button on the companion. */
    public void revivePulse() {
        if (!isInjured() || isDeadOrDying()) return;
        lastRevivePulse = System.currentTimeMillis();
    }

    /** Revives the companion (used by the revive command for testing and by the revive timer). */
    public void revive() {
        if (isDeadOrDying() || isRemoved()) return;   // reviving a fully-died body corrupted the death state and crashed
        entityData.set(DATA_INJURED, 0);
        entityData.set(DATA_REVIVE, 0f);
        entityData.set(DATA_INJURED_LEFT, 0);
        reviveProgress = 0f;
        setHealth(getMaxHealth());
        setPose(Pose.STANDING);
        speak("Thanks, I'm back on my feet!");
    }

    /** The 60 s revive window ran out: drop the gear and really die. */
    private void bleedOut() {
        if (forceDeath || isRemoved()) return;      // never re-enter: a half-committed death used to re-run die() every tick
        speak("I'm fading...");
        dropStorageAndClear();
        forceDeath = true;
        // Downed flow is OVER: clearing the flag stops the per-tick injured branch (which re-entered
        // bleedOut forever) and disables revive input on the client and server.
        entityData.set(DATA_INJURED, 0);
        entityData.set(DATA_REVIVE, 0f);
        entityData.set(DATA_INJURED_LEFT, 0);
        reviveProgress = 0f;
        injuredLeftTicks = 0;
        setHealth(0f);                             // real death: isAlive()==false -> vanilla tickDeath advances and removes us
        die(injurySource != null ? injurySource : level().damageSources().generic());
    }

    public boolean isReviving() {
        return isInjured() && System.currentTimeMillis() - lastRevivePulse < 700;
    }

    // ------------------------------------------------------------------ PlanActions (AI plan execution)

    /** Accepts a plan (JSON from the client or a subcommand). Returns true if a plan was started. */
    public boolean plan(String json) {
        return planner.accept(json, this, tickCount);
    }

    @Override public Player planOwner() { return getOwner(); }

    @Override
    public void planCommand(CompanionCommand cmd, String arg) {
        applyingPlanStep = true;
        try {
            ServerPlayer owner = getOwner() instanceof ServerPlayer sp ? sp : null;
            applyCommand(cmd, owner, arg);
        } finally {
            applyingPlanStep = false;
        }
    }

    @Override public void planSay(String text) { speak(text); }
    @Override public boolean planSeekActive() { return !seekBlock().isBlank(); }
    @Override public boolean planPlantActive() { return plantUntil > level().getGameTime(); }
    @Override public boolean planPickupActive() { return isPickupRequested() || isPickupEnabled(); }
    @Override public boolean planFighting() { LivingEntity t = getTarget(); return t != null && t.isAlive(); }
    @Override public boolean planPaused() { return isInjured(); }
    @Override public boolean planPlayerHold() { return isSitting() || moveMode() == MoveMode.STAY; }

    // ------------------------------------------------------------------ targeting (called by CompanionTargetGoal)

    public boolean isValidTarget(LivingEntity e, Player owner) {
        return e != this && e != owner && e.isAlive() && e instanceof Enemy && !(e instanceof CompanionEntity);
    }

    /** Resolves "attack this creeper" style orders. Accepts a mob name, entity id, or "nearest". */
    private LivingEntity findTarget(Player owner, String rawTarget) {
        if (owner == null) return null;
        String wanted = rawTarget == null ? "" : rawTarget.toLowerCase(Locale.ROOT).trim()
                .replace("the ", "").replace("a ", "").replace("an ", "").trim();
        if (wanted.isEmpty() || wanted.equals("nearest") || wanted.equals("closest") || wanted.equals("enemy"))
            return pickAttackTarget(owner);

        double radius = 40.0;
        List<LivingEntity> candidates = level().getEntitiesOfClass(LivingEntity.class,
                owner.getBoundingBox().inflate(radius), e -> e != this && e != owner && e.isAlive());
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            String id = EntityType.getKey(e.getType()).getPath();
            String typeName = e.getType().getDescription().getString().toLowerCase(Locale.ROOT);
            String customName = e.getName().getString().toLowerCase(Locale.ROOT);
            boolean match = wanted.equals(id) || wanted.equals(typeName)
                    || typeName.contains(wanted) || id.contains(wanted) || customName.contains(wanted);
            if (!match) continue;
            double d = owner.distanceToSqr(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Picks what to fight right now, or null. Order: explicit order > who hurt owner > what owner hit > who hurt me > nearby threats. */
    public LivingEntity chooseTarget() {
        Player owner = getOwner();
        if (isSitting() || stance() == Stance.PASSIVE || isInjured()) return null;
        if (forcedTarget != null && (!forcedTarget.isAlive() || tickCount > forcedTargetUntil)) forcedTarget = null;
        if (forcedTarget != null) return forcedTarget;
        // Combat chaining: right after a kill keep fighting instead of walking back to the owner first.
        boolean combatChain = tickCount - lastKillAt < 200;
        if (owner != null) {
            LivingEntity a = owner.getLastHurtByMob();
            if (a != null && owner.tickCount - owner.getLastHurtByMobTimestamp() < 200 && isValidTarget(a, owner) && visibleOrAggro(a, owner)) return a;
            LivingEntity b = owner.getLastHurtMob();
            // Explicit hit = order to help: no LOS/aggro requirement (creepers and other calm mobs never
            // retaliate, which made "I hit it, you help" silently fail), just a sane chase range.
            if (b != null && owner.tickCount - owner.getLastHurtMobTimestamp() < 200
                    && isValidTarget(b, owner) && owner.distanceToSqr(b) < 30 * 30) return b;
        }
        LivingEntity c = getLastHurtByMob();
        if (c != null && c.isAlive() && c != owner && !(c instanceof CompanionEntity) && !(c instanceof Player) && visibleOrAggro(c, owner)) return c;

        boolean mining = mineTask().running();
        // While a mining job is running, only an adjacent threat interrupts it (a far zombie must not stall the dig).
        double r = mining ? 4 : (combatChain ? 24 : (stance() == Stance.GUARD ? 16 : 6));
        var centre = combatChain ? this
                : ((owner != null && moveMode() == MoveMode.FOLLOW) ? owner : this);
        List<LivingEntity> near = level().getEntitiesOfClass(LivingEntity.class, centre.getBoundingBox().inflate(r),
                e -> isValidTarget(e, owner) && distanceToSqr(e) < (mining ? 8 * 8 : 28 * 28));
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : near) {
            Mob asMob = e instanceof Mob m ? m : null;
            // DEFENSIVE only reacts to mobs that are actually coming for us or the owner - unless we just
            // killed something and the next hostile is standing right there (chain the fight).
            if (stance() == Stance.DEFENSIVE && !combatChain && asMob != null && asMob.getTarget() != owner && asMob.getTarget() != this) continue;
            // No attacking through walls: a mob we cannot see only counts if it is already fighting the owner,
            // or (when aggressive) fighting the companion itself. Everything else must be visible. Closer wins.
            if (!getSensing().hasLineOfSight(e)) {
                boolean aggroOnOwner = owner != null && asMob != null && asMob.getTarget() == owner;
                boolean aggroOnSelf = asMob != null && asMob.getTarget() == this;
                if (!aggroOnOwner && !(aggroOnSelf && stance() == Stance.GUARD)) continue;
            }
            double d = distanceToSqr(e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** Wall-hack gate: a mob we cannot see only counts when it is already fighting the companion or the owner. */
    private boolean visibleOrAggro(LivingEntity e, Player owner) {
        if (getSensing().hasLineOfSight(e)) return true;
        if (e instanceof Mob m) return m.getTarget() == this || (owner != null && m.getTarget() == owner);
        return false;
    }

    /** True for the target the player explicitly ordered (kept through walls while it stays reachable). */
    public boolean isForced(LivingEntity e) { return e != null && e == forcedTarget; }

    /** Enemy the owner is looking at, else the enemy nearest to the owner. */
    private LivingEntity pickAttackTarget(Player owner) {
        List<LivingEntity> enemies = level().getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(24), e -> isValidTarget(e, owner));
        Vec3 eye = owner.getEyePosition(), look = owner.getLookAngle();
        LivingEntity best = null;
        double bestDot = 0.93;
        for (LivingEntity e : enemies) {
            double dot = look.dot(e.getEyePosition().subtract(eye).normalize());
            if (dot > bestDot) { bestDot = dot; best = e; }
        }
        if (best != null) return best;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : enemies) { double d = owner.distanceToSqr(e); if (d < bestD) { bestD = d; best = e; } }
        return best;
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && target != getTarget()) {
            targetSinceTick = tickCount;
            if (!level().isClientSide()) equipBestWeapon();
        }
        super.setTarget(target);
    }

    private void equipBestWeapon() {
        int sword = CompanionTools.bestSwordIndex(storage);
        if (sword >= 0) selectTool(sword);
    }

    /** Moves a storage tool into only its matching row slot, swapping safely if occupied. */
    private void selectTool(int sourceSlot) {
        if (sourceSlot < 0) return;
        ItemStack selected = storage.getItem(sourceSlot);
        int dedicated = CompanionTools.dedicatedSlot(selected);
        if (dedicated < 0) return;
        if (sourceSlot != dedicated) {
            ItemStack displaced = storage.getItem(dedicated);
            storage.setItem(dedicated, selected.copy());
            storage.setItem(sourceSlot, displaced.copy());
        }
        selectedToolSlot = dedicated - TOOL_START;
        storage.setChanged();
        refreshEquipment();
    }

    /**
     * Hostile mobs only smell players by default. The companion claims monsters that it can see and that have
     * no target yet. In GUARD mode it also STEALS monsters that already picked the owner when it stands closer
     * than the owner - "guarding" must mean the guard takes the hits, and that is exactly why the old version
     * (no-target claims only, every 2 s) let every aggro'd zombie walk straight past the companion to the player.
     * Their own retargeting still wins whenever a fight changes, and a downed/resting companion is left alone.
     */
    private void aggroMonsters() {
        if (isInjured() || isSitting() || stance() == Stance.PASSIVE) return;
        if (!(level() instanceof ServerLevel level)) return;
        Player owner = getOwner();
        boolean guard = stance() == Stance.GUARD || stance() == Stance.DEFENSIVE;
        int claimed = 0;
        for (Monster m : level.getEntitiesOfClass(Monster.class, getBoundingBox().inflate(16), e -> e.isAlive())) {
            LivingEntity cur = m.getTarget();
            boolean claim;
            if (cur == null) {
                claim = m.getSensing().hasLineOfSight(this);
            } else if (guard && owner != null && cur == owner) {
                claim = distanceToSqr(m) < owner.distanceToSqr(m);
            } else {
                claim = false;
            }
            if (!claim) continue;
            m.setTarget(this);
            if (++claimed >= 6) break;            // never pull a whole cave onto the companion in one pass
        }
    }

    /** Uses block tags plus actual destroy speed, then immediately syncs the held stack. */
    public void equipBestToolFor(net.minecraft.world.level.block.state.BlockState state) {
        int bestSlot = CompanionTools.bestToolIndex(storage, state);
        if (bestSlot < 0) {
            selectedToolSlot = -1;
            refreshEquipment();
            // Bare-hand mining is ~10x slower: nudge the player to put a tool in the bag (throttled).
            if (state.getDestroySpeed(level(), blockPosition()) > 1.0f && tickCount - lastNoToolNudge > 6000) {
                lastNoToolNudge = tickCount;
                String tool = state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE) ? "pickaxe"
                        : state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL) ? "shovel"
                        : state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE) ? "axe" : "tool";
                speak("Mining bare-handed is slow - give me a " + tool + " in my bag.");
            }
            return;
        }
        selectTool(bestSlot);
    }

    /** Places a torch from the bag at a free adjacent floor cell when it is too dark. */
    public boolean placeTorch() {
        if (!(level() instanceof ServerLevel level)) return false;
        if (level.getMaxLocalRawBrightness(blockPosition()) >= 9) return false;   // bright enough already
        int slot = -1;
        for (int i = MAIN_START; i < MAIN_START + MAIN_SIZE; i++) {
            if (storage.getItem(i).getItem() == net.minecraft.world.item.Items.TORCH) { slot = i; break; }
        }
        if (slot < 0) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos torchPos = blockPosition().offset(dx, 0, dz);
                BlockPos floor = torchPos.below();
                if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) continue;
                if (!level.getBlockState(torchPos).isAir() || !level.getBlockState(torchPos.above()).isAir()) continue;
                level.setBlock(torchPos, net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), 3);
                storage.removeItem(slot, 1);
                return true;
            }
        }
        return false;
    }

    /** "equip pickaxe" -> holds the best matching item: the dedicated tool row first, otherwise the general bag (moved into a free tool slot). Token matching, because STT output is fuzzy. */
    private void equipByName(String raw) {
        String want = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", " ").trim();
        List<String> tokens = new ArrayList<>();
        for (String w : want.split(" ")) {
            if (w.isBlank() || w.equals("my") || w.equals("the") || w.equals("a") || w.equals("an")
                    || w.equals("some") || w.equals("any")) continue;
            tokens.add(w);
        }
        boolean catchAll = tokens.isEmpty() || (tokens.size() == 1 && FAMILY_WORDS.contains(tokens.get(0)));
        int slot = pickEquippable(TOOL_START, 4, tokens, catchAll);
        // With universal slots a usable item may sit in general storage: bring it into a free tool slot first.
        if (slot < 0 && !catchAll) {
            int inBag = pickEquippable(MAIN_START, MAIN_SIZE, tokens, false);
            if (inBag >= 0) {
                for (int i = 0; i < 4; i++) {
                    int target = TOOL_START + i;
                    if (storage.getItem(target).isEmpty()) {
                        storage.setItem(target, storage.removeItem(inBag, 1));
                        storage.setChanged();
                        slot = target;
                        break;
                    }
                }
            }
        }
        if (slot >= 0) {
            selectedToolSlot = slot - TOOL_START;
            refreshEquipment();
            speak("Equipped the " + storage.getItem(slot).getHoverName().getString() + ".");
        } else {
            sayNoTool(want);
        }
    }

    /** Scores every candidate: an exact word match beats a substring match; synonyms keep fuzzy STT working. */
    private int pickEquippable(int from, int count, List<String> tokens, boolean catchAll) {
        int best = -1, bestScore = 0;
        for (int i = 0; i < count; i++) {
            ItemStack s = storage.getItem(from + i);
            if (s.isEmpty()) continue;
            if (catchAll) return from + i;                  // generic "equip": first non-empty slot
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            String name = s.getHoverName().getString().toLowerCase(Locale.ROOT);
            int score = 0;
            for (String t : tokens) {
                int sc = Math.max(idWordScore(id, t), nameWordScore(name, t));
                if (sc == 0) sc = synonymIn(id, t) ? 1 : 0;
                score = Math.max(score, sc);
            }
            if (score > bestScore) { bestScore = score; best = from + i; }
        }
        return bestScore > 0 ? best : -1;
    }

    /** 3 when the token is a whole word in the id ("axe" matches "iron_axe" but not "pickaxe"), 1 on a bare substring. */
    private static int idWordScore(String haystack, String token) {
        for (String w : haystack.split("[^a-z0-9]+")) if (w.equals(token)) return 3;
        return haystack.contains(token) ? 1 : 0;
    }

    /** Same for the displayed name; a name word match scores slightly under an id word match. */
    private static int nameWordScore(String haystack, String token) {
        for (String w : haystack.split("[^a-z0-9]+")) if (w.equals(token)) return 2;
        return haystack.contains(token) ? 1 : 0;
    }

    /** Family keywords: "axe" shouldn't grab a pickaxe, but "weapon" covers swords, axes and tridents. */
    private static boolean synonymIn(String id, String token) {
        if (token.startsWith("pick")) return id.contains("pickaxe");
        if (token.equals("shovel") || token.equals("spade")) return id.contains("shovel");
        if (token.equals("hatchet")) return id.contains("axe");
        if (token.equals("weapon") || token.equals("blade") || token.equals("arms"))
            return id.contains("sword") || id.contains("axe") || id.contains("trident") || id.contains("mace");
        if (token.equals("hoe") || token.equals("hoes")) return id.contains("hoe");
        return false;
    }

    private void sayNoTool(String want) {
        speak(want.isBlank() ? "My bag has no tools." : "I don't have " + want.replace('_', ' ') + " in my bag.");
    }

    // ------------------------------------------------------------------ block seeking helpers

    /** Converts "some wood", "diamonds", "oak planks"... into a block id (or family id), or "" when unknown. */
    public static String normalizeBlockId(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT).trim().replace(' ', '_').replace('-', '_');
        s = s.replaceAll("^(some|any|all|a|an|the|me|my|of|_)+", "");
        int ns = s.indexOf(':');
        if (ns >= 0) s = s.substring(ns + 1);                    // any namespace: a bare path resolves in every namespace
        s = BLOCK_ALIASES.getOrDefault(s, s);
        if (isBlock(s)) return s;
        if (s.endsWith("s") && s.length() > 3) {                 // plurals: stones -> stone, logs -> log
            String t = s.substring(0, s.length() - 1);
            if (isBlock(t) || FAMILY_IDS.contains(t)) return t;
        }
        return FAMILY_IDS.contains(s) ? s : "";
    }

    /** True for any registered block id - vanilla or modded. A bare path ("zanite_ore") is matched in EVERY
     *  namespace: Identifier.tryParse defaults an unqualified name to minecraft, which would miss aether's. */
    private static boolean isBlock(String id) {
        if (id == null || id.isEmpty()) return false;
        if (id.indexOf(':') >= 0) {
            Identifier loc = Identifier.tryParse(id);
            return loc != null && BuiltInRegistries.BLOCK.containsKey(loc);
        }
        Identifier vanilla = Identifier.tryParse("minecraft:" + id);
        if (vanilla != null && BuiltInRegistries.BLOCK.containsKey(vanilla)) return true;
        for (Identifier loc : BuiltInRegistries.BLOCK.keySet()) {
            if (loc.getPath().equals(id)) return true;
        }
        return false;
    }

    public static String prettyBlockId(String id) {
        if (id == null || id.isBlank()) return "that block";
        String s = id;
        int ns = s.indexOf(':');
        if (ns >= 0) s = s.substring(ns + 1);
        return switch (s) {
            case "log", "wood" -> "wood";
            case "planks", "plank" -> "planks";
            case "leaves" -> "leaves";
            case "wool" -> "wool";
            default -> {
                if (s.startsWith("deepslate_")) s = s.substring(10);
                yield s.replace('_', ' ');
            }
        };
    }

    // ------------------------------------------------------------------ movement helpers

    /** Puts the companion on a free, solid spot near {@code to}. Falls back to the exact owner position. */
    public void teleportNear(LivingEntity to) {
        Vec3 from = position();
        BlockPos base = to.blockPosition();
        for (int i = 0; i < 16; i++) {
            int dx = random.nextIntBetweenInclusive(-3, 3), dz = random.nextIntBetweenInclusive(-3, 3);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            BlockPos p = base.offset(dx, 0, dz);
            if (!level().getBlockState(p.below()).isFaceSturdy(level(), p.below(), Direction.UP)) continue;
            if (!level().noCollision(this, getBoundingBox().move(p.getX() + 0.5 - getX(), p.getY() - getY(), p.getZ() + 0.5 - getZ()))) continue;
            setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            afterTeleport(from);
            return;
        }
        setPos(to.getX(), to.getY(), to.getZ());
        afterTeleport(from);
    }

    /** Shared tail of a teleport: stop pathing, reset fall damage, and play the enderman poof at both ends. */
    private void afterTeleport(Vec3 from) {
        getNavigation().stop();
        resetFallDistance();
        if (!(level() instanceof ServerLevel server)) return;
        Vec3 to = position();
        server.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9f, 1.0f);
        server.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.9f, 1.2f);
        server.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 0.9, from.z, 24, 0.3, 0.6, 0.3, 0.15);
        server.sendParticles(ParticleTypes.PORTAL, to.x, to.y + 0.9, to.z, 24, 0.3, 0.6, 0.3, 0.15);
    }

    // ------------------------------------------------------------------ vanilla hooks

    @Override
    public void tick() {
        super.tick();
        // 26.1-CHECK: vanilla only advances the swing clock from Player.tick - LivingEntity.updateSwingTime has
        // no mob caller - so a mob's attackAnim stays 0 and HumanoidModel.setupAttackAnimation early-returns
        // (if attackTime <= 0): every swing() we broadcast arrived, but nothing ever played it. Players advance
        // the clock every tick; do the same here, BEFORE the client early-out (the client is what renders it).
        updateSwingTime();
        if (level().isClientSide()) return;
        if (!registryChecked) {
            registryChecked = true;
            if (!claimRegistry()) return;       // stale duplicate: it dropped its bag and removed itself
        }
        if (tickCount % 10 == 0) refreshTaskText();
        // Combat chaining bookkeeping: when our target dies, open a window in which chooseTarget keeps
        // picking the next nearby hostile (wider radius, no "must be attacking us" filter) instead of
        // wandering back to the owner between kills.
        if (lastTargetSeen != null) {
            if (!lastTargetSeen.isAlive()) lastKillAt = tickCount;
            lastTargetSeen = null;
        }
        LivingEntity nowTarget = getTarget();
        if (nowTarget != null && nowTarget.isAlive()) lastTargetSeen = nowTarget;
        if (isSitting()) {
            getNavigation().stop();
            if (tickCount % 40 == 0) heal(1.0f);               // resting heals
        }
        // Clear the synced subtitle after 6 seconds (the client animation itself lasts ~5 s).
        // Keep the text token alive for the whole TTS line. The client hides it from
        // actual playback progress; clearing it after six seconds truncated longer audio.
        if (!speech().isBlank() && tickCount - lastSpokenTick > 600) {
            entityData.set(DATA_SPEECH, "");
        }
        if (tickCount % 20 == 0) refreshEquipment();
        if (tickCount % 20 == 0) aggroMonsters();   // hostile mobs should see the companion as a target
        // Drop an automatic target we cannot reach (so mining etc. resumes); player-forced targets persist.
        LivingEntity tgt = getTarget();
        if (tgt != null && tgt != forcedTarget && tgt.isAlive() && tickCount - targetSinceTick > 200
                && getNavigation().isDone() && distanceToSqr(tgt) > 16 * 16) {
            setTarget(null);
        }
        if (isInjured()) {
            getNavigation().stop();
            setTarget(null);
            setPose(Pose.SWIMMING);                            // Mojang's crawl animation (swimming pose on land)
            if (tickCount % 10 == 0) makeMobsForgetMe();       // a downed body is not a target
            boolean reviving = isReviving();
            if (reviving) {
                reviveProgress = Math.min(1f, reviveProgress + 1f / REVIVE_TICKS);
                entityData.set(DATA_REVIVE, reviveProgress);
                if (reviveProgress >= 1f) { revive(); return; }
            } else {
                reviveProgress = Math.max(0f, reviveProgress - 1f / (REVIVE_TICKS * 2));
                entityData.set(DATA_REVIVE, reviveProgress);
                if (--injuredLeftTicks <= 0) { bleedOut(); return; }
                if (injuredLeftTicks == 200) speak("I'm fading... revive me!");
            }
            if (tickCount % 10 == 0) entityData.set(DATA_INJURED_LEFT, injuredLeftTicks);
        }
        if (!level().isClientSide() && planner.active()) planner.tick(this, tickCount);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { // 26.1-CHECK: hurt -> hurtServer since 1.21.2
        if (isInjured()) {
            // Downed bodies take no normal damage, but /kill and the void must still be able to finish them.
            if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
            return super.hurtServer(level, source, amount);
        }
        // Friendly fire enabled: the owner can hurt the companion just like another player can.
        // Lethal damage still uses the normal downed/revive flow (and a downed companion is invulnerable).
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) {
            playSound(net.minecraft.sounds.SoundEvents.GENERIC_HURT, 1.0f, 1.0f);
            if (tickCount - lastOuchTick > 60) {
                lastOuchTick = tickCount;
                speak(new String[]{"Ouch!", "Watch it!", "Hey, that hurt!", "Ow!", "Easy there!"}[random.nextInt(5)]);
            }
        }
        if (hurt && isSitting()) setMoveMode(MoveMode.STAY);   // stand up and defend yourself
        return hurt;
    }

    /** First lethal hit converts into the injured crawl instead of death. */
    @Override
    public void die(DamageSource source) {
        if (!forceDeath && !isInjured()) { injure(source); return; }
        if (!forceDeath) { dropStorageAndClear(); forceDeath = true; }   // killed while downed (/kill, void): the bag must not vanish
        if (level() instanceof ServerLevel sl && ownerUuid != null) CompanionRegistry.clear(sl.getServer(), ownerUuid, getUUID());
        super.die(source);
    }

    // Right-clicking the model is intentionally disabled: all orders come from the radial menu or voice.

    @Override
    public boolean removeWhenFarAway(double distSq) { return false; }

    // ------------------------------------------------------------------ task text, plants

    private void refreshTaskText() {
        long now = level().getGameTime();
        // "Task assigned but the seek goal is dormant" is its own stall class (sitting / injured / a locked target).
        if (mineTask.exists() && !mineTask.paused && !seekGoal.selected() && tickCount - lastGoalInactiveLog > 600) {
            lastGoalInactiveLog = tickCount;
            com.mojang.logging.LogUtils.getLogger().warn("[companionmod] mining task active but the seek goal is NOT selected (sitting? injured? target locked? paused?)");
        }
        String s = "";
        if (mineTask.phase == MineTask.Phase.ASCEND) s = "Digging up";
        else if (mineTask.exists()) {
            String p = prettyBlockId(mineTask.blockId).replace(" ore", "");
            s = Character.toUpperCase(p.charAt(0)) + p.substring(1) + " " + mineTask.mined + "/" + mineTask.want;
            if (mineTask.paused) s += " || say 'continue'";
            else {
                String st = seekGoal.stateName();
                if (st != null && !st.isBlank()) s += " - " + st;
            }
        } else if (plantUntil > now) s = "Planting";
        else if (farmUntil > now) s = "Farming";
        else if (fishUntil > now) s = "Fishing";
        else if (!buildSchematic.isBlank()) s = "Building";
        if (!s.equals(entityData.get(DATA_TASK))) entityData.set(DATA_TASK, s);
    }

    private static boolean isPlantId(String id) {
        if (id.endsWith("_sapling") || id.endsWith("_propagule")) return true;
        Identifier loc = Identifier.tryParse(id);
        if (loc == null) return false;
        return BuiltInRegistries.BLOCK.getOptional(loc).map(b -> {
            BlockState st = b.defaultBlockState();
            return st.is(net.minecraft.tags.BlockTags.SAPLINGS) || st.is(net.minecraft.tags.BlockTags.FLOWERS)
                    || st.is(net.minecraft.tags.BlockTags.CROPS);
        }).orElse(false);
    }

    // ------------------------------------------------------------------ identity, registry, downed handling

    /** Owner UUID from the synced data: valid on BOTH sides (the {@code ownerUuid} field is server-only). */
    public UUID syncedOwner() {
        String s = entityData.get(DATA_OWNER);
        if (s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static String sanitizeName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\p{Cntrl}", "").trim();
        return s.length() > 32 ? s.substring(0, 32).trim() : s;
    }

    /**
     * One companion per owner: the world's registry says which UUID is the real one. A copy that was left in an
     * unloaded chunk when the shrine made a new companion drops its bag and removes itself the moment it loads.
     * @return false when this entity just removed itself
     */
    private boolean claimRegistry() {
        if (ownerUuid == null || !(level() instanceof ServerLevel sl)) return true;
        UUID registered = CompanionRegistry.get(sl.getServer(), ownerUuid);
        if (registered == null) { CompanionRegistry.set(sl.getServer(), ownerUuid, getUUID()); return true; }
        if (registered.equals(getUUID())) return true;
        dropStorageAndClear();
        discard();
        return false;
    }

    /** Mobs that were already chasing the body (or remember it as their attacker) let go of it. */
    private void makeMobsForgetMe() {
        if (!(level() instanceof ServerLevel sl)) return;
        for (Mob m : sl.getEntitiesOfClass(Mob.class, getBoundingBox().inflate(32), e -> e != this)) {
            if (m.getTarget() == this) m.setTarget(null);
            if (m.getLastHurtByMob() == this) m.setLastHurtByMob(null);
            // hasMemoryValue is null-safe; getMemory() itself throws for brains without ATTACK_TARGET
            // (cows, chickens, most passive mobs), which crashed the server when the companion went down.
            if (m.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                m.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).ifPresent(t -> {
                    if (t == this) m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                });
            }
        }
    }

    /** Drops the whole bag and the mirrored gear on the ground, and empties them so nothing can be dropped twice. */
    private void dropStorageAndClear() {
        Containers.dropContents(level(), this, storage);
        storage.clearContent();
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        for (EquipmentSlot slot : ARMOR_SLOTS) setItemSlot(slot, ItemStack.EMPTY);
    }

    /** Used when the shrine has to recreate the companion (other dimension): nothing the player gave it is lost. */
    public void copyStateFrom(CompanionEntity other) {
        for (int i = 0; i < SIZE; i++) storage.setItem(i, other.storage.getItem(i).copy());
        if (other.hasCustomName()) setCustomName(other.getCustomName());
        setSkin(other.skin());
        setFeminine(other.isFeminine());
        setStance(other.stance());
        refreshEquipment();
    }

    /** A downed companion is not a valid target for any mob. */
    @Override
    public boolean canBeSeenAsEnemy() { return !isInjured() && super.canBeSeenAsEnemy(); }

    @Override
    public boolean canBeSeenByAnyone() { return !isInjured() && super.canBeSeenByAnyone(); }
}
```

## common\src\main\java\com\example\companionmod\entity\CompanionProtection.java

```java
package com.example.companionmod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * One place that decides whether the companion may break or place a block. It acts on behalf of its owner, so it
 * respects vanilla spawn protection and the world border. Mods that protect land through player events
 * (claim mods) can only be honoured fully with a fake player; this is the loader-neutral baseline.
 */
public final class CompanionProtection {
    private CompanionProtection() {}

    public static boolean canModify(ServerLevel level, CompanionEntity mob, BlockPos pos) {
        Player owner = mob.getOwner();
        if (owner == null) return false;
        return level.mayInteract(owner, pos);   // 26.1-CHECK: parameter type is Player or Entity; a Player fits both
    }
}
```

## common\src\main\java\com\example\companionmod\entity\CompanionTools.java

```java
package com.example.companionmod.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** The single source of truth for the four dedicated equipment slots. */
public final class CompanionTools {
    public enum Kind {
        PICKAXE(0, "Pickaxe", "_pickaxe"),
        AXE(1, "Axe", "_axe"),
        SHOVEL(2, "Shovel", "_shovel"),
        SWORD(3, "Sword", "_sword");

        public final int slotOffset;
        public final String label;
        private final String suffix;

        Kind(int slotOffset, String label, String suffix) {
            this.slotOffset = slotOffset;
            this.label = label;
            this.suffix = suffix;
        }
    }

    private CompanionTools() {}

    public static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static Kind kindOf(ItemStack stack) {
        String path = itemId(stack);
        for (Kind kind : Kind.values()) {
            if (path.endsWith(kind.suffix)) return kind;
        }
        return null;
    }

    public static boolean isDedicatedTool(ItemStack stack) {
        return kindOf(stack) != null;
    }

    public static int dedicatedSlot(ItemStack stack) {
        Kind kind = kindOf(stack);
        return kind == null ? -1 : CompanionEntity.TOOL_START + kind.slotOffset;
    }

    public static Kind preferredKind(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Kind.PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return Kind.AXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Kind.SHOVEL;
        return null;
    }

    /** Searches the tool row and storage, so overflow tools are still usable. */
    public static int bestToolIndex(Container inventory, BlockState state) {
        Kind preferred = preferredKind(state);
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            Kind kind = kindOf(stack);
            if (kind == null) continue;
            float speed = Math.max(1.0F, stack.getDestroySpeed(state));
            double score = speed * (kind == preferred ? 1000.0 : 1.0);
            if (i >= CompanionEntity.TOOL_START && i < CompanionEntity.MAIN_START) score += 0.01;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** First melee-worthy item: any sword anywhere, or anything the player parked in the tool row (slots are universal). */
    public static int bestSwordIndex(Container inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (kindOf(inventory.getItem(i)) == Kind.SWORD) return i;
        }
        for (int i = CompanionEntity.TOOL_START; i < CompanionEntity.MAIN_START; i++) {
            if (!inventory.getItem(i).isEmpty()) return i;
        }
        return -1;
    }
}
```

## common\src\main\java\com\example\companionmod\entity\plan\CompanionPlanner.java

```java
package com.example.companionmod.entity.plan;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.voice.PlanParser;
import com.example.companionmod.voice.PlanStep;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Set;

/**
 * Runs an AI-made plan one step at a time by issuing ordinary companion orders, so every behaviour (mining, pickup,
 * combat, follow) keeps its existing safety rules. The server re-validates every plan; the model never executes anything itself.
 *
 * <p>Player orders do not throw the plan away any more: they {@link #pause(long) freeze} it in place and
 * {@code continue} {@link #resume(long) unfreezes} it, deadlines shifted forward by the pause so nothing expires
 * while it was interrupted.</p>
 */
public final class CompanionPlanner {
    private static final Set<String> RETURN_AFTER = Set.of("wander", "patrol", "seek_block", "pickup", "attack", "guard", "plant");

    private final ArrayDeque<PlanStep> queue = new ArrayDeque<>();
    private PlanStep current;
    private long startTick, endTick, lastPlayerOrder = -100000L;
    private String goal = "";
    private String lastOp = "";
    private boolean paused;
    private long pausedAt;

    public boolean active() { return current != null || !queue.isEmpty(); }
    public boolean paused() { return paused; }
    public String goal() { return goal; }

    /** A work goal made real progress: a timed seek_block / plant step gets at least {@code ticks} more, so it is never cut off mid-job. */
    public void extendDeadline(long fromTick, long ticks) {
        if (current != null && ("seek_block".equals(current.op()) || "plant".equals(current.op()))) {
            endTick = Math.max(endTick, fromTick + ticks);
        }
    }

    public void cancel() { queue.clear(); current = null; goal = ""; paused = false; }

    /** Freezes the plan in place; resume() continues it as if nothing had happened. */
    public void pause(long tick) {
        if (active() && !paused) { paused = true; pausedAt = tick; }
    }

    /** Unfreezes a paused plan, shifting its deadlines past the pause. Returns true when there was a plan to resume. */
    public boolean resume(long tick) {
        if (!paused) return false;
        paused = false;
        long dt = Math.max(0L, tick - pausedAt);
        startTick += dt;
        endTick += dt;
        return active();
    }

    /** Call whenever the player gives a manual order: it always wins and blocks autonomy for 60 s (the plan pauses, not dies). */
    public void notePlayerOrder(long tick) { lastPlayerOrder = tick; pause(tick); }

    /** Accepts a plan JSON from the client. Returns true if a plan was started. An empty non-auto plan cancels the current one. */
    public boolean accept(String json, PlanActions host, long tick) {
        boolean auto = PlanParser.isAuto(json);
        if (auto && (active() || host.planPlayerHold() || tick - lastPlayerOrder < 1200)) return false;
        PlanParser.Plan plan = PlanParser.parse(json, auto);
        if (plan.isEmpty()) { if (!auto) cancel(); return false; }
        cancel();
        queue.addAll(plan.steps());
        goal = plan.goal();
        return true;
    }

    public void tick(PlanActions host, long tick) {
        if (paused || !active()) return;
        Player owner = host.planOwner();
        if (owner == null || !owner.isAlive()) { cancel(); return; }
        if (host.planPaused()) { endTick++; return; }
        if (current == null) { current = queue.poll(); begin(host, tick); return; }
        if (!finished(host, tick)) return;
        current = null;
        if (queue.isEmpty()) {
            if (RETURN_AFTER.contains(lastOp) && !host.planPlayerHold()) host.planCommand(CompanionCommand.FOLLOW, "");
            goal = "";
        }
    }

    private void begin(PlanActions host, long tick) {
        startTick = tick;
        endTick = tick + current.seconds() * 20L;
        lastOp = current.op();
        switch (current.op()) {
            case "say" -> host.planSay(current.arg());
            case "wait" -> { }
            case "seek_block" -> {
                String id = CompanionEntity.normalizeBlockId(current.arg());
                // A small model answers "get wood" with a concrete tree block most of the time; a LEAVES
                // target under a wood/log/timber goal is the "he collects leaves, not logs" bug: the goal
                // text wins ('get 20 wood' -> the whole log family, never leaves).
                if ((id.endsWith("_leaves") || id.equals("leaves")) && mentionsWood(goal)) {
                    id = "log";
                } else if (id.isBlank() && mentionsWood(goal)) {
                    id = "log";
                }
                if (id.isBlank()) endTick = tick; else host.planCommand(CompanionCommand.SEEK_BLOCK, id);
            }
            case "pickup" -> host.planCommand(CompanionCommand.PICKUP, current.arg());
            default -> host.planCommand(CompanionCommand.parse(current.op()), current.arg());
        }
    }

    private boolean finished(PlanActions host, long tick) {
        long dt = tick - startTick;
        return switch (current.op()) {
            case "say" -> dt >= 30;                                   // small pause so lines never flood chat
            case "seek_block" -> tick >= endTick || (dt > 40 && !host.planSeekActive());
            case "pickup" -> tick >= endTick || (dt > 20 && !host.planPickupActive());
            case "plant" -> tick >= endTick || (dt > 40 && !host.planPlantActive());
            case "attack" -> tick >= endTick || (dt > 60 && !host.planFighting());
            default -> tick >= endTick;
        };
    }

    /** Whole-word wood words in the plan's goal text ("get 20 wood", "chop some logs", "collect timber"). */
    private static boolean mentionsWood(String goal) {
        String g = goal == null ? "" : goal.toLowerCase(java.util.Locale.ROOT);
        for (String w : new String[]{"wood", "log", "timber", "lumber", "tree", "trunk"}) {
            if (java.util.regex.Pattern.compile("(?<![a-z])" + java.util.regex.Pattern.quote(w) + "(?![a-z])").matcher(g).find()) return true;
        }
        return false;
    }
}
```

## common\src\main\java\com\example\companionmod\entity\plan\PlanActions.java

```java
package com.example.companionmod.entity.plan;

import com.example.companionmod.command.CompanionCommand;
import net.minecraft.world.entity.player.Player;

/** What the planner needs from the companion. CompanionEntity implements this by delegating to its existing methods. */
public interface PlanActions {
    Player planOwner();
    /** Same effect as the player giving this order (arg = block id for SEEK_BLOCK, item filter for PICKUP, else ""). */
    void planCommand(CompanionCommand cmd, String arg);
    void planSay(String text);
    boolean planSeekActive();
    boolean planPickupActive();
    default boolean planPlantActive() { return false; }
    boolean planFighting();
    /** Freeze the plan while true (e.g. injured and recovering). */
    default boolean planPaused() { return false; }
    /** True when the player told it to stay/sit: autonomous plans must not override that. */
    default boolean planPlayerHold() { return false; }
}
```

## common\src\main\java\com\example\companionmod\entity\task\MineTask.java

```java
package com.example.companionmod.entity.task;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The companion's current "go find/mine X" job. It lives on the entity and is saved with it, so progress survives
 * fights, sit/stay orders, injuries, chunk unloads and world reloads. The goal only reads and updates this object;
 * stopping and restarting the goal never loses anything. A new order pauses the task, "continue" resumes it.
 */
public final class MineTask {
    public enum Phase { SEARCH, DESCEND, BRANCH, ASCEND }
    public static final int NO_DESCEND = Integer.MIN_VALUE;

    public String blockId = "";
    public int want, mined, ty = NO_DESCEND, dirData, leg, legLen;
    public Phase phase = Phase.SEARCH;
    public boolean paused;
    /**
     * Bumped by every begin()/beginSurface(): the seek goal compares it each tick and drops its stale transient
     * state (walk, break, pillar) the same tick a new order arrives - without it the goal kept finishing the
     * previous task's walk for up to 8 s after a new order, which looked like a freeze. Transient on purpose.
     */
    public int serial;

    public void begin(String id, boolean nether, int facingDirData) {
        blockId = id;
        want = defaultWant(id);
        mined = 0;
        ty = targetY(id, nether);
        phase = Phase.SEARCH;
        dirData = facingDirData;
        leg = 0;
        legLen = 0;
        paused = false;
        serial++;
    }

    /** "Dig up to the surface": a staircase job with no block id, finished by reaching daylight. */
    public void beginSurface(int facingDirData) {
        blockId = "";
        want = 1;
        mined = 0;
        ty = NO_DESCEND;
        phase = Phase.ASCEND;
        dirData = facingDirData;
        leg = 0;
        legLen = 0;
        paused = false;
        serial++;
    }

    public boolean exists() { return !blockId.isEmpty() || phase == Phase.ASCEND; }
    public boolean running() { return exists() && !paused; }
    public boolean deep() { return ty != NO_DESCEND; }
    public void pause() { paused = true; }
    public boolean resume() { if (!exists()) return false; paused = false; return true; }
    public void finish() { blockId = ""; paused = false; mined = 0; phase = Phase.SEARCH; }
    public String describe() { return "mining " + blockId.replace('_', ' ') + " (" + mined + "/" + want + ")"; }

    /** Best Y level to branch-mine for. NO_DESCEND = surface target (logs, stone, emeralds...). Tweak freely. */
    public static int targetY(String id, boolean nether) {
        if (nether) return id.contains("ancient_debris") ? 15 : NO_DESCEND;
        if (id.contains("diamond") || id.contains("redstone")) return -58;
        if (id.contains("lapis")) return 0;
        if (id.contains("gold")) return -16;
        if (id.contains("iron")) return 16;
        if (id.contains("copper")) return 40;
        if (id.contains("coal")) return 45;
        if (id.contains("deepslate")) return -20;
        return NO_DESCEND;
    }

    public static int defaultWant(String id) {
        if (id.contains("diamond") || id.contains("emerald")) return 6;
        if (id.contains("ancient_debris")) return 4;
        return 16;
    }

    public void save(ValueOutput out) {
        if (!exists()) return;
        out.putString("MineBlock", blockId);
        out.putInt("MineWant", want);
        out.putInt("MineDone", mined);
        out.putInt("MineY", ty);
        out.putInt("MinePhase", phase.ordinal());
        out.putInt("MineDir", dirData);
        out.putInt("MineLeg", leg);
        out.putInt("MineLegLen", legLen);
        out.putBoolean("MinePaused", paused);
    }

    public void load(ValueInput in) {
        blockId = in.getStringOr("MineBlock", "");
        want = in.getIntOr("MineWant", 0);
        mined = in.getIntOr("MineDone", 0);
        ty = in.getIntOr("MineY", NO_DESCEND);
        phase = Phase.values()[Math.floorMod(in.getIntOr("MinePhase", 0), Phase.values().length)];
        dirData = in.getIntOr("MineDir", 0);
        leg = in.getIntOr("MineLeg", 0);
        legLen = in.getIntOr("MineLegLen", 0);
        paused = in.getBooleanOr("MinePaused", false);
    }
}
```

## common\src\main\java\com\example\companionmod\inventory\CompanionInventoryMenu.java

```java
package com.example.companionmod.inventory;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.entity.CompanionTools;
import com.example.companionmod.registry.ModContent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * The companion's 26-slot bag: 4 armor slots, 4 tool slots and 18 storage slots, plus the player's
 * inventory. The server menu wraps the companion's live container; the client menu (created from the
 * {@link MenuType} supplier) uses a synced shadow container. Armor/tool row changes are mirrored onto
 * the companion's equipment slots by {@link CompanionEntity#refreshEquipment()}.
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {
    public static final int SIZE = CompanionEntity.SIZE;          // 26
    private static final int PLAYER_SLOTS = 36;

    private final Container storage;
    private final CompanionEntity companion;   // null on the client

    /** Server side: opens the companion's live container. */
    public CompanionInventoryMenu(int id, Inventory playerInventory, CompanionEntity companion) {
        this(id, playerInventory, companion.storage(), companion);
    }

    /** Client side (called from the MenuType supplier): shadow container filled by slot sync. */
    public CompanionInventoryMenu(int id, Inventory playerInventory) {
        this(id, playerInventory, new SimpleContainer(SIZE), null);
    }

    private CompanionInventoryMenu(int id, Inventory playerInventory, Container storage, CompanionEntity companion) {
        super(ModContent.COMPANION_INV_MENU, id);
        this.storage = storage;
        this.companion = companion;

        // Row 1 (y 28): armor [0..3] + gap + tools [4..7]
        for (int i = 0; i < 4; i++) {
            addSlot(new ArmorSlot(storage, i, 8 + i * 18, 28, CompanionEntity.ARMOR_SLOTS[i]));
        }
        for (int i = 0; i < 4; i++) {
            addSlot(new ToolSlot(storage, CompanionEntity.TOOL_START + i, 98 + i * 18, 28));
        }
        // Rows 2-3 (y 55, 73): main storage [8..25]
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(storage, CompanionEntity.MAIN_START + row * 9 + col, 8 + col * 18, 55 + row * 18));
            }
        }
        // Player inventory + hotbar
        addStandardInventorySlots(playerInventory, 8, 112);
    }

    @Override
    public boolean stillValid(Player player) {
        return companion == null
                || (companion.isAlive() && player.distanceToSqr(companion) <= 64.0);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (companion != null && !companion.level().isClientSide()) companion.refreshEquipment();
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == storage && companion != null && !companion.level().isClientSide()) {
            companion.refreshEquipment();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack rest = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return rest;
        ItemStack stack = slot.getItem();
        rest = stack.copy();
        if (index < SIZE) {
            // companion storage -> player
            if (!moveItemStackTo(stack, SIZE, SIZE + PLAYER_SLOTS, true)) return ItemStack.EMPTY;
        } else {
            // player -> companion storage (armor row first, then tools, then storage)
            EquipmentSlot equipSlot = armorSlotFor(stack);
            if (equipSlot != null) {
                int target = CompanionEntity.ARMOR_START + armorIndex(equipSlot);
                if (!moveItemStackTo(stack, target, target + 1, false)
                        && !moveItemStackTo(stack, CompanionEntity.MAIN_START, CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (CompanionTools.isDedicatedTool(stack)) {
                int target = CompanionTools.dedicatedSlot(stack);
                if (!moveItemStackTo(stack, target, target + 1, false)
                        && !moveItemStackTo(stack, CompanionEntity.MAIN_START, CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, CompanionEntity.MAIN_START, CompanionEntity.MAIN_START + CompanionEntity.MAIN_SIZE, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return rest;
    }

    private static EquipmentSlot armorSlotFor(ItemStack stack) {
        Equippable e = stack.get(DataComponents.EQUIPPABLE);
        if (e == null) return null;
        return switch (e.slot()) {
            case HEAD, CHEST, LEGS, FEET -> e.slot();
            default -> null;
        };
    }

    private static int armorIndex(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> 0;
            case CHEST -> 1;
            case LEGS -> 2;
            case FEET -> 3;
            default -> -1;
        };
    }

    /** Armor row slots only accept the piece that belongs in that slot. */
    private static class ArmorSlot extends Slot {
        private final EquipmentSlot slot;

        ArmorSlot(Container container, int index, int x, int y, EquipmentSlot slot) {
            super(container, index, x, y);
            this.slot = slot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            Equippable e = stack.get(DataComponents.EQUIPPABLE);
            return e != null && e.slot() == slot;
        }
    }

    /** Any tool slot accepts any item: a "weapon" can be an axe, a pickaxe, whatever the player wants there. */
    private static class ToolSlot extends Slot {
        ToolSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.isEmpty();          // dedicated tools still auto-route here on shift-click; anything may sit in any slot
        }
    }
}
```

## common\src\main\java\com\example\companionmod\net\CommandHandler.java

```java
package com.example.companionmod.net;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.registry.ModContent;
import com.example.companionmod.voice.ReplyParser;
import com.example.companionmod.world.CompanionRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side entry point for voice commands. The client is untrusted: validate, rate-limit, only touch the sender's own companion. */
public final class CommandHandler {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    /**
     * Token bucket instead of a single 400 ms gap: the client legitimately sends bursts such as
     * "set_skin" + "feminine" in one tick, and the old single-gap limiter silently dropped the second
     * packet — which is why the feminine model never appeared.
     */
    private static final Map<UUID, Bucket> BUCKETS = new ConcurrentHashMap<>();
    private static final double CAPACITY = 8.0;
    private static final double REFILL_PER_SECOND = 6.0;

    private static final class Bucket {
        double tokens = CAPACITY;
        long lastRefill = System.currentTimeMillis();
    }

    private CommandHandler() {}

    public static void handle(ServerPlayer player, CommandPayload payload) {
        CompanionCommand cmd = CompanionCommand.parse(payload.action());
        LOG.info("[companionmod] server cmd: action='{}' -> {} target='{}' from {}",
                payload.action(), cmd, payload.target(), player.getName().getString());

        // STOP is an emergency control, not optional background work. Never lose it to the token bucket.
        if (cmd != CompanionCommand.STOP) {
            Bucket bucket = BUCKETS.computeIfAbsent(player.getUUID(), id -> new Bucket());
            long now = System.currentTimeMillis();
            synchronized (bucket) {
                bucket.tokens = Math.min(CAPACITY, bucket.tokens + (now - bucket.lastRefill) / 1000.0 * REFILL_PER_SECOND);
                bucket.lastRefill = now;
                if (bucket.tokens < 1.0) return;
                bucket.tokens -= 1.0;
            }
        }
        String say = ReplyParser.clean(payload.say());
        String target = cmd == CompanionCommand.PLAN ? payload.target() : ReplyParser.clean(payload.target());

        CompanionEntity companion = ModContent.findCompanion(player.level().getServer(), player.getUUID());
        if (companion == null) {
            boolean exists = CompanionRegistry.get(player.level().getServer(), player.getUUID()) != null;
            player.sendSystemMessage(Component.literal(exists
                    ? "Your companion is too far away (its chunk is not loaded). Activate a Companion Shrine to call it."
                    : "You have no companion. Activate a Companion Shrine to summon one."));
            return;
        }
        companion.applyCommand(cmd, player, target);
        if (!say.isBlank()) companion.speak(say);
    }

    public static void forget(UUID player) { BUCKETS.remove(player); }

    public static void forgetAll() { BUCKETS.clear(); }
}
```

## common\src\main\java\com\example\companionmod\net\CommandPayload.java

```java
package com.example.companionmod.net;

import com.example.companionmod.CompanionMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: "the player told their companion to do {@code action}, it answered {@code say},
 * and the order optionally names a {@code target} entity (e.g. "creeper")."
 */
public record CommandPayload(String action, String say, String target) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CommandPayload> TYPE = new CustomPacketPayload.Type<>(CompanionMod.id("command"));
    public static final StreamCodec<ByteBuf, CommandPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), CommandPayload::action,
            ByteBufCodecs.stringUtf8(512), CommandPayload::say,
            ByteBufCodecs.stringUtf8(1024), CommandPayload::target,
            CommandPayload::new);

    public CommandPayload(String action, String say) {
        this(action, say, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
```

## common\src\main\java\com\example\companionmod\registry\ModContent.java

```java
package com.example.companionmod.registry;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.block.CompanionShrineBlock;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.inventory.CompanionInventoryMenu;
import com.example.companionmod.world.CompanionRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.UUID;

/**
 * All game objects, created once. The loaders only register them (Fabric: Registry.register in order block -> item -> entity -> menu;
 * NeoForge: inside RegisterEvent per registry). Nothing in here touches a loader API.
 */
public final class ModContent {
    private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, CompanionMod.id("shrine_core"));
    private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, CompanionMod.id("shrine_core"));
    private static final ResourceKey<EntityType<?>> ENTITY_KEY = ResourceKey.create(Registries.ENTITY_TYPE, CompanionMod.id("companion"));

    /** One block, 3D model (assets/.../models/block/shrine_core.json). Unbreakable in survival: the shrine is the mod's anchor. */
    public static final Block SHRINE_CORE = new CompanionShrineBlock(
            BlockBehaviour.Properties.of().setId(BLOCK_KEY).mapColor(MapColor.COLOR_CYAN)
                    .strength(-1.0f, 3600000.0f).noOcclusion().sound(SoundType.AMETHYST).lightLevel(s -> 12)); // 26.1-CHECK: setId is mandatory since 1.21.2

    public static final Item SHRINE_CORE_ITEM = new BlockItem(SHRINE_CORE,
            new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix());

    public static final EntityType<CompanionEntity> COMPANION = EntityType.Builder
            .of(CompanionEntity::new, MobCategory.MISC)
            .sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10)
            .build(ENTITY_KEY);

    private static final ResourceKey<MenuType<?>> MENU_KEY = ResourceKey.create(Registries.MENU, CompanionMod.id("companion_inventory"));
    /** The supplier is the client-side factory (shadow container); the server creates menus with the entity directly. */
    public static final MenuType<CompanionInventoryMenu> COMPANION_INV_MENU = new MenuType<>(
            CompanionInventoryMenu::new, FeatureFlags.VANILLA_SET);

    private ModContent() {}

    public static net.minecraft.resources.Identifier blockId() { return BLOCK_KEY.identifier(); }   // 26.1-CHECK: ResourceKey.location() -> identifier()
    public static net.minecraft.resources.Identifier itemId() { return ITEM_KEY.identifier(); }
    public static net.minecraft.resources.Identifier entityId() { return ENTITY_KEY.identifier(); }
    public static net.minecraft.resources.Identifier menuId() { return MENU_KEY.identifier(); }

    /**
     * Finds the player's loaded companion. The world registry gives its UUID, so this is an O(1) lookup per level
     * instead of scanning every entity. Worlds without a registry entry yet fall back to one scan and adopt the result.
     * Returns null when there is none, or when the registered one sits in an unloaded chunk.
     */
    public static CompanionEntity findCompanion(MinecraftServer server, UUID owner) {
        if (server == null || owner == null) return null;
        UUID known = CompanionRegistry.get(server, owner);
        if (known != null) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(known) instanceof CompanionEntity c && c.isAlive()) return c;
            }
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) { // 26.1-CHECK: ServerLevel.getAllEntities()
                if (e instanceof CompanionEntity c && c.isAlive() && owner.equals(c.getOwnerUuid())) {
                    CompanionRegistry.set(server, owner, c.getUUID());
                    return c;
                }
            }
        }
        return null;
    }
}
```

## common\src\main\java\com\example\companionmod\voice\AudioDevices.java

```java
package com.example.companionmod.voice;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the Java Sound input/output devices (mixers) by name and resolves a stored name back to a
 * {@code Mixer.Info}. Names are the stable-ish human identifier ("Microphone Array (Realtek...)", ...);
 * an empty name means the system default, which is always available.
 */
public final class AudioDevices {
    private AudioDevices() {}

    /** Names of every mixer that can record (has a TargetDataLine). */
    public static List<String> inputs() {
        List<String> out = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(new Line.Info(TargetDataLine.class))) out.add(info.getName());
        }
        return out;
    }

    /** Names of every mixer that can play (has a SourceDataLine). */
    public static List<String> outputs() {
        List<String> out = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(new Line.Info(SourceDataLine.class))) out.add(info.getName());
        }
        return out;
    }

    /** The mixer with exactly this name, or null when it was unplugged / renamed / never existed. */
    public static Mixer.Info find(String name) {
        if (name == null || name.isBlank()) return null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) if (name.equals(info.getName())) return info;
        return null;
    }
}
```

## common\src\main\java\com\example\companionmod\voice\AudioRecorder.java

```java
package com.example.companionmod.voice;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal microphone recorder using javax.sound.sampled.
 * Note: In Minecraft, audio device access may require client-side permissions.
 */
public class AudioRecorder {
    private TargetDataLine line;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private Thread thread;

    public void start() {
        try {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            running.set(true);
            buffer.reset();
            thread = new Thread(() -> {
                byte[] chunk = new byte[1024];
                while (running.get()) {
                    int read = line.read(chunk, 0, chunk.length);
                    if (read > 0) buffer.write(chunk, 0, read);
                }
            });
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public byte[] stopAndGetWav() {
        running.set(false);
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) {}
        }
        if (line != null) {
            line.stop();
            line.close();
        }
        // Wrap raw PCM in a WAV container.
        byte[] pcm = buffer.toByteArray();
        try {
            AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
            ByteArrayInputStream bais = new ByteArrayInputStream(pcm);
            AudioInputStream ais = new AudioInputStream(bais, format, pcm.length / format.getFrameSize());
            ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
            return wavOut.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return new byte[0];
        }
    }
}
```

## common\src\main\java\com\example\companionmod\voice\FemaleGenderCompat.java

```java
package com.example.companionmod.voice;

import com.example.companionmod.client.CompanionRenderState;
import com.example.companionmod.entity.CompanionEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.LivingEntity;

import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Soft integration with Wildfire's Female Gender Mod 5.x
 * (<a href="https://github.com/FemaleGenderMod/FemaleGenderMod">GitHub</a>, mod id {@code female_gender_mod}).
 *
 * <p>Version 5 made {@code com.wildfire.client.render.GenderLayer} generic over
 * {@code HumanoidRenderState}/{@code HumanoidModel}, so it <b>can</b> be attached to the companion's renderer
 * (the old player-typed layer could not). The layer reads its data from the render state through
 * {@code ClientHelper.getRenderState(state)}; the mod only fills that for players / mannequins / armor stands,
 * so we fill it ourselves - from a <b>companion-owned</b> Wildfire config holder whose gender is always
 * FEMALE (set once, never touching disk/cloud). The owner's own gender therefore does NOT matter: a male
 * player still gets the companion's female chest. The result is the mod's real chest model, UV layouts,
 * physics, jacket overlay and textures - not our own approximation. We tick the holder's breast physics
 * ourselves each client tick because the mod only ticks players/mannequins/armor stands.</p>
 *
 * <p>Everything is reflective (no compile-time dependency, no crash when the mod is absent or on a loader where
 * the render-data setter is unavailable). The settings screen hides the feminine switch without the mod.</p>
 */
public final class FemaleGenderCompat {
    private static Boolean present;
    private static boolean layerAttached;
    private static String lastOutcome;             // diagnostics: log only when the outcome changes

    private static final Logger LOG = LogUtils.getLogger();
    private static boolean initDone;
    private static int initAttempts;
    private static boolean populateFailureLogged;
    private static Method holderGender;                 // EntityConfigHolder.gender()
    private static Method valueGet;                     // ConfigValue.get()
    private static Method genderCanHaveBreasts;         // Gender.canHaveBreasts()
    private static Constructor<?> genderRenderStateCtor;// GenderRenderState(holder, entity, state, partialTick)
    private static Constructor<?> holderCtor;           // new PlayerConfigHolder(UUID) - companion's own config
    private static Method genderUpdate;                 // ConfigValue.update(value) - force FEMALE on our holder
    private static Object femaleConst;                  // Gender.FEMALE
    private static Method breastPhysics;                // EntityConfigHolder.breastPhysics()
    private static Method physicsTick;                  // BothBreastsPhysics.tick(entity) - we tick it ourselves
    private static Object companionHolder;              // cached own-holder (created once per companion)
    private static UUID companionHolderId;
    private static Object stateKey;                     // ClientHelper impl's static STATE ContextKey
    private static Method setRenderData;                // NeoForge: setRenderData(ContextKey, Object); Fabric: setData(RenderStateDataKey, Object)
    private static Method getRenderData;                // read-back check: proves the layer can find our data

    private FemaleGenderCompat() {}

    public static boolean isPresent() {
        if (present == null) {
            present = modLoaded("female_gender_mod")          // current id (WildfireAPI.MODID)
                    || modLoaded("wildfire_gender")            // legacy id
                    || modLoaded("femalegender")               // very old id
                    || classExists("com.wildfire.api.client.WildfireClientAPI")
                    || classExists("com.wildfire.client.render.GenderLayer");
        }
        return present;
    }

    /** True once the mod's real chest layer has been added to the companion renderer. */
    public static boolean modRendering() { return layerAttached; }

    /**
     * Builds the mod's {@code GenderLayer} for the companion renderer, or null when unavailable.
     * Returns null when the render-data plumbing is missing (e.g. the Fabric build of the mod), so the
     * renderer keeps using its own chest overlay instead of silently rendering nothing.
     */
    @SuppressWarnings("unchecked")
    public static RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>> createLayer(
            RenderLayerParent<CompanionRenderState, HumanoidModel<CompanionRenderState>> parent) {
        if (!isPresent()) return null;
        init();
        if (genderRenderStateCtor == null || stateKey == null || setRenderData == null
                || holderCtor == null || genderUpdate == null || femaleConst == null) {
            LOG.warn("Female Gender Mod is installed but its chest layer cannot be attached (see hookup log)");
            return null;
        }
        try {
            Class<?> layerClass = Class.forName("com.wildfire.client.render.GenderLayer");
            Object layer = layerClass.getConstructor(RenderLayerParent.class).newInstance(parent);
            layerAttached = true;
            return (RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>>) layer;
        } catch (Throwable t) {
            LOG.warn("Female Gender Mod: GenderLayer could not be constructed", t);
            return null;
        }
    }

    /**
     * Fills the render state with a {@code GenderRenderState} built from the companion's OWN Wildfire config
     * holder (gender always FEMALE) - exactly what the mod's layer expects. Called from extractRenderState.
     *
     * <p>Runs only while the companion's feminine toggle is on: with the mod installed that switch decides
     * <b>whether</b> the chest renders; the owner's gender is irrelevant (a male player still gets the chest).
     * Every distinct outcome logs one line (only when it CHANGES), so latest.log always states why.</p>
     *
     * @return true when the mod's layer will draw the chest
     */
    public static boolean populateRenderState(HumanoidRenderState state, LivingEntity entity, float partialTick) {
        if (state == null || entity == null) return false;
        if (!(entity instanceof CompanionEntity companion)) return false;
        if (!layerAttached) {
            outcome(isPresent() ? "layer not attached (see warning above)" : "mod not installed");
            return false;
        }
        init();
        if (genderRenderStateCtor == null || stateKey == null || setRenderData == null
                || holderCtor == null || genderUpdate == null || femaleConst == null) {
            outcome("reflection incomplete (attempt " + initAttempts + "/5)");
            return false;
        }
        if (!companion.isFeminine()) { clearRenderData(state); outcome("feminine toggle OFF"); return false; }
        try {
            Object holder = ensureHolder(companion.getUUID());
            Object gender = genderOf(holder);
            Object genderState = genderRenderStateCtor.newInstance(holder, entity, state, partialTick);
            setRenderData.invoke(state, stateKey, genderState);
            if (state instanceof CompanionRenderState cs) cs.wgmData = genderState;   // stash for the submit phase
            Object readback = getRenderData != null ? getRenderData.invoke(state, stateKey) : null;
            outcome("rendering: gender=" + gender + ", bust=" + fmtBust(genderState)
                    + ", readback=" + (readback != null ? "OK" : "MISSING!")
                    + ", chest=" + state.chestEquipment
                    + ", invisible=" + state.isInvisibleToPlayer);
            return true;
        } catch (Throwable t) {
            if (!populateFailureLogged) {
                populateFailureLogged = true;
                LOG.warn("Female Gender Mod: could not build the chest render state for the companion", t);
            }
            outcome("exception: " + t);
            return false;
        }
    }

    /**
     * The companion's own config holder: created once per companion UUID, gender forced to FEMALE.
     * Never calls loadGenderInfo, so no disk read and no cloud fetch can fail or lag it.
     */
    private static Object ensureHolder(UUID id) throws Throwable {
        if (companionHolder == null || !id.equals(companionHolderId)) {
            companionHolder = holderCtor.newInstance(id);
            companionHolderId = id;
            genderUpdate.invoke(holderGender.invoke(companionHolder), femaleConst);
        }
        return companionHolder;
    }

    /**
     * Once per client tick: the mod only ticks breast physics for players/mannequins/armor stands, so the
     * companion's holder would stay at size 0 (and the mod's layer refuses to draw below 0.02) without this.
     */
    public static void tickPhysics(LivingEntity companion) {
        if (companionHolder == null || breastPhysics == null || physicsTick == null) return;
        try { physicsTick.invoke(breastPhysics.invoke(companionHolder), companion); } catch (Throwable ignored) { }
    }

    /**
     * WGM's breast-shaped armor layer ({@code GenderArmorLayer}): draws the chestplate over the female chest
     * so armor actually renders on it. Needs the vanilla EquipmentLayerRenderer the armor layers already use.
     */
    @SuppressWarnings("unchecked")
    public static RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>> createArmorLayer(
            RenderLayerParent<CompanionRenderState, HumanoidModel<CompanionRenderState>> parent,
            Object equipmentRenderer) {
        if (!isPresent() || !layerAttached || equipmentRenderer == null) return null;
        try {
            Class<?> cls = Class.forName("com.wildfire.client.render.GenderArmorLayer");
            Class<?> eq = Class.forName("net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer");
            Object layer = cls.getConstructor(RenderLayerParent.class, eq).newInstance(parent, equipmentRenderer);
            return (RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>>) layer;
        } catch (Throwable t) {
            LOG.warn("Female Gender Mod: GenderArmorLayer could not be constructed", t);
            return null;
        }
    }

    /** genderOf(holder) - the raw {@code Gender} enum value, or null when reflection fails. */
    private static Object genderOf(Object holder) {
        try { return valueGet.invoke(holderGender.invoke(holder)); } catch (Throwable t) { return null; }
    }

    /**
     * The mod's layer refuses to draw below physics size 0.02, so report both the configured bust and the LIVE
     * physics size: if the live size stays at 0 the mod's per-entity physics tick is not reaching our holder.
     */
    private static String fmtBust(Object genderState) {
        try {
            float config = (Float) genderState.getClass().getField("bustSize").get(genderState);
            Object phys = genderState.getClass().getField("leftBreastPhysics").get(genderState);
            float live = (Float) phys.getClass().getMethod("getBreastSize").invoke(phys);
            return String.format(java.util.Locale.ROOT, "%.2f, physics %s",
                    config, live < 0.02f ? "TOO SMALL (" + live + ") - mod will not draw!" : "ok");
        } catch (Throwable t) { return "?"; }
    }

    /** Clears stale render data from a previous frame so a gated-off chest can never keep drawing. */
    private static void clearRenderData(HumanoidRenderState state) {
        if (state instanceof CompanionRenderState cs) cs.wgmData = null;
        if (setRenderData == null || stateKey == null || state == null) return;
        try { setRenderData.invoke(state, stateKey, (Object) null); } catch (Throwable ignored) { }
    }

    /** Logs {@code s} only when it differs from the previous outcome - one line per real state change. */
    private static void outcome(String s) {
        if (!s.equals(lastOutcome)) { lastOutcome = s; LOG.info("[companionmod] WGM chest: {}", s); }
    }

    private static boolean layersLogged;

    /** One-time dump of the renderer's layer list - proves whether the mod's GenderLayer is actually attached. */
    public static void logLayersOnce(Object renderer) {
        if (layersLogged) return;
        layersLogged = true;
        try {
            java.lang.reflect.Field f = Class.forName("net.minecraft.client.renderer.entity.LivingEntityRenderer")
                    .getDeclaredField("layers");
            f.setAccessible(true);
            LOG.info("[companionmod] WGM chest: layers = {}", f.get(renderer));
        } catch (Throwable t) {
            LOG.info("[companionmod] WGM chest: layers unreadable ({})", t.toString());
        }
    }

    /**
     * Submit-phase: re-apply the stashed render data. Something (a NeoForge render-state modifier of another mod)
     * clears render data between extract and submit - GenderLayer would read null every frame without this.
     */
    public static void applyAtSubmit(HumanoidRenderState state) {
        if (state == null || !(state instanceof CompanionRenderState cs) || cs.wgmData == null) return;
        if (setRenderData == null || stateKey == null) return;
        try { setRenderData.invoke(state, stateKey, cs.wgmData); } catch (Throwable ignored) { }
    }

    /**
     * Submit-phase probe: the mod's GenderLayer reads the render data during submit, so if it vanished between
     * extract and submit that is the bug. Only runs when we EXPECT data (wgmData set) - otherwise a gated-off
     * chest would log a "failure" every frame.
     */
    public static void verifyAtSubmit(HumanoidRenderState state) {
        if (getRenderData == null || stateKey == null) return;
        if (state == null || !(state instanceof CompanionRenderState cs) || cs.wgmData == null) return;
        try {
            if (getRenderData.invoke(state, stateKey) == null) outcome("submit: DATA MISSING - cleared between extract and submit!");
        } catch (Throwable ignored) { }
    }

    /**
     * Resolves every Wildfire class/method we call. Retries a few times (the mod's services may not be up on the very
     * first frame) and logs exactly what is missing, so a failure shows up in latest.log instead of a silent no-chest.
     */
    private static synchronized void init() {
        if (initDone || initAttempts >= 5) return;
        initAttempts++;
        StringBuilder problems = new StringBuilder();
        try {
            Class<?> holderClass = Class.forName("com.wildfire.common.entities.EntityConfigHolder");
            holderGender = holderClass.getMethod("gender");
            valueGet = Class.forName("com.wildfire.common.config.value.ConfigValue").getMethod("get");
            genderCanHaveBreasts = Class.forName("com.wildfire.api.Gender").getMethod("canHaveBreasts");
            genderRenderStateCtor = Class.forName("com.wildfire.client.render.GenderRenderState")
                    .getConstructor(holderClass, LivingEntity.class, HumanoidRenderState.class, float.class);
            // companion-owned holder: constructor + force-FEMALE + physics ticking (no loadGenderInfo -> no IO)
            holderCtor = Class.forName("com.wildfire.common.entities.players.PlayerConfigHolder").getConstructor(UUID.class);
            genderUpdate = Class.forName("com.wildfire.common.config.value.ConfigValue").getMethod("update", Object.class);
            femaleConst = Class.forName("com.wildfire.api.Gender").getField("FEMALE").get(null);
            breastPhysics = holderClass.getMethod("breastPhysics");
            physicsTick = Class.forName("com.wildfire.client.physics.BothBreastsPhysics").getMethod("tick", LivingEntity.class);
        } catch (Throwable t) {
            problems.append("\n  config/API lookup failed: ").append(t);
        }
        try {
            Class<?> helper = Class.forName("com.wildfire.client.ClientHelper");
            Object instance = helper.getField("INSTANCE").get(null);
            // NeoClientHelper.STATE is a ContextKey, FabricClientHelper.STATE is a Fabric RenderStateDataKey
            stateKey = instance.getClass().getField("STATE").get(null);
            setRenderData = findSetter(stateKey);
            getRenderData = findGetter(stateKey);
            if (setRenderData == null) {
                problems.append("\n  no render-state setter accepts a ").append(stateKey.getClass().getName());
            }
        } catch (Throwable t) {
            problems.append("\n  render-state key lookup failed: ").append(t);
        }
        if (genderRenderStateCtor != null && stateKey != null && setRenderData != null
                && holderCtor != null && genderUpdate != null && femaleConst != null && breastPhysics != null && physicsTick != null) {
            initDone = true;
            LOG.info("Female Gender Mod hookup ready (render-state setter: {})", setRenderData.getName());
        } else {
            LOG.warn("Female Gender Mod hookup incomplete (attempt {}/5){}", initAttempts, problems);
        }
    }

    /** NeoForge exposes {@code setRenderData(ContextKey, Object)}, Fabric API exposes {@code setData(RenderStateDataKey, Object)}. */
    private static Method findSetter(Object key) {
        if (key == null) return null;
        for (Method m : HumanoidRenderState.class.getMethods()) {
            if (m.getParameterCount() != 2) continue;
            String n = m.getName();
            if (!n.equals("setRenderData") && !n.equals("setData")) continue;
            if (m.getParameterTypes()[0].isInstance(key)) return m;
        }
        return null;
    }

    /** The matching getter - used only to prove in the log that what we wrote is readable by the mod's layer. */
    private static Method findGetter(Object key) {
        if (key == null) return null;
        for (Method m : HumanoidRenderState.class.getMethods()) {
            if (m.getParameterCount() != 1) continue;
            String n = m.getName();
            if (!n.equals("getRenderData") && !n.equals("getData")) continue;
            if (m.getParameterTypes()[0].isInstance(key)) return m;
        }
        return null;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, FemaleGenderCompat.class.getClassLoader());
            return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean modLoaded(String id) {
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            Object result = loader.getMethod("isModLoaded", String.class).invoke(instance, id);
            if (Boolean.TRUE.equals(result)) return true;
        } catch (Throwable ignored) { }
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            Object result = modList.getMethod("isLoaded", String.class).invoke(instance, id);
            if (Boolean.TRUE.equals(result)) return true;
        } catch (Throwable ignored) { }
        return false;
    }
}
```

## common\src\main\java\com\example\companionmod\voice\LevelMeter.java

```java
package com.example.companionmod.voice;

/**
 * Turns PCM chunks into a 0..1 loudness value for the HUD bar. Thread-safe for one writer, many readers.
 * Mapping is on a dBFS scale (-60..-12 dB) so quiet speech still moves the bar and shouting does not pin it instantly.
 */
public final class LevelMeter {
    private static final float FLOOR_DB = -60f, CEIL_DB = -12f;
    private volatile float level, peak;
    private volatile long peakStamp;
    private volatile float maxSeen; // loudest raw (unsmoothed) value since reset, used for the "was anything said" gate

    public void feed(byte[] pcm16le, int len) {
        int n = len / 2;
        if (n == 0) return;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / n) / 32768.0;
        float db = rms <= 1e-6 ? -120f : (float) (20 * Math.log10(rms));
        float raw = Math.max(0f, Math.min(1f, (db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)));
        float cur = level;
        level = raw > cur ? cur + (raw - cur) * 0.6f : cur + (raw - cur) * 0.15f; // fast attack, slow release
        long now = System.nanoTime();
        if (raw >= peak || now - peakStamp > 600_000_000L) { peak = Math.max(raw, peak * 0.97f); peakStamp = now; }
        if (raw > maxSeen) maxSeen = raw;
    }

    public float level() { return level; }
    public float peak() { return peak; }
    public float maxSeen() { return maxSeen; }
    public void reset() { level = 0; peak = 0; maxSeen = 0; }
}
```

## common\src\main\java\com\example\companionmod\voice\MicCapture.java

```java
package com.example.companionmod.voice;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Records 16 kHz mono 16-bit PCM from the configured microphone (or the system default) on a daemon thread and feeds a {@link LevelMeter}. */
public final class MicCapture {
    public static final int SAMPLE_RATE = 16000;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private final LevelMeter meter;
    private final IntSupplier maxSeconds;
    private final Supplier<String> deviceName;   // read on every start: settings changes apply live
    private volatile int maxBytes;
    private volatile boolean running;
    private volatile String lastError = "";
    private TargetDataLine line;
    private Thread thread;
    private ByteArrayOutputStream buffer;
    private volatile long startedAt;

    public MicCapture(LevelMeter meter, int maxSeconds) { this(meter, () -> maxSeconds); }

    /** The cap is read on every start, so changing it in the settings screen takes effect immediately. */
    public MicCapture(LevelMeter meter, IntSupplier maxSeconds) { this(meter, maxSeconds, () -> ""); }

    /** {@code deviceName} is a Java Sound mixer name; blank/null = system default. */
    public MicCapture(LevelMeter meter, IntSupplier maxSeconds, Supplier<String> deviceName) {
        this.meter = meter;
        this.maxSeconds = maxSeconds;
        this.deviceName = deviceName;
    }

    public String lastError() { return lastError; }
    public boolean isRunning() { return running; }

    /** Opens the line: can block for a moment, so call it from a worker thread, never the render thread. */
    public synchronized boolean start() {
        if (running) return true;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            line = null;
            Mixer.Info wanted = AudioDevices.find(deviceName.get());
            if (wanted != null && AudioSystem.getMixer(wanted).isLineSupported(info)) {
                line = (TargetDataLine) AudioSystem.getMixer(wanted).getLine(info);
            }
            if (line == null) {                                   // unset, unknown (unplugged?) or unsupported: default
                if (!AudioSystem.isLineSupported(info)) { lastError = "No microphone supporting 16 kHz mono found"; return false; }
                line = (TargetDataLine) AudioSystem.getLine(info);
            }
            line.open(FORMAT, SAMPLE_RATE / 5 * 2);
            line.start();
        } catch (Exception e) {
            lastError = "Microphone unavailable: " + e.getMessage();
            return false;
        }
        maxBytes = Math.max(1, maxSeconds.getAsInt()) * SAMPLE_RATE * 2;
        buffer = new ByteArrayOutputStream();
        meter.reset();
        running = true;
        startedAt = System.currentTimeMillis();
        final TargetDataLine l = line;
        final ByteArrayOutputStream buf = buffer;
        thread = new Thread(() -> {
            byte[] chunk = new byte[SAMPLE_RATE / 50 * 2]; // 20 ms
            while (running) {
                int n = l.read(chunk, 0, chunk.length);
                if (n <= 0) continue;
                meter.feed(chunk, n);
                if (buf.size() < maxBytes) buf.write(chunk, 0, n);
            }
        }, "companionmod-mic");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** Stops recording and returns the PCM captured (empty array if it was never started). */
    public synchronized byte[] stop() {
        if (!running) return new byte[0];
        running = false;
        try { thread.join(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        line.stop();
        line.close();
        byte[] out = buffer.toByteArray();
        buffer = null;
        return out;
    }

    public long elapsedMillis() { return running ? System.currentTimeMillis() - startedAt : 0; }
}
```

## common\src\main\java\com\example\companionmod\voice\OllamaCatalog.java

```java
package com.example.companionmod.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Blocking lookups of the models and voices an Ollama / OpenAI-compatible server exposes.
 * Replaces the fetch code that used to live inside CompanionModelScreen and CompanionVoiceScreen.
 * Always call from a worker thread, never the render thread.
 */
public final class OllamaCatalog {
    public static final String DEFAULT_HOST = "http://127.0.0.1:11434";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private OllamaCatalog() {}

    public static String normalize(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) return DEFAULT_HOST;
        if (!h.contains("://")) h = "http://" + h;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /** Installed model names (GET /api/tags). {@code null} = server unreachable; empty = reachable but no models. */
    public static List<String> models(String host) {
        String body = get(normalize(host) + "/api/tags");
        if (body == null) return null;
        List<String> out = new ArrayList<>();
        try {
            JsonElement arr = JsonParser.parseString(body).getAsJsonObject().get("models");
            if (arr != null && arr.isJsonArray()) {
                for (JsonElement e : arr.getAsJsonArray()) {
                    String n = e.isJsonObject() ? str(e.getAsJsonObject(), "name") : null;
                    if (n != null && !n.isBlank()) out.add(n);
                }
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Voices for a speech model (GET /v1/audio/voices, then /api/voices). Blank model = every voice. Never null. */
    public static List<String> voices(String host, String model) {
        String h = normalize(host);
        String m = model == null ? "" : model.trim();
        List<String[]> all = new ArrayList<>();                                   // {voiceId, model}
        collect(all, get(h + "/v1/audio/voices"), "data", "id");
        if (all.isEmpty()) collect(all, get(h + "/api/voices"), "voices", "name");
        List<String> matching = new ArrayList<>(), everything = new ArrayList<>();
        for (String[] v : all) {
            everything.add(v[0]);
            if (m.isEmpty() || matchesModel(v[1], m)) matching.add(v[0]);
        }
        return matching.isEmpty() ? everything : matching;                        // unsure filter: show everything, not nothing
    }

    private static boolean matchesModel(String entryModel, String model) {
        if (entryModel == null || entryModel.isBlank()) return true;
        return entryModel.equals(model) || entryModel.startsWith(model) || model.startsWith(entryModel);
    }

    private static String get(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void collect(List<String[]> out, String body, String arrayKey, String idKey) {
        if (body == null || body.isBlank()) return;
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonElement arr = root.isJsonArray() ? root : (root.isJsonObject() ? root.getAsJsonObject().get(arrayKey) : null);
            if (arr == null || !arr.isJsonArray()) return;
            for (JsonElement e : arr.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                String id = str(v, idKey);
                if (id != null && !id.isBlank()) out.add(new String[]{id, str(v, "model")});
            }
        } catch (RuntimeException ignored) { }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
```

## common\src\main\java\com\example\companionmod\voice\OllamaVoiceBackend.java

```java
package com.example.companionmod.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;

/**
 * Talks to a local Ollama.
 *  - hearing + thinking: POST /v1/chat/completions with an input_audio part (the model answers with a small JSON object)
 *  - OR: speech-to-text first (STT model), then a normal text chat model decides the action
 *  - speaking:           POST /v1/audio/speech, only for models Ollama reports with the "speech" capability
 *  - detection:          POST /api/show to read capabilities
 * All methods block; call them from a worker thread.
 */
public final class OllamaVoiceBackend {
    public record Probe(boolean reachable, boolean chatModelPresent, boolean chatHasAudio, String speechModel, String message) {}

    private final VoiceConfig cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Deque<String[]> history = new ArrayDeque<>(); // {heardText, rawAssistantJson}
    private volatile String speechModel;      // resolved by probe(); null = text only
    private volatile boolean chatHasAudio;
    private volatile boolean probed;

    public OllamaVoiceBackend(VoiceConfig cfg) { this.cfg = cfg; }

    /** Convenience for the simple (host, model) form. */
    public OllamaVoiceBackend(String host, String model) {
        this(new VoiceConfig());
        cfg.host = host;
        cfg.chatModel = model;
    }

    public String speechModel() { return speechModel; }
    public boolean isProbed() { return probed; }

    // ---------------------------------------------------------------- probing

    public Probe probe() {
        JsonObject chat = show(cfg.chatModel);
        if (chat == null) {
            boolean up = ping();
            probed = true;
            speechModel = null;
            return new Probe(up, false, false, null, up
                    ? "Ollama is running but model '" + cfg.chatModel + "' was not found. Run: ollama pull " + cfg.chatModel
                    : "Cannot reach Ollama at " + cfg.host + ". Start it with: ollama serve");
        }
        boolean audio = hasCap(chat, "audio");
        chatHasAudio = audio;
        String speech = null;
        if (!cfg.speechModel.isBlank()) speech = cfg.speechModel;
        else if (hasCap(chat, "speech")) speech = cfg.chatModel;
        else if (!cfg.fallbackSpeechModel.isBlank()) {
            JsonObject fb = show(cfg.fallbackSpeechModel);
            if (fb != null && hasCap(fb, "speech")) speech = cfg.fallbackSpeechModel;
        }
        speechModel = cfg.speakReplies ? speech : null;
        probed = true;
        String hearing = audio ? ""
                : (!cfg.sttModel.isBlank() ? "Hearing via STT '" + cfg.sttModel + "'. "
                : "Warning: '" + cfg.chatModel + "' does not report audio input. Set an STT model in AI Connection to use a text LLM. ");
        String msg = hearing
                + (speechModel == null ? "Replies will be text only (no speech model found; try: ollama pull " + cfg.fallbackSpeechModel + ")."
                                       : "Speech via '" + speechModel + "'.");
        return new Probe(true, true, audio, speechModel, msg);
    }

    private boolean ping() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(cfg.host + "/api/version"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (IOException | InterruptedException e) { return false; }
    }

    private JsonObject show(String model) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(cfg.host + "/api/show"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? JsonParser.parseString(r.body()).getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) { return null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    private static boolean hasCap(JsonObject show, String cap) {
        JsonElement c = show.get("capabilities");
        if (c == null || !c.isJsonArray()) return false;
        for (JsonElement e : c.getAsJsonArray()) if (cap.equalsIgnoreCase(e.getAsString())) return true;
        return false;
    }

    // ---------------------------------------------------------------- speech to text

    /** OpenAI/whisper.cpp-compatible transcription. Returns null when the server does not support it. */
    private String transcribe(byte[] wav) {
        String base = cfg.sttHost == null || cfg.sttHost.isBlank() ? cfg.host : cfg.sttHost;
        String boundary = "----CompanionBoundary" + System.nanoTime();
        byte[] body = multipartBody(boundary, cfg.sttModel, wav);
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/audio/transcriptions"))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            if (o.has("text")) return o.get("text").getAsString();
            if (o.has("transcription")) return o.get("transcription").getAsString();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    private static byte[] multipartBody(String boundary, String model, byte[] wav) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                    + model + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(wav);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    // ---------------------------------------------------------------- hearing + thinking

    String systemPrompt(String name, String context) {
        return "You are " + name + ", a loyal companion fighting beside the player in their Minecraft world. "
             + "Personality: " + cfg.companionPersonality + ". Traits: " + cfg.companionTraits + ". "
             + "About you: " + cfg.companionDescription + " "
             + "Talk like a loyal friend: warm, slightly casual, react to the moment (worry, excitement, humor) in at most 2 short sentences. "
             + "Never repeat the same sentence twice in a row - vary your wording.\n"
             + "The player talks through a push-to-talk mic and the transcription can be badly wrong. "
             + "If the audio is garbled or you are not sure what was said, DO NOT guess a command: put your best guess in "
             + "\"heard\", say something short like \"Sorry, I did not catch that - say it again?\" and use action none. "
             + "Only choose an action when the player clearly asks for one in this message. "
             + "Greetings, small talk, questions, or comments about the world are action none - just reply in character. "
             + "Never start mining, gathering, building, fighting or exploring unless the player explicitly asked for it in this message. "
             + "A bare order (stay, come, sit, follow, stop, wait, defend) is that action with an empty target. "
             + "Reply with ONE JSON object and nothing else. The say field is REQUIRED: always answer with a short in-character line, even for bare orders (a word is enough):\n"
             + "{\"heard\":\"<what the player said>\",\"say\":\"<your short in-character reply>\","
             + "\"action\":\"<action>\",\"target\":\"<target or empty>\",\"memory\":\"<one fact to remember or empty>\"}\n"
             + "action must be exactly one of: follow, stay, sit, stand, attack, guard, defensive, passive, come, stop, continue (resume interrupted work), "
             + "surface (dig a staircase up to the daylight; target empty), "
             + "inventory (only when the player asks to SEE your bag), "
             + "pickup (walk to a dropped item ON THE GROUND and store it; put the item name in target, e.g. bone, diamond, rotten_flesh - only for items already lying around), "
             + "seek_block (walk to the named block and mine it; target = a block id - vanilla or from ANY mod, with or without namespace: oak_log or log for wood/trees, stone, cobblestone, dirt, coal_ore, iron_ore, zanite_ore, aether:zanite_ore; 'wood', 'logs' and 'planks' mean the whole family), "
             + "drop, heal, patrol, wander, explore, build (target = schematic name, e.g. house; places it block by block from the bag), "
             + "farm (harvest ripe crops nearby and replant), plant (target = sapling name e.g. birch_sapling; plants saplings from your bag on nearby soil), equip (target = tool name, e.g. pickaxe), trade, craft, smelt, brew, enchant, "
             + "fish (fish at nearby water), cook, open, close, dance, jump, sneak, sleep, wake, remember, forget, set_name (rename; new name in target), "
             + "set_skin (target = Minecraft player name), feminine, masculine, "
             + "track, hunt (attack the named or nearest hostile), fight, flee, hide, retreat, defend, none (just chatting).\n"
             + "Gathering a material (wood, stone, coal, iron, diamonds...) is ALWAYS seek_block with the block id, even when the player says 'collect', 'get' or 'bring'. "
             + "If a request needs several steps ('get some wood and bring it back'), reply action none with a short 'On it!' and the planning system takes over. "
             + "target rules: "
             + "'attack this creeper'/'kill that sheep' -> attack, target the mob. "
             + "'find diamonds'/'look for coal'/'mine iron' -> seek_block, target diamond_ore/coal_ore/iron_ore. "
             + "'find zanite ore'/'mine gravitite' -> seek_block, target zanite_ore/gravitite_ore (any mod's block id works). "
             + "'pick up this bone'/'grab that diamond' -> pickup, target bone/diamond. "
             + "'call yourself Alex' -> set_name, target Alex. "
             + "'build the house' -> build, target house. "
             + "'dig up to the surface'/'to the daylight' -> surface, target empty (never a block id). "
             + "Leave target empty for toggle commands.\n"
             + "Your bag has 4 armor slots (auto-worn so they protect you), 4 tool slots (you automatically hold the best tool for the block you are mining) "
             + "and 18 storage slots; pickup/store puts items where they belong and you can also be given items via the "
             + "inventory UI. When you are downed the player must hold right click on you for 3 seconds to revive you. "
             + "Current situation: " + context;
    }

    /** Sends the recorded WAV and returns the parsed reply. Throws IOException with a player-readable message on failure. */
    public synchronized VoiceReply converse(byte[] wav, String context) throws IOException {
        // A normal text LLM can be used when an STT model is configured: transcribe first, then reason over text.
        if (!cfg.sttModel.isBlank()) {
            String heard = transcribe(wav);
            if (heard != null && !heard.isBlank()) return converseText(heard, context);
        }

        JsonArray messages = baseMessages(context);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", "Respond to this audio from the player.");
        parts.add(t);
        JsonObject a = new JsonObject();
        a.addProperty("type", "input_audio");
        JsonObject ia = new JsonObject();
        ia.addProperty("data", Base64.getEncoder().encodeToString(wav));
        ia.addProperty("format", "wav");
        a.add("input_audio", ia);
        parts.add(a);
        user.add("content", parts);
        messages.add(user);

        String raw;
        try {
            raw = chat(messages, true);
        } catch (HttpStatusException e) {
            if (e.status == 400) raw = chat(messages, false); // some builds reject response_format with audio parts
            else throw e;
        }
        return remember(raw);
    }

    /** Text-only path used when an STT model supplies the transcript. */
    public synchronized VoiceReply converseText(String heard, String context) throws IOException {
        JsonArray messages = baseMessages(context);
        messages.add(msg("user", "The player said: \"" + heard + "\". Respond in character and choose the right action."));
        String raw;
        try {
            raw = chat(messages, true);
        } catch (HttpStatusException e) {
            if (e.status == 400) raw = chat(messages, false);
            else throw e;
        }
        VoiceReply reply = remember(raw);
        if (reply.heard().isBlank()) return new VoiceReply(heard, reply.say(), reply.command(), reply.target(), reply.memory());
        return reply;
    }

    private JsonArray baseMessages(String context) {
        JsonArray messages = new JsonArray();
        messages.add(msg("system", systemPrompt(cfg.companionName, context)));
        for (String[] h : history) {
            messages.add(msg("user", h[0]));
            messages.add(msg("assistant", h[1]));
        }
        return messages;
    }

    private VoiceReply remember(String raw) {
        VoiceReply reply = ReplyParser.parse(raw);
        if (!reply.heard().isBlank() || !reply.say().isBlank()) {
            history.addLast(new String[]{reply.heard().isBlank() ? "(audio)" : reply.heard(), raw});
            while (history.size() > cfg.historyTurns) history.removeFirst();
        }
        return reply;
    }

    public synchronized void clearHistory() { history.clear(); }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private String chat(JsonArray messages, boolean jsonMode) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.chatModel);
        body.addProperty("stream", false);
        body.addProperty("temperature", Math.min(0.85f, cfg.temperature)); // hot models invent chores from chitchat
        body.addProperty("max_tokens", 400);
        body.add("messages", messages);
        if (jsonMode) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_object");
            body.add("response_format", rf);
        }
        String resp = post("/v1/chat/completions", body.toString());
        try {
            return JsonParser.parseString(resp).getAsJsonObject().getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        } catch (RuntimeException e) {
            throw new IOException("Unexpected reply from Ollama: " + abbreviate(resp));
        }
    }

    // ---------------------------------------------------------------- model listing (settings picker)

    /** Names of every model the server has, from GET /api/tags. Empty list on failure. */
    public java.util.List<String> listModels() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(cfg.host + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return java.util.List.of();
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            JsonElement arr = o.get("models");
            if (arr == null || !arr.isJsonArray()) return java.util.List.of();
            java.util.List<String> out = new java.util.ArrayList<>();
            for (JsonElement e : arr.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    String n = e.getAsJsonObject().has("name") ? e.getAsJsonObject().get("name").getAsString() : "";
                    if (n != null && !n.isBlank() && !out.contains(n)) out.add(n);
                }
            }
            return out;
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return java.util.List.of();
        }
    }

    // ---------------------------------------------------------------- speaking

    /** Returns a WAV (16-bit mono, usually 24 kHz) or null when no speech model is available. */
    public byte[] synthesize(String text) throws IOException {
        String model = speechModel;
        if (model == null || text == null || text.isBlank()) return null;
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text.length() > 1000 ? text.substring(0, 1000) : text);
        if (!cfg.speechVoice.isBlank()) body.addProperty("voice", cfg.speechVoice);
        body.addProperty("response_format", "wav");
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(cfg.host + "/v1/audio/speech"))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                speechModel = null; // stop retrying a model that cannot speak; chat keeps working text-only
                throw new HttpStatusException(r.statusCode(), new String(r.body()));
            }
            return r.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    // ---------------------------------------------------------------- http

    static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status, String body) { super("Ollama returned HTTP " + status + ": " + abbreviate(body)); this.status = status; }
    }

    private String post(String path, String json) throws IOException {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(cfg.host + path))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new HttpStatusException(r.statusCode(), r.body());
            return r.body();
        } catch (java.net.ConnectException e) {
            throw new IOException("Cannot reach Ollama at " + cfg.host);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    private static String abbreviate(String s) { return s == null ? "" : (s.length() > 160 ? s.substring(0, 160) + "…" : s); }
}
```

## common\src\main\java\com\example\companionmod\voice\PlanningBackend.java

```java
package com.example.companionmod.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Text-only calls to the same Ollama chat model (lfm3-omni) that turn a request or a situation into a plan.
 * Reads VoiceConfig: host, chatModel, timeoutSeconds, temperature, companionName/Description/Personality/Traits.
 * Blocking: call from a worker thread.
 */
public final class PlanningBackend {
    private static final class HttpStatus extends IOException {
        final int status;
        HttpStatus(int status, String body) { super("Ollama HTTP " + status + ": " + (body.length() > 120 ? body.substring(0, 120) : body)); this.status = status; }
    }

    private final VoiceConfig cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public PlanningBackend(VoiceConfig cfg) { this.cfg = cfg; }

    /** The player asked for something open-ended ("get me some wood and come back"). */
    public PlanParser.Plan planGoal(String request, String context) throws IOException {
        String raw = chat(system(false), "Situation: " + context + "\nPlayer said: " + request);
        return PlanParser.parse(raw, false);
    }

    /** Nobody asked: should the companion do something useful on its own right now? */
    public PlanParser.Plan think(String context) throws IOException {
        String raw = chat(system(true), "Situation: " + context + "\nDecide now.");
        return PlanParser.parse(raw, true);
    }

    private String system(boolean auto) {
        String name = nz(cfg.companionName, "Companion");
        StringBuilder sb = new StringBuilder();
        sb.append("You are the planning mind of ").append(name).append(", a companion in the player's Minecraft world. ")
          .append(nz(cfg.companionDescription, "")).append(" Personality: ").append(nz(cfg.companionPersonality, "friendly"))
          .append(". Traits: ").append(nz(cfg.companionTraits, "loyal")).append(".\n")
          .append("The supplied LIVE MINECRAFT OBSERVATIONS are authoritative world data: exact registry IDs, positions, entities, inventory and active tasks. Read them before acting. Do not claim to observe anything absent from them.\n")
          .append("Turn the observations and request into a SHORT executable plan using ONLY these steps:\n")
          .append("follow, stay, sit, stand, come, stop (seconds = how long to hold it); wander, patrol, guard (seconds); defensive, passive; ")
          .append("attack or hunt (fights nearby enemies); ")
          .append("seek_block (arg = a VANILLA BLOCK ID such as oak_log, log, stone, cobblestone, coal_ore, iron_ore, diamond_ore - use it whenever the task is 'get wood/stone/coal/diamonds'; never a colloquial word); ")
          .append("pickup (arg = optional item name; ONLY for items already dropped on the ground); ")
          .append("farm (harvests ripe crops nearby); fish (fishes at nearby water); plant (arg = sapling name such as birch_sapling; plants saplings from the bag on nearby soil; use pickup first only if saplings lie on the ground); ")
          .append("build (arg = schematic name, places it from the schematics folder); say (arg = one short in-character sentence); wait (seconds).\n")
          .append("Ground choices in observations: use pickup only for listed drops, attack/hunt only for listed hostiles, seek_block only for a requested resource (an exact observed block ID is best), and avoid repeating an already active task. The executor will navigate, switch tools, swing, collect drops, fight and speak; do not output code or pretend unsupported actions exist.\n")
          .append("Reply with ONE JSON object and nothing else: {\"goal\":\"get wood\",\"steps\":[{\"op\":\"seek_block\",\"arg\":\"log\",\"seconds\":90},{\"op\":\"come\",\"arg\":\"\",\"seconds\":10}]}\n");
        if (auto) {
            sb.append("You are deciding on your own, unasked. Act only if something clearly useful and safe is happening ")
              .append("(enemies near the player -> guard; dropped items nearby -> pickup; player far away -> come; ")
              .append("ripe crops nearby -> farm; the player idle near water -> fish). ")
              .append("Otherwise reply {\"goal\":\"\",\"steps\":[]}. Never mine, build, start fights or use seek_block; ")
              .append("pickup only the drops listed in the observations. At most 4 steps.");
        } else {
            sb.append("At most 8 steps. Never invent other ops. If the player is only chatting and wants no action reply {\"goal\":\"\",\"steps\":[]}. ")
              .append("Greetings and small talk are NOT goals. Bare orders (stay, come, sit, follow, stop, wait, defend, equip...) ")
              .append("are executed by the game itself, never planned. If the task is impossible or unclear use a single say step explaining briefly. After leaving the player always end with come or follow.");
        }
        return sb.toString();
    }

    private String chat(String system, String user) throws IOException {
        try { return post(system, user, true); }
        catch (HttpStatus e) { if (e.status == 400) return post(system, user, false); throw e; }
    }

    private String post(String system, String user, boolean jsonMode) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.chatModel);
        body.addProperty("stream", false);
        body.addProperty("temperature", Math.min(0.5f, cfg.temperature));
        body.addProperty("max_tokens", 400);
        JsonArray msgs = new JsonArray();
        msgs.add(msg("system", system));
        msgs.add(msg("user", user));
        body.add("messages", msgs);
        if (jsonMode) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_object");
            body.add("response_format", rf);
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(cfg.host + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new HttpStatus(r.statusCode(), r.body());
            return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        catch (RuntimeException e) { throw new IOException("Unexpected reply from Ollama"); }
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static String nz(String s, String fallback) { return s == null || s.isBlank() ? fallback : s.trim(); }
}
```

## common\src\main\java\com\example\companionmod\voice\PlanParser.java

```java
package com.example.companionmod.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for AI plans (MC-free). Used on the client to read the model's output and on the
 * server to re-validate whatever the client sends. Only whitelisted ops survive; text, ids and durations are clamped.
 */
public final class PlanParser {
    public record Plan(String goal, List<PlanStep> steps) { public boolean isEmpty() { return steps.isEmpty(); } }

    public static final Set<String> OPS = Set.of("follow", "stay", "sit", "stand", "come", "stop", "wander", "patrol", "guard",
            "defensive", "passive", "attack", "seek_block", "pickup", "say", "wait", "hunt", "farm", "fish", "build", "plant", "continue");
    /** What the companion may do on its own initiative: no mining, no starting fights, no builds. */
    public static final Set<String> AUTO_OPS = Set.of("follow", "come", "wander", "patrol", "guard", "defensive", "pickup", "say", "wait", "farm", "fish");

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("mine", "seek_block"), Map.entry("dig", "seek_block"), Map.entry("find_block", "seek_block"),
            Map.entry("seek", "seek_block"), Map.entry("gather", "seek_block"), Map.entry("chop", "seek_block"),
            Map.entry("lumber", "seek_block"), Map.entry("cut", "seek_block"), Map.entry("pick_up", "pickup"),
            Map.entry("fight", "attack"), Map.entry("explore", "wander"), Map.entry("return", "come"),
            Map.entry("come_back", "come"), Map.entry("go_home", "come"), Map.entry("rest", "sit"),
            Map.entry("talk", "say"), Map.entry("speak", "say"), Map.entry("protect", "guard"),
            Map.entry("harvest", "farm"), Map.entry("bring", "come"), Map.entry("deliver", "come"),
            Map.entry("replant", "plant"), Map.entry("reforest", "plant"), Map.entry("resume", "continue"), Map.entry("keep_going", "continue"));
    /** Ops whose meaning depends on the argument: a material word means "go mine it", otherwise "loot the ground". */
    private static final Set<String> AMBIGUOUS = Set.of("get", "collect", "gather", "fetch", "pickup", "take", "grab");
    /** Words that usually name a mineable material rather than a dropped item. */
    private static final Set<String> MATERIAL_WORDS = Set.of("wood", "log", "logs", "stone", "cobble", "cobblestone",
            "dirt", "sand", "gravel", "clay", "coal", "iron", "gold", "diamond", "emerald", "lapis", "redstone",
            "copper", "quartz", "debris", "obsidian", "glass", "brick", "ore", "plank", "planks", "leaves",
            "wool", "snow", "ice", "rock", "timber", "lumber", "trunk", "tree");

    private PlanParser() {}

    public static boolean isAuto(String raw) {
        JsonObject o = obj(raw);
        return o != null && o.has("auto") && o.get("auto").isJsonPrimitive() && o.get("auto").getAsBoolean();
    }

    public static Plan parse(String raw, boolean auto) {
        List<PlanStep> out = new ArrayList<>();
        JsonObject o = obj(raw);
        if (o == null) return new Plan("", out);
        String goal = clean(str(o, "goal"), 60);
        JsonElement arr = o.has("steps") ? o.get("steps") : o.get("plan");
        int maxSteps = auto ? 4 : 8, maxStep = auto ? 60 : 180, budget = auto ? 120 : 600;
        if (arr != null && arr.isJsonArray()) {
            for (JsonElement e : arr.getAsJsonArray()) {
                if (out.size() >= maxSteps) break;
                String op, arg = "";
                int sec = -1;
                if (e.isJsonPrimitive()) op = e.getAsString();
                else if (e.isJsonObject()) {
                    JsonObject s = e.getAsJsonObject();
                    op = firstNonEmpty(s, "op", "action", "task", "type", "command");
                    arg = firstNonEmpty(s, "arg", "target", "item", "block", "name", "id", "what");
                    sec = intOr(s, "seconds", "duration", "time", "for", "seconds");
                } else continue;
                op = norm(op);
                if (AMBIGUOUS.contains(op)) op = isMaterialWord(arg) ? "seek_block" : "pickup";
                if (!OPS.contains(op) || (auto && !AUTO_OPS.contains(op))) continue;
                switch (op) {
                    case "seek_block" -> { arg = blockId(arg); if (arg.isEmpty()) continue; }
                    case "say" -> { arg = clean(arg, 120); if (arg.isEmpty()) continue; }
                    case "pickup" -> arg = clean(arg, 32).toLowerCase();
                    case "build" -> {
                        arg = clean(arg, 40).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
                        if (arg.isEmpty()) continue;
                    }
                    case "attack", "hunt" -> arg = clean(arg, 32).toLowerCase(Locale.ROOT);
                    case "plant" -> arg = clean(arg, 32).toLowerCase(Locale.ROOT);
                    default -> arg = "";
                }
                if (sec < 0) sec = defaultSeconds(op);
                sec = Math.max(0, Math.min(sec, Math.min(maxStep, budget)));
                if (needsTime(op) && sec == 0) sec = 1;
                budget -= sec;
                if (budget < 0) break;
                out.add(new PlanStep(op, arg, sec));
            }
        }
        return new Plan(goal, out);
    }

    public static String toJson(Plan plan, boolean auto) {
        JsonObject o = new JsonObject();
        o.addProperty("goal", plan.goal());
        o.addProperty("auto", auto);
        JsonArray a = new JsonArray();
        for (PlanStep s : plan.steps()) {
            JsonObject j = new JsonObject();
            j.addProperty("op", s.op());
            j.addProperty("arg", s.arg());
            j.addProperty("seconds", s.seconds());
            a.add(j);
        }
        o.add("steps", a);
        return o.toString();
    }

    public static int totalSeconds(Plan p) { return p.steps().stream().mapToInt(PlanStep::seconds).sum(); }

    private static boolean needsTime(String op) { return !op.equals("say") && !op.equals("stop") && !op.equals("continue"); }

    private static boolean isMaterialWord(String arg) {
        String a = arg == null ? "" : arg.toLowerCase(Locale.ROOT);
        for (String w : MATERIAL_WORDS) if (a.contains(w)) return true;
        return false;
    }

    /** First non-blank string among the keys. */
    private static String firstNonEmpty(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = clean(str(o, k), 60);
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static int intOr(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive()) {
                try { return (int) Math.round(o.get(k).getAsDouble()); } catch (RuntimeException ignored) { }
            }
        }
        return -1;
    }

    private static int defaultSeconds(String op) {
        return switch (op) {
            case "wander", "patrol", "guard", "attack", "hunt", "pickup", "farm", "fish" -> 30;
            case "build" -> 90;
            case "plant" -> 60;
            case "seek_block" -> 120;
            case "wait" -> 3;
            case "say", "stop", "continue" -> 0;
            default -> 8;
        };
    }

    private static String norm(String op) {
        String s = op == null ? "" : op.trim().toLowerCase().replaceAll("[^a-z_]+", "_");
        return ALIASES.getOrDefault(s, s);
    }

    private static String blockId(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase().replace("minecraft:", "").replace(' ', '_').replaceAll("[^a-z0-9_]", "");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    public static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static JsonObject obj(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        try {
            int a = t.indexOf('{'), b = t.lastIndexOf('}');
            int sa = t.indexOf('['), sb = t.lastIndexOf(']');
            if (sa >= 0 && (a < 0 || sa < a) && sb > sa) {          // bare array of steps
                JsonObject w = new JsonObject();
                w.add("steps", JsonParser.parseString(t.substring(sa, sb + 1)));
                return w;
            }
            if (a < 0 || b <= a) return null;
            JsonElement el = JsonParser.parseString(t.substring(a, b + 1));
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) { return null; }
    }
}
```

## common\src\main\java\com\example\companionmod\voice\PlanStep.java

```java
package com.example.companionmod.voice;

/** One validated step of an AI-made plan. {@code seconds} is the hold time / timeout for the step. */
public record PlanStep(String op, String arg, int seconds) {}
```

## common\src\main\java\com\example\companionmod\voice\PushToTalkController.java

```java
package com.example.companionmod.voice;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Hold-to-talk state machine and the single companion speech playback queue.
 *
 * <p>Thread rules: the game thread only flips the state and queues work. Opening / closing the microphone happens on
 * its own IO thread (it can block), HTTP and playback on the worker. The state lives in one {@link AtomicReference}
 * and every transition is a compare-and-set, so the speech thread can never overwrite LISTENING / THINKING
 * (which used to leave the microphone open).</p>
 */
public final class PushToTalkController {
    private final OllamaVoiceBackend backend;
    private final VoiceConfig cfg;
    private final Supplier<String> context;
    private final Consumer<VoiceReply> onReply;
    private final Consumer<String> onStatus;
    private final SpeechPlayer.Listener speechListener;

    private final LevelMeter micMeter = new LevelMeter();
    private final LevelMeter speakMeter = new LevelMeter();
    private final MicCapture mic;
    private final SpeechPlayer player;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "companionmod-voice"));
    private final ExecutorService micIo = Executors.newSingleThreadExecutor(r -> daemon(r, "companionmod-mic-io"));
    private final AtomicReference<VoiceState> state = new AtomicReference<>(VoiceState.IDLE);
    private volatile boolean probeStarted;
    private volatile boolean micFailed;

    /** Latest line waiting to be spoken; rapid chatter is intentionally coalesced. */
    private final AtomicReference<String> pendingSpeech = new AtomicReference<>();
    private volatile boolean speechDraining;
    private volatile long lastSpeechProbe;      // speech-model recovery: re-probe at most once a minute

    public PushToTalkController(OllamaVoiceBackend backend, VoiceConfig cfg, Supplier<String> context,
                                Consumer<VoiceReply> onReply, Consumer<String> onStatus) {
        this(backend, cfg, context, onReply, onStatus, SpeechPlayer.Listener.NONE);
    }

    public PushToTalkController(OllamaVoiceBackend backend, VoiceConfig cfg, Supplier<String> context,
                                Consumer<VoiceReply> onReply, Consumer<String> onStatus,
                                SpeechPlayer.Listener speechListener) {
        this.backend = backend;
        this.cfg = cfg;
        this.context = context;
        this.onReply = onReply;
        this.onStatus = onStatus;
        this.speechListener = speechListener == null ? SpeechPlayer.Listener.NONE : speechListener;
        this.player = new SpeechPlayer(speakMeter, this.speechListener, () -> cfg.outputDevice);
        this.mic = new MicCapture(micMeter, () -> cfg.maxRecordSeconds, () -> cfg.micDevice);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public VoiceState state() { return state.get(); }
    public float level() {
        return switch (state.get()) { case LISTENING -> micMeter.level(); case SPEAKING -> speakMeter.level(); default -> 0f; };
    }
    public float peak() {
        return switch (state.get()) { case LISTENING -> micMeter.peak(); case SPEAKING -> speakMeter.peak(); default -> 0f; };
    }
    public long listeningMillis() { return mic.elapsedMillis(); }

    /** Game thread. */
    public void onPress() {
        VoiceState s = state.get();
        if (s == VoiceState.SPEAKING) player.stop();
        else if (s != VoiceState.IDLE) return;
        if (!state.compareAndSet(s, VoiceState.LISTENING)) return;     // lost a race with the speech thread: ignore this press
        micFailed = false;
        if (!probeStarted) {
            probeStarted = true;
            worker.execute(() -> onStatus.accept(backend.probe().message()));
        }
        micIo.execute(() -> {
            if (!mic.start()) {
                micFailed = true;
                state.compareAndSet(VoiceState.LISTENING, VoiceState.IDLE);
                onStatus.accept(mic.lastError());
            }
        });
    }

    /** Game thread. */
    public void onRelease() {
        if (state.get() != VoiceState.LISTENING) return;
        final String ctx = context.get();                               // world snapshot must be taken on the game thread
        micIo.execute(() -> {
            if (micFailed) return;
            long ms = mic.elapsedMillis();
            byte[] pcm = mic.stop();
            if (ms < cfg.minRecordMillis || micMeter.maxSeen() < cfg.silenceGate) {
                state.compareAndSet(VoiceState.LISTENING, VoiceState.IDLE);
                onStatus.accept("I didn't hear anything.");
                return;
            }
            if (!state.compareAndSet(VoiceState.LISTENING, VoiceState.THINKING)) return;
            final byte[] wav = WavUtil.pcm16ToWav(pcm, MicCapture.SAMPLE_RATE, 1);
            worker.execute(() -> {
                VoiceReply reply;
                try {
                    if (!backend.isProbed()) backend.probe();
                    reply = backend.converse(wav, ctx);
                } catch (Exception e) {
                    state.compareAndSet(VoiceState.THINKING, VoiceState.IDLE);
                    onStatus.accept(e.getMessage() == null ? e.toString() : e.getMessage());
                    return;
                }
                onReply.accept(reply);
                state.compareAndSet(VoiceState.THINKING, VoiceState.IDLE);
            });
        });
    }

    /** Called on the game thread; synthesis and playback happen on the worker. */
    public void speakText(String text) {
        if (text == null || text.isBlank()) return;
        pendingSpeech.set(text.length() > 200 ? text.substring(0, 200) : text);
        if (speechDraining) return;
        speechDraining = true;
        worker.execute(this::drainSpeech);
    }

    private void drainSpeech() {
        String current = null;
        try {
            for (;;) {
                current = pendingSpeech.getAndSet(null);
                if (current == null) break;
                if (!backend.isProbed()) backend.probe();
                if (backend.speechModel() == null) {
                    // The startup probe may have run before the speech model was pulled (or it 404'd later).
                    // Re-probe at most once a minute so speech recovers without a relog.
                    long nowMs = System.currentTimeMillis();
                    if (nowMs - lastSpeechProbe > 60_000) {
                        lastSpeechProbe = nowMs;
                        try { backend.probe(); } catch (RuntimeException ignored) { }
                    }
                    if (backend.speechModel() == null) {
                        speechListener.onUnavailable(current);
                        continue;
                    }
                }
                // Only speak while idle: never talk over the player (LISTENING / THINKING keep their state).
                if (!state.compareAndSet(VoiceState.IDLE, VoiceState.SPEAKING) && state.get() != VoiceState.SPEAKING) {
                    speechListener.onUnavailable(current);
                    continue;
                }
                byte[] audio = backend.synthesize(current);
                if (state.get() != VoiceState.SPEAKING) {          // the player pressed the key while we were synthesizing
                    speechListener.onUnavailable(current);
                    continue;
                }
                if (audio == null) speechListener.onUnavailable(current);
                else player.playBlocking(audio, cfg.speechVolume, current);
            }
        } catch (Exception e) {
            if (current != null) speechListener.onUnavailable(current);
            onStatus.accept("Speech failed, continuing in text: " + e.getMessage());
        } finally {
            speechDraining = false;
            state.compareAndSet(VoiceState.SPEAKING, VoiceState.IDLE);
        }
    }

    public void shutdown() {
        player.stop();
        micIo.execute(() -> { if (mic.isRunning()) mic.stop(); });
        micIo.shutdown();
        worker.shutdownNow();
        state.set(VoiceState.IDLE);
    }
}
```

## common\src\main\java\com\example\companionmod\voice\ReplyParser.java

```java
package com.example.companionmod.voice;

import com.example.companionmod.command.CompanionCommand;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Turns the model's raw text into a {@link VoiceReply}. Tolerates code fences, prose around the JSON, and plain text. */
public final class ReplyParser {
    private ReplyParser() {}

    public static VoiceReply parse(String raw) {
        if (raw == null) return VoiceReply.text("");
        String text = raw.trim();
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                JsonElement el = JsonParser.parseString(text.substring(a, b + 1));
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    String heard = str(o, "heard");
                    String say = clean(str(o, "say"));
                    String target = truncate(clean(str(o, "target")), 60);   // the packet field allows 1024; keep model targets short
                    String memory = clean(str(o, "memory"));
                    CompanionCommand cmd = CompanionCommand.parse(str(o, "action"));
                    if (say.isBlank() && cmd != CompanionCommand.NONE) say = ack(cmd);   // json_mode models often drop the line; never go silent
                    return new VoiceReply(heard, say, cmd, target, memory);
                }
            } catch (RuntimeException ignored) { /* fall through to plain text */ }
        }
        return VoiceReply.text(clean(text));
    }

    /** Short in-character fallback line when the model chose an action but left "say" empty. */
    private static String ack(CompanionCommand c) {
        return switch (c) {
            case FOLLOW -> "Following.";
            case STAY -> "Staying put.";
            case SIT -> "Sitting down.";
            case STAND -> "Standing up.";
            case COME -> "Coming.";
            case ATTACK, FIGHT, HUNT -> "On it.";
            case GUARD -> "Guarding.";
            case PATROL -> "Patrolling.";
            case PICKUP -> "Picking up.";
            case DROP -> "Dropped.";
            case SEEK_BLOCK, MINE -> "Digging.";
            case BUILD -> "Building.";
            case FARM -> "Farming.";
            case PLANT -> "Planting.";
            case HEAL -> "Healing up.";
            case INVENTORY -> "Here, look.";
            case STOP -> "Stopped.";
            default -> "On it.";
        };
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    /** Strip control chars / markdown noise and cap length (also used server-side on untrusted client text). */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").replace("*", "").replace("`", "").trim();
        return t.length() > 240 ? t.substring(0, 240) : t;
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
```

## common\src\main\java\com\example\companionmod\voice\SpeechPlayer.java

```java
package com.example.companionmod.voice;

import com.example.companionmod.client.PcmStreamSound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.function.Supplier;

/**
 * Plays WAV replies and reports actual PCM playback progress to the subtitle clock. Prefers Minecraft's
 * own sound engine (OpenAL) so the voice pauses with the game, obeys the audio sliders, and gets any
 * in-world effects installed (e.g. Sound Physics Remastered's echo); a plain {@code SourceDataLine}
 * stays as a fallback for runtimes where the engine can't stream memory PCM.
 *
 * <p>When the player picks a specific output device in the settings, the engine path is skipped: OpenAL
 * cannot target a chosen Java Sound device, so playback goes straight to that device's SourceDataLine.</p>
 */
public final class SpeechPlayer {
    public interface Listener {
        Listener NONE = new Listener() {};
        default void onStart(String text, long durationMillis) {}
        default void onProgress(String text, float progress) {}
        default void onStop(String text) {}
        default void onUnavailable(String text) {}
    }

    private final LevelMeter meter;
    private final Listener listener;
    private final Supplier<String> outputDevice;   // read on every playback: settings changes apply live
    private volatile boolean playing;
    private volatile boolean stopRequested;

    public SpeechPlayer(LevelMeter meter) { this(meter, Listener.NONE); }
    public SpeechPlayer(LevelMeter meter, Listener listener) { this(meter, listener, () -> ""); }
    public SpeechPlayer(LevelMeter meter, Listener listener, Supplier<String> outputDevice) {
        this.meter = meter;
        this.listener = listener == null ? Listener.NONE : listener;
        this.outputDevice = outputDevice;
    }

    public boolean isPlaying() { return playing; }
    public void stop() { stopRequested = true; }

    public void playBlocking(byte[] wav) { playBlocking(wav, 1.0f, ""); }
    public void playBlocking(byte[] wav, float volume) { playBlocking(wav, volume, ""); }

    /** Blocking playback; call from a worker thread. */
    public void playBlocking(byte[] wav, float volume, String text) {
        WavUtil.Pcm pcm;
        try {
            pcm = WavUtil.decode(wav);
        } catch (RuntimeException e) {
            listener.onUnavailable(text);
            return;
        }
        AudioFormat fmt = new AudioFormat(pcm.sampleRate(), 16, pcm.channels(), true, false);
        float gain = Math.max(0f, Math.min(1f, volume));
        byte[] data = applyGain(pcm.data(), gain);
        int frameBytes = Math.max(1, 2 * pcm.channels());
        long duration = Math.max(1L, Math.round(
                1000.0 * data.length / frameBytes / pcm.sampleRate()));
        playing = true;
        stopRequested = false;
        meter.reset();
        listener.onStart(text, duration);

        // ---- engine path: OpenAL channel -> pause-aware, slider-obeying, SPR-echoed --------------------
        // Skipped when a specific output device was chosen: OpenAL cannot target a chosen Java Sound device.
        String dev = outputDevice == null ? "" : outputDevice.get();
        boolean explicitDevice = dev != null && !dev.isBlank();
        if (!explicitDevice && PcmStreamSound.engineStreamingSupported()) {
            try {
                boolean handled = PcmStreamSound.play(data, pcm.sampleRate(), pcm.channels(), 1.0f, duration,
                        () -> stopRequested, new PcmStreamSound.Listener() {
                            @Override public void onProgress(float progress) { listener.onProgress(text, progress); }
                            @Override public void onLevel(byte[] bytes, int off, int len) {
                                byte[] slice = new byte[len];
                                System.arraycopy(bytes, off, slice, 0, len);
                                meter.feed(slice, len);
                            }
                            @Override public void onStop() { listener.onStop(text); }
                        });
                if (handled) { playing = false; meter.reset(); return; }
            } catch (Throwable ignored) { }   // any engine surprise falls through to the line below
        }

        // ---- fallback: direct SourceDataLine (engine missing / rejected the stream / explicit device) -
        boolean started = false;
        try (SourceDataLine out = openOutput(fmt, explicitDevice ? dev : "")) {
            out.open(fmt);
            out.start();
            started = true;
            int chunk = Math.max(frameBytes, pcm.sampleRate() / 25 * frameBytes);
            for (int off = 0; off < data.length && !stopRequested; off += chunk) {
                int len = Math.min(chunk, data.length - off);
                byte[] slice = new byte[len];
                System.arraycopy(data, off, slice, 0, len);
                meter.feed(slice, len);
                int written = out.write(slice, 0, len);
                listener.onProgress(text, Math.min(1.0f, (off + written) / (float) data.length));
            }
            if (stopRequested) out.flush(); else out.drain();
        } catch (Exception ignored) {
            if (!started) listener.onUnavailable(text);
        } finally {
            if (started) listener.onStop(text);
            playing = false;
            meter.reset();
        }
    }

    /** The chosen device's line, or the system default when {@code dev} is blank/unknown. */
    private static SourceDataLine openOutput(AudioFormat fmt, String dev) throws LineUnavailableException {
        Mixer.Info wanted = AudioDevices.find(dev);
        if (wanted != null) {
            try {
                return AudioSystem.getSourceDataLine(fmt, wanted);
            } catch (IllegalArgumentException | LineUnavailableException e) {
                // chosen mixer vanished or cannot play at this format: fall through to the default line
            }
        }
        return AudioSystem.getSourceDataLine(fmt);
    }

    private static byte[] applyGain(byte[] raw, float gain) {
        if (gain >= 0.999f || raw.length < 2) return raw;
        byte[] out = raw.clone();
        for (int i = 0; i + 1 < out.length; i += 2) {
            short sample = (short) ((out[i] & 0xFF) | (out[i + 1] << 8));
            sample = (short) (sample * gain);
            out[i] = (byte) (sample & 0xFF);
            out[i + 1] = (byte) ((sample >> 8) & 0xFF);
        }
        return out;
    }
}
```

## common\src\main\java\com\example\companionmod\voice\VoiceBackend.java

```java
package com.example.companionmod.voice;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for a local voice AI backend (Ollama + LFM3-Audio).
 * Implementations should perform network I/O off the render thread.
 */
public interface VoiceBackend {
    /**
     * Send recorded WAV bytes to the backend and receive a text response.
     */
    CompletableFuture<String> ask(byte[] wavBytes);

    /**
     * Request TTS audio for the given text and return a WAV file path or bytes.
     */
    CompletableFuture<byte[]> speak(String text, String voice);
}
```

## common\src\main\java\com\example\companionmod\voice\VoiceConfig.java

```java
package com.example.companionmod.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client-side settings, stored as config/companionmod-client.json. Missing keys fall back to these defaults. */
public final class VoiceConfig {
    public String host = "http://127.0.0.1:11434";
    /** Model that hears the player (audio in) and decides what to say / do. */
    public String chatModel = "lfm3-audio:latest";
    /** Speech model for spoken replies. Empty = auto-detect (chatModel if Ollama reports "speech", else fallbackSpeechModel). */
    public String speechModel = "";
    public String fallbackSpeechModel = "phonon";
    public String speechVoice = "";
    /** Java Sound input device name for push-to-talk. Empty = system default microphone. */
    public String micDevice = "";
    /** Java Sound output device for spoken replies (bypasses Minecraft's engine so a specific device can be used). Empty = default. */
    public String outputDevice = "";
    /** Optional speech-to-text model (Whisper etc.) so a normal text LLM can be used instead of an audio model. */
    public String sttModel = "";
    /** OpenAI-compatible base URL for the STT model. Empty = use {@link #host}. */
    public String sttHost = "";
    public boolean speakReplies = true;
    /** 0..1 playback volume for spoken replies. */
    public float speechVolume = 0.9f;
    public String companionName = "Companion";
    public String companionDescription = "A loyal companion fighting beside you.";
    public String companionPersonality = "Friendly and helpful";
    /** Comma-separated traits the model should stay in character with. */
    public String companionTraits = "loyal, curious, protective";
    public String companionCardBackground = "default";
    /** Minecraft player name whose skin the companion wears. Empty = bundled skin. "owner" = your own skin. */
    public String companionSkin = "";
    /** Renders the optional feminine chest model. */
    public boolean feminineModel = false;
    /** Body model: "auto" (follows the skin), "slim" (Alex) or "classic" (wide). */
    public String companionBody = "auto";
    public boolean memoryEnabled = true;
    public int memoryMaxEvents = 30;
    public int maxRecordSeconds = 20;
    public int minRecordMillis = 350;
    /** Recordings whose loudest moment stays below this (0..1 on the HUD scale) are discarded as silence. */
    public float silenceGate = 0.12f;
    /** When true, spoken requests that aren't direct orders are turned into an AI plan by a second LLM call. Off by default: chitchat must not become chores. */
    public boolean dynamicGoals = false;
    /** When true, the companion decides on its own to do small safe things every few minutes. Off by default. */
    public boolean autonomyEnabled = false;
    /** Rough seconds between two autonomous thoughts. */
    public int autonomyIntervalSeconds = 30;
    /** Bumped whenever defaults change meaningfully; migrates older config files. */
    public int settingsVersion = 2;
    public int historyTurns = 12;
    public int timeoutSeconds = 60;
    /** Higher = more varied/character replies; 0.3 felt robotic, 0.7 keeps it chatty but on-key. */
    public float temperature = 0.7f;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static VoiceConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                VoiceConfig c = GSON.fromJson(Files.readString(file), VoiceConfig.class);
                if (c != null) {
                    if (c.settingsVersion < 2) {          // v2: self-planning was far too trigger-happy (greeting -> "dig diamonds")
                        c.dynamicGoals = false;
                        c.autonomyEnabled = false;
                        c.settingsVersion = 2;
                    }
                    c.save(file); return c;             // re-save so new keys appear in old files
                }
            }
        } catch (Exception ignored) { /* corrupt file: use defaults */ }
        VoiceConfig c = new VoiceConfig();
        c.save(file);
        return c;
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (IOException ignored) { }
    }
}
```

## common\src\main\java\com\example\companionmod\voice\VoiceReply.java

```java
package com.example.companionmod.voice;

import com.example.companionmod.command.CompanionCommand;

/**
 * What the model understood and what the companion answers.
 * {@code heard} may be empty if the model skipped it; {@code target} carries an entity name for
 * "attack this creeper" style orders; {@code memory} is an optional fact to remember.
 */
public record VoiceReply(String heard, String say, CompanionCommand command, String target, String memory) {
    public VoiceReply(String heard, String say, CompanionCommand command) {
        this(heard, say, command, "", "");
    }

    public static VoiceReply text(String say) {
        return new VoiceReply("", say, CompanionCommand.NONE, "", "");
    }
}
```

## common\src\main\java\com\example\companionmod\voice\VoiceState.java

```java
package com.example.companionmod.voice;

public enum VoiceState { IDLE, LISTENING, THINKING, SPEAKING }
```

## common\src\main\java\com\example\companionmod\voice\WavUtil.java

```java
package com.example.companionmod.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WavUtil {
    private WavUtil() {}

    /** Result of decoding a WAV: raw 16-bit little-endian PCM plus its format. */
    public record Pcm(byte[] data, int sampleRate, int channels) {}

    public static byte[] pcm16ToWav(byte[] pcm, int sampleRate, int channels) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
         .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.writeBytes(h.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    /**
     * Decode a 16-bit PCM WAV. Handles streamed WAVs whose size fields are 0xFFFFFFFF (Ollama's speech endpoint does this):
     * the data chunk is then taken to run to the end of the buffer. Throws on anything that is not 16-bit PCM.
     */
    public static Pcm decode(byte[] wav) {
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F')
            throw new IllegalArgumentException("not a RIFF/WAV file");
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, rate = 24000, ch = 1, bits = 16;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4);
            long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if (id.equals("fmt ")) {
                int fmt = b.getShort(body) & 0xFFFF;
                ch = b.getShort(body + 2);
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
                if (fmt != 1 && fmt != 0xFFFE) throw new IllegalArgumentException("unsupported WAV format " + fmt);
            } else if (id.equals("data")) {
                if (bits != 16) throw new IllegalArgumentException("only 16-bit WAV supported");
                long avail = wav.length - body;
                int len = (int) Math.min(size, avail);
                byte[] pcm = new byte[len - (len % (2 * Math.max(ch, 1)))];
                System.arraycopy(wav, body, pcm, 0, pcm.length);
                return new Pcm(pcm, rate, ch);
            }
            pos = (int) Math.min((long) body + size + (size & 1), Integer.MAX_VALUE - 16);
        }
        throw new IllegalArgumentException("no data chunk");
    }
}
```

## common\src\main\java\com\example\companionmod\world\CompanionRegistry.java

```java
package com.example.companionmod.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which companion entity belongs to which player, stored in the world folder (companionmod_companions.json).
 * This is what lets the shrine find a companion that sits in an unloaded chunk, and lets a stale copy remove itself.
 * Plain JSON on purpose: no SavedData API, so it is identical on Fabric and NeoForge.
 */
public final class CompanionRegistry {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, UUID> OWNERS = new HashMap<>();
    private static Path loadedFrom;

    private CompanionRegistry() {}

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("companionmod_companions.json");
    }

    private static void ensure(MinecraftServer server) {
        Path f = file(server);
        if (f.equals(loadedFrom)) return;
        loadedFrom = f;
        OWNERS.clear();
        try {
            if (!Files.isRegularFile(f)) return;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                try { OWNERS.put(UUID.fromString(e.getKey()), UUID.fromString(e.getValue().getAsString())); }
                catch (RuntimeException ignored) { }
            }
        } catch (IOException | RuntimeException ignored) { /* corrupt file: start empty */ }
    }

    public static synchronized UUID get(MinecraftServer server, UUID owner) {
        ensure(server);
        return OWNERS.get(owner);
    }

    public static synchronized void set(MinecraftServer server, UUID owner, UUID companion) {
        ensure(server);
        if (!companion.equals(OWNERS.put(owner, companion))) save(server);
    }

    /** Removes the entry only if it still points at {@code companion} (a newer companion keeps its entry). */
    public static synchronized void clear(MinecraftServer server, UUID owner, UUID companion) {
        ensure(server);
        if (OWNERS.remove(owner, companion)) save(server);
    }

    /** Forget the in-memory copy (server stopped); the file stays. */
    public static synchronized void reset() {
        loadedFrom = null;
        OWNERS.clear();
    }

    private static void save(MinecraftServer server) {
        JsonObject o = new JsonObject();
        OWNERS.forEach((k, v) -> o.addProperty(k.toString(), v.toString()));
        Path f = file(server);
        try {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }
}
```

## common\src\main\java\com\example\companionmod\world\ModCommands.java

```java
package com.example.companionmod.world;

import com.example.companionmod.command.CompanionCommand;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.registry.ModContent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /companion shrine                 place a shrine at your feet
 * /companion summon                 summon / call your companion at your feet (testing)
 * /companion do <action> [target]   run any voice action without a microphone (follow, stay, sit, attack creeper, ...)
 * /companion skin <playerName>      wear that player's Minecraft skin ("" clears it)
 * /companion feminine <true|false>  toggle the feminine chest model
 */
public final class ModCommands {
    private ModCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("companion")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))   // 26.1-CHECK: permission API was reworked in 1.21.11; older form: src -> src.hasPermission(2)
                .then(Commands.literal("shrine").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    var core = ShrinePlacer.place((net.minecraft.server.level.ServerLevel) p.level(), p.blockPosition().offset(3, 0, 3));
                    ctx.getSource().sendSuccess(() -> Component.literal("Shrine placed at " + core.toShortString()), true);
                    return 1;
                }))
                .then(Commands.literal("summon").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    ShrineSummoner.summon((net.minecraft.server.level.ServerLevel) p.level(), p.blockPosition(), p);
                    return 1;
                }))
                .then(Commands.literal("do")
                        .then(Commands.argument("action", StringArgumentType.word())
                                .executes(ctx -> doCommand(ctx, ""))
                                .then(Commands.argument("target", StringArgumentType.greedyString())
                                        .executes(ctx -> doCommand(ctx, StringArgumentType.getString(ctx, "target"))))))
                .then(Commands.literal("skin")
                        .then(Commands.argument("playerName", StringArgumentType.greedyString()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            c.setSkin(StringArgumentType.getString(ctx, "playerName"));
                            ctx.getSource().sendSuccess(() -> Component.literal("Companion skin set to " + c.skin()), false);
                            return 1;
                        })))
                .then(Commands.literal("feminine")
                        .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            boolean on = BoolArgumentType.getBool(ctx, "enabled");
                            c.setFeminine(on);
                            ctx.getSource().sendSuccess(() -> Component.literal("Feminine model: " + on), false);
                            return 1;
                        })))
                .then(Commands.literal("revive").executes(ctx -> {
                    CompanionEntity c = companion(ctx);
                    if (c == null) return 0;
                    c.revive();
                    ctx.getSource().sendSuccess(() -> Component.literal("Companion revived"), false);
                    return 1;
                }))
                .then(Commands.literal("find")
                        .then(Commands.argument("block", StringArgumentType.greedyString()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            String id = CompanionEntity.normalizeBlockId(StringArgumentType.getString(ctx, "block"));
                            if (id.isBlank()) {
                                ctx.getSource().sendFailure(Component.literal("Unknown block."));
                                return 0;
                            }
                            c.setSeekBlock(id);
                            c.speak("Looking for " + CompanionEntity.prettyBlockId(id) + ".");
                            ctx.getSource().sendSuccess(() -> Component.literal("Seeking " + id), false);
                            return 1;
                        })))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            CompanionEntity c = companion(ctx);
                            if (c == null) return 0;
                            String name = StringArgumentType.getString(ctx, "name");
                            c.setCustomName(Component.literal(name));
                            c.setCustomNameVisible(false);
                            ctx.getSource().sendSuccess(() -> Component.literal("Companion renamed to " + name), false);
                            return 1;
                        }))));
    }

    private static CompanionEntity companion(CommandContext<CommandSourceStack> ctx) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
            if (c == null) ctx.getSource().sendFailure(Component.literal("You have no companion."));
            return c;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Run this as a player."));
            return null;
        }
    }

    private static int doCommand(CommandContext<CommandSourceStack> ctx, String target) {
        try {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            CompanionEntity c = ModContent.findCompanion(p.level().getServer(), p.getUUID());
            if (c == null) { ctx.getSource().sendFailure(Component.literal("You have no companion.")); return 0; }
            CompanionCommand cmd = CompanionCommand.parse(StringArgumentType.getString(ctx, "action"));
            c.applyCommand(cmd, p, target);
            ctx.getSource().sendSuccess(() -> Component.literal("Companion: " + cmd + (target.isBlank() ? "" : " -> " + target)), false);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
    }
}
```

## common\src\main\java\com\example\companionmod\world\ServerHooks.java

```java
package com.example.companionmod.world;

import com.example.companionmod.net.CommandHandler;
import net.minecraft.server.level.ServerPlayer;

/** Everything both loaders must call from their join / leave / stop events, so Fabric and NeoForge behave identically. */
public final class ServerHooks {
    private ServerHooks() {}

    public static void onPlayerJoin(ServerPlayer player) { ShrinePlacer.onPlayerJoin(player); }

    public static void onPlayerLeave(ServerPlayer player) { CommandHandler.forget(player.getUUID()); }

    public static void onServerStopped() {
        CompanionRegistry.reset();
        CommandHandler.forgetAll();
    }
}
```

## common\src\main\java\com\example\companionmod\world\ShrinePlacer.java

```java
package com.example.companionmod.world;

import com.example.companionmod.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Places the shrine (a single block with a 3D model) and remembers it with a tiny marker file in the world folder,
 * so it is placed exactly once: next to the first player that ever joins a world, which on a fresh world is the spawn point.
 */
public final class ShrinePlacer {
    private ShrinePlacer() {}

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("companionmod_shrine.json");
    }

    public static void onPlayerJoin(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        MinecraftServer server = level.getServer();
        Path file = marker(server);
        if (Files.exists(file)) return;
        BlockPos core = place(level, player.blockPosition().offset(3, 0, 2));
        try {
            Files.writeString(file, "{\"x\":" + core.getX() + ",\"y\":" + core.getY() + ",\"z\":" + core.getZ() + "}");
        } catch (IOException ignored) { }
        player.sendSystemMessage(Component.literal("A strange shrine hums near spawn at " + core.getX() + " " + core.getY() + " " + core.getZ() + "."));
    }

    /** Puts the shrine block on the surface at (origin.x, origin.z). Returns its position. */
    public static BlockPos place(ServerLevel level, BlockPos origin) {
        int x = origin.getX(), z = origin.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);   // first free block above the ground
        BlockPos core = new BlockPos(x, y, z);
        BlockPos below = core.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {   // water, leaves, a hole: give it footing
            level.setBlock(below, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 3);
        }
        level.setBlock(core, ModContent.SHRINE_CORE.defaultBlockState(), 3);
        return core;
    }
}
```

## common\src\main\java\com\example\companionmod\world\ShrineSummoner.java

```java
package com.example.companionmod.world;

import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public final class ShrineSummoner {
    private ShrineSummoner() {}

    /**
     * One companion per player: reuse the living one (calling it to the shrine) or create a new one.
     * A companion from another dimension is recreated here WITH its bag, name and look; one stranded in an
     * unloaded chunk is replaced, and when that chunk loads the stale copy drops its bag and removes itself.
     */
    public static void summon(ServerLevel level, BlockPos shrine, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        Vec3 spot = findSpot(level, shrine);
        CompanionEntity existing = ModContent.findCompanion(server, player.getUUID());

        if (existing != null && existing.level() == level) {
            existing.setPos(spot.x, spot.y, spot.z);
            existing.resetFallDistance();
            existing.getNavigation().stop();
            player.sendSystemMessage(Component.literal(existing.getName().getString() + " answers the shrine's call."));
            effects(level, spot);
            return;
        }

        CompanionEntity c = ModContent.COMPANION.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (c == null) return;
        c.setPos(spot.x, spot.y, spot.z);
        c.setYRot(player.getYRot() + 180f);
        c.setOwner(player.getUUID());
        c.setCustomName(Component.literal("Companion"));
        c.setCustomNameVisible(true);
        if (existing != null) c.copyStateFrom(existing);
        else c.store(new ItemStack(Items.IRON_SWORD));     // goes into the sword slot, so it stays in hand
        CompanionRegistry.set(server, player.getUUID(), c.getUUID());
        if (existing != null) existing.discard();          // its bag now lives in the new body
        level.addFreshEntity(c);
        player.sendSystemMessage(Component.literal(existing != null
                ? "Your companion steps out of the light, carrying everything it had."
                : "A companion steps out of the light. Hold V to talk to it."));
        effects(level, spot);
    }

    private static void effects(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1.0, at.z, 40, 0.35, 0.8, 0.35, 0.03);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.1, at.z, 15, 0.4, 0.05, 0.4, 0.02);
        level.playSound(null, BlockPos.containing(at), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.3f);
    }

    /** A free 2-high spot beside the shrine (preferring the four sides), else on top. */
    private static Vec3 findSpot(ServerLevel level, BlockPos shrine) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = shrine.relative(d);
            if (free(level, p) && free(level, p.above())) return Vec3.atBottomCenterOf(p);
        }
        return Vec3.atBottomCenterOf(shrine.above());
    }

    private static boolean free(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }
}
```

## common\src\test\java\com\example\companionmod\voice\VoiceSmokeTest.java

```java
package com.example.companionmod.voice;

import com.example.companionmod.command.CompanionCommand;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Plain-main smoke test (no JUnit needed): java -cp out:gson.jar ...VoiceSmokeTest */
public class VoiceSmokeTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // --- WAV roundtrip + streamed-header WAV (size fields 0xFFFFFFFF like Ollama's speech endpoint)
        byte[] pcm = new byte[4800];
        for (int i = 0; i < 2400; i++) { short s = (short) (Math.sin(i / 10.0) * 12000); pcm[2*i] = (byte) s; pcm[2*i+1] = (byte) (s >> 8); }
        WavUtil.Pcm back = WavUtil.decode(WavUtil.pcm16ToWav(pcm, 16000, 1));
        check(back.data().length == 4800 && back.sampleRate() == 16000 && back.channels() == 1, "wav roundtrip");
        byte[] streamed = WavUtil.pcm16ToWav(pcm, 24000, 1);
        ByteBuffer sb = ByteBuffer.wrap(streamed).order(ByteOrder.LITTLE_ENDIAN);
        sb.putInt(4, -1); sb.putInt(40, -1);
        WavUtil.Pcm sp = WavUtil.decode(streamed);
        check(sp.data().length == 4800 && sp.sampleRate() == 24000, "streamed-header wav (0xFFFFFFFF sizes)");

        // --- level meter: silence ~0, loud tone high, release is slower than attack
        LevelMeter m = new LevelMeter();
        m.feed(new byte[640], 640);
        check(m.level() < 0.01f, "meter silence ~0");
        byte[] loud = new byte[640];
        for (int i = 0; i < 320; i++) { short s = (short) (Math.sin(i / 3.0) * 20000); loud[2*i] = (byte) s; loud[2*i+1] = (byte) (s >> 8); }
        for (int i = 0; i < 6; i++) m.feed(loud, 640);
        check(m.level() > 0.7f && m.maxSeen() > 0.8f, "meter loud tone high (level=" + m.level() + ")");
        float hi = m.level(); m.feed(new byte[640], 640);
        check(m.level() > hi * 0.6f, "meter slow release");

        // --- parser
        VoiceReply r = ReplyParser.parse("```json\n{\"heard\":\"follow me\",\"say\":\"On my way!\",\"action\":\"Follow\"}\n```");
        check(r.command() == CompanionCommand.FOLLOW && r.say().equals("On my way!") && r.heard().equals("follow me"), "parser fenced json");
        check(ReplyParser.parse("Sure thing, boss").command() == CompanionCommand.NONE, "parser plain text -> NONE");
        check(CompanionCommand.parse("stand down") == CompanionCommand.PASSIVE && CompanionCommand.parse("come here") == CompanionCommand.COME, "command synonyms");

        // --- fake Ollama
        final String[] lastChatBody = {null};
        final int[] chatCalls = {0};
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/api/version", x -> send(x, 200, "{\"version\":\"0.41.0\"}"));
        s.createContext("/api/show", x -> {
            JsonObject req = JsonParser.parseString(new String(x.getRequestBody().readAllBytes())).getAsJsonObject();
            String model = req.get("model").getAsString();
            if (model.startsWith("lfm3-audio")) send(x, 200, "{\"capabilities\":[\"tools\",\"audio\"]}");
            else if (model.equals("phonon")) send(x, 200, "{\"capabilities\":[\"speech\"]}");
            else send(x, 404, "{\"error\":\"not found\"}");
        });
        s.createContext("/v1/chat/completions", x -> {
            lastChatBody[0] = new String(x.getRequestBody().readAllBytes());
            chatCalls[0]++;
            if (chatCalls[0] == 1) { send(x, 400, "{\"error\":\"response_format unsupported with audio\"}"); return; } // exercise retry
            send(x, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"heard\\\":\\\"sit down\\\",\\\"say\\\":\\\"Resting.\\\",\\\"action\\\":\\\"sit\\\"}\"}}]}");
        });
        s.createContext("/v1/audio/speech", x -> {
            x.getRequestBody().readAllBytes();
            byte[] w = WavUtil.pcm16ToWav(new byte[2400], 24000, 1);
            x.sendResponseHeaders(200, w.length); x.getResponseBody().write(w); x.close();
        });
        s.start();
        String host = "http://127.0.0.1:" + s.getAddress().getPort();

        VoiceConfig cfg = new VoiceConfig();
        cfg.host = host;
        OllamaVoiceBackend be = new OllamaVoiceBackend(cfg);
        OllamaVoiceBackend.Probe p = be.probe();
        check(p.reachable() && p.chatModelPresent() && p.chatHasAudio(), "probe: lfm3-audio found with audio capability");
        check("phonon".equals(p.speechModel()), "probe: falls back to phonon because lfm3-audio has no 'speech' cap (" + p.message() + ")");

        VoiceReply vr = be.converse(WavUtil.pcm16ToWav(pcm, 16000, 1), "mode=FOLLOW");
        check(vr.command() == CompanionCommand.SIT && vr.heard().equals("sit down"), "converse parsed JSON reply after 400->retry");
        JsonObject sent = JsonParser.parseString(lastChatBody[0]).getAsJsonObject();
        check(!sent.has("response_format"), "retry dropped response_format");
        String part = sent.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString();
        check(part.equals("input_audio"), "request carries input_audio part");
        check(sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("mode=FOLLOW"), "context injected in system prompt");

        byte[] wav = be.synthesize("Resting.");
        check(wav != null && WavUtil.decode(wav).sampleRate() == 24000, "synthesize returns decodable wav");

        // missing model path
        cfg.chatModel = "nope";
        check(!new OllamaVoiceBackend(cfg).probe().chatModelPresent(), "probe: missing model reported");
        s.stop(0);
        cfg.host = "http://127.0.0.1:1";
        check(!new OllamaVoiceBackend(cfg).probe().reachable(), "probe: unreachable host reported");

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void send(com.sun.net.httpserver.HttpExchange x, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        x.getResponseBody().write(b);
        x.close();
    }
}
```

## fabric\src\client\java\com\example\companionmod\fabric\CompanionModFabricClient.java

```java
package com.example.companionmod.fabric;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.client.ClientVoice;
import com.example.companionmod.client.CompanionInventoryScreen;
import com.example.companionmod.client.CompanionRenderer;
import com.example.companionmod.registry.ModContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

/** Fabric client entrypoint: renderer, HUD, and the shared voice/radial/skin logic in common/ClientVoice. */
public class CompanionModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientVoice.setSender(ClientPlayNetworking::send);
        KeyMappingHelper.registerKeyMapping(ClientVoice.talkKey());   // all three show up in Controls, rebindable
        KeyMappingHelper.registerKeyMapping(ClientVoice.radialKey());
        KeyMappingHelper.registerKeyMapping(ClientVoice.settingsKey());
        EntityRendererRegistry.register(ModContent.COMPANION, CompanionRenderer::new);
        MenuScreens.register(ModContent.COMPANION_INV_MENU, CompanionInventoryScreen::new);
        HudElementRegistry.addLast(CompanionMod.id("voice_hud"),
                (graphics, deltaTracker) -> ClientVoice.renderHud(graphics, deltaTracker));
        ClientTickEvents.END_CLIENT_TICK.register(ClientVoice::tick);
    }
}
```

## fabric\src\main\java\com\example\companionmod\fabric\CompanionModFabric.java

```java
package com.example.companionmod.fabric;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.net.CommandHandler;
import com.example.companionmod.net.CommandPayload;
import com.example.companionmod.registry.ModContent;
import com.example.companionmod.world.ModCommands;
import com.example.companionmod.world.ServerHooks;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;

/** Fabric entrypoint. Mirrors CompanionModNeoForge one-to-one; the real logic lives in common/. */
public class CompanionModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CompanionMod.setConfigDir(FabricLoader.getInstance().getConfigDir());

        Registry.register(BuiltInRegistries.BLOCK, ModContent.blockId(), ModContent.SHRINE_CORE);
        Registry.register(BuiltInRegistries.ITEM, ModContent.itemId(), ModContent.SHRINE_CORE_ITEM);
        Registry.register(BuiltInRegistries.ENTITY_TYPE, ModContent.entityId(), ModContent.COMPANION);
        Registry.register(BuiltInRegistries.MENU, ModContent.menuId(), ModContent.COMPANION_INV_MENU);   // was registered twice -> duplicate-key crash
        FabricDefaultAttributeRegistry.register(ModContent.COMPANION, CompanionEntity.createAttributes());

        PayloadTypeRegistry.serverboundPlay().register(CommandPayload.TYPE, CommandPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CommandPayload.TYPE, (payload, ctx) -> CommandHandler.handle(ctx.player(), payload));

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> ModCommands.register(dispatcher));

        // 26.1-CHECK: ServerPlayConnectionEvents lives in networking.v1 (NOT event.lifecycle.v1); getPlayer() on the handler.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ServerHooks.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ServerHooks.onPlayerLeave(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerHooks.onServerStopped());
        // 26.1-CHECK: itemgroup.v1.ItemGroupEvents was replaced by creativetab.v1.CreativeModeTabEvents in this Fabric API
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(output -> output.accept(ModContent.SHRINE_CORE_ITEM));
    }
}
```

## neoforge\src\main\java\com\example\companionmod\neoforge\CompanionModNeoForge.java

```java
package com.example.companionmod.neoforge;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.entity.CompanionEntity;
import com.example.companionmod.net.CommandHandler;
import com.example.companionmod.net.CommandPayload;
import com.example.companionmod.registry.ModContent;
import com.example.companionmod.world.ModCommands;
import com.example.companionmod.world.ServerHooks;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** NeoForge entrypoint. Mirrors CompanionModFabric one-to-one; the real logic lives in common/. */
@Mod(CompanionMod.MOD_ID)
public class CompanionModNeoForge {
    public CompanionModNeoForge(IEventBus modBus) {
        CompanionMod.setConfigDir(FMLPaths.CONFIGDIR.get());

        modBus.addListener(RegisterEvent.class, e -> {
            e.register(Registries.BLOCK, h -> h.register(ModContent.blockId(), ModContent.SHRINE_CORE));
            e.register(Registries.ITEM, h -> h.register(ModContent.itemId(), ModContent.SHRINE_CORE_ITEM));
            e.register(Registries.ENTITY_TYPE, h -> h.register(ModContent.entityId(), ModContent.COMPANION));
            e.register(Registries.MENU, h -> h.register(ModContent.menuId(), ModContent.COMPANION_INV_MENU));
        });
        modBus.addListener(EntityAttributeCreationEvent.class, e -> e.put(ModContent.COMPANION, CompanionEntity.createAttributes().build()));
        modBus.addListener(RegisterPayloadHandlersEvent.class, e ->
                e.registrar("1").playToServer(CommandPayload.TYPE, CommandPayload.CODEC,
                        (payload, ctx) -> CommandHandler.handle((ServerPlayer) ctx.player(), payload)));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, e -> {
            if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(ModContent.SHRINE_CORE_ITEM);
        });

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> ModCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer sp) ServerHooks.onPlayerJoin(sp);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer sp) ServerHooks.onPlayerLeave(sp);
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> ServerHooks.onServerStopped());
    }
}
```

## neoforge\src\main\java\com\example\companionmod\neoforge\CompanionModNeoForgeClient.java

```java
package com.example.companionmod.neoforge;

import com.example.companionmod.CompanionMod;
import com.example.companionmod.client.ClientVoice;
import com.example.companionmod.client.CompanionInventoryScreen;
import com.example.companionmod.client.CompanionRenderer;
import com.example.companionmod.registry.ModContent;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Replaces the old static @EventBusSubscriber client class. Differences from the first draft:
 *  - the mic key is a real, rebindable KeyMapping (was raw GLFW_KEY_V);
 *  - it is ignored while any screen is open (the old version fired while typing a "v" in chat);
 *  - all logic lives in common/ClientVoice so Fabric behaves identically.
 * 26.1-CHECK: event/class names (RegisterGuiLayersEvent, ClientPacketDistributor) against the NeoForge 26.1 primer.
 */
@Mod(value = CompanionMod.MOD_ID, dist = Dist.CLIENT)
public class CompanionModNeoForgeClient {
    public CompanionModNeoForgeClient(IEventBus modBus) {
        ClientVoice.setSender(ClientPacketDistributor::sendToServer);
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.register(ClientVoice.talkKey());
            e.register(ClientVoice.radialKey());
            e.register(ClientVoice.settingsKey());
        });
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, e -> e.registerEntityRenderer(ModContent.COMPANION, CompanionRenderer::new));
        modBus.addListener(RegisterMenuScreensEvent.class, e ->
                e.register(ModContent.COMPANION_INV_MENU, CompanionInventoryScreen::new));
        modBus.addListener(RegisterGuiLayersEvent.class, e ->
                e.registerAboveAll(CompanionMod.id("voice_hud"), (graphics, deltaTracker) -> ClientVoice.renderHud(graphics, deltaTracker)));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> ClientVoice.tick(Minecraft.getInstance()));
    }
}
```

