package com.keepclose.infrastructure.storage

import com.keepclose.config.AppConfig
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class AudioStorage(
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(AudioStorage::class.java)
    private val audioBasePath = config.audioStoragePath
    private val tempAudioPath = "$audioBasePath/temp"

    init {
        // Ensure directories exist
        File(audioBasePath).mkdirs()
        File(tempAudioPath).mkdirs()
        logger.info("AudioStorage initialized: basePath=$audioBasePath")
    }

    /**
     * Get the path to a pre-recorded audio file by its ID.
     * @param audioId The ID of the pre-recorded audio (e.g., "trial_reminder_1")
     * @return The absolute path to the audio file, or null if not found
     */
    fun getAudioPath(audioId: String): String? {
        val audioFile = File("$audioBasePath/$audioId.ogg")

        return if (audioFile.exists()) {
            logger.debug("Found pre-recorded audio: ${audioFile.absolutePath}")
            audioFile.absolutePath
        } else {
            logger.warn("Pre-recorded audio not found: $audioId")
            null
        }
    }

    /**
     * Save a temporary audio file (e.g., generated TTS).
     * @param fileName The name of the file to save
     * @param audioBytes The audio data
     * @return The absolute path to the saved file
     */
    fun saveTempAudio(fileName: String, audioBytes: ByteArray): Result<String> {
        return try {
            val filePath = "$tempAudioPath/$fileName"
            val file = File(filePath)

            file.writeBytes(audioBytes)

            logger.debug("Saved temporary audio: ${file.absolutePath}, size=${audioBytes.size} bytes")
            Result.success(file.absolutePath)
        } catch (e: Exception) {
            logger.error("Failed to save temporary audio: $fileName", e)
            Result.failure(e)
        }
    }

    /**
     * List all available pre-recorded audio files.
     * @return List of audio IDs (without .ogg extension)
     */
    fun listPreRecordedAudios(): List<String> {
        val audioDir = File(audioBasePath)

        if (!audioDir.exists()) {
            return emptyList()
        }

        return audioDir.listFiles()
            ?.filter { it.isFile && it.extension == "ogg" }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    /**
     * Clean up old temporary audio files.
     * @param olderThanMillis Delete files older than this (default: 1 hour)
     */
    fun cleanupTempAudios(olderThanMillis: Long = 3600000) {
        val tempDir = File(tempAudioPath)

        if (!tempDir.exists()) {
            return
        }

        val now = System.currentTimeMillis()
        var deletedCount = 0

        tempDir.listFiles()?.forEach { file ->
            if (file.isFile && (now - file.lastModified()) > olderThanMillis) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }

        if (deletedCount > 0) {
            logger.info("Cleaned up $deletedCount temporary audio files")
        }
    }
}
