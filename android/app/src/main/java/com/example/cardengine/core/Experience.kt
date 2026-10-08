package com.example.cardengine.core

enum class ExperienceType { WEB, VIDEO, IMAGE, MODEL_3D, AUDIO, DOCUMENT }

sealed interface Experience {
    val type: ExperienceType
    val source: String
}

data class WebExperience(override val source: String) : Experience {
    override val type = ExperienceType.WEB
}
