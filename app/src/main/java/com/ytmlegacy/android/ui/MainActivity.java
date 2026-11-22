package com.ytmlegacy.android.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.net.Tls;
import com.ytmlegacy.android.player.PlayerService;
import com.ytmlegacy.android.yt.Item;
import com.ytmlegacy.android.yt.Song;

import java.util.ArrayList;
import java.util.List;

/** The only activity: action bar, tabs, the pages, the mini player and Now Playing. */
public final class MainActivity extends Activity implements PlayerService.Listener {

    public static final String ACTION_NOW_PLAYING = "ytm.OPEN_NOW_PLAYING";
    public static final String ACTION_DOWNLOADS = "ytm.OPEN_DOWNLOADS";
    private static final int TABS = 4;

    private Prefs prefs;
    private final Handler handler = new Handler();
    private PlayerService player;
    private boolean bound;
    private final List<Runnable> pendingPlayer = new ArrayList<Runnable>();
    private final List<List<Screen>> stacks = new ArrayList<List<Screen>>();
    private int tab = -1;
    private TextView[] tabViews;
    private FrameLayout content;
    private TextView barTitle;
    private EditText barSearch;
    private View barUp;
    private View barLogo;
    private View barClear;
    private View barSearchButton;
    private View mini;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageView miniArt;
    private ImageButton miniToggle;
    private ProgressBar miniProgress;
    private ProgressBar miniLoading;
    private DragDownLayout npContainer;
    private NowPlaying nowPlaying;
    private Actions actions;
    private CsvUi csv;
    private String miniShownId = "";
    private String lastPlayingId = "";
    private boolean resumed;

    // ------------------------------------------------------------------ theme

    public static boolean isDarkTheme(Context c) {
        int t = Prefs.get(c).theme();
        if (t == Prefs.THEME_LIGHT) return false;
        if (t == Prefs.THEME_SYSTEM && Build.VERSION.SDK_INT >= 29) {
            int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night != Configuration.UI_MODE_NIGHT_NO;
        }
        return true;
    }

    public static boolean isOledTheme(Context c) {
        return Prefs.get(c).theme() == Prefs.THEME_OLED;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(com.ytmlegacy.android.data.Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        prefs = Prefs.get(this);
        setTheme(isOledTheme(this) ? R.style.Theme_YTM_Oled
                : isDarkTheme(this) ? R.style.Theme_YTM_Dark : R.style.Theme_YTM_Light);
        super.onCreate(state);
        Tls.install(this);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.activity_main);
        actions = new Actions(this);
        csv = new CsvUi(this);

        content = (FrameLayout) findViewById(R.id.content);
        barTitle = (TextView) findViewById(R.id.bar_title);
        barSearch = (EditText) findViewById(R.id.bar_search);
        barUp = findViewById(R.id.bar_up);
        barLogo = findViewById(R.id.bar_logo);
        barClear = findViewById(R.id.bar_clear);
        barSearchButton = findViewById(R.id.bar_search_button);
        barUp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pop();
            }
        });
        barLogo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(0);
            }
        });
        barSearchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch();
            }
        });
        barClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (barSearch.getText().length() > 0) {
                    barSearch.setText("");
                    showKeyboard(barSearch);
                } else {
                    pop();
                }
            }
        });

        tabViews = new TextView[]{(TextView) findViewById(R.id.tab_home), (TextView) findViewById(R.id.tab_explore),
                (TextView) findViewById(R.id.tab_library), (TextView) findViewById(R.id.tab_settings)};
        for (int i = 0; i < TABS; i++) {
            final int index = i;
            stacks.add(new ArrayList<Screen>());
            tabViews[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
        }

        mini = findViewById(R.id.mini);
        miniTitle = (TextView) findViewById(R.id.mini_title);
        miniArtist = (TextView) findViewById(R.id.mini_artist);
        miniArt = (ImageView) findViewById(R.id.mini_art);
        miniToggle = (ImageButton) findViewById(R.id.mini_toggle);
        miniProgress = (ProgressBar) findViewById(R.id.mini_progress);
        miniLoading = (ProgressBar) findViewById(R.id.mini_loading);
        int icon = Ui.iconColor(this);
        Ui.tint(miniToggle, icon);
        Ui.tint((ImageView) findViewById(R.id.mini_next), icon);
        Ui.tint((ImageView) findViewById(R.id.bar_search_button), 0xFFFFFFFF);
        findViewById(R.id.mini_body).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNowPlaying();
            }
        });
        View.OnClickListener toggle = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) player.toggle();
            }
        };
        miniToggle.setOnClickListener(toggle);
        miniLoading.setOnClickListener(toggle);
        findViewById(R.id.mini_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (player != null) player.next();
            }
        });

        npContainer = (DragDownLayout) findViewById(R.id.np_container);
        if (isOledTheme(this)) {
            findViewById(R.id.np).setBackgroundColor(0xFF000000);
            if (Build.VERSION.SDK_INT >= 21) Lollipop.blackNavigationBar(this);
        }
        nowPlaying = new NowPlaying(this, npContainer);
        npContainer.setListener(new DragDownLayout.Listener() {
            @Override
            public void onOpened() {
                nowPlaying.update();
                applyScreenOn();
            }

            @Override
            public void onClosed() {
                nowPlaying.hideQueue();
                applyScreenOn();
            }
        });

        Library.get(this).addListener(libraryListener);
        int start = Math.max(0, Math.min(TABS - 1, prefs.startTab()));
        if (state != null) start = state.getInt("tab", start);
        selectTab(start);
        if (state != null && state.getBoolean("np", false)) npContainer.setOpenNow(true);
        handleIntent(getIntent());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Before Android 4.2 the language is set on the shared resources, which a rotation resets.
        if (Build.VERSION.SDK_INT < 17) com.ytmlegacy.android.data.Lang.wrap(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putBoolean("np", npContainer.isOpen());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_NOW_PLAYING.equals(action)) {
            openNowPlaying();
        } else if (ACTION_DOWNLOADS.equals(action)) {
            closeNowPlaying();
            selectTab(2);
            push(new DownloadsScreen());
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            openLink(intent.getData());
        }
    }

    /** music.youtube.com / youtube.com / youtu.be links. */
    private void openLink(Uri uri) {
        String v = uri.getQueryParameter("v");
        String list = uri.getQueryParameter("list");
        if ((v == null || v.length() == 0) && "youtu.be".equals(uri.getHost()) && uri.getPathSegments().size() > 0) {
            v = uri.getPathSegments().get(0);
        }
        if (v != null && Song.isVideoId(v)) {
            final String id = v;
            new Task<Song>() {
                @Override
                protected Song work() throws Exception {
                    return com.ytmlegacy.android.yt.InnerTube.get(MainActivity.this).videoDetails(id);
                }

                @Override
                protected void done(Song s) {
                    if (s != null) playRadio(s);
                }

                @Override
                protected void failed(Exception e) {
                    Ui.toast(MainActivity.this, R.string.error_load);
                }
            }.start();
        } else if (list != null && list.length() > 0) {
            push(new BrowseScreen("VL" + list, "", ""));
        } else {
            List<String> seg = uri.getPathSegments();
            if (seg.size() >= 2 && ("browse".equals(seg.get(0)) || "channel".equals(seg.get(0)))) {
                push(new BrowseScreen(seg.get(1), "", ""));
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.post(tick);
        Screen s = top();
        if (s != null) s.onPlayerChanged();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (player != null) player.removeListener(this);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        player = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Library.get(this).removeListener(libraryListener);
        for (List<Screen> stack : stacks) for (Screen s : stack) s.onDestroy();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            player = ((PlayerService.LocalBinder) service).service();
            bound = true;
            player.addListener(MainActivity.this);
            List<Runnable> run = new ArrayList<Runnable>(pendingPlayer);
            pendingPlayer.clear();
            for (Runnable r : run) r.run();
            onPlayerState();
            onQueueChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            player = null;
        }
    };

    /** Restarts the activity (after a theme change). */
    void restart() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        finish();
        overridePendingTransition(0, 0);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ player

    public PlayerService player() {
        return player;
    }

    public String playingId() {
        Song s = player == null ? null : player.current();
        return s == null ? "" : s.videoId;
    }

    /** Runs {@code r} with the service started (it outlives the activity) and bound. */
    private void withPlayer(Runnable r) {
        startService(new Intent(this, PlayerService.class));
        askNotifications();
        if (player != null) r.run();
        else pendingPlayer.add(r);
    }

    public void playList(final List<Song> songs, final int index, final boolean shuffle) {
        if (songs == null || songs.isEmpty()) return;
        final List<Song> copy = new ArrayList<Song>(songs);
        withPlayer(new Runnable() {
            @Override
            public void run() {
                player.playList(copy, index, shuffle);
            }
        });
    }

    public void playRadio(final Song song) {
        if (song == null) return;
        withPlayer(new Runnable() {
            @Override
            public void run() {
                player.playRadio(song);
            }
        });
    }

    public void playMix(final String videoId, final String playlistId, final String params) {
        withPlayer(new Runnable() {
            @Override
            public void run() {
                player.playMix(videoId, playlistId, params);
            }
        });
    }

    public void addNext(final List<Song> songs) {
        if (songs.isEmpty()) return;
        withPlayer(new Runnable() {
            @Override
            public void run() {
                player.addNext(songs);
                Ui.toast(MainActivity.this, R.string.will_play_next);
            }
        });
    }

    public void addLast(final List<Song> songs) {
        if (songs.isEmpty()) return;
        withPlayer(new Runnable() {
            @Override
            public void run() {
                player.addLast(songs);
                Ui.toast(MainActivity.this, R.string.added_to_queue);
            }
        });
    }

    /** What tapping an item does. */
    public void open(Item item) {
        switch (item.type) {
            case Item.SONG:
            case Item.VIDEO:
                playRadio(item.song);
                break;
            case Item.MIX:
                playMix("", item.playlistId, item.params);
                break;
            default:
                if (item.browseId.length() > 0) push(new BrowseScreen(item.browseId, item.params, item.title));
                break;
        }
    }

    public Actions actions() {
        return actions;
    }

    public CsvUi csv() {
        return csv;
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications()) {
            prefs.setAskedNotifications();
            Permissions.askNotifications(this);
        }
    }

    @TargetApi(21)
    private static final class Lollipop {
        static void blackNavigationBar(Activity a) {
            a.getWindow().setNavigationBarColor(0xFF000000);
        }
    }

    @TargetApi(33)
    private static final class Permissions {
        static void askNotifications(Activity a) {
            if (a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
        }
    }

    @Override
    public void onPlayerState() {
        updateMini();
        if (npContainer.isOpen()) nowPlaying.update();
        String id = playingId();
        if (!id.equals(lastPlayingId)) {
            lastPlayingId = id;
            Screen s = top();
            if (s != null) s.onPlayerChanged();
            if (npContainer.isOpen()) nowPlaying.queueChanged();
        }
    }

    @Override
    public void onQueueChanged() {
        nowPlaying.queueChanged();
        updateMini();
    }

    @Override
    public void onPlayerError(String message) {
        if (resumed) Ui.toast(this, message);
    }

    private void updateMini() {
        Song s = player == null ? null : player.current();
        mini.setVisibility(s == null ? View.GONE : View.VISIBLE);
        if (s == null) return;
        if (!s.videoId.equals(miniShownId)) {
            miniShownId = s.videoId;
            miniTitle.setText(s.title);
            miniArtist.setText(s.artist);
            Images.get(this).load(miniArt, s.thumb, Images.get(this).px(44, 300), Ui.attrResource(this, R.attr.ytmPlaceholder));
        }
        miniToggle.setImageResource(player.wantsToPlay() ? R.drawable.ic_pause : R.drawable.ic_play);
        showMiniLoading(player.isLoading());
        tickMini();
    }

    /** While a song loads the spinner stands in for the play/pause button. */
    private void showMiniLoading(boolean loading) {
        miniLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        miniToggle.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
    }

    private void tickMini() {
        if (player == null) return;
        int d = player.duration();
        miniProgress.setProgress(d > 0 ? (int) ((long) player.position() * 1000 / d) : 0);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                tickMini();
                showMiniLoading(player.isLoading());
                if (npContainer.isOpen()) nowPlaying.tick();
            }
            handler.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ now playing

    public void openNowPlaying() {
        if (player == null || player.current() == null) {
            if (player == null) {
                pendingPlayer.add(new Runnable() {
                    @Override
                    public void run() {
                        if (player.current() != null) openNowPlaying();
                    }
                });
            }
            return;
        }
        hideKeyboard(barSearch);
        nowPlaying.update();
        nowPlaying.queueChanged();
        npContainer.open();
    }

    public void closeNowPlaying() {
        npContainer.close();
    }

    private void applyScreenOn() {
        if (npContainer.isOpen() && prefs.keepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------------ navigation

    private Screen top() {
        if (tab < 0) return null;
        List<Screen> stack = stacks.get(tab);
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private Screen root(int index) {
        switch (index) {
            case 0:
                return new HomeScreen(false);
            case 1:
                return new HomeScreen(true);
            case 2:
                return new LibraryScreen();
            default:
                return new SettingsScreen();
        }
    }

    public void selectTab(int index) {
        if (index == tab) {
            List<Screen> stack = stacks.get(tab);
            if (stack.size() > 1) {
                // Back to the tab's first page.
                Screen old = top();
                if (old != null) old.onHidden();
                while (stack.size() > 1) stack.remove(stack.size() - 1).onDestroy();
                show(top());
            } else if (top() != null) {
                top().onReselected();
            }
            return;
        }
        Screen old = top();
        if (old != null) old.onHidden();
        tab = index;
        for (int i = 0; i < TABS; i++) {
            tabViews[i].setSelected(i == index);
            tabViews[i].setTextColor(i == index ? 0xFF33B5E5 : Ui.attrColor(this, R.attr.ytmText2));
        }
        List<Screen> stack = stacks.get(index);
        if (stack.isEmpty()) {
            Screen s = root(index);
            s.attach(this);
            stack.add(s);
        }
        show(top());
    }

    public void push(Screen s) {
        if (npContainer.isOpen()) closeNowPlaying();
        Screen old = top();
        if (old != null) old.onHidden();
        s.attach(this);
        stacks.get(tab).add(s);
        show(s);
    }

    public void pop() {
        List<Screen> stack = stacks.get(tab);
        if (stack.size() <= 1) return;
        Screen old = stack.remove(stack.size() - 1);
        old.onHidden();
        old.onDestroy();
        show(top());
    }

    /** Leaves a deleted local playlist's page. */
    void popIfShowing(long playlistId) {
        Screen s = top();
        if (s instanceof LocalPlaylistScreen) pop();
    }

    private void openSearch() {
        Screen s = top();
        if (s != null && s.isSearch()) {
            showKeyboard(barSearch);
            return;
        }
        push(new SearchScreen());
    }

    private void show(Screen s) {
        if (s == null) return;
        content.removeAllViews();
        View v = s.view(getLayoutInflater(), content);
        if (v.getParent() != null) ((android.view.ViewGroup) v.getParent()).removeView(v);
        content.addView(v);
        List<Screen> stack = stacks.get(tab);
        boolean deep = stack.size() > 1;
        barUp.setVisibility(deep ? View.VISIBLE : View.GONE);
        barLogo.setVisibility(deep ? View.GONE : View.VISIBLE);
        boolean search = s.isSearch();
        barSearch.setVisibility(search ? View.VISIBLE : View.GONE);
        barClear.setVisibility(search ? View.VISIBLE : View.GONE);
        barTitle.setVisibility(search ? View.GONE : View.VISIBLE);
        barSearchButton.setVisibility(search ? View.GONE : View.VISIBLE);
        if (!search) hideKeyboard(barSearch);
        barTitle.setText(s.title());
        s.onShown();
    }

    void screenTitleChanged(Screen s) {
        if (s == top() && !s.isSearch()) barTitle.setText(s.title());
    }

    @Override
    public void onBackPressed() {
        if (npContainer.isOpen()) {
            if (nowPlaying.queueShown()) nowPlaying.hideQueue();
            else closeNowPlaying();
            return;
        }
        Screen s = top();
        if (s != null && s.onBack()) return;
        if (stacks.get(tab).size() > 1) {
            pop();
            return;
        }
        int start = Math.max(0, Math.min(TABS - 1, prefs.startTab()));
        if (tab != start) {
            selectTab(start);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ hardware menu key

    private static final int MENU_SEARCH = 1;
    private static final int MENU_NOW_PLAYING = 2;
    private static final int MENU_TIMER = 3;
    private static final int MENU_EQUALIZER = 4;
    private static final int MENU_SETTINGS = 5;
    private static final int MENU_EXIT = 6;

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        return true;
    }

    /** The classic menu panel of the hardware Menu key, built for the screen on show. */
    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        menu.clear();
        if (npContainer.isOpen()) return false; // the Now Playing menu is shown instead
        Screen s = top();
        if (s == null || !s.isSearch()) {
            menu.add(0, MENU_SEARCH, 1, R.string.search).setIcon(R.drawable.ic_search);
        }
        if (player != null && player.current() != null) {
            menu.add(0, MENU_NOW_PLAYING, 2, R.string.now_playing).setIcon(R.drawable.ic_note);
        }
        if (s != null) s.addMenu(menu);
        if (player != null && player.current() != null) {
            menu.add(0, MENU_TIMER, 30, R.string.sleep_timer).setIcon(R.drawable.ic_timer);
        }
        menu.add(0, MENU_EQUALIZER, 31, R.string.set_equalizer).setIcon(R.drawable.ic_equalizer);
        if (tab != 3) menu.add(0, MENU_SETTINGS, 32, R.string.tab_settings).setIcon(R.drawable.ic_more);
        menu.add(0, MENU_EXIT, 40, R.string.menu_exit).setIcon(R.drawable.ic_close);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SEARCH:
                openSearch();
                return true;
            case MENU_NOW_PLAYING:
                openNowPlaying();
                return true;
            case MENU_TIMER:
                actions.sleepTimer();
                return true;
            case MENU_EQUALIZER:
                push(new EqualizerScreen());
                return true;
            case MENU_SETTINGS:
                selectTab(3);
                return true;
            case MENU_EXIT:
                // Stops the music too: "exit" should leave nothing running.
                if (player != null) player.stop();
                finish();
                return true;
            default:
                Screen s = top();
                return (s != null && s.onMenu(item.getItemId())) || super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            openSearch();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && npContainer.isOpen()) {
            Song cur = player == null ? null : player.current();
            if (cur != null) actions.nowPlayingMenu(cur);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ misc

    private final Runnable libraryChanged = new Runnable() {
        @Override
        public void run() {
            for (List<Screen> stack : stacks) for (Screen s : stack) s.onLibraryChanged();
            if (npContainer.isOpen()) nowPlaying.update();
        }
    };

    private final Runnable libraryListener = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(libraryChanged);
            handler.postDelayed(libraryChanged, 300);
        }
    };

    public EditText searchField() {
        return barSearch;
    }

    public void showKeyboard(final EditText field) {
        field.requestFocus();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    public void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
    }

    public void postDelayed(Runnable r, long ms) {
        handler.postDelayed(r, ms);
    }

    public void removeCallbacks(Runnable r) {
        handler.removeCallbacks(r);
    }

    @SuppressWarnings("deprecation")
    public boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        csv.onResult(requestCode, resultCode, data);
    }
}
