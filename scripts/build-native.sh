#!/usr/bin/env bash
# Builds the two programs the app runs, from source, with nothing but Go and git:
#   librestic.so  restic (fetched from GitHub at a pinned, verified commit) + our small DNS file
#   libssh.so     native/sshhelper, the ssh client restic uses for sftp
# They are plain static Linux programs that Android runs from the app's native library folder.
#
#   scripts/build-native.sh            builds for the phone (arm64) into build/native/arm64-v8a/ and for this machine into build/native/host/
#   scripts/build-native.sh --install  also copies the phone build into app/src/main/jniLibs/arm64-v8a/
#
# Needs: go >= 1.25.8, git, network access for the first build (modules are verified by go.sum).
set -euo pipefail
cd "$(dirname "$0")/.."

RESTIC_VERSION=0.19.1
RESTIC_COMMIT=6aa3a516ce654808a1f28f9fa21e9b7c8e6e90bf # v0.19.1; the build stops if the tag points anywhere else

command -v go >/dev/null || { echo "go is not installed (need 1.25.8 or newer)"; exit 1; }
command -v git >/dev/null || { echo "git is not installed"; exit 1; }

OUT="$PWD/build/native"
SRC="$OUT/src/restic"
mkdir -p "$OUT"
if [ ! -d "$SRC/.git" ]; then
  rm -rf "$SRC"
  git clone -q --depth 1 --branch "v$RESTIC_VERSION" https://github.com/restic/restic.git "$SRC"
fi
[ "$(git -C "$SRC" rev-parse HEAD)" = "$RESTIC_COMMIT" ] || { echo "restic v$RESTIC_VERSION is not the expected commit, refusing to build"; exit 1; }
git -C "$SRC" checkout -q -- . && git -C "$SRC" clean -fdq
cp native/restic/android_dns.go "$SRC/cmd/restic/android_dns.go"

build() { # build <goarch> <dir>
  local arch="$1" dir="$OUT/$2"
  mkdir -p "$dir"
  ( cd "$SRC" && CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -trimpath -buildvcs=false -ldflags "-s -w -buildid=" -o "$dir/librestic.so" ./cmd/restic )
  ( cd native/sshhelper && CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -trimpath -buildvcs=false -ldflags "-s -w -buildid=" -o "$dir/libssh.so" . )
  chmod 755 "$dir/librestic.so" "$dir/libssh.so"
}

build arm64 arm64-v8a
build "$(go env GOARCH)" host
(cd native/sshhelper && go test ./... >/dev/null) && echo "sshhelper unit tests pass"
sha256sum "$OUT"/arm64-v8a/*.so
ls -l "$OUT"/arm64-v8a/*.so | awk '{print $5, $NF}'

if [ "${1:-}" = "--install" ]; then
  mkdir -p app/src/main/jniLibs/arm64-v8a
  install -m755 "$OUT/arm64-v8a/librestic.so" app/src/main/jniLibs/arm64-v8a/librestic.so
  install -m755 "$OUT/arm64-v8a/libssh.so" app/src/main/jniLibs/arm64-v8a/libssh.so
  echo "installed into app/src/main/jniLibs/arm64-v8a/"
fi
