package home.phonelock.parent

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/** Situation bits, matching lock/.../Schedule.java. */
const val MODE_NORMAL = 1
const val MODE_LIMITED = 2
const val MODE_BEDTIME = 4
private const val DEFAULT_MASK = MODE_NORMAL or MODE_LIMITED

/**
 * One time range of limited hours or bedtime. [days]: bit 0 = Monday … bit 6 = Sunday. [start]/[end]:
 * minutes after midnight; `end < start` runs past midnight, `end == start` is all day.
 */
data class ScheduleRule(val mode: String, val days: Int, val start: Int, val end: Int) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("days", days).put("start", start).put("end", end)

    companion object {
        fun listFrom(a: JSONArray?): List<ScheduleRule> = if (a == null) {
            emptyList()
        } else {
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                ScheduleRule(it.optString("mode"), it.optInt("days"), it.optInt("start"), it.optInt("end"))
            }
        }

        fun toJson(rules: List<ScheduleRule>) = JSONArray().apply { rules.forEach { put(it.toJson()) } }
    }
}

/**
 * The situation ("normal", "limited", "bedtime") these rules give at [ms], matching the device's
 * Schedule.modeAt: bedtime wins over limited hours, overnight ranges spill into the next day, and
 * start == end covers the whole day.
 */
fun List<ScheduleRule>.modeAt(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    fun has(r: ScheduleRule, d: Int) = r.days and (1 shl d) != 0
    fun covers(r: ScheduleRule) = when {
        r.start == r.end -> has(r, day)
        r.start < r.end -> has(r, day) && minute >= r.start && minute < r.end
        else -> (has(r, day) && minute >= r.start) || (has(r, (day + 6) % 7) && minute < r.end)
    }
    val hits = filter(::covers)
    return when {
        hits.any { it.mode == "bedtime" } -> "bedtime"
        hits.isNotEmpty() -> "limited"
        else -> "normal"
    }
}

/**
 * Two-letter day names for the day toggles ("Mo", "Tu" in English), Monday first (bit 0), as the schedule
 * stores them, in the app's language. ICU's SHORT width is the two-letter form; its list starts at index 1
 * with Sunday.
 */
private fun dayShortNames(): List<String> {
    val names = android.icu.text.DateFormatSymbols.getInstance(Locale.getDefault())
        .getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, android.icu.text.DateFormatSymbols.SHORT)
    return listOf(2, 3, 4, 5, 6, 7, 1).map { names[it] }
}

/** Short day names ("Mon", "Tue" in English), Monday first (bit 0), in the app's language. */
private fun dayNames(): List<String> = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }

/** "Limited hours", "Bedtime", "Normal hours" for a mode name from the device. */
fun modeLabel(mode: String) = when (mode) {
    "bedtime" -> Strings.get(R.string.apps_mode_bedtime)
    "limited" -> Strings.get(R.string.apps_mode_limited)
    else -> Strings.get(R.string.apps_mode_normal)
}

/** "normal hours", "limited hours", "bedtime" for one situation bit, for messages. */
internal fun situationName(bit: Int) = when (bit) {
    MODE_BEDTIME -> Strings.get(R.string.apps_situation_bedtime)
    MODE_LIMITED -> Strings.get(R.string.apps_situation_limited)
    else -> Strings.get(R.string.apps_situation_normal)
}

/** "Weekdays", "Every day", "Mon, Wed, Fri", … */
private fun describeDays(days: Int): String = when (days and 0x7f) {
    0x7f -> Strings.get(R.string.apps_days_every_day)
    0x1f -> Strings.get(R.string.apps_days_weekdays)
    0x60 -> Strings.get(R.string.apps_days_weekends)
    else -> dayNames().filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
}

@Composable
private fun clock(minutes: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
    }
    return DateFormat.getTimeFormat(LocalContext.current).format(cal.time)
}

/**
 * "Apps and when they're allowed": one row per app on the device with a checkbox for each situation; no
 * ticks means it's only ever allowed for a while. Each row shows the app's daily time limit, and its ⋮ menu
 * (the only place limits are edited) sets that limit, hides or uninstalls the app. Hidden apps are listed
 * below, only to be shown again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppTableSection(dev: String, state: PhoneState) {
    var showHidden by remember(dev) { mutableStateOf(false) }
    var uninstalling by remember(dev) { mutableStateOf<String?>(null) }
    val allLimits by AppTime.limits.collectAsState()
    val allToday by AppTime.today.collectAsState()
    val limits = allLimits[dev].orEmpty()
    val today = allToday[dev]
    var editingLimit by remember(dev) { mutableStateOf<TimeLimit?>(null) }
    editingLimit?.let { l ->
        LimitDialog(
            state,
            l,
            isNew = limits.none { it.id == l.id },
            onSave = {
                AppTime.saveLimit(dev, it)
                editingLimit = null
            },
            // Same as "Remove daily limit" in the row's menu, Undo included.
            onDelete = {
                AppTime.removeLimit(dev, l.id)
                editingLimit = null
                Undo.offer(Strings.get(R.string.apps_limit_removed, state.label(l.app))) { AppTime.saveLimit(dev, l) }
            },
            onDismiss = { editingLimit = null },
        )
    }
    uninstalling?.let { p ->
        AppDialog(
            onDismissRequest = { uninstalling = null },
            title = { Text(stringResource(R.string.apps_uninstall_title, state.label(p))) },
            text = { Text(stringResource(R.string.apps_uninstall_body)) },
            confirmButton = {
                DangerButton(stringResource(R.string.apps_uninstall)) {
                    Hub.uninstall(dev, p)
                    uninstalling = null
                }
            },
            dismissButton = { TextButton(onClick = { uninstalling = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Every app on the device, Phone first and then A to Z, ticked or not.
    val rows = phoneFirst(state, appsOnly((state.home + state.others).distinct()))
    Section(stringResource(R.string.apps_table_title)) {
        Text(stringResource(R.string.apps_table_hint), style = MaterialTheme.typography.bodySmall)
        SituationHeader()
        HorizontalDivider()
        rows.forEach { p ->
            val mask = if (p in state.home) state.masks[p] ?: DEFAULT_MASK else 0
            val own = limits.firstOrNull { it.app == p }
            val ownStatus = own?.let { limitStatus(it, today) }
            // Row and limit note together, so the section's spacing falls between apps, not inside one.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppName(dev, p, state.label(p), Modifier.weight(1f))
                    SituationChecks(dev, p, state.label(p), mask, enabled = state.full)
                    RowMenu(
                        stringResource(R.string.apps_more_for, state.label(p)),
                        enabled = state.full,
                        entries = listOfNotNull(
                            MenuEntry(stringResource(if (own == null) R.string.apps_set_limit else R.string.apps_change_limit)) {
                                editingLimit = own ?: newLimit(p)
                            },
                            own?.let { l ->
                                MenuEntry(stringResource(R.string.apps_remove_limit)) {
                                    AppTime.removeLimit(dev, l.id)
                                    Undo.offer(Strings.get(R.string.apps_limit_removed, state.label(p))) { AppTime.saveLimit(dev, l) }
                                }
                            },
                            // Hiding only apps with no ticks, so hiding never quietly takes an app off the home screen.
                            MenuEntry(stringResource(if (mask == 0) R.string.apps_hide else R.string.apps_hide_untick), enabled = mask == 0) {
                                Hub.offer(dev, p, false)
                                Undo.offer(Strings.get(R.string.apps_app_hidden, state.label(p))) { Hub.offer(dev, p, true) }
                            },
                            // Only apps installed on the device, not built in.
                            if (p in state.removable) MenuEntry(stringResource(R.string.apps_uninstall_menu)) { uninstalling = p } else null,
                        ),
                    )
                }
                // The daily limit on a line of its own under the row, lined up with the name (icon 32 dp + gap
                // 12 dp), so it has the full width instead of the narrow name column.
                ownStatus?.let { (text, out) ->
                    Text(
                        text,
                        Modifier.padding(start = 44.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (out) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Hidden apps live here only to be brought back.
        val hidden = appsOnly(state.notOffered)
        if (hidden.isNotEmpty()) {
            TextButton(onClick = { showHidden = !showHidden }) {
                Text(stringResource(R.string.apps_hidden_apps, hidden.size) + if (showHidden) " ▴" else " ▾")
            }
            if (showHidden) {
                phoneFirst(state, hidden).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppName(dev, p, state.label(p), Modifier.weight(1f))
                        TextButton(enabled = state.full, onClick = {
                            Hub.offer(dev, p, true)
                            Undo.offer(Strings.get(R.string.apps_app_shown, state.label(p))) { Hub.offer(dev, p, false) }
                        }) { Text(stringResource(R.string.apps_show)) }
                    }
                }
            }
        }
    }
}

/** Width of one Normal / Limited / Bedtime checkbox column, and of the row menu. */
private val CELL = 56.dp
internal val MENU = 40.dp

/**
 * The "Normal / Limited / Bedtime" column titles over [SituationChecks] rows, with room for a row menu at the
 * end, so every table of situations (apps, Wi-Fi and Bluetooth) lines up the same way.
 */
@Composable
internal fun SituationHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        listOf(R.string.apps_col_normal, R.string.apps_col_limited, R.string.apps_col_bedtime).map { stringResource(it) }.forEach {
            Text(it, Modifier.width(CELL), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.width(MENU))
    }
}

/**
 * The three situation checkboxes for [id] (an app, or Wi-Fi or Bluetooth) given its [mask]; each change is
 * shown at once with Undo. Rows without a menu add a [MENU]-wide spacer after this to stay in line.
 */
@Composable
internal fun SituationChecks(dev: String, id: String, label: String, mask: Int, enabled: Boolean) {
    listOf(MODE_NORMAL, MODE_LIMITED, MODE_BEDTIME).forEach { bit ->
        Row(Modifier.width(CELL), horizontalArrangement = Arrangement.Center) {
            Checkbox(checked = mask and bit != 0, enabled = enabled, onCheckedChange = { on ->
                Hub.appModeBit(dev, id, bit, on)
                val msg = if (on) R.string.apps_situation_allowed else R.string.apps_situation_not_allowed
                Undo.offer(Strings.get(msg, label, situationName(bit))) {
                    Hub.appModeBit(dev, id, bit, !on)
                }
            })
        }
    }
}

/** One item of a [RowMenu]. */
internal data class MenuEntry(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * A row's ⋮ menu, [MENU] wide so rows with and without one line up. [description] is read out for the
 * button; each entry closes the menu before acting.
 */
@Composable
internal fun RowMenu(description: String, enabled: Boolean, entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(MENU)) {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = description)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { e ->
                DropdownMenuItem(text = { Text(e.label) }, enabled = e.enabled, onClick = {
                    open = false
                    e.onClick()
                })
            }
        }
    }
}

/** "Schedule": the limited-hours and bedtime ranges, with add and remove. */
@Composable
internal fun ScheduleSection(dev: String, state: PhoneState) {
    // The rule being added (index -1) or edited (its index in the device's list).
    var editing by remember(dev) { mutableStateOf<Pair<Int, ScheduleRule>?>(null) }
    val now = System.currentTimeMillis()
    // Shown in the order they next happen, a running one first; edits still address the device's list.
    val order = state.schedule.indices.sortedBy { state.schedule[it].nextStart(now) }
    Section(stringResource(R.string.apps_schedule_title)) {
        // Until the device's schedule arrives, an empty list would wrongly say there's none; the header card
        // says it's getting the settings meanwhile.
        if (state.full && state.schedule.isEmpty()) EmptyNote(stringResource(R.string.apps_schedule_empty))
        order.forEach { i ->
            val r = state.schedule[i]
            val remove = {
                val before = state.schedule
                Hub.setSchedule(dev, before.filterIndexed { j, _ -> j != i })
                Undo.offer(Strings.get(R.string.apps_rule_removed, modeLabel(r.mode))) { Hub.setSchedule(dev, before) }
            }
            // Tap to edit; ⋮ for edit or remove. Both wait for the device's current schedule (a full state).
            ListRow(
                modeLabel(r.mode),
                AnnotatedString(
                    if (r.start == r.end) {
                        stringResource(R.string.apps_rule_all_day, describeDays(r.days))
                    } else {
                        stringResource(R.string.apps_rule_range, describeDays(r.days), clock(r.start), clock(r.end))
                    },
                ),
                leading = { SymbolBadge(if (r.mode == "bedtime") "🌙" else "⏳") },
                actionsEnabled = state.full,
                actions = listOf(
                    MenuEntry(stringResource(R.string.apps_edit)) { editing = i to r },
                    MenuEntry(stringResource(R.string.remove), onClick = remove),
                ),
                onClick = if (state.full) ({ editing = i to r }) else null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Edits replace the whole schedule, so they wait until the device's current one has arrived.
            OutlinedButton(enabled = state.full, onClick = { editing = -1 to ScheduleRule("limited", 0x1f, 8 * 60 + 30, 15 * 60 + 15) }) {
                Text(stringResource(R.string.apps_add_limited))
            }
            OutlinedButton(enabled = state.full, onClick = { editing = -1 to ScheduleRule("bedtime", 0x7f, 21 * 60 + 30, 7 * 60) }) {
                Text(stringResource(R.string.apps_add_bedtime))
            }
        }
    }
    editing?.let { (index, draft) ->
        RuleDialog(draft, isNew = index < 0, onSave = { rule ->
            val before = state.schedule
            val after = if (index < 0) before + rule else before.mapIndexed { j, r -> if (j == index) rule else r }
            Hub.setSchedule(dev, after)
            val msg = if (index < 0) R.string.apps_rule_added else R.string.apps_rule_changed
            Undo.offer(Strings.get(msg, modeLabel(rule.mode))) { Hub.setSchedule(dev, before) }
            editing = null
        }, onDismiss = { editing = null })
    }
}

/**
 * When this rule next starts, in ms; [now] itself if it is running at that moment, so active ones sort
 * first. Overnight rules count from their evening start; all-day rules from midnight.
 */
internal fun ScheduleRule.nextStart(now: Long): Long {
    if (listOf(this).modeAt(now) != "normal") return now
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    for (offset in 0..7) {
        val day = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, offset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val weekday = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7
        if (days and (1 shl weekday) == 0) continue
        val startMs = day.timeInMillis + (if (start == end) 0 else start) * 60_000L
        if (startMs >= cal.timeInMillis) return startMs
    }
    return Long.MAX_VALUE
}

/** A time to pick: [label] above the time in an outlined box with a clock icon; tapping opens the picker. */
@Composable
private fun TimeField(label: String, minutes: Int, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier, shape = RoundedCornerShape(10.dp)) {
        Icon(ClockIcon, contentDescription = null, Modifier.size(18.dp))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(clock(minutes), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Adds or edits a schedule range: limited hours or bedtime, which days, and from and until when. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RuleDialog(draft: ScheduleRule, isNew: Boolean, onSave: (ScheduleRule) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(draft.mode) }
    var days by remember { mutableIntStateOf(draft.days) }
    var start by remember { mutableIntStateOf(draft.start) }
    var end by remember { mutableIntStateOf(draft.end) }
    fun pickTime(initial: Int, set: (Int) -> Unit) {
        TimePickerDialog(
            ctx,
            { _, h, m -> set(h * 60 + m) },
            initial / 60,
            initial % 60,
            DateFormat.is24HourFormat(ctx),
        ).show()
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            // Whole titles per mode rather than a lower-cased mode name, so each language can word them its own way.
            val bedtime = mode == "bedtime"
            Text(
                stringResource(
                    when {
                        isNew && bedtime -> R.string.apps_add_bedtime
                        isNew -> R.string.apps_add_limited
                        bedtime -> R.string.apps_edit_bedtime
                        else -> R.string.apps_edit_limited
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Which situation this range is; editable, so a range can switch between the two.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("limited" to R.string.apps_mode_limited, "bedtime" to R.string.apps_mode_bedtime).forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = mode == key,
                            onClick = { mode = key },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(stringResource(label)) }
                    }
                }
                // Days as equal toggles with short names, so the two Ts and two Ss can't be mixed up.
                Text(stringResource(R.string.apps_days), style = MaterialTheme.typography.labelLarge)
                MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val dayShort = dayShortNames()
                    dayShort.forEachIndexed { i, l ->
                        SegmentedButton(
                            checked = days and (1 shl i) != 0,
                            onCheckedChange = { days = days xor (1 shl i) },
                            shape = SegmentedButtonDefaults.itemShape(i, dayShort.size),
                            icon = {},
                        ) {
                            Text(l, maxLines = 1, softWrap = false)
                        }
                    }
                }
                // Times as outlined fields with a clock, so they read as something to tap.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeField(stringResource(R.string.apps_from), start, Modifier.weight(1f)) { pickTime(start) { start = it } }
                    TimeField(stringResource(R.string.apps_until), end, Modifier.weight(1f)) { pickTime(end) { end = it } }
                }
                when {
                    end == start -> Text(stringResource(R.string.apps_all_day), style = MaterialTheme.typography.bodySmall)
                    end < start -> Text(stringResource(R.string.apps_past_midnight), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft.copy(mode = mode, days = days, start = start, end = end)) },
                enabled = days != 0,
            ) { Text(stringResource(if (isNew) R.string.add else R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
