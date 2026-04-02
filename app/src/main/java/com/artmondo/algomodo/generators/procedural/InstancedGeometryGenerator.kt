package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class InstancedGeometryGenerator : Generator {

    override val id = "procedural-instanced-geometry"
    override val family = "procedural"
    override val styleName = "Instanced Geometry"
    override val definition =
        "Many copies of a base shape arranged in grids, spirals, or radial patterns with wave-propagation animation."
    override val algorithmNotes =
        "Generates N instances of a parametric shape placed by arrangement rule (grid, radial, golden-angle spiral, random scatter). " +
        "Each instance has seeded size, rotation, and palette color. Animation propagates a wave outward from center, " +
        "modulating scale and rotation per instance based on distance."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private val GOLDEN_ANGLE = PI.toFloat() * (3f - sqrt(5f))
    }

    override val parameterSchema = listOf(
        Parameter.SelectParam("Shape", "shape", ParamGroup.GEOMETRY,
            null, listOf("triangle", "hexagon", "circle", "square", "star"), "hexagon"),
        Parameter.NumberParam("Count", "count", ParamGroup.COMPOSITION,
            "Number of shape instances", 10f, 500f, 10f, 150f),
        Parameter.SelectParam("Arrangement", "arrangement", ParamGroup.COMPOSITION,
            null, listOf("grid", "radial", "spiral", "scatter"), "grid"),
        Parameter.NumberParam("Size Variance", "sizeVar", ParamGroup.GEOMETRY,
            "Random size variation per instance", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Rotation Var", "rotationVar", ParamGroup.GEOMETRY,
            "Random rotation variation", 0f, 1f, 0.05f, 0.4f),
        Parameter.NumberParam("Wave Speed", "waveSpeed", ParamGroup.FLOW_MOTION,
            "Animation wave propagation speed", 0f, 2f, 0.1f, 1.0f),
        Parameter.SelectParam("Fill Mode", "fillMode", ParamGroup.TEXTURE,
            null, listOf("filled", "outlined", "mixed"), "filled"),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "shape" to "hexagon", "count" to 150f, "arrangement" to "grid",
        "sizeVar" to 0.3f, "rotationVar" to 0.4f, "waveSpeed" to 1.0f, "fillMode" to "filled",
        "reactivity" to 1.0f
    )

    private fun getPolyVertices(shape: String): Pair<FloatArray, FloatArray>? {
        val n: Int; var offset = 0f; var altRadius: Float? = null
        val TAU = PI.toFloat() * 2f
        when (shape) {
            "triangle" -> { n = 3; offset = -PI.toFloat() / 2f }
            "hexagon" -> { n = 6; offset = 0f }
            "star" -> { n = 10; offset = -PI.toFloat() / 2f; altRadius = 0.45f }
            else -> return null
        }
        val xv = FloatArray(n); val yv = FloatArray(n)
        for (i in 0 until n) {
            val a = (i.toFloat() / n) * TAU + offset
            val r = if (altRadius != null && i % 2 != 0) altRadius else 1f
            xv[i] = cos(a) * r; yv[i] = sin(a) * r
        }
        return Pair(xv, yv)
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val rng = SeededRNG(seed)
        val minDim = min(w, h)
        val cx = w / 2f; val cy = h / 2f
        val TAU = PI.toFloat() * 2f

        val shape = (params["shape"] as? String) ?: "hexagon"
        val count = (params["count"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 150
        val arrangement = (params["arrangement"] as? String) ?: "grid"
        val sizeVar = (params["sizeVar"] as? Number)?.toFloat() ?: 0.3f
        val rotVar = (params["rotationVar"] as? Number)?.toFloat() ?: 0.4f
        val waveSpd = (params["waveSpeed"] as? Number)?.toFloat() ?: 1.0f
        val fillMode = (params["fillMode"] as? String) ?: "filled"

        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        val colors = palette.colorInts()
        val nC = colors.size

        // Background
        canvas.drawColor(Color.rgb(10, 10, 15))

        // Instance data arrays
        val instX = FloatArray(count); val instY = FloatArray(count)
        val instSize = FloatArray(count); val instRot = FloatArray(count)
        val instColorIdx = IntArray(count); val instFilled = BooleanArray(count)
        val instDist = FloatArray(count)
        var instCount = 0

        val baseSize = minDim * 0.035f

        when (arrangement) {
            "grid" -> {
                val cols = ceil(sqrt(count * w / h)).toInt()
                val rows = ceil(count.toFloat() / cols).toInt()
                val cellW = w / (cols + 1)
                val cellH = h / (rows + 1)
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (instCount >= count) break
                        val x = cellW * (c + 1) + rng.range(-0.2f, 0.2f) * cellW
                        val y = cellH * (r + 1) + rng.range(-0.2f, 0.2f) * cellH
                        instX[instCount] = x; instY[instCount] = y
                        instSize[instCount] = baseSize * (1f + rng.range(-sizeVar, sizeVar))
                        instRot[instCount] = rng.random() * rotVar * TAU
                        instColorIdx[instCount] = rng.integer(0, nC - 1)
                        instFilled[instCount] = fillMode == "filled" || (fillMode == "mixed" && rng.random() > 0.4f)
                        val dx = x - cx; val dy = y - cy
                        instDist[instCount] = sqrt(dx * dx + dy * dy)
                        instCount++
                    }
                    if (instCount >= count) break
                }
            }
            "radial" -> {
                val rings = ceil(sqrt(count / 3f)).toInt().coerceAtLeast(1)
                for (ri in 0 until rings) {
                    if (instCount >= count) break
                    val ringR = (ri + 1f) / (rings + 1f) * minDim * 0.45f
                    val perRing = (count.toFloat() / rings).roundToInt().coerceAtLeast(3)
                    for (pi in 0 until perRing) {
                        if (instCount >= count) break
                        val a = (pi.toFloat() / perRing) * TAU + ri * 0.3f
                        instX[instCount] = cx + cos(a) * ringR
                        instY[instCount] = cy + sin(a) * ringR
                        instSize[instCount] = baseSize * (1f + rng.range(-sizeVar, sizeVar))
                        instRot[instCount] = rng.random() * rotVar * TAU
                        instColorIdx[instCount] = rng.integer(0, nC - 1)
                        instFilled[instCount] = fillMode == "filled" || (fillMode == "mixed" && rng.random() > 0.4f)
                        instDist[instCount] = ringR
                        instCount++
                    }
                }
            }
            "spiral" -> {
                val maxR = minDim * 0.44f
                for (i in 0 until count) {
                    val t = i.toFloat() / count
                    val r = t * maxR
                    val a = i * GOLDEN_ANGLE
                    instX[instCount] = cx + cos(a) * r
                    instY[instCount] = cy + sin(a) * r
                    instSize[instCount] = baseSize * (1f + rng.range(-sizeVar, sizeVar))
                    instRot[instCount] = rng.random() * rotVar * TAU
                    instColorIdx[instCount] = rng.integer(0, nC - 1)
                    instFilled[instCount] = fillMode == "filled" || (fillMode == "mixed" && rng.random() > 0.4f)
                    instDist[instCount] = r
                    instCount++
                }
            }
            else -> { // scatter
                for (i in 0 until count) {
                    val x = rng.range(baseSize, w - baseSize)
                    val y = rng.range(baseSize, h - baseSize)
                    instX[instCount] = x; instY[instCount] = y
                    instSize[instCount] = baseSize * (1f + rng.range(-sizeVar, sizeVar))
                    instRot[instCount] = rng.random() * rotVar * TAU
                    instColorIdx[instCount] = rng.integer(0, nC - 1)
                    instFilled[instCount] = fillMode == "filled" || (fillMode == "mixed" && rng.random() > 0.4f)
                    val dx = x - cx; val dy = y - cy
                    instDist[instCount] = sqrt(dx * dx + dy * dy)
                    instCount++
                }
            }
        }

        // Sort by distance (back-to-front)
        val sortIdx = (0 until instCount).sortedByDescending { instDist[it] }.toIntArray()

        val isCircle = shape == "circle"
        val isSquare = shape == "square"
        val poly = if (!isCircle && !isSquare) getPolyVertices(shape) else null
        val polyN = poly?.first?.size ?: 0

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.strokeWidth = max(1f, minDim * 0.003f)
        val waveSpd2 = waveSpd * 2f
        val bassAmp = 0.35f + audioBass * 0.8f
        val midAmp = 0.5f + audioMid * 0.6f

        for (si in 0 until instCount) {
            val ii = sortIdx[si]
            val phase = instDist[ii] * 0.008f - time * waveSpd2
            val sinPhase = sin(phase)
            val scaleWave = 1f + bassAmp * sinPhase
            val rotWave = midAmp * sin(phase * 0.7f + 1.2f)
            val alphaWave = 0.55f + 0.45f * sin(phase * 0.5f + 0.8f)

            val sz = instSize[ii] * scaleWave
            val rot = instRot[ii] + rotWave
            val ci = instColorIdx[ii]
            val c = colors[ci]
            val alpha = (alphaWave * 255f).toInt().coerceIn(0, 255)

            val ix = instX[ii]; val iy = instY[ii]

            canvas.save()
            canvas.translate(ix, iy)
            canvas.rotate(Math.toDegrees(rot.toDouble()).toFloat())

            if (isCircle) {
                if (instFilled[ii]) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawCircle(0f, 0f, sz, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawCircle(0f, 0f, sz, paint)
                }
            } else if (isSquare) {
                if (instFilled[ii]) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawRect(-sz, -sz, sz, sz, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                    canvas.drawRect(-sz, -sz, sz, sz, paint)
                }
            } else if (poly != null) {
                val path = Path()
                path.moveTo(poly.first[0] * sz, poly.second[0] * sz)
                for (v in 1 until polyN) {
                    path.lineTo(poly.first[v] * sz, poly.second[v] * sz)
                }
                path.close()

                if (instFilled[ii]) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
                }
                canvas.drawPath(path, paint)
            }

            canvas.restore()
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["count"] as? Number)?.toInt() ?: 150
        return (count / 500f).coerceIn(0.2f, 0.8f)
    }
}
