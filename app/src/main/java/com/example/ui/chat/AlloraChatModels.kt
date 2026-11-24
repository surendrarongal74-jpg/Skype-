package com.example.ui.chat

import com.example.data.ai.orchestrator.ConversationSerializer
import com.example.data.model.ToolExecutionTrace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface ChatBlock {
    val id: String

    data class Text(
        override val id: String,
        val content: String
    ) : ChatBlock

    data class Links(
        override val id: String,
        val items: List<ChatLinkItem>
    ) : ChatBlock

    data class Table(
        override val id: String,
        val title: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val linkColumn: String? = null
    ) : ChatBlock

    data class OrderSummary(
        override val id: String,
        val merchantName: String,
        val merchantLogo: String? = null,
        val items: List<ChatOrderLineItem>,
        val fees: String,
        val total: String,
        val paymentMethod: String,
        val orderPlaced: Boolean = false
    ) : ChatBlock

    data class Summary(
        override val id: String,
        val title: String,
        val body: String
    ) : ChatBlock

    data class Draft(
        override val id: String,
        val title: String,
        val body: String
    ) : ChatBlock

    data class Images(
        override val id: String,
        val caption: String? = null,
        val items: List<ChatImageItem>
    ) : ChatBlock

    data class Task(
        override val id: String,
        val steps: List<String>,
        val status: String = "running" // "running" | "done"
    ) : ChatBlock

    data class LiveBrowser(
        override val id: String,
        val sessionId: String,
        val initialUrl: String,
        val taskLabel: String
    ) : ChatBlock

    data class HumanIntervention(
        override val id: String,
        val sessionId: String,
        val website: String,
        val kind: String,
        val reason: String
    ) : ChatBlock

    data class PaymentApproval(
        override val id: String,
        val idempotencyKey: String,
        val userId: String,
        val productName: String,
        val merchant: String,
        val subtotalDisplay: String,
        val taxesDisplay: String,
        val totalDisplay: String,
        val balanceBeforeDisplay: String,
        val balanceAfterDisplay: String,
        val deliveryOrBookingDetails: String,
        val expiresAtMs: Long,
        val status: String = "PENDING" // PENDING | APPROVED | REJECTED | EXPIRED
    ) : ChatBlock

    data class EmailApproval(
        override val id: String,
        val draftId: String,
        val recipient: String,
        val subject: String,
        val body: String,
        val dateLabel: String = "Draft",
        val attachmentsLabel: String = "",
        val status: String = "PENDING", // PENDING | SENT | DELETED | REJECTED
        val threadId: String = "",
        val accountEmail: String = "",
        val isDeleteConfirmation: Boolean = false,
        val targetItemId: String = ""
    ) : ChatBlock

    data class EmailDigest(
        override val id: String,
        val title: String,
        val items: List<ChatEmailItemSummary>
    ) : ChatBlock

    data class BrowserPermission(
        override val id: String,
        val domain: String,
        val actionLabel: String,
        val reason: String,
        val status: String = "PENDING" // PENDING | ALLOW_ONCE | ALLOW_LIMITED | DENIED
    ) : ChatBlock

    data class RegistrationApproval(
        override val id: String,
        val siteName: String,
        val url: String,
        val fields: Map<String, String>,
        val status: String = "PENDING" // PENDING | APPROVED | REJECTED
    ) : ChatBlock

    data class WalletCard(
        override val id: String,
        val availableBalance: String,
        val pendingBalance: String,
        val currency: String,
        val monthlySpend: String,
        val reconciliationStatus: String,
        val emergencyLocked: Boolean,
        val transactions: List<ChatWalletTxSummary> = emptyList()
    ) : ChatBlock

    data class TaskTimeline(
        override val id: String,
        val taskId: String,
        val title: String,
        val goal: String
    ) : ChatBlock
}

data class ChatEmailItemSummary(
    val id: String,
    val sender: String,
    val subject: String,
    val snippet: String,
    val aiSummary: String,
    val dateLabel: String,
    val status: String,
    val accountEmail: String = "",
    val threadId: String = "",
    val attachmentsSummary: String = "",
    val isImportant: Boolean = false
)

data class ChatWalletTxSummary(
    val id: String,
    val title: String,
    val amountDisplay: String,
    val status: String,
    val dateLabel: String
)

data class ChatLinkItem(
    val name: String,
    val role: String,
    val url: String,
    val imageUrl: String? = null
)

data class ChatOrderLineItem(
    val name: String,
    val qty: Int,
    val price: String,
    val thumbnail: String? = null
)

data class ChatImageItem(
    val url: String,
    val caption: String? = null
)

data class ChatAttachment(
    val id: String = "att_${UUID.randomUUID().toString().take(8)}",
    val uriString: String,
    val displayName: String,
    val isImage: Boolean,
    val mimeType: String = ""
)

data class AlloraReplyQuote(
    val messageId: String,
    val senderLabel: String,
    val snippet: String
)

data class AlloraRichMessage(
    val id: String,
    val isUser: Boolean,
    val plainText: String,
    val blocks: List<ChatBlock>,
    val timestampLabel: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val replyTo: AlloraReplyQuote? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val isError: Boolean = false,
    val isStreaming: Boolean = false,
    val followUpChips: List<String> = emptyList(),
    val toolTraces: List<ToolExecutionTrace> = emptyList()
)

data class AlloraChatThread(
    val id: String,
    val title: String,
    val category: String = "Chat",
    val updatedAtMs: Long = System.currentTimeMillis(),
    val messages: List<AlloraRichMessage> = emptyList()
)

object ChatBlockJsonParser {
    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun formatDateDivider(timestampMs: Long): String {
        val formatter = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
        return formatter.format(Date(timestampMs))
    }

    fun parseAiBlocksFromModelOutput(rawText: String): List<ChatBlock> {
        val cleaned = extractJsonEnvelope(rawText)
        if (cleaned.isNotBlank()) {
            val parsedObj = runCatching { lenientJson.parseToJsonElement(cleaned) as? JsonObject }.getOrNull()
            if (parsedObj != null) {
                val blocksArr = parsedObj["blocks"] as? JsonArray
                if (blocksArr != null) {
                    val parsed = blocksArr.mapIndexedNotNull { idx, elem ->
                        parseSingleBlock(elem as? JsonObject, idx)
                    }
                    if (parsed.isNotEmpty()) {
                        return parsed
                    }
                }
            }
        }

        // Clean plain-text fallback split into short conversational bubbles (<40 words target)
        return splitTextIntoShortBubbles(rawText)
    }

    internal fun splitTextIntoShortBubbles(rawText: String): List<ChatBlock> {
        val normalized = rawText.trim()
            .replace("\r\n", "\n")
            .takeIf { it.isNotBlank() }
            ?: return emptyList()

        val paragraphs = normalized.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<ChatBlock>()
        var seq = 0
        paragraphs.forEach { para ->
            val words = para.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size <= 42) {
                result.add(ChatBlock.Text(id = newBlockId("txt", seq++), content = para))
            } else {
                // Group sentences under ~40 words so bubbles never bloat
                val sentences = para.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
                val bucket = StringBuilder()
                var bucketWords = 0
                sentences.forEach { sentence ->
                    val sWords = sentence.split(Regex("\\s+")).size
                    if (bucketWords > 0 && bucketWords + sWords > 40) {
                        result.add(ChatBlock.Text(id = newBlockId("txt", seq++), content = bucket.toString().trim()))
                        bucket.clear()
                        bucketWords = 0
                    }
                    if (bucket.isNotEmpty()) bucket.append(' ')
                    bucket.append(sentence)
                    bucketWords += sWords
                }
                if (bucket.isNotEmpty()) {
                    result.add(ChatBlock.Text(id = newBlockId("txt", seq++), content = bucket.toString().trim()))
                }
            }
        }
        return result.ifEmpty {
            listOf(ChatBlock.Text(id = newBlockId("txt", 0), content = normalized))
        }
    }

    private fun extractJsonEnvelope(raw: String): String {
        val trimmed = raw.trim()
        val unwrapped = if (trimmed.startsWith("```")) {
            trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .substringBeforeLast("```")
                .trim()
        } else {
            trimmed
        }
        val firstBrace = unwrapped.indexOf('{')
        val lastBrace = unwrapped.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return unwrapped.substring(firstBrace, lastBrace + 1)
        }
        return ""
    }

    private fun parseSingleBlock(obj: JsonObject?, index: Int): ChatBlock? {
        if (obj == null) return null
        val type = obj.str("type")?.lowercase(Locale.US) ?: return null
        val blockId = obj.str("id") ?: newBlockId(type, index)
        return when (type) {
            "text" -> {
                val content = (obj.str("content") ?: obj.str("text")).orEmpty().trim()
                if (content.isBlank()) null else ChatBlock.Text(id = blockId, content = content)
            }
            "links" -> {
                val rawItems = (obj["items"] ?: obj["links"]) as? JsonArray ?: return null
                val items = rawItems.mapNotNull { elem ->
                    val itemObj = elem as? JsonObject ?: return@mapNotNull null
                    val name = (itemObj.str("name") ?: itemObj.str("title")).orEmpty().trim()
                    val url = itemObj.str("url").orEmpty().trim()
                    if (name.isBlank()) return@mapNotNull null
                    ChatLinkItem(
                        name = name,
                        role = (itemObj.str("role") ?: itemObj.str("label") ?: "View").trim(),
                        url = url,
                        imageUrl = itemObj.str("imageUrl") ?: itemObj.str("image")
                    )
                }
                if (items.isEmpty()) null else ChatBlock.Links(id = blockId, items = items)
            }
            "table" -> {
                val title = (obj.str("title") ?: "Details").trim()
                val cols = (obj["columns"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val rawRows = obj["rows"] as? JsonArray ?: return null
                val rows = rawRows.mapNotNull { rowElem ->
                    when (rowElem) {
                        is JsonArray -> rowElem.map { cell ->
                            (cell as? JsonPrimitive)?.contentOrNull ?: cell.toString()
                        }
                        else -> null
                    }
                }
                if (rows.isEmpty()) null else ChatBlock.Table(
                    id = blockId,
                    title = title,
                    columns = cols,
                    rows = rows,
                    linkColumn = obj.str("linkColumn") ?: obj.str("link_column")
                )
            }
            "order_summary" -> {
                val merchantName = (obj.str("merchantName") ?: obj.str("merchant") ?: "Merchant").trim()
                val rawItems = obj["items"] as? JsonArray ?: JsonArray(emptyList())
                val lineItems = rawItems.mapNotNull { elem ->
                    val itemObj = elem as? JsonObject ?: return@mapNotNull null
                    val name = itemObj.str("name").orEmpty().trim()
                    if (name.isBlank()) return@mapNotNull null
                    ChatOrderLineItem(
                        name = name,
                        qty = itemObj.int("qty") ?: itemObj.int("quantity") ?: 1,
                        price = itemObj.str("price") ?: "$0.00",
                        thumbnail = itemObj.str("thumbnail") ?: itemObj.str("imageUrl") ?: itemObj.str("image")
                    )
                }
                ChatBlock.OrderSummary(
                    id = blockId,
                    merchantName = merchantName,
                    merchantLogo = obj.str("merchantLogo") ?: obj.str("logo"),
                    items = lineItems,
                    fees = obj.str("fees") ?: "$0.00",
                    total = obj.str("total") ?: "$0.00",
                    paymentMethod = obj.str("paymentMethod") ?: obj.str("payment_method") ?: "Card on file",
                    orderPlaced = obj.bool("orderPlaced") ?: false
                )
            }
            "summary" -> {
                val title = (obj.str("title") ?: "Summary").trim()
                val body = (obj.str("body") ?: obj.str("content")).orEmpty().trim()
                if (body.isBlank()) null else ChatBlock.Summary(id = blockId, title = title, body = body)
            }
            "draft" -> {
                val title = (obj.str("title") ?: "Draft").trim()
                val body = (obj.str("body") ?: obj.str("content") ?: obj.str("text")).orEmpty().trim()
                if (body.isBlank()) null else ChatBlock.Draft(id = blockId, title = title, body = body)
            }
            "images" -> {
                val rawItems = (obj["items"] ?: obj["images"]) as? JsonArray ?: return null
                val items = rawItems.mapNotNull { elem ->
                    when (elem) {
                        is JsonPrimitive -> {
                            val url = elem.contentOrNull?.trim().orEmpty()
                            if (url.isBlank()) null else ChatImageItem(url = url)
                        }
                        is JsonObject -> {
                            val url = (elem.str("url") ?: elem.str("imageUrl") ?: elem.str("src")).orEmpty().trim()
                            if (url.isBlank()) null else ChatImageItem(
                                url = url,
                                caption = elem.str("caption")
                            )
                        }
                        else -> null
                    }
                }
                if (items.isEmpty()) null else ChatBlock.Images(
                    id = blockId,
                    caption = obj.str("caption"),
                    items = items
                )
            }
            "task" -> {
                val rawSteps = obj["steps"] as? JsonArray
                val steps = rawSteps?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                    ?: listOfNotNull(obj.str("step") ?: obj.str("title"))
                val status = obj.str("status")?.lowercase(Locale.US) ?: "done"
                if (steps.isEmpty()) null else ChatBlock.Task(id = blockId, steps = steps, status = status)
            }
            "live_browser" -> ChatBlock.LiveBrowser(
                id = blockId,
                sessionId = obj.str("sessionId").orEmpty(),
                initialUrl = obj.str("initialUrl").orEmpty(),
                taskLabel = obj.str("taskLabel") ?: "Live Browser Session"
            )
            "human_intervention" -> ChatBlock.HumanIntervention(
                id = blockId,
                sessionId = obj.str("sessionId").orEmpty(),
                website = obj.str("website").orEmpty(),
                kind = obj.str("kind") ?: "SECURITY_CHECK",
                reason = obj.str("reason").orEmpty()
            )
            "payment_approval" -> ChatBlock.PaymentApproval(
                id = blockId,
                idempotencyKey = obj.str("idempotencyKey").orEmpty(),
                userId = obj.str("userId").orEmpty(),
                productName = obj.str("productName") ?: "Purchase",
                merchant = obj.str("merchant") ?: "Merchant",
                subtotalDisplay = obj.str("subtotalDisplay") ?: "$0.00",
                taxesDisplay = obj.str("taxesDisplay") ?: "$0.00",
                totalDisplay = obj.str("totalDisplay") ?: "$0.00",
                balanceBeforeDisplay = obj.str("balanceBeforeDisplay") ?: "$0.00",
                balanceAfterDisplay = obj.str("balanceAfterDisplay") ?: "$0.00",
                deliveryOrBookingDetails = obj.str("deliveryOrBookingDetails").orEmpty(),
                expiresAtMs = obj.long("expiresAtMs") ?: (System.currentTimeMillis() + 900_000L),
                status = obj.str("status") ?: "PENDING"
            )
            "email_approval" -> ChatBlock.EmailApproval(
                id = blockId,
                draftId = obj.str("draftId").orEmpty(),
                recipient = obj.str("recipient").orEmpty(),
                subject = obj.str("subject").orEmpty(),
                body = obj.str("body").orEmpty(),
                dateLabel = obj.str("dateLabel") ?: "Draft",
                attachmentsLabel = obj.str("attachmentsLabel").orEmpty(),
                status = obj.str("status") ?: "PENDING",
                threadId = obj.str("threadId").orEmpty(),
                accountEmail = obj.str("accountEmail").orEmpty(),
                isDeleteConfirmation = obj.bool("isDeleteConfirmation") ?: false,
                targetItemId = obj.str("targetItemId").orEmpty()
            )
            "email_digest" -> {
                val rawItems = obj["items"] as? JsonArray ?: JsonArray(emptyList())
                val items = rawItems.mapNotNull { elem ->
                    val o = elem as? JsonObject ?: return@mapNotNull null
                    ChatEmailItemSummary(
                        id = o.str("id") ?: UUID.randomUUID().toString(),
                        sender = o.str("sender").orEmpty(),
                        subject = o.str("subject").orEmpty(),
                        snippet = o.str("snippet").orEmpty(),
                        aiSummary = o.str("aiSummary").orEmpty(),
                        dateLabel = o.str("dateLabel") ?: "Recent",
                        status = o.str("status") ?: "SYNCED",
                        accountEmail = o.str("accountEmail").orEmpty(),
                        threadId = o.str("threadId").orEmpty(),
                        attachmentsSummary = o.str("attachmentsSummary").orEmpty(),
                        isImportant = o.bool("isImportant") ?: false
                    )
                }
                ChatBlock.EmailDigest(
                    id = blockId,
                    title = obj.str("title") ?: "Inbox Messages",
                    items = items
                )
            }
            "browser_permission" -> ChatBlock.BrowserPermission(
                id = blockId,
                domain = obj.str("domain").orEmpty(),
                actionLabel = obj.str("actionLabel") ?: "Browser Access",
                reason = obj.str("reason").orEmpty(),
                status = obj.str("status") ?: "PENDING"
            )
            "registration_approval" -> {
                val fieldsObj = obj["fields"] as? JsonObject
                val fields = fieldsObj?.mapValues { (_, v) ->
                    (v as? JsonPrimitive)?.contentOrNull.orEmpty()
                } ?: emptyMap()
                ChatBlock.RegistrationApproval(
                    id = blockId,
                    siteName = obj.str("siteName").orEmpty(),
                    url = obj.str("url").orEmpty(),
                    fields = fields,
                    status = obj.str("status") ?: "PENDING"
                )
            }
            "wallet_card" -> {
                val rawTxs = obj["transactions"] as? JsonArray ?: JsonArray(emptyList())
                val txs = rawTxs.mapNotNull { elem ->
                    val o = elem as? JsonObject ?: return@mapNotNull null
                    ChatWalletTxSummary(
                        id = o.str("id") ?: UUID.randomUUID().toString(),
                        title = o.str("title").orEmpty(),
                        amountDisplay = o.str("amountDisplay").orEmpty(),
                        status = o.str("status").orEmpty(),
                        dateLabel = o.str("dateLabel").orEmpty()
                    )
                }
                ChatBlock.WalletCard(
                    id = blockId,
                    availableBalance = obj.str("availableBalance") ?: "$0.00",
                    pendingBalance = obj.str("pendingBalance") ?: "$0.00",
                    currency = obj.str("currency") ?: "USD",
                    monthlySpend = obj.str("monthlySpend") ?: "$0.00",
                    reconciliationStatus = obj.str("reconciliationStatus") ?: "SYNC_PENDING",
                    emergencyLocked = obj.bool("emergencyLocked") ?: false,
                    transactions = txs
                )
            }
            "task_timeline" -> ChatBlock.TaskTimeline(
                id = blockId,
                taskId = obj.str("taskId").orEmpty(),
                title = obj.str("title") ?: "Autonomous Task",
                goal = obj.str("goal").orEmpty()
            )
            else -> null
        }
    }

    fun summarizePlainText(blocks: List<ChatBlock>): String {
        val parts = blocks.mapNotNull { block ->
            when (block) {
                is ChatBlock.Text -> block.content
                is ChatBlock.Summary -> "${block.title}: ${block.body}"
                is ChatBlock.Draft -> "${block.title}: ${block.body}"
                is ChatBlock.Table -> "${block.title} (${block.rows.size} rows)"
                is ChatBlock.Links -> block.items.joinToString(", ") { "${it.name} (${it.role})" }
                is ChatBlock.OrderSummary -> "Order from ${block.merchantName}: ${block.total}"
                is ChatBlock.Images -> block.caption ?: "${block.items.size} photo(s)"
                is ChatBlock.Task -> block.steps.lastOrNull()
                is ChatBlock.LiveBrowser -> "ALLORA Computer: ${block.taskLabel} (${block.initialUrl})"
                is ChatBlock.HumanIntervention -> "Needs your quick check on ${block.website}: ${block.reason}"
                is ChatBlock.PaymentApproval -> "Payment approval (${block.status}): ${block.productName} at ${block.merchant} (${block.totalDisplay})"
                is ChatBlock.EmailApproval -> if (block.isDeleteConfirmation) {
                    "Confirm deleting email (${block.status}): ${block.subject}"
                } else {
                    "Email approval (${block.status}) to ${block.recipient}: ${block.subject}"
                }
                is ChatBlock.EmailDigest -> "${block.title}: ${block.items.size} email(s)"
                is ChatBlock.BrowserPermission -> "Site permission (${block.status}) for ${block.domain}"
                is ChatBlock.RegistrationApproval -> "Form review (${block.status}) for ${block.siteName}"
                is ChatBlock.WalletCard -> "Wallet balance: ${block.availableBalance} (${block.reconciliationStatus})"
                is ChatBlock.TaskTimeline -> "Task progress: ${block.title}"
            }
        }
        return parts.joinToString("\n\n").trim()
    }

    fun serializeThreads(threads: List<AlloraChatThread>): String {
        val root = buildJsonObject {
            putJsonArray("threads") {
                threads.forEach { thread ->
                    addJsonObject {
                        put("id", thread.id)
                        put("title", thread.title)
                        put("category", thread.category)
                        put("updatedAtMs", thread.updatedAtMs)
                        putJsonArray("messages") {
                            thread.messages.forEach { msg ->
                                add(serializeMessage(msg))
                            }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    fun deserializeThreads(raw: String): List<AlloraChatThread> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = lenientJson.parseToJsonElement(raw).jsonObject
            val arr = root["threads"] as? JsonArray ?: return emptyList()
            arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val id = obj.str("id") ?: return@mapNotNull null
                val title = obj.str("title") ?: "Allora Chat"
                val category = obj.str("category") ?: "Chat"
                val updatedAt = obj.long("updatedAtMs") ?: System.currentTimeMillis()
                val msgs = (obj["messages"] as? JsonArray)?.mapNotNull { m ->
                    deserializeMessage(m as? JsonObject)
                } ?: emptyList()
                AlloraChatThread(
                    id = id,
                    title = title,
                    category = category,
                    updatedAtMs = updatedAt,
                    messages = ConversationSerializer.repairRichMessages(msgs)
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun serializeMessage(msg: AlloraRichMessage): JsonObject = buildJsonObject {
        put("id", msg.id)
        put("isUser", msg.isUser)
        put("plainText", msg.plainText)
        put("timestampLabel", msg.timestampLabel)
        put("createdAtMs", msg.createdAtMs)
        put("isError", msg.isError)
        msg.replyTo?.let { q ->
            putJsonObject("replyTo") {
                put("messageId", q.messageId)
                put("senderLabel", q.senderLabel)
                put("snippet", q.snippet)
            }
        }
        if (msg.attachments.isNotEmpty()) {
            putJsonArray("attachments") {
                msg.attachments.forEach { att ->
                    addJsonObject {
                        put("id", att.id)
                        put("uriString", att.uriString)
                        put("displayName", att.displayName)
                        put("isImage", att.isImage)
                        put("mimeType", att.mimeType)
                    }
                }
            }
        }
        if (msg.followUpChips.isNotEmpty()) {
            putJsonArray("followUpChips") {
                msg.followUpChips.forEach { add(it) }
            }
        }
        if (msg.toolTraces.isNotEmpty()) {
            putJsonArray("toolTraces") {
                msg.toolTraces.forEach { tr ->
                    addJsonObject {
                        put("toolName", tr.toolName)
                        put("summary", tr.summary)
                        put("succeeded", tr.succeeded)
                    }
                }
            }
        }
        putJsonArray("blocks") {
            msg.blocks.forEach { block ->
                add(serializeBlock(block))
            }
        }
    }

    private fun deserializeMessage(obj: JsonObject?): AlloraRichMessage? {
        if (obj == null) return null
        val id = obj.str("id") ?: return null
        val legacyRole = obj.str("role")?.trim()?.lowercase(Locale.US)
        val isUser = when (legacyRole) {
            "user", "human" -> true
            "model", "assistant", "ai", "function", "tool", "system" -> false
            else -> obj.bool("isUser") ?: false
        }
        val rawPlainText = obj.str("plainText") ?: obj.str("text") ?: obj.str("content") ?: ""
        val plainText = ConversationSerializer.sanitizeUserFacingErrorText(rawPlainText)
        val timestampLabel = obj.str("timestampLabel") ?: "Now"
        val createdAtMs = obj.long("createdAtMs") ?: System.currentTimeMillis()
        val isError = (obj.bool("isError") ?: false) ||
            ConversationSerializer.isRoleCompatibilityErrorMessage(rawPlainText)
        val replyObj = obj["replyTo"] as? JsonObject
        val replyTo = replyObj?.let {
            val mid = it.str("messageId") ?: return@let null
            AlloraReplyQuote(
                messageId = mid,
                senderLabel = it.str("senderLabel") ?: "Allora",
                snippet = it.str("snippet").orEmpty()
            )
        }
        val attachments = (obj["attachments"] as? JsonArray)?.mapNotNull { elem ->
            val a = elem as? JsonObject ?: return@mapNotNull null
            ChatAttachment(
                id = a.str("id") ?: UUID.randomUUID().toString(),
                uriString = a.str("uriString").orEmpty(),
                displayName = a.str("displayName") ?: "Attachment",
                isImage = a.bool("isImage") ?: false,
                mimeType = a.str("mimeType").orEmpty()
            )
        } ?: emptyList()
        val chips = (obj["followUpChips"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val parsedTraces = (obj["toolTraces"] as? JsonArray)?.mapNotNull { elem ->
            val t = elem as? JsonObject ?: return@mapNotNull null
            ToolExecutionTrace(
                toolName = t.str("toolName").orEmpty(),
                summary = t.str("summary").orEmpty(),
                succeeded = t.bool("succeeded") ?: true
            )
        } ?: emptyList()
        val traces = if (legacyRole in setOf("function", "tool") && parsedTraces.isEmpty()) {
            val fnName = obj.str("name")?.takeIf { it.isNotBlank() } ?: "tool_execution"
            listOf(
                ToolExecutionTrace(
                    toolName = fnName,
                    summary = plainText.take(80).ifBlank { "Recorded tool result" },
                    succeeded = !isError
                )
            )
        } else {
            parsedTraces
        }
        val blocks = (obj["blocks"] as? JsonArray)?.mapIndexedNotNull { idx, elem ->
            parseSingleBlock(elem as? JsonObject, idx)
        }?.ifEmpty {
            if (plainText.isNotBlank()) listOf(ChatBlock.Text(id = "${id}_txt", content = plainText)) else emptyList()
        } ?: if (plainText.isNotBlank()) listOf(ChatBlock.Text(id = "${id}_txt", content = plainText)) else emptyList()

        return AlloraRichMessage(
            id = id,
            isUser = isUser,
            plainText = plainText,
            blocks = blocks,
            timestampLabel = timestampLabel,
            createdAtMs = createdAtMs,
            replyTo = replyTo,
            attachments = attachments,
            isError = isError,
            isStreaming = false,
            followUpChips = chips,
            toolTraces = traces
        )
    }

    private fun serializeBlock(block: ChatBlock): JsonObject = when (block) {
        is ChatBlock.Text -> buildJsonObject {
            put("id", block.id)
            put("type", "text")
            put("content", block.content)
        }
        is ChatBlock.Links -> buildJsonObject {
            put("id", block.id)
            put("type", "links")
            putJsonArray("items") {
                block.items.forEach { item ->
                    addJsonObject {
                        put("name", item.name)
                        put("role", item.role)
                        put("url", item.url)
                        item.imageUrl?.let { put("imageUrl", it) }
                    }
                }
            }
        }
        is ChatBlock.Table -> buildJsonObject {
            put("id", block.id)
            put("type", "table")
            put("title", block.title)
            putJsonArray("columns") { block.columns.forEach { add(it) } }
            putJsonArray("rows") {
                block.rows.forEach { row ->
                    add(kotlinx.serialization.json.buildJsonArray { row.forEach { add(it) } })
                }
            }
            block.linkColumn?.let { put("linkColumn", it) }
        }
        is ChatBlock.OrderSummary -> buildJsonObject {
            put("id", block.id)
            put("type", "order_summary")
            put("merchantName", block.merchantName)
            block.merchantLogo?.let { put("merchantLogo", it) }
            putJsonArray("items") {
                block.items.forEach { item ->
                    addJsonObject {
                        put("name", item.name)
                        put("qty", item.qty)
                        put("price", item.price)
                        item.thumbnail?.let { put("thumbnail", it) }
                    }
                }
            }
            put("fees", block.fees)
            put("total", block.total)
            put("paymentMethod", block.paymentMethod)
            put("orderPlaced", block.orderPlaced)
        }
        is ChatBlock.Summary -> buildJsonObject {
            put("id", block.id)
            put("type", "summary")
            put("title", block.title)
            put("body", block.body)
        }
        is ChatBlock.Draft -> buildJsonObject {
            put("id", block.id)
            put("type", "draft")
            put("title", block.title)
            put("body", block.body)
        }
        is ChatBlock.Images -> buildJsonObject {
            put("id", block.id)
            put("type", "images")
            block.caption?.let { put("caption", it) }
            putJsonArray("items") {
                block.items.forEach { item ->
                    addJsonObject {
                        put("url", item.url)
                        item.caption?.let { put("caption", it) }
                    }
                }
            }
        }
        is ChatBlock.Task -> buildJsonObject {
            put("id", block.id)
            put("type", "task")
            putJsonArray("steps") { block.steps.forEach { add(it) } }
            put("status", block.status)
        }
        is ChatBlock.LiveBrowser -> buildJsonObject {
            put("id", block.id)
            put("type", "live_browser")
            put("sessionId", block.sessionId)
            put("initialUrl", block.initialUrl)
            put("taskLabel", block.taskLabel)
        }
        is ChatBlock.HumanIntervention -> buildJsonObject {
            put("id", block.id)
            put("type", "human_intervention")
            put("sessionId", block.sessionId)
            put("website", block.website)
            put("kind", block.kind)
            put("reason", block.reason)
        }
        is ChatBlock.PaymentApproval -> buildJsonObject {
            put("id", block.id)
            put("type", "payment_approval")
            put("idempotencyKey", block.idempotencyKey)
            put("userId", block.userId)
            put("productName", block.productName)
            put("merchant", block.merchant)
            put("subtotalDisplay", block.subtotalDisplay)
            put("taxesDisplay", block.taxesDisplay)
            put("totalDisplay", block.totalDisplay)
            put("balanceBeforeDisplay", block.balanceBeforeDisplay)
            put("balanceAfterDisplay", block.balanceAfterDisplay)
            put("deliveryOrBookingDetails", block.deliveryOrBookingDetails)
            put("expiresAtMs", block.expiresAtMs)
            put("status", block.status)
        }
        is ChatBlock.EmailApproval -> buildJsonObject {
            put("id", block.id)
            put("type", "email_approval")
            put("draftId", block.draftId)
            put("recipient", block.recipient)
            put("subject", block.subject)
            put("body", block.body)
            put("dateLabel", block.dateLabel)
            put("attachmentsLabel", block.attachmentsLabel)
            put("status", block.status)
            put("threadId", block.threadId)
            put("accountEmail", block.accountEmail)
            put("isDeleteConfirmation", block.isDeleteConfirmation)
            put("targetItemId", block.targetItemId)
        }
        is ChatBlock.EmailDigest -> buildJsonObject {
            put("id", block.id)
            put("type", "email_digest")
            put("title", block.title)
            putJsonArray("items") {
                block.items.forEach { item ->
                    addJsonObject {
                        put("id", item.id)
                        put("sender", item.sender)
                        put("subject", item.subject)
                        put("snippet", item.snippet)
                        put("aiSummary", item.aiSummary)
                        put("dateLabel", item.dateLabel)
                        put("status", item.status)
                        put("accountEmail", item.accountEmail)
                        put("threadId", item.threadId)
                        put("attachmentsSummary", item.attachmentsSummary)
                        put("isImportant", item.isImportant)
                    }
                }
            }
        }
        is ChatBlock.BrowserPermission -> buildJsonObject {
            put("id", block.id)
            put("type", "browser_permission")
            put("domain", block.domain)
            put("actionLabel", block.actionLabel)
            put("reason", block.reason)
            put("status", block.status)
        }
        is ChatBlock.RegistrationApproval -> buildJsonObject {
            put("id", block.id)
            put("type", "registration_approval")
            put("siteName", block.siteName)
            put("url", block.url)
            put("status", block.status)
            putJsonObject("fields") {
                block.fields.forEach { (k, v) -> put(k, v) }
            }
        }
        is ChatBlock.WalletCard -> buildJsonObject {
            put("id", block.id)
            put("type", "wallet_card")
            put("availableBalance", block.availableBalance)
            put("pendingBalance", block.pendingBalance)
            put("currency", block.currency)
            put("monthlySpend", block.monthlySpend)
            put("reconciliationStatus", block.reconciliationStatus)
            put("emergencyLocked", block.emergencyLocked)
            putJsonArray("transactions") {
                block.transactions.forEach { tx ->
                    addJsonObject {
                        put("id", tx.id)
                        put("title", tx.title)
                        put("amountDisplay", tx.amountDisplay)
                        put("status", tx.status)
                        put("dateLabel", tx.dateLabel)
                    }
                }
            }
        }
        is ChatBlock.TaskTimeline -> buildJsonObject {
            put("id", block.id)
            put("type", "task_timeline")
            put("taskId", block.taskId)
            put("title", block.title)
            put("goal", block.goal)
        }
    }

    private fun newBlockId(prefix: String, index: Int): String =
        "blk_${prefix}_${index}_${UUID.randomUUID().toString().take(6)}"

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
