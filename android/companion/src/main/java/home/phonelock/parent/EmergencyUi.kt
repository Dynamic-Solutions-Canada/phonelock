package home.phonelock.parent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay

/**
 * The main screen's emergency card for [dev], at the top while an alert is recent: the same panel as the
 * full-screen alert. Above it, while the alarm for [dev] is paused on this phone, a note with Resume, shown
 * even with no alert so a pause is never forgotten. Hidden when there's neither.
 */
@Composable
internal fun EmergencySection(dev: String) {
    val all by EmergencyAlerts.alerts.collectAsState()
    PausedAlarmHint(dev)
    val a = all.firstOrNull { it.dev == dev } ?: return
    EmergencyPanel(a, onClose = null)
}

/** "Emergency alarms from <name> are paused on this phone until <time>", with Resume; gone when it ends. */
@Composable
private fun PausedAlarmHint(dev: String) {
    val paused by EmergencyAlerts.paused.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val until = paused[dev]?.takeIf { SosPause.holds(it, now) } ?: return

    // Hide the note when the pause runs out, without waiting for something else to redraw the screen. At
    // least `until`, so a timer that wakes a moment early still ends it.
    LaunchedEffect(until) {
        delay(until - System.currentTimeMillis())
        now = maxOf(System.currentTimeMillis(), until)
    }
    HintCard(stringResource(R.string.alerts_emergency_paused_hint, Hub.deviceName(dev), time(until))) {
        TextButton(onClick = { EmergencyAlerts.resume(dev) }) { Text(stringResource(R.string.alerts_emergency_resume)) }
    }
}

/**
 * Asks how long to pause the alarm for [a]'s phone on this phone, with the app's usual lengths, a clock time
 * or a custom length. [onDone] closes it, whether a length was picked or not.
 */
@Composable
private fun PauseAlarmDialog(a: EmergencyAlerts.Sos, onDone: () -> Unit) {
    LengthDialog(
        stringResource(R.string.alerts_emergency_pause_title, Hub.deviceName(a.dev)),
        note = stringResource(R.string.alerts_emergency_pause_note),
        onPick = { minutes ->
            EmergencyAlerts.pause(a.dev, SosPause.forMinutes(System.currentTimeMillis(), minutes))
            onDone()
        },
        onDismiss = onDone,
    )
}

/**
 * One emergency alert: who and when, her latest location on a map with the address, battery, her messages,
 * who's on it, and the answers (I'm on it, Call her, Silence, Pause alarm, Dismiss). [onClose] closes the
 * full-screen alert (null on the main screen). [askPause] opens the pause length picker at once (the
 * notification's "Choose time…"); each new [askPause] request is a new [Intent] identity, so it opens again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmergencyPanel(a: EmergencyAlerts.Sos, onClose: (() -> Unit)?, askPause: Any? = null) {
    val ctx = LocalContext.current
    val paused by EmergencyAlerts.paused.collectAsState()
    val pausedUntil = paused[a.dev]?.takeIf { SosPause.holds(it, System.currentTimeMillis()) }
    var pausing by remember(askPause) { mutableStateOf(askPause != null) }

    // Bring Pause back when the pause runs out by itself (the hint above hides at the same moment).
    var tick by remember { mutableLongStateOf(0L) }
    if (pausedUntil != null) {
        LaunchedEffect(pausedUntil) {
            delay(pausedUntil - System.currentTimeMillis())
            tick = maxOf(System.currentTimeMillis(), pausedUntil)
        }
    }
    val places by LocationHistory.places.collectAsState()
    val addresses by LocationHistory.addresses.collectAsState()
    val danger = if (a.cancelled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer
    val onDanger = if (a.cancelled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = danger)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                EmergencyAlerts.title(a),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = onDanger,
            )
            Text(stringResource(R.string.alerts_emergency_pressed_at, time(a.since)), color = onDanger)
            Text(EmergencyAlerts.summary(a), style = MaterialTheme.typography.bodyLarge, color = onDanger)

            // Her trail since she pressed SOS (readings every minute), with her latest position.
            val trail = places[a.dev].orEmpty().filter { it.to >= a.since }.reversed()
            val latest = a.where?.let { LatLng(it.lat, it.lng) }
            if (trail.isNotEmpty() || latest != null) {
                TrailMap(
                    trail,
                    marker = latest,
                    markerTitle = stringResource(R.string.alerts_emergency_latest),
                    selection = a.where,
                    addresses = addresses,
                    now = latest,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!a.cancelled && EmergencyAlerts.me() !in a.acks) {
                    Button(
                        onClick = { EmergencyAlerts.ack(a.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.alerts_emergency_ack)) }
                }
                a.number?.let { n ->
                    OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }
                    }) { Text(stringResource(R.string.alerts_emergency_call_her)) }
                }
                if (a.ringing) OutlinedButton(onClick = { EmergencyAlerts.silence(a.id) }) { Text(stringResource(R.string.alerts_emergency_silence)) }
                // Hidden while paused: the note above the panel has Resume.
                if (pausedUntil == null || tick >= pausedUntil) {
                    OutlinedButton(onClick = { pausing = true }) { Text(stringResource(R.string.alerts_emergency_pause)) }
                }
                TextButton(onClick = {
                    EmergencyAlerts.dismiss(a.id)
                    onClose?.invoke()
                }) { Text(stringResource(R.string.alerts_dismiss)) }
            }
        }
    }
    if (pausing) PauseAlarmDialog(a) { pausing = false }
}

/**
 * The full-screen emergency alert: shown over the lock screen and turning the screen on, like an incoming
 * call. Closing it doesn't silence the alarm; answering does.
 */
class EmergencyActivity : ComponentActivity() {
    /** The intent shown, as state, so a notification tapped while this is open redraws it. */
    private var shown by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        shown = intent
        setContent {
            AppTheme {
                val all by EmergencyAlerts.alerts.collectAsState()
                val current = shown
                val id = current?.getStringExtra("sos")
                val a = all.firstOrNull { it.id == id } ?: all.firstOrNull()
                Column(
                    Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (a == null) {
                        EmptyNote(stringResource(R.string.alerts_emergency_none))
                    } else {
                        // "Choose time…" from the notification: open the pause picker (keyed by this intent).
                        val askPause = current?.takeIf { it.getBooleanExtra("pause", false) }
                        EmergencyPanel(a, onClose = { finish() }, askPause = askPause)
                    }
                    TextButton(onClick = {
                        startActivity(
                            Intent(this@EmergencyActivity, MainActivity::class.java)
                                .putExtra("device", a?.dev).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        finish()
                    }) { Text(stringResource(R.string.alerts_emergency_open_app)) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shown = intent
    }
}
