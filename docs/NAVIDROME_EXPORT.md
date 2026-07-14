# Navidrome export

The Navidrome feature in this fork is a one-way, opt-in export of Stash's downloaded library. It does not synchronize Navidrome playback state back into Stash and it does not call Navidrome's user-facing API directly. A separately deployed `stash-ingest` service authenticates the phone, validates uploads, writes into the Navidrome media tree, and triggers any server-side scan or cleanup policy.

## Setup

1. Deploy or obtain a compatible `stash-ingest` endpoint over HTTPS and create a dedicated bearer token.
2. In Stash, open **Settings → Accounts & sync → Your Navidrome**.
3. Enter the ingest base URL and token, then save. The URL must use HTTPS and cannot contain credentials, a query, or a fragment.
4. Use **Test connection**. A current server authenticates the token and contract through `/v1/capabilities`; a legacy v1 server can only confirm reachability until the first export.
5. Enable **Automatic export**. New completed downloads and changed playlist manifests will then be queued.
6. Leave **Unmetered network only** and **Only while charging** enabled unless immediate delivery matters more than data and battery use.
7. Use **Sync all now** once after setup, after restoring Stash data, or when reconciling an existing library.

The bearer token is encrypted with the same Tink AES-256-GCM/Android Keystore mechanism used for other app secrets. The Settings UI never reads it back. Entering a blank token while editing a configured connection preserves the encrypted value. If a device or backup change invalidates the Keystore key, Settings reports that the encrypted token is unavailable and requires a replacement instead of silently presenting the connection as healthy.

## Data flow

Automatic export sends only:

- downloaded audio files;
- resolved album artwork, when available;
- M3U8 manifests for active, sync-enabled Spotify and YouTube playlists;
- aggregate counts for the completed export attempt.

It does not send Spotify or YouTube cookies, passwords, OAuth tokens, listening history, or the Navidrome user's password. Disabling export makes pending work inert. Removing the connection also removes the local endpoint and encrypted bearer token; it deliberately does not delete local music or already exported server files.

Work is handled by Android WorkManager with exponential retry. HTTP 408, 429, and server failures are retried; other 4xx responses are treated as permanent configuration or contract failures. Audio uploads use a longer write timeout than ordinary API calls so large lossless files can finish on slower Wi-Fi.

WorkManager captures constraints when work is queued. Each worker therefore also rechecks the current unmetered-network and charging preferences immediately before an upload. Tightening either setting applies to already queued work; loosening a setting always applies to newly queued work, while older work may retain its original stricter WorkManager constraint until it runs or is reconciled.

The settings screen keeps a non-secret status record for the most recent worker attempt and the last successful export. A manual full export is shown as queued before WorkManager starts it; running, completed, incomplete, audio-failure, and artwork-failure states use fixed user-facing labels rather than displaying arbitrary server responses.

## Ingest contract v1

Every request carries:

```text
Authorization: Bearer <dedicated ingest token>
X-Stash-Contract: 1
X-Stash-Request-Id: <random UUID>
```

Current servers echo the contract and request ID in their response so a failed app attempt can be correlated with a redacted server log event. Legacy servers safely ignore the additional request ID.

The configured base URL is followed by one of these paths:

| Method and path | Body | Additional headers |
| --- | --- | --- |
| `PUT /v1/files/{relative-path}` | Audio bytes | `X-Stash-Sha256`, `X-Stash-Size`, optional URL-safe Base64 `X-Stash-Metadata` JSON |
| `PUT /v1/covers/{relative-path}` | Artwork bytes | `X-Stash-Sha256`, `X-Stash-Size` |
| `PUT /v1/playlists/{file-name}` | UTF-8 M3U8 | `X-Stash-Sha256`, `X-Stash-Size` |
| `POST /v1/sync-complete` | JSON aggregate counts and `full` or `playlists` mode | `Content-Type: application/json` |

Relative audio and cover paths deliberately retain the installed contract-v1 slug layout so a full reconciliation deduplicates against the existing library rather than creating a second tree. Playlist entries are rooted under `Stash/`; the ingest service maps that logical root to the configured Navidrome library directory. Implementations must reject path traversal, enforce upload limits, authenticate before reading request bodies, write atomically, and use hashes to make retries idempotent.

The legacy slug layout can collide when artist, album, and title differ only by stripped punctuation or non-Latin characters. Changing it on the client alone would duplicate existing media, so collision-proof identity paths are deferred to a coordinated contract-v2 ingest migration with an explicit old-path reconciliation plan.

This repository currently contains the Android client contract, not a production ingest-service implementation. End-to-end compatibility therefore requires validating the deployed service against the paths, headers, status handling, and filesystem layout above.

## Upgrading from the old fork build

The previous fork defaulted export on, embedded a private endpoint, and stored the bearer token as plaintext preferences. The new implementation removes the hardcoded endpoint, defaults export off, and migrates a stored legacy token into encrypted storage when the preferences are first read. Because the old default URL was not necessarily persisted, re-enter the endpoint and explicitly enable export after upgrading.

Old GitHub-hosted debug APKs may have been signed with ephemeral runner keys. Android cannot install a differently signed APK over an existing app with the same ID. Back up Stash from its database backup UI before any required uninstall, configure a persistent fork signing key for future builds, and verify an actual in-place update before treating the new artifact as upgrade-safe.

## CI and release boundaries

`.github/workflows/navidrome-fork.yml` runs affected unit tests and creates a debug APK. It does not publish a release, merge upstream, deploy the ingest service, or install an APK on a device. The verification job times out after 45 minutes instead of occupying a runner indefinitely. Artifacts contain the APK plus a JSON provenance record with repository, commit, workflow run, signing mode, APK SHA-256, and APK signing-certificate SHA-256. CI verifies the APK signature and, when stable-signing secrets are configured, fails unless the APK certificate matches that keystore. Builds without all stable-signing secrets are labeled `ci-only`. Weekly upstream checks create or refresh an issue instead of merging code automatically; scheduled workflows become active only after the workflow exists on the repository's default branch.
