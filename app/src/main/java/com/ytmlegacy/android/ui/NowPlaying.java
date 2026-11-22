package com.ytmlegacy.android.ui;

import android.content.Context;
import android.media.AudioManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.player.PlayerService;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** The Now Playing screen: cover, title, progress, transport, volume and the queue. */
final class NowPlaying {

    private static final int ACTIVE = 0xFF33B5E5;
    private static final int IDLE = 0xFFE6ECF0;
    private static final int DIM = 0x80E6ECF0;

    private final MainActivity act;
    private final DragDownLayout container;
    private final ImageView art;
    private final TextView title;
    private final TextView artist;
    private final TextView context;
    private final TextView time;
    private final TextView duration;
    private final SeekBar seek;
    private final SeekBar volume;
    private final ImageButton toggle;
    private final ProgressBar loading;
    private final ImageButton shuffle;
    private final ImageButton repeat;
    private final ImageButton like;
    private final ImageButton download;
    private final RevealLayout queuePanel;
    private final DragSortListView queueList;
    private final TextView queueTitle;
    private final View top;
    private final View middle;
    private final AudioManager audio;
    private final QueueAdapter queueAdapter = new QueueAdapter();
    private boolean seeking;
    private String shownId = "";

    NowPlaying(final MainActivity act, DragDownLayout container) {
        this.act = act;
        this.container = container;
        audio = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        art = (ImageView) container.findViewById(R.id.np_art);
        title = (TextView) container.findViewById(R.id.np_title);
        artist = (TextView) container.findViewById(R.id.np_artist);
        context = (TextView) container.findViewById(R.id.np_context);
        time = (TextView) container.findViewById(R.id.np_time);
        duration = (TextView) container.findViewById(R.id.np_duration);
        seek = (SeekBar) container.findViewById(R.id.np_seek);
        volume = (SeekBar) container.findViewById(R.id.np_volume);
        toggle = (ImageButton) container.findViewById(R.id.np_toggle);
        loading = (ProgressBar) container.findViewById(R.id.np_loading);
        shuffle = (ImageButton) container.findViewById(R.id.np_shuffle);
        repeat = (ImageButton) container.findViewById(R.id.np_repeat);
        like = (ImageButton) container.findViewById(R.id.np_like);
        download = (ImageButton) container.findViewById(R.id.np_download);
        queuePanel = (RevealLayout) container.findViewById(R.id.np_queue_panel);
        if (MainActivity.isOledTheme(act)) container.findViewById(R.id.np_queue_body).setBackgroundColor(0xFF000000);
        queueList = (DragSortListView) container.findViewById(R.id.np_queue_list);
        queueTitle = (TextView) container.findViewById(R.id.np_queue_title);
        top = container.findViewById(R.id.np_top);
        middle = container.findViewById(R.id.np_middle);
        title.setSelected(true); // marquee

        int[] tinted = {R.id.np_close, R.id.np_queue, R.id.np_prev, R.id.np_next, R.id.np_lyrics, R.id.np_timer,
                R.id.np_more, R.id.np_vol_down, R.id.np_vol_up};
        for (int id : tinted) Ui.tint((ImageView) container.findViewById(id), IDLE);
        Ui.tint(toggle, 0xFFFFFFFF);

        container.setDragArea(new DragDownLayout.DragArea() {
            @Override
            public boolean canDragFrom(float x, float y) {
                if (DragDownLayout.contains(top, NowPlaying.this.container, x, y)) return true;
                return queuePanel.getVisibility() != View.VISIBLE
                        && DragDownLayout.contains(middle, NowPlaying.this.container, x, y);
            }
        });
        click(R.id.np_close, new Runnable() {
            public void run() {
                act.closeNowPlaying();
            }
        });
        click(R.id.np_queue, new Runnable() {
            public void run() {
                toggleQueue();
            }
        });
        click(R.id.np_toggle, new Runnable() {
            public void run() {
                PlayerService p = act.player();
                if (p != null) p.toggle();
            }
        });
        click(R.id.np_prev, new Runnable() {
            public void run() {
                PlayerService p = act.player();
                if (p != null) p.previous();
            }
        });
        click(R.id.np_next, new Runnable() {
            public void run() {
                PlayerService p = act.player();
                if (p != null) p.next();
            }
        });
        click(R.id.np_shuffle, new Runnable() {
            public void run() {
                PlayerService p = act.player();
                if (p == null) return;
                p.setShuffle(!p.shuffle());
                Ui.toast(act, p.shuffle() ? R.string.shuffle_on : R.string.shuffle_off);
            }
        });
        click(R.id.np_repeat, new Runnable() {
            public void run() {
                PlayerService p = act.player();
                if (p == null) return;
                p.cycleRepeat();
                int m = p.repeatMode();
                Ui.toast(act, m == Prefs.REPEAT_ONE ? R.string.repeat_one : m == Prefs.REPEAT_ALL
                        ? R.string.repeat_all : R.string.repeat_off);
            }
        });
        click(R.id.np_like, new Runnable() {
            public void run() {
                Song s = current();
                if (s == null) return;
                Library lib = Library.get(act);
                lib.setLiked(s, !lib.isLiked(s.videoId));
                update();
            }
        });
        click(R.id.np_download, new Runnable() {
            public void run() {
                Song s = current();
                if (s == null) return;
                Library lib = Library.get(act);
                if (lib.isDownloaded(s.videoId)) {
                    Ui.dialog(act).setTitle(s.title).setItems(new CharSequence[]{act.getString(R.string.remove_download)},
                            new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface d, int w) {
                                    Library.get(act).removeDownload(current() == null ? "" : current().videoId);
                                }
                            }).show();
                } else {
                    List<Song> one = new ArrayList<Song>();
                    one.add(s);
                    act.actions().download(one);
                }
            }
        });
        click(R.id.np_lyrics, new Runnable() {
            public void run() {
                Song s = current();
                if (s != null) act.actions().lyrics(s);
            }
        });
        click(R.id.np_timer, new Runnable() {
            public void run() {
                act.actions().sleepTimer();
            }
        });
        click(R.id.np_more, new Runnable() {
            public void run() {
                Song s = current();
                if (s != null) act.actions().nowPlayingMenu(s);
            }
        });
        // Tapping the cover opens the fullscreen stand mode.
        art.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.openStandMode();
            }
        });
        artist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Song s = current();
                if (s == null || s.artistId.length() == 0) return;
                act.closeNowPlaying();
                act.push(new BrowseScreen(s.artistId, "", s.artist));
            }
        });
        container.findViewById(R.id.np_queue_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlayerService p = act.player();
                if (p != null) p.clearUpcoming();
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                PlayerService p = act.player();
                if (p != null) time.setText(Song.formatTime((long) progress * p.duration() / 1000 / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                PlayerService p = act.player();
                if (p != null) p.seek((long) bar.getProgress() * p.duration() / 1000);
            }
        });
        volume.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        queueList.setAdapter(queueAdapter);
        queueList.setDropListener(new DragSortListView.DropListener() {
            @Override
            public void onDrop(int from, int to) {
                PlayerService p = act.player();
                if (p == null) return;
                queueAdapter.moveLocally(from, to); // no flicker while the service catches up
                p.move(from, to);
            }
        });
        queueList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                PlayerService p = act.player();
                if (p != null && position < queueAdapter.songs.size()) p.playIndex(position);
            }
        });
        queueList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= queueAdapter.songs.size()) return false;
                act.actions().queueMenu(queueAdapter.songs.get(position), position);
                return true;
            }
        });
    }

    private void click(int id, final Runnable r) {
        container.findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
    }

    private Song current() {
        PlayerService p = act.player();
        return p == null ? null : p.current();
    }

    boolean queueShown() {
        return queuePanel.isOpen();
    }

    /** Without animation: the player screen itself is closing. */
    void hideQueueNow() {
        queuePanel.hideNow();
        Ui.tint((ImageView) container.findViewById(R.id.np_queue), IDLE);
    }

    void hideQueue() {
        if (!queueShown()) return;
        queuePanel.close(null);
        Ui.tint((ImageView) container.findViewById(R.id.np_queue), IDLE);
    }

    void toggleQueue() {
        if (queueShown()) {
            hideQueue();
            return;
        }
        queueChanged();
        queuePanel.open();
        Ui.tint((ImageView) container.findViewById(R.id.np_queue), ACTIVE);
        PlayerService p = act.player();
        if (p != null) queueList.setSelectionFromTop(Math.max(0, p.queuePosition()), Ui.dp(act, 40));
    }

    void queueChanged() {
        PlayerService p = act.player();
        if (p == null || queueList.isDragging()) return;
        queueAdapter.songs = p.queueSnapshot();
        queueAdapter.current = p.queuePosition();
        queueAdapter.notifyDataSetChanged();
        int left = Math.max(0, queueAdapter.songs.size() - queueAdapter.current - 1);
        queueTitle.setText(act.getString(R.string.up_next) + " • " + left);
    }

    /** Full refresh (song or state changed). */
    void update() {
        PlayerService p = act.player();
        Song s = p == null ? null : p.current();
        if (s == null) {
            title.setText(R.string.nothing_playing);
            artist.setText("");
            return;
        }
        if (!s.videoId.equals(shownId)) {
            shownId = s.videoId;
            title.setText(s.title);
            artist.setText(s.subtitle());
            if (s.thumb.length() > 0) {
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.clearColorFilter();
                Images.get(act).load(art, s.thumb, Images.get(act).px(340, 1200), R.drawable.art_placeholder_dark);
            } else {
                art.setTag(R.id.tag_image_url, "");
                art.setScaleType(ImageView.ScaleType.CENTER);
                art.setImageResource(R.drawable.ic_note);
                Ui.tint(art, 0x55FFFFFF);
            }
        }
        boolean wants = p.wantsToPlay();
        toggle.setImageResource(wants ? R.drawable.ic_pause : R.drawable.ic_play);
        loading.setVisibility(p.isLoading() ? View.VISIBLE : View.GONE);
        Ui.tint(shuffle, p.shuffle() ? ACTIVE : DIM);
        int r = p.repeatMode();
        repeat.setImageResource(r == Prefs.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        Ui.tint(repeat, r == Prefs.REPEAT_OFF ? DIM : ACTIVE);
        Library lib = Library.get(act);
        boolean liked = lib.isLiked(s.videoId);
        like.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        Ui.tint(like, liked ? ACTIVE : IDLE);
        boolean downloaded = lib.isDownloaded(s.videoId);
        download.setImageResource(downloaded ? R.drawable.ic_download_done : R.drawable.ic_download);
        Ui.tint(download, downloaded ? ACTIVE : IDLE);
        Ui.tint((ImageView) container.findViewById(R.id.np_timer), p.sleepRemaining() != 0 ? ACTIVE : IDLE);
        if (p.radioActive()) context.setText(p.radioLoading() ? act.getString(R.string.radio_loading)
                : act.getString(R.string.radio_on));
        else context.setText((p.queuePosition() + 1) + " / " + p.queueSnapshot().size());
        tick();
    }

    /** Position and volume (twice a second). */
    void tick() {
        PlayerService p = act.player();
        if (p == null) return;
        int pos = p.position();
        int dur = p.duration();
        if (!seeking) {
            seek.setProgress(dur > 0 ? (int) ((long) pos * 1000 / dur) : 0);
            time.setText(Song.formatTime(pos / 1000));
        }
        duration.setText(Song.formatTime(dur / 1000));
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (volume.getProgress() != v) volume.setProgress(v);
        loading.setVisibility(p.isLoading() ? View.VISIBLE : View.GONE);
    }

    private final class QueueAdapter extends BaseAdapter implements DragSortListView.DragAdapter {
        List<Song> songs = new ArrayList<Song>();
        int current;
        private int dragFrom = -1;
        private int dragTo = -1;

        /** Rows whose song moved by one place since the last target, and which way. */
        private int shiftLow = -1;
        private int shiftHigh = -1;
        private int shiftDir;

        @Override
        public void setDrag(int from, int to) {
            if (from >= 0 && dragFrom == from && dragTo >= 0 && to != dragTo) {
                shiftLow = Math.min(dragTo, to);
                shiftHigh = Math.max(dragTo, to);
                shiftDir = to > dragTo ? 1 : -1; // gap moved down: rows slide up into place
            } else {
                shiftLow = shiftHigh = -1;
            }
            dragFrom = from;
            dragTo = to;
            notifyDataSetChanged();
        }

        /** The queue index shown at a list position while a row is being dragged. */
        private int real(int position) {
            if (dragFrom < 0) return position;
            if (position == dragTo) return dragFrom;
            if (dragFrom < dragTo && position >= dragFrom && position < dragTo) return position + 1;
            if (dragFrom > dragTo && position > dragTo && position <= dragFrom) return position - 1;
            return position;
        }

        void moveLocally(int from, int to) {
            if (from < 0 || to < 0 || from >= songs.size() || to >= songs.size()) return;
            List<Song> copy = new ArrayList<Song>(songs);
            copy.add(to, copy.remove(from));
            songs = copy;
            if (current == from) current = to;
            else if (from < current && to >= current) current--;
            else if (from > current && to <= current) current++;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return songs.size();
        }

        @Override
        public Object getItem(int position) {
            return songs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(act).inflate(R.layout.row_queue, parent, false);
                Ui.tint((ImageView) v.findViewById(R.id.row_handle), 0x99FFFFFF);
                Ui.tint((ImageView) v.findViewById(R.id.row_badge), ACTIVE);
            }
            int index = real(position);
            // The gap where the dragged row would land.
            v.setVisibility(dragFrom >= 0 && position == dragTo ? View.INVISIBLE : View.VISIBLE);
            if (position >= shiftLow && position <= shiftHigh && position != dragTo) {
                // Slide the row into its new place instead of jumping there.
                android.view.animation.TranslateAnimation slide = new android.view.animation.TranslateAnimation(
                        0, 0, shiftDir * Ui.dp(act, 64), 0);
                slide.setDuration(120);
                slide.setInterpolator(new android.view.animation.DecelerateInterpolator());
                v.startAnimation(slide);
            } else if (v.getAnimation() != null && dragFrom < 0) {
                v.clearAnimation();
            }
            Song s = songs.get(index);
            TextView t = (TextView) v.findViewById(R.id.row_title);
            t.setText(s.title);
            t.setTextColor(index == current ? ACTIVE : index < current ? 0x80FFFFFF : 0xFFFFFFFF);
            ((TextView) v.findViewById(R.id.row_subtitle)).setText(s.artist);
            ((TextView) v.findViewById(R.id.row_duration)).setText(s.durationSec > 0 ? Song.formatTime(s.durationSec) : "");
            v.findViewById(R.id.row_badge).setVisibility(Library.get(act).isDownloaded(s.videoId) ? View.VISIBLE : View.GONE);
            Images.get(act).load((ImageView) v.findViewById(R.id.row_thumb), s.thumb, Images.get(act).px(48, 400),
                    R.drawable.art_placeholder_dark);
            return v;
        }
    }
}
