package com.example.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProviderNotConfiguredException(message: String) : IllegalStateException(message)

data class ProviderTransactionReceipt(
    val providerTransactionId: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val verifiedBalanceMinor: Long? = null,
    val pendingBalanceMinor: Long? = null,
    val verificationStatus: String = "VERIFIED"
)

data class ProviderReconciliationSnapshot(
    val providerName: String,
    val providerAccountRef: String,
    val verifiedBalanceMinor: Long,
    val pendingBalanceMinor: Long,
    val currency: String,
    val transactionIds: List<String> = emptyList(),
    val reconciledAtMs: Long = System.currentTimeMillis()
)

interface PaymentProviderGateway {
    val providerName: String
    val isConfigured: Boolean

    suspend fun createDeposit(
        userId: String,
        amountMinor: Long,
        currency: String,
        tokenizedPaymentMethod: String,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt>

    suspend fun createWithdrawal(
        userId: String,
        amountMinor: Long,
        currency: String,
        tokenizedPayoutDestination: String,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt>

    suspend fun createCharge(
        userId: String,
        amountMinor: Long,
        currency: String,
        merchant: String,
        description: String,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt>

    suspend fun createRefund(
        userId: String,
        providerTransactionId: String,
        amountMinor: Long,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt>

    suspend fun fetchReconciliation(userId: String): Result<ProviderReconciliationSnapshot>
}

/**
 * Production HTTP client for a tokenized regulated payment gateway backend.
 * Zero raw card PANs, CVVs, banking passwords, or UPI PINs ever touch this client.
 * When `gatewayBaseUrl` is blank/unconfigured, all financial mutations fail honestly with
 * ProviderNotConfiguredException rather than inventing fake money.
 */
class TrustedPaymentProviderClient(
    private val gatewayBaseUrl: String = "",
    override val providerName: String = if (gatewayBaseUrl.isBlank()) "UNCONFIGURED" else "Regulated Payment Gateway",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) : PaymentProviderGateway {

    override val isConfigured: Boolean
        get() = gatewayBaseUrl.startsWith("https://") || gatewayBaseUrl.startsWith("http://127.0.0.1") || gatewayBaseUrl.startsWith("http://localhost")

    override suspend fun createDeposit(
        userId: String,
        amountMinor: Long,
        currency: String,
        tokenizedPaymentMethod: String,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt> = postTransaction(
        endpoint = "/v1/wallet/deposits",
        idempotencyKey = idempotencyKey,
        payload = JSONObject().apply {
            put("userId", userId)
            put("amountMinor", amountMinor)
            put("currency", currency)
            put("tokenizedInstrument", tokenizedPaymentMethod)
            put("idempotencyKey", idempotencyKey)
        },
        secretValidationInput = tokenizedPaymentMethod
    )

    override suspend fun createWithdrawal(
        userId: String,
        amountMinor: Long,
        currency: String,
        tokenizedPayoutDestination: String,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt> = postTransaction(
        endpoint = "/v1/wallet/withdrawals",
        idempotencyKey = idempotencyKey,
        payload = JSONObject().apply {
            put("userId", userId)
            put("amountMinor", amountMinor)
            put("currency", currency)
            put("tokenizedDestination", tokenizedPayoutDestination)
            put("idempotencyKey", idempotencyKey)
        },
        secretValidationInput = tokenizedPayoutDestination
    )

    override suspend fun createCharge(
        userId: String,
        amountMinor: Long,
        currency: String,
        merchant: String,
        description: String,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt> = postTransaction(
        endpoint = "/v1/wallet/charges",
        idempotencyKey = idempotencyKey,
        payload = JSONObject().apply {
            put("userId", userId)
            put("amountMinor", amountMinor)
            put("currency", currency)
            put("merchant", merchant)
            put("description", description)
            put("idempotencyKey", idempotencyKey)
        },
        secretValidationInput = description
    )

    override suspend fun createRefund(
        userId: String,
        providerTransactionId: String,
        amountMinor: Long,
        idempotencyKey: String
    ): Result<ProviderTransactionReceipt> = postTransaction(
        endpoint = "/v1/wallet/refunds",
        idempotencyKey = idempotencyKey,
        payload = JSONObject().apply {
            put("userId", userId)
            put("providerTransactionId", providerTransactionId)
            put("amountMinor", amountMinor)
            put("idempotencyKey", idempotencyKey)
        },
        secretValidationInput = ""
    )

    override suspend fun fetchReconciliation(userId: String): Result<ProviderReconciliationSnapshot> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireConfigured()
                val req = Request.Builder()
                    .url("${gatewayBaseUrl.trimEnd('/')}/v1/wallet/reconcile?userId=$userId")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        error("Payment provider reconciliation HTTP ${resp.code}: $bodyStr")
                    }
                    val obj = JSONObject(bodyStr)
                    val txArr = obj.optJSONArray("transactionIds")
                    val ids = buildList {
                        if (txArr != null) {
                            for (i in 0 until txArr.length()) {
                                val id = txArr.optString(i)
                                if (id.isNotBlank()) add(id)
                            }
                        }
                    }
                    ProviderReconciliationSnapshot(
                        providerName = obj.optString("providerName", providerName),
                        providerAccountRef = obj.optString("providerAccountRef", "acct_$userId"),
                        verifiedBalanceMinor = obj.getLong("verifiedBalanceMinor"),
                        pendingBalanceMinor = obj.optLong("pendingBalanceMinor", 0L),
                        currency = obj.optString("currency", "USD"),
                        transactionIds = ids
                    )
                }
            }
        }

    private suspend fun postTransaction(
        endpoint: String,
        idempotencyKey: String,
        payload: JSONObject,
        secretValidationInput: String
    ): Result<ProviderTransactionReceipt> = withContext(Dispatchers.IO) {
        runCatching {
            SecurityAuditService.containsForbiddenSecretInput(secretValidationInput)?.let { reason ->
                throw SecurityException(reason)
            }
            requireConfigured()
            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder()
                .url("${gatewayBaseUrl.trimEnd('/')}$endpoint")
                .addHeader("Idempotency-Key", idempotencyKey)
                .post(body)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("Payment provider HTTP ${resp.code}: $raw")
                }
                val obj = JSONObject(raw)
                val txId = obj.optString("providerTransactionId").trim()
                require(txId.isNotBlank()) { "Provider did not return a verified transaction reference." }
                ProviderTransactionReceipt(
                    providerTransactionId = txId,
                    status = obj.optString("status", "SUCCEEDED"),
                    amountMinor = obj.getLong("amountMinor"),
                    currency = obj.optString("currency", "USD"),
                    verifiedBalanceMinor = if (obj.has("verifiedBalanceMinor")) obj.getLong("verifiedBalanceMinor") else null,
                    pendingBalanceMinor = if (obj.has("pendingBalanceMinor")) obj.getLong("pendingBalanceMinor") else null,
                    verificationStatus = obj.optString("verificationStatus", "VERIFIED")
                )
            }
        }
    }

    private fun requireConfigured() {
        if (!isConfigured) {
            throw ProviderNotConfiguredException(
                "Regulated payment provider backend is not configured. Tokenized deposits, withdrawals, and real-money charges require a connected payment-provider gateway."
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
