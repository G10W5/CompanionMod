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
