package com.artmondo.algomodo.core.rng

import kotlin.math.*

/**
 * Simplex noise implementation matching the web TypeScript version.
 * Produces deterministic noise values for given seed.
 */
class SimplexNoise(seed: Int) {
    private val perm = IntArray(512)
    private val permMod12 = IntArray(512)

    companion object {
        private val grad3 = arrayOf(
            floatArrayOf(1f, 1f, 0f), floatArrayOf(-1f, 1f, 0f), floatArrayOf(1f, -1f, 0f),
            floatArrayOf(-1f, -1f, 0f), floatArrayOf(1f, 0f, 1f), floatArrayOf(-1f, 0f, 1f),
            floatArrayOf(1f, 0f, -1f), floatArrayOf(-1f, 0f, -1f), floatArrayOf(0f, 1f, 1f),
            floatArrayOf(0f, -1f, 1f), floatArrayOf(0f, 1f, -1f), floatArrayOf(0f, -1f, -1f)
        )

        private const val F2 = 0.5f * (1.7320508075688772f - 1f) // (sqrt(3) - 1) / 2
        private const val G2 = (3f - 1.7320508075688772f) / 6f   // (3 - sqrt(3)) / 6
    }

    init {
        val rng = SeededRNG(seed)
        val p = IntArray(256) { it }
        // Shuffle using seeded RNG
        for (i in 255 downTo 1) {
            val j = rng.integer(0, i)
            val tmp = p[i]
            p[i] = p[j]
            p[j] = tmp
        }
        for (i in 0 until 512) {
            perm[i] = p[i and 255]
            permMod12[i] = perm[i] % 12
        }
    }

    /** 2D simplex noise, returns value in [-1, 1] */
    fun noise2D(xin: Float, yin: Float): Float {
        val s = (xin + yin) * F2
        val i = floor(xin + s).toInt()
        val j = floor(yin + s).toInt()
        val t = (i + j).toFloat() * G2
        val x0 = xin - (i - t)
        val y0 = yin - (j - t)

        val i1: Int
        val j1: Int
        if (x0 > y0) {
            i1 = 1; j1 = 0
        } else {
            i1 = 0; j1 = 1
        }

        val x1 = x0 - i1 + G2
        val y1 = y0 - j1 + G2
        val x2 = x0 - 1f + 2f * G2
        val y2 = y0 - 1f + 2f * G2

        val ii = i and 255
        val jj = j and 255
        val gi0 = permMod12[ii + perm[jj]]
        val gi1 = permMod12[ii + i1 + perm[jj + j1]]
        val gi2 = permMod12[ii + 1 + perm[jj + 1]]

        var n0 = 0f
        var t0 = 0.5f - x0 * x0 - y0 * y0
        if (t0 >= 0) {
            t0 *= t0
            n0 = t0 * t0 * dot2(grad3[gi0], x0, y0)
        }

        var n1 = 0f
        var t1 = 0.5f - x1 * x1 - y1 * y1
        if (t1 >= 0) {
            t1 *= t1
            n1 = t1 * t1 * dot2(grad3[gi1], x1, y1)
        }

        var n2 = 0f
        var t2 = 0.5f - x2 * x2 - y2 * y2
        if (t2 >= 0) {
            t2 *= t2
            n2 = t2 * t2 * dot2(grad3[gi2], x2, y2)
        }

        return 70f * (n0 + n1 + n2)
    }

    /** Fractal Brownian Motion */
    fun fbm(
        x: Float, y: Float,
        octaves: Int = 6,
        lacunarity: Float = 2f,
        gain: Float = 0.5f
    ): Float {
        var value = 0f
        var amplitude = 1f
        var frequency = 1f
        var maxValue = 0f

        for (i in 0 until octaves) {
            value += amplitude * noise2D(x * frequency, y * frequency)
            maxValue += amplitude
            amplitude *= gain
            frequency *= lacunarity
        }

        return value / maxValue
    }

    /** Ridged multifractal noise */
    fun ridged(
        x: Float, y: Float,
        octaves: Int = 6,
        lacunarity: Float = 2f,
        gain: Float = 0.5f
    ): Float {
        var value = 0f
        var amplitude = 1f
        var frequency = 1f
        var maxValue = 0f

        for (i in 0 until octaves) {
            val n = 1f - abs(noise2D(x * frequency, y * frequency))
            value += amplitude * n * n
            maxValue += amplitude
            amplitude *= gain
            frequency *= lacunarity
        }

        return value / maxValue
    }

    /** Turbulence noise (absolute value of each octave) */
    fun turbulence(
        x: Float, y: Float,
        octaves: Int = 6,
        lacunarity: Float = 2f,
        gain: Float = 0.5f
    ): Float {
        var value = 0f
        var amplitude = 1f
        var frequency = 1f
        var maxValue = 0f

        for (i in 0 until octaves) {
            value += amplitude * abs(noise2D(x * frequency, y * frequency))
            maxValue += amplitude
            amplitude *= gain
            frequency *= lacunarity
        }

        return value / maxValue
    }

    private fun dot2(g: FloatArray, x: Float, y: Float): Float {
        return g[0] * x + g[1] * y
    }
}
