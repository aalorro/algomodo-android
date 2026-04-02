package com.artmondo.algomodo.generators.procedural

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

class SdfRaymarchGenerator : Generator {

    override val id = "procedural-sdf-raymarch"
    override val family = "procedural"
    override val styleName = "SDF Raymarch"
    override val definition =
        "2D signed-distance-field rendering with smooth boolean operations, domain warping, glow halos, and distance-band contours."
    override val algorithmNotes =
        "Generates SDF primitives (circles, boxes, rings, crosses, triangles) with seeded positions. " +
        "Domain warping via value noise creates organic shapes. Fractal mode uses domain folding. " +
        "Lattice mode tiles via domain repetition. Interior is palette-colored; exterior shows glow, banding, and edge lines."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Scene", "sceneType", ParamGroup.COMPOSITION,
            "spheres | boxes | rings | blend: mixed | lattice: tiled | fractal: folded",
            listOf("spheres", "boxes", "rings", "blend", "lattice", "fractal"), "blend"),
        Parameter.NumberParam("Complexity", "complexity", ParamGroup.COMPOSITION,
            "Number of SDF primitives", 1f, 8f, 1f, 4f),
        Parameter.NumberParam("Glow", "glowIntensity", ParamGroup.TEXTURE,
            "Glow halo intensity around shapes", 0f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Band Width", "bandWidth", ParamGroup.TEXTURE,
            "Distance-based contour band width (0 = off)", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Smooth Blend", "smoothBlend", ParamGroup.GEOMETRY,
            "Smooth union/subtract blending factor", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Warp", "warpAmount", ParamGroup.GEOMETRY,
            "Domain warping intensity via noise", 0f, 1f, 0.05f, 0.2f),
        Parameter.NumberParam("Rotation Speed", "rotationSpeed", ParamGroup.FLOW_MOTION,
            "Primitive orbit speed", 0f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Global animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sceneType" to "blend", "complexity" to 4f, "glowIntensity" to 0.5f,
        "bandWidth" to 0.3f, "smoothBlend" to 0.3f, "warpAmount" to 0.2f,
        "rotationSpeed" to 0.5f, "speed" to 0.5f, "reactivity" to 1.0f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val wf = w.toFloat(); val hf = h.toFloat()
        val rng = SeededRNG(seed)
        val minDim = min(w, h)
        val minDimF = minDim.toFloat()
        val cx = wf / 2f; val cy = hf / 2f
        val step = when (quality) { Quality.DRAFT -> 4; Quality.ULTRA -> 1; else -> 2 }

        val sceneType = (params["sceneType"] as? String) ?: "blend"
        val complexity = (params["complexity"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 4
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx

        val glowIntensity = ((params["glowIntensity"] as? Number)?.toFloat() ?: 0.5f) + audioBass * 0.5f
        val bandWidth = (params["bandWidth"] as? Number)?.toFloat() ?: 0.3f
        val smoothK = ((params["smoothBlend"] as? Number)?.toFloat() ?: 0.3f) * minDimF * 0.15f
        val useSmooth = smoothK >= 0.001f
        val warpAmount = ((params["warpAmount"] as? Number)?.toFloat() ?: 0.2f) * minDimF * 0.08f
        val rotSpeed = ((params["rotationSpeed"] as? Number)?.toFloat() ?: 0.5f) * (1f + audioMid * 1.5f)
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val t = time * spd

        val colors = palette.colorInts()
        val nC = colors.size

        val vn = if (warpAmount > 0.1f || sceneType == "fractal") ValueNoise(seed) else null
        val warpScale = 2.5f / minDimF

        // Primitive types: 0=circle 1=roundbox 2=ring 3=cross 4=triangle
        val isFractal = sceneType == "fractal"
        val isLattice = sceneType == "lattice"
        val maxPrims = complexity * (if (isFractal) 2 else 1) + if (isFractal) min(3, complexity) else 0

        val primType = IntArray(maxPrims)
        val primCx = FloatArray(maxPrims)
        val primCy = FloatArray(maxPrims)
        val primR = FloatArray(maxPrims)
        val primHW = FloatArray(maxPrims)
        val primHH = FloatArray(maxPrims)
        val primRR = FloatArray(maxPrims)
        val primThick = FloatArray(maxPrims)  // ring thickness / cross arm width
        val primOrbitR = FloatArray(maxPrims)
        val primOrbitSpd = FloatArray(maxPrims)
        val primOrbitPhase = FloatArray(maxPrims)
        val primColorIdx = IntArray(maxPrims)
        val primOp = IntArray(maxPrims)       // 0=union 1=subtract
        val primRotation = FloatArray(maxPrims)  // per-primitive rotation

        var primCount = 0

        fun addPrim(depth: Int) {
            val count = if (depth == 0) complexity else min(3, complexity)
            val sizeScale = if (depth == 0) 1f else 0.4f
            for (i in 0 until count) {
                if (primCount >= maxPrims) break
                val idx = primCount++

                // Choose type based on scene
                primType[idx] = when (sceneType) {
                    "spheres" -> 0
                    "boxes" -> 1
                    "rings" -> 2
                    "blend" -> rng.integer(0, 4)
                    "lattice" -> rng.integer(0, 3)
                    "fractal" -> if (depth == 0) rng.integer(0, 2) else 0
                    else -> 0
                }

                if (isLattice) {
                    // Lattice: place near center, domain rep handles tiling
                    primCx[idx] = rng.range(0.35f, 0.65f) * wf
                    primCy[idx] = rng.range(0.35f, 0.65f) * hf
                    primR[idx] = rng.range(0.04f, 0.10f) * minDimF * sizeScale
                } else {
                    primCx[idx] = rng.range(0.15f, 0.85f) * wf
                    primCy[idx] = rng.range(0.15f, 0.85f) * hf
                    primR[idx] = rng.range(0.06f, 0.20f) * minDimF * sizeScale
                }

                val hw = rng.range(0.05f, 0.18f) * minDimF * sizeScale
                val hh = rng.range(0.05f, 0.18f) * minDimF * sizeScale
                val rr = rng.range(0.01f, 0.05f) * minDimF * sizeScale
                primHW[idx] = hw - rr
                primHH[idx] = hh - rr
                primRR[idx] = rr
                primThick[idx] = rng.range(0.008f, 0.03f) * minDimF * sizeScale
                primOrbitR[idx] = rng.range(0.02f, 0.12f) * minDimF * sizeScale
                primOrbitSpd[idx] = rng.range(0.5f, 2.0f) * if (rng.random() > 0.5f) 1f else -1f
                primOrbitPhase[idx] = rng.randomAngle()
                primColorIdx[idx] = rng.integer(0, nC - 1)
                primOp[idx] = if (i == 0 || rng.random() > 0.3f) 0 else 1
                primRotation[idx] = rng.range(0f, PI.toFloat())
            }
        }

        addPrim(0)
        if (isFractal) addPrim(1)

        // Precompute animated positions
        val animCx = FloatArray(primCount)
        val animCy = FloatArray(primCount)
        val animRot = FloatArray(primCount)
        for (i in 0 until primCount) {
            val a = t * rotSpeed * primOrbitSpd[i] + primOrbitPhase[i]
            animCx[i] = primCx[i] + cos(a) * primOrbitR[i]
            animCy[i] = primCy[i] + sin(a) * primOrbitR[i]
            animRot[i] = primRotation[i] + t * rotSpeed * 0.5f
        }

        // Lattice repetition cell size
        val cellSize = if (isLattice) minDimF / (1.5f + complexity * 0.4f) else 0f
        val halfCell = cellSize * 0.5f

        // Fractal domain folding params
        val foldAngle = if (isFractal) rng.range(PI.toFloat() * 0.2f, PI.toFloat() * 0.8f) else 0f
        val foldCosA = cos(foldAngle)
        val foldSinA = sin(foldAngle)
        val foldIterations = if (isFractal) min(complexity, 4) else 0

        // Shading constants
        val shadeDiv = 1f / (minDimF * 0.05f)
        val glowDiv = -1f / (minDimF * 0.06f)
        val bandScale = if (bandWidth > 0.01f) PI.toFloat() / (bandWidth * minDimF * 0.05f) else 0f
        val hasGlow = glowIntensity > 0f
        val hasBand = bandScale > 0f
        val edgeWidth = minDimF * 0.003f
        val edgeDiv = 1f / edgeWidth

        // Background gradient colors
        val bgC0 = colors[0]
        val bgR0 = Color.red(bgC0) * 0.04f
        val bgG0 = Color.green(bgC0) * 0.04f
        val bgB0 = Color.blue(bgC0) * 0.04f

        val pixels = IntArray(w * h)

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                var qx = px.toFloat()
                var qy = py.toFloat()

                // Domain warping
                if (vn != null && warpAmount > 0.1f) {
                    val nx = qx * warpScale + t * 0.05f
                    val ny = qy * warpScale
                    qx += vn.noise(nx, ny) * warpAmount
                    qy += vn.noise(ny + 31.7f, nx + 17.3f) * warpAmount
                }

                // Domain folding for fractal mode
                if (isFractal && vn != null) {
                    var fx = qx - cx
                    var fy = qy - cy
                    for (fi in 0 until foldIterations) {
                        // Abs fold
                        fx = abs(fx)
                        fy = abs(fy)
                        // Rotate
                        val rx2 = fx * foldCosA - fy * foldSinA
                        val ry2 = fx * foldSinA + fy * foldCosA
                        fx = rx2 - minDimF * 0.08f
                        fy = ry2 - minDimF * 0.08f
                    }
                    qx = fx + cx
                    qy = fy + cy
                }

                // Lattice domain repetition
                if (isLattice && cellSize > 1f) {
                    qx = ((qx % cellSize) + cellSize) % cellSize - halfCell + cx
                    qy = ((qy % cellSize) + cellSize) % cellSize - halfCell + cy
                }

                var d = Float.MAX_VALUE
                var closest = 0

                for (i in 0 until primCount) {
                    var dx = qx - animCx[i]
                    var dy = qy - animCy[i]

                    // Per-primitive rotation for non-circle types
                    if (primType[i] != 0) {
                        val rot = animRot[i]
                        val cosR = cos(rot); val sinR = sin(rot)
                        val rdx = dx * cosR + dy * sinR
                        val rdy = -dx * sinR + dy * cosR
                        dx = rdx; dy = rdy
                    }

                    val di: Float = when (primType[i]) {
                        0 -> {
                            // Circle
                            sqrt(dx * dx + dy * dy) - primR[i]
                        }
                        1 -> {
                            // Rounded box
                            val adx = abs(dx) - primHW[i]
                            val ady = abs(dy) - primHH[i]
                            val adx0 = if (adx > 0f) adx else 0f
                            val ady0 = if (ady > 0f) ady else 0f
                            sqrt(adx0 * adx0 + ady0 * ady0) +
                                min(max(adx, ady), 0f) - primRR[i]
                        }
                        2 -> {
                            // Ring (annular circle)
                            abs(sqrt(dx * dx + dy * dy) - primR[i]) - primThick[i]
                        }
                        3 -> {
                            // Cross (union of two thin boxes)
                            val armW = primThick[i] * 3f
                            val d1 = max(abs(dx) - primR[i] * 0.8f, abs(dy) - armW)
                            val d2 = max(abs(dx) - armW, abs(dy) - primR[i] * 0.8f)
                            min(d1, d2)
                        }
                        else -> {
                            // Equilateral triangle
                            val k = sqrt(3f)
                            var tx = abs(dx) - primR[i]
                            var ty = dy + primR[i] / k
                            if (tx + k * ty > 0f) {
                                val ntx = (tx - k * ty) / 2f
                                val nty = (-k * tx - ty) / 2f
                                tx = ntx; ty = nty
                            }
                            tx = tx.coerceIn(-2f * primR[i], 0f)
                            sqrt(tx * tx + ty * ty) * if (ty > 0f) 1f else -1f
                        }
                    }

                    if (i == 0) {
                        d = di; closest = 0
                    } else if (primOp[i] == 1) {
                        // Smooth subtract
                        if (useSmooth) {
                            val h2 = (0.5f - 0.5f * (d + di) / smoothK).coerceIn(0f, 1f)
                            val nd = d * (1f - h2) + (-di) * h2 + smoothK * h2 * (1f - h2)
                            if (nd != d) closest = i
                            d = nd
                        } else {
                            val nd = max(d, -di)
                            if (nd != d) closest = i
                            d = nd
                        }
                    } else {
                        // Smooth union
                        if (useSmooth) {
                            if (di < d) closest = i
                            val h2 = (0.5f + 0.5f * (di - d) / smoothK).coerceIn(0f, 1f)
                            d = d * h2 + di * (1f - h2) - smoothK * h2 * (1f - h2)
                        } else {
                            if (di < d) { d = di; closest = i }
                        }
                    }
                }

                val c = colors[primColorIdx[closest] % nC]
                val cR = Color.red(c).toFloat()
                val cG = Color.green(c).toFloat()
                val cB = Color.blue(c).toFloat()

                // Secondary color for blending
                val c2Idx = (primColorIdx[closest] + 1) % nC
                val c2 = colors[c2Idx]

                var r: Float; var g: Float; var b: Float

                if (d < 0f) {
                    // Interior: depth-based shading with secondary color blend
                    val depth = min(1f, -d * shadeDiv)
                    val shade = 0.65f + 0.35f * depth
                    // Blend toward secondary color deeper inside
                    val blend = depth * 0.3f
                    r = (cR * (1f - blend) + Color.red(c2) * blend) * shade
                    g = (cG * (1f - blend) + Color.green(c2) * blend) * shade
                    b = (cB * (1f - blend) + Color.blue(c2) * blend) * shade
                } else {
                    // Exterior: glow + bands + background gradient
                    val distFromCenter = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
                    val bgFade = min(1f, distFromCenter / (minDimF * 0.7f))

                    r = bgR0 * (1f - bgFade * 0.5f)
                    g = bgG0 * (1f - bgFade * 0.5f)
                    b = bgB0 * (1f - bgFade * 0.5f)

                    if (hasGlow) {
                        val glow = exp(d * glowDiv) * glowIntensity
                        r += cR * glow
                        g += cG * glow
                        b += cB * glow
                    }
                    if (hasBand) {
                        val band = (sin(d * bandScale) * 0.5f + 0.5f) * 0.3f
                        r += cR * band
                        g += cG * band
                        b += cB * band
                    }
                }

                // Edge highlight at d ≈ 0
                val edgeFactor = (1f - min(1f, abs(d) * edgeDiv))
                if (edgeFactor > 0f) {
                    val edgeBright = edgeFactor * edgeFactor * 0.8f
                    r += (255f - r) * edgeBright
                    g += (255f - g) * edgeBright
                    b += (255f - b) * edgeBright
                }

                val ri = r.toInt().coerceIn(0, 255)
                val gi = g.toInt().coerceIn(0, 255)
                val bi = b.toInt().coerceIn(0, 255)
                val pixel = Color.rgb(ri, gi, bi)

                if (step == 1) {
                    pixels[py * w + px] = pixel
                } else {
                    val maxDy = min(step, h - py)
                    val maxDx = min(step, w - px)
                    for (ddy in 0 until maxDy) {
                        val rowBase = (py + ddy) * w + px
                        for (ddx in 0 until maxDx) {
                            pixels[rowBase + ddx] = pixel
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val complexity = (params["complexity"] as? Number)?.toInt() ?: 4
        val scene = (params["sceneType"] as? String) ?: "blend"
        val warp = (params["warpAmount"] as? Number)?.toFloat() ?: 0.2f
        val base = complexity * 0.1f + if (scene == "fractal") 0.3f else 0f
        return (base + 0.2f + if (warp > 0.1f) 0.1f else 0f).coerceIn(0.3f, 1f)
    }
}
