package com.example.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class IdentitySessionStatus {
    CONNECTED,
    EXPIRED,
    REAUTH_REQUIRED,
    DISCONNECTED
}

data class OAuthIdentityRecord(
    val userId: String,
    val providerId: String,
    val accountLabel: String,
    val grantedScopes: List<String>,
    val status: IdentitySessionStatus,
    val connectedAtMs: Long,
    val expiresAtMs: Long,
    val statusNote: String = ""
) {
    fun isCurrentlyValid(nowMs: Long = System.currentTimeMillis()): Boolean =
        status == IdentitySessionStatus.CONNECTED && (expiresAtMs <= 0L || nowMs < expiresAtMs)
}

data class WebsiteAuthProfile(
    val userId: String,
    val domain: String,
    val label: String,
    val status: IdentitySessionStatus,
    val lastVerifiedUrl: String,
    val lastVerifiedAtMs: Long,
    val expiresAtMs: Long = 0L,
    val profileKey: String = "uid_${UserWorkspaceService.sanitizeUidSegment(userId)}"
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        status == IdentitySessionStatus.EXPIRED ||
            status == IdentitySessionStatus.REAUTH_REQUIRED ||
            (expiresAtMs > 0L && nowMs >= expiresAtMs)
}

data class UserIdentitySnapshot(
    val userId: String,
    val profileKey: String,
    val oauthSessions: List<OAuthIdentityRecord> = emptyList(),
    val websiteSessions: List<WebsiteAuthProfile> = emptyList()
)

/**
 * Dedicated per-UID identity & authentication lifecycle coordinator (Module 3).
 * Tracks OAuth integrations, authorized website sessions, expiry detection, restoration,
 * and clean disconnection without ever persisting passwords, PINs, OTPs, or raw bearer tokens.
 */
class IdentityAuthService(
    private val browserSessionService: BrowserSessionService,
    private val auditService: SecurityAuditService,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val oauthRecords = ConcurrentHashMap<String, MutableMap<String, OAuthIdentityRecord>>()
    private val websiteSessions = ConcurrentHashMap<String, MutableMap<String, WebsiteAuthProfile>>()

    private val _identitySnapshot = MutableStateFlow<UserIdentitySnapshot?>(null)
    val identitySnapshot: StateFlow<UserIdentitySnapshot?> = _identitySnapshot.asStateFlow()

    suspend fun registerOAuthConnection(
        userId: String,
        providerId: String,
        accountLabel: String,
        grantedScopes: List<String>,
        expiresAtMs: Long = 0L
    ): OAuthIdentityRecord {
        val now = timeProvider()
        val normalizedProvider = providerId.trim().lowercase(Locale.ROOT)
        val record = OAuthIdentityRecord(
            userId = userId,
            providerId = normalizedProvider,
            accountLabel = SecurityAuditService.redactSensitiveFields(accountLabel).take(160),
            grantedScopes = grantedScopes.distinct(),
            status = IdentitySessionStatus.CONNECTED,
            connectedAtMs = now,
            expiresAtMs = expiresAtMs,
            statusNote = "Authorized OAuth session active."
        )
        val map = oauthRecords.computeIfAbsent(userId) { ConcurrentHashMap() }
        map[normalizedProvider] = record
        refreshSnapshot(userId)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "IDENTITY_OAUTH_CONNECTED",
            outcome = AuditOutcome.VERIFIED,
            detail = "Connected $normalizedProvider (${record.accountLabel}) with ${record.grantedScopes.size} scope(s)."
        )
        return record
    }

    suspend fun checkOAuthValidity(userId: String, providerId: String): IdentitySessionStatus {
        val normalized = providerId.trim().lowercase(Locale.ROOT)
        val rec = oauthRecords[userId]?.get(normalized) ?: return IdentitySessionStatus.DISCONNECTED
        val now = timeProvider()
        if (rec.expiresAtMs in 1..now && rec.status == IdentitySessionStatus.CONNECTED) {
            val expired = rec.copy(
                status = IdentitySessionStatus.EXPIRED,
                statusNote = "OAuth session expired; owner re-authentication required."
            )
            oauthRecords[userId]?.put(normalized, expired)
            refreshSnapshot(userId)
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.AUTH,
                action = "IDENTITY_OAUTH_EXPIRED",
                outcome = AuditOutcome.REVOKED,
                detail = "Detected expired OAuth session for $normalized."
            )
            return IdentitySessionStatus.EXPIRED
        }
        return rec.status
    }

    suspend fun markOAuthExpired(userId: String, providerId: String, reason: String) {
        val normalized = providerId.trim().lowercase(Locale.ROOT)
        val map = oauthRecords.computeIfAbsent(userId) { ConcurrentHashMap() }
        val existing = map[normalized]
        val updated = existing?.copy(
            status = IdentitySessionStatus.EXPIRED,
            statusNote = SecurityAuditService.redactSensitiveFields(reason).take(240)
        ) ?: OAuthIdentityRecord(
            userId = userId,
            providerId = normalized,
            accountLabel = "",
            grantedScopes = emptyList(),
            status = IdentitySessionStatus.EXPIRED,
            connectedAtMs = 0L,
            expiresAtMs = timeProvider(),
            statusNote = SecurityAuditService.redactSensitiveFields(reason).take(240)
        )
        map[normalized] = updated
        refreshSnapshot(userId)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "IDENTITY_OAUTH_EXPIRED",
            outcome = AuditOutcome.REVOKED,
            detail = "Marked $normalized session expired: ${updated.statusNote}"
        )
    }

    suspend fun disconnectOAuthProvider(userId: String, providerId: String) {
        val normalized = providerId.trim().lowercase(Locale.ROOT)
        oauthRecords[userId]?.remove(normalized)
        refreshSnapshot(userId)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "IDENTITY_OAUTH_DISCONNECTED",
            outcome = AuditOutcome.REVOKED,
            detail = "Disconnected OAuth provider $normalized for UID workspace."
        )
    }

    suspend fun registerAuthorizedWebsiteSession(
        userId: String,
        url: String,
        label: String,
        expiresAtMs: Long = 0L
    ): WebsiteAuthProfile {
        val domain = PermissionService.normalizeDomain(url)
        val now = timeProvider()
        val safeUrl = BrowserSessionService.sanitizeUrlForDiagnostic(url)
        val profile = WebsiteAuthProfile(
            userId = userId,
            domain = domain,
            label = SecurityAuditService.redactSensitiveFields(label).ifBlank { domain }.take(160),
            status = IdentitySessionStatus.CONNECTED,
            lastVerifiedUrl = safeUrl,
            lastVerifiedAtMs = now,
            expiresAtMs = expiresAtMs
        )
        val map = websiteSessions.computeIfAbsent(userId) { ConcurrentHashMap() }
        map[domain] = profile
        browserSessionService.registerConnectedSession(userId, domain, profile.label, active = true)
        refreshSnapshot(userId)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "WEBSITE_SESSION_AUTHORIZED",
            outcome = AuditOutcome.VERIFIED,
            detail = "Verified website session on $domain (${profile.profileKey})."
        )
        return profile
    }

    suspend fun markWebsiteSessionExpired(userId: String, urlOrDomain: String, reason: String) {
        val domain = PermissionService.normalizeDomain(urlOrDomain)
        val map = websiteSessions.computeIfAbsent(userId) { ConcurrentHashMap() }
        val cur = map[domain]
        val updated = (cur ?: WebsiteAuthProfile(
            userId = userId,
            domain = domain,
            label = domain,
            status = IdentitySessionStatus.EXPIRED,
            lastVerifiedUrl = BrowserSessionService.sanitizeUrlForDiagnostic(urlOrDomain),
            lastVerifiedAtMs = timeProvider()
        )).copy(status = IdentitySessionStatus.EXPIRED)
        map[domain] = updated
        browserSessionService.registerConnectedSession(userId, domain, "$domain ($reason)", active = false)
        refreshSnapshot(userId)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "WEBSITE_SESSION_EXPIRED",
            outcome = AuditOutcome.REVOKED,
            detail = "Website session expired on $domain: ${SecurityAuditService.redactSensitiveFields(reason)}"
        )
    }

    suspend fun disconnectWebsiteSession(userId: String, domain: String) {
        val normalized = PermissionService.normalizeDomain(domain)
        websiteSessions[userId]?.remove(normalized)
        browserSessionService.registerConnectedSession(userId, normalized, "Disconnected by owner", active = false)
        refreshSnapshot(userId)
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "WEBSITE_SESSION_DISCONNECTED",
            outcome = AuditOutcome.REVOKED,
            detail = "Disconnected website session on $normalized."
        )
    }

    suspend fun detectWebsiteSessionExpiry(
        userId: String,
        url: String,
        title: String,
        visibleSnippet: String
    ): Boolean {
        val domain = PermissionService.normalizeDomain(url)
        val combined = "${url.lowercase(Locale.ROOT)} ${title.lowercase(Locale.ROOT)} ${visibleSnippet.lowercase(Locale.ROOT)}"
        val expiryHints = listOf(
            "session expired",
            "your session has expired",
            "token expired",
            "re-authenticate",
            "signed out",
            "logged out due to inactivity",
            "sign in again"
        )
        val existing = websiteSessions[userId]?.get(domain)
        val looksExpired = expiryHints.any { combined.contains(it) } ||
            (existing != null && existing.expiresAtMs in 1..timeProvider())
        if (looksExpired) {
            markWebsiteSessionExpired(userId, domain, "Session expired on $domain")
            return true
        }
        return false
    }

    fun getWebsiteSession(userId: String, urlOrDomain: String): WebsiteAuthProfile? {
        val domain = PermissionService.normalizeDomain(urlOrDomain)
        return websiteSessions[userId]?.get(domain)
    }

    fun getSnapshot(userId: String): UserIdentitySnapshot {
        val oauth = oauthRecords[userId]?.values?.toList().orEmpty()
        val sites = websiteSessions[userId]?.values?.toList().orEmpty()
        return UserIdentitySnapshot(
            userId = userId,
            profileKey = "uid_${UserWorkspaceService.sanitizeUidSegment(userId)}",
            oauthSessions = oauth,
            websiteSessions = sites
        )
    }

    suspend fun disconnectAllForUser(userId: String) {
        oauthRecords.remove(userId)
        websiteSessions.remove(userId)
        if (_identitySnapshot.value?.userId == userId) {
            _identitySnapshot.value = null
        }
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.AUTH,
            action = "IDENTITY_ALL_SESSIONS_DISCONNECTED",
            outcome = AuditOutcome.REVOKED,
            detail = "Cleared all OAuth and website identity profiles for UID workspace."
        )
    }

    private fun refreshSnapshot(userId: String) {
        _identitySnapshot.value = getSnapshot(userId)
    }
}
