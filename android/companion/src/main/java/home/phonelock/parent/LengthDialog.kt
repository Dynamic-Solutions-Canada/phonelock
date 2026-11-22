package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Asks "[title]" with the standard lengths ([GRANT_DURATIONS]), plus "Until a time…" and "Custom length…";
 * [onPick] gets the minutes. Every "for how long" question in the app uses this, so they all offer the same
 * lengths.
 *
 * [maxMinutes] is the longest the receiver accepts (24 hours for most commands; the phone caps some shorter, and
 * would silently cut a longer one): standard lengths beyond it are left out, and a clock time or custom length
 * beyond it is refused with "At most …" rather than shortened. [extra], if given, is one more choice shown first
 * (label and action), e.g. "Allow again now". [note], if given, explains the choice above the lengths.
 */
@Composable
internal fun LengthDialog(
    title: String,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    extra: Pair<String, () -> Unit>? = null,
    note: String? = null,
    maxMinutes: Long = MAX_LENGTH_MINUTES,
) {
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf(false) }
    val tooLong = stringResource(R.string.length_at_most, fmtMinutes(maxMinutes))
    if (custom) {
        CustomLengthDialog(title, maxMinutes, onPick = onPick, onDismiss = onDismiss)
        return
    }
    OptionsDialog(
        title,
        onDismiss,
        note = note,
        options = listOfNotNull(
            extra?.let { (label, action) -> MenuEntry(label, onClick = action) },
            *GRANT_DURATIONS.filter { it <= maxMinutes }.map { m -> MenuEntry(fmtMinutes(m)) { onPick(m) } }.toTypedArray(),
            MenuEntry(stringResource(R.string.apps_until_a_time)) {
                val now = java.util.Calendar.getInstance()
                android.app.TimePickerDialog(
                    ctx,
                    { _, h, min ->
                        val minutes = minutesUntil(h, min)
                        if (minutes <= maxMinutes) {
                            onPick(minutes)
                        } else {
                            android.widget.Toast.makeText(ctx, tooLong, android.widget.Toast.LENGTH_LONG).show()
                        }
                    },
                    now.get(java.util.Calendar.HOUR_OF_DAY),
                    now.get(java.util.Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(ctx),
                ).show()
            },
            MenuEntry(stringResource(R.string.apps_custom_length)) { custom = true },
        ),
    )
}

/** Hours and minutes fields for a length that isn't in the list, up to [maxMinutes]. */
@Composable
private fun CustomLengthDialog(title: String, maxMinutes: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var hours by remember { mutableStateOf("") }
    var mins by remember { mutableStateOf("") }
    val total = (hours.toLongOrNull() ?: 0) * 60 + (mins.toLongOrNull() ?: 0)
    val ok = total in 1..maxMinutes
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        hours,
                        { hours = it.filter(Char::isDigit).take(2) },
                        Modifier.weight(1f),
                        label = { Text(stringResource(R.string.apps_hours)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        mins,
                        { mins = it.filter(Char::isDigit).take(4) },
                        Modifier.weight(1f),
                        label = { Text(stringResource(R.string.apps_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                if (total > maxMinutes) {
                    Text(
                        stringResource(R.string.length_at_most, fmtMinutes(maxMinutes)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(total) }, enabled = ok) { Text(stringResource(R.string.apps_set)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Longest length most commands accept (24 hours: grants, blocks, modes, screen rules). */
internal const val MAX_LENGTH_MINUTES = 24 * 60L

/** Minutes from now until the next [hour]:[minute] (tomorrow if that time has passed today); at least 1. */
private fun minutesUntil(hour: Int, minute: Int): Long {
    val now = java.util.Calendar.getInstance()
    val target = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (!after(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
    }
    return ((target.timeInMillis - now.timeInMillis + 59_999) / 60_000).coerceIn(1, MAX_LENGTH_MINUTES)
}
