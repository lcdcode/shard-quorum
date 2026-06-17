package com.lcdcode.shardquorum.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.nayuki.qrcodegen.QrCode
import java.io.ByteArrayOutputStream

/**
 * Renders one or more labeled QR codes into a single PNG for saving. A shard
 * sheet in encrypted-envelope mode stacks the shard QR and the recovery
 * envelope QR in one file, so a saved shard always carries everything needed to
 * recover - the envelope can never be forgotten separately.
 */
object QrPng {
    private const val SCALE = 12
    private const val QUIET_MODULES = 4
    private const val MARGIN = 24
    private const val SECTION_GAP = 28
    private const val LABEL_TEXT_SIZE = 34f
    private const val LABEL_GAP = 10
    private const val BLACK = 0xff000000.toInt()
    private const val WHITE = 0xffffffff.toInt()

    /** A QR plus the caption drawn above it. */
    data class LabeledQr(val label: String, val content: String)

    fun encodeSheet(sections: List<LabeledQr>): ByteArray {
        require(sections.isNotEmpty()) { "a sheet needs at least one QR" }
        val qrs = sections.map { renderQr(it.content) }
        try {
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = BLACK
                textSize = LABEL_TEXT_SIZE
                typeface = Typeface.DEFAULT_BOLD
            }
            val metrics = labelPaint.fontMetrics
            val labelHeight = (metrics.descent - metrics.ascent).toInt() + LABEL_GAP

            val contentWidth = qrs.maxOf { it.width }
            val width = contentWidth + MARGIN * 2
            val height = MARGIN * 2 +
                qrs.sumOf { labelHeight + it.height } +
                SECTION_GAP * (sections.size - 1)

            val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(sheet)
                canvas.drawColor(WHITE)
                var y = MARGIN
                sections.forEachIndexed { i, section ->
                    canvas.drawText(section.label, MARGIN.toFloat(), y - metrics.ascent, labelPaint)
                    y += labelHeight
                    val qr = qrs[i]
                    canvas.drawBitmap(qr, ((width - qr.width) / 2).toFloat(), y.toFloat(), null)
                    y += qr.height + SECTION_GAP
                }
                val out = ByteArrayOutputStream()
                sheet.compress(Bitmap.CompressFormat.PNG, 100, out)
                return out.toByteArray()
            } finally {
                sheet.recycle()
            }
        } finally {
            qrs.forEach { it.recycle() }
        }
    }

    /** Renders QR [content] to a module-scaled bitmap with a white quiet zone. */
    private fun renderQr(content: String): Bitmap {
        val qr = QrCode.encodeText(content, QrCode.Ecc.MEDIUM)
        val modules = qr.size + QUIET_MODULES * 2
        val dimension = modules * SCALE
        val pixels = IntArray(dimension * dimension) { WHITE }
        for (qy in 0 until qr.size) {
            for (qx in 0 until qr.size) {
                if (!qr.getModule(qx, qy)) continue
                val left = (qx + QUIET_MODULES) * SCALE
                val top = (qy + QUIET_MODULES) * SCALE
                for (dy in 0 until SCALE) {
                    val row = (top + dy) * dimension + left
                    for (dx in 0 until SCALE) pixels[row + dx] = BLACK
                }
            }
        }
        return Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888)
    }
}
