package com.keepclose.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class LabeledUrl(
    val url: String,
    val label: String? = null  // e.g., "Stripe", "Dashboard", "LinkedIn"
)

@Serializable
data class CreateContactRequest(
    val userId: String,
    val phoneNumber: String,
    val displayName: String? = null,
    // Additional vCard fields
    val email: String? = null,
    val organization: String? = null,
    val title: String? = null,
    val urls: List<LabeledUrl>? = null,  // Multiple URLs with optional labels
    val notes: String? = null
)

@Serializable
data class CreateContactResponse(
    val success: Boolean,
    val contactId: String? = null,
    val updated: Boolean = false,  // true if contact was updated instead of created
    val error: String? = null
)

@Serializable
data class DeleteContactRequest(
    val contactId: String
)
