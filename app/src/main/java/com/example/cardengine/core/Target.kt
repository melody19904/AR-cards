package com.example.cardengine.core

interface RecognitionDefinition {
    val type: RecognitionType
}

interface ExperienceDefinition {
    val type: ExperienceType
}

data class Target(
    val id: String,
    val recognition: RecognitionDefinition,
    val experience: ExperienceDefinition
)
