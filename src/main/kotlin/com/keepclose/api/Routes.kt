package com.keepclose.api

import com.keepclose.api.dto.SendMessageRequest
import com.keepclose.api.dto.SendMessageResponse
import com.keepclose.config.validateApiKey
import com.keepclose.domain.MessageService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    routing {
        healthRoutes()
        messageRoutes()
    }
}

fun Route.healthRoutes() {
    val messageService by inject<MessageService>()

    get("/health") {
        try {
            val baileysStatus = messageService.checkBaileysStatus()
            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "status" to "ok",
                    "baileys" to baileysStatus
                )
            )
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf(
                    "status" to "error",
                    "error" to (e.message ?: "Unknown error")
                )
            )
        }
    }
}

fun Route.messageRoutes() {
    val messageService by inject<MessageService>()

    route("/api/v1") {
        post("/messages") {
            // Validate API key
            if (!call.validateApiKey()) {
                return@post
            }

            try {
                val request = call.receive<SendMessageRequest>()

                // Validation
                if (request.type == com.keepclose.api.dto.MessageType.TEXT && request.text.isNullOrBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        SendMessageResponse(
                            success = false,
                            error = "Text is required for TEXT messages"
                        )
                    )
                }

                if (request.type == com.keepclose.api.dto.MessageType.AUDIO && request.audio == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        SendMessageResponse(
                            success = false,
                            error = "Audio payload is required for AUDIO messages"
                        )
                    )
                }

                val result = messageService.sendMessage(request)

                result.fold(
                    onSuccess = { messageId ->
                        call.respond(
                            HttpStatusCode.OK,
                            SendMessageResponse(
                                success = true,
                                messageId = messageId
                            )
                        )
                    },
                    onFailure = { error ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            SendMessageResponse(
                                success = false,
                                error = error.message
                            )
                        )
                    }
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    SendMessageResponse(
                        success = false,
                        error = e.message ?: "Invalid request"
                    )
                )
            }
        }
    }
}
