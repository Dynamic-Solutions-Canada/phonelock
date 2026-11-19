# PhoneLock: Agent Instructions

PhoneLock is two Android apps for parents who lock down a child's phone. The lock app (`android/lock/`, plain Java) is a device-owner home screen on the child's phone. The parent app (`android/companion/`, Kotlin and Compose) sets that phone up over Wireless debugging, pairs with it, and controls it. The two talk through an encrypted ntfy topic, on ntfy.sh or the self-hosted hub in `server/`. Both apps ship as GitHub Releases on this public repo and update themselves from them. The parent app also has a Play flavour, which isn't published yet. It builds on no shared kits.

## Standards

This repo follows the shared standards at https://github.com/Dynamic-Solutions-Canada/app-standards. Read the index for this stack, https://github.com/Dynamic-Solutions-Canada/app-standards/blob/main/stacks/android.md, then the topic file for the area you are changing. If `external/standards/` exists, it is the same content pinned for offline use. Where this file differs from the standards, this file wins and says why.

## Architecture

`docs/protocol.md` is the source of truth for every message between the parent app and the phone. A protocol change updates it in the same commit as both apps.

| Area | Files |
| --- | --- |
| Lock app: policy, kiosk mode, restrictions | `android/lock/src/main/java/home/phonelock/Policy.java`, `AdminReceiver.java`, `HomeActivity.java` |
| Lock app: messages from and to parents | `Remote.java`, `RemoteService.java`, `Outbox.java`, `Crypto.java`, `Pairing.java` |
| Both apps: secrets sealed at rest by the keystore | `SecretBox.java` (lock app), `SecretBox.kt` and `DeviceStore.kt` (parent app) |
| Lock app: adb config broadcasts (pairing, release) | `ConfigReceiver.java` |
| Lock app: self-update | `Updater.java` |
| Parent app: screens and navigation | `android/companion/src/main/java/home/phonelock/parent/MainActivity.kt`, `DeviceScreens.kt`, `SettingsScreen.kt` |
| Parent app: phone-to-phone setup and its adb client | `ChildSetup.kt`, `WifiSetupScreen.kt`, `Setup.kt`, `adb/` |
| Parent app: transport, push and encryption | `Hub.kt`, `Push.kt`, `Crypto.kt`; the background connection in `android/companion/src/github/.../ParentService.kt` and `BackgroundConnection.kt`, the Play stub in `android/companion/src/play/` |
| Parent app: theme, About screen and third-party notices | `AppTheme.kt`, `AboutScreen.kt`; the notices file is generated from `android/companion/build.gradle.kts` by `android/buildSrc/.../RenderThirdPartyNotices.kt` |
| Parent app: downloads and signer checks | `Releases.kt`; self-update in `android/companion/src/github/.../AppUpdate.kt`, the Play stub in `android/companion/src/play/` |
| Wording for code with no Compose context | `Strings.kt` |
| Languages | `res/xml/locales_config.xml` and `res/values*/` in each app |
| Hub server | `server/` (see `server/README.md`) |
| Gradle project (both apps, wrapper, debug key) | `android/` |
| Release and hub deploy scripts | `scripts/deploy/` |
| Build runner, checks, adb and test scripts | `scripts/dev/` |
| Command entry points, the capped Gradle runner, the translation and preferences checks | `package.json`, `scripts/dev/gradle`, `scripts/dev/check-l10n.py`, `scripts/dev/check-prefs.py` |
| Release notes and the Release body | `release-notes/`, `scripts/deploy/release-notes.mjs` |
| Brand masters and the icon generator's config (icons come from `npm run icons`, devkit-icons) | `assets/brand/` (see its README) |
| Play listing source (nine locales), console settings, data safety and open questions | `store-assets/play/listings.json`, `store-assets/play/listings.md`, checked by `scripts/dev/check-listings.mjs` |
| Phone-to-phone setup design record | `docs/setup.md` |

## Behaviour

- **Anything published reaches children's phones unattended.** `scripts/deploy/publish-release.sh` publishes a GitHub Release; the lock app installs it by itself when the screen is off, and a lock app that crashes on start can't be fixed remotely. Run it only through the `release` skill, and `scripts/deploy/deploy-hub.sh` only when the maintainer asks; only a build tested on a device gets published.
- **Release asset names are a permanent interface.** Installed apps fetch `lock-release.apk`, `lock-version.json`, `PhoneLock-parent.apk` and `parent-version.json` from `releases/latest/download/` by those exact names. Don't rename them.
- **The lock app's certificate is pinned.** `Setup.LOCK_SIGNATURE_SHA256` is the release key's certificate hash. Setup refuses a downloaded lock app signed by anything else, and both updaters require the installed app's signer. Debug builds keep the release pin, because setup always downloads the released lock app.
- **The version code is the commit count** (`git rev-list --count HEAD`, in `android/build.gradle.kts`). History is never rewritten: a lower count would make every installed copy refuse the update.
- **Messages are end-to-end encrypted.** The topic name alone must never be enough to control a phone, and the hub only relays ciphertext.

## Conventions and gotchas

- Every third-party dependency has an `// ... (approved: <what>)` comment beside it in its module's `build.gradle.kts` (versions live in `android/gradle/libs.versions.toml`), or, for the npm dev tools, in the `"//"` note in `package.json`; adding one needs the maintainer's approval, recorded the same way. The lock app has no third-party runtime dependencies, and it excludes the Kotlin standard library on purpose.
- Every dialog in the parent app uses `AppDialog`, and every list row uses `ListRow`. Every activity's content sits inside `AppTheme`, the one place the theme is set.
- Parent-app screens use `stringResource()`; services and notifications use `Strings`.
- Parent-app settings go through the typed classes in `Prefs.kt`, which own every preferences file, key name and default; `npm run check:prefs` (in `validate`) fails on a key typed anywhere else. The stored names are what installed copies hold, so they don't change without a migration. The lock app still reads its preferences directly; it moves to the same pattern in a later change.
- Every string exists in all nine locales with the same placeholders, or release lint fails. Keep the two apps' `locales_config.xml` lists the same. Translations are AI-written and unreviewed.
- Debug builds are signed with the committed debug-only key in `android/keystore/debug.keystore` (passwords `android`). The production key signs releases only. A debug build can't update over a release one, or the reverse.
- The `github` flavour has the self-updater and install permission, full-screen alerts, and the background connection with its battery-saving exemption; the `play` flavour has none of them, because Play forbids or restricts each. The Play build reaches phones only by push through its hub, so it refuses phones on any other server. Shared code calls `AppUpdate` and `BackgroundConnection`, which each flavour implements, never the flavour's classes directly.
- `PRIVACY.md` at the root is only a pointer to the policy page, a deliberate exception to the root file set (DOCS-1): installed copies link to its old GitHub address, which must keep landing somewhere useful (BRAND-11). Never put policy text back in it; the policy is edited on the website.
- Signing material, Firebase settings and pairing data live in the git-ignored `.secrets/` folder. Never open, print or copy anything there.

## Commands

| Command | Proves | Does not prove |
| --- | --- | --- |
| `npm run validate` | Format, lint, unit tests, the translation and preferences checks and debug builds, as CI runs it | Device behaviour or the protocol path |
| `npm run verify:android` | Debug builds of the lock app and both parent-app flavours compile, with no secrets needed | Release signing |
| `npm run test` | The JVM unit tests pass | Device behaviour |
| `scripts/deploy/build.sh` | Both release APKs compile, pass the translation lint, and are signed (needs the signing files in `.secrets/`) | That they install or run |
| `npm run test:device` | The on-device tests pass on a connected phone or emulator (manual, not in `validate`) | That the camera reads a real QR code |
| `npm run check:listings` | The Play listing source has the app's nine locales and fits Play's limits (in `validate`) | That its claims are still true |
| `scripts/dev/test-remote.sh` | A paired phone answers encrypted commands over the topic (manual, not in `validate`) | Anything without a paired test phone |

Gradle tasks go through `scripts/dev/gradle`, which caps memory locally; the full list and what each proves is under **Checks** in `docs/development.md`.

## Release

Use the `release` skill. Notes are committed per version in `release-notes/` (en-US plus six siblings) as the last commit before publishing, because the version is the commit count. `scripts/deploy/publish-release.sh` then builds from the pushed `HEAD` and creates release `v1.0.<commit count>` with the four fixed assets, and refuses to run without the notes.

## Guidance map

| Guide, doc, or skill | Read or use when | Governs |
| --- | --- | --- |
| `.agents/guides/ui-conventions.md` | Adding or changing a parent-app screen, dialog, list or wording | `android/companion/src/main/java/home/phonelock/parent/**`, `android/companion/src/main/res/values*/strings*.xml` |
| `docs/protocol.md` | Any message, field or encryption change | `Remote.java`, `Hub.kt`, `Crypto.*`, `scripts/dev/ParentCli.java` |
| `docs/development.md` | Building, the hub settings, languages, releasing, adb install and control | `scripts/deploy/build.sh`, `tools/`, `res/values*/` |
| `README.md` | Changing a user-visible feature, requirement or the setup steps | The product as users see it |
| `server/README.md` | Changing or deploying the hub | `server/`, `scripts/deploy/deploy-hub.sh` |
| The privacy policy, https://dynamic-solutions.ca/privacy/phonelock (source: `privacy/phonelock.php` in the dynamic-solutions-website repo) | Changing what either app sends, stores or shares | Both apps, `Disclosure.kt` |
| `store-assets/play/listings.md` | Play listing, data safety and Play-flavour questions | `store-assets/play/`, `android/companion/src/play/`, the `play` flavour |
| `release` skill | Releasing a new version | `release-notes/`, `scripts/deploy/publish-release.sh`, `scripts/deploy/release-notes.mjs` |

## Keeping docs current

| What changed | Update |
| --- | --- |
| A source file, area or script added, moved or removed | The Architecture table above |
| A message type, field or the encryption | `docs/protocol.md`, both apps, `scripts/dev/ParentCli.java` |
| What either app sends, stores or who can read it | The privacy policy page in the website repo, in the same piece of work; Data safety in `store-assets/play/listings.md` |
| Build, adb or release procedure | `docs/development.md`, Commands above, and the `release` skill |
| User-visible features or setup steps | `README.md` |
| Interface text or languages | Every `values*/strings*.xml` in that app; Languages in `docs/development.md` (and its review-status table) and the README |
| A dependency added | Its `approved:` comment, and the privacy policy page if it talks to a network |
| A check, test suite or build command added | `package.json` (and `validate` when it is static), **Checks** in `docs/development.md`, Commands above |
| Hub configuration or deployment | `server/README.md` |
| A feature or claim the Play listing describes | `store-assets/play/listings.json` (every locale) and its claims list in `store-assets/play/listings.md` |
