package com.example.cardengine.recognition

import com.example.cardengine.core.RecognitionResult
import com.example.cardengine.core.RecognitionType

class QrRecognitionProvider : RecognitionProvider<String> {
    override fun process(input: String): List<RecognitionResult> {
        if (input.isBlank()) return emptyList()
        
        // For QR codes, the decoded string is literally the Target ID
        return listOf(
            RecognitionResult(
                targetId = input.trim(),
                type = RecognitionType.QR,
                confidence = 1.0f, // QRs are definitive matches
                geometry = null
            )
        )
    }
}
