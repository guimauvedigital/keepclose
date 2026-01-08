package com.keepclose.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class SendMessageResponse(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)
