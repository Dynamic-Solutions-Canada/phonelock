package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Longest remote parent mode the phone accepts (it cuts a longer request to this). */
private const val MAX_PARENT_MODE_MINUTES = 12 * 60L

/**
 * Confirms remote parent mode and asks for how long, with the app's usual length choices up to the phone's
 * 12-hour limit. It unlocks Settings and the Play Store for whoever holds the device, so the dialog says so; it
 * always ends by itself.
 */
@Composable
internal fun ParentModeDialog(name: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    LengthDialog(
        stringResource(R.string.apps_parent_mode_title, name),
        onPick = onPick,
        onDismiss = onDismiss,
        note = stringResource(R.string.apps_parent_mode_note),
        maxMinutes = MAX_PARENT_MODE_MINUTES,
    )
}

/** New parent PIN, typed twice. 4 to 8 digits, like the setup screen. */
@Composable
internal fun PinDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin.all(Char::isDigit)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    pin,
                    { pin = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.apps_pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    again,
                    { again = it.filter(Char::isDigit).take(8) },
                    label = { Text(stringResource(R.string.apps_pin_again)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = again.isNotEmpty() && again != pin,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Text(stringResource(R.string.apps_pin_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid && pin == again, onClick = { onSave(pin) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Starts limited hours or bedtime by hand: pick which, then how long (the usual lengths, a clock time or a
 * custom length). If the schedule is stricter meanwhile (bedtime starting during manual limited hours), the
 * schedule wins; when it ends, the schedule applies again.
 */
@Composable
internal fun StartModeDialog(name: String, onPick: (String, Long) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf<String?>(null) }
    val chosen = mode
    if (chosen != null) {
        LengthDialog(stringResource(R.string.apps_mode_how_long, modeLabel(chosen)), onPick = { onPick(chosen, it) }, onDismiss = onDismiss)
        return
    }
    OptionsDialog(
        stringResource(R.string.apps_start_mode_title, name),
        onDismiss,
        note = stringResource(R.string.apps_start_mode_note),
        options = listOf(
            MenuEntry(stringResource(R.string.apps_mode_limited)) { mode = "limited" },
            MenuEntry(stringResource(R.string.apps_mode_bedtime)) { mode = "bedtime" },
        ),
    )
}
