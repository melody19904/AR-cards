package com.example.cardengine.recognition

import com.example.cardengine.core.RecognitionResult

class RecognitionEngine(
    private val qrProvider: QrRecognitionProvider = QrRecognitionProvider()
) {
    // We will expand this later to take raw CameraX frames.
    // For now, it accepts the decoded QR string from MainActivity.
    fun processQr(qrData: String): RecognitionResult? {
        return qrProvider.process(qrData).firstOrNull()
    }
}
