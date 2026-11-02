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
             + "Reply with ONE JSON object and nothing else:\n"
             + "{\"heard\":\"<what the player said>\",\"say\":\"<your short in-character reply>\","
             + "\"action\":\"<action>\",\"target\":\"<target or empty>\",\"memory\":\"<one fact to remember or empty>\"}\n"
             + "action must be exactly one of: follow, stay, sit, stand, attack, guard, defensive, passive, come, stop, continue (resume interrupted work), "
             + "inventory (only when the player asks to SEE your bag), "
             + "pickup (walk to a dropped item ON THE GROUND and store it; put the item name in target, e.g. bone, diamond, rotten_flesh - only for items already lying around), "
             + "seek_block (walk to the named block and mine it; target = a vanilla block id: oak_log or log for wood/trees, stone, cobblestone, dirt, coal_ore, iron_ore, gold_ore, redstone_ore, lapis_ore, emerald_ore, diamond_ore, copper_ore, ancient_debris; 'wood', 'logs' and 'planks' mean the whole family), "
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
             + "'pick up this bone'/'grab that diamond' -> pickup, target bone/diamond. "
             + "'call yourself Alex' -> set_name, target Alex. "
             + "'build the house' -> build, target house. "
             + "Leave target empty for toggle commands.\n"
             + "Your bag has 4 armor slots (auto-worn so they protect you), 4 tool slots (you automatically hold the best tool for the block you are mining) "
             + "and 18 storage slots; pickup/store puts items where they belong and you can also be given items via the "
             + "inventory UI. When you are downed the player must hold left click on you for 3 seconds to revive you. "
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
