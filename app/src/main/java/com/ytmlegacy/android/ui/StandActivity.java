package com.ytmlegacy.android.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Lang;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.player.PlayerService;
import com.ytmlegacy.android.yt.Song;

import java.util.Random;

/**
 * "Stand" mode: cover art, title and artist on black, for a phone or tablet standing on a dock.
 * Built to cost little battery: nothing redraws on a timer except a tiny burn-in shift once a
 * minute, the background is black (dark pixels are off on AMOLED), the screen can be dimmed,
 * and the views change only when the song does. A tap shows the controls for a few seconds.
 */
public final class StandActivity extends Activity implements PlayerService.Listener {

    private static final long CONTROLS_MS = 4000;
    private static final long SHIFT_MS = 60000;

    private final Handler handler = new Handler();
    private final Random random = new Random();
    private PlayerService player;
    private boolean bound;
    private StandLayout stand;
    private ImageView art;
    private TextView title;
    private TextView artist;
    private LinearLayout controls;
    private ImageButton toggle;
    private String shownId = "";
    private boolean shownPlaying;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        w.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
        if (Prefs.get(this).standDim()) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = 0.2f;
            w.setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        stand = new StandLayout(this);
        art = new RoundedImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = text(26, 0xFFFFFFFF, true);
        artist = text(18, 0xB3FFFFFF, false);
        texts.addView(title);
        texts.addView(artist);
        stand.addView(art);
        stand.addView(texts);
        root.addView(stand, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setBackgroundColor(0xB3000000);
        int pad = Ui.dp(this, 12);
        controls.setPadding(pad, pad, pad, pad);
        controls.addView(button(R.drawable.ic_skip_previous, R.string.action_previous, new Runnable() {
            public void run() {
                if (player != null) player.previous();
            }
        }));
        toggle = button(R.drawable.ic_pause, R.string.action_pause, new Runnable() {
            public void run() {
                if (player != null) player.toggle();
            }
        });
        controls.addView(toggle);
        controls.addView(button(R.drawable.ic_skip_next, R.string.action_next, new Runnable() {
            public void run() {
                if (player != null) player.next();
            }
        }));
        controls.addView(button(R.drawable.ic_close, R.string.action_close, new Runnable() {
            public void run() {
                finish();
            }
        }));
        controls.setVisibility(View.GONE);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(controls, clp);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showControls();
            }
        });
        setContentView(root);
        hideSystemBars();
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END); // no marquee: it would redraw all the time
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private ImageButton button(int icon, int label, final Runnable action) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(getString(label));
        b.setBackgroundResource(R.drawable.round_button_bg);
        Ui.tint(b, 0xFFFFFFFF);
        int size = Ui.dp(this, 64);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = lp.rightMargin = Ui.dp(this, 8);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
                showControls(); // keep them up a little longer after a press
            }
        });
        return b;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlayerService.class), connection, BIND_AUTO_CREATE);
        handler.postDelayed(shift, SHIFT_MS);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.removeListener(this);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        player = null;
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            player = ((PlayerService.LocalBinder) service).service();
            bound = true;
            player.addListener(StandActivity.this);
            onPlayerState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            player = null;
        }
    };

    // ------------------------------------------------------------------ player

    @Override
    public void onPlayerState() {
        Song s = player == null ? null : player.current();
        if (s == null) return;
        if (!s.videoId.equals(shownId)) {
            shownId = s.videoId;
            title.setText(s.title);
            artist.setText(s.subtitle());
            art.clearColorFilter();
            Images.get(this).load(art, s.thumb, Images.get(this).px(360, 1200), R.drawable.art_placeholder_dark);
        }
        boolean playing = player.wantsToPlay();
        if (playing != shownPlaying) {
            shownPlaying = playing;
            toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    @Override
    public void onQueueChanged() { }

    @Override
    public void onPlayerError(String message) { }

    // ------------------------------------------------------------------ controls, bars, burn-in

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            AlphaAnimation a = new AlphaAnimation(1f, 0f);
            a.setDuration(250);
            controls.startAnimation(a);
            controls.setVisibility(View.GONE);
            hideSystemBars();
        }
    };

    private void showControls() {
        handler.removeCallbacks(hideControls);
        if (controls.getVisibility() != View.VISIBLE) {
            controls.setVisibility(View.VISIBLE);
            AlphaAnimation a = new AlphaAnimation(0f, 1f);
            a.setDuration(200);
            controls.startAnimation(a);
        }
        handler.postDelayed(hideControls, CONTROLS_MS);
    }

    /** Moves the picture a few pixels once a minute, so it does not burn into the screen. */
    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            int max = Ui.dp(StandActivity.this, 10);
            stand.setShift(random.nextInt(2 * max + 1) - max, random.nextInt(2 * max + 1) - max);
            handler.postDelayed(this, SHIFT_MS);
        }
    };

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 19) Kitkat.immersive(getWindow().getDecorView());
        else if (Build.VERSION.SDK_INT >= 14) Ics.lowProfile(getWindow().getDecorView());
    }

    @TargetApi(19)
    private static final class Kitkat {
        static void immersive(View decor) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @TargetApi(14)
    private static final class Ics {
        /** The navigation buttons dim to dots; hiding them would bring them back on every touch. */
        static void lowProfile(View decor) {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && controls.getVisibility() != View.VISIBLE) {
            showControls();
            return true; // the first tap only wakes the controls
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Cover and texts placed by orientation: portrait = cover over the text, landscape = cover
     * on the left and text on the right. Re-laid out when the device turns (no restart needed).
     */
    private static final class StandLayout extends ViewGroup {
        private int shiftX;
        private int shiftY;

        StandLayout(Context c) {
            super(c);
        }

        void setShift(int x, int y) {
            shiftX = x;
            shiftY = y;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            boolean landscape = w > h;
            int gap = Ui.dp(getContext(), 24);
            int artSize = landscape ? Math.min((int) (h * 0.78f), (int) (w * 0.48f)) : Math.min((int) (w * 0.82f), (int) (h * 0.6f));
            View art = getChildAt(0);
            View texts = getChildAt(1);
            art.measure(MeasureSpec.makeMeasureSpec(artSize, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(artSize, MeasureSpec.EXACTLY));
            int textWidth = landscape ? Math.max(0, w - artSize - 3 * gap) : (int) (w * 0.86f);
            texts.measure(MeasureSpec.makeMeasureSpec(textWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            if (texts instanceof LinearLayout) {
                ((LinearLayout) texts).setGravity(landscape ? Gravity.LEFT : Gravity.CENTER_HORIZONTAL);
                for (int i = 0; i < ((LinearLayout) texts).getChildCount(); i++) {
                    ((TextView) ((LinearLayout) texts).getChildAt(i)).setGravity(landscape ? Gravity.LEFT
                            : Gravity.CENTER_HORIZONTAL);
                }
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            int h = b - t;
            boolean landscape = w > h;
            int gap = Ui.dp(getContext(), 24);
            View art = getChildAt(0);
            View texts = getChildAt(1);
            int a = art.getMeasuredWidth();
            int tw = texts.getMeasuredWidth();
            int th = texts.getMeasuredHeight();
            if (landscape) {
                int total = a + gap + tw;
                int left = (w - total) / 2 + shiftX;
                int top = (h - a) / 2 + shiftY;
                art.layout(left, top, left + a, top + a);
                int tl = left + a + gap;
                int tt = (h - th) / 2 + shiftY;
                texts.layout(tl, tt, tl + tw, tt + th);
            } else {
                int total = a + gap + th;
                int top = (h - total) / 2 + shiftY;
                int left = (w - a) / 2 + shiftX;
                art.layout(left, top, left + a, top + a);
                int tl = (w - tw) / 2 + shiftX;
                int tt = top + a + gap;
                texts.layout(tl, tt, tl + tw, tt + th);
            }
        }
    }
}
