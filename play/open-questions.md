# Before listing on Google Play

Open items for publishing the parent app. The hub limits, disclosure screen, monitoring declaration, privacy policy draft, data safety answers and listing draft are done; these are not.

## Needs a decision

1. **Play build without the background connection (done, decided 2026-11-18).** The parent app's foreground service (type `specialUse`, `ParentService`) holds a live connection for phones push doesn't reach, and asks for the battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Play restricts both. Both now live only in the `github` flavour (`companion/src/github/`), reached through `BackgroundConnection`; the `play` flavour's `BackgroundConnection` does nothing, so the Play build has neither, nor the "Allow background use" hint. Because only PhoneLock's own hub is supported there, the Play build refuses to add a phone whose pairing code names any other server, and shows a warning on a phone push doesn't reach yet. The GitHub build is unchanged.
2. **Removing PhoneLock (planned, decided 2026-11-18).** The lock app can already give up device owner (`ConfigReceiver` `release`, over adb), but parents have no button for it. Play and the privacy policy expect a way out. Planned: "Remove PhoneLock from this device…" in the parent app's device menu (over the hub, or over Wireless debugging like setup), which releases and uninstalls. Until it ships, `PRIVACY.md` "Your choices" says removal needs a factory reset from recovery mode or a computer over USB debugging; update that sentence in the same change that adds the button.

3. **One signer for the GitHub and Play builds (decided 2026-11-16, D-021).** Play installs can only update over, and be updated by, builds with the same signing key. Decided: enrol the existing PhoneLock release key as Play's app signing key in Play Console before the first upload, so both channels keep one signer; that step, and anything that reads the key, is the maintainer's. If Google refuses the key, the GitHub and Play builds take separate application ids, which is a build change of its own. Either way it happens after the parent app's id rename (D-011) and before the first Play upload, because both are permanent after it.

## Needs doing

- **Reviewer access.** A setup and feature video, and possibly a demo device reviewers can pair with.
- **Store assets.** Icon, feature graphic, phone screenshots.
- **First release on GitHub,** after a build passes testing on a real child device (needed for the lock app download and the updater).
