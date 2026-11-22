package com.ytmlegacy.android.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** Listening statistics kept on the device: this week, this month and all time. */
public final class StatsScreen extends ListScreen {

    private static final long DAY = 24L * 3600 * 1000;
    private static final int[] PERIODS = {R.string.stats_week, R.string.stats_month, R.string.stats_all};

    private int period;
    private final List<TextView> chips = new ArrayList<TextView>();
    private LinearLayout numbers;
    private HourChart chart;
    private TextView peak;

    @Override
    public String title() {
        return act.getString(R.string.stats);
    }

    @Override
    protected View header(LayoutInflater inflater) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(act, 12);
        box.setPadding(p, p, p, 0);
        LinearLayout row = new LinearLayout(act);
        for (int i = 0; i < PERIODS.length; i++) {
            final int index = i;
            TextView chip = new TextView(act);
            chip.setText(PERIODS[i]);
            chip.setTextSize(13);
            chip.setBackgroundResource(Ui.attrResource(act, R.attr.ytmChip));
            chip.setPadding(Ui.dp(act, 16), Ui.dp(act, 6), Ui.dp(act, 16), Ui.dp(act, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 8);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (period == index) return;
                    period = index;
                    bindChips();
                    reload();
                }
            });
            chips.add(chip);
            row.addView(chip, lp);
        }
        box.addView(row);
        numbers = new LinearLayout(act);
        numbers.setOrientation(LinearLayout.VERTICAL);
        box.addView(numbers);
        chart = new HourChart(act);
        box.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 90)));
        peak = new TextView(act);
        peak.setTextSize(13);
        peak.setTextColor(Ui.attrColor(act, R.attr.ytmText2));
        peak.setPadding(0, Ui.dp(act, 4), 0, 0);
        box.addView(peak);
        bindChips();
        return box;
    }

    private void bindChips() {
        for (int i = 0; i < chips.size(); i++) {
            TextView c = chips.get(i);
            c.setSelected(i == period);
            c.setTextColor(i == period ? 0xFFFFFFFF : Ui.attrColor(act, R.attr.ytmText));
        }
    }

    @Override
    protected void load() {
        if (!Prefs.get(act).stats()) {
            List<Rows.Row> rows = new ArrayList<Rows.Row>();
            rows.add(Rows.message(act.getString(R.string.stats_off), false, null, null));
            numbers.removeAllViews();
            chart.setVisibility(View.GONE);
            peak.setText("");
            setRows(rows);
            return;
        }
        final long since = period == 0 ? System.currentTimeMillis() - 7 * DAY
                : period == 1 ? System.currentTimeMillis() - 30 * DAY : 0;
        run(new Task<Library.Stats>() {
            @Override
            protected Library.Stats work() {
                return Library.get(act).stats(since);
            }

            @Override
            protected void done(Library.Stats st) {
                show(st);
            }
        });
    }

    private void show(Library.Stats st) {
        numbers.removeAllViews();
        LinearLayout top = new LinearLayout(act);
        top.addView(tile(listened(st.seconds), act.getString(R.string.stats_time)), weight());
        top.addView(tile(String.valueOf(st.plays), act.getString(R.string.stats_plays)), weight());
        LinearLayout bottom = new LinearLayout(act);
        bottom.addView(tile(String.valueOf(st.songs), act.getString(R.string.stats_songs)), weight());
        bottom.addView(tile(String.valueOf(st.artists), act.getString(R.string.stats_artists)), weight());
        bottom.addView(tile(String.valueOf(st.days), act.getString(R.string.stats_days)), weight());
        numbers.addView(top);
        numbers.addView(bottom);
        chart.setVisibility(st.seconds > 0 ? View.VISIBLE : View.GONE);
        chart.setData(st.byHour);
        int best = -1;
        for (int h = 0; h < 24; h++) if (st.byHour[h] > 0 && (best < 0 || st.byHour[h] > st.byHour[best])) best = h;
        peak.setText(best >= 0 ? act.getString(R.string.stats_peak, String.format(java.util.Locale.US,
                "%02d:00–%02d:00", best, (best + 1) % 24)) : "");

        List<Rows.Row> rows = new ArrayList<Rows.Row>();
        if (st.seconds == 0) {
            rows.add(Rows.message(act.getString(R.string.stats_empty), false, null, null));
            setRows(rows);
            return;
        }
        if (!st.topArtists.isEmpty()) {
            rows.add(Rows.header(act.getString(R.string.stats_top_artists), null));
            for (final String[] a : st.topArtists) {
                Rows.Row r = Rows.action(R.drawable.ic_person, a[0], minutes(Long.parseLong(a[2])), null,
                        a[1].length() == 0 ? null : new Runnable() {
                            @Override
                            public void run() {
                                act.push(new BrowseScreen(a[1], "", a[0]));
                            }
                        });
                rows.add(r);
            }
        }
        if (!st.topSongs.isEmpty()) {
            rows.add(Rows.header(act.getString(R.string.stats_top_songs), null));
            List<Song> songs = new ArrayList<Song>();
            for (Object[] o : st.topSongs) songs.add((Song) o[0]);
            for (int i = 0; i < st.topSongs.size(); i++) {
                Object[] o = st.topSongs.get(i);
                Item item = Item.ofSong((Song) o[0]);
                int plays = (Integer) o[1];
                item.subtitle = ((Song) o[0]).artist + " • "
                        + act.getResources().getQuantityString(R.plurals.plays, plays, plays);
                rows.add(Rows.song(item, songs, i));
            }
        }
        if (!st.topAlbums.isEmpty()) {
            rows.add(Rows.header(act.getString(R.string.stats_top_albums), null));
            for (final String[] a : st.topAlbums) {
                rows.add(Rows.action(R.drawable.ic_album, a[0], a[2] + " • " + minutes(Long.parseLong(a[3])), a[4],
                        a[1].length() == 0 ? null : new Runnable() {
                            @Override
                            public void run() {
                                act.push(new BrowseScreen(a[1], "", a[0]));
                            }
                        }));
            }
        }
        setRows(rows);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Ui.dp(act, 4);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private View tile(String value, String label) {
        LinearLayout t = new LinearLayout(act);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(Ui.attrColor(act, R.attr.ytmCard));
        int p = Ui.dp(act, 10);
        t.setPadding(p, p, p, p);
        TextView v = new TextView(act);
        v.setText(value);
        v.setTextSize(22);
        v.setTextColor(0xFF33B5E5);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setSingleLine(true);
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextSize(12);
        l.setTextColor(Ui.attrColor(act, R.attr.ytmText2));
        l.setSingleLine(true);
        t.addView(v);
        t.addView(l);
        return t;
    }

    /** "12 h 5 min" or "45 min". */
    private String listened(long seconds) {
        long min = seconds / 60;
        if (min == 0) return seconds + " s";
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    private String minutes(long seconds) {
        return listened(seconds);
    }

    @Override
    public void onShown() {
        reload();
    }

    @Override
    public void onRow(Rows.Row row) {
        if (row.type == Rows.ACTION && row.action == null) return;
        super.onRow(row);
    }

    /** 24 bars, one per hour of the day. Drawn once per data change. */
    private static final class HourChart extends View {
        private long[] data = new long[24];
        private final Paint bar = new Paint();
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        HourChart(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            bar.setColor(0xFF33B5E5);
            label.setColor(Ui.attrColor(c, R.attr.ytmText2));
            label.setTextSize(10 * c.getResources().getDisplayMetrics().scaledDensity);
        }

        void setData(long[] d) {
            data = d;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long max = 1;
            for (long v : data) max = Math.max(max, v);
            float labelH = 14 * density;
            float top = 8 * density;
            float h = getHeight() - labelH - top;
            float slot = getWidth() / 24f;
            float w = Math.max(1, slot * 0.7f);
            for (int i = 0; i < 24; i++) {
                float bh = data[i] == 0 ? 0 : Math.max(2 * density, h * data[i] / max);
                float x = i * slot + (slot - w) / 2;
                bar.setAlpha(data[i] == 0 ? 60 : 255);
                canvas.drawRect(x, top + h - Math.max(bh, density), x + w, top + h, bar);
            }
            for (int i = 0; i < 24; i += 6) {
                canvas.drawText(String.valueOf(i), i * slot + (slot - w) / 2, getHeight() - 2 * density, label);
            }
        }
    }
}
