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

## Install and acceptance

With the candidate copied to the device:

```sh
su -c "pm install -r -t /sdcard/Download/stash-upgrade.apk"
```

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

The candidate is not promoted as the normal update until this device check and
GitHub Actions both pass. The existing APK/database backup remain rollback
evidence; Android cannot downgrade a migrated database safely merely by
reinstalling an older APK.
