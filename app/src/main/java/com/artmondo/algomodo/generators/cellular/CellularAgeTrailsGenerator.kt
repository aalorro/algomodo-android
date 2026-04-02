package com.artmondo.algomodo.generators.cellular

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import kotlin.math.pow
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality

class CellularAgeTrailsGenerator : Generator {

    override val id = "cellular-age-trails"
    override val family = "cellular"
    override val styleName = "Age Trails"
    override val definition = "A Game of Life variant that tracks how recently each cell was alive, producing colorful fading trails."
    override val algorithmNotes = "Standard B3/S23 rules are applied. Each cell maintains an age counter: living cells have age 0, recently dead cells count up. Cells with age below the trail length are rendered with color mapped through the palette gradient."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.COMPOSITION, "Cell grid resolution", 32f, 256f, 16f, 128f),
        Parameter.NumberParam("Initial Density", "density", ParamGroup.COMPOSITION, "Fraction of cells alive at start — above 0.40 the initial density is too high for patterns to emerge", 0.1f, 0.40f, 0.05f, 0.35f),
        Parameter.SelectParam("CA Rule", "rule", ParamGroup.COMPOSITION, "Life B3/S23 | HighLife B36/S23 (self-replicators) | Maze B3/S12345 | Day & Night B3678/S34678 | Seeds B2/S (explosive, nothing survives)", listOf("life", "highlife", "maze", "daynight", "seeds"), "life"),
        Parameter.NumberParam("Exposure Frames", "warmupSteps", ParamGroup.COMPOSITION, "CA steps blended into the static render — more frames = denser historical record", 50f, 2000f, 50f, 400f),
        Parameter.NumberParam("Trail Decay", "decay", ParamGroup.FLOW_MOTION, "Per-step decay multiplier on accumulated brightness — lower = short crisp trails, higher = long ghostly halos", 0.80f, 0.999f, 0.005f, 0.95f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.FLOW_MOTION, "Brightness added per alive-cell frame — raise to saturate stable regions faster", 0.05f, 2.0f, 0.05f, 0.5f),
        Parameter.NumberParam("Steps / Frame", "stepsPerFrame", ParamGroup.FLOW_MOTION, "CA steps advanced per animation frame", 1f, 10f, 1f, 1f),
        Parameter.NumberParam("Gamma", "gamma", ParamGroup.TEXTURE, "Tone-map exponent — < 1 lifts dim trails into view, > 1 compresses midtones for starker contrast", 0.3f, 3.0f, 0.1f, 0.7f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette: map brightness through the active colour ramp | heat: fixed black → red → orange → white heat map", listOf("palette", "heat"), "palette")
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 128f,
        "density" to 0.35f,
        "rule" to "life",
        "warmupSteps" to 400f,
        "decay" to 0.95f,
        "exposure" to 0.5f,
        "stepsPerFrame" to 1f,
        "gamma" to 0.7f,
        "colorMode" to "palette"
    )

    // Persistent CA state for incremental animation — avoids replaying from step 0 each frame
    private var stateKey = 0L
    private var stateStep = -1
    private var stateAlive: BooleanArray? = null
    private var stateAge: IntArray? = null
    // Reusable double-buffer for CA step (avoids allocation per step)
    private var tmpAlive: BooleanArray? = null
    private var tmpAge: IntArray? = null

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val gridSize = (params["gridSize"] as? Number)?.toInt() ?: 128
        val density = (params["density"] as? Number)?.toFloat() ?: 0.35f
        val rule = (params["rule"] as? String) ?: "life"
        val warmupSteps = (params["warmupSteps"] as? Number)?.toInt() ?: 400
        val stepsPerFrame = (params["stepsPerFrame"] as? Number)?.toFloat() ?: 1f
        val decay = (params["decay"] as? Number)?.toFloat() ?: 0.95f
        val exposure = (params["exposure"] as? Number)?.toFloat() ?: 0.5f
        val gamma = (params["gamma"] as? Number)?.toFloat() ?: 0.7f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        val w = bitmap.width
        val h = bitmap.height
        val totalCells = gridSize * gridSize
        val targetSteps = warmupSteps + (time * stepsPerFrame).toInt()
        val effectiveTrailLength = if (decay < 1f)
            (kotlin.math.ln(0.01f) / kotlin.math.ln(decay)).toInt().coerceIn(1, 200) else 200

        // Cache key from CA-affecting parameters only (rendering params don't affect simulation)
        val key = seed.toLong() * 1_000_003 + gridSize.toLong() * 31 +
                density.toBits().toLong() * 17 + rule.hashCode().toLong()

        // Reset state if params changed or time went backwards (animation restart)
        if (key != stateKey || stateAlive?.size != totalCells || targetSteps < stateStep) {
            val rng = SeededRNG(seed)
            stateAlive = BooleanArray(totalCells)
            stateAge = IntArray(totalCells) { NEVER_ALIVE }
            val alive = stateAlive!!
            val age = stateAge!!
            for (i in 0 until totalCells) {
                if (rng.random() < density) {
                    alive[i] = true
                    age[i] = 0
                }
            }
            stateStep = 0
            stateKey = key
        }

        val alive = stateAlive!!
        val age = stateAge!!

        // Ensure temp buffers are correctly sized
        if (tmpAlive?.size != totalCells) {
            tmpAlive = BooleanArray(totalCells)
            tmpAge = IntArray(totalCells)
        }
        val na = tmpAlive!!
        val nAge = tmpAge!!

        // Pre-compute wrapped row offsets and column indices
        val rowOff = IntArray(gridSize) { it * gridSize }
        val yUp = IntArray(gridSize) { ((it - 1 + gridSize) % gridSize) * gridSize }
        val yDown = IntArray(gridSize) { ((it + 1) % gridSize) * gridSize }
        val xLeft = IntArray(gridSize) { (it - 1 + gridSize) % gridSize }
        val xRight = IntArray(gridSize) { (it + 1) % gridSize }

        // Pre-compute birth/survive as boolean lookup tables (avoids when/setOf in hot loop)
        val birth = BooleanArray(9)
        val survive = BooleanArray(9)
        when (rule) {
            "highlife" -> { birth[3] = true; birth[6] = true; survive[2] = true; survive[3] = true }
            "maze" -> { birth[3] = true; for (n in 1..5) survive[n] = true }
            "daynight" -> { for (n in intArrayOf(3, 6, 7, 8)) birth[n] = true; for (n in intArrayOf(3, 4, 6, 7, 8)) survive[n] = true }
            "seeds" -> { birth[2] = true }
            else -> { birth[3] = true; survive[2] = true; survive[3] = true }
        }

        // Advance CA incrementally — only the steps we haven't computed yet
        while (stateStep < targetSteps) {
            for (y in 0 until gridSize) {
                val ru = yUp[y]; val rs = rowOff[y]; val rd = yDown[y]
                for (x in 0 until gridSize) {
                    val xl = xLeft[x]; val xr = xRight[x]
                    val neighbors =
                        (if (alive[ru + xl]) 1 else 0) +
                        (if (alive[ru + x]) 1 else 0) +
                        (if (alive[ru + xr]) 1 else 0) +
                        (if (alive[rs + xl]) 1 else 0) +
                        (if (alive[rs + xr]) 1 else 0) +
                        (if (alive[rd + xl]) 1 else 0) +
                        (if (alive[rd + x]) 1 else 0) +
                        (if (alive[rd + xr]) 1 else 0)
                    val idx = rs + x
                    val willLive = if (alive[idx]) survive[neighbors] else birth[neighbors]
                    na[idx] = willLive
                    nAge[idx] = if (willLive) 0 else age[idx] + 1
                }
            }
            System.arraycopy(na, 0, alive, 0, totalCells)
            System.arraycopy(nAge, 0, age, 0, totalCells)
            stateStep++
        }

        // Build color look-up table (avoids per-pixel pow / palette.lerpColor)
        val lutSize = effectiveTrailLength + 1
        val colorLut = IntArray(lutSize)
        for (a in 0 until lutSize) {
            val t = a.toFloat() / effectiveTrailLength
            val brightness = ((1f - t) * exposure).coerceIn(0f, 1f)
            val mapped = brightness.toDouble().pow(gamma.toDouble()).toFloat()
            val color = when (colorMode) {
                "heat" -> palette.lerpColor(mapped)
                else -> palette.lerpColor(t)
            }
            val alpha = (mapped * 255).toInt().coerceIn(0, 255)
            colorLut[a] = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        }

        // Render grid cells to pixels using LUT
        val cellW = w.toFloat() / gridSize
        val cellH = h.toFloat() / gridSize
        val pixels = IntArray(w * h)

        for (gy in 0 until gridSize) {
            val pyStart = (gy * cellH).toInt()
            val pyEnd = ((gy + 1) * cellH).toInt().coerceAtMost(h)
            val gridRow = gy * gridSize
            for (gx in 0 until gridSize) {
                val pxStart = (gx * cellW).toInt()
                val pxEnd = ((gx + 1) * cellW).toInt().coerceAtMost(w)
                val cellAge = age[gridRow + gx]
                val color = if (cellAge < lutSize) colorLut[cellAge] else Color.BLACK
                for (py in pyStart until pyEnd) {
                    java.util.Arrays.fill(pixels, py * w + pxStart, py * w + pxEnd, color)
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    companion object {
        private const val NEVER_ALIVE = 10000
    }
}
