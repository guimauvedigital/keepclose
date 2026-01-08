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

    suspend fun generateAudio(
        text: String,
        voiceId: String? = null,
        modelId: String? = null,
        speed: Double? = null,
        stability: Double? = null,
        similarityBoost: Double? = null,
        style: Double? = null,
        speakerBoost: Boolean? = null
    ): Result<ByteArray> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("ElevenLabs API key not configured"))
        }

        return try {
            val voice = voiceId ?: defaultVoiceId
            if (voice.isBlank()) {
                return Result.failure(IllegalStateException("No voice ID provided or configured"))
            }

            // Default values according to user specs
            val model = modelId ?: "eleven_multilingual_v2"
            val finalStability = stability ?: 0.8
            val finalSimilarityBoost = similarityBoost ?: 1.0
            val finalStyle = style ?: 0.6
            val finalSpeakerBoost = speakerBoost ?: true

            logger.debug("Generating TTS audio with ElevenLabs, voiceId=$voice, model=$model")

            // Build URL with speed parameter if provided
            val url = if (speed != null && speed != 1.0) {
                "$baseUrl/text-to-speech/$voice?enable_logging=false&optimize_streaming_latency=0&output_format=mp3_44100_128"
            } else {
                "$baseUrl/text-to-speech/$voice"
            }

            val response: HttpResponse = httpClient.post(url) {
                header("xi-api-key", apiKey)
                contentType(ContentType.Application.Json)

                // Add speed parameter in query if needed
                if (speed != null && speed != 1.0) {
                    parameter("model_id", model)
                }

                setBody(
                    TtsRequest(
                        text = text,
                        model_id = model,
                        voice_settings = VoiceSettings(
                            stability = finalStability,
                            similarity_boost = finalSimilarityBoost,
                            style = finalStyle,
                            use_speaker_boost = finalSpeakerBoost
                        )
                    )
                )
            }

            // Apply speed adjustment if needed (post-processing with ffmpeg)
            val mp3Bytes = if (response.status.isSuccess()) {
                val bytes = response.readBytes()
                logger.info("TTS audio generated successfully, size=${bytes.size} bytes")

                if (speed != null && speed != 1.0) {
                    applySpeedAdjustment(bytes, speed)
                } else {
                    bytes
                }
            } else {
                val error = "ElevenLabs API error: ${response.status} - ${response.bodyAsText()}"
                logger.error(error)
                return Result.failure(Exception(error))
            }

            // Convert MP3 to OGG Opus
            val oggBytes = convertMp3ToOgg(mp3Bytes)
            Result.success(oggBytes)
        } catch (e: Exception) {
            logger.error("Failed to generate TTS audio", e)
            Result.failure(e)
        }
    }

    private fun applySpeedAdjustment(mp3Bytes: ByteArray, speed: Double): ByteArray {
        val tempInput = File.createTempFile("tts_input_", ".mp3")
        val tempOutput = File.createTempFile("tts_speed_", ".mp3")

        try {
            tempInput.writeBytes(mp3Bytes)

            // Use ffmpeg atempo filter to adjust speed
            // atempo range is 0.5 to 2.0, so for 1.12x we use atempo=1.12
            val process = ProcessBuilder(
                "ffmpeg",
                "-i", tempInput.absolutePath,
                "-filter:a", "atempo=$speed",
                "-y",
                tempOutput.absolutePath
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()

            if (exitCode != 0) {
                val error = process.inputStream.bufferedReader().readText()
                logger.error("FFmpeg speed adjustment failed: $error")
                throw Exception("Speed adjustment failed with exit code $exitCode")
            }

            logger.debug("Speed adjusted to ${speed}x successfully")
            return tempOutput.readBytes()
        } finally {
            tempInput.delete()
            tempOutput.delete()
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
    val voice_settings: VoiceSettings,
    val optimize_streaming_latency: Int? = null,
    val output_format: String? = null
)

@Serializable
private data class VoiceSettings(
    val stability: Double,
    val similarity_boost: Double,
    val style: Double? = null,
    val use_speaker_boost: Boolean? = null
)
