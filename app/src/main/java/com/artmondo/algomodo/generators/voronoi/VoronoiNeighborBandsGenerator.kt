package com.artmondo.algomodo.generators.voronoi

import android.graphics.Color
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
import kotlin.math.sqrt

/**
 * Hybrid CPU/GPU port. The neighbour count for each cell requires a
 * connectivity analysis that needs the cell map — we keep that on the CPU
 * (at low resolution ~200×200) but offload the actual per-pixel render to
 * the GPU. Per-cell colours derived from neighbour counts are uploaded as a
 * vec3 uniform array.
 */
class VoronoiNeighborBandsGenerator : GpuGenerator {

    override val id = "voronoi-neighbor-bands"
    override val family = "voronoi"
    override val styleName = "Voronoi Neighbor Bands"
    override val definition =
        "Voronoi cells coloured by their number of neighbours, creating distinct bands that highlight the topology of the tessellation."
    override val algorithmNotes =
        "Hybrid CPU + GPU. CPU builds a low-resolution cell map and computes per-cell neighbour counts " +
        "via 4-neighbour adjacency scan. Per-cell colours (flat / gradient / alternating) are pre-computed " +
        "and uploaded as a vec3 uniform array. The GPU shader does the final F1/F2 voronoi render and " +
        "looks up the cell colour by index."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 150f, 5f, 35f),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COMPOSITION, "Number of concentric neighbor rings around each cell — each ring gets the next palette color", 1f, 12f, 1f, 4f),
        Parameter.SelectParam("Band Mode", "bandMode", ParamGroup.TEXTURE, "flat = solid color per ring; gradient = smooth blend between rings; alternating = rings flip between two palette ends", listOf("flat", "gradient", "alternating"), "flat"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 4f, 0.5f, 1f),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 35f,
        "bandCount" to 4f,
        "bandMode" to "flat",
        "borderWidth" to 1f,
        "distanceMetric" to "Euclidean",
        "animSpeed" to 0.4f,
        "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 35)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val bandCount = ((params["bandCount"] as? Number)?.toInt() ?: 4).coerceAtLeast(1)
        val bandMode = (params["bandMode"] as? String) ?: "flat"
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = VoronoiGlsl.metricId(distanceMetric)
        val bandModeId = when (bandMode) { "gradient" -> 1; "alternating" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        VoronoiGlsl.scatterPoints(px, py, numPoints, width, height, rng)

        if (time > 0f) {
            val noise = SimplexNoise(seed)
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = width.toFloat(); val hf = height.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 60f, time * 0.15f * speed) * wf * 0.04f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 160f, time * 0.15f * speed) * hf * 0.04f * amp).coerceIn(0f, hf - 1f)
            }
        }

        // ── Low-res cell map for adjacency analysis ──
        // Resolution is independent of the final render — ~200 cells along the
        // longest dimension is plenty for 4-neighbour adjacency counting.
        val targetMax = 200
        val scale = targetMax.toFloat() / max(width, height).toFloat()
        val mw = max(1, (width * scale).toInt())
        val mh = max(1, (height * scale).toInt())
        val sx = width.toFloat() / mw; val sy = height.toFloat() / mh
        val cellMap = IntArray(mw * mh)
        for (row in 0 until mh) {
            val ry = (row + 0.5f) * sy
            val rowOff = row * mw
            for (col in 0 until mw) {
                val rx = (col + 0.5f) * sx
                var bd = Float.MAX_VALUE; var bi = 0
                for (i in 0 until numPoints) {
                    val dx = rx - px[i]; val dy = ry - py[i]
                    val d = when (metricId) {
                        1 -> abs(dx) + abs(dy)
                        2 -> max(abs(dx), abs(dy))
                        else -> dx * dx + dy * dy
                    }
                    if (d < bd) { bd = d; bi = i }
                }
                cellMap[rowOff + col] = bi
            }
        }

        // Adjacency scan: 4-neighbour transitions
        val neighbours = Array(numPoints) { mutableSetOf<Int>() }
        for (row in 0 until mh) {
            val rowOff = row * mw
            for (col in 0 until mw) {
                val c = cellMap[rowOff + col]
                if (col + 1 < mw) {
                    val right = cellMap[rowOff + col + 1]
                    if (right != c) { neighbours[c].add(right); neighbours[right].add(c) }
                }
                if (row + 1 < mh) {
                    val below = cellMap[(row + 1) * mw + col]
                    if (below != c) { neighbours[c].add(below); neighbours[below].add(c) }
                }
            }
        }

        val neighborCounts = IntArray(numPoints) { neighbours[it].size }
        val maxNeighbors = (neighborCounts.maxOrNull() ?: 1).coerceAtLeast(1)
        val minNeighbors = neighborCounts.minOrNull() ?: 0
        val range = (maxNeighbors - minNeighbors).coerceAtLeast(1)

        val colors = palette.colorInts()
        val colorsSize = colors.size
        val lut = if (bandModeId == 1) palette.buildLut(256) else null

        // Per-cell colours
        val cellCols = FloatArray(VoronoiGlsl.MAX_POINTS * 3)
        for (i in 0 until numPoints) {
            val nc = neighborCounts[i]
            val c = when (bandModeId) {
                1 -> {
                    val t = (nc - minNeighbors).toFloat() / range
                    lut!![(t * 255f).toInt().coerceIn(0, 255)]
                }
                2 -> {
                    val bandIdx = (nc - minNeighbors) % bandCount
                    if (bandIdx % 2 == 0) colors.first() else colors.last()
                }
                else -> {
                    val bandIdx = (nc - minNeighbors) % bandCount
                    colors[bandIdx % colorsSize]
                }
            }
            cellCols[i * 3] = Color.red(c) / 255f
            cellCols[i * 3 + 1] = Color.green(c) / 255f
            cellCols[i * 3 + 2] = Color.blue(c) / 255f
        }

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uCellColors"),
            VoronoiGlsl.MAX_POINTS, cellCols, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBorderWidth"), borderWidth)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform vec3 uCellColors[VORONOI_MAX_POINTS];
        uniform float uBorderWidth;

        out vec4 fragColor;

        void main() {
            vec4 f = voronoiF1F2(gl_FragCoord.xy);
            float f1Lin = (uMetric == 0) ? sqrt(f.x) : f.x;
            float f2Lin = (uMetric == 0) ? sqrt(f.y) : f.y;

            vec3 col;
            if (uBorderWidth > 0.0 && (f2Lin - f1Lin) < uBorderWidth * 2.0) {
                col = vec3(0.0);
            } else {
                int idx = int(f.z + 0.5);
                col = uCellColors[idx];
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
