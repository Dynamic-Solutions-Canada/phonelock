package home.phonelock.parent

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString

/**
 * This app's own update, as [AppUpdate] reports it. Each build flavor has its own [AppUpdate]: the GitHub
 * build checks the public releases and installs with the parent's confirmation; the Play build leaves updates
 * to the Play Store and reports nothing.
 *
 * [installed] and [latest] are version codes ([latest] is 0 until a newer release is known), [size] is the
 * newer APK's size in bytes, [busy] is true while it downloads or installs, and [progress] (0..1) is how far
 * the download is.
 */
data class UpdateState(
    val installed: Int = 0,
    val latest: Int = 0,
    val size: Long = 0,
    val busy: Boolean = false,
    val progress: Float? = null,
) {
    val available: Boolean get() = latest > installed
}

/**
 * Settings' "This app" section: its version, and either "Update to …" or "Check for update". Shown only in
 * builds that update themselves (the GitHub build); the Play Store build has nothing here.
 */
@Composable
internal fun AppUpdateSection() {
    if (!AppUpdate.SUPPORTED) return
    val st by AppUpdate.state.collectAsState()

    Section(stringResource(R.string.device_app_section)) {
        Text(stringResource(R.string.device_app_version, st.installed))
        ProgressTrack(st.busy)
        when {
            st.busy -> Text(
                st.progress?.let { stringResource(R.string.device_app_downloading, st.latest, (it * 100).toInt()) }
                    ?: stringResource(R.string.device_app_installing, st.latest),
                style = MaterialTheme.typography.bodyMedium,
            )

            st.available -> ListRow(
                stringResource(R.string.device_app_update_to, st.latest),
                AnnotatedString(stringResource(R.string.device_app_update_detail, st.size / (1024 * 1024))),
                leading = { SymbolBadge("⬇") },
                onClick = { AppUpdate.install() },
                titleLines = 2,
            )

            else -> ListRow(
                stringResource(R.string.device_app_check),
                AnnotatedString(stringResource(R.string.device_app_check_detail)),
                leading = { SymbolBadge("⬆") },
                onClick = { AppUpdate.check(asked = true) },
                titleLines = 2,
            )
        }
    }
}
