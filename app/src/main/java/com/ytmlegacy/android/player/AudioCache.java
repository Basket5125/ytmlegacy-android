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
        return fetch(stream, false);
    }

    /**
     * {@code parallel}: download the file over several connections at once (old phones get
     * far more out of their Wi-Fi that way). Only for files that are used when complete.
     */
    public Fetch fetch(Streams.Stream stream, boolean parallel) {
        String key = stream.videoId + "_" + stream.itag;
        synchronized (fetches) {
            Fetch f = fetches.get(key);
            if (f != null && !f.failed && !f.cancelled) return f;
            f = new Fetch(stream, new File(area(), name(stream.videoId, stream.itag, stream.classic ? "mp4" : stream.extension())));
            f.parallel = parallel && stream.length > 4 * CHUNK_PART;
            fetches.put(key, f);
            pool.execute(f);
            return f;
        }
    }

    private static final int CHUNK_PART = 256 * 1024;
    private static final int PARTS = 3;
    private final ExecutorService partPool = Executors.newCachedThreadPool(new ThreadFactory() {
        private int n;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "audio-part-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * A complete, classic-MP4 file of the song, for players that cannot read fragments
     * (Android 2.x): a download, the cache, or fetched now over parallel connections. Blocks.
     */
    public File classicFile(String videoId, int quality) throws Exception {
        File f = com.ytmlegacy.android.data.Library.get(context).downloadedFile(videoId);
        if (f != null && !f.getName().endsWith(".webm")) return f.getName().endsWith(".m4a") ? classicOf(f) : f;
        f = cached(videoId, Streams.itagsFor(quality));
        if (f != null && !f.getName().endsWith(".webm")) return f.getName().endsWith(".m4a") ? classicOf(f) : f;
        Streams.Stream st = Streams.get(context).resolve(videoId, quality, false);
        Fetch fetch = fetch(st, true);
        if (!fetch.awaitComplete(240000)) {
            throw new IOException(fetch.error().length() > 0 ? fetch.error() : "download timed out");
        }
        return st.classic ? fetch.target : classicOf(fetch.target);
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
        volatile boolean parallel;
        volatile boolean failed;
        volatile boolean cancelled;
        private volatile String error = "";

        Fetch(Streams.Stream stream, File target) {
            this.stream = stream;
            this.target = target;
            this.part = new File(target.getPath() + ".part");
            this.total = stream.length;
        }

        void runParallel() {
            runParallelImpl(this);
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
            if (parallel) {
                runParallel();
                return;
            }
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

    /** The whole file over {@link #PARTS} connections, each writing its own byte range. */
    private void runParallelImpl(final Fetch f) {
        final long length = f.stream.length;
        final java.util.concurrent.atomic.AtomicReference<Streams.Stream> link =
                new java.util.concurrent.atomic.AtomicReference<Streams.Stream>(f.stream);
        final java.util.concurrent.atomic.AtomicReference<Exception> failure =
                new java.util.concurrent.atomic.AtomicReference<Exception>();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(PARTS);
        try {
            RandomAccessFile pre = new RandomAccessFile(f.part, "rw");
            try {
                pre.setLength(length);
            } finally {
                pre.close();
            }
            long size = (length + PARTS - 1) / PARTS;
            for (int i = 0; i < PARTS; i++) {
                final long from = i * size;
                final long to = Math.min(length, from + size) - 1;
                partPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (from <= to) downloadRange(f, link, from, to);
                        } catch (Exception e) {
                            failure.compareAndSet(null, e);
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            done.await();
            if (f.cancelled) {
                f.part.delete();
                return;
            }
            if (failure.get() != null) throw failure.get();
            if (!f.part.renameTo(f.target)) throw new IOException("rename failed");
            synchronized (f) {
                f.have = length;
                f.total = length;
                f.complete = true;
                f.notifyAll();
            }
            Log.i(TAG, "cached " + f.target.getName() + " (" + length / 1024 + " KB, " + PARTS + " connections)");
            trim();
        } catch (Exception e) {
            Log.w(TAG, "fetch " + f.stream.videoId + " failed: " + e);
            f.error = e.getMessage() == null ? e.toString() : e.getMessage();
            synchronized (f) {
                f.failed = true;
                f.notifyAll();
            }
            f.part.delete();
        } finally {
            synchronized (fetches) {
                String key = f.stream.videoId + "_" + f.stream.itag;
                if (fetches.get(key) == f && !f.complete) fetches.remove(key);
            }
        }
    }

    private void downloadRange(Fetch f, java.util.concurrent.atomic.AtomicReference<Streams.Stream> link,
                               long from, long to) throws Exception {
        RandomAccessFile out = new RandomAccessFile(f.part, "rw");
        try {
            long pos = from;
            int retries = 0;
            byte[] buffer = new byte[32768];
            while (pos <= to && !f.cancelled) {
                long end = Math.min(to, pos + CHUNK - 1);
                HttpURLConnection conn = null;
                try {
                    Streams.Stream s = link.get();
                    conn = Http.openRange(s.url, s.userAgent, pos, end, 10000, 20000);
                    InputStream in = conn.getInputStream();
                    out.seek(pos);
                    long want = end - pos + 1;
                    int n;
                    while (want > 0 && !f.cancelled && (n = in.read(buffer, 0, (int) Math.min(buffer.length, want))) > 0) {
                        out.write(buffer, 0, n);
                        pos += n;
                        want -= n;
                    }
                    in.close();
                    retries = 0;
                } catch (Http.StatusException e) {
                    if ((e.status == 403 || e.status == 410 || e.status == 401) && retries++ < 3) {
                        synchronized (link) {
                            if (link.get().url.equals(f.stream.url) || retries > 1) {
                                Streams.get(context).forget(f.stream.videoId);
                                Streams.Stream fresh = Streams.get(context).resolve(f.stream.videoId,
                                        qualityOf(f.stream.itag), f.stream.isWebm());
                                if (fresh.itag != f.stream.itag) throw new IOException("stream changed format");
                                link.set(fresh);
                            }
                        }
                    } else {
                        throw e;
                    }
                } catch (IOException e) {
                    if (f.cancelled) break;
                    if (++retries > 5) throw e;
                    Thread.sleep(700L * retries);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        } finally {
            out.close();
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
