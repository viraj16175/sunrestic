#!/usr/bin/env bash
# End-to-end test of the built programs against a real sshd on this machine (no root, loopback only):
# restic init, backup, check, restore through our ssh helper, then what happens when the server's key changes.
#   scripts/test-native.sh [path-to-openssh-bin-dir]     (sshd and ssh-keygen are needed; default: whatever is on PATH)
set -euo pipefail
cd "$(dirname "$0")/.."
BIN="${1:-$(dirname "$(command -v sshd)")}"
RESTIC="$PWD/build/native/host/librestic.so"
HELPER="$PWD/build/native/host/libssh.so"
[ -x "$RESTIC" ] && [ -x "$HELPER" ] || { echo "run scripts/build-native.sh first"; exit 1; }

W="$(mktemp -d)"; PORT=$((20000 + RANDOM % 20000)); USER_NAME="$(id -un)"
SSHD_PID=""
cleanup() { [ -n "$SSHD_PID" ] && kill "$SSHD_PID" 2>/dev/null || true; rm -rf "$W"; }
trap cleanup EXIT
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; exit 1; }

"$BIN/ssh-keygen" -q -t ed25519 -N "" -f "$W/hostkey"
"$BIN/ssh-keygen" -q -t ed25519 -N "" -f "$W/client"
cp "$W/client.pub" "$W/authorized_keys"
cat > "$W/sshd_config" <<CFG
Port $PORT
ListenAddress 127.0.0.1
HostKey $W/hostkey
AuthorizedKeysFile $W/authorized_keys
PidFile $W/sshd.pid
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM no
StrictModes no
LogLevel ERROR
Subsystem sftp internal-sftp
CFG
start_sshd() { "$BIN/sshd" -D -e -f "$W/sshd_config" 2>"$W/sshd.log" & SSHD_PID=$!; for _ in $(seq 50); do (echo > "/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && return; sleep 0.1; done; cat "$W/sshd.log"; fail "sshd did not start"; }
start_sshd

SFTP="$HELPER -i $W/client -p $PORT -o HostName=127.0.0.1 -o HostKeyAlias=testhost -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$W/known_hosts -o BatchMode=yes $USER_NAME@testhost -s sftp"
export RESTIC_PASSWORD=test-password RESTIC_CACHE_DIR="$W/cache" HOME="$W"
R() { "$RESTIC" -r "sftp:$USER_NAME@testhost:$W/repo" -o "sftp.command=$SFTP" "$@"; }

mkdir -p "$W/data/sub"; head -c 3000000 /dev/urandom > "$W/data/big.bin"; echo "hello" > "$W/data/sub/a.txt"; echo "ünïcode name" > "$W/data/sub/ü.txt"
R init -q >/dev/null 2>"$W/err" || { cat "$W/err"; fail "restic init over the helper"; }; pass "restic init through the helper"
grep -q '^\[testhost\]:'"$PORT"' ssh-ed25519' "$W/known_hosts" && pass "new host remembered in known_hosts (accept-new)" || fail "host was not remembered"
R backup -q "$W/data" >/dev/null 2>"$W/err" || { cat "$W/err"; fail "backup"; }; pass "backup"
R check >/dev/null 2>"$W/err" || { cat "$W/err"; fail "check"; }; pass "repository check"
R restore -q latest --target "$W/restored" >/dev/null 2>"$W/err" || { cat "$W/err"; fail "restore"; }
diff -r "$W/data" "$W/restored$W/data" && pass "restored files are identical" || fail "restored files differ"
R snapshots --json 2>/dev/null | grep -q '"hostname"' && pass "known host accepted on a second connection" || fail "second connection failed"

kill "$SSHD_PID"; wait "$SSHD_PID" 2>/dev/null || true; SSHD_PID=""
cp "$W/known_hosts" "$W/known_hosts.good"
"$BIN/ssh-keygen" -q -t ed25519 -N "" -f "$W/hostkey2"; mv "$W/hostkey2" "$W/hostkey"; start_sshd
if R snapshots >/dev/null 2>"$W/err"; then fail "a changed host key was accepted"; fi
grep -q "HOST IDENTIFICATION HAS CHANGED" "$W/err" && pass "changed host key is refused with a clear message" || { cat "$W/err"; fail "wrong message for a changed host key"; }
cmp -s "$W/known_hosts" "$W/known_hosts.good" && pass "known_hosts was not modified by the refusal" || fail "known_hosts changed"

# a key the server does not know
"$BIN/ssh-keygen" -q -t ed25519 -N "" -f "$W/stranger"
cp "$W/known_hosts.good" "$W/known_hosts"; "$BIN/ssh-keygen" -q -t ed25519 -N "" -f "$W/hostkey3" ; kill "$SSHD_PID"; wait "$SSHD_PID" 2>/dev/null || true; SSHD_PID=""
rm -f "$W/hostkey"; mv "$W/hostkey3" "$W/hostkey"; : > "$W/known_hosts"; start_sshd
SFTP="${SFTP/-i $W\/client/-i $W/stranger}"
if R snapshots >/dev/null 2>"$W/err"; then fail "a key the server does not know was accepted"; fi
pass "an unknown client key is refused ($(head -c 90 "$W/err" | tr '\n' ' '))"
echo "all native tests passed"
