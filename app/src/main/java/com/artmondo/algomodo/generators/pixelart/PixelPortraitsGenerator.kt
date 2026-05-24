package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PixelPortraitsGenerator : Generator {
    override val id = "pixel-portraits"
    override val family = "pixel-art"
    override val styleName = "Pixel Portraits"
    override val definition = "Algorithmic pixel art portraits of cats, punks, aliens, androids, skulls, and demons — each with unique features, expressions, and accessories."
    override val algorithmNotes = "Composites predefined feature layers (head shape, eyes, mouth, ears/accessories) per subject type on a small grid. Bilateral symmetry is approximated, with asymmetric details for character. Each seed produces unique variation via palette hue shifts, eye spacing, accessory presence, and detail randomization."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Subject", "subject", ParamGroup.COMPOSITION, "Portrait subject type",
            listOf("cat", "punk", "alien", "android", "skull", "demon", "ape", "ketchup"), "cat"),
        Parameter.SelectParam("Expression", "expression", ParamGroup.COMPOSITION, "Facial expression",
            listOf("neutral", "happy", "angry", "surprised"), "neutral"),
        Parameter.SelectParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution",
            listOf("32", "48", "64", "96"), "48"),
        Parameter.BooleanParam("Accessories", "accessories", ParamGroup.COMPOSITION,
            "Show optional accessories (whiskers, piercings, antennae, etc.)", true),
        Parameter.BooleanParam("Symmetry", "symmetry", ParamGroup.GEOMETRY,
            "Mirror left half to right for perfect bilateral symmetry", true),
        Parameter.SelectParam("Background", "bgStyle", ParamGroup.COLOR, "Background style",
            listOf("flat", "patterned"), "patterned"),
        Parameter.BooleanParam("Animated", "animated", ParamGroup.FLOW_MOTION,
            "Enable idle animations (blinking, eye movement, mouth twitch)", true),
        Parameter.BooleanParam("Bobbing", "bobbing", ParamGroup.FLOW_MOTION,
            "Enable head bob animation", true),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION,
            "Animation speed (idle sway and blinks)", 0.1f, 3f, 0.1f, 0.5f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "subject" to "cat", "expression" to "neutral", "gridSize" to "48",
        "accessories" to true, "symmetry" to true, "bgStyle" to "patterned",
        "animated" to true, "bobbing" to true, "animSpeed" to 0.5f, "reactivity" to 0f
    )

    // --- Subject extents for vertical centering ---
    private data class Extent(val top: Int, val bottom: Int)
    private val subjectExtents = mapOf(
        "cat" to Extent(-14, 28),
        "punk" to Extent(-16, 28),
        "alien" to Extent(-18, 22),
        "android" to Extent(-12, 28),
        "skull" to Extent(-11, 27),
        "demon" to Extent(-14, 28),
        "ape" to Extent(-13, 28),
        "ketchup" to Extent(-16, 20)
    )

    private fun computeCy(sz: Int, subject: String): Int {
        val ext = subjectExtents[subject] ?: Extent(-14, 28)
        val scale = sz / 48.0
        return ((sz - (ext.top + ext.bottom) * scale) / 2).roundToInt()
    }

    // --- Pixel drawing helpers ---

    private fun setPixel(d: IntArray, sz: Int, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x < 0 || x >= sz || y < 0 || y >= sz) return
        d[y * sz + x] = (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
    }

    private fun fillRect(d: IntArray, sz: Int, x0: Int, y0: Int, w: Int, h: Int, r: Int, g: Int, b: Int) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) setPixel(d, sz, x, y, r, g, b)
        }
    }

    private fun strokeRect(d: IntArray, sz: Int, x0: Int, y0: Int, w: Int, h: Int, r: Int, g: Int, b: Int) {
        for (x in x0 until x0 + w) {
            setPixel(d, sz, x, y0, r, g, b)
            setPixel(d, sz, x, y0 + h - 1, r, g, b)
        }
        for (y in y0 until y0 + h) {
            setPixel(d, sz, x0, y, r, g, b)
            setPixel(d, sz, x0 + w - 1, y, r, g, b)
        }
    }

    private fun fillEllipse(d: IntArray, sz: Int, cx: Int, cy: Int, rx: Int, ry: Int, r: Int, g: Int, b: Int) {
        if (rx <= 0 || ry <= 0) return
        for (y in cy - ry..cy + ry) {
            for (x in cx - rx..cx + rx) {
                val dxn = (x - cx).toDouble() / rx
                val dyn = (y - cy).toDouble() / ry
                if (dxn * dxn + dyn * dyn <= 1.0) setPixel(d, sz, x, y, r, g, b)
            }
        }
    }

    private fun fillTriangle(d: IntArray, sz: Int,
                             x0: Int, y0: Int, x1: Int, y1: Int, x2: Int, y2: Int,
                             r: Int, g: Int, b: Int) {
        val minX = min(x0, min(x1, x2)); val maxX = max(x0, max(x1, x2))
        val minY = min(y0, min(y1, y2)); val maxY = max(y0, max(y1, y2))
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val d0 = (x - x1) * (y0 - y1) - (x0 - x1) * (y - y1)
                val d1 = (x - x2) * (y1 - y2) - (x1 - x2) * (y - y2)
                val d2 = (x - x0) * (y2 - y0) - (x2 - x0) * (y - y0)
                val hasNeg = (d0 < 0) || (d1 < 0) || (d2 < 0)
                val hasPos = (d0 > 0) || (d1 > 0) || (d2 > 0)
                if (!(hasNeg && hasPos)) setPixel(d, sz, x, y, r, g, b)
            }
        }
    }

    private fun hexToRgb(hex: String): IntArray {
        val n = hex.removePrefix("#").toLong(16).toInt()
        return intArrayOf((n ushr 16) and 0xFF, (n ushr 8) and 0xFF, n and 0xFF)
    }

    // --- Sunglasses helper (shared by cat & punk) ---
    private val sunglassFrames = arrayOf(
        intArrayOf(15, 15, 20),
        intArrayOf(60, 30, 20),
        intArrayOf(180, 40, 50),
        intArrayOf(210, 170, 40)
    )

    private fun drawSunglasses(d: IntArray, sz: Int,
                               leftX: Int, rightX: Int, topY: Int,
                               lensW: Int, lensH: Int, frame: IntArray) {
        fillRect(d, sz, leftX, topY, lensW, lensH, 18, 18, 28)
        fillRect(d, sz, rightX, topY, lensW, lensH, 18, 18, 28)
        strokeRect(d, sz, leftX, topY, lensW, lensH, frame[0], frame[1], frame[2])
        strokeRect(d, sz, rightX, topY, lensW, lensH, frame[0], frame[1], frame[2])
        for (bx in leftX + lensW until rightX) {
            setPixel(d, sz, bx, topY, frame[0], frame[1], frame[2])
        }
        setPixel(d, sz, leftX + 1, topY + 1, 230, 240, 255)
        setPixel(d, sz, rightX + 1, topY + 1, 230, 240, 255)
    }

    // --- Subject colors ---
    private data class SubjectColors(
        val skin: IntArray, val skinDark: IntArray, val eye: IntArray, val pupil: IntArray,
        val mouth: IntArray, val accent: IntArray, val hair: IntArray, val body: IntArray, val bodyDark: IntArray
    )

    private fun darken(c: IntArray, amt: Int) = intArrayOf(
        max(0, c[0] - amt), max(0, c[1] - amt), max(0, c[2] - amt))
    private fun lighten(c: IntArray, amt: Int) = intArrayOf(
        min(255, c[0] + amt), min(255, c[1] + amt), min(255, c[2] + amt))
    private fun luminance(c: IntArray): Double =
        (c[0] * 0.299 + c[1] * 0.587 + c[2] * 0.114) / 255.0

    private fun getSubjectColors(subject: String, palette: Palette, rng: SeededRNG): SubjectColors {
        val colors = Array(palette.colors.size) { hexToRgb(palette.colors[it]) }
        val nc = colors.size
        val bg = colors[0]
        val bgLum = luminance(bg)

        fun shift(base: IntArray, amount: Int): IntArray {
            val v = rng.integer(-amount, amount)
            return intArrayOf(
                (base[0] + v).coerceIn(0, 255),
                (base[1] + v).coerceIn(0, 255),
                (base[2] + v).coerceIn(0, 255))
        }

        fun pickContrast(arr: List<IntArray>): IntArray {
            val minDelta = 0.22
            val candidates = arr.filter { abs(luminance(it) - bgLum) >= minDelta }
            if (candidates.isEmpty()) {
                var best = arr[0]; var bestDelta = -1.0
                for (c in arr) {
                    val delta = abs(luminance(c) - bgLum)
                    if (delta > bestDelta) { bestDelta = delta; best = c }
                }
                return best
            }
            return candidates[rng.integer(0, candidates.size - 1)]
        }

        return when (subject) {
            "cat" -> {
                val catFur = listOf(
                    intArrayOf(255, 110, 180), intArrayOf(220, 50, 50), intArrayOf(255, 140, 45),
                    intArrayOf(240, 210, 60), intArrayOf(85, 185, 85), intArrayOf(60, 130, 230),
                    intArrayOf(75, 55, 160), intArrayOf(180, 80, 210), intArrayOf(240, 240, 238),
                    intArrayOf(140, 85, 45), intArrayOf(135, 135, 140), intArrayOf(40, 40, 45),
                    intArrayOf(225, 205, 165))
                val base = pickContrast(catFur)
                SubjectColors(base, darken(base, 45), intArrayOf(180, 220, 80), intArrayOf(10, 10, 10),
                    intArrayOf(220, 140, 155), colors[nc - 1], darken(base, 25), base, darken(base, 45))
            }
            "punk" -> {
                val skins = listOf(
                    intArrayOf(240, 210, 180), intArrayOf(215, 175, 140),
                    intArrayOf(180, 135, 100), intArrayOf(140, 95, 65), intArrayOf(95, 60, 40))
                val skin = pickContrast(skins)
                SubjectColors(skin, darken(skin, 35), intArrayOf(40, 40, 40), intArrayOf(10, 10, 10),
                    intArrayOf(180, 60, 60), colors[nc - 1],
                    colors[min(nc - 1, rng.integer(1, nc - 1))],
                    intArrayOf(30, 30, 30), intArrayOf(20, 20, 20))
            }
            "alien" -> {
                val greens = listOf(
                    intArrayOf(170, 240, 195), intArrayOf(125, 215, 140), intArrayOf(85, 180, 95),
                    intArrayOf(55, 140, 70), intArrayOf(25, 90, 40))
                val skin = pickContrast(greens)
                SubjectColors(skin, darken(skin, 35), intArrayOf(10, 10, 10), intArrayOf(0, 0, 0),
                    darken(skin, 55), intArrayOf(150, 255, 150), darken(skin, 45),
                    intArrayOf(70, 70, 90), intArrayOf(50, 50, 70))
            }
            "android" -> SubjectColors(
                shift(colors[min(nc - 1, 2)], 15), shift(colors[min(nc - 1, 1)], 10),
                intArrayOf(240, 230, 210), intArrayOf(30, 20, 15),
                intArrayOf(180, 60, 50), colors[nc - 1],
                shift(colors[min(nc - 1, 3)], 10),
                shift(colors[min(nc - 1, 2)], 15), shift(colors[min(nc - 1, 1)], 10))
            "skull" -> {
                val heads = listOf(intArrayOf(245, 245, 238), intArrayOf(230, 215, 185))
                val greys = listOf(intArrayOf(205, 205, 210), intArrayOf(160, 160, 168),
                    intArrayOf(120, 120, 128), intArrayOf(80, 80, 88), intArrayOf(50, 50, 58))
                val head = pickContrast(heads); val body = pickContrast(greys)
                SubjectColors(head, darken(head, 55), intArrayOf(20, 20, 20), intArrayOf(0, 0, 0),
                    darken(head, 35), intArrayOf(70, 70, 75), darken(head, 30), body, darken(body, 30))
            }
            "demon" -> {
                val reds = listOf(
                    intArrayOf(255, 150, 165), intArrayOf(240, 95, 100), intArrayOf(210, 55, 60),
                    intArrayOf(175, 35, 45), intArrayOf(135, 25, 30), intArrayOf(85, 15, 20))
                val skin = pickContrast(reds)
                SubjectColors(skin, darken(skin, 40), intArrayOf(255, 200, 0), intArrayOf(255, 100, 0),
                    darken(skin, 75), intArrayOf(255, 150, 0), darken(skin, 60), skin, darken(skin, 40))
            }
            "ape" -> {
                val browns = listOf(
                    intArrayOf(200, 150, 105), intArrayOf(165, 115, 75), intArrayOf(130, 85, 50),
                    intArrayOf(95, 60, 35), intArrayOf(60, 35, 20))
                val tone = pickContrast(browns)
                SubjectColors(lighten(tone, 25), tone, intArrayOf(225, 225, 215), intArrayOf(20, 18, 15),
                    darken(tone, 40), intArrayOf(240, 200, 60), darken(tone, 30),
                    darken(tone, 20), darken(tone, 50))
            }
            "ketchup" -> {
                val reds = listOf(
                    intArrayOf(245, 100, 90), intArrayOf(220, 50, 45),
                    intArrayOf(180, 30, 25), intArrayOf(130, 20, 15))
                val red = pickContrast(reds)
                SubjectColors(red, darken(red, 60), intArrayOf(245, 245, 240), intArrayOf(15, 15, 15),
                    darken(red, 80), intArrayOf(255, 200, 30), intArrayOf(40, 40, 45),
                    intArrayOf(245, 240, 220), intArrayOf(180, 170, 140))
            }
            else -> SubjectColors(
                intArrayOf(200, 200, 200), intArrayOf(120, 120, 120),
                intArrayOf(0, 0, 0), intArrayOf(0, 0, 0),
                intArrayOf(180, 60, 60), intArrayOf(255, 200, 30),
                intArrayOf(60, 40, 30), intArrayOf(80, 80, 80), intArrayOf(40, 40, 40))
        }
    }

    private fun renderBackground(d: IntArray, sz: Int, subject: String, bgStyle: String,
                                 palette: Palette, rng: SeededRNG) {
        val colors = Array(palette.colors.size) { hexToRgb(palette.colors[it]) }
        val bg = colors[0]
        val bgArgb = (0xFF shl 24) or (bg[0] shl 16) or (bg[1] shl 8) or bg[2]
        for (i in 0 until sz * sz) d[i] = bgArgb

        if (bgStyle != "patterned") return

        when (subject) {
            "alien" -> {
                val numStars = (sz * sz * 0.01).toInt()
                for (s in 0 until numStars) {
                    val sx = rng.integer(0, sz - 1); val sy = rng.integer(0, sz - 1)
                    val b = 150 + rng.integer(0, 105)
                    setPixel(d, sz, sx, sy, b, b, (b * 0.8).toInt())
                }
            }
            "demon" -> {
                val band = sz shr 2
                for (y in sz - band until sz) {
                    for (x in 0 until sz) {
                        val t = (y - (sz - band)).toDouble() / band
                        val flicker = sin(x * 1.5 + y * 0.5) * 0.3 + 0.7
                        val r = min(255, bg[0] + (200 * t * flicker).toInt())
                        val g = min(255, bg[1] + (80 * t * flicker).toInt())
                        setPixel(d, sz, x, y, r, g, bg[2])
                    }
                }
            }
            "punk" -> {
                for (y in 0 until sz) {
                    for (x in 0 until sz) {
                        if (x % 6 == 0 || y % 6 == 0) {
                            val c = colors[min(colors.size - 1, 1)]
                            setPixel(d, sz, x, y,
                                (c[0] * 0.3 + bg[0] * 0.7).toInt(),
                                (c[1] * 0.3 + bg[1] * 0.7).toInt(),
                                (c[2] * 0.3 + bg[2] * 0.7).toInt())
                        }
                    }
                }
            }
            "cat" -> {
                val numBones = 3 + rng.integer(0, 3)
                for (b in 0 until numBones) {
                    val bx = rng.integer(2, sz - 6); val by = rng.integer(2, sz - 4)
                    val cr = (bg[0] + 40) and 0xFF
                    val cg = (bg[1] + 35) and 0xFF
                    val cb = (bg[2] + 30) and 0xFF
                    for (i in 0 until 4) setPixel(d, sz, bx + i, by, cr, cg, cb)
                    setPixel(d, sz, bx + 1, by - 1, cr, cg, cb); setPixel(d, sz, bx + 1, by + 1, cr, cg, cb)
                    setPixel(d, sz, bx + 2, by - 1, cr, cg, cb); setPixel(d, sz, bx + 2, by + 1, cr, cg, cb)
                    setPixel(d, sz, bx + 4, by - 1, cr, cg, cb); setPixel(d, sz, bx + 4, by + 1, cr, cg, cb)
                }
            }
            "android" -> {
                var y = 0
                while (y < sz) {
                    var x = 0
                    while (x < sz) {
                        if (rng.random() < 0.15f) {
                            val intensity = 15 + rng.integer(0, 25)
                            setPixel(d, sz, x, y, bg[0], min(255, bg[1] + intensity), bg[2])
                        }
                        x += 3
                    }
                    y += 3
                }
            }
            "skull" -> {
                val numCracks = 2 + rng.integer(0, 3)
                for (c in 0 until numCracks) {
                    var cx = rng.integer(0, sz - 1); var cy = rng.integer(0, sz - 1)
                    val len = rng.integer(3, 8)
                    for (i in 0 until len) {
                        setPixel(d, sz, cx, cy, (bg[0] * 0.7).toInt(), (bg[1] * 0.7).toInt(), (bg[2] * 0.7).toInt())
                        cx += rng.integer(-1, 1); cy += rng.integer(0, 1)
                    }
                }
            }
            "ape" -> {
                val numLeaves = (sz * sz * 0.012).toInt()
                val cr = max(0, bg[0] - 20); val cg = min(255, bg[1] + 25); val cb = max(0, bg[2] - 15)
                for (l in 0 until numLeaves) {
                    val lx = rng.integer(0, sz - 1); val ly = rng.integer(0, sz - 1)
                    setPixel(d, sz, lx, ly, cr, cg, cb)
                    setPixel(d, sz, lx + 1, ly, cr, cg, cb)
                    setPixel(d, sz, lx, ly + 1, cr, cg, cb)
                }
            }
            "ketchup" -> {
                val numSplats = (sz * sz * 0.008).toInt()
                for (p in 0 until numSplats) {
                    val sx = rng.integer(1, sz - 2); val sy = rng.integer(1, sz - 2)
                    setPixel(d, sz, sx, sy, 200, 40, 30)
                    if (rng.random() < 0.5f) setPixel(d, sz, sx + 1, sy, 170, 30, 25)
                    if (rng.random() < 0.4f) setPixel(d, sz, sx, sy + 1, 170, 30, 25)
                }
            }
        }
    }

    private fun renderBody(d: IntArray, sz: Int, subject: String,
                           accessories: Boolean, sc: SubjectColors, rng: SeededRNG) {
        val cx = sz / 2
        val cy = computeCy(sz, subject)
        val scaleF = sz / 48.0
        fun s(v: Int) = (v * scaleF).roundToInt()
        val neckY = cy + s(8)
        val torsoTop = neckY + s(2)

        when (subject) {
            "cat" -> {
                fillRect(d, sz, cx - s(4), neckY, s(8), s(3), sc.skin[0], sc.skin[1], sc.skin[2])
                for (i in 0 until s(8)) {
                    val tx = cx + s(8) + (i * 0.5).toInt()
                    val ty = torsoTop + s(10) - i
                    setPixel(d, sz, tx, ty, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                    setPixel(d, sz, tx + 1, ty, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                }
                setPixel(d, sz, cx + s(8) + s(4), torsoTop + s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                fillEllipse(d, sz, cx, torsoTop + s(8), s(9), s(10), sc.body[0], sc.body[1], sc.body[2])
                val belly = intArrayOf(min(255, sc.body[0] + 30), min(255, sc.body[1] + 30), min(255, sc.body[2] + 20))
                fillEllipse(d, sz, cx, torsoTop + s(9), s(5), s(7), belly[0], belly[1], belly[2])
                fillEllipse(d, sz, cx - s(5), torsoTop + s(14), s(3), s(3), sc.body[0], sc.body[1], sc.body[2])
                fillEllipse(d, sz, cx + s(5), torsoTop + s(14), s(3), s(3), sc.body[0], sc.body[1], sc.body[2])
                fillEllipse(d, sz, cx - s(5), torsoTop + s(16), s(3), s(2), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                fillEllipse(d, sz, cx + s(5), torsoTop + s(16), s(3), s(2), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                setPixel(d, sz, cx - s(5), torsoTop + s(17), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                setPixel(d, sz, cx + s(5), torsoTop + s(17), sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "punk" -> {
                fillRect(d, sz, cx - s(3), neckY, s(6), s(3), sc.skin[0], sc.skin[1], sc.skin[2])
                for (y in torsoTop until torsoTop + s(18)) {
                    val t = (y - torsoTop).toDouble() / s(18)
                    val hw = (s(7) + t * s(4)).toInt()
                    fillRect(d, sz, cx - hw, y, hw * 2, 1, sc.body[0], sc.body[1], sc.body[2])
                }
                fillTriangle(d, sz, cx - s(4), torsoTop, cx, torsoTop + s(6), cx + s(4), torsoTop, 60, 60, 70)
                fillRect(d, sz, cx - s(7), torsoTop, s(2), s(5), 40, 40, 40)
                fillRect(d, sz, cx + s(5), torsoTop, s(2), s(5), 40, 40, 40)
                var y = torsoTop + s(4)
                while (y < torsoTop + s(16)) { setPixel(d, sz, cx, y, 80, 80, 80); y += 2 }
                for (i in 0 until s(12)) {
                    val xOff = (i * 0.3).toInt()
                    fillRect(d, sz, cx - s(9) - xOff, torsoTop + s(2) + i, s(3), 1, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                    fillRect(d, sz, cx + s(7) + xOff, torsoTop + s(2) + i, s(3), 1, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                }
                fillEllipse(d, sz, cx - s(13), torsoTop + s(14), s(2), s(2), sc.skin[0], sc.skin[1], sc.skin[2])
                fillEllipse(d, sz, cx + s(13), torsoTop + s(14), s(2), s(2), sc.skin[0], sc.skin[1], sc.skin[2])
                fillRect(d, sz, cx - s(10), torsoTop + s(16), s(20), s(1), 60, 50, 30)
                setPixel(d, sz, cx, torsoTop + s(16), 200, 200, 180)
                if (accessories) {
                    for (i in 0 until 3) {
                        setPixel(d, sz, cx - s(6), torsoTop + s(3) + i * s(3), 200, 200, 200)
                        setPixel(d, sz, cx + s(6), torsoTop + s(3) + i * s(3), 200, 200, 200)
                    }
                }
            }
            "alien" -> {
                fillRect(d, sz, cx - s(2), neckY, s(4), s(3), sc.skin[0], sc.skin[1], sc.skin[2])
                fillEllipse(d, sz, cx, torsoTop + s(8), s(7), s(10), sc.body[0], sc.body[1], sc.body[2])
                fillEllipse(d, sz, cx, torsoTop + s(6), s(4), s(4), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                setPixel(d, sz, cx, torsoTop + s(5), sc.accent[0], sc.accent[1], sc.accent[2])
                setPixel(d, sz, cx - 1, torsoTop + s(6), sc.accent[0], sc.accent[1], sc.accent[2])
                setPixel(d, sz, cx + 1, torsoTop + s(6), sc.accent[0], sc.accent[1], sc.accent[2])
                setPixel(d, sz, cx, torsoTop + s(7), sc.accent[0], sc.accent[1], sc.accent[2])
                for (i in 0 until s(10)) {
                    val xOff = (i * 0.4).toInt()
                    setPixel(d, sz, cx - s(7) - xOff, torsoTop + s(2) + i, sc.skin[0], sc.skin[1], sc.skin[2])
                    setPixel(d, sz, cx - s(7) - xOff - 1, torsoTop + s(2) + i, sc.skin[0], sc.skin[1], sc.skin[2])
                    setPixel(d, sz, cx + s(7) + xOff, torsoTop + s(2) + i, sc.skin[0], sc.skin[1], sc.skin[2])
                    setPixel(d, sz, cx + s(7) + xOff + 1, torsoTop + s(2) + i, sc.skin[0], sc.skin[1], sc.skin[2])
                }
                val lhx = cx - s(11); val rhx = cx + s(11); val hy = torsoTop + s(12)
                setPixel(d, sz, lhx - 1, hy, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, lhx, hy + 1, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, lhx + 1, hy, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, rhx - 1, hy, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, rhx, hy + 1, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, rhx + 1, hy, sc.skin[0], sc.skin[1], sc.skin[2])
            }
            "android" -> {
                fillRect(d, sz, cx - s(3), neckY, s(6), s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                val bodyLeft = cx - s(11); val bodyW = s(22); val bodyH = s(18)
                fillRect(d, sz, bodyLeft, torsoTop, bodyW, bodyH, sc.body[0], sc.body[1], sc.body[2])
                strokeRect(d, sz, bodyLeft, torsoTop, bodyW, bodyH, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                fillRect(d, sz, cx - s(4), torsoTop + s(4), s(8), s(5), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                fillEllipse(d, sz, cx, torsoTop + s(2), s(1), s(1), sc.accent[0], sc.accent[1], sc.accent[2])
                fillEllipse(d, sz, cx - s(7), torsoTop + s(11), s(1), s(1), sc.accent[0], sc.accent[1], sc.accent[2])
                fillEllipse(d, sz, cx + s(7), torsoTop + s(11), s(1), s(1), sc.accent[0], sc.accent[1], sc.accent[2])
                setPixel(d, sz, cx - s(3), torsoTop + s(15), sc.accent[0], sc.accent[1], sc.accent[2])
                setPixel(d, sz, cx, torsoTop + s(15), sc.accent[0], sc.accent[1], sc.accent[2])
                setPixel(d, sz, cx + s(3), torsoTop + s(15), sc.accent[0], sc.accent[1], sc.accent[2])
                fillRect(d, sz, cx - s(13), torsoTop + s(2), s(2), s(14), sc.body[0], sc.body[1], sc.body[2])
                fillRect(d, sz, cx + s(11), torsoTop + s(2), s(2), s(14), sc.body[0], sc.body[1], sc.body[2])
                fillEllipse(d, sz, cx - s(12), torsoTop + s(16), s(2), s(1), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                fillEllipse(d, sz, cx + s(12), torsoTop + s(16), s(2), s(1), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
            }
            "skull" -> renderSkullBody(d, sz, cx, cy, sc, scaleF)
            "demon" -> renderDemonBody(d, sz, cx, sc, accessories, scaleF, neckY, torsoTop)
            "ape" -> {
                fillRect(d, sz, cx - s(4), neckY, s(8), s(2), sc.body[0], sc.body[1], sc.body[2])
                fillEllipse(d, sz, cx, torsoTop + s(8), s(11), s(10), sc.body[0], sc.body[1], sc.body[2])
                fillEllipse(d, sz, cx, torsoTop + s(9), s(6), s(6), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                fillEllipse(d, sz, cx, torsoTop + s(11), s(4), s(3), sc.skin[0], sc.skin[1], sc.skin[2])
                for (i in 0 until s(15)) {
                    val xOff = (i * 0.2).toInt()
                    fillRect(d, sz, cx - s(12) - xOff, torsoTop + s(2) + i, s(3), 1, sc.body[0], sc.body[1], sc.body[2])
                    fillRect(d, sz, cx + s(9) + xOff, torsoTop + s(2) + i, s(3), 1, sc.body[0], sc.body[1], sc.body[2])
                }
                fillEllipse(d, sz, cx - s(13), torsoTop + s(16), s(3), s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                fillEllipse(d, sz, cx + s(13), torsoTop + s(16), s(3), s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                setPixel(d, sz, cx - s(13), torsoTop + s(15), sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, cx + s(13), torsoTop + s(15), sc.skin[0], sc.skin[1], sc.skin[2])
            }
            "ketchup" -> {
                val bodyTop = cy - s(6); val bodyMidTop = cy - s(2); val bodyBottom = cy + s(20)
                for (y in bodyTop until bodyMidTop) {
                    val t = (y - bodyTop).toDouble() / s(4)
                    val hw = (s(5) + t * s(4)).toInt()
                    fillRect(d, sz, cx - hw, y, hw * 2, 1, sc.skin[0], sc.skin[1], sc.skin[2])
                }
                fillRect(d, sz, cx - s(9), bodyMidTop, s(18), bodyBottom - bodyMidTop, sc.skin[0], sc.skin[1], sc.skin[2])
                fillRect(d, sz, cx - s(9), bodyBottom - 1, s(18), s(1), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                fillRect(d, sz, cx + s(8), bodyMidTop, 1, bodyBottom - bodyMidTop - 1, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                fillRect(d, sz, cx - s(8), bodyMidTop + s(1), 1, s(8), 255, 130, 120)
                val labelLeft = cx - s(7); val labelW = s(14)
                val labelTop = cy - s(3); val labelBottom = cy + s(11)
                fillRect(d, sz, labelLeft, labelTop, labelW, labelBottom - labelTop, sc.body[0], sc.body[1], sc.body[2])
                strokeRect(d, sz, labelLeft, labelTop, labelW, labelBottom - labelTop, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                fillRect(d, sz, labelLeft + 1, labelTop + 1, labelW - 2, s(1), sc.accent[0], sc.accent[1], sc.accent[2])
                fillRect(d, sz, labelLeft + 1, labelBottom - s(2), labelW - 2, s(1), sc.accent[0], sc.accent[1], sc.accent[2])
                if (accessories) {
                    fillEllipse(d, sz, cx, cy + s(8), s(1), s(1), 220, 35, 30)
                    setPixel(d, sz, cx, cy + s(7), 60, 130, 40)
                }
            }
        }
    }

    private fun renderSkullBody(d: IntArray, sz: Int, cx: Int, cy: Int, sc: SubjectColors, scaleF: Double) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        val bone = sc.skin; val boneDark = sc.skinDark
        fun setB(x: Int, y: Int) = setPixel(d, sz, x, y, bone[0], bone[1], bone[2])
        fun setBd(x: Int, y: Int) = setPixel(d, sz, x, y, boneDark[0], boneDark[1], boneDark[2])

        val neckY = cy + s(8); val torsoTop = neckY + s(2)
        for (y in neckY until torsoTop) { setB(cx - 1, y); setB(cx, y); setB(cx + 1, y) }

        val clavY = torsoTop
        for (i in 0..s(7)) {
            val drop = if (i >= s(5)) 1 else 0
            setB(cx - s(1) - i, clavY + drop); setB(cx + s(1) + i, clavY + drop)
        }
        setB(cx - s(8), clavY + 1); setB(cx - s(9), clavY + 2)
        setB(cx + s(8), clavY + 1); setB(cx + s(9), clavY + 2)

        val rcTop = torsoTop + s(2); val rcBot = torsoTop + s(11)
        val rcH = rcBot - rcTop
        for (y in rcTop..rcBot) {
            val t = (y - rcTop).toDouble() / rcH
            val w = (s(2) + sin(t * PI) * s(5)).toInt()
            setB(cx - w, y); setB(cx + w, y)
        }
        for (r in 0 until 4) {
            val ry = rcTop + s(1) + r * s(2)
            val t = (ry - rcTop).toDouble() / rcH
            val w = (s(2) + sin(t * PI) * s(5)).toInt()
            for (x in 1 until w) { setB(cx - x, ry); setB(cx + x, ry) }
        }
        for (y in rcTop until rcTop + s(7)) setB(cx, y)
        for (x in cx - s(3)..cx + s(3)) setB(x, rcBot)

        val spineTop = rcBot + 1
        setB(cx, spineTop); setB(cx - 1, spineTop); setB(cx + 1, spineTop)
        setB(cx, spineTop + 1)
        setBd(cx - 1, spineTop + 1); setBd(cx + 1, spineTop + 1)

        val pelvY = spineTop + s(2)
        for (x in cx - s(7)..cx + s(7)) setB(x, pelvY)
        setB(cx - s(7), pelvY + 1); setB(cx - s(6), pelvY + 2)
        setB(cx + s(7), pelvY + 1); setB(cx + s(6), pelvY + 2)
        setB(cx - s(2), pelvY + 1); setB(cx + s(2), pelvY + 1)
        setB(cx - s(3), pelvY + 2); setB(cx + s(3), pelvY + 2)
        setB(cx - s(5), pelvY + 3); setB(cx + s(5), pelvY + 3)
        setB(cx - s(4), pelvY + 3); setB(cx + s(4), pelvY + 3)

        val shY = clavY + 2; val shX = s(9)
        val elbowY = torsoTop + s(8); val elbowX = s(11)
        for (y in shY until elbowY) {
            val t = (y - shY).toDouble() / (elbowY - shY)
            val xOff = (shX + t * (elbowX - shX)).toInt()
            setB(cx - xOff, y); setB(cx + xOff, y)
        }
        setB(cx - elbowX, elbowY); setB(cx - elbowX - 1, elbowY)
        setB(cx + elbowX, elbowY); setB(cx + elbowX + 1, elbowY)

        val wristY = torsoTop + s(14); val wristX = s(9)
        for (y in elbowY + 1..wristY) {
            val t = (y - elbowY).toDouble() / (wristY - elbowY)
            val xOff = (elbowX - t * (elbowX - wristX)).toInt()
            setB(cx - xOff, y); setB(cx + xOff, y)
            setBd(cx - xOff + 1, y); setBd(cx + xOff - 1, y)
        }
        val handY = wristY + 1
        for (i in 0 until 3) {
            val off = s(8) + i
            setB(cx - off, handY); setB(cx - off, handY + 1)
            setB(cx + off, handY); setB(cx + off, handY + 1)
        }
        setB(cx - s(9), handY + 2); setB(cx + s(9), handY + 2)
    }

    private fun renderDemonBody(d: IntArray, sz: Int, cx: Int, sc: SubjectColors,
                                accessories: Boolean, scaleF: Double, neckY: Int, torsoTop: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        fillRect(d, sz, cx - s(5), neckY, s(10), s(3), sc.skin[0], sc.skin[1], sc.skin[2])
        for (y in torsoTop until torsoTop + s(18)) {
            val t = (y - torsoTop).toDouble() / s(18)
            val hw = (s(10) - t * s(2)).toInt()
            fillRect(d, sz, cx - hw, y, hw * 2, 1, sc.body[0], sc.body[1], sc.body[2])
        }
        fillEllipse(d, sz, cx - s(3), torsoTop + s(4), s(4), s(3), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        fillEllipse(d, sz, cx + s(3), torsoTop + s(4), s(4), s(3), sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        for (i in 0 until 3) {
            val ay = torsoTop + s(8) + i * s(3)
            setPixel(d, sz, cx, ay, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
            setPixel(d, sz, cx - 1, ay, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
            setPixel(d, sz, cx + 1, ay, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        }
        fillTriangle(d, sz, cx - s(10), torsoTop + s(1), cx - s(14), torsoTop - s(2), cx - s(7), torsoTop - s(1),
            sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        fillTriangle(d, sz, cx + s(10), torsoTop + s(1), cx + s(14), torsoTop - s(2), cx + s(7), torsoTop - s(1),
            sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        setPixel(d, sz, cx - s(14), torsoTop - s(2), sc.accent[0], sc.accent[1], sc.accent[2])
        setPixel(d, sz, cx + s(14), torsoTop - s(2), sc.accent[0], sc.accent[1], sc.accent[2])
        for (i in 0 until s(12)) {
            val xOff = (i * 0.2).toInt()
            val armW = max(1, s(3) - ((i.toDouble() / s(12)) * s(1)).toInt())
            fillRect(d, sz, cx - s(11) - xOff, torsoTop + s(2) + i, armW, 1, sc.body[0], sc.body[1], sc.body[2])
            fillRect(d, sz, cx + s(9) + xOff, torsoTop + s(2) + i, armW, 1, sc.body[0], sc.body[1], sc.body[2])
        }
        val clhx = cx - s(14); val crhx = cx + s(13); val chy = torsoTop + s(14)
        fillRect(d, sz, clhx - 1, chy, s(3), s(2), sc.skin[0], sc.skin[1], sc.skin[2])
        fillRect(d, sz, crhx - 1, chy, s(3), s(2), sc.skin[0], sc.skin[1], sc.skin[2])
        setPixel(d, sz, clhx - 1, chy + s(2), sc.accent[0], sc.accent[1], sc.accent[2])
        setPixel(d, sz, clhx + 1, chy + s(2), sc.accent[0], sc.accent[1], sc.accent[2])
        setPixel(d, sz, crhx - 1, chy + s(2), sc.accent[0], sc.accent[1], sc.accent[2])
        setPixel(d, sz, crhx + 1, chy + s(2), sc.accent[0], sc.accent[1], sc.accent[2])
        fillRect(d, sz, cx - s(8), torsoTop + s(16), s(16), s(1), sc.accent[0], sc.accent[1], sc.accent[2])
    }

    private fun renderPortrait(d: IntArray, sz: Int, subject: String, expression: String,
                               accessories: Boolean, sc: SubjectColors, rng: SeededRNG, seed: Int) {
        val cx = sz / 2
        val cy = computeCy(sz, subject)
        val scaleF = sz / 48.0
        val eyeSpacing = rng.integer(-1, 1)
        when (subject) {
            "cat" -> renderCat(d, sz, cx, cy, scaleF, expression, accessories, sc, eyeSpacing, seed)
            "punk" -> renderPunk(d, sz, cx, cy, scaleF, expression, accessories, sc, rng, eyeSpacing, seed)
            "alien" -> renderAlien(d, sz, cx, cy, scaleF, expression, accessories, sc, eyeSpacing, seed)
            "android" -> renderAndroid(d, sz, cx, cy, scaleF, expression, accessories, sc, eyeSpacing, seed)
            "skull" -> renderSkull(d, sz, cx, cy, scaleF, expression, accessories, sc, rng, eyeSpacing)
            "demon" -> renderDemon(d, sz, cx, cy, scaleF, expression, accessories, sc, rng, eyeSpacing)
            "ape" -> renderApe(d, sz, cx, cy, scaleF, expression, accessories, sc, rng, eyeSpacing)
            "ketchup" -> renderKetchup(d, sz, cx, cy, scaleF, expression, accessories, sc, eyeSpacing)
        }
    }

    private fun renderCat(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                          expression: String, accessories: Boolean, sc: SubjectColors,
                          eyeSpacing: Int, seed: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        fillEllipse(d, sz, cx, cy, s(11), s(9), sc.skin[0], sc.skin[1], sc.skin[2])
        fillEllipse(d, sz, cx - s(9), cy + s(2), s(4), s(3), sc.skin[0], sc.skin[1], sc.skin[2])
        fillEllipse(d, sz, cx + s(9), cy + s(2), s(4), s(3), sc.skin[0], sc.skin[1], sc.skin[2])
        fillTriangle(d, sz, cx - s(10), cy - s(4), cx - s(8), cy - s(14), cx - s(3), cy - s(7),
            sc.skin[0], sc.skin[1], sc.skin[2])
        fillTriangle(d, sz, cx + s(10), cy - s(4), cx + s(8), cy - s(14), cx + s(3), cy - s(7),
            sc.skin[0], sc.skin[1], sc.skin[2])
        fillTriangle(d, sz, cx - s(9), cy - s(5), cx - s(7), cy - s(12), cx - s(4), cy - s(7),
            sc.mouth[0], sc.mouth[1], sc.mouth[2])
        fillTriangle(d, sz, cx + s(9), cy - s(5), cx + s(7), cy - s(12), cx + s(4), cy - s(7),
            sc.mouth[0], sc.mouth[1], sc.mouth[2])
        val eyeY = cy - s(1)
        val eyeLx = cx - s(4) + eyeSpacing; val eyeRx = cx + s(4) + eyeSpacing
        fillEllipse(d, sz, eyeLx, eyeY, s(3), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eyeRx, eyeY, s(3), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        for (py in eyeY - s(1)..eyeY + s(1)) {
            setPixel(d, sz, eyeLx, py, sc.pupil[0], sc.pupil[1], sc.pupil[2])
            setPixel(d, sz, eyeRx, py, sc.pupil[0], sc.pupil[1], sc.pupil[2])
        }
        fillTriangle(d, sz, cx - s(1), cy + s(2), cx + s(1), cy + s(2), cx, cy + s(3),
            sc.mouth[0], sc.mouth[1], sc.mouth[2])
        val my = cy + s(4)
        when (expression) {
            "happy" -> for (i in -s(3)..s(3)) {
                val curve = if (abs(i) > s(1)) -1 else if (i == 0) -1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "angry" -> {
                for (i in -s(2)..s(2)) setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                for (i in 0 until s(4)) {
                    setPixel(d, sz, eyeLx - s(2) + i, eyeY - s(3) + (i * 0.4).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    setPixel(d, sz, eyeRx + s(2) - i, eyeY - s(3) + (i * 0.4).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                }
            }
            "surprised" -> fillEllipse(d, sz, cx, my, s(1), s(1), sc.mouth[0], sc.mouth[1], sc.mouth[2])
            else -> {
                setPixel(d, sz, cx - s(2), my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                setPixel(d, sz, cx - s(1), my + 1, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                setPixel(d, sz, cx, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                setPixel(d, sz, cx + s(1), my + 1, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                setPixel(d, sz, cx + s(2), my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
        }
        if (accessories) {
            val wy = cy + s(3)
            for (i in 0 until s(4)) {
                setPixel(d, sz, cx - s(5) - i, wy - 1 - (i * 0.3).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                setPixel(d, sz, cx + s(5) + i, wy - 1 - (i * 0.3).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
            }
            for (i in 0 until s(4)) {
                setPixel(d, sz, cx - s(5) - i, wy, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                setPixel(d, sz, cx + s(5) + i, wy, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
            }
            for (i in 0 until s(4)) {
                setPixel(d, sz, cx - s(5) - i, wy + 1 + (i * 0.3).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                setPixel(d, sz, cx + s(5) + i, wy + 1 + (i * 0.3).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
            }
            val catGlassesRng = SeededRNG(seed + 5000)
            if (catGlassesRng.random() < 0.5f) {
                val frame = catGlassesRng.pick(sunglassFrames.toList())
                drawSunglasses(d, sz, eyeLx - s(3), eyeRx - s(3), eyeY - s(2), s(6), s(5), frame)
            }
        }
    }

    private fun renderPunk(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                           expression: String, accessories: Boolean, sc: SubjectColors,
                           rng: SeededRNG, eyeSpacing: Int, seed: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        fillEllipse(d, sz, cx, cy, s(9), s(10), sc.skin[0], sc.skin[1], sc.skin[2])
        val mohawkW = s(1) + rng.integer(0, s(1))
        for (my2 in cy - s(13) until cy - s(7)) {
            for (mx in cx - mohawkW..cx + mohawkW) {
                setPixel(d, sz, mx, my2, sc.hair[0], sc.hair[1], sc.hair[2])
            }
        }
        val eyeY = cy - s(2)
        val eLx = cx - s(4) + eyeSpacing; val eRx = cx + s(4) + eyeSpacing
        fillRect(d, sz, eLx - s(2) - 1, eyeY - s(1) - 1, s(4) + 2, s(2) + 2, 10, 10, 10)
        fillRect(d, sz, eRx - s(2) - 1, eyeY - s(1) - 1, s(4) + 2, s(2) + 2, 10, 10, 10)
        fillRect(d, sz, eLx - s(2), eyeY - s(1), s(4), s(2), 230, 230, 230)
        fillRect(d, sz, eRx - s(2), eyeY - s(1), s(4), s(2), 230, 230, 230)
        setPixel(d, sz, eLx, eyeY, sc.eye[0], sc.eye[1], sc.eye[2])
        setPixel(d, sz, eRx, eyeY, sc.eye[0], sc.eye[1], sc.eye[2])
        if (expression == "angry") {
            for (i in 0 until s(4)) {
                setPixel(d, sz, eLx - s(2) + i, eyeY - s(3) + (i * 0.4).toInt(), 10, 10, 10)
                setPixel(d, sz, eRx + s(2) - i, eyeY - s(3) + (i * 0.4).toInt(), 10, 10, 10)
            }
        }
        setPixel(d, sz, cx, cy + s(1), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        setPixel(d, sz, cx, cy + s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        val my = cy + s(4)
        when (expression) {
            "happy" -> for (i in -s(3)..s(3)) {
                val curve = if (abs(i) > s(1)) -1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "angry" -> for (i in -s(3)..s(3)) setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            else -> {
                for (i in -s(3)..s(1)) setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                setPixel(d, sz, cx + s(2), my - 1, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
        }
        if (accessories) {
            setPixel(d, sz, cx + 1, cy + s(2), 200, 200, 220)
            setPixel(d, sz, cx - s(8), cy - s(1), 200, 200, 220)
            val punkGlassesRng = SeededRNG(seed + 5000)
            if (punkGlassesRng.random() < 0.5f) {
                val frame = punkGlassesRng.pick(sunglassFrames.toList())
                drawSunglasses(d, sz, eLx - s(3), eRx - s(3), eyeY - s(2), s(6), s(4), frame)
            }
        }
    }

    private fun renderAlien(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                            expression: String, accessories: Boolean, sc: SubjectColors,
                            eyeSpacing: Int, seed: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        for (y in cy - s(12) until cy + s(8)) {
            val t = (y - (cy - s(12))).toDouble() / s(20)
            val rx = s(10) * (1 - t * 0.5)
            var x = (cx - rx).toInt()
            val xEnd = (cx + rx).toInt()
            while (x <= xEnd) {
                setPixel(d, sz, x, y, sc.skin[0], sc.skin[1], sc.skin[2])
                x++
            }
        }
        val eyeY = cy - s(3)
        val eLx = cx - s(5) + eyeSpacing; val eRx = cx + s(5) + eyeSpacing
        fillEllipse(d, sz, eLx, eyeY, s(4), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eRx, eyeY, s(4), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        setPixel(d, sz, eLx, eyeY, sc.accent[0], sc.accent[1], sc.accent[2])
        setPixel(d, sz, eRx, eyeY, sc.accent[0], sc.accent[1], sc.accent[2])
        setPixel(d, sz, cx - 1, cy + s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        setPixel(d, sz, cx + 1, cy + s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        val my = cy + s(4)
        when (expression) {
            "happy" -> for (i in -s(3)..s(3)) {
                val curve = if (abs(i) > s(1)) -1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "angry" -> {
                for (i in -s(2)..s(2)) setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                for (i in 0 until s(4)) {
                    setPixel(d, sz, eLx - s(2) + i, eyeY - s(3) + (i * 0.4).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    setPixel(d, sz, eRx + s(2) - i, eyeY - s(3) + (i * 0.4).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                }
            }
            "surprised" -> {
                fillEllipse(d, sz, cx, my, s(2), s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                for (i in 0 until s(3)) {
                    setPixel(d, sz, eLx - s(1) + i, eyeY - s(3), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    setPixel(d, sz, eRx - s(1) + i, eyeY - s(3), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                }
            }
            else -> for (i in -s(2)..s(2)) setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
        }
        if (accessories) {
            val antennaType = SeededRNG(seed + 4000).integer(0, 1)
            if (antennaType == 0) {
                for (i in 1..s(5)) {
                    setPixel(d, sz, cx - s(3), cy - s(12) - i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    setPixel(d, sz, cx + s(3), cy - s(12) - i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                }
                fillEllipse(d, sz, cx - s(3), cy - s(12) - s(5) - 1, 1, 1, sc.accent[0], sc.accent[1], sc.accent[2])
                fillEllipse(d, sz, cx + s(3), cy - s(12) - s(5) - 1, 1, 1, sc.accent[0], sc.accent[1], sc.accent[2])
            } else {
                for (i in 1..s(7)) setPixel(d, sz, cx - 1, cy - s(12) - i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                setPixel(d, sz, cx - 1, cy - s(12) - s(7) - 1, sc.accent[0], sc.accent[1], sc.accent[2])
            }
        }
    }

    private fun renderAndroid(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                              expression: String, accessories: Boolean, sc: SubjectColors,
                              eyeSpacing: Int, seed: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        val headLeft = cx - s(9); val headTop = cy - s(8)
        val headW = s(18); val headH = s(17)
        fillRect(d, sz, headLeft, headTop, headW, headH, sc.skin[0], sc.skin[1], sc.skin[2])
        strokeRect(d, sz, headLeft, headTop, headW, headH, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        val eyeY = cy - s(1)
        val eLx = cx - s(4) + eyeSpacing; val eRx = cx + s(4) + eyeSpacing
        fillEllipse(d, sz, eLx, eyeY, s(3), s(3), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        fillEllipse(d, sz, eRx, eyeY, s(3), s(3), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        fillEllipse(d, sz, eLx, eyeY, s(2), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eRx, eyeY, s(2), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eLx, eyeY, s(1), s(1), sc.pupil[0], sc.pupil[1], sc.pupil[2])
        fillEllipse(d, sz, eRx, eyeY, s(1), s(1), sc.pupil[0], sc.pupil[1], sc.pupil[2])
        val my = cy + s(5)
        when (expression) {
            "happy" -> for (i in -s(3)..s(3)) {
                val curve = if (abs(i) > s(1)) -1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "angry" -> for (i in -s(3)..s(3)) {
                val curve = if (abs(i) > s(1)) 1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "surprised" -> fillRect(d, sz, cx - s(1), my - s(1), s(2), s(3), sc.mouth[0], sc.mouth[1], sc.mouth[2])
            else -> fillRect(d, sz, cx - s(2), my, s(4), s(1), sc.mouth[0], sc.mouth[1], sc.mouth[2])
        }
        if (accessories) {
            val antennaType = SeededRNG(seed + 4000).integer(0, 1)
            if (antennaType == 0) {
                for (i in 1..s(3)) {
                    setPixel(d, sz, cx - s(3), headTop - i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    setPixel(d, sz, cx + s(3), headTop - i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                }
                fillEllipse(d, sz, cx - s(3), headTop - s(4), 1, 1, sc.accent[0], sc.accent[1], sc.accent[2])
                fillEllipse(d, sz, cx + s(3), headTop - s(4), 1, 1, sc.accent[0], sc.accent[1], sc.accent[2])
            } else {
                for (i in 1..s(5)) setPixel(d, sz, cx - 1, headTop - i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                setPixel(d, sz, cx - 1, headTop - s(6), sc.accent[0], sc.accent[1], sc.accent[2])
                setPixel(d, sz, cx - 2, headTop - s(6), sc.accent[0], sc.accent[1], sc.accent[2])
            }
            setPixel(d, sz, headLeft - 1, cy + s(1), sc.accent[0], sc.accent[1], sc.accent[2])
            setPixel(d, sz, headLeft + headW, cy + s(1), sc.accent[0], sc.accent[1], sc.accent[2])
        }
    }

    private fun renderSkull(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                            expression: String, accessories: Boolean, sc: SubjectColors,
                            rng: SeededRNG, eyeSpacing: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        val hcy = cy - s(2); val hrx = s(10); val hry = s(9)
        val pinchYTop = cy + s(2); val pinchYBot = cy + s(3)
        val pinchInset = s(6)
        for (y in hcy - hry..hcy + hry) {
            val dy = (y - hcy).toDouble() / hry
            if (dy * dy > 1) continue
            val hw = hrx * sqrt(1 - dy * dy)
            val x0 = floor(cx - hw).toInt()
            val x1 = kotlin.math.ceil(cx + hw).toInt()
            val inPinch = y in pinchYTop..pinchYBot
            for (x in x0..x1) {
                if (inPinch && abs(x - cx) >= pinchInset) continue
                setPixel(d, sz, x, y, sc.skin[0], sc.skin[1], sc.skin[2])
            }
        }
        for (y in cy + s(4) until cy + s(10)) {
            val t = (y - (cy + s(4))).toDouble() / s(6)
            val jw = s(7) * (1 - t * 0.3)
            var x = (cx - jw).toInt()
            val xEnd = (cx + jw).toInt()
            while (x <= xEnd) {
                setPixel(d, sz, x, y, sc.skin[0], sc.skin[1], sc.skin[2]); x++
            }
        }
        val eyeY = cy - s(2)
        val eLx = cx - s(4) + eyeSpacing; val eRx = cx + s(4) + eyeSpacing
        val socketR = if (expression == "surprised") s(4) else s(3)
        fillEllipse(d, sz, eLx, eyeY, socketR, socketR, sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eRx, eyeY, socketR, socketR, sc.eye[0], sc.eye[1], sc.eye[2])
        when (expression) {
            "angry" -> {
                setPixel(d, sz, eLx, eyeY, 255, 50, 50); setPixel(d, sz, eRx, eyeY, 255, 50, 50)
                for (i in 0 until s(4)) {
                    setPixel(d, sz, eLx - s(2) + i, eyeY - s(4) + (i * 0.5).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    setPixel(d, sz, eRx + s(2) - i, eyeY - s(4) + (i * 0.5).toInt(), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                }
            }
            "happy" -> {
                setPixel(d, sz, eLx, eyeY, 255, 220, 100); setPixel(d, sz, eRx, eyeY, 255, 220, 100)
            }
            "surprised" -> {
                fillEllipse(d, sz, eLx, eyeY, s(2), s(2), 180, 220, 255)
                fillEllipse(d, sz, eRx, eyeY, s(2), s(2), 180, 220, 255)
                for (i in 0 until s(4)) {
                    setPixel(d, sz, eLx - s(2) + i, eyeY - s(5), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    setPixel(d, sz, eRx - s(2) + i, eyeY - s(5), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                }
            }
        }
        fillTriangle(d, sz, cx, cy + s(1), cx - s(2), cy + s(4), cx + s(2), cy + s(4),
            sc.eye[0], sc.eye[1], sc.eye[2])
        val my = cy + s(6)
        when (expression) {
            "surprised" -> {
                fillRect(d, sz, cx - s(4), my, s(8) + 1, s(3), sc.eye[0], sc.eye[1], sc.eye[2])
                var i = -s(4)
                while (i <= s(4)) {
                    setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                    setPixel(d, sz, cx + i, my + s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                    i += 2
                }
            }
            "happy" -> for (i in -s(4)..s(4)) {
                val dy = if (abs(i) > s(2)) -1 else 0
                val isTooth = i % 2 == 0
                val c = if (isTooth) sc.mouth else sc.accent
                setPixel(d, sz, cx + i, my + dy, c[0], c[1], c[2])
                setPixel(d, sz, cx + i, my + 1 + dy, c[0], c[1], c[2])
            }
            "angry" -> for (i in -s(4)..s(4)) {
                val dy = (if (i % 2 == 0) 0 else -1) + (if (abs(i) > s(2)) 1 else 0)
                val isTooth = i % 2 == 0
                val c = if (isTooth) sc.mouth else sc.accent
                setPixel(d, sz, cx + i, my + dy, c[0], c[1], c[2])
                setPixel(d, sz, cx + i, my + 1 + dy, c[0], c[1], c[2])
            }
            else -> for (i in -s(4)..s(4)) {
                val isTooth = i % 2 == 0
                val c = if (isTooth) sc.mouth else sc.accent
                setPixel(d, sz, cx + i, my, c[0], c[1], c[2])
                setPixel(d, sz, cx + i, my + 1, c[0], c[1], c[2])
            }
        }
        if (accessories) renderSkullHat(d, sz, cx, cy, scaleF, rng)
    }

    private fun renderSkullHat(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double, rng: SeededRNG) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        val hatType = rng.integer(0, 2)
        when (hatType) {
            0 -> {
                val bandCol = intArrayOf(55, 55, 65); val bandHi = intArrayOf(150, 150, 165)
                for (i in 0..s(9)) {
                    val t = i.toDouble() / s(9)
                    val bx = cx - ((1 - t) * s(9)).roundToInt()
                    val by = cy - s(3) - (sin(t * PI / 2) * s(9)).roundToInt()
                    setPixel(d, sz, bx, by, bandCol[0], bandCol[1], bandCol[2])
                    setPixel(d, sz, bx, by - 1, bandCol[0], bandCol[1], bandCol[2])
                    if (i % 3 == 0) setPixel(d, sz, bx, by - 1, bandHi[0], bandHi[1], bandHi[2])
                }
                fillEllipse(d, sz, cx - s(9), cy - s(2), s(2), s(3), 25, 25, 30)
                fillEllipse(d, sz, cx - s(9), cy - s(2), s(1), s(2), 110, 110, 125)
                for (cy2 in cy + s(1) until cy + s(6)) {
                    setPixel(d, sz, cx - s(10), cy2, bandCol[0], bandCol[1], bandCol[2])
                }
            }
            1 -> {
                val gold = intArrayOf(245, 205, 65); val goldDark = intArrayOf(180, 140, 35)
                val goldHi = intArrayOf(255, 235, 150)
                fillRect(d, sz, cx - s(9), cy - s(9), s(18) + 1, s(2), gold[0], gold[1], gold[2])
                fillRect(d, sz, cx - s(9), cy - s(8), s(18) + 1, 1, goldDark[0], goldDark[1], goldDark[2])
                fillRect(d, sz, cx - s(8), cy - s(9), s(16) + 1, 1, goldHi[0], goldHi[1], goldHi[2])
                val peakY = cy - s(13); val bandTop = cy - s(9)
                val pxs = intArrayOf(cx - s(8), cx - s(4), cx, cx + s(4), cx + s(8))
                for (px in pxs) {
                    fillTriangle(d, sz, px - s(2), bandTop, px, peakY, px + s(2), bandTop,
                        gold[0], gold[1], gold[2])
                    setPixel(d, sz, px, peakY, 220, 60, 80)
                    setPixel(d, sz, px, peakY + 1, 180, 40, 60)
                }
                setPixel(d, sz, cx - 1, cy - s(9) + 1, 80, 180, 230)
                setPixel(d, sz, cx, cy - s(9) + 1, 120, 210, 255)
                setPixel(d, sz, cx + 1, cy - s(9) + 1, 80, 180, 230)
            }
            else -> {
                val hatCol = intArrayOf(30, 25, 32); val trim = intArrayOf(215, 180, 70)
                fillRect(d, sz, cx - s(12), cy - s(8), s(24) + 1, 1, hatCol[0], hatCol[1], hatCol[2])
                fillRect(d, sz, cx - s(11), cy - s(9), s(22) + 1, 1, hatCol[0], hatCol[1], hatCol[2])
                fillRect(d, sz, cx - s(9), cy - s(10), s(18) + 1, 1, hatCol[0], hatCol[1], hatCol[2])
                fillTriangle(d, sz, cx - s(8), cy - s(10), cx, cy - s(15), cx + s(8), cy - s(10),
                    hatCol[0], hatCol[1], hatCol[2])
                fillRect(d, sz, cx - s(12), cy - s(8) + 1, s(24) + 1, 1, trim[0], trim[1], trim[2])
                setPixel(d, sz, cx - s(1), cy - s(12), 230, 230, 220)
                setPixel(d, sz, cx, cy - s(12), 230, 230, 220)
                setPixel(d, sz, cx - s(1), cy - s(11), 230, 230, 220)
                setPixel(d, sz, cx, cy - s(11), 230, 230, 220)
            }
        }
    }

    private fun renderDemon(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                            expression: String, accessories: Boolean, sc: SubjectColors,
                            rng: SeededRNG, eyeSpacing: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        fillEllipse(d, sz, cx, cy, s(9), s(10), sc.skin[0], sc.skin[1], sc.skin[2])
        fillTriangle(d, sz, cx - s(2), cy + s(8), cx, cy + s(12), cx + s(2), cy + s(8),
            sc.hair[0], sc.hair[1], sc.hair[2])
        fillTriangle(d, sz, cx - s(8), cy - s(5), cx - s(5), cy - s(14), cx - s(4), cy - s(6),
            sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        fillTriangle(d, sz, cx + s(8), cy - s(5), cx + s(5), cy - s(14), cx + s(4), cy - s(6),
            sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        setPixel(d, sz, cx - s(5), cy - s(14), sc.accent[0], sc.accent[1], sc.accent[2])
        setPixel(d, sz, cx + s(5), cy - s(14), sc.accent[0], sc.accent[1], sc.accent[2])
        val eyeY = cy - s(2)
        val eLx = cx - s(4) + eyeSpacing; val eRx = cx + s(4) + eyeSpacing
        fillEllipse(d, sz, eLx, eyeY, s(2), s(1), sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eRx, eyeY, s(2), s(1), sc.eye[0], sc.eye[1], sc.eye[2])
        setPixel(d, sz, eLx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
        setPixel(d, sz, eRx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
        when (expression) {
            "angry" -> for (i in 0 until s(4)) {
                setPixel(d, sz, eLx - s(2) + i, eyeY - s(3) + (i * 0.4).toInt(), sc.hair[0], sc.hair[1], sc.hair[2])
                setPixel(d, sz, eRx + s(2) - i, eyeY - s(3) + (i * 0.4).toInt(), sc.hair[0], sc.hair[1], sc.hair[2])
            }
            "surprised" -> for (i in 0 until s(3)) {
                setPixel(d, sz, eLx - s(1) + i, eyeY - s(3), sc.hair[0], sc.hair[1], sc.hair[2])
                setPixel(d, sz, eRx - s(1) + i, eyeY - s(3), sc.hair[0], sc.hair[1], sc.hair[2])
            }
        }
        setPixel(d, sz, cx, cy + s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        val my = cy + s(4)
        when (expression) {
            "happy" -> {
                fillRect(d, sz, cx - s(3), my, s(6), s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                for (i in -s(3)..s(3)) {
                    val curve = if (abs(i) > s(1)) -1 else 0
                    setPixel(d, sz, cx + i, my + s(2) + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                }
                setPixel(d, sz, cx - s(2), my, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, cx + s(2), my, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, cx - s(2), my + 1, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, cx + s(2), my + 1, sc.skin[0], sc.skin[1], sc.skin[2])
            }
            "angry" -> {
                fillRect(d, sz, cx - s(3), my, s(6), s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                var i = -s(2)
                while (i <= s(2)) {
                    setPixel(d, sz, cx + i, my, sc.skin[0], sc.skin[1], sc.skin[2])
                    i += 2
                }
            }
            "surprised" -> fillEllipse(d, sz, cx, my + s(1), s(2), s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
            else -> {
                for (i in -s(2)..s(2)) setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
                setPixel(d, sz, cx - s(2), my + 1, sc.skin[0], sc.skin[1], sc.skin[2])
                setPixel(d, sz, cx + s(2), my + 1, sc.skin[0], sc.skin[1], sc.skin[2])
            }
        }
        if (accessories) {
            for (i in 0 until 8) {
                val fx = rng.integer(cx - s(10), cx + s(10))
                val fy = rng.integer(cy + s(6), cy + s(12))
                val bright = rng.random()
                setPixel(d, sz, fx, fy, (255 * bright).toInt(), (150 * bright).toInt(), (30 * bright).toInt())
                setPixel(d, sz, fx, fy - 1, (255 * bright * 0.7f).toInt(), (100 * bright * 0.7f).toInt(), 0)
            }
        }
    }

    private fun renderApe(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                          expression: String, accessories: Boolean, sc: SubjectColors,
                          rng: SeededRNG, eyeSpacing: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        fillEllipse(d, sz, cx, cy, s(11), s(10), sc.hair[0], sc.hair[1], sc.hair[2])
        fillTriangle(d, sz, cx - s(5), cy - s(8), cx, cy - s(13), cx + s(5), cy - s(8),
            sc.hair[0], sc.hair[1], sc.hair[2])
        fillEllipse(d, sz, cx, cy + s(2), s(8), s(7), sc.skin[0], sc.skin[1], sc.skin[2])
        fillTriangle(d, sz, cx - s(3), cy - s(5), cx, cy - s(1), cx + s(3), cy - s(5),
            sc.hair[0], sc.hair[1], sc.hair[2])
        fillRect(d, sz, cx - s(7), cy - s(4), s(4), s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        fillRect(d, sz, cx + s(3), cy - s(4), s(4), s(2), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        val eyeY = cy - s(2)
        val eLx = cx - s(4) + eyeSpacing; val eRx = cx + s(4) + eyeSpacing
        fillEllipse(d, sz, eLx, eyeY, s(2), s(1), sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eRx, eyeY, s(2), s(1), sc.eye[0], sc.eye[1], sc.eye[2])
        setPixel(d, sz, eLx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
        setPixel(d, sz, eRx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
        fillEllipse(d, sz, cx, cy + s(4), s(5), s(3), sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        setPixel(d, sz, cx - s(1), cy + s(3), sc.pupil[0], sc.pupil[1], sc.pupil[2])
        setPixel(d, sz, cx + s(1), cy + s(3), sc.pupil[0], sc.pupil[1], sc.pupil[2])
        val my = cy + s(6)
        when (expression) {
            "happy" -> for (i in -s(4)..s(4)) {
                val curve = if (abs(i) > s(2)) -1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "angry" -> {
                fillRect(d, sz, cx - s(4), my, s(8), s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                var i = -s(3)
                while (i <= s(3)) {
                    setPixel(d, sz, cx + i, my, sc.eye[0], sc.eye[1], sc.eye[2]); i += 2
                }
                for (j in 0 until s(4)) {
                    setPixel(d, sz, eLx - s(2) + j, eyeY - s(3) + (j * 0.4).toInt(), sc.pupil[0], sc.pupil[1], sc.pupil[2])
                    setPixel(d, sz, eRx + s(2) - j, eyeY - s(3) + (j * 0.4).toInt(), sc.pupil[0], sc.pupil[1], sc.pupil[2])
                }
            }
            "surprised" -> fillEllipse(d, sz, cx, my, s(2), s(1), sc.mouth[0], sc.mouth[1], sc.mouth[2])
            else -> fillRect(d, sz, cx - s(4), my, s(8) + 1, 1, sc.mouth[0], sc.mouth[1], sc.mouth[2])
        }
        if (accessories) renderApeGlasses(d, sz, eLx, eRx, eyeY, scaleF, rng, sc)
    }

    private fun renderApeGlasses(d: IntArray, sz: Int, eLx: Int, eRx: Int, eyeY: Int,
                                 scaleF: Double, rng: SeededRNG, sc: SubjectColors) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        val glassType = rng.integer(0, 3)
        val frameColors = listOf(
            intArrayOf(25, 25, 30), intArrayOf(220, 180, 60),
            intArrayOf(110, 70, 35), intArrayOf(200, 40, 40))
        val darkLenses = listOf(
            intArrayOf(25, 25, 35), intArrayOf(35, 70, 45),
            intArrayOf(30, 45, 95), intArrayOf(165, 90, 30))
        val frame = frameColors[rng.integer(0, frameColors.size - 1)]
        val isShade = glassType == 2 || glassType == 3
        val lens = if (isShade) darkLenses[rng.integer(0, darkLenses.size - 1)] else intArrayOf(220, 230, 245)

        when (glassType) {
            0 -> {
                fillEllipse(d, sz, eLx, eyeY, s(3), s(2), lens[0], lens[1], lens[2])
                fillEllipse(d, sz, eRx, eyeY, s(3), s(2), lens[0], lens[1], lens[2])
                val rimPts = arrayOf(
                    intArrayOf(-s(3), 0), intArrayOf(s(3), 0), intArrayOf(0, -s(2)), intArrayOf(0, s(2)),
                    intArrayOf(-s(2), -s(1)), intArrayOf(s(2), -s(1)), intArrayOf(-s(2), s(1)), intArrayOf(s(2), s(1)),
                    intArrayOf(-s(3), -s(1)), intArrayOf(s(3), -s(1)), intArrayOf(-s(3), s(1)), intArrayOf(s(3), s(1)))
                for (p in rimPts) {
                    setPixel(d, sz, eLx + p[0], eyeY + p[1], frame[0], frame[1], frame[2])
                    setPixel(d, sz, eRx + p[0], eyeY + p[1], frame[0], frame[1], frame[2])
                }
                for (x in eLx + s(3) + 1 until eRx - s(3))
                    setPixel(d, sz, x, eyeY, frame[0], frame[1], frame[2])
                setPixel(d, sz, eLx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                setPixel(d, sz, eRx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
            }
            1 -> {
                fillRect(d, sz, eLx - s(3), eyeY - s(2), s(6) + 1, s(4) + 1, lens[0], lens[1], lens[2])
                fillRect(d, sz, eRx - s(3), eyeY - s(2), s(6) + 1, s(4) + 1, lens[0], lens[1], lens[2])
                strokeRect(d, sz, eLx - s(3), eyeY - s(2), s(6) + 1, s(4) + 1, frame[0], frame[1], frame[2])
                strokeRect(d, sz, eRx - s(3), eyeY - s(2), s(6) + 1, s(4) + 1, frame[0], frame[1], frame[2])
                for (x in eLx + s(3) + 1 until eRx - s(3))
                    setPixel(d, sz, x, eyeY, frame[0], frame[1], frame[2])
                setPixel(d, sz, eLx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                setPixel(d, sz, eRx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
            }
            2 -> {
                fillEllipse(d, sz, eLx, eyeY, s(3), s(2), lens[0], lens[1], lens[2])
                fillEllipse(d, sz, eRx, eyeY, s(3), s(2), lens[0], lens[1], lens[2])
                setPixel(d, sz, eLx, eyeY + s(2), lens[0], lens[1], lens[2])
                setPixel(d, sz, eRx, eyeY + s(2), lens[0], lens[1], lens[2])
                for (x in eLx - s(3)..eLx + s(3)) setPixel(d, sz, x, eyeY - s(2), frame[0], frame[1], frame[2])
                for (x in eRx - s(3)..eRx + s(3)) setPixel(d, sz, x, eyeY - s(2), frame[0], frame[1], frame[2])
                for (x in eLx + s(3)..eRx - s(3)) setPixel(d, sz, x, eyeY - s(1), frame[0], frame[1], frame[2])
                setPixel(d, sz, eLx - 1, eyeY - 1, 200, 220, 240)
                setPixel(d, sz, eRx - 1, eyeY - 1, 200, 220, 240)
            }
            else -> {
                fillRect(d, sz, eLx - s(3), eyeY - s(2), s(6) + 1, s(4) + 1, lens[0], lens[1], lens[2])
                fillRect(d, sz, eRx - s(3), eyeY - s(2), s(6) + 1, s(4) + 1, lens[0], lens[1], lens[2])
                fillRect(d, sz, eLx - s(3), eyeY - s(2), s(6) + 1, 1, frame[0], frame[1], frame[2])
                fillRect(d, sz, eRx - s(3), eyeY - s(2), s(6) + 1, 1, frame[0], frame[1], frame[2])
                fillRect(d, sz, eLx - s(3), eyeY - s(2) - 1, s(6) + 1, 1, frame[0], frame[1], frame[2])
                fillRect(d, sz, eRx - s(3), eyeY - s(2) - 1, s(6) + 1, 1, frame[0], frame[1], frame[2])
                for (y in eyeY - s(2)..eyeY + s(2)) {
                    setPixel(d, sz, eLx - s(3), y, frame[0], frame[1], frame[2])
                    setPixel(d, sz, eLx + s(3), y, frame[0], frame[1], frame[2])
                    setPixel(d, sz, eRx - s(3), y, frame[0], frame[1], frame[2])
                    setPixel(d, sz, eRx + s(3), y, frame[0], frame[1], frame[2])
                }
                fillRect(d, sz, eLx - s(3), eyeY + s(2), s(6) + 1, 1, frame[0], frame[1], frame[2])
                fillRect(d, sz, eRx - s(3), eyeY + s(2), s(6) + 1, 1, frame[0], frame[1], frame[2])
                for (x in eLx + s(3)..eRx - s(3)) setPixel(d, sz, x, eyeY - 1, frame[0], frame[1], frame[2])
                setPixel(d, sz, eLx - 1, eyeY - 1, 200, 220, 240)
                setPixel(d, sz, eLx, eyeY - 1, 180, 200, 220)
                setPixel(d, sz, eRx - 1, eyeY - 1, 200, 220, 240)
                setPixel(d, sz, eRx, eyeY - 1, 180, 200, 220)
            }
        }
    }

    private fun renderKetchup(d: IntArray, sz: Int, cx: Int, cy: Int, scaleF: Double,
                              expression: String, accessories: Boolean, sc: SubjectColors,
                              eyeSpacing: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        val capTop = cy - s(16); val capBottom = cy - s(11)
        fillRect(d, sz, cx - s(5), capTop, s(10), capBottom - capTop, sc.hair[0], sc.hair[1], sc.hair[2])
        fillRect(d, sz, cx - s(4), capTop, s(8), 1, 90, 90, 95)
        var y = capTop + s(2)
        while (y < capBottom) { fillRect(d, sz, cx - s(5), y, s(10), 1, 20, 20, 25); y += 2 }

        val neckTop = capBottom; val neckBottom = cy - s(6)
        fillRect(d, sz, cx - s(4), neckTop, s(8), neckBottom - neckTop, sc.skin[0], sc.skin[1], sc.skin[2])
        fillRect(d, sz, cx - s(5), neckTop, s(10), 1, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])

        val eyeY = cy + s(1)
        val eLx = cx - s(3) + eyeSpacing; val eRx = cx + s(3) + eyeSpacing
        fillEllipse(d, sz, eLx, eyeY, s(2), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        fillEllipse(d, sz, eRx, eyeY, s(2), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
        setPixel(d, sz, eLx - s(2), eyeY, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        setPixel(d, sz, eLx + s(2), eyeY, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        setPixel(d, sz, eRx - s(2), eyeY, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        setPixel(d, sz, eRx + s(2), eyeY, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
        setPixel(d, sz, eLx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
        setPixel(d, sz, eRx, eyeY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
        setPixel(d, sz, eLx - 1, eyeY - 1, 255, 255, 255)
        setPixel(d, sz, eRx - 1, eyeY - 1, 255, 255, 255)

        when (expression) {
            "angry" -> for (i in 0 until s(3)) {
                setPixel(d, sz, eLx - s(2) + i, eyeY - s(2) + (i * 0.4).toInt(), sc.pupil[0], sc.pupil[1], sc.pupil[2])
                setPixel(d, sz, eRx + s(2) - i, eyeY - s(2) + (i * 0.4).toInt(), sc.pupil[0], sc.pupil[1], sc.pupil[2])
            }
            "surprised" -> for (i in 0 until s(3)) {
                setPixel(d, sz, eLx - s(1) + i, eyeY - s(3), sc.pupil[0], sc.pupil[1], sc.pupil[2])
                setPixel(d, sz, eRx - s(1) + i, eyeY - s(3), sc.pupil[0], sc.pupil[1], sc.pupil[2])
            }
        }

        val my = cy + s(5)
        when (expression) {
            "happy" -> for (i in -s(2)..s(2)) {
                val curve = if (abs(i) > s(1)) -1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "angry" -> for (i in -s(2)..s(2)) {
                val curve = if (abs(i) > s(1)) 1 else 0
                setPixel(d, sz, cx + i, my + curve, sc.mouth[0], sc.mouth[1], sc.mouth[2])
            }
            "surprised" -> fillEllipse(d, sz, cx, my, s(1), s(1), sc.mouth[0], sc.mouth[1], sc.mouth[2])
            else -> for (i in -s(2)..s(2)) setPixel(d, sz, cx + i, my, sc.mouth[0], sc.mouth[1], sc.mouth[2])
        }

        if (accessories) {
            setPixel(d, sz, cx, capBottom + 1, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
            setPixel(d, sz, cx, capBottom + 2, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
            setPixel(d, sz, cx - s(3), capTop + 1, 220, 220, 220)
        }
    }

    private fun renderImpl(canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
                           seed: Int, palette: Palette, time: Float) {
        val sz = PixelArtUtil.ps(params, "gridSize", "48").toIntOrNull()?.coerceIn(16, 128) ?: 48
        val subject = PixelArtUtil.ps(params, "subject", "cat")
        val expression = PixelArtUtil.ps(params, "expression", "neutral")
        val accessories = PixelArtUtil.pb(params, "accessories", true)
        val bgStyle = PixelArtUtil.ps(params, "bgStyle", "patterned")
        val symmetry = PixelArtUtil.pb(params, "symmetry", true)
        val animated = PixelArtUtil.pb(params, "animated", true)
        val bobbing = PixelArtUtil.pb(params, "bobbing", true)
        val speed = PixelArtUtil.pf(params, "animSpeed", 0.5f)

        val animTime = if (animated && time > 0f) time * speed else 0f

        val rng = SeededRNG(seed)
        val sc = getSubjectColors(subject, palette, rng)

        val d = IntArray(sz * sz)

        renderBackground(d, sz, subject, bgStyle, palette, SeededRNG(seed + 1000))
        renderBody(d, sz, subject, accessories, sc, SeededRNG(seed + 2000))
        renderPortrait(d, sz, subject, expression, accessories, sc, SeededRNG(seed), seed)

        if (symmetry) {
            val half = sz / 2
            for (y in 0 until sz) {
                for (x in 0 until half) {
                    val mirrorX = sz - 1 - x
                    d[y * sz + mirrorX] = d[y * sz + x]
                }
            }
        }

        if (accessories && subject == "demon") drawDemonPitchfork(d, sz, seed)

        if (animTime > 0f) applyAnimation(d, sz, subject, expression, accessories, bobbing,
            sc, seed, animTime, palette, bgStyle)

        PixelArtUtil.blitNearest(canvas, bitmap, d, sz)
    }

    private fun drawDemonPitchfork(d: IntArray, sz: Int, seed: Int) {
        val cx = sz / 2
        val cy = computeCy(sz, "demon")
        val scaleF = sz / 48.0
        fun s(v: Int) = (v * scaleF).roundToInt()
        val neckY = cy + s(8); val torsoTop = neckY + s(2)

        val accRng = SeededRNG(seed + 3000)
        val pfType = accRng.integer(2, 4)
        val wood = intArrayOf(80, 50, 25); val woodHi = intArrayOf(130, 85, 45)
        val metal = intArrayOf(80, 80, 90); val metalHi = intArrayOf(170, 170, 185)

        val shaftX = cx - s(17)
        val shaftTop = torsoTop - s(4); val shaftBot = torsoTop + s(17)
        for (y in shaftTop..shaftBot) {
            setPixel(d, sz, shaftX, y, wood[0], wood[1], wood[2])
            setPixel(d, sz, shaftX + 1, y, woodHi[0], woodHi[1], woodHi[2])
        }
        for (y in torsoTop + s(13)..torsoTop + s(15)) {
            setPixel(d, sz, shaftX, y, 35, 25, 15)
            setPixel(d, sz, shaftX + 1, y, 35, 25, 15)
        }
        val crossY = shaftTop - 1
        fillRect(d, sz, shaftX - s(3), crossY, s(7), 1, metal[0], metal[1], metal[2])
        val prongTop = crossY - s(4)
        fun drawProng(px: Int) {
            for (y in prongTop + 1..crossY) setPixel(d, sz, px, y, metal[0], metal[1], metal[2])
            setPixel(d, sz, px, prongTop, metalHi[0], metalHi[1], metalHi[2])
        }
        when (pfType) {
            2 -> { drawProng(shaftX - s(2)); drawProng(shaftX + s(2) + 1) }
            3 -> { drawProng(shaftX - s(3)); drawProng(shaftX); drawProng(shaftX + s(3)) }
            else -> {
                drawProng(shaftX - s(3)); drawProng(shaftX - s(1))
                drawProng(shaftX + s(1) + 1); drawProng(shaftX + s(3) + 1)
            }
        }
    }

    private fun applyAnimation(d: IntArray, sz: Int, subject: String, expression: String,
                               accessories: Boolean, bobbing: Boolean, sc: SubjectColors,
                               seed: Int, animTime: Float, palette: Palette, bgStyle: String) {
        val cx = sz / 2
        val cy = computeCy(sz, subject)
        val scaleF = sz / 48.0
        fun s(v: Int) = (v * scaleF).roundToInt()
        val eyeSpacing = SeededRNG(seed).integer(-1, 1)

        val headBob = if (bobbing) (sin(animTime * 1.2) * 0.8).roundToInt() else 0
        val lookCycle = animTime * 0.3
        val lookPhase = lookCycle - floor(lookCycle)
        val eyeLookX = if (lookPhase < 0.3) -1 else if (lookPhase < 0.6) 1 else 0
        val eyeLookY = (sin(animTime * 0.7) * 0.5).roundToInt()
        val blinkCycle = animTime * 0.4
        val blinkFrac = blinkCycle - floor(blinkCycle)
        val isBlinking = blinkFrac < 0.04
        val mouthOpen = sin(animTime * 0.8) > 0.85

        // Head bob — shift portrait pixels vertically
        if (headBob != 0) {
            val snap = d.copyOf()
            renderBackground(d, sz, subject, bgStyle, palette, SeededRNG(seed + 1000))
            for (y in 0 until sz) {
                val srcY = y - headBob
                if (srcY < 0 || srcY >= sz) continue
                for (x in 0 until sz) {
                    val srcPi = srcY * sz + x; val dstPi = y * sz + x
                    if (snap[srcPi] != d[dstPi]) d[dstPi] = snap[srcPi]
                }
            }
        }

        val eyeY = (when (subject) {
            "alien" -> cy - s(3)
            "android" -> cy - s(1)
            "ketchup" -> cy + s(1)
            else -> cy - s(2)
        }) + headBob
        val eLx = (if (subject == "ketchup") cx - s(3) else cx - s(4)) + eyeSpacing
        val eRx = (if (subject == "ketchup") cx + s(3) else cx + s(4)) + eyeSpacing

        if (isBlinking) {
            val ew = if (subject == "alien") s(4) else if (subject == "ketchup") s(2) else s(3)
            val eh = if (subject == "skull" || subject == "android") s(3) else s(2)
            val lidR = if (subject == "ketchup") sc.body[0] else sc.skin[0]
            val lidG = if (subject == "ketchup") sc.body[1] else sc.skin[1]
            val lidB = if (subject == "ketchup") sc.body[2] else sc.skin[2]
            for (ey in eyeY - eh..eyeY + eh) {
                for (ex in eLx - ew..eLx + ew) setPixel(d, sz, ex, ey, lidR, lidG, lidB)
                for (ex in eRx - ew..eRx + ew) setPixel(d, sz, ex, ey, lidR, lidG, lidB)
            }
            for (ex in eLx - s(2)..eLx + s(2)) setPixel(d, sz, ex, eyeY, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
            for (ex in eRx - s(2)..eRx + s(2)) setPixel(d, sz, ex, eyeY, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
        } else if (eyeLookX != 0 || eyeLookY != 0) {
            when (subject) {
                "cat" -> for (py in eyeY - s(1)..eyeY + s(1)) {
                    setPixel(d, sz, eLx + eyeLookX, py + eyeLookY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                    setPixel(d, sz, eRx + eyeLookX, py + eyeLookY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                }
                "punk" -> {
                    setPixel(d, sz, eLx + eyeLookX, eyeY + eyeLookY, sc.eye[0], sc.eye[1], sc.eye[2])
                    setPixel(d, sz, eRx + eyeLookX, eyeY + eyeLookY, sc.eye[0], sc.eye[1], sc.eye[2])
                }
                "alien" -> {
                    setPixel(d, sz, eLx + eyeLookX, eyeY + eyeLookY, sc.accent[0], sc.accent[1], sc.accent[2])
                    setPixel(d, sz, eRx + eyeLookX, eyeY + eyeLookY, sc.accent[0], sc.accent[1], sc.accent[2])
                }
                "demon", "ape" -> {
                    setPixel(d, sz, eLx + eyeLookX, eyeY + eyeLookY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                    setPixel(d, sz, eRx + eyeLookX, eyeY + eyeLookY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                }
                "ketchup" -> {
                    fillEllipse(d, sz, eLx, eyeY, s(2), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
                    fillEllipse(d, sz, eRx, eyeY, s(2), s(2), sc.eye[0], sc.eye[1], sc.eye[2])
                    setPixel(d, sz, eLx + eyeLookX, eyeY + eyeLookY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                    setPixel(d, sz, eRx + eyeLookX, eyeY + eyeLookY, sc.pupil[0], sc.pupil[1], sc.pupil[2])
                }
            }
        }

        if (mouthOpen && !isBlinking) {
            val my = (cy + (if (subject == "ape") s(6) else if (subject == "ketchup") s(5) else s(4))) + headBob
            when (subject) {
                "cat", "alien", "ketchup" -> fillEllipse(d, sz, cx, my + 1, s(1), s(1), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                "punk" -> fillRect(d, sz, cx - s(2), my, s(4), s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                "demon" -> {
                    fillRect(d, sz, cx - s(4), my, s(8), s(3), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                    var i = -s(3)
                    while (i <= s(3)) { setPixel(d, sz, cx + i, my, sc.skin[0], sc.skin[1], sc.skin[2]); i += 2 }
                }
                "ape" -> {
                    fillRect(d, sz, cx - s(3), my, s(6), s(2), sc.mouth[0], sc.mouth[1], sc.mouth[2])
                    var i = -s(2)
                    while (i <= s(2)) { setPixel(d, sz, cx + i, my, sc.eye[0], sc.eye[1], sc.eye[2]); i += 2 }
                }
            }
        }

        applySubjectAnimation(d, sz, subject, expression, accessories, sc, seed, animTime,
            cx, cy, scaleF, headBob, eLx, eRx, eyeY)
    }

    private fun applySubjectAnimation(d: IntArray, sz: Int, subject: String, expression: String,
                                      accessories: Boolean, sc: SubjectColors, seed: Int,
                                      animTime: Float, cx: Int, cy: Int, scaleF: Double,
                                      headBob: Int, eLx: Int, eRx: Int, eyeY: Int) {
        fun s(v: Int) = (v * scaleF).roundToInt()
        when (subject) {
            "android" -> {
                val pulse = (180 + sin(animTime * 4) * 75).toInt()
                setPixel(d, sz, eLx, eyeY, pulse, (pulse * 0.6).toInt(), (pulse * 0.2).toInt())
                setPixel(d, sz, eRx, eyeY, pulse, (pulse * 0.6).toInt(), (pulse * 0.2).toInt())
                if (accessories && sin(animTime * 5) > 0.3) {
                    val glow = (150 + sin(animTime * 8) * 105).toInt()
                    val antennaType = SeededRNG(seed + 4000).integer(0, 1)
                    if (antennaType == 0) {
                        setPixel(d, sz, cx - s(3), cy - s(12) + headBob, glow, (glow * 0.4).toInt(), 0)
                        setPixel(d, sz, cx + s(3), cy - s(12) + headBob, glow, (glow * 0.4).toInt(), 0)
                    } else {
                        setPixel(d, sz, cx - 1, cy - s(14) + headBob, glow, (glow * 0.4).toInt(), 0)
                        setPixel(d, sz, cx - 2, cy - s(14) + headBob, glow, (glow * 0.4).toInt(), 0)
                    }
                }
            }
            "demon" -> if (accessories) {
                val flameRng = SeededRNG(seed + (animTime * 5).toInt())
                for (i in 0 until 6) {
                    val fx = flameRng.integer(cx - s(11), cx + s(11))
                    val fy = flameRng.integer(cy + s(8) + headBob, cy + s(14) + headBob)
                    val bright = flameRng.random()
                    setPixel(d, sz, fx, fy, (255 * bright).toInt(), (120 * bright).toInt(), 0)
                    setPixel(d, sz, fx, fy - 1, (255 * bright * 0.6f).toInt(), (80 * bright * 0.6f).toInt(), 0)
                }
            }
            "alien" -> if (accessories) {
                val pulse = (sin(animTime * 4) + 1) * 0.5
                val glow = (150 + pulse * 105).toInt()
                val antennaType = SeededRNG(seed + 4000).integer(0, 1)
                if (antennaType == 0) {
                    setPixel(d, sz, cx - s(3), cy - s(12) - s(5) - 1 + headBob, glow, 255, glow)
                    setPixel(d, sz, cx + s(3), cy - s(12) - s(5) - 1 + headBob, glow, 255, glow)
                } else {
                    setPixel(d, sz, cx - 1, cy - s(12) - s(7) - 1 + headBob, glow, 255, glow)
                    setPixel(d, sz, cx, cy - s(12) - s(7) - 1 + headBob, glow, 255, glow)
                }
            }
            "cat" -> {
                if (accessories) {
                    val whiskerShift = (sin(animTime * 2) * 0.8).roundToInt()
                    val wy = cy + s(3) + headBob + whiskerShift
                    for (i in 0 until 3) {
                        setPixel(d, sz, cx - s(5) - i, wy - 1 + i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                        setPixel(d, sz, cx + s(5) + i, wy - 1 + i, sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
                    }
                }
                val neckY2 = cy + s(9) + headBob
                val torsoTop2 = neckY2 + s(2)
                val wagAngle = sin(animTime * 3) * 0.6
                for (i in 0 until s(8)) {
                    val t = i.toDouble() / s(8)
                    val swing = wagAngle * t * t
                    val tx = cx + s(8) + (i * 0.5).toInt() + (swing * s(4)).roundToInt()
                    val ty = torsoTop2 + s(10) - i
                    setPixel(d, sz, tx, ty, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                    setPixel(d, sz, tx + 1, ty, sc.bodyDark[0], sc.bodyDark[1], sc.bodyDark[2])
                }
                val tipSwing = (wagAngle * s(4)).roundToInt()
                setPixel(d, sz, cx + s(8) + s(4) + tipSwing, torsoTop2 + s(2) + headBob,
                    sc.skinDark[0], sc.skinDark[1], sc.skinDark[2])
            }
            "skull" -> if (expression == "angry" || sin(animTime * 2) > 0.6) {
                val glowI = (200 + sin(animTime * 6) * 55).toInt()
                setPixel(d, sz, eLx, eyeY, glowI, 30, 30)
                setPixel(d, sz, eRx, eyeY, glowI, 30, 30)
            }
            "punk" -> {
                val browRaise = if (sin(animTime * 1.5) > 0.7) -1 else 0
                if (browRaise != 0) {
                    val browY = eyeY - s(2) + browRaise
                    for (i in 0 until s(3)) setPixel(d, sz, eRx + s(1) - i, browY, 10, 10, 10)
                }
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        renderImpl(canvas, bitmap, params, seed, palette, time)
    }
}
