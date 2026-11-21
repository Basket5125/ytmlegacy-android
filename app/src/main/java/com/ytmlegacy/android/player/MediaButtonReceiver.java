package com.ytmlegacy.android.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Headset / Bluetooth buttons before Android 5 (later the MediaSession gets them). */
public final class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) return;
        Intent service = new Intent(context, PlayerService.class)
                .setAction(PlayerService.ACTION_KEY)
                .putExtra(PlayerService.EXTRA_KEY, event.getKeyCode());
        try {
            context.startService(service);
        } catch (RuntimeException ignored) {
            // Background start not allowed (Android 8+); the MediaSession handles buttons there.
        }
        if (isOrderedBroadcast()) abortBroadcast();
    }
}
