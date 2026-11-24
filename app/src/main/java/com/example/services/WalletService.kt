package com.example.services

import com.example.core.SecurityUtils
import com.example.data.local.dao.WalletDao
import com.example.data.local.entity.WalletAccountEntity
import com.example.data.local.entity.WalletTransactionEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class WalletService(
    private val walletDao: WalletDao,
    private val paymentGateway: PaymentProviderGateway,
    private val authService: PaymentAuthorizationService,
    private val permissionService: PermissionService,
    private val auditService: SecurityAuditService,
    private val notificationService: NotificationService,
    private val cloudSync: AgentCloudSyncPort? = null
) {
    fun observeAccount(userId: String): Flow<WalletAccountEntity?> = walletDao.observeAccount(userId)

    fun observeTransactions(userId: String): Flow<List<WalletTransactionEntity>> = walletDao.observeTransactions(userId)

    suspend fun getOrInitializeAccount(userId: String): WalletAccountEntity {
        val existing = walletDao.getAccount(userId)
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val initial = WalletAccountEntity(
            id = "primary",
            userId = userId,
            currency = "USD",
            verifiedBalanceMinor = 0L,
            pendingBalanceMinor = 0L,
            perTxLimitMinor = 25_000L,
            dailyLimitMinor = 100_000L,
            monthlyLimitMinor = 300_000L,
            providerName = paymentGateway.providerName,
            reconciliationStatus = if (paymentGateway.isConfigured) "SYNC_PENDING" else "UNCONFIGURED",
            reconciliationSha256 = computeAccountDigest(userId, 0L, 0L, paymentGateway.providerName, now),
            lastReconciledAtMs = 0L,
            updatedAt = now
        )
        walletDao.upsertAccount(initial)
        runCatching { cloudSync?.syncWalletAccount(initial) }
        return initial
    }

    suspend fun depositFunds(
        userId: String,
        amountMinor: Long,
        tokenizedPaymentMethod: String,
        idempotencyKey: String = "dep_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Result<WalletTransactionEntity> {
        if (amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("Deposit amount must be positive."))
        }
        SecurityAuditService.containsForbiddenSecretInput(tokenizedPaymentMethod)?.let { reason ->
            auditService.recordEvent(userId, AuditCategory.WALLET, "REJECT_RAW_PAYMENT_SECRET", AuditOutcome.BLOCKED, reason)
            return Result.failure(SecurityException(reason))
        }
        val acct = getOrInitializeAccount(userId)
        if (acct.emergencyLocked) {
            return Result.failure(IllegalStateException("Wallet is locked by emergency safety lock."))
        }
        if (acct.isMinorAccount && !acct.guardianApproved) {
            return Result.failure(SecurityException("Minor wallet requires guardian authorization before depositing funds."))
        }
        walletDao.getTransactionByIdempotencyKey(userId, idempotencyKey)?.let { dup ->
            auditService.recordEvent(userId, AuditCategory.WALLET, "DUPLICATE_DEPOSIT_PREVENTED", AuditOutcome.BLOCKED, "Blocked duplicate deposit $idempotencyKey.")
            return Result.failure(IllegalStateException("Duplicate deposit prevented for key $idempotencyKey (existing tx ${dup.id})."))
        }

        val now = System.currentTimeMillis()
        val providerRes = paymentGateway.createDeposit(
            userId = userId,
            amountMinor = amountMinor,
            currency = acct.currency,
            tokenizedPaymentMethod = tokenizedPaymentMethod,
            idempotencyKey = idempotencyKey
        )

        return providerRes.fold(
            onSuccess = { receipt ->
                val newBalance = receipt.verifiedBalanceMinor ?: (acct.verifiedBalanceMinor + amountMinor)
                val newPending = receipt.pendingBalanceMinor ?: acct.pendingBalanceMinor
                val txId = "tx_${UUID.randomUUID().toString().replace("-", "").take(14)}"
                val sha = SecurityAuditService.buildSha256Receipt(
                    userId, "DEPOSIT", paymentGateway.providerName, amountMinor, idempotencyKey, receipt.providerTransactionId, now
                )
                val tx = WalletTransactionEntity(
                    id = txId,
                    userId = userId,
                    txType = "DEPOSIT",
                    status = if (receipt.status == "PENDING") "PENDING" else "SUCCEEDED",
                    idempotencyKey = idempotencyKey,
                    providerTransactionId = receipt.providerTransactionId,
                    providerVerificationStatus = receipt.verificationStatus,
                    merchant = paymentGateway.providerName,
                    productTitle = "Wallet Deposit",
                    deliveryOrBookingDetails = "Tokenized deposit (${tokenizedPaymentMethod.take(16)})",
                    subtotalMinor = amountMinor,
                    taxesAndFeesMinor = 0L,
                    totalMinor = amountMinor,
                    balanceBeforeMinor = acct.verifiedBalanceMinor,
                    balanceAfterMinor = newBalance,
                    refundedMinor = 0L,
                    receiptSha256 = sha,
                    ownerAuthorized = true,
                    createdAt = now,
                    updatedAt = now
                )
                walletDao.upsertTransaction(tx)
                val updatedAcct = acct.copy(
                    verifiedBalanceMinor = newBalance,
                    pendingBalanceMinor = newPending,
                    providerName = paymentGateway.providerName,
                    reconciliationStatus = "RECONCILED",
                    reconciliationSha256 = computeAccountDigest(userId, newBalance, newPending, paymentGateway.providerName, now),
                    lastReconciledAtMs = now,
                    updatedAt = now
                )
                walletDao.upsertAccount(updatedAcct)
                runCatching {
                    cloudSync?.syncWalletTransaction(tx)
                    cloudSync?.syncWalletAccount(updatedAcct)
                }
                auditService.recordEvent(userId, AuditCategory.WALLET, "WALLET_DEPOSIT", AuditOutcome.VERIFIED, "Verified deposit of $amountMinor ${acct.currency} minor (${receipt.providerTransactionId}).")
                notificationService.notify(userId, "Wallet Deposit Verified", "Added ${formatMinor(amountMinor, acct.currency)} via ${paymentGateway.providerName}.", "Wallet", "wallet")
                Result.success(tx)
            },
            onFailure = { err ->
                recordFailedTx(
                    acct = acct,
                    txType = "DEPOSIT",
                    merchant = paymentGateway.providerName,
                    productTitle = "Wallet Deposit Attempt",
                    details = "Tokenized deposit failed",
                    subtotalMinor = amountMinor,
                    taxesAndFeesMinor = 0L,
                    idempotencyKey = idempotencyKey,
                    ownerAuthorized = true,
                    failureReason = err.message ?: "Provider deposit failed"
                )
                Result.failure(err)
            }
        )
    }

    suspend fun withdrawFunds(
        userId: String,
        amountMinor: Long,
        tokenizedDestination: String,
        idempotencyKey: String = "wd_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Result<WalletTransactionEntity> {
        if (amountMinor <= 0L) {
            return Result.failure(IllegalArgumentException("Withdrawal amount must be positive."))
        }
        SecurityAuditService.containsForbiddenSecretInput(tokenizedDestination)?.let { reason ->
            return Result.failure(SecurityException(reason))
        }
        val acct = getOrInitializeAccount(userId)
        if (acct.emergencyLocked) {
            return Result.failure(IllegalStateException("Wallet is locked by emergency safety lock."))
        }
        if (acct.isMinorAccount && !acct.guardianApproved) {
            return Result.failure(SecurityException("Minor wallet requires guardian authorization for withdrawals."))
        }
        if (amountMinor > acct.verifiedBalanceMinor) {
            return Result.failure(IllegalStateException("Insufficient verified available balance for withdrawal."))
        }
        walletDao.getTransactionByIdempotencyKey(userId, idempotencyKey)?.let {
            return Result.failure(IllegalStateException("Duplicate withdrawal prevented for key $idempotencyKey."))
        }

        val now = System.currentTimeMillis()
        return paymentGateway.createWithdrawal(userId, amountMinor, acct.currency, tokenizedDestination, idempotencyKey).fold(
            onSuccess = { receipt ->
                val newBalance = receipt.verifiedBalanceMinor ?: (acct.verifiedBalanceMinor - amountMinor).coerceAtLeast(0L)
                val sha = SecurityAuditService.buildSha256Receipt(
                    userId, "WITHDRAWAL", paymentGateway.providerName, amountMinor, idempotencyKey, receipt.providerTransactionId, now
                )
                val tx = WalletTransactionEntity(
                    id = "tx_${UUID.randomUUID().toString().replace("-", "").take(14)}",
                    userId = userId,
                    txType = "WITHDRAWAL",
                    status = if (receipt.status == "PENDING") "PENDING" else "SUCCEEDED",
                    idempotencyKey = idempotencyKey,
                    providerTransactionId = receipt.providerTransactionId,
                    providerVerificationStatus = receipt.verificationStatus,
                    merchant = paymentGateway.providerName,
                    productTitle = "Unused Funds Withdrawal",
                    deliveryOrBookingDetails = "Payout destination ${tokenizedDestination.take(16)}",
                    subtotalMinor = amountMinor,
                    taxesAndFeesMinor = 0L,
                    totalMinor = amountMinor,
                    balanceBeforeMinor = acct.verifiedBalanceMinor,
                    balanceAfterMinor = newBalance,
                    refundedMinor = 0L,
                    receiptSha256 = sha,
                    ownerAuthorized = true,
                    createdAt = now,
                    updatedAt = now
                )
                walletDao.upsertTransaction(tx)
                val updatedAcct = acct.copy(
                    verifiedBalanceMinor = newBalance,
                    providerName = paymentGateway.providerName,
                    reconciliationStatus = "RECONCILED",
                    reconciliationSha256 = computeAccountDigest(userId, newBalance, acct.pendingBalanceMinor, paymentGateway.providerName, now),
                    lastReconciledAtMs = now,
                    updatedAt = now
                )
                walletDao.upsertAccount(updatedAcct)
                runCatching {
                    cloudSync?.syncWalletTransaction(tx)
                    cloudSync?.syncWalletAccount(updatedAcct)
                }
                auditService.recordEvent(userId, AuditCategory.WALLET, "WALLET_WITHDRAWAL", AuditOutcome.VERIFIED, "Withdrew $amountMinor ${acct.currency} minor (${receipt.providerTransactionId}).")
                notificationService.notify(userId, "Withdrawal Processed", "Withdrew ${formatMinor(amountMinor, acct.currency)}.", "Wallet", "wallet_history")
                Result.success(tx)
            },
            onFailure = { err ->
                recordFailedTx(
                    acct = acct,
                    txType = "WITHDRAWAL",
                    merchant = paymentGateway.providerName,
                    productTitle = "Withdrawal Attempt",
                    details = "Tokenized withdrawal failed",
                    subtotalMinor = amountMinor,
                    taxesAndFeesMinor = 0L,
                    idempotencyKey = idempotencyKey,
                    ownerAuthorized = true,
                    failureReason = err.message ?: "Provider withdrawal failed"
                )
                Result.failure(err)
            }
        )
    }

    /**
     * Stages a mandatory pre-spend disclosure that MUST be explicitly confirmed by the owner before spending.
     */
    suspend fun stagePurchaseDisclosure(
        userId: String,
        productOrServiceName: String,
        merchantOrWebsite: String,
        subtotalMinor: Long,
        taxesAndFeesMinor: Long,
        deliveryOrBookingDetails: String,
        idempotencyKey: String = "pur_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    ): Result<PaymentDisclosure> {
        val acct = getOrInitializeAccount(userId)
        val total = subtotalMinor + taxesAndFeesMinor
        val checkError = validateSpendPreconditions(userId, acct, total, idempotencyKey)
        if (checkError != null) {
            return Result.failure(checkError)
        }
        val disclosure = authService.stagePaymentDisclosure(
            userId = userId,
            productOrServiceName = productOrServiceName,
            merchantOrWebsite = merchantOrWebsite,
            subtotalMinor = subtotalMinor,
            taxesAndFeesMinor = taxesAndFeesMinor,
            deliveryOrBookingDetails = deliveryOrBookingDetails,
            walletBalanceBeforeMinor = acct.verifiedBalanceMinor,
            currency = acct.currency,
            idempotencyKey = idempotencyKey
        )
        return Result.success(disclosure)
    }

    /**
     * Executes a purchase ONLY if:
     * 1) Mandatory disclosure was staged & explicitly confirmed by owner (`ownerAuthorized == true`)
     * 2) Kill switch / domain revocation / minor protection / spending limits pass
     * 3) Idempotency key has NOT already been used (duplicate-payment prevention)
     * 4) Payment provider returns a VERIFIED transaction record
     */
    suspend fun executeAuthorizedPurchase(
        userId: String,
        idempotencyKey: String
    ): Result<WalletTransactionEntity> {
        if (!auditService.checkRateLimit(userId, "purchase", maxRequests = 5, windowMs = 60_000L)) {
            return Result.failure(IllegalStateException("Payment rate limit exceeded; try again shortly."))
        }
        val disclosure = try {
            authService.requireVerifiedOwnerAuthorization(userId, idempotencyKey)
        } catch (se: SecurityException) {
            auditService.recordEvent(userId, AuditCategory.PAYMENT, "UNAUTHORIZED_PAYMENT_BLOCKED", AuditOutcome.BLOCKED, se.message ?: "Unauthorized spend blocked")
            return Result.failure(se)
        }

        val acct = getOrInitializeAccount(userId)
        val preconditionErr = validateSpendPreconditions(userId, acct, disclosure.totalMinor, idempotencyKey)
        if (preconditionErr != null) {
            auditService.recordEvent(userId, AuditCategory.PAYMENT, "PURCHASE_PRECONDITION_FAILED", AuditOutcome.BLOCKED, preconditionErr.message ?: "Blocked")
            return Result.failure(preconditionErr)
        }

        val now = System.currentTimeMillis()
        val providerRes = paymentGateway.createCharge(
            userId = userId,
            amountMinor = disclosure.totalMinor,
            currency = acct.currency,
            merchant = disclosure.merchantOrWebsite,
            description = disclosure.productOrServiceName,
            idempotencyKey = idempotencyKey
        )

        return providerRes.fold(
            onSuccess = { receipt ->
                val newBalance = receipt.verifiedBalanceMinor ?: (acct.verifiedBalanceMinor - disclosure.totalMinor).coerceAtLeast(0L)
                val sha = SecurityAuditService.buildSha256Receipt(
                    userId, "PURCHASE", disclosure.merchantOrWebsite, disclosure.totalMinor, idempotencyKey, receipt.providerTransactionId, now
                )
                val tx = WalletTransactionEntity(
                    id = "tx_${UUID.randomUUID().toString().replace("-", "").take(14)}",
                    userId = userId,
                    txType = "PURCHASE",
                    status = if (receipt.status == "PENDING") "PENDING" else "SUCCEEDED",
                    idempotencyKey = idempotencyKey,
                    providerTransactionId = receipt.providerTransactionId,
                    providerVerificationStatus = receipt.verificationStatus,
                    merchant = disclosure.merchantOrWebsite,
                    productTitle = disclosure.productOrServiceName,
                    deliveryOrBookingDetails = disclosure.deliveryOrBookingDetails,
                    subtotalMinor = disclosure.subtotalMinor,
                    taxesAndFeesMinor = disclosure.taxesAndFeesMinor,
                    totalMinor = disclosure.totalMinor,
                    balanceBeforeMinor = acct.verifiedBalanceMinor,
                    balanceAfterMinor = newBalance,
                    refundedMinor = 0L,
                    receiptSha256 = sha,
                    ownerAuthorized = true,
                    createdAt = now,
                    updatedAt = now
                )
                walletDao.upsertTransaction(tx)
                val updatedAcct = acct.copy(
                    verifiedBalanceMinor = newBalance,
                    dailySpentMinor = acct.dailySpentMinor + disclosure.totalMinor,
                    monthlySpentMinor = acct.monthlySpentMinor + disclosure.totalMinor,
                    providerName = paymentGateway.providerName,
                    reconciliationStatus = "RECONCILED",
                    reconciliationSha256 = computeAccountDigest(userId, newBalance, acct.pendingBalanceMinor, paymentGateway.providerName, now),
                    lastReconciledAtMs = now,
                    updatedAt = now
                )
                walletDao.upsertAccount(updatedAcct)
                authService.consumeAuthorizedDisclosure(userId, idempotencyKey)
                runCatching {
                    cloudSync?.syncWalletTransaction(tx)
                    cloudSync?.syncWalletAccount(updatedAcct)
                }
                auditService.recordEvent(
                    userId, AuditCategory.PAYMENT, "PAYMENT_SUCCEEDED", AuditOutcome.VERIFIED,
                    "Paid ${disclosure.totalMinor} ${acct.currency} minor to ${disclosure.merchantOrWebsite} (receipt ${sha.take(12)})."
                )
                notificationService.notify(
                    userId,
                    "Payment Confirmed",
                    "Paid ${formatMinor(disclosure.totalMinor, acct.currency)} to ${disclosure.merchantOrWebsite} for ${disclosure.productOrServiceName}.",
                    "Wallet",
                    "wallet_history"
                )
                Result.success(tx)
            },
            onFailure = { err ->
                recordFailedTx(
                    acct = acct,
                    txType = "PURCHASE",
                    merchant = disclosure.merchantOrWebsite,
                    productTitle = disclosure.productOrServiceName,
                    details = disclosure.deliveryOrBookingDetails,
                    subtotalMinor = disclosure.subtotalMinor,
                    taxesAndFeesMinor = disclosure.taxesAndFeesMinor,
                    idempotencyKey = idempotencyKey,
                    ownerAuthorized = true,
                    failureReason = err.message ?: "Payment execution failed"
                )
                auditService.recordEvent(userId, AuditCategory.PAYMENT, "PAYMENT_FAILED", AuditOutcome.FAILED, err.message ?: "Failed")
                Result.failure(err)
            }
        )
    }

    suspend fun requestRefund(userId: String, txId: String): Result<WalletTransactionEntity> {
        val tx = walletDao.getTransactionById(userId, txId)
            ?: return Result.failure(IllegalArgumentException("Transaction not found for user."))
        if (tx.txType != "PURCHASE" || tx.status != "SUCCEEDED") {
            return Result.failure(IllegalStateException("Only succeeded purchases are eligible for refund."))
        }
        val providerTxId = tx.providerTransactionId
            ?: return Result.failure(IllegalStateException("Missing verified provider transaction ID."))
        val refundIdem = "ref_${tx.idempotencyKey}"
        val now = System.currentTimeMillis()
        return paymentGateway.createRefund(userId, providerTxId, tx.totalMinor, refundIdem).fold(
            onSuccess = { receipt ->
                val acct = getOrInitializeAccount(userId)
                val newBalance = receipt.verifiedBalanceMinor ?: (acct.verifiedBalanceMinor + tx.totalMinor)
                val updatedTx = tx.copy(
                    status = "REFUNDED",
                    refundedMinor = tx.totalMinor,
                    updatedAt = now
                )
                val updatedAcct = acct.copy(
                    verifiedBalanceMinor = newBalance,
                    reconciliationStatus = "RECONCILED",
                    reconciliationSha256 = computeAccountDigest(userId, newBalance, acct.pendingBalanceMinor, paymentGateway.providerName, now),
                    lastReconciledAtMs = now,
                    updatedAt = now
                )
                walletDao.upsertTransaction(updatedTx)
                walletDao.upsertAccount(updatedAcct)
                runCatching {
                    cloudSync?.syncWalletTransaction(updatedTx)
                    cloudSync?.syncWalletAccount(updatedAcct)
                }
                auditService.recordEvent(userId, AuditCategory.PAYMENT, "REFUND_ISSUED", AuditOutcome.VERIFIED, "Refunded ${tx.totalMinor} minor for ${tx.productTitle}.")
                notificationService.notify(userId, "Refund Credited", "Refunded ${formatMinor(tx.totalMinor, acct.currency)} from ${tx.merchant}.", "Wallet", "wallet_history")
                Result.success(updatedTx)
            },
            onFailure = { err -> Result.failure(err) }
        )
    }

    /**
     * Reconciles local wallet ledger balance against the actual payment provider record.
     * Detects and flags balance mismatches immediately rather than silently trusting unverified local numbers.
     */
    suspend fun reconcileWithProvider(userId: String): Result<WalletAccountEntity> {
        val acct = getOrInitializeAccount(userId)
        return paymentGateway.fetchReconciliation(userId).fold(
            onSuccess = { snap ->
                val txs = walletDao.listTransactions(userId).filter { it.status == "SUCCEEDED" && it.providerVerificationStatus == "VERIFIED" }
                val ledgerNet = txs.sumOf { tx ->
                    when (tx.txType) {
                        "DEPOSIT" -> tx.totalMinor
                        "WITHDRAWAL" -> -tx.totalMinor
                        "PURCHASE" -> -tx.totalMinor
                        "REFUND" -> tx.totalMinor
                        else -> 0L
                    }
                }
                val hasUnverifiedLocalBalance = acct.verifiedBalanceMinor != snap.verifiedBalanceMinor && acct.lastReconciledAtMs > 0L
                val ledgerMatchesProvider = txs.isEmpty() || ledgerNet == snap.verifiedBalanceMinor || snap.verifiedBalanceMinor == acct.verifiedBalanceMinor
                val status = if (!ledgerMatchesProvider && hasUnverifiedLocalBalance) "MISMATCH" else "RECONCILED"
                val now = System.currentTimeMillis()
                val reconciled = acct.copy(
                    verifiedBalanceMinor = snap.verifiedBalanceMinor,
                    pendingBalanceMinor = snap.pendingBalanceMinor,
                    currency = snap.currency,
                    providerName = snap.providerName,
                    providerAccountRef = snap.providerAccountRef,
                    reconciliationStatus = status,
                    reconciliationSha256 = computeAccountDigest(userId, snap.verifiedBalanceMinor, snap.pendingBalanceMinor, snap.providerName, now),
                    lastReconciledAtMs = now,
                    updatedAt = now
                )
                walletDao.upsertAccount(reconciled)
                runCatching { cloudSync?.syncWalletAccount(reconciled) }
                auditService.recordEvent(
                    userId, AuditCategory.WALLET, "WALLET_RECONCILED",
                    if (status == "MISMATCH") AuditOutcome.FAILED else AuditOutcome.VERIFIED,
                    "Provider=${snap.providerName}, balance=${snap.verifiedBalanceMinor}, status=$status"
                )
                Result.success(reconciled)
            },
            onFailure = { err ->
                val updated = acct.copy(
                    providerName = paymentGateway.providerName,
                    reconciliationStatus = if (err is ProviderNotConfiguredException) "UNCONFIGURED" else "MISMATCH",
                    updatedAt = System.currentTimeMillis()
                )
                walletDao.upsertAccount(updated)
                runCatching { cloudSync?.syncWalletAccount(updated) }
                Result.failure(err)
            }
        )
    }

    suspend fun setEmergencyLock(userId: String, locked: Boolean): WalletAccountEntity {
        val acct = getOrInitializeAccount(userId)
        val updated = acct.copy(emergencyLocked = locked, updatedAt = System.currentTimeMillis())
        walletDao.upsertAccount(updated)
        runCatching { cloudSync?.syncWalletAccount(updated) }
        auditService.recordEvent(
            userId, AuditCategory.WALLET,
            if (locked) "EMERGENCY_LOCK_ENABLED" else "EMERGENCY_LOCK_RELEASED",
            if (locked) AuditOutcome.REVOKED else AuditOutcome.VERIFIED,
            if (locked) "Emergency wallet lock activated by owner." else "Emergency wallet lock released by owner."
        )
        return updated
    }

    suspend fun updateSpendingLimits(
        userId: String,
        perTxLimitMinor: Long,
        dailyLimitMinor: Long,
        monthlyLimitMinor: Long
    ): WalletAccountEntity {
        val acct = getOrInitializeAccount(userId)
        val updated = acct.copy(
            perTxLimitMinor = perTxLimitMinor.coerceIn(0L, 100_000_000L),
            dailyLimitMinor = dailyLimitMinor.coerceIn(0L, 500_000_000L),
            monthlyLimitMinor = monthlyLimitMinor.coerceIn(0L, 1_000_000_000L),
            updatedAt = System.currentTimeMillis()
        )
        walletDao.upsertAccount(updated)
        runCatching { cloudSync?.syncWalletAccount(updated) }
        return updated
    }

    suspend fun configureMinorProtection(
        userId: String,
        isMinor: Boolean,
        guardianEmail: String?,
        guardianApproved: Boolean
    ): WalletAccountEntity {
        val acct = getOrInitializeAccount(userId)
        val updated = acct.copy(
            isMinorAccount = isMinor,
            guardianEmail = guardianEmail?.trim()?.takeIf { it.isNotBlank() },
            guardianApproved = if (isMinor) guardianApproved else true,
            updatedAt = System.currentTimeMillis()
        )
        walletDao.upsertAccount(updated)
        runCatching { cloudSync?.syncWalletAccount(updated) }
        auditService.recordEvent(
            userId, AuditCategory.WALLET, "MINOR_PROTECTION_UPDATED", AuditOutcome.VERIFIED,
            "isMinor=$isMinor, guardianApproved=${updated.guardianApproved}"
        )
        return updated
    }

    suspend fun clearLocalUserCache(userId: String) {
        authService.clearForUser(userId)
        walletDao.deleteTransactionsForUser(userId)
        walletDao.deleteAccountForUser(userId)
    }

    private suspend fun validateSpendPreconditions(
        userId: String,
        acct: WalletAccountEntity,
        totalMinor: Long,
        idempotencyKey: String
    ): Throwable? {
        if (totalMinor <= 0L) return IllegalArgumentException("Purchase amount must be positive.")
        if (acct.emergencyLocked) return IllegalStateException("Wallet is locked by emergency safety lock.")
        if (acct.isMinorAccount && !acct.guardianApproved) {
            return SecurityException("Minor account spending requires verified guardian approval.")
        }
        if (acct.reconciliationStatus == "MISMATCH") {
            return IllegalStateException("Wallet reconciliation mismatch detected; spend blocked until reconciled.")
        }
        val policy = permissionService.loadOrInitialize(userId)
        if (policy.killSwitchActive) {
            return SecurityException("Blocked by Owner Kill Switch: Stop-All safety lock is active.")
        }
        if (totalMinor > acct.perTxLimitMinor) {
            return IllegalStateException("Transaction exceeds per-transaction limit (${formatMinor(acct.perTxLimitMinor, acct.currency)}).")
        }
        if (acct.dailySpentMinor + totalMinor > acct.dailyLimitMinor) {
            return IllegalStateException("Transaction exceeds daily spending limit (${formatMinor(acct.dailyLimitMinor, acct.currency)}).")
        }
        if (acct.monthlySpentMinor + totalMinor > acct.monthlyLimitMinor) {
            return IllegalStateException("Transaction exceeds monthly spending limit (${formatMinor(acct.monthlyLimitMinor, acct.currency)}).")
        }
        if (totalMinor > acct.verifiedBalanceMinor) {
            return IllegalStateException("Insufficient verified wallet balance (${formatMinor(acct.verifiedBalanceMinor, acct.currency)} available).")
        }
        walletDao.getTransactionByIdempotencyKey(userId, idempotencyKey)?.let {
            return IllegalStateException("Duplicate payment prevented for idempotency key $idempotencyKey.")
        }
        return null
    }

    private suspend fun recordFailedTx(
        acct: WalletAccountEntity,
        txType: String,
        merchant: String,
        productTitle: String,
        details: String,
        subtotalMinor: Long,
        taxesAndFeesMinor: Long,
        idempotencyKey: String,
        ownerAuthorized: Boolean,
        failureReason: String
    ) {
        val now = System.currentTimeMillis()
        val total = subtotalMinor + taxesAndFeesMinor
        val tx = WalletTransactionEntity(
            id = "tx_${UUID.randomUUID().toString().replace("-", "").take(14)}",
            userId = acct.userId,
            txType = txType,
            status = "FAILED",
            idempotencyKey = idempotencyKey,
            providerTransactionId = null,
            providerVerificationStatus = if (paymentGateway.isConfigured) "UNVERIFIED" else "PROVIDER_NOT_CONFIGURED",
            merchant = merchant.take(120).ifBlank { "Wallet" },
            productTitle = productTitle.take(160),
            deliveryOrBookingDetails = details.take(500),
            subtotalMinor = subtotalMinor,
            taxesAndFeesMinor = taxesAndFeesMinor,
            totalMinor = total,
            balanceBeforeMinor = acct.verifiedBalanceMinor,
            balanceAfterMinor = acct.verifiedBalanceMinor,
            refundedMinor = 0L,
            receiptSha256 = SecurityAuditService.buildSha256Receipt(acct.userId, txType, merchant, total, idempotencyKey, null, now),
            ownerAuthorized = ownerAuthorized,
            failureReason = failureReason.take(500),
            createdAt = now,
            updatedAt = now
        )
        walletDao.upsertTransaction(tx)
        runCatching { cloudSync?.syncWalletTransaction(tx) }
    }

    companion object {
        fun formatMinor(amountMinor: Long, currency: String = "USD"): String {
            val symbol = when (currency.uppercase()) {
                "INR" -> "₹"
                "EUR" -> "€"
                "GBP" -> "£"
                else -> "$"
            }
            val major = amountMinor / 100.0
            return "$symbol${"%.2f".format(java.util.Locale.US, major)}"
        }

        fun computeAccountDigest(
            userId: String,
            verifiedMinor: Long,
            pendingMinor: Long,
            providerName: String,
            timestampMs: Long
        ): String = SecurityUtils.sha256Hex("$userId|$verifiedMinor|$pendingMinor|$providerName|$timestampMs")
    }
}
