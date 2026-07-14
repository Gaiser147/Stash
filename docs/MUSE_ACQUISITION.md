# Muse acquisition inbox

This fork can consume missing-track jobs created by the Discord Muse bot. The
feature is optional and off by default. It is deliberately separate from
Spotify sync and from the one-way Navidrome export.

## Current implementation boundary

The Android acquisition-v2 path is implemented and unit-tested as of
2026-07-14: assigned claim, shared lossless pipeline, measured artifact,
ticket-bound direct/resumable upload, ingest receipt, and only then Muse
completion. This does not by itself enable production rollout. Device pairing,
multi-node scheduling and the final server-side Navidrome availability check
remain separate system gates. See the concise
[`IMPLEMENTATION_STATUS.md`](../IMPLEMENTATION_STATUS.md) ledger.

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

Muse issues a lease before Stash starts work. Acquisition v2 uses the shared
`TrackAcquisitionPipeline`, not the search screen's preview-cache copier. It is
lossless-only and passes the complete resolver result to `LosslessUrlDownloader`,
including source-specific HTTP headers and an optional decryption key. The
pipeline reports `resolving`, `downloading`, and `verifying`, enforces a 500 MiB
ceiling, finalizes metadata, then reads codec, duration, bitrate, sample rate and
bit depth back from the committed file and computes SHA-256 over those exact
bytes. Cancellation is rethrown and partial downloads are removed.

The worker then obtains a short-lived upload ticket, uploads directly to the
ticket's HTTPS target, and accepts either a direct target or a resumable session
descriptor. Muse's bearer token is never forwarded to the upload target. The
worker can call v2 `complete` only after the upload returned an opaque ingest
receipt; local download success alone is not completion. Muse/stash-ingest own
the subsequent validation, indexing, Navidrome lookup, and transition to
`available`.

During long work Stash sends a heartbeat every two minutes. Phase/progress
reports are throttled to phase changes, five-percent steps, or 15 seconds. A
transient download or source failure is reported for bounded retry; process
death leaves the lease to expire and Muse may reassign it.

`no_match` and `no_confident_match` are terminal only for the reporting node's
attempt. Stash sends them as non-retryable because repeating the same resolver
on the same node is not useful; Muse may offer the job to another capable node
and must not treat that result as a globally failed acquisition.

The Settings screen stores only fixed status codes and counts, not response
bodies or tokens. Disabling or removing the connection cancels periodic/manual
poll work and never deletes local music or Muse jobs.

## HTTP contracts

All requests use `Authorization: Bearer <dedicated-token>` and
`Cache-Control: no-store`.

### Acquisition v2

Stash probes v2 first. A v2 broker assigns work server-side; there is no global
job-list endpoint and a device never sees jobs assigned to other nodes.

| Method and path | Purpose |
| --- | --- |
| `GET /v2/capabilities` | Verify `muse-acquisition/v2` and required capabilities. |
| `POST /v2/work/claim` | Send bounded device capabilities/constraints; receive HTTP 204 or exactly one job and lease. |
| `POST /v2/work/{id}/phase` | Report a versioned phase and optional byte/percent progress. |
| `POST /v2/work/{id}/heartbeat` | Extend the lease while a long download/upload is active. |
| `POST /v2/work/{id}/upload-ticket` | Submit SHA-256, size and measured media fields; receive a direct or resumable upload target. |
| `POST /v2/work/{id}/complete` | Submit the required ingest receipt plus the same artifact descriptor. |
| `POST /v2/work/{id}/fail` | Release/fail this attempt with a bounded error code and retryability. |

Required capability names are `assignment_claim`, `phase`, `heartbeat`,
`upload_ticket`, and `ingest_receipt`. Jobs may originate only from
`manual_missing_search` or `manual_quality_upgrade`; Spotify, Discord, guild,
playlist, cookie and OAuth identifiers are rejected at the parser boundary.

The claim request body is fixed to this shape (values are illustrative):

```json
{
  "consumerId": "installation-opaque-id",
  "deviceCapabilities": {
    "maxConcurrentJobs": 1,
    "supportedQualities": ["lossless", "hi_res", "max"],
    "supportsDirectUpload": true,
    "supportsResumableUpload": true
  },
  "constraints": {
    "maxFileBytes": 524288000
  }
}
```

A non-empty claim response carries `contract`, one `job`, and one lease. The
job's private track descriptor is limited to artist/title plus optional album,
ISRC and duration:

```json
{
  "contract": "muse-acquisition/v2",
  "job": {
    "id": "00000000-0000-4000-8000-000000000001",
    "query": "Artist Title",
    "attempt": 1,
    "approvedAt": "2026-07-14T12:00:00Z",
    "expiresAt": "2026-07-21T12:00:00Z",
    "origin": "manual_missing_search",
    "quality": "lossless",
    "status": "leased",
    "track": {
      "artist": "Artist",
      "title": "Title",
      "album": "Album",
      "isrc": "USAAA2600001",
      "durationMs": 203000
    }
  },
  "lease": {
    "token": "opaque-lease-token",
    "expiresAt": "2026-07-14T12:05:00Z"
  }
}
```

The upload ticket is created only after the artifact is known. Direct tickets
provide `uploadUrl`, `method`, and `headers`. Resumable tickets provide a
`resumable` object with `initUrl`, job-scoped `headers`, and `chunkSizeBytes`.
URLs must be HTTPS and may not contain userinfo or a fragment. A successful
direct response may contain `ingestReceiptId`/`receiptId`; stash-ingest v2
returns `receipt.receiptId` and `receipt.sourceSha256`. Stash requires a
returned source hash to match the uploaded artifact.

The upload-ticket body and the later completion body deliberately share the
same measured artifact descriptor:

```json
{
  "consumerId": "installation-opaque-id",
  "leaseToken": "opaque-lease-token",
  "artifact": {
    "localTrackId": "opaque-local-id",
    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "sizeBytes": 42000000,
    "media": {
      "codec": "flac",
      "bitrateKbps": 1648,
      "durationMs": 203000,
      "sampleRateHz": 96000,
      "bitsPerSample": 24
    }
  }
}
```

```json
{
  "contract": "muse-acquisition/v2",
  "ticket": {
    "id": "opaque-ticket-id",
    "expiresAt": "2026-07-14T12:15:00Z",
    "uploadUrl": "https://ingest.example.test/v2/uploads/opaque-ticket-id",
    "method": "PUT",
    "headers": {
      "Authorization": "Bearer job-scoped-upload-token"
    }
  }
}
```

The production resumable ticket instead has this exact shape:

```json
{
  "contract": "muse-acquisition/v2",
  "ticket": {
    "id": "opaque-ticket-id",
    "expiresAt": "2026-07-14T12:15:00Z",
    "resumable": {
      "initUrl": "https://ingest.example.test/v2/uploads",
      "headers": {
        "Authorization": "Bearer job-scoped-jws"
      },
      "chunkSizeBytes": 8388608
    }
  }
}
```

Stash sends an empty `POST` to `initUrl`. stash-ingest returns `uploadId`, a
same-origin `uploadUrl`, `uploadOffset`, and `uploadLength`. Stash reconciles
the durable offset with `HEAD uploadUrl`, then sends sequential `PATCH`
requests using `application/offset+octet-stream`, `Upload-Offset`, and a
lowercase per-chunk `X-Chunk-Sha256`. Each `204` response must advance
`Upload-Offset`. Finally Stash sends an empty `POST` to
`{uploadUrl}/complete` and consumes the nested ingest receipt. The scoped
ticket headers are used on all four operations; the Muse bearer is never used
against stash-ingest.

`POST /v2/work/{id}/complete` uses the same request as the upload-ticket call
with one additional required top-level field:

```json
{
  "consumerId": "installation-opaque-id",
  "leaseToken": "opaque-lease-token",
  "ingestReceiptId": "opaque-ingest-receipt-id",
  "artifact": {
    "localTrackId": "opaque-local-id",
    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "sizeBytes": 42000000,
    "media": {
      "codec": "flac",
      "bitrateKbps": 1648,
      "durationMs": 203000,
      "sampleRateHz": 96000,
      "bitsPerSample": 24
    }
  }
}
```

### Explicit v1 compatibility

If and only if the v2 capability endpoint is definitively absent (HTTP 404,
405, or 410), Stash verifies and uses the existing v1 contract:

| Method and path | Purpose |
| --- | --- |
| `GET /v1/capabilities` | Verify `muse-acquisition/v1` support. |
| `GET /v1/jobs?limit=3` | List oldest approved jobs. |
| `POST /v1/jobs/{id}/claim` | Claim a job for the stable installation consumer ID. |
| `POST /v1/jobs/{id}/report` | Extend the lease or report legacy completion/failure. |

The v1 compatibility path retains its historical local-download completion
semantics because v1 has no upload ticket or receipt. A transient or malformed
v2 response never silently downgrades to v1.

More precisely, only HTTP 404, 405 or 410 from `GET /v2/capabilities` permits a
v1 probe. Authentication errors, successful-but-invalid capability documents,
timeouts, HTTP 408/429 and 5xx responses remain v2 failures. Even after an
allowed downgrade trigger, work proceeds only when the complete v1 capability
contract verifies.

The consumer ID is a random installation identifier and contains no Android,
Discord, or Spotify identity. The lease token remains in WorkManager execution
memory/input only and is never written to user-visible logs.

## Server lifecycle boundary

- Muse considers a valid stash-ingest receipt accepted but not necessarily
  Navidrome-available; only the server transitions `validating → indexing →
  available` after its own checks.
- Upload-ticket headers are already scoped to one job/artifact and may include
  their own authorization. They must never require the long-lived Muse bearer.
- stash-ingest's `POST → HEAD → PATCH → POST complete` upload sequence and
  response headers are the authoritative resumable contract; Muse only mints
  the short-lived ticket and never proxies artifact bytes.
- v2 phase, heartbeat, complete and fail may return HTTP 204 or a JSON object
  carrying `contract: muse-acquisition/v2`.

## Release boundary

The public `com.stash.app.preview` APK is still a side-by-side test build. A
Muse-enabled preview does not prove upgrade compatibility with an installed
`com.stash.app.debug`; follow [the in-place upgrade gate](IN_PLACE_UPGRADE.md)
before distributing a normal-package APK.
