# Google Play: console-only settings (parent app)

What the Play Console needs that isn't listing text (LIST-4): limits, the claims the listing makes, store settings, the Data safety answers with their reasons, reviewer access and open questions. Every word a user reads on the listing, in all nine locales, is in [`listings.json`](listings.json), checked by `npm run check:listings`. Release notes are in the root `release-notes/`. Only the parent app goes on Play; the lock app is installed on the child's device from GitHub.

The listing translations were written with AI assistance and have not been reviewed by native speakers (LOC-12; the review status of every catalog is under **Languages** in `docs/development.md`). Nothing here reaches Play except through an approved publish.

## Limits

| Field | Play limit | Longest now |
| --- | --- | --- |
| `title` | 30 | 29 (fr-FR) |
| `shortDescription` | 80 | 80 (pt-BR) |
| `fullDescription` | 4,000 | 1954 (fr-FR) |
| Release notes | 500 | checked by `npm run check:release-notes` |

`shortDescription` is also the About screen's tagline (`about_tagline` in each `strings_about.xml`), word for word in every language; change both together.

## Claims to keep true

A release that changes any of these updates the listing in the same change.

- Everything between the phones is end-to-end encrypted, and the relay server can't read it (docs/protocol.md).
- No ads, no tracking, no accounts, no telemetry (PRIVACY.md, LOG-9).
- The child's device needs Android 13 or later (minSdk 33 in both apps).
- Setup is from the parent's phone over Wi-Fi with Wireless debugging: no computer, no cable, nothing erased; the child's device has no accounts signed in during setup.
- Location history covers the last 7 days.
- The child's home screen says what is shared, the child is notified whenever a parent views the screen, and PhoneLock can't be hidden.
- The SOS button calls the local emergency number, and holding it alerts the parents.
- The listing is in the nine locales of the app (LIST-7).

## Store settings

- **App name:** PhoneLock: Parental Control (`title` in `listings.json`)
- **Category:** Parenting
- **Contains ads:** No
- **Target audience:** adults (parents). Not designed for children.
- **Privacy policy URL:** https://github.com/Dynamic-Solutions-Canada/phonelock/blob/main/PRIVACY.md, until the policy moves to a website page (see open questions).

## Data safety

### Basis

Google doesn't count data as "collected" when it leaves the device only end-to-end encrypted, unreadable by the developer or any intermediary, with only the sender and recipient holding keys ([Google: Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)). Every PhoneLock message, file and screenshot is encrypted on the child's or parent's device with a key the relay server never has (see docs/protocol.md), so the child-device data shown in the app is not "collected".

What does leave the device readable to a service is the push token, through the Firebase Cloud Messaging SDK.

### Answers

- **Does your app collect or share any of the required user data types?** Yes
- **Is all of the user data collected by your app encrypted in transit?** Yes
- **Do you provide a way for users to request that their data is deleted?** Yes. Removing the device or uninstalling deletes it on the phone; the relay deletes everything it holds within 3 days.

| Type | Collected | Shared | Optional | Purpose |
|---|---|---|---|---|
| Device or other IDs (Firebase installation ID and push token) | Yes | No (Google processes it as our service provider) | No | App functionality (notifications) |

Location: the child's positions are end-to-end encrypted (not collected), but the parent app sends map areas and positions to Google Maps and the Android geocoder to draw maps and addresses. Check Google's Maps SDK guidance for whether this must be declared as "Approximate location, shared with Google, app functionality"; declaring it is the safe choice.

Not collected: personal info, financial info, health, messages, photos and videos, audio, files, calendar, contacts, app activity, web browsing, app info and performance. The child-device data the app shows is end-to-end encrypted (see Basis).

### Other declarations

- **Monitoring app (Stalkerware policy):** the manifest declares `isMonitoringTool` = `child_monitoring`, and the listing presents it as parental control only. The child is told on their device and it can't be hidden.
- **Prominent disclosure:** the first-run "Before you start" screen (Disclosure.kt), before any device is added.
- **Camera:** scanning pairing QR codes, decoded on the phone (ZXing); nothing about a scan is sent anywhere.
- **Foreground service and battery-optimization exemption:** not in the Play build. Both live only in the `github` flavour (`companion/src/github/`), so the Play build declares neither.

## Reviewer access

PhoneLock's parent app manages a separate child device, set up from the parent app over Wireless debugging, so reviewers need two devices to exercise it fully. A video of the setup and of each feature is at [VIDEO LINK]. To try the parent app's screens without a child device, [describe a demo device or pairing link the reviewer can use, if we provide one]. Never put reviewer credentials in this file (D-083).

## Open questions

1. **Play build without the background connection (done, decided 2026-11-18).** The parent app's foreground service (type `specialUse`, `ParentService`) and the battery-optimization exemption live only in the `github` flavour, reached through `BackgroundConnection`. The Play build has neither, accepts only phones on PhoneLock's own hub, and warns about a phone push doesn't reach yet.
2. **Removing PhoneLock (planned, decided 2026-11-18).** The lock app can already give up device owner (`ConfigReceiver` `release`, over adb), but parents have no button for it. Play and the privacy policy expect a way out. Planned: "Remove PhoneLock from this device…" in the parent app's device menu, which releases and uninstalls. Until it ships, `PRIVACY.md` "Your choices" says removal needs a factory reset from recovery mode or a computer over USB debugging; update that sentence in the same change that adds the button.
3. **One signer for the GitHub and Play builds (decided 2026-11-16, D-021).** Play installs can only update over, and be updated by, builds with the same signing key. Decided: enrol the existing PhoneLock release key as Play's app signing key in Play Console before the first upload. That step, and anything that reads the key, is the maintainer's. If Google refuses the key, the GitHub and Play builds take separate application ids. Either way it happens after the parent app's id rename (D-011) and before the first Play upload, because both are permanent after it.
4. **Privacy policy URL.** The listing points at PRIVACY.md on GitHub until the policy becomes a page on the website (D-017); the URL changes here and in the app (Disclosure.kt) together.

## Still to do before the first submission

- **Ryan reviews the English listing text** (`en-US` in `listings.json`) before the first Play publish that uses it.
- **Reviewer access:** a setup and feature video, and possibly a demo device reviewers can pair with.
- **Store assets:** icon, feature graphic, phone screenshots.
- **First release on GitHub,** after a build passes testing on a real child device (needed for the lock app download and the updater).
