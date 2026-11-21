package com.ytmlegacy.android.net;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Small HttpURLConnection helpers. Logs URL and status only, never headers or bodies. */
public final class Http {

    private static final String TAG = "Http";

    public static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private Http() { }

    /** An HTTP answer outside 2xx. */
    public static final class StatusException extends IOException {
        public final int status;

        public StatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static String postJson(String url, String json, Map<String, String> headers,
                                  int connectMs, int readMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            byte[] body = json.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(body);
            } finally {
                out.close();
            }
            int status = conn.getResponseCode();
            Log.i(TAG, "POST " + shortUrl(url) + " -> " + status);
            InputStream in = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = in == null ? "" : new String(readAll(decode(conn, in), 0), "UTF-8");
            if (status < 200 || status >= 300) {
                throw new StatusException(status, "HTTP " + status + " " + shortUrl(url));
            }
            return text;
        } catch (IOException e) {
            if (conn != null) conn.disconnect();
            throw e;
        }
    }

    /** GET into memory, at most {@code maxBytes} (0 = no limit). */
    public static byte[] get(String url, Map<String, String> headers, int connectMs, int readMs,
                             int maxBytes) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.i(TAG, "GET " + shortUrl(url) + " -> " + status);
                throw new StatusException(status, "HTTP " + status + " " + shortUrl(url));
            }
            return readAll(decode(conn, conn.getInputStream()), maxBytes);
        } catch (IOException e) {
            if (conn != null) conn.disconnect();
            throw e;
        }
    }

    /**
     * Opens bytes {@code start}..{@code end} (inclusive; end &lt; 0 = to the end) of a media URL.
     * The caller reads and closes the stream. Throws {@link StatusException} on 4xx/5xx.
     */
    public static HttpURLConnection openRange(String url, String userAgent, long start, long end,
                                              int connectMs, int readMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Range", "bytes=" + start + "-" + (end >= 0 ? String.valueOf(end) : ""));
        int status;
        try {
            status = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
        if (status != 206 && status != 200) {
            Log.i(TAG, "RANGE " + shortUrl(url) + " -> " + status);
            conn.disconnect();
            throw new StatusException(status, "HTTP " + status + " for media");
        }
        if (status == 200 && start > 0) {
            // Server ignored the range: skip to the wanted offset.
            InputStream in = conn.getInputStream();
            long skipped = 0;
            byte[] scratch = new byte[16384];
            while (skipped < start) {
                int n = in.read(scratch, 0, (int) Math.min(scratch.length, start - skipped));
                if (n < 0) {
                    conn.disconnect();
                    throw new IOException("short body while skipping");
                }
                skipped += n;
            }
        }
        return conn;
    }

    public static InputStream decode(HttpURLConnection conn, InputStream in) throws IOException {
        String encoding = conn.getContentEncoding();
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            return new GZIPInputStream(in, 16384);
        }
        return in;
    }

    public static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (maxBytes > 0 && out.size() > maxBytes) throw new IOException("answer too large");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Host and path only: query strings of stream URLs carry signatures. */
    public static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
