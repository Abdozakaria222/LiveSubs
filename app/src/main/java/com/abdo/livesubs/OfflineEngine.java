package com.abdo.livesubs;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unlimited, on-device engine: Vosk (speech → text) + Google ML Kit (text → Arabic).
 * Needs the internet only once, to download the models.
 */
public class OfflineEngine {

    public interface Progress { void on(String msg); }

    /** code (= ML Kit language), Arabic label, Vosk small model name */
    public static final String[][] LANGS = {
            {"en", "إنجليزي", "vosk-model-small-en-us-0.15"},
            {"tr", "تركي", "vosk-model-small-tr-0.3"},
            {"ko", "كوري", "vosk-model-small-ko-0.22"},
            {"hi", "هندي", "vosk-model-small-hi-0.22"},
            {"es", "إسباني", "vosk-model-small-es-0.42"},
            {"fr", "فرنساوي", "vosk-model-small-fr-0.22"},
            {"de", "ألماني", "vosk-model-small-de-0.15"},
            {"ru", "روسي", "vosk-model-small-ru-0.22"},
            {"it", "إيطالي", "vosk-model-small-it-0.22"},
            {"pt", "برتغالي", "vosk-model-small-pt-0.3"},
            {"ja", "ياباني", "vosk-model-small-ja-0.22"},
            {"zh", "صيني", "vosk-model-small-cn-0.22"},
    };

    public static String[] labels() {
        String[] l = new String[LANGS.length];
        for (int i = 0; i < LANGS.length; i++) l[i] = LANGS[i][1];
        return l;
    }

    public static int indexOf(String code) {
        for (int i = 0; i < LANGS.length; i++) if (LANGS[i][0].equals(code)) return i;
        return 0;
    }

    private static String[] lang(String code) { return LANGS[indexOf(code)]; }

    private static File modelDir(Context c, String code) {
        return new File(new File(c.getFilesDir(), "vosk"), lang(code)[2]);
    }

    public static boolean isDownloaded(Context c, String code) {
        return new File(modelDir(c, code), ".ok").exists();
    }

    private final String code;
    private Model model;
    private Recognizer rec;
    private Translator translator;
    private volatile boolean ready = false;

    public OfflineEngine(String code) { this.code = code; }

    public boolean isReady() { return ready; }

    /** Downloads (first time only) and loads both models. Call off the main thread. */
    public synchronized void prepare(Context ctx, Progress p) throws Exception {
        if (ready) return;
        File dir = ensureVoskModel(ctx, p);
        p.on("⏳ تحميل موديل التعرّف على الكلام…");
        model = new Model(dir.getAbsolutePath());
        rec = new Recognizer(model, 16000f);

        p.on("⏳ تجهيز موديل الترجمة (" + lang(code)[1] + " ← عربي)…");
        TranslatorOptions opts = new TranslatorOptions.Builder()
                .setSourceLanguage(code)
                .setTargetLanguage(TranslateLanguage.ARABIC)
                .build();
        translator = Translation.getClient(opts);
        Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()),
                10, TimeUnit.MINUTES);
        ready = true;
    }

    private File ensureVoskModel(Context ctx, Progress p) throws IOException {
        File dir = modelDir(ctx, code);
        if (new File(dir, ".ok").exists()) return dir;

        String name = lang(code)[2];
        File root = dir.getParentFile();
        root.mkdirs();
        File zip = new File(root, name + ".zip");
        URL url = new URL("https://alphacephei.com/vosk/models/" + name + ".zip");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        int total = c.getContentLength();
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new FileOutputStream(zip)) {
            byte[] b = new byte[64 * 1024];
            long done = 0;
            int n, lastPct = -1;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct;
                        p.on("⬇️ تنزيل موديل " + lang(code)[1] + ": " + pct + "%");
                    }
                }
            }
        } finally {
            c.disconnect();
        }

        p.on("📦 فك ضغط الموديل…");
        deleteRec(dir);
        dir.mkdirs();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            byte[] b = new byte[64 * 1024];
            String base = dir.getCanonicalPath();
            while ((e = zin.getNextEntry()) != null) {
                String path = e.getName();
                int slash = path.indexOf('/');
                if (slash < 0) continue;                // skip anything outside the top folder
                path = path.substring(slash + 1);       // strip top folder
                if (path.isEmpty()) continue;
                File f = new File(dir, path);
                if (!f.getCanonicalPath().startsWith(base)) continue; // zip-slip guard
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = zin.read(b)) > 0) out.write(b, 0, n);
                }
            }
        }
        zip.delete();
        new File(dir, ".ok").createNewFile();
        return dir;
    }

    private static void deleteRec(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    /** Feeds 16 kHz mono PCM. Returns a finished sentence, or null while still listening. */
    public String feed(byte[] pcm, int len) {
        if (rec.acceptWaveForm(pcm, len)) return text(rec.getResult(), "text");
        return null;
    }

    /** Forces the current sentence to end (used when someone talks for a long time). */
    public String flush() { return text(rec.getFinalResult(), "text"); }

    private String text(String json, String field) {
        try {
            String t = new JSONObject(json).optString(field, "").trim();
            if (code.equals("ja") || code.equals("zh")) t = t.replace(" ", "");
            return t;
        } catch (Exception e) {
            return "";
        }
    }

    public String translate(String s) throws Exception {
        return Tasks.await(translator.translate(s), 10, TimeUnit.SECONDS);
    }

    public synchronized void close() {
        ready = false;
        if (translator != null) { translator.close(); translator = null; }
        if (rec != null) { rec.close(); rec = null; }
        if (model != null) { model.close(); model = null; }
    }
}
