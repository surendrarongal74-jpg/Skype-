package com.example.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GmailProfileResponse(
    val emailAddress: String,
    val messagesTotal: Int = 0
)

data class GmailMessagePayload(
    val id: String,
    val threadId: String = "",
    val sender: String = "",
    val recipient: String = "",
    val subject: String = "",
    val snippet: String = "",
    val bodyText: String = "",
    val labels: List<String> = emptyList(),
    val attachmentsSummary: String = "",
    val messageIdHeader: String = ""
)

interface EmailRestGateway {
    suspend fun fetchProfile(accessToken: String): Result<GmailProfileResponse>
    suspend fun listAndFetchMessages(accessToken: String, query: String = "", maxResults: Int = 10): Result<List<GmailMessagePayload>>
    suspend fun sendMessage(
        accessToken: String,
        to: String,
        subject: String,
        body: String,
        threadId: String? = null,
        inReplyTo: String? = null
    ): Result<String>
    suspend fun modifyLabels(accessToken: String, messageId: String, addLabels: List<String>, removeLabels: List<String>): Result<Unit>
    suspend fun trashMessage(accessToken: String, messageId: String): Result<Unit> = Result.success(Unit)
}

class GmailRestClient(
    private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) : EmailRestGateway {

    override suspend fun fetchProfile(accessToken: String): Result<GmailProfileResponse> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "Gmail OAuth access token is required." }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/profile")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Gmail profile HTTP ${resp.code}: ${SecurityAuditService.redactSensitiveFields(raw)}")
                }
                val obj = JSONObject(raw)
                val email = obj.optString("emailAddress").trim()
                require(email.contains("@")) { "Invalid Gmail profile response." }
                GmailProfileResponse(
                    emailAddress = email,
                    messagesTotal = obj.optInt("messagesTotal", 0)
                )
            }
        }
    }

    override suspend fun listAndFetchMessages(
        accessToken: String,
        query: String,
        maxResults: Int
    ): Result<List<GmailMessagePayload>> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "Gmail OAuth access token is required." }
            val qParam = if (query.isNotBlank()) "&q=${URLEncoder.encode(query, "UTF-8")}" else ""
            val listUrl = "${baseUrl.trimEnd('/')}/messages?maxResults=${maxResults.coerceIn(1, 25)}$qParam"
            val listReq = Request.Builder()
                .url(listUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val messageIds = httpClient.newCall(listReq).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Gmail messages list HTTP ${resp.code}: ${SecurityAuditService.redactSensitiveFields(raw)}")
                }
                val obj = JSONObject(raw)
                val arr = obj.optJSONArray("messages") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) add(id)
                    }
                }
            }

            val fetched = mutableListOf<GmailMessagePayload>()
            for (msgId in messageIds) {
                val getReq = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/messages/$msgId?format=full")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                httpClient.newCall(getReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val raw = resp.body?.string().orEmpty()
                    val obj = JSONObject(raw)
                    val payload = obj.optJSONObject("payload")
                    val headers = payload?.optJSONArray("headers") ?: JSONArray()
                    var from = ""
                    var to = ""
                    var subject = ""
                    var messageIdHdr = ""
                    for (j in 0 until headers.length()) {
                        val h = headers.optJSONObject(j) ?: continue
                        when (h.optString("name").lowercase()) {
                            "from" -> from = h.optString("value")
                            "to" -> to = h.optString("value")
                            "subject" -> subject = h.optString("value")
                            "message-id" -> messageIdHdr = h.optString("value")
                        }
                    }
                    val labelsArr = obj.optJSONArray("labelIds") ?: JSONArray()
                    val labels = buildList {
                        for (k in 0 until labelsArr.length()) {
                            val lbl = labelsArr.optString(k)
                            if (lbl.isNotBlank()) add(lbl)
                        }
                    }
                    val extracted = extractMimeParts(payload)
                    val bodyText = extracted.plainText
                        .ifBlank { extracted.strippedHtml }
                        .ifBlank { obj.optString("snippet") }
                    fetched.add(
                        GmailMessagePayload(
                            id = obj.optString("id", msgId),
                            threadId = obj.optString("threadId"),
                            sender = from,
                            recipient = to,
                            subject = subject.ifBlank { "(No Subject)" },
                            snippet = obj.optString("snippet"),
                            bodyText = bodyText,
                            labels = labels,
                            attachmentsSummary = extracted.attachments.joinToString(", "),
                            messageIdHeader = messageIdHdr
                        )
                    )
                }
            }
            fetched
        }
    }

    override suspend fun sendMessage(
        accessToken: String,
        to: String,
        subject: String,
        body: String,
        threadId: String?,
        inReplyTo: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "Gmail OAuth access token is required." }
            require(to.contains("@")) { "Recipient email address is invalid." }
            val rfc2822 = buildString {
                append("To: ").append(to).append("\r\n")
                append("Subject: ").append(subject).append("\r\n")
                if (!inReplyTo.isNullOrBlank()) {
                    append("In-Reply-To: ").append(inReplyTo.trim()).append("\r\n")
                    append("References: ").append(inReplyTo.trim()).append("\r\n")
                }
                append("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
                append(body)
            }
            val encodedRaw = encodeBase64Url(rfc2822.toByteArray(Charsets.UTF_8))
            val json = JSONObject().apply {
                put("raw", encodedRaw)
                if (!threadId.isNullOrBlank()) {
                    put("threadId", threadId.trim())
                }
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/messages/send")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Gmail send HTTP ${resp.code}: ${SecurityAuditService.redactSensitiveFields(raw)}")
                }
                val obj = JSONObject(raw)
                obj.optString("id").ifBlank { "sent_${System.currentTimeMillis()}" }
            }
        }
    }

    override suspend fun modifyLabels(
        accessToken: String,
        messageId: String,
        addLabels: List<String>,
        removeLabels: List<String>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "Gmail OAuth access token is required." }
            val json = JSONObject().apply {
                put("addLabelIds", JSONArray(addLabels))
                put("removeLabelIds", JSONArray(removeLabels))
            }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/messages/$messageId/modify")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Gmail modify labels HTTP ${resp.code}")
                }
            }
        }
    }

    override suspend fun trashMessage(accessToken: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(accessToken.isNotBlank()) { "Gmail OAuth access token is required." }
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/messages/$messageId/trash")
                .addHeader("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Gmail trash HTTP ${resp.code}")
                }
            }
        }
    }

    private data class ExtractedMimeContent(
        val plainText: String = "",
        val strippedHtml: String = "",
        val attachments: List<String> = emptyList()
    )

    private fun extractMimeParts(part: JSONObject?): ExtractedMimeContent {
        if (part == null) return ExtractedMimeContent()
        val plainBuilder = StringBuilder()
        val htmlBuilder = StringBuilder()
        val attachments = mutableListOf<String>()

        fun walk(node: JSONObject) {
            val mimeType = node.optString("mimeType").lowercase()
            val filename = node.optString("filename").trim()
            val bodyObj = node.optJSONObject("body")
            val attachmentId = bodyObj?.optString("attachmentId").orEmpty().trim()
            val sizeBytes = bodyObj?.optLong("size", 0L) ?: 0L
            val data = bodyObj?.optString("data").orEmpty()

            if (filename.isNotBlank() || attachmentId.isNotBlank()) {
                val displayName = filename.ifBlank { "attachment" }
                val sizeLabel = if (sizeBytes > 0L) {
                    if (sizeBytes >= 1024L) "${sizeBytes / 1024L}KB" else "${sizeBytes}B"
                } else ""
                val meta = listOfNotNull(
                    mimeType.takeIf { it.isNotBlank() },
                    sizeLabel.takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                attachments.add(if (meta.isNotBlank()) "$displayName ($meta)" else displayName)
            } else if (data.isNotBlank()) {
                val decoded = decodeBase64Url(data)
                if (decoded.isNotBlank()) {
                    when {
                        mimeType == "text/plain" || mimeType.isBlank() -> {
                            if (plainBuilder.isNotEmpty()) plainBuilder.append('\n')
                            plainBuilder.append(decoded)
                        }
                        mimeType == "text/html" -> {
                            val stripped = decoded
                                .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                                .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                                .replace(Regex("<[^>]+>"), " ")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            if (stripped.isNotBlank()) {
                                if (htmlBuilder.isNotEmpty()) htmlBuilder.append('\n')
                                htmlBuilder.append(stripped)
                            }
                        }
                    }
                }
            }

            val subParts = node.optJSONArray("parts")
            if (subParts != null) {
                for (i in 0 until subParts.length()) {
                    val child = subParts.optJSONObject(i) ?: continue
                    walk(child)
                }
            }
        }

        walk(part)
        return ExtractedMimeContent(
            plainText = plainBuilder.toString().trim(),
            strippedHtml = htmlBuilder.toString().trim(),
            attachments = attachments.distinct()
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun encodeBase64Url(input: ByteArray): String {
            if (input.isEmpty()) return ""
            val out = StringBuilder((input.size * 4 + 2) / 3)
            var i = 0
            while (i < input.size) {
                val b0 = input[i++].toInt() and 0xFF
                val hasB1 = i < input.size
                val b1 = if (hasB1) input[i++].toInt() and 0xFF else 0
                val hasB2 = i < input.size
                val b2 = if (hasB2) input[i++].toInt() and 0xFF else 0

                val triplet = (b0 shl 16) or (b1 shl 8) or b2
                out.append(URL_ALPHABET[(triplet shr 18) and 0x3F])
                out.append(URL_ALPHABET[(triplet shr 12) and 0x3F])
                if (hasB1) out.append(URL_ALPHABET[(triplet shr 6) and 0x3F])
                if (hasB2) out.append(URL_ALPHABET[triplet and 0x3F])
            }
            return out.toString()
        }

        fun decodeBase64Url(encoded: String): String {
            val clean = encoded.trim().replace("=", "")
            if (clean.isEmpty()) return ""
            val rev = IntArray(128) { -1 }
            URL_ALPHABET.forEachIndexed { idx, ch -> rev[ch.code] = idx }
            rev['+'.code] = 62
            rev['/'.code] = 63

            val bytes = ArrayList<Byte>(clean.length * 3 / 4)
            var buffer = 0
            var bitsLeft = 0
            for (ch in clean) {
                val code = ch.code
                if (code >= 128 || rev[code] == -1) continue
                buffer = (buffer shl 6) or rev[code]
                bitsLeft += 6
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    bytes.add(((buffer shr bitsLeft) and 0xFF).toByte())
                }
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
