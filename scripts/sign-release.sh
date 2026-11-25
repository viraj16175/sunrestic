#!/usr/bin/env bash
# Signs a release APK with YOUR key and checks the result. The key and its password never go into the project:
# the script asks for the password on the terminal (or reads it from a file only you can read).
#
#   scripts/sign-release.sh [input.apk] [output.apk]
#
# Defaults: input = the full release build, output = ~/sunrestic-signed/sunrestic-<version>.apk
# Environment: KEYSTORE (default ~/keys/sunrestic-release.jks), KEY_ALIAS (default sunrestic),
#              KEY_PASS_FILE (optional: a file with the password, mode 600), ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYSTORE="${KEYSTORE:-$HOME/keys/sunrestic-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-sunrestic}"
IN="${1:-app/build/outputs/apk/full/release/app-full-release.apk}"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
OUT="${2:-$HOME/sunrestic-signed/sunrestic-${VERSION}.apk}"

[ -f "$KEYSTORE" ] || { echo "No key at $KEYSTORE (create it first, see BUILDS.md)"; exit 1; }
[ -f "$IN" ] || { echo "No APK at $IN (build it first: gradle assembleFullRelease)"; exit 1; }
BT="$(ls -d "${ANDROID_HOME:?set ANDROID_HOME}"/build-tools/* | sort -V | tail -1)"

mkdir -p "$(dirname "$OUT")"
TMP="$(mktemp --suffix=.apk)"
trap 'rm -f "$TMP"' EXIT
cp "$IN" "$TMP"
"$BT/zipalign" -f -p 4 "$IN" "$TMP"

pass_arg=()
[ -n "${KEY_PASS_FILE:-}" ] && pass_arg=(--ks-pass "file:$KEY_PASS_FILE" --key-pass "file:$KEY_PASS_FILE")
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" "${pass_arg[@]}" --out "$OUT" "$TMP"

"$BT/apksigner" verify --print-certs "$OUT" | grep -E "Signer #1 certificate (DN|SHA-256)"
echo "Signed: $OUT"
