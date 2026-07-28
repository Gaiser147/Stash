# Muse Companion for Android

The `Muse` top-level tab is the Android client for Muse's private
`muse-companion/v1` control plane. It does not replace Stash's Media3 player:
the visible target switch selects either **Dieses Gerät** (the existing local
player and mini-player) or **Discord** (the remote Muse snapshot and actions).

## Security boundary

- The first pairing attempt generates an ECDSA P-256 key under the fixed
  `stash_muse_companion_p256_v1` alias in Android Keystore. The private key is
  non-exportable. Only SPKI, the lowercase SHA-256 SPKI fingerprint and a
  standard public JWK (`EC` / `P-256` / `x` / `y`) can leave the component.
- Pairing obtains a two-minute, one-use Muse challenge first. Stash validates
  the server-computed SPKI fingerprint, canonical six-line request digest,
  expiry and signature metadata, then signs the exact six-line challenge proof
  as Android's ASN.1-DER ECDSA bytes encoded with unpadded base64url.
- Every authenticated REST and SSE request signs the exact Muse canonical
  request message with `SHA256withECDSA`. Timestamp, fresh 192-bit base64url
  nonce, raw body hash, exact encoded path/query and access-or-refresh-token
  hash match `src/utils/companion-proof.ts` on the server.
- Access and rotating refresh credentials, the short-lived pairing secret,
  endpoint, device grant and target preference are persisted as one Tink
  AES-GCM encrypted DataStore blob rooted in Android Keystore. Tokens are never
  logged or put in navigation arguments, URLs, UI state or crash messages.
- A server change cannot carry credentials to another origin. Only a root HTTPS
  endpoint without userinfo, path rewriting, query or fragment is accepted.
  Stash ships without a production endpoint.
- Every mutation has a new idempotency key and only the relevant expected
  player/queue revisions. An uncertain mutation is never replayed blindly;
  Stash reconciles via REST and asks the user to choose again.
- SSE runs only while the Muse screen is visible. Each connection cycle begins
  with REST reconciliation. Expired access tokens are refreshed using a request
  proof bound to the rotating refresh credential. A lost response retries once
  with the same serialized values and idempotency key but fresh proof headers.
- Device disconnect calls Muse's proof-bound self-revoke route first. Local
  bearer material and the Keystore identity are deleted only after the server
  confirms `revoked`.

## Current v1 surface

Implemented:

- capability negotiation, challenge/proof pairing, poll and encrypted
  idempotent token rotation;
- read-only guild, voice-channel, Now Playing, quality/source and queue state;
- permission- and DJ-aware disabled controls;
- previous/next, pause/resume, relative and exact seek, volume, repeat and
  confirmed stop;
- queue move/remove/shuffle, confirmed clear and server-side undo;
- local/Discord target selection, device/grant/scopes display, proof-bound
  self-revoke and an entry point to the existing Stash Sync screen.

## Private file-manifest import

Devices granted `spotify:manifest` can create a user-approved, random private
import-profile identity and choose JSON, CSV, M3U or M3U8 through Android's
system document picker. The URI is opened once; no persistable permission or
raw file is stored. `PrivatePlaylistImportParser` sanitizes locally and the raw
byte buffer is zeroed after transformation.

The adapter refuses the entire operation before network I/O when any entry was
skipped, required title/artist/duration metadata is absent, there are more than
100 playlists, more than 2,000 items in one playlist, more than 5,000 total
items, duplicate stable playlist IDs, or the exact normalized request exceeds
2 MiB. It never truncates. The parser's single artist becomes one array entry;
delimiters are not guessed.

After preflight Stash shows format, counts, byte size and candidate generation
for a second confirmation. PUT uses a proof-bound raw body and idempotency key.
Only an acknowledged response advances the encrypted local generation; HTTP
or validation failure preserves both the last acknowledged local generation
and Muse's transactional last-known-good generation. A deterministic manifest
fingerprint lets the same generation be confirmed after response loss. GET
shows the private profile status and DELETE disconnects it before Stash erases
the local profile identity. This surface has no acquisition/download action.

The app deliberately does not invent client-only versions of missing server
contracts. **Queue handoff** — moving playback between the local Media3 player
and Discord — remains visibly unavailable until Muse gains the routes for it.

Autoplay, library search/browse and enqueue were in that list until 2026-07-28
and are now implemented against real server contracts:

- **Autoplay** uses the state the player snapshot already reported.
- **Library search/browse** reads `GET /companion/v1/guilds/{guild}/library/…`
  (search, albums, artists, playlists) under the `library:read` scope that
  pairing has always granted. Responses carry only displayable fields plus a
  stable `navidrome:song:<id>` reference — never a stream URL.
- **Enqueue** submits that reference plus a placement (`end`, `next`, `now`).
  The device never sends a song object: Muse resolves the reference against its
  own library, so a paired phone cannot make the bot play an arbitrary URL.

## Production blockers

Before enabling production flags, run Android instrumentation acceptance on a
normal and a rooted device: hardware/TEE key characteristics where available,
pairing replay, 60-second clock handling, refresh-response loss, process death,
SSE expiry/reconnect, server revocation, wrong scopes, DJ parity and in-place
Room/signature upgrade. Network acceptance must use the real HTTPS reverse
proxy; Android cleartext traffic remains disabled.
