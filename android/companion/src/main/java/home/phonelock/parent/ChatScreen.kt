package home.phonelock.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay

/** Slow-mode spacings a parent can pick for the child's chat messages, in minutes (0 = off). */
private val SLOW_MODE_MINUTES = listOf(0, 1, 5, 15)

/** The longest preview of the last message on the device page's Chat row, in characters. */
private const val PREVIEW_CHARS = 80

/**
 * The Chat row on a child's device page: opens the chat ([onOpen] with "chat") and says how many messages are
 * unread, or shows the last one. Only for phones whose lock app has the chat ([PhoneState.canChat]).
 */
@Composable
internal fun ChatEntry(dev: String, state: PhoneState, onOpen: (String) -> Unit) {
    if (!state.canChat) return
    val threads by Chat.threads.collectAsState()
    val thread = threads[dev] ?: ChatThread()
    val last = thread.messages.lastOrNull()
    val detail = when {
        thread.unread > 0 -> pluralStringResource(R.plurals.chat_unread, thread.unread, thread.unread)
        last != null -> stringResource(R.string.chat_notification_line, senderLabel(dev, last), preview(last.text))
        else -> stringResource(R.string.chat_empty)
    }
    ListRow(
        stringResource(R.string.chat_title),
        AnnotatedString(detail),
        leading = { SymbolBadge("💬") },
        onClick = { onOpen("chat") },
    )
}

/** [text]'s first line, cut to [PREVIEW_CHARS] with an ellipsis, for a one-line preview. */
private fun preview(text: String): String {
    val line = text.lineSequence().first()
    return if (line.length <= PREVIEW_CHARS && line.length == text.length) line else line.take(PREVIEW_CHARS).trimEnd() + "…"
}

/** Who wrote [m], for display: "You" for this parent's own, the child's device name, or the other parent's name. */
@Composable
private fun senderLabel(dev: String, m: ChatMessage): String = if (m.mine) stringResource(R.string.chat_you) else Chat.senderName(dev, m.from)

/**
 * The family chat with [dev] ([name] is the child's device): the thread, the parents' limits on the child's
 * sending, and a field to write in. Opening it fetches anything new from the phone, and while it is on screen its
 * messages count as read and raise no notification.
 */
@Composable
internal fun ChatScreen(dev: String, name: String, state: PhoneState, onBack: () -> Unit) {
    val threads by Chat.threads.collectAsState()
    val thread = threads[dev] ?: ChatThread()

    LaunchedEffect(dev) { Chat.sync(dev, visible = true) }
    LifecycleResumeEffect(dev) {
        Chat.onScreen = dev
        Chat.shown(dev)
        onPauseOrDispose { if (Chat.onScreen == dev) Chat.onScreen = null }
    }
    // Messages that arrive while the chat is open are read as they appear.
    LaunchedEffect(thread.unread) { if (thread.unread > 0 && Chat.onScreen == dev) Chat.shown(dev) }

    Page(stringResource(R.string.chat_title), onBack, scroll = false, dev = dev, waitingOn = setOf("chat")) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatLimits(dev, name, state)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (thread.messages.isEmpty()) {
                    EmptyNote(stringResource(R.string.chat_empty))
                } else {
                    ChatThreadList(dev, thread)
                }
            }
            ChatInput(dev)
        }
    }
}

/** The thread, oldest first, kept scrolled to the newest message as messages arrive. */
@Composable
private fun ChatThreadList(dev: String, thread: ChatThread) {
    val list = rememberLazyListState()
    val messages = thread.messages
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(messages.size) {
        now = System.currentTimeMillis()
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(messages, key = { _, m -> m.cid }) { i, m ->
            val prev = messages.getOrNull(i - 1)
            // A day heading where the day changes, and the sender's name where the sender changes, as in Recent events.
            val newDay = prev == null || startOfDay(prev.at) != startOfDay(m.at)
            if (newDay) {
                Text(
                    dayName(startOfDay(m.at), now),
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            val showSender = !m.mine && (prev == null || newDay || prev.from != m.from || prev.mine)
            ChatBubble(dev, m, if (m.mine) thread.status(m) else null, showSender)
        }
    }
}

/** One message: this parent's own on the right with its [status], others' on the left under their sender's name. */
@Composable
private fun ChatBubble(dev: String, m: ChatMessage, status: ChatStatus?, showSender: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
    ) {
        if (showSender) {
            Text(senderLabel(dev, m), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        val colors = MaterialTheme.colorScheme
        Text(
            m.text,
            Modifier.widthIn(max = 320.dp)
                .background(if (m.mine) colors.primaryContainer else colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (m.mine) colors.onPrimaryContainer else colors.onSurface,
        )
        val statusText = when (status) {
            ChatStatus.SENDING -> stringResource(R.string.chat_sending)
            ChatStatus.DELIVERED -> stringResource(R.string.chat_delivered)
            ChatStatus.SEEN -> stringResource(R.string.chat_seen)
            null -> null
        }
        Text(
            listOfNotNull(time(m.at), statusText).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** The field this parent writes in, with Send; the text stays while it can't be sent (empty or too long). */
@Composable
private fun ChatInput(dev: String) {
    var text by rememberSaveable(dev) { mutableStateOf("") }
    val sendable = ChatThread.sendable(text) != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text,
            { text = it.take(ChatThread.MAX_TEXT) },
            Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_message_hint)) },
            maxLines = 5,
        )
        IconButton(onClick = { if (Chat.send(dev, text)) text = "" }, enabled = sendable) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
        }
    }
}

/**
 * The parents' limits on the child's sending, from the phone's state: slow mode (one message every few minutes)
 * and a timeout (no sending until a time), each changed or ended from its chip. Reading and parents' messages are
 * never limited.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatLimits(dev: String, name: String, state: PhoneState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.chatUntil) {
        // Tick until the timeout ends, so its chip turns off by itself.
        while (state.chatUntil > now) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val every = state.chatEvery
    val until = state.chatUntil.takeIf { it > now } ?: 0L
    var pickingSlow by remember { mutableStateOf(false) }
    var pickingTimeout by remember { mutableStateOf(false) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = every > 0,
            onClick = { pickingSlow = true },
            enabled = state.full,
            label = {
                Text(
                    if (every > 0) {
                        stringResource(R.string.chat_slow_mode_on, fmtMinutes(every.toLong()))
                    } else {
                        stringResource(R.string.chat_slow_mode)
                    },
                )
            },
        )
        FilterChip(
            selected = until > 0,
            onClick = { pickingTimeout = true },
            enabled = state.full,
            label = {
                Text(if (until > 0) stringResource(R.string.chat_timeout_until, time(until)) else stringResource(R.string.chat_timeout))
            },
        )
    }

    if (pickingSlow) {
        val off = stringResource(R.string.chat_slow_mode_off)
        val options = SLOW_MODE_MINUTES.map { m ->
            val label = if (m == 0) off else stringResource(R.string.chat_slow_mode_every, fmtMinutes(m.toLong()))
            MenuEntry(label) {
                // Each change sends both settings, so the timeout goes as it stands.
                Hub.setChatLimit(dev, m, until)
                pickingSlow = false
            }
        }
        OptionsDialog(
            stringResource(R.string.chat_slow_mode_title, name),
            onDismiss = { pickingSlow = false },
            options = options,
            note = stringResource(R.string.chat_slow_mode_note),
        )
    }

    if (pickingTimeout) {
        val end = if (until > 0) {
            stringResource(R.string.chat_timeout_end) to {
                Hub.setChatLimit(dev, every, 0)
                pickingTimeout = false
            }
        } else {
            null
        }
        LengthDialog(
            stringResource(R.string.chat_timeout_title, name),
            onPick = { minutes ->
                Hub.setChatLimit(dev, every, System.currentTimeMillis() + minutes * 60_000)
                pickingTimeout = false
            },
            onDismiss = { pickingTimeout = false },
            extra = end,
            note = stringResource(R.string.chat_timeout_note),
        )
    }
}
