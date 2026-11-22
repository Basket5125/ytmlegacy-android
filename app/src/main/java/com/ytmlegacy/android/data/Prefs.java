package com.ytmlegacy.android.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** All settings, with their defaults, in one place. */
public final class Prefs {

    // Audio quality
    public static final int Q_LOW = 0;
    public static final int Q_NORMAL = 1;
    public static final int Q_HIGH = 2;

    // Repeat
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    // Theme
    public static final int THEME_DARK = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_SYSTEM = 2;
    public static final int THEME_OLED = 3;

    // Container compatibility
    public static final int COMPAT_AUTO = 0;
    public static final int COMPAT_CLASSIC = 1;
    public static final int COMPAT_DIRECT = 2;

    /** Opus in WebM is only reliable in MediaPlayer from Android 8. */
    public static final int OPUS_MIN_SDK = 26;
    /** Fragmented MP4 is played by the AOSP MediaPlayer from Android 4.4 (verified on 2.3/4.4/5.0). */
    public static final int FMP4_MIN_SDK = 19;

    public static final String DEFAULT_CLIENT_VERSION = "1.20260720.01.00";

    private static Prefs instance;
    private final SharedPreferences p;

    private Prefs(Context context) {
        p = context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context context) {
        if (instance == null) instance = new Prefs(context);
        return instance;
    }

    public SharedPreferences raw() {
        return p;
    }

    private void put(String key, Object value) {
        SharedPreferences.Editor e = p.edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else if (value instanceof Long) e.putLong(key, (Long) value);
        else e.putString(key, String.valueOf(value));
        e.apply();
    }

    // ---------------------------------------------------------------- playback

    public int quality() { return p.getInt("quality", Q_NORMAL); }
    public void setQuality(int v) { put("quality", v); }

    /** Quality on mobile data: -1 = same as Wi-Fi. */
    public int mobileQuality() { return p.getInt("mobile_quality", -1); }
    public void setMobileQuality(int v) { put("mobile_quality", v); }

    public int downloadQuality() { return p.getInt("download_quality", Q_NORMAL); }
    public void setDownloadQuality(int v) { put("download_quality", v); }

    public static boolean opusSupported() { return Build.VERSION.SDK_INT >= OPUS_MIN_SDK; }

    public boolean crossfade() { return p.getBoolean("crossfade", false); }
    public void setCrossfade(boolean v) { put("crossfade", v); }
    public int crossfadeSeconds() { return p.getInt("crossfade_sec", 5); }
    public void setCrossfadeSeconds(int v) { put("crossfade_sec", v); }

    public boolean normalize() { return p.getBoolean("normalize", true); }
    public void setNormalize(boolean v) { put("normalize", v); }

    public boolean pauseOnUnplug() { return p.getBoolean("pause_unplug", true); }
    public void setPauseOnUnplug(boolean v) { put("pause_unplug", v); }

    /** Lower the volume (instead of pausing) when another app speaks briefly. */
    public boolean duck() { return p.getBoolean("duck", true); }
    public void setDuck(boolean v) { put("duck", v); }

    public boolean resumeAfterCall() { return p.getBoolean("resume_focus", true); }
    public void setResumeAfterCall(boolean v) { put("resume_focus", v); }

    public int repeat() { return p.getInt("repeat", REPEAT_OFF); }
    public void setRepeat(int v) { put("repeat", v); }
    public boolean shuffle() { return p.getBoolean("shuffle", false); }
    public void setShuffle(boolean v) { put("shuffle", v); }

    /** Keep playing similar music (infinite radio) when the queue ends. */
    public boolean autoRadio() { return p.getBoolean("auto_radio", true); }
    public void setAutoRadio(boolean v) { put("auto_radio", v); }

    public int compat() { return p.getInt("compat", COMPAT_AUTO); }
    public void setCompat(int v) { put("compat", v); }
    /** Set when this device's player refused a fragmented MP4. */
    public boolean fmp4Broken() { return p.getBoolean("fmp4_broken", false); }
    public void setFmp4Broken(boolean v) { put("fmp4_broken", v); }

    /** True when audio must be repacked into a classic MP4 before MediaPlayer sees it. */
    public boolean needClassicMp4() {
        int c = compat();
        if (c == COMPAT_CLASSIC) return true;
        if (c == COMPAT_DIRECT) return false;
        return Build.VERSION.SDK_INT < FMP4_MIN_SDK || fmp4Broken();
    }

    public boolean lockScreenArt() { return p.getBoolean("lock_art", true); }
    public void setLockScreenArt(boolean v) { put("lock_art", v); }

    // ---------------------------------------------------------------- equalizer

    public boolean eqEnabled() { return p.getBoolean("eq_on", false); }
    public void setEqEnabled(boolean v) { put("eq_on", v); }
    /** Preset index, or -1 for custom band levels. */
    public int eqPreset() { return p.getInt("eq_preset", -1); }
    public void setEqPreset(int v) { put("eq_preset", v); }
    public String eqBands() { return p.getString("eq_bands", ""); }
    public void setEqBands(String v) { put("eq_bands", v); }
    public int bassBoost() { return p.getInt("bass", 0); }
    public void setBassBoost(int v) { put("bass", v); }
    public int virtualizer() { return p.getInt("virt", 0); }
    public void setVirtualizer(int v) { put("virt", v); }

    // ---------------------------------------------------------------- cache

    public boolean cacheEnabled() { return p.getBoolean("cache_on", true); }
    public void setCacheEnabled(boolean v) { put("cache_on", v); }
    public int cacheMb() { return p.getInt("cache_mb", 300); }
    public void setCacheMb(int v) { put("cache_mb", v); }
    /** Tracks of the queue fetched ahead of time. */
    public int prefetchCount() { return p.getInt("prefetch", 2); }
    public void setPrefetchCount(int v) { put("prefetch", v); }
    public boolean prefetchWifiOnly() { return p.getBoolean("prefetch_wifi", false); }
    public void setPrefetchWifiOnly(boolean v) { put("prefetch_wifi", v); }

    // ---------------------------------------------------------------- downloads

    public boolean downloadWifiOnly() { return p.getBoolean("dl_wifi", true); }
    public void setDownloadWifiOnly(boolean v) { put("dl_wifi", v); }
    public boolean downloadExternal() { return p.getBoolean("dl_external", false); }
    public void setDownloadExternal(boolean v) { put("dl_external", v); }

    // ---------------------------------------------------------------- look

    public int theme() { return p.getInt("theme", THEME_DARK); }
    public void setTheme(int v) { put("theme", v); }
    /** UI language: "" = the phone's, else "en"/"pl". */
    public String appLanguage() { return p.getString("app_lang", ""); }
    public void setAppLanguage(String v) { put("app_lang", v); }

    /** Cover-art sharpness: 0 low, 1 normal, 2 high, 3 maximum. */
    public static final float[] IMAGE_FACTORS = {0.6f, 1f, 1.5f, 2f};
    public int imageQualityWifi() { return p.getInt("img_wifi", 2); }
    public void setImageQualityWifi(int v) { put("img_wifi", v); }
    public int imageQualityMobile() { return p.getInt("img_mobile", 1); }
    public void setImageQualityMobile(int v) { put("img_mobile", v); }

    public int startTab() { return p.getInt("start_tab", 0); }
    public void setStartTab(int v) { put("start_tab", v); }
    public boolean keepScreenOn() { return p.getBoolean("screen_on", false); }
    public void setKeepScreenOn(boolean v) { put("screen_on", v); }

    // ---------------------------------------------------------------- content / privacy

    public String language() { return p.getString("hl", defaultLanguage()); }
    public void setLanguage(String v) { put("hl", v); }
    public String region() { return p.getString("gl", defaultRegion()); }
    public void setRegion(String v) { put("gl", v); }

    public boolean searchHistory() { return p.getBoolean("search_history", true); }
    public void setSearchHistory(boolean v) { put("search_history", v); }
    public boolean playHistory() { return p.getBoolean("play_history", true); }
    public void setPlayHistory(boolean v) { put("play_history", v); }

    public String clientVersion() {
        String v = p.getString("client_version", "");
        return v.trim().length() == 0 ? DEFAULT_CLIENT_VERSION : v.trim();
    }
    public void setClientVersion(String v) { put("client_version", v); }

    public String visitorData() { return p.getString("visitor", ""); }
    public long visitorTime() { return p.getLong("visitor_at", 0); }
    public void setVisitorData(String v) {
        SharedPreferences.Editor e = p.edit();
        e.putString("visitor", v).putLong("visitor_at", System.currentTimeMillis()).apply();
    }

    public boolean askedNotifications() { return p.getBoolean("asked_notif", false); }
    public void setAskedNotifications() { put("asked_notif", true); }

    private static String defaultLanguage() {
        String l = java.util.Locale.getDefault().getLanguage();
        return l == null || l.length() == 0 ? "en" : l;
    }

    private static String defaultRegion() {
        String c = java.util.Locale.getDefault().getCountry();
        return c == null || c.length() != 2 ? "US" : c;
    }
}
