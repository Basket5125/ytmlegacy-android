package com.ytmlegacy.android.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.Bitmap;

/**
 * System media controls. Android 5+: MediaSession ({@link ModernRemote});
 * Android 4.0–4.4: RemoteControlClient on the lock screen ({@link LegacyRemote}).
 */
interface Remote {
    int STOPPED = 0;
    int PAUSED = 1;
    int PLAYING = 2;
    int BUFFERING = 3;

    /** What the controls can ask the player to do. */
    interface Commands {
        void play();

        void pause();

        void next();

        void previous();

        void stop();

        void seek(long ms);
    }

    void update(int state, long positionMs, long durationMs, String title, String artist, String album,
                Bitmap art, boolean metadataChanged);

    void release();

    /** Everything a playback notification shows. */
    final class NotificationData {
        String title = "";
        String text = "";
        String sub = "";
        Bitmap art;
        boolean playing;
        int smallIcon;
        PendingIntent content;
        PendingIntent delete;
        PendingIntent previous;
        PendingIntent toggle;
        PendingIntent next;
        PendingIntent close;
        String channel = "";
    }

    /** A MediaStyle notification for Android 5+, or null when this remote does not build one. */
    Notification notification(NotificationData data);
}
