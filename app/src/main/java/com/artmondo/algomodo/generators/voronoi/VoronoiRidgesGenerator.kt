package com.artmondo.algomodo.generators.voronoi

import android.opengl.GLES30
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import com.artmondo.algomodo.rendering.gl.VoronoiGlsl
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * GPU port. Multi-octave (1..5) voronoi ridge field.
 *
 * Per-octave point arrays are packed into a single uniform vec2 array
 * [uRidgePoints] with per-octave offsets / counts. To fit within GLES 3.0
 * uniform-vector budgets each octave is capped at [MAX_POINTS_PER_OCTAVE].
 * This is a meaningful behavioural change vs the CPU (which capped each
 * octave at 4000) but keeps the visual character intact while running at
 * native framerate.
 */
class VoronoiRidgesGenerator : GpuGenerator {

    override val id = "voronoi-ridges"
    override val family = "voronoi"
    override val styleName = "Voronoi Ridges"
    override val definition =
        "Voronoi ridge patterns where cell boundaries are rendered as glowing, sharp, or smooth luminous ridges against a dark background."
    override val algorithmNotes =
        "GPU shader. Each octave o has cellCount * lacunarity^o points (capped at 384/octave) scattered " +
        "randomly. The shader scans every octave per pixel for F1/F2 and accumulates (1 - normalised edgeDist) * gain^o. " +
        "ridgeSharpness applies pow(intensity, s). Per-octave max edge distance is sampled CPU-side and uploaded."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 5f, 50f),
        Parameter.NumberParam("Ridge Sharpness", "ridgeSharpness", ParamGroup.TEXTURE, "Power curve applied to ridge values — higher = sharper peaks", 0.5f, 4f, 0.1f, 1.5f),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "euclidean: round ridges | manhattan: diamond facets | chebyshev: square crystalline", listOf("euclidean", "manhattan", "chebyshev"), "euclidean"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette", "greyscale", "inverted"), "palette"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.3f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 50f,
        "ridgeSharpness" to 1.5f,
        "distanceMetric" to "euclidean",
        "colorMode" to "palette",
        "animSpeed" to 0.3f,
        "animAmp" to 0.15f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = (params["cellCount"] as? Number)?.toInt() ?: 50
        val octaves = 1
        val lacunarity = 1f
        val gain = 1f
        val ridgeSharpness = (params["ridgeSharpness"] as? Number)?.toFloat() ?: 1.5f
        val distMetric = (params["distanceMetric"] as? String) ?: "euclidean"
        val style = (params["colorMode"] as? String) ?: "palette"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.3f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.15f

        val metricId = when (distMetric.lowercase()) {
            "manhattan" -> 1; "chebyshev" -> 2; else -> 0
        }
        val isEuclidean = metricId == 0
        val styleId = when (style) { "greyscale" -> 1; "inverted" -> 2; else -> 0 }

        val noise = SimplexNoise(seed)
        val avgCellSize = sqrt((width.toFloat() * height.toFloat()) / numPoints)

        val packedPts = FloatArray(MAX_TOTAL_POINTS * 2)
        val octOffset = IntArray(MAX_OCTAVES)
        val octCount = IntArray(MAX_OCTAVES)
        val octAmp = FloatArray(MAX_OCTAVES)
        val octInvMaxEd = FloatArray(MAX_OCTAVES)

        var ampSum = 0f
        var cursor = 0
        for (oct in 0 until octaves) {
            val octRng = SeededRNG(seed + oct * 7919)
            val freq = lacunarity.pow(oct.toFloat())
            val amp = gain.pow(oct.toFloat())
            val requested = (numPoints * freq).toInt().coerceAtLeast(3)
            val octPoints = requested.coerceAtMost(MAX_POINTS_PER_OCTAVE)

            val opx = FloatArray(octPoints); val opy = FloatArray(octPoints)
            for (i in 0 until octPoints) {
                opx[i] = octRng.random() * width
                opy[i] = octRng.random() * height
            }

            if (time > 0f) {
                val driftRange = animAmp * avgCellSize / freq
                for (i in 0 until octPoints) {
                    opx[i] = (opx[i] + noise.noise2D(i * 0.3f + 70f + oct * 100f, time * animSpeed) * driftRange).coerceIn(0f, width - 1f)
                    opy[i] = (opy[i] + noise.noise2D(i * 0.3f + 170f + oct * 100f, time * animSpeed) * driftRange).coerceIn(0f, height - 1f)
                }
            }

            // Sample 50 random pixels for maxEd normalisation (was 80 on CPU; halved here
            // because brute-force scan over octPoints can be expensive for big octaves).
            var maxEd = 1f
            val sampleRng = SeededRNG(seed + oct * 3571)
            for (s in 0 until 50) {
                val sx = sampleRng.random() * width; val sy = sampleRng.random() * height
                var sf1 = Float.MAX_VALUE; var sf2 = Float.MAX_VALUE
                for (i in 0 until octPoints) {
                    val dx = sx - opx[i]; val dy = sy - opy[i]
                    val d = when {
                        isEuclidean -> dx * dx + dy * dy
                        metricId == 1 -> abs(dx) + abs(dy)
                        else -> max(abs(dx), abs(dy))
                    }
                    if (d < sf1) { sf2 = sf1; sf1 = d } else if (d < sf2) { sf2 = d }
                }
                val ed = if (isEuclidean) sqrt(sf2) - sqrt(sf1) else sf2 - sf1
                if (ed > maxEd) maxEd = ed
            }

            octOffset[oct] = cursor
            octCount[oct] = octPoints
            octAmp[oct] = amp
            octInvMaxEd[oct] = 1f / maxEd
            ampSum += amp

            for (i in 0 until octPoints) {
                packedPts[(cursor + i) * 2]     = opx[i]
                packedPts[(cursor + i) * 2 + 1] = opy[i]
            }
            cursor += octPoints
        }

        val invAmpSum = 1f / ampSum

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uRidgePoints"),
            MAX_TOTAL_POINTS, packedPts, 0)
        GLES30.glUniform1iv(GLES30.glGetUniformLocation(programId, "uOctOffset"),
            MAX_OCTAVES, octOffset, 0)
        GLES30.glUniform1iv(GLES30.glGetUniformLocation(programId, "uOctCount"),
            MAX_OCTAVES, octCount, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uOctAmp"),
            MAX_OCTAVES, octAmp, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uOctInvMaxEd"),
            MAX_OCTAVES, octInvMaxEd, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uStyleId"), styleId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRidgeSharpness"), ridgeSharpness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvAmpSum"), invAmpSum)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        const int MAX_RIDGE_OCTAVES = $MAX_OCTAVES;
        const int MAX_POINTS_PER_OCTAVE = $MAX_POINTS_PER_OCTAVE;
        const int MAX_RIDGE_POINTS = $MAX_TOTAL_POINTS;

        uniform vec2 uRidgePoints[MAX_RIDGE_POINTS];
        uniform int uOctOffset[MAX_RIDGE_OCTAVES];
        uniform int uOctCount[MAX_RIDGE_OCTAVES];
        uniform float uOctAmp[MAX_RIDGE_OCTAVES];
        uniform float uOctInvMaxEd[MAX_RIDGE_OCTAVES];
        uniform int uOctaves;
        uniform int uMetric;
        uniform int uStyleId;
        uniform float uRidgeSharpness;
        uniform float uInvAmpSum;

        out vec4 fragColor;

        void main() {
            vec2 p = gl_FragCoord.xy;
            float ridgeSum = 0.0;
            int nearestIdx = 0;

            for (int oct = 0; oct < MAX_RIDGE_OCTAVES; oct++) {
                if (oct >= uOctaves) break;
                int off = uOctOffset[oct];
                int cnt = uOctCount[oct];

                float f1 = 1e30; float f2 = 1e30; int ni = 0;
                for (int i = 0; i < MAX_POINTS_PER_OCTAVE; i++) {
                    if (i >= cnt) break;
                    vec2 d = p - uRidgePoints[off + i];
                    float dist;
                    if (uMetric == 1)      dist = abs(d.x) + abs(d.y);
                    else if (uMetric == 2) dist = max(abs(d.x), abs(d.y));
                    else                   dist = dot(d, d);    // euclidean squared
                    if (dist < f1) { f2 = f1; f1 = dist; ni = i; }
                    else if (dist < f2) { f2 = dist; }
                }

                float edgeDist;
                if (uMetric == 0) edgeDist = sqrt(f2) - sqrt(f1);
                else              edgeDist = f2 - f1;
                float normalized = clamp(edgeDist * uOctInvMaxEd[oct], 0.0, 1.0);
                ridgeSum += (1.0 - normalized) * uOctAmp[oct];

                if (oct == 0) nearestIdx = ni;
            }

            float ridgeNorm = clamp(ridgeSum * uInvAmpSum, 0.0, 1.0);
            float intensity = pow(ridgeNorm, uRidgeSharpness);

            vec3 col;
            if (uStyleId == 1) {
                // greyscale
                col = vec3(intensity);
            } else if (uStyleId == 2) {
                // inverted: palette * (1 - intensity)
                float t = mod(float(nearestIdx), 5.0) / 4.0;
                col = palette_color(t) * (1.0 - intensity);
            } else {
                // palette: palette * intensity
                float t = mod(float(nearestIdx), 5.0) / 4.0;
                col = palette_color(t) * intensity;
            }

            fragColor = vec4(col, 1.0);
        }
    """

    companion object {
        const val MAX_OCTAVES = 5
        const val MAX_POINTS_PER_OCTAVE = 384
        const val MAX_TOTAL_POINTS = MAX_OCTAVES * MAX_POINTS_PER_OCTAVE  // 1920
    }
}
