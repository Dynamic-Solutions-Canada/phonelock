# PhoneLock

Two Android apps for locking down a child's phone:

- **lock/** – device-owner app installed on the child's phone. It is the home screen, keeps the phone in
  lock-task (kiosk) mode with an allowlist of apps, hides everything else, and applies user restrictions
  (no SMS, no installs from unknown sources, no account changes, no factory reset, no safe mode, …).
  Parents can grant apps temporarily over [ntfy](https://ntfy.sh).
- **companion/** – parent app: sets up and pairs child phones, answers requests, grants and revokes apps, finds, rings and views the phone.

## Hub (server)

Both apps talk through an [ntfy](https://ntfy.sh) server. `server/` runs our own (ntfy in Docker behind the apps host's shared nginx, Cloudflare-proxied) with Firebase Cloud Messaging, so parent phones get messages by push instead of a permanent background connection; see `server/README.md`. The parent app reads the hub address and Firebase settings from `secrets/firebase.properties` (`hubUrl`, `appId`, `apiKey`, `projectId`, `senderId`); without it, it uses ntfy.sh and its own connection. Only the parent app is meant for Google Play; the parent app installs the lock app on the child device over Wireless debugging.

## Languages

The parent app is available in the same languages as Persistent and Little Launcher: English (US and Canada), Spanish (Spain), French (France and Canada), German, Simplified Chinese, Brazilian Portuguese and Japanese. Canadian English and Canadian French use the English and French wording, with their own date and number formats. Other languages fall back to English; Traditional Chinese never gets the Simplified wording.

It follows the phone's language unless the parent picks one in a device's Settings > Language, which is the same setting as Android's per-app language. The list lives in `companion/src/main/res/xml/locales_config.xml`. English wording is in `companion/src/main/res/values/strings*.xml`, one file per area, and each language has the same files under `values-<language>/` with the same names. Code with no screen to draw on (notifications, services) gets its wording through `Strings`.

Release builds fail if a language is missing a string, has an extra one, or has mismatched placeholders, so new wording needs all six translations before a release. The translations were written with AI assistance and have not been reviewed by native speakers yet. Names, app labels and what people type are never translated. The privacy policy stays in English. Entries already in Recent events keep the language they were written in. Phone number layout and the first day of the week follow the phone's region, not the language picked in the app.

The lock app on the child's phone offers the same languages (`lock/src/main/res/xml/locales_config.xml`; keep the two lists the same). A parent sets it per device in that device's Settings > Lock app > language, and phone-to-phone setup starts it in the parent app's language. The activity it reports to parents is written in that language, on the assumption that parents and child share one. Wording the child reads uses the informal "you" (French "tu", German "du"). The diagnostics report stays in English for support. Phones paired before the lock app had languages stay in English after updating, until a parent picks one; phones set up since follow the setup's language or the device's.

The SOS screen shows and dials the local emergency number from Android's emergency number database: the country's main number (911, 999, 000, 112, and 911 in Canada and 111 in New Zealand, where two are listed), or the ambulance number where there are only separate ones (119 in Japan, 120 in China, 192 in Brazil). Without a SIM, or when the phone doesn't know its country, it shows 911.

## Build (WSL)

```bash
./build.sh            # -> lock/build/outputs/apk/release/lock-release.apk
```

Needs JDK 17, Android SDK (`~/android-sdk`, platform 36, build-tools 36) and Gradle 8.11.1 in `~/tools`.
Signing key and secrets live in `secrets/` (git-ignored). Keep `secrets/phonelock.keystore` safe: updates must be
signed with the same key.

## Set up a child's device from a parent's phone

In the parent app, tap **Set up a child's device** and follow the steps it shows. On the child's device (Android 13 or later, on the same Wi-Fi, with no accounts signed in): turn on Developer options, then Wireless debugging > **Pair device with pairing code**. The parent app finds it, pairs with the code, installs the lock app from the public release (checking it is signed with our key), makes it device owner, pairs it with the parent app and sets the parent PIN, turns on the features that need a one-time grant (screen view, call roles, call log, files, Do Not Disturb, battery saver exemption), confirms the device answers through the hub, then switches debugging off. Nothing is erased. Add the child's accounts back afterwards in parent mode (hold the clock 4 s, enter the PIN). The second parent adds the device from the first parent's app: device settings > **Share with another parent**. **Fix setup over Wi-Fi** in the device settings re-applies the one-time grants on a device already set up.

Publishing a release (both APKs with `lock-version.json` and `parent-version.json`, to the GitHub releases the setup code downloads from; installed lock apps check a few times a day and update themselves the next time the screen is off, and the parent app (GitHub build) offers its update in Settings):

```bash
tools/publish-release.sh <version>
```

## Install on the child's phone with adb

The phone must have **no accounts** when the device owner is set (add them back afterwards).

```bash
adb install lock-release.apk
adb shell dpm set-device-owner home.phonelock/.AdminReceiver
```

Then pair it and switch on screen view and ringing through Do Not Disturb (needs USB debugging, so do it before `debug off`):

```bash
tools/configure-lock.sh <slug> [adb-serial]
```

Parents can then find the phone (opens Google Maps), ring it at full volume and view its screen from the parent app. The kid gets a notice whenever a parent views the screen, both parents' activity logs record every view, and location is kept switched on.

The device also logs its location every 15 minutes (kept 7 days, shown in the parent app's Location history, and sent late rather than lost when offline) and reports its battery with every message. Parents choose a low-battery alert level, and get "Turning off" and "Turned on" notices, so a dead battery can be told apart from lost signal. Her home screen says that location and battery are shared. `tools/configure-lock.sh` exempts the lock app from Doze so this keeps working while the device sits still.

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
