package com.artmondo.algomodo.audio

/**
 * Pre-computed audio frequency band data indexed by time windows.
 * Passed through the params map as "_audioAnalysis" so generators can
 * look up bass/mid/high energy at their current render time.
 */
class AudioAnalysis(
    val durationSec: Float,
    private val windowSec: Float,
    private val bass: FloatArray,
    private val mid: FloatArray,
    private val high: FloatArray,
    private val spectra: Array<FloatArray>
) {
    private val windowCount = bass.size

    private fun windowIndex(time: Float): Int {
        if (windowCount == 0) return 0
        if (durationSec <= 0f || windowSec <= 0f) return 0
        val t = time % durationSec
        if (t.isNaN() || t.isInfinite()) return 0
        return ((t / windowSec).toInt()).coerceIn(0, windowCount - 1)
    }

    fun getBass(time: Float): Float = if (windowCount == 0) 0f else bass[windowIndex(time)]
    fun getMid(time: Float): Float = if (windowCount == 0) 0f else mid[windowIndex(time)]
    fun getHigh(time: Float): Float = if (windowCount == 0) 0f else high[windowIndex(time)]
    fun getSpectrum(time: Float): FloatArray = if (windowCount == 0) FloatArray(0) else spectra[windowIndex(time)]
}
