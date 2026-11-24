package com.example.core

import java.security.MessageDigest

object SecurityUtils {
    /**
     * Computes a standards-compliant lowercase 64-character SHA-256 hexadecimal digest.
     */
    fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

    fun sha256Hex(input: ByteArray): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input)
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val i = b.toInt() and 0xFF
                if (i < 0x10) append('0')
                append(i.toString(16))
            }
        }
    }

    fun buildBookingAuditPayload(
        userId: String,
        referenceCode: String,
        bookingType: String,
        title: String,
        amountUsd: Double,
        createdAtMs: Long
    ): String = "$userId|$referenceCode|$bookingType|$title|${"%.2f".format(java.util.Locale.US, amountUsd)}|$createdAtMs"
}
