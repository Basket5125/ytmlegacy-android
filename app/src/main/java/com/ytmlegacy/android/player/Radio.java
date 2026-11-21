package com.ytmlegacy.android.player;

import android.content.Context;
import android.util.Log;

import com.ytmlegacy.android.yt.InnerTube;
import com.ytmlegacy.android.yt.Parser;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A truly endless radio. YouTube's own radio continuation starts repeating itself after a few
 * pages (49 → 16 → 8 → 6 new songs in tests), so every song ever queued is remembered and,
 * once a page brings too few new ones, the radio is re-seeded from a random recent song.
 */
public final class Radio {

    private static final String TAG = "Radio";
    private static final int MIN_NEW = 4;
    private static final int MAX_SEEN = 3000;

    private final Context context;
    private final Set<String> seen = new LinkedHashSet<String>();
    private final Set<String> usedSeeds = new HashSet<String>();
    private final Random random = new Random();
    private String continuation = "";
    private String playlistId = "";
    private String params = "";
    private boolean active;

    public Radio(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized boolean active() {
        return active;
    }

    public synchronized void stop() {
        active = false;
        continuation = "";
    }

    /** Starts a radio; the first {@link #more} call loads from this seed or mix. */
    public synchronized void start(String seedVideoId, String mixPlaylistId, String mixParams, List<Song> queued) {
        active = true;
        continuation = "";
        seen.clear();
        usedSeeds.clear();
        playlistId = mixPlaylistId == null ? "" : mixPlaylistId;
        params = mixParams == null ? "" : mixParams;
        if (seedVideoId != null && seedVideoId.length() > 0) usedSeeds.add(seedVideoId);
        pendingSeed = seedVideoId == null ? "" : seedVideoId;
        markSeen(queued);
    }

    private String pendingSeed = "";

    public synchronized void markSeen(List<Song> songs) {
        for (Song s : songs) seen.add(s.videoId);
        while (seen.size() > MAX_SEEN) {
            String first = seen.iterator().next();
            seen.remove(first);
        }
    }

    /** First page of a mix/radio with its continuation; used to fill the queue when starting. */
    public List<Song> more(List<Song> recent) {
        String token;
        String seed;
        String mix;
        String mixParams;
        synchronized (this) {
            if (!active) return new ArrayList<Song>();
            token = continuation;
            seed = pendingSeed;
            mix = playlistId;
            mixParams = params;
        }
        InnerTube tube = InnerTube.get(context);
        List<Song> fresh = new ArrayList<Song>();
        for (int attempt = 0; attempt < 4 && fresh.size() < MIN_NEW; attempt++) {
            Parser.Watch w;
            try {
                if (token.length() > 0) {
                    w = tube.nextContinuation(token);
                } else if (mix.length() > 0) {
                    w = tube.next(seed, mix, mixParams);
                    mix = "";
                } else {
                    if (seed.length() == 0) seed = pickSeed(recent);
                    if (seed.length() == 0) break;
                    w = tube.next(seed, "RDAMVM" + seed, "");
                }
            } catch (Exception e) {
                Log.w(TAG, "radio page failed: " + e);
                break;
            }
            int added = 0;
            synchronized (this) {
                for (Song s : w.songs) {
                    if (seen.add(s.videoId)) {
                        fresh.add(s);
                        added++;
                    }
                }
            }
            token = w.continuation;
            Log.i(TAG, "page: " + w.songs.size() + " songs, " + added + " new");
            if (added < MIN_NEW) {
                // The radio is looping: start over from another recent song.
                token = "";
                List<Song> pool = new ArrayList<Song>(recent);
                pool.addAll(fresh);
                seed = pickSeed(pool);
            } else {
                seed = "";
            }
        }
        synchronized (this) {
            continuation = token;
            pendingSeed = "";
            playlistId = "";
        }
        if (fresh.size() > 40) return new ArrayList<Song>(fresh.subList(0, 40));
        return fresh;
    }

    /** A random song of the last ones queued that has not seeded the radio yet. */
    private synchronized String pickSeed(List<Song> recent) {
        List<String> candidates = new ArrayList<String>();
        int from = Math.max(0, recent.size() - 15);
        for (int i = from; i < recent.size(); i++) {
            String id = recent.get(i).videoId;
            if (!usedSeeds.contains(id)) candidates.add(id);
        }
        if (candidates.isEmpty()) {
            usedSeeds.clear();
            for (int i = from; i < recent.size(); i++) candidates.add(recent.get(i).videoId);
        }
        if (candidates.isEmpty()) return "";
        String pick = candidates.get(random.nextInt(candidates.size()));
        usedSeeds.add(pick);
        return pick;
    }
}
