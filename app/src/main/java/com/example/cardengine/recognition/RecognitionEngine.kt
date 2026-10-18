package com.example.cardengine.recognition

import com.example.cardengine.core.RecognitionResult

class RecognitionEngine(
    private val qrProvider: QrRecognitionProvider = QrRecognitionProvider()
) {
    fun processQr(
        qrData: String,
        corners: FloatArray? = null,
        frameWidth: Int = 0,
        frameHeight: Int = 0
    ): RecognitionResult? {
        return qrProvider.processFrame(
            QrFrameInput(
                rawValue = qrData,
                corners = corners,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        ).firstOrNull()
    }
}
