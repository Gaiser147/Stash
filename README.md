# Stash APK builds

Built in a Claude Code session, not by GitHub Actions. This branch only holds
APKs and is never merged.

| File | Source commit | Package | Version |
| --- | --- | --- | --- |
| `stash-autoplay-preview-4c1c5b1-arm64.apk` | `4c1c5b1` (branch `claude/navidrome-recommendation-algorithm-8v758e`, after PR #8): **Navidrome upload no longer re-sends songs after a failed cover; playlists are reached** — **newest** | `com.stash.app.preview` ("Stash Navidrome Preview") | 0.9.75-muse.1-navidrome-preview (112) |
| `stash-autoplay-preview-73569f7-arm64.apk` | `73569f7` (branch `claude/navidrome-recommendation-algorithm-8v758e`, PR #8): adds **reporting plays to Navidrome** | `com.stash.app.preview` ("Stash Navidrome Preview") | 0.9.75-muse.1-navidrome-preview (112) |
| `stash-autoplay-preview-ef08495-arm64.apk` | `ef08495` (branch `claude/navidrome-recommendation-algorithm-8v758e`, PR #8): adds **Mix for you** + shuffle fix | `com.stash.app.preview` ("Stash Navidrome Preview") | 0.9.75-muse.1-navidrome-preview (112) |
| `stash-autoplay-preview-ef9854c-arm64.apk` | `ef9854c` (branch `claude/navidrome-recommendation-algorithm-8v758e`, PR #8) | `com.stash.app.preview` ("Stash Navidrome Preview") | 0.9.75-muse.1-navidrome-preview (112) |

- CPU: **arm64-v8a only** (every current Android phone). The universal APK is
  167 MiB, over GitHub's 100 MB file limit, and Git LFS is disabled for forks,
  so the x86 / x86_64 / armeabi-v7a native libraries were removed from the built
  APK and it was re-signed with the same key. No code was changed.
- SHA-256 `stash-autoplay-preview-4c1c5b1-arm64.apk`: `12f0a2f9a2d6db8de7f6c74a036d685808a5a9c7b281dd167352aa17084ed0f2`
- SHA-256 `stash-autoplay-preview-73569f7-arm64.apk`: `493760faa8abe38266a23350b6b2a4363e45edde232c83e7ea6a85b2afe2de5a`
- SHA-256 `stash-autoplay-preview-ef08495-arm64.apk`: `847c203f87fa8d8228c1fb4fb2ef1a2c5d6d92cc085b64d90a37da0d975f37ec`
- SHA-256 (older build): `13bf8580f6c846f5b1900d2f5b71ae6a5cd537625d203e8a3c42943066863a0c`
- Signer: ephemeral Android debug key (certificate SHA-256 `18ff577b0ed925e5eb45941796bd440b94907ce03865866ef79c3a9aed63b13b`)

This is the **preview** channel (see `docs/RELEASE_CHANNELS.md`): it installs
next to the existing app with separate, disposable data, and it can **not**
update the installed `com.stash.app.debug` app, because that needs the
retained signing key, which the build environment doesn't have.

Download on your phone: open the `.apk` file on GitHub and tap **Download** (Raw).
