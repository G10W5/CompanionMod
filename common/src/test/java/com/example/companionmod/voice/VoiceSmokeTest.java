package com.example.companionmod.voice;

import com.example.companionmod.command.CompanionCommand;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Plain-main smoke test (no JUnit needed): java -cp out:gson.jar ...VoiceSmokeTest */
public class VoiceSmokeTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // --- WAV roundtrip + streamed-header WAV (size fields 0xFFFFFFFF like Ollama's speech endpoint)
        byte[] pcm = new byte[4800];
        for (int i = 0; i < 2400; i++) { short s = (short) (Math.sin(i / 10.0) * 12000); pcm[2*i] = (byte) s; pcm[2*i+1] = (byte) (s >> 8); }
        WavUtil.Pcm back = WavUtil.decode(WavUtil.pcm16ToWav(pcm, 16000, 1));
        check(back.data().length == 4800 && back.sampleRate() == 16000 && back.channels() == 1, "wav roundtrip");
        byte[] streamed = WavUtil.pcm16ToWav(pcm, 24000, 1);
        ByteBuffer sb = ByteBuffer.wrap(streamed).order(ByteOrder.LITTLE_ENDIAN);
        sb.putInt(4, -1); sb.putInt(40, -1);
        WavUtil.Pcm sp = WavUtil.decode(streamed);
        check(sp.data().length == 4800 && sp.sampleRate() == 24000, "streamed-header wav (0xFFFFFFFF sizes)");

        // --- level meter: silence ~0, loud tone high, release is slower than attack
        LevelMeter m = new LevelMeter();
        m.feed(new byte[640], 640);
        check(m.level() < 0.01f, "meter silence ~0");
        byte[] loud = new byte[640];
        for (int i = 0; i < 320; i++) { short s = (short) (Math.sin(i / 3.0) * 20000); loud[2*i] = (byte) s; loud[2*i+1] = (byte) (s >> 8); }
        for (int i = 0; i < 6; i++) m.feed(loud, 640);
        check(m.level() > 0.7f && m.maxSeen() > 0.8f, "meter loud tone high (level=" + m.level() + ")");
        float hi = m.level(); m.feed(new byte[640], 640);
        check(m.level() > hi * 0.6f, "meter slow release");

        // --- parser
        VoiceReply r = ReplyParser.parse("```json\n{\"heard\":\"follow me\",\"say\":\"On my way!\",\"action\":\"Follow\"}\n```");
        check(r.command() == CompanionCommand.FOLLOW && r.say().equals("On my way!") && r.heard().equals("follow me"), "parser fenced json");
        check(ReplyParser.parse("Sure thing, boss").command() == CompanionCommand.NONE, "parser plain text -> NONE");
        check(CompanionCommand.parse("stand down") == CompanionCommand.PASSIVE && CompanionCommand.parse("come here") == CompanionCommand.COME, "command synonyms");

        // --- fake Ollama
        final String[] lastChatBody = {null};
        final int[] chatCalls = {0};
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/api/version", x -> send(x, 200, "{\"version\":\"0.41.0\"}"));
        s.createContext("/api/show", x -> {
            JsonObject req = JsonParser.parseString(new String(x.getRequestBody().readAllBytes())).getAsJsonObject();
            String model = req.get("model").getAsString();
            if (model.startsWith("lfm3-audio")) send(x, 200, "{\"capabilities\":[\"tools\",\"audio\"]}");
            else if (model.equals("phonon")) send(x, 200, "{\"capabilities\":[\"speech\"]}");
            else send(x, 404, "{\"error\":\"not found\"}");
        });
        s.createContext("/v1/chat/completions", x -> {
            lastChatBody[0] = new String(x.getRequestBody().readAllBytes());
            chatCalls[0]++;
            if (chatCalls[0] == 1) { send(x, 400, "{\"error\":\"response_format unsupported with audio\"}"); return; } // exercise retry
            send(x, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"heard\\\":\\\"sit down\\\",\\\"say\\\":\\\"Resting.\\\",\\\"action\\\":\\\"sit\\\"}\"}}]}");
        });
        s.createContext("/v1/audio/speech", x -> {
            x.getRequestBody().readAllBytes();
            byte[] w = WavUtil.pcm16ToWav(new byte[2400], 24000, 1);
            x.sendResponseHeaders(200, w.length); x.getResponseBody().write(w); x.close();
        });
        s.start();
        String host = "http://127.0.0.1:" + s.getAddress().getPort();

        VoiceConfig cfg = new VoiceConfig();
        cfg.host = host;
        OllamaVoiceBackend be = new OllamaVoiceBackend(cfg);
        OllamaVoiceBackend.Probe p = be.probe();
        check(p.reachable() && p.chatModelPresent() && p.chatHasAudio(), "probe: lfm3-audio found with audio capability");
        check("phonon".equals(p.speechModel()), "probe: falls back to phonon because lfm3-audio has no 'speech' cap (" + p.message() + ")");

        VoiceReply vr = be.converse(WavUtil.pcm16ToWav(pcm, 16000, 1), "mode=FOLLOW");
        check(vr.command() == CompanionCommand.SIT && vr.heard().equals("sit down"), "converse parsed JSON reply after 400->retry");
        JsonObject sent = JsonParser.parseString(lastChatBody[0]).getAsJsonObject();
        check(!sent.has("response_format"), "retry dropped response_format");
        String part = sent.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString();
        check(part.equals("input_audio"), "request carries input_audio part");
        check(sent.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("mode=FOLLOW"), "context injected in system prompt");

        byte[] wav = be.synthesize("Resting.");
        check(wav != null && WavUtil.decode(wav).sampleRate() == 24000, "synthesize returns decodable wav");

        // missing model path
        cfg.chatModel = "nope";
        check(!new OllamaVoiceBackend(cfg).probe().chatModelPresent(), "probe: missing model reported");
        s.stop(0);
        cfg.host = "http://127.0.0.1:1";
        check(!new OllamaVoiceBackend(cfg).probe().reachable(), "probe: unreachable host reported");

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void send(com.sun.net.httpserver.HttpExchange x, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(code, b.length);
        x.getResponseBody().write(b);
        x.close();
    }
}
