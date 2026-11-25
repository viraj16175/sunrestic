# Privacy

Sunrestic has no accounts, no analytics, no advertising and no server of its own. It does not send anything anywhere except to
the repository you set up (your SFTP server, S3/B2 bucket, REST server, or a folder on the phone).

## What the app reads, and only when you ask for a backup
- **Apps:** the list of installed apps, their APK files and, with root, their data folders.
- **Folders:** the folders you choose.
- **Contacts, call log, messages, wallpaper:** only when you choose them. They are read with Android's own permissions,
  which you grant when asked.

## Where it goes
Everything is encrypted by restic with your repository password before it leaves the phone. The only copy of your data is in your
repository. The app keeps a few small files of its own on the phone: settings, a cache of app lists, a log of the last jobs, your
SSH key file (if you import one) and the list of servers it has seen (`known_hosts`).

## Your password
- Stored only if you ask: encrypted with a key in Android's Keystore that needs your fingerprint or screen lock every time.
- If you turn on **automatic backups**, a second copy is stored with a Keystore key that needs no fingerprint, so the phone can back up
  by itself. Anyone with root access to your unlocked phone could then read it. Turn that off to remove it.

## Network
- The app connects to your repository only.
- For S3, REST and B2, restic looks up the server name through a DNS server set in the app (default **1.1.1.1**, Cloudflare). Change it
  under Repo, Options, DNS if you prefer another one. SFTP names are looked up by Android itself.

## Permissions, in plain words
Internet and network state (reach your repository), notifications and foreground service (show backup progress), exact alarms and
battery exemption (start the daily backup on time), biometric (unlock with fingerprint), query all packages (list your apps),
install packages (restore APKs without root), all files access (folders without root), contacts, call log, SMS, wallpaper
(phone data backup).

If you find something that is not described here, please open an issue.
