package com.ytmlegacy.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** A playlist kept on the phone (or the liked songs). */
public final class LocalPlaylistScreen extends ListScreen {

    private final long id;
    private String name = "";
    private List<Song> songs = new ArrayList<Song>();
    private View header;

    public LocalPlaylistScreen(long id) {
        this.id = id;
    }

    @Override
    public String title() {
        return name.length() > 0 ? name : act.getString(R.string.playlist);
    }

    @Override
    protected View header(LayoutInflater inflater) {
        header = inflater.inflate(R.layout.page_header, null, false);
        return header;
    }

    @Override
    protected void load() {
        run(new Task<List<Song>>() {
            @Override
            protected List<Song> work() {
                name = Library.get(act).playlistName(id);
                return Library.get(act).playlistSongs(id);
            }

            @Override
            protected void done(List<Song> list) {
                songs = list;
                act.screenTitleChanged(LocalPlaylistScreen.this);
                bindHeader();
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                for (int i = 0; i < songs.size(); i++) rows.add(Rows.song(songs.get(i), songs, i));
                setRows(rows);
            }
        });
    }

    private void bindHeader() {
        ImageView art = (ImageView) header.findViewById(R.id.ph_art);
        String thumb = songs.isEmpty() ? "" : songs.get(0).thumb;
        Images.get(act).load(art, thumb, 360, Ui.attrResource(act, R.attr.ytmPlaceholder));
        ((TextView) header.findViewById(R.id.ph_title)).setText(title());
        header.findViewById(R.id.ph_artist).setVisibility(View.GONE);
        int seconds = 0;
        for (Song s : songs) seconds += s.durationSec;
        ((TextView) header.findViewById(R.id.ph_subtitle)).setText(act.getResources().getQuantityString(R.plurals.songs, songs.size(), songs.size())
                + (seconds > 0 ? " • " + Song.formatTime(seconds) : ""));
        header.findViewById(R.id.ph_play).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!songs.isEmpty()) act.playList(songs, 0, false);
            }
        });
        header.findViewById(R.id.ph_shuffle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!songs.isEmpty()) act.playList(songs, (int) (Math.random() * songs.size()), true);
            }
        });
        header.findViewById(R.id.ph_save).setVisibility(View.GONE);
        ImageButton more = (ImageButton) header.findViewById(R.id.ph_more);
        Ui.tint(more, Ui.iconColor(act));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.actions().localPlaylistMenu(id);
            }
        });
    }

    @Override
    public void onRowMenu(Rows.Row row) {
        if (row.song() != null) act.actions().songMenu(row.song(), id, row.index);
    }

    @Override
    public void onLibraryChanged() {
        if (!isDestroyed()) reload();
    }
}
