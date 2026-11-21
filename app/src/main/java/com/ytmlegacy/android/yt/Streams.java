package com.ytmlegacy.android.yt;

import android.content.Context;
import android.util.Log;

import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.net.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stream URLs straight from YouTube's servers (googlevideo), through the InnerTube "player"
 * endpoint. Tested Nov 2026: the VISIONOS client hands out audio URLs that can be read in full
 * (with Range requests) without a PO token; ANDROID gives a classic MP4 (itag 18) as a fallback.
 */
public final class Streams {

    private static final String TAG = "Streams";
    private static final String PLAYER = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false";

    /** Audio, ready for the player. */
    public static final class Stream {
        public String videoId = "";
        public String url = "";
        public int itag;
        public String mime = "";
        public long length;
        public long expiresAt;
        /** Gain (0..1) that brings the track to YouTube's loudness target. */
        public float normalGain = 1f;
        public int durationSec;
        /** User agent the URL was issued for. */
        public String userAgent = "Mozilla/5.0";
        /** A classic (non-fragmented) MP4: plays on any MediaPlayer as it is. */
        public boolean classic;

        public boolean isWebm() {
            return mime.startsWith("audio/webm");
        }

        public String extension() {
            return isWebm() ? "webm" : "m4a";
        }

        boolean fresh() {
            return System.currentTimeMillis() < expiresAt - 10 * 60 * 1000;
        }
    }

    private static final class Client {
        final String name;
        final String id;
        final String version;
        final String userAgent;
        final String[] extra;

        Client(String name, String id, String version, String userAgent, String... extra) {
            this.name = name;
            this.id = id;
            this.version = version;
            this.userAgent = userAgent;
            this.extra = extra;
        }
    }

    private static final Client VISIONOS = new Client("VISIONOS", "101", "0.1", "Mozilla/5.0");
    private static final Client ANDROID = new Client("ANDROID", "3", "21.29.366",
            "com.google.android.youtube/21.29.366 (Linux; U; Android 14; en_US; Pixel 7 Build/UP1A.231005.007) gzip",
            "osName", "Android", "osVersion", "14", "androidSdkVersion", "34",
            "deviceMake", "Google", "deviceModel", "Pixel 7");

    private static Streams instance;
    private final Context context;
    private final Map<String, Stream> cache = new LinkedHashMap<String, Stream>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Stream> eldest) {
            return size() > 40;
        }
    };

    private Streams(Context context) {
        this.context = context;
    }

    public static synchronized Streams get(Context context) {
        if (instance == null) instance = new Streams(context.getApplicationContext());
        return instance;
    }

    /** Itags to try for a quality, best first. */
    public static int[] itagsFor(int quality) {
        switch (quality) {
            case Prefs.Q_LOW:
                return new int[]{139, 140};
            case Prefs.Q_HIGH:
                return Prefs.opusSupported() ? new int[]{251, 140, 139} : new int[]{140, 139};
            default:
                return new int[]{140, 139};
        }
    }

    public void forget(String videoId) {
        synchronized (cache) {
            java.util.Iterator<String> keys = cache.keySet().iterator();
            while (keys.hasNext()) if (keys.next().startsWith(videoId + ":")) keys.remove();
        }
    }

    /**
     * The stream of a video at a quality. {@code allowWebm} false skips Opus (for converted or
     * very old players). Throws when YouTube refuses the video for every client.
     */
    public Stream resolve(String videoId, int quality, boolean allowWebm) throws IOException {
        String key = videoId + ":" + quality + ":" + allowWebm;
        synchronized (cache) {
            Stream s = cache.get(key);
            if (s != null && s.fresh()) return s;
        }
        InnerTube tube = InnerTube.get(context);
        String visitor = tube.visitorData(false);
        IOException failure = null;
        JSONObject player = null;
        for (int attempt = 0; attempt < 2 && player == null; attempt++) {
            try {
                JSONObject answer = request(VISIONOS, videoId, visitor);
                String status = playability(answer);
                if ("OK".equals(status)) {
                    player = answer;
                } else {
                    failure = new IOException("YouTube: " + reason(answer, status));
                    // "Sign in to confirm you're not a bot" goes away with a fresh visitor id.
                    if (attempt == 0) visitor = tube.visitorData(true);
                }
            } catch (IOException e) {
                failure = e;
            }
        }
        Stream stream = null;
        if (player != null) stream = pick(player, quality, allowWebm, VISIONOS.userAgent);
        if (stream == null) {
            // Fallback: the ANDROID client's single file with sound (classic MP4, itag 18).
            try {
                JSONObject answer = request(ANDROID, videoId, visitor);
                if ("OK".equals(playability(answer))) stream = pickProgressive(answer, ANDROID.userAgent);
                else if (failure == null) failure = new IOException("YouTube: " + reason(answer, playability(answer)));
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        if (stream == null) throw failure != null ? failure : new IOException("no playable stream");
        stream.videoId = videoId;
        com.ytmlegacy.android.player.Gains.put(context, videoId, stream.normalGain);
        synchronized (cache) {
            cache.put(key, stream);
        }
        Log.i(TAG, videoId + " -> itag " + stream.itag + " (" + stream.mime + ", " + stream.length + " B)");
        return stream;
    }

    private JSONObject request(Client client, String videoId, String visitor) throws IOException {
        Prefs prefs = Prefs.get(context);
        JSONObject body;
        try {
            JSONObject c = new JSONObject()
                    .put("clientName", client.name)
                    .put("clientVersion", client.version)
                    .put("hl", prefs.language())
                    .put("gl", prefs.region());
            for (int i = 0; i + 1 < client.extra.length; i += 2) {
                String k = client.extra[i];
                String v = client.extra[i + 1];
                if ("androidSdkVersion".equals(k)) c.put(k, Integer.parseInt(v));
                else c.put(k, v);
            }
            if (visitor.length() > 0) c.put("visitorData", visitor);
            body = new JSONObject()
                    .put("context", new JSONObject().put("client", c))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", client.userAgent);
        headers.put("X-YouTube-Client-Name", client.id);
        headers.put("X-YouTube-Client-Version", client.version);
        if (visitor.length() > 0) headers.put("X-Goog-Visitor-Id", visitor);
        String text = Http.postJson(PLAYER, body.toString(), headers, 8000, 15000);
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("bad player answer");
        }
    }

    private static String playability(JSONObject answer) {
        JSONObject p = answer.optJSONObject("playabilityStatus");
        return p == null ? "" : p.optString("status", "");
    }

    private static String reason(JSONObject answer, String status) {
        JSONObject p = answer.optJSONObject("playabilityStatus");
        String r = p == null ? "" : p.optString("reason", "");
        return r.length() > 0 ? r : status;
    }

    private static Stream pick(JSONObject player, int quality, boolean allowWebm, String ua) {
        JSONObject data = player.optJSONObject("streamingData");
        JSONArray formats = data == null ? null : data.optJSONArray("adaptiveFormats");
        if (formats == null) return null;
        for (int itag : itagsFor(quality)) {
            if (!allowWebm && itag == 251) continue;
            JSONObject best = null;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.optJSONObject(i);
                if (f == null || f.optInt("itag") != itag || f.optString("url", "").length() == 0) continue;
                // Prefer the original track over dubbed / loudness-normalised (DRC) copies.
                if (f.optBoolean("isDrc", false)) continue;
                JSONObject track = f.optJSONObject("audioTrack");
                if (track != null && !track.optBoolean("audioIsDefault", true)) continue;
                best = f;
                break;
            }
            if (best == null) {
                for (int i = 0; i < formats.length() && best == null; i++) {
                    JSONObject f = formats.optJSONObject(i);
                    if (f != null && f.optInt("itag") == itag && f.optString("url", "").length() > 0) best = f;
                }
            }
            if (best != null) return build(player, best, ua, false);
        }
        return null;
    }

    private static Stream pickProgressive(JSONObject player, String ua) {
        JSONObject data = player.optJSONObject("streamingData");
        JSONArray formats = data == null ? null : data.optJSONArray("formats");
        if (formats == null) return null;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f != null && f.optInt("itag") == 18 && f.optString("url", "").length() > 0) {
                return build(player, f, ua, true);
            }
        }
        return null;
    }

    private static Stream build(JSONObject player, JSONObject f, String ua, boolean classic) {
        Stream s = new Stream();
        s.url = f.optString("url");
        s.itag = f.optInt("itag");
        String mime = f.optString("mimeType", "");
        int semi = mime.indexOf(';');
        s.mime = semi < 0 ? mime : mime.substring(0, semi).trim();
        if (classic) s.mime = "video/mp4";
        try {
            s.length = Long.parseLong(f.optString("contentLength", "0"));
        } catch (NumberFormatException e) {
            s.length = 0;
        }
        s.classic = classic;
        s.userAgent = ua;
        long expire = 0;
        int at = s.url.indexOf("expire=");
        if (at >= 0) {
            int end = s.url.indexOf('&', at);
            try {
                expire = Long.parseLong(s.url.substring(at + 7, end < 0 ? s.url.length() : end)) * 1000;
            } catch (NumberFormatException ignored) {
            }
        }
        JSONObject data = player.optJSONObject("streamingData");
        if (expire == 0 && data != null) {
            expire = System.currentTimeMillis() + data.optLong("expiresInSeconds", 3600) * 1000;
        }
        s.expiresAt = expire;
        double loudness = f.optDouble("loudnessDb", Double.NaN);
        if (Double.isNaN(loudness)) {
            JSONObject audio = Parser.path(player, "playerConfig", "audioConfig");
            if (audio != null) loudness = audio.optDouble("loudnessDb", Double.NaN);
        }
        if (!Double.isNaN(loudness) && loudness > 0) {
            s.normalGain = (float) Math.pow(10, -loudness / 20.0);
        }
        JSONObject details = player.optJSONObject("videoDetails");
        if (details != null) {
            try {
                s.durationSec = Integer.parseInt(details.optString("lengthSeconds", "0"));
            } catch (NumberFormatException ignored) {
            }
        }
        return s;
    }
}
