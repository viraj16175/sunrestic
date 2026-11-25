# The two builds

The app is built in two flavors from the same code. `BuildConfig.PLAY` tells the code which one it is.

| | **full** (for the owner) | **play** (Google Play rules) |
|---|---|---|
| Gradle tasks | `assembleFullRelease`, `assembleFullDebug` | `assemblePlayRelease`, `assemblePlayDebug` |
| Version name | `0.1` | `0.1-play` |
| Debug build | separate app `app.sunrestic.debug` ("Sunrestic (debug)") | same |

## What differs

| Thing | full | play |
|---|---|---|
| `QUERY_ALL_PACKAGES` (see all installed apps) | yes | **no.** Uses `<queries>` for launcher apps and VPN apps instead |
| `REQUEST_INSTALL_PACKAGES` (install restored APKs without root) | yes | **no.** Without root, apps cannot be restored; with root they can |
| `SCHEDULE_EXACT_ALARM` | yes | **no.** The daily alarm uses the alarm-clock type |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (one-tap dialog) | yes | **no.** Opens the plain battery settings list |
| `SEND_SMS` | yes | **no** (not in Google's backup exception) |
| `MANAGE_EXTERNAL_STORAGE` (All files access, folders without root) | yes | yes (needs Play review) |
| READ_SMS, RECEIVE_SMS/MMS/WAP_PUSH, READ/WRITE_CALL_LOG, contacts | yes | yes (needs Play review, see below) |
| Root features | yes | yes, off by default, with a disclosure dialog before it is turned on |
| Wallpaper backup (`SET_WALLPAPER`, reading needs All files access) | yes | yes |

## Play Console declarations the play build needs
1. **SMS and Call Log permissions:** Permissions Declaration Form, exception **"Backup and restore for users"**.
   The app must be able to restore data back to the device's original default handler (it asks the user to switch back).
2. **All files access (`MANAGE_EXTERNAL_STORAGE`):** declaration form, permitted use **"Backup and restore apps"**.
3. **Foreground service type `specialUse`:** a short explanation of what the service does (runs a backup).
4. A privacy policy that says what is read (contacts, call log, messages, app list) and that it is stored encrypted in
   the user's own repository.

## What Google's policy pages say (read 2026-11-25)
- **Root:** the Malware policy's "Rooting" and "Elevated Privilege Abuse" sections cover code that *roots* a device. Nothing says an
  app may or may not use root the user already has. "Design your app to function with standard user privileges" is the
  closest advice, so in the Play build root is **off by default** and a dialog explains it before it is turned on.
- **Device settings (Deceptive Behavior, section 2):** changing device settings needs "explicit user consent" and must be
  "easily reversible". A settings restore with a preview and a saved copy of the old values would fit. `WRITE_SETTINGS` and
  `WRITE_SECURE_SETTINGS` are not named on the pages read.
- **Contacts:** the permissions policy has a banner "Contacts Permission" (effective 2027-01-27) saying broad contacts access
  is restricted unless it is needed for core functionality, and a "Manage Contacts Permission" item (effective 2027-08-25,
  Play Console declaration for apps targeting API 38+). The detailed contacts section was **not read**; read it before
  submitting.
- **All files access:** the page lists no permitted-use list itself; the separate help article 9956427 does ("Backup and
  restore apps").
- **Wallpaper:** `SET_WALLPAPER` is a normal permission and no rule about it was found.

## Not verified
- Whether Play review accepts an app that offers (optional) root use.
- The detailed Contacts permission section.
- Rules for foreground services of type `specialUse`.

## Signing
Release builds are signed with the local debug key for now. Before publishing, create a real upload key.

## Signing a release
Run these as the person who owns the key, in `nix-shell` (it provides keytool and the Android tools).
1. Create your key once and back it up (two copies, offline). Losing it means users can never update.
   `mkdir -p ~/keys; chmod 700 ~/keys`
   `keytool -genkeypair -v -keystore ~/keys/sunrestic-release.jks -alias sunrestic -keyalg RSA -keysize 4096 -validity 10000`
2. Build `gradle assembleFullRelease` (signed with the debug key, fine as an intermediate file). Someone else may do this step.
3. Sign: `scripts/sign-release.sh` asks for the key password and writes `~/sunrestic-signed/sunrestic-<version>.apk`, then prints
   the certificate fingerprint. The key never enters the project folder.
Switching from the debug key to your key means Android refuses to update the old install: uninstall once and set up again.
`keystore.properties` (see the example file) is an alternative that makes Gradle sign by itself; it needs the key readable by
whoever runs the build.

## The native programs (restic and ssh)
The app runs two static programs from its native library folder: `librestic.so` (restic) and `libssh.so` (the ssh client restic
uses for sftp). They are built from source with only Go and git:
- `scripts/build-native.sh` fetches restic at a pinned, verified commit, adds `native/restic/android_dns.go` (DNS override for Android),
  builds `native/sshhelper` (a small Go ssh client, about 400 lines, using golang.org/x/crypto/ssh), and writes both to
  `build/native/arm64-v8a/` (phone) and `build/native/host/` (this machine). `--install` copies the phone build into `app/src/main/jniLibs/`.
- `scripts/test-native.sh` starts a real sshd on loopback and runs restic init, backup, check and restore through the helper, then checks
  that a changed host key and an unknown client key are refused.
- Builds are reproducible: the same source gives the same restic hash.
- The Go helper only understands the ssh options the app uses (-i, -p, -l, -o HostName/HostKeyAlias/StrictHostKeyChecking/UserKnownHostsFile/
  ConnectTimeout, user@host, -s sftp). Keys with a passphrase are not supported (same as ssh with BatchMode).
- `flake.nix` (Nix, OpenSSH) is the older way to build the same two files and still works.
