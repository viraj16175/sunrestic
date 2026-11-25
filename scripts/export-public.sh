#!/usr/bin/env bash
# Copies the project into a clean, empty folder for publishing: only files git tracks or would add (so nothing ignored),
# without a few personal or old leftovers, and without any git history.
#   scripts/export-public.sh ~/sunrestic
# Then: cd ~/sunrestic && git init -b main && git add -A && git commit -m "Sunrestic 0.9.0" && git tag -a v0.9.0 -m "First public beta"
set -euo pipefail
DEST="${1:?usage: scripts/export-public.sh <new or empty folder>}"
cd "$(dirname "$0")/.."
if [ -d "$DEST" ] && [ -n "$(ls -A "$DEST")" ]; then echo "$DEST is not empty"; exit 1; fi
mkdir -p "$DEST"
n=0
while IFS= read -r -d '' f; do
  case "$f" in install_icon.sh|result|resti.tar|*.tar.gz|*.apk|*.jks|*.keystore|keystore.properties|local.properties) continue ;; esac
  [ -e "$f" ] || continue
  mkdir -p "$DEST/$(dirname "$f")"
  cp -p "$f" "$DEST/$f"
  n=$((n + 1))
done < <(git ls-files -z --cached --others --exclude-standard)
echo "copied $n files to $DEST"
