package com.keepclose.domain

import com.keepclose.api.dto.AudioSource
import com.keepclose.api.dto.MessageType
import com.keepclose.api.dto.SendMessageRequest
import com.keepclose.infrastructure.storage.AudioStorage
import com.keepclose.infrastructure.tts.ElevenLabsClient
import com.keepclose.infrastructure.whatsapp.BaileysClient
import org.slf4j.LoggerFactory
import java.util.*

class MessageService(
    private val baileysClient: BaileysClient,
    private val elevenLabsClient: ElevenLabsClient,
    private val audioStorage: AudioStorage
) {
    private val logger = LoggerFactory.getLogger(MessageService::class.java)

    suspend fun sendMessage(request: SendMessageRequest): Result<String> {
        return try {
            logger.info("Processing message request: type=${request.type}, to=${request.to}")

            when (request.type) {
                MessageType.TEXT -> sendTextMessage(request)
                MessageType.AUDIO -> sendAudioMessage(request)
            }
        } catch (e: Exception) {
            logger.error("Failed to send message", e)
            Result.failure(e)
        }
    }

    private suspend fun sendTextMessage(request: SendMessageRequest): Result<String> {
        val text = request.text ?: return Result.failure(IllegalArgumentException("Text is required"))

        logger.debug("Sending text message to ${request.to}")
        return baileysClient.sendText(request.to, text)
    }

    private suspend fun sendAudioMessage(request: SendMessageRequest): Result<String> {
        val audioPayload = request.audio
            ?: return Result.failure(IllegalArgumentException("Audio payload is required"))

        val audioPath = when (audioPayload.source) {
            AudioSource.PRE_RECORDED -> {
                val preRecordedId = audioPayload.preRecordedId
                    ?: return Result.failure(IllegalArgumentException("preRecordedId is required for PRE_RECORDED audio"))

                logger.debug("Using pre-recorded audio: $preRecordedId")
                audioStorage.getAudioPath(preRecordedId)
                    ?: return Result.failure(IllegalArgumentException("Pre-recorded audio not found: $preRecordedId"))
            }

            AudioSource.TTS -> {
                val ttsPayload = audioPayload.tts
                    ?: return Result.failure(IllegalArgumentException("TTS payload is required for TTS audio"))

                logger.debug("Generating TTS audio for text: ${ttsPayload.text.take(50)}...")

                // Generate audio using ElevenLabs
                val audioBytes = elevenLabsClient.generateAudio(
                    text = ttsPayload.text,
                    voiceId = ttsPayload.voiceId
                ).getOrElse { error ->
                    logger.error("Failed to generate TTS audio", error)
                    return Result.failure(error)
                }

                // Save to temporary file
                val tempFileName = "tts_${UUID.randomUUID()}.ogg"
                audioStorage.saveTempAudio(tempFileName, audioBytes)
                    .getOrElse { error ->
                        logger.error("Failed to save TTS audio", error)
                        return Result.failure(error)
                    }
            }
        }

        logger.debug("Sending audio message to ${request.to}, path: $audioPath")
        return baileysClient.sendAudio(request.to, audioPath)
    }

    suspend fun checkBaileysStatus(): String {
        return baileysClient.getStatus().getOrElse { "error" }
    }
}
