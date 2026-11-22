package com.ytmlegacy.android.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Page;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** The rows every list of the app is built from, and their adapter. */
public final class Rows {

    public static final int HEADER = 0;
    public static final int SONG = 1;
    public static final int ENTRY = 2;
    public static final int CAROUSEL = 3;
    public static final int TILES = 4;
    public static final int ACTION = 5;
    public static final int MESSAGE = 6;
    static final int TYPES = 7;

    private Rows() { }

    public static final class Row {
        public int type;
        public String title = "";
        public String subtitle = "";
        public Item item;
        public Item item2;
        public List<Item> items;
        public Page.Section section;
        /** For songs of a list: the whole list and this song's index, so a tap plays the list. */
        public List<Song> list;
        public int index;
        public int icon;
        public String thumb = "";
        public Runnable action;
        public boolean progress;
        public String button = "";
        public Object tag;
        /** A carousel's cards, built once and kept (inflating them while scrolling made lists stutter). */
        View view;

        public Song song() {
            return item == null ? null : item.song;
        }
    }

    /** What a list does when its rows are used. */
    public interface Callbacks {
        void onRow(Row row);

        void onRowMenu(Row row);

        void onCard(Item item);

        void onCardMenu(Item item);

        void onMore(Page.Section section);
    }

    // ------------------------------------------------------------------ building

    public static Row header(String title, Page.Section more) {
        Row r = new Row();
        r.type = HEADER;
        r.title = title;
        r.section = more;
        return r;
    }

    public static Row song(Item item, List<Song> list, int index) {
        Row r = new Row();
        r.type = SONG;
        r.item = item;
        r.list = list;
        r.index = index;
        return r;
    }

    public static Row song(Song s, List<Song> list, int index) {
        return song(Item.ofSong(s), list, index);
    }

    public static Row entry(Item item) {
        Row r = new Row();
        r.type = ENTRY;
        r.item = item;
        return r;
    }

    public static Row action(int icon, String title, String subtitle, String thumb, Runnable action) {
        Row r = new Row();
        r.type = ACTION;
        r.icon = icon;
        r.title = title;
        r.subtitle = subtitle == null ? "" : subtitle;
        r.thumb = thumb == null ? "" : thumb;
        r.action = action;
        return r;
    }

    public static Row message(String text, boolean progress, String button, Runnable action) {
        Row r = new Row();
        r.type = MESSAGE;
        r.title = text;
        r.progress = progress;
        r.button = button == null ? "" : button;
        r.action = action;
        return r;
    }

    /**
     * Rows of a page. {@code songsAsList}: tapping a song plays its whole section (album,
     * playlist); otherwise a song starts its radio (search, home).
     */
    public static List<Row> fromPage(Page page, boolean songsAsList) {
        List<Row> rows = new ArrayList<Row>();
        for (Page.Section s : page.sections) addSection(rows, s, songsAsList);
        return rows;
    }

    public static void addSection(List<Row> rows, Page.Section s, boolean songsAsList) {
        if (s.title.length() > 0) rows.add(header(s.title, s.moreBrowseId.length() > 0 ? s : null));
        if (s.style == Page.Section.CAROUSEL) {
            Row r = new Row();
            r.type = CAROUSEL;
            r.items = s.items;
            r.section = s;
            rows.add(r);
            return;
        }
        if (s.style == Page.Section.TILES) {
            for (int i = 0; i < s.items.size(); i += 2) {
                Row r = new Row();
                r.type = TILES;
                r.item = s.items.get(i);
                r.item2 = i + 1 < s.items.size() ? s.items.get(i + 1) : null;
                rows.add(r);
            }
            return;
        }
        List<Song> songs = new ArrayList<Song>();
        for (Item i : s.items) if (i.song != null) songs.add(i.song);
        int index = 0;
        for (Item i : s.items) {
            if (i.song != null) rows.add(song(i, songsAsList ? songs : null, index++));
            else rows.add(entry(i));
        }
    }

    // ------------------------------------------------------------------ adapter

    public static final class Adapter extends BaseAdapter {
        private final Context context;
        private final LayoutInflater inflater;
        private final Callbacks callbacks;
        private List<Row> rows = new ArrayList<Row>();
        private final int iconColor;
        private final int placeholder;
        private final boolean dark;
        private final int textColor;
        private final android.os.Handler handler = new android.os.Handler();
        private ViewGroup lastParent;
        private String playingId = "";
        /** Rows of the playing song are drawn in blue; set by the screen. */

        public Adapter(Context context, Callbacks callbacks) {
            this.context = context;
            this.inflater = LayoutInflater.from(context);
            this.callbacks = callbacks;
            iconColor = Ui.iconColor(context);
            placeholder = Ui.attrResource(context, R.attr.ytmPlaceholder);
            dark = MainActivity.isDarkTheme(context);
            textColor = Ui.attrColor(context, R.attr.ytmText);
        }

        public void setRows(List<Row> rows) {
            this.rows = rows;
            notifyDataSetChanged();
            handler.removeCallbacks(prebuild);
            handler.post(prebuild);
        }

        /** Builds the carousels not yet on screen, one per frame, before the user scrolls to them. */
        private final Runnable prebuild = new Runnable() {
            @Override
            public void run() {
                if (lastParent == null) {
                    handler.postDelayed(this, 50);
                    return;
                }
                for (Row r : rows) {
                    if (r.type == CAROUSEL && r.view == null) {
                        buildCarousel(r, lastParent);
                        handler.postDelayed(this, 16);
                        return;
                    }
                }
            }
        };

        public List<Row> rows() {
            return rows;
        }

        public void setPlaying(String videoId) {
            String id = videoId == null ? "" : videoId;
            if (!id.equals(playingId)) {
                playingId = id;
                notifyDataSetChanged();
            }
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return TYPES;
        }

        @Override
        public int getItemViewType(int position) {
            int t = rows.get(position).type;
            // Carousels keep their own view: the list must not hand it to another row.
            return t == CAROUSEL ? android.widget.AdapterView.ITEM_VIEW_TYPE_IGNORE : t;
        }

        @Override
        public boolean isEnabled(int position) {
            int t = rows.get(position).type;
            return t == SONG || t == ENTRY || t == ACTION;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            final Row row = rows.get(position);
            lastParent = parent;
            switch (row.type) {
                case HEADER:
                    return header(row, convert, parent);
                case SONG:
                case ENTRY:
                    return song(row, convert, parent);
                case CAROUSEL:
                    return carousel(row, convert, parent);
                case TILES:
                    return tiles(row, convert, parent);
                case ACTION:
                    return action(row, convert, parent);
                default:
                    return message(row, convert, parent);
            }
        }

        private View header(final Row row, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.row_header, parent, false);
            ((TextView) v.findViewById(R.id.header_title)).setText(row.title.toUpperCase(java.util.Locale.getDefault()));
            TextView more = (TextView) v.findViewById(R.id.header_more);
            if (row.section != null) {
                more.setVisibility(View.VISIBLE);
                more.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        callbacks.onMore(row.section);
                    }
                });
            } else {
                more.setVisibility(View.GONE);
                more.setOnClickListener(null);
            }
            return v;
        }

        private View song(final Row row, View v, ViewGroup parent) {
            if (v == null) {
                v = inflater.inflate(R.layout.row_song, parent, false);
                Ui.tint((ImageView) v.findViewById(R.id.row_more), iconColor);
                Ui.tint((ImageView) v.findViewById(R.id.row_badge), 0xFF33B5E5);
            }
            Item item = row.item;
            TextView title = (TextView) v.findViewById(R.id.row_title);
            title.setText(item.title);
            boolean playing = item.song != null && item.song.videoId.equals(playingId);
            title.setTextColor(playing ? 0xFF33B5E5 : textColor);
            String sub = item.subtitle;
            if (row.type == ENTRY && sub.length() == 0) sub = typeName(item.type);
            ((TextView) v.findViewById(R.id.row_subtitle)).setText(sub);
            TextView duration = (TextView) v.findViewById(R.id.row_duration);
            if (item.song != null && item.song.durationSec > 0) {
                duration.setVisibility(View.VISIBLE);
                duration.setText(Song.formatTime(item.song.durationSec));
            } else {
                duration.setVisibility(View.GONE);
            }
            boolean downloaded = item.song != null && Library.get(context).isDownloaded(item.song.videoId);
            v.findViewById(R.id.row_badge).setVisibility(downloaded ? View.VISIBLE : View.GONE);
            ImageView thumb = (ImageView) v.findViewById(R.id.row_thumb);
            Images.get(context).load(thumb, item.thumb, Images.get(context).px(48, 400), placeholder);
            View more = v.findViewById(R.id.row_more);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    callbacks.onRowMenu(row);
                }
            });
            return v;
        }

        private View carousel(final Row row, View v, ViewGroup parent) {
            if (row.view == null) buildCarousel(row, parent);
            return row.view;
        }

        private void buildCarousel(final Row row, ViewGroup parent) {
            View v = inflater.inflate(R.layout.row_carousel, parent, false);
            v.setHorizontalFadingEdgeEnabled(false); // fading edges cost a layer per frame
            LinearLayout holder = (LinearLayout) v.findViewById(R.id.carousel_items);
            int px = Images.get(context).px(126, 720);
            for (final Item item : row.items) {
                View card = inflater.inflate(R.layout.card, holder, false);
                ((TextView) card.findViewById(R.id.card_title)).setText(item.title);
                ((TextView) card.findViewById(R.id.card_subtitle)).setText(
                        item.subtitle.length() > 0 ? item.subtitle : typeName(item.type));
                Images.get(context).load((ImageView) card.findViewById(R.id.card_art), item.thumb, px, placeholder);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        callbacks.onCard(item);
                    }
                });
                card.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View view) {
                        callbacks.onCardMenu(item);
                        return true;
                    }
                });
                holder.addView(card);
            }
            row.view = v;
        }

        private View tiles(final Row row, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.row_tiles, parent, false);
            bindTile((TextView) v.findViewById(R.id.tile_1), row.item);
            bindTile((TextView) v.findViewById(R.id.tile_2), row.item2);
            return v;
        }

        @SuppressWarnings("deprecation")
        private void bindTile(TextView t, final Item item) {
            if (item == null) {
                t.setVisibility(View.INVISIBLE);
                t.setOnClickListener(null);
                return;
            }
            t.setVisibility(View.VISIBLE);
            t.setText(item.title);
            int stripe = item.color != 0 ? (item.color | 0xFF000000) : 0xFF33B5E5;
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{stripe, dark ? 0xFF22272C : 0xFFFFFFFF, dark ? 0xFF22272C : 0xFFFFFFFF});
            bg.setCornerRadius(Ui.dp(context, 3));
            GradientDrawable pressed = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{stripe, 0x6633B5E5, 0x6633B5E5});
            pressed.setCornerRadius(Ui.dp(context, 3));
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            states.addState(new int[0], bg);
            t.setBackgroundDrawable(states);
            t.setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 6), 0);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    callbacks.onCard(item);
                }
            });
        }

        private View action(final Row row, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.row_action, parent, false);
            ((TextView) v.findViewById(R.id.action_title)).setText(row.title);
            TextView sub = (TextView) v.findViewById(R.id.action_subtitle);
            sub.setText(row.subtitle);
            sub.setVisibility(row.subtitle.length() > 0 ? View.VISIBLE : View.GONE);
            ImageView icon = (ImageView) v.findViewById(R.id.action_icon);
            if (row.thumb.length() > 0) {
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                icon.clearColorFilter();
                Images.get(context).load(icon, row.thumb, Images.get(context).px(40, 300), placeholder);
            } else {
                icon.setTag(R.id.tag_image_url, "");
                icon.setScaleType(ImageView.ScaleType.CENTER);
                icon.setImageResource(row.icon);
                Ui.tint(icon, 0xFF33B5E5);
            }
            return v;
        }

        private View message(final Row row, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.row_message, parent, false);
            ((TextView) v.findViewById(R.id.message_text)).setText(row.title);
            ((ProgressBar) v.findViewById(R.id.message_progress)).setVisibility(row.progress ? View.VISIBLE : View.GONE);
            Button b = (Button) v.findViewById(R.id.message_button);
            if (row.button.length() > 0 && row.action != null) {
                b.setVisibility(View.VISIBLE);
                b.setText(row.button);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        row.action.run();
                    }
                });
            } else {
                b.setVisibility(View.GONE);
            }
            return v;
        }

        private String typeName(int type) {
            switch (type) {
                case Item.ALBUM:
                    return context.getString(R.string.album);
                case Item.PLAYLIST:
                case Item.MIX:
                    return context.getString(R.string.playlist);
                case Item.ARTIST:
                    return context.getString(R.string.by_artist);
                case Item.VIDEO:
                    return context.getString(R.string.video);
                case Item.SONG:
                    return context.getString(R.string.song);
                default:
                    return "";
            }
        }
    }
}
