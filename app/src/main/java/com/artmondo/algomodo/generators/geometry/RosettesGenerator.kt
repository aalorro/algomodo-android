package com.artmondo.algomodo.generators.geometry

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class RosettesGenerator : Generator {

    override val id = "geo-rosettes"
    override val family = "geometry"
    override val styleName = "Rosette / Mandala"
    override val definition =
        "Concentric rosette and mandala patterns with rotational symmetry, layered petal structures, and palette-mapped colors."
    override val algorithmNotes =
        "Draws multiple layers of rose curves using the polar equation r = cos(n/d * theta). " +
        "Each layer uses a different n/d ratio offset by layerSpread. " +
        "Animation modes: spin rotates curves, bloom oscillates radius, morph drifts the n/d ratio."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "Numerator (n)",
            key = "numerator",
            group = ParamGroup.GEOMETRY,
            help = "Rose curve numerator \u2014 r = cos(n/d \u00b7 \u03b8). Odd n with odd d \u2192 n petals; even n \u2192 2n petals",
            min = 1f, max = 20f, step = 1f, default = 7f
        ),
        Parameter.NumberParam(
            name = "Denominator (d)",
            key = "denominator",
            group = ParamGroup.GEOMETRY,
            help = "Rose curve denominator \u2014 controls petal density and winding number",
            min = 1f, max = 20f, step = 1f, default = 4f
        ),
        Parameter.NumberParam(
            name = "Layers",
            key = "layers",
            group = ParamGroup.COMPOSITION,
            help = "Number of overlapping rose curves with staggered n/d ratios",
            min = 1f, max = 8f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "Layer Spread",
            key = "layerSpread",
            group = ParamGroup.COMPOSITION,
            help = "How much the n/d ratio shifts between layers",
            min = 0f, max = 3f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Radius",
            key = "radius",
            group = ParamGroup.GEOMETRY,
            help = "Fraction of half-canvas used as the maximum petal radius",
            min = 0.2f, max = 0.95f, step = 0.05f, default = 0.45f
        ),
        Parameter.NumberParam(
            name = "Stroke Width",
            key = "strokeWidth",
            group = ParamGroup.TEXTURE,
            help = null,
            min = 0.5f, max = 6f, step = 0.5f, default = 1.5f
        ),
        Parameter.NumberParam(
            name = "Resolution",
            key = "resolution",
            group = ParamGroup.COMPOSITION,
            help = "Steps per full curve traversal \u2014 higher = smoother petals",
            min = 500f, max = 8000f, step = 500f, default = 3000f
        ),
        Parameter.SelectParam(
            name = "Anim Mode",
            key = "animMode",
            group = ParamGroup.FLOW_MOTION,
            help = "spin: rotate all curves | bloom: layers phase-shift at different rates | morph: n/d ratio drifts slowly",
            options = listOf("spin", "bloom", "morph"),
            default = "spin"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = null,
            min = 0.05f, max = 2f, step = 0.05f, default = 0.3f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "numerator" to 7f,
        "denominator" to 4f,
        "layers" to 3f,
        "layerSpread" to 1.0f,
        "radius" to 0.45f,
        "strokeWidth" to 1.5f,
        "resolution" to 3000f,
        "animMode" to "spin",
        "speed" to 0.3f
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
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val baseN = (params["numerator"] as? Number)?.toFloat() ?: 7f
        val baseD = (params["denominator"] as? Number)?.toFloat() ?: 4f
        val layers = (params["layers"] as? Number)?.toInt() ?: 3
        val layerSpread = (params["layerSpread"] as? Number)?.toFloat() ?: 1.0f
        val radiusFrac = (params["radius"] as? Number)?.toFloat() ?: 0.45f
        val strokeWidth = (params["strokeWidth"] as? Number)?.toFloat() ?: 1.5f
        val resolution = (params["resolution"] as? Number)?.toInt() ?: 3000
        val animMode = (params["animMode"] as? String) ?: "spin"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.3f

        canvas.drawColor(Color.BLACK)

        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = min(w, h) / 2f * radiusFrac

        val scaledResolution = when (quality) {
            Quality.DRAFT -> (resolution / 2).coerceAtLeast(500)
            Quality.BALANCED -> resolution
            Quality.ULTRA -> (resolution * 1.5f).toInt()
        }

        val paint = Paint().apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = quality != Quality.DRAFT
            strokeCap = Paint.Cap.ROUND
        }

        val paletteColors = palette.colorInts()

        for (layer in 0 until layers) {
            val layerFrac = layer.toFloat() / layers.coerceAtLeast(1)

            // Each layer gets a different n/d ratio shifted by layerSpread
            val layerN = baseN + layer * layerSpread
            val layerD = baseD
            val k = layerN / layerD

            // Radius scale: layers have slightly different sizes
            val layerRadius = maxRadius * (0.6f + layerFrac * 0.4f)

            // Animation offsets per mode
            val angleOffset: Float
            val radiusScale: Float
            val nMorph: Float

            when (animMode) {
                "bloom" -> {
                    // Each layer oscillates radius at different phase
                    angleOffset = 0f
                    radiusScale = 0.7f + 0.3f * sin(time * speed * 0.5f + layer * PI.toFloat() / layers)
                    nMorph = 0f
                }
                "morph" -> {
                    // n/d ratio drifts slowly over time
                    angleOffset = 0f
                    radiusScale = 1f
                    nMorph = sin(time * speed * 0.15f) * 0.5f
                }
                else -> {
                    // "spin": rotate all curves, each layer at slightly different rate
                    angleOffset = time * speed * 0.2f * (1f + layer * 0.3f)
                    radiusScale = 1f
                    nMorph = 0f
                }
            }

            val effectiveK = k + nMorph
            val effectiveRadius = layerRadius * radiusScale

            // Rose curve needs theta range of 2*PI*d to close (or 2*PI if d divides n)
            val thetaMax = 2f * PI.toFloat() * layerD

            paint.color = paletteColors[layer % paletteColors.size]
            paint.alpha = (180 + layer * 20).coerceAtMost(255)

            val path = Path()
            var first = true

            for (i in 0..scaledResolution) {
                val theta = thetaMax * i / scaledResolution + angleOffset
                val r = abs(cos(effectiveK * theta)) * effectiveRadius
                val px = cx + r * cos(theta)
                val py = cy + r * sin(theta)

                if (first) {
                    path.moveTo(px, py)
                    first = false
                } else {
                    path.lineTo(px, py)
                }
            }
            path.close()
            canvas.drawPath(path, paint)

            // Draw filled version with low alpha for depth
            val fillPaint = Paint().apply {
                style = Paint.Style.FILL
                color = paletteColors[layer % paletteColors.size]
                alpha = 25 + layer * 10
                isAntiAlias = quality != Quality.DRAFT
            }
            canvas.drawPath(path, fillPaint)
        }

        // Central dot
        val dotPaint = Paint().apply {
            style = Paint.Style.FILL
            color = paletteColors[0]
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, maxRadius * 0.02f, dotPaint)
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val layers = (params["layers"] as? Number)?.toInt() ?: 3
        val resolution = (params["resolution"] as? Number)?.toInt() ?: 3000
        return (layers * resolution / 30000f).coerceIn(0.2f, 1f)
    }
}
