package com.ytmlegacy.android.player;

import android.content.Context;
import android.util.Log;

import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.net.Http;
import com.ytmlegacy.android.yt.Streams;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Audio files on disk: the playback cache (LRU, size-capped, can be switched off) and the
 * downloads in progress for it. Files are fetched from googlevideo in Range chunks: a plain GET
 * of a stream is throttled to a trickle, ranged requests come at full speed.
 */
public final class AudioCache {

    private static final String TAG = "AudioCache";
    private static final int FIRST_CHUNK = 256 * 1024;
    private static final int CHUNK = 2 * 1024 * 1024;

    private static AudioCache instance;
    private final Context context;
    private final Prefs prefs;
    private final File dir;
    private final File tmpDir;
    private final Map<String, Fetch> fetches = new HashMap<String, Fetch>();
    /** Videos that must not be evicted now (playing / next). */
    private final Set<String> keep = new HashSet<String>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3, new ThreadFactory() {
        private int n;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "audio-fetch-" + (++n));
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    private AudioCache(Context context) {
        this.context = context.getApplicationContext();
        prefs = Prefs.get(context);
        dir = new File(context.getCacheDir(), "audio");
        tmpDir = new File(context.getCacheDir(), "audio_tmp");
        dir.mkdirs();
        tmpDir.mkdirs();
        // Leftovers of an interrupted run.
        deleteChildren(tmpDir);
        File[] parts = dir.listFiles();
        if (parts != null) for (File f : parts) if (f.getName().endsWith(".part") || f.getName().endsWith(".tmp")) f.delete();
    }

    public static synchronized AudioCache get(Context context) {
        if (instance == null) instance = new AudioCache(context);
        return instance;
    }

    private File area() {
        return prefs.cacheEnabled() ? dir : tmpDir;
    }

    // ------------------------------------------------------------------ lookups

    /** A complete cached file of the video with one of the itags (in order), or null. */
    public File cached(String videoId, int[] itags) {
        for (File base : new File[]{dir, tmpDir}) {
            for (int itag : itags) {
                for (String ext : new String[]{"m4a", "webm", "mp4"}) {
                    File f = new File(base, name(videoId, itag, ext));
                    if (f.exists() && f.length() > 0) {
                        f.setLastModified(System.currentTimeMillis());
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** The classic-MP4 copy of a cached fMP4, converted on first use. */
    public File classicOf(File fragmented) throws IOException {
        File out = new File(fragmented.getParentFile(), fragmented.getName().replace(".m4a", ".c.m4a"));
        synchronized (this) {
            if (out.exists() && out.length() > 0) return out;
            if (!Mp4Classic.isFragmented(fragmented)) return fragmented;
            long t = System.currentTimeMillis();
            Mp4Classic.convert(fragmented, out);
            Log.i(TAG, "classic " + out.getName() + " in " + (System.currentTimeMillis() - t) + " ms");
        }
        trim();
        return out;
    }

    static String name(String videoId, int itag, String ext) {
        return videoId + "_" + itag + "." + ext;
    }

    // ------------------------------------------------------------------ fetching

    /** The running or new fetch of a stream into the cache. */
    public Fetch fetch(Streams.Stream stream) {
        String key = stream.videoId + "_" + stream.itag;
        synchronized (fetches) {
            Fetch f = fetches.get(key);
            if (f != null && !f.failed && !f.cancelled) return f;
            f = new Fetch(stream, new File(area(), name(stream.videoId, stream.itag, stream.classic ? "mp4" : stream.extension())));
            fetches.put(key, f);
            pool.execute(f);
            return f;
        }
    }

    /** Stops background fetches of videos not in {@code wanted}. */
    public void cancelExcept(Set<String> wanted) {
        synchronized (fetches) {
            for (Fetch f : fetches.values()) {
                if (!wanted.contains(f.stream.videoId) && !f.complete) f.cancel();
            }
        }
    }

    /** One stream being written to disk; readers wait for the bytes they need. */
    public final class Fetch implements Runnable {
        public final Streams.Stream stream;
        public final File target;
        private final File part;
        private volatile long total;
        private volatile long have;
        volatile boolean complete;
        volatile boolean failed;
        volatile boolean cancelled;
        private volatile String error = "";

        Fetch(Streams.Stream stream, File target) {
            this.stream = stream;
            this.target = target;
            this.part = new File(target.getPath() + ".part");
            this.total = stream.length;
        }

        public void cancel() {
            cancelled = true;
            synchronized (this) {
                notifyAll();
            }
        }

        public boolean isComplete() {
            return complete;
        }

        /** The file to read from while fetching (the .part, or the final file when done). */
        public File file() {
            return complete ? target : part;
        }

        /** Waits until the total length is known; -1 on failure. */
        public long awaitTotal(long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (this) {
                while (total <= 0 && !failed && !cancelled) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    wait(Math.min(left, 500));
                }
            }
            return failed || cancelled ? -1 : total;
        }

        /** Waits until byte {@code position} is on disk (or the fetch ends); returns bytes available. */
        public long awaitBytes(long position, long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (this) {
                while (have <= position && !complete && !failed && !cancelled) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    wait(Math.min(left, 500));
                }
            }
            return have;
        }

        public boolean awaitComplete(long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (this) {
                while (!complete && !failed && !cancelled) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) break;
                    wait(Math.min(left, 500));
                }
            }
            return complete;
        }

        public boolean failed() {
            return failed || cancelled;
        }

        public String error() {
            return error;
        }

        public long have() {
            return have;
        }

        public long total() {
            return total;
        }

        @Override
        public void run() {
            Streams.Stream current = stream;
            RandomAccessFile out = null;
            try {
                out = new RandomAccessFile(part, "rw");
                out.setLength(0);
                int retries = 0;
                while (!cancelled && (total <= 0 || have < total)) {
                    long want = have == 0 ? FIRST_CHUNK : CHUNK;
                    long end = have + want - 1;
                    if (total > 0) end = Math.min(end, total - 1);
                    HttpURLConnection conn = null;
                    try {
                        conn = Http.openRange(current.url, current.userAgent, have, end, 8000, 15000);
                        if (total <= 0) {
                            long t = totalFrom(conn.getHeaderField("Content-Range"));
                            synchronized (this) {
                                total = t;
                                notifyAll();
                            }
                        }
                        InputStream in = conn.getInputStream();
                        byte[] buffer = new byte[32768];
                        long wanted = end - have + 1;
                        int n;
                        out.seek(have);
                        while (wanted > 0 && !cancelled && (n = in.read(buffer, 0, (int) Math.min(buffer.length, wanted))) > 0) {
                            out.write(buffer, 0, n);
                            wanted -= n;
                            synchronized (this) {
                                have += n;
                                notifyAll();
                            }
                        }
                        in.close();
                        retries = 0;
                        if (total <= 0) break;
                    } catch (Http.StatusException e) {
                        if ((e.status == 403 || e.status == 410 || e.status == 401) && retries < 3) {
                            // Link expired or refused: look the stream up again and go on.
                            retries++;
                            Streams.get(context).forget(stream.videoId);
                            Streams.Stream fresh = Streams.get(context).resolve(stream.videoId,
                                    qualityOf(stream.itag), stream.isWebm());
                            if (fresh.itag != stream.itag) throw new IOException("stream changed format");
                            current = fresh;
                        } else if (e.status == 416 && total > 0) {
                            break;
                        } else {
                            throw e;
                        }
                    } catch (IOException e) {
                        if (cancelled) break;
                        if (++retries > 4) throw e;
                        Thread.sleep(500L * retries);
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
                out.close();
                out = null;
                if (cancelled) {
                    part.delete();
                    return;
                }
                if (total > 0 && have < total) throw new IOException("incomplete " + have + "/" + total);
                if (!part.renameTo(target)) throw new IOException("rename failed");
                synchronized (this) {
                    complete = true;
                    notifyAll();
                }
                Log.i(TAG, "cached " + target.getName() + " (" + have / 1024 + " KB)");
                trim();
            } catch (Exception e) {
                Log.w(TAG, "fetch " + stream.videoId + " failed: " + e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                synchronized (this) {
                    failed = true;
                    notifyAll();
                }
                part.delete();
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) { }
                synchronized (fetches) {
                    if (fetches.get(stream.videoId + "_" + stream.itag) == this && !complete) {
                        fetches.remove(stream.videoId + "_" + stream.itag);
                    }
                }
            }
        }
    }

    private static int qualityOf(int itag) {
        if (itag == 139) return Prefs.Q_LOW;
        if (itag == 251) return Prefs.Q_HIGH;
        return Prefs.Q_NORMAL;
    }

    private static long totalFrom(String contentRange) {
        if (contentRange == null) return 0;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0) return 0;
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ housekeeping

    public void setKeep(Set<String> videoIds) {
        synchronized (keep) {
            keep.clear();
            keep.addAll(videoIds);
        }
        trim();
    }

    private boolean kept(String fileName) {
        synchronized (keep) {
            for (String id : keep) if (fileName.startsWith(id + "_")) return true;
        }
        return false;
    }

    private boolean busy(String fileName) {
        synchronized (fetches) {
            for (Fetch f : fetches.values()) {
                if (!f.complete && fileName.startsWith(f.stream.videoId + "_")) return true;
            }
        }
        return false;
    }

    /** Keeps the cache under its size; with the cache off only the kept tracks stay. */
    public synchronized void trim() {
        File[] temp = tmpDir.listFiles();
        if (temp != null) {
            for (File f : temp) if (!kept(f.getName()) && !busy(f.getName())) f.delete();
        }
        if (!prefs.cacheEnabled()) {
            File[] all = dir.listFiles();
            if (all != null) for (File f : all) if (!kept(f.getName()) && !busy(f.getName())) f.delete();
            return;
        }
        long limit = prefs.cacheMb() * 1024L * 1024L;
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= limit) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        for (File f : files) {
            if (total <= limit) break;
            if (kept(f.getName()) || busy(f.getName())) continue;
            long size = f.length();
            if (f.delete()) total -= size;
        }
    }

    public long sizeBytes() {
        long total = 0;
        for (File base : new File[]{dir, tmpDir}) {
            File[] files = base.listFiles();
            if (files != null) for (File f : files) total += f.length();
        }
        return total;
    }

    public void clear() {
        List<String> skip = new ArrayList<String>();
        synchronized (keep) {
            skip.addAll(keep);
        }
        for (File base : new File[]{dir, tmpDir}) {
            File[] files = base.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (busy(f.getName())) continue;
                boolean k = false;
                for (String id : skip) if (f.getName().startsWith(id + "_")) k = true;
                if (!k) f.delete();
            }
        }
    }

    private static void deleteChildren(File d) {
        File[] files = d.listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
