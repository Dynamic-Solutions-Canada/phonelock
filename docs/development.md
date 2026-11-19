# Development

How to build, set up, control and release PhoneLock. The parent ⇄ phone message format is in [protocol.md](protocol.md), and the hub server in [server/README.md](../server/README.md).

## Build (WSL)

```bash
./build.sh            # -> lock/build/outputs/apk/release/lock-release.apk
```

`build.sh` builds the lock app and the GitHub flavour of the parent app (`companion/build/outputs/apk/github/release/companion-github-release.apk`). The Play flavour is `:companion:assemblePlayRelease`, built only when asked for.

Needs JDK 17, Node (the version in `.nvmrc`), Python 3, and the Android SDK with the platform set by `compileSdk` in `companion/build.gradle`, in `ANDROID_HOME` or `~/android-sdk`. Gradle comes from the committed wrapper (`./gradlew`), which downloads the pinned version.

Signing key and secrets live in `.secrets/` (git-ignored). Keep `.secrets/phonelock.keystore` safe: updates must be signed with the same key.

Debug builds are signed with the committed debug-only key in `keystore/debug.keystore` (passwords `android`), never the release key. A debug build cannot update over a release one, or the reverse, without an uninstall, and Maps shows only where the Maps key also allows the debug certificate.

## Checks

The root `package.json` holds the entry points. Gradle tasks go through `tools/gradle`, which runs the wrapper under a memory cap (`PHONELOCK_GRADLE_MEMORY`, default 4G, no swap) with one worker; check `free -m` first. In CI it runs the wrapper directly.

| Command | Proves | Does not prove |
| --- | --- | --- |
| `npm run validate` | Everything below, as CI runs it on every push and pull request | Device behaviour or the protocol path |
| `npm run format:check` | Kotlin, Java and Gradle sources match the Spotless format (`npm run format` rewrites them) | Behaviour |
| `npm run lint` | Android lint passes for the lock app and both parent-app flavours (debug) | Runtime behaviour |
| `npm run test` | The JVM unit tests pass (emergency numbers, screen guard, usage report, SPAKE2), and the release-notes script's tests | Device behaviour |
| `npm run check:l10n` | Every catalog in both apps has the same keys, placeholders and plurals as English | That the translations read well |
| `npm run check:release-notes` | Every version in `release-notes/` has all seven files, within Play's 500-character limit | That they read well |
| `npm run verify:android` | Debug builds of the lock app and both parent-app flavours compile | Release signing |
| `npm run package:android` | Release APKs and the Play bundle build and sign (needs `.secrets/`) | That they install or run |
| `npm run install:android` | Installs the debug parent app on the connected phone | Anything on the child's phone |
| `npm run test:device` | The on-device tests pass on the connected phone or emulator (the QR decoder on Android's image buffers); not in `validate`, since it needs a device. Uninstall a release build first: the debug build is signed with a different key | That the camera reads a real QR code (a real-phone scan before release, in the `release` skill) |

`tools/test-remote.sh` is a manual check against a paired phone and is not part of `validate`; its header says what a pass looks like.

### Third-party notices

Each parent-app build carries `assets/third_party_notices.txt`, which About > Third-party notices shows. `companion/third-party-notices.gradle` generates it per variant with the AboutLibraries Gradle plugin (no runtime library), and the build fails when a dependency's licence isn't on the allowed list there, or the list misses a module on the runtime classpath. A new dependency under a licence not on the list needs that licence read and added, for that dependency only where possible. The lock app has no third-party runtime dependencies, so it has no notices file.

### Lint baseline

Lint issues already in the code when lint joined `validate` are recorded in `lock/lint-baseline.xml` and `companion/lint-baseline.xml`, so only new issues fail. The baseline only shrinks: fix or deliberately suppress an entry (with a comment saying why), then regenerate it with `tools/gradle :lock:updateLintBaselineDebug :companion:updateLintBaselineGithubDebug`. Open work, largest first:

- **MissingPermission** (lock app): calls that rely on permissions the lock app holds as device owner or checks at runtime; each needs a check or a reasoned suppression.
- **WrongConstant** (lock app): user restriction keys and a Do Not Disturb filter passed as values lint doesn't recognise.
- **GestureBackNavigation** (lock app): `onBackPressed` overrides that back gestures no longer call; move to `OnBackPressedDispatcher`.
- **QueryAllPackagesPermission** (lock app): needed by a launcher that lists every app; suppress with that reason, and justify it if the lock app is ever submitted to a store.
- The parent app's 5 errors and both apps' warnings (StaticFieldLeak, UseKtx, ApplySharedPref and others): see the baseline files.

## Where data lives

Everything below is in the app's private storage, which Android deletes on uninstall, except where it says shared storage. Neither app is in Android's backup (`allowBackup="false"` in both manifests). This list is unconfirmed until a real uninstall of each app has been checked.

Parent app:

- **Preferences:** `hub` (paired devices and their keys, names, the disclosure), `devicedata`, `emergency`, `push`, `quiet`, `screenalerts`, `ui`, and `appupdate` in the GitHub build.
- **Files:** `app-time.json`, `location-history.json`, `app-history.json`, `icons/` (app icons from the child's phone), `screen-alerts/` (screenshots of unfamiliar screens, at most 20 per device), and `adbkey.pk8` with `adbkey.crt` (this phone's identity for setting up a child's phone over Wireless debugging).
- **Cache:** the downloaded `lock-release.apk` and, in the GitHub build, `parent-update.apk`; `upload.tmp` while a file is sent; `opened/` for files opened from the child's phone.
- **Shared storage:** `Download/PhoneLock/`, for files a parent downloads from the child's phone. It stays after uninstall.

Lock app:

- **Preferences:** `phonelock` (policy, PIN, pairing key and settings).
- **Files:** `track.json` (the location log), `app_days.json` (in device-protected storage, so time limits work before the phone is unlocked), and `update.apk` while an update installs.
- **Shared storage:** files a parent sends to the child's phone are saved in its shared storage and stay there.
- **Device policy:** the device-owner restrictions stay in force until the lock app is released (see adb control below).

The hub keeps encrypted messages for 3 days and encrypted attachments for 24 hours (`server/server.yml`); see [server/README.md](../server/README.md).

## Hub

Both apps talk through an [ntfy](https://ntfy.sh) server. `server/` runs our own (ntfy in Docker behind the apps host's shared nginx, Cloudflare-proxied) with Firebase Cloud Messaging, so parent phones get messages by push instead of a permanent background connection; see [server/README.md](../server/README.md). The parent app reads the hub address and Firebase settings from `.secrets/firebase.properties` (`hubUrl`, `appId`, `apiKey`, `projectId`, `senderId`); without it, it uses ntfy.sh and its own connection. That connection exists only in the GitHub build: the Play build has no background connection, so it relies on the hub's push and accepts only phones on the hub.

## Languages

The parent app's list lives in `companion/src/main/res/xml/locales_config.xml`, and the lock app's in `lock/src/main/res/xml/locales_config.xml`; keep the two lists the same. English wording is in `res/values/strings*.xml`, one file per area, and each language has the same files under `values-<language>/` with the same names. Parent-app code with no screen to draw on (notifications, services) gets its wording through `Strings`.

Release builds fail if a language is missing a string, has an extra one, or has mismatched placeholders, so new wording needs every translation before a release. The translations were written with AI assistance and have not been reviewed by native speakers yet. Names, app labels and what people type are never translated. Wording the child reads uses the informal "you" (French "tu", German "du"). The diagnostics report stays in English for support.

## Publish a release

`tools/publish-release.sh` publishes both APKs with `lock-version.json` and `parent-version.json` to the GitHub releases the setup code downloads from. Installed lock apps check a few times a day and update themselves the next time the screen is off, and the parent app (GitHub build) offers its update in Settings > About. Anything published reaches children's phones unattended, so only publish a build already tested on a device.

The full procedure is the `release` skill (`.agents/skills/release/SKILL.md`). In short: the version is `1.0.<commit count>`, so the notes for the next version are committed as the last commit before publishing. They live in `release-notes/1.0.N.md` (en-US) with one sibling per other wording catalog (`1.0.N.es-ES.md`, and `fr-FR`, `de-DE`, `zh-CN`, `pt-BR`, `ja-JP`), written for users, under 500 characters, with no heading or disclosure line (`npm run check:release-notes` checks them). `tools/publish-release.sh` refuses to run without them, and builds the Release body from the en-US file, a "Get it" section and the AI disclosure line. It never falls back to a fixed or generated text.

```bash
tools/publish-release.sh
```

## Install on the child's phone with adb

The phone must have **no accounts** when the device owner is set (add them back afterwards).

```bash
adb install lock-release.apk
adb shell dpm set-device-owner home.phonelock/.AdminReceiver
```

Then pair it and switch on screen view and ringing through Do Not Disturb (needs USB debugging, so do it before `debug off`). It also exempts the lock app from Doze, so location logging keeps working while the phone sits still.

```bash
tools/configure-lock.sh <slug> [adb-serial]
```

## adb control

```bash
adb shell am broadcast -n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG --es cmd <cmd> --es value <value>
```

| cmd | value | |
|---|---|---|
| `status` | | current state |
| `pin` | digits | parent PIN (hold the clock 4 s on the home screen) |
| `apps` | `pkg1,pkg2,…` | home-screen apps, in order |
| `support` | `pkg,…` | allowed inside the lock but not shown |
| `hide` | `pkg,…` | always hidden |
| `parent` | minutes | parent mode (Settings + Play Store unlocked) |
| `lock` | | end parent mode |
| `remote` | topic or `server\|topic` | ntfy topic for remote commands |
| `say` | command text | run a remote command locally |
| `dns` | hostname or empty | lock Private DNS |
| `debug` | `on`/`off` | keep USB debugging while locked |
| `release` | | remove all policy and stop being device owner |
