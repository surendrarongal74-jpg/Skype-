package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import com.example.agent.TaskExecutionEngine
import com.example.agent.tools.AlloraAgentWorkspaceTools
import com.example.agent.tools.ToolRegistry
import com.example.agent.tools.WebResearchTools
import com.example.data.local.AlloraDatabase
import com.example.data.local.entity.BrowserRecordEntity
import com.example.data.local.entity.BrowserTabEntity
import com.example.data.local.entity.EmailItemEntity
import com.example.data.local.entity.MemoryEntryEntity
import com.example.data.local.entity.PersonalityProfileEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.SecurityAuditLogEntity
import com.example.data.local.entity.UserNotificationEntity
import com.example.data.local.entity.WalletAccountEntity
import com.example.data.local.entity.WalletTransactionEntity
import com.example.data.local.entity.WorkspaceFileEntity
import com.example.data.remote.AlloraAgentCloudStore
import com.example.services.AgentCapability
import com.example.services.AuditCategory
import com.example.services.AuditOutcome
import com.example.services.BrowserAutomationService
import com.example.services.BrowserDomCommand
import com.example.services.BrowserLiveActivityState
import com.example.services.BrowserSessionService
import com.example.services.BrowserVerificationManager
import com.example.services.EmailService
import com.example.services.EmailWorkspaceState
import com.example.services.GmailRestClient
import com.example.services.IdentityAuthService
import com.example.services.MemoryCategory
import com.example.services.MemoryService
import com.example.services.NotificationService
import com.example.services.PaymentAuthorizationService
import com.example.services.PaymentDisclosure
import com.example.services.PermissionLevel
import com.example.services.PermissionPolicyState
import com.example.services.PermissionService
import com.example.services.PersonalityPresetMode
import com.example.services.PersonalityService
import com.example.services.ProjectStatus
import com.example.services.ProjectWorkspaceService
import com.example.services.SecurityAuditService
import com.example.services.TrustedPaymentProviderClient
import com.example.services.UserIdentitySnapshot
import com.example.services.UserWorkspaceService
import com.example.services.VerificationChallengeState
import com.example.services.WalletService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinates the user-isolated Wallet, Email, Browser, Permission, Workspace, Security Audit,
 * and Notification services for a single authenticated Firebase UID.
 */
class AlloraAgentWorkspaceController(
    private val appContext: Context,
    val currentUserId: String,
    val userEmail: String,
    val database: AlloraDatabase,
    val cloudStore: AlloraAgentCloudStore?,
    val auditService: SecurityAuditService,
    val permissionService: PermissionService,
    val workspaceService: UserWorkspaceService,
    val notificationService: NotificationService,
    val paymentAuthService: PaymentAuthorizationService,
    val walletService: WalletService,
    val emailService: EmailService,
    val browserSessionService: BrowserSessionService,
    val browserAutomationService: BrowserAutomationService,
    val workspaceTools: AlloraAgentWorkspaceTools,
    val memoryService: MemoryService = MemoryService(database.memoryDao(), auditService, cloudStore),
    val projectService: ProjectWorkspaceService = ProjectWorkspaceService(database.projectDao(), auditService, cloudStore),
    val personalityService: PersonalityService = PersonalityService(database.personalityProfileDao(), cloudStore),
    val identityAuthService: IdentityAuthService = IdentityAuthService(browserSessionService, auditService),
    val verificationManager: BrowserVerificationManager = browserAutomationService.verificationManager
) {
    private val controllerJob = SupervisorJob()
    private val scope = CoroutineScope(controllerJob + Dispatchers.IO)

    val walletAccount: StateFlow<WalletAccountEntity?> = database.walletDao()
        .observeAccount(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, null)

    val walletTransactions: StateFlow<List<WalletTransactionEntity>> = database.walletDao()
        .observeTransactions(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pendingDisclosure: StateFlow<PaymentDisclosure?> = paymentAuthService.pendingDisclosure

    private val _permissionPolicy = MutableStateFlow(PermissionPolicyState(userId = currentUserId))
    val permissionPolicy: StateFlow<PermissionPolicyState> = _permissionPolicy.asStateFlow()

    val emails: StateFlow<List<EmailItemEntity>> = database.emailDao()
        .observeEmails(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val emailWorkspaceState: StateFlow<EmailWorkspaceState> = emailService.workspaceState

    val browserTabs: StateFlow<List<BrowserTabEntity>> = database.browserDao()
        .observeTabs(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val browserRecords: StateFlow<List<BrowserRecordEntity>> = database.browserDao()
        .observeRecords(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val browserLiveActivity: StateFlow<BrowserLiveActivityState> = browserAutomationService.liveActivity
    val pendingBrowserDomCommand: StateFlow<BrowserDomCommand?> = browserAutomationService.pendingDomCommand
    val identitySnapshot: StateFlow<UserIdentitySnapshot?> = identityAuthService.identitySnapshot
    val activeVerificationChallenge: StateFlow<VerificationChallengeState?> = verificationManager.currentChallenge

    val workspaceFiles: StateFlow<List<WorkspaceFileEntity>> = database.workspaceFileDao()
        .observeFiles(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val securityAuditLogs: StateFlow<List<SecurityAuditLogEntity>> = database.securityAuditDao()
        .observeLogs(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val workspaceNotifications: StateFlow<List<UserNotificationEntity>> = database.userNotificationDao()
        .observeNotifications(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val memories: StateFlow<List<MemoryEntryEntity>> = memoryService
        .observeMemories(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val projects: StateFlow<List<ProjectEntity>> = projectService
        .observeProjects(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val personalityProfile: StateFlow<PersonalityProfileEntity> = personalityService
        .observeProfile(currentUserId)
        .stateIn(scope, SharingStarted.Eagerly, PersonalityProfileEntity(userId = currentUserId))

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var attachedTaskEngine: TaskExecutionEngine? = null

    fun attachTaskEngine(engine: TaskExecutionEngine) {
        attachedTaskEngine = engine
    }

    fun initializeWorkspace() {
        scope.launch {
            runCatching {
                workspaceService.startOrTouchSession(currentUserId)
                walletService.getOrInitializeAccount(currentUserId)
                _permissionPolicy.value = permissionService.loadOrInitialize(currentUserId)
                emailService.loadWorkspaceState(currentUserId, userEmail)
                browserSessionService.ensureDefaultTab(currentUserId)
                personalityService.getProfile(currentUserId)
                memoryService.purgeExpiredMemories(currentUserId)
            }
        }
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    // ---- Wallet Actions ----

    fun depositFunds(amountMinor: Long, tokenizedPaymentRef: String) {
        scope.launch {
            walletService.depositFunds(currentUserId, amountMinor, tokenizedPaymentRef).fold(
                onSuccess = { tx ->
                    _statusBanner.value = "Deposited ${WalletService.formatMinor(tx.totalMinor)} (${tx.providerTransactionId})."
                },
                onFailure = { err ->
                    _statusBanner.value = err.message ?: "Deposit could not be verified."
                }
            )
        }
    }

    fun withdrawFunds(amountMinor: Long, tokenizedDestinationRef: String) {
        scope.launch {
            walletService.withdrawFunds(currentUserId, amountMinor, tokenizedDestinationRef).fold(
                onSuccess = { tx ->
                    _statusBanner.value = "Withdrew ${WalletService.formatMinor(tx.totalMinor)} (${tx.providerTransactionId})."
                },
                onFailure = { err ->
                    _statusBanner.value = err.message ?: "Withdrawal failed."
                }
            )
        }
    }

    fun stagePurchaseDisclosure(
        productName: String,
        merchant: String,
        subtotalMinor: Long,
        taxesMinor: Long,
        deliveryDetails: String
    ) {
        scope.launch {
            walletService.stagePurchaseDisclosure(
                userId = currentUserId,
                productOrServiceName = productName,
                merchantOrWebsite = merchant,
                subtotalMinor = subtotalMinor,
                taxesAndFeesMinor = taxesMinor,
                deliveryOrBookingDetails = deliveryDetails
            ).onFailure { err ->
                _statusBanner.value = err.message ?: "Unable to stage payment disclosure."
            }
        }
    }

    fun authorizePendingPayment(idempotencyKey: String) {
        scope.launch {
            val engine = attachedTaskEngine
            val matchingTask = engine?.tasks?.value?.firstOrNull { task ->
                task.userId == currentUserId &&
                    task.pendingApproval?.toolName == ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT &&
                    task.pendingApproval?.args?.get("idempotency_key") == idempotencyKey
            }
            if (matchingTask != null && matchingTask.pendingApproval != null) {
                engine.authorizePendingPaymentAndApprove(
                    taskId = matchingTask.taskId,
                    stepId = matchingTask.pendingApproval.stepId,
                    idempotencyKey = idempotencyKey
                ).onFailure { err ->
                    _statusBanner.value = err.message ?: "Authorization failed."
                }
                return@launch
            }

            paymentAuthService.authorizeDisclosure(currentUserId, idempotencyKey).fold(
                onSuccess = {
                    walletService.executeAuthorizedPurchase(currentUserId, idempotencyKey).fold(
                        onSuccess = { tx ->
                            _statusBanner.value = "Payment authorized & verified (${tx.providerTransactionId})."
                        },
                        onFailure = { err ->
                            _statusBanner.value = err.message ?: "Payment blocked or provider not verified."
                        }
                    )
                },
                onFailure = { err -> _statusBanner.value = err.message ?: "Authorization rejected." }
            )
        }
    }

    fun declinePendingPayment(idempotencyKey: String) {
        scope.launch {
            paymentAuthService.declineDisclosure(currentUserId, idempotencyKey)
            val engine = attachedTaskEngine
            val matchingTask = engine?.tasks?.value?.firstOrNull { task ->
                task.userId == currentUserId &&
                    task.pendingApproval?.toolName == ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT &&
                    task.pendingApproval?.args?.get("idempotency_key") == idempotencyKey
            }
            if (matchingTask != null && matchingTask.pendingApproval != null) {
                engine.rejectPendingStep(matchingTask.taskId, matchingTask.pendingApproval.stepId)
            }
            _statusBanner.value = "Payment declined. No wallet funds were spent."
        }
    }

    fun refundTransaction(txId: String) {
        scope.launch {
            walletService.requestRefund(currentUserId, txId).fold(
                onSuccess = { tx -> _statusBanner.value = "Refund recorded (${tx.receiptSha256.take(10)})." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Refund unavailable." }
            )
        }
    }

    fun reconcileWalletNow() {
        scope.launch {
            walletService.reconcileWithProvider(currentUserId).fold(
                onSuccess = { acct -> _statusBanner.value = "Reconciliation status: ${acct.reconciliationStatus}" },
                onFailure = { err -> _statusBanner.value = err.message ?: "Reconciliation unavailable." }
            )
        }
    }

    fun toggleEmergencyWalletLock(locked: Boolean) {
        scope.launch {
            walletService.setEmergencyLock(currentUserId, locked)
            _statusBanner.value = if (locked) "Emergency Wallet Lock enabled." else "Emergency Wallet Lock released."
        }
    }

    fun updateWalletLimits(perTxMinor: Long, dailyMinor: Long, monthlyMinor: Long) {
        scope.launch {
            walletService.updateSpendingLimits(currentUserId, perTxMinor, dailyMinor, monthlyMinor)
            _statusBanner.value = "Wallet spending limits updated."
        }
    }

    fun updateMinorAccountProtection(isMinor: Boolean, guardianApproved: Boolean, guardianEmail: String?) {
        scope.launch {
            walletService.configureMinorProtection(currentUserId, isMinor, guardianEmail, guardianApproved)
            _statusBanner.value = if (isMinor) "Minor & guardian safeguards updated." else "Adult wallet policy active."
        }
    }

    // ---- Permission & Security Actions ----

    fun updateCapabilityLevel(capability: AgentCapability, level: PermissionLevel) {
        scope.launch {
            _permissionPolicy.value = permissionService.updateCapabilityLevel(currentUserId, capability, level)
            _statusBanner.value = "${capability.label} set to ${level.label}."
        }
    }

    fun updateMaxTxAmount(maxTxMinor: Long) {
        scope.launch {
            _permissionPolicy.value = permissionService.updateMaxTransactionLimit(currentUserId, maxTxMinor)
            _statusBanner.value = "Agent transaction ceiling updated to ${WalletService.formatMinor(maxTxMinor)}."
        }
    }

    fun setKillSwitch(active: Boolean) {
        scope.launch {
            _permissionPolicy.value = permissionService.setKillSwitch(currentUserId, active)
            if (active) {
                attachedTaskEngine?.stopAllActions()
                browserAutomationService.stopAutomationForUser(currentUserId)
            }
            _statusBanner.value = if (active) "Kill Switch ACTIVE — autonomous actions halted." else "Kill Switch cleared."
        }
    }

    fun revokeDomain(domain: String) {
        scope.launch {
            _permissionPolicy.value = permissionService.revokeDomain(currentUserId, domain)
            _statusBanner.value = "Revoked agent access for $domain."
        }
    }

    fun allowDomain(domain: String) {
        scope.launch {
            _permissionPolicy.value = permissionService.restoreDomain(currentUserId, domain)
            _statusBanner.value = "Restored access for $domain."
        }
    }

    fun revokeAllActiveSessions() {
        scope.launch {
            emailService.disconnectEmail(currentUserId)
            identityAuthService.disconnectAllForUser(currentUserId)
            verificationManager.clearForUser(currentUserId)
            workspaceService.revokeAllSessions(currentUserId)
            browserAutomationService.stopAutomationForUser(currentUserId)
            paymentAuthService.clearForUser(currentUserId)
            attachedTaskEngine?.stopAllActions()
            auditService.recordEvent(
                currentUserId, AuditCategory.AUTH, "REVOKE_ALL_SESSIONS", AuditOutcome.REVOKED,
                "Owner revoked OAuth email tokens, browser runtime sessions, and active agent jobs."
            )
            _statusBanner.value = "Revoked all active email & browser sessions."
        }
    }

    // ---- Email Workspace Actions ----

    fun connectGmailOAuth(oauthBearerToken: String) {
        scope.launch {
            emailService.connectGmailOAuth(currentUserId, oauthBearerToken).fold(
                onSuccess = { state -> _statusBanner.value = "Connected ${state.connectedEmail} with OAuth." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Email OAuth verification failed." }
            )
        }
    }

    fun switchEmailAccount(accountEmail: String) {
        scope.launch {
            emailService.switchActiveAccount(currentUserId, accountEmail).fold(
                onSuccess = { state -> _statusBanner.value = "Switched inbox to ${state.connectedEmail}." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Unable to switch email account." }
            )
        }
    }

    fun disconnectEmailAccount(accountEmail: String) {
        scope.launch {
            val state = emailService.disconnectAccount(currentUserId, accountEmail)
            _statusBanner.value = if (state.connectedAccounts.isEmpty()) {
                "Disconnected $accountEmail."
            } else {
                "Removed $accountEmail. Active inbox: ${state.connectedEmail}."
            }
        }
    }

    fun disconnectGmail() {
        scope.launch {
            emailService.disconnectEmail(currentUserId)
            _statusBanner.value = "Disconnected all email accounts."
        }
    }

    fun syncInbox(query: String = "", accountEmail: String? = null) {
        scope.launch {
            emailService.syncInbox(
                userId = currentUserId,
                query = query,
                ownerConfirmedAction = true,
                accountEmail = accountEmail
            ).fold(
                onSuccess = { list -> _statusBanner.value = "Synced ${list.size} message(s)." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Inbox sync unavailable." }
            )
        }
    }

    fun createDraft(
        recipient: String,
        subject: String,
        body: String,
        threadId: String = "",
        accountEmail: String? = null
    ) {
        scope.launch {
            emailService.createDraft(
                userId = currentUserId,
                recipient = recipient,
                subject = subject,
                body = body,
                threadId = threadId,
                accountEmail = accountEmail
            ).fold(
                onSuccess = { _statusBanner.value = "Draft saved and ready for review." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Draft failed." }
            )
        }
    }

    fun sendOwnerApprovedEmail(
        draftId: String?,
        recipient: String,
        subject: String,
        body: String,
        threadId: String? = null,
        accountEmail: String? = null
    ) {
        scope.launch {
            emailService.sendEmail(
                userId = currentUserId,
                draftId = draftId,
                recipient = recipient,
                subject = subject,
                body = body,
                ownerApproved = true,
                threadId = threadId,
                accountEmail = accountEmail
            ).fold(
                onSuccess = { _statusBanner.value = "Email sent to ${it.recipient}." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Email send failed." }
            )
        }
    }

    fun archiveEmail(itemId: String) {
        scope.launch {
            emailService.archiveEmail(currentUserId, itemId, ownerApproved = true).fold(
                onSuccess = { _statusBanner.value = "Email archived." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Archive failed." }
            )
        }
    }

    fun deleteEmail(itemId: String, ownerApproved: Boolean) {
        scope.launch {
            emailService.deleteEmail(currentUserId, itemId, ownerApproved = ownerApproved).fold(
                onSuccess = { _statusBanner.value = "Moved email to trash." },
                onFailure = { err -> _statusBanner.value = err.message ?: "Could not delete email." }
            )
        }
    }

    fun toggleEmailImportant(itemId: String, currentlyImportant: Boolean) {
        scope.launch { emailService.toggleImportant(currentUserId, itemId, !currentlyImportant) }
    }

    fun summarizeEmailItem(itemId: String) {
        scope.launch {
            emailService.summarizeEmail(currentUserId, itemId).onFailure {
                _statusBanner.value = it.message ?: "Could not summarize email."
            }
        }
    }

    fun updateAutoSendAllowlist(enabled: Boolean, recipientsCsv: String) {
        scope.launch {
            val set = recipientsCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            emailService.updateNarrowAutoSendPolicy(currentUserId, enabled, set)
            _statusBanner.value = "Updated trusted recipients (${set.size})."
        }
    }

    // ---- Browser Workspace Actions ----

    fun openNewTab(urlOrSearch: String = "https://duckduckgo.com") {
        scope.launch {
            browserSessionService.openNewTab(currentUserId, urlOrSearch, automation = false).onFailure {
                _statusBanner.value = it.message ?: "Blocked unsafe URL."
            }
        }
    }

    fun selectTab(tabId: String) {
        scope.launch { browserSessionService.switchActiveTab(currentUserId, tabId) }
    }

    fun closeTab(tabId: String) {
        scope.launch { browserSessionService.closeTab(currentUserId, tabId) }
    }

    fun recordPageVisit(tabId: String, url: String, title: String) {
        scope.launch {
            browserSessionService.updateTabLocation(currentUserId, tabId, url, title)
            browserAutomationService.inspectPageForManualIntervention(currentUserId, url, title, title)
        }
    }

    fun addBookmark(url: String, title: String) {
        scope.launch {
            browserSessionService.addBookmark(currentUserId, url, title)
            _statusBanner.value = "Bookmarked ${title.ifBlank { url }}."
        }
    }

    fun addDownloadBytes(url: String, fileName: String, mimeType: String, bytes: ByteArray) {
        scope.launch {
            browserSessionService.saveIsolatedDownload(currentUserId, url, fileName, mimeType, bytes).fold(
                onSuccess = { _statusBanner.value = "Saved ${it.title} in your private workspace." },
                onFailure = { _statusBanner.value = it.message ?: "Download failed." }
            )
        }
    }

    fun updateSitePermission(domain: String, mode: String) {
        scope.launch {
            browserSessionService.setSitePermission(currentUserId, domain, mode)
            _statusBanner.value = "Site permission for $domain set to $mode."
        }
    }

    fun registerBrowserConnectedSession(domain: String, label: String, active: Boolean) {
        scope.launch {
            browserSessionService.registerConnectedSession(currentUserId, domain, label, active)
        }
    }

    fun deleteBrowserRecord(recordId: String) {
        scope.launch { browserSessionService.deleteRecord(currentUserId, recordId) }
    }

    fun stopBrowserAutomation() {
        browserAutomationService.stopAutomationForUser(currentUserId)
    }

    fun resolveManualIntervention() {
        browserAutomationService.resolveManualIntervention(currentUserId)
    }

    // ---- File Workspace Actions ----

    fun importPickedDocument(uri: Uri) {
        scope.launch {
            workspaceService.importPickedFile(currentUserId, uri, sourceTag = "OWNER_UPLOAD").fold(
                onSuccess = { _statusBanner.value = "Imported ${it.fileName} securely into your workspace." },
                onFailure = { err -> _statusBanner.value = err.message ?: "File import blocked." }
            )
        }
    }

    fun deleteWorkspaceFile(fileId: String) {
        scope.launch { workspaceService.deleteWorkspaceFile(currentUserId, fileId) }
    }

    fun markAllNotificationsRead() {
        scope.launch { notificationService.markAllRead(currentUserId) }
    }

    // ---- ALLORA Memory, Projects & Personality Actions ----

    fun saveMemory(
        category: MemoryCategory,
        title: String,
        content: String,
        userApproved: Boolean = true,
        retentionDays: Int = MemoryService.DEFAULT_RETENTION_DAYS
    ) {
        scope.launch {
            memoryService.saveMemory(
                userId = currentUserId,
                category = category,
                title = title,
                content = content,
                source = "USER",
                userApproved = userApproved,
                retentionDays = retentionDays
            ).fold(
                onSuccess = { _statusBanner.value = "Saved memory '${it.title}'." },
                onFailure = { _statusBanner.value = it.message ?: "Unable to save memory." }
            )
        }
    }

    fun updateMemory(
        memoryId: String,
        title: String? = null,
        content: String? = null,
        category: MemoryCategory? = null,
        userApproved: Boolean? = null,
        retentionDays: Int? = null
    ) {
        scope.launch {
            memoryService.updateMemory(
                userId = currentUserId,
                memoryId = memoryId,
                title = title,
                content = content,
                category = category,
                userApproved = userApproved,
                retentionDays = retentionDays
            ).fold(
                onSuccess = { _statusBanner.value = "Updated memory '${it.title}'." },
                onFailure = { _statusBanner.value = it.message ?: "Unable to update memory." }
            )
        }
    }

    fun deleteMemory(memoryId: String) {
        scope.launch {
            memoryService.deleteMemory(currentUserId, memoryId)
            _statusBanner.value = "Memory deleted."
        }
    }

    fun purgeExpiredMemories() {
        scope.launch {
            val count = memoryService.purgeExpiredMemories(currentUserId)
            _statusBanner.value = if (count > 0) "Purged $count expired memory entries." else "No expired memories found."
        }
    }

    fun createProject(title: String, description: String, tags: List<String> = emptyList(), linkedThreadId: String? = null) {
        scope.launch {
            projectService.createProject(currentUserId, title, description, tags, linkedThreadId).fold(
                onSuccess = { _statusBanner.value = "Project '${it.title}' created." },
                onFailure = { _statusBanner.value = it.message ?: "Unable to create project." }
            )
        }
    }

    fun updateProject(
        projectId: String,
        title: String? = null,
        description: String? = null,
        status: ProjectStatus? = null,
        progressPercent: Int? = null,
        linkedThreadId: String? = null
    ) {
        scope.launch {
            projectService.updateProject(currentUserId, projectId, title, description, status, progressPercent, linkedThreadId)
        }
    }

    fun linkTaskToProject(projectId: String, taskId: String) {
        scope.launch { projectService.linkTaskToProject(currentUserId, projectId, taskId) }
    }

    fun linkFileToProject(projectId: String, fileId: String) {
        scope.launch { projectService.linkFileToProject(currentUserId, projectId, fileId) }
    }

    fun deleteProject(projectId: String) {
        scope.launch {
            projectService.deleteProject(currentUserId, projectId)
            _statusBanner.value = "Project removed."
        }
    }

    fun updatePersonality(
        presetMode: PersonalityPresetMode? = null,
        warmthLevel: Int? = null,
        concisenessLevel: Int? = null,
        proactivityLevel: Int? = null,
        explanationTransparency: Boolean? = null,
        voiceConsistencyEnabled: Boolean? = null,
        customSignatureNote: String? = null
    ) {
        scope.launch {
            personalityService.updateProfile(
                userId = currentUserId,
                presetMode = presetMode,
                warmthLevel = warmthLevel,
                concisenessLevel = concisenessLevel,
                proactivityLevel = proactivityLevel,
                explanationTransparency = explanationTransparency,
                voiceConsistencyEnabled = voiceConsistencyEnabled,
                customSignatureNote = customSignatureNote
            ).fold(
                onSuccess = { _statusBanner.value = "ALLORA personality profile updated." },
                onFailure = { _statusBanner.value = it.message ?: "Unable to update personality." }
            )
        }
    }

    suspend fun buildDynamicAgentContext(): String {
        val personalityDirective = personalityService.buildPersonalitySystemDirective(currentUserId)
        val memorySnippet = memoryService.buildApprovedContextSnippet(currentUserId)
        val activeProjects = projectService.listProjects(currentUserId)
            .filter { it.status == ProjectStatus.ACTIVE.name }
            .take(4)
        val projectsSnippet = if (activeProjects.isEmpty()) "" else buildString {
            appendLine("[ACTIVE_USER_PROJECTS]")
            activeProjects.forEach { p ->
                appendLine("- ${p.title} (${p.progressPercent}% • ${p.status}): ${p.description.take(120)}")
            }
            append("[/ACTIVE_USER_PROJECTS]")
        }
        return listOf(personalityDirective, memorySnippet, projectsSnippet)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    suspend fun prepareForSignOut() {
        browserAutomationService.stopAutomationForUser(currentUserId)
        verificationManager.clearForUser(currentUserId)
        identityAuthService.disconnectAllForUser(currentUserId)
        browserSessionService.clearBrowserIsolationForSignOut(currentUserId)
        emailService.clearLocalUserCache(currentUserId)
        walletService.clearLocalUserCache(currentUserId)
        workspaceService.wipeLocalFileIndex(currentUserId)
        notificationService.clearLocalUserCache(currentUserId)
        memoryService.wipeLocalMemories(currentUserId)
        projectService.wipeLocalProjects(currentUserId)
        personalityService.wipeLocalProfile(currentUserId)
        scope.cancel()
    }

    fun close() {
        controllerJob.cancel()
    }

    suspend fun closeAndJoin() {
        controllerJob.cancelAndJoin()
    }

    companion object {
        fun createForUser(
            appContext: Context,
            userId: String,
            userEmail: String,
            database: AlloraDatabase,
            firestore: FirebaseFirestore,
            auth: FirebaseAuth
        ): AlloraAgentWorkspaceController {
            val cloudStore = AlloraAgentCloudStore(firestore, auth)
            val auditService = SecurityAuditService(database.securityAuditDao(), cloudStore)
            val permissionService = PermissionService(database.permissionPolicyDao(), auditService, cloudStore)
            val workspaceService = UserWorkspaceService(appContext, database.workspaceFileDao(), auditService, cloudStore)
            val notificationService = NotificationService(database.userNotificationDao(), cloudStore)
            val paymentGateway = TrustedPaymentProviderClient()
            val paymentAuthService = PaymentAuthorizationService(auditService)
            val walletService = WalletService(
                walletDao = database.walletDao(),
                paymentGateway = paymentGateway,
                authService = paymentAuthService,
                permissionService = permissionService,
                auditService = auditService,
                notificationService = notificationService,
                cloudSync = cloudStore
            )
            val browserSessionService = BrowserSessionService(
                browserDao = database.browserDao(),
                workspaceService = workspaceService,
                auditService = auditService,
                cloudSync = cloudStore
            )
            val identityAuthService = IdentityAuthService(
                browserSessionService = browserSessionService,
                auditService = auditService
            )
            val verificationManager = BrowserVerificationManager(auditService = auditService)
            val emailGateway = GmailRestClient()
            val emailService = EmailService(
                emailDao = database.emailDao(),
                restGateway = emailGateway,
                permissionService = permissionService,
                auditService = auditService,
                notificationService = notificationService,
                cloudSync = cloudStore,
                identityAuthService = identityAuthService
            )
            val browserAutomationService = BrowserAutomationService(
                sessionService = browserSessionService,
                permissionService = permissionService,
                auditService = auditService,
                verificationManager = verificationManager,
                identityAuthService = identityAuthService
            )
            val workspaceTools = AlloraAgentWorkspaceTools(
                walletService = walletService,
                emailService = emailService,
                browserAutomationService = browserAutomationService,
                webResearchTools = WebResearchTools()
            )
            val memoryService = MemoryService(database.memoryDao(), auditService, cloudStore)
            val projectService = ProjectWorkspaceService(database.projectDao(), auditService, cloudStore)
            val personalityService = PersonalityService(database.personalityProfileDao(), cloudStore)
            return AlloraAgentWorkspaceController(
                appContext = appContext,
                currentUserId = userId,
                userEmail = userEmail,
                database = database,
                cloudStore = cloudStore,
                auditService = auditService,
                permissionService = permissionService,
                workspaceService = workspaceService,
                notificationService = notificationService,
                paymentAuthService = paymentAuthService,
                walletService = walletService,
                emailService = emailService,
                browserSessionService = browserSessionService,
                browserAutomationService = browserAutomationService,
                workspaceTools = workspaceTools,
                memoryService = memoryService,
                projectService = projectService,
                personalityService = personalityService,
                identityAuthService = identityAuthService,
                verificationManager = verificationManager
            )
        }
    }
}
