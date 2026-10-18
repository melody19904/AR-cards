package com.example.cardengine.core

sealed interface Experience {
    val type: ExperienceType
    val source: String
}

data class WebExperience(
    override val source: String
) : Experience {
    override val type = ExperienceType.WEB
}

data class ImageExperience(
    override val source: String
) : Experience {
    override val type = ExperienceType.IMAGE
}

data class VideoExperience(
    override val source: String
) : Experience {
    override val type = ExperienceType.VIDEO
}

data class Model3DExperience(
    override val source: String
) : Experience {
    override val type = ExperienceType.MODEL_3D
}

data class AudioExperience(
    override val source: String
) : Experience {
    override val type = ExperienceType.AUDIO
}

data class DocumentExperience(
    override val source: String
) : Experience {
    override val type = ExperienceType.DOCUMENT
}

data class UnknownExperience(
    override val source: String = ""
) : Experience {
    override val type = ExperienceType.UNKNOWN
}
