# Stash implementation status

Last updated: 2026-07-14

This file is the short implementation ledger for the Stash fork. The wire
contract and operational detail for Muse acquisition live in
[`docs/MUSE_ACQUISITION.md`](docs/MUSE_ACQUISITION.md). Android Companion detail
lives in [`docs/MUSE_COMPANION_ANDROID.md`](docs/MUSE_COMPANION_ANDROID.md).

## Muse Companion Android foundation: implemented, production-gated

- The five-item bottom navigation now has a real top-level **Muse** destination;
  the prior Stash Sync surface remains reachable inside it.
- A non-exportable Android-Keystore P-256 identity exposes only SPKI, its
  SHA-256 fingerprint and public JWK. Authenticated REST, refresh and foreground
  SSE use the server's exact nonce/timestamp/body/token-bound proof format.
- Pairing first obtains a short-lived request-bound challenge, verifies Muse's
  SPKI fingerprint and canonical request digest, and signs the exact challenge
  proof as Android DER ECDSA/base64url before an eight-digit code is created.
- Pairing secrets and rotating access/refresh credentials are stored only in a
  Tink-encrypted DataStore blob. HTTPS endpoint validation prevents credentials
  from being moved to another origin, and no production endpoint is embedded.
- The app reconciles before every SSE cycle, rotates expiring credentials,
  sends idempotency keys plus expected revisions, and reconciles rather than
  blindly replaying an uncertain mutation.
- Refresh response-loss retries reuse the exact four-field JSON/idempotency
  body with fresh proof headers. Disconnect first obtains Muse's proof-bound
  self-revoke confirmation and only then deletes tokens and the Keystore key.
- The Muse UI provides local/Discord target selection, pairing/device status,
  permission-aware Now Playing and Queue views, transport/seek/volume/repeat,
  queue move/remove/shuffle/clear/undo, and a path to the existing Sync screen.
- The existing Media3 player is untouched; `Dieses Gerät` continues to use the
  normal Stash mini-player.
- The `spotify:manifest` device surface now uses Android's document picker and
  the offline parser for JSON/CSV/M3U/M3U8. It hard-rejects skipped/missing
  entries, 100/2,000/5,000 collection limits and normalized bodies over 2 MiB
  before an explicit upload confirmation. Proof-bound PUT/GET/DELETE keep the
  last acknowledged generation intact on every failure and never persist the
  raw file.

## Android acquisition v2: implemented

- Stash negotiates `muse-acquisition/v2` first and claims at most one
  server-assigned job. It never lists a shared v2 inbox.
- The shared `TrackAcquisitionPipeline` resolves lossless sources, preserves
  required request headers and decryption data, enforces a 500 MiB ceiling,
  finalizes atomically, probes the committed bytes, validates duration and
  codec, and computes the uploaded file's SHA-256.
- Cancellation propagates through resolver, download, metadata finalization,
  hashing and upload. Partial network files are removed.
- Worker phases, throttled progress and two-minute lease heartbeats cover
  resolving, downloading, verifying, uploading and validating.
- Completion is receipt-gated: a local download can never call v2 completion.
  Stash first obtains a job-scoped ticket and uploads without forwarding the
  Muse bearer.
- Resumable uploads implement stash-ingest's durable protocol: empty `POST`
  init, `HEAD` reconciliation, sequential hash-bound `PATCH` chunks, then an
  empty completion `POST`. Existing server offsets are resumed.
- Spotify/account/Discord/Guild/playlist identifiers are rejected at the v2
  job parser boundary. Only manual missing-search and manual quality-upgrade
  origins are accepted.

## Compatibility boundary

- v1 remains an explicit compatibility path with its historical local
  completion semantics.
- Downgrade is allowed only when v2 is definitively absent with HTTP 404, 405
  or 410 and the v1 capability contract then verifies successfully.
- Authentication failures, malformed v2 responses, timeouts, rate limits and
  server errors never silently downgrade to v1.
- The original unbounded `LosslessUrlDownloader.download` API remains source
  compatible. The Muse pipeline alone uses the separate bounded variant.

## Verification

- Full `:data:download:testDebugUnitTest`: 526 tests, 1 skipped, 0 failures,
  0 errors.
- Focused `:feature:muse:testDebugUnitTest`: 20 tests, 0 failures, including
  pairing canonicalization, strict refresh serialization and private manifests.
- Full `:app:compileDebugKotlin`: successful after the final Companion contract
  update (317 tasks, no compilation errors).
- Community `:app:compileReleaseKotlin` with `stash.communityBuild=true`:
  successful with private bundled source credentials disabled.
- Coverage includes v2 assignment, upload-before-complete, resumable offset
  recovery, per-chunk hashes, nested ingest receipts, v1 fallback boundaries,
  cancellation, source headers/decryption, measured media, size limits and
  legacy DownloadManager regressions.
- Exact `legacyProd` signing cannot run without the retained private signer;
  the checked Gradle invocation fails closed at configuration time with the
  required `STASH_DEBUG_KEYSTORE_*` message when it is absent.

## Remaining production gates

- Run the exact `legacyProd` artifact build and in-place update test with the
  retained signer and an archived device backup.
- Exercise one real Muse → Stash → stash-ingest → Navidrome job and verify that
  Muse can open the returned `navidromeSongId` before marking it `available`.
- Complete server Search/Browse/enqueue and Autoplay-state contracts before
  enabling those UI capabilities; no shadow client contract is used.
- Run Companion instrumentation/HTTPS acceptance on rooted and normal Android
  devices, including refresh-response loss, SSE expiry, revocation and DJ parity.
- Multi-node scheduling, official Spotify OAuth and public worker participation
  remain separate later waves.
