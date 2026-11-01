package com.example.companionmod.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WavUtil {
    private WavUtil() {}

    /** Result of decoding a WAV: raw 16-bit little-endian PCM plus its format. */
    public record Pcm(byte[] data, int sampleRate, int channels) {}

    public static byte[] pcm16ToWav(byte[] pcm, int sampleRate, int channels) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
         .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.writeBytes(h.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    /**
     * Decode a 16-bit PCM WAV. Handles streamed WAVs whose size fields are 0xFFFFFFFF (Ollama's speech endpoint does this):
     * the data chunk is then taken to run to the end of the buffer. Throws on anything that is not 16-bit PCM.
     */
    public static Pcm decode(byte[] wav) {
        if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F')
            throw new IllegalArgumentException("not a RIFF/WAV file");
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, rate = 24000, ch = 1, bits = 16;
        while (pos + 8 <= wav.length) {
            String id = new String(wav, pos, 4);
            long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if (id.equals("fmt ")) {
                int fmt = b.getShort(body) & 0xFFFF;
                ch = b.getShort(body + 2);
                rate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
                if (fmt != 1 && fmt != 0xFFFE) throw new IllegalArgumentException("unsupported WAV format " + fmt);
            } else if (id.equals("data")) {
                if (bits != 16) throw new IllegalArgumentException("only 16-bit WAV supported");
                long avail = wav.length - body;
                int len = (int) Math.min(size, avail);
                byte[] pcm = new byte[len - (len % (2 * Math.max(ch, 1)))];
                System.arraycopy(wav, body, pcm, 0, pcm.length);
                return new Pcm(pcm, rate, ch);
            }
            pos = (int) Math.min((long) body + size + (size & 1), Integer.MAX_VALUE - 16);
        }
        throw new IllegalArgumentException("no data chunk");
    }
}
