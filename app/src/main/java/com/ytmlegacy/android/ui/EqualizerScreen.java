package com.ytmlegacy.android.ui;

import android.content.DialogInterface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.player.Effects;
import com.ytmlegacy.android.player.PlayerService;

import java.util.Locale;

/** Equalizer bands and presets, bass boost and virtualizer (android.media.audiofx). */
public final class EqualizerScreen extends Screen {

    private Prefs prefs;
    private LinearLayout box;
    private Effects.Info info;
    private short[] levels;

    @Override
    public String title() {
        return act.getString(R.string.set_equalizer);
    }

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        prefs = Prefs.get(act);
        ScrollView scroll = new ScrollView(act);
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(act, 16);
        box.setPadding(p, Ui.dp(act, 8), p, p);
        scroll.addView(box);
        TextView wait = text(act.getString(R.string.loading), 14, false);
        box.addView(wait);
        new Task<Effects.Info>() {
            @Override
            protected Effects.Info work() {
                PlayerService pl = act.player();
                return pl != null ? pl.effectsInfo() : Effects.probe(prefs);
            }

            @Override
            protected void done(Effects.Info result) {
                if (isDestroyed()) return;
                info = result;
                build();
            }
        }.start();
        return scroll;
    }

    private void apply() {
        PlayerService p = act.player();
        if (p != null) p.settingsChanged();
    }

    private TextView text(String s, int sp, boolean primary) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Ui.attrColor(act, primary ? R.attr.ytmText : R.attr.ytmText2));
        return t;
    }

    private void build() {
        box.removeAllViews();
        if (info == null || info.bands == 0) {
            box.addView(text(act.getString(R.string.eq_unavailable), 15, true));
            return;
        }
        if (levels == null) levels = Effects.parseBands(prefs.eqBands(), info.bands);

        LinearLayout on = new LinearLayout(act);
        on.setGravity(Gravity.CENTER_VERTICAL);
        on.setMinimumHeight(Ui.dp(act, 52));
        on.setBackgroundResource(Ui.attrResource(act, R.attr.ytmItemBackground));
        TextView label = text(act.getString(R.string.eq_enable), 16, true);
        on.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final CheckBox check = new CheckBox(act);
        check.setButtonDrawable(Ui.attrResource(act, R.attr.ytmCheckBox));
        check.setChecked(prefs.eqEnabled());
        check.setClickable(false);
        check.setFocusable(false);
        on.addView(check);
        on.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setEqEnabled(!prefs.eqEnabled());
                apply();
                build();
            }
        });
        box.addView(on);

        final boolean enabled = prefs.eqEnabled();
        // Preset
        final int preset = prefs.eqPreset();
        TextView presetRow = text(act.getString(R.string.eq_preset) + ": "
                + (preset >= 0 && preset < info.presets.size() ? info.presets.get(preset) : act.getString(R.string.eq_custom)),
                15, true);
        presetRow.setMinHeight(Ui.dp(act, 48));
        presetRow.setGravity(Gravity.CENTER_VERTICAL);
        presetRow.setBackgroundResource(Ui.attrResource(act, R.attr.ytmItemBackground));
        presetRow.setEnabled(enabled);
        presetRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] names = new String[info.presets.size() + 1];
                names[0] = act.getString(R.string.eq_custom);
                for (int i = 0; i < info.presets.size(); i++) names[i + 1] = info.presets.get(i);
                Ui.dialog(act).setTitle(R.string.eq_preset).setSingleChoiceItems(names, preset + 1,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                prefs.setEqPreset(which - 1);
                                if (which > 0) {
                                    final int chosen = which - 1;
                                    new Task<short[]>() {
                                        @Override
                                        protected short[] work() {
                                            PlayerService pl = act.player();
                                            return pl != null ? pl.presetLevels(chosen) : Effects.probePreset(chosen);
                                        }

                                        @Override
                                        protected void done(short[] l) {
                                            if (l != null && l.length == info.bands) levels = l;
                                            apply();
                                            build();
                                        }
                                    }.start();
                                } else {
                                    apply();
                                    build();
                                }
                            }
                        }).show();
            }
        });
        if (!enabled) dim(presetRow);
        box.addView(presetRow);

        // Bands
        for (int b = 0; b < info.bands; b++) {
            final int band = b;
            final TextView l = text(bandLabel(b), 13, false);
            box.addView(l);
            SeekBar bar = (SeekBar) LayoutInflater.from(act).inflate(R.layout.seekbar, box, false);
            bar.setMax(info.maxLevel - info.minLevel);
            bar.setProgress(levels[b] - info.minLevel);
            bar.setEnabled(enabled);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    levels[band] = (short) (progress + info.minLevel);
                    l.setText(bandLabel(band));
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) { }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    prefs.setEqPreset(-1);
                    prefs.setEqBands(Effects.joinBands(levels));
                    apply();
                    build();
                }
            });
            box.addView(bar);
        }
        if (info.bassSupported) strength(act.getString(R.string.eq_bass), prefs.bassBoost(), enabled, true);
        if (info.virtualizerSupported) strength(act.getString(R.string.eq_virtualizer), prefs.virtualizer(), enabled, false);

        TextView reset = new TextView(act);
        reset.setText(R.string.eq_reset);
        reset.setTextColor(0xFF33B5E5);
        reset.setTextSize(14);
        reset.setMinHeight(Ui.dp(act, 48));
        reset.setGravity(Gravity.CENTER_VERTICAL);
        reset.setBackgroundResource(Ui.attrResource(act, R.attr.ytmItemBackground));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                levels = new short[info.bands];
                prefs.setEqPreset(-1);
                prefs.setEqBands(Effects.joinBands(levels));
                prefs.setBassBoost(0);
                prefs.setVirtualizer(0);
                apply();
                build();
            }
        });
        box.addView(reset);
    }

    private void strength(String title, int value, boolean enabled, final boolean bass) {
        final TextView l = text(title + ": " + value / 10 + "%", 13, false);
        box.addView(l);
        SeekBar bar = (SeekBar) LayoutInflater.from(act).inflate(R.layout.seekbar, box, false);
        bar.setMax(1000);
        bar.setProgress(value);
        bar.setEnabled(enabled);
        final String name = title;
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) l.setText(name + ": " + progress / 10 + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (bass) prefs.setBassBoost(s.getProgress());
                else prefs.setVirtualizer(s.getProgress());
                apply();
            }
        });
        box.addView(bar);
    }

    private String bandLabel(int b) {
        int hz = info.centerHz[b];
        String f = hz >= 1000 ? String.format(Locale.US, "%.1f kHz", hz / 1000f) : hz + " Hz";
        return f + "   " + String.format(Locale.US, "%+.1f dB", levels[b] / 100f);
    }

    private static void dim(View v) {
        android.view.animation.AlphaAnimation a = new android.view.animation.AlphaAnimation(0.4f, 0.4f);
        a.setDuration(0);
        a.setFillAfter(true);
        v.startAnimation(a);
    }
}
