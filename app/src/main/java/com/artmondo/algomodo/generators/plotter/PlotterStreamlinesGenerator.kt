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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streamline generator from a noise-based vector field.
 *
 * Seeds points across the canvas and traces streamlines by following the
 * noise-derived angle at each point. Evenly-spaced seeding prevents overlap.
 */
class PlotterStreamlinesGenerator : Generator {

    override val id = "plotter-streamlines"
    override val family = "plotter"
    override val styleName = "Streamlines"
    override val definition =
        "Evenly-spaced streamlines following a simplex noise vector field."
    override val algorithmNotes =
        "Seed points are distributed on a grid with jitter. From each seed, the streamline " +
        "is integrated forward and backward by sampling the noise field angle at the current " +
        "position and stepping in that direction. Lines terminate when they leave the canvas " +
        "or come too close to another streamline."
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
        val lineCount = (params["lineCount"] as? Number)?.toFloat() ?: 400f
        val maxLength = (params["maxSteps"] as? Number)?.toFloat() ?: 200f
        val noiseScale = (params["fieldScale"] as? Number)?.toFloat() ?: 1.8f
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 6f

        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.15f
        val timeOff = time * animSpeed

        val rng = SeededRNG(seed)
        val noise = SimplexNoise(seed)
        val paletteColors = palette.colorInts()

        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // Separation distance between streamlines
        val sep = 15f / (lineCount / 400f).coerceAtLeast(0.1f)
        val stepSize = 2f

        // Occupancy grid for separation test
        val gridCellSize = sep * 0.5f
        val gridW = (w / gridCellSize).toInt() + 1
        val gridH = (h / gridCellSize).toInt() + 1
        val occupied = BooleanArray(gridW * gridH)

        fun markOccupied(px: Float, py: Float) {
            val gx = (px / gridCellSize).toInt()
            val gy = (py / gridCellSize).toInt()
            if (gx in 0 until gridW && gy in 0 until gridH) {
                occupied[gy * gridW + gx] = true
            }
        }

        fun isOccupied(px: Float, py: Float): Boolean {
            val gx = (px / gridCellSize).toInt()
            val gy = (py / gridCellSize).toInt()
            val r = 1
            for (dy in -r..r) {
                for (dx in -r..r) {
                    val nx = gx + dx
                    val ny = gy + dy
                    if (nx in 0 until gridW && ny in 0 until gridH) {
                        if (occupied[ny * gridW + nx]) return true
                    }
                }
            }
            return false
        }

        // Generate seed points with jitter
        val seeds = mutableListOf<Pair<Float, Float>>()
        var sy = sep / 2f
        while (sy < h) {
            var sx = sep / 2f
            while (sx < w) {
                seeds.add(Pair(
                    sx + rng.range(-sep * 0.3f, sep * 0.3f),
                    sy + rng.range(-sep * 0.3f, sep * 0.3f)
                ))
                sx += sep
            }
            sy += sep
        }
        rng.shuffle(seeds.toMutableList()).let { seeds.clear(); seeds.addAll(it) }

        var colorIdx = 0
        for ((startX, startY) in seeds) {
            if (startX < 0 || startX >= w || startY < 0 || startY >= h) continue
            if (isOccupied(startX, startY)) continue

            val points = mutableListOf<Pair<Float, Float>>()

            // Trace forward
            var px = startX
            var py = startY
            var totalDist = 0f
            for (step in 0 until (maxLength / stepSize).toInt()) {
                if (px < 0 || px >= w || py < 0 || py >= h) break
                if (step > 2 && isOccupied(px, py)) break

                points.add(Pair(px, py))
                markOccupied(px, py)

                val angle = noise.noise2D(px / w * noiseScale + timeOff, py / h * noiseScale) * PI.toFloat()
                px += cos(angle) * stepSize
                py += sin(angle) * stepSize
                totalDist += stepSize
            }

            // Trace backward
            px = startX
            py = startY
            val backPoints = mutableListOf<Pair<Float, Float>>()
            for (step in 0 until (maxLength / stepSize).toInt()) {
                if (px < 0 || px >= w || py < 0 || py >= h) break
                if (step > 2 && isOccupied(px, py)) break

                backPoints.add(Pair(px, py))
                markOccupied(px, py)

                val angle = noise.noise2D(px / w * noiseScale + timeOff, py / h * noiseScale) * PI.toFloat()
                px -= cos(angle) * stepSize
                py -= sin(angle) * stepSize
            }

            // Combine backward (reversed) + forward
            val allPoints = backPoints.reversed() + points.drop(1)
            if (allPoints.size < 3) continue

            val path = Path()
            path.moveTo(allPoints[0].first, allPoints[0].second)
            for (i in 1 until allPoints.size) {
                path.lineTo(allPoints[i].first, allPoints[i].second)
            }

            paint.color = paletteColors[colorIdx % paletteColors.size]
            paint.alpha = 200
            canvas.drawPath(path, paint)
            colorIdx++
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val lineCount = (params["lineCount"] as? Number)?.toFloat() ?: 400f
        return (lineCount / 600f).coerceIn(0.2f, 1f)
    }
}
