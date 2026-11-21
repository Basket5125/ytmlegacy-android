package com.ytmlegacy.android.player;

import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.yt.Song;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The play queue. {@code play} is the order songs are played in; {@code natural} keeps the
 * order they were added in, so shuffle can be turned off again. Entries are compared by
 * identity, so the same song may be queued twice.
 */
public final class PlayQueue {

    private final List<Song> play = new ArrayList<Song>();
    private final List<Song> natural = new ArrayList<Song>();
    private int pos = -1;
    private boolean shuffle;
    private final Random random = new Random();

    public synchronized int size() {
        return play.size();
    }

    public synchronized int position() {
        return pos;
    }

    public synchronized boolean shuffled() {
        return shuffle;
    }

    public synchronized Song current() {
        return pos >= 0 && pos < play.size() ? play.get(pos) : null;
    }

    public synchronized Song at(int index) {
        return index >= 0 && index < play.size() ? play.get(index) : null;
    }

    public synchronized List<Song> snapshot() {
        return new ArrayList<Song>(play);
    }

    /** Songs after the current one. */
    public synchronized int remaining() {
        return pos < 0 ? play.size() : play.size() - pos - 1;
    }

    public synchronized void set(List<Song> songs, int start, boolean shuffleOn) {
        play.clear();
        natural.clear();
        for (Song s : songs) {
            Song copy = s.copy();
            play.add(copy);
            natural.add(copy);
        }
        pos = play.isEmpty() ? -1 : Math.max(0, Math.min(start, play.size() - 1));
        shuffle = false;
        if (shuffleOn) setShuffle(true);
    }

    public synchronized void clear() {
        play.clear();
        natural.clear();
        pos = -1;
    }

    /** Turning shuffle on keeps the current song and shuffles everything else after it. */
    public synchronized void setShuffle(boolean on) {
        if (on == shuffle) return;
        shuffle = on;
        Song current = current();
        if (on) {
            List<Song> rest = new ArrayList<Song>(natural);
            if (current != null) removeIdentity(rest, current);
            Collections.shuffle(rest, random);
            play.clear();
            if (current != null) play.add(current);
            play.addAll(rest);
            pos = current == null ? (play.isEmpty() ? -1 : 0) : 0;
        } else {
            play.clear();
            play.addAll(natural);
            pos = current == null ? (play.isEmpty() ? -1 : 0) : indexOfIdentity(play, current);
        }
    }

    /** The index to play after the current one, or -1 at the end. */
    public synchronized int nextIndex(int repeat) {
        if (play.isEmpty()) return -1;
        if (repeat == Prefs.REPEAT_ONE) return pos;
        if (pos + 1 < play.size()) return pos + 1;
        return repeat == Prefs.REPEAT_ALL ? 0 : -1;
    }

    public synchronized int previousIndex(int repeat) {
        if (play.isEmpty()) return -1;
        if (pos > 0) return pos - 1;
        return repeat == Prefs.REPEAT_ALL ? play.size() - 1 : 0;
    }

    public synchronized void moveTo(int index) {
        if (index >= 0 && index < play.size()) pos = index;
    }

    public synchronized void addNext(List<Song> songs) {
        int at = pos + 1;
        Song current = current();
        int nat = current == null ? natural.size() : indexOfIdentity(natural, current) + 1;
        for (Song s : songs) {
            Song copy = s.copy();
            play.add(Math.min(at++, play.size()), copy);
            natural.add(Math.min(nat++, natural.size()), copy);
        }
        if (pos < 0 && !play.isEmpty()) pos = 0;
    }

    public synchronized void addLast(List<Song> songs) {
        for (Song s : songs) {
            Song copy = s.copy();
            play.add(copy);
            natural.add(copy);
        }
        if (pos < 0 && !play.isEmpty()) pos = 0;
    }

    /** Removes the entry at a play index; returns true when it was the current song. */
    public synchronized boolean remove(int index) {
        if (index < 0 || index >= play.size()) return false;
        Song s = play.remove(index);
        removeIdentity(natural, s);
        boolean wasCurrent = index == pos;
        if (index < pos) pos--;
        if (pos >= play.size()) pos = play.size() - 1;
        return wasCurrent;
    }

    public synchronized void move(int from, int to) {
        if (from < 0 || from >= play.size() || to < 0 || to >= play.size() || from == to) return;
        Song current = current();
        Song s = play.remove(from);
        play.add(to, s);
        if (!shuffle) {
            natural.clear();
            natural.addAll(play);
        }
        pos = indexOfIdentity(play, current);
    }

    /** Drops songs played long ago so an endless radio does not grow without bound. */
    public synchronized void trimHistory(int keepBehind) {
        while (pos > keepBehind) {
            Song s = play.remove(0);
            removeIdentity(natural, s);
            pos--;
        }
    }

    // ------------------------------------------------------------------ persistence

    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray p = new JSONArray();
            for (Song s : play) p.put(s.toJson());
            o.put("play", p);
            if (shuffle) {
                JSONArray n = new JSONArray();
                for (Song s : natural) n.put(indexOfIdentity(play, s));
                o.put("natural", n);
            }
            o.put("pos", pos);
            o.put("shuffle", shuffle);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    public synchronized void fromJson(JSONObject o) {
        clear();
        JSONArray p = o.optJSONArray("play");
        if (p == null) return;
        for (int i = 0; i < p.length(); i++) {
            JSONObject s = p.optJSONObject(i);
            if (s != null) play.add(Song.fromJson(s));
        }
        JSONArray n = o.optJSONArray("natural");
        shuffle = o.optBoolean("shuffle", false) && n != null && n.length() == play.size();
        if (shuffle) {
            for (int i = 0; i < n.length(); i++) {
                int idx = n.optInt(i, -1);
                if (idx < 0 || idx >= play.size()) {
                    natural.clear();
                    natural.addAll(play);
                    break;
                }
                natural.add(play.get(idx));
            }
        } else {
            natural.addAll(play);
        }
        pos = Math.min(o.optInt("pos", 0), play.size() - 1);
        if (play.isEmpty()) pos = -1;
    }

    private static int indexOfIdentity(List<Song> list, Song s) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == s) return i;
        return -1;
    }

    private static void removeIdentity(List<Song> list, Song s) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == s) {
                list.remove(i);
                return;
            }
        }
    }
}
