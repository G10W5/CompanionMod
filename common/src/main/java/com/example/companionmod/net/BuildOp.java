package com.example.companionmod.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Build requests ride on the EXISTING CommandPayload (action = ACTION, target = a small JSON object), so no new
 * packet type has to be registered in either loader. The server intercepts ACTION in CommandHandler.
 */
public final class BuildOp {
    private BuildOp() {}

    public static final String ACTION = "build_op";

    /** @param specJson output of SpecParser.toJson (about 300-400 chars) */
    public static String spec(String specJson) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "spec");
        o.add("spec", JsonParser.parseString(specJson));
        return o.toString();
    }

    public static String file(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "file");
        o.addProperty("name", name);
        return o.toString();
    }
}
