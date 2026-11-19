#!/bin/bash
# Publish both APKs as a GitHub release on this (public) repo. The parent app's phone-to-phone setup
# installs the lock app from the latest release, and installed apps update themselves from it, using
# lock-version.json and parent-version.json to decide.
# Anything published reaches children's devices unattended: only publish a build already tested on a device
# over adb (a lock app that crashes on start can't be fixed remotely).
#   scripts/deploy/publish-release.sh            # builds from the committed, pushed HEAD
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO=Dynamic-Solutions-Canada/phonelock
# The last release of the parent app under its old id (home.phonelock.parent). Every later release re-attaches
# that release's PhoneLock-parent.apk and parent-version.json unchanged, so installed old apps see "up to date"
# and never fetch the new-id app, which they can't install over themselves (AppMove, D-011). Set it to that
# release's tag. v1.0.57 is that release (published 2026-11-20); never change it.
LEGACY_PARENT_RELEASE="v1.0.57"
[ -z "$(git status --porcelain)" ] || { echo "commit first: releases must match a pushed commit"; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "push first"; exit 1; }

# The version is the commit count, so the notes for it are committed as the last commit before this runs
# (the release skill's procedure). Stop before building when they are missing or invalid; never fall back to
# a fixed or generated text (REL-6).
version=$(git rev-list --count HEAD)
[ -n "$LEGACY_PARENT_RELEASE" ] || { echo "set LEGACY_PARENT_RELEASE to the last release under the old parent id first"; exit 1; }
# The release's files are staged under .artifacts/packages/<version>/ (LAYOUT-10, git-ignored), under the
# fixed asset names installed apps fetch.
out=.artifacts/packages/1.0.$version
rm -rf "$out"
mkdir -p "$out"
node scripts/deploy/release-notes.mjs github "v1.0.$version" > "$out/notes.md" || { rm -rf "$out"; exit 1; }

# The pinned old-id pair, checked before anything is built: its version file must name that release.
gh release download "$LEGACY_PARENT_RELEASE" --repo "$REPO" --dir "$out" \
  --pattern PhoneLock-parent.apk --pattern parent-version.json \
  || { echo "couldn't fetch the old-id parent app from $LEGACY_PARENT_RELEASE"; exit 1; }
grep -q "\"versionCode\":${LEGACY_PARENT_RELEASE#v1.0.}," "$out/parent-version.json" \
  || { echo "$LEGACY_PARENT_RELEASE's parent-version.json doesn't describe that release"; exit 1; }

# Built through scripts/dev/gradle (the build container by default), single worker with in-process Kotlin,
# under a 3.5 GiB cap unless PHONELOCK_GRADLE_MEMORY says otherwise: the settings that build reliably on this
# machine alongside other work (larger parallel builds have been OOM-killed).
PHONELOCK_GRADLE_MEMORY="${PHONELOCK_GRADLE_MEMORY:-3500M}" scripts/deploy/build.sh
lock=android/lock/build/outputs/apk/release/lock-release.apk
parent=android/companion/build/outputs/apk/github/release/companion-github-release.apk
cp "$lock" "$out/lock-release.apk"
cp "$parent" "$out/PhoneLock-parent-app.apk"
# Each app checks its own version file before downloading: the lock app lock-version.json, the parent app
# (GitHub build) parent-app-version.json; installed old-id parent apps read the pinned parent-version.json.
version_file() { printf '{"versionCode":%s,"sha256":"%s","size":%s}\n' "$version" \
  "$(sha256sum "$1" | cut -c1-64)" "$(stat -c %s "$1")"; }
version_file "$out/lock-release.apk" > "$out/lock-version.json"
version_file "$out/PhoneLock-parent-app.apk" > "$out/parent-app-version.json"

gh release create "v1.0.$version" --repo "$REPO" --title "PhoneLock 1.0.$version" --latest \
  --notes-file "$out/notes.md" \
  "$out/lock-release.apk" "$out/lock-version.json" "$out/PhoneLock-parent-app.apk" "$out/parent-app-version.json" \
  "$out/PhoneLock-parent.apk" "$out/parent-version.json"
echo "published v1.0.$version; its files are in $out"
