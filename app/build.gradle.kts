import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// ── Release signing configuration ───────────────────────────────────────────
//
// Reads signing credentials from one of two sources, in order of precedence:
//
//  1. `keystore.properties` in the repo root (local development). The file is
//     gitignored and never leaves the developer's machine.
//
//  2. Environment variables (CI / GitHub Actions). The release workflow
//     base64-decodes a secret into a temporary .jks file and exports the
//     passwords as env vars before running `assembleRelease`.
//
// If neither source is available, an ordinary local release build may remain
// unsigned/debug-signed for contributor testing. A build explicitly marked as
// distributable fails closed below; CI must never publish that fallback.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingProp(key: String, envKey: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(envKey)

val releaseStoreFilePath = signingProp("storeFile", "STASH_KEYSTORE_FILE")
val releaseStorePassword = signingProp("storePassword", "STASH_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProp("keyAlias", "STASH_KEY_ALIAS")
val releaseKeyPassword = signingProp("keyPassword", "STASH_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFilePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

// Optional stable signer for fork debug artifacts. Without these values the
// ordinary Android debug key is used, which is suitable for CI compilation but
// must not be advertised as an in-place update for an installed fork build.
val debugStoreFilePath = System.getenv("STASH_DEBUG_KEYSTORE_FILE")
val debugStorePassword = System.getenv("STASH_DEBUG_KEYSTORE_PASSWORD")
val debugKeyAlias = System.getenv("STASH_DEBUG_KEY_ALIAS")
val debugKeyPassword = System.getenv("STASH_DEBUG_KEY_PASSWORD")
val hasStableDebugSigning = listOf(
    debugStoreFilePath,
    debugStorePassword,
    debugKeyAlias,
    debugKeyPassword,
).all { !it.isNullOrBlank() }

// A CI-only preview uses a distinct application ID so an ephemeral signer can
// never collide with or require uninstalling the user's existing debug app.
val isSideBySidePreview = providers.gradleProperty("stash.sideBySidePreview")
    .orNull
    ?.toBooleanStrictOrNull() == true

// Distribution channels are explicit so the data-preserving upgrade package
// can keep its historic application id while new installations receive a
// stable, fork-owned namespace that cannot collide with upstream Stash.
val isCommunityBuild = providers.gradleProperty("stash.communityBuild")
    .orNull
    ?.toBooleanStrictOrNull() == true
val isDistributableBuild = providers.gradleProperty("stash.distributable")
    .orNull
    ?.toBooleanStrictOrNull() == true
val requestedTasks = gradle.startParameter.taskNames.map(String::lowercase)
val requestsCommunityRelease = isCommunityBuild && requestedTasks.any {
    it.contains("assemble") || it.contains("bundle") || it.contains("publish")
}
val requestsLegacyProd = requestedTasks.any { it.contains("legacyprod") }

if (isDistributableBuild && requestsCommunityRelease && !hasReleaseSigning) {
    throw GradleException(
        "A distributable community build requires all STASH_KEYSTORE_* release credentials.",
    )
}
if (requestsLegacyProd && !hasStableDebugSigning) {
    throw GradleException(
        "A legacyProd build requires the retained STASH_DEBUG_KEYSTORE_* signer.",
    )
}

// ── Last.fm API credentials ────────────────────────────────────────────────
//
// Read from `local.properties` (gitignored) or env vars (CI). Users who want
// Last.fm scrobbling need to register a Last.fm API account at
// https://www.last.fm/api/account/create and drop the key/secret into
// local.properties as:
//   lastfm.apiKey=...
//   lastfm.apiSecret=...
// Missing credentials are NOT an error — the app builds + runs normally, the
// Last.fm UI just shows "Not configured" and the scrobbler no-ops.
val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties().apply {
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val lastFmApiKey: String =
    localProperties.getProperty("lastfm.apiKey") ?: System.getenv("LASTFM_API_KEY").orEmpty()
val lastFmApiSecret: String =
    localProperties.getProperty("lastfm.apiSecret") ?: System.getenv("LASTFM_API_SECRET").orEmpty()
// Optional comma-separated pool of EXTRA api keys used only for unsigned read
// endpoints (tag/similar/etc.) to spread load and raise the shared-key rate
// ceiling. No secret needed — reads are unsigned. Auth/scrobble always use the
// primary key above. Empty by default.
val lastFmExtraApiKeys: String =
    localProperties.getProperty("lastfm.extraApiKeys") ?: System.getenv("LASTFM_EXTRA_API_KEYS").orEmpty()
// Optional Stash Last.fm proxy Worker base URL. When set, generic read lookups
// route through it (the Worker holds the server key + shared cache), decoupling
// Last.fm load from user count. Empty = app talks to Last.fm directly.
val lastFmProxyUrl: String =
    localProperties.getProperty("lastfm.proxyUrl") ?: System.getenv("LASTFM_PROXY_URL").orEmpty()

// ── Muse companion default endpoint ────────────────────────────────────────
//
// Prefills the Muse "connect" screen so a fresh install only has to tap
// "Koppeln" and approve in Discord — no typing the HTTPS address. Overridable
// per build via local.properties (`muse.defaultEndpoint=https://host`) or the
// MUSE_DEFAULT_ENDPOINT env var; empty falls back to the current manual-entry
// behavior. Must be a bare https origin (no path/query) — MuseEndpoint.normalize
// rejects anything else at runtime.
val museDefaultEndpoint: String =
    localProperties.getProperty("muse.defaultEndpoint")
        ?: System.getenv("MUSE_DEFAULT_ENDPOINT")
        ?: "https://learnwithclawdbot.com"

android {
    namespace = "com.stash.app"
    compileSdk = 35
    defaultConfig {
        applicationId = if (isCommunityBuild) "com.gaiser147.stash" else "com.stash.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 112
        versionName = "0.9.75-muse.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Stash"
        // AppAuth redirect scheme removed -- Spotify now uses sp_dc cookie auth
        // Last.fm API credentials exposed via BuildConfig for the app-level
        // Hilt module to inject into LastFmApiClient. Empty strings are
        // valid — the Settings UI just disables the connect button.
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmApiKey\"")
        buildConfigField("String", "LASTFM_API_SECRET", "\"$lastFmApiSecret\"")
        buildConfigField("String", "LASTFM_EXTRA_API_KEYS", "\"$lastFmExtraApiKeys\"")
        buildConfigField("String", "LASTFM_PROXY_URL", "\"$lastFmProxyUrl\"")
        // v0.9.13: TipJarRepository fetches the public supporters JSON from
        // this URL on Home foreground (cache-aware, ~15 min refresh). Edit
        // the JSON and push to update the supporter list without an APK
        // release. Forks repoint by overriding this single line.
        buildConfigField(
            "String",
            "SUPPORTERS_JSON_URL",
            "\"https://stash-tipjar.rawnaldclark.workers.dev\"",
        )
        // Online-Streaming Engine master kill-switch. Flipped to `true` once
        // the engine has been validated end-to-end (see Task 23 of the
        // online-streaming-engine plan). Until then the Home toggle stays
        // hidden and the Hilt wiring in StashApplication stays inert. The
        // feature-module-facing mirror lives at
        // `com.stash.core.common.constants.StashConstants.STREAMING_ENGINE_ENABLED`
        // — keep both in sync (Task 23 flips both at once).
        buildConfigField("Boolean", "STREAMING_ENGINE_ENABLED", "true")
        // Muse companion default endpoint. Prefills the connect screen; the
        // feature-module-facing mirror lives at
        // `com.stash.core.common.constants.StashConstants.MUSE_DEFAULT_ENDPOINT`
        // — keep both in sync.
        buildConfigField("String", "MUSE_DEFAULT_ENDPOINT", "\"$museDefaultEndpoint\"")
        // The current sp_dc/Web Player connector is intentionally absent from
        // the regular community channel. It remains available only to the
        // private legacy/lab build until a separate policy/security gate passes.
        buildConfigField(
            "Boolean",
            "EXPERIMENTAL_SPOTIFY_COOKIE_ENABLED",
            (!isCommunityBuild).toString(),
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        if (hasStableDebugSigning) {
            create("stableDebug") {
                storeFile = rootProject.file(debugStoreFilePath!!)
                storePassword = debugStorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 minification is DISABLED because youtubedl-android embeds
            // Chaquopy (Python runtime) + Apache Commons Compress + native
            // binaries that use extensive reflection. R8 obfuscation renames
            // classes those libraries look up by string name, causing
            // "class X is not a concrete class" crashes on startup.
            //
            // This is the standard approach for apps in this space: NewPipe,
            // Seal, YTDLnis, InnerTune all ship with minification off. The
            // APK is ~5-10MB larger without minification (negligible next to
            // the ~145MB Python/yt-dlp bundle it already carries), and the
            // code-obfuscation benefit is moot for an open-source GPL-3.0
            // project anyway.
            //
            // Resource shrinking is also off because it requires minification.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only wire the release signing config if credentials were found.
            // Otherwise release builds fall back to the debug key for local testing.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = if (isSideBySidePreview) ".preview" else ".debug"
            if (isSideBySidePreview) {
                versionNameSuffix = "-navidrome-preview"
            }
            manifestPlaceholders["appLabel"] = if (isSideBySidePreview) {
                "Stash Navidrome Preview"
            } else {
                "Stash Debug"
            }
            isDebuggable = true
            if (hasStableDebugSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
        create("legacyProd") {
            // Real in-place successor for the installed com.stash.app.debug
            // package: same application id + signer, but no debug surface.
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Stash"
            isDebuggable = false
            matchingFallbacks += listOf("release", "debug")
            if (hasStableDebugSigning) {
                signingConfig = signingConfigs.getByName("stableDebug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        jniLibs {
            // Must extract native libs to disk so ffmpeg/ffprobe executables and
            // libc++_shared.so are in the same directory and can be linked at runtime.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":core:network"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:nowplaying"))
    implementation(project(":feature:sync"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:search"))
    implementation(project(":feature:muse"))
    implementation(project(":data:download"))
    // data:ytmusic provides AlbumSummary, used by SearchScreen/ArtistProfileScreen
    // callback signatures that StashNavHost wires up for Album Discovery.
    implementation(project(":data:ytmusic"))
    // data:lyrics exposes LyricsBackfillScheduler for the v0.9.36 once-per-version
    // auto-enqueue path wired in StashApplication.onCreate.
    implementation(project(":data:lyrics"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    // ProcessLifecycleOwner — used by StashApplication to start/stop
    // SquidCookieAutoRefresher on app foreground/background transitions.
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    // Hilt WorkManager integration: provides HiltWorkerFactory for @HiltWorker classes.
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    // ── SPIKE: spike/clap-on-device (throwaway, do NOT merge to master) ──────
    // androidTest-only ONNX Runtime + instrumentation deps for the on-device
    // CLAP embedding measurement harness (com.stash.app.clapspike). Wired as
    // androidTestImplementation on purpose so the shipping APK stays unbloated.
    androidTestImplementation(libs.onnxruntime.android)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.truth)

    // ── GATE: amz client-side decrypt (-decryption_key support probe) ────────
    // One-shot on-device check that the bundled youtubedl-android ffmpeg accepts
    // `-decryption_key` (the amz CMAF decrypt recipe). The .so already ships in
    // the app APK via :data:download; these only put YoutubeDL/FFmpeg.init() on
    // the androidTest compile classpath so the probe can extract the binary.
    androidTestImplementation(libs.youtubedl.android)
    androidTestImplementation(libs.youtubedl.ffmpeg)
}
