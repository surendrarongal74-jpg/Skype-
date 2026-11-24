package com.example.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class QrCodeMatrix(
    val size: Int,
    val modules: BooleanArray
) {
    fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrCodeMatrix) return false
        return size == other.size && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int = 31 * size + modules.contentHashCode()
}

object QrCodeGenerator {
    /**
     * Generates a standards-compliant ISO/IEC 18004 QR code matrix via ZXing.
     */
    fun encode(payload: String, preferredSize: Int = 64): QrCodeMatrix? {
        if (payload.isBlank()) return null
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, preferredSize, preferredSize, hints)
        val w = matrix.width
        val h = matrix.height
        val size = minOf(w, h)
        val modules = BooleanArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                modules[y * size + x] = matrix.get(x, y)
            }
        }
        return QrCodeMatrix(size = size, modules = modules)
    }
}
