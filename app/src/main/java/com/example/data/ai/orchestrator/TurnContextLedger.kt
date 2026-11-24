package com.example.data.ai.orchestrator

import android.graphics.Bitmap
import com.example.data.ai.AlloraAiService
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot of active conversation and workspace state preserved across model failovers
 * so switching from Model A to Model B continues the exact same task without losing history.
 */
data class TurnContextSnapshot(
    val requestId: String,
    val userId: String,
    val systemPrompt: String,
    val historyPairs: List<Pair<Boolean, String>>,
    val userPrompt: String,
    val attachedBitmaps: List<Bitmap> = emptyList(),
    val audioWavBytes: ByteArray? = null,
    val taskId: String? = null,
    val browserSessionId: String? = null,
    val activeTaskObjective: String? = null,
    val activeBrowserUrl: String? = null,
    val lastToolResultSummary: String? = null,
    val pendingApprovalsSummary: String? = null,
    val userPreferencesSummary: String? = null
) {
    fun buildEnrichedSystemPrompt(): String {
        val extras = listOfNotNull(
            taskId?.takeIf { it.isNotBlank() }?.let { "Active task ID: $it" },
            activeTaskObjective?.takeIf { it.isNotBlank() }?.let { "Active task objective: $it" },
            browserSessionId?.takeIf { it.isNotBlank() }?.let { "Active browser session ID: $it" },
            activeBrowserUrl?.takeIf { it.isNotBlank() }?.let { "Active browser session URL: $it" },
            lastToolResultSummary?.takeIf { it.isNotBlank() }?.let { "Last completed tool result: $it" },
            pendingApprovalsSummary?.takeIf { it.isNotBlank() }?.let { "Pending owner approvals: $it" },
            userPreferencesSummary?.takeIf { it.isNotBlank() }?.let { "User context: $it" }
        )
        return if (extras.isEmpty()) systemPrompt
        else "$systemPrompt\n\n[Preserved Session State]\n${extras.joinToString("\n")}"
    }
}

/**
 * Idempotency ledger per request turn preventing duplicate execution of side-effecting
 * tools (wallet payments, email staging/sending, bookings, registrations) when a turn
 * fails over mid-execution between models.
 */
class ToolExecutionLedger(val requestId: String) {

    private val entries = ConcurrentHashMap<String, AlloraAiService.ToolExecOutput>()

    suspend fun executeIdempotently(
        toolName: String,
        canonicalArgsJson: String,
        executor: suspend () -> AlloraAiService.ToolExecOutput
    ): Pair<AlloraAiService.ToolExecOutput, Boolean> {
        val key = buildLedgerKey(toolName, canonicalArgsJson)
        entries[key]?.let { cached ->
            return cached to true
        }
        val computed = executor()
        if (computed.trace.succeeded || isSideEffectingTool(toolName)) {
            entries.putIfAbsent(key, computed)
        }
        return computed to false
    }

    fun recordedCount(): Int = entries.size

    companion object {
        private val SIDE_EFFECTING_TOOLS = setOf(
            AlloraAiService.TOOL_WALLET_PREPARE_PAYMENT,
            AlloraAiService.TOOL_EMAIL_STAGE_SEND,
            AlloraAiService.TOOL_BROWSER_OPEN,
            AlloraAiService.TOOL_BROWSER_INTERACT,
            AlloraAiService.TOOL_BROWSER_STAGE_REGISTRATION,
            AlloraAiService.TOOL_CREATE_ITINERARY,
            AlloraAiService.TOOL_RUN_AUTONOMOUS_TASK,
            AlloraAiService.TOOL_FILES_AND_MEMORY
        )

        fun isSideEffectingTool(toolName: String): Boolean = toolName in SIDE_EFFECTING_TOOLS

        fun buildLedgerKey(toolName: String, canonicalArgsJson: String): String {
            val normalizedArgs = canonicalArgsJson.replace(Regex("\\s+"), "").trim()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$toolName|$normalizedArgs".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(24)
            return "$toolName:$digest"
        }
    }
}
