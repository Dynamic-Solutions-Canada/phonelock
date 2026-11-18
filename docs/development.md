# Development

How to build, set up, control and release PhoneLock. The parent ⇄ phone message format is in [protocol.md](protocol.md), and the hub server in [server/README.md](../server/README.md).

## Build (WSL)

```bash
./build.sh            # -> lock/build/outputs/apk/release/lock-release.apk
```

`build.sh` builds the lock app and the GitHub flavour of the parent app (`companion/build/outputs/apk/github/release/companion-github-release.apk`). The Play flavour is `:companion:assemblePlayRelease`, built only when asked for.

Needs JDK 17, the Android SDK in `~/android-sdk` with the platform set by `compileSdk` in `companion/build.gradle`, and the Gradle version that `build.sh` runs from `~/tools`. Cap memory for every build, as `tools/publish-release.sh` does: one worker and in-process Kotlin.

Signing key and secrets live in `.secrets/` (git-ignored). Keep `.secrets/phonelock.keystore` safe: updates must be signed with the same key.

Debug builds are signed with the committed debug-only key in `keystore/debug.keystore` (passwords `android`), never the release key. A debug build cannot update over a release one, or the reverse, without an uninstall, and Maps shows only where the Maps key also allows the debug certificate.

The unit tests run with the same Gradle: `:lock:testDebugUnitTest :companion:testGithubDebugUnitTest`.

## Hub

Both apps talk through an [ntfy](https://ntfy.sh) server. `server/` runs our own (ntfy in Docker behind the apps host's shared nginx, Cloudflare-proxied) with Firebase Cloud Messaging, so parent phones get messages by push instead of a permanent background connection; see [server/README.md](../server/README.md). The parent app reads the hub address and Firebase settings from `.secrets/firebase.properties` (`hubUrl`, `appId`, `apiKey`, `projectId`, `senderId`); without it, it uses ntfy.sh and its own connection.

## Languages

The parent app's list lives in `companion/src/main/res/xml/locales_config.xml`, and the lock app's in `lock/src/main/res/xml/locales_config.xml`; keep the two lists the same. English wording is in `res/values/strings*.xml`, one file per area, and each language has the same files under `values-<language>/` with the same names. Parent-app code with no screen to draw on (notifications, services) gets its wording through `Strings`.

Release builds fail if a language is missing a string, has an extra one, or has mismatched placeholders, so new wording needs every translation before a release. The translations were written with AI assistance and have not been reviewed by native speakers yet. Names, app labels and what people type are never translated. Wording the child reads uses the informal "you" (French "tu", German "du"). The diagnostics report stays in English for support.

## Publish a release

`tools/publish-release.sh` publishes both APKs with `lock-version.json` and `parent-version.json` to the GitHub releases the setup code downloads from. Installed lock apps check a few times a day and update themselves the next time the screen is off, and the parent app (GitHub build) offers its update in Settings. Anything published reaches children's phones unattended, so only publish a build already tested on a device.

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
