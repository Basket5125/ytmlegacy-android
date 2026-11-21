package com.ytmlegacy.android.player;

import android.annotation.TargetApi;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;

import com.ytmlegacy.android.R;

/** MediaSession + MediaStyle notification (Android 5.0+). */
@TargetApi(21)
final class ModernRemote implements Remote {

    private final MediaSession session;
    private final Context context;

    @SuppressWarnings("deprecation")
    ModernRemote(Context context, final Commands commands) {
        this.context = context.getApplicationContext();
        session = new MediaSession(context, "YTMLegacy");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                commands.play();
            }

            @Override
            public void onPause() {
                commands.pause();
            }

            @Override
            public void onSkipToNext() {
                commands.next();
            }

            @Override
            public void onSkipToPrevious() {
                commands.previous();
            }

            @Override
            public void onStop() {
                commands.stop();
            }

            @Override
            public void onSeekTo(long pos) {
                commands.seek(pos);
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        });
        // Required on Android 5–7; ignored (always on) from 8.
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setActive(true);
    }

    @Override
    public void update(int state, long positionMs, long durationMs, String title, String artist, String album,
                       Bitmap art, boolean metadataChanged) {
        if (metadataChanged) {
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
            if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(m.build());
        }
        int s;
        switch (state) {
            case PLAYING:
                s = PlaybackState.STATE_PLAYING;
                break;
            case PAUSED:
                s = PlaybackState.STATE_PAUSED;
                break;
            case BUFFERING:
                s = PlaybackState.STATE_BUFFERING;
                break;
            default:
                s = PlaybackState.STATE_STOPPED;
        }
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(s, positionMs, state == PLAYING ? 1f : 0f, SystemClock.elapsedRealtime());
        session.setPlaybackState(b.build());
    }

    @Override
    public void release() {
        session.setActive(false);
        session.release();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Notification notification(NotificationData d) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, d.channel)
                : new Notification.Builder(context);
        b.setSmallIcon(d.smallIcon)
                .setContentTitle(d.title)
                .setContentText(d.text)
                .setSubText(d.sub.length() > 0 ? d.sub : null)
                .setLargeIcon(d.art)
                .setContentIntent(d.content)
                .setDeleteIntent(d.delete)
                .setOngoing(d.playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(R.drawable.ic_skip_previous, context.getString(R.string.action_previous), d.previous)
                .addAction(d.playing ? R.drawable.ic_pause : R.drawable.ic_play, context.getString(d.playing ? R.string.action_pause : R.string.action_play), d.toggle)
                .addAction(R.drawable.ic_skip_next, context.getString(R.string.action_next), d.next)
                .addAction(R.drawable.ic_close, context.getString(R.string.action_close), d.close)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }
}
