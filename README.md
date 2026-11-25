# Sunrestic

Encrypted backups of your Android phone to **your own storage**, using [restic](https://restic.net).
Apps (and their data, with root), folders, contacts, call log, messages and the wallpaper are saved as restic
snapshots in a repository you control. Nothing goes to any server of ours: there isn't one.

> Status: 0.9.0, a beta. The parts marked **experimental** below have had little testing.

## What it does
- **Backends:** SFTP, a folder on the phone, S3-compatible storage, a restic REST server, Backblaze B2.
- **Apps:**
  - With root: APK, app data, device data, external data, media and expansion files, with a safety copy and automatic rollback
    when restoring data.
  - Without root: the APK files only; restoring them uses Android's installer (you confirm each app).
  - Tags that live in the repository, so a wiped phone gets them back.
- **Folders:** pick folders and restore them to where they came from (or to another place). Needs root, or Android's
  "All files access" permission.
- **Phone data (no root, experimental):** contacts, call log, text messages (restoring messages needs the app to be the default
  messaging app for a moment) and the wallpaper.
- **Daily backup:** at a time you choose, either automatic or as a reminder you confirm with your fingerprint. Optional Wi-Fi-only
  and charging conditions. Quick-settings tile and a home-screen widget.
- **Safety:** keep-last/daily/weekly/monthly rules that never delete the only copy of an app, repository check, a
  "Check everything" page, VPN apps are never stopped during a backup.
- **Restore everything** in one go (folders, apps, phone data), from the latest backups or from one you pick.

## Requirements
Android 10 or newer, 64-bit ARM (arm64-v8a). Root is optional and only needed for app data and for folders without the
"All files access" permission.

## Install
Download the APK from the Releases page and check its fingerprint against the one in the release notes.

## How it stores things
Everything is a normal restic repository, so you can always restore without this app:
```
restic -r <your repository> snapshots --host android
restic -r <your repository> restore <snapshot id> --target ./restored
```
Snapshots carry these tags: `android-apps`, `android-folders`, `android-contacts`, `android-calls`, `android-sms`,
`android-wallpaper`, and `android-tags` (the tag list). An apps snapshot includes `app-manifest/manifest.json` with the app list.

## Limits you should know about
- App data and root-only features need root. Without it you get APKs, folders (with All files access) and phone data.
- Backups of phone data other than apps are plain exports (JSON). Restoring contacts, calls and messages only *adds* what is
  missing; it never deletes or edits what is already on the phone.
- Messages can only be written back while the app is the default messaging app; Android shows a switch screen for that.
- Experimental, little tested: phone data (contacts, calls, messages, wallpaper), S3, REST and B2, restoring apps without root,
  settings export/import.
- Keys with a passphrase are not supported for SFTP; use a key without one that is only allowed to reach the backup folder.

## Privacy
See [PRIVACY.md](PRIVACY.md). Short version: no analytics, no accounts, nothing leaves the phone except to the repository you set up.

## Build
```
nix-shell                      # or install JDK 17, Go 1.25.8+, the Android SDK (build-tools 36)
scripts/build-native.sh --install   # builds restic and the ssh helper from source (see BUILDS.md)
./gradlew assembleFullRelease       # or: gradle assembleFullRelease
```
`BUILDS.md` explains the two build flavors (full and a Play-Store-rules flavor), the native programs, and how to sign a release.

## License
Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Sunrestic bundles restic (BSD 2-Clause) and builds a small ssh helper
from Go's `golang.org/x/crypto` (BSD 3-Clause). It is not affiliated with the restic project.
