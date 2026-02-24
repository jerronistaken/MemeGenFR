package com.example.facialrecog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream


// ══════════════════════════════════════════════════════════════════════
//  UTILITY FUNCTIONS  (unchanged from original)
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalGetImage::class)
internal fun imageProxyToUprightBitmap(
    imageProxy: ImageProxy,
    rotationDegrees: Int,
    mirrorX: Boolean
): Bitmap {
    val nv21     = yuv420888ToNv21(imageProxy)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
    val out      = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
    val jpegBytes  = out.toByteArray()
    val rawBitmap  = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val matrix = Matrix().apply {
        if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        if (mirrorX) postScale(-1f, 1f)
    }
    val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    if (rotated != rawBitmap) rawBitmap.recycle()
    return rotated
}

internal fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width   = image.width; val height = image.height
    val yPlane  = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
    yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()
    val out = ByteArray(width * height + width * height / 2)
    var idx = 0
    for (row in 0 until height) {
        val rowStart = row * yPlane.rowStride
        for (col in 0 until width) out[idx++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
    }
    for (row in 0 until height / 2) {
        val uRow = row * uPlane.rowStride; val vRow = row * vPlane.rowStride
        for (col in 0 until width / 2) {
            out[idx++] = vBuffer.get(vRow + col * vPlane.pixelStride)
            out[idx++] = uBuffer.get(uRow + col * uPlane.pixelStride)
        }
    }
    return out
}