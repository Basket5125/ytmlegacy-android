package com.ytmlegacy.android.player;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.ui.Images;
import com.ytmlegacy.android.ui.MainActivity;
import com.ytmlegacy.android.yt.Song;
import com.ytmlegacy.android.yt.Streams;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Background playback: queue, two MediaPlayers (for crossfade), effects and system controls. */
public final class PlayerService extends Service implements Remote.Commands {

    private static final String TAG = "Player";
    public static final String ACTION_TOGGLE = "ytm.TOGGLE";
    public static final String ACTION_PLAY = "ytm.PLAY";
    public static final String ACTION_PAUSE = "ytm.PAUSE";
    public static final String ACTION_NEXT = "ytm.NEXT";
    public static final String ACTION_PREVIOUS = "ytm.PREVIOUS";
    public static final String ACTION_STOP = "ytm.STOP";
    public static final String ACTION_KEY = "ytm.KEY";
    public static final String ACTION_LIKE = "ytm.LIKE";
    public static final String ACTION_WIDGET = "ytm.WIDGET";
    /** True while the service exists (a new widget then asks it for the current state). */
    static volatile boolean running;
    /** Set on intents from the widget: on Android 8+ they start the service in the foreground. */
    public static final String EXTRA_FOREGROUND = "fg";
    public static final String EXTRA_KEY = "key";
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;

    /** UI callbacks, always on the main thread. */
    public interface Listener {
        void onPlayerState();

        void onQueueChanged();

        void onPlayerError(String message);
    }

    public final class LocalBinder extends Binder {
        public PlayerService service() {
            return PlayerService.this;
        }
    }

    /** One MediaPlayer with the song it plays. */
    private final class Track implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
            MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener {
        final Song song;
        final int quality;
        final MediaPlayer mp = new MediaPlayer();
        Effects fx;
        boolean prepared;
        boolean released;
        float fade = 1f;
        int pendingSeek;
        long startedAt;
        boolean buffering;
        /** Why the song could not be loaded (classic path), shown to the user. */
        String loadError;

        Track(Song song, int quality) {
            this.song = song;
            this.quality = quality;
        }

        void open() throws Exception {
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setWakeMode(PlayerService.this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setOnInfoListener(this);
            if (prefs.needClassicMp4()) {
                openClassic();
                return;
            }
            mp.setDataSource(StreamProxy.get(PlayerService.this).url(song.videoId, quality));
            mp.prepareAsync();
        }

        /**
         * Android 2.x: the song has to be complete and repacked before the player can read it.
         * It is opened as a file (by descriptor: mediaserver cannot read the app's files by path),
         * not over the local HTTP server, whose wait made the old player give up (error -1004).
         */
        private void openClassic() {
            classicLoader.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final File file = AudioCache.get(PlayerService.this).classicFile(song.videoId, quality);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (released) return;
                                try {
                                    java.io.FileInputStream in = new java.io.FileInputStream(file);
                                    try {
                                        mp.setDataSource(in.getFD());
                                    } finally {
                                        in.close();
                                    }
                                    mp.prepareAsync();
                                } catch (Exception e) {
                                    loadError = e.toString();
                                    failed(Track.this, loadError);
                                }
                            }
                        });
                    } catch (final Exception e) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (released) return;
                                loadError = e.getMessage() == null ? e.toString() : e.getMessage();
                                failed(Track.this, loadError);
                            }
                        });
                    }
                }
            });
        }

        void release() {
            if (released) return;
            released = true;
            if (fx != null) fx.release();
            try {
                mp.reset();
            } catch (Throwable ignored) {
            }
            mp.release();
        }

        int position() {
            if (!prepared || released) return pendingSeek;
            try {
                return mp.getCurrentPosition();
            } catch (Throwable e) {
                return 0;
            }
        }

        int duration() {
            if (prepared && !released) {
                try {
                    int d = mp.getDuration();
                    if (d > 0) return d;
                } catch (Throwable ignored) {
                }
            }
            return song.durationSec * 1000;
        }

        @Override
        public void onPrepared(MediaPlayer m) {
            if (released) return;
            prepared = true;
            fx = Effects.attach(mp.getAudioSessionId(), prefs);
            applyVolume(this);
            if (pendingSeek > 0) mp.seekTo(pendingSeek);
            pendingSeek = 0;
            if (this == cur) {
                if (wantPlaying) startCurrent();
                stateChanged(true);
            }
        }

        @Override
        public void onCompletion(MediaPlayer m) {
            if (released) return;
            if (this == fadingOut) {
                finishCrossfade();
                return;
            }
            if (this != cur) return;
            long played = SystemClock.elapsedRealtime() - startedAt;
            if (startedAt > 0 && played < 1500 && duration() > 10000 && position() < 1500) {
                // Ended at once: the player could not read the container (old MediaPlayer + fMP4).
                failed(this, "ended at once");
                return;
            }
            advance(true);
        }

        @Override
        public boolean onError(MediaPlayer m, int what, int extra) {
            Log.w(TAG, "MediaPlayer error " + what + "/" + extra + " for " + song.videoId);
            if (!released) failed(this, what + "/" + extra);
            return true;
        }

        @Override
        public boolean onInfo(MediaPlayer m, int what, int extra) {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true;
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering = false;
            if (this == cur) stateChanged(false);
            return false;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final ExecutorService prefetcher = Executors.newSingleThreadExecutor();
    /** Loads songs for Android 2.x players (download + repack), current and next. */
    private final ExecutorService classicLoader = Executors.newFixedThreadPool(2);

    private Prefs prefs;
    private AudioManager audio;
    private PlayQueue queue;
    private Radio radio;
    private Remote remote;
    private WifiManager.WifiLock wifiLock;

    private Track cur;
    private Track nxt;
    private Track fadingOut;
    private boolean crossfading;
    private long fadeStart;
    private long fadeLength;

    private boolean wantPlaying;
    private boolean pausedByFocus;
    private boolean hasFocus;
    private float duck = 1f;
    private int repeat;
    private int errorsInRow;
    private boolean radioLoading;
    private boolean waitingForRadio;
    private boolean foreground;
    /** True once started with startService: a service that is only bound dies with the activity. */
    private boolean started;
    private boolean noisyRegistered;
    private int restoredPosition;
    private long sleepAt;
    private boolean sleepEndOfTrack;
    /** Cover for the lock screen / MediaSession (≤ 320 px) and for the notification (≤ 128 px). */
    private Bitmap art;
    private Bitmap notificationArt;
    private String artFor = "";
    private final Set<String> formatRetried = new HashSet<String>();
    private long lastSave;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = Prefs.get(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        queue = new PlayQueue();
        radio = new Radio(this);
        repeat = prefs.repeat();
        restore();
        if (Build.VERSION.SDK_INT >= 26) Channels.create(this);
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            wifiLock = wifi.createWifiLock(Build.VERSION.SDK_INT >= 12 ? 3 /* FULL_HIGH_PERF */
                    : WifiManager.WIFI_MODE_FULL, "ytm-playback");
            wifiLock.setReferenceCounted(false);
        }
        handler.post(tick);
        updateWidget();
        final Song restored = queue.current();
        if (restored != null && PlayerWidget.hasWidgets(this)) {
            prefetcher.execute(new Runnable() {
                @Override
                public void run() {
                    loadArt(restored);
                }
            });
        }
    }

    /**
     * Makes this a started service before music plays, so it keeps running in the background
     * when the activity unbinds (playback may begin from a bound-only service, e.g. the mini
     * player's play button after the app was closed with "Exit").
     */
    private void ensureStarted() {
        if (started) return;
        Intent self = new Intent(this, PlayerService.class);
        try {
            startService(self);
            started = true;
        } catch (RuntimeException e) {
            // Android 8+ in the background: allowed as a foreground start; updateControls()
            // calls startForeground() right after, well within the 5 s limit.
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Oreo.startForegroundService(this, self);
                    started = true;
                } catch (RuntimeException e2) {
                    Log.w(TAG, "cannot start service: " + e2);
                }
            } else {
                Log.w(TAG, "cannot start service: " + e);
            }
        }
    }

    @TargetApi(26)
    private static final class Oreo {
        static void startForegroundService(Context c, Intent i) {
            c.startForegroundService(i);
        }
    }

    private boolean remoteReady;

    /** System media controls, created when playback first starts (not for an idle service). */
    @SuppressWarnings("deprecation")
    private void ensureRemote() {
        if (remoteReady) return;
        remoteReady = true;
        if (Build.VERSION.SDK_INT >= 21) {
            remote = new ModernRemote(this, this);
        } else if (Build.VERSION.SDK_INT >= 14) {
            remote = new LegacyRemote(this, this, prefs.lockScreenArt());
        } else {
            audio.registerMediaButtonEventReceiver(new ComponentName(this, MediaButtonReceiver.class));
        }
    }

    @TargetApi(26)
    private static final class Channels {
        static void create(Context context) {
            NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(CHANNEL,
                    context.getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(com.ytmlegacy.android.data.Lang.wrap(base));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        String action = intent == null ? null : intent.getAction();
        boolean fromForegroundStart = intent != null && intent.getBooleanExtra(EXTRA_FOREGROUND, false)
                && Build.VERSION.SDK_INT >= 26;
        if (fromForegroundStart && !foreground) {
            // startForegroundService() needs startForeground() within seconds, whatever the action.
            Song s = queue.current();
            try {
                startForeground(NOTIFICATION_ID, buildNotification(s != null ? s
                        : new Song("", getString(R.string.app_name), ""), wantPlaying));
                foreground = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "startForeground: " + e);
            }
        }
        if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_STOP.equals(action)) stop();
        else if (ACTION_KEY.equals(action)) key(intent.getIntExtra(EXTRA_KEY, 0));
        else if (ACTION_LIKE.equals(action)) toggleLike();
        else if (ACTION_WIDGET.equals(action)) {
            widgetState = "";
            updateWidget();
        }
        if (fromForegroundStart && !wantPlaying && foreground) {
            stopForegroundCompat(queue.current() == null);
            if (queue.current() == null) stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void toggleLike() {
        Song s = queue.current();
        if (s == null) return;
        Library lib = Library.get(this);
        lib.setLiked(s, !lib.isLiked(s.videoId));
        updateWidget();
    }

    // ------------------------------------------------------------------ widget

    private String widgetState = "";

    /** Pushes the state to the home-screen widget, only when something it shows changed. */
    private void updateWidget() {
        Song s = queue.current();
        boolean liked = s != null && Library.get(this).isLiked(s.videoId);
        boolean hasArt = s != null && s.videoId.equals(artFor) && notificationArt != null;
        String state = (s == null ? "" : s.videoId) + "|" + wantPlaying + "|" + liked + "|" + hasArt;
        if (state.equals(widgetState)) return;
        widgetState = state;
        if (s != null) prefs.setLastSong(s.toJson().toString());
        if (!PlayerWidget.hasWidgets(this)) return;
        PlayerWidget.push(this, s, wantPlaying, liked, hasArt ? notificationArt : null);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) stop();
    }

    @Override
    public void onDestroy() {
        running = false;
        flushStats();
        wantPlaying = false;
        updateWidget();
        save();
        handler.removeCallbacksAndMessages(null);
        release(nxt);
        release(fadingOut);
        release(cur);
        nxt = fadingOut = cur = null;
        abandonFocus();
        unregisterNoisy();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (remote != null) remote.release();
        else if (remoteReady && Build.VERSION.SDK_INT < 14) {
            audio.unregisterMediaButtonEventReceiver(new ComponentName(this, MediaButtonReceiver.class));
        }
        stopForegroundCompat(true);
        background.shutdown();
        prefetcher.shutdown();
        classicLoader.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ listeners & getters

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Song current() {
        return queue.current();
    }

    public List<Song> queueSnapshot() {
        return queue.snapshot();
    }

    public int queuePosition() {
        return queue.position();
    }

    public boolean isPlaying() {
        return wantPlaying && cur != null && cur.prepared;
    }

    /** Playing was asked for but sound has not started yet (resolving, buffering). */
    public boolean isLoading() {
        return wantPlaying && cur != null && (!cur.prepared || cur.buffering);
    }

    public boolean wantsToPlay() {
        return wantPlaying;
    }

    public int position() {
        if (cur == null) return restoredPosition;
        return cur.position();
    }

    public int duration() {
        if (cur != null) return cur.duration();
        Song s = queue.current();
        return s == null ? 0 : s.durationSec * 1000;
    }

    public int repeatMode() {
        return repeat;
    }

    public boolean shuffle() {
        return queue.shuffled();
    }

    public boolean radioActive() {
        return radio.active();
    }

    public boolean radioLoading() {
        return radioLoading;
    }

    /** Milliseconds until the sleep timer stops playback; 0 = off, -1 = at the end of the song. */
    public long sleepRemaining() {
        if (sleepEndOfTrack) return -1;
        return sleepAt == 0 ? 0 : Math.max(0, sleepAt - SystemClock.elapsedRealtime());
    }

    public Effects.Info effectsInfo() {
        Effects.Info i = cur != null && cur.fx != null ? cur.fx.info() : null;
        return i != null ? i : Effects.probe(prefs);
    }

    public short[] presetLevels(int preset) {
        short[] l = cur != null && cur.fx != null ? cur.fx.presetLevels(preset) : null;
        return l != null ? l : Effects.probePreset(preset);
    }

    /** Re-applies equalizer settings and volume after the settings changed. */
    public void settingsChanged() {
        // The cover quality may have changed: load the lock-screen art again.
        final Song playing = queue.current();
        if (playing != null) {
            prefetcher.execute(new Runnable() {
                @Override
                public void run() {
                    loadArt(playing, true);
                }
            });
        }
        for (Track t : new Track[]{cur, nxt, fadingOut}) {
            if (t != null && t.fx != null) t.fx.apply(prefs);
            if (t != null) applyVolume(t);
        }
        checkNext();
        prefetch();
    }

    // ------------------------------------------------------------------ queue commands

    /** Plays a list from an index. {@code radioAfter} keeps playing similar songs when it ends. */
    public void playList(List<Song> songs, int start, boolean shuffleOn) {
        if (songs.isEmpty()) return;
        radio.stop();
        queue.set(songs, start, shuffleOn);
        radio.markSeen(songs);
        formatRetried.clear();
        playAt(queue.position(), true, 0);
        queueChanged();
    }

    /** Plays one song followed by its endless radio. */
    public void playRadio(Song seed) {
        List<Song> one = new ArrayList<Song>();
        one.add(seed);
        queue.set(one, 0, false);
        radio.start(seed.videoId, "", "", one);
        formatRetried.clear();
        playAt(0, true, 0);
        queueChanged();
        loadRadio();
    }

    /** Starts a YouTube mix/radio playlist (e.g. RDCLAK…, RDEM…) as an endless radio. */
    public void playMix(final String videoId, final String playlistId, final String params) {
        radio.start(videoId, playlistId, params, new ArrayList<Song>());
        radioLoading = true;
        stateChanged(false);
        background.execute(new Runnable() {
            @Override
            public void run() {
                final List<Song> songs = radio.more(new ArrayList<Song>());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        radioLoading = false;
                        if (songs.isEmpty()) {
                            error(getString(R.string.error_load));
                            stateChanged(false);
                            return;
                        }
                        queue.set(songs, 0, false);
                        playAt(0, true, 0);
                        queueChanged();
                    }
                });
            }
        });
    }

    /** Radio from the current song onward (keeps what is playing). */
    public void startRadioFromCurrent() {
        Song s = queue.current();
        if (s == null) return;
        int pos = queue.position();
        List<Song> all = queue.snapshot();
        // Drop what was queued after the current song: the radio replaces it.
        for (int i = all.size() - 1; i > pos; i--) queue.remove(i);
        radio.start(s.videoId, "", "", queue.snapshot());
        queueChanged();
        loadRadio();
    }

    public void addNext(List<Song> songs) {
        boolean empty = queue.size() == 0;
        queue.addNext(songs);
        if (empty) playAt(queue.position(), true, 0);
        checkNext();
        queueChanged();
    }

    public void addLast(List<Song> songs) {
        boolean empty = queue.size() == 0;
        queue.addLast(songs);
        if (empty) playAt(queue.position(), true, 0);
        checkNext();
        queueChanged();
    }

    public void removeAt(int index) {
        boolean wasCurrent = queue.remove(index);
        if (wasCurrent) {
            if (queue.size() == 0) {
                stop();
                return;
            }
            playAt(queue.position(), wantPlaying, 0);
        }
        checkNext();
        queueChanged();
    }

    public void move(int from, int to) {
        queue.move(from, to);
        checkNext();
        queueChanged();
    }

    public void playIndex(int index) {
        playAt(index, true, 0);
    }

    public void clearUpcoming() {
        int pos = queue.position();
        for (int i = queue.size() - 1; i > pos; i--) queue.remove(i);
        radio.stop();
        checkNext();
        queueChanged();
    }

    public void setRepeat(int mode) {
        repeat = mode;
        prefs.setRepeat(mode);
        checkNext();
        stateChanged(false);
    }

    public void cycleRepeat() {
        setRepeat((repeat + 1) % 3);
    }

    public void setShuffle(boolean on) {
        queue.setShuffle(on);
        prefs.setShuffle(on);
        checkNext();
        queueChanged();
    }

    public void setSleepTimer(int minutes) {
        sleepEndOfTrack = minutes < 0;
        sleepAt = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60000L : 0;
        applyVolumeAll();
        stateChanged(false);
    }

    // ------------------------------------------------------------------ transport

    @Override
    public void play() {
        if (cur == null) {
            if (queue.current() == null) return;
            playAt(queue.position(), true, restoredPosition);
            return;
        }
        wantPlaying = true;
        if (cur.prepared) startCurrent();
        stateChanged(false);
    }

    @Override
    public void pause() {
        wantPlaying = false;
        pausedByFocus = false;
        if (cur != null && cur.prepared) {
            try {
                cur.mp.pause();
            } catch (Throwable ignored) {
            }
        }
        if (fadingOut != null) {
            release(fadingOut);
            fadingOut = null;
            crossfading = false;
            if (cur != null) {
                cur.fade = 1f;
                applyVolume(cur);
            }
        }
        unregisterNoisy();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        save();
        stateChanged(false);
    }

    public void toggle() {
        if (wantPlaying) pause();
        else play();
    }

    @Override
    public void next() {
        int r = repeat == Prefs.REPEAT_ONE ? Prefs.REPEAT_ALL : repeat;
        int n = queue.nextIndex(r);
        if (n < 0) {
            if (radio.active() || prefs.autoRadio()) {
                waitingForRadio = true;
                loadRadio();
            }
            return;
        }
        playAt(n, true, 0);
    }

    @Override
    public void previous() {
        if (position() > 3000) {
            seek(0);
            return;
        }
        int p = queue.previousIndex(repeat == Prefs.REPEAT_ONE ? Prefs.REPEAT_OFF : repeat);
        if (p >= 0) playAt(p, true, 0);
    }

    @Override
    public void stop() {
        pause();
        release(nxt);
        release(fadingOut);
        if (cur != null) restoredPosition = cur.position();
        release(cur);
        nxt = fadingOut = cur = null;
        abandonFocus();
        stopForegroundCompat(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID);
        if (remote != null) remote.update(Remote.STOPPED, 0, 0, "", "", "", null, false);
        save();
        stateChanged(false);
        started = false;
        stopSelf();
    }

    @Override
    public void seek(long ms) {
        if (cur == null) {
            restoredPosition = (int) ms;
            return;
        }
        if (fadingOut != null) {
            release(fadingOut);
            fadingOut = null;
            crossfading = false;
            cur.fade = 1f;
            applyVolume(cur);
        }
        if (cur.prepared) {
            try {
                cur.mp.seekTo((int) ms);
            } catch (Throwable ignored) {
            }
        } else {
            cur.pendingSeek = (int) ms;
        }
        // The prepared next song may need a new crossfade point.
        stateChanged(false);
    }

    private long lastHook;

    private void key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_HEADSETHOOK:
                // Double press = next.
                long now = SystemClock.elapsedRealtime();
                if (now - lastHook < 400) {
                    handler.removeCallbacks(hookToggle);
                    lastHook = 0;
                    next();
                } else {
                    lastHook = now;
                    handler.postDelayed(hookToggle, 400);
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                toggle();
                break;
            case 126: // KEYCODE_MEDIA_PLAY
                play();
                break;
            case 127: // KEYCODE_MEDIA_PAUSE
                pause();
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                next();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                previous();
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                stop();
                break;
            default:
                break;
        }
    }

    private final Runnable hookToggle = new Runnable() {
        @Override
        public void run() {
            toggle();
        }
    };

    // ------------------------------------------------------------------ engine

    private int qualityFor(Song s) {
        int q = prefs.quality();
        if (!onWifi() && prefs.mobileQuality() >= 0) q = prefs.mobileQuality();
        if (q == Prefs.Q_HIGH && formatRetried.contains(s.videoId)) q = Prefs.Q_NORMAL;
        return q;
    }

    private void playAt(int index, boolean start, int seekMs) {
        release(nxt);
        release(fadingOut);
        release(cur);
        nxt = fadingOut = null;
        crossfading = false;
        queue.moveTo(index);
        Song s = queue.current();
        restoredPosition = 0;
        if (s == null) {
            cur = null;
            stateChanged(true);
            return;
        }
        wantPlaying = start;
        StreamProxy.get(this).reset(s.videoId);
        cur = new Track(s, qualityFor(s));
        cur.pendingSeek = seekMs;
        try {
            cur.open();
        } catch (Exception e) {
            Log.w(TAG, "open failed", e);
            failed(cur, e.toString());
            return;
        }
        if (start) {
            ensureStarted();
            ensureRemote();
            requestFocus();
            registerNoisy();
            if (wifiLock != null) wifiLock.acquire();
        }
        stateChanged(true);
        prefetch();
        maybeLoadRadio();
        saveSoon();
    }

    private void startCurrent() {
        if (cur == null || !cur.prepared) return;
        ensureStarted();
        ensureRemote();
        if (!hasFocus) requestFocus();
        try {
            cur.mp.start();
        } catch (Throwable e) {
            failed(cur, e.toString());
            return;
        }
        if (cur.startedAt == 0) {
            cur.startedAt = SystemClock.elapsedRealtime();
            errorsInRow = 0;
            addHistory(cur.song);
        }
        registerNoisy();
        if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
    }

    /** Moves on after a song ended (auto) or was skipped. */
    private void advance(boolean auto) {
        if (auto && sleepEndOfTrack) {
            sleepEndOfTrack = false;
            int n = queue.nextIndex(repeat);
            if (n >= 0 && n != queue.position()) {
                playAt(n, false, 0);
            } else {
                pause();
                seek(0);
            }
            return;
        }
        int n = queue.nextIndex(repeat);
        if (n < 0) {
            if (radio.active() || prefs.autoRadio()) {
                waitingForRadio = true;
                if (!radio.active()) startAutoRadio();
                else loadRadio();
                stateChanged(false);
                return;
            }
            // End of the queue: stop at the first song.
            wantPlaying = false;
            playAt(0, false, 0);
            return;
        }
        if (n == queue.position() && cur != null && cur.prepared) { // repeat one
            cur.mp.seekTo(0);
            cur.startedAt = 0;
            startCurrent();
            stateChanged(false);
            return;
        }
        if (nxt != null && nxt.prepared && nxt.song == queue.at(n)) {
            Track old = cur;
            cur = nxt;
            nxt = null;
            queue.moveTo(n);
            release(old);
            wantPlaying = true;
            cur.fade = 1f;
            applyVolume(cur);
            startCurrent();
            stateChanged(true);
            prefetch();
            maybeLoadRadio();
            saveSoon();
            return;
        }
        playAt(n, true, 0);
    }

    private void failed(Track t, String what) {
        if (t == nxt) {
            release(nxt);
            nxt = null;
            return;
        }
        if (t == fadingOut) {
            finishCrossfade();
            return;
        }
        if (t != cur) return;
        String id = t.song.videoId;
        StreamProxy proxy = StreamProxy.get(this);
        String proxyError = t.loadError != null ? t.loadError : proxy.lastError(id);
        // Only a song the player actually received and still refused points at the format.
        boolean formatProblem = proxyError.length() == 0 && proxy.served(id) && online();
        if (formatProblem && !formatRetried.contains(id)) {
            formatRetried.add(id);
            if (t.quality == Prefs.Q_HIGH && Prefs.opusSupported()) {
                Log.i(TAG, "Opus failed for " + id + ", retrying with AAC");
            } else if (prefs.compat() == Prefs.COMPAT_AUTO && !prefs.needClassicMp4()) {
                Log.i(TAG, "player refused fragmented MP4; switching to classic MP4 (" + what + ")");
                prefs.setFmp4Broken(true);
            }
            final int pos = queue.position();
            final boolean play = wantPlaying;
            release(cur);
            cur = null;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    playAt(pos, play, 0);
                }
            });
            return;
        }
        errorsInRow++;
        String message = proxyError.length() > 0 ? proxyError
                : online() ? getString(R.string.error_playback) : getString(R.string.error_offline);
        error(getString(R.string.error_song, t.song.title, message));
        release(cur);
        cur = null;
        boolean play = wantPlaying;
        if (errorsInRow >= 4 || !play) {
            wantPlaying = false;
            stateChanged(true);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (cur == null && wantPlaying) {
                    int n = queue.nextIndex(repeat == Prefs.REPEAT_ONE ? Prefs.REPEAT_OFF : repeat);
                    if (n >= 0 && n != queue.position()) playAt(n, true, 0);
                    else {
                        wantPlaying = false;
                        stateChanged(true);
                    }
                }
            }
        }, 1200);
        stateChanged(true);
    }

    private void release(Track t) {
        if (t != null) t.release();
    }

    /** Drops a prepared next song that is no longer the next one. */
    private void checkNext() {
        if (nxt == null) return;
        int n = queue.nextIndex(repeat);
        if (n < 0 || n == queue.position() || queue.at(n) != nxt.song) {
            release(nxt);
            nxt = null;
        }
    }

    private void applyVolume(Track t) {
        if (t == null || !t.prepared || t.released) return;
        float v = t.fade * duck;
        if (prefs.normalize()) v *= Gains.get(this, t.song.videoId);
        if (sleepAt > 0) {
            long left = sleepAt - SystemClock.elapsedRealtime();
            if (left < 10000) v *= Math.max(0f, left / 10000f);
        }
        v = Math.max(0f, Math.min(1f, v));
        try {
            t.mp.setVolume(v, v);
        } catch (Throwable ignored) {
        }
    }

    private void applyVolumeAll() {
        applyVolume(cur);
        applyVolume(nxt);
        applyVolume(fadingOut);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            handler.postDelayed(this, crossfading ? 100 : 500);
        }
    };

    // ------------------------------------------------------------------ listening statistics

    /** One listening session of a song; its row is written on the background thread. */
    private static final class PlaySession {
        final Song song;
        long row = -1;

        PlaySession(Song song) {
            this.song = song;
        }
    }

    private PlaySession session;
    private long statMs;
    private long statLastTick;
    private long statLastFlush;

    /**
     * Counts the seconds music is actually heard. Pausing does not end a session, so a song
     * paused and resumed counts once; a different song starts a new one.
     */
    private void accountStats() {
        long now = SystemClock.elapsedRealtime();
        Song playing = prefs.stats() && isPlaying() && cur != null ? cur.song : null;
        if (playing != null && (session == null || session.song != playing)) {
            flushStats();
            session = new PlaySession(playing);
            statMs = 0;
            statLastTick = now;
            statLastFlush = now;
            return;
        }
        if (playing != null) {
            if (statLastTick > 0) statMs += Math.min(now - statLastTick, 2000);
            statLastTick = now;
            if (now - statLastFlush > 30000) flushStats();
        } else {
            statLastTick = 0;
            if (statMs >= 1000) flushStats();
        }
    }

    private void flushStats() {
        statLastFlush = SystemClock.elapsedRealtime();
        final PlaySession ps = session;
        if (ps == null || statMs < 1000) return;
        final int seconds = (int) (statMs / 1000);
        statMs -= seconds * 1000L;
        Runnable write = new Runnable() {
            @Override
            public void run() {
                Library lib = Library.get(PlayerService.this);
                if (ps.row < 0) ps.row = lib.startPlay(ps.song, seconds);
                else lib.addPlaySeconds(ps.row, seconds);
            }
        };
        try {
            background.execute(write);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            write.run(); // service shutting down
        }
    }

    private void onTick() {
        accountStats();
        if (sleepAt > 0) {
            long left = sleepAt - SystemClock.elapsedRealtime();
            if (left <= 0) {
                sleepAt = 0;
                pause();
                applyVolumeAll();
            } else if (left < 10000) {
                applyVolumeAll();
            }
        }
        if (crossfading) {
            float t = Math.min(1f, (SystemClock.elapsedRealtime() - fadeStart) / (float) Math.max(1, fadeLength));
            if (fadingOut != null) fadingOut.fade = 1f - t;
            if (cur != null) cur.fade = t;
            applyVolume(fadingOut);
            applyVolume(cur);
            if (t >= 1f) finishCrossfade();
        }
        if (cur == null || !cur.prepared || !isPlaying()) return;
        int pos = cur.position();
        int dur = cur.duration();
        if (dur <= 0) return;
        int n = queue.nextIndex(repeat);
        boolean hasNext = n >= 0 && n != queue.position() && !sleepEndOfTrack;
        int fadeMs = prefs.crossfade() ? prefs.crossfadeSeconds() * 1000 : 0;
        if (hasNext && nxt == null && !crossfading && dur - pos < fadeMs + 20000) {
            Song s = queue.at(n);
            nxt = new Track(s, qualityFor(s));
            nxt.fade = fadeMs > 0 ? 0f : 1f;
            try {
                nxt.open();
            } catch (Exception e) {
                release(nxt);
                nxt = null;
            }
        }
        if (hasNext && fadeMs > 0 && !crossfading && nxt != null && nxt.prepared && nxt.song == queue.at(n)
                && dur - pos <= fadeMs && dur > fadeMs * 2) {
            startCrossfade(n, dur - pos);
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastSave > 15000) save();
    }

    private void startCrossfade(int index, int left) {
        fadingOut = cur;
        cur = nxt;
        nxt = null;
        queue.moveTo(index);
        crossfading = true;
        fadeStart = SystemClock.elapsedRealtime();
        fadeLength = Math.max(500, left);
        cur.fade = 0f;
        applyVolume(cur);
        startCurrent();
        stateChanged(true);
        prefetch();
        maybeLoadRadio();
        saveSoon();
    }

    private void finishCrossfade() {
        crossfading = false;
        release(fadingOut);
        fadingOut = null;
        if (cur != null) {
            cur.fade = 1f;
            applyVolume(cur);
        }
    }

    // ------------------------------------------------------------------ radio & prefetch

    private void startAutoRadio() {
        Song last = queue.current();
        if (last == null) return;
        radio.start(last.videoId, "", "", queue.snapshot());
        loadRadio();
    }

    private void maybeLoadRadio() {
        if (repeat == Prefs.REPEAT_ALL) return;
        if (queue.remaining() >= 3) return;
        if (radio.active()) loadRadio();
        else if (prefs.autoRadio() && queue.remaining() == 0 && queue.size() > 0) startAutoRadio();
    }

    private void loadRadio() {
        if (radioLoading || !radio.active()) return;
        radioLoading = true;
        final List<Song> recent = queue.snapshot();
        background.execute(new Runnable() {
            @Override
            public void run() {
                final List<Song> more = radio.more(recent);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        radioLoading = false;
                        if (!more.isEmpty() && radio.active()) {
                            queue.addLast(more);
                            queue.trimHistory(200);
                            queueChanged();
                            if (waitingForRadio) {
                                waitingForRadio = false;
                                int n = queue.nextIndex(Prefs.REPEAT_OFF);
                                if (n >= 0) playAt(n, true, 0);
                            }
                        } else if (waitingForRadio) {
                            waitingForRadio = false;
                            if (more.isEmpty()) error(getString(R.string.error_radio));
                            wantPlaying = false;
                            stateChanged(false);
                        }
                        stateChanged(false);
                    }
                });
            }
        });
    }

    /** Fetches the next songs of the queue into the cache, so they start at once. */
    private void prefetch() {
        final List<Song> upcoming = new ArrayList<Song>();
        int count = prefs.prefetchCount();
        if (!prefs.cacheEnabled()) count = Math.min(count, 1);
        if (prefs.prefetchWifiOnly() && !onWifi()) count = 0;
        int pos = queue.position();
        for (int i = 1; i <= count; i++) {
            Song s = queue.at(pos + i);
            if (s == null && repeat == Prefs.REPEAT_ALL) s = queue.at(pos + i - queue.size());
            if (s != null) upcoming.add(s);
        }
        final Set<String> keep = new HashSet<String>();
        Song c = queue.current();
        if (c != null) keep.add(c.videoId);
        for (Song s : upcoming) keep.add(s.videoId);
        final Song current = c;
        final boolean classic = prefs.needClassicMp4();
        prefetcher.execute(new Runnable() {
            @Override
            public void run() {
                AudioCache cache = AudioCache.get(PlayerService.this);
                cache.setKeep(keep);
                cache.cancelExcept(keep);
                // The song being played gets all the bandwidth first; on a slow phone/Wi-Fi the
                // next songs fetched at the same time made it start several times slower.
                if (current != null) loadArt(current); // the cover must not wait for the download
                if (current != null && !waitUntilCached(current, cache)) return;
                for (Song s : upcoming) {
                    if (Library.get(PlayerService.this).isDownloaded(s.videoId)) continue;
                    int q = qualityFor(s);
                    if (cache.cached(s.videoId, Streams.itagsFor(q)) != null) continue;
                    try {
                        Streams.Stream st = Streams.get(PlayerService.this).resolve(s.videoId, q, !classic);
                        cache.fetch(st);
                    } catch (Exception e) {
                        Log.i(TAG, "prefetch " + s.videoId + ": " + e.getMessage());
                    }
                }
                if (current != null) loadArt(current);
            }
        });
    }

    /** Waits (on the prefetch thread) until the playing song is on disk; false when it changed. */
    private boolean waitUntilCached(Song song, AudioCache cache) {
        long end = SystemClock.elapsedRealtime() + 180000;
        int[] itags = Streams.itagsFor(qualityFor(song));
        while (SystemClock.elapsedRealtime() < end) {
            if (queue.current() != song) return false;
            if (Library.get(this).isDownloaded(song.videoId) || cache.cached(song.videoId, itags) != null) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return true;
    }

    private void addHistory(final Song s) {
        if (!prefs.playHistory()) return;
        background.execute(new Runnable() {
            @Override
            public void run() {
                Library.get(PlayerService.this).addHistory(s);
            }
        });
    }

    // ------------------------------------------------------------------ focus, noisy

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(final int change) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onFocus(change);
                }
            });
        }
    };

    private void onFocus(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                duck = 1f;
                applyVolumeAll();
                if (pausedByFocus && prefs.resumeAfterCall()) {
                    pausedByFocus = false;
                    play();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (prefs.duck()) {
                    duck = 0.2f;
                    applyVolumeAll();
                    break;
                }
                // fall through: pause like a call
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (wantPlaying) {
                    pause();
                    pausedByFocus = true;
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                hasFocus = false;
                pause();
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        int r = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        audio.abandonAudioFocus(focusListener);
        hasFocus = false;
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (prefs.pauseOnUnplug()) pause();
        }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        noisyRegistered = false;
    }

    @SuppressWarnings("deprecation")
    private NetworkInfo network() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        return cm == null ? null : cm.getActiveNetworkInfo();
    }

    @SuppressWarnings("deprecation")
    private boolean onWifi() {
        NetworkInfo n = network();
        return n != null && n.isConnected() && (n.getType() == ConnectivityManager.TYPE_WIFI
                || n.getType() == 9 /* ETHERNET */);
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        NetworkInfo n = network();
        return n != null && n.isConnected();
    }

    // ------------------------------------------------------------------ notification & remote

    private void stateChanged(boolean trackChanged) {
        for (Listener l : new ArrayList<Listener>(listeners)) l.onPlayerState();
        updateControls(trackChanged);
    }

    private void queueChanged() {
        for (Listener l : new ArrayList<Listener>(listeners)) l.onQueueChanged();
        stateChanged(false);
        saveSoon();
        maybeLoadRadio();
        prefetch();
    }

    private void error(String message) {
        for (Listener l : new ArrayList<Listener>(listeners)) l.onPlayerError(message);
    }

    private void loadArt(final Song s) {
        loadArt(s, false);
    }

    /** {@code force}: load again even if this song's art is there (the size setting changed). */
    private void loadArt(final Song s, boolean force) {
        if ((!force && s.videoId.equals(artFor)) || s.thumb.length() == 0) return;
        // Sizes follow the cover-quality setting (Wi-Fi / mobile), within what may cross Binder
        // (1 MB per transaction): MediaSession scales to at most 320 dp itself; the 4.x lock
        // screen gets at most 512 px, sent as RGB_565 (~0.5 MB).
        Images images = Images.get(this);
        float density = getResources().getDisplayMetrics().density;
        int lockPx = Build.VERSION.SDK_INT >= 21 ? images.px(213, (int) (320 * density)) : images.px(171, 512);
        int iconPx = images.px(64, Build.VERSION.SDK_INT >= 21 ? 256 : 192);
        Bitmap loaded = images.loadSync(s.thumb, lockPx);
        final Bitmap b = scaled(loaded, lockPx);
        final Bitmap small = squared(loaded, iconPx); // square for the notification and widget
        handler.post(new Runnable() {
            @Override
            public void run() {
                Song c = queue.current();
                if (c == null || !c.videoId.equals(s.videoId)) return;
                art = b;
                notificationArt = small;
                artFor = s.videoId;
                updateControls(true);
            }
        });
    }

    /** Center-cropped to a square of {@code size} pixels (video thumbnails are 16:9). */
    private static Bitmap squared(Bitmap b, int size) {
        if (b == null) return null;
        int side = Math.min(b.getWidth(), b.getHeight());
        try {
            Bitmap crop = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            return side > size ? Bitmap.createScaledBitmap(crop, size, size, true) : crop;
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap scaled(Bitmap b, int max) {
        if (b == null) return null;
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= max && h <= max) return b;
        float f = max / (float) Math.max(w, h);
        try {
            return Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * f)), Math.max(1, (int) (h * f)), true);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private void notifySafe(NotificationManager nm, Notification n) {
        try {
            nm.notify(NOTIFICATION_ID, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "notify: " + e);
        }
    }

    private int remoteState() {
        if (cur == null) return wantPlaying ? Remote.BUFFERING : (queue.current() == null ? Remote.STOPPED : Remote.PAUSED);
        if (isLoading()) return Remote.BUFFERING;
        return isPlaying() ? Remote.PLAYING : Remote.PAUSED;
    }

    private void updateControls(boolean trackChanged) {
        updateWidget();
        Song s = queue.current();
        if (s == null) return;
        if (!s.videoId.equals(artFor)) {
            if (trackChanged) art = null;
        }
        if (remote != null) {
            try {
                remote.update(remoteState(), position(), duration(), s.title, s.artist, s.album,
                        s.videoId.equals(artFor) ? art : null, trackChanged);
            } catch (RuntimeException e) {
                Log.w(TAG, "remote: " + e);
            }
        }
        boolean playing = wantPlaying;
        if (!playing && !foreground && cur == null) return; // nothing to show yet
        Notification n = buildNotification(s, playing);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (playing) {
            if (!foreground) {
                try {
                    startForeground(NOTIFICATION_ID, n);
                    foreground = true;
                } catch (RuntimeException e) {
                    // Android 12+: not allowed from the background; keep a normal notification.
                    Log.w(TAG, "startForeground: " + e);
                    notifySafe(nm, n);
                }
            } else {
                notifySafe(nm, n);
            }
        } else {
            if (foreground) stopForegroundCompat(false);
            notifySafe(nm, n);
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat(boolean removeNotification) {
        stopForeground(removeNotification);
        foreground = false;
    }

    private Notification buildNotification(Song s, boolean playing) {
        // Android 5+: the MediaStyle notification (with its channel on 8+) needs the session.
        if (Build.VERSION.SDK_INT >= 21) ensureRemote();
        Remote.NotificationData d = new Remote.NotificationData();
        d.title = s.title;
        d.text = s.artist;
        d.sub = s.album;
        d.art = s.videoId.equals(artFor) ? notificationArt : null;
        d.playing = playing;
        d.smallIcon = R.drawable.ic_stat_note;
        d.channel = CHANNEL;
        Intent open = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        d.content = PendingIntent.getActivity(this, 10, open, piFlags());
        d.delete = service(ACTION_STOP, 11);
        d.previous = service(ACTION_PREVIOUS, 12);
        d.toggle = service(ACTION_TOGGLE, 13);
        d.next = service(ACTION_NEXT, 14);
        d.close = service(ACTION_STOP, 15);
        Notification n = remote != null ? remote.notification(d) : null;
        return n != null ? n : LegacyNotification.build(this, d);
    }

    private PendingIntent service(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, piFlags());
    }

    static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    // ------------------------------------------------------------------ persistence

    private final Runnable saveTask = new Runnable() {
        @Override
        public void run() {
            save();
        }
    };

    private void saveSoon() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 2000);
    }

    private void save() {
        lastSave = SystemClock.elapsedRealtime();
        try {
            JSONObject o = new JSONObject();
            o.put("queue", queue.toJson());
            o.put("position", position());
            o.put("radio", radio.active());
            final String text = o.toString();
            background.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        File f = new File(getFilesDir(), "queue.json");
                        File tmp = new File(getFilesDir(), "queue.json.tmp");
                        FileOutputStream out = new FileOutputStream(tmp);
                        try {
                            out.write(text.getBytes("UTF-8"));
                        } finally {
                            out.close();
                        }
                        tmp.renameTo(f);
                    } catch (Exception e) {
                        Log.w(TAG, "save queue: " + e);
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "save queue: " + e);
        }
    }

    private void restore() {
        File f = new File(getFilesDir(), "queue.json");
        if (!f.exists()) return;
        try {
            byte[] data = com.ytmlegacy.android.net.Http.readAll(new java.io.FileInputStream(f), 0);
            JSONObject o = new JSONObject(new String(data, "UTF-8"));
            JSONObject q = o.optJSONObject("queue");
            if (q != null) queue.fromJson(q);
            restoredPosition = o.optInt("position", 0);
            if (o.optBoolean("radio", false) && queue.current() != null) {
                radio.start("", "", "", queue.snapshot());
            }
        } catch (Exception e) {
            Log.w(TAG, "restore queue: " + e);
        }
    }
}
