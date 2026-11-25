#!/usr/bin/env bash
# Builds the static restic + ssh (flake.nix) and drops them into the app as
# native libs, so Android extracts them to an executable directory.
set -euo pipefail
# cd "$(dirname "$0")/.."
# nix build .#bundle --out-link build/bundle
d=app/src/main/jniLibs/arm64-v8a
mkdir -p "$d"
install -m755 build/bundle/bin/restic "$d/librestic.so"
install -m755 build/bundle/bin/ssh "$d/libssh.so"
ls -l "$d"
