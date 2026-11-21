package com.ytmlegacy.android.player;

import android.content.Context;
import android.content.SharedPreferences;

/** Loudness-normalisation gain per video, remembered so cached/downloaded tracks keep it. */
public final class Gains {

    private static SharedPreferences store;

    private Gains() { }

    private static synchronized SharedPreferences store(Context context) {
        if (store == null) store = context.getApplicationContext().getSharedPreferences("gains", Context.MODE_PRIVATE);
        return store;
    }

    public static void put(Context context, String videoId, float gain) {
        SharedPreferences s = store(context);
        if (Math.abs(s.getFloat(videoId, 1f) - gain) < 0.001f) return;
        SharedPreferences.Editor e = s.edit();
        if (s.getAll().size() > 3000) e.clear();
        e.putFloat(videoId, gain).apply();
    }

    public static float get(Context context, String videoId) {
        return store(context).getFloat(videoId, 1f);
    }
}
