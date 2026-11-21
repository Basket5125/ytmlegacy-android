package com.ytmlegacy.android.yt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns YouTube Music InnerTube answers into {@link Page}s. Renderers are found by key anywhere
 * in the tree (layouts move around), and fields are read by structure (endpoints, page types),
 * never by localized text.
 */
public final class Parser {

    private static final Pattern DURATION = Pattern.compile("^\\d{1,2}(:\\d{2}){1,2}$");
    private static final String SEPARATOR = "•";

    private static final String[] SECTION_KEYS = {
            "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer", "musicShelfRenderer",
            "musicPlaylistShelfRenderer", "gridRenderer", "musicCardShelfRenderer", "itemSectionRenderer"
    };
    private static final String[] ITEM_KEYS = {
            "musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "musicNavigationButtonRenderer"
    };
    /** Subtrees never holding content sections. */
    private static final String[] SKIP_KEYS = {
            "responseContext", "frameworkUpdates", "microformat", "menu", "header", "trackingParams",
            "background", "thumbnail", "foregroundThumbnail"
    };

    private Parser() { }

    // ------------------------------------------------------------------ pages

    /**
     * A browse or search answer.
     *
     * @param typedRows rows start with a type label ("Song • …"), as in an unfiltered search
     */
    public static Page page(JSONObject root, boolean typedRows) {
        Page page = new Page();
        parseHeader(root, page);
        collectSections(root.opt("contents"), page, typedRows);
        // Continuation answers (paging) carry their items here.
        collectSections(root.opt("continuationContents"), page, typedRows);
        if (page.sections.isEmpty()) {
            Page.Section loose = continuationSection(root, typedRows);
            if (!loose.items.isEmpty()) page.sections.add(loose);
        }
        fillFromHeader(page);
        return page;
    }

    /** The items and next token of a continuation answer (playlist/search/shelf paging). */
    public static Page.Section continuationSection(JSONObject root, boolean typedRows) {
        Page.Section section = new Page.Section();
        Object holder = root.opt("continuationContents");
        if (holder == null) holder = root.opt("onResponseReceivedActions");
        if (holder == null) holder = root;
        List<JSONObject> wrappers = new ArrayList<JSONObject>();
        collectItemWrappers(holder, wrappers);
        for (JSONObject w : wrappers) {
            Item item = item(w, typedRows);
            if (item != null) section.items.add(item);
        }
        String legacy = findContinuation(holder, true);
        if (legacy.length() > 0) {
            section.continuation = legacy;
            section.legacyContinuation = true;
        } else {
            section.continuation = findContinuation(holder, false);
        }
        return section;
    }

    private static void collectSections(Object node, Page page, boolean typed) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String key : SECTION_KEYS) {
                JSONObject s = o.optJSONObject(key);
                if (s != null) {
                    section(key, s, page, typed);
                    return;
                }
            }
            // A continuation answer's shelf continuation, e.g. musicShelfContinuation.
            for (String key : new String[]{"musicShelfContinuation", "musicPlaylistShelfContinuation",
                    "gridContinuation"}) {
                JSONObject s = o.optJSONObject(key);
                if (s != null) {
                    section("musicShelfRenderer", s, page, typed);
                    return;
                }
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (skip(key)) continue;
                collectSections(o.opt(key), page, typed);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectSections(a.opt(i), page, typed);
        }
    }

    private static boolean skip(String key) {
        for (String s : SKIP_KEYS) if (s.equals(key)) return true;
        return false;
    }

    private static void section(String key, JSONObject s, Page page, boolean typed) {
        Page.Section section = new Page.Section();
        if ("itemSectionRenderer".equals(key)) {
            // May wrap real shelves; otherwise it holds loose rows (new search layout).
            JSONArray contents = s.optJSONArray("contents");
            boolean nested = false;
            if (contents != null) {
                for (int i = 0; i < contents.length(); i++) {
                    JSONObject c = contents.optJSONObject(i);
                    if (c == null) continue;
                    for (String k : SECTION_KEYS) {
                        if (c.has(k)) {
                            nested = true;
                            section(k, c.optJSONObject(k), page, typed);
                        }
                    }
                }
            }
            if (nested) return;
            section.title = text(path(s, "header", "itemSectionHeaderRenderer", "title"));
            addItems(contents, section, typed);
            if (section.items.isEmpty()) return;
            // Merge consecutive untitled loose rows into one list.
            if (section.title.length() == 0 && !page.sections.isEmpty()) {
                Page.Section last = page.sections.get(page.sections.size() - 1);
                if (last.style == Page.Section.LIST && last.title.length() == 0
                        && last.continuation.length() == 0) {
                    last.items.addAll(section.items);
                    return;
                }
            }
            page.sections.add(section);
            return;
        }
        if ("musicCardShelfRenderer".equals(key)) {
            section.title = text(path(s, "header", "musicCardShelfHeaderBasicRenderer", "title"));
            Item top = cardItem(s);
            if (top != null) section.items.add(top);
            addItems(s.optJSONArray("contents"), section, typed);
            if (top != null && top.type == Item.ARTIST) {
                for (Item i : section.items) {
                    if (i.song != null && i.song.artist.length() == 0) {
                        i.song.artist = top.title;
                        i.song.artistId = top.browseId;
                        i.subtitle = i.song.subtitle();
                    }
                }
            }
            if (!section.items.isEmpty()) page.sections.add(section);
            return;
        }
        if ("musicCarouselShelfRenderer".equals(key) || "musicImmersiveCarouselShelfRenderer".equals(key)) {
            JSONObject header = path(s, "header", "musicCarouselShelfBasicHeaderRenderer");
            if (header == null) header = path(s, "header", "musicImmersiveCarouselShelfBasicHeaderRenderer");
            section.title = text(header == null ? null : header.optJSONObject("title"));
            JSONObject more = header == null ? null
                    : path(header, "moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint");
            if (more == null && header != null) more = path(header, "title", "runs", "0", "navigationEndpoint", "browseEndpoint");
            if (more != null) {
                section.moreBrowseId = more.optString("browseId", "");
                section.moreParams = more.optString("params", "");
            }
            section.style = Page.Section.CAROUSEL;
            addItems(s.optJSONArray("contents"), section, typed);
        } else if ("gridRenderer".equals(key)) {
            section.title = text(path(s, "header", "gridHeaderRenderer", "title"));
            addItems(s.optJSONArray("items"), section, typed);
            section.style = Page.Section.LIST;
        } else { // musicShelfRenderer, musicPlaylistShelfRenderer
            section.title = text(s.optJSONObject("title"));
            JSONObject more = path(s, "bottomEndpoint", "browseEndpoint");
            if (more != null) {
                section.moreBrowseId = more.optString("browseId", "");
                section.moreParams = more.optString("params", "");
            }
            addItems(s.optJSONArray("contents"), section, typed);
            String legacy = findContinuation(s.opt("continuations"), true);
            if (legacy.length() > 0) {
                section.continuation = legacy;
                section.legacyContinuation = true;
            } else {
                section.continuation = findContinuation(s.opt("contents"), false);
            }
            if (s.has("playlistId") && page.playlistId.length() == 0) {
                page.playlistId = s.optString("playlistId", "");
            }
        }
        if (section.items.isEmpty()) return;
        boolean allNav = true;
        for (Item i : section.items) if (i.type != Item.NAV) allNav = false;
        if (allNav) section.style = Page.Section.TILES;
        page.sections.add(section);
    }

    private static void addItems(JSONArray contents, Page.Section section, boolean typed) {
        if (contents == null) return;
        for (int i = 0; i < contents.length(); i++) {
            JSONObject w = contents.optJSONObject(i);
            if (w == null) continue;
            Item item = item(w, typed);
            if (item != null) section.items.add(item);
        }
    }

    private static void collectItemWrappers(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : ITEM_KEYS) {
                if (o.has(k)) {
                    out.add(o);
                    return;
                }
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (skip(key)) continue;
                collectItemWrappers(o.opt(key), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectItemWrappers(a.opt(i), out);
        }
    }

    /** A token for the next page: legacy = nextContinuationData, else a continuationCommand. */
    static String findContinuation(Object node, boolean legacy) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (legacy) {
                JSONObject d = o.optJSONObject("nextContinuationData");
                if (d != null && d.optString("continuation", "").length() > 0) return d.optString("continuation");
            } else {
                JSONObject c = o.optJSONObject("continuationCommand");
                if (c != null && c.optString("token", "").length() > 0) return c.optString("token");
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (skip(key)) continue;
                String found = findContinuation(o.opt(key), legacy);
                if (found.length() > 0) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String found = findContinuation(a.opt(i), legacy);
                if (found.length() > 0) return found;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ header

    private static void parseHeader(JSONObject root, Page page) {
        JSONObject h = findFirst(root, "musicResponsiveHeaderRenderer");
        if (h == null) h = findFirst(root, "musicDetailHeaderRenderer");
        if (h != null) {
            page.title = text(h.optJSONObject("title"));
            page.subtitle = text(h.optJSONObject("subtitle"));
            page.secondSubtitle = text(h.optJSONObject("secondSubtitle"));
            JSONObject strap = h.optJSONObject("straplineTextOne");
            if (strap != null) {
                page.artist = text(strap);
                page.artistId = firstBrowseId(strap, "UC");
            }
            if (page.artistId.length() == 0) {
                page.artistId = firstBrowseId(h.optJSONObject("subtitle"), "UC");
            }
            page.thumb = thumbnail(h);
            JSONObject desc = findFirst(h, "musicDescriptionShelfRenderer");
            if (desc != null) page.description = text(desc.optJSONObject("description"));
            else page.description = text(h.optJSONObject("description"));
            JSONObject watch = findFirst(h, "watchEndpoint");
            if (watch != null) page.playlistId = watch.optString("playlistId", "");
            JSONObject shuffle = findFirst(h, "watchPlaylistEndpoint");
            if (shuffle != null) page.shufflePlaylistId = shuffle.optString("playlistId", "");
        } else {
            h = findFirst(root, "musicImmersiveHeaderRenderer");
            if (h == null) h = findFirst(root, "musicVisualHeaderRenderer");
            if (h != null) { // artist
                page.title = text(h.optJSONObject("title"));
                page.description = text(h.optJSONObject("description"));
                page.thumb = thumbnail(h);
                JSONObject play = findFirst(h.opt("playButton"), "watchEndpoint");
                if (play != null) page.shufflePlaylistId = play.optString("playlistId", "");
                JSONObject radio = findFirst(h.opt("startRadioButton"), "watchEndpoint");
                if (radio != null) {
                    page.radioPlaylistId = radio.optString("playlistId", "");
                    page.radioVideoId = radio.optString("videoId", "");
                }
            } else {
                h = findFirst(root, "musicHeaderRenderer");
                if (h != null) page.title = text(h.optJSONObject("title"));
            }
        }
        if (page.playlistId.length() == 0) {
            JSONObject micro = findFirst(root.opt("microformat"), "microformatDataRenderer");
            String url = micro == null ? "" : micro.optString("urlCanonical", "");
            int at = url.indexOf("list=");
            if (at >= 0) {
                String id = url.substring(at + 5);
                int amp = id.indexOf('&');
                page.playlistId = amp < 0 ? id : id.substring(0, amp);
            }
        }
    }

    /** Album rows carry neither art nor album/artist: take them from the header. */
    private static void fillFromHeader(Page page) {
        boolean album = page.playlistId.startsWith("OLAK5uy_");
        for (Page.Section s : page.sections) {
            if (s.style != Page.Section.LIST) continue;
            for (Item i : s.items) {
                if (i.song == null) continue;
                if (i.song.thumb.length() == 0) {
                    i.song.thumb = page.thumb;
                    i.thumb = page.thumb;
                }
                if (album && i.song.album.length() == 0) i.song.album = page.title;
                if (i.song.artist.length() == 0 && page.artist.length() > 0) {
                    i.song.artist = page.artist;
                    i.song.artistId = page.artistId;
                }
                i.subtitle = i.song.subtitle();
            }
        }
    }

    // ------------------------------------------------------------------ items

    static Item item(JSONObject wrapper, boolean typed) {
        JSONObject r = wrapper.optJSONObject("musicResponsiveListItemRenderer");
        if (r != null) return row(r, typed);
        r = wrapper.optJSONObject("musicTwoRowItemRenderer");
        if (r != null) return card(r);
        r = wrapper.optJSONObject("musicNavigationButtonRenderer");
        if (r != null) return navButton(r);
        return null;
    }

    /** musicResponsiveListItemRenderer: song rows and list entries for albums/artists/playlists. */
    static Item row(JSONObject r, boolean typed) {
        JSONArray flex = r.optJSONArray("flexColumns");
        JSONObject col0 = column(flex, 0);
        String title = text(col0 == null ? null : col0.optJSONObject("text"));
        if (title.length() == 0) return null;
        JSONObject titleRun = path(col0, "text", "runs", "0");
        String videoId = "";
        JSONObject data = r.optJSONObject("playlistItemData");
        if (data != null) videoId = data.optString("videoId", "");
        JSONObject watch = titleRun == null ? null : path(titleRun, "navigationEndpoint", "watchEndpoint");
        if (videoId.length() == 0 && watch != null) videoId = watch.optString("videoId", "");
        JSONObject overlayWatch = path(r, "overlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint");
        if (videoId.length() == 0 && overlayWatch != null) videoId = overlayWatch.optString("videoId", "");

        // Runs of all other columns, with a separator between columns.
        List<JSONObject> runs = new ArrayList<JSONObject>();
        // Only the second column may hold an artist without a link; later ones are plays/views.
        List<JSONObject> secondColumn = new ArrayList<JSONObject>();
        if (flex != null) {
            for (int c = 1; c < flex.length(); c++) {
                JSONObject col = column(flex, c);
                JSONArray colRuns = col == null ? null : path2(col, "text", "runs");
                if (colRuns == null || colRuns.length() == 0) continue;
                if (!runs.isEmpty()) runs.add(separatorRun());
                for (int i = 0; i < colRuns.length(); i++) {
                    JSONObject run = colRuns.optJSONObject(i);
                    if (run == null) continue;
                    runs.add(run);
                    if (c == 1) secondColumn.add(run);
                }
            }
        }
        String fixed = "";
        JSONArray fixedCols = r.optJSONArray("fixedColumns");
        if (fixedCols != null && fixedCols.length() > 0) {
            JSONObject fc = fixedCols.optJSONObject(0);
            fixed = text(path(fc, "musicResponsiveListItemFixedColumnRenderer", "text"));
        }

        JSONObject browse = path(r, "navigationEndpoint", "browseEndpoint");
        String browsePage = browse == null ? "" : pageType(browse);
        boolean isCollection = browse != null && (browsePage.endsWith("ALBUM") || browsePage.endsWith("ARTIST")
                || browsePage.endsWith("PLAYLIST") || browsePage.endsWith("USER_CHANNEL")
                || browsePage.length() == 0);
        if (videoId.length() > 0 && !isCollection) {
            Song song = new Song();
            song.videoId = videoId;
            song.title = title;
            String type = videoType(watch != null ? watch : overlayWatch);
            if (type.length() == 0) type = videoType(findFirst(r, "watchEndpoint"));
            if (type.endsWith("PODCAST_EPISODE")) return null;
            song.video = type.endsWith("OMV") || type.endsWith("UGC") || type.endsWith("OFFICIAL_SOURCE_MUSIC");
            fillArtistAlbum(song, runs, typed, secondColumn);
            if (DURATION.matcher(fixed.trim()).matches()) song.durationSec = (int) parseTime(fixed);
            song.thumb = thumbnail(r);
            Item item = Item.ofSong(song);
            return item;
        }
        if (browse == null) return null;
        Item item = new Item();
        item.title = title;
        item.browseId = browse.optString("browseId", "");
        item.params = browse.optString("params", "");
        item.thumb = thumbnail(r);
        item.subtitle = joinRuns(runs, typed);
        if (!setCollectionType(item, browsePage)) return null;
        return item;
    }

    /** musicTwoRowItemRenderer: the square cards of carousels. */
    static Item card(JSONObject r) {
        Item item = new Item();
        item.title = text(r.optJSONObject("title"));
        if (item.title.length() == 0) return null;
        JSONArray subtitleRuns = path2(r, "subtitle", "runs");
        List<JSONObject> runs = new ArrayList<JSONObject>();
        if (subtitleRuns != null) {
            for (int i = 0; i < subtitleRuns.length(); i++) {
                JSONObject run = subtitleRuns.optJSONObject(i);
                if (run != null) runs.add(run);
            }
        }
        item.subtitle = joinRuns(runs, false);
        item.thumb = thumbnail(r);
        JSONObject nav = r.optJSONObject("navigationEndpoint");
        if (nav == null) return null;
        JSONObject browse = nav.optJSONObject("browseEndpoint");
        JSONObject watch = nav.optJSONObject("watchEndpoint");
        JSONObject mix = nav.optJSONObject("watchPlaylistEndpoint");
        if (browse != null) {
            item.browseId = browse.optString("browseId", "");
            item.params = browse.optString("params", "");
            if (!setCollectionType(item, pageType(browse))) return null;
            return item;
        }
        if (watch != null && watch.optString("videoId", "").length() > 0) {
            Song song = new Song();
            song.videoId = watch.optString("videoId");
            song.title = item.title;
            String type = videoType(watch);
            if (type.endsWith("PODCAST_EPISODE")) return null;
            song.video = !type.endsWith("ATV");
            fillArtistAlbum(song, runs, false, runs);
            song.thumb = item.thumb;
            Item s = Item.ofSong(song);
            s.subtitle = song.artist.length() > 0 ? song.artist : item.subtitle;
            return s;
        }
        if (mix != null && mix.optString("playlistId", "").length() > 0) {
            item.type = Item.MIX;
            item.playlistId = mix.optString("playlistId");
            item.params = mix.optString("params", "");
            return item;
        }
        return null;
    }

    static Item navButton(JSONObject r) {
        Item item = new Item();
        item.type = Item.NAV;
        item.title = text(r.optJSONObject("buttonText"));
        JSONObject browse = path(r, "clickCommand", "browseEndpoint");
        if (browse == null || item.title.length() == 0) return null;
        item.browseId = browse.optString("browseId", "");
        item.params = browse.optString("params", "");
        JSONObject solid = r.optJSONObject("solid");
        if (solid != null) item.color = (int) solid.optLong("leftStripeColor", 0);
        return item;
    }

    /** The big "top result" card of a search. */
    private static Item cardItem(JSONObject s) {
        String title = text(s.optJSONObject("title"));
        if (title.length() == 0) return null;
        JSONObject nav = path(s, "title", "runs", "0", "navigationEndpoint");
        if (nav == null) nav = s.optJSONObject("onTap");
        if (nav == null) return null;
        List<JSONObject> runs = new ArrayList<JSONObject>();
        JSONArray sub = path2(s, "subtitle", "runs");
        if (sub != null) for (int i = 0; i < sub.length(); i++) runs.add(sub.optJSONObject(i));
        JSONObject watch = nav.optJSONObject("watchEndpoint");
        if (watch != null && watch.optString("videoId", "").length() > 0) {
            Song song = new Song();
            song.videoId = watch.optString("videoId");
            song.title = title;
            song.video = !videoType(watch).endsWith("ATV");
            fillArtistAlbum(song, runs, true, null);
            song.thumb = thumbnail(s);
            return Item.ofSong(song);
        }
        JSONObject browse = nav.optJSONObject("browseEndpoint");
        if (browse == null) return null;
        Item item = new Item();
        item.title = title;
        item.subtitle = joinRuns(runs, true);
        item.thumb = thumbnail(s);
        item.browseId = browse.optString("browseId", "");
        item.params = browse.optString("params", "");
        if (!setCollectionType(item, pageType(browse))) return null;
        return item;
    }

    private static boolean setCollectionType(Item item, String pageType) {
        String id = item.browseId;
        if (pageType.endsWith("_ALBUM") || id.startsWith("MPREb_")) {
            item.type = Item.ALBUM;
        } else if (pageType.endsWith("_PLAYLIST") || id.startsWith("VL")) {
            item.type = Item.PLAYLIST;
            if (id.startsWith("VL")) item.playlistId = id.substring(2);
        } else if (pageType.endsWith("_ARTIST") || pageType.endsWith("USER_CHANNEL")
                || (pageType.length() == 0 && id.startsWith("UC"))) {
            item.type = Item.ARTIST;
        } else if (id.startsWith("FEmusic_") || id.startsWith("MPAD")) {
            item.type = Item.NAV;
        } else {
            return false; // podcasts, audiobooks, …
        }
        return id.length() > 0;
    }

    /**
     * Artist and album out of the subtitle runs ("Artist • Album • 3:21", "Song • Artist",
     * "Artist • 1.2M views"). Artists/albums are recognised by their browse endpoints.
     */
    static void fillArtistAlbum(Song song, List<JSONObject> runs, boolean typed,
                                List<JSONObject> fallbackRuns) {
        List<List<JSONObject>> groups = splitGroups(runs);
        if (typed && groups.size() > 1) {
            List<JSONObject> first = groups.get(0);
            if (first.size() == 1 && first.get(0).optJSONObject("navigationEndpoint") == null) {
                groups.remove(0); // the "Song"/"Video" label
            }
        }
        int artistGroup = -1;
        for (int g = 0; g < groups.size() && artistGroup < 0; g++) {
            for (JSONObject run : groups.get(g)) {
                JSONObject b = path(run, "navigationEndpoint", "browseEndpoint");
                if (b == null) continue;
                String pt = pageType(b);
                String id = b.optString("browseId", "");
                if (pt.endsWith("_ARTIST") || pt.endsWith("USER_CHANNEL") || (pt.length() == 0 && id.startsWith("UC"))) {
                    artistGroup = g;
                    if (song.artistId.length() == 0) song.artistId = id;
                    break;
                }
            }
        }
        if (artistGroup >= 0) {
            song.artist = groupText(groups.get(artistGroup)).trim();
        } else if (!typed && fallbackRuns != null) {
            List<List<JSONObject>> fallback = splitGroups(fallbackRuns);
            if (!fallback.isEmpty()) {
                String t = groupText(fallback.get(0)).trim();
                if (t.length() > 0 && !DURATION.matcher(t).matches() && !hasAlbum(fallback.get(0))) song.artist = t;
            }
        }
        for (List<JSONObject> group : groups) {
            for (JSONObject run : group) {
                JSONObject b = path(run, "navigationEndpoint", "browseEndpoint");
                if (b == null) continue;
                String id = b.optString("browseId", "");
                if (pageType(b).endsWith("_ALBUM") || id.startsWith("MPREb_")) {
                    if (song.album.length() == 0) {
                        song.album = run.optString("text", "");
                        song.albumId = id;
                    }
                }
            }
            String t = groupText(group).trim();
            if (song.durationSec == 0 && DURATION.matcher(t).matches()) song.durationSec = (int) parseTime(t);
        }
    }

    private static boolean hasAlbum(List<JSONObject> group) {
        for (JSONObject run : group) {
            JSONObject b = path(run, "navigationEndpoint", "browseEndpoint");
            if (b != null && (pageType(b).endsWith("_ALBUM") || b.optString("browseId", "").startsWith("MPREb_"))) {
                return true;
            }
        }
        return false;
    }

    private static List<List<JSONObject>> splitGroups(List<JSONObject> runs) {
        List<List<JSONObject>> groups = new ArrayList<List<JSONObject>>();
        List<JSONObject> current = new ArrayList<JSONObject>();
        for (JSONObject run : runs) {
            if (run == null) continue;
            if (SEPARATOR.equals(run.optString("text", "").trim())) {
                if (!current.isEmpty()) groups.add(current);
                current = new ArrayList<JSONObject>();
            } else {
                current.add(run);
            }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private static String groupText(List<JSONObject> group) {
        StringBuilder b = new StringBuilder();
        for (JSONObject run : group) b.append(run.optString("text", ""));
        return b.toString();
    }

    /** Subtitle text; for typed rows the type label stays (it tells albums from singles). */
    private static String joinRuns(List<JSONObject> runs, boolean typed) {
        StringBuilder b = new StringBuilder();
        for (JSONObject run : runs) if (run != null) b.append(run.optString("text", ""));
        return b.toString().trim();
    }

    private static JSONObject separatorRun() {
        JSONObject o = new JSONObject();
        try {
            o.put("text", " " + SEPARATOR + " ");
        } catch (Exception ignored) {
        }
        return o;
    }

    // ------------------------------------------------------------------ radio / watch

    /** The queue of a "next" answer (radio, album/playlist playback) and its tabs. */
    public static final class Watch {
        public final List<Song> songs = new ArrayList<Song>();
        public String continuation = "";
        public String lyricsBrowseId = "";
        public String relatedBrowseId = "";
        public String playlistId = "";
    }

    public static Watch watch(JSONObject root) {
        Watch w = new Watch();
        List<JSONObject> renderers = new ArrayList<JSONObject>();
        collectPanelVideos(root, renderers);
        for (JSONObject r : renderers) {
            Song s = panelSong(r);
            if (s != null) w.songs.add(s);
        }
        JSONObject panel = findFirst(root, "playlistPanelRenderer");
        if (panel == null) panel = findFirst(root, "playlistPanelContinuation");
        if (panel != null) {
            w.playlistId = panel.optString("playlistId", "");
            JSONObject radio = findFirst(panel.opt("continuations"), "nextRadioContinuationData");
            if (radio == null) radio = findFirst(panel.opt("continuations"), "nextContinuationData");
            if (radio != null) w.continuation = radio.optString("continuation", "");
        }
        List<JSONObject> tabs = new ArrayList<JSONObject>();
        collectByKey(root, "tabRenderer", tabs);
        for (JSONObject tab : tabs) {
            JSONObject b = path(tab, "endpoint", "browseEndpoint");
            if (b == null) continue;
            String pt = pageType(b);
            if (pt.endsWith("TRACK_LYRICS")) w.lyricsBrowseId = b.optString("browseId", "");
            else if (pt.endsWith("TRACK_RELATED")) w.relatedBrowseId = b.optString("browseId", "");
        }
        return w;
    }

    private static void collectPanelVideos(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject wrapper = o.optJSONObject("playlistPanelVideoWrapperRenderer");
            if (wrapper != null) {
                JSONObject primary = path(wrapper, "primaryRenderer", "playlistPanelVideoRenderer");
                if (primary != null) out.add(primary);
                return;
            }
            JSONObject r = o.optJSONObject("playlistPanelVideoRenderer");
            if (r != null) {
                out.add(r);
                return;
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("responseContext".equals(key) || "frameworkUpdates".equals(key)) continue;
                collectPanelVideos(o.opt(key), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectPanelVideos(a.opt(i), out);
        }
    }

    static Song panelSong(JSONObject r) {
        Song s = new Song();
        s.videoId = r.optString("videoId", "");
        if (!Song.isVideoId(s.videoId)) return null;
        s.title = text(r.optJSONObject("title"));
        if (s.title.length() == 0) return null;
        List<JSONObject> runs = new ArrayList<JSONObject>();
        JSONArray by = path2(r, "longBylineText", "runs");
        if (by == null) by = path2(r, "shortBylineText", "runs");
        if (by != null) for (int i = 0; i < by.length(); i++) runs.add(by.optJSONObject(i));
        List<List<JSONObject>> groups = splitGroups(runs);
        if (!groups.isEmpty()) {
            s.artist = groupText(groups.get(0)).trim();
            s.artistId = firstBrowseIdIn(groups.get(0), "UC");
        }
        for (List<JSONObject> g : groups) {
            for (JSONObject run : g) {
                JSONObject b = path(run, "navigationEndpoint", "browseEndpoint");
                if (b != null && (pageType(b).endsWith("_ALBUM") || b.optString("browseId", "").startsWith("MPREb_"))
                        && s.album.length() == 0) {
                    s.album = run.optString("text", "");
                    s.albumId = b.optString("browseId", "");
                }
            }
        }
        String length = text(r.optJSONObject("lengthText"));
        if (DURATION.matcher(length.trim()).matches()) s.durationSec = (int) parseTime(length);
        s.thumb = thumbnail(r);
        String type = videoType(path(r, "navigationEndpoint", "watchEndpoint"));
        s.video = type.length() > 0 && !type.endsWith("ATV");
        return s;
    }

    // ------------------------------------------------------------------ search helpers

    /** Text suggestions of a get_search_suggestions answer. */
    public static List<String> suggestions(JSONObject root) {
        List<JSONObject> found = new ArrayList<JSONObject>();
        collectByKey(root, "searchSuggestionRenderer", found);
        List<String> out = new ArrayList<String>();
        for (JSONObject s : found) {
            JSONObject search = path(s, "navigationEndpoint", "searchEndpoint");
            String q = search != null ? search.optString("query", "") : text(s.optJSONObject("suggestion"));
            if (q.length() > 0 && !out.contains(q)) out.add(q);
        }
        return out;
    }

    /** Lyrics text (with the source line appended), or "" when the answer has none. */
    public static String lyrics(JSONObject root) {
        JSONObject shelf = findFirst(root, "musicDescriptionShelfRenderer");
        if (shelf == null) return "";
        String body = text(shelf.optJSONObject("description"));
        String footer = text(shelf.optJSONObject("footer"));
        return footer.length() > 0 ? body + "\n\n" + footer : body;
    }

    // ------------------------------------------------------------------ JSON utilities

    public static String text(JSONObject t) {
        if (t == null) return "";
        JSONArray runs = t.optJSONArray("runs");
        if (runs == null) return t.optString("simpleText", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) b.append(run.optString("text", ""));
        }
        return b.toString();
    }

    /** Follows keys (or array indexes as digits); null when a step is missing. */
    public static JSONObject path(JSONObject o, String... keys) {
        Object cur = o;
        for (String k : keys) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(k);
            } else if (cur instanceof JSONArray) {
                try {
                    cur = ((JSONArray) cur).opt(Integer.parseInt(k));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur instanceof JSONObject ? (JSONObject) cur : null;
    }

    private static JSONArray path2(JSONObject o, String key, String arrayKey) {
        JSONObject inner = o == null ? null : o.optJSONObject(key);
        return inner == null ? null : inner.optJSONArray(arrayKey);
    }

    private static JSONObject column(JSONArray flex, int index) {
        if (flex == null || index >= flex.length()) return null;
        JSONObject c = flex.optJSONObject(index);
        return c == null ? null : c.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
    }

    public static JSONObject findFirst(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject direct = o.optJSONObject(key);
            if (direct != null) return direct;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if ("responseContext".equals(k) || "frameworkUpdates".equals(k)) continue;
                JSONObject found = findFirst(o.opt(k), key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                JSONObject found = findFirst(a.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static void collectByKey(Object node, String key, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject item = o.optJSONObject(key);
            if (item != null) {
                out.add(item);
                return;
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) collectByKey(o.opt(keys.next()), key, out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectByKey(a.opt(i), key, out);
        }
    }

    private static String pageType(JSONObject browseEndpoint) {
        JSONObject c = path(browseEndpoint, "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig");
        return c == null ? "" : c.optString("pageType", "");
    }

    private static String videoType(JSONObject watchEndpoint) {
        JSONObject c = watchEndpoint == null ? null : path(watchEndpoint,
                "watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig");
        return c == null ? "" : c.optString("musicVideoType", "");
    }

    private static String firstBrowseId(JSONObject textObject, String prefix) {
        JSONArray runs = textObject == null ? null : textObject.optJSONArray("runs");
        if (runs == null) return "";
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (int i = 0; i < runs.length(); i++) list.add(runs.optJSONObject(i));
        return firstBrowseIdIn(list, prefix);
    }

    private static String firstBrowseIdIn(List<JSONObject> runs, String prefix) {
        for (JSONObject run : runs) {
            JSONObject b = run == null ? null : path(run, "navigationEndpoint", "browseEndpoint");
            if (b != null && b.optString("browseId", "").startsWith(prefix)) return b.optString("browseId");
        }
        return "";
    }

    /** The largest thumbnail under the renderer (any of the usual thumbnail containers). */
    static String thumbnail(JSONObject r) {
        JSONObject t = null;
        for (String k : new String[]{"thumbnail", "thumbnailRenderer", "foregroundThumbnail"}) {
            JSONObject holder = r.optJSONObject(k);
            if (holder == null) continue;
            JSONObject inner = holder.optJSONObject("musicThumbnailRenderer");
            if (inner == null) inner = holder.optJSONObject("croppedSquareThumbnailRenderer");
            if (inner != null) holder = inner;
            t = holder.optJSONObject("thumbnail");
            if (t == null && holder.has("thumbnails")) t = holder;
            if (t != null) break;
        }
        JSONArray list = t == null ? null : t.optJSONArray("thumbnails");
        if (list == null || list.length() == 0) return "";
        JSONObject best = list.optJSONObject(list.length() - 1);
        String url = best == null ? "" : best.optString("url", "");
        if (url.startsWith("//")) url = "https:" + url;
        return url;
    }

    public static long parseTime(String value) {
        if (value == null) return 0;
        String[] parts = value.trim().split(":");
        long seconds = 0;
        try {
            for (String p : parts) seconds = seconds * 60 + Long.parseLong(p.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        return seconds;
    }
}
