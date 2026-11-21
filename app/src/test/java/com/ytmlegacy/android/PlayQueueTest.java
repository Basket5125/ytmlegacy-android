package com.ytmlegacy.android;

import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.player.PlayQueue;
import com.ytmlegacy.android.yt.Song;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlayQueueTest {

    static List<Song> songs(int n) {
        List<Song> l = new ArrayList<Song>();
        for (int i = 0; i < n; i++) l.add(new Song("id" + i, "t" + i, "a"));
        return l;
    }

    @Test
    public void shuffleKeepsCurrentAndAllSongs() {
        PlayQueue q = new PlayQueue();
        q.set(songs(20), 7, false);
        assertEquals("id7", q.current().videoId);
        q.setShuffle(true);
        assertEquals(0, q.position());
        assertEquals("id7", q.current().videoId);
        Set<String> ids = new HashSet<String>();
        for (Song s : q.snapshot()) ids.add(s.videoId);
        assertEquals(20, ids.size());
        q.moveTo(5);
        String playing = q.current().videoId;
        q.setShuffle(false);
        assertEquals(playing, q.current().videoId);
        assertEquals("id" + q.position(), q.current().videoId);
    }

    @Test
    public void repeatModes() {
        PlayQueue q = new PlayQueue();
        q.set(songs(3), 2, false);
        assertEquals(-1, q.nextIndex(Prefs.REPEAT_OFF));
        assertEquals(0, q.nextIndex(Prefs.REPEAT_ALL));
        assertEquals(2, q.nextIndex(Prefs.REPEAT_ONE));
        assertEquals(1, q.previousIndex(Prefs.REPEAT_OFF));
    }

    @Test
    public void addRemoveMove() {
        PlayQueue q = new PlayQueue();
        q.set(songs(3), 1, false);
        List<Song> extra = new ArrayList<Song>();
        extra.add(new Song("x", "x", "x"));
        q.addNext(extra);
        assertEquals("x", q.at(2).videoId);
        assertTrue(!q.remove(0));
        assertEquals("id1", q.current().videoId);
        assertEquals(0, q.position());
        q.move(0, 2);
        assertEquals("id1", q.current().videoId);
        assertEquals(2, q.position());
        assertTrue(q.remove(2));
    }

    @Test
    public void persistsShuffle() {
        PlayQueue q = new PlayQueue();
        q.set(songs(10), 3, true);
        PlayQueue r = new PlayQueue();
        r.fromJson(q.toJson());
        assertEquals(q.current().videoId, r.current().videoId);
        assertTrue(r.shuffled());
        r.setShuffle(false);
        assertEquals("id3", r.current().videoId);
        assertEquals(3, r.position());
    }

    @Test
    public void trimHistory() {
        PlayQueue q = new PlayQueue();
        q.set(songs(300), 250, false);
        q.trimHistory(200);
        assertEquals(200, q.position());
        assertEquals("id250", q.current().videoId);
    }
}
