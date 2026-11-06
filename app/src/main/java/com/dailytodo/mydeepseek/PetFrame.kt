package com.dailytodo.mydeepseek

import kotlin.math.ceil

/** Primitive frame data crossing the WebView bridge; upstream physics is unchanged. */
data class PetFrame(
    val x: Double, val y: Double, val width: Double, val height: Double,
    val boxX: Double, val boxY: Double, val size: Double, val bottomPad: Double,
    val vx: Double, val vy: Double
) {
    fun valid() = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
        boxX.isFinite() && boxY.isFinite() && size.isFinite() && bottomPad.isFinite() && vx.isFinite() && vy.isFinite() &&
        width > 0 && height > 0 && size in 40.0..600.0

    companion object {
        // Round the extent, not each moving edge separately: fractional origins
        // must not resize Chromium's surface by one pixel on every drag frame.
        fun extent(pixels: Double) = ceil(pixels - 0.000001).toInt().coerceAtLeast(1)
    }
}

/** Only the newest frame is painted when multiple bridge calls arrive in one vsync. */
class FrameMailbox<T> {
    private var latest: T? = null
    private var scheduled = false
    @Synchronized fun offer(value: T): Boolean {
        latest = value
        if (scheduled) return false
        scheduled = true
        return true
    }
    @Synchronized fun take(): T? {
        val result = latest
        latest = null; scheduled = false
        return result
    }
}
