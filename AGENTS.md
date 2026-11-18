# PhoneLock: Agent Instructions

PhoneLock is two Android apps for parents who lock down a child's phone. The lock app (`lock/`, plain Java) is a device-owner home screen on the child's phone. The parent app (`companion/`, Kotlin and Compose) sets that phone up over Wireless debugging, pairs with it, and controls it. The two talk through an encrypted ntfy topic, on ntfy.sh or the self-hosted hub in `server/`. Both apps ship as GitHub Releases on this public repo and update themselves from them. The parent app also has a Play flavour, which isn't published yet. It builds on no shared kits.

## Standards

This repo follows the shared standards at https://github.com/Dynamic-Solutions-Canada/app-standards. Read the index for this stack, https://github.com/Dynamic-Solutions-Canada/app-standards/blob/main/stacks/android.md, then the topic file for the area you are changing. If `external/standards/` exists, it is the same content pinned for offline use. Where this file differs from the standards, this file wins and says why.

## Architecture

`PROTOCOL.md` is the source of truth for every message between the parent app and the phone. A protocol change updates it in the same commit as both apps.

| Area | Files |
| --- | --- |
| Lock app: policy, kiosk mode, restrictions | `lock/src/main/java/home/phonelock/Policy.java`, `AdminReceiver.java`, `HomeActivity.java` |
| Lock app: messages from and to parents | `Remote.java`, `RemoteService.java`, `Outbox.java`, `Crypto.java`, `Pairing.java` |
| Lock app: adb config broadcasts (pairing, release) | `ConfigReceiver.java` |
| Lock app: self-update | `Updater.java` |
| Parent app: screens and navigation | `companion/src/main/java/home/phonelock/parent/MainActivity.kt`, `DeviceScreens.kt`, `SettingsScreen.kt` |
| Parent app: phone-to-phone setup and its adb client | `ChildSetup.kt`, `WifiSetupScreen.kt`, `Setup.kt`, `adb/` |
| Parent app: transport, push and encryption | `Hub.kt`, `Push.kt`, `ParentService.kt`, `Crypto.kt` |
| Parent app: downloads and signer checks | `Releases.kt`; self-update in `companion/src/github/.../AppUpdate.kt`, the Play stub in `companion/src/play/` |
| Wording for code with no Compose context | `Strings.kt` |
| Languages | `res/xml/locales_config.xml` and `res/values*/` in each app |
| Hub server | `server/` (see `server/README.md`) |
| Release, hub deploy, adb and test scripts | `tools/` |
| Play listing drafts and open questions | `play/` |

## Behaviour

- **Anything published reaches children's phones unattended.** `tools/publish-release.sh` publishes a GitHub Release; the lock app installs it by itself when the screen is off, and a lock app that crashes on start can't be fixed remotely. Never run it, or `tools/deploy-hub.sh`, unless the maintainer asks; only a build tested on a device gets published.
- **Release asset names are a permanent interface.** Installed apps fetch `lock-release.apk`, `lock-version.json`, `PhoneLock-parent.apk` and `parent-version.json` from `releases/latest/download/` by those exact names. Don't rename them.
- **The lock app's certificate is pinned.** `Setup.LOCK_SIGNATURE_SHA256` is the release key's certificate hash. Setup refuses a downloaded lock app signed by anything else, and both updaters require the installed app's signer. Debug builds keep the release pin, because setup always downloads the released lock app.
- **The version code is the commit count** (`git rev-list --count HEAD`, in the root `build.gradle`). History is never rewritten: a lower count would make every installed copy refuse the update.
- **Messages are end-to-end encrypted.** The topic name alone must never be enough to control a phone, and the hub only relays ciphertext.

## Conventions and gotchas

- Every third-party dependency has an `// ... (approved: <what>)` comment in its `build.gradle`; adding one needs the maintainer's approval, recorded the same way. The lock app has no third-party runtime dependencies, and it excludes the Kotlin standard library on purpose.
- Every dialog in the parent app uses `AppDialog`, and every list row uses `ListRow`.
- Parent-app screens use `stringResource()`; services and notifications use `Strings`.
- Every string exists in all nine locales with the same placeholders, or release lint fails. Keep the two apps' `locales_config.xml` lists the same. Translations are AI-written and unreviewed.
- Debug builds are signed with the committed debug-only key in `keystore/debug.keystore` (passwords `android`). The production key signs releases only. A debug build can't update over a release one, or the reverse.
- The `github` flavour has the self-updater and install permission; the `play` flavour has neither, because Play forbids self-updating.
- Signing material, Firebase settings and pairing data live in the git-ignored `secrets/` folder. Never open, print or copy anything there.

## Commands

| Command | Proves | Does not prove |
| --- | --- | --- |
| `./build.sh` | Both release APKs compile, pass the translation lint, and are signed (needs the signing files in `secrets/`) | That they install or run |
| `:lock:assembleDebug :companion:assembleGithubDebug`, with the Gradle `build.sh` runs | Debug builds compile, with no secrets needed | Release lint or signing |
| `:lock:testDebugUnitTest :companion:testGithubDebugUnitTest`, with the same Gradle | The JUnit tests pass (emergency numbers, screen guard, usage report, adb) | Device behaviour |
| `tools/test-remote.sh` | A paired phone answers encrypted commands over the topic | Anything without a paired test phone |

Cap memory for every build, as `tools/publish-release.sh` does: one worker and in-process Kotlin.

## Release

`tools/publish-release.sh` builds from the pushed `HEAD` and creates release `v1.0.<commit count>` with the four fixed assets. Run it only when the maintainer asks, after a device test.

## Guidance map

| Guide, doc, or skill | Read or use when | Governs |
| --- | --- | --- |
| `PROTOCOL.md` | Any message, field or encryption change | `Remote.java`, `Hub.kt`, `Crypto.*`, `tools/ParentCli.java` |
| `README.md` | Building, setting up a child's phone, adb control, languages | `build.sh`, `tools/`, `res/values*/` |
| `server/README.md` | Changing or deploying the hub | `server/`, `tools/deploy-hub.sh` |
| `PRIVACY.md` | Changing what either app sends, stores or shares | Both apps, `Disclosure.kt` |
| `play/` | Play listing, data safety and Play-flavour questions | `companion/src/play/`, the `play` flavour |

## Keeping docs current

| What changed | Update |
| --- | --- |
| A source file, area or script added, moved or removed | The Architecture table above |
| A message type, field or the encryption | `PROTOCOL.md`, both apps, `tools/ParentCli.java` |
| What either app sends, stores or who can read it | `PRIVACY.md`, `play/data-safety.md` |
| Build, setup, adb or release procedure | `README.md`, Commands above |
| Interface text or languages | Every `values*/strings*.xml` in that app; the README's Languages section |
| A dependency added | Its `approved:` comment, and `PRIVACY.md` if it talks to a network |
| Hub configuration or deployment | `server/README.md` |
