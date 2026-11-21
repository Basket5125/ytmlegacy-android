package com.ytmlegacy.android.data;

import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Page;
import com.ytmlegacy.android.yt.Song;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Playlists as CSV. Reads exports of Spotify (Exportify, TuneMyMusic…), Google Takeout
 * (YouTube "Video ID" columns), plain lists of links/ids, and the files written here.
 */
public final class Csv {

    private static final Pattern ID_IN_URL = Pattern.compile("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})");

    private Csv() { }

    /** One line of a playlist file. */
    public static final class Row {
        public String videoId = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public int durationSec;
    }

    // ------------------------------------------------------------------ parsing

    /** RFC 4180 records; the separator (comma, semicolon or tab) is guessed from the first line. */
    public static List<List<String>> records(String text) {
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') text = text.substring(1);
        char sep = guessSeparator(text);
        List<List<String>> out = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.length() == 0) {
                quoted = true;
                any = true;
            } else if (c == sep) {
                row.add(field.toString());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (any || row.size() > 1 || row.get(0).length() > 0) out.add(row);
                row = new ArrayList<String>();
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (row.size() > 1 || row.get(0).length() > 0) out.add(row);
        }
        return out;
    }

    private static char guessSeparator(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        int commas = count(first, ',');
        int semis = count(first, ';');
        int tabs = count(first, '\t');
        if (tabs > commas && tabs > semis) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char x = s.charAt(i);
            if (x == '"') q = !q;
            else if (x == c && !q) n++;
        }
        return n;
    }

    /** The playlist rows of a CSV file. */
    public static List<Row> rows(String text) {
        List<List<String>> records = records(text);
        List<Row> out = new ArrayList<Row>();
        int header = -1;
        int id = -1, url = -1, title = -1, artist = -1, album = -1, duration = -1;
        boolean durationMs = false;
        // Two passes: a header naming a video id/link wins (Takeout files start with a
        // playlist preamble that has a "Title" column of its own), then one naming a title.
        for (int pass = 0; pass < 2 && header < 0; pass++) {
            for (int r = 0; r < Math.min(records.size(), 20) && header < 0; r++) {
                List<String> rec = records.get(r);
                int i0 = -1, u0 = -1, t0 = -1, a0 = -1, al0 = -1, d0 = -1;
                boolean ms = false;
                for (int c = 0; c < rec.size(); c++) {
                    String h = rec.get(c).trim().toLowerCase(Locale.US);
                    if (h.equals("video id") || h.equals("videoid") || h.equals("video_id") || h.equals("youtube id")
                            || h.equals("id filmu") || h.equals("identyfikator filmu")) i0 = c;
                    else if (h.equals("url") || h.equals("link") || h.equals("youtube url") || h.equals("video url")
                            || h.equals("track url") || h.equals("youtube link")) u0 = c;
                    else if (h.equals("track name") || h.equals("title") || h.equals("name") || h.equals("song")
                            || h.equals("track") || h.equals("song name") || h.equals("track title") || h.equals("tytuł")
                            || h.equals("tytul") || h.equals("utwór") || h.equals("song title")) t0 = c;
                    else if (h.equals("artist name(s)") || h.equals("artist name") || h.equals("artist")
                            || h.equals("artists") || h.equals("artist(s)") || h.equals("artist names")
                            || h.equals("wykonawca") || h.equals("channel") || h.equals("creator")) a0 = c;
                    else if (h.equals("album name") || h.equals("album") || h.equals("album title")) al0 = c;
                    else if (h.equals("duration (ms)") || h.equals("duration_ms") || h.equals("track duration (ms)")) {
                        d0 = c;
                        ms = true;
                    } else if (h.equals("duration") || h.equals("length") || h.equals("czas")) d0 = c;
                }
                if (pass == 0 ? (i0 >= 0 || u0 >= 0) : t0 >= 0) {
                    header = r;
                    id = i0;
                    url = u0;
                    title = t0;
                    artist = a0;
                    album = al0;
                    duration = d0;
                    durationMs = ms;
                }
            }
        }
        int start = header + 1;
        for (int r = start; r < records.size(); r++) {
            List<String> rec = records.get(r);
            Row row = new Row();
            if (header < 0) {
                // No header: a list of ids or links in the first column (or anywhere on the line).
                for (String f : rec) {
                    String v = idOf(f);
                    if (v.length() > 0) {
                        row.videoId = v;
                        break;
                    }
                }
                if (row.videoId.length() == 0) continue;
            } else {
                if (id >= 0 && id < rec.size()) row.videoId = idOf(rec.get(id));
                if (row.videoId.length() == 0 && url >= 0 && url < rec.size()) row.videoId = idOf(rec.get(url));
                if (title >= 0 && title < rec.size()) row.title = rec.get(title).trim();
                if (artist >= 0 && artist < rec.size()) row.artist = rec.get(artist).trim();
                if (album >= 0 && album < rec.size()) row.album = rec.get(album).trim();
                if (duration >= 0 && duration < rec.size()) row.durationSec = parseDuration(rec.get(duration), durationMs);
                if (row.videoId.length() == 0 && row.title.length() == 0) continue;
            }
            out.add(row);
        }
        return out;
    }

    static String idOf(String field) {
        if (field == null) return "";
        String f = field.trim();
        if (Song.isVideoId(f)) return f;
        Matcher m = ID_IN_URL.matcher(f);
        return m.find() ? m.group(1) : "";
    }

    private static int parseDuration(String s, boolean ms) {
        s = s.trim();
        if (s.length() == 0) return 0;
        try {
            if (s.contains(":")) {
                int total = 0;
                for (String p : s.split(":")) total = total * 60 + Integer.parseInt(p.trim());
                return total;
            }
            double v = Double.parseDouble(s);
            return (int) (ms ? v / 1000 : v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ matching

    /** The best search result for a row: title and artist words, then the closest length. */
    public static Song bestMatch(Row row, Page results) {
        Page.Section list = results.mainList();
        if (list == null) return null;
        String wantTitle = norm(row.title);
        String wantArtist = norm(row.artist);
        Song best = null;
        int bestScore = Integer.MIN_VALUE;
        int considered = 0;
        for (Item item : list.items) {
            if (item.song == null) continue;
            if (++considered > 6) break;
            Song s = item.song;
            String t = norm(s.title);
            String a = norm(s.artist);
            int score = 0;
            if (t.equals(wantTitle)) score += 60;
            else if (t.contains(wantTitle) || wantTitle.contains(t)) score += 35;
            else score += 10 * overlap(t, wantTitle);
            if (wantArtist.length() > 0) {
                String firstArtist = wantArtist.split(" ")[0];
                if (a.contains(firstArtist) || wantArtist.contains(a)) score += 30;
                else score += 5 * overlap(a, wantArtist);
            }
            if (row.durationSec > 0 && s.durationSec > 0) {
                int diff = Math.abs(row.durationSec - s.durationSec);
                score += diff <= 3 ? 25 : diff <= 10 ? 10 : diff > 60 ? -30 : 0;
            }
            if (!s.video) score += 5; // audio tracks over music videos
            score -= considered; // slight preference for the top results
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static int overlap(String a, String b) {
        int n = 0;
        for (String w : a.split(" ")) if (w.length() > 1 && (" " + b + " ").contains(" " + w + " ")) n++;
        return n;
    }

    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.US), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l');
        n = n.replaceAll("\\((feat|ft|with)[^)]*\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n;
    }

    // ------------------------------------------------------------------ writing

    public static String write(List<Song> songs) {
        StringBuilder b = new StringBuilder();
        b.append("Track Name,Artist Name(s),Album Name,Duration (ms),Video ID,URL\r\n");
        for (Song s : songs) {
            b.append(quote(s.title)).append(',')
                    .append(quote(s.artist)).append(',')
                    .append(quote(s.album)).append(',')
                    .append(s.durationSec * 1000L).append(',')
                    .append(s.videoId).append(',')
                    .append("https://music.youtube.com/watch?v=").append(s.videoId).append("\r\n");
        }
        return b.toString();
    }

    private static String quote(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0
                && s.indexOf(';') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
