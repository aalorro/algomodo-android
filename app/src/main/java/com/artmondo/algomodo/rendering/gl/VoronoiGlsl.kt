package com.artmondo.algomodo.rendering.gl

import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared GLSL ES 3.0 helpers for voronoi-family GPU generators.
 *
 * The CPU code historically used a spatial grid for nearest/second-nearest
 * point lookup. On the GPU we instead use a brute-force linear scan because
 * (a) the fragment shader runs in parallel across all pixels and (b) the point
 * counts are small (<= 300 in most generators, 512 cap here to be safe).
 *
 * CPU code path:
 *  - Seed point placement, Lloyd relaxation, and animation drift all stay on
 *    the CPU inside `bindUniforms`. The resulting [px], [py] arrays are
 *    flattened into a vec2 array uniform via [packPoints].
 *  - Distance metric is mapped to an int via [metricId] and uploaded as a
 *    uniform; the GLSL `vmetric()` helper branches on it.
 *
 * GPU code path:
 *  - `uPoints[VORONOI_MAX_POINTS]` holds the seed positions in pixel coords.
 *  - `vmetric(vec2 d)` returns Euclidean (squared), Manhattan, or Chebyshev
 *    distance depending on `uMetric`. For Euclidean callers can sqrt() after.
 *  - `vmetricConcave(vec2 d)` returns the Minkowski p=0.5 metric used by
 *    Crackle's "Concave" mode.
 *  - `voronoiF1F2(vec2 p)` does a single linear scan returning the nearest
 *    and second-nearest distances and their point indices as a vec4
 *    (f1, f2, idx1, idx2).
 */
object VoronoiGlsl {

    /**
     * Maximum number of points per shader. GLES 3.0 guarantees at least 896
     * fragment uniform components; 512 vec2 points = 1024 components which
     * exceeds the spec minimum but is comfortably within real-device limits
     * (typical GLES 3.0 mobile devices expose 1024-4096 vectors).
     */
    const val MAX_POINTS = 512

    const val GLSL_HELPERS = """
        const int VORONOI_MAX_POINTS = $MAX_POINTS;

        uniform vec2 uPoints[VORONOI_MAX_POINTS];
        uniform int uPointCount;
        uniform int uMetric;   // 0 = euclidean (squared), 1 = manhattan, 2 = chebyshev

        // Generic metric. For Euclidean, returns the SQUARED distance — callers
        // sqrt() it before comparing against linear widths.
        float vmetric(vec2 d) {
            if (uMetric == 1) return abs(d.x) + abs(d.y);
            if (uMetric == 2) return max(abs(d.x), abs(d.y));
            return d.x * d.x + d.y * d.y;
        }

        // Like vmetric but returns linear distance for Euclidean (callers that
        // do not want the squared form).
        float vmetricLinear(vec2 d) {
            if (uMetric == 1) return abs(d.x) + abs(d.y);
            if (uMetric == 2) return max(abs(d.x), abs(d.y));
            return sqrt(d.x * d.x + d.y * d.y);
        }

        // Minkowski p=0.5 (Concave) metric used by Crackle's third metric.
        float vmetricConcave(vec2 d) {
            return sqrt(abs(d.x)) + sqrt(abs(d.y));
        }

        // Find nearest and second-nearest point. Returns (f1, f2, idx1, idx2).
        // For Euclidean f1/f2 are squared distances; for the others they are
        // already in linear units. Callers know which metric they bound.
        vec4 voronoiF1F2(vec2 p) {
            float f1 = 1e30; float f2 = 1e30;
            float i1 = 0.0; float i2 = 0.0;
            for (int i = 0; i < VORONOI_MAX_POINTS; i++) {
                if (i >= uPointCount) break;
                float d = vmetric(p - uPoints[i]);
                if (d < f1) {
                    f2 = f1; i2 = i1;
                    f1 = d;  i1 = float(i);
                } else if (d < f2) {
                    f2 = d;  i2 = float(i);
                }
            }
            return vec4(f1, f2, i1, i2);
        }

        // Nearest only, returns (f1, idx1).
        vec3 voronoiF1(vec2 p) {
            float f1 = 1e30; float i1 = 0.0;
            for (int i = 0; i < VORONOI_MAX_POINTS; i++) {
                if (i >= uPointCount) break;
                float d = vmetric(p - uPoints[i]);
                if (d < f1) { f1 = d; i1 = float(i); }
            }
            return vec3(f1, i1, 0.0);
        }
    """

    /** Map a UI metric string to the shader's integer enum. */
    fun metricId(metric: String?): Int = when (metric?.lowercase()) {
        "manhattan" -> 1
        "chebyshev" -> 2
        else -> 0 // euclidean (and concave / unknown)
    }

    /**
     * Pack `count` points into a flat float[count*2] suitable for
     * `glUniform2fv(loc, count, packed, 0)`. Zero-pads up to [MAX_POINTS] so
     * callers can always upload [MAX_POINTS] entries safely.
     */
    fun packPoints(px: FloatArray, py: FloatArray, count: Int): FloatArray {
        val out = FloatArray(MAX_POINTS * 2)
        val n = min(count, MAX_POINTS)
        for (i in 0 until n) {
            out[i * 2] = px[i]
            out[i * 2 + 1] = py[i]
        }
        return out
    }

    /**
     * Shared CPU helper: deterministic Lloyd relaxation that matches the
     * CPU voronoi family's "Relaxed" toggle. Mutates [px]/[py] in place.
     *
     * Uses a coarse sampling step (4 px) and 3 passes — same parameters as the
     * legacy CPU implementations.
     */
    fun lloydRelax(
        px: FloatArray, py: FloatArray, count: Int,
        w: Int, h: Int, metricId: Int,
        passes: Int = 3, sampleStep: Int = 4
    ) {
        if (count <= 0) return
        val isEuclidean = metricId == 0
        val sumX = FloatArray(count)
        val sumY = FloatArray(count)
        val cnt = IntArray(count)
        for (pass in 0 until passes) {
            sumX.fill(0f); sumY.fill(0f); cnt.fill(0)
            var sy = 0
            while (sy < h) {
                val syf = sy.toFloat()
                var sx = 0
                while (sx < w) {
                    val sxf = sx.toFloat()
                    var bd = Float.MAX_VALUE; var bi = 0
                    for (i in 0 until count) {
                        val dx = sxf - px[i]; val dy = syf - py[i]
                        val d = when {
                            isEuclidean -> dx * dx + dy * dy
                            metricId == 1 -> abs(dx) + abs(dy)
                            else -> max(abs(dx), abs(dy))
                        }
                        if (d < bd) { bd = d; bi = i }
                    }
                    sumX[bi] += sxf; sumY[bi] += syf; cnt[bi]++
                    sx += sampleStep
                }
                sy += sampleStep
            }
            for (i in 0 until count) {
                if (cnt[i] > 0) {
                    px[i] = sumX[i] / cnt[i]
                    py[i] = sumY[i] / cnt[i]
                }
            }
        }
    }

    /**
     * Drift points using SimplexNoise. Mirrors the CPU pattern
     * `noise.noise2D(i * key + base, time * 0.2 * speed) * size * scale * amp`.
     *
     * [keyStep] controls per-point variation in noise lookup (typical 0.3-0.5),
     * [driftScale] is the fraction of bitmap size used for the displacement
     * (typical 0.05 for Cells, 0.02 for Centroidal).
     */
    fun animatePoints(
        px: FloatArray, py: FloatArray, count: Int,
        w: Int, h: Int, noise: SimplexNoise, time: Float,
        animSpeed: Float, animAmp: Float,
        keyStep: Float = 0.3f, baseX: Float = 100f, baseY: Float = 200f,
        speedNorm: Float = 0.4f, ampNorm: Float = 0.2f,
        driftScale: Float = 0.05f
    ) {
        if (time <= 0f || animAmp <= 0f) return
        val speed = animSpeed / speedNorm
        val amp = animAmp / ampNorm
        val wf = w.toFloat(); val hf = h.toFloat()
        val t = time * 0.2f * speed
        for (i in 0 until count) {
            px[i] = (px[i] + noise.noise2D(i * keyStep + baseX, t) * wf * driftScale * amp)
                .coerceIn(0f, wf - 1f)
            py[i] = (py[i] + noise.noise2D(i * keyStep + baseY, t) * hf * driftScale * amp)
                .coerceIn(0f, hf - 1f)
        }
    }

    /**
     * Initialise [px]/[py] with [count] uniformly-distributed random points
     * across [w] x [h] using the provided [rng].
     */
    fun scatterPoints(px: FloatArray, py: FloatArray, count: Int, w: Int, h: Int, rng: SeededRNG) {
        val wf = w.toFloat(); val hf = h.toFloat()
        for (i in 0 until count) {
            px[i] = rng.random() * wf
            py[i] = rng.random() * hf
        }
    }
}
