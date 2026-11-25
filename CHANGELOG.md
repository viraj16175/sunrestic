# Changelog

## 0.9.0 (first public beta)
- Back up and restore apps (with data when rooted), folders, contacts, call log, messages and the wallpaper to a restic repository.
- SFTP, local folder, S3, REST and Backblaze B2 storage.
- Tags kept in the repository; retention rules; repository check; "Check everything" page.
- Daily automatic backup or reminder, Wi-Fi/charging conditions, tile and widget.
- Restore everything in one go, with safety copies and rollback for app data.
- restic and the ssh helper are built from source (`scripts/build-native.sh`); the app is licensed under Apache-2.0.
