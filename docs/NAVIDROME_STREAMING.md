# Navidrome streaming (design note — not implemented)

Status: **planned**. The owner's Navidrome server is offline right now, so this
records the intended design without shipping code. Upload is unaffected: it keeps
going through `stash-ingest` as described in [NAVIDROME_EXPORT.md](NAVIDROME_EXPORT.md).

## Goal

Treat the user's own Navidrome server as a first-class source:

1. **Stream + cache** songs the server has but the phone doesn't, before trying
   the third-party lossless proxies or YouTube.
2. **Feed autoplay**: songs on the server count as "owned" library candidates for
   the autoplay engine (`core/data/.../autoplay/`), so new songs uploaded from any
   device show up in autoplay mixes.

## Protocol

Navidrome implements the Subsonic API (`/rest/*`, token auth:
`u`, `t = md5(password + salt)`, `s`, `v=1.16.1`, `c=stash`, `f=json`).
Relevant endpoints:

| Endpoint | Use |
| --- | --- |
| `ping` | Connection test in Settings |
| `search3` | Match a Stash track (artist + title + ISRC when present) to a server song id |
| `stream?id=…&format=raw` | Original file (FLAC) with Range support; `maxBitRate` for cellular |
| `getCoverArt` | Artwork |
| `getSimilarSongs2` / `getTopSongs` | Server-side similar songs (Navidrome's Last.fm agent), an extra autoplay candidate source |
| `scrobble` | Optional: report plays back to Navidrome |

Credentials follow the existing pattern: HTTPS-only endpoint validation, secret
stored with Tink/Android Keystore, never read back into the UI.

## Integration points

- **`NavidromeStreamResolver`** in `core/media/.../streaming/`, registered FIRST in
  `StreamSourceRegistry.resolve` (own server beats every proxy). Returns a
  `stream` URL with auth query params; `StreamUrlCache` handles reuse. It must
  return null quickly when the server is unreachable (short connect timeout plus
  a health flag like `KennyyHealthMonitor`) so playback falls through to the
  existing chain without delay.
- **Caching**: reuse `StreamCache` (Media3 cache). An optional "keep on device"
  action converts a cached Navidrome stream into a normal download.
- **Server library index**: a periodic worker pages `search3` (empty query) into
  a lightweight table (`server_song_id`, artist, title, album, duration, ISRC)
  keyed by the same canonical `artist|title` identity autoplay uses. Autoplay's
  `LibraryCandidateSource` then treats matching rows as library songs, and
  `DiscoveryCandidateSource` stops suggesting songs that are already on the server.
- **Upload loop**: a liked/finished autoplay discovery goes through the existing
  download path, and `stash-ingest` uploads it to Navidrome, so the server library
  grows from what the user actually enjoys.

## Open questions (resolve when the server is back online)

- Navidrome version, and whether the Last.fm agent is configured (needed for
  `getSimilarSongs2`).
- Transcoding policy for cellular (raw FLAC vs. `maxBitRate=320`).
- Which account Stash uses: a dedicated user or the owner's.
