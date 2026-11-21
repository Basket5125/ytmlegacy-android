package com.ytmlegacy.android.ui;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.player.DownloadService;
import com.ytmlegacy.android.player.PlayerService;
import com.ytmlegacy.android.yt.InnerTube;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Page;
import com.ytmlegacy.android.yt.Parser;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** Menus and the actions behind them. */
public final class Actions {

    private static final int MAX_COLLECTION = 1000;
    private final MainActivity act;

    Actions(MainActivity act) {
        this.act = act;
    }

    private interface Choice {
        void run();
    }

    private void menu(String title, final List<String> labels, final List<Choice> choices) {
        Ui.dialog(act).setTitle(title)
                .setItems(labels.toArray(new CharSequence[labels.size()]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        choices.get(which).run();
                    }
                }).show();
    }

    private static List<Song> one(Song s) {
        List<Song> l = new ArrayList<Song>();
        l.add(s);
        return l;
    }

    // ------------------------------------------------------------------ songs

    /**
     * Menu of a song. {@code playlistId} ≥ 0 when shown inside a local playlist (offers removal),
     * {@code index} is its position there.
     */
    public void songMenu(final Song s, final long playlistId, final int index) {
        List<String> labels = new ArrayList<String>();
        List<Choice> choices = new ArrayList<Choice>();
        final Library lib = Library.get(act);
        labels.add(act.getString(R.string.play_next));
        choices.add(new Choice() {
            public void run() {
                act.addNext(one(s));
            }
        });
        labels.add(act.getString(R.string.add_to_queue));
        choices.add(new Choice() {
            public void run() {
                act.addLast(one(s));
            }
        });
        labels.add(act.getString(R.string.start_radio));
        choices.add(new Choice() {
            public void run() {
                act.playRadio(s);
            }
        });
        addCommonSongChoices(s, labels, choices);
        if (playlistId >= 0) {
            labels.add(act.getString(R.string.remove_from_playlist));
            choices.add(new Choice() {
                public void run() {
                    lib.removeFromPlaylist(playlistId, s.videoId);
                }
            });
            if (index > 0) {
                labels.add(act.getString(R.string.move_up));
                choices.add(new Choice() {
                    public void run() {
                        lib.moveInPlaylist(playlistId, index, index - 1);
                    }
                });
            }
            labels.add(act.getString(R.string.move_down));
            choices.add(new Choice() {
                public void run() {
                    lib.moveInPlaylist(playlistId, index, index + 1);
                }
            });
        }
        menu(s.title, labels, choices);
    }

    /** Menu of an entry of the play queue. */
    public void queueMenu(final Song s, final int index) {
        List<String> labels = new ArrayList<String>();
        List<Choice> choices = new ArrayList<Choice>();
        final PlayerService p = act.player();
        if (p == null) return;
        labels.add(act.getString(R.string.play));
        choices.add(new Choice() {
            public void run() {
                p.playIndex(index);
            }
        });
        labels.add(act.getString(R.string.remove_from_queue));
        choices.add(new Choice() {
            public void run() {
                p.removeAt(index);
            }
        });
        if (index > 0) {
            labels.add(act.getString(R.string.move_up));
            choices.add(new Choice() {
                public void run() {
                    p.move(index, index - 1);
                }
            });
        }
        if (index + 1 < p.queueSnapshot().size()) {
            labels.add(act.getString(R.string.move_down));
            choices.add(new Choice() {
                public void run() {
                    p.move(index, index + 1);
                }
            });
        }
        addCommonSongChoices(s, labels, choices);
        menu(s.title, labels, choices);
    }

    /** The menu of the song in Now Playing. */
    public void nowPlayingMenu(final Song s) {
        List<String> labels = new ArrayList<String>();
        List<Choice> choices = new ArrayList<Choice>();
        labels.add(act.getString(R.string.start_radio));
        choices.add(new Choice() {
            public void run() {
                PlayerService p = act.player();
                if (p != null) p.startRadioFromCurrent();
            }
        });
        labels.add(act.getString(R.string.add_to_playlist));
        choices.add(new Choice() {
            public void run() {
                addToPlaylist(one(s));
            }
        });
        if (s.artistId.length() > 0) {
            labels.add(act.getString(R.string.go_to_artist));
            choices.add(new Choice() {
                public void run() {
                    act.closeNowPlaying();
                    act.push(new BrowseScreen(s.artistId, "", s.artist));
                }
            });
        }
        if (s.albumId.length() > 0) {
            labels.add(act.getString(R.string.go_to_album));
            choices.add(new Choice() {
                public void run() {
                    act.closeNowPlaying();
                    act.push(new BrowseScreen(s.albumId, "", s.album));
                }
            });
        }
        labels.add(act.getString(R.string.share));
        choices.add(new Choice() {
            public void run() {
                share(s.title + " – " + s.artist, "https://music.youtube.com/watch?v=" + s.videoId);
            }
        });
        menu(s.title, labels, choices);
    }

    private void addCommonSongChoices(final Song s, List<String> labels, List<Choice> choices) {
        final Library lib = Library.get(act);
        labels.add(act.getString(R.string.add_to_playlist));
        choices.add(new Choice() {
            public void run() {
                addToPlaylist(one(s));
            }
        });
        final boolean liked = lib.isLiked(s.videoId);
        labels.add(act.getString(liked ? R.string.unlike : R.string.like));
        choices.add(new Choice() {
            public void run() {
                lib.setLiked(s, !liked);
            }
        });
        final boolean downloaded = lib.isDownloaded(s.videoId);
        labels.add(act.getString(downloaded ? R.string.remove_download : R.string.download));
        choices.add(new Choice() {
            public void run() {
                if (downloaded) lib.removeDownload(s.videoId);
                else download(one(s));
            }
        });
        if (s.artistId.length() > 0) {
            labels.add(act.getString(R.string.go_to_artist));
            choices.add(new Choice() {
                public void run() {
                    act.push(new BrowseScreen(s.artistId, "", s.artist));
                }
            });
        }
        if (s.albumId.length() > 0) {
            labels.add(act.getString(R.string.go_to_album));
            choices.add(new Choice() {
                public void run() {
                    act.push(new BrowseScreen(s.albumId, "", s.album));
                }
            });
        }
        labels.add(act.getString(R.string.share));
        choices.add(new Choice() {
            public void run() {
                share(s.title + " – " + s.artist, "https://music.youtube.com/watch?v=" + s.videoId);
            }
        });
    }

    public void download(List<Song> songs) {
        if (songs.isEmpty()) return;
        DownloadService.enqueue(act, songs);
        Ui.toast(act, R.string.download_queued);
    }

    public void share(String title, String url) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, url);
        try {
            act.startActivity(Intent.createChooser(i, act.getString(R.string.share)));
        } catch (Exception e) {
            Ui.toast(act, url);
        }
    }

    // ------------------------------------------------------------------ collections

    public void itemMenu(Item item) {
        itemMenu(item, null);
    }

    public void itemMenu(final Item item, final Page known) {
        if (item.song != null) {
            songMenu(item.song, -1, -1);
            return;
        }
        List<String> labels = new ArrayList<String>();
        List<Choice> choices = new ArrayList<Choice>();
        if (item.type == Item.ARTIST) {
            labels.add(act.getString(R.string.radio));
            choices.add(new Choice() {
                public void run() {
                    withSongs(item, known, new SongsReady() {
                        public void ready(List<Song> songs, Page page) {
                            if (page != null && page.radioPlaylistId.length() > 0) {
                                act.playMix(page.radioVideoId, page.radioPlaylistId, "");
                            } else if (!songs.isEmpty()) {
                                act.playRadio(songs.get(0));
                            }
                        }
                    });
                }
            });
            labels.add(act.getString(R.string.shuffle));
            choices.add(new Choice() {
                public void run() {
                    playCollection(item, known, true);
                }
            });
            menu(item.title, labels, choices);
            return;
        }
        if (item.type == Item.NAV) {
            act.open(item);
            return;
        }
        labels.add(act.getString(R.string.play));
        choices.add(new Choice() {
            public void run() {
                playCollection(item, known, false);
            }
        });
        labels.add(act.getString(R.string.shuffle));
        choices.add(new Choice() {
            public void run() {
                playCollection(item, known, true);
            }
        });
        labels.add(act.getString(R.string.play_next));
        choices.add(new Choice() {
            public void run() {
                withSongs(item, known, new SongsReady() {
                    public void ready(List<Song> songs, Page page) {
                        act.addNext(songs);
                    }
                });
            }
        });
        labels.add(act.getString(R.string.add_to_queue));
        choices.add(new Choice() {
            public void run() {
                withSongs(item, known, new SongsReady() {
                    public void ready(List<Song> songs, Page page) {
                        act.addLast(songs);
                    }
                });
            }
        });
        final String radioId = item.playlistId.length() > 0 ? "RDAMPL" + item.playlistId : "";
        if (radioId.length() > 0) {
            labels.add(act.getString(R.string.start_radio));
            choices.add(new Choice() {
                public void run() {
                    act.playMix("", radioId, "");
                }
            });
        }
        labels.add(act.getString(R.string.add_to_playlist));
        choices.add(new Choice() {
            public void run() {
                withSongs(item, known, new SongsReady() {
                    public void ready(List<Song> songs, Page page) {
                        addToPlaylist(songs);
                    }
                });
            }
        });
        labels.add(act.getString(R.string.download_all));
        choices.add(new Choice() {
            public void run() {
                withSongs(item, known, new SongsReady() {
                    public void ready(List<Song> songs, Page page) {
                        download(songs);
                    }
                });
            }
        });
        if (item.browseId.length() > 0 && item.type != Item.MIX) {
            final Library lib = Library.get(act);
            final boolean saved = lib.isSaved(item.browseId);
            labels.add(act.getString(saved ? R.string.remove_from_library : R.string.save_to_library));
            choices.add(new Choice() {
                public void run() {
                    if (saved) lib.unsave(item.browseId);
                    else lib.save(item);
                }
            });
        }
        if (item.playlistId.length() > 0 || item.browseId.startsWith("MPREb_")) {
            labels.add(act.getString(R.string.share));
            choices.add(new Choice() {
                public void run() {
                    String url = item.playlistId.length() > 0
                            ? "https://music.youtube.com/playlist?list=" + item.playlistId
                            : "https://music.youtube.com/browse/" + item.browseId;
                    share(item.title, url);
                }
            });
        }
        menu(item.title, labels, choices);
    }

    public void playCollection(final Item item, Page known, final boolean shuffle) {
        if (item.type == Item.MIX) {
            act.playMix("", item.playlistId, item.params);
            return;
        }
        withSongs(item, known, new SongsReady() {
            public void ready(List<Song> songs, Page page) {
                if (item.type == Item.ARTIST && page != null && shuffle && page.shufflePlaylistId.length() > 0) {
                    act.playMix("", page.shufflePlaylistId, "");
                    return;
                }
                if (songs.isEmpty()) {
                    Ui.toast(act, R.string.empty);
                    return;
                }
                act.playList(songs, shuffle ? (int) (Math.random() * songs.size()) : 0, shuffle);
            }
        });
    }

    interface SongsReady {
        void ready(List<Song> songs, Page page);
    }

    /** All songs of an album/playlist (following its pages), with a progress dialog. */
    @SuppressWarnings("deprecation")
    void withSongs(final Item item, final Page known, final SongsReady then) {
        final ProgressDialog progress = new ProgressDialog(act);
        progress.setMessage(act.getString(R.string.loading));
        progress.setIndeterminate(true);
        progress.setCancelable(true);
        final Task<Object[]> task = new Task<Object[]>() {
            @Override
            protected Object[] work() throws Exception {
                InnerTube tube = InnerTube.get(act);
                if (item.type == Item.MIX) {
                    Parser.Watch w = tube.next("", item.playlistId, item.params);
                    return new Object[]{w.songs, null};
                }
                Page page = known != null ? known : tube.browse(item.browseId, item.params);
                List<Song> songs = new ArrayList<Song>();
                Page.Section main = page.mainList();
                if (main == null) return new Object[]{page.songs(), page};
                for (Item i : main.items) if (i.song != null) songs.add(i.song);
                String token = main.continuation;
                boolean legacy = main.legacyContinuation;
                while (token.length() > 0 && songs.size() < MAX_COLLECTION && !isCancelled()) {
                    Page.Section more = tube.browseContinuation(token, legacy, false, false);
                    for (Item i : more.items) {
                        if (i.song == null) continue;
                        if (i.song.thumb.length() == 0) i.song.thumb = page.thumb;
                        songs.add(i.song);
                    }
                    token = more.continuation;
                    legacy = more.legacyContinuation;
                }
                if (songs.isEmpty()) songs = page.songs();
                return new Object[]{songs, page};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done(Object[] r) {
                dismiss(progress);
                then.ready((List<Song>) r[0], (Page) r[1]);
            }

            @Override
            protected void failed(Exception e) {
                dismiss(progress);
                Ui.toast(act, act.isOnline() ? R.string.error_load : R.string.error_offline);
            }
        };
        progress.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                task.cancel();
            }
        });
        boolean quick = known != null && (known.mainList() == null || known.mainList().continuation.length() == 0);
        if (!quick) progress.show();
        task.start();
    }

    private static void dismiss(ProgressDialog d) {
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ local playlists

    public void addToPlaylist(final List<Song> songs) {
        if (songs.isEmpty()) return;
        final List<Library.Playlist> playlists = Library.get(act).playlists();
        List<String> labels = new ArrayList<String>();
        labels.add("+ " + act.getString(R.string.new_playlist));
        for (Library.Playlist p : playlists) labels.add(p.name);
        Ui.dialog(act).setTitle(R.string.add_to_playlist)
                .setItems(labels.toArray(new CharSequence[labels.size()]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            newPlaylist(songs);
                        } else {
                            Library.Playlist p = playlists.get(which - 1);
                            int n = Library.get(act).addToPlaylist(p.id, songs);
                            Ui.toast(act, act.getString(R.string.added_to, n, p.name));
                        }
                    }
                }).show();
    }

    public void newPlaylist(final List<Song> songs) {
        final EditText name = Ui.field(act, "", act.getString(R.string.playlist_name));
        Ui.dialog(act).setTitle(R.string.new_playlist).setView(Ui.padded(act, name))
                .setPositiveButton(R.string.create, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String n = name.getText().toString().trim();
                        if (n.length() == 0) n = act.getString(R.string.new_playlist);
                        long id = Library.get(act).createPlaylist(n);
                        if (songs != null && !songs.isEmpty()) {
                            int added = Library.get(act).addToPlaylist(id, songs);
                            Ui.toast(act, act.getString(R.string.added_to, added, n));
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    public void localPlaylistMenu(final long id) {
        final Library lib = Library.get(act);
        final String name = lib.playlistName(id);
        List<String> labels = new ArrayList<String>();
        List<Choice> choices = new ArrayList<Choice>();
        labels.add(act.getString(R.string.play));
        choices.add(new Choice() {
            public void run() {
                List<Song> s = lib.playlistSongs(id);
                if (!s.isEmpty()) act.playList(s, 0, false);
            }
        });
        labels.add(act.getString(R.string.add_to_queue));
        choices.add(new Choice() {
            public void run() {
                act.addLast(lib.playlistSongs(id));
            }
        });
        labels.add(act.getString(R.string.download_all));
        choices.add(new Choice() {
            public void run() {
                download(lib.playlistSongs(id));
            }
        });
        labels.add(act.getString(R.string.export_csv));
        choices.add(new Choice() {
            public void run() {
                act.csv().export(name, lib.playlistSongs(id));
            }
        });
        if (id != Library.LIKED_ID) {
            labels.add(act.getString(R.string.rename));
            choices.add(new Choice() {
                public void run() {
                    final EditText field = Ui.field(act, name, act.getString(R.string.playlist_name));
                    Ui.dialog(act).setTitle(R.string.rename).setView(Ui.padded(act, field))
                            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    String n = field.getText().toString().trim();
                                    if (n.length() > 0) lib.renamePlaylist(id, n);
                                }
                            }).setNegativeButton(R.string.cancel, null).show();
                }
            });
            labels.add(act.getString(R.string.delete));
            choices.add(new Choice() {
                public void run() {
                    Ui.dialog(act).setMessage(act.getString(R.string.delete_playlist_q, name))
                            .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    lib.deletePlaylist(id);
                                    act.popIfShowing(id);
                                }
                            }).setNegativeButton(R.string.cancel, null).show();
                }
            });
        }
        menu(name, labels, choices);
    }

    // ------------------------------------------------------------------ lyrics, timer

    @SuppressWarnings("deprecation")
    public void lyrics(final Song s) {
        final ProgressDialog progress = ProgressDialog.show(act, s.title, act.getString(R.string.loading), true, true);
        new Task<String>() {
            @Override
            protected String work() throws Exception {
                InnerTube tube = InnerTube.get(act);
                Parser.Watch w = tube.next(s.videoId, "", "");
                if (w.lyricsBrowseId.length() == 0) return "";
                return tube.lyrics(w.lyricsBrowseId);
            }

            @Override
            protected void done(String text) {
                dismiss(progress);
                show(text.length() > 0 ? text : act.getString(R.string.no_lyrics));
            }

            @Override
            protected void failed(Exception e) {
                dismiss(progress);
                Ui.toast(act, act.isOnline() ? R.string.error_load : R.string.error_offline);
            }

            private void show(String text) {
                ScrollView scroll = new ScrollView(act);
                TextView t = new TextView(act);
                int p = Ui.dp(act, 18);
                t.setPadding(p, p / 2, p, p);
                t.setTextSize(16);
                t.setLineSpacing(0, 1.15f);
                t.setText(text);
                if (android.os.Build.VERSION.SDK_INT < 11) t.setTextColor(0xFFFFFFFF);
                scroll.addView(t);
                Ui.dialog(act).setTitle(s.title).setView(scroll).setPositiveButton(R.string.ok, null).show();
            }
        }.start();
    }

    public void sleepTimer() {
        final PlayerService p = act.player();
        if (p == null) return;
        final int[] minutes = {0, 5, 10, 15, 30, 45, 60, 90, 120, -1};
        List<String> labels = new ArrayList<String>();
        for (int m : minutes) {
            if (m == 0) labels.add(act.getString(R.string.sleep_off));
            else if (m < 0) labels.add(act.getString(R.string.sleep_end_of_track));
            else labels.add(act.getString(R.string.sleep_minutes, m));
        }
        long left = p.sleepRemaining();
        String title = act.getString(R.string.sleep_timer);
        if (left > 0) title += " • " + Song.formatTime(left / 1000);
        else if (left < 0) title += " • " + act.getString(R.string.sleep_end_of_track);
        AlertDialog.Builder b = Ui.dialog(act).setTitle(title);
        b.setItems(labels.toArray(new CharSequence[labels.size()]), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                p.setSleepTimer(minutes[which]);
                if (minutes[which] > 0) {
                    Ui.toast(act, act.getString(R.string.sleep_set, act.getString(R.string.sleep_minutes, minutes[which])));
                }
            }
        }).show();
    }
}
