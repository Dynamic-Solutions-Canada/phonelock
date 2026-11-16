package home.phonelock.parent

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Recovery access: turn USB and wireless debugging on for a while on the child's device, for repairs or
 * updates over adb when its own screens can't be used. [onPick] gets the minutes, 0 to turn it off now.
 */
@Composable
internal fun DebuggingDialog(name: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    OptionsDialog(stringResource(R.string.apps_debugging_title), onDismiss,
        note = stringResource(R.string.apps_debugging_note, name),
        options = listOf(30L, 60L, 120L).map { m ->
            MenuEntry(stringResource(R.string.apps_debugging_on_for, fmtMinutes(m))) { onPick(m) }
        } + MenuEntry(stringResource(R.string.apps_debugging_off)) { onPick(0) })
}
