package com.max.apkscope;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Small view-building helpers so the whole UI can live in code. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0A0A12;
    static final int CARD = 0xFF14141F;
    static final int CARD2 = 0xFF1C1C2B;
    static final int STROKE = 0xFF262638;
    static final int TEXT = 0xFFEDEDF7;
    static final int DIM = 0xFF8A8AA3;
    static final int FAINT = 0xFF5A5A72;
    static final int ACCENT = 0xFF8B7CFF;
    static final int CYAN = 0xFF4FD6FF;
    static final int GREEN = 0xFF5BE49B;
    static final int ORANGE = 0xFFFFB45B;
    static final int PINK = 0xFFFF6B9A;
    static final int YELLOW = 0xFFFFE066;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    static GradientDrawable round(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static Drawable pressable(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable base = round(c, fill, radiusDp, stroke);
        GradientDrawable mask = round(c, 0xFFFFFFFF, radiusDp, 0);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, mask);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView mono(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    static TextView single(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static EditText search(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(FAINT);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setBackground(round(c, CARD, 16, STROKE));
        e.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        return e;
    }

    static TextView chip(Context c, String s, int color) {
        TextView t = text(c, s, 12.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(c, alpha(color, 0x1F), 99, alpha(color, 0x55)));
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static TextView button(Context c, String s, int color) {
        TextView t = text(c, s, 14.5f, 0xFF0A0A12, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(c, color, 16, 0));
        t.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static TextView ghostButton(Context c, String s, int color) {
        TextView t = text(c, s, 14.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(pressable(c, CARD2, 16, STROKE));
        t.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(round(c, CARD, 22, STROKE));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams margins(Context c, LinearLayout.LayoutParams p, float l, float t, float r, float b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { r.run(); }
        };
    }
}
