package com.stash.feature.muse

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object MuseEndpoint {
    fun normalize(raw: String): String {
        val value = raw.trim().removeSuffix("/")
        val parsed = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Bitte eine vollständige HTTPS-Adresse eingeben.")
        require(parsed.scheme == "https") { "Muse muss über HTTPS erreichbar sein." }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Zugangsdaten gehören nicht in die Muse-Adresse."
        }
        require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
            "Die Muse-Adresse darf keinen Pfad, Query oder Fragment enthalten."
        }
        return parsed.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
    }

    fun route(endpoint: String, vararg segments: String): HttpUrl {
        val base = normalize(endpoint).toHttpUrlOrNull()
            ?: error("Stored Muse endpoint is invalid")
        return base.newBuilder().apply {
            addPathSegments("companion/v1")
            segments.forEach(::addPathSegment)
        }.build()
    }

    /**
     * Same as [route] but with query parameters. The request proof signs the
     * exact encoded path *and* query, so the parameters must be part of the URL
     * before signing — which they are, since the proof is derived from the
     * built request.
     */
    fun routeWithQuery(
        endpoint: String,
        segments: List<String>,
        query: Map<String, String>,
    ): HttpUrl = route(endpoint, *segments.toTypedArray()).newBuilder().apply {
        query.forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()
}
