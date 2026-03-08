package com.artmondo.algomodo.generators.noise

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

class NoiseSimplexFieldGenerator : Generator {

    override val id = "noise-simplex-field"
    override val family = "noise"
    override val styleName = "Simplex Noise Field"
    override val definition =
        "Simplex noise field visualised as colours, directional lines, or magnitude circles."
    override val algorithmNotes =
        "In 'color' mode, every pixel is sampled from multi-octave simplex noise and mapped through the palette. " +
        "In 'direction' mode a grid of short lines whose angle is driven by noise. " +
        "In 'magnitude' mode filled circles sized by noise value. " +
        "All modes support octaves, domain warping, color banding, and multiple animation modes."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.COMPOSITION, "Noise frequency", 0.5f, 12f, 0.5f, 3f),
        Parameter.NumberParam("Octaves", "octaves", ParamGroup.COMPOSITION, "Noise layers — more = finer detail", 1f, 6f, 1f, 1f),
        Parameter.SelectParam("Style", "style", ParamGroup.GEOMETRY, "smooth: pixel colors | ridged: directional lines | turbulent: magnitude circles", listOf("smooth", "ridged", "turbulent"), "smooth"),
        Parameter.NumberParam("Warp Amount", "warpAmount", ParamGroup.COMPOSITION, "Domain warping for organic distortion", 0f, 2f, 0.1f, 0f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "palette: smooth gradient | bands: hard contour steps", listOf("palette", "bands"), "palette"),
        Parameter.NumberParam("Band Count", "bandCount", ParamGroup.COLOR, "Number of contour bands (bands mode)", 2f, 24f, 1f, 6f),
        Parameter.SelectParam("Anim Mode", "animMode", ParamGroup.FLOW_MOTION, "drift: pan through field | rotate: spin coordinates", listOf("drift", "rotate"), "drift"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 3f, "octaves" to 1f, "style" to "smooth", "warpAmount" to 0f,
        "colorMode" to "palette", "bandCount" to 6f, "animMode" to "drift", "speed" to 0.5f
    )

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>,
        seed: Int, palette: Palette, quality: Quality, time: Float
    ) {
        val w = bitmap.width; val h = bitmap.height
        val scale = (params["scale"] as? Number)?.toFloat() ?: 3f
        val octaves = (params["octaves"] as? Number)?.toInt() ?: 1
        val style = (params["style"] as? String) ?: "smooth"
        val warpAmount = (params["warpAmount"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val bandCount = (params["bandCount"] as? Number)?.toInt() ?: 6
        val animMode = (params["animMode"] as? String) ?: "drift"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val noise = SimplexNoise(seed)
        val invScale = scale / w.toFloat()
        val cx = w / 2f * invScale; val cy = h / 2f * invScale

        when (style) {
            "smooth" -> renderColorMode(bitmap, canvas, w, h, noise, invScale, cx, cy,
                octaves, warpAmount, colorMode, bandCount, animMode, speed, time, palette, quality)
            "ridged" -> renderDirectionMode(canvas, w, h, noise, invScale, cx, cy,
                octaves, warpAmount, colorMode, bandCount, animMode, speed, time, palette)
            "turbulent" -> renderMagnitudeMode(canvas, w, h, noise, invScale, cx, cy,
                octaves, warpAmount, colorMode, bandCount, animMode, speed, time, palette)
        }
    }

    private fun animateCoords(nx: Float, ny: Float, cx: Float, cy: Float,
                              animMode: String, speed: Float, time: Float): Pair<Float, Float> {
        return when (animMode) {
            "rotate" -> {
                val dx = nx - cx; val dy = ny - cy
                val angle = time * speed * 0.3f
                Pair(cx + dx * cos(angle) - dy * sin(angle),
                     cy + dx * sin(angle) + dy * cos(angle))
            }
            else -> Pair(nx + time * speed * 0.35f, ny + time * speed * 0.2f)
        }
    }

    private fun sampleNoise(noise: SimplexNoise, nx: Float, ny: Float,
                            octaves: Int, warpAmount: Float): Float {
        var x = nx; var y = ny
        if (warpAmount > 0f) {
            val wx = noise.fbm(x + 5.2f, y + 1.3f, 3)
            val wy = noise.fbm(x + 1.7f, y + 9.2f, 3)
            x += wx * warpAmount; y += wy * warpAmount
        }
        return if (octaves <= 1) noise.noise2D(x, y) else noise.fbm(x, y, octaves)
    }

    private fun mapColor(t: Float, colorMode: String, bandCount: Int, palette: Palette): Int {
        return when (colorMode) {
            "bands" -> {
                val band = (t * bandCount).toInt().coerceIn(0, bandCount - 1)
                palette.lerpColor(band.toFloat() / (bandCount - 1).coerceAtLeast(1))
            }
            else -> palette.lerpColor(t)
        }
    }

    private fun renderColorMode(
        bitmap: Bitmap, canvas: Canvas, w: Int, h: Int,
        noise: SimplexNoise, invScale: Float, cx: Float, cy: Float,
        octaves: Int, warpAmount: Float, colorMode: String, bandCount: Int,
        animMode: String, speed: Float, time: Float, palette: Palette, quality: Quality
    ) {
        val pixels = IntArray(w * h)
        val step = if (quality == Quality.DRAFT) 2 else 1

        for (py in 0 until h step step) {
            for (px in 0 until w step step) {
                val (nx, ny) = animateCoords(px * invScale, py * invScale, cx, cy, animMode, speed, time)
                val raw = sampleNoise(noise, nx, ny, octaves, warpAmount)
                val t = ((raw + 1f) * 0.5f).coerceIn(0f, 1f)
                val color = mapColor(t, colorMode, bandCount, palette)

                if (step == 1) {
                    pixels[py * w + px] = color
                } else {
                    for (dy in 0 until step) for (dx in 0 until step) {
                        val fx = px + dx; val fy = py + dy
                        if (fx < w && fy < h) pixels[fy * w + fx] = color
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    private fun renderDirectionMode(
        canvas: Canvas, w: Int, h: Int,
        noise: SimplexNoise, invScale: Float, cx: Float, cy: Float,
        octaves: Int, warpAmount: Float, colorMode: String, bandCount: Int,
        animMode: String, speed: Float, time: Float, palette: Palette
    ) {
        val bgPaint = Paint().apply { color = palette.colorAt(0); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val linePaint = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND
        }

        val spacing = (w / 15f).coerceAtLeast(8f)
        val halfLen = spacing * 0.4f

        var gx = spacing * 0.5f
        while (gx < w) {
            var gy = spacing * 0.5f
            while (gy < h) {
                val (nx, ny) = animateCoords(gx * invScale, gy * invScale, cx, cy, animMode, speed, time)
                val raw = sampleNoise(noise, nx, ny, octaves, warpAmount)
                val angle = raw * PI.toFloat()
                val t = ((raw + 1f) * 0.5f).coerceIn(0f, 1f)
                linePaint.color = mapColor(t, colorMode, bandCount, palette)

                val dx = cos(angle) * halfLen; val dy = sin(angle) * halfLen
                canvas.drawLine(gx - dx, gy - dy, gx + dx, gy + dy, linePaint)
                gy += spacing
            }
            gx += spacing
        }
    }

    private fun renderMagnitudeMode(
        canvas: Canvas, w: Int, h: Int,
        noise: SimplexNoise, invScale: Float, cx: Float, cy: Float,
        octaves: Int, warpAmount: Float, colorMode: String, bandCount: Int,
        animMode: String, speed: Float, time: Float, palette: Palette
    ) {
        val bgPaint = Paint().apply { color = palette.colorAt(0); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val circlePaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

        val spacing = (w / 15f).coerceAtLeast(8f)
        val maxRadius = spacing * 0.45f

        var gx = spacing * 0.5f
        while (gx < w) {
            var gy = spacing * 0.5f
            while (gy < h) {
                val (nx, ny) = animateCoords(gx * invScale, gy * invScale, cx, cy, animMode, speed, time)
                val raw = sampleNoise(noise, nx, ny, octaves, warpAmount)
                val t = ((raw + 1f) * 0.5f).coerceIn(0f, 1f)
                val radius = t * maxRadius
                circlePaint.color = mapColor(t, colorMode, bandCount, palette)
                if (radius > 0.5f) canvas.drawCircle(gx, gy, radius, circlePaint)
                gy += spacing
            }
            gx += spacing
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val style = (params["style"] as? String) ?: "smooth"
        return if (style == "smooth") 0.6f else 0.3f
    }
}
