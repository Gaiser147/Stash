# Fork release channels

The fork deliberately has three Android package boundaries. They are not
interchangeable, and an APK from one channel must never be presented as an
update for another.

## `legacyProd`: data-preserving installed-app updates

- application id: `com.stash.app.debug`
- build task: `:app:assembleLegacyProd`
- signer: the retained certificate whose SHA-256 is pinned by CI
- debuggable: false

This is the only channel that can update the existing 0.9.32 installation in
place. It preserves the package data directory and Android-Keystore identity.
Use the backup and acceptance procedure in [IN_PLACE_UPGRADE.md](IN_PLACE_UPGRADE.md)
before every first device installation of a new Room schema.

## `community`: new installations

- application id: `com.gaiser147.stash`
- build command: `:app:assembleRelease -Pstash.communityBuild=true -Pstash.distributable=true`
- signer: a separate stable community release key
- debuggable: false

The build fails when any release-signing input is missing. The community APK
does not expose the experimental Spotify Web Player cookie connector; Spotify
is connected through Muse OAuth or a private data import once those companion
features are enabled.

The `community-build.yml` workflow validates the package id, signer, APK hash,
and commit provenance and uploads those files together. It does not publish a
GitHub Release automatically.

## `preview`: disposable side-by-side testing

- application id: `com.stash.app.preview`
- build property: `-Pstash.sideBySidePreview=true`
- signer: may be ephemeral
- data: separate and disposable

Preview success never proves that an in-place update is safe. Do not point a
preview and the installed app at automatic full export simultaneously.

## App Links

Each stable package/signing-certificate pair needs its own entry in the
server's `assetlinks.json`. A package id or certificate change is treated as a
new client identity; credentials and pairings are never migrated merely by
matching a visible app label.
