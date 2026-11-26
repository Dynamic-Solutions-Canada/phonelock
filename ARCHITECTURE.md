# PhoneLock architecture

```
Parent app (android/companion)                 Child's phone: lock app (android/lock)
  ├── setup: own adb client over Wireless ──►    device owner, kiosk home screen
  │   debugging (installs, makes device owner)   ├── Policy: restrictions, lock task, schedules
  ├── pairing: shared secret and topic           ├── Remote / RemoteService: commands in, events out
  └── Hub, Push, Crypto ◄── encrypted ntfy topic ──► Outbox, Crypto
                              (ntfy.sh or the hub in server/, which also pushes through Firebase)

GitHub Releases ──► the lock app's and the GitHub parent app's self-updaters (the lock app installs when the screen is off)
```

## Ownership and data flow

- **The lock app owns the phone.** It is the device owner and the home screen; every restriction is applied by `Policy` on the phone itself. After setup, the parent app changes the phone by sending commands and reads the events it sends back; it uses adb again only to fix setup over Wi-Fi.
- **The parent app owns setup.** It sets the phone up over Wireless debugging with its own adb client (no third-party adb library, see `docs/setup.md`), installs the released lock app, makes it device owner and pairs with it.
- **One encrypted topic per phone.** Both apps publish and subscribe on one private ntfy topic. Every message is end-to-end encrypted with the pairing secret, so the topic name alone can't control a phone and the hub relays only ciphertext. Every message and field is in `docs/protocol.md`, the source of truth for both apps.
- **The hub is optional for the GitHub build.** It is a self-hosted ntfy (`server/`) that also pushes each message through Firebase; the Play build of the parent app reaches phones only by that push, so it requires the hub.
- **The lock app and the GitHub build of the parent app update themselves from GitHub Releases**, by fixed asset names, and check the signer before installing. The Play build has no updater.

## Decisions that surprise newcomers

- The rules every change must keep (published builds reach phones unattended, fixed asset names, the pinned certificate, the commit-count version, end-to-end encryption) are under Behaviour in `AGENTS.md`, and the dependency rules under its conventions.
- A parent can release a phone, ending PhoneLock's control; the phone acts on it only within five minutes of it being sent, so a delayed message can't free a phone later.

## Where things are

| Area | Files |
| --- | --- |
| Lock app: policy, kiosk mode, restrictions | `android/lock/src/main/java/home/phonelock/Policy.java`, `AdminReceiver.java`, `HomeActivity.java` |
| Lock app: messages from and to parents | `Remote.java`, `RemoteService.java`, `Outbox.java`, `Crypto.java`, `Pairing.java` |
| Both apps: secrets sealed at rest by the keystore | `SecretBox.java` (lock app), `SecretBox.kt` and `DeviceStore.kt` (parent app) |
| Lock app: adb config broadcasts (pairing, release) | `ConfigReceiver.java` |
| Lock app: release (ending its control, from a parent or adb) | `Release.java` |
| Lock app: self-update | `Updater.java` |
| Lock app: family chat (design in `docs/chat.md`) | `ChatLog.java` (stored thread, messages, notifications), `ChatHistory.java` (its rules, unit tested), `ChatActivity.java` (the child's screen), `ServerClock.java` (the hub's clock, for chat times); a built-in app like Wi-Fi and Bluetooth (`BuiltInApps.java`, `ConnectionsActivity.java`), with the look they share in `ScreenStyle.java` |
| Parent app: family chat | `Chat.kt` (transport, sync, resends, notifications, sealed history), `ChatStore.kt` (thread rules, unit tested), `ChatScreen.kt` (the chat page, the Chat row and the slow mode and timeout controls) |
| Parent app: screens and navigation | `android/companion/src/main/java/home/phonelock/parent/MainActivity.kt`, `DeviceScreens.kt`, `SettingsScreen.kt`; `LengthDialog.kt`, the one "for how long" picker (usual lengths, a clock time or a custom length) |
| Parent app: phone-to-phone setup and its adb client | `ChildSetup.kt`, `WifiSetupScreen.kt`, `Setup.kt`, `adb/` |
| Parent app: transport, push and encryption | `Hub.kt`, `Push.kt`, `Crypto.kt`; the background connection in `android/companion/src/github/.../ParentService.kt` and `BackgroundConnection.kt`, the Play stub in `android/companion/src/play/` |
| Parent app: emergency (SOS) alerts, their alarm and pausing it per phone | `EmergencyAlerts.kt`, `EmergencyUi.kt`, `SosPause.kt` |
| Parent app: theme, About screen and third-party notices | `AppTheme.kt`, `AboutScreen.kt`; the notices file is generated from `android/companion/build.gradle.kts` by `android/buildSrc/.../RenderThirdPartyNotices.kt` |
| Parent app: downloads and signer checks | `Releases.kt`; self-update in `android/companion/src/github/.../AppUpdate.kt`, the Play stub in `android/companion/src/play/` |
| Wording for code with no Compose context | `Strings.kt` |
| Languages | `res/xml/locales_config.xml` and `res/values*/` in each app |
| Hub server | `server/` (see `server/README.md`) |
| Gradle project (both apps, wrapper, version catalog, debug key, build container) | `android/` (`gradle/libs.versions.toml`, `docker/Dockerfile`, `buildSrc/` for build logic) |
| Release and hub deploy scripts | `scripts/deploy/` |
| Build runner, checks, adb and test scripts | `scripts/dev/` |
| Command entry points, the capped Gradle runner, the translation and preferences checks | `package.json`, `scripts/dev/gradle`, `scripts/dev/check-l10n.py`, `scripts/dev/check-prefs.py` |
| Release notes and the Release body | `release-notes/`, `scripts/deploy/release-notes.mjs` |
| Brand masters and the icon generator's config (icons come from `npm run icons`, devkit-icons) | `assets/brand/` (see its README) |
| Play listing source (nine locales), console settings, data safety and open questions | `store-assets/play/listings.json`, `store-assets/play/listings.md`, checked by `scripts/dev/check-listings.mjs` |
| Phone-to-phone setup design record | `docs/setup.md` |
