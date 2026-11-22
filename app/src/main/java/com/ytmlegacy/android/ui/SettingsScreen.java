package com.ytmlegacy.android.ui;

import android.content.DialogInterface;
import android.os.Build;
import android.os.Environment;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.data.Prefs;
import com.ytmlegacy.android.net.Tls;
import com.ytmlegacy.android.player.AudioCache;
import com.ytmlegacy.android.player.PlayerService;
import com.ytmlegacy.android.yt.InnerTube;

/** Settings. Options a phone's Android cannot do are shown greyed out with the version needed. */
public final class SettingsScreen extends Screen {

    private static final String[] LANGUAGES = {"en", "pl", "de", "fr", "es", "it", "pt", "nl", "sv", "cs", "uk", "ru",
            "tr", "ja", "ko"};
    private static final String[] LANGUAGE_NAMES = {"English", "Polski", "Deutsch", "Français", "Español", "Italiano",
            "Português", "Nederlands", "Svenska", "Čeština", "Українська", "Русский", "Türkçe", "日本語", "한국어"};
    private static final String[] REGIONS = {"US", "PL", "GB", "DE", "FR", "ES", "IT", "NL", "SE", "CZ", "UA", "BR",
            "MX", "CA", "AU", "IN", "JP", "KR", "TR"};
    private static final int[] CACHE_MB = {100, 300, 500, 1000, 2000};

    private Prefs prefs;
    private LinearLayout box;

    @Override
    public String title() {
        return act.getString(R.string.tab_settings);
    }

    @Override
    protected View onCreateView(LayoutInflater inflater, ViewGroup parent) {
        prefs = Prefs.get(act);
        ScrollView scroll = new ScrollView(act);
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, Ui.dp(act, 16));
        scroll.addView(box);
        build();
        return scroll;
    }

    @Override
    public void onShown() {
        build();
    }

    private void changed() {
        PlayerService p = act.player();
        if (p != null) p.settingsChanged();
        build();
    }

    private String[] qualities(boolean withSame) {
        String[] q = {act.getString(R.string.q_low), act.getString(R.string.q_normal), act.getString(R.string.q_high)};
        if (!withSame) return q;
        return new String[]{act.getString(R.string.q_same), q[0], q[1], q[2]};
    }

    private void build() {
        if (box == null) return;
        box.removeAllViews();
        final String needs8 = act.getString(R.string.requires_android, "8.0");

        header(R.string.set_playback);
        final String[] q = qualities(false);
        choice(act.getString(R.string.set_quality), q, prefs.quality(), Prefs.opusSupported() ? null : new int[]{2},
                needs8, null, new Chosen() {
                    public void chosen(int i) {
                        prefs.setQuality(i);
                        changed();
                    }
                });
        choice(act.getString(R.string.set_quality_mobile), qualities(true), prefs.mobileQuality() + 1,
                Prefs.opusSupported() ? null : new int[]{3}, needs8, null, new Chosen() {
                    public void chosen(int i) {
                        prefs.setMobileQuality(i - 1);
                        changed();
                    }
                });
        toggle(act.getString(R.string.set_crossfade), act.getString(R.string.set_crossfade_sum), prefs.crossfade(), true, null,
                new Toggled() {
                    public void toggled(boolean on) {
                        prefs.setCrossfade(on);
                        changed();
                    }
                });
        if (prefs.crossfade()) {
            slider(act.getString(R.string.set_crossfade_len, prefs.crossfadeSeconds()), 1, 12, prefs.crossfadeSeconds(),
                    new Slid() {
                        public void slid(int v, TextView label) {
                            prefs.setCrossfadeSeconds(v);
                            label.setText(act.getString(R.string.set_crossfade_len, v));
                        }
                    });
        }
        toggle(act.getString(R.string.set_normalize), act.getString(R.string.set_normalize_sum), prefs.normalize(), true, null,
                new Toggled() {
                    public void toggled(boolean on) {
                        prefs.setNormalize(on);
                        changed();
                    }
                });
        action(act.getString(R.string.set_equalizer), act.getString(R.string.set_equalizer_sum), new Runnable() {
            public void run() {
                act.push(new EqualizerScreen());
            }
        });
        toggle(act.getString(R.string.set_auto_radio), act.getString(R.string.set_auto_radio_sum), prefs.autoRadio(), true,
                null, new Toggled() {
                    public void toggled(boolean on) {
                        prefs.setAutoRadio(on);
                    }
                });
        toggle(act.getString(R.string.set_unplug), null, prefs.pauseOnUnplug(), true, null, new Toggled() {
            public void toggled(boolean on) {
                prefs.setPauseOnUnplug(on);
            }
        });
        toggle(act.getString(R.string.set_duck), act.getString(R.string.set_duck_sum), prefs.duck(), true, null,
                new Toggled() {
                    public void toggled(boolean on) {
                        prefs.setDuck(on);
                    }
                });
        toggle(act.getString(R.string.set_resume), null, prefs.resumeAfterCall(), true, null, new Toggled() {
            public void toggled(boolean on) {
                prefs.setResumeAfterCall(on);
            }
        });
        boolean lockOk = Build.VERSION.SDK_INT >= 14;
        toggle(act.getString(R.string.set_lock_art), null, prefs.lockScreenArt(), lockOk,
                lockOk ? null : act.getString(R.string.requires_android, "4.0"), new Toggled() {
                    public void toggled(boolean on) {
                        prefs.setLockScreenArt(on);
                    }
                });
        String[] compat = {act.getString(R.string.compat_auto), act.getString(R.string.compat_classic),
                act.getString(R.string.compat_direct)};
        String compatSummary = prefs.compat() == Prefs.COMPAT_AUTO && prefs.needClassicMp4()
                ? act.getString(R.string.compat_sum_detected) : null;
        choice(act.getString(R.string.set_compat), compat, prefs.compat(), null, null, compatSummary, new Chosen() {
            public void chosen(int i) {
                prefs.setCompat(i);
                if (i == Prefs.COMPAT_AUTO) prefs.setFmp4Broken(false);
                changed();
            }
        });

        header(R.string.set_cache);
        toggle(act.getString(R.string.set_cache_on), act.getString(R.string.set_cache_on_sum), prefs.cacheEnabled(), true,
                null, new Toggled() {
                    public void toggled(boolean on) {
                        prefs.setCacheEnabled(on);
                        AudioCache.get(act).trim();
                        changed();
                    }
                });
        String[] sizes = new String[CACHE_MB.length];
        int sel = 1;
        for (int i = 0; i < CACHE_MB.length; i++) {
            sizes[i] = CACHE_MB[i] >= 1000 ? (CACHE_MB[i] / 1000) + " GB" : CACHE_MB[i] + " MB";
            if (CACHE_MB[i] == prefs.cacheMb()) sel = i;
        }
        if (prefs.cacheEnabled()) {
            choice(act.getString(R.string.set_cache_size), sizes, sel, null, null, null, new Chosen() {
                public void chosen(int i) {
                    prefs.setCacheMb(CACHE_MB[i]);
                    AudioCache.get(act).trim();
                    build();
                }
            });
        }
        String[] ahead = {act.getString(R.string.set_prefetch_off), act.getString(R.string.set_prefetch_n, 1),
                act.getString(R.string.set_prefetch_n, 2), act.getString(R.string.set_prefetch_n, 3)};
        choice(act.getString(R.string.set_prefetch), ahead, Math.min(3, prefs.prefetchCount()), null, null, null, new Chosen() {
            public void chosen(int i) {
                prefs.setPrefetchCount(i);
                changed();
            }
        });
        toggle(act.getString(R.string.set_prefetch_wifi), null, prefs.prefetchWifiOnly(), true, null, new Toggled() {
            public void toggled(boolean on) {
                prefs.setPrefetchWifiOnly(on);
            }
        });
        action(act.getString(R.string.set_cache_clear),
                act.getString(R.string.set_cache_used, Ui.size(AudioCache.get(act).sizeBytes())), new Runnable() {
                    public void run() {
                        AudioCache.get(act).clear();
                        Ui.toast(act, R.string.cleared);
                        build();
                    }
                });
        action(act.getString(R.string.set_images_clear),
                act.getString(R.string.set_cache_used, Ui.size(Images.get(act).diskBytes())), new Runnable() {
                    public void run() {
                        Images.get(act).clearDisk();
                        Ui.toast(act, R.string.cleared);
                        build();
                    }
                });

        header(R.string.set_downloads);
        choice(act.getString(R.string.set_quality_download), q, prefs.downloadQuality(),
                Prefs.opusSupported() ? null : new int[]{2}, needs8, null, new Chosen() {
                    public void chosen(int i) {
                        prefs.setDownloadQuality(i);
                        build();
                    }
                });
        toggle(act.getString(R.string.set_dl_wifi), null, prefs.downloadWifiOnly(), true, null, new Toggled() {
            public void toggled(boolean on) {
                prefs.setDownloadWifiOnly(on);
            }
        });
        boolean external = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        toggle(act.getString(R.string.set_dl_external), act.getString(R.string.set_dl_external_sum),
                prefs.downloadExternal(), external, null, new Toggled() {
                    public void toggled(boolean on) {
                        prefs.setDownloadExternal(on);
                    }
                });

        header(R.string.set_look);
        String[] langNames = {act.getString(R.string.lang_system), "English", "Polski"};
        int langIndex = indexOf(com.ytmlegacy.android.data.Lang.CODES, prefs.appLanguage());
        choice(act.getString(R.string.set_app_language), langNames, Math.max(0, langIndex), null, null, null,
                new Chosen() {
                    public void chosen(int i) {
                        String code = com.ytmlegacy.android.data.Lang.CODES[i];
                        if (code.equals(prefs.appLanguage())) return;
                        prefs.setAppLanguage(code);
                        com.ytmlegacy.android.data.Lang.applyToApp(act.getApplicationContext());
                        act.restart();
                    }
                });
        boolean systemTheme = Build.VERSION.SDK_INT >= 29;
        // Indexes are the Prefs.THEME_* values.
        String[] themes = {act.getString(R.string.theme_dark), act.getString(R.string.theme_light),
                act.getString(R.string.theme_system), act.getString(R.string.theme_oled)};
        choice(act.getString(R.string.set_theme), themes, prefs.theme(), systemTheme ? null : new int[]{2},
                act.getString(R.string.requires_android, "10"), null, new Chosen() {
                    public void chosen(int i) {
                        if (i == prefs.theme()) return;
                        prefs.setTheme(i);
                        act.restart();
                    }
                });
        String[] imageQ = {act.getString(R.string.img_low), act.getString(R.string.img_normal),
                act.getString(R.string.img_high), act.getString(R.string.img_max)};
        choice(act.getString(R.string.set_img_wifi), imageQ, prefs.imageQualityWifi(), null, null, null, new Chosen() {
            public void chosen(int i) {
                prefs.setImageQualityWifi(i);
                Images.get(act).clearMemory();
                build();
            }
        });
        choice(act.getString(R.string.set_img_mobile), imageQ, prefs.imageQualityMobile(), null, null, null,
                new Chosen() {
                    public void chosen(int i) {
                        prefs.setImageQualityMobile(i);
                        Images.get(act).clearMemory();
                        build();
                    }
                });
        String[] tabs = {act.getString(R.string.tab_home), act.getString(R.string.tab_explore),
                act.getString(R.string.tab_library)};
        choice(act.getString(R.string.set_start_tab), tabs, Math.min(2, prefs.startTab()), null, null, null, new Chosen() {
            public void chosen(int i) {
                prefs.setStartTab(i);
                build();
            }
        });
        toggle(act.getString(R.string.set_screen_on), null, prefs.keepScreenOn(), true, null, new Toggled() {
            public void toggled(boolean on) {
                prefs.setKeepScreenOn(on);
            }
        });

        header(R.string.set_content);
        int lang = indexOf(LANGUAGES, prefs.language());
        choice(act.getString(R.string.set_language), LANGUAGE_NAMES, lang, null, null,
                lang < 0 ? prefs.language() : null, new Chosen() {
                    public void chosen(int i) {
                        prefs.setLanguage(LANGUAGES[i]);
                        build();
                    }
                });
        int region = indexOf(REGIONS, prefs.region());
        choice(act.getString(R.string.set_region), REGIONS, region, null, null, region < 0 ? prefs.region() : null,
                new Chosen() {
                    public void chosen(int i) {
                        prefs.setRegion(REGIONS[i]);
                        build();
                    }
                });

        header(R.string.set_privacy);
        toggle(act.getString(R.string.set_search_history), null, prefs.searchHistory(), true, null, new Toggled() {
            public void toggled(boolean on) {
                prefs.setSearchHistory(on);
                if (!on) Library.get(act).clearSearches();
            }
        });
        action(act.getString(R.string.set_clear_search), null, new Runnable() {
            public void run() {
                Library.get(act).clearSearches();
                Ui.toast(act, R.string.cleared);
            }
        });
        toggle(act.getString(R.string.set_play_history), null, prefs.playHistory(), true, null, new Toggled() {
            public void toggled(boolean on) {
                prefs.setPlayHistory(on);
                if (!on) Library.get(act).clearHistory();
            }
        });
        action(act.getString(R.string.clear_history), null, new Runnable() {
            public void run() {
                Library.get(act).clearHistory();
                Ui.toast(act, R.string.cleared);
            }
        });
        action(act.getString(R.string.set_reset_visitor), act.getString(R.string.set_reset_visitor_sum), new Runnable() {
            public void run() {
                prefs.setVisitorData("");
                new Task<String>() {
                    @Override
                    protected String work() {
                        return InnerTube.get(act).visitorData(true);
                    }

                    @Override
                    protected void done(String r) {
                        Ui.toast(act, R.string.done);
                    }
                }.start();
            }
        });

        header(R.string.set_advanced);
        action(act.getString(R.string.set_client_version), prefs.clientVersion(), new Runnable() {
            public void run() {
                final EditText field = Ui.field(act, prefs.clientVersion(), Prefs.DEFAULT_CLIENT_VERSION);
                Ui.dialog(act).setTitle(R.string.set_client_version).setView(Ui.padded(act, field))
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                prefs.setClientVersion(field.getText().toString().trim());
                                build();
                            }
                        }).setNegativeButton(R.string.cancel, null).show();
            }
        });

        header(R.string.set_about);
        String version = "";
        try {
            version = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView about = new TextView(act);
        about.setText(act.getString(R.string.about_text, version, Build.VERSION.RELEASE, Tls.provider()));
        about.setTextColor(Ui.attrColor(act, R.attr.ytmText2));
        about.setTextSize(13);
        int p = Ui.dp(act, 16);
        about.setPadding(p, Ui.dp(act, 8), p, Ui.dp(act, 8));
        box.addView(about);
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equalsIgnoreCase(v)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ row builders

    interface Toggled {
        void toggled(boolean on);
    }

    interface Chosen {
        void chosen(int index);
    }

    interface Slid {
        void slid(int value, TextView label);
    }

    private void header(int title) {
        View v = LayoutInflater.from(act).inflate(R.layout.row_header, box, false);
        ((TextView) v.findViewById(R.id.header_title)).setText(act.getString(title).toUpperCase(java.util.Locale.getDefault()));
        box.addView(v);
    }

    private LinearLayout row(String title, String summary, boolean enabled) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(Ui.attrResource(act, R.attr.ytmItemBackground));
        row.setMinimumHeight(Ui.dp(act, 56));
        int p = Ui.dp(act, 16);
        row.setPadding(p, Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));
        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(Ui.attrColor(act, R.attr.ytmText));
        texts.addView(t);
        if (summary != null && summary.length() > 0) {
            TextView s = new TextView(act);
            s.setText(summary);
            s.setTextSize(13);
            s.setTextColor(Ui.attrColor(act, R.attr.ytmText2));
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setEnabled(enabled);
        if (!enabled) setAlphaCompat(texts);
        box.addView(row);
        return row;
    }

    @SuppressWarnings("deprecation")
    private static void setAlphaCompat(View v) {
        android.view.animation.AlphaAnimation a = new android.view.animation.AlphaAnimation(0.4f, 0.4f);
        a.setDuration(0);
        a.setFillAfter(true);
        v.startAnimation(a);
    }

    private void toggle(String title, String summary, boolean value, boolean enabled, String disabledReason,
                        final Toggled then) {
        LinearLayout row = row(title, enabled ? summary : disabledReason != null ? disabledReason : summary, enabled);
        final CheckBox box = new CheckBox(act);
        box.setButtonDrawable(Ui.attrResource(act, R.attr.ytmCheckBox));
        box.setChecked(value);
        box.setEnabled(enabled);
        box.setClickable(false);
        box.setFocusable(false);
        row.addView(box);
        if (!enabled) return;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                box.setChecked(!box.isChecked());
                then.toggled(box.isChecked());
            }
        });
    }

    /**
     * A single-choice setting. Options listed in {@code disabled} need a newer Android: they are
     * marked with {@code disabledNote} and cannot be picked.
     */
    private void choice(final String title, final String[] options, int selected, final int[] disabled,
                        final String disabledNote, String summaryOverride, final Chosen then) {
        String summary = summaryOverride != null ? summaryOverride
                : selected >= 0 && selected < options.length ? options[selected] : "";
        LinearLayout row = row(title, summary, true);
        final int current = selected;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] labels = options.clone();
                if (disabled != null && disabledNote != null) {
                    for (int d : disabled) if (d < labels.length) labels[d] = labels[d] + " (" + disabledNote + ")";
                }
                Ui.dialog(act).setTitle(title).setSingleChoiceItems(labels, current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (disabled != null) {
                            for (int d : disabled) {
                                if (d == which) {
                                    Ui.toast(act, disabledNote == null ? "" : disabledNote);
                                    return; // not available on this Android
                                }
                            }
                        }
                        dialog.dismiss();
                        then.chosen(which);
                    }
                }).setNegativeButton(R.string.cancel, null).show();
            }
        });
    }

    private void slider(String label, final int min, int max, int value, final Slid then) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(act, 16);
        wrap.setPadding(p, 0, p, Ui.dp(act, 8));
        final TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(Ui.attrColor(act, R.attr.ytmText2));
        wrap.addView(t);
        SeekBar bar = (SeekBar) LayoutInflater.from(act).inflate(R.layout.seekbar, wrap, false);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) then.slid(progress + min, t);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                PlayerService pl = act.player();
                if (pl != null) pl.settingsChanged();
            }
        });
        wrap.addView(bar);
        box.addView(wrap);
    }

    private void action(String title, String summary, final Runnable then) {
        LinearLayout row = row(title, summary, true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                then.run();
            }
        });
    }
}
