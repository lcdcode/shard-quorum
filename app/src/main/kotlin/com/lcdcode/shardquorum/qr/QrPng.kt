package com.lcdcode.shardquorum.qr

import android.graphics.Bitmap
import io.nayuki.qrcodegen.QrCode
import java.io.ByteArrayOutputStream

/**
 * Renders QR content to a PNG byte array for saving to a file. Each QR module
 * becomes a [SCALE]x[SCALE] block of pixels, surrounded by a [QUIET_MODULES]
 * white quiet zone so the saved image scans reliably.
 */
object QrPng {
    private const val SCALE = 12
    private const val QUIET_MODULES = 4
    private const val BLACK = 0xff000000.toInt()
    private const val WHITE = 0xffffffff.toInt()

    fun encode(content: String): ByteArray {
        val qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM)
        val modules = qr.size + QUIET_MODULES * 2
        val dimension = modules * SCALE
        val pixels = IntArray(dimension * dimension) { WHITE }

        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (!qr.getModule(x, y)) continue
                val left = (x + QUIET_MODULES) * SCALE
                val top = (y + QUIET_MODULES) * SCALE
                for (dy in 0 until SCALE) {
                    val row = (top + dy) * dimension + left
                    for (dx in 0 until SCALE) pixels[row + dx] = BLACK
                }
            }
        }

        val bitmap = Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixels(pixels, 0, dimension, 0, 0, dimension, dimension)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
