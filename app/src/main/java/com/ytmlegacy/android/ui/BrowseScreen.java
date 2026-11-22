package com.ytmlegacy.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.yt.InnerTube;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Page;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** An album, playlist, artist, chart or genre page. */
public final class BrowseScreen extends ListScreen {

    private final String browseId;
    private final String params;
    private String title;
    private Page page;
    private Page.Section main;
    /** The songs of the main list; rows share this list, so paging extends what plays. */
    private final List<Song> songs = new ArrayList<Song>();
    private View header;

    public BrowseScreen(String browseId, String params, String title) {
        this.browseId = browseId;
        this.params = params == null ? "" : params;
        this.title = title == null ? "" : title;
    }

    private boolean isCollection() {
        return browseId.startsWith("MPREb_") || browseId.startsWith("VL") || browseId.startsWith("OLAK");
    }

    private boolean isArtist() {
        return browseId.startsWith("UC") && params.length() == 0;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    protected View header(LayoutInflater inflater) {
        header = inflater.inflate(R.layout.page_header, null, false);
        header.setVisibility(View.GONE);
        return header;
    }

    @Override
    protected void load() {
        run(new Task<Page>() {
            @Override
            protected Page work() throws Exception {
                return InnerTube.get(act).browse(browseId, params);
            }

            @Override
            protected void done(Page p) {
                page = p;
                if (p.title.length() > 0 && !p.title.equals(title)) {
                    title = p.title;
                    act.screenTitleChanged(BrowseScreen.this);
                }
                main = p.mainList();
                songs.clear();
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                for (Page.Section s : p.sections) {
                    if (s == main && isCollection()) {
                        if (s.title.length() > 0) rows.add(Rows.header(s.title, null));
                        int index = 0;
                        for (Item i : s.items) {
                            if (i.song != null) {
                                songs.add(i.song);
                                rows.add(Rows.song(i, songs, index++));
                            } else {
                                rows.add(Rows.entry(i));
                            }
                        }
                    } else {
                        Rows.addSection(rows, s, isCollection() || isArtist());
                    }
                }
                bindHeader();
                setRows(rows);
            }

            @Override
            protected void failed(Exception e) {
                showError(e);
            }
        });
    }

    @Override
    protected boolean hasMore() {
        return main != null && main.continuation.length() > 0;
    }

    @Override
    protected void loadMore() {
        final Page.Section from = main;
        final String token = from.continuation;
        run(new Task<Page.Section>() {
            @Override
            protected Page.Section work() throws Exception {
                return InnerTube.get(act).browseContinuation(token, from.legacyContinuation, false, false);
            }

            @Override
            protected void done(Page.Section more) {
                from.continuation = more.continuation;
                from.legacyContinuation = more.legacyContinuation;
                from.items.addAll(more.items);
                List<Rows.Row> rows = new ArrayList<Rows.Row>();
                for (Item i : more.items) {
                    if (i.song != null) {
                        if (i.song.thumb.length() == 0 && page != null) i.song.thumb = i.thumb = page.thumb;
                        songs.add(i.song);
                        rows.add(Rows.song(i, isCollection() ? songs : null, songs.size() - 1));
                    } else {
                        rows.add(Rows.entry(i));
                    }
                }
                moreLoaded(rows);
            }

            @Override
            protected void failed(Exception e) {
                moreLoaded(null);
            }
        });
    }

    private Item asItem() {
        Item i = new Item();
        i.browseId = browseId;
        i.params = params;
        i.title = page == null ? title : page.title;
        i.subtitle = page == null ? "" : (page.artist.length() > 0 ? page.artist : page.subtitle);
        i.thumb = page == null ? "" : page.thumb;
        i.type = browseId.startsWith("MPREb_") ? Item.ALBUM : isArtist() ? Item.ARTIST : Item.PLAYLIST;
        if (browseId.startsWith("VL")) i.playlistId = browseId.substring(2);
        else if (page != null) i.playlistId = page.playlistId;
        return i;
    }

    private void bindHeader() {
        if (page == null || header == null) return;
        boolean show = page.thumb.length() > 0 && (isCollection() || isArtist());
        header.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        Images.get(act).load((ImageView) header.findViewById(R.id.ph_art), page.thumb, Images.get(act).px(120, 720),
                Ui.attrResource(act, R.attr.ytmPlaceholder));
        ((TextView) header.findViewById(R.id.ph_title)).setText(page.title);
        TextView artist = (TextView) header.findViewById(R.id.ph_artist);
        artist.setText(page.artist);
        artist.setVisibility(page.artist.length() > 0 ? View.VISIBLE : View.GONE);
        artist.setOnClickListener(page.artistId.length() == 0 ? null : new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.push(new BrowseScreen(page.artistId, "", page.artist));
            }
        });
        String sub = page.subtitle;
        if (page.secondSubtitle.length() > 0) sub = sub.length() > 0 ? sub + "\n" + page.secondSubtitle : page.secondSubtitle;
        ((TextView) header.findViewById(R.id.ph_subtitle)).setText(sub);
        final TextView desc = (TextView) header.findViewById(R.id.ph_description);
        desc.setText(page.description);
        desc.setVisibility(page.description.length() > 0 ? View.VISIBLE : View.GONE);
        desc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                desc.setMaxLines(desc.getLineCount() > 3 && desc.getTag() == null ? 1000 : 3);
                desc.setTag(desc.getTag() == null ? "open" : null);
            }
        });
        TextView play = (TextView) header.findViewById(R.id.ph_play);
        TextView shuffle = (TextView) header.findViewById(R.id.ph_shuffle);
        if (isArtist()) {
            play.setText(R.string.shuffle);
            shuffle.setText(R.string.radio);
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (page.shufflePlaylistId.length() > 0) act.playMix("", page.shufflePlaylistId, "");
                    else if (!songs.isEmpty() || !page.songs().isEmpty()) act.playList(page.songs(), 0, true);
                }
            });
            shuffle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (page.radioPlaylistId.length() > 0) act.playMix(page.radioVideoId, page.radioPlaylistId, "");
                    else if (!page.songs().isEmpty()) act.playRadio(page.songs().get(0));
                }
            });
        } else {
            play.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    act.actions().playCollection(asItem(), page, false);
                }
            });
            shuffle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    act.actions().playCollection(asItem(), page, true);
                }
            });
        }
        final ImageButton save = (ImageButton) header.findViewById(R.id.ph_save);
        Ui.tint(save, 0xFF33B5E5);
        save.setVisibility(isArtist() ? View.GONE : View.VISIBLE);
        bindSave(save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Library lib = Library.get(act);
                if (lib.isSaved(browseId)) lib.unsave(browseId);
                else lib.save(asItem());
                bindSave(save);
            }
        });
        ImageButton more = (ImageButton) header.findViewById(R.id.ph_more);
        Ui.tint(more, Ui.iconColor(act));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.actions().itemMenu(asItem(), page);
            }
        });
    }

    @Override
    public void addMenu(android.view.Menu menu) {
        super.addMenu(menu);
        if (page != null && (isCollection() || isArtist())) {
            menu.add(0, MENU_SCREEN + 1, 21, R.string.menu_options).setIcon(R.drawable.ic_more);
        }
    }

    @Override
    public boolean onMenu(int id) {
        if (id == MENU_SCREEN + 1 && page != null) {
            act.actions().itemMenu(asItem(), page);
            return true;
        }
        return super.onMenu(id);
    }

    private void bindSave(ImageButton save) {
        boolean saved = Library.get(act).isSaved(browseId);
        save.setImageResource(saved ? R.drawable.ic_bookmark : R.drawable.ic_bookmark_border);
    }
}
