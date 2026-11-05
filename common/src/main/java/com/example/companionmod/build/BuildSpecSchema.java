package com.example.companionmod.build;

import java.util.List;

/**
 * Constrained-output schema + system prompt for the local model. Send SCHEMA as response_format.json_schema.schema on
 * /v1/chat/completions (what BuildSpecBackend does), or as "format" on Ollama's native /api/chat, so the model
 * physically cannot emit anything outside these enums/ranges.
 */
public final class BuildSpecSchema {
    private BuildSpecSchema() {}

    private static String enumOf(List<String> v) {
        StringBuilder sb = new StringBuilder("{\"type\":\"string\",\"enum\":[");
        for (int i = 0; i < v.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(v.get(i)).append('"');
        return sb.append("]}").toString();
    }

    private static String integer(int lo, int hi) {
        return "{\"type\":\"integer\",\"minimum\":" + lo + ",\"maximum\":" + hi + "}";
    }

    public static final String SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"type\",\"name\",\"width\",\"depth\",\"height\",\"floors\",\"roof\",\"shape\",\"materials\"],"
            + "\"properties\":{"
            + "\"type\":" + enumOf(List.of("house", "tower")) + ","
            + "\"name\":{\"type\":\"string\",\"maxLength\":40},"
            + "\"width\":" + integer(5, 25) + ","
            + "\"depth\":" + integer(5, 25) + ","
            + "\"height\":" + integer(10, 40) + ","
            + "\"floors\":" + integer(1, 4) + ","
            + "\"roof\":" + enumOf(List.of("gable", "flat")) + ","
            + "\"shape\":" + enumOf(List.of("square", "round")) + ","
            + "\"materials\":{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"wall\",\"floor\",\"roof\",\"trim\",\"window\",\"foundation\",\"door\",\"light\"],"
            + "\"properties\":{"
            + "\"wall\":" + enumOf(Materials.WALL) + ","
            + "\"floor\":" + enumOf(Materials.FLOOR) + ","
            + "\"roof\":" + enumOf(Materials.ROOF) + ","
            + "\"trim\":" + enumOf(Materials.TRIM) + ","
            + "\"window\":" + enumOf(Materials.WINDOW) + ","
            + "\"foundation\":" + enumOf(Materials.WALL) + ","
            + "\"door\":" + enumOf(Materials.DOOR) + ","
            + "\"light\":" + enumOf(Materials.LIGHT)
            + "}}}}";

    public static final String SYSTEM_PROMPT = """
            You design Minecraft buildings. Reply with ONE JSON object and nothing else.
            You only choose a building type, a size and materials. You never place blocks or give coordinates.
            Fields:
            - type: "house" or "tower".
            - width/depth: footprint in blocks (house 7-15 is normal). For a tower, width is the diameter (odd, 5-13).
            - height: tower height in blocks (14-32). Ignored for houses.
            - floors: storeys of a house (1-3). Use 1 for towers.
            - roof: "gable" (pointed) or "flat". shape: "round" only for towers, otherwise "square".
            - materials: wall, floor, roof, trim, window, foundation, door, light. Pick colours that suit the style.
            Examples:
            User: a small wooden cabin
            {"type":"house","name":"cabin","width":7,"depth":7,"height":10,"floors":1,"roof":"gable","shape":"square","materials":{"wall":"spruce_planks","floor":"oak_planks","roof":"dark_oak_planks","trim":"spruce_log","window":"glass_pane","foundation":"cobblestone","door":"spruce_door","light":"lantern"}}
            User: a tall round stone watchtower
            {"type":"tower","name":"watchtower","width":9,"depth":9,"height":28,"floors":1,"roof":"flat","shape":"round","materials":{"wall":"stone_bricks","floor":"oak_planks","roof":"stone_bricks","trim":"dark_oak_log","window":"iron_bars","foundation":"cobblestone","door":"oak_door","light":"lantern"}}
            User: a two story brick house with a spruce roof
            {"type":"house","name":"brick house","width":11,"depth":9,"height":16,"floors":2,"roof":"gable","shape":"square","materials":{"wall":"bricks","floor":"oak_planks","roof":"spruce_planks","trim":"stripped_oak_log","window":"glass_pane","foundation":"stone_bricks","door":"oak_door","light":"lantern"}}
            """;
}
