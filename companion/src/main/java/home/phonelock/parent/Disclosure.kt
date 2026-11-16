package home.phonelock.parent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The one-time "what this app does" screen shown before the first device is added: what is collected from
 * the child's device, who can read it, what the child is told, and the parent's agreement. Google Play
 * requires this prominent disclosure and consent for monitoring apps; it is also simply the honest start.
 */
object Disclosure {
    const val PRIVACY_POLICY_URL = "https://github.com/Dynamic-Solutions-Canada/phonelock/blob/main/PRIVACY.md"
    /** Bump when the disclosure text changes materially, so parents see and accept the new version. */
    private const val VERSION = 2

    private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

    fun accepted(c: Context) = prefs(c).getInt("disclosure_accepted", 0) >= VERSION

    fun accept(c: Context) = prefs(c).edit().putInt("disclosure_accepted", VERSION).apply()
}

@Composable
internal fun DisclosureScreen(onAccept: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.device_disclosure_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.device_disclosure_intro))
        Section(stringResource(R.string.device_disclosure_seen_title)) {
            Text(stringResource(R.string.device_disclosure_seen))
        }
        Section(stringResource(R.string.device_disclosure_readers_title)) {
            Text(stringResource(R.string.device_disclosure_readers))
        }
        Section(stringResource(R.string.device_disclosure_child_title)) {
            Text(stringResource(R.string.device_disclosure_child))
        }
        Text(stringResource(R.string.device_disclosure_consent), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Disclosure.PRIVACY_POLICY_URL)))
        }) { Text(stringResource(R.string.device_privacy_policy)) }
        Button(onClick = {
            Disclosure.accept(ctx)
            onAccept()
        }) { Text(stringResource(R.string.device_disclosure_agree)) }
    }
}
