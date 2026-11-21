package com.ytmlegacy.android.yt;

import org.json.JSONException;
import org.json.JSONObject;

/** One playable track (a YouTube video id with music metadata). */
public final class Song {
    public String videoId = "";
    public String title = "";
    /** Display string of all artists, comma separated. */
    public String artist = "";
    public String artistId = "";
    public String album = "";
    public String albumId = "";
    public int durationSec;
    public String thumb = "";
    /** A music video (OMV/UGC) rather than an audio track (ATV). */
    public boolean video;

    public Song() { }

    public Song(String videoId, String title, String artist) {
        this.videoId = videoId == null ? "" : videoId;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("v", videoId).put("t", title).put("a", artist).put("ai", artistId)
                    .put("al", album).put("ali", albumId).put("d", durationSec)
                    .put("th", thumb).put("vid", video);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public static Song fromJson(JSONObject o) {
        Song s = new Song();
        s.videoId = o.optString("v", "");
        s.title = o.optString("t", "");
        s.artist = o.optString("a", "");
        s.artistId = o.optString("ai", "");
        s.album = o.optString("al", "");
        s.albumId = o.optString("ali", "");
        s.durationSec = o.optInt("d", 0);
        s.thumb = o.optString("th", "");
        s.video = o.optBoolean("vid", false);
        return s;
    }

    public Song copy() {
        return fromJson(toJson());
    }

    /** "Artist • Album" or whichever part is known. */
    public String subtitle() {
        if (artist.length() > 0 && album.length() > 0) return artist + " • " + album;
        return artist.length() > 0 ? artist : album;
    }

    public static String formatTime(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) {
            b.append(h).append(':');
            if (m < 10) b.append('0');
        }
        b.append(m).append(':');
        if (s < 10) b.append('0');
        b.append(s);
        return b.toString();
    }

    public static boolean isVideoId(String id) {
        if (id == null || id.length() != 11) return false;
        for (int i = 0; i < 11; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
