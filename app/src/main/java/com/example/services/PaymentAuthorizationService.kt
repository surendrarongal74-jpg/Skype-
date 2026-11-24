package com.example.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PaymentDisclosure(
    val disclosureId: String,
    val userId: String,
    val productOrServiceName: String,
    val merchantOrWebsite: String,
    val subtotalMinor: Long,
    val taxesAndFeesMinor: Long,
    val totalMinor: Long,
    val deliveryOrBookingDetails: String,
    val walletBalanceBeforeMinor: Long,
    val walletBalanceAfterMinor: Long,
    val currency: String,
    val idempotencyKey: String,
    val ownerAuthorized: Boolean = false,
    val authorizedAtMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = createdAtMs + PaymentAuthorizationService.DEFAULT_EXPIRATION_MS
)

class PaymentAuthorizationService(
    private val auditService: SecurityAuditService
) {
    private val stagedDisclosures = ConcurrentHashMap<String, PaymentDisclosure>()
    private val disclosureOwners = ConcurrentHashMap<String, String>()
    private val _pendingDisclosure = MutableStateFlow<PaymentDisclosure?>(null)
    val pendingDisclosure: StateFlow<PaymentDisclosure?> = _pendingDisclosure.asStateFlow()

    fun stagePaymentDisclosure(
        userId: String,
        productOrServiceName: String,
        merchantOrWebsite: String,
        subtotalMinor: Long,
        taxesAndFeesMinor: Long,
        deliveryOrBookingDetails: String,
        walletBalanceBeforeMinor: Long,
        currency: String,
        idempotencyKey: String,
        expiresAtMs: Long = System.currentTimeMillis() + DEFAULT_EXPIRATION_MS
    ): PaymentDisclosure {
        require(userId.isNotBlank()) { "Authenticated userId is required for payment disclosure." }
        require(subtotalMinor >= 0L && taxesAndFeesMinor >= 0L) { "Negative payment amounts are invalid." }
        val existingOwner = disclosureOwners[idempotencyKey]
        if (existingOwner != null && existingOwner != userId) {
            throw SecurityException("Idempotency key collision across users is blocked.")
        }
        val now = System.currentTimeMillis()
        val total = subtotalMinor + taxesAndFeesMinor
        val balanceAfter = (walletBalanceBeforeMinor - total).coerceAtLeast(0L)
        val disclosure = PaymentDisclosure(
            disclosureId = "paydisc_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            userId = userId,
            productOrServiceName = productOrServiceName.trim().take(160),
            merchantOrWebsite = merchantOrWebsite.trim().take(120),
            subtotalMinor = subtotalMinor,
            taxesAndFeesMinor = taxesAndFeesMinor,
            totalMinor = total,
            deliveryOrBookingDetails = deliveryOrBookingDetails.trim().take(500),
            walletBalanceBeforeMinor = walletBalanceBeforeMinor,
            walletBalanceAfterMinor = balanceAfter,
            currency = currency,
            idempotencyKey = idempotencyKey,
            ownerAuthorized = false,
            createdAtMs = now,
            expiresAtMs = expiresAtMs
        )
        disclosureOwners[idempotencyKey] = userId
        stagedDisclosures[key(userId, idempotencyKey)] = disclosure
        _pendingDisclosure.value = disclosure
        return disclosure
    }

    suspend fun authorizeDisclosure(userId: String, idempotencyKey: String): Result<PaymentDisclosure> {
        val recordedOwner = disclosureOwners[idempotencyKey]
        if (recordedOwner != null && recordedOwner != userId) {
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.ISOLATION,
                action = "CROSS_USER_PAYMENT_AUTH_BLOCKED",
                outcome = AuditOutcome.BLOCKED,
                detail = "Blocked payment authorization attempt for another user's disclosure ($idempotencyKey)."
            )
            return Result.failure(SecurityException("Cross-user payment authorization denied."))
        }
        val k = key(userId, idempotencyKey)
        val existing = stagedDisclosures[k]
            ?: return Result.failure(IllegalStateException("Payment disclosure '$idempotencyKey' not found for user."))
        if (existing.userId != userId) {
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.ISOLATION,
                action = "CROSS_USER_PAYMENT_AUTH_BLOCKED",
                outcome = AuditOutcome.BLOCKED,
                detail = "Blocked payment authorization attempt across users."
            )
            return Result.failure(SecurityException("Cross-user payment authorization denied."))
        }
        val now = System.currentTimeMillis()
        if (now > existing.expiresAtMs) {
            stagedDisclosures.remove(k)
            disclosureOwners.remove(idempotencyKey)
            if (_pendingDisclosure.value?.idempotencyKey == idempotencyKey) {
                _pendingDisclosure.value = null
            }
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.PAYMENT,
                action = "EXPIRED_PAYMENT_DISCLOSURE_BLOCKED",
                outcome = AuditOutcome.BLOCKED,
                detail = "Payment approval window expired for ${existing.productOrServiceName}."
            )
            return Result.failure(SecurityException("Payment approval expired. Stage a fresh payment disclosure to proceed."))
        }
        val approved = existing.copy(
            ownerAuthorized = true,
            authorizedAtMs = now
        )
        stagedDisclosures[k] = approved
        if (_pendingDisclosure.value?.idempotencyKey == idempotencyKey) {
            _pendingDisclosure.value = approved
        }
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.PAYMENT,
            action = "OWNER_PAYMENT_AUTHORIZED",
            outcome = AuditOutcome.ALLOWED,
            detail = "Owner explicitly authorized ${approved.productOrServiceName} at ${approved.merchantOrWebsite} (${approved.totalMinor} ${approved.currency} minor)."
        )
        return Result.success(approved)
    }

    suspend fun declineDisclosure(userId: String, idempotencyKey: String) {
        val owner = disclosureOwners[idempotencyKey]
        if (owner != null && owner != userId) {
            auditService.recordEvent(
                userId = userId,
                category = AuditCategory.ISOLATION,
                action = "CROSS_USER_PAYMENT_DECLINE_BLOCKED",
                outcome = AuditOutcome.BLOCKED,
                detail = "Blocked cross-user decline attempt on $idempotencyKey."
            )
            return
        }
        stagedDisclosures.remove(key(userId, idempotencyKey))
        disclosureOwners.remove(idempotencyKey)
        if (_pendingDisclosure.value?.idempotencyKey == idempotencyKey &&
            _pendingDisclosure.value?.userId == userId
        ) {
            _pendingDisclosure.value = null
        }
        auditService.recordEvent(
            userId = userId,
            category = AuditCategory.PAYMENT,
            action = "OWNER_PAYMENT_DECLINED",
            outcome = AuditOutcome.DENIED,
            detail = "Owner declined payment disclosure $idempotencyKey."
        )
    }

    fun requireVerifiedOwnerAuthorization(userId: String, idempotencyKey: String): PaymentDisclosure {
        val owner = disclosureOwners[idempotencyKey]
        if (owner != null && owner != userId) {
            throw SecurityException("Payment blocked: cross-user authorization mismatch.")
        }
        val disclosure = stagedDisclosures[key(userId, idempotencyKey)]
            ?: throw SecurityException("Payment blocked: mandatory disclosure breakdown was never presented to the owner.")
        if (disclosure.userId != userId) {
            throw SecurityException("Payment blocked: cross-user authorization mismatch.")
        }
        if (System.currentTimeMillis() > disclosure.expiresAtMs) {
            stagedDisclosures.remove(key(userId, idempotencyKey))
            disclosureOwners.remove(idempotencyKey)
            throw SecurityException("Payment blocked: owner payment authorization expired.")
        }
        if (!disclosure.ownerAuthorized || disclosure.authorizedAtMs == null) {
            throw SecurityException("Payment blocked: owner authorization button has not been confirmed.")
        }
        return disclosure
    }

    fun consumeAuthorizedDisclosure(userId: String, idempotencyKey: String) {
        stagedDisclosures.remove(key(userId, idempotencyKey))
        if (disclosureOwners[idempotencyKey] == userId) {
            disclosureOwners.remove(idempotencyKey)
        }
        if (_pendingDisclosure.value?.idempotencyKey == idempotencyKey &&
            _pendingDisclosure.value?.userId == userId
        ) {
            _pendingDisclosure.value = null
        }
    }

    fun clearForUser(userId: String) {
        stagedDisclosures.keys.removeAll { it.startsWith("$userId::") }
        disclosureOwners.entries.removeAll { it.value == userId }
        if (_pendingDisclosure.value?.userId == userId) {
            _pendingDisclosure.value = null
        }
    }

    private fun key(userId: String, idempotencyKey: String): String = "$userId::$idempotencyKey"

    companion object {
        const val DEFAULT_EXPIRATION_MS = 15 * 60_000L
    }
}
