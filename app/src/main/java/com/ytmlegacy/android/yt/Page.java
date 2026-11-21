package com.ytmlegacy.android.yt;

import java.util.ArrayList;
import java.util.List;

/** A parsed browse or search answer. */
public final class Page {

    /** A group of items with an optional title. */
    public static final class Section {
        public static final int LIST = 0;
        public static final int CAROUSEL = 1;
        /** Genre / navigation buttons. */
        public static final int TILES = 2;

        public String title = "";
        public int style = LIST;
        public final List<Item> items = new ArrayList<Item>();
        /** "More" target, may be empty. */
        public String moreBrowseId = "";
        public String moreParams = "";
        /** Continuation of this section's list, may be empty. */
        public String continuation = "";
        /** True when the continuation is sent as ?ctoken= (old style), false for a body token. */
        public boolean legacyContinuation;
    }

    public String title = "";
    public String subtitle = "";
    public String secondSubtitle = "";
    public String thumb = "";
    public String description = "";
    /** Artist of an album page, if linked. */
    public String artist = "";
    public String artistId = "";
    /** Playlist id that plays the page (album OLAK…, playlist PL…/RD…). */
    public String playlistId = "";
    /** Artist "shuffle"/"radio" playlist ids from the header buttons. */
    public String shufflePlaylistId = "";
    public String radioPlaylistId = "";
    public String radioVideoId = "";
    public final List<Section> sections = new ArrayList<Section>();

    /** The section holding the main track list (playlist/album/search), or null. */
    public Section mainList() {
        Section best = null;
        for (Section s : sections) {
            if (s.style != Section.LIST) continue;
            if (best == null || s.items.size() > best.items.size()
                    || (s.continuation.length() > 0 && best.continuation.length() == 0)) {
                best = s;
            }
        }
        return best;
    }

    /** Every playable song on the page, in order. */
    public List<Song> songs() {
        List<Song> out = new ArrayList<Song>();
        for (Section s : sections) {
            if (s.style != Section.LIST) continue;
            for (Item i : s.items) if (i.song != null) out.add(i.song);
        }
        return out;
    }
}
