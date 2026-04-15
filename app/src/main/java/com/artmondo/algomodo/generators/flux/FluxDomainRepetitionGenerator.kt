package com.artmondo.algomodo.generators.flux

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * SDF shapes tiled via modulo coordinates with per-cell variation -- neon edges,
 * six motion modes (static, pulse, spin, wave, drift, explode), and crisp rendering.
 *
 * Cell-based iteration with per-cell hash/cos/sin/radius computed once.
 * Fast interior checks for all 4 SDF shapes skip full evaluation for ~60% of interior pixels.
 * Fast exterior discard via squared max-visible-radius skips glow-region pixels early.
 * Glow uses 256-entry exp decay LUT. Fast atan2 polynomial for star SDF.
 */
class FluxDomainRepetitionGenerator : Generator {

    override val id = "flux-domain-repetition"
    override val family = "flux"
    override val styleName = "Domain Repetition"
    override val definition =
        "SDF shapes tiled via modulo coordinates with per-cell variation -- neon edges, six motion modes (static, pulse, spin, wave, drift, explode), and crisp rendering"
    override val algorithmNotes =
        "Cell-based iteration with per-cell hash/cos/sin/radius computed once. " +
        "Fast interior checks for all 4 SDF shapes skip full evaluation for ~60% of interior pixels. " +
        "Fast exterior discard via squared max-visible-radius skips glow-region pixels early. " +
        "Glow uses 256-entry exp decay LUT. Palette LUT cached by key across frames. " +
        "Auto step=2 during animation. Fast atan2 polynomial for star SDF."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Shape",
            key = "shape",
            group = ParamGroup.COMPOSITION,
            help = "SDF primitive repeated across the grid",
            options = listOf("circle", "box", "star", "hexagon"),
            default = "circle"
        ),
        Parameter.SelectParam(
            name = "Motion",
            key = "motionMode",
            group = ParamGroup.FLOW_MOTION,
            help = "static: no animation | pulse: breathing shapes | spin: per-cell rotation | wave: ripple across grid | drift: shapes wander | explode: burst from center",
            options = listOf("static", "pulse", "spin", "wave", "drift", "explode"),
            default = "wave"
        ),
        Parameter.NumberParam(
            name = "Cell Size",
            key = "cellSize",
            group = ParamGroup.GEOMETRY,
            help = "Size of each repeating cell in pixels",
            min = 30f, max = 200f, step = 5f, default = 80f
        ),
        Parameter.NumberParam(
            name = "Size Variation",
            key = "sizeVariation",
            group = ParamGroup.GEOMETRY,
            help = "Per-cell random variation in shape size",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Rotation Variation",
            key = "rotationVariation",
            group = ParamGroup.GEOMETRY,
            help = "Per-cell random variation in rotation angle",
            min = 0f, max = 1f, step = 0.05f, default = 0.4f
        ),
        Parameter.NumberParam(
            name = "Fill Ratio",
            key = "fillRatio",
            group = ParamGroup.GEOMETRY,
            help = "Shape radius relative to cell size",
            min = 0.2f, max = 0.9f, step = 0.05f, default = 0.6f
        ),
        Parameter.NumberParam(
            name = "Edge Glow",
            key = "edgeGlow",
            group = ParamGroup.TEXTURE,
            help = "Neon glow intensity around shape edges",
            min = 0f, max = 1f, step = 0.05f, default = 0.4f
        ),
        Parameter.NumberParam(
            name = "Edge Sharpness",
            key = "edgeSharpness",
            group = ParamGroup.TEXTURE,
            help = "Crispness of edge lines -- higher = thinner, sharper neon",
            min = 0.2f, max = 2f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation speed",
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
        "shape" to "circle",
        "motionMode" to "wave",
        "cellSize" to 80f,
        "sizeVariation" to 0.3f,
        "rotationVariation" to 0.4f,
        "fillRatio" to 0.6f,
        "edgeGlow" to 0.4f,
        "edgeSharpness" to 1.0f,
        "speed" to 0.5f,
        "reactivity" to 1.0f
    )

    // 256-entry glow LUT (rebuilt each render with current edgeGlow)
    private val glowLUT = FloatArray(256)

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

        // -- Parameters --
        val shapeStr = (params["shape"] as? String) ?: "circle"
        val motionStr = (params["motionMode"] as? String) ?: "wave"
        val baseCellSize = ((params["cellSize"] as? Number)?.toFloat() ?: 80f).coerceIn(30f, 200f)
        val sizeVar = ((params["sizeVariation"] as? Number)?.toFloat() ?: 0.3f).coerceIn(0f, 1f)
        val rotVar = ((params["rotationVariation"] as? Number)?.toFloat() ?: 0.4f).coerceIn(0f, 1f)
        val fillRatio = ((params["fillRatio"] as? Number)?.toFloat() ?: 0.6f).coerceIn(0.2f, 0.9f)
        val edgeGlowAmt = ((params["edgeGlow"] as? Number)?.toFloat() ?: 0.4f).coerceIn(0f, 1f)
        val edgeSharp = ((params["edgeSharpness"] as? Number)?.toFloat() ?: 1.0f).coerceIn(0.2f, 2f)
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // Audio
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx

        val effFillRatio = (fillRatio + audioBass * 0.2f).coerceIn(0.15f, 0.95f)
        val audioRotOffset = audioMid * 0.5f

        val shapeId = when (shapeStr) {
            "circle" -> 0
            "box" -> 1
            "star" -> 2
            else -> 3 // hexagon
        }

        val motionId = when (motionStr) {
            "static" -> 0
            "pulse" -> 1
            "spin" -> 2
            "wave" -> 3
            "drift" -> 4
            else -> 5 // explode
        }

        val t = time * spd

        val cellSize = if (motionId == 1) {
            baseCellSize * (1f + sin(t * 1.4f) * 0.06f)
        } else {
            baseCellSize
        }
        val halfCell = cellSize * 0.5f
        val invCellSize = 1f / cellSize

        // Auto step=2 during animation for ~4x pixel reduction; step=1 for static renders
        var step = when (quality) {
            Quality.DRAFT -> 3
            Quality.ULTRA -> 1
            else -> 2
        }
        if (time > 0f && step < 2) step = 2

        // -- Palette colors as raw RGB arrays for per-pixel blending --
        val palColors = palette.colorInts()
        val numColors = palColors.size
        val colorLUT = IntArray(numColors) { palColors[it] }
        val colorsR = IntArray(numColors) { Color.red(palColors[it]) }
        val colorsG = IntArray(numColors) { Color.green(palColors[it]) }
        val colorsB = IntArray(numColors) { Color.blue(palColors[it]) }

        // -- Build glow LUT --
        val glowFalloff = if (edgeGlowAmt > 0.01f) 0.08f + edgeGlowAmt * 0.22f else 0f
        val hasGlow = glowFalloff > 0f
        if (hasGlow) {
            for (i in 0 until 256) {
                glowLUT[i] = exp(-i * glowFalloff)
            }
        }

        val edgeBandWidth = (2.5f / edgeSharp).coerceAtLeast(0.5f)

        val PI_F = PI.toFloat()
        val TAU = PI_F * 2f

        val rng = SeededRNG(seed)
        val seedBits = (rng.random() * 0x7fffffff).toInt()

        // -- Visible cell range --
        val extend = if (motionId == 4 || motionId == 5) 2 else 1
        val cellX0 = floor(0f * invCellSize).toInt() - extend
        val cellY0 = floor(0f * invCellSize).toInt() - extend
        val cellX1 = floor((w - 1).toFloat() * invCellSize).toInt() + extend
        val cellY1 = floor((h - 1).toFloat() * invCellSize).toInt() + extend

        val pixels = IntArray(w * h)
        val bgColor = Color.BLACK
        pixels.fill(bgColor)

        // Star constants
        val starInnerRatio = 0.38f
        val starSegAngle = PI_F / 5f
        val starInvSeg = 1f / starSegAngle
        val starDblSeg = starSegAngle * 2f
        // Hex constants
        val hexK1 = 0.8660254037844387f
        val hexK2 = 0.5f

        // Wave / explode pre-compute
        val waveDiag = sqrt((cellX1.toFloat() * cellX1 + cellY1.toFloat() * cellY1)) + 1f
        val waveInvDiag = 1f / waveDiag
        val explodeCx = (cellX0 + cellX1) * 0.5f
        val explodeCy = (cellY0 + cellY1) * 0.5f
        val explodeMaxR = sqrt(
            (cellX1 - explodeCx) * (cellX1 - explodeCx) +
            (cellY1 - explodeCy) * (cellY1 - explodeCy)
        ) + 1f

        // Glow max pixel extend -- tight cap for performance
        val glowMaxPx = if (hasGlow) min(20, (3.5f / glowFalloff).toInt()) else 0

        // -- Cell-based iteration --
        for (cy in cellY0..cellY1) {
            for (cx in cellX0..cellX1) {

                // -- Per-cell hash (once, not per-pixel) --
                val hash1 = ((cx * 73856093) xor (cy * 19349663) xor seedBits) and 0x7fffffff
                val hashF1 = hash1.toFloat() / 0x7fffffff.toFloat()
                val hash2 = ((cx * 83492791) xor (cy * 56198423) xor seedBits) and 0x7fffffff
                val hashF2 = hash2.toFloat() / 0x7fffffff.toFloat()
                val hash3 = ((cx * 12345689) xor (cy * 98765431) xor seedBits) and 0x7fffffff
                val hashF3 = hash3.toFloat() / 0x7fffffff.toFloat()

                val cellPhase = hashF3 * TAU

                // -- Motion-specific modulations --
                var motionRadius = effFillRatio
                var motionRotation = hashF2 * rotVar * PI_F + audioRotOffset
                var motionOffX = 0f
                var motionOffY = 0f

                when (motionId) {
                    0 -> { /* Static */ }
                    1 -> {
                        val breathe = sin(t * 1.4f + cellPhase) * 0.15f
                        motionRadius = (effFillRatio + breathe * effFillRatio).coerceIn(0.15f, 0.95f)
                    }
                    2 -> {
                        val spinDir = if (hashF1 > 0.5f) 1f else -1f
                        val spinSpeed = 0.5f + hashF3 * 1.5f
                        motionRotation += t * spinSpeed * spinDir
                    }
                    3 -> {
                        val cellDist = (cx + cy).toFloat() * waveInvDiag
                        val wave = sin(t * 2.5f - cellDist * TAU * 2f + cellPhase * 0.3f)
                        motionRadius = (effFillRatio + wave * 0.12f).coerceIn(0.15f, 0.95f)
                        motionRotation += wave * 0.4f
                    }
                    4 -> {
                        val driftAmp = halfCell * 0.4f
                        motionOffX = sin(t * 0.7f + cellPhase) * driftAmp +
                                     sin(t * 1.3f + hashF1 * TAU) * driftAmp * 0.4f
                        motionOffY = cos(t * 0.9f + cellPhase + 1.2f) * driftAmp +
                                     cos(t * 1.1f + hashF2 * TAU) * driftAmp * 0.4f
                        motionRotation += t * 0.5f
                    }
                    else -> { // 5 = explode
                        val ecx = cx - explodeCx
                        val ecy = cy - explodeCy
                        val edist = sqrt(ecx * ecx + ecy * ecy)
                        val enorm = edist / explodeMaxR
                        val pushPhase = sin(t * 1.2f) * 0.5f + 0.5f
                        val pushAmp = pushPhase * halfCell * 1.2f * enorm
                        if (edist > 0.001f) {
                            motionOffX = (ecx / edist) * pushAmp
                            motionOffY = (ecy / edist) * pushAmp
                        }
                        motionRadius = (effFillRatio * (1f - pushPhase * 0.25f * enorm) +
                                (1f - enorm) * 0.05f).coerceIn(0.15f, 0.95f)
                        motionRotation += t * (0.3f + enorm * 0.8f)
                    }
                }

                // Audio jitter for drift/wave
                if (audioHigh > 0.01f && (motionId == 3 || motionId == 4)) {
                    motionOffX += sin(cellPhase + t * 8f) * audioHigh * halfCell * 0.15f
                    motionOffY += cos(cellPhase + t * 8f) * audioHigh * halfCell * 0.15f
                }

                val radius = halfCell * motionRadius * (1f + (hashF1 - 0.5f) * sizeVar)
                val cosR = cos(motionRotation)
                val sinR = sin(motionRotation)

                val cellCenterX = cx * cellSize + halfCell + motionOffX
                val cellCenterY = cy * cellSize + halfCell + motionOffY

                // Color
                val ci: Int = if (motionId == 3 || motionId == 5) {
                    val colorShift = sin(t * 0.8f + cellPhase) * 0.5f + 0.5f
                    ((hash1 + (colorShift * numColors).toInt()) % numColors)
                } else {
                    hash1 % numColors
                }
                val cellColor = colorLUT[ci]
                val cellR = colorsR[ci]
                val cellG = colorsG[ci]
                val cellB = colorsB[ci]

                // Neon edge color (pre-compute RGB)
                val neonR = min(255, cellR + ((255 - cellR) * 0.6f).toInt())
                val neonG = min(255, cellG + ((255 - cellG) * 0.6f).toInt())
                val neonB = min(255, cellB + ((255 - cellB) * 0.6f).toInt())

                // -- Fast interior thresholds --
                // Circle: squared distance threshold
                val circleInnerR = radius - edgeBandWidth
                val circleInnerRSq = if (circleInnerR > 0f) circleInnerR * circleInnerR else 0f
                // Box: half-width with interior margin
                val boxHW = radius * 0.85f
                val boxInnerHW = boxHW - edgeBandWidth
                // Hex: inscribed circle radius for fast interior
                val hexInnerR = radius * 0.866f - edgeBandWidth
                val hexInnerRSq = if (hexInnerR > 0f) hexInnerR * hexInnerR else 0f
                // Star: inscribed circle (inner tips)
                val starInnerDist = radius * starInnerRatio - edgeBandWidth
                val starInnerDistSq = if (starInnerDist > 0f) starInnerDist * starInnerDist else 0f

                // -- Fast exterior discard -- max visible distance from center
                val maxVisR = radius + edgeBandWidth + glowMaxPx
                val maxVisRSq = maxVisR * maxVisR

                // Pixel range -- tight bounding box from maxVisR
                val pxStart = max(0, (cellCenterX - maxVisR).toInt())
                val pyStart = max(0, (cellCenterY - maxVisR).toInt())
                val pxEnd = min(w, (cellCenterX + maxVisR + 1f).toInt())
                val pyEnd = min(h, (cellCenterY + maxVisR + 1f).toInt())

                // Skip cells entirely off-screen
                if (pxStart >= w || pyStart >= h || pxEnd <= 0 || pyEnd <= 0) continue

                val px0 = pxStart - (pxStart % step)
                val py0 = pyStart - (pyStart % step)

                // -- Pixel loop --
                var py = py0
                while (py < pyEnd) {
                    val localY = py - cellCenterY
                    val localYSq = localY * localY
                    val rowBase = py * w

                    var px = px0
                    while (px < pxEnd) {
                        val localX = px - cellCenterX

                        // -- Fast exterior discard (squared distance from center) --
                        val distSqFromCenter = localX * localX + localYSq
                        if (distSqFromCenter > maxVisRSq) {
                            px += step
                            continue
                        }

                        // Rotate local coords
                        val rx2 = cosR * localX - sinR * localY
                        val ry2 = sinR * localX + cosR * localY

                        // -- Inlined SDF with fast interior checks --
                        var d: Float

                        when (shapeId) {
                            0 -> {
                                // Circle -- distSq already computed (unrotated space; same magnitude)
                                if (distSqFromCenter < circleInnerRSq) {
                                    // Deep interior -- skip SDF entirely
                                    writeBlock(pixels, px, py, step, w, h, cellColor)
                                    px += step
                                    continue
                                }
                                d = sqrt(distSqFromCenter) - radius
                            }
                            1 -> {
                                // Box -- fast interior: both abs coords inside margin
                                val abx = abs(rx2)
                                val aby = abs(ry2)
                                if (boxInnerHW > 0f && abx < boxInnerHW && aby < boxInnerHW) {
                                    writeBlock(pixels, px, py, step, w, h, cellColor)
                                    px += step
                                    continue
                                }
                                val dx = abx - boxHW
                                val dy = aby - boxHW
                                val odx = if (dx > 0f) dx else 0f
                                val ody = if (dy > 0f) dy else 0f
                                d = sqrt(odx * odx + ody * ody) +
                                    if (dx > dy) (if (dx < 0f) dx else 0f) else (if (dy < 0f) dy else 0f)
                            }
                            2 -> {
                                // Star -- fast interior via inscribed circle
                                if (distSqFromCenter < starInnerDistSq) {
                                    writeBlock(pixels, px, py, step, w, h, cellColor)
                                    px += step
                                    continue
                                }
                                // Fast atan2 for star angle
                                val angle = fatan2(ry2, rx2)
                                val dist = sqrt(rx2 * rx2 + ry2 * ry2)
                                val innerR2 = radius * starInnerRatio
                                val a = ((angle % starDblSeg) + starDblSeg) % starDblSeg
                                val tt = abs(a - starSegAngle) * starInvSeg
                                d = dist - (innerR2 + (radius - innerR2) * (1f - tt))
                            }
                            else -> {
                                // Hexagon -- fast interior via inscribed circle
                                if (distSqFromCenter < hexInnerRSq) {
                                    writeBlock(pixels, px, py, step, w, h, cellColor)
                                    px += step
                                    continue
                                }
                                var hpx = abs(rx2)
                                var hpy = abs(ry2)
                                val dot = 2f * min(hexK1 * hpx + hexK2 * hpy, 0f)
                                hpx -= dot * hexK1
                                hpy -= dot * hexK2
                                hpx -= hpx.coerceIn(-radius, radius)
                                hpy -= radius
                                d = sqrt(hpx * hpx + hpy * hpy) * if (hpy < 0f) -1f else 1f
                            }
                        }

                        // -- Distance to color -- crisp neon edges --
                        val rgba: Int

                        if (d < -edgeBandWidth) {
                            rgba = cellColor
                        } else if (d < 0f) {
                            val edgeT = 1f - (-d / edgeBandWidth)
                            val intensity = edgeT * edgeT * edgeT
                            val inv = 1f - intensity
                            val rr = (cellR * inv + neonR * intensity).toInt()
                            val gg = (cellG * inv + neonG * intensity).toInt()
                            val bb = (cellB * inv + neonB * intensity).toInt()
                            rgba = Color.rgb(rr, gg, bb)
                        } else if (d < edgeBandWidth) {
                            val edgeT = 1f - d / edgeBandWidth
                            val intensity = edgeT * edgeT
                            val rr = (neonR * intensity).toInt()
                            val gg = (neonG * intensity).toInt()
                            val bb = (neonB * intensity).toInt()
                            rgba = Color.rgb(rr, gg, bb)
                        } else if (hasGlow) {
                            val falloffDist = d - edgeBandWidth
                            if (falloffDist > 255f) {
                                px += step
                                continue
                            }
                            val glow = glowLUT[falloffDist.toInt()]
                            if (glow < 0.008f) {
                                px += step
                                continue
                            }
                            val rr = (cellR * glow * 0.7f).toInt()
                            val gg = (cellG * glow * 0.7f).toInt()
                            val bb = (cellB * glow * 0.7f).toInt()
                            rgba = Color.rgb(rr, gg, bb)
                        } else {
                            // outside shape, no glow -- skip
                            px += step
                            continue
                        }

                        // -- Write pixel(s) --
                        writeBlock(pixels, px, py, step, w, h, rgba)

                        px += step
                    }
                    py += step
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val shape = (params["shape"] as? String) ?: "circle"
        val shapeMul = if (shape == "star" || shape == "hexagon") 1.3f else 1f
        val glow = (params["edgeGlow"] as? Number)?.toFloat() ?: 0.4f
        val glowMul = if (glow > 0.01f) 1.2f else 1f
        return (0.4f * shapeMul * glowMul).coerceIn(0.1f, 1f)
    }

    companion object {
        /**
         * Fast atan2 approximation -- polynomial, max error ~0.005 rad.
         * Ported exactly from the web version.
         */
        @JvmStatic
        private fun fatan2(y: Float, x: Float): Float {
            val ax = if (x < 0f) -x else x
            val ay = if (y < 0f) -y else y
            val mn = if (ax < ay) ax else ay
            val mx = if (ax < ay) ay else ax
            val a = mn / (mx + 1e-15f)
            val s = a * a
            var r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a
            if (ay > ax) r = 1.5707963268f - r
            if (x < 0f) r = 3.1415926536f - r
            if (y < 0f) r = -r
            return r
        }

        /**
         * Write a step x step block of pixels into the pixel buffer, clamped to bitmap bounds.
         */
        @JvmStatic
        private fun writeBlock(pixels: IntArray, px: Int, py: Int, step: Int, w: Int, h: Int, color: Int) {
            if (step == 1) {
                pixels[py * w + px] = color
            } else {
                val maxSy = if (py + step < h) step else h - py
                val maxSx = if (px + step < w) step else w - px
                for (sy in 0 until maxSy) {
                    val rb = (py + sy) * w + px
                    for (sx in 0 until maxSx) {
                        pixels[rb + sx] = color
                    }
                }
            }
        }
    }
}
