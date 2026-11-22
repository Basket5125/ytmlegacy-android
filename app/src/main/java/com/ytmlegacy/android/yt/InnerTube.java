package com.ytmlegacy.android.yt;

import android.content.Context;
import android.util.Log;

import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.net.Http;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YouTube Music's InnerTube API (WEB_REMIX client) for everything except stream URLs.
 * No account, no cookies: only the pseudonymous visitorData YouTube hands out.
 */
public final class InnerTube {

    private static final String TAG = "InnerTube";
    private static final String BASE = "https://music.youtube.com/youtubei/v1/";
    private static final String KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final long VISITOR_MAX_AGE = 24L * 3600 * 1000;

    public static final String FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";
    public static final String FILTER_VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA==";
    public static final String FILTER_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA==";
    public static final String FILTER_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA==";
    public static final String FILTER_PLAYLISTS = "EgeKAQQoAEABagoQAxAEEAoQCRAF";

    private static InnerTube instance;
    private final Prefs prefs;
    private final java.io.File pages;

    private InnerTube(Context context) {
        prefs = Prefs.get(context);
        pages = new java.io.File(context.getCacheDir(), "pages");
    }

    /** Home and Explore answers are kept on disk, so those tabs show at once on the next start. */
    private java.io.File pageFile(String browseId) {
        return new java.io.File(pages, browseId + "_" + prefs.language() + "_" + prefs.region() + ".json");
    }

    /** Age in ms of the saved Home/Explore page, or Long.MAX_VALUE when there is none. */
    public long cachedAge(String browseId) {
        java.io.File f = pageFile(browseId);
        return f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
    }

    /** The last saved Home/Explore page (at most 3 days old), or null. */
    public Page cachedPage(String browseId) {
        java.io.File f = pageFile(browseId);
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 3L * 24 * 3600 * 1000) return null;
        try {
            byte[] data = Http.readAll(new java.io.FileInputStream(f), 0);
            return Parser.page(new JSONObject(new String(data, "UTF-8")), false);
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    private void savePage(String browseId, JSONObject root) {
        try {
            pages.mkdirs();
            java.io.File tmp = new java.io.File(pages, browseId + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                out.write(root.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            tmp.renameTo(pageFile(browseId));
        } catch (Exception e) {
            Log.w(TAG, "save page: " + e);
        }
    }

    public static synchronized InnerTube get(Context context) {
        if (instance == null) instance = new InnerTube(context.getApplicationContext());
        return instance;
    }

    // ---------------------------------------------------------------- endpoints

    public Page home() throws IOException {
        JSONObject root = browseRaw("FEmusic_home", "");
        savePage("FEmusic_home", root);
        return Parser.page(root, false);
    }

    public Page explore() throws IOException {
        JSONObject root = browseRaw("FEmusic_explore", "");
        savePage("FEmusic_explore", root);
        return Parser.page(root, false);
    }

    public Page browse(String browseId, String params) throws IOException {
        return Parser.page(browseRaw(browseId, params), false);
    }

    public JSONObject browseRaw(String browseId, String params) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("browseId", browseId);
            if (params != null && params.length() > 0) body.put("params", params);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return post("browse", body, "");
    }

    /** Next page of a list section (playlist tracks, search results, shelves). */
    public Page.Section browseContinuation(String token, boolean legacy, boolean search, boolean typed)
            throws IOException {
        String endpoint = search ? "search" : "browse";
        JSONObject root;
        if (legacy) {
            // The token is already URL-encoded (it ends in %3D); send it as it came.
            String t = token;
            root = post(endpoint, new JSONObject(), "&ctoken=" + t + "&continuation=" + t + "&type=next");
        } else {
            JSONObject body = new JSONObject();
            try {
                body.put("continuation", token);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            root = post(endpoint, body, "");
        }
        return Parser.continuationSection(root, typed);
    }

    /** Search; {@code filter} is one of the FILTER_ params, or "" for everything. */
    public Page search(String query, String filter) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("query", query);
            if (filter != null && filter.length() > 0) body.put("params", filter);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return Parser.page(post("search", body, ""), filter == null || filter.length() == 0);
    }

    public List<String> suggestions(String input) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("input", input);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return Parser.suggestions(post("music/get_search_suggestions", body, ""));
    }

    /**
     * The "up next" queue: a song's radio (playlistId RDAMVM+id), an album/playlist, or a mix.
     */
    public Parser.Watch next(String videoId, String playlistId, String params) throws IOException {
        JSONObject body = new JSONObject();
        try {
            if (videoId != null && videoId.length() > 0) body.put("videoId", videoId);
            if (playlistId != null && playlistId.length() > 0) body.put("playlistId", playlistId);
            if (params != null && params.length() > 0) body.put("params", params);
            body.put("isAudioOnly", true);
            body.put("enablePersistentPlaylistPanel", true);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return Parser.watch(post("next", body, ""));
    }

    public Parser.Watch nextContinuation(String token) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("continuation", token);
            body.put("isAudioOnly", true);
            body.put("enablePersistentPlaylistPanel", true);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return Parser.watch(post("next", body, ""));
    }

    public String lyrics(String browseId) throws IOException {
        return Parser.lyrics(browseRaw(browseId, ""));
    }

    /** Title/author/length of a video id (used when importing CSV files that hold only ids). */
    public Song videoDetails(String videoId) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("videoId", videoId);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        JSONObject root = post("player", body, "");
        JSONObject d = root.optJSONObject("videoDetails");
        if (d == null) return null;
        Song s = new Song();
        s.videoId = d.optString("videoId", videoId);
        s.title = d.optString("title", "");
        s.artist = d.optString("author", "");
        if (s.artist.endsWith(" - Topic")) s.artist = s.artist.substring(0, s.artist.length() - 8);
        s.artistId = d.optString("channelId", "");
        try {
            s.durationSec = Integer.parseInt(d.optString("lengthSeconds", "0"));
        } catch (NumberFormatException ignored) {
        }
        s.thumb = Parser.thumbnail(d);
        s.video = !"MUSIC_VIDEO_TYPE_ATV".equals(d.optString("musicVideoType", ""));
        return s.title.length() == 0 ? null : s;
    }

    // ---------------------------------------------------------------- transport

    public JSONObject post(String endpoint, JSONObject body, String extraQuery) throws IOException {
        String version = prefs.clientVersion();
        try {
            JSONObject client = new JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", version)
                    .put("hl", prefs.language())
                    .put("gl", prefs.region());
            String visitor = visitorData(false);
            if (visitor.length() > 0) client.put("visitorData", visitor);
            body.put("context", new JSONObject().put("client", client));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("User-Agent", Http.BROWSER_UA);
        headers.put("Origin", "https://music.youtube.com");
        headers.put("Referer", "https://music.youtube.com/");
        headers.put("X-Origin", "https://music.youtube.com");
        headers.put("X-YouTube-Client-Name", "67");
        headers.put("X-YouTube-Client-Version", version);
        String visitor = prefs.visitorData();
        if (visitor.length() > 0) headers.put("X-Goog-EOM-Visitor-Id", visitor);
        String url = BASE + endpoint + "?prettyPrint=false&key=" + KEY + extraQuery;
        String text = Http.postJson(url, body.toString(), headers, 10000, 20000);
        try {
            JSONObject root = new JSONObject(text);
            rememberVisitor(root);
            return root;
        } catch (JSONException e) {
            throw new IOException("bad JSON from " + endpoint);
        }
    }

    private void rememberVisitor(JSONObject root) {
        JSONObject ctx = root.optJSONObject("responseContext");
        String v = ctx == null ? "" : ctx.optString("visitorData", "");
        if (v.length() > 0 && prefs.visitorData().length() == 0) prefs.setVisitorData(v);
    }

    /**
     * A visitor id for the player requests (some clients refuse to play without one).
     * Fetched once a day, or anew when {@code fresh}.
     */
    public synchronized String visitorData(boolean fresh) {
        String v = prefs.visitorData();
        if (!fresh && v.length() > 0 && System.currentTimeMillis() - prefs.visitorTime() < VISITOR_MAX_AGE) {
            return v;
        }
        try {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", Http.BROWSER_UA);
            headers.put("Origin", "https://music.youtube.com");
            headers.put("X-YouTube-Client-Name", "67");
            headers.put("X-YouTube-Client-Version", prefs.clientVersion());
            JSONObject client = new JSONObject().put("clientName", "WEB_REMIX")
                    .put("clientVersion", prefs.clientVersion())
                    .put("hl", prefs.language()).put("gl", prefs.region());
            JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client));
            String text = Http.postJson(BASE + "visitor_id?prettyPrint=false&key=" + KEY,
                    body.toString(), headers, 10000, 15000);
            JSONObject ctx = new JSONObject(text).optJSONObject("responseContext");
            String fetched = ctx == null ? "" : ctx.optString("visitorData", "");
            if (fetched.length() > 0) {
                prefs.setVisitorData(fetched);
                return fetched;
            }
        } catch (Exception e) {
            Log.w(TAG, "visitor id: " + e);
        }
        return v;
    }
}
