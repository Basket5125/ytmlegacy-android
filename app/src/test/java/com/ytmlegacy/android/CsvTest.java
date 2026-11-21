package com.ytmlegacy.android;

import com.ytmlegacy.android.data.Csv;
import com.ytmlegacy.android.yt.Page;
import com.ytmlegacy.android.yt.Parser;
import com.ytmlegacy.android.yt.Song;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class CsvTest {

    @Test
    public void exportify() {
        String csv = "﻿\"Track URI\",\"Track Name\",\"Artist URI(s)\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\r\n"
                + "\"spotify:track:1\",\"Bohemian Rhapsody - Remastered 2011\",\"x\",\"Queen\",\"A Night at the Opera\",\"354320\"\r\n"
                + "\"spotify:track:2\",\"Hello, \"\"World\"\"\",\"x\",\"A, B\",\"Alb\",\"1000\"\r\n";
        List<Csv.Row> rows = Csv.rows(csv);
        assertEquals(2, rows.size());
        assertEquals("Bohemian Rhapsody - Remastered 2011", rows.get(0).title);
        assertEquals("Queen", rows.get(0).artist);
        assertEquals(354, rows.get(0).durationSec);
        assertEquals("Hello, \"World\"", rows.get(1).title);
        assertEquals("A, B", rows.get(1).artist);
    }

    @Test
    public void takeoutWithPreamble() {
        String csv = "Playlist Id,Channel Id,Time Created,Time Updated,Title,Description,Visibility\n"
                + "PLx,UCx,2020,2021,My list,,Private\n\n"
                + "Video Id,Time Added\n"
                + "dQw4w9WgXcQ,2021-01-01 00:00:00 UTC\n"
                + "BSTsnWoslP4  ,2021-01-02\n";
        List<Csv.Row> rows = Csv.rows(csv);
        assertEquals(2, rows.size());
        assertEquals("dQw4w9WgXcQ", rows.get(0).videoId);
        assertEquals("BSTsnWoslP4", rows.get(1).videoId);
    }

    @Test
    public void plainLinksAndSemicolons() {
        List<Csv.Row> rows = Csv.rows("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=x\nhttps://youtu.be/BSTsnWoslP4\nnothing\n");
        assertEquals(2, rows.size());
        assertEquals("BSTsnWoslP4", rows.get(1).videoId);
        List<Csv.Row> semi = Csv.rows("Title;Artist\nSong A;Artist A\n");
        assertEquals(1, semi.size());
        assertEquals("Artist A", semi.get(0).artist);
    }

    @Test
    public void roundTrip() {
        List<Song> songs = new ArrayList<Song>();
        Song s = new Song("BSTsnWoslP4", "Bohemian Rhapsody", "Queen");
        s.album = "A Night, At The Opera";
        s.durationSec = 355;
        songs.add(s);
        List<Csv.Row> rows = Csv.rows(Csv.write(songs));
        assertEquals(1, rows.size());
        assertEquals("BSTsnWoslP4", rows.get(0).videoId);
        assertEquals("A Night, At The Opera", rows.get(0).album);
        assertEquals(355, rows.get(0).durationSec);
    }

    @Test
    public void matchesSearchResult() throws Exception {
        Page page = Parser.page(ParserTest.fixture("search_songs"), false);
        Csv.Row row = new Csv.Row();
        row.title = "Bohemian Rhapsody - Remastered 2011";
        row.artist = "Queen";
        row.durationSec = 354;
        Song best = Csv.bestMatch(row, page);
        assertNotNull(best);
        assertEquals("BSTsnWoslP4", best.videoId);
    }
}
