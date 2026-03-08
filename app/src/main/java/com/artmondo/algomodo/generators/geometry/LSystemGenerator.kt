package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.SvgBuilder
import com.artmondo.algomodo.rendering.SvgPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
            val sb = StringBuilder()
            for (ch in current) {
                sb.append(rules[ch] ?: ch.toString())
            }
            current = sb.toString()
            if (current.length > 2_000_000) break
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
        val colorMode = (params["colorMode"] as? String) ?: "depth"
        val revealSpeed = (params["revealSpeed"] as? Number)?.toFloat() ?: 1.0f

        val def = getPreset(preset)
        val instructions = rewrite(def.axiom, def.rules, iterations)
        val startAngleRad = def.startAngle * PI.toFloat() / 180f

        val rawSegments = interpret(
            instructions, stepLength, angleDeg, startAngleRad,
            def.drawChars, stochastic, seed
        )
        val segments = fitSegments(rawSegments, w, h, w * 0.05f)

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

        for (i in 0 until visibleCount) {
            val seg = segments[i]
            val orderFrac = i.toFloat() / segments.size

            // Color by mode
            paint.color = when (colorMode) {
                "depth" -> {
                    val t = (seg.depth.toFloat() / maxDepth).coerceIn(0f, 1f)
                    palette.lerpColor(t)
                }
                "single" -> paletteColors[0]
                else -> palette.lerpColor(orderFrac) // "gradient"
            }

            // Stroke width: taper by depth or use fixed lineWidth
            paint.strokeWidth = if (taper) {
                val depthFrac = seg.depth.toFloat() / maxDepth
                (lineWidth * (1f - depthFrac * 0.8f)).coerceAtLeast(0.3f)
            } else {
                lineWidth
            }

            canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, paint)
        }
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
        val colorMode = (params["colorMode"] as? String) ?: "depth"

        val def = getPreset(preset)
        val instructions = rewrite(def.axiom, def.rules, iterations)
        val startAngleRad = def.startAngle * PI.toFloat() / 180f

        val rawSegments = interpret(
            instructions, stepLength, angleDeg, startAngleRad,
            def.drawChars, stochastic, seed
        )
        val segments = fitSegments(rawSegments, w, h, w * 0.05f)

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
