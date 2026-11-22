package home.phonelock.parent

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * The main screen's "Unfamiliar screens" section: Google services screens the device reported, each with
 * its screenshot (tap to enlarge) and Mark OK / Block / Dismiss. Shown only while there are some, like
 * Requests.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenAlertsSection(dev: String) {
    val all by ScreenAlerts.alerts.collectAsState()
    val mine = all.filter { it.dev == dev }
    if (mine.isEmpty()) return
    var enlarged by remember { mutableStateOf<ImageBitmap?>(null) }

    Section(stringResource(R.string.alerts_screen_section)) {
        Text(
            stringResource(R.string.alerts_screen_section_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        mine.forEach { a ->
            // Decoded once per report; the files are small JPEGs.
            val image = remember(a.image) { a.image?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (image != null) {
                    Image(
                        image,
                        stringResource(R.string.alerts_screenshot),
                        Modifier.width(72.dp).heightIn(max = 150.dp)
                            .clip(RoundedCornerShape(8.dp)).clickable { enlarged = image },
                        contentScale = ContentScale.Fit,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                    val at = time(a.ts)
                    val line = if (a.from.isNotEmpty()) stringResource(R.string.alerts_screen_from_at, a.from, at) else at
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ScreenAlerts.decide(a.id, "ok") }) { Text(stringResource(R.string.alerts_screen_mark_ok)) }
                        OutlinedButton(onClick = { ScreenAlerts.decide(a.id, "block") }) { Text(stringResource(R.string.alerts_block)) }
                        TextButton(onClick = { ScreenAlerts.decide(a.id, "dismiss") }) { Text(stringResource(R.string.alerts_dismiss)) }
                    }
                }
            }
        }
    }

    enlarged?.let { img ->
        AppDialog(
            onDismissRequest = { enlarged = null },
            confirmButton = { TextButton(onClick = { enlarged = null }) { Text(stringResource(R.string.alerts_close)) } },
            text = { Image(img, stringResource(R.string.alerts_screenshot), Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) },
        )
    }
}

/**
 * Settings' "Google screens" section: the groups of Google screens the device blocks unless allowed (account
 * management, Google settings, help, feedback, Quick Share), then single screens a parent decided about. Each
 * row says where it stands and has a menu to allow it (always, for a while, or until bedtime), block it, or go
 * back to the default. Hidden until the device reports its groups (an older lock app doesn't).
 */
@Composable
internal fun ScreenRulesSection(dev: String, state: PhoneState?) {
    val st = state ?: return
    val singles = st.screenRules.keys.filterNot { it.startsWith(Hub.AREA_PREFIX) }
    if (st.screenAreas.isEmpty() && singles.isEmpty()) return
    val now = System.currentTimeMillis()
    // "Until bedtime" only makes sense when a bedtime is coming up; the device checks again when asked.
    val bedtimeAhead = st.mode != "bedtime" && st.schedule.any { it.mode == "bedtime" }

    Section(stringResource(R.string.alerts_rules_section)) {
        Text(
            stringResource(R.string.alerts_rules_section_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        st.screenAreas.forEach { area ->
            ScreenRuleRow(
                dev,
                Hub.AREA_PREFIX + area.key,
                area.name,
                st.screenRules[Hub.AREA_PREFIX + area.key],
                blockedByDefault = true,
                now = now,
                bedtimeAhead = bedtimeAhead,
            )
        }
        singles.sortedBy { ScreenAlerts.screenName(it) }.forEach { screen ->
            ScreenRuleRow(
                dev,
                screen,
                ScreenAlerts.screenName(screen),
                st.screenRules[screen],
                blockedByDefault = false,
                now = now,
                bedtimeAhead = bedtimeAhead,
            )
        }
    }
}

/**
 * One row of [ScreenRulesSection]: [title], where it stands under [rule] ("ok", "block", "ok:<until>" or none,
 * which means blocked for an area and reported for a single screen), and its menu.
 */
@Composable
private fun ScreenRuleRow(
    dev: String,
    key: String,
    title: String,
    rule: String?,
    blockedByDefault: Boolean,
    now: Long,
    bedtimeAhead: Boolean,
) {
    val until = rule?.takeIf { it.startsWith("ok:") }?.removePrefix("ok:")?.toLongOrNull()?.takeIf { it > now }
    val allowed = rule == "ok" || until != null
    val decided = rule == "ok" || rule == "block" || until != null
    val status = when {
        rule == "ok" -> stringResource(R.string.alerts_rule_allowed)
        until != null -> stringResource(R.string.alerts_rule_allowed_until, time(until))
        rule == "block" -> stringResource(R.string.alerts_rule_blocked)
        blockedByDefault -> stringResource(R.string.alerts_rule_blocked_default)
        else -> stringResource(R.string.alerts_rule_reported)
    }
    // Menu labels, read here in composition rather than inside the list builder.
    val allowLabel = stringResource(R.string.allow)
    val allowForLabel = stringResource(R.string.alerts_rule_allow_for)
    var choosingLength by remember { mutableStateOf(false) }
    val allowBedtimeLabel = stringResource(R.string.alerts_rule_allow_until_bedtime)
    val blockLabel = stringResource(R.string.alerts_block)
    val resetLabel = stringResource(if (blockedByDefault) R.string.alerts_rule_back_to_blocked else R.string.alerts_rule_forget)

    /** Sends a decision about this row. */
    fun set(rule: String, minutes: Int = 0, untilBedtime: Boolean = false) = Hub.setScreenRule(dev, key, rule, minutes, untilBedtime)

    val entries = buildList {
        if (rule != "ok") add(MenuEntry(allowLabel) { set("ok") })
        add(MenuEntry(allowForLabel) { choosingLength = true })
        if (bedtimeAhead) add(MenuEntry(allowBedtimeLabel) { set("ok", untilBedtime = true) })
        if (rule != "block") add(MenuEntry(blockLabel) { set("block") })
        if (decided) add(MenuEntry(resetLabel) { set("") })
    }
    ListRow(title, AnnotatedString(status), leading = { SymbolBadge(if (allowed) "✓" else "⛔") }, actions = entries)

    // "Allow for a while…": the app's usual lengths, a clock time or a custom length (up to 24 hours).
    if (choosingLength) {
        LengthDialog(
            stringResource(R.string.apps_allow_how_long, title),
            onPick = { minutes ->
                set("ok", minutes = minutes.toInt())
                choosingLength = false
            },
            onDismiss = { choosingLength = false },
        )
    }
}
