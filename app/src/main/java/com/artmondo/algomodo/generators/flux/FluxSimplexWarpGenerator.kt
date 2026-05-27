package com.artmondo.algomodo.generators.flux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES30
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.GpuShaderRunner
import com.artmondo.algomodo.rendering.gl.NoiseGlsl
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hybrid CPU/GPU generator.
 *
 * `mesh` and `dots` modes draw vector-style geometry (quad outlines / circles)
 * to the Android Canvas and stay on the CPU. `heatmap` mode is a pure
 * per-pixel field and runs on the GPU via the [GpuGenerator] fragment-shader
 * pipeline.
 *
 * Param schema, defaults, and id are preserved so existing presets continue
 * to load. Heatmap visuals are very close but not bit-identical to the CPU
 * implementation (Ashima/Gustavson `snoise` vs `ValueNoise`).
 */
class FluxSimplexWarpGenerator : GpuGenerator {

    override val id = "flux-simplex-warp"
    override val family = "flux"
    override val styleName = "Simplex Warp"
    override val definition =
        "A regular grid of points displaced by layered simplex noise FBM, rendered as a deformed mesh, " +
        "pixel heatmap, or scattered dots \u2014 visualizing the topology of a continuous warp field"
    override val algorithmNotes =
        "Hybrid renderer. mesh / dots: CPU — builds a displaced vertex grid via FBM, then draws " +
        "quad outlines or circles to the canvas. heatmap: GPU fragment shader — per-pixel FBM " +
        "displacement magnitude is quantised into discrete palette bands."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            "Render Mode", "renderMode", ParamGroup.COMPOSITION,
            "mesh: quad outlines colored by displacement | heatmap: pixel-level interpolated displacement map | dots: circles at displaced vertices",
            listOf("mesh", "heatmap", "dots"), "mesh"
        ),
        Parameter.NumberParam(
            "Grid Size", "gridSize", ParamGroup.GEOMETRY,
            "Number of grid divisions along each axis",
            10f, 80f, 1f, 30f
        ),
        Parameter.NumberParam(
            "Noise Scale", "noiseScale", ParamGroup.GEOMETRY,
            "Frequency of the simplex noise field \u2014 higher = more turbulent",
            0.5f, 5f, 0.1f, 2.0f
        ),
        Parameter.NumberParam(
            "Warp Amount", "warpAmount", ParamGroup.GEOMETRY,
            "Strength of vertex displacement relative to cell size",
            0.1f, 3f, 0.1f, 1.0f
        ),
        Parameter.NumberParam(
            "Octaves", "octaves", ParamGroup.COMPOSITION,
            "FBM layers \u2014 more octaves = finer warp detail",
            1f, 4f, 1f, 3f
        ),
        Parameter.NumberParam(
            "Line Width", "lineWidth", ParamGroup.TEXTURE,
            "Stroke width for mesh and dot outlines",
            0.5f, 3f, 0.1f, 1.0f
        ),
        Parameter.NumberParam(
            "Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation drift speed through the noise field",
            0.1f, 3f, 0.05f, 0.5f
        ),
        Parameter.NumberParam(
            "Audio Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Sensitivity to audio input (0 = none)",
            0f, 2f, 0.1f, 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "renderMode" to "mesh",
        "gridSize" to 30f,
        "noiseScale" to 2.0f,
        "warpAmount" to 1.0f,
        "octaves" to 3f,
        "lineWidth" to 1.0f,
        "speed" to 0.5f,
        "reactivity" to 1.0f
    )

    // ────────────────────────────────────────────────────────────────────
    // Dispatch: heatmap → GPU, mesh/dots → CPU
    // ────────────────────────────────────────────────────────────────────
    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val renderMode = (params["renderMode"] as? String) ?: "mesh"
        if (renderMode == "heatmap") {
            GpuShaderRunner.forCurrentThread().render(
                generator = this,
                bitmap = bitmap,
                params = params,
                seed = seed,
                palette = palette,
                quality = quality,
                time = time
            )
            return
        }
        renderMeshOrDotsCpu(canvas, bitmap, params, seed, palette, quality, time)
    }

    // ────────────────────────────────────────────────────────────────────
    // GPU heatmap shader
    // ────────────────────────────────────────────────────────────────────
    override fun bindUniforms(
        programId: Int,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float,
        width: Int,
        height: Int
    ) {
        val noiseScale = (params["noiseScale"] as? Number)?.toFloat() ?: 2.0f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 3).coerceIn(1, 4)
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val (seedOffX, seedOffY) = NoiseGlsl.seedToOffset(seed)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), noiseScale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), spd)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uReactivity"), rx)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uSeedOffset"), seedOffX, seedOffY)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${NoiseGlsl.GLSL_HELPERS}

        uniform float uNoiseScale;
        uniform int   uOctaves;
        uniform float uSpeed;
        uniform float uReactivity;
        uniform vec2  uSeedOffset;

        out vec4 fragColor;

        void main() {
            // Mid-band modulates noise scale (mirrors CPU)
            float audioMid = uAudio.y * uReactivity;
            float noiseScale = uNoiseScale * (1.0 + audioMid * 0.5);

            float invW = noiseScale / uResolution.x;
            float invH = noiseScale / uResolution.y;
            float tOff = uTime * uSpeed * 0.2;

            vec2 frag = gl_FragCoord.xy;
            float nx = frag.x * invW + uSeedOffset.x;
            float nyA = frag.y * invH + 100.0 + tOff + uSeedOffset.y;
            float nyB = frag.y * invH + 300.0 + tOff + uSeedOffset.y;

            float du = fbm(vec2(nx, nyA), uOctaves, 2.0, 0.5);
            float dv = fbm(vec2(nx + 200.0, nyB), uOctaves, 2.0, 0.5);

            float magSq = du * du + dv * dv;
            float v = clamp(sqrt(magSq) * 0.707, 0.0, 1.0);

            // Quantise into 5 palette bands (matches CPU `numColors`)
            float qi = floor(v * 5.0);
            qi = min(qi, 4.0);
            float v2 = qi / 4.0;

            vec3 col = palette_color(v2);
            fragColor = vec4(col, 1.0);
        }
    """

    // ────────────────────────────────────────────────────────────────────
    // CPU path for mesh / dots (preserved from the original implementation)
    // ────────────────────────────────────────────────────────────────────
    private fun renderMeshOrDotsCpu(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width
        val h = bitmap.height

        val renderMode = (params["renderMode"] as? String) ?: "mesh"
        val gridSize = ((params["gridSize"] as? Number)?.toInt() ?: 30).coerceIn(4, 80)
        val noiseScaleParam = (params["noiseScale"] as? Number)?.toFloat() ?: 2.0f
        val warpAmountParam = (params["warpAmount"] as? Number)?.toFloat() ?: 1.0f
        val octaves = ((params["octaves"] as? Number)?.toInt() ?: 3).coerceIn(1, 4)
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1.0f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        val warpAmount = warpAmountParam * (1f + audioBass * 2f)
        val noiseScale = noiseScaleParam * (1f + audioMid * 0.5f)

        val noise = SimplexNoise(seed)

        val cols = gridSize + 1
        val rows = gridSize + 1
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val cellSize = min(cellW, cellH)
        val t = time * spd

        val colorInts = palette.colorInts()
        val numColors = colorInts.size
        val numColorsM1 = numColors - 1
        val rawR = IntArray(numColors)
        val rawG = IntArray(numColors)
        val rawB = IntArray(numColors)
        for (i in 0 until numColors) {
            val c = colorInts[i]
            rawR[i] = Color.red(c)
            rawG[i] = Color.green(c)
            rawB[i] = Color.blue(c)
        }

        // Pre-compute displaced vertices and magnitudes
        val totalVerts = cols * rows
        val vx = FloatArray(totalVerts)
        val vy = FloatArray(totalVerts)
        val mag = FloatArray(totalVerts)
        var maxMag = 0f
        for (iy in 0 until rows) {
            val baseY = iy * cellH
            val gy = iy.toFloat() / gridSize
            val rowOff = iy * cols
            for (ix in 0 until cols) {
                val baseX = ix * cellW
                val gx = ix.toFloat() / gridSize
                val dx = noise.fbm(gx * noiseScale, gy * noiseScale + 100f + t * 0.2f,
                    octaves, 2f, 0.5f) * warpAmount * cellSize
                val dy = noise.fbm(gx * noiseScale + 200f, gy * noiseScale + 300f + t * 0.2f,
                    octaves, 2f, 0.5f) * warpAmount * cellSize
                val idx = rowOff + ix
                vx[idx] = baseX + dx
                vy[idx] = baseY + dy
                val m = sqrt(dx * dx + dy * dy)
                mag[idx] = m
                if (m > maxMag) maxMag = m
            }
        }
        val invMaxMag = if (maxMag > 0f) 1f / maxMag else 0f
        for (i in 0 until totalVerts) mag[i] *= invMaxMag

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (renderMode == "mesh") {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = lineWidth
            paint.strokeJoin = Paint.Join.ROUND
            val path = Path()
            for (iy in 0 until gridSize) {
                val rowOff = iy * cols
                val nextRowOff = (iy + 1) * cols
                for (ix in 0 until gridSize) {
                    val tl = rowOff + ix
                    val tr = rowOff + ix + 1
                    val bl = nextRowOff + ix
                    val br = nextRowOff + ix + 1
                    val avgMag = (mag[tl] + mag[tr] + mag[bl] + mag[br]) * 0.25f
                    val ci = avgMag * numColorsM1
                    val i0 = ci.toInt()
                    val i1 = if (i0 < numColorsM1) i0 + 1 else numColorsM1
                    val f = ci - i0
                    val cr = (rawR[i0] + (rawR[i1] - rawR[i0]) * f).toInt()
                    val cg = (rawG[i0] + (rawG[i1] - rawG[i0]) * f).toInt()
                    val cb = (rawB[i0] + (rawB[i1] - rawB[i0]) * f).toInt()
                    paint.color = Color.rgb(cr, cg, cb)
                    path.reset()
                    path.moveTo(vx[tl], vy[tl])
                    path.lineTo(vx[tr], vy[tr])
                    path.lineTo(vx[br], vy[br])
                    path.lineTo(vx[bl], vy[bl])
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
        } else {
            // dots
            paint.style = Paint.Style.FILL
            val maxRadius = cellSize * 0.4f
            val minRadius = 1f
            for (iy in 0 until rows) {
                val rowOff = iy * cols
                for (ix in 0 until cols) {
                    val idx = rowOff + ix
                    val m = mag[idx]
                    val radius = minRadius + m * (maxRadius - minRadius)
                    val ci = m * numColorsM1
                    val i0 = ci.toInt()
                    val i1 = if (i0 < numColorsM1) i0 + 1 else numColorsM1
                    val f = ci - i0
                    val cr = (rawR[i0] + (rawR[i1] - rawR[i0]) * f).toInt()
                    val cg = (rawG[i0] + (rawG[i1] - rawG[i0]) * f).toInt()
                    val cb = (rawB[i0] + (rawB[i1] - rawB[i0]) * f).toInt()
                    paint.color = Color.rgb(cr, cg, cb)
                    canvas.drawCircle(vx[idx], vy[idx], radius, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val grid = (params["gridSize"] as? Number)?.toInt() ?: 30
        val oct = (params["octaves"] as? Number)?.toInt() ?: 3
        val mode = (params["renderMode"] as? String) ?: "mesh"
        return when (mode) {
            "heatmap" -> 0.15f  // GPU — cheap
            "dots" -> (grid * grid * oct * 2f / 50000f).coerceIn(0.1f, 1f)
            else -> (grid * grid * oct * 2f / 50000f).coerceIn(0.1f, 1f)
        }
    }
}
