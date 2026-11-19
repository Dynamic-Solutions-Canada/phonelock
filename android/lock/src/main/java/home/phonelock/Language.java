package home.phonelock;

import android.app.LocaleConfig;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import android.util.Log;
import java.util.Locale;

/**
 * The lock app's language, set by a parent from the parent app (the {@code lang} command, or at setup) and
 * stored by Android as this app's per-app language. It changes this app's wording and the event text it sends
 * to parents; other apps on the device keep following the device's own language.
 */
final class Language {
    private Language() {}

    /**
     * Set once this phone has been paired, or had its language chosen, by a lock app that has languages. A
     * phone without it was paired by an older, English-only lock app.
     */
    private static final String SET_UP_KEY = "language_set_up";

    /** Records that this phone's language follows the language-aware rules (see {@link #SET_UP_KEY}). */
    static void markSetUp(Context c) {
        Policy.prefs(c).edit().putBoolean(SET_UP_KEY, true).apply();
    }

    /**
     * Called once after an update: a phone paired by an older, English-only lock app stays in English rather
     * than switching to the device's language unannounced, which would also change the language of the activity
     * its parents read. A parent can pick another language afterwards. Phones set up since, unpaired phones and
     * phones that already have a language are left alone. Returns whether English was pinned.
     */
    static boolean keepEnglishAfterUpdate(Context c) {
        if (Policy.prefs(c).getBoolean(SET_UP_KEY, false) || !Remote.paired(c)) return false;
        if (!current(c).isEmpty()) {
            markSetUp(c);
            return false;
        }

        // The English matching the device's region, so dates and times keep the region's layout where we can.
        // apply() records the flag only once it has worked, so a failure here is retried at the next update.
        String region =
                Resources.getSystem().getConfiguration().getLocales().get(0).getCountry();
        return apply(c, "CA".equals(region) ? "en-CA" : "en-US");
    }

    /**
     * {@code c} with this app's chosen language, for text built in the same moment a language was applied,
     * before Android has updated the app's own resources. Returns {@code c} itself when following the device.
     */
    static Context localized(Context c) {
        LocaleList locales = c.getSystemService(LocaleManager.class).getApplicationLocales();
        if (locales.isEmpty()) return c;
        Configuration conf = new Configuration(c.getResources().getConfiguration());
        conf.setLocales(locales);
        return c.createConfigurationContext(conf);
    }

    /**
     * Uses language {@code tag} (e.g. "es-ES") for this app, or the device's language when {@code tag} is
     * empty. Tags not in res/xml/locales_config.xml are ignored, so a newer parent app can't switch this one to
     * a language it has no wording for. Returns whether anything was applied.
     */
    static boolean apply(Context c, String tag) {
        String wanted = tag == null ? "" : tag.trim();
        if (!wanted.isEmpty() && !offered(c, wanted)) {
            Log.w(Policy.TAG, "language not offered: " + wanted);
            return false;
        }

        LocaleList locales = wanted.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(wanted);
        c.getSystemService(LocaleManager.class).setApplicationLocales(locales);
        markSetUp(c);

        // Android recreates the screens itself; the service's ongoing notification and channel names are
        // re-posted in the new language when it starts again (starting it while running is routine).
        RemoteService.start(c);
        return true;
    }

    /** The language tag picked for this app, or "" when it follows the device. */
    static String current(Context c) {
        LocaleList locales = c.getSystemService(LocaleManager.class).getApplicationLocales();
        return locales.isEmpty() ? "" : locales.get(0).toLanguageTag();
    }

    /** Whether {@code tag} is one of the languages this app has wording for. */
    private static boolean offered(Context c, String tag) {
        LocaleList supported = new LocaleConfig(c).getSupportedLocales();
        if (supported == null) return false;
        Locale wanted = Locale.forLanguageTag(tag);
        for (int i = 0; i < supported.size(); i++) {
            if (supported.get(i).equals(wanted)) return true;
        }
        return false;
    }
}
