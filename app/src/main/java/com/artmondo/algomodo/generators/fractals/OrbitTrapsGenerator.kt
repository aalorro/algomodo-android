package com.artmondo.algomodo.generators.fractals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class OrbitTrapsGenerator : Generator {

    override val id = "fractal-orbit-traps"
    override val family = "fractals"
    override val styleName = "Orbit Traps"
    override val definition =
        "Mandelbrot/Julia orbit-trap fractal that colors pixels by their closest approach to geometric trap shapes during iteration."
    override val algorithmNotes =
        "During the escape-time iteration z = z^2 + c, the minimum distance from z to a geometric " +
        "trap shape (point, circle, cross, or square) is tracked. The final minimum distance is " +
        "mapped to palette colors, producing intricate crystalline patterns overlaid on the fractal boundary. " +
        "Animation rotates the trap shape and shifts the Julia c parameter."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Mode", "mode", ParamGroup.COMPOSITION, "mandelbrot: c = pixel, z0 = 0 | julia: z0 = pixel, c = constant", listOf("mandelbrot", "julia"), "mandelbrot"),
        Parameter.SelectParam("Trap Shape", "trapShape", ParamGroup.COMPOSITION, "Geometric shape used as the orbit trap", listOf("point", "circle", "cross", "square"), "circle"),
        Parameter.NumberParam("Trap Size", "trapSize", ParamGroup.GEOMETRY, "Radius or size of the trap shape", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("C Real", "cReal", ParamGroup.COMPOSITION, "Real part of c (Julia mode)", -1.5f, 1.5f, 0.01f, -0.7f),
        Parameter.NumberParam("C Imaginary", "cImag", ParamGroup.COMPOSITION, "Imaginary part of c (Julia mode)", -1.5f, 1.5f, 0.01f, 0.27f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Maximum escape-time iterations", 32f, 256f, 16f, 80f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Animation speed — rotates trap and shifts c parameter", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "mode" to "mandelbrot",
        "trapShape" to "circle",
        "trapSize" to 0.5f,
        "cReal" to -0.7f,
        "cImag" to 0.27f,
        "zoom" to 1f,
        "maxIterations" to 80f,
        "speed" to 0.5f
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
        val w = bitmap.width
        val h = bitmap.height
        val mode = (params["mode"] as? String) ?: "mandelbrot"
        val trapShape = (params["trapShape"] as? String) ?: "circle"
        val trapSize = (params["trapSize"] as? Number)?.toFloat() ?: 0.5f
        val baseCr = (params["cReal"] as? Number)?.toFloat() ?: -0.7f
        val baseCi = (params["cImag"] as? Number)?.toFloat() ?: 0.27f
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 80
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val scaledMaxIter = when (quality) {
            Quality.DRAFT -> (maxIter / 2).coerceAtLeast(16)
            Quality.BALANCED -> maxIter
            Quality.ULTRA -> (maxIter * 1.5f).toInt()
        }

        val trapAngle = time * speed * 0.2f
        val cosT = cos(trapAngle)
        val sinT = sin(trapAngle)

        val cr = baseCr + sin(time * speed * 0.15f) * 0.08f
        val ci = baseCi + cos(time * speed * 0.2f) * 0.08f

        val aspect = w.toFloat() / h
        val rangeY = 3f / zoom
        val rangeX = rangeY * aspect
        val isJulia = mode == "julia"
        val invTrapSize = 1f / trapSize

        val lutSize = 256
        val lutMax = lutSize - 1
        val paletteLut = IntArray(lutSize) { palette.lerpColor(it.toFloat() / lutMax) }
        val darkLut = IntArray(lutSize) { i ->
            val c = paletteLut[i]
            (0xFF shl 24) or
                (((c shr 16 and 0xFF) * 38 shr 8) shl 16) or
                (((c shr 8 and 0xFF) * 38 shr 8) shl 8) or
                ((c and 0xFF) * 38 shr 8)
        }
        val timeShift = time * speed * 0.01f

        val trapIdx = when (trapShape) {
            "point" -> 0; "circle" -> 1; "cross" -> 2; "square" -> 3; else -> 0
        }

        // Adaptive resolution: render at reduced size during animation, upscale after
        val isAnim = time > 0f
        val renderW = if (isAnim) (w * 3 / 5).coerceAtLeast(w / 2) else w
        val renderH = if (isAnim) (h * 3 / 5).coerceAtLeast(h / 2) else h

        val rScaleX = rangeX / renderW
        val rScaleY = rangeY / renderH
        val halfRX = rangeX * 0.5f
        val halfRY = rangeY * 0.5f

        val renderPixels = IntArray(renderW * renderH)

        val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val threads = Array(cores) { t ->
            Thread {
                val y0 = t * renderH / cores
                val y1 = (t + 1) * renderH / cores

                // Brent's cycle detection state (per-thread, reused per pixel)
                for (py in y0 until y1) {
                    val y0v = py * rScaleY - halfRY
                    val rowOff = py * renderW

                    for (px in 0 until renderW) {
                        val x0 = px * rScaleX - halfRX

                        var zr: Float
                        var zi: Float
                        val cReal: Float
                        val cImag: Float

                        if (isJulia) {
                            zr = x0; zi = y0v; cReal = cr; cImag = ci
                        } else {
                            zr = 0f; zi = 0f; cReal = x0; cImag = y0v
                        }

                        var minDist = Float.MAX_VALUE
                        var iter = 0
                        var zrSq = zr * zr
                        var ziSq = zi * zi

                        when (trapIdx) {
                            0 -> {
                                // Point trap: no rotation, no sqrt in loop
                                var minDistSq = Float.MAX_VALUE
                                var oldZr = 10f; var oldZi = 10f
                                var stepLim = 2; var stepCnt = 0
                                while (iter < scaledMaxIter) {
                                    val magSq = zrSq + ziSq
                                    if (magSq > 64f) break
                                    if (magSq < minDistSq) minDistSq = magSq
                                    zi = 2f * zr * zi + cImag
                                    zr = zrSq - ziSq + cReal
                                    zrSq = zr * zr
                                    ziSq = zi * zi
                                    if (abs(zr - oldZr) < 1e-5f && abs(zi - oldZi) < 1e-5f) {
                                        iter = scaledMaxIter; break
                                    }
                                    stepCnt++
                                    if (stepCnt >= stepLim) {
                                        oldZr = zr; oldZi = zi; stepCnt = 0
                                        stepLim = (stepLim shl 1).coerceAtMost(scaledMaxIter)
                                    }
                                    iter++
                                }
                                minDist = sqrt(minDistSq)
                            }
                            1 -> {
                                // Circle trap: no rotation, sqrt needed
                                var oldZr = 10f; var oldZi = 10f
                                var stepLim = 2; var stepCnt = 0
                                while (iter < scaledMaxIter) {
                                    val magSq = zrSq + ziSq
                                    if (magSq > 64f) break
                                    val dist = abs(sqrt(magSq) - trapSize)
                                    if (dist < minDist) minDist = dist
                                    zi = 2f * zr * zi + cImag
                                    zr = zrSq - ziSq + cReal
                                    zrSq = zr * zr
                                    ziSq = zi * zi
                                    if (abs(zr - oldZr) < 1e-5f && abs(zi - oldZi) < 1e-5f) {
                                        iter = scaledMaxIter; break
                                    }
                                    stepCnt++
                                    if (stepCnt >= stepLim) {
                                        oldZr = zr; oldZi = zi; stepCnt = 0
                                        stepLim = (stepLim shl 1).coerceAtMost(scaledMaxIter)
                                    }
                                    iter++
                                }
                            }
                            2 -> {
                                // Cross trap: needs rotation
                                var oldZr = 10f; var oldZi = 10f
                                var stepLim = 2; var stepCnt = 0
                                while (iter < scaledMaxIter) {
                                    if (zrSq + ziSq > 64f) break
                                    val rz = zr * cosT - zi * sinT
                                    val iz = zr * sinT + zi * cosT
                                    val dist = min(abs(rz), abs(iz))
                                    if (dist < minDist) minDist = dist
                                    zi = 2f * zr * zi + cImag
                                    zr = zrSq - ziSq + cReal
                                    zrSq = zr * zr
                                    ziSq = zi * zi
                                    if (abs(zr - oldZr) < 1e-5f && abs(zi - oldZi) < 1e-5f) {
                                        iter = scaledMaxIter; break
                                    }
                                    stepCnt++
                                    if (stepCnt >= stepLim) {
                                        oldZr = zr; oldZi = zi; stepCnt = 0
                                        stepLim = (stepLim shl 1).coerceAtMost(scaledMaxIter)
                                    }
                                    iter++
                                }
                            }
                            else -> {
                                // Square trap: needs rotation
                                var oldZr = 10f; var oldZi = 10f
                                var stepLim = 2; var stepCnt = 0
                                while (iter < scaledMaxIter) {
                                    if (zrSq + ziSq > 64f) break
                                    val rz = zr * cosT - zi * sinT
                                    val iz = zr * sinT + zi * cosT
                                    val dist = min(
                                        abs(abs(rz) - trapSize),
                                        abs(abs(iz) - trapSize)
                                    )
                                    if (dist < minDist) minDist = dist
                                    zi = 2f * zr * zi + cImag
                                    zr = zrSq - ziSq + cReal
                                    zrSq = zr * zr
                                    ziSq = zi * zi
                                    if (abs(zr - oldZr) < 1e-5f && abs(zi - oldZi) < 1e-5f) {
                                        iter = scaledMaxIter; break
                                    }
                                    stepCnt++
                                    if (stepCnt >= stepLim) {
                                        oldZr = zr; oldZi = zi; stepCnt = 0
                                        stepLim = (stepLim shl 1).coerceAtMost(scaledMaxIter)
                                    }
                                    iter++
                                }
                            }
                        }

                        val rawT = (minDist * invTrapSize).coerceIn(0f, 1f)
                        val shifted = ((rawT + timeShift) % 1f + 1f) % 1f
                        val lutIdx = (shifted * lutMax).toInt().coerceIn(0, lutMax)
                        renderPixels[rowOff + px] = if (iter >= scaledMaxIter) darkLut[lutIdx] else paletteLut[lutIdx]
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }

        // Upscale to full bitmap if rendered at reduced resolution
        if (renderW < w) {
            val pixels = IntArray(w * h)
            val xMap = IntArray(w) { (it * renderW / w).coerceAtMost(renderW - 1) }
            for (py in 0 until h) {
                val sy = (py * renderH / h).coerceAtMost(renderH - 1)
                val srcOff = sy * renderW
                val dstOff = py * w
                for (px in 0 until w) {
                    pixels[dstOff + px] = renderPixels[srcOff + xMap[px]]
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        } else {
            bitmap.setPixels(renderPixels, 0, w, 0, 0, w, h)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 80
        return (maxIter / 400f).coerceIn(0.2f, 1f)
    }
}
