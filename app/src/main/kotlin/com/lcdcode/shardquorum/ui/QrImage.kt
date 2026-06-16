package com.lcdcode.shardquorum.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

/**
 * Renders [content] as a QR code, one bitmap pixel per module, scaled up with
 * nearest-neighbor filtering so modules stay perfectly crisp at any size. The
 * white padding supplies the quiet zone the QR spec requires for scanning.
 */
@Composable
fun QrImage(content: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { renderQrBitmap(content) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.White)
            .padding(8.dp),
        filterQuality = FilterQuality.None,
    )
}

private fun renderQrBitmap(content: String): Bitmap {
    val qr = io.nayuki.qrcodegen.QrCode.encodeText(content, io.nayuki.qrcodegen.QrCode.Ecc.MEDIUM)
    val bitmap = Bitmap.createBitmap(qr.size, qr.size, Bitmap.Config.ARGB_8888)
    for (y in 0 until qr.size) {
        for (x in 0 until qr.size) {
            bitmap.setPixel(x, y, if (qr.getModule(x, y)) BLACK else WHITE)
        }
    }
    return bitmap
}

private const val BLACK = 0xff000000.toInt()
private const val WHITE = 0xffffffff.toInt()
