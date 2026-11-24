package com.example.services

import com.example.data.local.dao.PersonalityProfileDao
import com.example.data.local.entity.PersonalityProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PersonalityPresetMode(
    val displayName: String,
    val subtitle: String,
    val defaultWarmth: Int,
    val defaultConciseness: Int,
    val defaultProactivity: Int
) {
    BALANCED_COMPANION(
        "Intelligent Companion + OS Agent",
        "Natural conversational companion, calm execution clarity, futuristic poise",
        defaultWarmth = 75,
        defaultConciseness = 65,
        defaultProactivity = 65
    ),
    EXECUTIVE_AGENT(
        "Precision Execution Agent",
        "Crisp, structured action updates and high signal-to-noise synthesis",
        defaultWarmth = 45,
        defaultConciseness = 88,
        defaultProactivity = 55
    ),
    WARM_COMPANION(
        "Adaptive Personal Companion",
        "Empathetic, context-aware day-to-day partner across text and voice",
        defaultWarmth = 92,
        defaultConciseness = 52,
        defaultProactivity = 78
    ),
    FUTURISTIC_OS(
        "Futuristic Digital Identity",
        "Ambient intelligent OS cadence with transparent step narration",
        defaultWarmth = 64,
        defaultConciseness = 74,
        defaultProactivity = 80
    )
}

/**
 * Manages ALLORA's configurable personality profile strictly decoupled from PermissionService.
 * Changing personality tone or proactivity NEVER modifies execution permissions or approval gates.
 */
class PersonalityService(
    private val profileDao: PersonalityProfileDao,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    fun observeProfile(userId: String): Flow<PersonalityProfileEntity> =
        profileDao.observeProfile(userId).map { existing ->
            existing ?: PersonalityProfileEntity(userId = userId)
        }

    suspend fun getProfile(userId: String): PersonalityProfileEntity {
        if (userId.isBlank()) return PersonalityProfileEntity(userId = "")
        return profileDao.getProfile(userId) ?: PersonalityProfileEntity(userId = userId).also {
            profileDao.upsertProfile(it)
            runCatching { cloudSync?.syncPersonalityProfile(it) }
        }
    }

    suspend fun updateProfile(
        userId: String,
        presetMode: PersonalityPresetMode? = null,
        warmthLevel: Int? = null,
        concisenessLevel: Int? = null,
        proactivityLevel: Int? = null,
        explanationTransparency: Boolean? = null,
        voiceConsistencyEnabled: Boolean? = null,
        customSignatureNote: String? = null
    ): Result<PersonalityProfileEntity> {
        if (userId.isBlank()) {
            return Result.failure(SecurityException("Authenticated userId is required."))
        }
        val current = getProfile(userId)
        val nextPreset = presetMode?.name ?: current.presetMode
        val sanitizedNote = customSignatureNote?.let {
            SecurityAuditService.redactSensitiveFields(it.trim()).take(300)
        } ?: current.customSignatureNote

        val updated = current.copy(
            presetMode = nextPreset,
            warmthLevel = (warmthLevel ?: presetMode?.defaultWarmth ?: current.warmthLevel).coerceIn(0, 100),
            concisenessLevel = (concisenessLevel ?: presetMode?.defaultConciseness ?: current.concisenessLevel).coerceIn(0, 100),
            proactivityLevel = (proactivityLevel ?: presetMode?.defaultProactivity ?: current.proactivityLevel).coerceIn(0, 100),
            explanationTransparency = explanationTransparency ?: current.explanationTransparency,
            voiceConsistencyEnabled = voiceConsistencyEnabled ?: current.voiceConsistencyEnabled,
            customSignatureNote = sanitizedNote,
            updatedAt = System.currentTimeMillis()
        )
        profileDao.upsertProfile(updated)
        runCatching { cloudSync?.syncPersonalityProfile(updated) }
        return Result.success(updated)
    }

    suspend fun buildPersonalitySystemDirective(userId: String): String {
        val profile = getProfile(userId)
        return buildDirectiveFromEntity(profile)
    }

    suspend fun wipeLocalProfile(userId: String) {
        profileDao.deleteAllForUser(userId)
    }

    companion object {
        fun buildDirectiveFromEntity(profile: PersonalityProfileEntity): String {
            val preset = PersonalityPresetMode.entries.firstOrNull { it.name == profile.presetMode }
                ?: PersonalityPresetMode.BALANCED_COMPANION
            val warmthLabel = when {
                profile.warmthLevel >= 75 -> "warm, supportive, and conversational"
                profile.warmthLevel >= 45 -> "balanced, respectful, and natural"
                else -> "crisp, direct, and matter-of-fact"
            }
            val concisenessLabel = when {
                profile.concisenessLevel >= 75 -> "concise and high-density"
                profile.concisenessLevel >= 45 -> "clear with helpful context"
                else -> "thorough with complete step-by-step detail"
            }
            val proactivityLabel = when {
                profile.proactivityLevel >= 75 -> "proactively surface useful next steps without acting without permission"
                profile.proactivityLevel >= 45 -> "offer gentle relevant next steps when helpful"
                else -> "respond strictly to the explicit request"
            }

            return buildString {
                appendLine("ALLORA PERSONALITY & BEHAVIOR DIRECTIVE (${preset.displayName}):")
                appendLine("- Identity blend: Intelligent personal companion + professional task-execution agent + futuristic digital operating environment.")
                appendLine("- Tone & pacing: Be $warmthLabel; keep responses $concisenessLabel; $proactivityLabel.")
                if (profile.explanationTransparency) {
                    appendLine("- Transparency: Clearly explain what step or tool is running, stay calm during errors, and remain honest about uncertainty.")
                }
                if (profile.voiceConsistencyEnabled) {
                    appendLine("- Cross-modal continuity: Keep the exact same identity, awareness, and context across text and voice.")
                }
                appendLine("- Honesty invariant: Never claim a task, booking, email, download, or payment completed unless verified tool output confirms it. Personality preferences never override execution permissions.")
                if (profile.customSignatureNote.isNotBlank()) {
                    appendLine("- Owner style preference: ${profile.customSignatureNote}")
                }
            }
        }
    }
}
