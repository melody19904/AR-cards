package com.example.cardengine.experience

/**
 * What a recognized target should show. Recognition (QR/image) never knows
 * about this — it only produces a target id. This is the ONLY place that
 * decides what gets rendered.
 */
sealed class Experience {
    abstract val targetId: String

    data class Web(override val targetId: String, val url: String) : Experience()
    data class Image(override val targetId: String, val url: String) : Experience()
    data class Video(override val targetId: String, val url: String) : Experience()
    data class Unknown(override val targetId: String) : Experience()
}
