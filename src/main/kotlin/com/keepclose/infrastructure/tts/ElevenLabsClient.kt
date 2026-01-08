package com.keepclose.infrastructure.tts

import com.keepclose.config.AppConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

class ElevenLabsClient(
    private val httpClient: HttpClient,
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(ElevenLabsClient::class.java)
    private val baseUrl = "https://api.elevenlabs.io/v1"
    private val apiKey = config.elevenLabsApiKey
    private val defaultVoiceId = config.elevenLabsDefaultVoiceId

    suspend fun generateAudio(text: String, voiceId: String? = null): Result<ByteArray> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("ElevenLabs API key not configured"))
        }

        return try {
            val voice = voiceId ?: defaultVoiceId
            if (voice.isBlank()) {
                return Result.failure(IllegalStateException("No voice ID provided or configured"))
            }

            logger.debug("Generating TTS audio with ElevenLabs, voiceId=$voice")

            val response: HttpResponse = httpClient.post("$baseUrl/text-to-speech/$voice") {
                header("xi-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    TtsRequest(
                        text = text,
                        model_id = "eleven_monolingual_v1",
                        voice_settings = VoiceSettings(
                            stability = 0.5,
                            similarity_boost = 0.75
                        )
                    )
                )
            }

            if (response.status.isSuccess()) {
                val mp3Bytes = response.readBytes()
                logger.info("TTS audio generated successfully, size=${mp3Bytes.size} bytes")

                // Convert MP3 to OGG Opus
                val oggBytes = convertMp3ToOgg(mp3Bytes)
                Result.success(oggBytes)
            } else {
                val error = "ElevenLabs API error: ${response.status} - ${response.bodyAsText()}"
                logger.error(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            logger.error("Failed to generate TTS audio", e)
            Result.failure(e)
        }
    }

    private fun convertMp3ToOgg(mp3Bytes: ByteArray): ByteArray {
        // Create temporary files for conversion
        val tempMp3 = File.createTempFile("tts_", ".mp3")
        val tempOgg = File.createTempFile("tts_", ".ogg")

        try {
            // Write MP3 to temp file
            tempMp3.writeBytes(mp3Bytes)

            // Convert using ffmpeg
            val process = ProcessBuilder(
                "ffmpeg",
                "-i", tempMp3.absolutePath,
                "-c:a", "libopus",
                "-b:a", "128k",
                "-vbr", "on",
                "-compression_level", "10",
                "-frame_duration", "60",
                "-application", "voip",
                "-y",
                tempOgg.absolutePath
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = process.inputStream.bufferedReader().readText()
                logger.error("FFmpeg conversion failed: $error")
                throw Exception("Audio conversion failed with exit code $exitCode")
            }

            logger.debug("Audio converted to OGG Opus successfully")
            return tempOgg.readBytes()
        } finally {
            // Cleanup
            tempMp3.delete()
            tempOgg.delete()
        }
    }
}

@Serializable
private data class TtsRequest(
    val text: String,
    val model_id: String,
    val voice_settings: VoiceSettings
)

@Serializable
private data class VoiceSettings(
    val stability: Double,
    val similarity_boost: Double
)
