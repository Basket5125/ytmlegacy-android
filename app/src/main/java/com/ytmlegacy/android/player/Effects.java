package com.ytmlegacy.android.player;

import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.util.Log;

import com.ytmlegacy.android.data.Prefs;

import java.util.ArrayList;
import java.util.List;

/** Equalizer, bass boost and virtualizer of one MediaPlayer (audiofx, Android 2.3+). */
public final class Effects {

    private static final String TAG = "Effects";
    private Equalizer eq;
    private BassBoost bass;
    private Virtualizer virt;

    /** What the device's equalizer offers, for the settings screen. */
    public static final class Info {
        public int bands;
        public short minLevel;
        public short maxLevel;
        public int[] centerHz = new int[0];
        public final List<String> presets = new ArrayList<String>();
        public boolean bassSupported;
        public boolean virtualizerSupported;
    }

    static Effects attach(int session, Prefs prefs) {
        Effects fx = new Effects();
        try {
            fx.eq = new Equalizer(0, session);
        } catch (Throwable e) {
            Log.i(TAG, "no equalizer: " + e);
        }
        try {
            fx.bass = new BassBoost(0, session);
        } catch (Throwable e) {
            Log.i(TAG, "no bass boost: " + e);
        }
        try {
            fx.virt = new Virtualizer(0, session);
        } catch (Throwable e) {
            Log.i(TAG, "no virtualizer: " + e);
        }
        fx.apply(prefs);
        return fx;
    }

    void apply(Prefs prefs) {
        boolean on = prefs.eqEnabled();
        try {
            if (eq != null) {
                if (on) {
                    int preset = prefs.eqPreset();
                    if (preset >= 0 && preset < eq.getNumberOfPresets()) {
                        eq.usePreset((short) preset);
                    } else {
                        short[] levels = parseBands(prefs.eqBands(), eq.getNumberOfBands());
                        for (short b = 0; b < levels.length; b++) eq.setBandLevel(b, levels[b]);
                    }
                }
                eq.setEnabled(on);
            }
        } catch (Throwable e) {
            Log.w(TAG, "equalizer: " + e);
        }
        try {
            if (bass != null) {
                if (on && bass.getStrengthSupported() && prefs.bassBoost() > 0) {
                    bass.setStrength((short) Math.min(1000, prefs.bassBoost()));
                    bass.setEnabled(true);
                } else {
                    bass.setEnabled(false);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "bass boost: " + e);
        }
        try {
            if (virt != null) {
                if (on && virt.getStrengthSupported() && prefs.virtualizer() > 0) {
                    virt.setStrength((short) Math.min(1000, prefs.virtualizer()));
                    virt.setEnabled(true);
                } else {
                    virt.setEnabled(false);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "virtualizer: " + e);
        }
    }

    void release() {
        try {
            if (eq != null) eq.release();
        } catch (Throwable ignored) {
        }
        try {
            if (bass != null) bass.release();
        } catch (Throwable ignored) {
        }
        try {
            if (virt != null) virt.release();
        } catch (Throwable ignored) {
        }
        eq = null;
        bass = null;
        virt = null;
    }

    Info info() {
        if (eq == null) return null;
        try {
            Info i = new Info();
            i.bands = eq.getNumberOfBands();
            short[] range = eq.getBandLevelRange();
            i.minLevel = range[0];
            i.maxLevel = range[1];
            i.centerHz = new int[i.bands];
            for (short b = 0; b < i.bands; b++) i.centerHz[b] = eq.getCenterFreq(b) / 1000;
            for (short p = 0; p < eq.getNumberOfPresets(); p++) i.presets.add(eq.getPresetName(p));
            i.bassSupported = bass != null && bass.getStrengthSupported();
            i.virtualizerSupported = virt != null && virt.getStrengthSupported();
            return i;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Band levels (millibels) of a preset, to show its curve on the sliders. */
    short[] presetLevels(int preset) {
        if (eq == null) return null;
        try {
            short old = eq.getCurrentPreset();
            eq.usePreset((short) preset);
            short[] levels = new short[eq.getNumberOfBands()];
            for (short b = 0; b < levels.length; b++) levels[b] = eq.getBandLevel(b);
            if (old >= 0) eq.usePreset(old);
            return levels;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Equalizer details without music playing: a silent player lends its audio session. */
    public static Info probe(Prefs prefs) {
        MediaPlayer mp = null;
        Effects fx = null;
        try {
            mp = new MediaPlayer();
            fx = new Effects();
            try {
                fx.eq = new Equalizer(0, mp.getAudioSessionId());
                fx.bass = new BassBoost(0, mp.getAudioSessionId());
                fx.virt = new Virtualizer(0, mp.getAudioSessionId());
            } catch (Throwable e) {
                Log.i(TAG, "probe: " + e);
            }
            return fx.info();
        } catch (Throwable e) {
            return null;
        } finally {
            if (fx != null) fx.release();
            if (mp != null) mp.release();
        }
    }

    public static short[] probePreset(int preset) {
        MediaPlayer mp = null;
        Effects fx = new Effects();
        try {
            mp = new MediaPlayer();
            fx.eq = new Equalizer(0, mp.getAudioSessionId());
            return fx.presetLevels(preset);
        } catch (Throwable e) {
            return null;
        } finally {
            fx.release();
            if (mp != null) mp.release();
        }
    }

    public static short[] parseBands(String s, int count) {
        short[] out = new short[Math.max(0, count)];
        if (s == null || s.length() == 0) return out;
        String[] parts = s.split(",");
        for (int i = 0; i < out.length && i < parts.length; i++) {
            try {
                out[i] = Short.parseShort(parts[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static String joinBands(short[] levels) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) b.append(',');
            b.append(levels[i]);
        }
        return b.toString();
    }
}
