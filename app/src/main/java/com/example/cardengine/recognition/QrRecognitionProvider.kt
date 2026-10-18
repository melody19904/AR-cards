package com.example.cardengine.recognition

import com.example.cardengine.core.RecognitionResult
import com.example.cardengine.core.RecognitionType
import com.example.cardengine.core.TargetGeometry
import kotlin.math.hypot

data class QrFrameInput(
    val rawValue: String,
    val corners: FloatArray? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

class QrRecognitionProvider : RecognitionProvider<String> {

    override fun process(input: String): List<RecognitionResult> {
        return processFrame(QrFrameInput(rawValue = input))
    }

    fun processFrame(input: QrFrameInput): List<RecognitionResult> {
        val targetId = input.rawValue.trim()
        if (targetId.isBlank()) return emptyList()

        val c = input.corners
        val geom = if (c != null && c.size == 8 && input.frameWidth > 0 && input.frameHeight > 0) {
            val w = hypot((c[2] - c[0]).toDouble(), (c[3] - c[1]).toDouble()).toFloat()
            val h = hypot((c[6] - c[0]).toDouble(), (c[7] - c[1]).toDouble()).toFloat()
            TargetGeometry(
                width = w,
                height = h,
                corners = c,
                frameWidth = input.frameWidth,
                frameHeight = input.frameHeight
            )
        } else null

        return listOf(
            RecognitionResult(
                targetId = targetId,
                type = RecognitionType.QR,
                confidence = 1.0f,
                geometry = geom
            )
        )
    }
}
