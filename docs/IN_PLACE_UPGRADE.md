# In-place upgrade gate

The supported target is a real Android update of the existing
`com.stash.app.debug` installation without clearing application data. The
separate `com.stash.app.preview` package is useful for exploratory testing but
does not satisfy this gate.

## Verified starting point

The device installation inspected on 2026-07-14 is:

- package `com.stash.app.debug`;
- version `0.9.32`, version code `69`;
- first installed 2026-05-20 and last updated 2026-05-21;
- APK SHA-256
  `44606247ef126fa5a9fc7c25ab092f719af0c458d75e312f1ef12f7aae175b9b`;
- signing-certificate SHA-256
  `3b89ee42d3535464d02d289427b71e3ffae4f9ea9e8b7628bcdabf44a55ae9be`.

The installed signer exactly matches the retained private fork keystore and
the archived May APK. CI rejects any normal-package artifact whose extracted
signer differs. The private keystore must only be supplied through protected
GitHub Actions secrets and must never enter Git or an artifact.

The four protected stable-signing inputs are configured in the fork repository.
Canonical branch run `29332684474` at commit `771b9e3f` produced version
`0.9.75`, version code `111`, as artifact
`stash-in-place-upgrade-candidate` with `application_id=com.stash.app.debug`,
`install_mode=in-place-upgrade`, and APK SHA-256
`121d5f5787cec94f0b3471ba1918b82a7545cf8cc6bc82aa2eaf06c2ab633b1f`.
Its v2 signing certificate matches the installed app exactly. This is still a
candidate, not a device-accepted release.

The installed certificate was independently printed on the device-side APK
copy as APK Signature Scheme v2 with subject `C=US, O=Android, CN=Android
Debug` and the pinned fingerprint above. The certificate subject alone is not
an identity check; the full SHA-256 fingerprint is authoritative.

Stash `0.9.32` used Room schema 26. The current app uses schema 32 and registers
every migration from `26→27` through `31→32`. There is no
`fallbackToDestructiveMigration`; a missing migration stops startup instead of
silently wiping the library. A dedicated chain test seeds a legacy track,
playlist, and ordering link at schema 26, migrates directly to 32, validates
the exported Room schema, and asserts that those rows and the download path
survive.

## Before installing the candidate

1. Keep a copy of the verified installed APK above.
2. Export a database backup from Stash's built-in backup UI.
3. Record the visible track count, representative playlists and ordering,
   download availability, storage location, sync toggles, and connected-account
   states. Do not copy tokens or cookies into notes.
4. Keep the current app installed. Never uninstall as part of an update test.
5. Confirm the candidate provenance says `application_id=com.stash.app.debug`,
   `install_mode=in-place-upgrade`, and contains the pinned signer digest.

## Obtain and verify the exact candidate

Use artifact `stash-in-place-upgrade-candidate` from canonical GitHub Actions
run [`29332684474`](https://github.com/Gaiser147/Stash/actions/runs/29332684474).
Do not substitute the public side-by-side preview APK. After extracting the
artifact on the computer, verify:

```sh
sha256sum app-debug.apk
apksigner verify --print-certs app-debug.apk
```

The only accepted values for this device test are:

```text
APK SHA-256:    121d5f5787cec94f0b3471ba1918b82a7545cf8cc6bc82aa2eaf06c2ab633b1f
Signer SHA-256: 3b89ee42d3535464d02d289427b71e3ffae4f9ea9e8b7628bcdabf44a55ae9be
```

Copy it without changing the installed package:

```sh
adb push app-debug.apk /sdcard/Download/stash-upgrade.apk
adb shell sha256sum /sdcard/Download/stash-upgrade.apk
```

## Backup-first rooted-device procedure

First create a backup from Stash's own backup screen and copy that file off the
phone. It is the portable logical database backup. Keep the already extracted
`stash-installed.apk` as the matching 0.9.32 binary and pull both files to the
computer before continuing.

Then create a second, same-device emergency snapshot while the app is stopped.
The following commands run inside `adb shell`; they do not uninstall or clear
the package:

```sh
PKG=com.stash.app.debug
STAMP=$(date +%Y%m%dT%H%M%S)
su -c "am force-stop $PKG"
su -c "du -sh /data/user/0/$PKG"
df -h /sdcard/Download
su -c "tar -C /data/user/0 -cpf /sdcard/Download/stash-data-$STAMP.tar $PKG"
if su -c "test -d /data/user_de/0/$PKG"; then
  su -c "tar -C /data/user_de/0 -cpf /sdcard/Download/stash-device-data-$STAMP.tar $PKG"
fi
sha256sum /sdcard/Download/stash-data-$STAMP.tar
ls -lh /sdcard/Download/stash-data-$STAMP.tar
```

Pull every resulting archive to the computer and verify that its local hash is
the same before installation. The raw archive can contain account state and
encrypted secrets, so keep it private. It is emergency evidence for this same
device, not a general cross-device restore format. Downloaded media in shared
or user-selected storage can be too large for this archive; record its path and
sample files, but do not duplicate or move the library as part of the update.

Before installation, record the current package boundary one final time:

```sh
su -c "dumpsys package $PKG" | grep -E 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime='
```

## Install and acceptance

With the candidate copied to the device:

```sh
su -c "pm install -r -t /sdcard/Download/stash-upgrade.apk"
```

Do not add `-d`, do not use `pm clear`, and never run `pm uninstall`. On
success, verify Android retained the package and advanced it in place:

```sh
su -c "dumpsys package $PKG" | grep -E 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime='
su -c "pm path $PKG"
```

The expected result is version `0.9.75`, code `111`, with the original
`firstInstallTime` retained. If installation returns anything except
`Success`, stop and preserve the complete error; do not try to repair it by
removing the old app.

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` means the signer check failed. Do not
uninstall; report the candidate SHA and provenance. A successful install must
then pass all of the following:

- app opens without a database migration crash;
- track count, playlist count/order, and representative rows match the
  pre-update record;
- existing downloaded files play and no storage root changed;
- ordinary settings remain intact;
- encrypted connections either remain usable or present an explicit
  reauthentication error without deleting library data;
- a new download, sync, process restart, and device restart complete normally;
- Muse and Navidrome integrations remain off until explicitly configured.

After those checks, validate one constrained WorkManager path at a time: first
connection test, then one ordinary download, then a manual check/export with
automatic periodic work still off. Confirm that unmetered/charging constraints,
queued/running/completed status, retry behavior, and process/device restart are
truthful before enabling any schedule.

The candidate is not promoted as the normal update until this device check and
GitHub Actions both pass. The existing APK/database backup remain rollback
evidence; Android cannot downgrade a migrated database safely merely by
reinstalling an older APK. If startup or migration fails, capture `logcat`,
leave the package/data intact, and stop. Any restore of the raw 0.9.32 snapshot
must be paired with its old APK and handled as a separate expert rollback, not
as an improvised downgrade over schema 32.
