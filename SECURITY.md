# Security Policy

## Reporting a Vulnerability

If you discover a security issue in Stash — particularly anything related to credential handling, token storage, network transport, or data leakage — please report it privately so it can be fixed before public disclosure.

**How to report:**

1. Open a **[private security advisory](https://github.com/rawnaldclark/Stash/security/advisories/new)** on GitHub. This creates a confidential channel visible only to the maintainers.
2. Include:
   - A description of the issue and its potential impact.
   - Step-by-step reproduction if possible.
   - Your suggested severity (Critical / High / Medium / Low).
   - Whether you'd like credit in the release notes when the fix ships.

Please **do not** open a public issue, tweet about it, or post it on Reddit until a fix has been released and disclosed.

## Scope

The following are in scope for security reports:

- Credential or cookie leakage (Spotify sp_dc, YouTube cookies, any other secrets)
- Insecure storage of encrypted values (Tink/AES-256-GCM misuse)
- Network transport issues (TLS downgrade, certificate pinning bypass)
- SQL injection, path traversal, or arbitrary file write in the download pipeline
- Code execution via crafted audio files, malicious InnerTube responses, or yt-dlp edge cases
- Any issue that could let a malicious actor with network access to your device compromise your Stash credentials or downloaded library

The following are **out of scope** (but please still mention them in a regular issue if you see them):

- Issues that require physical device access, root, or ADB debugging
- Theoretical attacks without a working proof of concept
- Dependency vulnerabilities already tracked by Dependabot (we get those automatically)
- Social engineering or phishing scenarios
- Issues in Spotify, YouTube, or Google's own infrastructure

## Handling Your Credentials

Stash is designed so that you never have to trust the project maintainers with your accounts. Here's how credentials are handled:

- **Storage**: All tokens and cookies are encrypted at rest using Google's [Tink](https://developers.google.com/tink) library with AES-256-GCM. The encryption key is generated per-install and stored in Android's hardware-backed Keystore.
- **Transport**: Spotify and YouTube credentials are sent only to their respective service hosts. TLS 1.2 or higher is enforced. If the optional Navidrome export is enabled, its separate bearer token is sent only to the user-configured HTTPS ingest URL; Spotify and YouTube login cookies are never included in those requests.
- **No required server**: Stash has no required account or Stash-operated backend, and there is no telemetry. The optional one-way Navidrome export talks only to an ingest service the user explicitly configures. Its exact data flow is documented in [docs/NAVIDROME_EXPORT.md](docs/NAVIDROME_EXPORT.md).
- **Open source**: Every line of code that touches credentials is in this repo and can be audited. See `core/auth/` and `data/spotify/` and `data/ytmusic/`.

## Fork Artifact Signing

Debug APKs built without a persistent signing key are CI test artifacts, not an update channel. A newly generated debug key cannot update an already installed APK with the same application ID.

The Navidrome fork workflow labels an artifact `stable-signed` only when all four repository secrets are configured and the keystore can be opened:

- `STASH_DEBUG_KEYSTORE_BASE64`
- `STASH_DEBUG_KEYSTORE_PASSWORD`
- `STASH_DEBUG_KEY_ALIAS`
- `STASH_DEBUG_KEY_PASSWORD`

Keep the keystore and passwords outside the repository, restrict access to the Actions environment, and retain an offline backup. Losing or rotating this key requires uninstalling the previous debug app before installing a newly signed build, which can delete app-local data unless it was backed up first. Never describe an artifact labeled `ci-only` as upgrade-safe.

## Response Timeline

For reports filed via GitHub Security Advisories:

- **72 hours** — initial acknowledgment that we've seen the report.
- **7 days** — first assessment with a severity rating and whether it's accepted as in-scope.
- **30 days** — target for a fix to land in a release build, assuming the issue is reproducible and actionable.

Critical issues affecting credential handling will be prioritized above all other work.

## Thank You

Stash is a small hobby project maintained in spare time. Security researchers who report issues responsibly are genuinely appreciated and will be credited in release notes (with your permission).
