package com.abdo.livesubs;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Small floating window that shows subtitles when "display over other apps" is not allowed. */
@TargetApi(26)
public class PipActivity extends Activity implements SubtitleBus.Listener {
    private TextView tv;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable blank = () -> { if (tv != null) tv.setText(""); };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tv = new TextView(this);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        tv.setPadding(12, 6, 12, 6);
        tv.setAutoSizeTextTypeUniformWithConfiguration(8, 40, 1, TypedValue.COMPLEX_UNIT_SP);
        tv.setText("🎧 جاري الاستماع…");
        setContentView(tv);
        SubtitleBus.set(this);
        enterPip();
    }

    private void enterPip() {
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(239, 100))
                    .build());
        } catch (Exception ignored) { }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        if (!inPip && !TranslatorService.running) finish();
    }

    @Override public void onText(String text, long holdMs) {
        h.post(() -> {
            tv.setText(text);
            h.removeCallbacks(blank);
            h.postDelayed(blank, holdMs);
        });
    }

    @Override public void onClose() { h.post(this::finish); }

    @Override
    protected void onDestroy() {
        SubtitleBus.clear(this);
        h.removeCallbacks(blank);
        super.onDestroy();
    }
}
