package com.example.data.ai.orchestrator

data class RoutingPlan(
    val orderedCandidates: List<AiModelConfig>,
    val manualModeBlockedReason: String? = null,
    val recommendedAlternative: AiModelConfig? = null
)

/**
 * Selects and orders compatible, configured, and healthy AI models based on task profile,
 * required capabilities, latency/TTFT health, cost efficiency, and user selection preferences.
 */
class IntelligentModelRouter(
    private val registry: AiModelRegistry,
    private val healthMonitor: ModelHealthMonitor,
    private val adapters: Map<AiProviderType, AiProviderAdapter>
) {

    fun buildRoutingPlan(
        taskProfile: AiTaskProfile,
        extraRequiredCapabilities: Set<AiCapability> = emptySet(),
        estimatedPromptTokens: Int = 512,
        userPreferences: AiUserPreferences = AiUserPreferences(),
        nowMs: Long = System.currentTimeMillis()
    ): RoutingPlan {
        val requiredCaps = taskProfile.requiredCapabilities + extraRequiredCapabilities
        val allConfigured = registry.getAllModels().filter { cfg ->
            cfg.enabled &&
                cfg.contextWindowTokens >= estimatedPromptTokens &&
                adapters[cfg.providerType]?.isConfiguredForModel(cfg) == true
        }

        val compatible = allConfigured.filter { cfg ->
            cfg.capabilities.containsAll(requiredCaps)
        }

        val rankedAllCompatible = scoreAndSort(
            candidates = compatible,
            taskProfile = taskProfile,
            nowMs = nowMs
        )

        val healthyCandidates = rankedAllCompatible.filter { cfg ->
            healthMonitor.getSnapshot(cfg).isAvailableForRouting(nowMs)
        }
        // Only fall back to cooling-down candidates if zero healthy compatible models exist
        val automaticOrder = healthyCandidates.ifEmpty {
            rankedAllCompatible.sortedBy { cfg ->
                healthMonitor.getSnapshot(cfg).cooldownUntilMs
            }
        }

        return when (userPreferences.selectionMode) {
            AiSelectionMode.MANUAL_MODE -> {
                val chosen = registry.getModel(userPreferences.manualModelId)
                val recommended = automaticOrder.firstOrNull { it.modelId != chosen?.modelId }
                    ?: rankedAllCompatible.firstOrNull()

                val unavailableReason = when {
                    chosen == null -> "Selected model (${userPreferences.manualModelId}) is no longer registered."
                    !chosen.enabled -> "${chosen.displayName} is disabled by administrator settings."
                    adapters[chosen.providerType]?.isConfiguredForModel(chosen) != true ->
                        "${chosen.displayName} does not have active provider credentials configured."
                    !chosen.capabilities.containsAll(requiredCaps) -> {
                        val missing = (requiredCaps - chosen.capabilities).joinToString { it.label }
                        "${chosen.displayName} does not support required capabilities ($missing) for ${taskProfile.displayName}."
                    }
                    !healthMonitor.getSnapshot(chosen).isAvailableForRouting(nowMs) -> {
                        val snap = healthMonitor.getSnapshot(chosen)
                        val detail = snap.lastErrorMessage ?: snap.lastErrorCategory?.displayLabel ?: "Cooling down after recent errors"
                        "${chosen.displayName} is temporarily unavailable ($detail)."
                    }
                    else -> null
                }

                if (unavailableReason != null && !userPreferences.autoSwitchOnFailure) {
                    RoutingPlan(
                        orderedCandidates = emptyList(),
                        manualModeBlockedReason = unavailableReason,
                        recommendedAlternative = recommended
                    )
                } else if (unavailableReason != null) {
                    // User allowed auto-switch despite manual selection
                    RoutingPlan(
                        orderedCandidates = automaticOrder,
                        recommendedAlternative = recommended
                    )
                } else if (chosen != null) {
                    val list = if (userPreferences.autoSwitchOnFailure) {
                        listOf(chosen) + automaticOrder.filterNot { it.modelId == chosen.modelId }
                    } else {
                        listOf(chosen)
                    }
                    RoutingPlan(
                        orderedCandidates = list,
                        recommendedAlternative = recommended
                    )
                } else {
                    RoutingPlan(orderedCandidates = automaticOrder)
                }
            }

            AiSelectionMode.PREFERRED_MODEL -> {
                val preferred = compatible.firstOrNull { it.modelId == userPreferences.preferredModelId }
                val preferredHealthy = preferred != null &&
                    healthMonitor.getSnapshot(preferred).isAvailableForRouting(nowMs)
                val ordered = if (preferredHealthy && preferred != null) {
                    listOf(preferred) + automaticOrder.filterNot { it.modelId == preferred.modelId }
                } else {
                    automaticOrder
                }
                RoutingPlan(
                    orderedCandidates = ordered,
                    recommendedAlternative = automaticOrder.firstOrNull()
                )
            }

            AiSelectionMode.AUTOMATIC -> {
                RoutingPlan(
                    orderedCandidates = automaticOrder,
                    recommendedAlternative = automaticOrder.drop(1).firstOrNull()
                )
            }
        }
    }

    fun getModelAvailabilityLabel(
        config: AiModelConfig,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> {
        if (!config.enabled) return false to "Disabled"
        val configured = adapters[config.providerType]?.isConfiguredForModel(config) == true
        if (!configured) return false to "Not Configured"
        val health = healthMonitor.getSnapshot(config)
        return when (health.circuitState) {
            CircuitState.CLOSED -> true to "Available"
            CircuitState.HALF_OPEN -> true to "Recovering"
            CircuitState.OPEN -> {
                if (nowMs >= health.cooldownUntilMs) true to "Recovering"
                else false to "Cooldown"
            }
        }
    }

    private fun scoreAndSort(
        candidates: List<AiModelConfig>,
        taskProfile: AiTaskProfile,
        nowMs: Long
    ): List<AiModelConfig> {
        return candidates.sortedBy { cfg ->
            val snap = healthMonitor.getSnapshot(cfg)
            var penalty = cfg.priority * 100.0

            // Circuit state penalty
            penalty += when {
                snap.circuitState == CircuitState.CLOSED -> 0.0
                snap.circuitState == CircuitState.HALF_OPEN || nowMs >= snap.cooldownUntilMs -> 2500.0
                else -> 50_000.0
            }

            // Preferred capability bonus
            val preferredMatches = cfg.capabilities.intersect(taskProfile.preferredCapabilities).size
            penalty -= preferredMatches * 80.0

            // Profile-specific tuning
            when (taskProfile) {
                AiTaskProfile.SIMPLE_CHAT, AiTaskProfile.VOICE_LOW_LATENCY -> {
                    if (cfg.speedRating == "Instant" || cfg.speedRating == "Ultra-Fast") penalty -= 120.0
                    penalty += (snap.avgTtftMs / 50.0).coerceAtMost(400.0)
                    penalty += cfg.costPer1kOutputUsd * 50_000.0
                }
                AiTaskProfile.COMPLEX_REASONING -> {
                    if (AiCapability.REASONING in cfg.capabilities) penalty -= 350.0
                    if (cfg.speedRating == "Precision") penalty -= 150.0
                }
                AiTaskProfile.TOOL_AUTOMATION -> {
                    if (AiCapability.TOOL_CALLING in cfg.capabilities) penalty -= 250.0
                    if (snap.toolFailureCount > 0) penalty += snap.toolFailureCount * 600.0
                }
                AiTaskProfile.WEB_GROUNDING -> {
                    if (AiCapability.WEB_GROUNDING in cfg.capabilities) penalty -= 600.0
                }
                AiTaskProfile.STRUCTURED_JSON -> {
                    if (AiCapability.STRUCTURED_JSON in cfg.capabilities) penalty -= 250.0
                }
                AiTaskProfile.VISION_ANALYSIS, AiTaskProfile.AUDIO_TRANSCRIPTION -> {
                    if (snap.avgLatencyMs > 0) penalty += (snap.avgLatencyMs / 100.0).coerceAtMost(300.0)
                }
            }

            // Penalize recent failure rate
            if (snap.totalRequests >= 3) {
                val failureRate = snap.failedRequests.toDouble() / snap.totalRequests.toDouble()
                penalty += failureRate * 3_000.0
            }
            penalty += snap.consecutiveFailures * 800.0
            penalty
        }
    }
}
