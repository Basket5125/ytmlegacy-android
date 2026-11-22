package com.ytmlegacy.android.ui;

import android.view.View;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.yt.InnerTube;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Page;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** Home: YouTube Music's home feed, with the songs played recently on top. */
public final class HomeScreen extends ListScreen {

    private final boolean explore;

    public HomeScreen(boolean explore) {
        this.explore = explore;
    }

    @Override
    public String title() {
        return act.getString(explore ? R.string.tab_explore : R.string.app_name);
    }

    private boolean shownCached;

    @Override
    protected void load() {
        if (!shownCached) {
            // Show the copy saved last time at once; the fresh page replaces it when it arrives.
            shownCached = true;
            final String id = explore ? "FEmusic_explore" : "FEmusic_home";
            run(new Task<Page>() {
                @Override
                protected Page work() {
                    return InnerTube.get(act).cachedPage(id);
                }

                @Override
                protected void done(Page page) {
                    if (page == null) {
                        fetchFresh();
                        return;
                    }
                    List<Rows.Row> rows = new ArrayList<Rows.Row>();
                    if (!explore) addRecent(rows);
                    rows.addAll(Rows.fromPage(page, false));
                    showCached(rows);
                    // A copy younger than half an hour is good enough: parsing the same big
                    // answer again is what heats old single-core phones. Reselecting refreshes.
                    if (InnerTube.get(act).cachedAge(id) < 30L * 60 * 1000) loadDone();
                    else fetchFresh();
                }

                @Override
                protected void failed(Exception e) {
                    fetchFresh();
                }
            });
            return;
        }
        fetchFresh();
    }

    private void fetchFresh() {
        run(new Task<Page>() {
            @Override
            protected Page work() throws Exception {
                InnerTube tube = InnerTube.get(act);
                return explore ? tube.explore() : tube.home();
            }

            @Override
            protected void done(Page page) {
                fresh = true;
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                if (!explore) addRecent(rows);
                rows.addAll(Rows.fromPage(page, false));
                int first = list.getFirstVisiblePosition();
                View top = list.getChildAt(0);
                int offset = top == null ? 0 : top.getTop();
                setRows(rows);
                if (cachedShown) list.setSelectionFromTop(first, offset); // keep the scroll position
            }

            @Override
            protected void failed(Exception e) {
                if (cachedShown) {
                    loadDone();
                    return; // the saved copy stays on screen
                }
                if (!explore && Prefs.get(act).playHistory()) {
                    // Offline: still offer what was played lately.
                    List<Rows.Row> rows = new ArrayList<Rows.Row>();
                    showError(e);
                    rows.addAll(adapter.rows());
                    addRecent(rows);
                    adapter.setRows(rows);
                } else {
                    showError(e);
                }
            }
        });
    }

    private boolean fresh;
    private boolean cachedShown;

    private void showCached(List<Rows.Row> rows) {
        cachedShown = true;
        adapter.setRows(rows);
        adapter.setPlaying(act.playingId());
    }

    private void addRecent(List<Rows.Row> rows) {
        if (!Prefs.get(act).playHistory()) return;
        List<Song> recent = Library.get(act).history(12);
        if (recent.isEmpty()) return;
        Page.Section s = new Page.Section();
        s.title = act.getString(R.string.history);
        s.style = Page.Section.CAROUSEL;
        for (Song song : recent) s.items.add(Item.ofSong(song));
        rows.add(Rows.header(s.title, null));
        Rows.Row r = new Rows.Row();
        r.type = Rows.CAROUSEL;
        r.items = s.items;
        r.section = s;
        rows.add(r);
    }

    @Override
    public void onReselected() {
        if (list != null && list.getFirstVisiblePosition() == 0) reload();
        else super.onReselected();
    }
}
