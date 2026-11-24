package com.example.services

import com.example.core.SecurityUtils
import com.example.data.local.dao.SecurityAuditDao
import com.example.data.local.entity.SecurityAuditLogEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AuditCategory {
    AUTH, WALLET, PAYMENT, EMAIL, BROWSER, PERMISSION, TASK, ISOLATION
}

enum class AuditOutcome {
    ALLOWED, DENIED, BLOCKED, VERIFIED, FAILED, REVOKED
}

class SecurityAuditService(
    private val auditDao: SecurityAuditDao,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    private val rateBuckets = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun observeAuditLogs(userId: String, limit: Int = 100): Flow<List<SecurityAuditLogEntity>> =
        auditDao.observeLogs(userId, limit)

    suspend fun listAuditLogs(userId: String): List<SecurityAuditLogEntity> =
        auditDao.listLogs(userId)

    suspend fun recordEvent(
        userId: String,
        category: AuditCategory,
        action: String,
        outcome: AuditOutcome,
        detail: String
    ): SecurityAuditLogEntity {
        val redactedDetail = redactSensitiveFields(detail).take(1000)
        val log = SecurityAuditLogEntity(
            id = "aud_${UUID.randomUUID().toString().replace("-", "").take(14)}",
            userId = userId,
            category = category.name,
            action = action.take(120),
            outcome = outcome.name,
            detail = redactedDetail,
            createdAt = System.currentTimeMillis()
        )
        auditDao.insertLog(log)
        runCatching { cloudSync?.appendSecurityAudit(log) }
        return log
    }

    /**
     * Sliding-window in-memory rate limiter scoped per (userId, actionKey).
     */
    fun checkRateLimit(
        userId: String,
        actionKey: String,
        maxRequests: Int,
        windowMs: Long
    ): Boolean {
        val now = System.currentTimeMillis()
        val key = "$userId::$actionKey"
        val queue = rateBuckets.computeIfAbsent(key) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && now - queue.first() > windowMs) {
                queue.removeFirst()
            }
            if (queue.size >= maxRequests) {
                return false
            }
            queue.addLast(now)
            return true
        }
    }

    suspend fun wipeLocalAuditCache(userId: String) {
        auditDao.deleteAllForUser(userId)
    }

    companion object {
        private val CARD_PAN_REGEX = Regex("""\b(?:\d[ -]*?){13,19}\b""")
        private val CVV_REGEX = Regex("""(?i)\b(cvv|cvc|pin|upi\s*pin|otp|password|passcode|bearer)\s*[:=#]?\s*([A-Za-z0-9._\-]{3,64})""")
        private val TOKEN_KV_REGEX = Regex("""(?i)\b(access_token|refresh_token|id_token|client_secret|api_key|authorization)\s*[:=]\s*["']?([A-Za-z0-9._\-+/=]{6,256})["']?""")
        private val KEY_PREFIX_REGEX = Regex("""\b(sk-[A-Za-z0-9_\-]{12,}|AIza[0-9A-Za-z_\-]{16,})\b""")
        private val AUTH_HEADER_REGEX = Regex("""(?i)(Bearer|ya29\.[A-Za-z0-9_\-]+)\s*[A-Za-z0-9._\-]*""")

        /**
         * Detects if input attempts to pass raw card PANs, CVVs, banking passwords, UPI PINs, or email passwords.
         */
        fun containsForbiddenSecretInput(rawInput: String): String? {
            val lower = rawInput.lowercase(Locale.ROOT)
            if (CVV_REGEX.containsMatchIn(rawInput)) {
                return "Raw CVV, UPI PIN, OTP, or password credentials are strictly prohibited."
            }
            if (lower.contains("banking password") || lower.contains("email password") || lower.contains("upi pin")) {
                return "Storing or transmitting banking passwords, email passwords, or UPI PINs is prohibited."
            }
            val matches = CARD_PAN_REGEX.findAll(rawInput)
            for (m in matches) {
                val digits = m.value.filter { it.isDigit() }
                if (digits.length in 13..19 && passesLuhnCheck(digits)) {
                    return "Raw payment card numbers (PAN) are prohibited; use tokenized payment instruments only."
                }
            }
            return null
        }

        fun redactSensitiveFields(text: String): String {
            var cleaned = CVV_REGEX.replace(text) { mr -> "${mr.groupValues[1]}=[REDACTED]" }
            cleaned = TOKEN_KV_REGEX.replace(cleaned) { mr -> "${mr.groupValues[1]}=[REDACTED_TOKEN]" }
            cleaned = KEY_PREFIX_REGEX.replace(cleaned, "[REDACTED_KEY]")
            cleaned = AUTH_HEADER_REGEX.replace(cleaned, "[REDACTED_TOKEN]")
            cleaned = CARD_PAN_REGEX.replace(cleaned) { mr ->
                val digits = mr.value.filter { it.isDigit() }
                if (digits.length in 13..19 && passesLuhnCheck(digits)) "[REDACTED_CARD]" else mr.value
            }
            return cleaned
        }

        fun passesLuhnCheck(digits: String): Boolean {
            if (digits.length < 13 || digits.any { !it.isDigit() }) return false
            var sum = 0
            var alternate = false
            for (i in digits.length - 1 downTo 0) {
                var n = digits[i] - '0'
                if (alternate) {
                    n *= 2
                    if (n > 9) n -= 9
                }
                sum += n
                alternate = !alternate
            }
            return sum % 10 == 0
        }

        fun buildSha256Receipt(
            userId: String,
            txType: String,
            merchant: String,
            totalMinor: Long,
            idempotencyKey: String,
            providerTxId: String?,
            timestampMs: Long
        ): String {
            return SecurityUtils.sha256Hex(
                "$userId|$txType|$merchant|$totalMinor|$idempotencyKey|${providerTxId.orEmpty()}|$timestampMs"
            )
        }
    }
}
