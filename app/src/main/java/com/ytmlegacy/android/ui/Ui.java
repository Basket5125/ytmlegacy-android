package com.ytmlegacy.android.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.ytmlegacy.android.R;

/** Small UI helpers. */
public final class Ui {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() { }

    public static void post(Runnable r) {
        MAIN.post(r);
    }

    public static void postDelayed(Runnable r, long ms) {
        MAIN.postDelayed(r, ms);
    }

    public static int dp(Context c, float dp) {
        return (int) (dp * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int attrColor(Context c, int attr) {
        TypedArray a = c.getTheme().obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, 0xFF808080);
        } finally {
            a.recycle();
        }
    }

    public static int attrResource(Context c, int attr) {
        TypedValue v = new TypedValue();
        c.getTheme().resolveAttribute(attr, v, true);
        return v.resourceId;
    }

    public static void tint(ImageView v, int color) {
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    public static void toast(Context c, String text) {
        Toast.makeText(c.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context c, int res) {
        toast(c, c.getString(res));
    }

    /** A dialog builder in the app's theme (Holo on 3.0+). */
    @SuppressWarnings("deprecation")
    public static AlertDialog.Builder dialog(Context c) {
        if (Build.VERSION.SDK_INT >= 11) {
            boolean dark = MainActivity.isDarkTheme(c);
            return Holo.builder(c, dark);
        }
        return new AlertDialog.Builder(c);
    }

    @android.annotation.TargetApi(11)
    private static final class Holo {
        @SuppressWarnings("deprecation")
        static AlertDialog.Builder builder(Context c, boolean dark) {
            return new AlertDialog.Builder(c, dark ? AlertDialog.THEME_HOLO_DARK : AlertDialog.THEME_HOLO_LIGHT);
        }
    }

    /** A text field for dialogs. */
    public static EditText field(Context c, String text, String hint) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setText(text);
        e.setHint(hint);
        e.setSelection(e.getText().length());
        if (Build.VERSION.SDK_INT < 11) {
            // Gingerbread dialogs are dark: keep the text readable.
            e.setTextColor(0xFF000000);
        }
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        e.setLayoutParams(lp);
        return e;
    }

    public static View padded(Context c, View v) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        int p = dp(c, 16);
        f.setPadding(p, dp(c, 8), p, dp(c, 4));
        f.addView(v);
        return f;
    }

    public static String size(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String androidName(int sdk) {
        switch (sdk) {
            case 11: return "3.0";
            case 14: return "4.0";
            case 16: return "4.1";
            case 19: return "4.4";
            case 21: return "5.0";
            case 23: return "6.0";
            case 26: return "8.0";
            case 29: return "10";
            default: return "API " + sdk;
        }
    }

    public static int iconColor(Context c) {
        return attrColor(c, R.attr.ytmIcon);
    }
}
