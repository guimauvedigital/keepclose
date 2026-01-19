package com.keepclose.config

data class AppConfig(
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080,
    val baileysUrl: String = System.getenv("BAILEYS_URL") ?: "http://localhost:3001",
    val baileysAuthPath: String = System.getenv("BAILEYS_AUTH_PATH") ?: "/data/auth_info",
    val elevenLabsApiKey: String = System.getenv("ELEVENLABS_API_KEY") ?: "",
    val elevenLabsDefaultVoiceId: String = System.getenv("ELEVENLABS_DEFAULT_VOICE_ID") ?: "",
    val audioStoragePath: String = System.getenv("AUDIO_STORAGE_PATH") ?: "/data/audio",
    // iCloud CardDAV configuration
    val icloudEmail: String = System.getenv("ICLOUD_EMAIL") ?: "",
    val icloudAppPassword: String = System.getenv("ICLOUD_APP_PASSWORD") ?: ""
) {
    fun isICloudConfigured(): Boolean = icloudEmail.isNotBlank() && icloudAppPassword.isNotBlank()
}
