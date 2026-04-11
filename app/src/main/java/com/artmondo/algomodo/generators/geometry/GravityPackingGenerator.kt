package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.PI

/**
 * Gravity Packing — shapes of varying sizes and random orientations drop
 * from the top and stack under gravity using a per-column height-map.
 *
 * Port of web gravity-packing.ts. Supports 2D shapes (square, rectangle,
 * circle) and shaded 3D shapes (isometric cube, radial-gradient sphere,
 * cylinder with body gradient). Animation replays the fall using a
 * staggered quadratic ease-in and a settled breathing pulse.
 */
class GravityPackingGenerator : Generator {

    override val id = "geo-gravity-packing"
    override val family = "geometry"
    override val styleName = "Gravity Packing"
    override val definition =
        "Shapes of varying sizes and random orientations drop from the top and stack under gravity — " +
        "2D and 3D forms (cubes, spheres, cylinders) packing tightly against the floor and each other."
    override val algorithmNotes =
        "Generates shapes from a size distribution (uniform/gaussian/power-law) in random order with random rotation. " +
        "Each shape drops from the top at a horizontal position (random or best-fit from 50 candidates) and falls " +
        "until it hits the floor or another shape. Collision uses the axis-aligned bounding box of the rotated shape " +
        "on a per-column height-map. 3D shapes render with face shading (cubes), radial gradients with specular " +
        "highlights (spheres), or cylindrical body gradients with perspective caps (cylinders)."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam(
            "Count", "shapeCount", ParamGroup.COMPOSITION,
            "Number of shapes to drop",
            50f, 500f, 10f, 200f
        ),
        Parameter.SelectParam(
            "Shape", "shape", ParamGroup.COMPOSITION,
            "square | rectangle | circle: 2D | cube: isometric 3D | sphere: shaded ball | cylinder: 3D tube | mixed: all types",
            listOf("square", "rectangle", "circle", "cube", "sphere", "cylinder", "mixed"),
            "square"
        ),
        Parameter.SelectParam(
            "Size Distribution", "sizeMode", ParamGroup.COMPOSITION,
            "uniform: equal chance | gaussian: bell curve | power: many small, few large",
            listOf("uniform", "gaussian", "power"),
            "power"
        ),
        Parameter.SelectParam(
            "Packing", "packMode", ParamGroup.COMPOSITION,
            "random-x: random horizontal | best-fit: tries many x positions for tightest fit",
            listOf("random-x", "best-fit"),
            "best-fit"
        ),
        Parameter.NumberParam(
            "Min Size", "minSize", ParamGroup.GEOMETRY,
            "Minimum shape dimension",
            5f, 40f, 1f, 8f
        ),
        Parameter.NumberParam(
            "Max Size", "maxSize", ParamGroup.GEOMETRY,
            "Maximum shape dimension",
            20f, 150f, 5f, 80f
        ),
        Parameter.NumberParam(
            "Gap", "gap", ParamGroup.GEOMETRY,
            "Spacing between shapes in pixels",
            0f, 8f, 1f, 1f
        ),
        Parameter.NumberParam(
            "Jitter", "jitter", ParamGroup.TEXTURE,
            "Random position offset for organic feel",
            0f, 10f, 1f, 2f
        ),
        Parameter.SelectParam(
            "Color By", "colorBy", ParamGroup.COLOR,
            "size: by dimension | order: by drop sequence | height: by y position | palette-random: random palette color",
            listOf("size", "order", "height", "palette-random"),
            "size"
        ),
        Parameter.SelectParam(
            "Fill Mode", "fillMode", ParamGroup.TEXTURE,
            "mixed: random fill/outline per shape | filled: all solid | outline: all stroked",
            listOf("mixed", "filled", "outline"),
            "mixed"
        ),
        Parameter.NumberParam(
            "Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Time multiplier for drop animation — 0 = static",
            0f, 2f, 0.1f, 0f
        ),
        Parameter.NumberParam(
            "Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION,
            "Intensity of settled breathing pulse",
            0.1f, 2f, 0.1f, 1f
        ),
        Parameter.NumberParam(
            "Audio Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            null,
            0f, 2f, 0.1f, 0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "shapeCount" to 200f,
        "shape" to "square",
        "sizeMode" to "power",
        "packMode" to "best-fit",
        "minSize" to 8f,
        "maxSize" to 80f,
        "gap" to 1f,
        "jitter" to 2f,
        "colorBy" to "size",
        "fillMode" to "mixed",
        "animSpeed" to 0f,
        "animAmp" to 1f,
        "reactivity" to 0f
    )

    private companion object {
        const val MAX_SHAPES = 500

        // Shape kinds. 2D kinds (< KIND_CUBE) are batched into Path-based passes;
        // 3D kinds (>= KIND_CUBE) are drawn individually with shading.
        const val KIND_RECT = 0
        const val KIND_CIRCLE = 1
        const val KIND_CUBE = 2
        const val KIND_SPHERE = 3
        const val KIND_CYLINDER = 4

        const val NUM_BUCKETS = 16
        const val MAX_CACHE_ENTRIES = 4

        val RAD_TO_DEG = (180.0 / PI).toFloat()
    }

    /**
     * Immutable snapshot of one packing result. Safe to share across threads.
     * Rotation angle is pre-decomposed into cos/sin so per-frame path building
     * does not repeat the trig.
     */
    private class PackingData(
        val count: Int,
        val sx: FloatArray,
        val sy: FloatArray,
        val sw: FloatArray,
        val sh: FloatArray,
        val sAngle: FloatArray,
        val sCosA: FloatArray,
        val sSinA: FloatArray,
        val sKind: IntArray,
        val sFill: BooleanArray,
        val sColorT: FloatArray
    )

    // Thread-safe LRU cache — lets live canvas and export threads coexist.
    private val cacheLock = Any()
    private val packingCache = object : LinkedHashMap<String, PackingData>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PackingData>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private fun getOrBuildPacking(
        key: String,
        seed: Int, count: Int, shape: String, sizeMode: String,
        packMode: String, fillMode: String,
        minSize: Float, maxSize: Float, gap: Float, jitter: Float,
        w: Float, h: Float
    ): PackingData {
        synchronized(cacheLock) {
            packingCache[key]?.let { return it }
        }
        val built = runPacking(
            seed, count, shape, sizeMode, packMode, fillMode,
            minSize, maxSize, gap, jitter, w, h
        )
        synchronized(cacheLock) {
            packingCache[key] = built
        }
        return built
    }

    // ── Size distribution sampling ──────────────────────────────────
    private fun generateSize(rng: SeededRNG, minS: Float, maxS: Float, mode: String): Float {
        return when (mode) {
            "gaussian" -> {
                val s = rng.gaussian((minS + maxS) * 0.5f, (maxS - minS) * 0.2f)
                s.coerceIn(minS, maxS)
            }
            "power" -> {
                val t = rng.random()
                minS + (maxS - minS) * t * t
            }
            else -> rng.range(minS, maxS)
        }
    }

    // Drop from top: lowest valid y (AABB top edge) given the height-map.
    private fun dropAt(
        hm: FloatArray, x: Float, aabbW: Float, aabbH: Float,
        gap: Float, canvasH: Float
    ): Float {
        val x0 = max(0, (x - gap).toInt())
        val x1 = min(hm.size - 1, (x + aabbW + gap).toInt())
        var floor = canvasH
        for (col in x0..x1) {
            if (hm[col] < floor) floor = hm[col]
        }
        return floor - aabbH - gap
    }

    private fun stampHeightMap(hm: FloatArray, x: Float, y: Float, wShape: Float) {
        val x0 = max(0, x.toInt())
        val x1 = min(hm.size - 1, (x + wShape).toInt())
        for (col in x0..x1) {
            if (y < hm[col]) hm[col] = y
        }
    }

    // ── Gravity packing with random rotation ───────────────────────
    private fun runPacking(
        seed: Int, count: Int, shape: String, sizeMode: String,
        packMode: String, fillMode: String,
        minSize: Float, maxSize: Float, gap: Float, jitter: Float,
        w: Float, h: Float
    ): PackingData {
        val rng = SeededRNG(seed)
        val cap = count.coerceIn(1, MAX_SHAPES)

        val sx = FloatArray(cap)
        val sy = FloatArray(cap)
        val sw = FloatArray(cap)
        val sh = FloatArray(cap)
        val sAngle = FloatArray(cap)
        val sCosA = FloatArray(cap)
        val sSinA = FloatArray(cap)
        val sKind = IntArray(cap)
        val sFill = BooleanArray(cap)
        val sColorT = FloatArray(cap)

        val hmWidth = max(1, w.toInt())
        val hm = FloatArray(hmWidth) { h }

        var placed = 0
        var i = 0
        while (i < count && placed < cap) {
            val sz = generateSize(rng, minSize, maxSize, sizeMode)
            var aspect = 1f
            var kind = KIND_RECT
            when (shape) {
                "rectangle" -> aspect = 0.4f + rng.random() * 1.2f
                "circle" -> kind = KIND_CIRCLE
                "cube" -> kind = KIND_CUBE
                "sphere" -> kind = KIND_SPHERE
                "cylinder" -> {
                    kind = KIND_CYLINDER
                    aspect = 0.4f + rng.random() * 1.2f
                }
                "mixed" -> {
                    val r = rng.random()
                    when {
                        r < 0.17f -> kind = KIND_RECT
                        r < 0.33f -> { kind = KIND_RECT; aspect = 0.5f + rng.random() * 1f }
                        r < 0.5f -> kind = KIND_CIRCLE
                        r < 0.67f -> kind = KIND_CUBE
                        r < 0.83f -> kind = KIND_SPHERE
                        else -> { kind = KIND_CYLINDER; aspect = 0.5f + rng.random() * 0.8f }
                    }
                }
                // "square" → defaults (rect, aspect 1)
            }

            // Circle, cube and sphere share a square AABB regardless of rotation.
            val useEqualAabb = kind in KIND_CIRCLE..KIND_SPHERE
            val aspectClamped = aspect.coerceIn(0.5f, 2f)
            val shapeW = if (useEqualAabb) sz else sz * aspectClamped
            val shapeH = if (useEqualAabb) sz else sz / aspectClamped

            val angle = rng.random() * 2f * PI.toFloat()
            val cosAngle = cos(angle)
            val sinAngle = sin(angle)

            val aabbW: Float
            val aabbH: Float
            if (useEqualAabb) {
                aabbW = sz
                aabbH = sz
            } else {
                val absCos = abs(cosAngle)
                val absSin = abs(sinAngle)
                aabbW = shapeW * absCos + shapeH * absSin
                aabbH = shapeW * absSin + shapeH * absCos
            }

            // Choose x and drop from top using AABB
            var bestAX = 0f
            var bestAY = -Float.MAX_VALUE
            val maxX = max(1f, w - aabbW)

            if (packMode == "best-fit") {
                var c = 0
                while (c < 50) {
                    val cx = rng.range(0f, maxX)
                    val cy = dropAt(hm, cx, aabbW, aabbH, gap, h)
                    if (cy > bestAY) { bestAY = cy; bestAX = cx }
                    c++
                }
            } else {
                bestAX = rng.range(0f, maxX)
                bestAY = dropAt(hm, bestAX, aabbW, aabbH, gap, h)
            }

            if (bestAY < -aabbH * 0.5f) {
                i++
                continue
            }

            if (jitter > 0f) {
                bestAX += (rng.random() - 0.5f) * jitter * 2f
                bestAX = bestAX.coerceIn(0f, w - aabbW)
            }

            // Store center and actual dimensions
            sx[placed] = bestAX + aabbW * 0.5f
            sy[placed] = bestAY + aabbH * 0.5f
            sw[placed] = shapeW
            sh[placed] = shapeH
            sAngle[placed] = angle
            sCosA[placed] = cosAngle
            sSinA[placed] = sinAngle
            sKind[placed] = kind
            sColorT[placed] = i.toFloat() / count.coerceAtLeast(1)

            sFill[placed] = when (fillMode) {
                "mixed" -> rng.random() > 0.4f
                "filled" -> true
                else -> false
            }

            stampHeightMap(hm, bestAX, bestAY, aabbW)
            placed++
            i++
        }

        return PackingData(placed, sx, sy, sw, sh, sAngle, sCosA, sSinA, sKind, sFill, sColorT)
    }

    // ── Per-frame bounds resolution ─────────────────────────────────
    // Shared mutable holder: reused across all shapes in a frame to avoid
    // allocating (cx, cy, sw, sh, visible) tuples.
    private class Bounds {
        var visible = true
        var cx = 0f
        var cy = 0f
        var sw = 0f
        var sh = 0f
    }

    private fun resolveBounds(
        out: Bounds, data: PackingData, idx: Int,
        doAnim: Boolean, localTime: Float, stagger: Float, fallDur: Float,
        animSpeed: Float, animAmp: Float, audioBass: Float
    ) {
        if (!doAnim) {
            out.visible = true
            out.cx = data.sx[idx]
            out.cy = data.sy[idx]
            out.sw = data.sw[idx]
            out.sh = data.sh[idx]
            return
        }

        val dropStart = idx * stagger
        if (localTime < dropStart) {
            out.visible = false
            return
        }
        out.visible = true
        out.cx = data.sx[idx]
        out.sw = data.sw[idx]
        out.sh = data.sh[idx]

        val finalY = data.sy[idx]
        val startY = -max(data.sw[idx], data.sh[idx])
        val elapsed = localTime - dropStart
        val fallP = min(1f, elapsed / fallDur)
        if (fallP < 1f) {
            val eased = fallP * fallP
            out.cy = startY + (finalY - startY) * eased
        } else {
            out.cy = finalY
            val phase = data.sx[idx] * 0.005f + finalY * 0.003f + idx * 0.1f
            val pulse = 1f + sin(localTime * animSpeed * 2f + phase) * 0.08f * animAmp * (1f + audioBass)
            out.sw *= pulse
            out.sh *= pulse
        }
    }

    // ── Build a 2D path for rect/circle ─────────────────────────────
    private fun addShapePath(
        path: Path,
        cx: Float, cy: Float, w: Float, h: Float,
        kind: Int, cosA: Float, sinA: Float
    ) {
        if (kind == KIND_CIRCLE) {
            path.addCircle(cx, cy, w * 0.5f, Path.Direction.CW)
        } else {
            val hw = w * 0.5f
            val hh = h * 0.5f
            val x0 = cx - hw * cosA + hh * sinA
            val y0 = cy - hw * sinA - hh * cosA
            val x1 = cx + hw * cosA + hh * sinA
            val y1 = cy + hw * sinA - hh * cosA
            val x2 = cx + hw * cosA - hh * sinA
            val y2 = cy + hw * sinA + hh * cosA
            val x3 = cx - hw * cosA - hh * sinA
            val y3 = cy - hw * sinA + hh * cosA
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            path.lineTo(x2, y2)
            path.lineTo(x3, y3)
            path.close()
        }
    }

    // ── Color helpers for 3D shading ────────────────────────────────
    private fun lighten(r: Int, g: Int, b: Int, amt: Int): Int =
        Color.rgb(min(255, r + amt), min(255, g + amt), min(255, b + amt))

    private fun darken(r: Int, g: Int, b: Int, amt: Int): Int =
        Color.rgb(max(0, r - amt), max(0, g - amt), max(0, b - amt))

    // ── 3D shape drawing (canvas is translated & rotated by caller) ─
    private fun drawIsoCube(
        canvas: Canvas, sz: Float, cr: Int, cg: Int, cb: Int,
        filled: Boolean, lw: Float,
        fillPaint: Paint, strokePaint: Paint, tempPath: Path
    ) {
        val e = sz * 0.45f
        val dx = e * 0.866f
        val dy = e * 0.5f

        if (filled) {
            // Top face (brightest)
            fillPaint.color = lighten(cr, cg, cb, 60)
            tempPath.rewind()
            tempPath.moveTo(-dx, -dy); tempPath.lineTo(0f, -e)
            tempPath.lineTo(dx, -dy); tempPath.lineTo(0f, 0f); tempPath.close()
            canvas.drawPath(tempPath, fillPaint)

            // Right face (base)
            fillPaint.color = Color.rgb(cr, cg, cb)
            tempPath.rewind()
            tempPath.moveTo(0f, 0f); tempPath.lineTo(dx, -dy)
            tempPath.lineTo(dx, dy); tempPath.lineTo(0f, e); tempPath.close()
            canvas.drawPath(tempPath, fillPaint)

            // Left face (darkest)
            fillPaint.color = darken(cr, cg, cb, 50)
            tempPath.rewind()
            tempPath.moveTo(-dx, -dy); tempPath.lineTo(0f, 0f)
            tempPath.lineTo(0f, e); tempPath.lineTo(-dx, dy); tempPath.close()
            canvas.drawPath(tempPath, fillPaint)

            // Edges
            strokePaint.color = Color.argb(77, 0, 0, 0)
            strokePaint.strokeWidth = lw
            tempPath.rewind()
            tempPath.moveTo(0f, -e); tempPath.lineTo(dx, -dy); tempPath.lineTo(dx, dy)
            tempPath.lineTo(0f, e); tempPath.lineTo(-dx, dy); tempPath.lineTo(-dx, -dy)
            tempPath.close()
            tempPath.moveTo(0f, 0f); tempPath.lineTo(dx, -dy)
            tempPath.moveTo(0f, 0f); tempPath.lineTo(-dx, -dy)
            tempPath.moveTo(0f, 0f); tempPath.lineTo(0f, e)
            canvas.drawPath(tempPath, strokePaint)
        } else {
            strokePaint.color = Color.rgb(cr, cg, cb)
            strokePaint.strokeWidth = lw * 2f
            tempPath.rewind()
            tempPath.moveTo(0f, -e); tempPath.lineTo(dx, -dy); tempPath.lineTo(dx, dy)
            tempPath.lineTo(0f, e); tempPath.lineTo(-dx, dy); tempPath.lineTo(-dx, -dy)
            tempPath.close()
            tempPath.moveTo(0f, 0f); tempPath.lineTo(dx, -dy)
            tempPath.moveTo(0f, 0f); tempPath.lineTo(-dx, -dy)
            tempPath.moveTo(0f, 0f); tempPath.lineTo(0f, e)
            canvas.drawPath(tempPath, strokePaint)
        }
    }

    private fun drawSphere3D(
        canvas: Canvas, sz: Float, cr: Int, cg: Int, cb: Int,
        filled: Boolean, fillPaint: Paint, strokePaint: Paint
    ) {
        val radius = sz * 0.45f
        if (radius < 0.5f) return

        if (filled) {
            val grad = RadialGradient(
                -radius * 0.3f, -radius * 0.3f, radius,
                intArrayOf(
                    lighten(cr, cg, cb, 80),
                    Color.rgb(cr, cg, cb),
                    darken(cr, cg, cb, 60)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            fillPaint.shader = grad
            canvas.drawCircle(0f, 0f, radius, fillPaint)
            fillPaint.shader = null

            // Specular highlight
            val specR = radius * 0.35f
            if (specR > 0.5f) {
                val spec = RadialGradient(
                    -radius * 0.25f, -radius * 0.3f, specR,
                    intArrayOf(Color.argb(102, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
                fillPaint.shader = spec
                canvas.drawCircle(0f, 0f, radius, fillPaint)
                fillPaint.shader = null
            }
        } else {
            strokePaint.color = Color.rgb(cr, cg, cb)
            strokePaint.strokeWidth = max(1.5f, sz * 0.03f)
            canvas.drawCircle(0f, 0f, radius, strokePaint)
        }
    }

    private fun drawCylinder3D(
        canvas: Canvas, w: Float, h: Float, cr: Int, cg: Int, cb: Int,
        filled: Boolean, lw: Float,
        fillPaint: Paint, strokePaint: Paint, tmpRect: RectF
    ) {
        val hw = w * 0.45f
        val hh = h * 0.45f
        val capRy = hw * 0.3f
        if (hw < 0.5f || hh < 0.5f) return

        if (filled) {
            val dark = darken(cr, cg, cb, 40)
            val light = lighten(cr, cg, cb, 40)

            // Bottom cap (ellipse)
            fillPaint.color = darken(cr, cg, cb, 30)
            tmpRect.set(-hw, hh - capRy * 2f, hw, hh)
            canvas.drawOval(tmpRect, fillPaint)

            // Body with linear gradient dark → light → dark
            val bodyGrad = LinearGradient(
                -hw, 0f, hw, 0f,
                intArrayOf(dark, light, dark),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            fillPaint.shader = bodyGrad
            canvas.drawRect(-hw, -hh + capRy, hw, hh - capRy, fillPaint)
            fillPaint.shader = null

            // Top cap
            fillPaint.color = lighten(cr, cg, cb, 50)
            tmpRect.set(-hw, -hh, hw, -hh + capRy * 2f)
            canvas.drawOval(tmpRect, fillPaint)

            // Edges
            strokePaint.color = Color.argb(64, 0, 0, 0)
            strokePaint.strokeWidth = lw
            canvas.drawLine(-hw, -hh + capRy, -hw, hh - capRy, strokePaint)
            canvas.drawLine(hw, -hh + capRy, hw, hh - capRy, strokePaint)
            tmpRect.set(-hw, -hh, hw, -hh + capRy * 2f)
            canvas.drawOval(tmpRect, strokePaint)
            // Bottom half-arc
            tmpRect.set(-hw, hh - capRy * 2f, hw, hh)
            canvas.drawArc(tmpRect, 0f, 180f, false, strokePaint)
        } else {
            strokePaint.color = Color.rgb(cr, cg, cb)
            strokePaint.strokeWidth = lw * 2f
            tmpRect.set(-hw, -hh, hw, -hh + capRy * 2f)
            canvas.drawOval(tmpRect, strokePaint)
            canvas.drawLine(-hw, -hh + capRy, -hw, hh - capRy, strokePaint)
            canvas.drawLine(hw, -hh + capRy, hw, hh - capRy, strokePaint)
            tmpRect.set(-hw, hh - capRy * 2f, hw, hh)
            canvas.drawArc(tmpRect, 0f, 180f, false, strokePaint)
        }
    }

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w == 0f || h == 0f) return

        val count = ((params["shapeCount"] as? Number)?.toInt() ?: 200).coerceIn(1, MAX_SHAPES)
        val shape = (params["shape"] as? String) ?: "square"
        val sizeMode = (params["sizeMode"] as? String) ?: "power"
        val packMode = (params["packMode"] as? String) ?: "best-fit"
        val fillMode = (params["fillMode"] as? String) ?: "mixed"

        // Size scale mirrors the web port (w / 1080) so parameter ranges feel consistent.
        val sizeScale = w / 1080f
        val minSize = ((params["minSize"] as? Number)?.toFloat() ?: 8f) * sizeScale
        val maxSize = ((params["maxSize"] as? Number)?.toFloat() ?: 80f) * sizeScale
        val gap = ((params["gap"] as? Number)?.toFloat() ?: 1f) * sizeScale
        val jitter = ((params["jitter"] as? Number)?.toFloat() ?: 2f) * sizeScale

        val colorBy = (params["colorBy"] as? String) ?: "size"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 1f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx

        // Cache key — deterministic params that affect packing only.
        val key = "$seed|$count|$shape|$sizeMode|$packMode|$fillMode|$minSize|$maxSize|$gap|$jitter|${w.toInt()}|${h.toInt()}"
        val data = getOrBuildPacking(
            key, seed, count, shape, sizeMode, packMode, fillMode,
            minSize, maxSize, gap, jitter, w, h
        )
        val placed = data.count

        canvas.drawColor(Color.rgb(10, 10, 10))
        if (placed == 0) return

        // Min/max for color normalization
        var minSz = Float.POSITIVE_INFINITY
        var maxSz = 0f
        var minY = Float.POSITIVE_INFINITY
        var maxY = 0f
        for (idx in 0 until placed) {
            val sz = max(data.sw[idx], data.sh[idx])
            if (sz < minSz) minSz = sz
            if (sz > maxSz) maxSz = sz
            if (data.sy[idx] < minY) minY = data.sy[idx]
            if (data.sy[idx] > maxY) maxY = data.sy[idx]
        }
        val szRange = (maxSz - minSz).takeIf { it > 0f } ?: 1f
        val yRange = (maxY - minY).takeIf { it > 0f } ?: 1f

        // Animation timing
        val doAnim = animSpeed > 0f
        val stagger = if (doAnim) 0.02f / max(0.1f, animSpeed) else 0f
        val fallDur = 0.4f

        // Pre-compute quantized color buckets via the shared palette LUT helper.
        // We split into per-channel arrays so 3D shading math (lighten/darken)
        // can operate on ints directly without re-parsing colors per shape.
        val paletteLut = palette.buildLut(NUM_BUCKETS)
        val bucketR = IntArray(NUM_BUCKETS)
        val bucketG = IntArray(NUM_BUCKETS)
        val bucketB = IntArray(NUM_BUCKETS)
        for (b in 0 until NUM_BUCKETS) {
            val c = paletteLut[b]
            bucketR[b] = Color.red(c)
            bucketG[b] = Color.green(c)
            bucketB[b] = Color.blue(c)
        }

        val rngColor = SeededRNG(seed + 99)
        val bucketAssign = IntArray(placed)
        for (idx in 0 until placed) {
            val t = when (colorBy) {
                "size" -> (max(data.sw[idx], data.sh[idx]) - minSz) / szRange
                "order" -> data.sColorT[idx]
                "height" -> (data.sy[idx] - minY) / yRange
                else -> rngColor.random()
            }
            bucketAssign[idx] = (t * NUM_BUCKETS).toInt().coerceIn(0, NUM_BUCKETS - 1)
        }

        val antiAlias = quality != Quality.DRAFT
        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = antiAlias
        }
        val strokePaint = Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = antiAlias
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val outlineLw = max(1.5f, 2.5f * sizeScale)
        val borderLw = max(0.5f, 0.8f * sizeScale)

        val bounds = Bounds()
        val tempPath = Path()
        val tmpRect = RectF()

        // ── Pass 1: Filled 2D shapes (batched per color bucket) ─────
        for (b in 0 until NUM_BUCKETS) {
            tempPath.rewind()
            var has = false
            for (idx in 0 until placed) {
                if (bucketAssign[idx] != b) continue
                if (!data.sFill[idx]) continue
                if (data.sKind[idx] >= KIND_CUBE) continue

                resolveBounds(bounds, data, idx, doAnim, time, stagger, fallDur, animSpeed, animAmp, audioBass)
                if (!bounds.visible) continue
                addShapePath(
                    tempPath, bounds.cx, bounds.cy, bounds.sw, bounds.sh,
                    data.sKind[idx], data.sCosA[idx], data.sSinA[idx]
                )
                has = true
            }
            if (has) {
                fillPaint.color = Color.rgb(bucketR[b], bucketG[b], bucketB[b])
                canvas.drawPath(tempPath, fillPaint)
            }
        }

        // ── Pass 2: Outline 2D shapes (batched per color bucket) ────
        strokePaint.strokeWidth = outlineLw
        for (b in 0 until NUM_BUCKETS) {
            tempPath.rewind()
            var has = false
            for (idx in 0 until placed) {
                if (bucketAssign[idx] != b) continue
                if (data.sFill[idx]) continue
                if (data.sKind[idx] >= KIND_CUBE) continue

                resolveBounds(bounds, data, idx, doAnim, time, stagger, fallDur, animSpeed, animAmp, audioBass)
                if (!bounds.visible) continue
                addShapePath(
                    tempPath, bounds.cx, bounds.cy, bounds.sw, bounds.sh,
                    data.sKind[idx], data.sCosA[idx], data.sSinA[idx]
                )
                has = true
            }
            if (has) {
                strokePaint.color = Color.rgb(bucketR[b], bucketG[b], bucketB[b])
                canvas.drawPath(tempPath, strokePaint)
            }
        }

        // ── Pass 3: Dark borders on filled 2D shapes ────────────────
        tempPath.rewind()
        var hasBorders = false
        for (idx in 0 until placed) {
            if (!data.sFill[idx]) continue
            if (data.sKind[idx] >= KIND_CUBE) continue

            resolveBounds(bounds, data, idx, doAnim, time, stagger, fallDur, animSpeed, animAmp, audioBass)
            if (!bounds.visible) continue
            addShapePath(
                tempPath, bounds.cx, bounds.cy, bounds.sw, bounds.sh,
                data.sKind[idx], data.sCosA[idx], data.sSinA[idx]
            )
            hasBorders = true
        }
        if (hasBorders) {
            strokePaint.color = Color.argb(64, 0, 0, 0)
            strokePaint.strokeWidth = borderLw
            canvas.drawPath(tempPath, strokePaint)
        }

        // ── Pass 4: 3D shapes (drawn individually) ──────────────────
        for (idx in 0 until placed) {
            val kind = data.sKind[idx]
            if (kind < KIND_CUBE) continue

            resolveBounds(bounds, data, idx, doAnim, time, stagger, fallDur, animSpeed, animAmp, audioBass)
            if (!bounds.visible) continue

            val bIdx = bucketAssign[idx]
            val cr = bucketR[bIdx]
            val cg = bucketG[bIdx]
            val cb = bucketB[bIdx]
            val isFilled = data.sFill[idx]

            canvas.save()
            canvas.translate(bounds.cx, bounds.cy)
            if (kind != KIND_SPHERE) {
                canvas.rotate(data.sAngle[idx] * RAD_TO_DEG)
            }

            when (kind) {
                KIND_CUBE -> drawIsoCube(
                    canvas, max(bounds.sw, bounds.sh), cr, cg, cb, isFilled, borderLw,
                    fillPaint, strokePaint, tempPath
                )
                KIND_SPHERE -> drawSphere3D(
                    canvas, max(bounds.sw, bounds.sh), cr, cg, cb, isFilled,
                    fillPaint, strokePaint
                )
                KIND_CYLINDER -> drawCylinder3D(
                    canvas, bounds.sw, bounds.sh, cr, cg, cb, isFilled, borderLw,
                    fillPaint, strokePaint, tmpRect
                )
            }

            canvas.restore()
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val count = (params["shapeCount"] as? Number)?.toFloat() ?: 200f
        val isBestFit = (params["packMode"] as? String) == "best-fit"
        val factor = if (isBestFit) 50f else 1f
        return (count * factor / 25000f).coerceIn(0.1f, 1f)
    }
}
