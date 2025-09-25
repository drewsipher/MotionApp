package com.example.motionapp

import android.graphics.Bitmap
import android.graphics.Color

object MotionFilters {
    private class ScratchBuffers(var current: IntArray = IntArray(0), var previous: IntArray = IntArray(0)) {
        fun ensure(size: Int) {
            if (current.size != size) current = IntArray(size)
            if (previous.size != size) previous = IntArray(size)
        }
    }

    private val scratch = ThreadLocal.withInitial { ScratchBuffers() }

    // Takes current and previous frame, inverts current, then averages with previous.
    // weight is how much to weight the current inverted vs previous (0..1).
    fun invertAndAverage(
        current: Bitmap,
        previous: Bitmap,
        weight: Float,
        reuse: Bitmap? = null
    ): Bitmap {
        val w = minOf(current.width, previous.width)
        val h = minOf(current.height, previous.height)
        val out = if (reuse != null && reuse.width == w && reuse.height == h && reuse.config == Bitmap.Config.ARGB_8888 && !reuse.isRecycled) {
            reuse
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }

        val scratchBuffers = scratch.get()
        scratchBuffers.ensure(w * h)
        val cPixels = scratchBuffers.current
        val pPixels = scratchBuffers.previous
        current.getPixels(cPixels, 0, w, 0, 0, w, h)
        previous.getPixels(pPixels, 0, w, 0, 0, w, h)

        val invWeight = 1f - weight
        for (i in 0 until w * h) {
            val c = cPixels[i]
            val p = pPixels[i]
            val cr = 255 - Color.red(c)
            val cg = 255 - Color.green(c)
            val cb = 255 - Color.blue(c)
            val pr = Color.red(p)
            val pg = Color.green(p)
            val pb = Color.blue(p)
            val r = (cr * weight + pr * invWeight).toInt().coerceIn(0, 255)
            val g = (cg * weight + pg * invWeight).toInt().coerceIn(0, 255)
            val b = (cb * weight + pb * invWeight).toInt().coerceIn(0, 255)
            cPixels[i] = Color.argb(255, r, g, b)
        }
        out.setPixels(cPixels, 0, w, 0, 0, w, h)
        return out
    }
}
