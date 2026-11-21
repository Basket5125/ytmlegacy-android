package com.ytmlegacy.android.data;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

/** The app's own UI language (Settings), independent of the phone's. */
public final class Lang {

    /** Language codes offered in Settings; "" = follow the phone. */
    public static final String[] CODES = {"", "en", "pl"};

    private static Locale systemLocale;

    private Lang() { }

    private static Locale chosen(Context context) {
        if (systemLocale == null) systemLocale = Locale.getDefault();
        String code = Prefs.get(context).appLanguage();
        return code.length() == 0 ? systemLocale : new Locale(code);
    }

    /** For attachBaseContext of activities and services. */
    public static Context wrap(Context base) {
        Locale locale = chosen(base);
        Locale.setDefault(locale);
        if (Build.VERSION.SDK_INT >= 17) return Jb.wrap(base, locale);
        apply(base.getResources(), locale);
        return base;
    }

    /** Updates the application's own resources (toasts, notification channels…). */
    public static void applyToApp(Context app) {
        Locale locale = chosen(app);
        Locale.setDefault(locale);
        apply(app.getResources(), locale);
    }

    @SuppressWarnings("deprecation")
    private static void apply(Resources res, Locale locale) {
        Configuration c = new Configuration(res.getConfiguration());
        c.locale = locale;
        res.updateConfiguration(c, res.getDisplayMetrics());
    }

    @TargetApi(17)
    private static final class Jb {
        static Context wrap(Context base, Locale locale) {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            c.setLocale(locale);
            return base.createConfigurationContext(c);
        }
    }
}
