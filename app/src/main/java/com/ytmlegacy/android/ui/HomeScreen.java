package com.ytmlegacy.android.ui;

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

    @Override
    protected void load() {
        run(new Task<Page>() {
            @Override
            protected Page work() throws Exception {
                InnerTube tube = InnerTube.get(act);
                return explore ? tube.explore() : tube.home();
            }

            @Override
            protected void done(Page page) {
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                if (!explore) addRecent(rows);
                rows.addAll(Rows.fromPage(page, false));
                setRows(rows);
            }

            @Override
            protected void failed(Exception e) {
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
