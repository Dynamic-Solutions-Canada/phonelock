package home.phonelock.parent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Receives the devices the parent app under its old id hands over (AppMove, contract version 1) and adds them
 * the same way a pairing link would. Refuses anything not started for a result by the old app signed with this
 * app's own key, so no other app can plant a device here. Shows the disclosure first if it hasn't been accepted.
 */
class ImportDevicesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val devices = trustedDevices()
        if (devices == null) {
            finish()
            return
        }
        val parentName = intent.getStringExtra(AppMove.EXTRA_PARENT_NAME).orEmpty()

        setContent {
            AppTheme {
                var disclosed by remember { mutableStateOf(Disclosure.accepted(this)) }
                if (!disclosed) {
                    DisclosureScreen { disclosed = true }
                } else {
                    ImportDialog(devices, onAdd = { add(devices, parentName) }, onCancel = { finish() })
                }
            }
        }
    }

    /**
     * The devices in the request, or null when it isn't one to trust: not from the old app (callingPackage is
     * known only for a request started for a result), not signed with this app's key, or an unknown version.
     * Devices this build can't reach (another server, in the Play build) are left out.
     */
    private fun trustedDevices(): List<Device>? {
        val caller = callingPackage ?: return null
        if (caller != AppMove.OLD_PACKAGE || caller == packageName) return null
        val own = Releases.installedSigner(packageManager, packageName) ?: return null
        if (Releases.installedSigner(packageManager, caller) != own) return null
        if (intent.action != AppMove.ACTION || intent.getIntExtra(AppMove.EXTRA_VERSION, 0) != AppMove.VERSION) return null
        return intent.getStringArrayListExtra(AppMove.EXTRA_LINKS).orEmpty()
            .mapNotNull { Hub.parsePairing(it) }
            .filter { BackgroundConnection.accepts(it.server) }
    }

    /** Adds every device not already here, takes the parent's name if this app has none, and reports back. */
    private fun add(devices: List<Device>, parentName: String) {
        val known = Hub.devices.value.map { it.id }.toSet()
        val added = devices.filter { it.id !in known }
        if (Hub.parentName.value.isBlank() && parentName.isNotBlank()) Hub.setParentName(parentName)
        added.forEach { Hub.addDevice(it) }
        setResult(Activity.RESULT_OK, Intent().putExtra(AppMove.EXTRA_ADDED, added.size))
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

/** Asks before adding: the device names, and that settings such as quiet time don't come across. */
@Composable
private fun ImportDialog(devices: List<Device>, onAdd: () -> Unit, onCancel: () -> Unit) {
    AppDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.setup_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.setup_import_none))
                } else {
                    Text(stringResource(R.string.setup_import_text, devices.joinToString(", ") { it.name }))
                }
            }
        },
        confirmButton = {
            if (devices.isNotEmpty()) TextButton(onClick = onAdd) { Text(stringResource(R.string.add)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
