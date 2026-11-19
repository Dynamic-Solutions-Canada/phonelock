package home.phonelock.parent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the About screen's links go (BRAND-8). Installed copies keep these, so change them with care. */
internal object AboutLinks {
    private const val REPO = "https://github.com/Dynamic-Solutions-Canada/phonelock"
    const val SOURCE = REPO

    /** The repo's issues, which has the issue template (D-086). */
    const val SUPPORT = "$REPO/issues"
    const val LICENSE = "$REPO/blob/main/LICENSE"

    /** Generated per build variant by companion/third-party-notices.gradle (HYG-3). */
    const val NOTICES_ASSET = "third_party_notices.txt"
}

/**
 * Settings' last section: one row with this app's name and version, opening the About screen. It is shown in
 * every build, so the Play build shows its version too (UI-11).
 */
@Composable
internal fun AboutSection(onOpen: () -> Unit) {
    Section(stringResource(R.string.about_title)) {
        ListRow(
            stringResource(R.string.app_name),
            AnnotatedString(stringResource(R.string.about_version, BuildConfig.VERSION_NAME)),
            leading = { SymbolBadge("ℹ️") },
            onClick = onOpen,
        )
    }
}

/**
 * The About screen, with BRAND-8's items in its order: the name, version and tagline; "Updates" in builds that
 * update themselves; then the links. Android needs no "Open logs folder" (LOG-8). [onNotices] opens the
 * third-party notices, which come back here.
 */
@Composable
internal fun AboutScreen(onNotices: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current

    /** One link row: a symbol, its label, and where it goes. */
    @Composable
    fun Link(symbol: String, title: String, url: String) = ListRow(
        title,
        AnnotatedString(url.removePrefix("https://")),
        leading = { SymbolBadge(symbol) },
        onClick = { openLink(ctx, url) },
    )

    Page(stringResource(R.string.about_title), onBack) {
        Section(stringResource(R.string.app_name)) {
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)
        }
        AppUpdateSection()
        Section(stringResource(R.string.about_links)) {
            Link("💻", stringResource(R.string.about_source_code), AboutLinks.SOURCE)
            Link("🔒", stringResource(R.string.about_privacy_policy), Disclosure.PRIVACY_POLICY_URL)
            Link("💬", stringResource(R.string.about_support), AboutLinks.SUPPORT)
            Link("📄", stringResource(R.string.about_license), AboutLinks.LICENSE)
            ListRow(
                stringResource(R.string.about_third_party_notices),
                AnnotatedString(stringResource(R.string.about_notices_detail)),
                leading = { SymbolBadge("📚") },
                onClick = onNotices,
            )
        }
    }
}

/**
 * The third-party notices file this build carries, as plain text the parent can select and copy. It is kept in
 * English like the licences themselves.
 */
@Composable
internal fun NoticesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // About 30 KB, read off the main thread so opening the page never stutters.
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            ctx.assets.open(AboutLinks.NOTICES_ASSET).bufferedReader().use { it.readText() }
        }
    }

    Page(stringResource(R.string.about_third_party_notices), onBack) {
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** Opens [url] in the browser. */
private fun openLink(ctx: Context, url: String) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
