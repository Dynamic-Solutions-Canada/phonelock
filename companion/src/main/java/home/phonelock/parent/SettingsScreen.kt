package home.phonelock.parent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString

/**
 * One device's settings, from the ⋮ on its header card: names, parent access (parent mode, PIN, battery
 * alert), the lock app (version, updates, diagnostics, repairs), and sharing or removing the device. A page
 * of sections rather than a long dialog, so each group is easy to find; [onOpen] opens Diagnostics or Fix
 * setup, which come back here.
 */
@Composable
internal fun SettingsScreen(dev: String, name: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val states by Hub.states.collectAsState()
    val parentName by Hub.parentName.collectAsState()
    val st = states[dev]
    var rename by remember(dev) { mutableStateOf(name) }
    var you by remember { mutableStateOf(parentName) }
    var confirmRemove by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var parentMode by remember { mutableStateOf(false) }
    var changingPin by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }

    /** One tappable setting: a symbol, what it does, and a line about it. */
    @Composable
    fun Setting(symbol: String, title: String, detail: String, onClick: () -> Unit) = ListRow(
        title,
        AnnotatedString(detail),
        leading = { SymbolBadge(symbol) },
        onClick = onClick,
        titleLines = 2,
    )

    // Read here: the Save button's onClick isn't composable.
    val namesSaved = stringResource(R.string.device_names_saved)

    Page(stringResource(R.string.device_settings_title), onBack) {
        Section(stringResource(R.string.device_names)) {
            OutlinedTextField(
                rename,
                { rename = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.child_device_name)) },
                singleLine = true,
            )
            OutlinedTextField(
                you,
                { you = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.your_name)) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.device_your_name_detail)) },
            )
            // Names are typed, so they're saved together on purpose rather than with every key press.
            OutlinedButton(
                enabled = (rename.isNotBlank() && rename != name) || (you.isNotBlank() && you != parentName),
                onClick = {
                    if (rename.isNotBlank() && rename != name) Hub.renameDevice(dev, rename)
                    if (you.isNotBlank() && you != parentName) Hub.setParentName(you)
                    Undo.notify(namesSaved)
                },
            ) { Text(stringResource(R.string.device_save_names)) }
        }
        Section(stringResource(R.string.device_parent_access)) {
            Setting("🔓", stringResource(R.string.device_parent_mode), stringResource(R.string.device_parent_mode_detail)) {
                parentMode = true
            }
            Setting("🔑", stringResource(R.string.device_change_pin), stringResource(R.string.device_change_pin_detail)) {
                changingPin = true
            }
            BatteryAlertSetting(dev, st)
            QuietAlertSetting(dev)
        }
        Section(stringResource(R.string.device_lock_app)) {
            DeviceLanguageSetting(dev, st)
            // The lock app's version, if known, and a newer one waiting to install, if any.
            val version = st?.version?.takeIf { it > 0 }
            val ready = st?.updateReady?.takeIf { it > 0 }
            Text(
                when {
                    version != null && ready != null -> stringResource(R.string.device_lock_version_update_ready, version, ready)
                    version != null -> stringResource(R.string.device_lock_version, version)
                    ready != null -> stringResource(R.string.device_lock_version_unknown_update_ready, ready)
                    else -> stringResource(R.string.device_lock_version_unknown)
                },
            )
            Setting("⬆", stringResource(R.string.device_check_update_now), stringResource(R.string.device_check_update_now_detail)) {
                Hub.updateNow(dev)
            }
            Setting("🩺", stringResource(R.string.device_diag_title), stringResource(R.string.device_diagnostics_detail)) {
                onOpen("diag")
            }
            Setting("📶", stringResource(R.string.device_fix_setup), stringResource(R.string.device_fix_setup_detail)) {
                onOpen("fixsetup")
            }
            Setting("🛠", stringResource(R.string.device_recovery), stringResource(R.string.device_recovery_detail)) {
                recovery = true
            }
        }
        ScreenRulesSection(dev, st)
        AppUpdateSection()
        LanguageSection()
        Section(stringResource(R.string.device_sharing)) {
            Setting("👥", stringResource(R.string.device_share), stringResource(R.string.device_share_detail)) { sharing = true }
            // Removing is the one destructive setting, so it's a red button rather than a row like the others.
            TextButton(onClick = { confirmRemove = true }) {
                Text(stringResource(R.string.device_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (recovery) {
        DebuggingDialog(name, onPick = {
            Hub.debugging(dev, it)
            recovery = false
        }, onDismiss = { recovery = false })
    }
    if (parentMode) {
        ParentModeDialog(
            name,
            onPick = {
                Hub.parentMode(dev, it)
                parentMode = false
            },
            onDismiss = { parentMode = false },
        )
    }
    if (changingPin) {
        PinDialog(onSave = {
            Hub.setPin(dev, it)
            changingPin = false
        }, onDismiss = { changingPin = false })
    }
    if (sharing) {
        Hub.devices.value.firstOrNull { it.id == dev }?.let { d ->
            SharePhoneDialog(d.copy(name = name)) { sharing = false }
        }
    }
    if (confirmRemove) {
        AppDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.device_remove_title, name)) },
            text = { Text(stringResource(R.string.device_remove_text)) },
            confirmButton = {
                DangerButton(stringResource(R.string.remove)) {
                    Hub.removeDevice(dev)
                    confirmRemove = false
                    onBack()
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
