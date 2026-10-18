package com.example.cardengine.core

enum class RecognitionType { QR, IMAGE, OBJECT }

data class TargetGeometry(
    val width: Float = 0f,
    val height: Float = 0f,
    val corners: FloatArray? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

data class RecognitionResult(
    val targetId: String,
    val type: RecognitionType,
    val confidence: Float,
    val geometry: TargetGeometry?
)
