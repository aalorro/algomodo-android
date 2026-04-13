package com.artmondo.algomodo.generators.flux

import android.graphics.*
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * A base shape instanced hundreds of times with per-instance transforms driven
 * by simplex noise fields -- a murmuration of shapes drifting through organic
 * flow patterns.
 *
 * N instances with seeded base positions, per-instance phase offsets, and depth
 * layers. Position driven by two decorrelated simplex noise fields with 25%
 * canvas drift amplitude. Per-instance size pulsing via sin(time + phase).
 * Color cycles through palette over time per instance. Opacity varies by depth
 * layer (back=dim, front=bright). Sorted by depth then Y for layered rendering.
 * Five shapes: triangle, circle, diamond, cross, mixed (random per instance).
 */
class FluxInstancedScatterGenerator : Generator {

    override val id = "flux-instanced-scatter"
    override val family = "flux"
    override val styleName = "Instanced Scatter"
    override val definition =
        "A base shape instanced hundreds of times with per-instance transforms driven by " +
        "simplex noise fields -- a murmuration of shapes drifting through organic flow patterns"
    override val algorithmNotes =
        "N instances with seeded base positions, per-instance phase offsets, and depth layers. " +
        "Position driven by two decorrelated simplex noise fields with 25% canvas drift amplitude for visible flow. " +
        "Per-instance size pulsing via sin(time + phase). Color cycles through palette over time per instance. " +
        "Opacity varies by depth layer (back=dim, front=bright). Stroke outlines for visual definition. " +
        "Sorted by depth then Y for layered rendering. Quality scales instance count (draft 0.3x, balanced 0.6x)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Shape",
            key = "shape",
            group = ParamGroup.COMPOSITION,
            help = "triangle | circle | diamond | cross | mixed: random shape per instance",
            options = listOf("triangle", "circle", "diamond", "cross", "mixed"),
            default = "triangle"
        ),
        Parameter.NumberParam(
            name = "Instance Count",
            key = "instanceCount",
            group = ParamGroup.COMPOSITION,
            help = "Number of shape instances scattered across the canvas",
            min = 50f, max = 2000f, step = 50f, default = 500f
        ),
        Parameter.NumberParam(
            name = "Base Size",
            key = "baseSize",
            group = ParamGroup.GEOMETRY,
            help = "Base radius of each shape instance in pixels",
            min = 15f, max = 100f, step = 1f, default = 25f
        ),
        Parameter.NumberParam(
            name = "Size Variation",
            key = "sizeVariation",
            group = ParamGroup.GEOMETRY,
            help = "Per-instance random size variation driven by noise",
            min = 0f, max = 1f, step = 0.05f, default = 0.4f
        ),
        Parameter.NumberParam(
            name = "Noise Scale",
            key = "noiseScale",
            group = ParamGroup.GEOMETRY,
            help = "Frequency of the noise field driving position offsets -- lower = larger swirls",
            min = 0.5f, max = 4f, step = 0.1f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Rotation Speed",
            key = "rotationSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = "How fast instances rotate over time",
            min = 0f, max = 2f, step = 0.1f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation speed of position drift",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "shape" to "triangle",
        "instanceCount" to 500f,
        "baseSize" to 25f,
        "sizeVariation" to 0.4f,
        "noiseScale" to 1.5f,
        "rotationSpeed" to 0.5f,
        "speed" to 0.5f,
        "reactivity" to 1.0f
    )

    // ── Module-level cached instance buffers ─────────────────────────────
    @Volatile private var baseXArr = FloatArray(MAX_INSTANCES)
    @Volatile private var baseYArr = FloatArray(MAX_INSTANCES)
    @Volatile private var phaseArr = FloatArray(MAX_INSTANCES)
    @Volatile private var depthArr = FloatArray(MAX_INSTANCES)
    @Volatile private var shapeIdArr = IntArray(MAX_INSTANCES)

    override fun renderCanvas(
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

        // ── Parameters ────────────────────────────────────────────────────
        val shape = (params["shape"] as? String) ?: "triangle"
        val rawInstanceCount = ((params["instanceCount"] as? Number)?.toInt() ?: 500).coerceIn(50, 2000)
        val sizeVariation = (params["sizeVariation"] as? Number)?.toFloat() ?: 0.4f
        val noiseScaleParam = (params["noiseScale"] as? Number)?.toFloat() ?: 1.5f
        val rotationSpeed = (params["rotationSpeed"] as? Number)?.toFloat() ?: 0.5f
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio ─────────────────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioEnergy = ((audioAnalysis?.getBass(time) ?: 0f) +
                (audioAnalysis?.getMid(time) ?: 0f) +
                (audioAnalysis?.getHigh(time) ?: 0f)) / 3f * rx

        val baseSize = ((params["baseSize"] as? Number)?.toFloat() ?: 25f) * (1f + audioBass * 0.8f)
        val noiseScale = noiseScaleParam * (1f + audioMid * 0.6f)

        // ── Quality scaling ───────────────────────────────────────────────
        val qualityFactor = when (quality) {
            Quality.DRAFT -> 0.3f
            Quality.BALANCED -> 0.6f
            Quality.ULTRA -> 1.0f
        }
        val instanceCount = (rawInstanceCount * qualityFactor).roundToInt().coerceAtLeast(10)

        val isMixed = shape == "mixed"
        val globalShapeId = when (shape) {
            "triangle" -> 0
            "circle" -> 1
            "diamond" -> 2
            "cross" -> 3
            else -> -1 // mixed
        }

        // ── Initialize seeded base positions + per-instance properties ────
        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Ensure buffers are large enough
        if (baseXArr.size < instanceCount) {
            val sz = maxOf(instanceCount, MAX_INSTANCES)
            baseXArr = FloatArray(sz)
            baseYArr = FloatArray(sz)
            phaseArr = FloatArray(sz)
            depthArr = FloatArray(sz)
            shapeIdArr = IntArray(sz)
        }

        val bxArr = baseXArr
        val byArr = baseYArr
        val phArr = phaseArr
        val dpArr = depthArr
        val sidArr = shapeIdArr

        for (i in 0 until instanceCount) {
            bxArr[i] = rng.random() * w
            byArr[i] = rng.random() * h
            phArr[i] = rng.random() * TAU
            dpArr[i] = rng.random()
            sidArr[i] = if (isMixed) (rng.random() * 4f).toInt().coerceAtMost(3) else 0
        }

        // ── Palette ─────────────────────────────────────────────────────────
        val colorInts = palette.colorInts()
        val paletteLength = colorInts.size

        // Dark background from first palette color
        val bgColor = colorInts[0]
        val bgR = maxOf(0, Color.red(bgColor) - 50)
        val bgG = maxOf(0, Color.green(bgColor) - 50)
        val bgB = maxOf(0, Color.blue(bgColor) - 50)
        canvas.drawColor(Color.rgb(bgR, bgG, bgB))

        // ── Compute per-instance transforms ─────────────────────────────────
        val finalX = FloatArray(instanceCount)
        val finalY = FloatArray(instanceCount)
        val angles = FloatArray(instanceCount)
        val scales = FloatArray(instanceCount)
        val colorIdx = IntArray(instanceCount)
        val alphas = FloatArray(instanceCount)
        val sortOrder = IntArray(instanceCount)

        val t = time * spd
        // Drift amplitude: 25% of canvas -- strong visible flow
        val driftX = w * 0.25f
        val driftY = h * 0.25f

        // Audio: energy creates a burst effect on size
        val energyBurst = 1f + audioEnergy * 0.4f

        for (i in 0 until instanceCount) {
            val bx = bxArr[i]
            val by = byArr[i]
            val ph = phArr[i]
            val dp = dpArr[i]

            // Normalized coords for noise
            val nx = bx / w * noiseScale
            val ny = by / h * noiseScale

            // Depth-scaled time: deeper instances drift slower (parallax)
            val depthSpeed = 0.4f + dp * 0.6f // far=0.4x, near=1.0x
            val tScaled = t * depthSpeed

            // Position: two decorrelated noise fields with strong drift
            val dx = noise.noise2D(nx, tScaled * 0.15f + 100f) * driftX +
                     noise.noise2D(nx * 2.1f, tScaled * 0.3f + 200f) * driftX * 0.3f
            val dy = noise.noise2D(ny + 50f, tScaled * 0.15f + 300f) * driftY +
                     noise.noise2D(ny * 2.1f + 50f, tScaled * 0.3f + 400f) * driftY * 0.3f

            // Wrap around canvas edges
            var fx = (bx + dx) % w; if (fx < 0f) fx += w
            var fy = (by + dy) % h; if (fy < 0f) fy += h
            finalX[i] = fx
            finalY[i] = fy

            // Rotation: per-instance spin speed varies with depth + noise
            val spinDir = if (ph > PI.toFloat()) 1f else -1f
            val spinRate = (0.3f + dp * 1.2f) * rotationSpeed * spinDir
            angles[i] = t * spinRate + noise.noise2D(nx + 500f, ny + 500f) * PI.toFloat()

            // Size: per-instance hash variation + depth scaling + pulsing
            // ph is unique per instance (0..TAU) -- use as hash for strong size spread
            val sizeHash = (ph / TAU) * 2f - 1f // [-1, 1] unique per instance
            val depthScale = 0.5f + dp * 0.5f // far=0.5x, near=1.0x
            val pulse = 1f + sin(t * 1.8f + ph) * 0.15f * energyBurst
            scales[i] = baseSize * (1f + sizeHash * sizeVariation) * depthScale * pulse

            // Color: cycles through palette over time, offset by instance phase
            val colorT = (noise.noise2D(nx * 0.5f, ny * 0.5f) * 0.5f + 0.5f + t * 0.08f + ph / TAU) % 1f
            val ci = (colorT * paletteLength).toInt()
            colorIdx[i] = if (ci < paletteLength) ci else paletteLength - 1

            // Alpha: depth-based opacity (far=dim, near=bright)
            alphas[i] = 0.3f + dp * 0.6f

            sortOrder[i] = i
        }

        // ── Sort by depth (far first), then Y within same depth ─────────────
        // Insertion sort (stable, good for partially-ordered data)
        for (i in 1 until instanceCount) {
            val key = sortOrder[i]
            val keyD = dpArr[key] * 10000f + finalY[key]
            var j = i - 1
            while (j >= 0) {
                val jIdx = sortOrder[j]
                val jD = dpArr[jIdx] * 10000f + finalY[jIdx]
                if (jD <= keyD) break
                sortOrder[j + 1] = sortOrder[j]
                j--
            }
            sortOrder[j + 1] = key
        }

        // ── Draw shapes -- sorted back to front ──────────────────────────────
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }

        val shapePath = Path()

        for (si in 0 until instanceCount) {
            val idx = sortOrder[si]
            val fx = finalX[idx]
            val fy = finalY[idx]
            val angle = angles[idx]
            val s = scales[idx]
            val alpha = alphas[idx]
            val ci = colorIdx[idx]

            if (s <= 0.5f) continue

            val color = colorInts[ci]
            val cr = Color.red(color)
            val cg = Color.green(color)
            val cb = Color.blue(color)

            val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)

            // Fill with alpha
            fillPaint.color = Color.argb(alphaInt, cr, cg, cb)

            // Stroke: darker outline for definition
            val sr = maxOf(0, cr - 60)
            val sg = maxOf(0, cg - 60)
            val sb = maxOf(0, cb - 60)
            strokePaint.color = Color.argb(alphaInt, sr, sg, sb)
            strokePaint.strokeWidth = maxOf(0.5f, s * 0.08f)

            // Resolve per-instance shape (mixed mode uses per-instance ID)
            val sid = if (isMixed) sidArr[idx] else globalShapeId

            if (sid == 1) {
                // Circle -- no rotation needed
                canvas.drawCircle(fx, fy, s, fillPaint)
                canvas.drawCircle(fx, fy, s, strokePaint)
            } else {
                canvas.save()
                canvas.translate(fx, fy)
                canvas.rotate(angle * RAD_TO_DEG)

                shapePath.reset()

                when (sid) {
                    0 -> {
                        // Triangle
                        val h3 = s * 0.866f
                        shapePath.moveTo(s, 0f)
                        shapePath.lineTo(-s * 0.5f, h3)
                        shapePath.lineTo(-s * 0.5f, -h3)
                        shapePath.close()
                    }
                    2 -> {
                        // Diamond
                        shapePath.moveTo(0f, -s)
                        shapePath.lineTo(s, 0f)
                        shapePath.lineTo(0f, s)
                        shapePath.lineTo(-s, 0f)
                        shapePath.close()
                    }
                    else -> {
                        // Cross (sid == 3)
                        val arm = s
                        val thick = s * 0.3f
                        shapePath.addRect(-arm, -thick, arm, thick, Path.Direction.CW)
                        shapePath.addRect(-thick, -arm, thick, arm, Path.Direction.CW)
                    }
                }

                canvas.drawPath(shapePath, fillPaint)
                canvas.drawPath(shapePath, strokePaint)
                canvas.restore()
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["instanceCount"] as? Number)?.toFloat() ?: 500f
        val isComplex = if ((params["shape"] as? String) == "cross") 1.3f else 1f
        val base = (count * 0.5f * isComplex + 100f) / 1200f // normalize to ~0..1 range
        return when (quality) {
            Quality.DRAFT -> base * 0.3f
            Quality.BALANCED -> base * 0.6f
            Quality.ULTRA -> base
        }.coerceIn(0.05f, 1f)
    }

    companion object {
        private const val PI = 3.1415926535897932f
        private const val TAU = 2f * PI
        private const val RAD_TO_DEG = 180f / PI
        private const val MAX_INSTANCES = 2000
    }
}
