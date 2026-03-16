package com.artmondo.algomodo.generators.procedural

import com.artmondo.algomodo.core.rng.SeededRNG

/**
 * Fast hash-based 2D value noise with Hermite smoothstep interpolation.
 * ~3× faster than SimplexNoise for generators that don't need gradient quality.
 */
class ValueNoise(seed: Int) {
    private val PERM = IntArray(512)
    private val VALS = FloatArray(256)

    init {
        val rng = SeededRNG(seed)
        val perm = IntArray(256) { it }
        for (i in 0 until 256) {
            VALS[i] = rng.random() * 2f - 1f
        }
        for (i in 255 downTo 1) {
            val j = (rng.random() * (i + 1)).toInt()
            val tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp
        }
        for (i in 0 until 256) {
            PERM[i] = perm[i]
            PERM[i + 256] = perm[i]
        }
    }

    /** Returns value in [-1, 1] for any float coordinates. */
    fun noise(x: Float, y: Float): Float {
        val xb = x + 65536f
        val yb = y + 65536f
        val xi = xb.toInt()
        val yi = yb.toInt()
        val fx = xb - xi
        val fy = yb - yi
        val ix = xi and 255
        val iy = yi and 255
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val py0 = PERM[iy]
        val py1 = PERM[iy + 1]
        val a = VALS[PERM[ix + py0]]
        val b = VALS[PERM[ix + 1 + py0]]
        val c = VALS[PERM[ix + py1]]
        val d = VALS[PERM[ix + 1 + py1]]
        val p = a + sx * (b - a)
        val q = c + sx * (d - c)
        return p + sy * (q - p)
    }

    /** Multi-octave FBM using precomputed weights. */
    fun fbm(x: Float, y: Float, octaves: Int, lacunarity: Float = 2f, gain: Float = 0.5f): Float {
        var value = 0f
        var amp = 1f
        var freq = 1f
        var totalAmp = 0f
        for (o in 0 until octaves) {
            value += noise(x * freq, y * freq) * amp
            totalAmp += amp
            freq *= lacunarity
            amp *= gain
        }
        return value / totalAmp
    }
}
