package com.example.services

import com.example.data.local.dao.PermissionPolicyDao
import com.example.data.local.entity.PermissionPolicyEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class PermissionLevel(val label: String) {
    ALWAYS_ASK("Always Ask"),
    LIMITED_AUTONOMY("Limited Autonomy"),
    MANUAL_APPROVAL_REQUIRED("Manual Approval Required");

    fun tierNumber(): Int = when (this) {
        LIMITED_AUTONOMY -> 1
        ALWAYS_ASK -> 2
        MANUAL_APPROVAL_REQUIRED -> 3
    }

    fun tierLabel(): String = when (this) {
        LIMITED_AUTONOMY -> "Level 1 • Automatic"
        ALWAYS_ASK -> "Level 2 • Confirm First"
        MANUAL_APPROVAL_REQUIRED -> "Level 3 • Explicit + Controls"
    }

    fun tierDescription(): String = when (this) {
        LIMITED_AUTONOMY -> "Safe read-only/internal actions execute automatically within caps."
        ALWAYS_ASK -> "ALLORA asks for owner confirmation before executing each action."
        MANUAL_APPROVAL_REQUIRED -> "Explicit per-action approval plus spending/domain extra controls."
    }

    companion object {
        fun fromString(raw: String?): PermissionLevel =
            entries.firstOrNull { it.name == raw } ?: MANUAL_APPROVAL_REQUIRED
    }
}

enum class AgentCapability(val label: String) {
    WALLET_ACCESS("Wallet Access"),
    PURCHASE("Purchases & Payments"),
    EMAIL_READ("Read Emails"),
    EMAIL_SEND("Send Emails"),
    BROWSER_ACCESS("Browser Navigation"),
    WEBSITE_LOGIN("Website Logins"),
    FILE_ACCESS("Workspace Files & Uploads"),
    BOOKING("Bookings & Reservations"),
    AUTONOMOUS_TASK("Autonomous Task Engine")
}

data class PermissionPolicyState(
    val userId: String = "",
    val walletAccess: PermissionLevel = PermissionLevel.MANUAL_APPROVAL_REQUIRED,
    val purchasePermission: PermissionLevel = PermissionLevel.MANUAL_APPROVAL_REQUIRED,
    val emailRead: PermissionLevel = PermissionLevel.ALWAYS_ASK,
    val emailSend: PermissionLevel = PermissionLevel.MANUAL_APPROVAL_REQUIRED,
    val browserAccess: PermissionLevel = PermissionLevel.ALWAYS_ASK,
    val websiteLogin: PermissionLevel = PermissionLevel.MANUAL_APPROVAL_REQUIRED,
    val fileAccess: PermissionLevel = PermissionLevel.ALWAYS_ASK,
    val bookingPermission: PermissionLevel = PermissionLevel.MANUAL_APPROVAL_REQUIRED,
    val autonomousTaskPermission: PermissionLevel = PermissionLevel.LIMITED_AUTONOMY,
    val maxTxAmountMinor: Long = 25_000L,
    val killSwitchActive: Boolean = false,
    val revokedDomains: Set<String> = emptySet(),
    val updatedAtMs: Long = 0L
) {
    fun levelFor(capability: AgentCapability): PermissionLevel = when (capability) {
        AgentCapability.WALLET_ACCESS -> walletAccess
        AgentCapability.PURCHASE -> purchasePermission
        AgentCapability.EMAIL_READ -> emailRead
        AgentCapability.EMAIL_SEND -> emailSend
        AgentCapability.BROWSER_ACCESS -> browserAccess
        AgentCapability.WEBSITE_LOGIN -> websiteLogin
        AgentCapability.FILE_ACCESS -> fileAccess
        AgentCapability.BOOKING -> bookingPermission
        AgentCapability.AUTONOMOUS_TASK -> autonomousTaskPermission
    }
}

sealed interface PermissionDecision {
    data object Allowed : PermissionDecision
    data class RequiresOwnerApproval(val reason: String) : PermissionDecision
    data class Blocked(val reason: String) : PermissionDecision
}

class PermissionService(
    private val policyDao: PermissionPolicyDao,
    private val auditService: SecurityAuditService,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    private val _activePolicy = MutableStateFlow(PermissionPolicyState())
    val activePolicy: StateFlow<PermissionPolicyState> = _activePolicy.asStateFlow()

    fun observePolicy(userId: String): Flow<PermissionPolicyEntity?> = policyDao.observePolicy(userId)

    suspend fun loadOrInitialize(userId: String): PermissionPolicyState {
        val existing = policyDao.getPolicy(userId)
        val resolved = if (existing != null) {
            existing.toState()
        } else {
            val defaultEntity = PermissionPolicyEntity(userId = userId)
            policyDao.upsertPolicy(defaultEntity)
            runCatching { cloudSync?.syncPermissionPolicy(defaultEntity) }
            defaultEntity.toState()
        }
        _activePolicy.value = resolved
        return resolved
    }

    suspend fun updateCapabilityLevel(
        userId: String,
        capability: AgentCapability,
        level: PermissionLevel
    ): PermissionPolicyState {
        val cur = getPolicyState(userId)
        val updated = when (capability) {
            AgentCapability.WALLET_ACCESS -> cur.copy(walletAccess = level)
            AgentCapability.PURCHASE -> cur.copy(purchasePermission = level)
            AgentCapability.EMAIL_READ -> cur.copy(emailRead = level)
            AgentCapability.EMAIL_SEND -> cur.copy(emailSend = level)
            AgentCapability.BROWSER_ACCESS -> cur.copy(browserAccess = level)
            AgentCapability.WEBSITE_LOGIN -> cur.copy(websiteLogin = level)
            AgentCapability.FILE_ACCESS -> cur.copy(fileAccess = level)
            AgentCapability.BOOKING -> cur.copy(bookingPermission = level)
            AgentCapability.AUTONOMOUS_TASK -> cur.copy(autonomousTaskPermission = level)
        }.copy(updatedAtMs = System.currentTimeMillis())

        persistAndNotify(updated)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.PERMISSION,
            action = "UPDATE_${capability.name}",
            outcome = AuditOutcome.VERIFIED,
            detail = "Owner set ${capability.label} to ${level.label}."
        )
        return updated
    }

    suspend fun updateMaxTransactionLimit(userId: String, maxTxAmountMinor: Long): PermissionPolicyState {
        val cur = getPolicyState(userId)
        val clamped = maxTxAmountMinor.coerceIn(0L, 100_000_000L)
        val updated = cur.copy(maxTxAmountMinor = clamped, updatedAtMs = System.currentTimeMillis())
        persistAndNotify(updated)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.PERMISSION,
            action = "SET_MAX_TX_LIMIT",
            outcome = AuditOutcome.VERIFIED,
            detail = "Updated per-transaction permission threshold to $clamped minor units."
        )
        return updated
    }

    suspend fun setKillSwitch(userId: String, active: Boolean): PermissionPolicyState {
        val cur = getPolicyState(userId)
        val updated = cur.copy(killSwitchActive = active, updatedAtMs = System.currentTimeMillis())
        persistAndNotify(updated)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.PERMISSION,
            action = if (active) "KILL_SWITCH_ENGAGED" else "KILL_SWITCH_RELEASED",
            outcome = if (active) AuditOutcome.REVOKED else AuditOutcome.VERIFIED,
            detail = if (active) "Owner engaged Stop-All kill switch. Autonomous actions blocked immediately." else "Owner released Stop-All kill switch."
        )
        return updated
    }

    suspend fun revokeDomain(userId: String, domain: String): PermissionPolicyState {
        val normalized = normalizeDomain(domain)
        val cur = getPolicyState(userId)
        val updated = cur.copy(
            revokedDomains = (cur.revokedDomains + normalized).filter { it.isNotBlank() }.toSet(),
            updatedAtMs = System.currentTimeMillis()
        )
        persistAndNotify(updated)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.PERMISSION,
            action = "REVOKE_DOMAIN",
            outcome = AuditOutcome.REVOKED,
            detail = "Revoked autonomous browser/login access for $normalized."
        )
        return updated
    }

    suspend fun restoreDomain(userId: String, domain: String): PermissionPolicyState {
        val normalized = normalizeDomain(domain)
        val cur = getPolicyState(userId)
        val updated = cur.copy(
            revokedDomains = cur.revokedDomains - normalized,
            updatedAtMs = System.currentTimeMillis()
        )
        persistAndNotify(updated)
        return updated
    }

    /**
     * Evaluates an agent action strictly against the owner's live permission policy.
     * Any revocation or kill switch takes effect immediately for future actions.
     */
    suspend fun evaluateAction(
        userId: String,
        capability: AgentCapability,
        amountMinor: Long = 0L,
        domain: String? = null
    ): PermissionDecision {
        val policy = getPolicyState(userId)

        if (policy.killSwitchActive) {
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.PERMISSION,
                action = "BLOCKED_BY_KILL_SWITCH",
                outcome = AuditOutcome.BLOCKED,
                detail = "Blocked ${capability.label} because owner Stop-All kill switch is active."
            )
            return PermissionDecision.Blocked("Blocked by Owner Kill Switch: Stop-All safety lock is active.")
        }

        if (!domain.isNullOrBlank()) {
            val normalized = normalizeDomain(domain)
            if (normalized in policy.revokedDomains) {
                auditService.recordEvent(
                    userId = userId,
                    category = AuditCategory.PERMISSION,
                    action = "BLOCKED_REVOKED_DOMAIN",
                    outcome = AuditOutcome.BLOCKED,
                    detail = "Blocked ${capability.label} for revoked domain $normalized."
                )
                return PermissionDecision.Blocked("Access to domain '$normalized' was revoked by owner.")
            }
        }

        if (capability == AgentCapability.PURCHASE || capability == AgentCapability.BOOKING) {
            if (amountMinor > policy.maxTxAmountMinor && policy.maxTxAmountMinor > 0L) {
                return PermissionDecision.RequiresOwnerApproval(
                    "Transaction amount exceeds configured permission ceiling (${policy.maxTxAmountMinor / 100.0})."
                )
            }
            // Purchases always require explicit per-transaction owner confirmation
            if (capability == AgentCapability.PURCHASE) {
                return PermissionDecision.RequiresOwnerApproval(
                    "ALLORA requires explicit owner authorization with full price breakdown before spending."
                )
            }
        }

        if (capability == AgentCapability.WEBSITE_LOGIN) {
            return PermissionDecision.Blocked(
                "ALLORA never submits passwords or bypasses logins autonomously. Owner manual login in Browser is required."
            )
        }

        return when (policy.levelFor(capability)) {
            PermissionLevel.LIMITED_AUTONOMY -> PermissionDecision.Allowed
            PermissionLevel.ALWAYS_ASK -> PermissionDecision.RequiresOwnerApproval(
                "${capability.label} is set to 'Always Ask'. Owner confirmation is required."
            )
            PermissionLevel.MANUAL_APPROVAL_REQUIRED -> PermissionDecision.RequiresOwnerApproval(
                "${capability.label} requires explicit manual approval in Permission Center."
            )
        }
    }

    suspend fun clearLocalUserCache(userId: String) {
        policyDao.deleteForUser(userId)
        if (_activePolicy.value.userId == userId) {
            _activePolicy.value = PermissionPolicyState()
        }
    }

    private suspend fun getPolicyState(userId: String): PermissionPolicyState {
        val current = _activePolicy.value
        if (current.userId == userId) return current
        return loadOrInitialize(userId)
    }

    private suspend fun persistAndNotify(state: PermissionPolicyState) {
        val entity = state.toEntity()
        policyDao.upsertPolicy(entity)
        _activePolicy.value = state
        runCatching { cloudSync?.syncPermissionPolicy(entity) }
    }

    private fun PermissionPolicyEntity.toState(): PermissionPolicyState = PermissionPolicyState(
        userId = userId,
        walletAccess = PermissionLevel.fromString(walletAccess),
        purchasePermission = PermissionLevel.fromString(purchasePermission),
        emailRead = PermissionLevel.fromString(emailRead),
        emailSend = PermissionLevel.fromString(emailSend),
        browserAccess = PermissionLevel.fromString(browserAccess),
        websiteLogin = PermissionLevel.fromString(websiteLogin),
        fileAccess = PermissionLevel.fromString(fileAccess),
        bookingPermission = PermissionLevel.fromString(bookingPermission),
        autonomousTaskPermission = PermissionLevel.fromString(autonomousTaskPermission),
        maxTxAmountMinor = maxTxAmountMinor,
        killSwitchActive = killSwitchActive,
        revokedDomains = revokedDomainsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
        updatedAtMs = updatedAt
    )

    private fun PermissionPolicyState.toEntity(): PermissionPolicyEntity = PermissionPolicyEntity(
        userId = userId,
        walletAccess = walletAccess.name,
        purchasePermission = purchasePermission.name,
        emailRead = emailRead.name,
        emailSend = emailSend.name,
        browserAccess = browserAccess.name,
        websiteLogin = websiteLogin.name,
        fileAccess = fileAccess.name,
        bookingPermission = bookingPermission.name,
        autonomousTaskPermission = autonomousTaskPermission.name,
        maxTxAmountMinor = maxTxAmountMinor,
        killSwitchActive = killSwitchActive,
        revokedDomainsCsv = revokedDomains.joinToString(",").take(2000),
        updatedAt = updatedAtMs
    )

    companion object {
        fun normalizeDomain(raw: String): String =
            raw.trim()
                .lowercase(Locale.ROOT)
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .substringBefore("?")
                .removePrefix("www.")
    }
}
