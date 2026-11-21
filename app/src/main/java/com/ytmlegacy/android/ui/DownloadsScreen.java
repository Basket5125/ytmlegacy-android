package com.ytmlegacy.android.ui;

import android.content.DialogInterface;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.player.DownloadService;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** Songs saved for offline listening, plus the ones still downloading. */
public final class DownloadsScreen extends ListScreen {

    @Override
    public String title() {
        return act.getString(R.string.downloads);
    }

    @Override
    protected void load() {
        run(new Task<List<Library.Download>>() {
            @Override
            protected List<Library.Download> work() {
                return Library.get(act).downloads(-1);
            }

            @Override
            protected void done(List<Library.Download> all) {
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                final List<Song> ready = new ArrayList<Song>();
                List<Library.Download> pending = new ArrayList<Library.Download>();
                long bytes = 0;
                for (Library.Download d : all) {
                    if (d.state == Library.DL_DONE) {
                        ready.add(d.song);
                        bytes += d.size;
                    } else {
                        pending.add(d);
                    }
                }
                if (!pending.isEmpty()) {
                    rows.add(Rows.header(act.getString(R.string.downloading), null));
                    boolean failed = false;
                    for (Library.Download d : pending) {
                        Item i = Item.ofSong(d.song);
                        i.subtitle = d.state == Library.DL_FAILED ? act.getString(R.string.download_failed)
                                + (d.error.length() > 0 ? ": " + d.error : "")
                                : d.state == Library.DL_RUNNING ? act.getString(R.string.download_running)
                                : act.getString(R.string.download_queued_state);
                        if (d.state == Library.DL_FAILED) failed = true;
                        Rows.Row r = Rows.song(i, null, 0);
                        r.tag = d;
                        rows.add(r);
                    }
                    if (failed) {
                        rows.add(Rows.action(R.drawable.ic_refresh, act.getString(R.string.downloads_retry), null, null,
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        List<Song> retry = new ArrayList<Song>();
                                        for (Library.Download d : Library.get(act).downloads(Library.DL_FAILED)) retry.add(d.song);
                                        DownloadService.enqueue(act, retry);
                                        reload();
                                    }
                                }));
                    }
                    rows.add(Rows.action(R.drawable.ic_close, act.getString(R.string.downloads_cancel), null, null,
                            new Runnable() {
                                @Override
                                public void run() {
                                    act.startService(new android.content.Intent(act, DownloadService.class)
                                            .setAction(DownloadService.ACTION_CANCEL));
                                    Ui.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!isDestroyed()) reload();
                                        }
                                    }, 400);
                                }
                            }));
                }
                if (!ready.isEmpty()) {
                    rows.add(Rows.action(R.drawable.ic_shuffle, act.getString(R.string.shuffle),
                            act.getResources().getQuantityString(R.plurals.songs, ready.size(), ready.size()) + " • " + Ui.size(bytes), null,
                            new Runnable() {
                                @Override
                                public void run() {
                                    act.playList(ready, (int) (Math.random() * ready.size()), true);
                                }
                            }));
                    rows.add(Rows.header(act.getString(R.string.downloads), null));
                    for (int i = 0; i < ready.size(); i++) rows.add(Rows.song(ready.get(i), ready, i));
                }
                setRows(rows);
            }
        });
    }

    @Override
    public void onRow(Rows.Row row) {
        if (row.tag instanceof Library.Download) {
            onRowMenu(row);
            return;
        }
        super.onRow(row);
    }

    @Override
    public void onRowMenu(final Rows.Row row) {
        if (row.tag instanceof Library.Download) {
            final Library.Download d = (Library.Download) row.tag;
            Ui.dialog(act).setTitle(d.song.title)
                    .setItems(new CharSequence[]{act.getString(R.string.delete)}, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Library.get(act).removeDownload(d.song.videoId);
                        }
                    }).show();
            return;
        }
        super.onRowMenu(row);
    }

    @Override
    public void onLibraryChanged() {
        if (!isDestroyed()) reload();
    }
}
