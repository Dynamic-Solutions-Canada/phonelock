package home.phonelock.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Add a child's device": first only the choice between setting up a new device and adding one another
 * parent already set up, then that path's steps. A pairing link opened from outside ([initialLink]) goes
 * straight to adding an existing device. [done] gets the added device's id, or null when cancelled.
 */
@Composable
internal fun AddDeviceScreen(initialLink: String?, canCancel: Boolean, done: (String?) -> Unit) {
    // A pairing link opened while this screen is showing switches to adding that device.
    var path by rememberSaveable(initialLink) { mutableStateOf(if (initialLink != null) "existing" else null) }
    when (path) {
        "new" -> WifiSetupScreen(existing = null) { added -> if (added != null) done(added) else path = null }
        "existing" -> AddExistingDevice(initialLink) { added -> if (added != null) done(added) else path = null }
        else -> ChooseHowToAdd(canCancel, onPick = { path = it }, onCancel = { done(null) })
    }
}

/** The two ways to add a device, as cards with what each involves; nothing else on screen. */
@Composable
private fun ChooseHowToAdd(canCancel: Boolean, onPick: (String) -> Unit, onCancel: () -> Unit) {
    if (canCancel) BackHandler(onBack = onCancel)
    Column(
        Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.setup_add_child_device), style = MaterialTheme.typography.headlineSmall)
        LostDevicesHint()
        Text(stringResource(R.string.setup_already_installed_question))
        ChoiceCard(
            stringResource(R.string.setup_set_up_new_device),
            stringResource(R.string.setup_new_device_body),
            stringResource(R.string.setup_new_device_when),
        ) { onPick("new") }
        ChoiceCard(
            stringResource(R.string.setup_add_existing_title),
            stringResource(R.string.setup_add_existing_body),
            stringResource(R.string.setup_add_existing_when),
        ) { onPick("existing") }
        if (canCancel) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, whenToUse: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(24.dp), colors = sectionCardColors()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Text(whenToUse, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Adding a device another parent set up: what to expect, scan (or paste) their pairing code, then names.
 * [done] gets the device id, or null to go back to the choice.
 */
@Composable
private fun AddExistingDevice(initialLink: String?, done: (String?) -> Unit) {
    val heading = stringResource(R.string.setup_add_a_device)
    var link by remember { mutableStateOf(initialLink ?: "") }
    val parsed = Hub.parsePairing(link)
    // The Play build reaches phones only through its hub, so a code for any other server can't be added.
    val usable = parsed != null && BackgroundConnection.accepts(parsed.server)
    // A link opened from outside skips the explanation and the scan, unless it can't be added.
    var step by rememberSaveable {
        mutableIntStateOf(
            if (usable) {
                3
            } else if (parsed != null) {
                2
            } else {
                1
            },
        )
    }
    var scanning by remember { mutableStateOf(false) }
    val parentName by Hub.parentName.collectAsState()
    var you by remember { mutableStateOf(parentName) }
    var deviceName by remember(parsed?.topic) { mutableStateOf(parsed?.name?.takeIf { it != Setup.UNNAMED_DEVICE } ?: "") }

    if (scanning) {
        BackHandler { scanning = false }
        QrScanner(
            onResult = {
                link = it
                scanning = false
                if (Hub.parsePairing(it)?.let { d -> BackgroundConnection.accepts(d.server) } == true) step = 3
            },
            onCancel = { scanning = false },
        )
        return
    }

    when (step) {
        1 -> WizardIntro(heading, stringResource(R.string.setup_what_to_expect), onBack = { done(null) }, onStart = { step = 2 }) {
            StepOutline(
                listOf(
                    stringResource(R.string.setup_get_code_title) to stringResource(R.string.setup_get_code_detail),
                    stringResource(R.string.setup_scan_it_title) to stringResource(R.string.setup_scan_it_detail),
                    stringResource(R.string.setup_name_it_title) to stringResource(R.string.setup_name_it_detail),
                ),
            )
            Text(stringResource(R.string.setup_you_need), style = MaterialTheme.typography.titleSmall)
            Requirements(listOf(stringResource(R.string.setup_need_other_parent)))
        }

        2 -> WizardStep(
            heading,
            1,
            2,
            stringResource(R.string.setup_scan_code_title),
            onBack = { step = 1 },
            nextEnabled = usable,
            onNext = { step = 3 },
        ) {
            Text(
                stringResource(R.string.setup_scan_code_where),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { scanning = true }) { Text(stringResource(R.string.setup_scan_qr)) }
            OutlinedTextField(
                link,
                { link = it.trim() },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.setup_paste_link)) },
                isError = link.isNotBlank() && !usable,
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                parsed != null && !usable -> R.string.setup_server_not_supported
                                parsed != null -> R.string.setup_code_recognised
                                link.isNotBlank() -> R.string.setup_not_a_link
                                else -> R.string.setup_link_starts_with
                            },
                        ),
                    )
                },
            )
        }

        else -> WizardStep(
            heading,
            2,
            2,
            stringResource(R.string.setup_name_it_title),
            onBack = { step = 2 },
            next = stringResource(R.string.setup_add_device_button),
            nextEnabled = usable && you.isNotBlank(),
            onNext = {
                parsed?.takeIf { usable }?.let {
                    Hub.setParentName(you)
                    Hub.addDevice(it.copy(name = deviceName.ifBlank { it.name }))
                    done(it.id)
                }
            },
        ) {
            OutlinedTextField(
                deviceName,
                { deviceName = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.child_device_name)) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.setup_device_name_hint)) },
            )
            OutlinedTextField(
                you,
                { you = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.your_name)) },
                singleLine = true,
                supportingText = {
                    val example = you.ifBlank { stringResource(R.string.setup_example_parent_name) }
                    Text(stringResource(R.string.setup_your_name_hint_example, example))
                },
            )
        }
    }
}
