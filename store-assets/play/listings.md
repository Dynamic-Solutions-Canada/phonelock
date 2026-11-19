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
- No ads, no tracking, no accounts, no telemetry (the privacy policy, LOG-9).
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
- **Privacy policy URL:** https://dynamic-solutions.ca/privacy/phonelock (the company site, D-017)

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
- **Foreground service and battery-optimization exemption:** not in the Play build. Both live only in the `github` flavour (`android/companion/src/github/`), so the Play build declares neither.

## Play app signing (D-021)

Every PhoneLock build that users can install under one application id must have one signer, so the GitHub build's updater and Play can replace each other (DIST-8, SEC-11). Play enrols every new app in a Google-generated signing key by default, and its key can only be changed before a release reaches open testing or production, so the existing release key is enrolled first, before any upload at all. Every step is the maintainer's, and nothing here names, reads or copies key material: the release keystore stays only in its private storage and backup.

Checklist, after the parent app's id rename (package 13) and before the first upload to any track:

1. In Play Console, create the app under its final application id. Upload nothing yet.
2. Open **Protected with Play > Play Store protection > Manage Play app signing** and choose **Change key**, then the option to provide a copy of your own key from a Java keystore. Follow the console's instructions, which give the encryption tool (PEPK) and the public key to encrypt the export with. Keep the exported, encrypted file out of the repo and delete it after the upload.
3. Create a separate **upload key** (RSA 2048-bit or larger), as Google recommends, store it with its index and backup per the credential rule, and register its certificate in the same page. The release key then never leaves private storage for Play.
4. Check the **app signing key certificate** SHA-256 that the console shows against the published fingerprints. The lock app's, pinned in `Setup.LOCK_SIGNATURE_SHA256`, is `8A:E1:B1:1F:4A:E8:CC:92:5D:96:DD:7C:2D:A6:DB:B0:23:E1:C4:3D:D1:E5:64:EC:22:CD:DA:EE:11:B9:43:23`. The parent app's is the certificate in a released `PhoneLock-parent.apk` (`apksigner verify --print-certs`); it must match the console's, or Play and GitHub installs can't update each other.
5. Record the certificate fingerprints and the upload certificate's in BRAND-10's registry row for PhoneLock (SEC-11).
6. Only then make the first upload, signed with the upload key, to internal testing.

Open before step 2: Play now offers new apps "quantum-ready" hybrid signing with Google-generated keys; check in the console whether providing your own key gives up anything a parent would notice. It doesn't change the decision, since a Google-generated key would end updates between the GitHub and Play builds.

**Fallback, if Google refuses the key:** the GitHub and Play builds take separate application ids (DIST-8), for example a suffix on the `play` flavour, each with its own Firebase app and Maps key restriction. A parent moving between them re-adds devices like any new install. That is a build change with its own package, decided then.

## Reviewer access

PhoneLock's parent app manages a separate child device, set up from the parent app over Wireless debugging, so reviewers need two devices to exercise it fully. A video of the setup and of each feature is at [VIDEO LINK]. To try the parent app's screens without a child device, [describe a demo device or pairing link the reviewer can use, if we provide one]. Never put reviewer credentials in this file (D-083).

## Open questions

1. **Play build without the background connection (done, decided 2026-11-18).** The parent app's foreground service (type `specialUse`, `ParentService`) and the battery-optimization exemption live only in the `github` flavour, reached through `BackgroundConnection`. The Play build has neither, accepts only phones on PhoneLock's own hub, and warns about a phone push doesn't reach yet.
2. **Removing PhoneLock (planned, decided 2026-11-18).** The lock app can already give up device owner (`ConfigReceiver` `release`, over adb), but parents have no button for it. Play and the privacy policy expect a way out. Planned: "Remove PhoneLock from this device…" in the parent app's device menu, which releases and uninstalls. Until it ships, the privacy policy's "Your choices" says removal needs a factory reset from recovery mode or a computer over USB debugging; update that sentence in the same change that adds the button.
3. **One signer for the GitHub and Play builds (decided 2026-11-16, D-021).** The steps are under **Play app signing** below; all of them are the maintainer's.

## Still to do before the first submission

- **Ryan reviews the English listing text** (`en-US` in `listings.json`) before the first Play publish that uses it.
- **Reviewer access:** a setup and feature video, and possibly a demo device reviewers can pair with.
- **Store assets:** feature graphic and phone screenshots (the 512 px icon is generated into `graphics/icon.png` by `npm run icons`).
- **First release on GitHub,** after a build passes testing on a real child device (needed for the lock app download and the updater).
