package com.example.companionmod.voice;

import com.example.companionmod.build.BuildSpecSchema;
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
 * Asks the local model for a BuildSpec (a few small, bounded choices; never coordinates). Mirrors PlanningBackend:
 * same host/model/timeouts, OpenAI-compatible endpoint, blocking (call from a worker thread).
 * Tries strict JSON-schema output first, then plain json_object, then no format; SpecParser copes with anything.
 */
public final class BuildSpecBackend {
    private static final class HttpStatus extends IOException {
        final int status;
        HttpStatus(int status, String body) { super("Ollama HTTP " + status + ": " + (body.length() > 120 ? body.substring(0, 120) : body)); this.status = status; }
    }

    private enum Mode { SCHEMA, JSON_OBJECT, PLAIN }

    private final VoiceConfig cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public BuildSpecBackend(VoiceConfig cfg) { this.cfg = cfg; }

    /** @return raw model text (ideally one JSON object) */
    public String design(String request) throws IOException {
        IOException last = null;
        for (Mode m : Mode.values()) {
            try {
                return post(request, m);
            } catch (HttpStatus e) {
                last = e;
                if (e.status != 400 && e.status != 422) throw e;      // only "format not supported" is worth retrying
            }
        }
        throw last == null ? new IOException("no reply") : last;
    }

    private String post(String request, Mode mode) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.chatModel);
        body.addProperty("stream", false);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", 400);
        JsonArray msgs = new JsonArray();
        msgs.add(msg("system", BuildSpecSchema.SYSTEM_PROMPT));
        msgs.add(msg("user", request));
        body.add("messages", msgs);
        if (mode == Mode.SCHEMA) {
            JsonObject js = new JsonObject();
            js.addProperty("name", "build_spec");
            js.addProperty("strict", true);
            js.add("schema", JsonParser.parseString(BuildSpecSchema.SCHEMA));
            JsonObject rf = new JsonObject();
            rf.addProperty("type", "json_schema");
            rf.add("json_schema", js);
            body.add("response_format", rf);
        } else if (mode == Mode.JSON_OBJECT) {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (RuntimeException e) {
            throw new IOException("Unexpected reply from Ollama");
        }
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
