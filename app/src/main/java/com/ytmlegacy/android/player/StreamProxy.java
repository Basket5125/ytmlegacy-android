package com.ytmlegacy.android.player;

import android.content.Context;
import android.util.Log;

import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.yt.Streams;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * A tiny HTTP server on 127.0.0.1 that feeds MediaPlayer. It serves a download, a cached file
 * or a stream while it is still being fetched (MediaPlayer's own HTTP client asks without a
 * Range header, which googlevideo throttles, so it never talks to YouTube directly).
 */
public final class StreamProxy {

    private static final String TAG = "StreamProxy";
    private static StreamProxy instance;

    private final Context context;
    private ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactory() {
        private int n;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "proxy-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    /** Errors from the last request per video, for the player's error message. */
    private final java.util.Map<String, String> lastError = new java.util.HashMap<String, String>();

    private StreamProxy(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized StreamProxy get(Context context) {
        if (instance == null) instance = new StreamProxy(context);
        instance.start();
        return instance;
    }

    private synchronized void start() {
        if (server != null && !server.isClosed()) return;
        try {
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot open local port", e);
        }
        final ServerSocket s = server;
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try {
                        final Socket socket = s.accept();
                        workers.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(socket);
                            }
                        });
                    } catch (IOException e) {
                        if (!s.isClosed()) Log.w(TAG, "accept: " + e);
                    }
                }
            }
        }, "proxy-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** The URL MediaPlayer opens for a video at a quality. */
    public String url(String videoId, int quality) {
        return "http://127.0.0.1:" + server.getLocalPort() + "/a/" + videoId + "?q=" + quality
                + "&n=" + System.nanoTime();
    }

    /** Videos the player has been sent audio bytes for. */
    private final java.util.Set<String> served = new java.util.HashSet<String>();

    /** Forgets what happened to a video's earlier requests (called when it starts playing). */
    public void reset(String videoId) {
        synchronized (lastError) {
            lastError.remove(videoId);
            served.remove(videoId);
        }
    }

    /** True when the player received audio of the video (so a failure is about the format). */
    public boolean served(String videoId) {
        synchronized (lastError) {
            return served.contains(videoId);
        }
    }

    private void markServed(String videoId) {
        synchronized (lastError) {
            served.add(videoId);
        }
    }

    public String lastError(String videoId) {
        synchronized (lastError) {
            String e = lastError.get(videoId);
            return e == null ? "" : e;
        }
    }

    private void handle(Socket socket) {
        String videoId = "";
        try {
            socket.setSoTimeout(30000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String rangeHeader = null;
            String line;
            while ((line = reader.readLine()) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("range")) {
                    rangeHeader = line.substring(colon + 1).trim();
                }
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            boolean head = "HEAD".equals(parts[0]);
            String path = parts[1];
            int q = path.indexOf('?');
            String query = q < 0 ? "" : path.substring(q + 1);
            path = q < 0 ? path : path.substring(0, q);
            if (!path.startsWith("/a/")) {
                status(socket.getOutputStream(), 404, "Not Found");
                return;
            }
            videoId = path.substring(3);
            int quality = Prefs.Q_NORMAL;
            for (String p : query.split("&")) {
                if (p.startsWith("q=")) {
                    try {
                        quality = Integer.parseInt(p.substring(2));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            serve(socket.getOutputStream(), videoId, quality, rangeHeader, head);
        } catch (Exception e) {
            if (!(e instanceof java.net.SocketException)) Log.w(TAG, "request " + videoId + ": " + e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serve(OutputStream out, String videoId, int quality, String range, boolean head)
            throws Exception {
        Prefs prefs = Prefs.get(context);
        boolean classic = prefs.needClassicMp4();
        AudioCache cache = AudioCache.get(context);

        // 1. A download.
        File file = Library.get(context).downloadedFile(videoId);
        // 2. The cache.
        if (file == null) file = cache.cached(videoId, Streams.itagsFor(quality));
        if (file != null) {
            if (classic && file.getName().endsWith(".m4a")) file = cache.classicOf(file);
            if (!(classic && file.getName().endsWith(".webm"))) {
                if (!head) markServed(videoId);
                serveFile(out, file, mimeOf(file.getName()), range, head);
                return;
            }
        }
        // 3. YouTube.
        Streams.Stream stream;
        try {
            stream = Streams.get(context).resolve(videoId, quality, !classic);
        } catch (IOException e) {
            remember(videoId, e.getMessage());
            status(out, 503, "Unavailable");
            return;
        }
        AudioCache.Fetch fetch = cache.fetch(stream);
        if (classic && !stream.classic) {
            if (!fetch.awaitComplete(180000)) {
                remember(videoId, fetch.error());
                status(out, 503, "Unavailable");
                return;
            }
            if (!head) markServed(videoId);
            serveFile(out, cache.classicOf(fetch.target), "audio/mp4", range, head);
            return;
        }
        if (!head) markServed(videoId);
        serveFetch(out, fetch, stream.classic ? "video/mp4" : stream.mime, range, head);
    }

    private void remember(String videoId, String error) {
        synchronized (lastError) {
            lastError.put(videoId, error == null ? "" : error);
        }
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".webm")) return "audio/webm";
        if (name.endsWith(".mp4")) return "video/mp4";
        return "audio/mp4";
    }

    /** {start, end} of a "bytes=a-b" header within a length, or null for no/invalid range. */
    private static long[] range(String header, long length) {
        if (header == null || !header.startsWith("bytes=")) return null;
        String spec = header.substring(6).split(",")[0].trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (a.length() == 0) { // suffix range
                long n = Long.parseLong(b);
                start = Math.max(0, length - n);
                end = length - 1;
            } else {
                start = Long.parseLong(a);
                end = b.length() == 0 ? length - 1 : Math.min(Long.parseLong(b), length - 1);
            }
            if (start > end || start >= length) return new long[]{-1, -1};
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeHeaders(OutputStream out, long length, long[] r, String mime) throws IOException {
        StringBuilder h = new StringBuilder();
        if (r != null) {
            h.append("HTTP/1.1 206 Partial Content\r\n");
            h.append("Content-Range: bytes ").append(r[0]).append('-').append(r[1]).append('/').append(length).append("\r\n");
            h.append("Content-Length: ").append(r[1] - r[0] + 1).append("\r\n");
        } else {
            h.append("HTTP/1.1 200 OK\r\n");
            h.append("Content-Length: ").append(length).append("\r\n");
        }
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
    }

    private static void status(OutputStream out, int code, String text) throws IOException {
        out.write(String.format(Locale.US, "HTTP/1.1 %d %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                code, text).getBytes("ISO-8859-1"));
        out.flush();
    }

    private static void serveFile(OutputStream out, File file, String mime, String rangeHeader, boolean head)
            throws IOException {
        long length = file.length();
        long[] r = range(rangeHeader, length);
        if (r != null && r[0] < 0) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + length
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            return;
        }
        writeHeaders(out, length, r, mime);
        if (head) return;
        long start = r == null ? 0 : r[0];
        long end = r == null ? length - 1 : r[1];
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            in.seek(start);
            byte[] buffer = new byte[32768];
            long left = end - start + 1;
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) break;
                out.write(buffer, 0, n);
                left -= n;
            }
            out.flush();
        } finally {
            in.close();
        }
    }

    private static void serveFetch(OutputStream out, AudioCache.Fetch fetch, String mime, String rangeHeader,
                                   boolean head) throws Exception {
        long length = fetch.awaitTotal(30000);
        if (length <= 0) {
            status(out, 503, "Unavailable");
            return;
        }
        long[] r = range(rangeHeader, length);
        if (r != null && r[0] < 0) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + length
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            return;
        }
        writeHeaders(out, length, r, mime);
        if (head) return;
        long pos = r == null ? 0 : r[0];
        long end = r == null ? length - 1 : r[1];
        RandomAccessFile in = open(fetch);
        try {
            byte[] buffer = new byte[32768];
            while (pos <= end) {
                long have = fetch.isComplete() ? length : fetch.awaitBytes(pos, 30000);
                if (have <= pos) {
                    if (fetch.failed()) throw new IOException("fetch failed: " + fetch.error());
                    if (!fetch.isComplete()) throw new IOException("stalled");
                }
                long available = Math.min(have, end + 1) - pos;
                while (available > 0) {
                    in.seek(pos);
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, available));
                    if (n <= 0) break;
                    out.write(buffer, 0, n);
                    pos += n;
                    available -= n;
                }
            }
            out.flush();
        } finally {
            in.close();
        }
    }

    private static RandomAccessFile open(AudioCache.Fetch fetch) throws IOException {
        for (int i = 0; i < 3; i++) {
            try {
                return new RandomAccessFile(fetch.file(), "r");
            } catch (FileNotFoundException e) {
                // The .part was just renamed to its final name.
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return new RandomAccessFile(fetch.file(), "r");
    }
}
