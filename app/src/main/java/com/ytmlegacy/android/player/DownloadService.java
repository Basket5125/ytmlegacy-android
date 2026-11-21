package com.ytmlegacy.android.player;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.net.Http;
import com.ytmlegacy.android.ui.Images;
import com.ytmlegacy.android.ui.MainActivity;
import com.ytmlegacy.android.yt.Song;
import com.ytmlegacy.android.yt.Streams;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.List;

/** Downloads songs for offline listening, one at a time, in the background. */
public final class DownloadService extends Service {

    private static final String TAG = "Downloads";
    public static final String ACTION_CANCEL = "ytm.DL_CANCEL";
    private static final String CHANNEL = "downloads";
    private static final int NOTIFICATION_ID = 2;
    private static final int CHUNK = 2 * 1024 * 1024;

    private volatile boolean cancelled;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    /** Queues songs and starts the service. */
    public static void enqueue(Context context, List<Song> songs) {
        Library lib = Library.get(context);
        for (Song s : songs) lib.queueDownload(s);
        context.startService(new Intent(context, DownloadService.class));
    }

    /** Where downloads are stored (app-private; external storage when chosen and present). */
    public static File directory(Context context) {
        File dir = null;
        if (Prefs.get(context).downloadExternal()
                && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            dir = context.getExternalFilesDir("offline");
        }
        if (dir == null) dir = new File(context.getFilesDir(), "offline");
        dir.mkdirs();
        return dir;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(com.ytmlegacy.android.data.Lang.wrap(base));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) Channels.create(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytm:downloads");
        wakeLock.setReferenceCounted(false);
    }

    @TargetApi(26)
    private static final class Channels {
        static void create(Context context) {
            NotificationManager nm = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(CHANNEL,
                    context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            Library lib = Library.get(this);
            for (Library.Download d : lib.downloads(Library.DL_QUEUED)) lib.removeDownload(d.song.videoId);
            return START_NOT_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            cancelled = false;
            Library.get(this).resetRunningDownloads();
            try {
                startForeground(NOTIFICATION_ID, notification(getString(R.string.downloading), "", 0, 0));
            } catch (RuntimeException e) {
                Log.w(TAG, "startForeground: " + e);
            }
            wakeLock.acquire(60L * 60 * 1000);
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    work();
                }
            }, "downloads");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        cancelled = true;
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private void work() {
        Library lib = Library.get(this);
        int done = 0;
        int failed = 0;
        try {
            Library.Download d;
            while (!cancelled && (d = lib.nextQueued()) != null) {
                if (Prefs.get(this).downloadWifiOnly() && !onWifi()) {
                    show(getString(R.string.download_waiting_wifi), "", 0, 0);
                    break;
                }
                lib.setDownloadState(d.song.videoId, Library.DL_RUNNING, null, 0, 0, "");
                try {
                    download(d.song);
                    done++;
                } catch (Exception e) {
                    Log.w(TAG, "download " + d.song.videoId + " failed: " + e);
                    failed++;
                    lib.setDownloadState(d.song.videoId, Library.DL_FAILED, null, 0, 0,
                            e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        } finally {
            stopForegroundCompat();
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (done + failed > 0 && !cancelled) {
                nm.notify(NOTIFICATION_ID, notification(getString(R.string.downloads_finished),
                        getString(R.string.downloads_summary, done, failed), -1, 0));
            } else if (!(Prefs.get(this).downloadWifiOnly() && !onWifi())) {
                nm.cancel(NOTIFICATION_ID);
            }
            if (wakeLock.isHeld()) wakeLock.release();
            stopSelf();
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        stopForeground(true);
    }

    private void download(Song song) throws Exception {
        Prefs prefs = Prefs.get(this);
        boolean classic = prefs.needClassicMp4();
        int quality = prefs.downloadQuality();
        Streams streams = Streams.get(this);
        Streams.Stream stream = streams.resolve(song.videoId, quality, !classic);
        File dir = directory(this);
        String ext = stream.classic ? "mp4" : stream.extension();
        File target = new File(dir, song.videoId + "_" + stream.itag + "." + ext);
        File part = new File(target.getPath() + ".part");
        // A song already in the playback cache is copied instead of downloaded again.
        File cached = AudioCache.get(this).cached(song.videoId, new int[]{stream.itag});
        if (cached != null && cached.getName().endsWith("." + ext)) {
            copy(cached, part);
        } else {
            fetch(stream, part, song);
        }
        if (cancelled) {
            part.delete();
            throw new IOException("cancelled");
        }
        if (classic && ext.equals("m4a") && Mp4Classic.isFragmented(part)) {
            File converted = new File(target.getPath() + ".conv");
            Mp4Classic.convert(part, converted);
            part.delete();
            if (!converted.renameTo(target)) throw new IOException("rename failed");
        } else if (!part.renameTo(target)) {
            throw new IOException("rename failed");
        }
        // Cover art next to the audio, so it shows offline.
        Song saved = song.copy();
        if (song.thumb.length() > 0 && song.thumb.startsWith("http")) {
            try {
                byte[] img = Http.get(Images.sized(song.thumb, 544), null, 8000, 15000, 4 * 1024 * 1024);
                File cover = new File(dir, song.videoId + ".jpg");
                FileOutputStream out = new FileOutputStream(cover);
                try {
                    out.write(img);
                } finally {
                    out.close();
                }
                saved.thumb = "file://" + cover.getAbsolutePath();
            } catch (IOException e) {
                Log.i(TAG, "cover of " + song.videoId + ": " + e.getMessage());
            }
        }
        Library lib = Library.get(this);
        lib.queueDownloadMetadata(saved);
        lib.setDownloadState(song.videoId, Library.DL_DONE, target.getAbsolutePath(), stream.itag, target.length(), "");
    }

    private void fetch(Streams.Stream stream, File part, Song song) throws Exception {
        RandomAccessFile out = new RandomAccessFile(part, "rw");
        try {
            out.setLength(0);
            long have = 0;
            long total = stream.length;
            Streams.Stream current = stream;
            int retries = 0;
            long lastShown = 0;
            while (!cancelled && (total <= 0 || have < total)) {
                long end = have + CHUNK - 1;
                if (total > 0) end = Math.min(end, total - 1);
                HttpURLConnection conn = null;
                try {
                    conn = Http.openRange(current.url, current.userAgent, have, end, 8000, 20000);
                    if (total <= 0) {
                        String range = conn.getHeaderField("Content-Range");
                        int slash = range == null ? -1 : range.lastIndexOf('/');
                        total = slash < 0 ? 0 : Long.parseLong(range.substring(slash + 1).trim());
                    }
                    InputStream in = conn.getInputStream();
                    byte[] buffer = new byte[32768];
                    long want = end - have + 1;
                    int n;
                    out.seek(have);
                    while (want > 0 && !cancelled && (n = in.read(buffer, 0, (int) Math.min(buffer.length, want))) > 0) {
                        out.write(buffer, 0, n);
                        have += n;
                        want -= n;
                    }
                    in.close();
                    retries = 0;
                    if (total <= 0) break;
                } catch (Http.StatusException e) {
                    if ((e.status == 403 || e.status == 410 || e.status == 401) && retries++ < 3) {
                        Streams.get(this).forget(stream.videoId);
                        current = Streams.get(this).resolve(stream.videoId,
                                Prefs.get(this).downloadQuality(), stream.isWebm());
                        if (current.itag != stream.itag) throw new IOException("stream changed format");
                    } else {
                        throw e;
                    }
                } catch (IOException e) {
                    if (++retries > 5) throw e;
                    Thread.sleep(1000L * retries);
                } finally {
                    if (conn != null) conn.disconnect();
                }
                long now = System.currentTimeMillis();
                if (now - lastShown > 700) {
                    lastShown = now;
                    int left = Library.get(this).downloads(Library.DL_QUEUED).size();
                    show(song.title, getString(R.string.downloads_left, left),
                            total > 0 ? (int) (have * 100 / total) : 0, left);
                }
            }
            if (total > 0 && have < total && !cancelled) throw new IOException("incomplete download");
        } finally {
            out.close();
        }
    }

    private static void copy(File from, File to) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    private void show(String title, String text, int progress, int left) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, notification(title, text, progress, left));
    }

    /** progress &lt; 0: finished (no bar). */
    @SuppressWarnings("deprecation")
    private Notification notification(String title, String text, int progress, int left) {
        Intent open = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_DOWNLOADS);
        PendingIntent content = PendingIntent.getActivity(this, 20, open, PlayerService.piFlags());
        if (Build.VERSION.SDK_INT >= 11) {
            return Builders.build(this, title, text, progress, content, CHANNEL,
                    progress < 0 ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_download);
        }
        Notification n = new Notification(progress < 0 ? android.R.drawable.stat_sys_download_done
                : android.R.drawable.stat_sys_download, null, System.currentTimeMillis());
        android.widget.RemoteViews v = new android.widget.RemoteViews(getPackageName(), R.layout.notification_download);
        v.setTextViewText(R.id.n_title, title);
        v.setTextViewText(R.id.n_text, text);
        v.setProgressBar(R.id.n_progress, 100, Math.max(0, progress), false);
        v.setViewVisibility(R.id.n_progress, progress < 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        n.contentView = v;
        n.contentIntent = content;
        if (progress >= 0) n.flags |= Notification.FLAG_ONGOING_EVENT;
        else n.flags |= Notification.FLAG_AUTO_CANCEL;
        return n;
    }

    @TargetApi(11)
    private static final class Builders {
        @SuppressWarnings("deprecation")
        static Notification build(Context c, String title, String text, int progress, PendingIntent content,
                                  String channel, int icon) {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel)
                    : new Notification.Builder(c);
            b.setSmallIcon(icon).setContentTitle(title).setContentText(text).setContentIntent(content)
                    .setOngoing(progress >= 0).setAutoCancel(progress < 0);
            if (Build.VERSION.SDK_INT >= 14 && progress >= 0) b.setProgress(100, progress, progress == 0);
            if (progress >= 0) {
                Intent cancel = new Intent(c, DownloadService.class).setAction(ACTION_CANCEL);
                PendingIntent pi = PendingIntent.getService(c, 21, cancel, PlayerService.piFlags());
                if (Build.VERSION.SDK_INT >= 16) {
                    b.addAction(android.R.drawable.ic_menu_close_clear_cancel, c.getString(R.string.cancel), pi);
                }
            }
            return Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean onWifi() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected() && (n.getType() == ConnectivityManager.TYPE_WIFI || n.getType() == 9);
    }
}
