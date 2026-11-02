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
