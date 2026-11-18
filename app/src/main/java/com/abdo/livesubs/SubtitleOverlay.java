package com.abdo.livesubs;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** Floating subtitle box drawn on top of whatever is playing. Must be used on the main thread. */
public class SubtitleOverlay {
    private final WindowManager wm;
    private final TextView tv;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable hide;
    private boolean attached = false;

    public SubtitleOverlay(Context ctx, float textSp, int bottomMarginDp) {
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        tv = new TextView(ctx);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        tv.setGravity(Gravity.CENTER);
        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        tv.setShadowLayer(6f, 0f, 0f, Color.BLACK);
        tv.setLineSpacing(0f, 1.1f);
        float d = ctx.getResources().getDisplayMetrics().density;
        tv.setPadding((int) (22 * d), (int) (8 * d), (int) (22 * d), (int) (8 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xB0000000);
        bg.setCornerRadius(10 * d);
        tv.setBackground(bg);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        tv.setMaxWidth((int) (screenW * 0.85));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                android.os.Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (bottomMarginDp * d);
        try {
            wm.addView(tv, lp);
            attached = true;
        } catch (Exception ignored) { }
        hide = () -> tv.setVisibility(View.GONE);
        tv.setVisibility(View.GONE);
    }

    public boolean isAttached() { return attached; }

    /** Shows text and hides it automatically after holdMs. */
    public void show(String text, long holdMs) {
        if (!attached) return;
        tv.setText(text);
        tv.setVisibility(View.VISIBLE);
        h.removeCallbacks(hide);
        h.postDelayed(hide, holdMs);
    }

    public void remove() {
        h.removeCallbacks(hide);
        if (attached) {
            try { wm.removeView(tv); } catch (Exception ignored) { }
            attached = false;
        }
    }
}
