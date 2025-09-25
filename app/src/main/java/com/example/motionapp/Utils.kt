package com.example.motionapp

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class ImageProxyBitmapConverter {
    private var argbBuffer: IntArray = IntArray(0)

    fun convert(image: ImageProxy): Bitmap {
        val crop = image.cropRect ?: Rect(0, 0, image.width, image.height)
        val outputWidth = crop.width()
        val outputHeight = crop.height()

        ensureCapacity(crop)

        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)

        val isRgba = image.format == PixelFormat.RGBA_8888 ||
            (image.format == ImageFormat.UNKNOWN && image.planes.size == 1 && image.planes[0].pixelStride == 4)

        if (isRgba) {
            val plane = PlaneReader(image.planes[0])
            val rowStride = image.planes[0].rowStride
            val pixelStride = image.planes[0].pixelStride

            var outputIndex = 0
            for (row in crop.top until crop.bottom) {
                var planeIndex = row * rowStride + crop.left * pixelStride
                repeat(outputWidth) {
                    val r = plane.get(planeIndex)
                    val g = plane.get(planeIndex + 1)
                    val b = plane.get(planeIndex + 2)
                    val a = plane.get(planeIndex + 3)
                    argbBuffer[outputIndex++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    planeIndex += pixelStride
                }
            }
        } else {
            val yPlane = PlaneReader(image.planes[0])
            val uPlane = PlaneReader(image.planes[1])
            val vPlane = PlaneReader(image.planes[2])

            val yRowStride = image.planes[0].rowStride
            val yPixelStride = image.planes[0].pixelStride
            val uRowStride = image.planes[1].rowStride
            val uPixelStride = image.planes[1].pixelStride
            val vRowStride = image.planes[2].rowStride
            val vPixelStride = image.planes[2].pixelStride

            var outputIndex = 0
            for (row in crop.top until crop.bottom) {
                val yRow = row * yRowStride
                val uRow = (row / 2) * uRowStride
                val vRow = (row / 2) * vRowStride
                var col = crop.left
                while (col < crop.right) {
                    val uvCol = (col / 2) * uPixelStride
                    val u = uPlane.get(uRow + uvCol) - 128
                    val v = vPlane.get(vRow + uvCol) - 128

                    val yIndex1 = yRow + col * yPixelStride
                    argbBuffer[outputIndex++] = yuvToArgbFast(yPlane.get(yIndex1), u, v)
                    col++

                    if (col < crop.right) {
                        val yIndex2 = yRow + col * yPixelStride
                        argbBuffer[outputIndex++] = yuvToArgbFast(yPlane.get(yIndex2), u, v)
                        col++
                    }
                }
            }
        }

        bitmap.setPixels(argbBuffer, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        return bitmap
    }

    private fun ensureCapacity(cropRect: Rect) {
        val outputSize = cropRect.width() * cropRect.height()
        if (argbBuffer.size != outputSize) {
            argbBuffer = IntArray(outputSize)
        }
    }

    private class PlaneReader(plane: ImageProxy.PlaneProxy) {
        private val buffer: ByteBuffer = plane.buffer
        private val array: ByteArray? = if (buffer.hasArray()) buffer.array() else null
        private val offset: Int = if (buffer.hasArray()) buffer.arrayOffset() else 0

        fun get(index: Int): Int {
            val value = if (array != null) array[offset + index] else buffer.get(index)
            return value.toInt() and 0xFF
        }
    }

    private fun yuvToArgbFast(yVal: Int, u: Int, v: Int): Int {
        val y = (yVal - 16).coerceAtLeast(0)
        val y1192 = 1192 * y

        var r = y1192 + 1634 * v
        var g = y1192 - 833 * v - 400 * u
        var b = y1192 + 2066 * u

        if (r < 0) r = 0 else if (r > 262143) r = 262143
        if (g < 0) g = 0 else if (g > 262143) g = 262143
        if (b < 0) b = 0 else if (b > 262143) b = 262143

        return (0xFF shl 24) or
            ((r shr 10) shl 16) or
            ((g shr 10) shl 8) or
            (b shr 10)
    }
}
