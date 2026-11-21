package com.ytmlegacy.android.yt;

/** An entry of a browse/search page: a song, album, playlist, artist, mix or genre tile. */
public final class Item {
    public static final int SONG = 1;
    public static final int VIDEO = 2;
    public static final int ALBUM = 3;
    public static final int PLAYLIST = 4;
    public static final int ARTIST = 5;
    /** A mix/radio started with a playlist id (watchPlaylistEndpoint). */
    public static final int MIX = 6;
    /** A mood/genre/navigation tile (browse id + params). */
    public static final int NAV = 7;

    public int type;
    public String title = "";
    public String subtitle = "";
    public String thumb = "";
    public String browseId = "";
    public String params = "";
    public String playlistId = "";
    /** Set for SONG and VIDEO. */
    public Song song;
    /** Stripe colour of a NAV tile (ARGB), 0 when none. */
    public int color;

    public boolean isPlayable() {
        return type == SONG || type == VIDEO;
    }

    public boolean isCollection() {
        return type == ALBUM || type == PLAYLIST;
    }

    public static Item ofSong(Song song) {
        Item item = new Item();
        item.type = song.video ? VIDEO : SONG;
        item.song = song;
        item.title = song.title;
        item.subtitle = song.subtitle();
        item.thumb = song.thumb;
        return item;
    }
}
