package com.abdo.livesubs;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    public static final String PREFS = "livesubs";
    public static final String DEFAULT_MODEL = "gemini-2.5-flash-lite";
    private static final int REQ_PROJECTION = 10;
    private static final int REQ_PERMS = 11;

    private EditText keyEt, modelEt, maxSecEt, fontEt, marginEt;
    private CheckBox micCb;
    private Spinner engineSp, langSp;
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = (int) (24 * d);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("🎬 مترجم القنوات الفوري");
        title.setTextSize(26);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("بيسمع صوت الفيلم أو المسلسل اللي شغال، ويترجمه للعربي ويعرضه كترجمة فوق الصورة.");
        hint.setTextSize(15);
        hint.setPadding(0, (int) (6 * d), 0, (int) (14 * d));
        root.addView(hint);

        engineSp = spinner(root, "محرك الترجمة", new String[]{
                "Gemini (دقة عالية، فيه حد يومي)",
                "أوفلاين (بلا حدود، دقة أقل)",
                "Gemini + تحويل تلقائي للأوفلاين (مُستحسن)"},
                prefs.getInt("engine", TranslatorService.ENGINE_AUTO));
        langSp = spinner(root, "لغة الفيلم (للأوفلاين فقط)", OfflineEngine.labels(),
                OfflineEngine.indexOf(prefs.getString("lang", "en")));
        Button dl = new Button(this);
        dl.setText("⬇ تنزيل موديل الأوفلاين للغة المختارة");
        dl.setOnClickListener(v -> downloadOffline());
        root.addView(dl);

        keyEt = field(root, "مفتاح Gemini API (من aistudio.google.com)", prefs.getString("key", ""), false);
        modelEt = field(root, "الموديل", prefs.getString("model", DEFAULT_MODEL), false);
        maxSecEt = field(root, "أقصى طول للمقطع بالثواني (3-15)", String.valueOf(prefs.getInt("maxSec", 6)), true);
        fontEt = field(root, "حجم خط الترجمة", String.valueOf(prefs.getInt("font", 26)), true);
        marginEt = field(root, "ارتفاع الترجمة من أسفل الشاشة (dp)", String.valueOf(prefs.getInt("margin", 50)), true);

        micCb = new CheckBox(this);
        micCb.setText("استخدم الميكروفون بدل صوت الجهاز (لو التقاط الصوت مش شغال)");
        micCb.setChecked(prefs.getBoolean("mic", false));
        micCb.setFocusable(true);
        root.addView(micCb);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, (int) (16 * d), 0, (int) (8 * d));
        Button start = new Button(this);
        start.setText("▶ تشغيل الترجمة");
        start.setOnClickListener(v -> startFlow());
        Button stop = new Button(this);
        stop.setText("■ إيقاف");
        stop.setOnClickListener(v -> {
            startService(new Intent(this, TranslatorService.class).setAction(TranslatorService.ACTION_STOP));
            setStatus("تم الإيقاف");
        });
        row.addView(start);
        row.addView(stop);
        root.addView(row);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFFFFD54F);
        root.addView(status);
        setStatus(TranslatorService.running ? "الترجمة شغالة حالياً" : "");

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        start.requestFocus();
    }

    private EditText field(LinearLayout root, String label, String value, boolean number) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        root.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(e);
        return e;
    }

    private Spinner spinner(LinearLayout root, String label, String[] items, int sel) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        root.addView(l);
        Spinner sp = new Spinner(this, Spinner.MODE_DIALOG);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(Math.max(0, Math.min(sel, items.length - 1)));
        sp.setFocusable(true);
        root.addView(sp);
        return sp;
    }

    private String selectedLang() { return OfflineEngine.LANGS[langSp.getSelectedItemPosition()][0]; }

    private void downloadOffline() {
        String code = selectedLang();
        prefs.edit().putString("lang", code).apply();
        setStatus("⏳ بدء التنزيل…");
        new Thread(() -> {
            OfflineEngine e = new OfflineEngine(code);
            try {
                e.prepare(this, msg -> runOnUiThread(() -> setStatus(msg)));
                runOnUiThread(() -> setStatus("✅ موديل " + OfflineEngine.LANGS[OfflineEngine.indexOf(code)][1] + " جاهز للأوفلاين"));
            } catch (Exception ex) {
                runOnUiThread(() -> setStatus("❗ فشل التنزيل: " + ex.getMessage()));
            } finally {
                e.close();
            }
        }).start();
    }

    private int num(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    private void setStatus(String s) { status.setText(s); }

    private void startFlow() {
        String key = keyEt.getText().toString().trim();
        String model = modelEt.getText().toString().trim();
        if (model.isEmpty()) model = DEFAULT_MODEL;
        int engine = engineSp.getSelectedItemPosition();
        prefs.edit()
                .putInt("engine", engine)
                .putString("lang", selectedLang())
                .putString("key", key)
                .putString("model", model)
                .putInt("maxSec", num(maxSecEt, 6))
                .putInt("font", num(fontEt, 26))
                .putInt("margin", num(marginEt, 50))
                .putBoolean("mic", micCb.isChecked())
                .apply();

        if (key.isEmpty() && engine != TranslatorService.ENGINE_OFFLINE) {
            setStatus("❗ اكتب مفتاح Gemini API الأول، أو اختار محرك الأوفلاين");
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            setStatus("❗ فعّل صلاحية \"العرض فوق التطبيقات\" ثم ارجع واضغط تشغيل.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                setStatus("الجهاز ماعندوش شاشة صلاحية العرض فوق التطبيقات.\nفعّلها بالأمر:\n"
                        + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            }
            return;
        }

        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }

        if (micCb.isChecked()) {
            launchService(0, null, true);
        } else {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            try {
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
            } catch (ActivityNotFoundException e) {
                setStatus("❗ الجهاز ده مش بيدعم التقاط صوت النظام. علّم على خيار الميكروفون وجرّب تاني.");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startFlow();
        } else {
            setStatus("❗ لازم صلاحية الصوت عشان التطبيق يسمع المحتوى");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PROJECTION) return;
        if (res == RESULT_OK && data != null) {
            launchService(res, data, false);
        } else {
            setStatus("❗ تم رفض إذن التقاط الصوت");
        }
    }

    private void launchService(int resultCode, Intent data, boolean mic) {
        Intent i = new Intent(this, TranslatorService.class)
                .putExtra(TranslatorService.EXTRA_USE_MIC, mic)
                .putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode);
        if (data != null) i.putExtra(TranslatorService.EXTRA_DATA, data);
        startForegroundService(i);
        setStatus("✅ الترجمة شغالة. ارجع للقناة.");
        moveTaskToBack(true);
    }
}
