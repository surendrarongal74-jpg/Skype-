package com.example.data.ai.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Thread-safe sliding/EMA health & circuit-breaker monitor across all registered AI models.
 * Handles concurrent turns safely without race conditions, applies exponential backoff + jitter
 * on outages and HTTP 429 rate limits, and transitions circuits cleanly through HALF_OPEN recovery.
 */
class ModelHealthMonitor(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val jitterFactorProvider: () -> Double = { Random.nextDouble(0.85, 1.15) }
) {
    private val statsByModel = ConcurrentHashMap<String, AiModelHealthSnapshot>()

    private val _healthSnapshots = MutableStateFlow<Map<String, AiModelHealthSnapshot>>(emptyMap())
    val healthSnapshots: StateFlow<Map<String, AiModelHealthSnapshot>> = _healthSnapshots.asStateFlow()

    fun getSnapshot(config: AiModelConfig): AiModelHealthSnapshot {
        val now = clockMs()
        val snap = statsByModel.computeIfAbsent(config.modelId) {
            AiModelHealthSnapshot(
                modelId = config.modelId,
                displayName = config.displayName,
                providerType = config.providerType,
                lastUpdatedMs = now
            )
        }
        return if (snap.circuitState == CircuitState.OPEN && now >= snap.cooldownUntilMs) {
            snap.copy(circuitState = CircuitState.HALF_OPEN, lastUpdatedMs = now)
        } else {
            snap
        }
    }

    fun ensureInitializedFor(configs: List<AiModelConfig>) {
        val now = clockMs()
        configs.forEach { cfg ->
            statsByModel.computeIfAbsent(cfg.modelId) {
                AiModelHealthSnapshot(
                    modelId = cfg.modelId,
                    displayName = cfg.displayName,
                    providerType = cfg.providerType,
                    lastUpdatedMs = now
                )
            }
        }
        publishSnapshots()
    }

    fun recordSuccess(
        config: AiModelConfig,
        latencyMs: Long,
        ttftMs: Long
    ) {
        val now = clockMs()
        statsByModel.compute(config.modelId) { _, existing ->
            val cur = existing ?: AiModelHealthSnapshot(
                modelId = config.modelId,
                displayName = config.displayName,
                providerType = config.providerType
            )
            val newTotal = cur.totalRequests + 1
            val newSuccess = cur.successfulRequests + 1
            val blendedLatency = if (cur.avgLatencyMs <= 0L) latencyMs
            else ((cur.avgLatencyMs * 0.7) + (latencyMs * 0.3)).toLong()
            val blendedTtft = when {
                ttftMs <= 0L -> cur.avgTtftMs
                cur.avgTtftMs <= 0L -> ttftMs
                else -> ((cur.avgTtftMs * 0.7) + (ttftMs * 0.3)).toLong()
            }
            cur.copy(
                displayName = config.displayName,
                providerType = config.providerType,
                circuitState = CircuitState.CLOSED,
                totalRequests = newTotal,
                successfulRequests = newSuccess,
                avgLatencyMs = blendedLatency,
                avgTtftMs = blendedTtft,
                consecutiveFailures = 0,
                cooldownUntilMs = 0L,
                lastUpdatedMs = now
            )
        }
        publishSnapshots()
    }

    fun recordFailure(
        config: AiModelConfig,
        exception: AiProviderException,
        triggeredFailover: Boolean
    ) {
        val now = clockMs()
        statsByModel.compute(config.modelId) { _, existing ->
            val cur = existing ?: AiModelHealthSnapshot(
                modelId = config.modelId,
                displayName = config.displayName,
                providerType = config.providerType
            )
            val failures = cur.consecutiveFailures + 1
            val shouldOpenImmediately = exception.category in setOf(
                AiFailureCategory.RATE_LIMIT_429,
                AiFailureCategory.AUTH_OR_KEY_ERROR,
                AiFailureCategory.PAYMENT_OR_QUOTA,
                AiFailureCategory.MODEL_NOT_FOUND
            )
            val openCircuit = cur.circuitState == CircuitState.HALF_OPEN ||
                shouldOpenImmediately ||
                failures >= FAILURE_THRESHOLD_OPEN

            val cooldownMs = if (openCircuit) {
                computeCooldownMs(exception, failures)
            } else {
                0L
            }

            cur.copy(
                displayName = config.displayName,
                providerType = config.providerType,
                circuitState = if (openCircuit) CircuitState.OPEN else cur.circuitState,
                totalRequests = cur.totalRequests + 1,
                failedRequests = cur.failedRequests + 1,
                rateLimit429Count = cur.rateLimit429Count + if (exception.category == AiFailureCategory.RATE_LIMIT_429) 1 else 0,
                timeoutCount = cur.timeoutCount + if (exception.category == AiFailureCategory.TIMEOUT) 1 else 0,
                streamErrorCount = cur.streamErrorCount + if (exception.category == AiFailureCategory.STREAM_INTERRUPTED) 1 else 0,
                toolFailureCount = cur.toolFailureCount + if (exception.category == AiFailureCategory.TOOL_CALL_UNSUPPORTED) 1 else 0,
                invalidResponseCount = cur.invalidResponseCount + if (exception.category == AiFailureCategory.INVALID_RESPONSE) 1 else 0,
                failoverOutCount = cur.failoverOutCount + if (triggeredFailover) 1 else 0,
                consecutiveFailures = failures,
                cooldownUntilMs = if (openCircuit) now + cooldownMs else cur.cooldownUntilMs,
                lastErrorCategory = exception.category,
                lastErrorMessage = exception.message.take(180),
                lastUpdatedMs = now
            )
        }
        publishSnapshots()
    }

    fun resetCircuit(modelId: String) {
        val now = clockMs()
        statsByModel.computeIfPresent(modelId) { _, cur ->
            cur.copy(
                circuitState = CircuitState.CLOSED,
                consecutiveFailures = 0,
                cooldownUntilMs = 0L,
                lastUpdatedMs = now
            )
        }
        publishSnapshots()
    }

    /**
     * When the user explicitly taps Retry, transition OPEN circuits to HALF_OPEN probes
     * and clear cooldown locks so recovered providers can be re-tested immediately.
     */
    fun resetCooldownsForExplicitRetry() {
        val now = clockMs()
        statsByModel.keys.forEach { id ->
            statsByModel.computeIfPresent(id) { _, cur ->
                if (cur.circuitState == CircuitState.OPEN || cur.cooldownUntilMs > now) {
                    cur.copy(
                        circuitState = CircuitState.HALF_OPEN,
                        cooldownUntilMs = 0L,
                        lastUpdatedMs = now
                    )
                } else {
                    cur
                }
            }
        }
        publishSnapshots()
    }

    private fun computeCooldownMs(exception: AiProviderException, consecutiveFailures: Int): Long {
        if (exception.retryAfterMs != null && exception.retryAfterMs > 0L) {
            return exception.retryAfterMs.coerceIn(5_000L, MAX_COOLDOWN_MS)
        }
        val baseMs = when (exception.category) {
            AiFailureCategory.AUTH_OR_KEY_ERROR,
            AiFailureCategory.PAYMENT_OR_QUOTA,
            AiFailureCategory.MODEL_NOT_FOUND -> 120_000L
            AiFailureCategory.RATE_LIMIT_429 -> 20_000L
            AiFailureCategory.SERVER_ERROR_5XX,
            AiFailureCategory.TIMEOUT,
            AiFailureCategory.STREAM_INTERRUPTED -> 12_000L
            AiFailureCategory.TOOL_CALL_UNSUPPORTED -> 25_000L
            else -> 10_000L
        }
        val exponent = (consecutiveFailures - 1).coerceIn(0, 4)
        val raw = min(MAX_COOLDOWN_MS.toDouble(), baseMs * 2.0.pow(exponent.toDouble()))
        val jittered = (raw * jitterFactorProvider().coerceIn(0.80, 1.25)).toLong()
        return jittered.coerceIn(5_000L, MAX_COOLDOWN_MS)
    }

    private fun publishSnapshots() {
        val now = clockMs()
        val resolved = statsByModel.mapValues { (_, snap) ->
            if (snap.circuitState == CircuitState.OPEN && now >= snap.cooldownUntilMs) {
                snap.copy(circuitState = CircuitState.HALF_OPEN)
            } else {
                snap
            }
        }
        _healthSnapshots.value = resolved
    }

    companion object {
        private const val FAILURE_THRESHOLD_OPEN = 2
        private const val MAX_COOLDOWN_MS = 300_000L
    }
}
