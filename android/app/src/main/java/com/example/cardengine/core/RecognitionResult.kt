package com.example.cardengine.core

enum class RecognitionType { QR, IMAGE, OBJECT }

data class TargetGeometry(
    // Placeholder for real 3D pose/homography data
    val width: Float = 0f,
    val height: Float = 0f
)

data class RecognitionResult(
    val targetId: String,
    val type: RecognitionType,
    val confidence: Float,
    val geometry: TargetGeometry?
)
