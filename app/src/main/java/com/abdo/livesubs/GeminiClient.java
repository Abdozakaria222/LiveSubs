package com.abdo.livesubs;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends a short WAV clip to Gemini and gets back an Arabic subtitle line. */
public class GeminiClient {

    public static class RateLimitException extends IOException {
        public RateLimitException(String m) { super(m); }
    }

    private final String apiKey;
    private final String model;

    public GeminiClient(String apiKey, String model) {
        this.apiKey = apiKey.trim();
        this.model = model.trim();
    }

    private static final String PROMPT =
            "You are a live subtitle translator for TV. The audio is a short clip from a movie or TV series "
          + "(any language: English, Turkish, Korean, Hindi, Spanish, etc.).\n"
          + "Task: understand the spoken dialogue and translate it into short, natural Arabic subtitles.\n"
          + "Rules:\n"
          + "- Output ONLY the Arabic subtitle text, at most 2 short lines. No quotes, no notes, no transliteration.\n"
          + "- If the dialogue is already in Arabic, write it as spoken.\n"
          + "- If there is no clear speech (only music, noise, silence or singing you cannot make out), output exactly: -\n"
          + "- Do not repeat the previous subtitle; it is given only for context.\n";

    public String translate(byte[] wav, String previous) throws IOException {
        try {
            String prompt = PROMPT + (previous == null || previous.isEmpty()
                    ? "" : "Previous subtitle (context only): " + previous + "\n");

            JSONArray parts = new JSONArray()
                    .put(new JSONObject().put("text", prompt))
                    .put(new JSONObject().put("inline_data", new JSONObject()
                            .put("mime_type", "audio/wav")
                            .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))));

            JSONObject gen = new JSONObject().put("temperature", 0.2).put("maxOutputTokens", 512);
            String m = model.toLowerCase();
            if (m.contains("2.5") && m.contains("flash") && !m.contains("lite")) {
                // turn off "thinking" so subtitles come back fast
                gen.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
            }

            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", gen);

            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("x-goog-api-key", apiKey);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();
            if (code == 429) throw new RateLimitException("429");
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (code >= 400) {
                String msg = resp;
                try { msg = new JSONObject(resp).getJSONObject("error").getString("message"); } catch (Exception ignored) {}
                throw new IOException("Gemini " + code + ": " + (msg.length() > 160 ? msg.substring(0, 160) : msg));
            }

            JSONObject json = new JSONObject(resp);
            JSONArray cands = json.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject content = cands.getJSONObject(0).optJSONObject("content");
            if (content == null) return null;
            JSONArray outParts = content.optJSONArray("parts");
            if (outParts == null) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < outParts.length(); i++) {
                JSONObject p = outParts.getJSONObject(i);
                if (p.optBoolean("thought", false)) continue;
                sb.append(p.optString("text", ""));
            }
            String out = sb.toString().trim();
            if (out.isEmpty() || out.equals("-") || out.equals("—")) return null;
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Wraps 16-bit mono PCM into a WAV container. */
    public static byte[] toWav(byte[] pcm, int sampleRate) {
        int dataLen = pcm.length;
        int byteRate = sampleRate * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE");
        put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1);
        le32(h, 24, sampleRate); le32(h, 28, byteRate); le16(h, 32, 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataLen);
        byte[] out = new byte[44 + dataLen];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static void put(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }
    private static void le32(byte[] b, int o, int v) { b[o] = (byte) v; b[o+1] = (byte) (v >> 8); b[o+2] = (byte) (v >> 16); b[o+3] = (byte) (v >> 24); }
    private static void le16(byte[] b, int o, int v) { b[o] = (byte) v; b[o+1] = (byte) (v >> 8); }
}
