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
          .append("build (arg = a short description such as \"stone tower\", or a schematic file name); say (arg = one short in-character sentence); wait (seconds).\n")
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
