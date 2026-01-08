package com.keepclose.config

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

suspend fun ApplicationCall.validateApiKey(): Boolean {
    val apiKey = System.getenv("API_KEY")

    // Si pas d'API key configurée, on log un warning mais on accepte (dev mode)
    if (apiKey.isNullOrBlank()) {
        application.environment.log.warn("⚠️ API_KEY not configured! API is unprotected!")
        return true
    }

    val providedKey = request.headers["X-API-Key"]

    if (providedKey.isNullOrBlank()) {
        respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Missing X-API-Key header")
        )
        return false
    }

    if (providedKey != apiKey) {
        respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "Invalid API key")
        )
        return false
    }

    return true
}
