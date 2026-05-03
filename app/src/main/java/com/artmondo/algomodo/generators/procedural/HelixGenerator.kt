package com.artmondo.algomodo.generators.procedural

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
 * DNA-inspired double/multi-strand helix with depth-sorted occlusion,
 * base-pair rungs, and multiple rendering variants.
 *
 * Parametric helical curves with sin-based depth modulation for pseudo-3D.
 * Strands offset by 2π/N, base-pairs connect adjacent strands.
 * Back-to-front painter's sort by sin(angle) depth key.
 */
class HelixGenerator : Generator {

    override val id = "procedural-helix"
    override val family = "procedural"
    override val styleName = "Helix"
    override val definition =
        "DNA-inspired double/multi-strand helix with depth-sorted occlusion, base-pair rungs, and multiple rendering variants."
    override val algorithmNotes =
        "Parametric helical curves with sin-based depth modulation for pseudo-3D. " +
        "Strands offset by 2π/N, base-pairs connect adjacent strands. " +
        "Back-to-front painter's sort by sin(angle) depth key."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        // Composition
        Parameter.NumberParam(
            name = "Strands", key = "strands", group = ParamGroup.COMPOSITION,
            help = "2 = DNA double helix, 3+ = fictional polymers",
            min = 2f, max = 4f, step = 1f, default = 2f
        ),
        Parameter.NumberParam(
            name = "Turns", key = "turns", group = ParamGroup.COMPOSITION,
            help = "Number of full rotations visible",
            min = 1f, max = 3f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Base-pair density", key = "bpDensity", group = ParamGroup.COMPOSITION,
            help = "Rungs per turn (canonical DNA ≈ 10.5)",
            min = 2f, max = 20f, step = 1f, default = 10f
        ),
        Parameter.SelectParam(
            name = "Variant", key = "variant", group = ParamGroup.COMPOSITION,
            help = "classic = strands + base-pairs, particle = dots, ribbon = filled bands, zdna = left-handed + jitter, supercoil = helical wrapping",
            options = listOf("classic", "particle", "ribbon", "zdna", "supercoil"),
            default = "classic"
        ),
        // Geometry
        Parameter.NumberParam(
            name = "Radius", key = "radius", group = ParamGroup.GEOMETRY,
            help = "Helix radius relative to canvas width",
            min = 0.05f, max = 0.4f, step = 0.01f, default = 0.18f
        ),
        Parameter.NumberParam(
            name = "Strand width", key = "strandWidth", group = ParamGroup.GEOMETRY,
            help = "Line thickness for strands",
            min = 1f, max = 8f, step = 0.5f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Base-pair width", key = "bpWidth", group = ParamGroup.GEOMETRY,
            help = "Line thickness for base-pair rungs",
            min = 0.5f, max = 4f, step = 0.5f, default = 1.5f
        ),
        // Flow/Motion
        Parameter.NumberParam(
            name = "Breathing", key = "breathing", group = ParamGroup.FLOW_MOTION,
            help = "Base-pair length oscillation (denaturation)",
            min = 0f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Mutation noise", key = "mutationNoise", group = ParamGroup.FLOW_MOTION,
            help = "Perlin noise on radius — wobble and bulge",
            min = 0f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.NumberParam(
            name = "Supercoil amplitude", key = "supercoilAmp", group = ParamGroup.FLOW_MOTION,
            help = "Amplitude of outer helical path (supercoil variant)",
            min = 0f, max = 0.3f, step = 0.01f, default = 0.08f
        ),
        Parameter.NumberParam(
            name = "Anim speed", key = "animSpeed", group = ParamGroup.FLOW_MOTION,
            help = "Animation rotation speed",
            min = 0.1f, max = 1f, step = 0.1f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Anim amplitude", key = "animAmp", group = ParamGroup.FLOW_MOTION,
            help = "Animation amplitude",
            min = 0.1f, max = 2f, step = 0.1f, default = 1f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity", key = "reactivity", group = ParamGroup.FLOW_MOTION,
            help = "Audio reactivity multiplier",
            min = 0f, max = 2f, step = 0.1f, default = 1f
        ),
        // Color
        Parameter.SelectParam(
            name = "Color mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette = lerp by depth, codon = groups of 3 by amino acid hue, depth = front/back shade, strand = distinct color per strand",
            options = listOf("palette", "codon", "depth", "strand"),
            default = "palette"
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "strands" to 2f, "turns" to 3f, "bpDensity" to 10f, "variant" to "classic",
        "radius" to 0.18f, "strandWidth" to 3f, "bpWidth" to 1.5f,
        "breathing" to 0f, "mutationNoise" to 0f, "supercoilAmp" to 0.08f,
        "animSpeed" to 0.5f, "animAmp" to 1f,
        "reactivity" to 1f, "colorMode" to "palette"
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val minDim = min(w, h)

        // ── Extract params ──────────────────────────────────────────
        val nStrands = ((params["strands"] as? Number)?.toInt() ?: 2).coerceIn(2, 4)
        val turns = ((params["turns"] as? Number)?.toFloat() ?: 3f).coerceIn(1f, 3f)
        val bpDensity = ((params["bpDensity"] as? Number)?.toInt() ?: 10).coerceIn(2, 20)
        val variant = (params["variant"] as? String) ?: "classic"
        val baseR = ((params["radius"] as? Number)?.toFloat() ?: 0.18f) * minDim
        val strandWidth = ((params["strandWidth"] as? Number)?.toFloat() ?: 3f) * (minDim / 400f)
        val bpWidth = ((params["bpWidth"] as? Number)?.toFloat() ?: 1.5f) * (minDim / 400f)
        val breathing = (params["breathing"] as? Number)?.toFloat() ?: 0f
        val mutNoise = (params["mutationNoise"] as? Number)?.toFloat() ?: 0f
        val scAmpParam = if (variant == "supercoil")
            ((params["supercoilAmp"] as? Number)?.toFloat() ?: 0.08f) * minDim else 0f
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.5f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "palette"

        // Audio
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioEnergy = (audioAnalysis?.getHigh(time) ?: 0f) * rx

        val audioRadius = 1f + audioBass * 0.3f
        val audioBreath = breathing + audioMid * 0.3f
        val audioSpeedMul = 1f + audioEnergy * 0.5f

        val noise = SimplexNoise(seed + 7)

        // ── Helix geometry ──────────────────────────────────────────
        val cx = w * 0.5f
        val totalHeight = h * 0.85f
        val yStart = (h - totalHeight) * 0.5f
        val omega = (turns * PI.toFloat() * 2f) / totalHeight
        val isZdna = variant == "zdna"
        val omegaDir = if (isZdna) -omega else omega

        // Phase: animate rotation (static render uses seed-based offset for variety)
        val phase = if (time > 0f)
            time * animSpeed * animAmp * audioSpeedMul * PI.toFloat() * 2f
        else
            SeededRNG(seed).random() * PI.toFloat() * 2f

        // Sampling resolution
        val totalBp = (turns * bpDensity).toInt().coerceAtLeast(2)
        val strandSegments = totalBp * 6

        // Palette colors
        val colors = palette.colorInts()
        val nc = colors.size

        // Codon hues for codon color mode
        val codonRng = SeededRNG(seed + 42)
        val codonHues = FloatArray(totalBp) { codonRng.random() * 360f }

        // ── Helper: strand position + depth ─────────────────────────
        // Normalized y from 0..totalHeight for noise sampling
        fun strandPos(y: Float, strandIdx: Int): Pair<Float, Float> {
            val strandPhase = (strandIdx.toFloat() / nStrands) * PI.toFloat() * 2f
            val angle = omegaDir * (y - yStart) + strandPhase + phase

            var r = baseR * audioRadius
            if (mutNoise > 0f) {
                // Use normalized y for noise so frequency is canvas-independent
                val ny = (y - yStart) / totalHeight
                val nv = noise.noise2D(ny * 8f + seed * 0.1f, strandIdx * 17f)
                r *= 1f + nv * mutNoise * 0.6f
            }

            var jitter = 0f
            if (isZdna) {
                val ny = (y - yStart) / totalHeight
                jitter = noise.noise2D(ny * 20f, strandIdx * 50f + seed * 0.3f) * baseR * 0.25f
            }

            var scX = 0f
            if (scAmpParam > 0f) {
                val scOmega = omega * 0.15f
                scX = sin(scOmega * (y - yStart) + phase * 0.3f) * scAmpParam
            }

            val x = cx + scX + cos(angle) * r + jitter
            val depth = sin(angle) // -1 = back, +1 = front
            return x to depth
        }

        // ── Helper: get color ───────────────────────────────────────
        fun getColor(strandIdx: Int, depth: Float, bpIdx: Int): Int {
            return when (colorMode) {
                "strand" -> colors[strandIdx % nc]
                "depth" -> {
                    // Brightness based on depth: back = dark, front = bright
                    val t = (depth + 1f) * 0.5f // 0..1
                    val baseColor = colors[strandIdx % nc]
                    val brightness = 0.3f + 0.7f * t
                    Color.rgb(
                        (Color.red(baseColor) * brightness).toInt().coerceIn(0, 255),
                        (Color.green(baseColor) * brightness).toInt().coerceIn(0, 255),
                        (Color.blue(baseColor) * brightness).toInt().coerceIn(0, 255)
                    )
                }
                "codon" -> {
                    val codonGroup = bpIdx / 3
                    val hue = codonHues[(codonGroup % codonHues.size).coerceAtLeast(0)]
                    hslToColor(hue, 0.7f, 0.55f)
                }
                else -> { // "palette" - lerp through palette by depth
                    val t = (depth + 1f) * 0.5f
                    val ci = ((t * (nc - 1)).toInt()).coerceIn(0, nc - 2)
                    val ci2 = ci + 1
                    val f = t * (nc - 1) - ci
                    val c1 = colors[ci]; val c2 = colors[ci2]
                    val rr = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt()
                    val gg = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt()
                    val bb = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt()
                    Color.rgb(rr.coerceIn(0, 255), gg.coerceIn(0, 255), bb.coerceIn(0, 255))
                }
            }
        }

        // ── Collect segments for depth sort ──────────────────────────
        val segs = ArrayList<Segment>(strandSegments * nStrands + totalBp * nStrands)

        // Ribbon half-width: scale by strandWidth param for visible difference
        val ribbonHalfWidth = strandWidth * 2.5f

        // Build strand segments
        val yStep = totalHeight / strandSegments
        for (si in 0 until nStrands) {
            var prevX = 0f; var prevY = 0f; var prevD = 0f
            for (j in 0..strandSegments) {
                val yPos = yStart + j * yStep
                val (x, depth) = strandPos(yPos, si)

                if (j > 0) {
                    val avgDepth = (prevD + depth) * 0.5f
                    val alphaDepth = 0.3f + 0.7f * ((avgDepth + 1f) * 0.5f)
                    val widthMod = 0.4f + 0.6f * ((avgDepth + 1f) * 0.5f)
                    val bpIdx = j / 6
                    val color = getColor(si, avgDepth, bpIdx)

                    when (variant) {
                        "particle" -> {
                            // Only emit every 3rd segment to avoid over-density
                            if (j % 3 == 0) {
                                segs.add(Segment(
                                    kind = KIND_PARTICLE, x1 = x, y1 = yPos, x2 = x, y2 = yPos,
                                    depth = depth, alpha = alphaDepth,
                                    width = 0f, radius = strandWidth * widthMod * 1.2f, color = color
                                ))
                            }
                        }
                        "ribbon" -> {
                            // Ribbon: quad with perpendicular offset based on depth
                            val angle = omegaDir * (yPos - yStart) + (si.toFloat() / nStrands) * PI.toFloat() * 2f + phase
                            val perpX = -sin(angle) * ribbonHalfWidth * widthMod
                            val prevAngle = omegaDir * (prevY - yStart) + (si.toFloat() / nStrands) * PI.toFloat() * 2f + phase
                            val prevWidthMod = 0.4f + 0.6f * ((prevD + 1f) * 0.5f)
                            val prevPerpX = -sin(prevAngle) * ribbonHalfWidth * prevWidthMod

                            segs.add(Segment(
                                kind = KIND_RIBBON,
                                x1 = prevX - prevPerpX, y1 = prevY,
                                x2 = x - perpX, y2 = yPos,
                                x3 = x + perpX, y3 = yPos,
                                x4 = prevX + prevPerpX, y4 = prevY,
                                depth = avgDepth, alpha = alphaDepth * 0.85f,
                                width = 0f, color = color
                            ))
                        }
                        else -> {
                            // Classic / zdna / supercoil: line segments
                            segs.add(Segment(
                                kind = KIND_STRAND,
                                x1 = prevX, y1 = prevY, x2 = x, y2 = yPos,
                                depth = avgDepth, alpha = alphaDepth,
                                width = strandWidth * widthMod, color = color
                            ))
                        }
                    }
                }
                prevX = x; prevY = yPos; prevD = depth
            }
        }

        // ── Build base-pair rungs (between adjacent strands) ────────
        if (variant != "particle") {
            for (bp in 0 until totalBp) {
                val yBp = yStart + (bp.toFloat() / totalBp) * totalHeight

                // Breathing: oscillate base-pair length even in static mode
                var breathScale = 1f
                if (audioBreath > 0f) {
                    val breathT = if (time > 0f)
                        sin(bp * 0.7f + time * 3f * animSpeed) * audioBreath
                    else
                        sin(bp * 1.2f + seed * 0.1f) * audioBreath
                    breathScale = 1f + breathT * 0.6f
                }

                for (si in 0 until nStrands) {
                    val nextSi = (si + 1) % nStrands
                    // For 2 strands, only connect strand 0 → strand 1
                    if (nStrands == 2 && si > 0) continue

                    val (axRaw, depthA) = strandPos(yBp, si)
                    val (bxRaw, depthB) = strandPos(yBp, nextSi)

                    val midX = (axRaw + bxRaw) * 0.5f
                    val ax = midX + (axRaw - midX) * breathScale
                    val bx = midX + (bxRaw - midX) * breathScale

                    val sortKey = min(depthA, depthB)
                    val edgeFade = 1f - abs(depthA)
                    val alpha = (0.15f + 0.85f * edgeFade.coerceAtLeast(0f))

                    val color = getColor(0, sortKey, bp)

                    segs.add(Segment(
                        kind = KIND_BP, x1 = ax, y1 = yBp, x2 = bx, y2 = yBp,
                        depth = sortKey, alpha = alpha,
                        width = bpWidth * (0.4f + 0.6f * edgeFade.coerceAtLeast(0f)),
                        color = color
                    ))
                }
            }
        }

        // ── Depth sort: back to front ───────────────────────────────
        segs.sortBy { it.depth }

        // ── Render ──────────────────────────────────────────────────
        canvas.drawColor(Color.rgb(10, 10, 20))

        val paint = Paint().apply {
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()

        for (seg in segs) {
            val a = (seg.alpha * 255f).toInt().coerceIn(0, 255)
            paint.color = seg.color
            paint.alpha = a

            when (seg.kind) {
                KIND_PARTICLE -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(seg.x1, seg.y1, seg.radius, paint)
                }
                KIND_RIBBON -> {
                    paint.style = Paint.Style.FILL
                    path.reset()
                    path.moveTo(seg.x1, seg.y1)
                    path.lineTo(seg.x2, seg.y2)
                    path.lineTo(seg.x3, seg.y3)
                    path.lineTo(seg.x4, seg.y4)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                else -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = seg.width
                    canvas.drawLine(seg.x1, seg.y1, seg.x2, seg.y2, paint)
                }
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val strands = ((params["strands"] as? Number)?.toInt() ?: 2)
        val turns = ((params["turns"] as? Number)?.toFloat() ?: 3f)
        val density = ((params["bpDensity"] as? Number)?.toInt() ?: 10)
        return (strands * turns * density * 0.005f).coerceIn(0.2f, 0.8f)
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun hslToColor(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color.rgb(
            ((r + m) * 255f).toInt().coerceIn(0, 255),
            ((g + m) * 255f).toInt().coerceIn(0, 255),
            ((b + m) * 255f).toInt().coerceIn(0, 255)
        )
    }

    companion object {
        private const val KIND_STRAND = 0
        private const val KIND_BP = 1
        private const val KIND_RIBBON = 2
        private const val KIND_PARTICLE = 3
    }

    private class Segment(
        val kind: Int,
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float = 0f, val y3: Float = 0f,
        val x4: Float = 0f, val y4: Float = 0f,
        val depth: Float,
        val alpha: Float,
        val width: Float,
        val radius: Float = 0f,
        val color: Int
    )
}
