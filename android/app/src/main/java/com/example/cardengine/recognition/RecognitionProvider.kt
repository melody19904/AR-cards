package com.example.cardengine.recognition

import com.example.cardengine.core.RecognitionResult

interface RecognitionProvider<T> {
    fun process(input: T): List<RecognitionResult>
}
