package com.ytmlegacy.android.ui;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** Recently played songs. */
public final class HistoryScreen extends ListScreen {

    @Override
    public String title() {
        return act.getString(R.string.history);
    }

    @Override
    protected void load() {
        run(new Task<List<Song>>() {
            @Override
            protected List<Song> work() {
                return Library.get(act).history(300);
            }

            @Override
            protected void done(List<Song> songs) {
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                if (!songs.isEmpty()) {
                    rows.add(Rows.action(R.drawable.ic_delete, act.getString(R.string.clear_history), null, null,
                            new Runnable() {
                                @Override
                                public void run() {
                                    Library.get(act).clearHistory();
                                    reload();
                                }
                            }));
                }
                for (int i = 0; i < songs.size(); i++) rows.add(Rows.song(songs.get(i), songs, i));
                setRows(rows);
            }
        });
    }
}
