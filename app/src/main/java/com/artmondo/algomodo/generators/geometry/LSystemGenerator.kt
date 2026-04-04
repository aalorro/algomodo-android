package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class LSystemGenerator : Generator {

    override val id = "lsystem"
    override val family = "geometry"
    override val styleName = "L-System"
    override val definition =
        "Lindenmayer system fractals rendered via turtle graphics, producing Koch, Sierpinski, dragon, plant, and Hilbert patterns."
    override val algorithmNotes =
        "Starts with an axiom string and repeatedly applies production rules for the configured " +
        "number of iterations. The resulting string is interpreted as turtle graphics commands: " +
        "F = draw forward, + = turn right, - = turn left, [ = push state, ] = pop state. " +
        "Animation progressively reveals the drawing, growing it segment by segment."
    override val supportsVector = true
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            name = "Preset",
            key = "preset",
            group = ParamGroup.COMPOSITION,
            help = "Tree/Plant: fractal branching | Dragon: space-filling dragon curve | Sierpinski: triangle fractal | Hilbert: space-filling square curve | Koch: snowflake edge | Gosper: flowsnake / peano curve",
            options = listOf("Tree", "Plant", "Dragon", "Sierpinski", "Hilbert", "Koch", "Gosper"),
            default = "Tree"
        ),
        Parameter.NumberParam(
            name = "Iterations",
            key = "iterations",
            group = ParamGroup.COMPOSITION,
            help = "String rewriting steps \u2014 each iteration multiplies detail; high values may be slow",
            min = 1f, max = 8f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Angle",
            key = "angle",
            group = ParamGroup.GEOMETRY,
            help = "Turn angle in degrees \u2014 overrides preset default; deviating from the preset angle creates distorted/organic variants",
            min = 5f, max = 180f, step = 1f, default = 25f
        ),
        Parameter.NumberParam(
            name = "Step Length",
            key = "stepLength",
            group = ParamGroup.GEOMETRY,
            help = "Length of each forward step before auto-scaling",
            min = 1f, max = 20f, step = 1f, default = 8f
        ),
        Parameter.NumberParam(
            name = "Stochastic",
            key = "stochastic",
            group = ParamGroup.GEOMETRY,
            help = "Random angle jitter in degrees \u2014 adds seeded noise to each turn. 0 = deterministic. 5\u201310 = natural plant variation. 15\u201320 = highly chaotic.",
            min = 0f, max = 20f, step = 1f, default = 0f
        ),
        Parameter.BooleanParam(
            name = "Taper Width",
            key = "taper",
            group = ParamGroup.TEXTURE,
            help = "Scale line width by branch depth \u2014 trunk thick, tips thin",
            default = false
        ),
        Parameter.NumberParam(
            name = "Line Width",
            key = "lineWidth",
            group = ParamGroup.TEXTURE,
            help = "Base line width (trunk width when taper is on)",
            min = 0.5f, max = 8f, step = 0.5f, default = 1f
        ),
        Parameter.SelectParam(
            name = "Fill",
            key = "fill",
            group = ParamGroup.COMPOSITION,
            help = "auto: single pattern | half: scatter random instances across ~half the canvas | full: fill the page with unique randomly placed elements",
            options = listOf("auto", "half", "full"),
            default = "auto"
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "depth: colour by branch nesting level | gradient: colour sweeps through palette by drawing order | single: first palette colour only",
            options = listOf("depth", "gradient", "single"),
            default = "depth"
        ),
        Parameter.NumberParam(
            name = "Reveal Speed",
            key = "revealSpeed",
            group = ParamGroup.FLOW_MOTION,
            help = "Draw-reveal speed in animation mode \u2014 the curve progressively draws itself then cycles",
            min = 0.1f, max = 5f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "preset" to "Tree",
        "iterations" to 5f,
        "angle" to 25f,
        "stepLength" to 8f,
        "stochastic" to 0f,
        "taper" to false,
        "lineWidth" to 1f,
        "fill" to "auto",
        "colorMode" to "depth",
        "revealSpeed" to 1.0f
    )

    private data class LSystemDef(
        val axiom: String,
        val rules: Map<Char, String>,
        val defaultAngle: Float,
        val startAngle: Float = 0f,
        val drawChars: Set<Char> = setOf('F', 'G')
    )

    private fun getPreset(preset: String): LSystemDef {
        return when (preset.lowercase()) {
            "tree" -> LSystemDef(
                axiom = "F",
                rules = mapOf('F' to "FF+[+F-F-F]-[-F+F+F]"),
                defaultAngle = 22.5f,
                startAngle = -90f
            )
            "plant" -> LSystemDef(
                axiom = "X",
                rules = mapOf('X' to "F+[[X]-X]-F[-FX]+X", 'F' to "FF"),
                defaultAngle = 25f,
                startAngle = -90f
            )
            "dragon" -> LSystemDef(
                axiom = "FX",
                rules = mapOf('X' to "X+YF+", 'Y' to "-FX-Y"),
                defaultAngle = 90f
            )
            "sierpinski" -> LSystemDef(
                axiom = "F-G-G",
                rules = mapOf('F' to "F-G+F+G-F", 'G' to "GG"),
                defaultAngle = 120f
            )
            "hilbert" -> LSystemDef(
                axiom = "A",
                rules = mapOf('A' to "-BF+AFA+FB-", 'B' to "+AF-BFB-FA+"),
                defaultAngle = 90f,
                drawChars = setOf('F')
            )
            "koch" -> LSystemDef(
                axiom = "F--F--F",
                rules = mapOf('F' to "F+F--F+F"),
                defaultAngle = 60f
            )
            "gosper" -> LSystemDef(
                axiom = "A",
                rules = mapOf('A' to "A-B--B+A++AA+B-", 'B' to "+A-BB--B-A++A+B"),
                defaultAngle = 60f,
                drawChars = setOf('A', 'B')
            )
            else -> getPreset("tree")
        }
    }

    private fun rewrite(axiom: String, rules: Map<Char, String>, iterations: Int): String {
        var current = axiom
        for (i in 0 until iterations) {
            val sb = StringBuilder(current.length * 4)
            for (ch in current) {
                sb.append(rules[ch] ?: ch.toString())
            }
            current = sb.toString()
            if (current.length > 600_000) break
        }
        return current
    }

    private data class Segment(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val depth: Int
    )

    private data class TurtleState(var x: Float, var y: Float, var angle: Float, var depth: Int)

    private fun interpret(
        instructions: String,
        stepLength: Float,
        turnAngle: Float,
        startAngle: Float,
        drawChars: Set<Char>,
        stochastic: Float,
        seed: Int
    ): List<Segment> {
        val segments = mutableListOf<Segment>()
        val state = TurtleState(0f, 0f, startAngle, 0)
        val stack = ArrayDeque<TurtleState>()
        val angleRad = turnAngle * PI.toFloat() / 180f
        val jitterRad = stochastic * PI.toFloat() / 180f
        val rng = if (stochastic > 0f) SeededRNG(seed) else null

        for (ch in instructions) {
            when {
                ch in drawChars -> {
                    val nx = state.x + stepLength * cos(state.angle)
                    val ny = state.y + stepLength * sin(state.angle)
                    segments.add(Segment(state.x, state.y, nx, ny, state.depth))
                    state.x = nx
                    state.y = ny
                }
                ch == '+' -> {
                    val jitter = if (rng != null) rng.range(-jitterRad, jitterRad) else 0f
                    state.angle += angleRad + jitter
                }
                ch == '-' -> {
                    val jitter = if (rng != null) rng.range(-jitterRad, jitterRad) else 0f
                    state.angle -= angleRad + jitter
                }
                ch == '[' -> {
                    stack.addLast(TurtleState(state.x, state.y, state.angle, state.depth))
                    state.depth++
                }
                ch == ']' -> {
                    if (stack.isNotEmpty()) {
                        val restored = stack.removeLast()
                        state.x = restored.x
                        state.y = restored.y
                        state.angle = restored.angle
                        state.depth = restored.depth
                    }
                }
            }
        }
        return segments
    }

    /**
     * Scatter-fill the canvas with unique L-system instances at random positions,
     * rotations, and scales. Each instance is re-interpreted with a different seed
     * for organic presets (Tree/Plant) so every element looks unique.
     * Geometric presets keep their exact shape but vary in rotation/scale.
     *
     * Returns canvas-coordinate segments (skip fitSegments after this).
     */
    private fun scatterFill(
        instructions: String,
        refSegments: List<Segment>,
        stepLength: Float,
        angleDeg: Float,
        startAngleRad: Float,
        drawChars: Set<Char>,
        stochastic: Float,
        seed: Int,
        canvasW: Float,
        canvasH: Float,
        fill: String,
        isOrganic: Boolean
    ): List<Segment> {
        if (refSegments.isEmpty()) return emptyList()

        val rng = SeededRNG(seed xor 0x5CA77E4.toInt())

        // Reference bounding box for scaling
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (seg in refSegments) {
            minX = min(minX, min(seg.x1, seg.x2))
            maxX = max(maxX, max(seg.x1, seg.x2))
            minY = min(minY, min(seg.y1, seg.y2))
            maxY = max(maxY, max(seg.y1, seg.y2))
        }
        val rawW = (maxX - minX).coerceAtLeast(1f)
        val rawH = (maxY - minY).coerceAtLeast(1f)
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f

        val margin = canvasW * 0.03f
        val availW = canvasW - 2f * margin
        val availH = canvasH - 2f * margin
        val canvasAR = availW / availH

        // Determine grid layout — roughly square cells matching canvas aspect ratio
        val targetCells = if (fill == "full") rng.integer(14, 22) else rng.integer(5, 9)
        var bestCols = 1; var bestRows = 1; var bestDiff = Int.MAX_VALUE
        for (cols in 1..8) {
            val rows = (cols.toFloat() / canvasAR).roundToInt().coerceIn(1, 12)
            val diff = abs(cols * rows - targetCells)
            if (diff < bestDiff || (diff == bestDiff && cols * rows > bestCols * bestRows)) {
                bestCols = cols; bestRows = rows; bestDiff = diff
            }
        }

        val gridCols = bestCols
        val gridRows = bestRows
        val cellW = availW / gridCols
        val cellH = availH / gridRows

        val result = ArrayList<Segment>()
        val maxTotalSegs = 250_000
        val segsPerInstance = refSegments.size

        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                if (result.size + segsPerInstance > maxTotalSegs) break

                // For half fill, randomly skip ~40% of cells
                if (fill == "half" && rng.random() < 0.4f) continue

                // Cell center with position jitter
                val px = margin + (col + 0.5f) * cellW + rng.range(-cellW * 0.12f, cellW * 0.12f)
                val py = margin + (row + 0.5f) * cellH + rng.range(-cellH * 0.12f, cellH * 0.12f)

                // Scale to fit cell with random size variation
                val maxDim = max(rawW, rawH)
                val fitScale = min(cellW * 0.88f, cellH * 0.88f) / maxDim
                val scale = fitScale * rng.range(0.5f, 1.0f)

                // Random rotation
                val rot = rng.range(0f, 2f * PI.toFloat())
                val cosR = cos(rot)
                val sinR = sin(rot)

                // Generate a unique instance per cell
                val instanceSeed = seed + row * 100 + col + 1
                val segs = if (isOrganic) {
                    // Organic presets: re-interpret with different seed + slight angle jitter
                    val jitter = stochastic.coerceAtLeast(3f)
                    val angleVar = angleDeg + rng.range(-4f, 4f)
                    interpret(instructions, stepLength, angleVar, startAngleRad,
                        drawChars, jitter, instanceSeed)
                } else {
                    // Geometric presets: keep exact shape, variety comes from rotation/scale
                    refSegments
                }

                // Recompute center for organic instances (their bbox shifts with jitter)
                val icx: Float; val icy: Float
                if (isOrganic && segs !== refSegments) {
                    var iMinX = Float.MAX_VALUE; var iMaxX = -Float.MAX_VALUE
                    var iMinY = Float.MAX_VALUE; var iMaxY = -Float.MAX_VALUE
                    for (seg in segs) {
                        iMinX = min(iMinX, min(seg.x1, seg.x2))
                        iMaxX = max(iMaxX, max(seg.x1, seg.x2))
                        iMinY = min(iMinY, min(seg.y1, seg.y2))
                        iMaxY = max(iMaxY, max(seg.y1, seg.y2))
                    }
                    icx = (iMinX + iMaxX) * 0.5f
                    icy = (iMinY + iMaxY) * 0.5f
                } else {
                    icx = cx; icy = cy
                }

                // Transform: center → scale → rotate → translate to cell position
                for (seg in segs) {
                    val dx1 = (seg.x1 - icx) * scale
                    val dy1 = (seg.y1 - icy) * scale
                    val dx2 = (seg.x2 - icx) * scale
                    val dy2 = (seg.y2 - icy) * scale
                    result.add(Segment(
                        px + dx1 * cosR - dy1 * sinR,
                        py + dx1 * sinR + dy1 * cosR,
                        px + dx2 * cosR - dy2 * sinR,
                        py + dx2 * sinR + dy2 * cosR,
                        seg.depth
                    ))
                }
            }
        }

        return result
    }

    private fun fitSegments(
        segments: List<Segment>,
        targetW: Float, targetH: Float, margin: Float
    ): List<Segment> {
        if (segments.isEmpty()) return segments

        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        for (seg in segments) {
            minX = min(minX, min(seg.x1, seg.x2))
            maxX = max(maxX, max(seg.x1, seg.x2))
            minY = min(minY, min(seg.y1, seg.y2))
            maxY = max(maxY, max(seg.y1, seg.y2))
        }

        val rangeX = (maxX - minX).coerceAtLeast(1f)
        val rangeY = (maxY - minY).coerceAtLeast(1f)
        val availW = targetW - 2f * margin
        val availH = targetH - 2f * margin
        val scale = min(availW / rangeX, availH / rangeY)
        val offsetX = margin + (availW - rangeX * scale) / 2f
        val offsetY = margin + (availH - rangeY * scale) / 2f

        return segments.map { seg ->
            Segment(
                (seg.x1 - minX) * scale + offsetX,
                (seg.y1 - minY) * scale + offsetY,
                (seg.x2 - minX) * scale + offsetX,
                (seg.y2 - minY) * scale + offsetY,
                seg.depth
            )
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
        val preset = (params["preset"] as? String) ?: "Tree"
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 5
        val angleDeg = (params["angle"] as? Number)?.toFloat() ?: 25f
        val stepLength = (params["stepLength"] as? Number)?.toFloat() ?: 8f
        val stochastic = (params["stochastic"] as? Number)?.toFloat() ?: 0f
        val taper = (params["taper"] as? Boolean) ?: false
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1f
        val fill = (params["fill"] as? String) ?: "auto"
        val colorMode = (params["colorMode"] as? String) ?: "depth"
        val revealSpeed = (params["revealSpeed"] as? Number)?.toFloat() ?: 1.0f

        val def = getPreset(preset)
        val instructions = rewrite(def.axiom, def.rules, iterations)
        val startAngleRad = def.startAngle * PI.toFloat() / 180f

        val rawSegments = interpret(
            instructions, stepLength, angleDeg, startAngleRad,
            def.drawChars, stochastic, seed
        )
        val isOrganic = preset.lowercase().let { it == "tree" || it == "plant" }
        val segments = if (fill != "auto") {
            scatterFill(instructions, rawSegments, stepLength, angleDeg, startAngleRad,
                def.drawChars, stochastic, seed, w, h, fill, isOrganic)
        } else {
            fitSegments(rawSegments, w, h, w * 0.05f)
        }

        canvas.drawColor(Color.BLACK)

        if (segments.isEmpty()) return

        // Find max depth for normalization
        var maxDepth = 1
        for (seg in segments) {
            if (seg.depth > maxDepth) maxDepth = seg.depth
        }

        // Progressive growth animation: reveal segments over time
        val visibleCount = if (time > 0.01f) {
            val frac = (time * revealSpeed * 0.3f).coerceIn(0f, 1f)
            (segments.size * frac).toInt().coerceAtLeast(1)
        } else {
            segments.size
        }

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
        }

        val paletteColors = palette.colorInts()
        // Quantized palette LUT for gradient mode — limits batch count to ~64
        val paletteLut = palette.buildLut(64)

        // --- Pre-compute color and width per segment ---
        val segColors = IntArray(visibleCount)
        val segWidths = FloatArray(visibleCount)
        for (i in 0 until visibleCount) {
            val seg = segments[i]
            segColors[i] = when (colorMode) {
                "depth" -> {
                    val t = (seg.depth.toFloat() / maxDepth).coerceIn(0f, 1f)
                    paletteLut[(t * 63f).toInt().coerceIn(0, 63)]
                }
                "single" -> paletteColors[0]
                else -> {
                    val orderFrac = i.toFloat() / segments.size
                    paletteLut[(orderFrac * 63f).toInt().coerceIn(0, 63)]
                }
            }
            segWidths[i] = if (taper) {
                val depthFrac = seg.depth.toFloat() / maxDepth
                // Quantize to 0.5 increments to improve batching
                ((lineWidth * (1f - depthFrac * 0.8f)).coerceAtLeast(0.3f) * 2f).toInt() / 2f
            } else lineWidth
        }

        // --- Batched path drawing: group consecutive same-styled segments ---
        var curColor = segColors[0]
        var curWidth = segWidths[0]
        var path = Path()
        val seg0 = segments[0]
        path.moveTo(seg0.x1, seg0.y1)
        path.lineTo(seg0.x2, seg0.y2)

        for (i in 1 until visibleCount) {
            if (segColors[i] != curColor || segWidths[i] != curWidth) {
                // Flush current batch
                paint.color = curColor
                paint.strokeWidth = curWidth
                canvas.drawPath(path, paint)
                path = Path()
                curColor = segColors[i]
                curWidth = segWidths[i]
            }
            val seg = segments[i]
            path.moveTo(seg.x1, seg.y1)
            path.lineTo(seg.x2, seg.y2)
        }
        // Flush final batch
        paint.color = curColor
        paint.strokeWidth = curWidth
        canvas.drawPath(path, paint)
    }

    override fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath> {
        val w = 1080f
        val h = 1080f
        val preset = (params["preset"] as? String) ?: "Tree"
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 5
        val angleDeg = (params["angle"] as? Number)?.toFloat() ?: 25f
        val stepLength = (params["stepLength"] as? Number)?.toFloat() ?: 8f
        val stochastic = (params["stochastic"] as? Number)?.toFloat() ?: 0f
        val taper = (params["taper"] as? Boolean) ?: false
        val lineWidth = (params["lineWidth"] as? Number)?.toFloat() ?: 1f
        val fill = (params["fill"] as? String) ?: "auto"
        val colorMode = (params["colorMode"] as? String) ?: "depth"

        val def = getPreset(preset)
        val instructions = rewrite(def.axiom, def.rules, iterations)
        val startAngleRad = def.startAngle * PI.toFloat() / 180f

        val rawSegments = interpret(
            instructions, stepLength, angleDeg, startAngleRad,
            def.drawChars, stochastic, seed
        )
        val isOrganic = preset.lowercase().let { it == "tree" || it == "plant" }
        val segments = if (fill != "auto") {
            scatterFill(instructions, rawSegments, stepLength, angleDeg, startAngleRad,
                def.drawChars, stochastic, seed, w, h, fill, isOrganic)
        } else {
            fitSegments(rawSegments, w, h, w * 0.05f)
        }

        if (segments.isEmpty()) return emptyList()

        var maxDepth = 1
        for (seg in segments) {
            if (seg.depth > maxDepth) maxDepth = seg.depth
        }

        val paths = mutableListOf<SvgPath>()
        val chunkSize = (segments.size / 20).coerceAtLeast(1)

        for (i in segments.indices step chunkSize) {
            val end = (i + chunkSize).coerceAtMost(segments.size)
            val orderFrac = i.toFloat() / segments.size
            val firstSeg = segments[i]

            val color = when (colorMode) {
                "depth" -> {
                    val t = (firstSeg.depth.toFloat() / maxDepth).coerceIn(0f, 1f)
                    palette.lerpColor(t)
                }
                "single" -> palette.colorInts()[0]
                else -> palette.lerpColor(orderFrac)
            }
            val hexColor = String.format("#%06X", 0xFFFFFF and color)

            val sw = if (taper) {
                val depthFrac = firstSeg.depth.toFloat() / maxDepth
                (lineWidth * (1f - depthFrac * 0.8f)).coerceAtLeast(0.3f)
            } else {
                lineWidth
            }

            val sb = StringBuilder()
            for (j in i until end) {
                val seg = segments[j]
                sb.append(SvgBuilder.moveTo(seg.x1, seg.y1))
                sb.append(" ")
                sb.append(SvgBuilder.lineTo(seg.x2, seg.y2))
                sb.append(" ")
            }

            paths.add(SvgPath(d = sb.toString().trim(), stroke = hexColor, strokeWidth = sw))
        }

        return paths
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val iterations = (params["iterations"] as? Number)?.toInt() ?: 4
        return (iterations / 8f).coerceIn(0.2f, 1f)
    }
}
