package com.ytmlegacy.android;

import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Page;
import com.ytmlegacy.android.yt.Parser;
import com.ytmlegacy.android.yt.Song;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Parsers against answers recorded from music.youtube.com (hl=en, gl=US, Nov 2026). */
public class ParserTest {

    public static JSONObject fixture(String name) throws Exception {
        InputStream in = ParserTest.class.getResourceAsStream("/fx/" + name + ".json");
        assertNotNull(name, in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new JSONObject(out.toString("UTF-8"));
    }

    static void dump(String name, Page page) {
        System.out.println("== " + name + " title='" + page.title + "' sub='" + page.subtitle
                + "' pl=" + page.playlistId + " artist=" + page.artist + " thumb=" + !page.thumb.isEmpty());
        for (Page.Section s : page.sections) {
            System.out.println("  [" + s.style + "] '" + s.title + "' " + s.items.size()
                    + (s.continuation.isEmpty() ? "" : " +cont" + (s.legacyContinuation ? "(legacy)" : ""))
                    + (s.moreBrowseId.isEmpty() ? "" : " more=" + s.moreBrowseId));
            for (int i = 0; i < Math.min(3, s.items.size()); i++) {
                Item it = s.items.get(i);
                String extra = it.song == null ? it.browseId + " " + it.playlistId
                        : it.song.videoId + " a='" + it.song.artist + "' al='" + it.song.album + "' d="
                        + it.song.durationSec + " vid=" + it.song.video;
                System.out.println("     t" + it.type + " '" + it.title + "' | " + it.subtitle + " | " + extra
                        + (it.thumb.isEmpty() ? " NOTHUMB" : ""));
            }
        }
    }

    @Test
    public void home() throws Exception {
        Page p = Parser.page(fixture("home"), false);
        dump("home", p);
        assertTrue(p.sections.size() >= 3);
        for (Page.Section s : p.sections) {
            assertFalse(s.title.isEmpty());
            for (Item i : s.items) assertFalse(i.thumb.isEmpty());
        }
    }

    @Test
    public void explore() throws Exception {
        Page p = Parser.page(fixture("explore"), false);
        dump("explore", p);
        boolean tiles = false;
        for (Page.Section s : p.sections) if (s.style == Page.Section.TILES) tiles = true;
        assertTrue(tiles);
        dump("moods", Parser.page(fixture("moods"), false));
        dump("charts", Parser.page(fixture("charts"), false));
    }

    @Test
    public void searchSongs() throws Exception {
        Page p = Parser.page(fixture("search_songs"), false);
        dump("search_songs", p);
        Page.Section main = p.mainList();
        assertNotNull(main);
        assertTrue(main.items.size() >= 10);
        assertFalse(main.continuation.isEmpty());
        Song first = main.items.get(0).song;
        assertEquals("BSTsnWoslP4", first.videoId);
        assertEquals("Bohemian Rhapsody", first.title);
        assertEquals("Queen", first.artist);
        assertEquals("A Night At The Opera", first.album);
        assertEquals(355, first.durationSec);
        Page.Section more = Parser.continuationSection(fixture("search_songs_cont"), false);
        System.out.println("search cont items=" + more.items.size() + " cont=" + !more.continuation.isEmpty());
        assertTrue(more.items.size() >= 10);
    }

    @Test
    public void searchAll() throws Exception {
        Page p = Parser.page(fixture("search_all"), true);
        dump("search_all", p);
        int songs = 0;
        for (Page.Section s : p.sections) {
            for (Item i : s.items) {
                if (i.song != null) {
                    songs++;
                    assertFalse("label leaked: " + i.song.artist, i.song.artist.equals("Song"));
                }
            }
        }
        assertTrue(songs > 3);
        for (String f : new String[]{"search_videos", "search_albums", "search_artists", "search_playlists"}) {
            Page x = Parser.page(fixture(f), false);
            dump(f, x);
            assertNotNull(x.mainList());
            assertTrue(x.mainList().items.size() > 3);
        }
    }

    @Test
    public void album() throws Exception {
        Page p = Parser.page(fixture("album"), false);
        dump("album", p);
        assertEquals("A Night At The Opera", p.title);
        assertEquals("Queen", p.artist);
        assertTrue(p.playlistId.startsWith("OLAK5uy_"));
        List<Song> songs = p.songs();
        assertEquals(12, songs.size());
        assertEquals("Queen", songs.get(0).artist);
        assertFalse(songs.get(0).thumb.isEmpty());
        assertTrue(songs.get(0).durationSec > 0);
    }

    @Test
    public void playlist() throws Exception {
        Page p = Parser.page(fixture("playlist"), false);
        dump("playlist", p);
        Page.Section main = p.mainList();
        assertEquals(100, main.items.size());
        assertFalse(main.continuation.isEmpty());
        assertFalse(main.legacyContinuation);
        Page.Section more = Parser.continuationSection(fixture("playlist_cont"), false);
        System.out.println("playlist cont items=" + more.items.size() + " cont=" + more.continuation.length());
        assertEquals(100, more.items.size());
        assertTrue(more.items.get(0).song.durationSec > 0);
    }

    @Test
    public void artist() throws Exception {
        Page p = Parser.page(fixture("artist"), false);
        dump("artist", p);
        assertEquals("Queen", p.title);
        assertFalse(p.radioPlaylistId.isEmpty() && p.shufflePlaylistId.isEmpty());
    }

    @Test
    public void radio() throws Exception {
        Parser.Watch w = Parser.watch(fixture("next"));
        System.out.println("radio songs=" + w.songs.size() + " cont=" + !w.continuation.isEmpty()
                + " lyrics=" + w.lyricsBrowseId + " first=" + w.songs.get(0).title + " / " + w.songs.get(0).artist
                + " / " + w.songs.get(1).title + " / " + w.songs.get(1).artist + " al=" + w.songs.get(1).album);
        assertTrue(w.songs.size() >= 40);
        assertFalse(w.continuation.isEmpty());
        assertTrue(w.lyricsBrowseId.startsWith("MPLYt"));
        Parser.Watch c = Parser.watch(fixture("next_cont"));
        System.out.println("radio cont songs=" + c.songs.size() + " cont=" + !c.continuation.isEmpty());
        assertTrue(c.songs.size() >= 40);
        assertFalse(c.continuation.isEmpty());
    }

    @Test
    public void suggestionsAndLyrics() throws Exception {
        List<String> s = Parser.suggestions(fixture("suggest"));
        System.out.println("suggestions " + s);
        assertTrue(s.contains("queen"));
        String l = Parser.lyrics(fixture("lyrics"));
        assertTrue(l.startsWith("Is this the real life?"));
    }
}
