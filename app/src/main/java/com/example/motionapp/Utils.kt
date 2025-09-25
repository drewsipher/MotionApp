package com.example.motionapp

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

fun ImageProxy.toBitmap(): Bitmap? {
    return try {
        // Convert YUV_420_888 to NV21 -> JPEG -> Bitmap
        yuv420ToBitmap(this)
    } catch (e: Exception) {
        null
    }
}

private fun yuv420ToBitmap(image: ImageProxy): Bitmap? {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    val chromaRowStride = image.planes[1].rowStride
    val chromaPixelStride = image.planes[1].pixelStride

    var offset = ySize
    val width = image.width
    val height = image.height

    // Convert YUV_420_888 to NV21 format
    for (row in 0 until height / 2) {
        for (col in 0 until width / 2) {
            val uIndex = row * chromaRowStride + col * chromaPixelStride
            val vIndex = row * image.planes[2].rowStride + col * image.planes[2].pixelStride
            nv21[offset++] = vBuffer[vIndex]
            nv21[offset++] = uBuffer[uIndex]
        }
    }

    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val cropRect = image.cropRect ?: android.graphics.Rect(0, 0, width, height)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(cropRect, 80, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val m = Matrix()
    m.postRotate(degrees.toFloat())
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}

fun Bitmap.mirrorHorizontally(): Bitmap {
    val m = Matrix()
    m.preScale(-1f, 1f)
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}
