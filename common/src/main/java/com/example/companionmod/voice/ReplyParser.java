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
