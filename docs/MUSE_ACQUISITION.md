# Muse acquisition inbox

This fork can consume missing-track jobs created by the Discord Muse bot. The
feature is optional and off by default. It is deliberately separate from
Spotify sync and from the one-way Navidrome export.

## User flow

1. A Discord user performs a plain text `/music` search and Muse finds no local
   Navidrome candidates.
2. Muse shows **In Stash suchen** only when its acquisition feature is enabled.
3. The same user explicitly confirms a second privacy message.
4. Muse stores one deduplicated, seven-day job containing the manual query.
5. An enabled Stash installation checks the private inbox under its configured
   WorkManager constraints, resolves a unique high-confidence YouTube Music
   catalog result, and sends lease/status reports back to Muse.

Private Spotify tracks, account IDs, playlist IDs, Discord IDs, guild IDs,
cookies, and OAuth tokens are never present in the Stash-facing job. Stash sees
only the versioned contract, opaque job ID, manual query, attempt count, and
timestamps.

## Stash setup

Open **Settings → Accounts & Sync → Muse requests** and configure:

- the private HTTPS base URL in front of Muse's acquisition listener;
- a dedicated bearer token of at least 32 characters;
- whether work requires an unmetered network and charging.

The token is encrypted with Android Keystore and is never displayed again.
The endpoint must use HTTPS and may not embed credentials, a query, or a
fragment. Muse's raw HTTP listener should remain bound to a single loopback or
private interface behind TLS, such as a private Caddy route or Tailscale Serve.

After **Test connection** succeeds, enable **Automatic request checks**. Android
checks at most every 15 minutes, handles at most three jobs per run, and always
requires a healthy battery and non-low storage. **Check now** schedules the same
constrained worker; it does not bypass those safety rules.

## Matching and retries

Stash does not blindly download the first result. It normalizes the manual
query, scores title/artist token coverage, requires a high score, and requires a
clear margin over the runner-up. Ambiguous title-only searches are rejected as
`no_confident_match` without invoking the downloader.

Muse issues a 30-minute lease only after Stash has searched. Stash reports
`downloading` before invoking the existing search-download pipeline. Successful
downloads are idempotently finalized into the local library. Transient download
or lossless-source failures return the job for a bounded retry; process death
leaves the lease to expire and Muse requeues it automatically.

The Settings screen stores only fixed status codes and counts, not response
bodies or tokens. Disabling or removing the connection cancels periodic/manual
poll work and never deletes local music or Muse jobs.

## HTTP contract

All requests use `Authorization: Bearer <dedicated-token>` and
`Cache-Control: no-store`.

| Method and path | Purpose |
| --- | --- |
| `GET /v1/capabilities` | Verify `muse-acquisition/v1` support. |
| `GET /v1/jobs?limit=3` | List oldest approved jobs. |
| `POST /v1/jobs/{id}/claim` | Claim a job for the stable installation consumer ID. |
| `POST /v1/jobs/{id}/report` | Extend the lease or report completion/bounded failure. |

The consumer ID is a random installation identifier and contains no Android,
Discord, or Spotify identity. The lease token remains in WorkManager execution
memory/input only and is never written to user-visible logs.

## Release boundary

The public `com.stash.app.preview` APK is still a side-by-side test build. A
Muse-enabled preview does not prove upgrade compatibility with an installed
`com.stash.app.debug`; follow [the in-place upgrade gate](IN_PLACE_UPGRADE.md)
before distributing a normal-package APK.
