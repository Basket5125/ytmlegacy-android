package com.ytmlegacy.android.player;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Build;

/** Lock-screen controls of Android 4.0–4.4 (RemoteControlClient). */
@TargetApi(14)
@SuppressWarnings("deprecation")
@android.annotation.SuppressLint("UnspecifiedImmutableFlag") // Android 4.x only: no mutability flags there
final class LegacyRemote implements Remote {

    private final AudioManager audio;
    private final ComponentName receiver;
    private final RemoteControlClient client;
    private final boolean showArt;

    LegacyRemote(Context context, final Commands commands, boolean showArt) {
        this.showArt = showArt;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        receiver = new ComponentName(context, MediaButtonReceiver.class);
        audio.registerMediaButtonEventReceiver(receiver);
        Intent button = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, button, 0);
        client = new RemoteControlClient(pi);
        int flags = RemoteControlClient.FLAG_KEY_MEDIA_PLAY | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS | RemoteControlClient.FLAG_KEY_MEDIA_STOP;
        if (Build.VERSION.SDK_INT >= 18) {
            flags |= RemoteControlClient.FLAG_KEY_MEDIA_POSITION_UPDATE;
            Position.attach(client, commands);
        }
        client.setTransportControlFlags(flags);
        audio.registerRemoteControlClient(client);
    }

    /** Seek bar on the Android 4.3/4.4 lock screen. */
    @TargetApi(18)
    private static final class Position {
        static volatile long current;

        static void attach(RemoteControlClient client, final Commands commands) {
            client.setOnGetPlaybackPositionListener(new RemoteControlClient.OnGetPlaybackPositionListener() {
                @Override
                public long onGetPlaybackPosition() {
                    return current;
                }
            });
            client.setPlaybackPositionUpdateListener(new RemoteControlClient.OnPlaybackPositionUpdateListener() {
                @Override
                public void onPlaybackPositionUpdate(long pos) {
                    commands.seek(pos);
                }
            });
        }

        static void set(RemoteControlClient client, int state, long pos) {
            current = pos;
            client.setPlaybackState(state, pos, state == RemoteControlClient.PLAYSTATE_PLAYING ? 1f : 0f);
        }
    }

    @Override
    public void update(int state, long positionMs, long durationMs, String title, String artist, String album,
                       Bitmap art, boolean metadataChanged) {
        int s;
        switch (state) {
            case PLAYING:
                s = RemoteControlClient.PLAYSTATE_PLAYING;
                break;
            case PAUSED:
                s = RemoteControlClient.PLAYSTATE_PAUSED;
                break;
            case BUFFERING:
                s = RemoteControlClient.PLAYSTATE_BUFFERING;
                break;
            default:
                s = RemoteControlClient.PLAYSTATE_STOPPED;
        }
        if (Build.VERSION.SDK_INT >= 18) Position.set(client, s, positionMs);
        else client.setPlaybackState(s);
        if (metadataChanged) {
            RemoteControlClient.MetadataEditor e = client.editMetadata(true);
            e.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, title);
            e.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, artist);
            e.putString(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, artist);
            e.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, album);
            e.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, durationMs);
            if (art != null && showArt) {
                // The editor may recycle the bitmap it is given; hand it a copy.
                // RGB_565 halves the bytes sent to the lock screen.
                Bitmap copy = art.copy(Bitmap.Config.RGB_565, false);
                if (copy != null) e.putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, copy);
            }
            e.apply();
        }
    }

    @Override
    public void release() {
        try {
            client.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
            audio.unregisterRemoteControlClient(client);
            audio.unregisterMediaButtonEventReceiver(receiver);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Notification notification(NotificationData data) {
        return null;
    }
}
