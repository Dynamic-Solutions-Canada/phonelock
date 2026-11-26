> **Scope:** Design record for the family chat: one group chat per child's phone, shared by that child and every parent paired with the phone. What it does, the decisions behind it, and how messages stay complete when a phone or a parent app is offline. The message formats are in `protocol.md`.
> **Governs:** `android/lock/src/main/java/home/phonelock/Chat*.java`, `android/companion/src/main/java/home/phonelock/parent/Chat*.kt`

# Family chat: design record

**Decided (Nov 2026, by the maintainer):** a text-only group chat per child's phone, carried on the phone's existing encrypted topic. The child's phone holds the history; parent apps catch up from it.

## What people see

- **Child:** a Chat icon in the home screen's app grid, scheduled like any app (it is a built-in app, like Wi-Fi and Bluetooth, and parents see it among the apps) and allowed in every situation by default, bedtime included, so parents can take it away at bedtime. Installed phones get it once, on the update that adds chat; a parent's later change sticks. Each message shows who sent it. A new message from a parent posts a notification that opens the chat; when Chat isn't allowed right now, the notification still shows the message and the screen says it's not available.
- **Parents:** a Chat row on each child's page (under Find and reach), with an unread count or the last message, and a notification for new messages from the child or another parent (one per child's phone, replaced by the next). The first catch-up of an existing history counts as already read, so a newly added parent isn't flooded. A parent app's own messages are the ones it sent; after reinstalling, earlier ones show under the parent's name. Parents' own messages show "Sending", then "Delivered" once the phone has stored them, and "Seen" once the child has opened the chat after them.
- **Parent controls on the child's sending** (instead of a built-in limit): **slow mode**, one message every 1, 5 or 15 minutes, and a **timeout**, no sending until a time picked with the usual "for how long" picker. Either can be ended early. Reading, and parents' messages, are never limited. The child's screen says why Send is unavailable and until when. Every parent sees the current setting, and changing it is logged as an event like other parent actions.
- **Not in the first version:** photos, voice, editing or deleting messages, clearing the history, and chats between parents alone.

- **Only on the PhoneLock hub (or another self-hosted server).** The public ntfy.sh server allows 250 messages a day per network, and a phone already uses about 100 of them for its location log, so a chat there could crowd out SOS alerts. A phone paired through ntfy.sh has no Chat icon and ignores chat messages, and parent apps show no chat for it.

## How it works

- **One thread per phone.** Every chat message goes on the phone's topic, so the child and every parent receive it directly. Messages are sealed like every other message; the hub relays only ciphertext.
- **The phone is the record.** The phone stores every chat message it receives or sends, gives each a sequence number (`seq`, counting up from 1), and keeps the last 90 days, at most 2,000 messages. The thread is ordered by `seq`, never by the senders' clocks, which disagree.
- **Delivery:** when the phone stores a parent's message it publishes `chatin` with that message's `seq`. That is the "Delivered" mark, and it tells every parent where the message sits in the thread.
- **Catching up:** a parent app sends `chatsync` with the highest `seq` it has whenever a chat opens and when the app reconnects; the phone answers with `chatlog`, everything after that `seq` (pages of up to 200). A newly added parent starts from 0 and gets the whole history.
- **Nothing is lost while the phone is off.** A parent app keeps each of its own messages as pending until the message appears in the phone's history (by `chatin` or `chatlog`). After a sync that doesn't contain it, the app sends it again in a new envelope (a fresh `id` and `ts`) with the same `cid`, oldest first and one after another, so they keep their order. Any message from the phone while one of the app's messages has waited over a minute for "Delivered" starts such a sync, so a phone coming back on gets them without the parent reopening the app. The phone stores each `cid` once, so a resend or a replayed message is never stored twice. This keeps the phone's usual freshness window (6 hours) for chat too: a late original is dropped and its resend arrives fresh.
- **The child's messages** are stored first, then published with their `seq`, and kept in the phone's outbox until the hub accepts them (like low-battery alerts), so a message typed with no connection is sent when it comes back.
- **Flood guard:** the hub limits each household's address (a burst of about 120 requests, then one every 2 seconds, and 5,000 messages a day), and SOS alerts, location and the parents' commands share those limits. So the phone sends the child's messages at least 3 seconds apart, and a minute apart once 500 have gone out in 24 hours. Nothing the child writes is refused; a message only waits its turn. The maintainer approved this alongside the parents' own controls.
- **Seen:** when the child views the chat, the phone publishes `chatread` with the highest `seq` shown, at most once a minute; a mark that couldn't be sent goes out with the next outbox flush.
- **A new pairing starts a new chat.** When the phone's pairing secret changes (paired again, or released), its chat history, numbering, limits and read mark are deleted, so the old thread is never offered to whoever pairs next.
- **Parents' unread counts** are per parent app: messages after the last one shown on that app, not counting its own.
- **Robustness:** chat messages and the stored history are parsed defensively; a malformed message is dropped and logged, never allowed to stop the background service. History is kept the way the location log keeps its entries, in the app's private storage.
- **Older apps:** the phone advertises `can.chat` in its full `state`; parent apps show Chat only for phones that do. An older parent app ignores chat messages and sees no chat until it updates.

## Rejected

- **A built-in limit that refuses the child's messages.** The maintainer chose parent controls (slow mode and timeout) instead; the flood guard only spaces sends out.
- **Parents as the record.** Each parent app would hold a different partial history, and a new parent would see nothing.
- **Exempting chat from the freshness window.** Replay protection only remembers ids for a day; resending from the sender with a stable `cid` keeps the window and still loses nothing.
