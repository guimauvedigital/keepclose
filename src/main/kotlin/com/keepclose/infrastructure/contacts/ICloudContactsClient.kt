package com.keepclose.infrastructure.contacts

import com.keepclose.config.AppConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.slf4j.LoggerFactory
import java.util.*

data class LabeledUrlData(
    val url: String,
    val label: String? = null
)

data class ContactData(
    val userId: String,
    val phoneNumber: String,
    val displayName: String? = null,
    val email: String? = null,
    val organization: String? = null,
    val title: String? = null,
    val urls: List<LabeledUrlData>? = null,
    val notes: String? = null
)

data class ContactResult(
    val contactId: String,
    val updated: Boolean
)

class ICloudContactsClient(
    private val httpClient: HttpClient,
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(ICloudContactsClient::class.java)

    private val baseUrl = "https://contacts.icloud.com"
    private var addressBookUrl: String? = null

    // Cache of phone -> contactUid for upsert functionality
    private val phoneToUidCache = mutableMapOf<String, String>()

    suspend fun createOrUpdateContact(contact: ContactData): Result<ContactResult> {
        if (!config.isICloudConfigured()) {
            return Result.failure(Exception("iCloud credentials not configured. Set ICLOUD_EMAIL and ICLOUD_APP_PASSWORD."))
        }

        return try {
            if (addressBookUrl == null) {
                discoverAddressBook().getOrThrow()
            }

            val formattedPhone = formatPhoneNumber(contact.phoneNumber)
            val normalizedPhone = formattedPhone.replace(Regex("[^0-9]"), "")

            // Check if contact exists (by phone number)
            val existingUid = phoneToUidCache[normalizedPhone] ?: findContactByPhone(normalizedPhone)

            val contactUid = existingUid ?: UUID.randomUUID().toString()
            val isUpdate = existingUid != null
            val vcardFileName = "$contactUid.vcf"
            val contactName = contact.displayName ?: contact.userId

            val vcard = buildVCard(
                uid = contactUid,
                fullName = contactName,
                phoneNumber = formattedPhone,
                email = contact.email,
                organization = contact.organization,
                title = contact.title,
                urls = contact.urls,
                note = buildNote(contact)
            )

            logger.debug("${if (isUpdate) "Updating" else "Creating"} contact: $contactName ($formattedPhone)")

            val cleanUrl = addressBookUrl!!.trimEnd('/').replace(":443", "")
            val response: HttpResponse = httpClient.put("$cleanUrl/$vcardFileName") {
                header(HttpHeaders.Authorization, buildBasicAuth())
                contentType(ContentType("text", "vcard"))
                setBody(vcard)
            }

            when (response.status) {
                HttpStatusCode.Created, HttpStatusCode.NoContent -> {
                    // Cache the mapping for future upserts
                    phoneToUidCache[normalizedPhone] = contactUid
                    logger.info("Contact ${if (isUpdate) "updated" else "created"} successfully: $contactName")
                    Result.success(ContactResult(contactUid, isUpdate))
                }
                HttpStatusCode.Unauthorized -> {
                    Result.failure(Exception("iCloud authentication failed. Check your credentials."))
                }
                else -> {
                    val body = response.bodyAsText()
                    logger.error("Failed to ${if (isUpdate) "update" else "create"} contact: ${response.status} - $body")
                    Result.failure(Exception("Failed to ${if (isUpdate) "update" else "create"} contact: ${response.status}"))
                }
            }
        } catch (e: Exception) {
            logger.error("Error creating/updating contact", e)
            Result.failure(e)
        }
    }

    // Keep old method for backward compatibility
    suspend fun createContact(userId: String, phoneNumber: String, displayName: String? = null): Result<String> {
        val result = createOrUpdateContact(ContactData(userId, phoneNumber, displayName))
        return result.map { it.contactId }
    }

    suspend fun deleteContact(contactUid: String): Result<Unit> {
        if (!config.isICloudConfigured()) {
            return Result.failure(Exception("iCloud credentials not configured"))
        }

        return try {
            if (addressBookUrl == null) {
                discoverAddressBook().getOrThrow()
            }

            val cleanUrl = addressBookUrl!!.trimEnd('/').replace(":443", "")
            val response: HttpResponse = httpClient.delete("$cleanUrl/$contactUid.vcf") {
                header(HttpHeaders.Authorization, buildBasicAuth())
            }

            if (response.status.isSuccess()) {
                // Remove from cache
                phoneToUidCache.entries.removeIf { it.value == contactUid }
                logger.info("Contact deleted: $contactUid")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete contact: ${response.status}"))
            }
        } catch (e: Exception) {
            logger.error("Error deleting contact", e)
            Result.failure(e)
        }
    }

    private suspend fun findContactByPhone(normalizedPhone: String): String? {
        try {
            val cleanUrl = addressBookUrl!!.trimEnd('/').replace(":443", "")

            // Use REPORT with addressbook-query to find contacts
            val response: HttpResponse = httpClient.request(cleanUrl) {
                method = HttpMethod("REPORT")
                header(HttpHeaders.Authorization, buildBasicAuth())
                header("Depth", "1")
                contentType(ContentType.Application.Xml)
                setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                        <d:prop>
                            <d:getetag/>
                            <card:address-data/>
                        </d:prop>
                    </card:addressbook-query>
                """.trimIndent())
            }

            if (!response.status.isSuccess()) {
                logger.debug("Could not search for existing contacts: ${response.status}")
                return null
            }

            val body = response.bodyAsText()

            // Parse response to find contact with matching phone
            val vcardPattern = Regex("BEGIN:VCARD[\\s\\S]*?END:VCARD")
            val hrefPattern = Regex("<[^>]*href[^>]*>([^<]+\\.vcf)</[^>]*href[^>]*>", RegexOption.IGNORE_CASE)

            val vcards = vcardPattern.findAll(body)
            val hrefs = hrefPattern.findAll(body).toList()

            vcards.forEachIndexed { index, match ->
                val vcard = match.value
                // Check if this vcard contains the phone number
                if (vcard.contains(normalizedPhone)) {
                    // Extract UID from vcard
                    val uidMatch = Regex("UID:([^\\r\\n]+)").find(vcard)
                    val uid = uidMatch?.groupValues?.get(1)?.trim()
                    if (uid != null) {
                        logger.debug("Found existing contact with phone $normalizedPhone: $uid")
                        phoneToUidCache[normalizedPhone] = uid
                        return uid
                    }
                }
            }

            return null
        } catch (e: Exception) {
            logger.debug("Error searching for contact: ${e.message}")
            return null
        }
    }

    private suspend fun discoverAddressBook(): Result<String> {
        logger.debug("Discovering iCloud CardDAV addressbook...")

        try {
            // Step 1: Get principal URL
            val principalResponse: HttpResponse = httpClient.request(baseUrl) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, buildBasicAuth())
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:propfind xmlns:d="DAV:">
                        <d:prop>
                            <d:current-user-principal />
                        </d:prop>
                    </d:propfind>
                """.trimIndent())
            }

            if (!principalResponse.status.isSuccess()) {
                return Result.failure(Exception("Failed to discover principal: ${principalResponse.status}"))
            }

            val principalBody = principalResponse.bodyAsText()
            val principalUrl = extractHref(principalBody, "current-user-principal")
                ?: return Result.failure(Exception("Could not find principal URL"))

            logger.debug("Found principal URL: $principalUrl")

            // Step 2: Get addressbook home
            val fullPrincipalUrl = if (principalUrl.startsWith("http")) principalUrl else "$baseUrl$principalUrl"

            val homeResponse: HttpResponse = httpClient.request(fullPrincipalUrl) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, buildBasicAuth())
                header("Depth", "0")
                contentType(ContentType.Application.Xml)
                setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                        <d:prop>
                            <card:addressbook-home-set />
                        </d:prop>
                    </d:propfind>
                """.trimIndent())
            }

            if (!homeResponse.status.isSuccess()) {
                return Result.failure(Exception("Failed to discover addressbook home: ${homeResponse.status}"))
            }

            val homeBody = homeResponse.bodyAsText()
            val homeUrl = extractHref(homeBody, "addressbook-home-set")
                ?: return Result.failure(Exception("Could not find addressbook home URL"))

            logger.debug("Found addressbook home: $homeUrl")

            // Step 3: List addressbooks and find the default one
            val fullHomeUrl = if (homeUrl.startsWith("http")) homeUrl else "$baseUrl$homeUrl"

            val listResponse: HttpResponse = httpClient.request(fullHomeUrl) {
                method = HttpMethod("PROPFIND")
                header(HttpHeaders.Authorization, buildBasicAuth())
                header("Depth", "1")
                contentType(ContentType.Application.Xml)
                setBody("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <d:propfind xmlns:d="DAV:">
                        <d:prop>
                            <d:resourcetype />
                            <d:displayname />
                        </d:prop>
                    </d:propfind>
                """.trimIndent())
            }

            if (!listResponse.status.isSuccess()) {
                return Result.failure(Exception("Failed to list addressbooks: ${listResponse.status}"))
            }

            val listBody = listResponse.bodyAsText()

            val addressbookHref = extractAddressbookHref(listBody)
                ?: return Result.failure(Exception("Could not find addressbook"))

            addressBookUrl = if (addressbookHref.startsWith("http")) {
                addressbookHref
            } else {
                val serverBase = Regex("(https?://[^/]+)").find(fullHomeUrl)?.groupValues?.get(1) ?: baseUrl
                "$serverBase$addressbookHref"
            }

            logger.info("Discovered addressbook URL: $addressBookUrl")
            return Result.success(addressBookUrl!!)

        } catch (e: Exception) {
            logger.error("Failed to discover addressbook", e)
            return Result.failure(e)
        }
    }

    private fun buildVCard(
        uid: String,
        fullName: String,
        phoneNumber: String,
        email: String? = null,
        organization: String? = null,
        title: String? = null,
        urls: List<LabeledUrlData>? = null,
        note: String? = null
    ): String {
        val nameParts = fullName.split(" ", limit = 2)
        val lastName = if (nameParts.size > 1) nameParts[1] else ""
        val firstName = nameParts[0]

        val cleanPhone = phoneNumber.replace(Regex("[^0-9+]"), "")
        val waid = cleanPhone.replace("+", "")

        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("PRODID:-//KeepClose//ContactsAPI//EN")
            appendLine("UID:$uid")
            appendLine("FN:$fullName")
            appendLine("N:$lastName;$firstName;;;")
            appendLine("TEL;type=CELL;type=VOICE;waid=$waid:$cleanPhone")

            if (!email.isNullOrBlank()) {
                appendLine("EMAIL;type=INTERNET;type=HOME:$email")
            }
            if (!organization.isNullOrBlank()) {
                appendLine("ORG:$organization")
            }
            if (!title.isNullOrBlank()) {
                appendLine("TITLE:$title")
            }

            // Add labeled URLs using Apple's grouped format
            urls?.forEachIndexed { index, labeledUrl ->
                val itemId = "item${index + 1}"
                appendLine("$itemId.URL:${labeledUrl.url}")
                if (!labeledUrl.label.isNullOrBlank()) {
                    appendLine("$itemId.X-ABLabel:${labeledUrl.label}")
                }
            }

            if (!note.isNullOrBlank()) {
                // Escape special characters in note
                val escapedNote = note
                    .replace("\\", "\\\\")
                    .replace("\n", "\\n")
                    .replace(",", "\\,")
                    .replace(";", "\\;")
                appendLine("NOTE:$escapedNote")
            }

            appendLine("END:VCARD")
        }
    }

    private fun buildNote(contact: ContactData): String {
        val parts = mutableListOf<String>()
        parts.add("UserID: ${contact.userId}")
        if (!contact.notes.isNullOrBlank()) {
            parts.add(contact.notes)
        }
        return parts.joinToString("\n")
    }

    private fun buildBasicAuth(): String {
        val credentials = "${config.icloudEmail}:${config.icloudAppPassword}"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray())
        return "Basic $encoded"
    }

    private fun formatPhoneNumber(number: String): String {
        val clean = number.replace(Regex("[^0-9]"), "")
        return if (number.startsWith("+")) "+$clean" else "+$clean"
    }

    private fun extractHref(xml: String, tagName: String): String? {
        val tagPattern = Regex("<[^>]*$tagName[^>]*>([\\s\\S]*?)</[^>]*$tagName[^>]*>", RegexOption.IGNORE_CASE)
        val tagMatch = tagPattern.find(xml)

        if (tagMatch != null) {
            val hrefPattern = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href[^>]*>", RegexOption.IGNORE_CASE)
            val hrefMatch = hrefPattern.find(tagMatch.groupValues[1])
            return hrefMatch?.groupValues?.get(1)?.trim()
        }

        return null
    }

    private fun extractAddressbookHref(xml: String): String? {
        val responsePattern = Regex("<[^>]*response[^>]*>([\\s\\S]*?)</[^>]*response[^>]*>", RegexOption.IGNORE_CASE)

        for (match in responsePattern.findAll(xml)) {
            val response = match.groupValues[1]

            if (response.contains("addressbook", ignoreCase = true) &&
                response.contains("resourcetype", ignoreCase = true)) {

                val hrefPattern = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href[^>]*>", RegexOption.IGNORE_CASE)
                val hrefMatch = hrefPattern.find(response)
                val href = hrefMatch?.groupValues?.get(1)?.trim()

                if (href != null && !href.endsWith("/card/") && href.contains("/card")) {
                    return href
                }
            }
        }

        val cardPattern = Regex("<[^>]*href[^>]*>([^<]*/card/[^<]*)</[^>]*href[^>]*>", RegexOption.IGNORE_CASE)
        val cardMatch = cardPattern.find(xml)
        return cardMatch?.groupValues?.get(1)?.trim()
    }
}
