package home.phonelock.parent

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Translated wording for code that has no Compose context: notifications, services, snackbars and
 * formatting helpers. Screens use stringResource() instead, so they redraw when the language changes.
 *
 * Reads the application's resources, which Android switches to the app language chosen in Settings (or
 * in Android's per-app language setting), so wording built after a change uses the new language.
 */
object Strings {
    private lateinit var app: Context

    fun init(c: Context) {
        app = c.applicationContext
    }

    /** The string [id] in the app's current language, with [args] filled into its %1$s-style slots. */
    fun get(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    /** The form of plural [id] that suits [count], with [args] filled in (usually [count] itself). */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        app.resources.getQuantityString(id, count, *args)
}
