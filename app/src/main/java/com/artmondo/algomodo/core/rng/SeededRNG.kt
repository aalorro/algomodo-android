package com.artmondo.algomodo.core.rng

import kotlin.math.*

/**
 * Deterministic pseudo-random number generator using xorshift128+.
 * Must produce bit-identical output to the web TypeScript version for the same seed.
 */
class SeededRNG(seed: Int) {
    private var s0: Long
    private var s1: Long
    private var s2: Long
    private var s3: Long

    init {
        s0 = seed.toLong()
        s1 = seed.toLong() xor 123456L
        s2 = seed.toLong() xor 789012L
        s3 = seed.toLong() xor 345678L
        // Warm up to avoid poor initial values
        repeat(100) { nextLong() }
    }

    private fun nextLong(): Long {
        var t = s3
        val s = s0
        s3 = s2
        s2 = s1
        s1 = s
        t = t xor (t shl 11)
        t = t xor (t ushr 8)
        s0 = t xor s xor (s ushr 19)
        return s0 + s1
    }

    /** Returns a float in [0.0, 1.0) */
    fun random(): Float {
        val v = nextLong()
        return ((v ushr 11) and 0x1FFFFFFFFFFFFFL).toFloat() / 9007199254740992f
    }

    /** Returns a double in [0.0, 1.0) */
    fun randomDouble(): Double {
        val v = nextLong()
        return ((v ushr 11) and 0x1FFFFFFFFFFFFFL).toDouble() / 9007199254740992.0
    }

    /** Returns an integer in [min, max] inclusive */
    fun integer(min: Int, max: Int): Int {
        return min + (random() * (max - min + 1)).toInt().coerceAtMost(max - min)
    }

    /** Returns a float in [min, max) */
    fun range(min: Float, max: Float): Float {
        return min + random() * (max - min)
    }

    /** Returns a gaussian (normal) distributed value */
    fun gaussian(mean: Float = 0f, stdDev: Float = 1f): Float {
        // Box-Muller transform
        val u1 = random().coerceAtLeast(1e-10f)
        val u2 = random()
        val z0 = sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
        return mean + z0 * stdDev
    }

    /** Returns a random angle in [0, 2*PI) */
    fun randomAngle(): Float {
        return random() * 2f * PI.toFloat()
    }

    /** Returns a random point inside a circle of given radius */
    fun randomPointInCircle(radius: Float): Pair<Float, Float> {
        val angle = randomAngle()
        val r = radius * sqrt(random())
        return Pair(r * cos(angle), r * sin(angle))
    }

    /** Fisher-Yates shuffle */
    fun <T> shuffle(list: MutableList<T>): MutableList<T> {
        for (i in list.size - 1 downTo 1) {
            val j = integer(0, i)
            val temp = list[i]
            list[i] = list[j]
            list[j] = temp
        }
        return list
    }

    /** Pick a random element */
    fun <T> pick(list: List<T>): T {
        return list[integer(0, list.size - 1)]
    }

    /** Pick N random elements without replacement */
    fun <T> pickN(list: List<T>, n: Int): List<T> {
        val copy = list.toMutableList()
        shuffle(copy)
        return copy.take(n.coerceAtMost(list.size))
    }

    /** Returns a boolean with given probability of being true */
    fun boolean(probability: Float = 0.5f): Boolean {
        return random() < probability
    }
}
