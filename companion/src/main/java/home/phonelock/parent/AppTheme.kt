package home.phonelock.parent

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * The app's one theme (UI-11): Material 3 in the phone's light or dark mode, with the wallpaper's dynamic
 * colours (always available, since the app needs Android 13). Every activity's content goes inside it, on a
 * full-screen surface, so each screen starts from the same colours and background.
 */
@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}
