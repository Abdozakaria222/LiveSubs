package com.abdo.livesubs;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Captures the device's playback audio (or the mic) and translates it to Arabic subtitles.
 * Engines: 0 = Gemini, 1 = offline (Vosk + ML Kit, unlimited), 2 = Gemini with automatic
 * switch to offline when Gemini hits its limit or the internet drops.
 */
public class TranslatorService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_USE_MIC = "use_mic";
    public static final String ACTION_STOP = "com.abdo.livesubs.STOP";
    public static final int ENGINE_GEMINI = 0, ENGINE_OFFLINE = 1, ENGINE_AUTO = 2;
    public static volatile boolean running = false;

    private static final String CHANNEL = "livesubs";
    private static final int SR = 16000;                // 16 kHz mono 16-bit
    private static final int FRAME_BYTES = SR * 2 / 10; // 100 ms
    private static final long FALLBACK_MS = 90_000;     // how long auto mode stays offline

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<byte[]> geminiQ = new LinkedBlockingQueue<>(3);
    private final LinkedBlockingQueue<byte[]> voskQ = new LinkedBlockingQueue<>(300);
    private volatile boolean active = false;
    private volatile long fallbackUntil = 0;
    private int engine = ENGINE_AUTO;
    private Object projection; // MediaProjection on Android 10+
    private AudioRecord recorder;
    private SubtitleOverlay overlay;
    private OfflineEngine offline;
    private Thread captureThread, geminiThread, offlineThread;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (active) return START_NOT_STICKY;

        boolean useMic = intent.getBooleanExtra(EXTRA_USE_MIC, false) || Build.VERSION.SDK_INT < 29;
        startInForeground(useMic);

        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        engine = p.getInt("engine", ENGINE_AUTO);
        String key = p.getString("key", "");
        String model = p.getString("model", MainActivity.DEFAULT_MODEL);
        String lang = p.getString("lang", "en");
        int maxSec = clamp(p.getInt("maxSec", 6), 3, 15);
        float font = clamp(p.getInt("font", 26), 14, 60);
        int margin = clamp(p.getInt("margin", 50), 0, 400);

        try {
            recorder = useMic ? buildMicRecorder() : buildPlaybackRecorder(intent);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord init failed");
        } catch (Exception e) {
            toast("تعذّر التقاط الصوت: " + e.getMessage()
                    + (useMic ? "" : "\nجرّب وضع الميكروفون من الإعدادات."));
            stopSelf();
            return START_NOT_STICKY;
        }

        overlay = new SubtitleOverlay(this, font, margin);
        overlay.show("🎧 جاري الاستماع والترجمة…", 3000);

        active = true;
        running = true;
        captureThread = new Thread(() -> captureLoop(maxSec), "capture");
        captureThread.start();
        if (engine != ENGINE_OFFLINE) {
            GeminiClient gemini = new GeminiClient(key, model);
            geminiThread = new Thread(() -> geminiLoop(gemini), "gemini");
            geminiThread.start();
        }
        if (engine != ENGINE_GEMINI) {
            offline = new OfflineEngine(lang);
            offlineThread = new Thread(() -> offlineLoop(maxSec), "offline");
            offlineThread.start();
        }
        return START_NOT_STICKY;
    }

    private boolean offlineNow() {
        if (engine == ENGINE_OFFLINE) return true;
        return engine == ENGINE_AUTO && offline != null && offline.isReady()
                && System.currentTimeMillis() < fallbackUntil;
    }

    // ---------- audio sources ----------

    private AudioFormat format() {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
    }

    private int bufSize() {
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return Math.max(min, FRAME_BYTES) * 4;
    }

    private AudioRecord buildPlaybackRecorder(Intent intent) {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Android 10+ only");
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("RECORD_AUDIO");
        PlaybackCapture.Result r = PlaybackCapture.create(this, intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                intent.getParcelableExtra(EXTRA_DATA), format(), bufSize(), main, this::stopSelf);
        projection = r.projection;
        return r.recorder;
    }

    private AudioRecord buildMicRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("RECORD_AUDIO");
        return new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize());
    }

    // ---------- capture: routes audio to Gemini (as sentence clips) or to Vosk (as a stream) ----------

    private void captureLoop(int maxSec) {
        final int minBytes = SR * 2 * 2;            // at least 2 s per clip
        final int maxBytes = SR * 2 * maxSec;       // hard cut
        final double speechRms = 350;               // above this = sound worth sending
        final int pauseFrames = 4;                  // 400 ms quiet ends a sentence

        byte[] frame = new byte[FRAME_BYTES];
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        int quiet = 0, loudFrames = 0;

        try { recorder.startRecording(); } catch (Exception e) {
            toast("فشل بدء التسجيل: " + e.getMessage());
            main.post(this::stopSelf);
            return;
        }

        while (active) {
            int n = recorder.read(frame, 0, frame.length);
            if (n <= 0) continue;

            if (offlineNow()) {
                voskQ.offer(Arrays.copyOf(frame, n)); // drops audio only if Vosk is far behind
                seg.reset();
                quiet = 0;
                loudFrames = 0;
                continue;
            }
            if (engine == ENGINE_OFFLINE) continue;

            boolean loud = rms(frame, n) > speechRms;
            if (seg.size() == 0 && !loud) continue; // wait for sound to begin
            seg.write(frame, 0, n);
            if (loud) { loudFrames++; quiet = 0; } else { quiet++; }

            boolean pauseCut = seg.size() >= minBytes && quiet >= pauseFrames;
            boolean hardCut = seg.size() >= maxBytes;
            if (pauseCut || hardCut) {
                if (loudFrames >= 5) enqueueGemini(seg.toByteArray());
                seg.reset();
                quiet = 0;
                loudFrames = 0;
            }
        }
    }

    private void enqueueGemini(byte[] pcm) {
        // keep latency low: if Gemini falls behind, drop the oldest clip
        if (!geminiQ.offer(pcm)) { geminiQ.poll(); geminiQ.offer(pcm); }
    }

    private static double rms(byte[] b, int n) {
        long sum = 0;
        int samples = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (long) s * s;
        }
        return samples == 0 ? 0 : Math.sqrt((double) sum / samples);
    }

    // ---------- Gemini ----------

    private void geminiLoop(GeminiClient gemini) {
        String last = "";
        while (active) {
            byte[] pcm;
            try { pcm = geminiQ.poll(1, TimeUnit.SECONDS); } catch (InterruptedException e) { break; }
            if (pcm == null) continue;
            try {
                String t = gemini.translate(GeminiClient.toWav(pcm, SR), last);
                if (t != null && !t.equals(last)) {
                    last = t;
                    showSub(t);
                }
            } catch (GeminiClient.RateLimitException e) {
                geminiQ.clear();
                if (switchToOffline("⏳ خلص حد Gemini المجاني… تحويل للأوفلاين")) continue;
                info("⏳ تم تجاوز حد الطلبات المجاني… انتظار", 4000);
                sleep(15000);
                geminiQ.clear();
            } catch (Exception e) {
                if (switchToOffline("📴 Gemini مش متاح… تحويل للأوفلاين")) continue;
                info("⚠️ " + e.getMessage(), 5000);
                sleep(3000);
            }
        }
    }

    /** In auto mode, hands over to the offline engine for a while. */
    private boolean switchToOffline(String msg) {
        if (engine != ENGINE_AUTO || offline == null) return false;
        if (!offline.isReady()) {
            info("⏳ الموديل الأوفلاين لسه بيتجهّز…", 4000);
            sleep(10000);
            return true;
        }
        fallbackUntil = System.currentTimeMillis() + FALLBACK_MS;
        info(msg, 3000);
        return true;
    }

    // ---------- offline (Vosk + ML Kit) ----------

    private void offlineLoop(int maxSec) {
        boolean loud = engine == ENGINE_OFFLINE; // in auto mode, prepare quietly in the background
        try {
            offline.prepare(this, msg -> { if (loud) info(msg, 120000); });
            if (loud) info("✅ الأوفلاين جاهز… جاري الاستماع", 3000);
        } catch (Exception e) {
            info("⚠️ فشل تجهيز الأوفلاين: " + e.getMessage(), 8000);
            offline.close();
            return;
        }

        final int maxBytes = SR * 2 * Math.max(maxSec, 6);
        int since = 0;
        String last = "";
        try {
            while (active) {
                byte[] f;
                try { f = voskQ.poll(1, TimeUnit.SECONDS); } catch (InterruptedException e) { break; }
                if (f == null) continue;
                String s = offline.feed(f, f.length);
                since += f.length;
                if (s == null && since > maxBytes) s = offline.flush();
                if (s == null) continue;
                since = 0;
                if (s.isEmpty()) continue;
                try {
                    String t = offline.translate(s);
                    if (t != null && !t.trim().isEmpty() && !t.equals(last)) {
                        last = t.trim();
                        showSub(last);
                    }
                } catch (Exception e) {
                    info("⚠️ ترجمة: " + e.getMessage(), 3000);
                }
            }
        } finally {
            offline.close();
        }
    }

    // ---------- UI helpers ----------

    private void showSub(String t) {
        info(t, Math.max(3500, Math.min(9000, t.length() * 120L)));
    }

    /** Shows text on the overlay, or in the floating PiP window if overlays aren't allowed. */
    private void info(String t, long ms) {
        main.post(() -> {
            if (overlay != null && overlay.isAttached()) overlay.show(t, ms);
            else SubtitleBus.post(t, ms);
        });
    }

    private void startInForeground(boolean useMic) {
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "الترجمة المباشرة", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        PendingIntent stopPi = PendingIntent.getService(this, 1,
                new Intent(this, TranslatorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = nb
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("مترجم القنوات يعمل")
                .setContentText("الترجمة الفورية مفعّلة")
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "إيقاف", stopPi).build())
                .setOngoing(true)
                .build();

        if (!useMic && Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(7, n);
        }
    }

    @Override
    public void onDestroy() {
        active = false;
        running = false;
        if (captureThread != null) captureThread.interrupt();
        if (geminiThread != null) geminiThread.interrupt();
        if (offlineThread != null) offlineThread.interrupt(); // closes the engine in its finally
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
        if (projection != null) { ((MediaProjection) projection).stop(); projection = null; }
        if (overlay != null) { overlay.remove(); overlay = null; }
        SubtitleBus.close();
        super.onDestroy();
    }

    private void toast(String m) { main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
