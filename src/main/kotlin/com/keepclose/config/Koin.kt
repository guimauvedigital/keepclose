package com.keepclose.config

import com.keepclose.domain.MessageService
import com.keepclose.infrastructure.storage.AudioStorage
import com.keepclose.infrastructure.tts.ElevenLabsClient
import com.keepclose.infrastructure.whatsapp.BaileysClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }
}

val appModule = module {
    // Config
    single { AppConfig() }

    // HTTP Client
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }
        }
    }

    // Infrastructure
    single { BaileysClient(get(), get()) }
    single { ElevenLabsClient(get(), get()) }
    single { AudioStorage(get()) }

    // Domain Services
    single { MessageService(get(), get(), get()) }
}
