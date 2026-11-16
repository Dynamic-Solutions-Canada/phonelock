package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import home.phonelock.parent.adb.AdbDiscovery

/** What the parent has entered so far, kept across the steps. */
private class SetupDraft(existing: Device?) {
    var child by mutableStateOf("")
    var you by mutableStateOf(Hub.parentName.value)
    var pin by mutableStateOf("")
    var pinAgain by mutableStateOf("")
    var code by mutableStateOf("")
    var picked by mutableStateOf<String?>(null)
    var manual by mutableStateOf("")
    var connectPort by mutableStateOf("")
    // New devices end locked down; when fixing one, debugging may still be wanted (e.g. for testing).
    var turnOff by mutableStateOf(existing == null)
}

/**
 * Sets up a child's device from this phone over Wireless debugging ([ChildSetup]), as steps: what to expect,
 * preparing their device, names and PIN, pairing, then live progress. With [existing], it fixes the setup of a
 * device already in this app (re-applies the one-time grants): what it does, then pairing. [onDone] gets the
 * id of the device that was added, or null (also when backing out of the first step).
 */
@Composable
internal fun WifiSetupScreen(existing: Device?, onDone: (String?) -> Unit) {
    val run by ChildSetup.run.collectAsState()
    val current = run
    // Kept while setup runs, so "Try again" returns to the pairing step with everything still filled in.
    val draft = remember { SetupDraft(existing) }
    var step by rememberSaveable { mutableIntStateOf(1) }
    if (current != null) {
        Page(stringResource(if (existing == null) R.string.setup_set_up_new_device else R.string.setup_fix_setup), onBack = {
            // Leaving mid-setup is allowed; it carries on and the result shows next time.
            ChildSetup.reset()
            onDone(current.deviceId)
        }) {
            SetupProgress(current, onRetry = {
                draft.code = "" // each pairing needs a fresh code
                ChildSetup.reset()
            }) {
                ChildSetup.reset()
                onDone(current.deviceId)
            }
        }
        return
    }

    if (existing != null) {
        val heading = stringResource(R.string.setup_fix_setup)
        when (step) {
            1 -> WizardIntro(heading, stringResource(R.string.setup_what_this_does), onBack = { onDone(null) }, onStart = { step = 2 }) {
                Text(stringResource(R.string.setup_fix_explanation, existing.name), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.setup_you_need), style = MaterialTheme.typography.titleSmall)
                Requirements(listOf(
                    stringResource(R.string.setup_need_same_wifi_fix),
                    stringResource(R.string.setup_need_parent_mode),
                    stringResource(R.string.setup_need_developer_options),
                ))
            }
            else -> PairStep(heading, 1, 1, draft, existing, onBack = { step = 1 })
        }
        return
    }

    val heading = stringResource(R.string.setup_set_up_new_device)
    when (step) {
        1 -> WizardIntro(heading, stringResource(R.string.setup_what_to_expect), onBack = { onDone(null) }, onStart = { step = 2 }) {
            StepOutline(listOf(
                stringResource(R.string.setup_outline_prepare_title) to stringResource(R.string.setup_outline_prepare_detail),
                stringResource(R.string.setup_outline_names_title) to stringResource(R.string.setup_outline_names_detail),
                stringResource(R.string.setup_outline_pair_title) to stringResource(R.string.setup_outline_pair_detail),
                stringResource(R.string.setup_outline_install_title) to stringResource(R.string.setup_outline_install_detail),
            ))
            Text(stringResource(R.string.setup_you_need), style = MaterialTheme.typography.titleSmall)
            Requirements(listOf(
                stringResource(R.string.setup_need_device),
                stringResource(R.string.setup_need_same_wifi),
                stringResource(R.string.setup_need_sign_out),
            ))
            Text(stringResource(R.string.setup_nothing_erased), style = MaterialTheme.typography.bodyMedium)
        }
        2 -> WizardStep(heading, 1, 3, stringResource(R.string.setup_outline_prepare_title), onBack = { step = 1 }, onNext = { step = 3 }) {
            StepOutline(listOf(
                stringResource(R.string.setup_connect_wifi_title) to stringResource(R.string.setup_connect_wifi_detail),
                stringResource(R.string.setup_remove_accounts_title) to stringResource(R.string.setup_remove_accounts_detail),
                stringResource(R.string.setup_developer_options_title) to stringResource(R.string.setup_developer_options_detail),
            ))
        }
        3 -> {
            val pinsMatch = draft.pin == draft.pinAgain
            WizardStep(heading, 2, 3, stringResource(R.string.setup_names_and_pin), onBack = { step = 2 },
                nextEnabled = draft.child.isNotBlank() && draft.you.isNotBlank() && draft.pin.length >= 4 && pinsMatch,
                onNext = { step = 4 }) {
                OutlinedTextField(draft.child, { draft.child = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.child_device_name)) },
                    supportingText = { Text(stringResource(R.string.setup_device_name_hint)) })
                OutlinedTextField(draft.you, { draft.you = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.your_name)) },
                    supportingText = { Text(stringResource(R.string.setup_shown_on_device)) })
                PinField(draft.pin, { draft.pin = it }, stringResource(R.string.setup_parent_pin),
                    stringResource(R.string.setup_pin_unlocks))
                PinField(draft.pinAgain, { draft.pinAgain = it }, stringResource(R.string.setup_pin_again),
                    stringResource(if (draft.pinAgain.isNotEmpty() && !pinsMatch) R.string.setup_pin_mismatch else R.string.setup_pin_confirm),
                    isError = draft.pinAgain.isNotEmpty() && !pinsMatch)
            }
        }
        else -> PairStep(heading, 3, 3, draft, null, onBack = { step = 3 })
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, hint: String, isError: Boolean = false) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(8)) }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, supportingText = { Text(hint) }, isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}

/**
 * The pairing step: what to open on their device, the device as found on the network, and its code. Rarely
 * needed options (typing its address, keeping debugging on) are folded away. Starts [ChildSetup].
 */
@Composable
private fun PairStep(heading: String, step: Int, steps: Int, draft: SetupDraft, existing: Device?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Look for devices showing "Pair device with pairing code" on this network.
    val discovery = remember { AdbDiscovery(ctx) }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.close() }
    }
    val found by discovery.found.collectAsState()
    val pairable = found.filter { it.pairing }
    var moreOptions by remember { mutableStateOf(false) }

    // The device to pair: picked from what's showing on the network, or typed as address:port.
    val endpoint = pairable.firstOrNull { it.name == draft.picked } ?: pairable.singleOrNull()
    // A typed address must be complete (host:port); it's never mixed with a found device's port.
    val typedHost = draft.manual.substringBeforeLast(":", "").takeIf { it.isNotEmpty() }
    val typedPort = draft.manual.substringAfterLast(":", "").toIntOrNull()?.takeIf { it in 1..65535 }
    val typing = draft.manual.isNotBlank()
    val host = if (typing) typedHost.takeIf { typedPort != null } else endpoint?.host
    val port = if (typing) typedPort.takeIf { typedHost != null } else endpoint?.port

    WizardStep(heading, step, steps, stringResource(R.string.setup_pair_title), onBack = onBack,
        next = stringResource(if (existing == null) R.string.setup_set_up_button else R.string.setup_fix_setup),
        nextEnabled = host != null && port != null && draft.code.length == 6,
        onNext = {
            discovery.close()
            ChildSetup.start(ChildSetup.Request(host!!, port!!, draft.code, existing?.name ?: draft.child.trim(),
                draft.you.trim(), draft.pin, existing, draft.connectPort.toIntOrNull(), draft.turnOff))
        }) {
        Text(stringResource(R.string.setup_pair_instructions),
            style = MaterialTheme.typography.bodyMedium)
        if (pairable.isEmpty()) {
            Text(stringResource(R.string.setup_looking),
                style = MaterialTheme.typography.bodySmall)
        } else {
            pairable.forEach { e ->
                FilterChip(selected = endpoint == e, onClick = { draft.picked = e.name },
                    label = { Text("${e.name} (${e.host})") })
            }
        }
        OutlinedTextField(draft.code, { draft.code = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text(stringResource(R.string.setup_wifi_pairing_code)) },
            supportingText = { Text(stringResource(R.string.setup_six_digits)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

        TextButton(onClick = { moreOptions = !moreOptions }) {
            Text(stringResource(if (moreOptions) R.string.setup_fewer_options else R.string.setup_more_options))
        }
        if (moreOptions) {
            OutlinedTextField(draft.manual, { draft.manual = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.setup_ip_and_port)) },
                supportingText = { Text(stringResource(R.string.setup_ip_and_port_hint)) })
            OutlinedTextField(draft.connectPort, { draft.connectPort = it.filter(Char::isDigit).take(5) },
                Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.setup_debugging_port)) },
                supportingText = { Text(stringResource(R.string.setup_debugging_port_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.setup_turn_off_debugging_option), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.setup_turn_off_debugging_detail),
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(draft.turnOff, { draft.turnOff = it })
            }
        }
    }
}

@Composable
private fun SetupProgress(run: SetupRun, onRetry: () -> Unit, onClose: () -> Unit) {
    val title = when {
        !run.finished -> R.string.setup_setting_up
        run.failed -> R.string.setup_stopped
        else -> R.string.done
    }
    Section(stringResource(title)) {
        run.steps.forEach { s ->
            val (mark, color) = when (s.state) {
                StepState.WAITING -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                StepState.RUNNING -> "…" to MaterialTheme.colorScheme.primary
                StepState.DONE -> "✓" to MaterialTheme.colorScheme.onSurface
                StepState.SKIPPED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
                StepState.FAILED -> "✗" to MaterialTheme.colorScheme.error
            }
            Row {
                Text(mark, Modifier.width(24.dp), color = color, fontWeight = FontWeight.Bold)
                Column {
                    Text(s.label, color = color, style = MaterialTheme.typography.bodyMedium)
                    s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    run.outcome?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    if (run.finished && !run.failed) Text(
        stringResource(if (run.debuggingOff) R.string.setup_finished_debugging_off else R.string.setup_finished_debugging_on),
        style = MaterialTheme.typography.bodyMedium)
    // Setup takes a couple of minutes: keep this phone awake and in front meanwhile (Android may pause a
    // background app part way through).
    if (!run.finished) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        Text(stringResource(R.string.setup_keep_screen_open), style = MaterialTheme.typography.bodySmall)
    }
    if (run.finished) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (run.failed) {
            Button(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
        } else {
            Button(onClick = onClose) { Text(stringResource(R.string.done)) }
        }
    }
}
