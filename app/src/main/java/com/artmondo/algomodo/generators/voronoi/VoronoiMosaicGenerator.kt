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
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * GPU port. Voronoi cells separated by visible "grout" bands and optionally
 * raised/inset tile-shading inside each cell.
 *
 * Per-cell colours (palette-cycle / palette-angle / palette-distance) are
 * computed on the CPU and uploaded as a vec3 uniform array indexed by point
 * id, matching the original [cellColors] precomputation.
 */
class VoronoiMosaicGenerator : GpuGenerator {

    override val id = "voronoi-mosaic"
    override val family = "voronoi"
    override val styleName = "Voronoi Mosaic"
    override val definition =
        "Mosaic-style Voronoi cells with visible grout gaps between tiles, resembling broken tile or stained glass artwork."
    override val algorithmNotes =
        "GPU shader. Per cell colour pre-computed CPU-side via palette-cycle / angle / distance. " +
        "Pixels with F2-F1 below the grout threshold take the grout colour. Raised/Inset styles add a per-tile " +
        "gradient driven by how far into the cell the pixel is."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 5f, 60f),
        Parameter.NumberParam("Grout Width", "groutWidth", ParamGroup.GEOMETRY, "Width of the grout lines between tiles", 0f, 12f, 0.5f, 3f),
        Parameter.SelectParam("Grout Color", "groutColor", ParamGroup.COLOR, "", listOf("grey", "white", "black", "palette-last"), "grey"),
        Parameter.SelectParam("Tile Style", "tileStyle", ParamGroup.TEXTURE, "flat = solid color; raised/inset adds a shading gradient inside each tile", listOf("flat", "raised", "inset"), "flat"),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette-cycle", "palette-angle", "palette-distance"), "palette-cycle"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 60f, "groutWidth" to 3f, "groutColor" to "grey",
        "tileStyle" to "flat", "colorMode" to "palette-cycle",
        "distanceMetric" to "Euclidean",
        "animSpeed" to 0.4f, "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 60)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val gap = (params["groutWidth"] as? Number)?.toFloat() ?: 3f
        val groutColor = (params["groutColor"] as? String) ?: "grey"
        val tileStyle = (params["tileStyle"] as? String) ?: "flat"
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = VoronoiGlsl.metricId(metric)
        val tileStyleId = when (tileStyle) { "raised" -> 1; "inset" -> 2; else -> 0 }
        val colorModeId = when (colorMode) { "palette-angle" -> 1; "palette-distance" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        VoronoiGlsl.scatterPoints(px, py, numPoints, width, height, rng)
        if (time > 0f && animAmp > 0f) {
            val noise = SimplexNoise(seed)
            val speed = animSpeed / 0.4f; val amp = animAmp / 0.2f
            val wf = width.toFloat(); val hf = height.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.3f + 80f, time * 0.12f * speed) * wf * 0.03f * amp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.3f + 180f, time * 0.12f * speed) * hf * 0.03f * amp).coerceIn(0f, hf - 1f)
            }
        }

        // Pre-compute per-cell colours
        val cellCols = FloatArray(VoronoiGlsl.MAX_POINTS * 3)
        val colorInts = palette.colorInts()
        when (colorModeId) {
            1 -> {
                val halfW = width / 2f; val halfH = height / 2f
                val invTwoPi = 1f / (2f * Math.PI.toFloat())
                val lut = palette.buildLut(256)
                for (i in 0 until numPoints) {
                    val ang = atan2(py[i] - halfH, px[i] - halfW)
                    val t = ((ang + Math.PI.toFloat()) * invTwoPi).coerceIn(0f, 1f)
                    val c = lut[(t * 255f).toInt().coerceIn(0, 255)]
                    cellCols[i * 3] = Color.red(c) / 255f
                    cellCols[i * 3 + 1] = Color.green(c) / 255f
                    cellCols[i * 3 + 2] = Color.blue(c) / 255f
                }
            }
            2 -> {
                val halfW = width / 2f; val halfH = height / 2f
                val maxDist = sqrt((width * width + height * height).toFloat()) / 2f
                val invMax = 1f / maxDist
                val lut = palette.buildLut(256)
                for (i in 0 until numPoints) {
                    val dx = px[i] - halfW; val dy = py[i] - halfH
                    val t = (sqrt(dx * dx + dy * dy) * invMax).coerceIn(0f, 1f)
                    val c = lut[(t * 255f).toInt().coerceIn(0, 255)]
                    cellCols[i * 3] = Color.red(c) / 255f
                    cellCols[i * 3 + 1] = Color.green(c) / 255f
                    cellCols[i * 3 + 2] = Color.blue(c) / 255f
                }
            }
            else -> {
                for (i in 0 until numPoints) {
                    val c = colorInts[i % colorInts.size]
                    cellCols[i * 3] = Color.red(c) / 255f
                    cellCols[i * 3 + 1] = Color.green(c) / 255f
                    cellCols[i * 3 + 2] = Color.blue(c) / 255f
                }
            }
        }

        val groutVec = floatArrayOf(0.5f, 0.5f, 0.5f)
        when (groutColor) {
            "white" -> { groutVec[0] = 1f; groutVec[1] = 1f; groutVec[2] = 1f }
            "black" -> { groutVec[0] = 0f; groutVec[1] = 0f; groutVec[2] = 0f }
            "palette-last" -> {
                val c = colorInts.last()
                groutVec[0] = Color.red(c) / 255f
                groutVec[1] = Color.green(c) / 255f
                groutVec[2] = Color.blue(c) / 255f
            }
            // grey defaults
        }

        val avgCellRadius = sqrt(width.toFloat() * height / numPoints) * 0.5f

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uCellColors"),
            VoronoiGlsl.MAX_POINTS, cellCols, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGroutWidth"), gap)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(programId, "uGroutColor"),
            groutVec[0], groutVec[1], groutVec[2])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTileStyle"), tileStyleId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvAvgRadius"), 1f / avgCellRadius)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform vec3 uCellColors[VORONOI_MAX_POINTS];
        uniform float uGroutWidth;
        uniform vec3 uGroutColor;
        uniform int uTileStyle;     // 0 flat, 1 raised, 2 inset
        uniform float uInvAvgRadius;

        out vec4 fragColor;

        void main() {
            vec4 f = voronoiF1F2(gl_FragCoord.xy);
            float f1Lin = (uMetric == 0) ? sqrt(f.x) : f.x;
            float f2Lin = (uMetric == 0) ? sqrt(f.y) : f.y;
            float edgeDist = f2Lin - f1Lin;
            float gapThreshold = uGroutWidth * 2.0;

            vec3 col;
            if (edgeDist < gapThreshold) {
                col = uGroutColor;
            } else {
                int idx = int(f.z + 0.5);
                vec3 baseCol = uCellColors[idx];
                if (uTileStyle == 0) {
                    col = baseCol;
                } else {
                    float t = clamp((edgeDist - gapThreshold) * uInvAvgRadius, 0.0, 1.0);
                    float factor = (uTileStyle == 1) ? (0.60 + t * 0.55) : (1.15 - t * 0.55);
                    col = clamp(baseCol * factor, 0.0, 1.0);
                }
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
