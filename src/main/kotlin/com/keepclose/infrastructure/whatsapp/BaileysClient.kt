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
        // Remove + and any non-digit characters
        return number.replace("+", "").replace(Regex("[^0-9]"), "")
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
