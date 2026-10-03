package com.example.cardengine

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Permanent, offline storage for cards the user has unlocked.
 * Layout: filesDir/vault/<cardId>/{render.png, reference.jpg, meta.json}
 *
 * Once a card is written here, it stays regardless of server availability.
 * The vault screen reads only from this directory.
 */
class VaultStore(private val context: Context) {

    private val root = File(context.filesDir, "vault").apply { mkdirs() }

    private fun dir(cardId: String) = File(root, cardId).apply { mkdirs() }

    fun saveCard(
        cardId: String,
        name: String,
        rarity: String,
        description: String,
        renderPng: ByteArray?,
        referenceJpg: ByteArray?
    ) {
        val d = dir(cardId)

        renderPng?.let { File(d, "render.png").writeBytes(it) }
        referenceJpg?.let { File(d, "reference.jpg").writeBytes(it) }

        val meta = JSONObject().apply {
            put("id", cardId)
            put("name", name)
            put("rarity", rarity)
            put("description", description)
            put("unlockedAt", System.currentTimeMillis())
        }
        File(d, "meta.json").writeText(meta.toString())
    }
    /** Saves the animation JSON bundle for offline playback. */
    fun saveAnimation(cardId: String, animName: String, bundleJson: String) {
        val d = File(dir(cardId), "animations/$animName").apply { mkdirs() }
        File(d, "bundle.json").writeText(bundleJson)
    }

    /** Reads back the animation bundle, or null if not cached. */
    fun getAnimation(cardId: String, animName: String): String? {
        val f = File(dir(cardId), "animations/$animName/bundle.json")
        return if (f.exists()) f.readText() else null
    }

    /** All animation names cached for a card. */
    fun listAnimations(cardId: String): List<String> {
        val root = File(dir(cardId), "animations")
        return root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }
    fun hasCard(cardId: String): Boolean =
        File(dir(cardId), "meta.json").exists()

    fun listCards(): JSONArray {
        val arr = JSONArray()
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return arr

        val entries = dirs.mapNotNull { d ->
            val metaFile = File(d, "meta.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                JSONObject(metaFile.readText()) to d
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { (meta, _) -> meta.optLong("unlockedAt", 0L) }

        for ((meta, d) in entries) {
            val renderFile = File(d, "render.png")
            if (renderFile.exists()) {
                val b64 = Base64.encodeToString(renderFile.readBytes(), Base64.NO_WRAP)
                meta.put("renderDataUri", "data:image/png;base64,$b64")
            } else {
                meta.put("renderDataUri", JSONObject.NULL)
            }
            arr.put(meta)
        }
        return arr
    }

    fun deleteCard(cardId: String): Boolean = dir(cardId).deleteRecursively()

    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun diskUsageBytes(): Long {
        var total = 0L
        root.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}