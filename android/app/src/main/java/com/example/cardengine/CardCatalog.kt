package com.example.cardengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches card list, references, and renders.
 *
 * Offline behaviour: if the server is unreachable, everything falls back to the
 * permanent vault at filesDir/vault/<cardId>/. Cards saved there (by VaultStore on
 * first unlock) provide the reference, the render, and the metadata so the app
 * remains fully usable with the server down.
 */
class CardCatalog(private val context: Context, private val baseUrl: String) {

    class LoadedCard(
        val id: String,
        val name: String,
        val rarity: String,
        val description: String,
        val referenceGray: Mat
    )

    // CRITICAL FIX: Bumped read timeout to 60 seconds to prevent BrokenPipeErrors on 3MB animation downloads
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val cacheDir = File(context.filesDir, "cardcache").apply { mkdirs() }
    private val vaultDir = File(context.filesDir, "vault").apply { mkdirs() }

    /** True if the most recent sync() got its card list from the server. */
    var lastSyncWasOnline: Boolean = false
        private set

    /**
     * Try the server. If it answers, use it. Otherwise fall back to the local vault.
     * Throws only if BOTH fail.
     */
    fun sync(): List<LoadedCard> {
        try {
            val listJson = httpGet("$baseUrl/cards")
            if (listJson != null) {
                val out = parseFromServer(listJson)
                lastSyncWasOnline = true
                return out
            }
        } catch (_: Exception) {
            // fall through to local
        }
        val local = loadFromVault()
        lastSyncWasOnline = false
        return local
    }

    private fun parseFromServer(listJson: String): List<LoadedCard> {
        val arr = JSONArray(listJson)
        val out = ArrayList<LoadedCard>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            if (id.isBlank()) continue

            val name = o.optString("name", id)
            val rarity = o.optString("rarity", "")
            val desc = o.optString("description", "")

            // Prefer a cached reference; otherwise download once and cache it.
            val jpg = File(cacheDir, "$id.jpg")
            if (!jpg.exists()) {
                val bytes = httpGetBytes("$baseUrl/cards/$id/card.jpg") ?: continue
                jpg.writeBytes(bytes)
            }
            val gray = decodeGray(jpg.readBytes()) ?: continue

            out.add(LoadedCard(id, name, rarity, desc, gray))
        }
        if (out.isEmpty()) throw IllegalStateException("server returned no usable cards")
        return out
    }

    /** Build LoadedCard entries from filesDir/vault/<id>/{reference.jpg, meta.json}. */
    private fun loadFromVault(): List<LoadedCard> {
        val dirs = vaultDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val out = ArrayList<LoadedCard>(dirs.size)

        for (d in dirs) {
            val metaFile = File(d, "meta.json")
            val refFile = File(d, "reference.jpg")
            if (!metaFile.exists() || !refFile.exists()) continue
            try {
                val meta = JSONObject(metaFile.readText())
                val gray = decodeGray(refFile.readBytes()) ?: continue
                out.add(
                    LoadedCard(
                        meta.optString("id", d.name),
                        meta.optString("name", d.name),
                        meta.optString("rarity", ""),
                        meta.optString("description", ""),
                        gray
                    )
                )
            } catch (_: Exception) {
                // skip malformed entry
            }
        }
        if (out.isEmpty()) throw IllegalStateException("no server and empty vault")
        return out
    }

    /**
     * Render PNG for a card. SERVER-FIRST, not cache-first: if you update render.png
     * on the server, every device must pick that up on its next sync, including
     * cards already sitting in someone's vault. Lookup order:
     *   1. Server (refreshes the session cache + vault copy on success)
     *   2. Session cache (filesDir/cardcache/<id>.render.png)
     *   3. Vault        (filesDir/vault/<id>/render.png)   <- true offline path
     */
    fun fetchRender(cardId: String): Bitmap? {
        val bytes = fetchRenderBytes(cardId) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun fetchRenderBytes(cardId: String): ByteArray? {
        httpGetBytes("$baseUrl/cards/$cardId/render.png")?.let { bytes ->
            try { File(cacheDir, "$cardId.render.png").writeBytes(bytes) } catch (_: Exception) {}
            return bytes
        }
        val cached = File(cacheDir, "$cardId.render.png")
        if (cached.exists()) return cached.readBytes()
        val vaulted = File(vaultDir, "$cardId/render.png")
        if (vaulted.exists()) return vaulted.readBytes()
        return null
    }

    /**
     * Reference JPEG for a card. Same server-first lookup; offline source is
     * filesDir/vault/<id>/reference.jpg.
     */
    fun fetchReferenceBytes(cardId: String): ByteArray? {
        httpGetBytes("$baseUrl/cards/$cardId/card.jpg")?.let { bytes ->
            try { File(cacheDir, "$cardId.jpg").writeBytes(bytes) } catch (_: Exception) {}
            return bytes
        }
        val cached = File(cacheDir, "$cardId.jpg")
        if (cached.exists()) return cached.readBytes()
        val vaulted = File(vaultDir, "$cardId/reference.jpg")
        if (vaulted.exists()) return vaulted.readBytes()
        return null
    }

    /** List animation names available for a card. Empty if none or offline. */
    fun fetchAnimationNames(cardId: String): List<String> {
        val raw = httpGet("$baseUrl/cards/$cardId/animations") ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    fun fetchAnimationBundle(cardId: String, animName: String): String? {
        return httpGet("$baseUrl/cards/$cardId/animations/$animName/bundle")
    }

    private fun httpGet(url: String): String? {
        return try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGetBytes(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeGray(jpeg: ByteArray): Mat? {
        val m = Imgcodecs.imdecode(MatOfByte(*jpeg), Imgcodecs.IMREAD_GRAYSCALE)
        return if (m.empty()) null else m
    }
}