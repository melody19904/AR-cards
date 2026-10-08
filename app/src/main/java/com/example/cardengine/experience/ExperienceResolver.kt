package com.example.cardengine.experience

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves a scanned string into an Experience.
 *
 * Two kinds of input reach this:
 *   1. A bare target id (e.g. "menu-001") encoded directly in a QR.
 *   2. A full https:// URL — treated as a WEB experience whose "target id"
 *      is the URL itself, so a plain web QR Just Works without a server
 *      round trip.
 *
 * Network call: GET {serverUrl}/resolve/{id}
 * Expected response:
 *   { "target_id": "menu-001", "type": "WEB", "source": "https://..." }
 *
 * On any failure (no network, 404, malformed json) we fall back to a small
 * local registry so demos/testing work with the server offline.
 */
class ExperienceResolver(
    private val serverUrlProvider: () -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    // Local fallback registry — used only when the server is unreachable.
    // Keep real target ids here so recognition still resolves something
    // sane on a flaky connection. Populate via manage_targets.py; this map
    // is a cache of that, not the source of truth.
    private val localRegistry: Map<String, Experience> = mapOf()

    fun resolve(raw: String): Experience {
        val trimmed = raw.trim()

        // Case 1: it's already a URL -> WEB experience, no network call needed.
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return Experience.Web(targetId = trimmed, url = trimmed)
        }

        // Case 2: bare target id -> ask the server.
        val fromServer = fetchFromServer(trimmed)
        if (fromServer != null) return fromServer

        // Case 3: offline fallback.
        return localRegistry[trimmed] ?: Experience.Unknown(trimmed)
    }

    private fun fetchFromServer(targetId: String): Experience? {
        val base = serverUrlProvider().trimEnd('/')
        if (base.isBlank()) return null

        return try {
            val req = Request.Builder()
                .url("$base/resolve/$targetId")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)

                val type = json.optString("type", "").uppercase()
                val source = json.optString("source", "")
                if (source.isBlank()) return null

                when (type) {
                    "WEB" -> Experience.Web(targetId, source)
                    "IMAGE" -> Experience.Image(targetId, source)
                    "VIDEO" -> Experience.Video(targetId, source)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

