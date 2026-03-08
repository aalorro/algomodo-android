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

class NewtonGenerator : Generator {

    override val id = "fractal-newton"
    override val family = "fractals"
    override val styleName = "Newton Fractal"
    override val definition =
        "Newton fractal formed by applying Newton's root-finding method to complex polynomials and coloring by convergence root."
    override val algorithmNotes =
        "For each pixel as a complex number z0, iterates z = z - damping * f(z)/f'(z) for " +
        "the selected polynomial. The pixel is colored based on which root z converges to " +
        "(using palette color per root), with brightness modulated by the number of iterations " +
        "needed. Damping != 1 creates nova fractal effects with richer basin boundaries."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Power", "power", ParamGroup.COMPOSITION, "Degree of the polynomial z^n - 1 (determines number of roots)", 2f, 6f, 1f, 3f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Maximum Newton iterations per pixel", 16f, 64f, 8f, 32f),
        Parameter.NumberParam("Damping", "damping", ParamGroup.GEOMETRY, "Relaxation factor — 1 = standard Newton, other values create nova fractals", 0.5f, 1.5f, 0.05f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "root: color by converged root | iteration: shade by speed | blended: both combined", listOf("root", "iteration", "blended"), "blended"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Speed of damping animation", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "power" to 3f,
        "zoom" to 1f,
        "maxIterations" to 32f,
        "damping" to 1f,
        "colorMode" to "blended",
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
        val power = (params["power"] as? Number)?.toInt() ?: 3
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 32
        val baseDamping = (params["damping"] as? Number)?.toDouble() ?: 1.0
        val colorMode = (params["colorMode"] as? String) ?: "blended"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val tolSq = 1e-6

        // Animate damping for nova fractal morphing
        val damping = baseDamping + sin(time.toDouble() * speed * 0.3) * 0.1

        // Compute roots of z^n - 1 (nth roots of unity)
        val roots = Array(power) { k ->
            val angle = 2.0 * PI * k / power
            doubleArrayOf(cos(angle), sin(angle))
        }

        val aspect = w.toDouble() / h.toDouble()
        val rangeY = 3.0 / zoom
        val rangeX = rangeY * aspect

        // Gentle view rotation for animation
        val rotAngle = time * speed * 0.08
        val cosR = cos(rotAngle)
        val sinR = sin(rotAngle)

        val pixels = IntArray(w * h)
        val paletteColors = palette.colorInts()

        for (py in 0 until h) {
            for (px in 0 until w) {
                val rawX = (px.toDouble() / w - 0.5) * rangeX
                val rawY = (py.toDouble() / h - 0.5) * rangeY
                var zr = rawX * cosR - rawY * sinR
                var zi = rawX * sinR + rawY * cosR

                var iter = 0
                var rootIndex = -1

                while (iter < maxIter) {
                    // Compute z^n and z^(n-1) for f(z) = z^n - 1, f'(z) = n * z^(n-1)
                    // z^(n-1) by repeated multiplication
                    var pr = 1.0; var pi = 0.0  // accumulates z^k
                    for (k in 0 until power - 1) {
                        val nr = pr * zr - pi * zi
                        val ni = pr * zi + pi * zr
                        pr = nr; pi = ni
                    }
                    // z^(n-1) = (pr, pi)
                    // z^n = z^(n-1) * z
                    val znr = pr * zr - pi * zi
                    val zni = pr * zi + pi * zr

                    // f(z) = z^n - 1
                    val fr = znr - 1.0
                    val fi = zni

                    // f'(z) = n * z^(n-1)
                    val fpr = power * pr
                    val fpi = power * pi

                    // f(z) / f'(z) = (fr + fi*i) / (fpr + fpi*i)
                    val denom = fpr * fpr + fpi * fpi
                    if (denom < 1e-20) break

                    val qr = (fr * fpr + fi * fpi) / denom
                    val qi = (fi * fpr - fr * fpi) / denom

                    // z = z - damping * f(z)/f'(z)
                    zr -= damping * qr
                    zi -= damping * qi

                    // Check convergence to any root
                    for (ri in roots.indices) {
                        val dr = zr - roots[ri][0]
                        val di = zi - roots[ri][1]
                        if (dr * dr + di * di < tolSq) {
                            rootIndex = ri
                            break
                        }
                    }
                    if (rootIndex >= 0) break
                    iter++
                }

                val color = when {
                    rootIndex < 0 -> Color.BLACK
                    else -> {
                        val baseColor = paletteColors[rootIndex % paletteColors.size]
                        val iterFrac = iter.toFloat() / maxIter

                        when (colorMode) {
                            "root" -> {
                                // Pure root color with subtle shading
                                val bright = 0.95f - iterFrac * 0.15f
                                Color.rgb(
                                    (Color.red(baseColor) * bright).toInt().coerceIn(0, 255),
                                    (Color.green(baseColor) * bright).toInt().coerceIn(0, 255),
                                    (Color.blue(baseColor) * bright).toInt().coerceIn(0, 255)
                                )
                            }
                            "iteration" -> {
                                // Color by iteration count using full palette
                                val t = (1f - iterFrac).coerceIn(0f, 1f)
                                palette.lerpColor(t)
                            }
                            else -> {
                                // Blended: root color modulated by convergence speed
                                val bright = 1f - iterFrac * 0.7f
                                Color.rgb(
                                    (Color.red(baseColor) * bright).toInt().coerceIn(0, 255),
                                    (Color.green(baseColor) * bright).toInt().coerceIn(0, 255),
                                    (Color.blue(baseColor) * bright).toInt().coerceIn(0, 255)
                                )
                            }
                        }
                    }
                }

                pixels[py * w + px] = color
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 32
        val power = (params["power"] as? Number)?.toInt() ?: 3
        return (maxIter * power / 300f).coerceIn(0.2f, 1f)
    }
}
