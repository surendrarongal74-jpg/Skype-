package com.example.data.ai.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces per-user AI request & token quotas fairly so one user or burst cannot exhaust
 * shared provider capacity or cause cascading 429 failures.
 */
class AiUsageLimiter(
    private val dailyRequestLimit: Int = 250,
    private val dailyTokenLimit: Long = 750_000L,
    private val monthlyRequestLimit: Int = 5_000,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class UserCounter(
        var dayKey: String,
        var monthKey: String,
        var dailyRequests: Int = 0,
        var dailyTokens: Long = 0L,
        var monthlyRequests: Int = 0
    )

    private val counters = ConcurrentHashMap<String, UserCounter>()

    private val _activeQuota = MutableStateFlow(
        AiQuotaSnapshot(
            dailyRequestLimit = dailyRequestLimit,
            dailyTokenLimit = dailyTokenLimit,
            monthlyRequestLimit = monthlyRequestLimit
        )
    )
    val activeQuota: StateFlow<AiQuotaSnapshot> = _activeQuota.asStateFlow()

    fun checkAndConsumeRequest(userId: String): AiQuotaSnapshot {
        val (dayKey, monthKey) = currentPeriodKeys()
        val updated = counters.compute(userId.ifBlank { "default" }) { _, prev ->
            val cur = prev ?: UserCounter(dayKey, monthKey)
            if (cur.dayKey != dayKey) {
                cur.dayKey = dayKey
                cur.dailyRequests = 0
                cur.dailyTokens = 0L
            }
            if (cur.monthKey != monthKey) {
                cur.monthKey = monthKey
                cur.monthlyRequests = 0
            }
            cur.dailyRequests += 1
            cur.monthlyRequests += 1
            cur
        }!!

        val exceeded = updated.dailyRequests > dailyRequestLimit ||
            updated.dailyTokens > dailyTokenLimit ||
            updated.monthlyRequests > monthlyRequestLimit

        val snap = AiQuotaSnapshot(
            dailyRequestsUsed = updated.dailyRequests,
            dailyRequestLimit = dailyRequestLimit,
            dailyTokensUsed = updated.dailyTokens,
            dailyTokenLimit = dailyTokenLimit,
            monthlyRequestsUsed = updated.monthlyRequests,
            monthlyRequestLimit = monthlyRequestLimit,
            isRateLimited = exceeded
        )
        _activeQuota.value = snap

        if (exceeded) {
            throw AiProviderException(
                category = AiFailureCategory.RATE_LIMIT_429,
                statusCode = 429,
                retryable = false,
                providerId = "QUOTA_LIMITER",
                message = "Daily or monthly AI usage limit reached (${updated.dailyRequests}/$dailyRequestLimit requests today). Limit resets automatically."
            )
        }
        return snap
    }

    fun recordTokenUsage(userId: String, totalTokens: Long) {
        if (totalTokens <= 0L) return
        val (dayKey, monthKey) = currentPeriodKeys()
        val updated = counters.compute(userId.ifBlank { "default" }) { _, prev ->
            val cur = prev ?: UserCounter(dayKey, monthKey)
            if (cur.dayKey != dayKey) {
                cur.dayKey = dayKey
                cur.dailyRequests = 0
                cur.dailyTokens = 0L
            }
            cur.dailyTokens += totalTokens
            cur
        }!!
        _activeQuota.value = AiQuotaSnapshot(
            dailyRequestsUsed = updated.dailyRequests,
            dailyRequestLimit = dailyRequestLimit,
            dailyTokensUsed = updated.dailyTokens,
            dailyTokenLimit = dailyTokenLimit,
            monthlyRequestsUsed = updated.monthlyRequests,
            monthlyRequestLimit = monthlyRequestLimit,
            isRateLimited = updated.dailyRequests >= dailyRequestLimit || updated.dailyTokens >= dailyTokenLimit
        )
    }

    fun getSnapshotForUser(userId: String): AiQuotaSnapshot {
        val cur = counters[userId.ifBlank { "default" }]
            ?: return AiQuotaSnapshot(
                dailyRequestLimit = dailyRequestLimit,
                dailyTokenLimit = dailyTokenLimit,
                monthlyRequestLimit = monthlyRequestLimit
            )
        return AiQuotaSnapshot(
            dailyRequestsUsed = cur.dailyRequests,
            dailyRequestLimit = dailyRequestLimit,
            dailyTokensUsed = cur.dailyTokens,
            dailyTokenLimit = dailyTokenLimit,
            monthlyRequestsUsed = cur.monthlyRequests,
            monthlyRequestLimit = monthlyRequestLimit,
            isRateLimited = cur.dailyRequests >= dailyRequestLimit || cur.dailyTokens >= dailyTokenLimit
        )
    }

    private fun currentPeriodKeys(): Pair<String, String> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = clockMs()
        }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val monthKey = "%04d-%02d".format(year, month)
        val dayKey = "%04d-%02d-%02d".format(year, month, day)
        return dayKey to monthKey
    }
}
