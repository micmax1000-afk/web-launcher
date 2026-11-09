package com.weblauncher.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/** Loyalty card with its barcode. format = ZXing BarcodeFormat name. */
data class LoyaltyCard(val id: String, val name: String, val code: String, val format: String, val color: Int)

/** Ticket or document kept offline: a PDF, a picture, or a QR/barcode text. */
data class WalletItem(val id: String, val title: String, val kind: String, val path: String, val time: Long, val format: String? = null)

object Barcodes {
    /** ML Kit Barcode.FORMAT_* -> ZXing format name. */
    fun fromMlKit(f: Int): String = when (f) {
        1 -> "CODE_128"; 2 -> "CODE_39"; 4 -> "CODE_93"; 8 -> "CODABAR"; 16 -> "DATA_MATRIX"
        32 -> "EAN_13"; 64 -> "EAN_8"; 128 -> "ITF"; 256 -> "QR_CODE"; 512 -> "UPC_A"; 1024 -> "UPC_E"
        2048 -> "PDF_417"; 4096 -> "AZTEC"; else -> "CODE_128"
    }

    val manualFormats = listOf("EAN_13" to "EAN-13 (13 cifre, il più comune)", "CODE_128" to "Code 128 (lettere e numeri)",
        "EAN_8" to "EAN-8 (8 cifre)", "QR_CODE" to "QR code", "CODE_39" to "Code 39")

    fun is2D(format: String) = format in setOf("QR_CODE", "DATA_MATRIX", "AZTEC", "PDF_417")

    fun render(code: String, format: String, width: Int): Bitmap? = runCatching {
        val f = BarcodeFormat.valueOf(format)
        val square = format == "QR_CODE" || format == "DATA_MATRIX" || format == "AZTEC"
        val w = width
        val h = if (square) width else if (format == "PDF_417") width / 3 else width * 2 / 5
        val hints = mapOf(EncodeHintType.MARGIN to (if (square) 1 else 4), EncodeHintType.CHARACTER_SET to "UTF-8")
        val m = MultiFormatWriter().encode(code, f, w, h, hints)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(m.width)
        for (y in 0 until m.height) {
            for (x in 0 until m.width) row[x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            bmp.setPixels(row, 0, m.width, 0, y, m.width, 1)
        }
        bmp
    }.getOrNull()

    val palette = intArrayOf(0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00897B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt(), 0xFFD81B60.toInt(), 0xFF546E7A.toInt())
}
