---
name: release
description: Release a new PhoneLock version: notes for every language, a device-tested build, then scripts/deploy/publish-release.sh, which creates the GitHub Release both apps update from. Use when the user asks to release, publish or ship a version. Do not use for deploying the hub (scripts/deploy/deploy-hub.sh) or for a Play upload.
---

# Release PhoneLock

Publish one `v1.0.N` release that installed lock apps and GitHub parent apps install by themselves. Invoking this skill authorises its own commit and push and running `scripts/deploy/publish-release.sh`; it still stops at the points marked **Stop**. Background is under **Publish a release** in `docs/development.md`.

1. **Stop** unless the working tree is clean and `HEAD` equals `origin/main` after `git fetch`. Report what is dirty or diverged.
2. **Stop** unless the maintainer confirms both device checks of the code at `HEAD` on a child's test phone: an update (the test phone's published lock app updated to the release build of `HEAD`, which must keep its pairing and still answer the parent app, the path every child's phone takes) and a fresh install over adb. A published lock app reaches every child's phone unattended, and one that crashes on start, or loses its pairing in the update, can't be fixed remotely. A debug build can't stand in for the update check: it can't update over a release build. The maintainer also confirms that the parent app at `HEAD` scanned a pairing QR code on a real phone (Add a device > Add a device another parent set up > Scan QR code, pointed at another phone's Share with another parent code) and reached "Step 2 of 2": the emulator's camera can't show a QR code, so only a real camera proves the scanner.
3. Choose the version. The version is the commit count, and the notes commit is the next commit, so `N` is `$(( $(git rev-list --count HEAD) + 1 ))`.
4. Write `release-notes/1.0.N.md` (en-US) and the six siblings `1.0.N.<identity>.md` for es-ES, fr-FR, de-DE, zh-CN, pt-BR and ja-JP, from the changes since the last release (`gh release view --repo Dynamic-Solutions-Canada/phonelock` names it). User-facing bullets only, under 500 characters, no heading and no disclosure line; the publish script adds the "Get it" section and the disclosure line. Child-facing wording uses the informal "you". Run `npm run check:release-notes`.
5. **Stop** and show the en-US notes and the translations for approval. Record who approved them.
6. Commit only the notes files with the subject `Add release notes for v1.0.N`, check that `git rev-list --count HEAD` is now `N`, and push. If another commit landed first, rename the files to the new count before pushing.
7. Wait for CI on that commit to pass (`gh run watch`). Do not publish a commit CI failed on.
8. Check `free -m`: the build runs in the build container (`npm run build:image` first if it isn't built) under a 3.5 GiB cap and needs that much free plus the 2.5 GiB margin. Then run `scripts/deploy/publish-release.sh`. It refuses a dirty or unpushed tree, missing or invalid notes and a missing release key, builds both release APKs, stages the four assets in `.artifacts/packages/1.0.N/`, and creates the release `PhoneLock 1.0.N` marked Latest, with the notes, a "Get it" section and the disclosure line.
9. Verify the Release: the title, the body, and the four fixed assets `lock-release.apk`, `lock-version.json`, `PhoneLock-parent.apk` and `parent-version.json`, each version file showing versionCode `N`. Never rename or remove an asset; installed copies fetch them by name.
10. Post-release: confirm the test phone's lock app updates (the next time its screen is off) and the parent app offers the update in Settings.

Report: the version, who confirmed the device test, who approved the notes, the commit, the CI run, the Release URL, and anything left for the maintainer.
