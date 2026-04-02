package com.artmondo.algomodo.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cooley-Tukey radix-2 FFT.
 * Input size must be a power of 2.
 */
object FFT {

    /** In-place FFT. [re] and [im] are modified. */
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        if (n <= 1) return

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * Math.PI / len
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until halfLen) {
                    val evenIdx = i + k
                    val oddIdx = i + k + halfLen
                    val tRe = curRe * re[oddIdx] - curIm * im[oddIdx]
                    val tIm = curRe * im[oddIdx] + curIm * re[oddIdx]
                    re[oddIdx] = re[evenIdx] - tRe
                    im[oddIdx] = im[evenIdx] - tIm
                    re[evenIdx] = re[evenIdx] + tRe
                    im[evenIdx] = im[evenIdx] + tIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newCurRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Compute magnitude spectrum from PCM samples. Returns magnitudes of size n/2. */
    fun magnitudeSpectrum(samples: FloatArray): FloatArray {
        val n = samples.size
        val re = samples.copyOf()
        val im = FloatArray(n)
        transform(re, im)
        val half = n / 2
        val mag = FloatArray(half)
        val invN = 2f / n
        for (i in 0 until half) {
            mag[i] = sqrt(re[i] * re[i] + im[i] * im[i]) * invN
        }
        return mag
    }
}
