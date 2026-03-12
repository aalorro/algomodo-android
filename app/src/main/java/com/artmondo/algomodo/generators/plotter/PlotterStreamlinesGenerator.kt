package com.artmondo.algomodo.generators.plotter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streamline generator from a noise-based vector field.
 *
 * Seeds points across the canvas and traces streamlines by following the
 * noise-derived vector at each point. Evenly-spaced seeding prevents overlap.
 * Supports curl-noise (divergence-free swirling), gradient (converging flows),
 * and sine-lattice (regular wave pattern) field types.
 */
class PlotterStreamlinesGenerator : Generator {

    override val id = "plotter-streamlines"
    override val family = "plotter"
    override val styleName = "Streamlines"
    override val definition =
        "Traces evenly-spaced streamlines through a smooth 2D vector field derived from noise."
    override val algorithmNotes =
        "A SimplexNoise scalar field generates the flow via curl (divergence-free), gradient " +
        "(converging), or sine-lattice modes. Each streamline is integrated with the Euler method " +
        "and terminated when it exits the canvas or approaches an existing line. A separation grid " +
        "prevents overcrowding."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Line Count", "lineCount", ParamGroup.COMPOSITION, null, 20f, 600f, 10f, 400f),
        Parameter.NumberParam("Max Length", "maxSteps", ParamGroup.COMPOSITION, "Maximum integration steps per streamline", 20f, 500f, 10f, 200f),
        Parameter.NumberParam("Step Length", "stepLength", ParamGroup.GEOMETRY, "Euler integration step size in pixels", 1f, 16f, 0.5f, 5f),
        Parameter.NumberParam("Field Scale", "fieldScale", ParamGroup.GEOMETRY, "Spatial frequency of the vector field", 0.3f, 6f, 0.1f, 1.8f),
        Parameter.NumberParam("Min Separation", "minSeparation", ParamGroup.GEOMETRY, "Minimum pixel gap between adjacent streamlines", 2f, 30f, 1f, 8f),
        Parameter.SelectParam("Field Type", "fieldType", ParamGroup.COMPOSITION, "curl-noise: swirling vortex-free field | gradient: flows toward peaks | sine-lattice: regular wave pattern", listOf("curl-noise", "gradient", "sine-lattice"), "curl-noise"),
        Parameter.NumberParam("Line Width", "lineWidth", ParamGroup.GEOMETRY, null, 0.5f, 12f, 0.5f, 6f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "velocity: color by field magnitude | position: color by canvas XY", listOf("palette-cycle", "velocity", "position"), "palette-cycle"),
        Parameter.SelectParam("Background", "background", ParamGroup.COLOR, null, listOf("white", "cream", "dark"), "cream"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Speed at which the vector field drifts over time (0 = static)", 0f, 1f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "lineCount" to 400f,
        "maxSteps" to 200f,
        "stepLength" to 5f,
        "fieldScale" to 1.8f,
        "minSeparation" to 8f,
        "fieldType" to "curl-noise",
        "lineWidth" to 6f,
        "colorMode" to "palette-cycle",
        "background" to "cream",
        "animSpeed" to 0.15f
    )

    companion object {
        private val BG = mapOf(
            "white" to Color.parseColor("#F8F8F5"),
            "cream" to Color.parseColor("#F2EAD8"),
            "dark" to Color.parseColor("#0E0E0E")
        )
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
        val maxLines = ((params["lineCount"] as? Number)?.toFloat() ?: 400f).toInt()
        val maxSteps = ((params["maxSteps"] as? Number)?.toFloat() ?: 200f).toInt()
        val step = (params["stepLength"] as? Number)?.toFloat() ?: 5f
        val fScale = (params["fieldScale"] as? Number)?.toFloat() ?: 1.8f
        val minSep = (params["minSeparation"] as? Number)?.toFloat() ?: 8f
        val fieldType = params["fieldType"] as? String ?: "curl-noise"
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 6f
        val colorMode = params["colorMode"] as? String ?: "palette-cycle"
        val background = params["background"] as? String ?: "cream"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.15f
        val timeOffset = time * animSpeed * 0.4f
        val isDark = background == "dark"

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)

        // Seed-based field rotation and noise origin — ensures each seed produces
        // a genuinely different flow direction instead of a fixed ~45° diagonal.
        val fieldRotation = rng.range(0f, 2f * PI.toFloat())
        val cosRot = cos(fieldRotation)
        val sinRot = sin(fieldRotation)
        val noiseOffX = rng.range(0f, 100f)
        val noiseOffY = rng.range(0f, 100f)

        // Draw background
        canvas.drawColor(BG[background] ?: BG["cream"]!!)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Separation grid — cell size = minSep / sqrt(2) for proper radius coverage
        val sepCell = max(1f, minSep / sqrt(2f))
        val sgw = ceil(w / sepCell).toInt() + 1
        val sgh = ceil(h / sepCell).toInt() + 1
        val sepGrid = BooleanArray(sgw * sgh)

        fun markOccupied(px: Float, py: Float) {
            val gx = min(sgw - 1, max(0, floor(px / sepCell).toInt()))
            val gy = min(sgh - 1, max(0, floor(py / sepCell).toInt()))
            sepGrid[gy * sgw + gx] = true
        }

        fun isOccupied(px: Float, py: Float): Boolean {
            val gx = floor(px / sepCell).toInt()
            val gy = floor(py / sepCell).toInt()
            val r = ceil(minSep / sepCell).toInt()
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val nx = gx + dx
                    val ny = gy + dy
                    if (nx in 0 until sgw && ny in 0 until sgh && sepGrid[ny * sgw + nx]) {
                        // Distance check against cell center
                        val cx = (nx + 0.5f) * sepCell
                        val cy = (ny + 0.5f) * sepCell
                        val ddx = px - cx
                        val ddy = py - cy
                        if (ddx * ddx + ddy * ddy < minSep * minSep) return true
                    }
                }
            }
            return false
        }

        // FBM-based vector field computation with seed-based rotation
        fun getField(x: Float, y: Float): Pair<Float, Float> {
            val nx = (x / w - 0.5f) * fScale + noiseOffX + timeOffset
            val ny = (y / h - 0.5f) * fScale + noiseOffY + timeOffset * 0.7f

            var vx: Float
            var vy: Float

            if (fieldType == "sine-lattice") {
                val freq = fScale * 3f
                vx = sin(ny * freq) + 0.3f * sin(nx * freq * 1.5f)
                vy = cos(nx * freq) + 0.3f * cos(ny * freq * 1.5f)
            } else {
                // Epsilon in noise-space units — ~1.5% of fScale
                val eps = fScale * 0.015f

                if (fieldType == "gradient") {
                    vx = noise.fbm(nx + eps, ny, 4, 2f, 0.5f) - noise.fbm(nx - eps, ny, 4, 2f, 0.5f)
                    vy = noise.fbm(nx, ny + eps, 4, 2f, 0.5f) - noise.fbm(nx, ny - eps, 4, 2f, 0.5f)
                } else {
                    // curl-noise (default): divergence-free swirling field
                    val dFy = noise.fbm(nx, ny + eps, 4, 2f, 0.5f) - noise.fbm(nx, ny - eps, 4, 2f, 0.5f)
                    val dFx = noise.fbm(nx + eps, ny, 4, 2f, 0.5f) - noise.fbm(nx - eps, ny, 4, 2f, 0.5f)
                    vx = dFy
                    vy = -dFx
                }
            }

            // Apply seed-based rotation so each seed produces a different flow direction
            val rvx = vx * cosRot - vy * sinRot
            val rvy = vx * sinRot + vy * cosRot
            val len = sqrt(rvx * rvx + rvy * rvy) + 1e-6f
            return (rvx / len) to (rvy / len)
        }

        val paletteColors = palette.colorInts()

        // Jittered grid seed pool — uniform coverage regardless of field topology
        val gridSpacing = minSep * 1.5f
        val gCols = ceil(w / gridSpacing).toInt()
        val gRows = ceil(h / gridSpacing).toInt()
        val seeds = mutableListOf<Pair<Float, Float>>()
        for (gr in 0 until gRows) {
            for (gc in 0 until gCols) {
                seeds.add(
                    (gc + rng.range(0f, 1f)) * gridSpacing to
                    (gr + rng.range(0f, 1f)) * gridSpacing
                )
            }
        }
        // Fisher-Yates shuffle
        for (i in seeds.size - 1 downTo 1) {
            val j = (rng.range(0f, 1f) * (i + 1)).toInt().coerceAtMost(i)
            val tmp = seeds[i]; seeds[i] = seeds[j]; seeds[j] = tmp
        }

        var linesDrawn = 0
        var seedIdx = 0

        while (linesDrawn < maxLines && seedIdx < seeds.size) {
            val (sx, sy) = seeds[seedIdx++]
            if (sx < 0 || sx >= w || sy < 0 || sy >= h) continue
            if (isOccupied(sx, sy)) continue

            // Trace forward
            val pts = mutableListOf(sx to sy)
            var x = sx
            var y = sy
            for (s in 0 until maxSteps) {
                val (vx, vy) = getField(x, y)
                val nx = x + vx * step
                val ny = y + vy * step
                if (nx < 0 || nx > w || ny < 0 || ny > h) break
                if (s > 0 && isOccupied(nx, ny)) break
                pts.add(nx to ny)
                x = nx; y = ny
            }

            if (pts.size < 3) continue

            // Determine color
            val color: Int
            if (colorMode == "position") {
                val t = (sx / w * 0.5f + sy / h * 0.5f)
                color = palette.lerpColor(t)
            } else if (colorMode == "velocity") {
                val (vx, vy) = getField(sx, sy)
                val speed = min(1f, sqrt(vx * vx + vy * vy))
                color = palette.lerpColor(speed)
            } else {
                color = paletteColors[linesDrawn % paletteColors.size]
            }

            paint.color = color
            paint.alpha = if (isDark) 217 else 191 // 0.85 / 0.75

            val path = Path()
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) {
                path.lineTo(pts[i].first, pts[i].second)
            }
            canvas.drawPath(path, paint)

            // Mark all points as occupied after drawing
            for ((px, py) in pts) markOccupied(px, py)

            linesDrawn++
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val lineCount = (params["lineCount"] as? Number)?.toFloat() ?: 400f
        return (lineCount / 600f).coerceIn(0.2f, 1f)
    }
}
