package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Device settings row: how long the device may go without a word before this phone alerts (see
 * [QuietWatch]). Unlike the battery alert, it's this parent's own choice, kept on this phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuietAlertSetting(dev: String) {
    val all by QuietWatch.minutes.collectAsState()
    val current = all[dev] ?: QuietWatch.minutesFor(dev)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.main_quiet_alert_title), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietWatch.CHOICES.forEach { m ->
                FilterChip(selected = current == m, onClick = { QuietWatch.setMinutes(dev, m) },
                    label = {
                        Text(when {
                            m == 0 -> stringResource(R.string.main_off)
                            m < 60 -> stringResource(R.string.duration_minutes, m)
                            else -> stringResource(R.string.duration_hours, m / 60)
                        })
                    })
            }
        }
        Text(stringResource(R.string.main_quiet_alert_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Low-battery alert levels offered to parents, in percent; 0 is "Off". */
private val ALERT_LEVELS = listOf(0, 10, 15, 20, 30)

/**
 * Device settings row: when to get a "Battery low" alert. The level lives on the device, so both parents
 * share it; it can be changed once a full state has said what it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatteryAlertSetting(dev: String, state: PhoneState?) {
    val current = state?.battAlert ?: -1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.main_battery_alert_title), style = MaterialTheme.typography.labelLarge)
        if (current < 0) {
            Text(stringResource(R.string.main_battery_alert_waiting), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ALERT_LEVELS.forEach { level ->
                FilterChip(selected = current == level, onClick = { Hub.setBatteryAlert(dev, level) },
                    label = { Text(if (level == 0) stringResource(R.string.main_off) else stringResource(R.string.main_percent, level)) })
            }
        }
    }
}
