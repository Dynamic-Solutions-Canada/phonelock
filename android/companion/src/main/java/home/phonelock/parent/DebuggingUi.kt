package home.phonelock.parent

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Longest the phone keeps debugging on (it cuts a longer request to this). */
private const val MAX_DEBUGGING_MINUTES = 4 * 60L

/**
 * Recovery access: turn USB and wireless debugging on for a while on the child's device, for repairs or
 * updates over adb when its own screens can't be used. [onPick] gets the minutes, 0 to turn it off now. Uses the
 * app's usual length choices, up to the phone's 4-hour limit.
 */
@Composable
internal fun DebuggingDialog(name: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    LengthDialog(
        stringResource(R.string.apps_debugging_title),
        onPick = onPick,
        onDismiss = onDismiss,
        note = stringResource(R.string.apps_debugging_note, name),
        extra = stringResource(R.string.apps_debugging_off) to { onPick(0) },
        maxMinutes = MAX_DEBUGGING_MINUTES,
    )
}
