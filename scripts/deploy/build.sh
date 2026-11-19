#!/bin/bash
# Build both release APKs, signed with PhoneLock's release key, through scripts/dev/gradle (in the build
# container by default, under its memory cap):
#   android/lock/build/outputs/apk/release/lock-release.apk            (child's phone)
#   android/companion/build/outputs/apk/github/release/companion-github-release.apk  (parents' phones, GitHub build;
#   the Play build is companion:assemblePlayRelease, built only when asked for)
# Extra arguments go to Gradle.
set -euo pipefail
cd "$(dirname "$0")/../.."

# The release key is the maintainer's and must already exist: every installed copy only accepts updates signed
# by it. Never create one here. A build signed by a new key would be refused by every phone, or leave them
# stuck on the old version. These checks look for the files only; nothing reads or prints them.
if [ -d secrets ] && [ ! -d .secrets ]; then
  echo "build.sh: rename secrets/ to .secrets/ first; it holds the release signing key." >&2
  exit 1
fi
if [ ! -f .secrets/phonelock.keystore ] || [ ! -f .secrets/signing.properties ]; then
  echo "build.sh: the release key isn't here (.secrets/phonelock.keystore and signing.properties)." >&2
  echo "Restore it from the maintainer's private storage; a release can't be built or signed without it." >&2
  exit 1
fi

scripts/dev/gradle :lock:assembleRelease :companion:assembleGithubRelease "$@"
ls -l android/lock/build/outputs/apk/release/*.apk android/companion/build/outputs/apk/github/release/*.apk
