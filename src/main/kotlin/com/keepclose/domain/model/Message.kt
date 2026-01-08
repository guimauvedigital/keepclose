package com.keepclose.domain.model

import com.keepclose.api.dto.MessageType
import com.keepclose.api.dto.AudioSource

data class Message(
    val to: String,
    val type: MessageType,
    val content: MessageContent
)

sealed class MessageContent {
    data class Text(val text: String) : MessageContent()
    data class Audio(val audioPath: String) : MessageContent()
}

data class AudioRequest(
    val source: AudioSource,
    val preRecordedId: String? = null,
    val ttsText: String? = null,
    val voiceId: String? = null
)
