package com.ytmlegacy.android.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Song;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local library: playlists, liked songs, saved albums/playlists, downloads, history. */
public final class Library extends SQLiteOpenHelper {

    public static final long LIKED_ID = 1;

    public static final int DL_QUEUED = 0;
    public static final int DL_RUNNING = 1;
    public static final int DL_DONE = 2;
    public static final int DL_FAILED = 3;

    private static Library instance;
    private final Context context;
    private final List<Runnable> listeners = new ArrayList<Runnable>();
    /** Ids of finished downloads, kept in memory for quick list badges. */
    private Set<String> downloaded;
    private Set<String> liked;

    private Library(Context context) {
        super(context, "library.db", null, 1);
        this.context = context;
    }

    public static synchronized Library get(Context context) {
        if (instance == null) instance = new Library(context.getApplicationContext());
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "created INTEGER NOT NULL, kind INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE playlist_songs (_id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, "
                + "pos INTEGER NOT NULL, video_id TEXT NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX ps_pl ON playlist_songs(playlist_id, pos)");
        db.execSQL("CREATE TABLE saved (browse_id TEXT PRIMARY KEY, type INTEGER NOT NULL, title TEXT, "
                + "subtitle TEXT, thumb TEXT, playlist_id TEXT, added INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE downloads (video_id TEXT PRIMARY KEY, json TEXT NOT NULL, path TEXT, "
                + "itag INTEGER, size INTEGER, state INTEGER NOT NULL, added INTEGER NOT NULL, error TEXT)");
        db.execSQL("CREATE TABLE history (video_id TEXT PRIMARY KEY, json TEXT NOT NULL, played INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE searches (query TEXT PRIMARY KEY, at INTEGER NOT NULL)");
        ContentValues v = new ContentValues();
        v.put("_id", LIKED_ID);
        v.put("name", context.getString(com.ytmlegacy.android.R.string.liked_songs));
        v.put("created", System.currentTimeMillis());
        v.put("kind", 1);
        db.insert("playlists", null, v);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // ------------------------------------------------------------------ change listeners

    public void addListener(Runnable r) {
        synchronized (listeners) {
            listeners.add(r);
        }
    }

    public void removeListener(Runnable r) {
        synchronized (listeners) {
            listeners.remove(r);
        }
    }

    private void changed() {
        List<Runnable> copy;
        synchronized (listeners) {
            copy = new ArrayList<Runnable>(listeners);
        }
        for (Runnable r : copy) r.run();
    }

    // ------------------------------------------------------------------ playlists

    public static final class Playlist {
        public long id;
        public String name;
        public int count;
        public boolean liked;
        public String thumb = "";
    }

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<Playlist>();
        Cursor c = getReadableDatabase().rawQuery("SELECT p._id, p.name, p.kind, "
                + "(SELECT COUNT(*) FROM playlist_songs s WHERE s.playlist_id = p._id), "
                + "(SELECT json FROM playlist_songs s WHERE s.playlist_id = p._id ORDER BY pos LIMIT 1) "
                + "FROM playlists p ORDER BY p.kind DESC, p.name COLLATE NOCASE", null);
        try {
            while (c.moveToNext()) {
                Playlist p = new Playlist();
                p.id = c.getLong(0);
                p.name = c.getString(1);
                p.liked = c.getInt(2) == 1;
                p.count = c.getInt(3);
                String json = c.getString(4);
                if (json != null) p.thumb = parse(json).thumb;
                out.add(p);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public String playlistName(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT name FROM playlists WHERE _id = ?",
                new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? c.getString(0) : "";
        } finally {
            c.close();
        }
    }

    public long createPlaylist(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created", System.currentTimeMillis());
        long id = getWritableDatabase().insert("playlists", null, v);
        changed();
        return id;
    }

    public void renamePlaylist(long id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("playlists", v, "_id = ?", new String[]{String.valueOf(id)});
        changed();
    }

    public void deletePlaylist(long id) {
        if (id == LIKED_ID) return;
        SQLiteDatabase db = getWritableDatabase();
        db.delete("playlist_songs", "playlist_id = ?", new String[]{String.valueOf(id)});
        db.delete("playlists", "_id = ?", new String[]{String.valueOf(id)});
        changed();
    }

    public List<Song> playlistSongs(long id) {
        List<Song> out = new ArrayList<Song>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT json FROM playlist_songs WHERE playlist_id = ? ORDER BY pos",
                new String[]{String.valueOf(id)});
        try {
            while (c.moveToNext()) out.add(parse(c.getString(0)));
        } finally {
            c.close();
        }
        return out;
    }

    /** Appends songs (skipping ones already in the playlist); returns how many were added. */
    public int addToPlaylist(long id, List<Song> songs) {
        SQLiteDatabase db = getWritableDatabase();
        Set<String> present = new HashSet<String>();
        Cursor c = db.rawQuery("SELECT video_id FROM playlist_songs WHERE playlist_id = ?",
                new String[]{String.valueOf(id)});
        int pos;
        try {
            while (c.moveToNext()) present.add(c.getString(0));
        } finally {
            c.close();
        }
        c = db.rawQuery("SELECT COALESCE(MAX(pos), -1) FROM playlist_songs WHERE playlist_id = ?",
                new String[]{String.valueOf(id)});
        try {
            pos = c.moveToFirst() ? c.getInt(0) + 1 : 0;
        } finally {
            c.close();
        }
        int added = 0;
        db.beginTransaction();
        try {
            for (Song s : songs) {
                if (!Song.isVideoId(s.videoId) || !present.add(s.videoId)) continue;
                ContentValues v = new ContentValues();
                v.put("playlist_id", id);
                v.put("pos", pos++);
                v.put("video_id", s.videoId);
                v.put("json", s.toJson().toString());
                db.insert("playlist_songs", null, v);
                added++;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (id == LIKED_ID) liked = null;
        changed();
        return added;
    }

    public void removeFromPlaylist(long id, String videoId) {
        getWritableDatabase().delete("playlist_songs", "playlist_id = ? AND video_id = ?",
                new String[]{String.valueOf(id), videoId});
        if (id == LIKED_ID) liked = null;
        changed();
    }

    /** Moves a song one step up (-1) or down (+1). */
    public void moveInPlaylist(long id, int from, int to) {
        List<Song> songs = playlistSongs(id);
        if (from < 0 || from >= songs.size() || to < 0 || to >= songs.size()) return;
        Song s = songs.remove(from);
        songs.add(to, s);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < songs.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("pos", i);
                db.update("playlist_songs", v, "playlist_id = ? AND video_id = ?",
                        new String[]{String.valueOf(id), songs.get(i).videoId});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    public synchronized boolean isLiked(String videoId) {
        if (liked == null) {
            Set<String> s = new HashSet<String>();
            Cursor c = getReadableDatabase().rawQuery("SELECT video_id FROM playlist_songs WHERE playlist_id = ?",
                    new String[]{String.valueOf(LIKED_ID)});
            try {
                while (c.moveToNext()) s.add(c.getString(0));
            } finally {
                c.close();
            }
            liked = s;
        }
        return liked.contains(videoId);
    }

    public void setLiked(Song song, boolean like) {
        if (like) {
            List<Song> one = new ArrayList<Song>();
            one.add(song);
            addToPlaylist(LIKED_ID, one);
        } else {
            removeFromPlaylist(LIKED_ID, song.videoId);
        }
    }

    // ------------------------------------------------------------------ saved albums / playlists

    public void save(Item item) {
        ContentValues v = new ContentValues();
        v.put("browse_id", item.browseId);
        v.put("type", item.type);
        v.put("title", item.title);
        v.put("subtitle", item.subtitle);
        v.put("thumb", item.thumb);
        v.put("playlist_id", item.playlistId);
        v.put("added", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("saved", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        changed();
    }

    public void unsave(String browseId) {
        getWritableDatabase().delete("saved", "browse_id = ?", new String[]{browseId});
        changed();
    }

    public boolean isSaved(String browseId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM saved WHERE browse_id = ?", new String[]{browseId});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public List<Item> saved() {
        List<Item> out = new ArrayList<Item>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT browse_id, type, title, subtitle, thumb, playlist_id FROM saved ORDER BY added DESC", null);
        try {
            while (c.moveToNext()) {
                Item i = new Item();
                i.browseId = c.getString(0);
                i.type = c.getInt(1);
                i.title = nz(c.getString(2));
                i.subtitle = nz(c.getString(3));
                i.thumb = nz(c.getString(4));
                i.playlistId = nz(c.getString(5));
                out.add(i);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ------------------------------------------------------------------ downloads

    public static final class Download {
        public Song song;
        public String path = "";
        public int itag;
        public long size;
        public int state;
        public String error = "";
    }

    public void queueDownload(Song song) {
        Download existing = download(song.videoId);
        if (existing != null && (existing.state == DL_DONE || existing.state == DL_RUNNING)) return;
        ContentValues v = new ContentValues();
        v.put("video_id", song.videoId);
        v.put("json", song.toJson().toString());
        v.put("state", DL_QUEUED);
        v.put("added", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("downloads", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        changed();
    }

    /** Replaces the stored metadata of a download (e.g. with the local cover path). */
    public void queueDownloadMetadata(Song song) {
        ContentValues v = new ContentValues();
        v.put("json", song.toJson().toString());
        getWritableDatabase().update("downloads", v, "video_id = ?", new String[]{song.videoId});
    }

    public void setDownloadState(String videoId, int state, String path, int itag, long size, String error) {
        ContentValues v = new ContentValues();
        v.put("state", state);
        if (path != null) v.put("path", path);
        v.put("itag", itag);
        v.put("size", size);
        v.put("error", error == null ? "" : error);
        getWritableDatabase().update("downloads", v, "video_id = ?", new String[]{videoId});
        synchronized (this) {
            downloaded = null;
        }
        changed();
    }

    public void removeDownload(String videoId) {
        Download d = download(videoId);
        if (d != null && d.path.length() > 0) new java.io.File(d.path).delete();
        getWritableDatabase().delete("downloads", "video_id = ?", new String[]{videoId});
        synchronized (this) {
            downloaded = null;
        }
        changed();
    }

    public Download download(String videoId) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT json, path, itag, size, state, error FROM downloads WHERE video_id = ?", new String[]{videoId});
        try {
            return c.moveToFirst() ? readDownload(c) : null;
        } finally {
            c.close();
        }
    }

    /** Downloads in a state (or all with state &lt; 0), newest first. */
    public List<Download> downloads(int state) {
        List<Download> out = new ArrayList<Download>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT json, path, itag, size, state, error FROM downloads"
                        + (state >= 0 ? " WHERE state = " + state : "") + " ORDER BY added DESC", null);
        try {
            while (c.moveToNext()) out.add(readDownload(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** The next queued download (oldest first). */
    public Download nextQueued() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT json, path, itag, size, state, error FROM downloads WHERE state = " + DL_QUEUED
                        + " ORDER BY added LIMIT 1", null);
        try {
            return c.moveToFirst() ? readDownload(c) : null;
        } finally {
            c.close();
        }
    }

    public void resetRunningDownloads() {
        ContentValues v = new ContentValues();
        v.put("state", DL_QUEUED);
        getWritableDatabase().update("downloads", v, "state = " + DL_RUNNING, null);
    }

    public synchronized boolean isDownloaded(String videoId) {
        if (downloaded == null) {
            Set<String> s = new HashSet<String>();
            Cursor c = getReadableDatabase().rawQuery("SELECT video_id FROM downloads WHERE state = " + DL_DONE, null);
            try {
                while (c.moveToNext()) s.add(c.getString(0));
            } finally {
                c.close();
            }
            downloaded = s;
        }
        return downloaded.contains(videoId);
    }

    /** The downloaded file of a video, or null. */
    public java.io.File downloadedFile(String videoId) {
        if (!isDownloaded(videoId)) return null;
        Download d = download(videoId);
        if (d == null || d.path.length() == 0) return null;
        java.io.File f = new java.io.File(d.path);
        return f.exists() ? f : null;
    }

    private static Download readDownload(Cursor c) {
        Download d = new Download();
        d.song = parse(c.getString(0));
        d.path = nz(c.getString(1));
        d.itag = c.getInt(2);
        d.size = c.getLong(3);
        d.state = c.getInt(4);
        d.error = nz(c.getString(5));
        return d;
    }

    // ------------------------------------------------------------------ history

    public void addHistory(Song song) {
        ContentValues v = new ContentValues();
        v.put("video_id", song.videoId);
        v.put("json", song.toJson().toString());
        v.put("played", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM history WHERE video_id NOT IN (SELECT video_id FROM history ORDER BY played DESC LIMIT 300)");
        changed();
    }

    public List<Song> history(int limit) {
        List<Song> out = new ArrayList<Song>();
        Cursor c = getReadableDatabase().rawQuery("SELECT json FROM history ORDER BY played DESC LIMIT " + limit, null);
        try {
            while (c.moveToNext()) out.add(parse(c.getString(0)));
        } finally {
            c.close();
        }
        return out;
    }

    public void clearHistory() {
        getWritableDatabase().delete("history", null, null);
        changed();
    }

    public void addSearch(String query) {
        ContentValues v = new ContentValues();
        v.put("query", query);
        v.put("at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.insertWithOnConflict("searches", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM searches WHERE query NOT IN (SELECT query FROM searches ORDER BY at DESC LIMIT 30)");
    }

    public List<String> searches(String prefix) {
        List<String> out = new ArrayList<String>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT query FROM searches WHERE query LIKE ? ESCAPE '\\' ORDER BY at DESC LIMIT 8",
                new String[]{escapeLike(prefix) + "%"});
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public void removeSearch(String query) {
        getWritableDatabase().delete("searches", "query = ?", new String[]{query});
    }

    public void clearSearches() {
        getWritableDatabase().delete("searches", null, null);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static Song parse(String json) {
        try {
            return Song.fromJson(new JSONObject(json));
        } catch (Exception e) {
            return new Song();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
