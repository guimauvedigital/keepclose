package com.keepclose.infrastructure.whatsapp

import com.keepclose.config.AppConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class BaileysClient(
    private val httpClient: HttpClient,
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(BaileysClient::class.java)
    private val baseUrl = config.baileysUrl

    suspend fun sendText(to: String, text: String): Result<String> {
        return try {
            logger.debug("Sending text to Baileys sidecar: to=$to")

            val response: HttpResponse = httpClient.post("$baseUrl/send/text") {
                contentType(ContentType.Application.Json)
                setBody(SendTextRequest(to = formatPhoneNumber(to), text = text))
            }

            if (response.status.isSuccess()) {
                val result = response.body<BaileysResponse>()
                logger.info("Text message sent successfully: messageId=${result.messageId}")
                Result.success(result.messageId ?: "unknown")
            } else {
                val error = "Baileys error: ${response.status}"
                logger.error(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            logger.error("Failed to send text message", e)
            Result.failure(e)
        }
    }

    suspend fun sendAudio(to: String, audioPath: String): Result<String> {
        return try {
            logger.debug("Sending audio to Baileys sidecar: to=$to, audioPath=$audioPath")

            val response: HttpResponse = httpClient.post("$baseUrl/send/audio") {
                contentType(ContentType.Application.Json)
                setBody(SendAudioRequest(to = formatPhoneNumber(to), audioPath = audioPath))
            }

            if (response.status.isSuccess()) {
                val result = response.body<BaileysResponse>()
                logger.info("Audio message sent successfully: messageId=${result.messageId}")
                Result.success(result.messageId ?: "unknown")
            } else {
                val error = "Baileys error: ${response.status}"
                logger.error(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            logger.error("Failed to send audio message", e)
            Result.failure(e)
        }
    }

    suspend fun getStatus(): Result<String> {
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/status")

            if (response.status.isSuccess()) {
                val result = response.body<StatusResponse>()
                Result.success(result.status)
            } else {
                Result.failure(Exception("Failed to get status: ${response.status}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to get Baileys status", e)
            Result.failure(e)
        }
    }

    suspend fun getQrCode(): Result<String> {
        return try {
            val response: HttpResponse = httpClient.get("$baseUrl/qr")

            if (response.status.isSuccess()) {
                val result = response.body<QrResponse>()
                Result.success(result.qr ?: "No QR available")
            } else {
                Result.failure(Exception("Failed to get QR: ${response.status}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to get QR code", e)
            Result.failure(e)
        }
    }

    private fun formatPhoneNumber(number: String): String {
        // Remove all non-digit characters
        val digits = number.replace(Regex("[^0-9]"), "")

        // Remove leading 0 after European country codes (e.g., 330767... -> 33767...)
        // This handles cases like +33 0 7 67 00 94 69 where the 0 after country code should be removed
        // Covers country codes 30-39 (France 33, Belgium 32, Spain 34, Italy 39, etc.)
        // and 40-49 (UK 44, Germany 49, Switzerland 41, etc.)
        return digits.replace(Regex("^(3[0-9]|4[0-9])0(\\d+)$")) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}"
        }
    }
}

@Serializable
private data class SendTextRequest(
    val to: String,
    val text: String
)

@Serializable
private data class SendAudioRequest(
    val to: String,
    val audioPath: String
)

@Serializable
private data class BaileysResponse(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)

@Serializable
private data class StatusResponse(
    val status: String
)

@Serializable
private data class QrResponse(
    val qr: String? = null
)
