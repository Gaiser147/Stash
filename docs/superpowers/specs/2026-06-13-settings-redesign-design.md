# Settings Redesign — Premium Hub-and-Spoke (design spec)

**Date:** 2026-06-13
**Status:** Approved (brainstorm complete) — ready for implementation planning
**Feature module:** `feature/settings`
**Mockups:** `.superpowers/brainstorm/877-1781322634/` (`hub-refined.html`, `category-map.html`, `subscreens.html`, `appearance.html`, `flow.html`)

## Problem

Settings is a single ~1,970-line composable (`feature/settings/.../SettingsScreen.kt`) that renders **12 stacked sections** in one long scroll: Support, Playback, Accounts, Audio Quality, Lossless, Downloads, Stash Mixes, Appearance, Audio Effects, Storage, Diagnostics, About. To reach anything near the bottom you scroll past everything. Several sections (Lossless especially) are deep mini-screens crammed inline — a toggle, routing health, an account connect, a quality radio group, and two expanders all in one card. The result reads as a cluttered wall of cards and does not match the premium feel of the rest of Stash.

## Goals

- **Findable:** turn the 12-section scroll into a short, scannable hub of categories you tap into.
- **Premium:** restyle every surface in Stash's real design language (near-black, frosted glass, Space Grotesk + Inter, purple accent used sparingly) — no generic dark-mode, no emoji.
- **Maintainable:** decompose the 1,970-line monolith into a hub screen + six focused category screens + a small reusable component kit.

## Non-goals

- **No behavior changes.** Every existing control keeps its current function and wiring. This is a re-organize + re-skin, not a logic change. (One row — "Sync your likes" — is *new* behavior that arrives via a separate feature; see Prerequisite.)
- No new settings invented by this redesign beyond re-homing what exists.
- The theme picker is a visual upgrade only (same three options: Light / Dark / Follow system).

## Prerequisite / dependency

The **like-mirroring feature** (PR #187, branch `feat/like-mirroring`) is **built and on-device-verified but NOT yet merged to `master`** as of 2026-06-13. It introduces, in `feature/settings`, the `LikeMirrorSection` + `LikeMirrorWarningDialog` composables, the extracted `BetaPill`, and `mirror_likes_spotify`/`mirror_likes_youtube` prefs in `LikePreferences`. This redesign **places that `LikeMirrorSection` into the Accounts & Sync screen** — it does not build it.

Ordering rule for the implementation plan:
- **Land PR #187 first.** Then this redesign re-homes `LikeMirrorSection` into Accounts & Sync verbatim.
- If this redesign is implemented before #187 merges, **omit "Sync your likes" from the Accounts & Sync IA** and add it when #187 lands. Everything else in the spec is independent of #187.

Do **not** repurpose the vestigial `heart_default_*` prefs for mirroring: their UI was removed (`SettingsScreen.kt:968` comment; the `onHeartDefault*` callbacks + `SettingsUiState` fields are dead-plumbed with no render site), and like-mirroring decision #4 forbids it (they default `true` and would auto-enable Spotify writes for existing users). Cleaning up the dead `heart_default_*` plumbing is **out of scope** here (leave it).

## Approved decisions

1. **Navigation model: hub-and-spoke (direction A1).** Settings home becomes a short list of six category rows; tapping a row opens that category's own screen. Confirmed over a grouped single-scroll (B) and over pure-type rows (A2).
2. **Support banner pinned at the top of the hub** — above the search field and category list (not a category). Keeps "Support Stash · Donate · Star" prominent.
3. **Settings search field** directly under the support banner.
4. **Category rows carry thin monochrome line-marks** (A1) plus a **current-state subtitle** (e.g. "Online · Cellular off", "Lossless · Max 24/192", "20.8 GB · 1,171 tracks").
5. **Six categories**, with the contents mapping in the IA section below. Every one of today's 12 sections has a home; nothing is dropped.
6. **Appearance stays its own category** even though it holds only Theme today (room to grow — accent colour, Now-Playing background, etc.).
7. **Audio Effects dissolves:** Equalizer → Audio & Quality; Library Health → Library & Storage.
8. **One shared premium component language** (below) is used by the hub and all six interiors.

## Information architecture

Hub (top → bottom): **Support banner** · **Search** · six category rows.

| Category | Current-state subtitle (example) | Contents (from today's sections) |
|---|---|---|
| **Playback** | Online · Cellular off | Online/Offline mode (segmented); Stream on cellular; Stream via YouTube (fallback); Force antra only (test) |
| **Audio & Quality** | Lossless · Max 24/192 · EQ on | Download quality tier; Lossless downloads toggle; Lossless quality (Max/Hi-Res/CD); Sources & routing health (kennyy/squid) + antra account; YouTube fallback; Advanced; **Equalizer ›** |
| **Accounts & Sync** | Spotify · YouTube · Last.fm | Spotify (connect + Auto-save liked); YouTube Music (connect + Send plays); Last.fm (connect); **Sync your likes** (`LikeMirrorSection`, *from like-mirroring PR #187 — see Prerequisite*) |
| **Library & Storage** | 20.8 GB · 1,171 tracks | Stash Mixes (Beta); Downloads — network rules; Storage used + download location; Export/Import backup; Move library; **Library Health ›** |
| **Appearance** | Follow system | Theme — Light / Dark / Follow system |
| **About & Help** | v0.9.51 | Share diagnostics; Share latest crash report; Version · License; Check for updates |

Pinned on the hub (not a category): **Support Stash** — Donate · Star.

Sub-screens that themselves drill further (kept as nested routes, not inlined): **Equalizer**, **Library Health**, **antra account**, **Advanced** (lossless). These already exist as their own destinations or expanders today.

## Visual / component language

Design tokens come straight from `core/ui/.../theme/Color.kt` + `Type.kt` (dark theme):

- **Background** `#06060C`; **glass card** = `rgba(255,255,255,0.04)` fill (`StashGlassBackground` `0x0AFFFFFF`), `1dp` `rgba(255,255,255,0.06)` border (`StashGlassBorder` `0x0FFFFFFF`), `shapes.large` radius (existing `GlassCard`).
- **Row divider:** no divider token exists in `Color.kt` today. Add a new hairline token (~`6%` white) or reuse `StashGlassBorder` for inter-row dividers inside a group card — the plan picks one; do not assume an existing divider colour.
- **Text** primary `#E8E8F0`, secondary `#A0A0B8`, tertiary `#606078`.
- **Accent** purple `#8B5CF6` (switches, selected radio, primary button), light `#A78BFA` (group labels).
- **Type:** Space Grotesk (SemiBold/Bold) for the screen title, group labels, and account/service names; Inter (Regular/Medium) for row titles, subtitles, values.
- **Service identity colours** stay: Spotify green `#1DB954`, YouTube red `#FF0033`; routing health dots use cyan `#22D3EE`/green.

Reusable components (new, in `feature/settings/components/`):

- **`SettingsSectionLabel`** — purple Space Grotesk, uppercase, `letterSpacing ≈ .09em`; optional trailing Beta pill.
- **`SettingsGroupCard`** — `GlassCard` wrapper that lays children as rows separated by hairline dividers.
- **`SettingsToggleRow`** — title + optional subtitle + purple `Switch`.
- **`SettingsNavRow`** — optional leading line-mark + title + subtitle/value + chevron (used by hub rows and drill-ins).
- **`SettingsSegmented`** — pill segmented control, active segment gradient-filled (Online/Offline, theme Dark/Light/Auto).
- **`SettingsPickerRow`** — selectable row with purple radio dot + title + metadata subtitle (lossless quality tiers, download quality).
- **`SettingsStatusRow`** — leading glow dot (cyan/green) + name + status text (routing health).
- **`SettingsAccountCard`** — service dot badge, name, "Connected as …", ghost Disconnect, optional nested `extraContent` (auto-save / send-plays toggles, Beta pill).
- **`SupportBanner`** — purple→cyan gradient-edged card, title + one-line pitch + Donate (filled) / Star (ghost).
- **`SettingsSearchField`** — glass pill with search glyph.
- **`BetaPill`** — extracted by like-mirroring PR #187 (arrives with that branch, not built here).

## Per-screen layout

- **Hub** (`SettingsHubScreen`): title "Settings" → `SupportBanner` → `SettingsSearchField` → one `SettingsGroupCard` of six `SettingsNavRow`s with line-marks + live current-state subtitles. Each row navigates to its category route.
- **Playback:** "Mode" label → Online/Offline `SettingsSegmented`; "Streaming" label → group of toggle rows (cellular, YT fallback, force-antra test). **Note:** today the entire Playback section is gated behind `StashConstants.STREAMING_ENGINE_ENABLED` (`SettingsScreen.kt:452`; currently `true`). The hub must handle the flag-off case — hide the Playback row (or show it empty) when the flag is false — and the plan must preserve that gate.
- **Audio & Quality:** "Lossless" label → toggle + quality `SettingsPickerRow`s with size/min metadata; "Sources" label → `SettingsStatusRow`s (kennyy/squid) + antra account nav + Advanced nav; "Effects" label → Equalizer nav row. (Download-quality tier sits at the top under its own label.)
- **Accounts & Sync:** "Connections" → `SettingsAccountCard`s for Spotify / YouTube Music (nested auto-save / send-plays) + Last.fm; "Sync your likes" label + Beta → mirror toggles.
- **Library & Storage:** Stash Mixes toggle; "Downloads" network picker; "Storage" meter + location + backup buttons + move library; Library Health nav row.
- **Appearance:** theme picker — **visual preview cards (T1)**: three thumbnails (Dark / Light / Follow system) each rendering a faithful mini-preview in that palette (Light uses the real `#F6F3FF` lavender-white / aubergine light tokens), selected card gets a purple ring + check. T2 (single live preview + segmented) is the recorded alternative.
- **About & Help:** value rows (Version, License) + action rows (Share diagnostics, Share crash report, Check for updates).

## Architecture notes (for the implementation plan)

- **Navigation:** add the category routes (`SettingsRoute` → the hub; one route per category) to `StashNavHost`. The bottom-nav "Settings" tab lands on the hub. Reuse the **existing shared-ViewModel pattern** already in `StashNavHost.kt:150-169`: child routes resolve the Settings `ViewModel` via `navController.getBackStackEntry(SettingsRoute)` + `hiltViewModel(settingsEntry)` (the Equalizer/LibraryHealth drill-ins already do this). Each category screen scopes its `SettingsViewModel` to the hub's back-stack entry so all screens share one state holder.
- **Decomposition:** split `SettingsScreen.kt` into `SettingsHubScreen` + six `Settings<Category>Screen` files + the component kit. This is the maintainability win (1,970-line file → focused files).
- **State:** reuse the existing `SettingsViewModel` (30-flow `combine`) whole — each category screen collects the same `uiState` and reads its slice (lower-risk than splitting the combine for a first pass). The hub's current-state subtitles derive from `uiState` (lossless tier, storage bytes, theme, connection states).
- **Self-gating sub-composables move intact:** `SpotifyAutoSaveSection`, `YouTubeHistorySyncSection`, and `LastFmSection` already render their own disconnected/empty/loading states; they relocate into the Accounts & Sync screen unchanged, so the plan does not re-derive those states.
- **Search:** **phased.** Phase 1 ships the hub + six interiors; the `SettingsSearchField` renders but real cross-settings search (index → jump to owning screen/row) lands as **Phase 2**. Phase 1 may show the field disabled/placeholder or omit it — the plan decides the Phase-1 treatment, but search logic is explicitly not in Phase 1 scope.
- **Preserved behavior:** every *existing* control keeps its existing callback into `SettingsViewModel` — verbatim re-home. The lone exception is the new "Sync your likes" row, which is not existing behavior; it arrives via like-mirroring PR #187 (see Prerequisite) and is simply placed here.

## Out of scope

- Any change to what a setting *does*.
- New settings/features beyond today's set.
- Light-theme polish beyond an accurate theme-picker preview.

## Open items to confirm at spec review

1. **Theme picker:** T1 (preview cards) is the chosen direction; confirm vs T2 (live preview + segmented).
2. **Like-mirroring ordering:** confirm PR #187 lands before this redesign (so "Sync your likes" is present), versus implementing the redesign first and adding that row later.

*(Settings search resolved: phased — Phase 1 hub + interiors, search logic Phase 2. See Architecture notes.)*
