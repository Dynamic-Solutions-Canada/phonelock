package home.phonelock.parent

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import java.util.Locale

/**
 * This app's language: the device's own, or one the parent picks. Stored by Android as the app's per-app
 * language (Android 13+, which this app requires), so the choice here and the one in Android's settings
 * (Apps > PhoneLock > Language) are the same setting. Android restarts the screens when it changes.
 */
object AppLanguage {
    /**
     * The languages offered (res/xml/locales_config.xml), sorted by their own names as language lists
     * usually are; Android doesn't keep the file's order.
     */
    fun choices(c: Context): List<Locale> {
        val list = LocaleConfig(c).supportedLocales ?: return emptyList()
        return (0 until list.size()).map { list[it] }.sortedBy(::nameOf)
    }

    /**
     * The phone's own first locale, ignoring the language picked for this app: for things that follow
     * where the parent is rather than what they read, such as phone number layout and the first day of
     * the week.
     */
    fun deviceRegion(): Locale = Resources.getSystem().configuration.locales[0]

    /** The language picked for this app, or null when it follows the device. */
    fun chosen(c: Context): Locale? {
        val locales = c.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else locales[0]
    }

    /**
     * The offered language this app is showing now, as a tag ("fr-CA"): the one picked, or else the
     * closest offered match for the device's language (same language and region, then same language).
     * "" when the device's language isn't offered (the app is then in English by default).
     */
    fun currentTag(c: Context): String {
        chosen(c)?.let { return it.toLanguageTag() }
        val device = deviceRegion()
        // Chinese also needs the same script: Traditional Chinese isn't offered and must not become Simplified.
        val script = { l: Locale -> ULocale.addLikelySubtags(ULocale.forLocale(l)).script }
        val offered = choices(c).filter { it.language != "zh" || script(it) == script(device) }
        val match = offered.firstOrNull { it.language == device.language && it.country == device.country }
            ?: offered.firstOrNull { it.language == device.language }
        return match?.toLanguageTag() ?: ""
    }

    /** Uses [locale] for this app, or the device's language when [locale] is null. */
    fun choose(c: Context, locale: Locale?) {
        val locales = if (locale == null) LocaleList.getEmptyLocaleList() else LocaleList(locale)
        c.getSystemService(LocaleManager::class.java).applicationLocales = locales
    }

    /**
     * [locale]'s name in its own language, e.g. "Français (Canada)", so anyone can find theirs. Chinese
     * names its script ("中文 (简体, 中国)"), since only the Simplified wording is offered.
     */
    fun nameOf(locale: Locale): String {
        val named = if (locale.language == "zh" && locale.script.isEmpty()) {
            Locale.Builder().setLocale(locale).setScript("Hans").build()
        } else {
            locale
        }
        return named.getDisplayName(named).replaceFirstChar { it.titlecase(named) }
    }
}

/**
 * Settings' "Language" section: which language this app uses, and a list to change it. App-wide, unlike
 * the device settings around it, and shown in every build.
 */
@Composable
internal fun LanguageSection() {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val chosen = AppLanguage.chosen(ctx)
    val matchDevice = stringResource(R.string.language_match_device)

    Section(stringResource(R.string.language_title)) {
        ListRow(chosen?.let(AppLanguage::nameOf) ?: matchDevice,
            AnnotatedString(stringResource(R.string.language_detail)),
            leading = { SymbolBadge("🌐") }, onClick = { picking = true }, titleLines = 2)
    }

    if (picking) {
        val options = listOf(MenuEntry(matchDevice) { AppLanguage.choose(ctx, null); picking = false }) +
            AppLanguage.choices(ctx).map { locale ->
                MenuEntry(AppLanguage.nameOf(locale)) { AppLanguage.choose(ctx, locale); picking = false }
            }
        OptionsDialog(stringResource(R.string.language_title), onDismiss = { picking = false }, options = options)
    }
}

/**
 * Settings' language row for the lock app on [dev]: which language their device's PhoneLock uses, and a
 * list to change it. Hidden until the device reports a language, since older lock apps ignore the command.
 */
@Composable
internal fun DeviceLanguageSetting(dev: String, st: PhoneState?) {
    val lang = st?.lang ?: return
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val matchDevice = stringResource(R.string.device_language_match)
    val current = if (lang.isEmpty()) matchDevice else AppLanguage.nameOf(Locale.forLanguageTag(lang))

    ListRow(stringResource(R.string.device_language_title), AnnotatedString(current),
        leading = { SymbolBadge("🌐") }, onClick = { picking = true }, titleLines = 2)

    if (picking) {
        val options = listOf(MenuEntry(matchDevice) { Hub.setDeviceLanguage(dev, ""); picking = false }) +
            AppLanguage.choices(ctx).map { locale ->
                MenuEntry(AppLanguage.nameOf(locale)) {
                    Hub.setDeviceLanguage(dev, locale.toLanguageTag())
                    picking = false
                }
            }
        OptionsDialog(stringResource(R.string.device_language_title), onDismiss = { picking = false },
            note = stringResource(R.string.device_language_note), options = options)
    }
}
