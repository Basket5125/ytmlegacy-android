package com.ytmlegacy.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Page;

import java.util.ArrayList;
import java.util.List;

/** A screen showing a list of rows, loaded in the background. */
public abstract class ListScreen extends Screen implements Rows.Callbacks {

    protected ListView list;
    protected Rows.Adapter adapter;
    private final List<Task<?>> tasks = new ArrayList<Task<?>>();
    private boolean loaded;
    private boolean loadingMore;

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        list = new ListView(act);
        list.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        list.setDivider(null);
        list.setCacheColorHint(0);
        list.setSelector(Ui.attrResource(act, R.attr.ytmItemBackground));
        list.setDrawSelectorOnTop(false);
        list.setFastScrollEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(act, 8));
        View header = header(inflater);
        if (header != null) list.addHeaderView(header, null, false);
        adapter = new Rows.Adapter(act, this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = parent.getItemAtPosition(position);
                if (o instanceof Rows.Row) onRow((Rows.Row) o);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = parent.getItemAtPosition(position);
                if (!(o instanceof Rows.Row)) return false;
                Rows.Row r = (Rows.Row) o;
                if (r.type != Rows.SONG && r.type != Rows.ENTRY && !(r.type == Rows.ACTION && r.tag != null)) {
                    return false;
                }
                onRowMenu(r);
                return true;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) { }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (loaded && !loadingMore && total > 0 && first + visible >= total - 5 && hasMore()) {
                    loadingMore = true;
                    loadMore();
                }
            }
        });
        showLoading();
        return list;
    }

    /** An optional header view above the rows (album art etc.). */
    protected View header(LayoutInflater inflater) {
        return null;
    }

    @Override
    public void onShown() {
        if (!loaded && !busy) reload();
        else adapter.setPlaying(act.playingId());
    }

    private boolean busy;

    public void reload() {
        busy = true;
        if (adapter.getCount() == 0) showLoading();
        load();
    }

    /** Starts loading; call {@link #setRows} or {@link #showError} when done. */
    protected abstract void load();

    protected boolean hasMore() {
        return false;
    }

    /** Loads the next page; call {@link #moreLoaded} when done. */
    protected void loadMore() {
        loadingMore = false;
    }

    protected void moreLoaded(List<Rows.Row> more) {
        loadingMore = false;
        if (more == null || more.isEmpty() || isDestroyed()) return;
        List<Rows.Row> rows = new ArrayList<Rows.Row>(adapter.rows());
        rows.addAll(more);
        adapter.setRows(rows);
    }

    protected void setRows(List<Rows.Row> rows) {
        loaded = true;
        busy = false;
        if (rows.isEmpty()) rows.add(Rows.message(act.getString(R.string.empty), false, null, null));
        adapter.setRows(rows);
        adapter.setPlaying(act.playingId());
    }

    protected void showLoading() {
        List<Rows.Row> rows = new ArrayList<Rows.Row>();
        rows.add(Rows.message(act.getString(R.string.loading), true, null, null));
        adapter.setRows(rows);
    }

    protected void showError(Exception e) {
        busy = false;
        loaded = false;
        List<Rows.Row> rows = new ArrayList<Rows.Row>();
        boolean online = act.isOnline();
        rows.add(Rows.message(act.getString(online ? R.string.error_load : R.string.error_offline), false,
                act.getString(R.string.retry), new Runnable() {
                    @Override
                    public void run() {
                        reload();
                    }
                }));
        if (!online) {
            rows.add(Rows.action(R.drawable.ic_download_done, act.getString(R.string.open_downloads), null, null,
                    new Runnable() {
                        @Override
                        public void run() {
                            act.push(new DownloadsScreen());
                        }
                    }));
        }
        adapter.setRows(rows);
    }

    protected <T> Task<T> run(Task<T> task) {
        tasks.add(task);
        return task.start();
    }

    @Override
    public void onDestroy() {
        for (Task<?> t : tasks) t.cancel();
        super.onDestroy();
    }

    @Override
    public void onReselected() {
        if (list != null) list.setSelection(0);
    }

    @Override
    public void onPlayerChanged() {
        if (adapter != null) adapter.setPlaying(act.playingId());
    }

    @Override
    public void onLibraryChanged() {
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ default actions

    @Override
    public void onRow(Rows.Row row) {
        switch (row.type) {
            case Rows.SONG:
                if (row.list != null) act.playList(row.list, row.index, false);
                else act.playRadio(row.song());
                break;
            case Rows.ENTRY:
                act.open(row.item);
                break;
            case Rows.ACTION:
                if (row.action != null) row.action.run();
                break;
            default:
                break;
        }
    }

    @Override
    public void onRowMenu(Rows.Row row) {
        if (row.song() != null) act.actions().songMenu(row.song(), -1, -1);
        else if (row.item != null) act.actions().itemMenu(row.item);
    }

    @Override
    public void onCard(Item item) {
        act.open(item);
    }

    @Override
    public void onCardMenu(Item item) {
        if (item.song != null) act.actions().songMenu(item.song, -1, -1);
        else act.actions().itemMenu(item);
    }

    @Override
    public void onMore(Page.Section section) {
        act.push(new BrowseScreen(section.moreBrowseId, section.moreParams, section.title));
    }
}
