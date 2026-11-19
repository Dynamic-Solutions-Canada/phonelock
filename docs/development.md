# Development

How to build, set up, control and release PhoneLock. The parent ⇄ phone message format is in [protocol.md](protocol.md), and the hub server in [server/README.md](../server/README.md).

## Build

The Gradle project is in `android/`, and every Gradle task goes through `scripts/dev/gradle`, which runs it in a disposable container from the `phonelock-build` image (`android/docker/Dockerfile`: JDK 17, the Android SDK packages the build needs, and git) under a hard memory cap. Nothing but Docker is needed on the host for the Android build. Build the image once, and again when its Dockerfile changes:

```bash
npm run build:image
```

Then, for the release APKs:

```bash
scripts/deploy/build.sh            # -> android/lock/build/outputs/apk/release/lock-release.apk
```

`scripts/deploy/build.sh` builds the lock app and the GitHub flavour of the parent app (`android/companion/build/outputs/apk/github/release/companion-github-release.apk`). The Play flavour is `:companion:assemblePlayRelease`, built only when asked for. It refuses to run without the release key in `.secrets/` and never creates one: every installed copy accepts only updates signed by the existing key.

The npm checks need Node (the version in `.nvmrc`, with `nvm use`; `npm ci` installs the dev tools) and Python 3. `PHONELOCK_GRADLE=local` runs Gradle on this machine instead, with JDK 17 and an Android SDK in `ANDROID_HOME` or `~/android-sdk`; `npm run test:device` does, because the container can't reach a connected phone. Gradle itself comes from the committed wrapper (`android/gradlew`), and its cache lives in `android/.gradle`.

Signing key and secrets live in `.secrets/` (git-ignored). Keep `.secrets/phonelock.keystore` safe: updates must be signed with the same key.

Debug builds are signed with the committed debug-only key in `android/keystore/debug.keystore` (passwords `android`), never the release key. A debug build cannot update over a release one, or the reverse, without an uninstall, and Maps shows only where the Maps key also allows the debug certificate.

## Checks

The root `package.json` holds the entry points. Gradle tasks go through `scripts/dev/gradle`, which runs the wrapper in the build container (or locally with `PHONELOCK_GRADLE=local`) under a memory cap (`PHONELOCK_GRADLE_MEMORY`, default 4G, no swap) with one worker; check `free -m` first and keep the cap plus 2.5 GiB free. In CI it runs the wrapper directly.

| Command | Proves | Does not prove |
| --- | --- | --- |
| `npm run validate` | Everything below, as CI runs it on every push and pull request | Device behaviour or the protocol path |
| `npm run format:check` | Kotlin, Java and Gradle sources match the Spotless format (`npm run format` rewrites them) | Behaviour |
| `npm run lint` | Android lint passes for the lock app and both parent-app flavours (debug) | Runtime behaviour |
| `npm run test` | The JVM unit tests pass (emergency numbers, screen guard, usage report, SPAKE2), and the release-notes script's tests | Device behaviour |
| `npm run check:l10n` | Every catalog in both apps has the same keys, placeholders and plurals as English | That the translations read well |
| `npm run check:prefs` | The parent app reads and writes preferences only through `Prefs.kt`, which owns every file, key and default | That a key's meaning is right |
| `npm run check:listings` | `store-assets/play/listings.json` has the app's nine locales in order, aliases that resolve, and every field within Play's limits | That the listing reads well, or that its claims are still true (see `store-assets/play/listings.md`) |
| `npm run check:release-notes` | Every version in `release-notes/` has all seven files, within Play's 500-character limit | That they read well |
| `npm run verify:android` | Debug builds of the lock app and both parent-app flavours compile | Release signing |
| `npm run package:android` | Release APKs and the Play bundle build and sign (needs `.secrets/`) | That they install or run |
| `npm run install:android` | Installs the debug parent app on the connected phone | Anything on the child's phone |
| `npm run test:device` | The on-device tests pass on the connected phone or emulator (the QR decoder on Android's image buffers); not in `validate`, since it needs a device. Uninstall a release build first: the debug build is signed with a different key | That the camera reads a real QR code (a real-phone scan before release, in the `release` skill) |

`scripts/dev/test-remote.sh` is a manual check against a paired phone and is not part of `validate`; its header says what a pass looks like.

### Icons

`npm run icons` (devkit-icons, from devkit) writes every launcher, notification and Play icon from the SVG masters in `assets/brand/`, and `npm run check:icons` fails when a committed icon differs from what the masters give. `check:icons` is part of `validate`. Both apps share one mark; how the masters are drawn, and the limits devkit puts on them, are in `assets/brand/README.md`. Run npm with the Node version in `.nvmrc` (`nvm use`), since `.npmrc` refuses any other.

### Third-party notices

Each parent-app build carries `assets/third_party_notices.txt`, which About > Third-party notices shows. `android/companion/build.gradle.kts` configures it and `android/buildSrc/src/main/kotlin/RenderThirdPartyNotices.kt` renders it per variant with the AboutLibraries Gradle plugin (no runtime library), and the build fails when a dependency's licence isn't on the allowed list there, or the list misses a module on the runtime classpath. A new dependency under a licence not on the list needs that licence read and added, for that dependency only where possible. The lock app has no third-party runtime dependencies, so it has no notices file.

### Lint baseline

Lint issues already in the code when lint joined `validate` are recorded in `android/lock/lint-baseline.xml` and `android/companion/lint-baseline.xml`, so only new issues fail. The baseline only shrinks: fix or deliberately suppress an entry (with a comment saying why), then regenerate it with `scripts/dev/gradle :lock:updateLintBaselineDebug :companion:updateLintBaselineGithubDebug`. Open work, largest first:

- **MissingPermission** (lock app): calls that rely on permissions the lock app holds as device owner or checks at runtime; each needs a check or a reasoned suppression.
- **WrongConstant** (lock app): user restriction keys and a Do Not Disturb filter passed as values lint doesn't recognise.
- **GestureBackNavigation** (lock app): `onBackPressed` overrides that back gestures no longer call; move to `OnBackPressedDispatcher`.
- **QueryAllPackagesPermission** (lock app): needed by a launcher that lists every app; suppress with that reason, and justify it if the lock app is ever submitted to a store.
- The parent app's 5 errors and both apps' warnings (StaticFieldLeak, UseKtx, ApplySharedPref and others): see the baseline files.

## Where data lives

Everything below is in the app's private storage, which Android deletes on uninstall, except where it says shared storage. Neither app is in Android's backup or a transfer to a new phone (`allowBackup="false"` and `data_extraction_rules.xml`, which excludes everything, in both manifests). Pairing keys and the parent app's adb key are sealed with a key held by the phone's keystore (`SecretBox` in each app), so a copy of the files can't be read on another phone. This list is unconfirmed until a real uninstall of each app has been checked.

Parent app:

- **Preferences:** `hub` (paired devices with their sealed keys, names, the disclosure), `devicedata`, `emergency`, `push`, `quiet`, `screenalerts`, `ui`, and `appupdate` in the GitHub build.
- **Files:** `app-time.json`, `location-history.json`, `app-history.json`, `icons/` (app icons from the child's phone), `screen-alerts/` (screenshots of unfamiliar screens, at most 20 per device), and `adbkey.pk8.enc` (sealed) with `adbkey.crt`: this phone's identity for setting up a child's phone over Wireless debugging. A plain `adbkey.pk8` from an older version is sealed on first use.
- **Cache:** the downloaded `lock-release.apk` and, in the GitHub build, `parent-update.apk`; `upload.tmp` while a file is sent; `opened/` for files opened from the child's phone.
- **Shared storage:** `Download/PhoneLock/`, for files a parent downloads from the child's phone. It stays after uninstall.

Lock app:

- **Preferences:** `phonelock` (policy, PIN hash, sealed pairing key and settings). A plain key from an older version is sealed on first start; `adb shell am broadcast ... --es cmd status` reports `key=lost` if the phone can no longer read it, and the home screen asks the child to have a parent set it up again (Fix setup over Wi-Fi re-sends it).
- **Files:** `track.json` (the location log), `app_days.json` (in device-protected storage, so time limits work before the phone is unlocked), and `update.apk` while an update installs.
- **Shared storage:** files a parent sends to the child's phone are saved in its shared storage and stay there.
- **Device policy:** the device-owner restrictions stay in force until the lock app is released (see adb control below).

The hub keeps encrypted messages for 3 days and encrypted attachments for 24 hours (`server/server.yml`); see [server/README.md](../server/README.md).

## Hub

Both apps talk through an [ntfy](https://ntfy.sh) server. `server/` runs our own (ntfy in Docker behind the apps host's shared nginx, Cloudflare-proxied) with Firebase Cloud Messaging, so parent phones get messages by push instead of a permanent background connection; see [server/README.md](../server/README.md). The parent app reads the hub address and Firebase settings from `.secrets/firebase.properties` (`hubUrl`, `appId`, `apiKey`, `projectId`, `senderId`); without it, it uses ntfy.sh and its own connection. That connection exists only in the GitHub build: the Play build has no background connection, so it relies on the hub's push and accepts only phones on the hub.

## Languages

The parent app's list lives in `android/companion/src/main/res/xml/locales_config.xml`, and the lock app's in `android/lock/src/main/res/xml/locales_config.xml`; keep the two lists the same. English wording is in `res/values/strings*.xml`, one file per area, and each language has the same files under `values-<language>/` with the same names. Parent-app code with no screen to draw on (notifications, services) gets its wording through `Strings`.

Release builds fail if a language is missing a string, has an extra one, or has mismatched placeholders, so new wording needs every translation before a release.

Review status of every wording catalog (LOC-12). Update the row when a native speaker reviews one.

| Catalog | Languages | Status |
| --- | --- | --- |
| Parent app (`android/companion/src/main/res/values*/strings*.xml`) | en-US source; es, fr, de, zh-Hans, pt, ja | AI-drafted, not reviewed |
| Lock app (`android/lock/src/main/res/values*/strings*.xml`) | en-US source; es, fr, de, zh-Hans, pt, ja | AI-drafted, not reviewed |
| Release notes (`release-notes/`) | en-US source; es-ES, fr-FR, de-DE, zh-CN, pt-BR, ja-JP | AI-drafted, not reviewed |
| Play listing (`store-assets/play/listings.json`) | en-US source; es-ES, fr-FR, de-DE, zh-CN, pt-BR, ja-JP (en-CA and fr-CA copy en-US and fr-FR) | AI-drafted, not reviewed |

Product terms are translated the same way everywhere, so the listing, the release notes and both apps agree: take a term's translation from the parent app's catalog (for example **parent mode** `device_parent_mode`, **normal hours**, **limited hours** and **bedtime** `apps_mode_*`, **Share with another parent** `device_share`, **Fix setup over Wi-Fi** `device_fix_setup`, and Android's own name for **Wireless debugging** in each language). The Play short description is also the About tagline (`about_tagline`), word for word. Names, app labels and what people type are never translated. Wording the child reads uses the informal "you" (French "tu", German "du"). The diagnostics report stays in English for support.

## Publish a release

`scripts/deploy/publish-release.sh` publishes both APKs with `lock-version.json` and `parent-app-version.json`, plus the parent app's last release under its old id (`PhoneLock-parent.apk` and `parent-version.json`, pinned by `LEGACY_PARENT_RELEASE` so old installs never fetch an app they can't install), to the GitHub releases the setup code downloads from. Installed lock apps check a few times a day and update themselves the next time the screen is off, and the parent app (GitHub build) offers its update in Settings > About. Anything published reaches children's phones unattended, so only publish a build already tested on a device.

The full procedure is the `release` skill (`.agents/skills/release/SKILL.md`). In short: the version is `1.0.<commit count>`, so the notes for the next version are committed as the last commit before publishing. They live in `release-notes/1.0.N.md` (en-US) with one sibling per other wording catalog (`1.0.N.es-ES.md`, and `fr-FR`, `de-DE`, `zh-CN`, `pt-BR`, `ja-JP`), written for users, under 500 characters, with no heading or disclosure line (`npm run check:release-notes` checks them). `scripts/deploy/publish-release.sh` refuses to run without them, and builds the Release body from the en-US file, a "Get it" section and the AI disclosure line. It never falls back to a fixed or generated text.

```bash
scripts/deploy/publish-release.sh
```

## Install on the child's phone with adb

The phone must have **no accounts** when the device owner is set (add them back afterwards).

```bash
adb install lock-release.apk
adb shell dpm set-device-owner home.phonelock/.AdminReceiver
```

Then pair it and switch on screen view and ringing through Do Not Disturb (needs USB debugging, so do it before `debug off`). It also exempts the lock app from Doze, so location logging keeps working while the phone sits still.

```bash
scripts/dev/configure-lock.sh <slug> [adb-serial]
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
