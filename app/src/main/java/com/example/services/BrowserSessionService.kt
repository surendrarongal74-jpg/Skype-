package com.example.services

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.example.data.local.dao.BrowserDao
import com.example.data.local.entity.BrowserRecordEntity
import com.example.data.local.entity.BrowserTabEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

class BrowserSessionService(
    private val browserDao: BrowserDao,
    private val workspaceService: UserWorkspaceService,
    private val auditService: SecurityAuditService,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    private var lastActiveWebViewUserId: String? = null

    fun observeTabs(userId: String): Flow<List<BrowserTabEntity>> = browserDao.observeTabs(userId)

    fun observeRecords(userId: String): Flow<List<BrowserRecordEntity>> = browserDao.observeRecords(userId)

    suspend fun ensureDefaultTab(userId: String): BrowserTabEntity {
        isolateWebViewStorageForUser(userId)
        val existing = browserDao.listTabs(userId)
        if (existing.isNotEmpty()) {
            return existing.firstOrNull { it.isActive } ?: existing.first().also { first ->
                browserDao.clearActiveFlags(userId)
                val updated = first.copy(isActive = true)
                browserDao.upsertTab(updated)
            }
        }
        val tab = BrowserTabEntity(
            id = "tab_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            url = DEFAULT_HOME_URL,
            title = "ALLORA Browser Home",
            isActive = true,
            isAutomationActive = false,
            updatedAt = System.currentTimeMillis()
        )
        browserDao.upsertTab(tab)
        runCatching { cloudSync?.syncBrowserTab(tab) }
        return tab
    }

    suspend fun openNewTab(userId: String, urlOrQuery: String, automation: Boolean = false): Result<BrowserTabEntity> {
        val resolvedUrl = resolveUserUrlOrSearch(urlOrQuery)
            ?: return Result.failure(IllegalArgumentException("Blocked unsafe or malformed URL scheme."))
        browserDao.clearActiveFlags(userId)
        val tab = BrowserTabEntity(
            id = "tab_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            url = resolvedUrl,
            title = PermissionService.normalizeDomain(resolvedUrl).ifBlank { "Browser Tab" },
            isActive = true,
            isAutomationActive = automation,
            updatedAt = System.currentTimeMillis()
        )
        browserDao.upsertTab(tab)
        runCatching { cloudSync?.syncBrowserTab(tab) }
        return Result.success(tab)
    }

    suspend fun switchActiveTab(userId: String, tabId: String) {
        val tabs = browserDao.listTabs(userId)
        val target = tabs.firstOrNull { it.id == tabId } ?: return
        browserDao.clearActiveFlags(userId)
        val updated = target.copy(isActive = true, updatedAt = System.currentTimeMillis())
        browserDao.upsertTab(updated)
        runCatching { cloudSync?.syncBrowserTab(updated) }
    }

    suspend fun updateTabLocation(
        userId: String,
        tabId: String,
        url: String,
        title: String,
        automationActive: Boolean? = null
    ) {
        val safeUrl = resolveUserUrlOrSearch(url) ?: return
        val tabs = browserDao.listTabs(userId)
        val cur = tabs.firstOrNull { it.id == tabId } ?: return
        val updated = cur.copy(
            url = safeUrl,
            title = title.ifBlank { PermissionService.normalizeDomain(safeUrl) }.take(200),
            isAutomationActive = automationActive ?: cur.isAutomationActive,
            updatedAt = System.currentTimeMillis()
        )
        browserDao.upsertTab(updated)
        runCatching { cloudSync?.syncBrowserTab(updated) }
        recordHistory(userId, safeUrl, updated.title)
    }

    suspend fun closeTab(userId: String, tabId: String) {
        browserDao.deleteTab(userId, tabId)
        runCatching { cloudSync?.deleteBrowserTab(userId, tabId) }
        val remaining = browserDao.listTabs(userId)
        if (remaining.isEmpty()) {
            ensureDefaultTab(userId)
        } else if (remaining.none { it.isActive }) {
            switchActiveTab(userId, remaining.first().id)
        }
    }

    suspend fun recordHistory(userId: String, url: String, title: String) {
        val domain = PermissionService.normalizeDomain(url)
        val rec = BrowserRecordEntity(
            id = "hist_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            recordType = "HISTORY",
            domain = domain.take(200),
            url = url.take(2048),
            title = title.ifBlank { domain }.take(240),
            permissionMode = "ALLOWED",
            status = "VISITED",
            updatedAt = System.currentTimeMillis()
        )
        browserDao.upsertRecord(rec)
        runCatching { cloudSync?.syncBrowserRecord(rec) }
    }

    suspend fun addBookmark(userId: String, url: String, title: String): BrowserRecordEntity {
        val domain = PermissionService.normalizeDomain(url)
        val rec = BrowserRecordEntity(
            id = "bm_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            recordType = "BOOKMARK",
            domain = domain.take(200),
            url = url.take(2048),
            title = title.ifBlank { domain }.take(240),
            permissionMode = "SAVED",
            status = "ACTIVE",
            updatedAt = System.currentTimeMillis()
        )
        browserDao.upsertRecord(rec)
        runCatching { cloudSync?.syncBrowserRecord(rec) }
        return rec
    }

    suspend fun setSitePermission(userId: String, domain: String, mode: String) {
        val normalized = PermissionService.normalizeDomain(domain)
        val rec = BrowserRecordEntity(
            id = "perm_${normalized.replace(".", "_")}",
            userId = userId,
            recordType = "SITE_PERMISSION",
            domain = normalized.take(200),
            url = "https://$normalized",
            title = normalized,
            permissionMode = mode,
            status = mode,
            updatedAt = System.currentTimeMillis()
        )
        browserDao.upsertRecord(rec)
        runCatching { cloudSync?.syncBrowserRecord(rec) }
    }

    suspend fun registerConnectedSession(userId: String, domain: String, label: String, active: Boolean) {
        val normalized = PermissionService.normalizeDomain(domain)
        val rec = BrowserRecordEntity(
            id = "sess_${normalized.replace(".", "_")}",
            userId = userId,
            recordType = "SESSION",
            domain = normalized.take(200),
            url = "https://$normalized",
            title = label.ifBlank { normalized }.take(240),
            permissionMode = if (active) "MANUAL_LOGIN_ACTIVE" else "REVOKED",
            status = if (active) "ACTIVE" else "REVOKED",
            updatedAt = System.currentTimeMillis()
        )
        browserDao.upsertRecord(rec)
        runCatching { cloudSync?.syncBrowserRecord(rec) }
    }

    suspend fun saveIsolatedDownload(
        userId: String,
        url: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<BrowserRecordEntity> = withContext(Dispatchers.IO) {
        runCatching {
            workspaceService.ensureWorkspaceDirectories(userId)
            val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").ifBlank { "download.bin" }
            val file = File(workspaceService.getUserDownloadsDir(userId), "${System.currentTimeMillis()}_$cleanName")
            file.writeBytes(bytes)
            workspaceService.registerSavedFile(userId, cleanName, mimeType, file, "BROWSER_DOWNLOAD")
            val domain = PermissionService.normalizeDomain(url)
            val rec = BrowserRecordEntity(
                id = "dl_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                userId = userId,
                recordType = "DOWNLOAD",
                domain = domain.take(200),
                url = url.take(2048),
                title = cleanName.take(240),
                permissionMode = "ISOLATED_DOWNLOAD",
                filePath = file.absolutePath.take(500),
                status = "COMPLETED",
                updatedAt = System.currentTimeMillis()
            )
            browserDao.upsertRecord(rec)
            runCatching { cloudSync?.syncBrowserRecord(rec) }
            auditService.recordEvent(userId, AuditCategory.BROWSER, "ISOLATED_DOWNLOAD", AuditOutcome.VERIFIED, "Saved $cleanName (${bytes.size} bytes) to UID workspace.")
            rec
        }
    }

    suspend fun deleteRecord(userId: String, recordId: String) {
        browserDao.deleteRecord(userId, recordId)
    }

    suspend fun listTabs(userId: String): List<BrowserTabEntity> = browserDao.listTabs(userId)

    suspend fun listConnectedSessions(userId: String): List<BrowserRecordEntity> =
        browserDao.listRecordsByType(userId, "SESSION")

    suspend fun restoreLastVisitedUrl(userId: String): String {
        val activeTabUrl = browserDao.listTabs(userId)
            .firstOrNull { it.isActive && isUsableWebUrl(it.url) }
            ?.url
        if (!activeTabUrl.isNullOrBlank()) return activeTabUrl
        val recentHistory = browserDao.listRecordsByType(userId, "HISTORY")
            .firstOrNull { isUsableWebUrl(it.url) }
            ?.url
        return recentHistory ?: DEFAULT_HOME_URL
    }

    fun getProfileStorageKey(userId: String): String =
        "allora_profile_${UserWorkspaceService.sanitizeUidSegment(userId)}"

    /**
     * Switches or resets runtime WebView cookie jar and DOM WebStorage whenever UID changes or signs out.
     * Prevents cookies, localStorage, or logged-in sessions from ever leaking between ALLORA users.
     */
    suspend fun isolateWebViewStorageForUser(userId: String) {
        if (lastActiveWebViewUserId != null && lastActiveWebViewUserId != userId) {
            clearWebViewRuntimeData()
            auditService.recordEvent(
                userId, AuditCategory.ISOLATION, "BROWSER_SESSION_WIPED_ON_USER_SWITCH", AuditOutcome.VERIFIED,
                "Cleared WebView cookies and WebStorage when switching active workspace."
            )
        }
        lastActiveWebViewUserId = userId
        workspaceService.ensureWorkspaceDirectories(userId)
    }

    suspend fun clearBrowserIsolationForSignOut(userId: String) {
        clearWebViewRuntimeData()
        browserDao.deleteTabsForUser(userId)
        browserDao.deleteRecordsForUser(userId)
        if (lastActiveWebViewUserId == userId) {
            lastActiveWebViewUserId = null
        }
    }

    private fun clearWebViewRuntimeData() {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
        runCatching {
            WebStorage.getInstance().deleteAllData()
        }
    }

    companion object {
        const val DEFAULT_HOME_URL = "https://duckduckgo.com"

        fun resolveUserUrlOrSearch(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return DEFAULT_HOME_URL
            val lower = trimmed.lowercase(Locale.ROOT)
            if (lower.startsWith("https://") || lower.startsWith("http://")) {
                return trimmed
            }
            if (EXPLICIT_SCHEME_REGEX.containsMatchIn(lower)) {
                return null
            }
            val looksLikeDomain = !trimmed.contains(" ") && trimmed.contains(".") && trimmed.length <= 200
            return if (looksLikeDomain) {
                "https://$trimmed"
            } else {
                "https://duckduckgo.com/?q=${Uri.encode(trimmed)}"
            }
        }

        fun isUsableWebUrl(url: String): Boolean {
            val lower = url.trim().lowercase(Locale.ROOT)
            if (lower.isBlank()) return false
            if (lower == "about:blank" || lower == "chromewebdata" || lower.startsWith("data:text/html")) {
                return false
            }
            return lower.startsWith("https://") || lower.startsWith("http://")
        }

        /**
         * Strips credentials, query values, and fragments from a URL before diagnostic logging
         * so passwords, OTPs, cookies, or session tokens never enter logs or chat notices.
         */
        fun sanitizeUrlForDiagnostic(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return ""
            return runCatching {
                val parsed = Uri.parse(trimmed)
                val scheme = parsed.scheme?.lowercase(Locale.ROOT).orEmpty()
                if (scheme != "http" && scheme != "https") {
                    return@runCatching "${scheme.ifBlank { "unknown" }}://<redacted>"
                }
                val host = parsed.host.orEmpty()
                val port = if (parsed.port > 0) ":${parsed.port}" else ""
                val path = parsed.encodedPath.orEmpty().ifBlank { "/" }
                val querySuffix = if (!parsed.query.isNullOrBlank()) "?<redacted>" else ""
                "$scheme://$host$port$path$querySuffix"
            }.getOrElse { trimmed.substringBefore('?').substringBefore('#') }
        }

        private val EXPLICIT_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    }
}
