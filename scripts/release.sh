#!/usr/bin/env bash
# Publishes a GitHub release for a version that is already tagged and pushed: the signed APK, plus release notes built from
# CHANGELOG.md, the signing certificate fingerprint and the APK's SHA-256. Needs the GitHub CLI (gh), logged in once with `gh auth login`.
#
#   scripts/release.sh              release the version in app/build.gradle.kts
#   scripts/release.sh --dry-run    show the notes and the commands, publish nothing
# Environment: APK (default ~/sunrestic-signed/sunrestic-<version>.apk)
set -euo pipefail
cd "$(dirname "$0")/.."

DRY=0; [ "${1:-}" = "--dry-run" ] && DRY=1
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
APK="${APK:-$HOME/sunrestic-signed/sunrestic-${VERSION}.apk}"
TAG="v$VERSION"
[ -f "$APK" ] || { echo "No signed APK at $APK (run scripts/sign-release.sh first)"; exit 1; }
[ -f CHANGELOG.md ] || { echo "No CHANGELOG.md"; exit 1; }

# the changelog section of this version: from its "## <version>" heading up to the next "## "
CHANGES="$(awk -v v="$VERSION" '/^## /{ if (on) exit; if (index($0, "## " v) == 1) { on=1; next } } on' CHANGELOG.md | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"
[ -n "$CHANGES" ] || { echo "CHANGELOG.md has no section '## $VERSION'"; exit 1; }

BT="$(ls -d "${ANDROID_HOME:?set ANDROID_HOME (run this inside nix-shell)}"/build-tools/* | sort -V | tail -1)"
CERT="$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/.*Signer #1 certificate SHA-256 digest: //p')"
SUM="$(sha256sum "$APK" | cut -d' ' -f1)"
[ -n "$CERT" ] || { echo "Could not read the signing certificate of $APK"; exit 1; }
if [ "$DRY" = 0 ] && "$BT/apksigner" verify --print-certs "$APK" | grep -q "Android Debug"; then
  echo "$APK is signed with the debug key. Sign it with your own key first (scripts/sign-release.sh)."; exit 1
fi

NOTES="$(mktemp)"; trap 'rm -f "$NOTES"' EXIT
{
  echo "$CHANGES"
  echo
  echo "**Beta.** Phone data (contacts, call log, messages, wallpaper), S3/REST/B2 and restoring apps without root are little tested."
  echo
  echo "Signing certificate SHA-256: \`$CERT\`"
  echo "APK SHA-256: \`$SUM\`"
  echo
  echo "Verify with: \`apksigner verify --print-certs sunrestic-$VERSION.apk\`"
} > "$NOTES"

PRE=(); case "$VERSION" in 0.*) PRE=(--prerelease) ;; esac
echo "=== $TAG: $(basename "$APK") ($(du -h "$APK" | cut -f1))"; cat "$NOTES"; echo "==="
if [ "$DRY" = 1 ]; then echo "(dry run) would run: gh release create $TAG $APK --title 'Sunrestic $VERSION' --notes-file <notes> ${PRE[*]:-}"; exit 0; fi

command -v gh >/dev/null || { echo "gh is not installed (nix-shell has it)"; exit 1; }
git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null || { echo "Tag $TAG is not on GitHub yet: git push origin $TAG"; exit 1; }
read -r -p "Publish this release publicly? [y/N] " yn
[ "$yn" = y ] || { echo "Cancelled."; exit 1; }
gh release create "$TAG" "$APK" --title "Sunrestic $VERSION" --notes-file "$NOTES" "${PRE[@]}"
