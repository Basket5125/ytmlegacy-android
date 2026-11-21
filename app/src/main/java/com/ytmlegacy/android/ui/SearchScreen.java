package com.ytmlegacy.android.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.yt.InnerTube;
import com.ytmlegacy.android.yt.Page;

import java.util.ArrayList;
import java.util.List;

/** Search with suggestions, recent searches and result filters. */
public final class SearchScreen extends ListScreen {

    private static final int[] FILTER_NAMES = {R.string.filter_all, R.string.filter_songs, R.string.filter_videos,
            R.string.filter_albums, R.string.filter_artists, R.string.filter_playlists};
    private static final String[] FILTERS = {"", InnerTube.FILTER_SONGS, InnerTube.FILTER_VIDEOS,
            InnerTube.FILTER_ALBUMS, InnerTube.FILTER_ARTISTS, InnerTube.FILTER_PLAYLISTS};

    private String query = "";
    private int filter;
    private boolean results;
    private Page.Section main;
    private HorizontalScrollView chips;
    private final List<TextView> chipViews = new ArrayList<TextView>();
    private Task<List<String>> suggestTask;
    private final Runnable suggestSoon = new Runnable() {
        @Override
        public void run() {
            suggest();
        }
    };

    @Override
    public String title() {
        return query.length() > 0 ? query : act.getString(R.string.search);
    }

    @Override
    public boolean isSearch() {
        return true;
    }

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        chips = new HorizontalScrollView(act);
        chips.setHorizontalScrollBarEnabled(false);
        chips.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(act);
        int p = Ui.dp(act, 6);
        row.setPadding(p, p, p, p);
        for (int i = 0; i < FILTERS.length; i++) {
            final int index = i;
            TextView chip = new TextView(act);
            chip.setText(FILTER_NAMES[i]);
            chip.setTextSize(13);
            chip.setTextColor(Ui.attrColor(act, R.attr.ytmText));
            chip.setBackgroundResource(Ui.attrResource(act, R.attr.ytmChip));
            chip.setPadding(Ui.dp(act, 14), Ui.dp(act, 6), Ui.dp(act, 14), Ui.dp(act, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (filter == index) return;
                    filter = index;
                    bindChips();
                    search();
                }
            });
            chipViews.add(chip);
            row.addView(chip);
        }
        chips.addView(row);
        box.addView(chips);
        View listView = super.onCreateView(inflater, parent);
        box.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bindChips();
        return box;
    }

    private void bindChips() {
        for (int i = 0; i < chipViews.size(); i++) {
            TextView c = chipViews.get(i);
            c.setSelected(i == filter);
            c.setTextColor(i == filter ? 0xFFFFFFFF : Ui.attrColor(act, R.attr.ytmText));
        }
    }

    @Override
    public void onShown() {
        EditText field = act.searchField();
        field.removeTextChangedListener(watcher);
        field.setText(query);
        field.setSelection(field.getText().length());
        field.addTextChangedListener(watcher);
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    submit(v.getText().toString());
                    return true;
                }
                return false;
            }
        });
        if (!results) {
            act.showKeyboard(field);
            suggest();
        } else {
            adapter.setPlaying(act.playingId());
        }
    }

    @Override
    public void onHidden() {
        act.searchField().removeTextChangedListener(watcher);
        act.removeCallbacks(suggestSoon);
    }

    private final TextWatcher watcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) { }

        @Override
        public void afterTextChanged(Editable s) {
            if (s.toString().equals(query) && results) return;
            results = false;
            chips.setVisibility(View.GONE);
            act.removeCallbacks(suggestSoon);
            act.postDelayed(suggestSoon, 250);
        }
    };

    @Override
    protected void load() {
        if (results) search();
        else suggest();
    }

    private void suggest() {
        if (isDestroyed() || results) return;
        final String input = act.searchField().getText().toString().trim();
        final List<Rows.Row> rows = new ArrayList<Rows.Row>();
        if (Prefs.get(act).searchHistory()) {
            for (final String q : Library.get(act).searches(input)) {
                Rows.Row r = Rows.action(R.drawable.ic_history, q, null, null, new Runnable() {
                    @Override
                    public void run() {
                        submit(q);
                    }
                });
                r.tag = q;
                rows.add(r);
            }
        }
        adapter.setRows(new ArrayList<Rows.Row>(rows));
        if (input.length() == 0) return;
        if (suggestTask != null) suggestTask.cancel();
        suggestTask = run(new Task<List<String>>() {
            @Override
            protected List<String> work() throws Exception {
                return InnerTube.get(act).suggestions(input);
            }

            @Override
            protected void done(List<String> list) {
                if (results || isDestroyed()) return;
                List<Rows.Row> all = new ArrayList<Rows.Row>(rows);
                for (final String q : list) {
                    boolean dup = false;
                    for (Rows.Row r : rows) if (q.equals(r.title)) dup = true;
                    if (dup) continue;
                    all.add(Rows.action(R.drawable.ic_search, q, null, null, new Runnable() {
                        @Override
                        public void run() {
                            submit(q);
                        }
                    }));
                }
                adapter.setRows(all);
            }
        });
    }

    private void submit(String q) {
        q = q.trim();
        if (q.length() == 0) return;
        query = q;
        results = true;
        EditText field = act.searchField();
        field.removeTextChangedListener(watcher);
        field.setText(q);
        field.setSelection(q.length());
        field.addTextChangedListener(watcher);
        act.hideKeyboard(field);
        if (Prefs.get(act).searchHistory()) Library.get(act).addSearch(q);
        chips.setVisibility(View.VISIBLE);
        search();
    }

    private void search() {
        final String q = query;
        final String f = FILTERS[filter];
        main = null;
        showLoading();
        run(new Task<Page>() {
            @Override
            protected Page work() throws Exception {
                return InnerTube.get(act).search(q, f);
            }

            @Override
            protected void done(Page page) {
                if (!q.equals(query) || !f.equals(FILTERS[filter])) return;
                main = page.mainList();
                List<Rows.Row> rows = Rows.fromPage(page, false);
                if (rows.isEmpty()) rows.add(Rows.message(act.getString(R.string.no_results), false, null, null));
                setRows(rows);
                list.setSelection(0);
            }

            @Override
            protected void failed(Exception e) {
                showError(e);
            }
        });
    }

    @Override
    protected boolean hasMore() {
        return results && filter > 0 && main != null && main.continuation.length() > 0;
    }

    @Override
    protected void loadMore() {
        final Page.Section from = main;
        final String token = from.continuation;
        run(new Task<Page.Section>() {
            @Override
            protected Page.Section work() throws Exception {
                return InnerTube.get(act).browseContinuation(token, from.legacyContinuation, true, false);
            }

            @Override
            protected void done(Page.Section more) {
                if (from != main) return;
                from.continuation = more.continuation;
                from.legacyContinuation = more.legacyContinuation;
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                Rows.addSection(rows, more, false);
                moreLoaded(rows);
            }

            @Override
            protected void failed(Exception e) {
                moreLoaded(null);
            }
        });
    }

    @Override
    public void onRowMenu(Rows.Row row) {
        if (row.type == Rows.ACTION && row.tag instanceof String) {
            final String q = (String) row.tag;
            Ui.dialog(act).setTitle(q).setItems(new CharSequence[]{act.getString(R.string.delete)},
                    new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            Library.get(act).removeSearch(q);
                            suggest();
                        }
                    }).show();
            return;
        }
        super.onRowMenu(row);
    }
}
