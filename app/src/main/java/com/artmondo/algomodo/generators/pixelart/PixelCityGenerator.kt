package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class PixelCityGenerator : Generator {
    override val id = "pixel-city"
    override val family = "pixel-art"
    override val styleName = "Pixel City"
    override val definition = "A procedurally generated city with skyline, top-down, perspective, and irregular top-down modes — animated windows, moving cars, atmospheric lighting."
    override val algorithmNotes = "Skyline mode: buildings with flickering windows, neon accents, shooting stars, scrolling clouds, and moving cars. Top-down mode: city blocks with road markings, cars, and emergency vehicle. Perspective mode: one-point perspective street view with buildings receding toward a vanishing point, sidewalks, streetlights, and depth-scaled cars. Irregular mode: organic top-down layout with mixed shapes (rectangles, circles, rhombuses, trapezoids, L-shapes) connected by winding road segments."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Mode", "mode", ParamGroup.COMPOSITION, "City view perspective",
            listOf("skyline", "top-down", "perspective", "irregular"), "skyline"),
        Parameter.NumberParam("Building Density", "buildingDensity", ParamGroup.COMPOSITION, "How densely packed the buildings are", 0.2f, 1.0f, 0.1f, 0.6f),
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Window Probability", "windowProbability", ParamGroup.TEXTURE, "Chance of a window being lit", 0f, 1f, 0.1f, 0.6f),
        Parameter.SelectParam("Time of Day", "timeOfDay", ParamGroup.COLOR, "Affects background color and lighting",
            listOf("night", "dusk", "day"), "night"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "mode" to "skyline", "buildingDensity" to 0.6f, "gridSize" to 64f,
        "windowProbability" to 0.6f, "timeOfDay" to "night",
        "animSpeed" to 1f, "reactivity" to 0f
    )

    // --- Layout data classes ---
    private data class Building(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val colorIdx: Int, val hasAntenna: Boolean, val windowPhaseOffset: Float
    )
    private data class SkylineLayout(
        val buildings: List<Building>, val baseline: Int,
        val starX: IntArray, val starY: IntArray, val starBright: IntArray, val numStars: Int
    )
    private data class TopBlock(val x: Int, val y: Int, val w: Int, val h: Int, val colorIdx: Int)
    private data class TopDownLayout(
        val blocks: List<TopBlock>, val roadH: IntArray, val roadV: IntArray, val roadWidth: Int
    )
    private data class IrregularBlock(
        val cx: Int, val cy: Int, val w: Int, val h: Int,
        val shape: String, val angle: Float, val colorIdx: Int, val windowPhaseOffset: Float
    )
    private data class IrregularLayout(
        val blocks: List<IrregularBlock>, val roadH: IntArray, val roadV: IntArray, val roadWidth: Int
    )
    private data class PerspBuilding(
        val side: String, val depth: Float, val width: Float, val height: Float,
        val colorIdx: Int, val windowPhaseOffset: Float, val hasAntenna: Boolean
    )
    private data class PerspectiveLayout(
        val buildings: List<PerspBuilding>,
        val vpx: Int, val vpy: Int, val roadHalfW: Int, val lampDepths: FloatArray
    )

    companion object {
        @Volatile private var cacheKey: String = ""
        @Volatile private var cacheSkyline: SkylineLayout? = null
        @Volatile private var cacheTopDown: TopDownLayout? = null
        @Volatile private var cachePerspective: PerspectiveLayout? = null
        @Volatile private var cacheIrregular: IrregularLayout? = null
    }

    // --- Pixel setter ---
    private fun setPx(pixels: IntArray, sz: Int, x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x < 0 || x >= sz || y < 0 || y >= sz) return
        pixels[y * sz + x] = PixelArtUtil.rgb(r, g, b)
    }

    private fun bgColor(tod: String): IntArray = when (tod) {
        "night" -> intArrayOf(10, 10, 30)
        "dusk" -> intArrayOf(60, 30, 50)
        else -> intArrayOf(140, 180, 220)
    }

    // --- Layout builders ---
    private fun buildSkyline(sz: Int, density: Float, rng: SeededRNG): SkylineLayout {
        val baseline = (sz * 0.72f).toInt()
        val buildings = mutableListOf<Building>()
        var x = 0
        var idx = 0
        while (x < sz) {
            val bw = rng.integer(3, max(4, (sz * 0.13f).toInt()))
            val maxH = (sz * 0.6f).toInt()
            val minH = (sz * 0.08f).toInt()
            val bh = if (rng.random() < density)
                rng.integer((maxH * 0.3f).toInt(), maxH)
            else
                rng.integer(minH, (maxH * 0.4f).toInt())
            val top = baseline - bh
            buildings.add(Building(
                x, top, bw, bh,
                idx + 1,
                bh > maxH * 0.5f && rng.random() < 0.4f,
                rng.random() * 100f
            ))
            x += bw + rng.integer(0, 1)
            idx++
        }
        val numStars = (sz * sz * 0.008f).toInt()
        val starX = IntArray(numStars)
        val starY = IntArray(numStars)
        val starBright = IntArray(numStars)
        for (s in 0 until numStars) {
            starX[s] = rng.integer(0, sz - 1)
            starY[s] = rng.integer(0, (sz * 0.4f).toInt())
            starBright[s] = 160 + rng.integer(0, 95)
        }
        return SkylineLayout(buildings, baseline, starX, starY, starBright, numStars)
    }

    private fun buildTopDown(sz: Int, density: Float, rng: SeededRNG): TopDownLayout {
        val blockSize = max(6, (sz / (3 + density * 4)).toInt())
        val roadWidth = max(2, (blockSize * 0.25f).toInt())
        val blocks = mutableListOf<TopBlock>()
        val roadH = mutableListOf<Int>()
        val roadV = mutableListOf<Int>()
        var idx = 0
        var by = roadWidth
        while (by < sz - blockSize) {
            roadH.add(by - (roadWidth / 2))
            var bx = roadWidth
            while (bx < sz - blockSize) {
                if (by == roadWidth) roadV.add(bx - (roadWidth / 2))
                val bw = min(blockSize, sz - bx - roadWidth)
                val bh = min(blockSize, sz - by - roadWidth)
                if (bw >= 3 && bh >= 3) {
                    blocks.add(TopBlock(bx, by, bw, bh, idx + 1))
                    idx++
                }
                bx += blockSize + roadWidth
            }
            by += blockSize + roadWidth
        }
        roadH.add(sz - roadWidth)
        roadV.add(sz - roadWidth)
        return TopDownLayout(blocks, roadH.toIntArray(), roadV.toIntArray(), roadWidth)
    }

    private fun buildPerspective(sz: Int, density: Float, rng: SeededRNG): PerspectiveLayout {
        val vpx = sz / 2
        val vpy = (sz * 0.58f).toInt()
        val roadHalfW = (sz * 0.32f).toInt()
        val buildings = mutableListOf<PerspBuilding>()
        val numPerSide = 4 + (density * 5f).toInt()
        for (side in listOf("left", "right")) {
            var depth = 0.03f
            var idx = 0
            while (depth < 0.92f && idx < numPerSide) {
                val w = 0.06f + rng.random() * 0.10f
                val h = 0.4f + rng.random() * (density * 0.8f)
                buildings.add(PerspBuilding(
                    side, depth, w, h,
                    idx + 1 + (if (side == "right") numPerSide else 0),
                    rng.random() * 100f,
                    h > 0.6f && rng.random() < 0.4f
                ))
                depth += w * 0.65f + 0.01f + rng.random() * 0.03f
                idx++
            }
        }
        val lampDepths = FloatArray(3) { i -> 0.05f + i * 0.15f }
        return PerspectiveLayout(buildings, vpx, vpy, roadHalfW, lampDepths)
    }

    private fun buildIrregular(sz: Int, density: Float, rng: SeededRNG): IrregularLayout {
        val shapes = listOf("rect", "circle", "rhombus", "trapezoid", "L-shape")
        val blockSize = max(6, (sz / (3 + density * 4)).toInt())
        val roadWidth = max(2, (blockSize * 0.25f).toInt())
        val blocks = mutableListOf<IrregularBlock>()
        val roadH = mutableListOf<Int>()
        val roadV = mutableListOf<Int>()
        var idx = 0
        var by = roadWidth
        while (by < sz - blockSize) {
            roadH.add(by - (roadWidth / 2))
            var bx = roadWidth
            while (bx < sz - blockSize) {
                if (by == roadWidth) roadV.add(bx - (roadWidth / 2))
                val bw = min(blockSize, sz - bx - roadWidth)
                val bh = min(blockSize, sz - by - roadWidth)
                if (bw >= 3 && bh >= 3) {
                    val shape = shapes[rng.integer(0, shapes.size - 1)]
                    blocks.add(IrregularBlock(
                        bx + (bw / 2), by + (bh / 2),
                        bw, bh, shape,
                        if (shape == "rhombus") rng.random() * 0.4f - 0.2f else 0f,
                        idx + 1, rng.random() * 100f
                    ))
                    idx++
                }
                bx += blockSize + roadWidth
            }
            by += blockSize + roadWidth
        }
        roadH.add(sz - roadWidth)
        roadV.add(sz - roadWidth)
        return IrregularLayout(blocks, roadH.toIntArray(), roadV.toIntArray(), roadWidth)
    }

    // --- Skyline render ---
    private fun renderSkyline(
        pixels: IntArray, sz: Int, layout: SkylineLayout,
        windowProb: Float, tod: String, colors: Array<IntArray>, time: Float
    ) {
        val nc = colors.size
        val bg = bgColor(tod)
        // Sky gradient
        for (y in 0 until sz) {
            val skyT = y.toFloat() / sz
            for (x in 0 until sz) {
                val r = (bg[0] * (1f - skyT * 0.3f)).toInt()
                val g = (bg[1] * (1f - skyT * 0.3f)).toInt()
                val b = (bg[2] + (20f - bg[2]) * skyT * 0.5f).toInt()
                pixels[y * sz + x] = PixelArtUtil.rgb(r, g, b)
            }
        }
        // Stars with twinkling
        if (tod != "day") {
            for (s in 0 until layout.numStars) {
                val twinkle = sin(time * 3f + s * 2.37f) * 0.4f + 0.6f
                val flash = if (sin(time * 7f + s * 5.1f) > 0.95f) 1.3f else 1f
                val b = min(255, (layout.starBright[s] * twinkle * flash).toInt())
                setPx(pixels, sz, layout.starX[s], layout.starY[s], b, b, (b * 0.9f).toInt())
            }
        }
        // Shooting star
        if (tod != "day") {
            val shootCycle = time * 0.3f
            val shootPhase = shootCycle - floor(shootCycle)
            if (shootPhase < 0.15f) {
                val t = shootPhase / 0.15f
                val sx = (sz * 0.2f + t * sz * 0.6f).toInt()
                val sy = (sz * 0.05f + t * sz * 0.2f).toInt()
                val brightness = (1f - t) * 255f
                for (i in 0..2) {
                    val v = (brightness * (1f - i * 0.3f)).toInt()
                    val vb = (brightness * (1f - i * 0.4f)).toInt()
                    setPx(pixels, sz, sx - i, sy - (i * 0.3f).toInt(), v, v, vb)
                }
            }
        }
        // Moon with glow
        if (tod != "day") {
            val moonX = (sz * 0.78f).toInt()
            val moonY = (sz * 0.12f).toInt()
            val moonR = max(2, (sz * 0.04f).toInt())
            val glowR = moonR + 2
            for (dy in -glowR..glowR) {
                for (dx in -glowR..glowR) {
                    val dist = sqrt((dx * dx + dy * dy).toFloat())
                    if (dist <= glowR) {
                        val glow = max(0f, 1f - dist / glowR) * 0.3f
                        val mx = moonX + dx; val my = moonY + dy
                        if (mx in 0 until sz && my in 0 until sz) {
                            val idx = my * sz + mx
                            val c = pixels[idx]
                            val r = min(255, ((c ushr 16) and 0xFF) + (60 * glow).toInt())
                            val g = min(255, ((c ushr 8) and 0xFF) + (55 * glow).toInt())
                            val b = min(255, (c and 0xFF) + (40 * glow).toInt())
                            pixels[idx] = PixelArtUtil.rgb(r, g, b)
                        }
                    }
                }
            }
            for (dy in -moonR..moonR) {
                for (dx in -moonR..moonR) {
                    if (dx * dx + dy * dy <= moonR * moonR) {
                        setPx(pixels, sz, moonX + dx, moonY + dy, 240, 235, 200)
                    }
                }
            }
        }
        // Scrolling clouds
        val numClouds = 3
        for (c in 0 until numClouds) {
            val cy = (sz * (0.15f + c * 0.1f)).toInt()
            var cxRaw = (sz * 0.3f * c + time * sz * 0.03f * (c + 1)) % (sz + 20).toFloat()
            if (cxRaw < 0) cxRaw += (sz + 20).toFloat()
            val cx = cxRaw - 10f
            val cw = 4 + c * 2
            val cloudAlpha = if (tod == "night") 0.15f else if (tod == "dusk") 0.25f else 0.5f
            for (dx in 0 until cw) {
                val h = if (dx > 1 && dx < cw - 1) 2 else 1
                for (dy in 0 until h) {
                    val px = (cx + dx).toInt(); val py = cy + dy
                    if (px in 0 until sz && py in 0 until sz) {
                        val idx = py * sz + px
                        val cc = pixels[idx]
                        val r = min(255, ((cc ushr 16) and 0xFF) + (200 * cloudAlpha).toInt())
                        val g = min(255, ((cc ushr 8) and 0xFF) + (200 * cloudAlpha).toInt())
                        val b = min(255, (cc and 0xFF) + (210 * cloudAlpha).toInt())
                        pixels[idx] = PixelArtUtil.rgb(r, g, b)
                    }
                }
            }
        }
        // Buildings
        val baseline = layout.baseline
        for (b in layout.buildings) {
            val bc = colors[b.colorIdx % nc]
            val sideShade = 0.7f
            for (by2 in b.y until baseline) {
                val xmax = min(sz, b.x + b.w)
                for (bx2 in b.x until xmax) {
                    val isEdge = bx2 == b.x || bx2 == b.x + b.w - 1
                    val shade = if (isEdge) sideShade else 1f
                    setPx(pixels, sz, bx2, by2,
                        (bc[0] * shade).toInt(), (bc[1] * shade).toInt(), (bc[2] * shade).toInt())
                }
            }
            // Antenna
            if (b.hasAntenna) {
                val ax = b.x + (b.w / 2)
                setPx(pixels, sz, ax, b.y - 1, bc[0] shr 1, bc[1] shr 1, bc[2] shr 1)
                setPx(pixels, sz, ax, b.y - 2, bc[0] shr 1, bc[1] shr 1, bc[2] shr 1)
                if (sin(time * 4f + b.windowPhaseOffset) > 0.3f) {
                    setPx(pixels, sz, ax, b.y - 3, 255, 30, 30)
                }
            }
            // Windows
            val windowOn = if (tod == "night") intArrayOf(255, 230, 100)
                else if (tod == "dusk") intArrayOf(255, 200, 90)
                else intArrayOf(200, 220, 240)
            val windowOff = if (tod == "night") intArrayOf(40, 40, 60)
                else if (tod == "dusk") intArrayOf(50, 40, 50)
                else intArrayOf(160, 170, 180)
            val neonColors = arrayOf(
                intArrayOf(255, 50, 100), intArrayOf(50, 200, 255),
                intArrayOf(200, 255, 50), intArrayOf(255, 150, 50)
            )
            var wy = b.y + 2
            while (wy < baseline - 1) {
                var wx = b.x + 1
                while (wx < b.x + b.w - 1) {
                    if (wx >= sz) { wx += 2; continue }
                    val wHash = wx * 131 + wy * 997 + (b.windowPhaseOffset * 100f).toInt()
                    val baseProb = sin(wHash.toFloat()) * 0.5f + 0.5f
                    val flicker = sin(time * 1.5f + wHash * 0.01f) * 0.15f
                    val isOn = (baseProb + flicker) < windowProb
                    if (isOn) {
                        if (tod != "day" && (wHash and 31) < 2) {
                            val neon = neonColors[(wHash shr 5) and 3]
                            val pulse = sin(time * 3f + wHash * 0.1f) * 0.2f + 0.8f
                            setPx(pixels, sz, wx, wy,
                                (neon[0] * pulse).toInt(), (neon[1] * pulse).toInt(), (neon[2] * pulse).toInt())
                        } else {
                            setPx(pixels, sz, wx, wy, windowOn[0], windowOn[1], windowOn[2])
                        }
                    } else {
                        setPx(pixels, sz, wx, wy, windowOff[0], windowOff[1], windowOff[2])
                    }
                    wx += 2
                }
                wy += 3
            }
        }
        // Ground
        val groundColor = colors[0]
        val groundLight = intArrayOf(
            min(255, groundColor[0] + 20),
            min(255, groundColor[1] + 15),
            min(255, groundColor[2] + 10)
        )
        for (y in baseline until sz) {
            for (x in 0 until sz) {
                val c = if (y == baseline) groundLight else groundColor
                setPx(pixels, sz, x, y, c[0], c[1], c[2])
            }
        }
        // Moving cars
        val numCars = max(2, (sz * 0.06f).toInt())
        for (c in 0 until numCars) {
            val speed = 0.5f + c * 0.3f
            val dir = if (c % 2 == 0) 1 else -1
            val carY = baseline + 1 + (c % 3)
            var carXRaw = (c * sz.toFloat() / numCars + time * sz * 0.05f * speed * dir) % sz
            if (carXRaw < 0) carXRaw += sz
            val carX = carXRaw.toInt()
            setPx(pixels, sz, carX, carY, 180, 180, 190)
            setPx(pixels, sz, carX + dir, carY, 160, 160, 170)
            setPx(pixels, sz, carX + dir * 2, carY, 255, 255, 200)
            if (dir == 1) {
                setPx(pixels, sz, carX - 1, carY, 255, 40, 40)
            } else {
                setPx(pixels, sz, carX + 2, carY, 255, 40, 40)
            }
        }
    }

    // --- Top-down render ---
    private fun renderTopDown(
        pixels: IntArray, sz: Int, layout: TopDownLayout,
        windowProb: Float, tod: String, colors: Array<IntArray>, time: Float
    ) {
        val nc = colors.size
        val roadBase = if (tod == "night") intArrayOf(25, 25, 30)
            else if (tod == "dusk") intArrayOf(45, 40, 42)
            else intArrayOf(90, 90, 95)
        val roadLine = if (tod == "night") intArrayOf(60, 60, 40)
            else if (tod == "dusk") intArrayOf(80, 75, 50)
            else intArrayOf(200, 200, 150)
        val roadArgb = PixelArtUtil.rgb(roadBase[0], roadBase[1], roadBase[2])
        for (i in 0 until sz * sz) pixels[i] = roadArgb
        // Dashed road markings
        for (ry in layout.roadH) {
            val cy = ry + (layout.roadWidth / 2)
            for (x in 0 until sz) {
                if (((x + (time * 3f).toInt()) % 6) < 3) {
                    setPx(pixels, sz, x, cy, roadLine[0], roadLine[1], roadLine[2])
                }
            }
        }
        for (rx in layout.roadV) {
            val cx = rx + (layout.roadWidth / 2)
            for (y in 0 until sz) {
                if (((y + (time * 3f).toInt()) % 6) < 3) {
                    setPx(pixels, sz, cx, y, roadLine[0], roadLine[1], roadLine[2])
                }
            }
        }
        // Buildings
        val inset = 1
        val shadowAlpha = 0.4f
        for (b in layout.blocks) {
            val bc = colors[b.colorIdx % nc]
            for (y in b.y + inset until b.y + b.h - inset) {
                for (x in b.x + inset until b.x + b.w - inset) {
                    setPx(pixels, sz, x, y, bc[0], bc[1], bc[2])
                }
            }
            // Shadow bottom
            for (x in b.x + inset + 1 until b.x + b.w) {
                val sy = b.y + b.h - inset
                if (sy < sz && x < sz && x >= 0 && sy >= 0) {
                    val idx = sy * sz + x
                    val c = pixels[idx]
                    val r = (((c ushr 16) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val g = (((c ushr 8) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val bb = ((c and 0xFF) * (1f - shadowAlpha)).toInt()
                    pixels[idx] = PixelArtUtil.rgb(r, g, bb)
                }
            }
            // Shadow right
            for (y in b.y + inset + 1 until b.y + b.h) {
                val sx = b.x + b.w - inset
                if (sx < sz && y < sz && sx >= 0 && y >= 0) {
                    val idx = y * sz + sx
                    val c = pixels[idx]
                    val r = (((c ushr 16) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val g = (((c ushr 8) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val bb = ((c and 0xFF) * (1f - shadowAlpha)).toInt()
                    pixels[idx] = PixelArtUtil.rgb(r, g, bb)
                }
            }
            // Roof lights
            val detailColor = colors[(b.colorIdx + 1) % nc]
            var y = b.y + inset + 1
            while (y < b.y + b.h - inset - 1) {
                var x = b.x + inset + 1
                while (x < b.x + b.w - inset - 1) {
                    val wHash = x * 131 + y * 997 + b.colorIdx * 37
                    val flicker = sin(time * 1.8f + wHash * 0.01f) * 0.2f
                    val isOn = (sin(wHash.toFloat()) * 0.5f + 0.5f + flicker) < windowProb
                    if (isOn) {
                        if (tod == "night" && (wHash and 15) < 1) {
                            val pulse = sin(time * 4f + wHash) * 0.3f + 0.7f
                            setPx(pixels, sz, x, y,
                                (255f * pulse).toInt(), (100f * pulse).toInt(), (150f * pulse).toInt())
                        } else {
                            setPx(pixels, sz, x, y, detailColor[0], detailColor[1], detailColor[2])
                        }
                    }
                    x += 2
                }
                y += 2
            }
        }
        // Cars
        val numCars = max(3, (sz * 0.08f).toInt())
        for (c in 0 until numCars) {
            val isHorizontal = c % 2 == 0
            val speed = 2 + (c % 3)
            val dir = if ((c shr 1) % 2 == 0) 1 else -1
            if (isHorizontal && layout.roadH.isNotEmpty()) {
                val roadIdx = c % layout.roadH.size
                val ry = layout.roadH[roadIdx] + (layout.roadWidth / 2) + (if (dir > 0) -1 else 1)
                var cxRaw = (c * 17 + time * speed * dir * sz * 0.04f) % sz
                if (cxRaw < 0) cxRaw += sz
                val ix = cxRaw.toInt()
                val carColor = when (c % 3) {
                    0 -> intArrayOf(200, 60, 60)
                    1 -> intArrayOf(60, 60, 200)
                    else -> intArrayOf(200, 200, 60)
                }
                setPx(pixels, sz, ix, ry, carColor[0], carColor[1], carColor[2])
                setPx(pixels, sz, ((ix + dir) % sz + sz) % sz, ry, carColor[0] shr 1, carColor[1] shr 1, carColor[2] shr 1)
                setPx(pixels, sz, ((ix + dir * 2) % sz + sz) % sz, ry, 255, 255, 220)
            } else if (layout.roadV.isNotEmpty()) {
                val roadIdx = c % layout.roadV.size
                val rx = layout.roadV[roadIdx] + (layout.roadWidth / 2) + (if (dir > 0) -1 else 1)
                var cyRaw = (c * 23 + time * speed * dir * sz * 0.04f) % sz
                if (cyRaw < 0) cyRaw += sz
                val iy = cyRaw.toInt()
                val carColor = when (c % 3) {
                    0 -> intArrayOf(200, 200, 200)
                    1 -> intArrayOf(60, 180, 60)
                    else -> intArrayOf(180, 120, 60)
                }
                setPx(pixels, sz, rx, iy, carColor[0], carColor[1], carColor[2])
                setPx(pixels, sz, rx, ((iy + dir) % sz + sz) % sz, carColor[0] shr 1, carColor[1] shr 1, carColor[2] shr 1)
                setPx(pixels, sz, rx, ((iy + dir * 2) % sz + sz) % sz, 255, 255, 220)
            }
        }
        // Emergency vehicle
        if (layout.roadH.isNotEmpty()) {
            val eSpeed = 4f
            val eRoad = layout.roadH[0] + (layout.roadWidth / 2)
            val ex = ((time * eSpeed * sz * 0.03f) % sz).toInt()
            val flash = sin(time * 12f) > 0f
            setPx(pixels, sz, ex, eRoad, 255, 255, 255)
            if (flash) {
                setPx(pixels, sz, (ex + 1) % sz, eRoad, 255, 0, 0)
                setPx(pixels, sz, ((ex - 1) % sz + sz) % sz, eRoad, 0, 0, 255)
            } else {
                setPx(pixels, sz, (ex + 1) % sz, eRoad, 0, 0, 255)
                setPx(pixels, sz, ((ex - 1) % sz + sz) % sz, eRoad, 255, 0, 0)
            }
        }
    }

    // --- Perspective render ---
    private fun renderPerspective(
        pixels: IntArray, sz: Int, layout: PerspectiveLayout,
        windowProb: Float, tod: String, colors: Array<IntArray>, time: Float
    ) {
        val nc = colors.size
        val bg = bgColor(tod)
        val vpx = layout.vpx; val vpy = layout.vpy
        val roadHalfW = layout.roadHalfW
        // Sky gradient
        for (y in 0 until sz) {
            val skyT = y.toFloat() / vpy
            for (x in 0 until sz) {
                val r: Int; val g: Int; val b: Int
                if (y < vpy) {
                    r = (bg[0] + (40f - bg[0] * 0.3f) * (1f - skyT)).toInt()
                    g = (bg[1] + (30f - bg[1] * 0.2f) * (1f - skyT)).toInt()
                    b = (bg[2] + (50f - bg[2] * 0.2f) * (1f - skyT)).toInt()
                } else {
                    r = bg[0] shr 1; g = bg[1] shr 1; b = bg[2] shr 1
                }
                pixels[y * sz + x] = PixelArtUtil.rgb(r, g, b)
            }
        }
        // Stars
        if (tod != "day") {
            val numStars = (vpy * sz * 0.008f).toInt()
            for (s in 0 until numStars) {
                val sx = (s * 137 + 53) % sz
                val sy = (s * 89 + 17) % vpy
                val twinkle = sin(time * 3f + s * 2.3f) * 0.3f + 0.7f
                val b = (190f * twinkle).toInt()
                setPx(pixels, sz, sx, sy, b, b, (b * 0.85f).toInt())
            }
        }
        // Moon
        if (tod != "day") {
            val moonX = (sz * 0.75f).toInt(); val moonY = (vpy * 0.25f).toInt()
            val mr = max(1, (sz * 0.025f).toInt())
            for (dy in -mr..mr) {
                for (dx in -mr..mr) {
                    if (dx * dx + dy * dy <= mr * mr) {
                        setPx(pixels, sz, moonX + dx, moonY + dy, 240, 235, 200)
                    }
                }
            }
        }
        // Road surface
        val roadColor = if (tod == "night") intArrayOf(28, 28, 35)
            else if (tod == "dusk") intArrayOf(48, 43, 46)
            else intArrayOf(82, 82, 90)
        val sidewalkColor = if (tod == "night") intArrayOf(52, 52, 58)
            else if (tod == "dusk") intArrayOf(72, 68, 70)
            else intArrayOf(135, 135, 142)
        val roadH = sz - 1 - vpy
        for (y in vpy until sz) {
            val t = (y - vpy).toFloat() / max(1, roadH)
            val tCurve = t * t
            val halfW = roadHalfW * tCurve
            val roadL = (vpx - halfW).toInt()
            val roadR = (vpx + halfW).toInt()
            val swW = max(1, (4 * tCurve).toInt())
            for (x in max(0, roadL - swW) until roadL) {
                setPx(pixels, sz, x, y, sidewalkColor[0], sidewalkColor[1], sidewalkColor[2])
            }
            for (x in roadR + 1..min(sz - 1, roadR + swW)) {
                setPx(pixels, sz, x, y, sidewalkColor[0], sidewalkColor[1], sidewalkColor[2])
            }
            for (x in roadL..roadR) {
                setPx(pixels, sz, x, y, roadColor[0], roadColor[1], roadColor[2])
            }
            setPx(pixels, sz, roadL, y,
                min(255, roadColor[0] + 45), min(255, roadColor[1] + 45), min(255, roadColor[2] + 35))
            setPx(pixels, sz, roadR, y,
                min(255, roadColor[0] + 45), min(255, roadColor[1] + 45), min(255, roadColor[2] + 35))
        }
        // Center dashed line
        val dashColor = if (tod == "night") intArrayOf(180, 180, 80)
            else if (tod == "dusk") intArrayOf(200, 190, 100)
            else intArrayOf(220, 220, 150)
        for (y in vpy + 1 until sz) {
            val t = (y - vpy).toFloat() / max(1, roadH)
            val dashLen = max(1, (8f * t * t).toInt())
            val gapLen = max(1, (5f * t * t).toInt())
            val phase = (y + (time * 10f).toInt()) % max(2, dashLen + gapLen)
            if (phase < dashLen) {
                setPx(pixels, sz, vpx, y, dashColor[0], dashColor[1], dashColor[2])
            }
        }
        // Sort buildings far-to-near
        val sorted = layout.buildings.sortedByDescending { it.depth }
        for (bld in sorted) {
            val dep = bld.depth
            val scale = 1f - dep
            if (scale <= 0.02f) continue
            val groundY = (vpy + scale * scale * roadH).toInt()
            val bScreenH = max(3, (bld.height * sz * scale).toInt())
            val bScreenW = max(2, (bld.width * sz * scale * 1.5f).toInt())
            val topY = groundY - bScreenH
            val tRoad = scale * scale
            val roadEdge = if (bld.side == "left") vpx - roadHalfW * tRoad else vpx + roadHalfW * tRoad
            val swW = max(1, (4f * tRoad).toInt())
            val bx0: Int; val bx1: Int
            if (bld.side == "left") {
                bx1 = (roadEdge - swW).toInt()
                bx0 = bx1 - bScreenW
            } else {
                bx0 = (roadEdge + swW + 1).toInt()
                bx1 = bx0 + bScreenW
            }
            val bColor = colors[bld.colorIdx % nc]
            // Front face
            for (y in max(0, topY)..min(sz - 1, groundY)) {
                for (x in bx0 until bx1) {
                    val isEdge = x == bx0 || x == bx1 - 1
                    val isTopBottom = y == topY || y == groundY
                    val shade = if (isEdge) 0.55f else if (isTopBottom) 0.65f else 0.85f
                    setPx(pixels, sz, x, y,
                        (bColor[0] * shade).toInt(), (bColor[1] * shade).toInt(), (bColor[2] * shade).toInt())
                }
            }
            // Side face
            val sideW = max(1, (bScreenW * 0.2f).toInt())
            val sideShade = 0.38f
            if (bld.side == "left") {
                for (y in max(0, topY)..min(sz - 1, groundY)) {
                    for (x in bx1 until bx1 + sideW) {
                        setPx(pixels, sz, x, y,
                            (bColor[0] * sideShade).toInt(), (bColor[1] * sideShade).toInt(), (bColor[2] * sideShade).toInt())
                    }
                }
            } else {
                for (y in max(0, topY)..min(sz - 1, groundY)) {
                    for (x in bx0 - sideW until bx0) {
                        setPx(pixels, sz, x, y,
                            (bColor[0] * sideShade).toInt(), (bColor[1] * sideShade).toInt(), (bColor[2] * sideShade).toInt())
                    }
                }
            }
            // Roof line
            if (topY >= 0) {
                for (x in bx0 until bx1) {
                    setPx(pixels, sz, x, topY,
                        min(255, bColor[0] + 55), min(255, bColor[1] + 50), min(255, bColor[2] + 40))
                }
            }
            // Windows
            val visTop = max(0, topY)
            val visBot = min(sz - 1, groundY)
            val visH = visBot - visTop
            val visW = bx1 - bx0
            if (visH > 3 && visW > 2) {
                val wSpY = max(2, (3f * scale + 0.5f).toInt())
                val wSpX = max(2, (3f * scale + 0.5f).toInt())
                val wOn = if (tod == "night") intArrayOf(255, 230, 100)
                    else if (tod == "dusk") intArrayOf(255, 200, 90)
                    else intArrayOf(200, 220, 240)
                val wOff = if (tod == "night") intArrayOf(32, 32, 48)
                    else if (tod == "dusk") intArrayOf(42, 38, 42)
                    else intArrayOf(152, 162, 172)
                val neon = arrayOf(
                    intArrayOf(255, 50, 120), intArrayOf(50, 200, 255), intArrayOf(200, 255, 50)
                )
                var wy = visTop + 2
                while (wy < visBot - 1) {
                    var wx = bx0 + 1
                    while (wx < bx1 - 1) {
                        val wHash = wx * 131 + wy * 997 + (bld.windowPhaseOffset * 100f).toInt()
                        val baseP = sin(wHash.toFloat()) * 0.5f + 0.5f
                        val flicker = sin(time * 1.5f + wHash * 0.01f) * 0.15f
                        val isOn = (baseP + flicker) < windowProb
                        if (isOn) {
                            if (tod != "day" && (wHash and 31) < 2) {
                                val n = neon[((wHash shr 5) % 3 + 3) % 3]
                                val p = sin(time * 3f + wHash * 0.1f) * 0.2f + 0.8f
                                setPx(pixels, sz, wx, wy,
                                    (n[0] * p).toInt(), (n[1] * p).toInt(), (n[2] * p).toInt())
                            } else {
                                setPx(pixels, sz, wx, wy, wOn[0], wOn[1], wOn[2])
                            }
                        } else {
                            setPx(pixels, sz, wx, wy, wOff[0], wOff[1], wOff[2])
                        }
                        wx += wSpX
                    }
                    wy += wSpY
                }
            }
            // Antenna
            if (bld.hasAntenna && topY > 1) {
                val ax = (bx0 + bx1) / 2
                val aLen = max(2, (3f * scale).toInt())
                for (i in 1..aLen) {
                    setPx(pixels, sz, ax, topY - i, bColor[0] shr 1, bColor[1] shr 1, bColor[2] shr 1)
                }
                if (sin(time * 4f + bld.windowPhaseOffset) > 0.3f) {
                    setPx(pixels, sz, ax, topY - aLen - 1, 255, 30, 30)
                }
            }
        }
        // Streetlights
        val lampOn = tod != "day"
        for (ld in layout.lampDepths) {
            val scale = 1f - ld
            if (scale < 0.1f) continue
            val tRoad = scale * scale
            val groundY = (vpy + tRoad * roadH).toInt()
            val roadEdgeL = (vpx - roadHalfW * tRoad).toInt()
            val roadEdgeR = (vpx + roadHalfW * tRoad).toInt()
            val poleH = max(3, (10f * scale).toInt())
            // Left lamp
            val lx = roadEdgeL - 1
            for (i in 0 until poleH) setPx(pixels, sz, lx, groundY - i, 65, 65, 72)
            if (lampOn) {
                setPx(pixels, sz, lx, groundY - poleH, 255, 240, 150)
                setPx(pixels, sz, lx + 1, groundY - poleH, 255, 240, 150)
                setPx(pixels, sz, lx + 1, groundY - poleH + 1, 200, 190, 100)
                val gR = max(1, (3f * scale).toInt())
                val dyMax = max(1, (2f * scale).toInt())
                for (dy in 0 until dyMax) {
                    for (dx in -gR..gR) {
                        val gx = lx + dx; val gy = groundY + dy
                        if (gx in 0 until sz && gy in 0 until sz) {
                            val idx = gy * sz + gx
                            val c = pixels[idx]
                            val falloff = 1f - abs(dx).toFloat() / (gR + 1)
                            val r = min(255, ((c ushr 16) and 0xFF) + (35f * falloff).toInt())
                            val g = min(255, ((c ushr 8) and 0xFF) + (30f * falloff).toInt())
                            val b = min(255, (c and 0xFF) + (12f * falloff).toInt())
                            pixels[idx] = PixelArtUtil.rgb(r, g, b)
                        }
                    }
                }
            }
            // Right lamp
            val rx = roadEdgeR + 2
            for (i in 0 until poleH) setPx(pixels, sz, rx, groundY - i, 65, 65, 72)
            if (lampOn) {
                setPx(pixels, sz, rx, groundY - poleH, 255, 240, 150)
                setPx(pixels, sz, rx - 1, groundY - poleH, 255, 240, 150)
                setPx(pixels, sz, rx - 1, groundY - poleH + 1, 200, 190, 100)
                val gR = max(1, (3f * scale).toInt())
                val dyMax = max(1, (2f * scale).toInt())
                for (dy in 0 until dyMax) {
                    for (dx in -gR..gR) {
                        val gx = rx + dx; val gy = groundY + dy
                        if (gx in 0 until sz && gy in 0 until sz) {
                            val idx = gy * sz + gx
                            val c = pixels[idx]
                            val falloff = 1f - abs(dx).toFloat() / (gR + 1)
                            val r = min(255, ((c ushr 16) and 0xFF) + (35f * falloff).toInt())
                            val g = min(255, ((c ushr 8) and 0xFF) + (30f * falloff).toInt())
                            val b = min(255, (c and 0xFF) + (12f * falloff).toInt())
                            pixels[idx] = PixelArtUtil.rgb(r, g, b)
                        }
                    }
                }
            }
        }
        // Cars
        val numCars = 4
        for (c in 0 until numCars) {
            val speed2 = 3f + c * 1.5f
            val dir = if (c % 2 == 0) 1 else -1
            val lane = if (dir > 0) 0.35f else -0.35f
            var rawDepth = (c * 0.22f + time * 0.04f * speed2 * dir) % 1.0f
            if (rawDepth < 0) rawDepth += 1f
            val carScale = 1f - rawDepth
            if (carScale < 0.06f) continue
            val tRoad = carScale * carScale
            val carY = (vpy + tRoad * roadH).toInt()
            val carX = (vpx + roadHalfW * tRoad * lane).toInt()
            if (carY <= vpy || carY >= sz - 1) continue
            val carW = max(1, (3f * carScale).toInt())
            val carH = max(1, (2f * carScale).toInt())
            for (dy in 0 until carH) {
                for (dx in 0 until carW) {
                    setPx(pixels, sz, carX + dx, carY + dy, 170, 170, 185)
                }
            }
            setPx(pixels, sz, carX, carY - 1, 255, 255, 200)
            if (carW > 1) setPx(pixels, sz, carX + carW - 1, carY - 1, 255, 255, 200)
            setPx(pixels, sz, carX, carY + carH, 255, 40, 40)
            if (carW > 1) setPx(pixels, sz, carX + carW - 1, carY + carH, 255, 40, 40)
        }
    }

    // --- Irregular render ---
    private fun renderIrregular(
        pixels: IntArray, sz: Int, layout: IrregularLayout,
        windowProb: Float, tod: String, colors: Array<IntArray>, time: Float
    ) {
        val nc = colors.size
        val roadBase = if (tod == "night") intArrayOf(25, 25, 30)
            else if (tod == "dusk") intArrayOf(45, 40, 42)
            else intArrayOf(90, 90, 95)
        val roadLine = if (tod == "night") intArrayOf(60, 60, 40)
            else if (tod == "dusk") intArrayOf(80, 75, 50)
            else intArrayOf(200, 200, 150)
        val roadArgb = PixelArtUtil.rgb(roadBase[0], roadBase[1], roadBase[2])
        for (i in 0 until sz * sz) pixels[i] = roadArgb
        // Dashed road markings
        for (ry in layout.roadH) {
            val cy = ry + (layout.roadWidth / 2)
            for (x in 0 until sz) {
                if (((x + (time * 3f).toInt()) % 6) < 3) {
                    setPx(pixels, sz, x, cy, roadLine[0], roadLine[1], roadLine[2])
                }
            }
        }
        for (rx in layout.roadV) {
            val cx = rx + (layout.roadWidth / 2)
            for (y in 0 until sz) {
                if (((y + (time * 3f).toInt()) % 6) < 3) {
                    setPx(pixels, sz, cx, y, roadLine[0], roadLine[1], roadLine[2])
                }
            }
        }
        val inset = 1
        val shadowAlpha = 0.4f
        for (blk in layout.blocks) {
            val bColor = colors[blk.colorIdx % nc]
            val hw = blk.w / 2; val hh = blk.h / 2
            val x0 = blk.cx - hw + inset; val x1 = blk.cx + hw - inset
            val y0 = blk.cy - hh + inset; val y1 = blk.cy + hh - inset

            fun fillPx(x: Int, y: Int, shade: Float) {
                setPx(pixels, sz, x, y,
                    (bColor[0] * shade).toInt(), (bColor[1] * shade).toInt(), (bColor[2] * shade).toInt())
            }

            when (blk.shape) {
                "rect" -> {
                    for (y in y0..y1) {
                        for (x in x0..x1) {
                            val isEdge = x == x0 || x == x1 || y == y0 || y == y1
                            fillPx(x, y, if (isEdge) 0.7f else 1f)
                        }
                    }
                }
                "circle" -> {
                    val rw = (x1 - x0) / 2f; val rh = (y1 - y0) / 2f
                    val ccx = (x0 + x1) / 2f; val ccy = (y0 + y1) / 2f
                    for (y in y0..y1) {
                        for (x in x0..x1) {
                            val ndx = (x - ccx) / max(1f, rw)
                            val ndy = (y - ccy) / max(1f, rh)
                            val dist = ndx * ndx + ndy * ndy
                            if (dist <= 1.0f) {
                                fillPx(x, y, if (dist > 0.72f) 0.7f else 1f)
                            }
                        }
                    }
                }
                "rhombus" -> {
                    for (y in y0..y1) {
                        for (x in x0..x1) {
                            val ndx = abs(x - blk.cx) / max(1f, (hw - inset).toFloat())
                            val ndy = abs(y - blk.cy) / max(1f, (hh - inset).toFloat())
                            if (ndx + ndy <= 1.0f) {
                                fillPx(x, y, if (ndx + ndy > 0.78f) 0.7f else 1f)
                            }
                        }
                    }
                }
                "trapezoid" -> {
                    for (y in y0..y1) {
                        val t = (y - y0).toFloat() / max(1, y1 - y0)
                        val rowHW = ((0.45f + t * 0.55f) * (hw - inset)).toInt()
                        for (x in blk.cx - rowHW..blk.cx + rowHW) {
                            val isEdge = x == blk.cx - rowHW || x == blk.cx + rowHW || y == y0 || y == y1
                            fillPx(x, y, if (isEdge) 0.7f else 1f)
                        }
                    }
                }
                "L-shape" -> {
                    for (y in y0..y1) {
                        for (x in x0..x1) {
                            val inBottom = y >= blk.cy
                            val inLeft = x <= blk.cx
                            if (inBottom || inLeft) {
                                val isEdge = x == x0 || x == x1 || y == y0 || y == y1 ||
                                    (y == blk.cy && x > blk.cx) || (x == blk.cx && y < blk.cy)
                                fillPx(x, y, if (isEdge) 0.7f else 1f)
                            }
                        }
                    }
                }
            }
            // Shadow bottom
            for (x in x0 + 1..x1 + 1) {
                val sy = y1 + 1
                if (sy in 0 until sz && x in 0 until sz) {
                    val idx = sy * sz + x
                    val c = pixels[idx]
                    val r = (((c ushr 16) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val g = (((c ushr 8) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val bb = ((c and 0xFF) * (1f - shadowAlpha)).toInt()
                    pixels[idx] = PixelArtUtil.rgb(r, g, bb)
                }
            }
            // Shadow right
            for (y in y0 + 1..y1) {
                val sx = x1 + 1
                if (sx in 0 until sz && y in 0 until sz) {
                    val idx = y * sz + sx
                    val c = pixels[idx]
                    val r = (((c ushr 16) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val g = (((c ushr 8) and 0xFF) * (1f - shadowAlpha)).toInt()
                    val bb = ((c and 0xFF) * (1f - shadowAlpha)).toInt()
                    pixels[idx] = PixelArtUtil.rgb(r, g, bb)
                }
            }
            // Windows / roof lights
            val detailColor = colors[(blk.colorIdx + 1) % nc]
            var y = y0 + 1
            while (y < y1) {
                var x = x0 + 1
                while (x < x1) {
                    if (x < 0 || x >= sz || y < 0 || y >= sz) { x += 2; continue }
                    val idx = y * sz + x
                    val c = pixels[idx]
                    val cr = (c ushr 16) and 0xFF
                    val cg = (c ushr 8) and 0xFF
                    val cb = c and 0xFF
                    // Skip road pixels (shape didn't fill here)
                    if (cr == roadBase[0] && cg == roadBase[1] && cb == roadBase[2]) { x += 2; continue }
                    val wHash = x * 131 + y * 997 + blk.colorIdx * 37
                    val flicker = sin(time * 1.8f + wHash * 0.01f) * 0.2f
                    val isOn = (sin(wHash.toFloat()) * 0.5f + 0.5f + flicker) < windowProb
                    if (isOn) {
                        if (tod == "night" && (wHash and 15) < 1) {
                            val pulse = sin(time * 4f + wHash) * 0.3f + 0.7f
                            setPx(pixels, sz, x, y, (255f * pulse).toInt(), (100f * pulse).toInt(), (150f * pulse).toInt())
                        } else {
                            setPx(pixels, sz, x, y, detailColor[0], detailColor[1], detailColor[2])
                        }
                    }
                    x += 2
                }
                y += 2
            }
        }
        // Helper: check if a pixel is road
        fun isRoad(x: Int, y: Int): Boolean {
            if (x < 0 || x >= sz || y < 0 || y >= sz) return false
            val c = pixels[y * sz + x]
            return ((c ushr 16) and 0xFF) == roadBase[0] &&
                ((c ushr 8) and 0xFF) == roadBase[1] &&
                (c and 0xFF) == roadBase[2]
        }
        val carPositions = mutableListOf<Pair<Int, Int>>()
        fun tryDrawCar(pix: Array<IntArray>, bodyColor: IntArray): Boolean {
            for (p in pix) {
                if (!isRoad(p[0], p[1])) return false
                if (carPositions.any { it.first == p[0] && it.second == p[1] }) return false
            }
            setPx(pixels, sz, pix[0][0], pix[0][1], bodyColor[0], bodyColor[1], bodyColor[2])
            setPx(pixels, sz, pix[1][0], pix[1][1], bodyColor[0] shr 1, bodyColor[1] shr 1, bodyColor[2] shr 1)
            setPx(pixels, sz, pix[2][0], pix[2][1], 255, 255, 220)
            for (p in pix) carPositions.add(Pair(p[0], p[1]))
            return true
        }
        val carColors = arrayOf(
            intArrayOf(200, 60, 60), intArrayOf(60, 60, 200), intArrayOf(200, 200, 60),
            intArrayOf(200, 200, 200), intArrayOf(60, 180, 60), intArrayOf(180, 120, 60)
        )
        val rhw = layout.roadWidth / 2
        // Straight cars
        val numStraight = max(2, (sz * 0.06f).toInt())
        for (c in 0 until numStraight) {
            val isHorizontal = c % 2 == 0
            val speed = 2 + (c % 3)
            val dir = if ((c shr 1) % 2 == 0) 1 else -1
            if (isHorizontal && layout.roadH.isNotEmpty()) {
                val roadIdx = c % layout.roadH.size
                val ry = layout.roadH[roadIdx] + rhw + (if (dir > 0) -1 else 1)
                var ixRaw = (c * 17 + time * speed * dir * sz * 0.04f) % sz
                if (ixRaw < 0) ixRaw += sz
                val ix = ixRaw.toInt()
                val px0 = ix
                val px1 = ((ix + dir) % sz + sz) % sz
                val px2 = ((ix + dir * 2) % sz + sz) % sz
                tryDrawCar(arrayOf(intArrayOf(px0, ry), intArrayOf(px1, ry), intArrayOf(px2, ry)),
                    carColors[c % carColors.size])
            } else if (layout.roadV.isNotEmpty()) {
                val roadIdx = c % layout.roadV.size
                val rx = layout.roadV[roadIdx] + rhw + (if (dir > 0) -1 else 1)
                var iyRaw = (c * 23 + time * speed * dir * sz * 0.04f) % sz
                if (iyRaw < 0) iyRaw += sz
                val iy = iyRaw.toInt()
                val py0 = iy
                val py1 = ((iy + dir) % sz + sz) % sz
                val py2 = ((iy + dir * 2) % sz + sz) % sz
                tryDrawCar(arrayOf(intArrayOf(rx, py0), intArrayOf(rx, py1), intArrayOf(rx, py2)),
                    carColors[(c + 3) % carColors.size])
            }
        }
        // Corner-turning cars
        val numTurning = max(1, min(layout.roadH.size * layout.roadV.size, (sz * 0.04f).toInt()))
        for (c in 0 until numTurning) {
            if (layout.roadH.isEmpty() || layout.roadV.isEmpty()) break
            val hIdx = c % layout.roadH.size
            val vIdx = (c * 3 + 1) % layout.roadV.size
            val speed = 1.5f + (c % 3) * 0.7f
            val ry = layout.roadH[hIdx] + rhw
            val rx = layout.roadV[vIdx] + rhw
            val totalLen = sz * 2
            var rawPos = (c * 31 + time * speed * sz * 0.04f) % totalLen
            if (rawPos < 0) rawPos += totalLen
            if (rawPos < sz) {
                val ix = rawPos.toInt()
                val dir = if (ix < rx) 1 else -1
                val px0 = ix
                val px1 = ((ix + dir) % sz + sz) % sz
                val px2 = ((ix + dir * 2) % sz + sz) % sz
                tryDrawCar(arrayOf(intArrayOf(px0, ry), intArrayOf(px1, ry), intArrayOf(px2, ry)),
                    carColors[(c + 2) % carColors.size])
            } else {
                val iy = (rawPos - sz).toInt()
                val dir = if (iy < ry) 1 else -1
                val py0 = iy
                val py1 = ((iy + dir) % sz + sz) % sz
                val py2 = ((iy + dir * 2) % sz + sz) % sz
                tryDrawCar(arrayOf(intArrayOf(rx, py0), intArrayOf(rx, py1), intArrayOf(rx, py2)),
                    carColors[(c + 2) % carColors.size])
            }
        }
        // Emergency vehicle
        if (layout.roadH.isNotEmpty()) {
            val eSpeed = 4f
            val eRoad = layout.roadH[0] + rhw
            val ex = ((time * eSpeed * sz * 0.03f) % sz).toInt()
            val epx = arrayOf(
                intArrayOf(ex, eRoad),
                intArrayOf((ex + 1) % sz, eRoad),
                intArrayOf(((ex - 1) % sz + sz) % sz, eRoad)
            )
            val allClear = epx.all { isRoad(it[0], it[1]) && carPositions.none { cp -> cp.first == it[0] && cp.second == it[1] } }
            if (allClear) {
                val flash = sin(time * 12f) > 0f
                setPx(pixels, sz, epx[0][0], epx[0][1], 255, 255, 255)
                setPx(pixels, sz, epx[1][0], epx[1][1], if (flash) 255 else 0, 0, if (flash) 0 else 255)
                setPx(pixels, sz, epx[2][0], epx[2][1], if (flash) 0 else 255, 0, if (flash) 255 else 0)
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val sz = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val mode = PixelArtUtil.ps(params, "mode", "skyline")
        val density = PixelArtUtil.pf(params, "buildingDensity", 0.6f)
        val windowProb = PixelArtUtil.pf(params, "windowProbability", 0.6f)
        val tod = PixelArtUtil.ps(params, "timeOfDay", "night")
        val speed = PixelArtUtil.pf(params, "animSpeed", 1f)
        val animTime = time * speed

        val colors = PixelArtUtil.paletteRgb(palette)
        val pixels = IntArray(sz * sz)

        synchronized(Companion) {
            val key = "$seed|$sz|$density|$mode"
            if (cacheKey != key) {
                val rng = SeededRNG(seed)
                cacheSkyline = if (mode == "skyline") buildSkyline(sz, density, rng) else null
                cacheTopDown = if (mode == "top-down") buildTopDown(sz, density, rng) else null
                cachePerspective = if (mode == "perspective") buildPerspective(sz, density, rng) else null
                cacheIrregular = if (mode == "irregular") buildIrregular(sz, density, rng) else null
                cacheKey = key
            }

            when (mode) {
                "skyline" -> cacheSkyline?.let { renderSkyline(pixels, sz, it, windowProb, tod, colors, animTime) }
                "perspective" -> cachePerspective?.let { renderPerspective(pixels, sz, it, windowProb, tod, colors, animTime) }
                "irregular" -> cacheIrregular?.let { renderIrregular(pixels, sz, it, windowProb, tod, colors, animTime) }
                else -> cacheTopDown?.let { renderTopDown(pixels, sz, it, windowProb, tod, colors, animTime) }
            }
        }

        PixelArtUtil.blitNearest(canvas, bitmap, pixels, sz)
    }
}
