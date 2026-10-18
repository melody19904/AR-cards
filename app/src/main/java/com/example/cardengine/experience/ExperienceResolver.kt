package com.example.cardengine.experience

import com.example.cardengine.core.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ExperienceResolver(
    private val serverUrlProvider: () -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val sessionCache = ConcurrentHashMap<String, Experience>()

    private val localFallbackRegistry: Map<String, Experience> = mapOf(
        "test-target" to WebExperience("https://example.com"),
        "menu-001" to WebExperience("https://example.com"),
        "poster-001" to ImageExperience("https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=900&q=80"),
        "video-001" to VideoExperience("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4")
    )

    fun clearCache() {
        sessionCache.clear()
    }

    fun getCached(raw: String): Experience? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return WebExperience(trimmed)
        }
        return sessionCache[trimmed]
    }

    fun resolve(raw: String): Experience {
        val trimmed = raw.trim()

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return WebExperience(trimmed)
        }

        sessionCache[trimmed]?.let { return it }

        val fromServer = fetchFromServer(trimmed)
        if (fromServer != null && fromServer !is UnknownExperience) {
            sessionCache[trimmed] = fromServer
            return fromServer
        }

        val fallback = localFallbackRegistry[trimmed]
        if (fallback != null) {
            sessionCache[trimmed] = fallback
            return fallback
        }

        return UnknownExperience(trimmed)
    }

    private fun fetchFromServer(targetId: String): Experience? {
        val base = serverUrlProvider().trimEnd('/')
        if (base.isBlank()) return null

        return try {
            val encodedId = URLEncoder.encode(targetId, "UTF-8").replace("+", "%20")
            val request = Request.Builder()
                .url("$base/resolve/$encodedId")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)

                val type = json.optString("type", "").uppercase()
                val source = json.optString("source", "")
                if (source.isBlank()) return null

                when (type) {
                    "WEB" -> WebExperience(source)
                    "IMAGE" -> ImageExperience(source)
                    "VIDEO" -> VideoExperience(source)
                    "MODEL_3D" -> Model3DExperience(source)
                    "AUDIO" -> AudioExperience(source)
                    "DOCUMENT" -> DocumentExperience(source)
                    else -> UnknownExperience(source)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
