package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MoireGenerator : Generator {

    override val id = "geo-moire"
    override val family = "geometry"
    override val styleName = "Moire Patterns"
    override val definition =
        "Optical moire interference patterns created by overlaying two offset copies of circles, lines, grids, dots, or radial patterns."
    override val algorithmNotes =
        "For each pixel, computes the intensity from two overlapping pattern layers. " +
        "The first layer is centered at the canvas center; the second is offset and rotated. " +
        "Each layer produces a periodic value based on the pattern type (distance for circles, " +
        "projection for lines, combined axes for dots/grids, angular for radial). " +
        "The two values are multiplied to produce the interference pattern."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            name = "Pattern",
            key = "pattern",
            group = ParamGroup.GEOMETRY,
            help = "Base pattern: two copies are overlaid at offset angle/position to create interference",
            options = listOf("lines", "circles", "dots", "radial"),
            default = "lines"
        ),
        Parameter.NumberParam(
            name = "Frequency",
            key = "frequency",
            group = ParamGroup.GEOMETRY,
            help = "Lines/circles per unit — higher = finer grating, more complex moiré bands",
            min = 2f, max = 60f, step = 1f, default = 20f
        ),
        Parameter.NumberParam(
            name = "Angle (°)",
            key = "angle",
            group = ParamGroup.GEOMETRY,
            help = "Relative rotation between the two overlapping patterns — small angles → wide beating bands",
            min = 0f, max = 90f, step = 0.5f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Offset",
            key = "offset",
            group = ParamGroup.GEOMETRY,
            help = "Translational offset of the second pattern (pixels)",
            min = 0f, max = 50f, step = 1f, default = 0f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "palette: interference mapped to palette gradient | bw: black & white | complement: dual-tone palette ends",
            options = listOf("palette", "bw", "complement"),
            default = "palette"
        ),
        Parameter.SelectParam(
            name = "Anim Mode",
            key = "animMode",
            group = ParamGroup.FLOW_MOTION,
            help = "rotate: angle drifts continuously | slide: offset translates | zoom: frequency oscillates",
            options = listOf("rotate", "slide", "zoom"),
            default = "rotate"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.1f, max = 3f, step = 0.1f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pattern" to "lines",
        "frequency" to 20f,
        "angle" to 5f,
        "offset" to 0f,
        "colorMode" to "palette",
        "animMode" to "rotate",
        "speed" to 0.5f
    )

    private fun patternValue(
        x: Float, y: Float, cx: Float, cy: Float,
        spacing: Float, pattern: String, rotation: Float
    ): Float {
        val dx = x - cx
        val dy = y - cy
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        val rx = dx * cosR - dy * sinR
        val ry = dx * sinR + dy * cosR
        val twoPiOverSpacing = 2f * PI.toFloat() / spacing

        return when (pattern) {
            "circles" -> {
                val dist = sqrt(rx * rx + ry * ry)
                sin(dist * twoPiOverSpacing) * 0.5f + 0.5f
            }
            "lines" -> {
                sin(rx * twoPiOverSpacing) * 0.5f + 0.5f
            }
            "dots" -> {
                // Dot grid: product of two orthogonal cosines — peaks form a dot lattice
                val vx = cos(rx * twoPiOverSpacing) * 0.5f + 0.5f
                val vy = cos(ry * twoPiOverSpacing) * 0.5f + 0.5f
                vx * vy
            }
            "radial" -> {
                // Radial/angular pattern — rays emanating from center
                val angle = atan2(ry, rx)
                sin(angle * spacing * 0.5f) * 0.5f + 0.5f
            }
            else -> {
                sin(rx * twoPiOverSpacing) * 0.5f + 0.5f
            }
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
        val w = bitmap.width
        val h = bitmap.height
        val pattern = (params["pattern"] as? String) ?: "lines"
        val baseFrequency = (params["frequency"] as? Number)?.toFloat() ?: 20f
        val baseAngle = (params["angle"] as? Number)?.toFloat() ?: 5f
        val baseOffset = (params["offset"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val animMode = (params["animMode"] as? String) ?: "rotate"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        // Apply animation based on animMode
        val animAngle: Float
        val animOffset: Float
        val animFrequency: Float

        when (animMode) {
            "rotate" -> {
                animAngle = baseAngle + time * speed * 4f
                animOffset = baseOffset
                animFrequency = baseFrequency
            }
            "slide" -> {
                animAngle = baseAngle
                animOffset = baseOffset + sin(time * speed * 0.5f) * 20f
                animFrequency = baseFrequency
            }
            "zoom" -> {
                animAngle = baseAngle
                animOffset = baseOffset
                animFrequency = baseFrequency + sin(time * speed * 0.3f) * baseFrequency * 0.3f
            }
            else -> {
                animAngle = baseAngle + time * speed * 4f
                animOffset = baseOffset
                animFrequency = baseFrequency
            }
        }

        val rotationRad = animAngle * PI.toFloat() / 180f
        val cx = w / 2f
        val cy = h / 2f

        val pixels = IntArray(w * h)

        for (py in 0 until h) {
            for (px in 0 until w) {
                // Layer 1: centered, no rotation
                val v1 = patternValue(px.toFloat(), py.toFloat(), cx, cy, animFrequency, pattern, 0f)
                // Layer 2: offset and rotated
                val v2 = patternValue(
                    px.toFloat(), py.toFloat(),
                    cx + animOffset * animFrequency * 0.1f, cy + animOffset * animFrequency * 0.1f,
                    animFrequency, pattern, rotationRad
                )

                // Interference: multiply layers
                val interference = v1 * v2

                val color = when (colorMode) {
                    "bw" -> {
                        val bri = (interference * 255).toInt().coerceIn(0, 255)
                        Color.rgb(bri, bri, bri)
                    }
                    "complement" -> {
                        // Map to two ends of the palette
                        if (interference > 0.5f) {
                            palette.lerpColor((interference - 0.5f) * 2f)
                        } else {
                            palette.lerpColor(1f - interference * 2f)
                        }
                    }
                    else -> {
                        // "palette" — full gradient mapping
                        palette.lerpColor(interference)
                    }
                }

                pixels[py * w + px] = color
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.3f
}
