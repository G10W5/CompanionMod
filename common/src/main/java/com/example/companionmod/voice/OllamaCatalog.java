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
