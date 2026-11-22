package com.ytmlegacy.android.ui;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.yt.Item;

import java.util.ArrayList;
import java.util.List;

/** The local library: liked songs, downloads, history, playlists and saved collections. */
public final class LibraryScreen extends ListScreen {

    @Override
    public String title() {
        return act.getString(R.string.tab_library);
    }

    @Override
    protected void load() {
        run(new Task<Object[]>() {
            @Override
            protected Object[] work() {
                Library lib = Library.get(act);
                return new Object[]{lib.playlists(), lib.saved(), lib.downloads(Library.DL_DONE).size()};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done(Object[] r) {
                build((List<Library.Playlist>) r[0], (List<Item>) r[1], (Integer) r[2]);
            }
        });
    }

    private void build(List<Library.Playlist> playlists, List<Item> saved, int downloads) {
        List<Rows.Row> rows = new ArrayList<Rows.Row>();
        for (final Library.Playlist p : playlists) {
            if (!p.liked) continue;
            rows.add(Rows.action(R.drawable.ic_favorite, act.getString(R.string.liked_songs),
                    act.getResources().getQuantityString(R.plurals.songs, p.count, p.count), null, new Runnable() {
                        @Override
                        public void run() {
                            act.push(new LocalPlaylistScreen(p.id));
                        }
                    }));
        }
        rows.add(Rows.action(R.drawable.ic_download_done, act.getString(R.string.downloads),
                act.getResources().getQuantityString(R.plurals.songs, downloads, downloads), null, new Runnable() {
                    @Override
                    public void run() {
                        act.push(new DownloadsScreen());
                    }
                }));
        if (Prefs.get(act).stats()) {
            rows.add(Rows.action(R.drawable.ic_stats, act.getString(R.string.stats), null, null, new Runnable() {
                @Override
                public void run() {
                    act.push(new StatsScreen());
                }
            }));
        }
        if (Prefs.get(act).playHistory()) {
            rows.add(Rows.action(R.drawable.ic_history, act.getString(R.string.history), null, null, new Runnable() {
                @Override
                public void run() {
                    act.push(new HistoryScreen());
                }
            }));
        }
        rows.add(Rows.header(act.getString(R.string.playlists), null));
        rows.add(Rows.action(R.drawable.ic_add, act.getString(R.string.new_playlist), null, null, new Runnable() {
            @Override
            public void run() {
                act.actions().newPlaylist(null);
            }
        }));
        rows.add(Rows.action(R.drawable.ic_import, act.getString(R.string.import_csv), null, null, new Runnable() {
            @Override
            public void run() {
                act.csv().pickImport();
            }
        }));
        for (final Library.Playlist p : playlists) {
            if (p.liked) continue;
            Rows.Row r = Rows.action(R.drawable.ic_queue, p.name, act.getResources().getQuantityString(R.plurals.songs, p.count, p.count),
                    p.thumb, new Runnable() {
                        @Override
                        public void run() {
                            act.push(new LocalPlaylistScreen(p.id));
                        }
                    });
            r.tag = p;
            rows.add(r);
        }
        if (!saved.isEmpty()) {
            rows.add(Rows.header(act.getString(R.string.saved), null));
            for (Item i : saved) rows.add(Rows.entry(i));
        }
        setRows(rows);
    }

    @Override
    public void onRowMenu(Rows.Row row) {
        if (row.tag instanceof Library.Playlist) {
            act.actions().localPlaylistMenu(((Library.Playlist) row.tag).id);
            return;
        }
        if (row.type == Rows.ENTRY && row.item != null) {
            act.actions().itemMenu(row.item);
            return;
        }
        super.onRowMenu(row);
    }

    @Override
    public void addMenu(android.view.Menu menu) {
        menu.add(0, MENU_SCREEN + 1, 21, R.string.new_playlist).setIcon(R.drawable.ic_add);
        menu.add(0, MENU_SCREEN + 2, 22, R.string.import_csv).setIcon(R.drawable.ic_import);
    }

    @Override
    public boolean onMenu(int id) {
        if (id == MENU_SCREEN + 1) {
            act.actions().newPlaylist(null);
            return true;
        }
        if (id == MENU_SCREEN + 2) {
            act.csv().pickImport();
            return true;
        }
        return false;
    }

    @Override
    public void onLibraryChanged() {
        if (!isDestroyed()) reload();
    }

    @Override
    public void onShown() {
        reload();
    }
}
