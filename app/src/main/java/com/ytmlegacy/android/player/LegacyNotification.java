package com.ytmlegacy.android.player;

import android.annotation.TargetApi;
import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import com.ytmlegacy.android.R;

/** Playback notifications before Android 5 (MediaStyle does not exist there). */
@SuppressWarnings("deprecation")
final class LegacyNotification {

    private LegacyNotification() { }

    static Notification build(Context context, Remote.NotificationData d) {
        if (Build.VERSION.SDK_INT >= 16) return Jb.build(context, d);
        if (Build.VERSION.SDK_INT >= 11) return Honeycomb.build(context, d);
        return gingerbread(context, d);
    }

    private static RemoteViews views(Context context, Remote.NotificationData d, boolean buttons) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.notification);
        v.setTextViewText(R.id.n_title, d.title);
        v.setTextViewText(R.id.n_text, d.text);
        if (d.art != null) v.setImageViewBitmap(R.id.n_art, d.art);
        else v.setImageViewResource(R.id.n_art, R.drawable.art_placeholder_small);
        if (buttons) {
            v.setViewVisibility(R.id.n_buttons, View.VISIBLE);
            v.setImageViewResource(R.id.n_toggle, d.playing ? R.drawable.ic_pause : R.drawable.ic_play);
            v.setOnClickPendingIntent(R.id.n_prev, d.previous);
            v.setOnClickPendingIntent(R.id.n_toggle, d.toggle);
            v.setOnClickPendingIntent(R.id.n_next, d.next);
            v.setOnClickPendingIntent(R.id.n_close, d.close);
        } else {
            v.setViewVisibility(R.id.n_buttons, View.GONE);
        }
        return v;
    }

    /** Android 2.3: custom view; buttons in notifications do not react there. */
    private static Notification gingerbread(Context context, Remote.NotificationData d) {
        Notification n = new Notification(d.smallIcon, null, System.currentTimeMillis());
        n.contentView = views(context, d, false);
        n.contentIntent = d.content;
        n.deleteIntent = d.delete;
        if (d.playing) n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        return n;
    }

    /** Android 3.0–4.0: custom view with working buttons. */
    @TargetApi(11)
    private static final class Honeycomb {
        static Notification build(Context context, Remote.NotificationData d) {
            Notification.Builder b = new Notification.Builder(context)
                    .setSmallIcon(d.smallIcon)
                    .setContent(views(context, d, true))
                    .setContentIntent(d.content)
                    .setDeleteIntent(d.delete)
                    .setOngoing(d.playing);
            return b.getNotification();
        }
    }

    /** Android 4.1–4.4: standard notification with action buttons. */
    @TargetApi(16)
    private static final class Jb {
        static Notification build(Context context, Remote.NotificationData d) {
            Notification.Builder b = new Notification.Builder(context)
                    .setSmallIcon(d.smallIcon)
                    .setContentTitle(d.title)
                    .setContentText(d.text)
                    .setLargeIcon(d.art)
                    .setContentIntent(d.content)
                    .setDeleteIntent(d.delete)
                    .setOngoing(d.playing)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .addAction(R.drawable.ic_skip_previous, "", d.previous)
                    .addAction(d.playing ? R.drawable.ic_pause : R.drawable.ic_play, "", d.toggle)
                    .addAction(R.drawable.ic_skip_next, "", d.next);
            if (d.sub.length() > 0) b.setSubText(d.sub);
            if (Build.VERSION.SDK_INT >= 17) b.setShowWhen(false);
            // Collapsed: our own layout with buttons; expanded: the standard one with actions.
            Notification n = b.build();
            n.contentView = views(context, d, true);
            return n;
        }
    }
}
