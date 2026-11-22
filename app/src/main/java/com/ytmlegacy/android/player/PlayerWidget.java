package com.ytmlegacy.android.player;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.RemoteViews;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.ui.MainActivity;
import com.ytmlegacy.android.yt.Song;

import org.json.JSONObject;

/**
 * Home-screen (and 4.2–4.4 lock-screen) widget in the style of Google Play Music's: cover art
 * and buttons in a row, a thin line, then "Title – Artist". The player service pushes updates;
 * nothing runs on a timer.
 */
public final class PlayerWidget extends AppWidgetProvider {

    /** After an app update (or a fresh process) the widget would keep a bare layout without buttons. */
    public static void refresh(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        int[] ids = m.getAppWidgetIds(new ComponentName(context, PlayerWidget.class));
        if (ids != null && ids.length > 0) new PlayerWidget().onUpdate(context, m, ids);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        // Shown before the player service has run: the last song, paused, without art.
        Song last = null;
        String json = Prefs.get(context).lastSong();
        if (json.length() > 0) {
            try {
                last = Song.fromJson(new JSONObject(json));
            } catch (Exception ignored) {
            }
        }
        manager.updateAppWidget(ids, views(context, last, false,
                last != null && Library.get(context).isLiked(last.videoId), null));
        if (PlayerService.running) {
            // The player is up (and in the foreground while playing): let it draw the real state.
            try {
                context.startService(new Intent(context, PlayerService.class).setAction(PlayerService.ACTION_WIDGET));
            } catch (RuntimeException ignored) {
                // Paused in the background on Android 8+: the last song shown above is right anyway.
            }
        }
    }

    static boolean hasWidgets(Context context) {
        int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, PlayerWidget.class));
        return ids != null && ids.length > 0;
    }

    static void push(Context context, Song song, boolean playing, boolean liked, Bitmap art) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        try {
            m.updateAppWidget(new ComponentName(context, PlayerWidget.class), views(context, song, playing, liked, art));
        } catch (RuntimeException e) {
            android.util.Log.w("PlayerWidget", "update: " + e);
        }
    }

    private static RemoteViews views(Context context, Song song, boolean playing, boolean liked, Bitmap art) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget);
        if (song == null) {
            v.setTextViewText(R.id.w_text, context.getString(R.string.app_name));
        } else {
            SpannableStringBuilder t = new SpannableStringBuilder(song.title);
            t.setSpan(new StyleSpan(Typeface.BOLD), 0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (song.artist.length() > 0) {
                int start = t.length();
                t.append("  –  ").append(song.artist);
                t.setSpan(new ForegroundColorSpan(0xFFAAAAAA), start, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            v.setTextViewText(R.id.w_text, t);
        }
        if (art != null) v.setImageViewBitmap(R.id.w_art, art);
        else v.setImageViewResource(R.id.w_art, R.drawable.ic_launcher);
        v.setImageViewResource(R.id.w_toggle, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        v.setImageViewResource(R.id.w_like, liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        Intent open = new Intent(context, MainActivity.class).setAction(MainActivity.ACTION_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(context, 30, open, PlayerService.piFlags());
        v.setOnClickPendingIntent(R.id.w_art, openPi);
        v.setOnClickPendingIntent(R.id.w_text, openPi);
        v.setOnClickPendingIntent(R.id.w_toggle, service(context, PlayerService.ACTION_TOGGLE, 31));
        v.setOnClickPendingIntent(R.id.w_prev, service(context, PlayerService.ACTION_PREVIOUS, 32));
        v.setOnClickPendingIntent(R.id.w_next, service(context, PlayerService.ACTION_NEXT, 33));
        v.setOnClickPendingIntent(R.id.w_like, service(context, PlayerService.ACTION_LIKE, 34));
        return v;
    }

    /** Android 8+ may not start a background service from a widget tap: start it in the foreground. */
    private static PendingIntent service(Context context, String action, int code) {
        Intent i = new Intent(context, PlayerService.class).setAction(action)
                .putExtra(PlayerService.EXTRA_FOREGROUND, true);
        if (Build.VERSION.SDK_INT >= 26) return Oreo.foreground(context, code, i);
        return PendingIntent.getService(context, code, i, PlayerService.piFlags());
    }

    @TargetApi(26)
    private static final class Oreo {
        static PendingIntent foreground(Context c, int code, Intent i) {
            return PendingIntent.getForegroundService(c, code, i, PlayerService.piFlags());
        }
    }
}
