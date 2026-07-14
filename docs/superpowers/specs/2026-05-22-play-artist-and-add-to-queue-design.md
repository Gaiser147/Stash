# Play Artist + Add-to-Queue Design

**Date:** 2026-05-22
**Status:** Approved

## Goal

Two Search-tab UX improvements:

1. **Play Artist button** on Artist Profile — taps fire ~hours of queue (popular + albums + singles), not just the 5 visible Top Tracks.
2. **Add to Queue button** on Album Discovery — append the album's tracks to the current queue without interrupting playback. Lets users stack albums from multiple artists.

## Background

`PlayerRepository` already exposes:
- `setQueue(tracks, startIndex)` — replaces queue
- `addToQueue(track)` — appends ONE track
- `addNext(track)` — inserts after current

No batch `addToQueue(List<Track>)`. Both new features need batching to avoid N MediaController round-trips, so the API gap is filled as shared infrastructure.

`ArtistProfile` (data class in `data/ytmusic`) carries:
- `popular: List<TrackSummary>` (5)
- `albums: List<AlbumSummary>`
- `singles: List<AlbumSummary>`

Album track lists are fetched lazily via `albumCache.get(browseId)` (already used by `AlbumDiscoveryViewModel`).

## Component 1: Batch `addToQueue` API

`PlayerRepository.addToQueue(tracks: List<Track>)` — appends N tracks in a single `MediaController.addMediaItems(...)` call. Reuses existing `buildMediaItemForTrack` per-track. Empty list is a no-op.

Single-track variant stays unchanged for existing callers.

## Component 2: Play Artist (hybrid start)

UI: button in `ArtistProfileScreen` hero area, styled with Stash's existing GlassCard + extended-theme conventions (NO generic Material — see project memory `feedback_stash_design_system`). Icon: filled play-arrow inside a primary-colored circle, sized for the hero. Loading indicator overlays the icon while initial `setQueue` resolves.

Behavior:

1. User taps Play Artist
2. VM synthesizes domain `Track` objects from `profile.popular` (5 tracks)
3. If popular and albums are both empty → emit Snackbar "No tracks available" and stop
4. If popular is non-empty → `playerRepository.setQueue(popularTracks, 0)` → playback starts immediately on track 1
5. Launch background catalog-fill job (`fillCatalogJob`):
   - Walk `profile.albums` then `profile.singles` in given order
   - For each, `albumCache.get(browseId)` → tracks
   - Dedupe by `videoId` against a `seen: MutableSet<String>` initialized from popular
   - Append batches via `playerRepository.addToQueue(tracks)`
   - Stop once cumulative track count reaches `CATALOG_CAP = 100`
6. On a second tap while `fillCatalogJob` is active: cancel it, restart from step 2

No Snackbar for "Playing N tracks" — actual playback IS the feedback.

Edge cases:
- Offline / cache miss: skip that album, continue. Worst case only the 5 popular tracks play.
- Empty popular but non-empty albums: skip to step 5, set initial queue from first album's tracks.

## Component 3: Add to Queue (Album)

UI: secondary icon button in `AlbumHero`, sibling to existing Play Album button. Icon: `Icons.AutoMirrored.Filled.PlaylistAdd`. Compact (icon-only with descriptive contentDescription).

Behavior:

1. User taps Add to Queue
2. VM synthesizes domain `Track` list via shared helper extracted from existing `playAlbum`
3. Call `playerRepository.addToQueue(tracks)`
4. Emit Snackbar `"Added N tracks to queue"`

Edge cases:
- Album not yet loaded (uiState.tracks empty) → no-op silently. Button SHOULD be disabled in this state via UI gating.

## Non-goals

- Cross-album dedup on Add to Queue (user explicitly stacked, doubles are their problem)
- Smart ordering / interleaving / popularity weighting in Play Artist's catalog fill
- Continuation-token deeper popular fetch (the album-walk gives wider coverage cheaply)
- Persistence of pending fill job across process restarts
- Shuffle-by-default for Play Artist (album-chronological is the v1 default; user hits shuffle if they want randomization)

## Implementation order

1. `PlayerRepository.addToQueue(List)` batch API + Impl + test-fake updates
2. `ArtistProfileViewModel.playArtist()` + initial setQueue path
3. `ArtistProfileViewModel` catalog-fill job + cancellation guard
4. Hero button in `ArtistProfileScreen`
5. Refactor `AlbumDiscoveryViewModel.playAlbum` to extract `synthesizeDomainTracks()` helper, add `addAlbumToQueue()`
6. Icon button in `AlbumHero`
