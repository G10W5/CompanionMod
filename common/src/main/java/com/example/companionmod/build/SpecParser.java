package com.example.companionmod.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns model output (or, as a fallback, the player's raw sentence) into a BuildSpec. Never throws. */
public final class SpecParser {
    private SpecParser() {}

    /** Preferred entry point: use the model JSON if it parses, otherwise guess from the player's words. */
    public static BuildSpec parseOrHeuristic(String modelOutput, String playerText) {
        Optional<BuildSpec> o = parse(modelOutput);
        return o.orElseGet(() -> heuristic(playerText)).clamped();
    }

    public static Optional<BuildSpec> parse(String raw) {
        if (raw == null) return Optional.empty();
        int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
        if (a < 0 || b <= a) return Optional.empty();
        try {
            JsonElement el = JsonParser.parseString(raw.substring(a, b + 1));
            if (!el.isJsonObject()) return Optional.empty();
            JsonObject o = el.getAsJsonObject();
            String name = str(o, "name", "build");
            String type = str(o, "type", "").toLowerCase();
            if (!type.equals("house") && !type.equals("tower")) type = guessType(name);
            BuildSpec d = BuildSpec.defaults(type);
            Map<String, String> mats = new HashMap<>();
            if (o.has("materials") && o.get("materials").isJsonObject())
                for (var e : o.getAsJsonObject("materials").entrySet())
                    if (e.getValue().isJsonPrimitive()) mats.put(e.getKey().toLowerCase(), e.getValue().getAsString());
            return Optional.of(new BuildSpec(type, name,
                    num(o, "width", d.width()), num(o, "depth", d.depth()), num(o, "height", d.height()),
                    num(o, "floors", d.floors()), str(o, "roof", "gable").toLowerCase(),
                    str(o, "shape", "square").toLowerCase(), mats).clamped());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** Compact JSON for the network (about 300-400 bytes, fits the 1024-char packet field). */
    public static String toJson(BuildSpec spec) {
        JsonObject o = new JsonObject();
        o.addProperty("type", spec.type());
        o.addProperty("name", spec.name());
        o.addProperty("width", spec.width());
        o.addProperty("depth", spec.depth());
        o.addProperty("height", spec.height());
        o.addProperty("floors", spec.floors());
        o.addProperty("roof", spec.roof());
        o.addProperty("shape", spec.shape());
        JsonObject m = new JsonObject();
        for (var e : new TreeMap<>(spec.materials()).entrySet()) m.addProperty(e.getKey(), e.getValue());
        o.add("materials", m);
        return o.toString();
    }

    private static String str(JsonObject o, String k, String def) {
        try { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def; }
        catch (RuntimeException e) { return def; }
    }

    private static int num(JsonObject o, String k, int def) {
        try { return o.has(k) && o.get(k).isJsonPrimitive() ? (int) Math.round(o.get(k).getAsDouble()) : def; }
        catch (RuntimeException e) { return def; }
    }

    private static String guessType(String text) {
        String t = text.toLowerCase();
        return t.matches(".*(tower|keep|turret|lighthouse|spire|minaret).*") ? "tower" : "house";
    }

    // ---- fallback: no model needed -------------------------------------------------

    private static final LinkedHashMap<String, String> WORDS = new LinkedHashMap<>();
    static {
        // longest / most specific phrases first
        WORDS.put("dark oak", "dark_oak_planks"); WORDS.put("stone brick", "stone_bricks");
        WORDS.put("mossy", "mossy_stone_bricks"); WORDS.put("nether brick", "nether_bricks");
        WORDS.put("deepslate", "deepslate_bricks"); WORDS.put("cobble", "cobblestone");
        WORDS.put("sandstone", "sandstone"); WORDS.put("brick", "bricks");
        WORDS.put("spruce", "spruce_planks"); WORDS.put("birch", "birch_planks");
        WORDS.put("jungle", "jungle_planks"); WORDS.put("acacia", "acacia_planks");
        WORDS.put("oak", "oak_planks"); WORDS.put("wood", "oak_planks"); WORDS.put("stone", "stone_bricks");
    }
    private static final String[] NUM_WORDS = {"one", "two", "three", "four"};

    public static BuildSpec heuristic(String text) {
        String t = text == null ? "" : text.toLowerCase();
        String type = guessType(t);
        boolean round = t.contains("round") || t.contains("circular") || t.contains("lighthouse");
        int size = 9, height = type.equals("tower") ? 20 : 16;
        if (t.matches(".*\\b(tiny|small|little|cabin|hut)\\b.*")) { size = 7; height = Math.min(height, 14); }
        if (t.matches(".*\\b(big|large|huge|mansion|manor|grand)\\b.*")) { size = 15; height += 4; }
        if (t.matches(".*\\b(tall|high|towering)\\b.*")) height = 32;
        int floors = 1;
        Matcher fm = Pattern.compile("(\\d|one|two|three|four)[ -]*(story|stories|storey|floor|level)").matcher(t);
        if (fm.find()) {
            String g = fm.group(1);
            floors = Character.isDigit(g.charAt(0)) ? g.charAt(0) - '0' : Arrays.asList(NUM_WORDS).indexOf(g) + 1;
        } else if (t.contains("mansion") || t.contains("manor")) floors = 2;
        String roof = t.contains("flat roof") || t.contains("modern") ? "flat" : "gable";

        Map<String, String> mats = new HashMap<>();
        String roofMat = null, wallMat = null;
        for (var e : WORDS.entrySet()) {
            int i = t.indexOf(e.getKey());
            if (i < 0) continue;
            boolean isRoof = t.substring(Math.min(t.length(), i + e.getKey().length())).matches("^[a-z ]{0,10}roof.*");
            if (isRoof && roofMat == null) roofMat = e.getValue();
            else if (!isRoof && wallMat == null) wallMat = e.getValue();
        }
        if (wallMat != null) mats.put("wall", wallMat);
        if (roofMat != null) mats.put("roof", roofMat);
        return new BuildSpec(type, type, size, Math.max(5, size - 2), height, floors, roof,
                round ? "round" : "square", mats).clamped();
    }
}
