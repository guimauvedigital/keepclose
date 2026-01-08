package com.keepclose.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SendMessageRequest(
    val to: String,
    val type: MessageType,
    val text: String? = null,
    val audio: AudioPayload? = null
)

@Serializable
data class AudioPayload(
    val source: AudioSource,
    val preRecordedId: String? = null,
    val tts: TtsPayload? = null
)

@Serializable
data class TtsPayload(
    val text: String,
    val voiceId: String? = null,
    val modelId: String? = null,
    val speed: Double? = null,
    val stability: Double? = null,
    val similarityBoost: Double? = null,
    val style: Double? = null,
    val speakerBoost: Boolean? = null
)

@Serializable
enum class MessageType {
    TEXT, AUDIO
}

@Serializable
enum class AudioSource {
    PRE_RECORDED, TTS
}
