# Ein-Klick-Pairing für Muse/Stash — Implementierungsplan

Stand: 2026-07-15. Branch-Basis: `codex/navidrome-v0.9.75` (Stash) und
`main` (muse-discord7server). Feature-Branches werden separat abgezweigt.

## Kontext / Ziel

Heute muss ein Nutzer beim ersten Verbinden **drei Dinge von Hand** eintragen:
die Companion-HTTPS-Adresse, die Acquisition-Base-URL und einen 32+-Zeichen-
Bearer-Token (den man vom Pi kopieren muss). Das ist fehleranfällig und für
Freunde praktisch unbrauchbar. Ziel: **URLs sind vorbelegt, der Token kommt
automatisch, und das Pairing läuft über einen QR/Deep-Link + eine Bestätigung
im Muse-Bot.**

## Zentrale Architektur-Erkenntnisse (aus Code-Exploration)

1. **Pairing ist heute client-initiiert.** Stash fordert Challenge an, erzeugt
   P-256-Proof, legt das Pairing an (`pairingId`/`pairingCode`/`pairingSecret`
   entstehen server- aber auf Client-Anfrage), zeigt den 8-stelligen Code, und
   der Discord-Nutzer tippt ihn im `/music → Geräte`-Modal ein. Discord ist nur
   die **Genehmigungsseite**. → **Der QR kann komplett clientseitig gerendert
   werden**, sobald Stash den Endpoint kennt. Kein serverseitiger Begin-Pairing-
   Umbau nötig.
   - Muse: `src/commands/music.ts:1315-1450` (Modal-Flow), `src/services/companion-devices.ts:241-291` (Code-Erzeugung + `approvePairing`).
   - Stash: `MuseCompanionRepository.kt:43-100` (`createPairing`/`pollPairing`), `MuseViewModel.kt:122-127`.

2. **Der Acquisition-Token ist heute GLOBAL** (`ACQUISITION_INBOX_TOKEN`, ein
   Digest, `timingSafeEqual` — `acquisition-inbox-server.ts:55,270-278`). Nicht
   pro-Gerät. Muse hat aber bereits ein Muster für **pro-Vorgang-gescopte
   Bearer**: die job-scoped Upload-Tickets (`acquisition-v2-jobs.ts:1504-1511`).

3. **Die Companion-Poll-Antwort ist der natürliche Auslieferungskanal.**
   `POST /companion/v1/pairings/{id}/poll` liefert nach Genehmigung
   `accessToken`/`refreshToken`/`device`/`grant` zurück
   (`companion-devices.ts:1009-1128`, Typ `:82-94`). Stash liest sie in
   `MusePairingPairedResponse` mit `ignoreUnknownKeys=true`
   (`MuseCompanionModels.kt:94-104`) — **zusätzliche Felder sind
   rückwärtskompatibel**.

4. **BuildConfig-Modulgrenze:** `com.stash.app.BuildConfig` ist nur im `app`-
   Modul sichtbar. `feature:muse` und `data:download` sehen es nicht →
   Mirror-Konstante in `core:common` `StashConstants` (Muster wie
   `STREAMING_ENGINE_ENABLED`), Wert per `local.properties`/ENV wie die
   `LASTFM_*`-Keys (`app/build.gradle.kts:92-167`).

5. **Modul-Dependency fehlt:** `feature:muse` hängt heute NICHT an
   `data:download` (wo die Acquisition-Prefs liegen). Für den Token-Schreibzugriff
   braucht es entweder eine neue Gradle-Dependency oder — sauberer — ein
   Bridge-Interface/Hilt-EntryPoint.

## Design-Entscheidung: pro-Gerät-Acquisition-Token (nicht den globalen ausliefern)

Statt den globalen `ACQUISITION_INBOX_TOKEN` an jedes Gerät zu verteilen (dann
könnte jedes gekoppelte Gerät ihn weitergeben und ein Widerruf wäre unmöglich
ohne alle neu zu pairen), stellt Muse bei der Genehmigung einen **geräte-
gebundenen, widerrufbaren Acquisition-Token** aus. Das ist nur wenig mehr
Aufwand, weil die Digest-Prüfung schon existiert — sie muss nur eine kleine
Menge gültiger Digests statt genau eines akzeptieren, und der Token wird an
`tokenVersion`/`device` gebunden (Widerruf über den bestehenden
Revoke-Pfad `companion-devices.ts`, der `tokenVersion` erhöht).

## Arbeitspakete

### A. Muse-Server: Acquisition-Token über die Poll-Antwort ausliefern

1. **Token ausstellen** in `consumeApprovedPairing()`
   (`companion-devices.ts:1009-1128`): nur wenn der genehmigte Scope-Satz
   Acquisition erlaubt (neue Rolle-/Scope-Bindung, s.u.), einen geräte-
   gebundenen Token erzeugen — abgeleitet aus `deviceId`+`tokenVersion`+einem
   neuen Server-Pepper (analog `protectedDigest`), damit der Server ihn ohne
   Speicherung neu verifizieren kann.
2. **Poll-Response-Typ** `CompanionPairingPoll` (`companion-devices.ts:82-94`)
   um `acquisitionToken?` + `acquisitionEndpoint?` erweitern; im Erfolgszweig
   (`:1122-1127`) mitgeben. Endpoint = konfigurierte öffentliche Acquisition-URL
   (neue optionale Config, Default = Companion-Origin + `/muse-acquisition`).
3. **Acquisition-Auth erweitern** (`acquisition-inbox-server.ts:270-278`):
   `isAuthorized()` akzeptiert weiterhin den globalen Token (Rückwärtskompat für
   Stash-Settings-Weg) UND geräte-gebundene Tokens, indem es den Bearer gegen
   die deterministische Ableitung prüft (HMAC über `deviceId:tokenVersion` mit
   dem neuen Pepper; die Menge aktiver Geräte kommt aus `companion-devices`).
   Ein widerrufenes Gerät (erhöhte `tokenVersion`) validiert dann nicht mehr.
4. **Scope/Rolle:** neuer Scope `acquisition:submit` in
   `companion-contract.ts:8-27`; nur an Rollen `admin_download_node`/`trust_pool`
   bindbar (`assertRoleScopes` `companion-devices.ts:1297-1306`). Discord-Mapping
   `deviceScopes()` (`music.ts:1573-1584`) entsprechend ergänzen.
5. **Config:** optionale `ACQUISITION_PUBLIC_URL` + neuer Pepper
   `ACQUISITION_DEVICE_TOKEN_PEPPER` in `config.ts` (fail-fast nur wenn
   Acquisition aktiv). Env auf dem Pi 4 ergänzen (separater Schritt, kein Commit).
6. **Tests:** Poll liefert Token nur bei passendem Scope; global+device-Token
   beide akzeptiert; widerrufenes Gerät abgelehnt; Spotify-Rollen bekommen keinen
   Acquisition-Token. Analog zu `tests/companion-*.test.ts` und
   `tests/acquisition-inbox-server.test.ts`.

### B. Stash-App: Endpoint-Defaults + Token-Übernahme

1. **BuildConfig-Defaults**: `MUSE_DEFAULT_ENDPOINT` (+ optional
   `MUSE_ACQUISITION_ENDPOINT`) als `buildConfigField` in
   `app/build.gradle.kts:139-167`, Wert aus `local.properties`/ENV (Muster
   `LASTFM_*`, leerer Fallback erlaubt). Mirror in `core:common`
   `StashConstants.kt:40` mit Sync-Kommentar.
2. **Endpoint vorbelegen**: in `MuseViewModel.fromStored` (`MuseViewModel.kt:447`)
   den Draft mit dem normalisierten Default füllen, wenn nichts gespeichert ist.
   Muss durch `MuseEndpoint.normalize` (`MuseEndpoint.kt:6-19`) — also nur
   `https://host`, kein Pfad. Nutzer sieht die Adresse vorausgefüllt, kann sie
   ändern.
3. **Acquisition-Token aus Poll-Antwort übernehmen**:
   - Feld `acquisitionToken`/`acquisitionEndpoint` in `MusePairingPairedResponse`
     (`MuseCompanionModels.kt:94-104`) — `ignoreUnknownKeys` hält es
     rückwärtskompatibel.
   - Im `Paired`-Zweig von `pollPairing()` (`MuseCompanionRepository.kt:94-97`)
     nach `savePaired(...)` den Token in die Acquisition-Prefs schreiben.
   - Neue Schreibmethode in `MuseAcquisitionPreferences.kt` (analog
     `saveConnection` `:96-108`, aber ohne die UI-Mindestlänge, mit
     Companion-gelieferter URL), Token Tink-verschlüsselt unter
     `token_encrypted_v1`. Danach `MuseAcquisitionScheduler.refreshSchedule()`.
   - **Modulgrenze lösen:** Bridge-Interface (z.B. `AcquisitionTokenSink`) im
     `data:download`, per Hilt in `feature:muse` injiziert — vermeidet eine harte
     `feature:muse → data:download`-Dependency-Umkehr.

### C. Deep-Link/QR für „ein Tap statt tippen" (optional, additiv)

Da der QR clientseitig gerendert werden kann und der Endpoint per Default schon
gesetzt ist, ist der Restnutzen eines QR gering — der Nutzer muss ohnehin nur
„Koppeln" tippen und dann in Discord bestätigen. Empfehlung: **Deep-Link
`stash://pair?endpoint=…` als Bequemlichkeit** (kein Kamera-Zwang), damit ein
in Discord/Chat geteilter Link Stash öffnet, Endpoint setzt und `startPairing()`
auslöst:
- Manifest-Intent-Filter (`app/src/main/AndroidManifest.xml:48-75`),
- `MainActivity.handleDeepLinkIntent` (`MainActivity.kt:82-92`) um `intent.data`
  erweitern, Endpoint an `MuseViewModel.applyPairingLink(endpoint)` reichen,
- `MuseRoute` parametrisieren (`TopLevelDestination.kt:27`, `StashNavHost.kt:110`).
Echtes Kamera-QR (ML Kit/ZXing + `CAMERA`-Permission) ist bewusst **out of scope**
der ersten Welle — hoher Aufwand, wenig Mehrwert gegenüber Default-Endpoint+Link.

## Reihenfolge & Validierung

1. **A zuerst** (Muse-Server), weil B davon abhängt. Lokale Tests + GitHub
   Actions grün, nichts deployen/enablen. PR auf `muse-discord7server`.
2. **B danach** (Stash): Unit-Tests für Repository/Prefs, `assembleDebug`,
   stable-signed CI-Kandidat wie gehabt. PR auf `Stash`.
3. **C optional** als dritte, additive Welle.
4. **Rollout:** neuer signierter Stash-Release-Kandidat; auf dem Pi 4 die neuen
   Env-Variablen setzen und Muse-Container in einem kontrollierten Restart
   neu laden (separater, freigegebener Schritt).

## Bewusst außerhalb des Scopes
- Storage-Gate (507) bleibt, bis Musik migriert/Volume erweitert ist — die
  Acquisition-Uploads funktionieren erst danach produktiv.
- Kein serverseitiger Begin-Pairing-Umbau (der QR bleibt clientseitig).
- Kamera-QR-Scan.
- `COMPANION_WORKER_POOL_ENABLED` und Spotify-Gates bleiben unberührt.
