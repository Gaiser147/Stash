# Settings Redesign — Premium Hub-and-Spoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 12-section single-scroll Settings screen with a premium hub-and-spoke: a Settings hub (Support banner + search field + six category rows with current-state subtitles) that navigates into six focused, restyled category screens — preserving every existing control's behavior.

**Architecture:** Decompose the ~1,970-line `SettingsScreen.kt` into a small reusable component kit (`feature/settings/components/`), a `SettingsHubScreen`, and six `Settings<Category>Screen` files. Add one route per category to `TopLevelDestination.kt`/`StashNavHost.kt`; every category screen shares the **existing** `SettingsViewModel` by resolving it from the `SettingsRoute` back-stack entry (the pattern already used by the Equalizer/antra/squid drill-ins). No ViewModel logic changes — controls re-home with their existing callbacks.

**Tech Stack:** Kotlin + Jetpack Compose (Material3), Hilt, Navigation-Compose (type-safe `@Serializable` routes), the existing `core/ui` theme (`GlassCard`, `StashExtendedColors`, Space Grotesk + Inter).

**Source spec:** `docs/superpowers/specs/2026-06-13-settings-redesign-design.md` (approved 2026-06-13).

---

## Prerequisite & scope

- **Land like-mirroring PR #187 (`feat/like-mirroring`) first — recommended hard prerequisite for the WHOLE plan.** #187 provides two things this plan depends on: (1) the **public** `components/BetaPill.kt` (extracted from the currently-`private` `BetaPill` inside `SpotifyAutoSaveSection.kt:123`), used by `SettingsSectionLabel` in **Phase A**; and (2) `LikeMirrorSection` + `mirror_likes_*` prefs, used by **Task C3**.
- **If you must start before #187 merges:** Task A1 **also extracts `BetaPill` to a public `components/BetaPill.kt`** (it will be a no-op merge-dup with #187 — reconcile on merge), AND Task C3 **skips the "Sync your likes" block**. Everything else is independent of #187. Do not start Phase A assuming the private `BetaPill` is reachable — it is not.
- **Phase 1 only.** The `SettingsSearchField` renders but is non-functional (placeholder, see Task B2). Real cross-settings search is a separate Phase-2 plan — not in scope here.
- **Behavior-preserving.** Do not change any control's logic or its `SettingsViewModel` callback. This is relocation + restyle. Do NOT touch the dead `heart_default_*` plumbing.

## Verification model (read before starting)

This module is **presentational Compose with no unit-test harness** (`SettingsViewModel` has none — established project gap). Per project convention, UI tasks are verified by **`:feature:settings:compileDebugKotlin`** + the on-device pass in Task D2 — not unit tests. The ONE exception is the hub's subtitle-derivation logic (Task B1a), which is extracted as a **pure function and unit-tested** (TDD). Don't invent Compose UI tests; follow the module's pattern.

- Windows shell: invoke gradle as `.\gradlew.bat`.
- `:feature:settings` is a fast module (not the 6-min `core:media`). Compile freely.
- Always `git add` exact paths and commit per task.

## File map

**New — component kit (`feature/settings/src/main/kotlin/com/stash/feature/settings/components/`):**

| File | Responsibility |
|---|---|
| `SettingsTheme.kt` | One place for the divider token + shared dims (e.g. `settingsDivider`, row paddings). |
| `SettingsSectionLabel.kt` | Purple Space-Grotesk uppercase group label, optional trailing `BetaPill`. |
| `SettingsGroupCard.kt` | `GlassCard` wrapper that stacks children with hairline dividers. |
| `SettingsToggleRow.kt` | Title + optional subtitle + purple `Switch`. |
| `SettingsNavRow.kt` | Optional leading line-mark + title + subtitle/value + chevron (hub rows & drill-ins). |
| `SettingsSegmented.kt` | Pill segmented control, active segment gradient-filled. |
| `SettingsPickerRow.kt` | Selectable row: purple radio dot + title + metadata subtitle. |
| `SettingsStatusRow.kt` | Leading glow dot (cyan/green) + name + status text. |
| `SettingsAccountCard.kt` | Service dot badge, name, "Connected as …", ghost Disconnect, slot for nested `extraContent`. |
| `SupportBanner.kt` | Gradient-edged Support card: title + pitch + Donate (filled) / Star (ghost). |
| `SettingsSearchField.kt` | Glass search pill (Phase-1 non-functional placeholder). |
| `SettingsScaffold.kt` | Shared sub-screen frame: back chevron + Space-Grotesk title + scrolling column. |

**New — screens (`feature/settings/src/main/kotlin/com/stash/feature/settings/`):**
`SettingsHubScreen.kt`, `SettingsHubSummaries.kt` (+ test), `SettingsPlaybackScreen.kt`, `SettingsAudioQualityScreen.kt`, `SettingsAccountsScreen.kt`, `SettingsLibraryStorageScreen.kt`, `SettingsAppearanceScreen.kt`, `SettingsAboutScreen.kt`.

**Modify:** `app/.../navigation/TopLevelDestination.kt` (six new routes), `app/.../navigation/StashNavHost.kt` (hub + six composables), `feature/settings/.../SettingsScreen.kt` (gut the monolith; becomes the hub host or is deleted — Task D1).

**Reuse intact (relocated, not rewritten):** `SpotifyAutoSaveSection`, `YouTubeHistorySyncSection`, `LastFmSection`, `LikeMirrorSection` (#187), `SquidWtfCaptchaScreen`/`AntraConnectScreen` drill-ins, `EqualizerScreen`, `LibraryHealthScreen`, `DiagnosticsPreviewScreen`.

## Design tokens (from `core/ui/.../theme/Color.kt` + `Type.kt`)

`StashTheme.extendedColors.glassBackground` (card fill), `.glassBorder` (card border). Background `#06060C`. Text via `MaterialTheme.colorScheme.onSurface`/`onSurfaceVariant` + `extendedColors.textTertiary` (`#606078`). Accent `MaterialTheme.colorScheme.primary` (`StashPurple #8B5CF6`); group labels use `StashPurpleLight #A78BFA`. Service colors `extendedColors.spotifyGreen`/`youtubeRed`; status dots `extendedColors.cyanLight #22D3EE`/`success #10B981`. Type: `titleLarge`/`titleSmall` (Space Grotesk) for titles/labels, `bodyMedium`/`bodySmall` (Inter) for rows. There is **no divider token** — Task A1 adds `settingsDivider = Color(0x0FFFFFFF)`.

---

## Phase A — Component kit

> Each component is a stateless `@Composable` with a `@Preview`. Verify with `:feature:settings:compileDebugKotlin`. Visual correctness is confirmed in Task D2.

### Task A1: Divider token + section label + group card + shared rows

**Files:**
- Create: `feature/settings/.../components/SettingsTheme.kt`
- Create: `feature/settings/.../components/SettingsSectionLabel.kt`
- Create: `feature/settings/.../components/SettingsGroupCard.kt`
- Create: `feature/settings/.../components/SettingsValueRow.kt` (the relocated `StorageRow` — needed by both Library & Storage and About, so it lives in the kit, not a screen)
- **Prerequisite check:** confirm `components/BetaPill.kt` exists as a public composable (from #187). If not, create it now by moving the `private fun BetaPill()` out of `SpotifyAutoSaveSection.kt:123` into a public `components/BetaPill.kt` (see Prerequisite & scope).

- [ ] **Step 1: `SettingsTheme.kt`** — shared dims + divider:

```kotlin
package com.stash.feature.settings.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** v0.9.x Settings redesign shared dims. No divider token exists in core/ui;
 *  this hairline (~6% white) matches StashGlassBorder for inter-row separators. */
internal val SettingsDivider = Color(0x0FFFFFFF)
internal val SettingsRowPadH = 15.dp
internal val SettingsRowPadV = 14.dp
internal val SettingsGroupGap = 18.dp
```

- [ ] **Step 2: `SettingsSectionLabel.kt`** — purple uppercase group label with optional Beta pill:

```kotlin
package com.stash.feature.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stash.core.ui.theme.StashPurpleLight

/** Purple Space-Grotesk uppercase group label (titleSmall). Optional trailing Beta pill. */
@Composable
fun SettingsSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    beta: Boolean = false,
) {
    Row(
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.0.sp,
            ),
            color = StashPurpleLight,
        )
        if (beta) {
            Spacer(Modifier.width(8.dp))
            BetaPill() // from like-mirroring PR #187
        }
    }
}
```

- [ ] **Step 3: `SettingsGroupCard.kt`** — GlassCard that stacks rows with hairline dividers. Use a `SettingsRowScope`-free approach: caller passes rows; this draws dividers between them.

```kotlin
package com.stash.feature.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.stash.core.ui.components.GlassCard

/** Glass card whose direct children are settings rows; draws a hairline divider
 *  between each. Pass row composables via [rows]. */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    rows: List<@Composable () -> Unit>,
) {
    GlassCard(modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = SettingsDivider, thickness = 1.dp)
                row()
            }
        }
    }
}
```

> **Resolve the padding model now (not in D2).** `GlassCard` already applies 16dp padding, so a `SettingsGroupCard` whose rows ALSO pad will look doubly inset and the full-width dividers won't reach the card edge. Decide here, since all six Phase-C screens build on this primitive: make `SettingsGroupCard` a **bespoke `Surface`** copying `GlassCard`'s params (`color = extendedColors.glassBackground`, `border = BorderStroke(1.dp, extendedColors.glassBorder)`, `shape = shapes.large`) but with **zero outer padding**, so rows own their padding and dividers span edge-to-edge. Keep `GlassCard` for non-grouped one-off cards (e.g. Support banner is its own component).

- [ ] **Step 4: `SettingsValueRow.kt`** — relocate the existing private `StorageRow` (label left / value right, both `bodyMedium`) from `SettingsScreen.kt` into a public kit component `SettingsValueRow(label: String, value: String)`. Both Library & Storage (Task C4) and About (Task C6) use it.
- [ ] **Step 5: Compile.** Run: `.\gradlew.bat :feature:settings:compileDebugKotlin` — expect BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** — `feat(settings): kit — divider token, section label, group card, value row`

### Task A2: Toggle row + nav row

**Files:** Create `SettingsToggleRow.kt`, `SettingsNavRow.kt`.

- [ ] **Step 1: `SettingsToggleRow.kt`** — title + optional subtitle + purple `Switch` (mirror the row shape already in `SpotifyAutoSaveSection.kt`):

```kotlin
@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) { /* Row: Column(weight 1f){ Text(title, bodyMedium, onSurface); subtitle?->bodySmall onSurfaceVariant } + Switch(checked,onCheckedChange,enabled) ; padding SettingsRowPadH/V ; clickable when enabled toggles */ }
```

- [ ] **Step 2: `SettingsNavRow.kt`** — optional leading icon (`ImageVector?`), title, optional subtitle/value, trailing chevron; whole row `clickable { onClick() }`:

```kotlin
@Composable
fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
) { /* Row: icon(18dp, onSurfaceVariant)? + Column(weight){title bodyMedium; subtitle bodySmall textTertiary} + chevron (Icons.Rounded.ChevronRight, textTertiary) */ }
```

Use `androidx.compose.material.icons.Icons` line-style vectors (`Icons.Rounded.*`) for `leadingIcon`s — they are monochrome and match the "thin line-mark" decision; tint with `onSurfaceVariant`. Pick semantically-close icons (e.g. PlayArrow, GraphicEq, Person, FolderOpen, Palette, InfoOutline) when the hub wires them in Task B2.

- [ ] **Step 3: Compile.** `.\gradlew.bat :feature:settings:compileDebugKotlin`
- [ ] **Step 4: Commit** — `feat(settings): kit — toggle row, nav row`

### Task A3: Segmented control + picker row

**Files:** Create `SettingsSegmented.kt`, `SettingsPickerRow.kt`.

- [ ] **Step 1: `SettingsSegmented.kt`** — `options: List<String>`, `selectedIndex`, `onSelect: (Int)->Unit`. Row with `rgba(255,255,255,0.05)` track, each segment `weight(1f)`, active segment a purple-tinted gradient (`Brush.verticalGradient(listOf(Color(0xFF3A2D6B), Color(0xFF2A2150)))`), inactive text `onSurfaceVariant`. Rounded 12dp.
- [ ] **Step 2: `SettingsPickerRow.kt`** — `selected: Boolean`, `title`, `subtitle` (metadata), `onClick`. Leading 19dp ring (`primary` when selected, with filled inner dot), title `bodyMedium`, subtitle `bodySmall textTertiary`. Whole row clickable.
- [ ] **Step 3: Compile + commit** — `feat(settings): kit — segmented control, picker row`

### Task A4: Status row + account card

**Files:** Create `SettingsStatusRow.kt`, `SettingsAccountCard.kt`.

- [ ] **Step 1: `SettingsStatusRow.kt`** — `name`, `status` ("active"), `dotColor` (default `cyanLight`). 7dp dot with a soft glow (`Modifier.shadow` or a `Box` blur substitute), name `bodyMedium onSurfaceVariant`, status `bodySmall textTertiary` end-aligned.
- [ ] **Step 2: `SettingsAccountCard.kt`** — params: `serviceName`, `accentColor`, `authState: AuthState`, `onConnect`, `onDisconnect`, optional `extraContent: (@Composable ColumnScope.() -> Unit)? = null` (match `AccountConnectionCard.kt:66` exactly so the existing `extraContent = { SpotifyAutoSaveSection(...) }` call sites drop in unchanged). Header row: 30dp rounded badge tinted `accentColor.copy(alpha=.15f)` with an `accentColor` dot, name (`titleMedium`), connection line (`onSurfaceVariant`; green "Connected as …" when `AuthState.Connected`), trailing ghost button ("Connect"/"Disconnect"). If `extraContent != null`, render it below a `HorizontalDivider(color = SettingsDivider)`.

> **Decision:** rather than build a new `SettingsAccountCard`, the lower-risk path is to **keep using the existing `AccountConnectionCard`** (it already has the exact API + self-gating) and only restyle it if D2 shows it off-brand. If you keep `AccountConnectionCard`, skip building `SettingsAccountCard` and note it in the commit. Only Spotify + YouTube Music use it; **Last.fm does NOT** (see Task C3).

- [ ] **Step 3: Compile + commit** — `feat(settings): kit — status row, account card`

### Task A5: Support banner + search field + scaffold

**Files:** Create `SupportBanner.kt`, `SettingsSearchField.kt`, `SettingsScaffold.kt`.

- [ ] **Step 1: `SupportBanner.kt`** — rounded 20dp card, `Brush.linearGradient(listOf(StashPurple.copy(.16f), StashCyan.copy(.06f)))` fill, `1dp` `StashPurpleLight.copy(.28f)` border. Title "Support Stash" (`titleMedium`), one-line pitch (`bodySmall onSurfaceVariant`), Row of two buttons: Donate (filled `primary`, heart icon) + Star (ghost glass, star icon). Params: `onDonate`, `onStar`. Reuse today's Support section copy + click handlers (find them under `SectionHeader(title = "Support Stash")`).
- [ ] **Step 2: `SettingsSearchField.kt`** — glass pill, search glyph + "Search settings" placeholder text in `textTertiary`. **Phase 1: non-functional** — accept `onClick: () -> Unit = {}` and render as a static pill (no `TextField`). A code comment marks it as the Phase-2 search entry point.
- [ ] **Step 3: `SettingsScaffold.kt`** — shared sub-screen frame: top bar with back chevron (`onBack`) + Space-Grotesk `title` (`titleLarge`), then a `Column` in a `verticalScroll` with 16dp horizontal padding and `SettingsGroupGap` between groups. `content: @Composable ColumnScope.() -> Unit`.
- [ ] **Step 4: Compile + commit** — `feat(settings): kit — support banner, search field, scaffold`

---

## Phase B — Navigation + Hub

### Task B1: Category routes + nav wiring

**Files:**
- Modify: `app/.../navigation/TopLevelDestination.kt`
- Modify: `app/.../navigation/StashNavHost.kt`

- [ ] **Step 1: Add routes** to `TopLevelDestination.kt` (next to `EqualizerRoute` etc.):

```kotlin
@Serializable data object SettingsPlaybackRoute
@Serializable data object SettingsAudioQualityRoute
@Serializable data object SettingsAccountsRoute
@Serializable data object SettingsLibraryStorageRoute
@Serializable data object SettingsAppearanceRoute
@Serializable data object SettingsAboutRoute
```

- [ ] **Step 2: Rewire `composable<SettingsRoute>`** in `StashNavHost.kt` to host the hub, navigating to each category route. Replace the current `SettingsScreen(...)` call with `SettingsHubScreen(onOpen<Category> = { navController.navigate(<Route>) }, onDonate=…, onStar=…)`.
- [ ] **Step 3: Add six category `composable<…Route>` blocks**, each resolving the shared ViewModel via the established pattern:

```kotlin
composable<SettingsPlaybackRoute> { backStackEntry ->
    val settingsEntry = remember(backStackEntry) { navController.getBackStackEntry(SettingsRoute) }
    val viewModel: com.stash.feature.settings.SettingsViewModel = hiltViewModel(settingsEntry)
    SettingsPlaybackScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
}
// …Audio&Quality (also passes onNavigateToEqualizer = { navController.navigate(EqualizerRoute) },
//   onNavigateToAntraConnect, onNavigateToSquidWtfCaptcha),
// …Accounts, …LibraryStorage (onNavigateToLibraryHealth), …Appearance, …About (onNavigateToDiagnosticsPreview).
```

Keep the existing `EqualizerRoute`/`LibraryHealthRoute`/`AntraConnectRoute`/`SquidWtfCaptchaRoute`/`DiagnosticsPreviewRoute` composables as-is — the category screens navigate to them.

- [ ] **Step 4: Compile the app module** (routes are app-level): `.\gradlew.bat :app:compileDebugKotlin` — expect SUCCESS once B2 + C* screens exist; until then it won't link, so **defer this compile to after Task C6** (note it here, run it in D1). For now just commit the route additions.
- [ ] **Step 5: Commit** — `feat(settings): category routes + nav wiring scaffold`

### Task B1a: Hub summaries (pure function — TDD)

**Files:**
- Create: `feature/settings/.../SettingsHubSummaries.kt`
- Test: `feature/settings/src/test/kotlin/com/stash/feature/settings/SettingsHubSummariesTest.kt`

This is the one genuinely unit-testable piece: deriving each hub row's current-state subtitle from `SettingsUiState`.

- [ ] **Step 1: Write the failing test** — assert the mapping for representative states:

```kotlin
class SettingsHubSummariesTest {
    private fun state(/* overrides */) = SettingsUiState(/* … */)

    @Test fun `playback summary reflects online and cellular`() {
        val s = settingsHubSummaries(state(/* online, cellular off */))
        assertEquals("Online · Cellular off", s.playback)
    }
    @Test fun `audio summary shows lossless tier when enabled`() {
        assertEquals("Lossless · Max 24/192", settingsHubSummaries(state(/* lossless on, Max */)).audioQuality)
    }
    @Test fun `audio summary shows download tier when lossless off`() { /* … */ }
    @Test fun `accounts summary lists connected services`() { /* "Spotify · YouTube" */ }
    @Test fun `appearance summary shows theme`() { assertEquals("Follow system", …) }
    @Test fun `about summary shows version`() { /* "v0.9.51" — pass version in, don't hardcode */ }
}
```

(Build real `SettingsUiState` instances — it's a data class with defaults. Pass app version into `settingsHubSummaries(state, versionName)` rather than reading `BuildConfig` inside the function, so it's pure/testable.)

- [ ] **Step 2: Run, verify compile failure.** `.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.stash.feature.settings.SettingsHubSummariesTest"`
- [ ] **Step 3: Implement** `settingsHubSummaries(state: SettingsUiState, versionName: String): HubSummaries` — a `data class HubSummaries(playback, audioQuality, accounts, libraryStorage, appearance, about: String)` built from the existing uiState fields (`losslessEnabled`/`losslessQualityTier`, `audioQuality`, `spotifyAuthState`/`youTubeAuthState`/`lastFmState`, `themeMode`, `totalStorageBytes`/`totalTracks`).
- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: Commit** — `feat(settings): hub subtitle derivation (pure, tested)`

### Task B2: Settings hub screen

**Files:** Create `SettingsHubScreen.kt`.

- [ ] **Step 1: Implement** `SettingsHubScreen(viewModel: SettingsViewModel = hiltViewModel(), onOpenPlayback, onOpenAudioQuality, onOpenAccounts, onOpenLibraryStorage, onOpenAppearance, onOpenAbout, onDonate, onStar)`. Collect `uiState`. **Derive the version name the same way the current screen does — `feature/settings` has no `BuildConfig`** (buildConfig is not enabled). Copy the lookup at `SettingsScreen.kt:1607` (`LocalContext.current.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "…fallback…"`) and pass it in: `summaries = settingsHubSummaries(uiState, versionName)`. Layout: title "Settings" → `SupportBanner(onDonate,onStar)` → `SettingsSearchField()` → `SettingsGroupCard(rows = [six SettingsNavRow(... summary, leadingIcon, onClick=onOpen…)])`. Reuse the screen's existing scroll container/background.
- [ ] **Step 2: Compile.** `.\gradlew.bat :feature:settings:compileDebugKotlin`
- [ ] **Step 3: Commit** — `feat(settings): SettingsHubScreen`

---

## Phase C — Category screens (re-home existing controls)

> Each screen wraps content in `SettingsScaffold(title, onBack)`. **Relocate** existing control blocks from `SettingsScreen.kt` (identified by their `SectionHeader(...)`/comment anchors — line numbers will have shifted post-#187), swapping bespoke rows for the kit where it's a clean win, but **never changing a control's `viewModel::on…` callback**. Each screen takes `viewModel: SettingsViewModel` + `onBack` (+ drill-in nav lambdas as noted).

### Task C1: Playback screen

**Files:** Create `SettingsPlaybackScreen.kt`.

- [ ] **Step 1:** Move the block currently under `// -- Playback (Online / Offline mode)` (the `if (StashConstants.STREAMING_ENGINE_ENABLED) { … }` gate — **preserve the gate**). Render: `SettingsSectionLabel("Mode")` → `SettingsSegmented(["Online","Offline"], …)` bound to the existing online/offline state+callback; `SettingsSectionLabel("Streaming")` → `SettingsGroupCard` of `SettingsToggleRow`s for stream-on-cellular, stream-via-YouTube, force-antra-only (test) — each wired to its current callback. If the flag is false, show an empty/"unavailable" state.
- [ ] **Step 2: Compile + commit** — `feat(settings): Playback category screen`

### Task C2: Audio & Quality screen

**Files:** Create `SettingsAudioQualityScreen.kt`. Takes `onNavigateToEqualizer`, `onNavigateToAntraConnect`, `onNavigateToSquidWtfCaptcha`, `onBack`.

- [ ] **Step 1:** Relocate the **Audio Quality** download-tier group, the **Lossless** card (toggle + quality picker → `SettingsPickerRow`s with size/min metadata, YouTube-fallback expander, Advanced), and the **Sources/routing** rows → `SettingsStatusRow`s (kennyy/squid) + antra-account `SettingsNavRow` (→ `onNavigateToAntraConnect`) + squid captcha entry (→ `onNavigateToSquidWtfCaptcha`). Add `SettingsSectionLabel("Effects")` → `SettingsNavRow("Equalizer", onClick = onNavigateToEqualizer)`. Preserve all lossless callbacks verbatim.
- [ ] **Step 2: Compile + commit** — `feat(settings): Audio & Quality category screen`

### Task C3: Accounts & Sync screen

**Files:** Create `SettingsAccountsScreen.kt`.

- [ ] **Step 1:** `SettingsSectionLabel("Connections")` → relocate the three account sections intact, **matching how they're rendered today** (`SettingsScreen.kt:554-645`):
  - **Spotify** — `AccountConnectionCard(serviceName="Spotify", …, extraContent = { SpotifyAutoSaveSection(...) })`.
  - **YouTube Music** — `AccountConnectionCard(serviceName="YouTube Music", …, extraContent = { YouTubeHistorySyncSection(...) })`.
  - **Last.fm** — NOT an account card. It's a bare `GlassCard { LastFmSection(...) }` with its own connect/finish/disconnect/drain UX and the inline `scrobbleDrainResult` text. Move it verbatim, GlassCard and all.
  Then — **only if PR #187 merged** — `SettingsSectionLabel("Sync your likes", beta = true)` → `LikeMirrorSection(...)` with its existing uiState + callbacks and the warning-dialog hookup. Keep every callback as-is.
- [ ] **Step 2: Compile + commit** — `feat(settings): Accounts & Sync category screen`

### Task C4: Library & Storage screen

**Files:** Create `SettingsLibraryStorageScreen.kt`. Takes `onNavigateToLibraryHealth`, `onBack`.

- [ ] **Step 1:** Relocate: Stash Mixes (Beta) toggle; Downloads network-rule group; Storage meter (`SettingsValueRow`s) + download location + Export/Import backup buttons + Move-library; and `SettingsNavRow("Library Health", onClick = onNavigateToLibraryHealth)`. Keep the import-confirmation `AlertDialog` + its state with this screen (move it here). Preserve all callbacks.
- [ ] **Step 2: Compile + commit** — `feat(settings): Library & Storage category screen`

### Task C5: Appearance screen (T1 theme preview cards)

**Files:** Create `SettingsAppearanceScreen.kt`.

- [ ] **Step 1: Implement the T1 picker.** `SettingsSectionLabel("Theme")` → a `Row` of three tappable theme thumbnails (Dark / Light / Follow system). Each thumbnail is a small rounded preview rendered in that palette: Dark = `#06060C` + `onSurface`/`textTertiary` bars + `primary` progress; Light = real light tokens (`StashBackgroundLight #F6F3FF`, `StashTextPrimaryLight`, `StashTextSecondaryLight`) ; Follow-system = diagonal split of the two. Selected thumb gets a 2dp `primary` ring + a `primary` check badge. Tapping calls the existing theme callback — `onThemeChanged: (ThemeMode) -> Unit` (`SettingsScreen.kt:295`, invoked at `:1092` as `onClick = { onThemeChanged(mode) }`) — with the matching `ThemeMode`. Below: a one-line hint for Follow system.
- [ ] **Step 2: Compile + commit** — `feat(settings): Appearance screen — theme preview cards`

### Task C6: About & Help screen

**Files:** Create `SettingsAboutScreen.kt`. Takes `onNavigateToDiagnosticsPreview`, `onBack`.

- [ ] **Step 1:** Relocate Diagnostics (Share diagnostics → `onNavigateToDiagnosticsPreview`, Share latest crash report → `onShareLatestCrashReport` — with their enabled/subtitle logic) + About (Version, License `SettingsValueRow`s; Check for updates). Preserve callbacks.
- [ ] **Step 2: Compile + commit** — `feat(settings): About & Help category screen`

---

## Phase D — Cutover & verification

### Task D1: Delete the monolith, link the app

**Files:** Modify `SettingsScreen.kt` (delete), `StashNavHost.kt`.

- [ ] **Step 1:** Remove the old stateless `SettingsScreen` composable. Its private `SectionHeader` is fully superseded by `SettingsSectionLabel` — **delete it**. Its private `StorageRow` was already relocated to the kit as `SettingsValueRow` (Task A1) — **delete the original**; update any remaining reference to use `SettingsValueRow`. Delete `SettingsScreen.kt` entirely once empty (the hub + category screens replace it).
- [ ] **Step 2:** Ensure `composable<SettingsRoute>` hosts `SettingsHubScreen` and all six category composables are present (Task B1).
- [ ] **Step 3: Compile the whole app.** `.\gradlew.bat :app:compileDebugKotlin` — expect BUILD SUCCESSFUL. Fix any dangling references (removed params, moved helpers).
- [ ] **Step 4: Run the settings unit test** (the one that exists): `.\gradlew.bat :feature:settings:testDebugUnitTest --tests "com.stash.feature.settings.SettingsHubSummariesTest"` — green.
- [ ] **Step 5: Commit** — `refactor(settings): cut over to hub-and-spoke; remove monolith`

### Task D2: On-device verification

Per project convention: `:app:installDebug`, then drive adb yourself.

- [ ] **Step 1: Install** — `.\gradlew.bat :app:installDebug`
- [ ] **Step 2: Hub** — Settings tab shows: Support banner pinned on top (Donate/Star tappable), search pill, six rows with thin icons + correct current-state subtitles. No giant scroll.
- [ ] **Step 3: Each category** — tap into all six; confirm every control works and writes through (toggle Online/Offline, cellular, lossless tier change, theme switch updates app, account connect/disconnect surfaces, Stash Mixes toggle, backup buttons, Library Health/Equalizer/antra/squid drill-ins open and return). Back chevron returns to the hub.
- [ ] **Step 4: Parity sweep** — verify nothing from the old 12 sections is missing or dead (walk the spec's IA table). Theme preview cards render correctly in both palettes.
- [ ] **Step 5: Screenshot** the hub + each category for the PR; confirm premium look (glass, purple labels, no emoji, Space Grotesk titles).
- [ ] **Step 6:** Push the branch; open a PR referencing the spec. Then use superpowers:finishing-a-development-branch.

## Execution notes

- Work in a dedicated worktree off `master` (copy `local.properties` in). **Branch only after #187 merges** so Accounts & Sync includes "Sync your likes"; otherwise proceed and skip that block (Task C3).
- Reviewer/agent model for any subagent dispatch: use `"opus"` (the `fable` subagent alias is unavailable in this harness).
- Keep commits per task. The whole feature is one PR; the six category screens are independent enough to review screen-by-screen.
