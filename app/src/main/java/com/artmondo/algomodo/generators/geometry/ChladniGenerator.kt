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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class ChladniGenerator : Generator {

    override val id = "chladni"
    override val family = "geometry"
    override val styleName = "Chladni Patterns"
    override val definition =
        "Chladni vibration figures showing nodal patterns of a vibrating plate, mapped to palette colors."
    override val algorithmNotes =
        "Evaluates the Chladni equation cos(m*pi*x)*cos(n*pi*y) - cos(n*pi*x)*cos(m*pi*y) " +
        "for each pixel, where x and y are normalized to [0,1]. Supports square, circular, " +
        "sum, and product formulas. Color modes: nodal lines, full amplitude, phase, or signed S-curve."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam(
            name = "M Frequency",
            key = "m",
            group = ParamGroup.GEOMETRY,
            help = "Horizontal mode number \u2014 together with N determines the resonant mode shape",
            min = 1f, max = 12f, step = 1f, default = 3f
        ),
        Parameter.NumberParam(
            name = "N Frequency",
            key = "n",
            group = ParamGroup.GEOMETRY,
            help = "Vertical mode number",
            min = 1f, max = 12f, step = 1f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Line Width",
            key = "tolerance",
            group = ParamGroup.GEOMETRY,
            help = "Threshold around zero \u2014 wider = thicker nodal lines",
            min = 0.005f, max = 0.12f, step = 0.005f, default = 0.025f
        ),
        Parameter.SelectParam(
            name = "Formula",
            key = "formula",
            group = ParamGroup.COMPOSITION,
            help = "square: classic rectangular plate | circular: circular membrane, Bessel-like rings | sum: additive superposition | product: multiplicative coupling",
            options = listOf("square", "circular", "sum", "product"),
            default = "square"
        ),
        Parameter.NumberParam(
            name = "Beat Mix",
            key = "beatMix",
            group = ParamGroup.COMPOSITION,
            help = "Blend between mode (m, n) and mode (n, m) with a time-oscillating weight. 0 = pure (m,n) mode. 1 = full beat oscillation.",
            min = 0f, max = 1f, step = 0.05f, default = 0f
        ),
        Parameter.SelectParam(
            name = "Color Mode",
            key = "colorMode",
            group = ParamGroup.COLOR,
            help = "nodal: only nodal lines lit | amplitude: full field filled by wave amplitude | phase: positive and negative regions | signed: smooth tanh S-curve",
            options = listOf("nodal", "amplitude", "phase", "signed"),
            default = "nodal"
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Phase evolution speed \u2014 animates nodal line morphing",
            min = 0.05f, max = 3f, step = 0.05f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "m" to 3f,
        "n" to 5f,
        "tolerance" to 0.025f,
        "formula" to "square",
        "beatMix" to 0f,
        "colorMode" to "nodal",
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
        val baseM = (params["m"] as? Number)?.toFloat() ?: 3f
        val baseN = (params["n"] as? Number)?.toFloat() ?: 5f
        val tolerance = (params["tolerance"] as? Number)?.toFloat() ?: 0.025f
        val formula = (params["formula"] as? String) ?: "square"
        val beatMix = (params["beatMix"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "nodal"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        // Animate m and n slowly, controlled by speed
        val m = baseM + 0.5f * sin(time * speed * 0.3f)
        val n = baseN + 0.5f * cos(time * speed * 0.24f)

        // Beat mixing: blend between (m,n) and (n,m) modes
        val beatWeight = if (beatMix > 0f) {
            beatMix * (sin(time * speed * 0.5f) * 0.5f + 0.5f)
        } else 0f

        val piM = m * PI.toFloat()
        val piN = n * PI.toFloat()

        val pixels = IntArray(w * h)

        for (py in 0 until h) {
            val y = py.toFloat() / h
            for (px in 0 until w) {
                val x = px.toFloat() / w

                val value = when (formula) {
                    "circular" -> {
                        // Circular membrane — radial + angular modulation
                        val cx = x - 0.5f
                        val cy = y - 0.5f
                        val r = sqrt(cx * cx + cy * cy) * 2f
                        val theta = kotlin.math.atan2(cy, cx)
                        cos(piM * r) * cos(n * theta) - cos(piN * r) * sin(m * theta)
                    }
                    "sum" -> {
                        // Additive superposition of two modes
                        val v1 = cos(piM * x) * cos(piN * y) - cos(piN * x) * cos(piM * y)
                        val v2 = cos(piN * x) * cos(piM * y) - cos(piM * x) * cos(piN * y)
                        (v1 + v2) * 0.5f
                    }
                    "product" -> {
                        // Multiplicative coupling
                        val v1 = cos(piM * x) * cos(piN * y)
                        val v2 = cos(piN * x) * cos(piM * y)
                        v1 * v2
                    }
                    else -> {
                        // "square" — classic Chladni
                        cos(piM * x) * cos(piN * y) - cos(piN * x) * cos(piM * y)
                    }
                }

                // Apply beat mixing
                val finalValue = if (beatWeight > 0f) {
                    val swapped = when (formula) {
                        "circular" -> {
                            val cx = x - 0.5f
                            val cy = y - 0.5f
                            val r = sqrt(cx * cx + cy * cy) * 2f
                            val theta = kotlin.math.atan2(cy, cx)
                            cos(piN * r) * cos(m * theta) - cos(piM * r) * sin(n * theta)
                        }
                        else -> cos(piN * x) * cos(piM * y) - cos(piM * x) * cos(piN * y)
                    }
                    value * (1f - beatWeight) + swapped * beatWeight
                } else value

                // Map to color based on color mode
                val color = when (colorMode) {
                    "nodal" -> {
                        // Bright where |value| is near zero (nodal lines)
                        val dist = abs(finalValue)
                        if (dist < tolerance) {
                            val t = (1f - dist / tolerance).coerceIn(0f, 1f)
                            palette.lerpColor(t * 0.8f + 0.1f)
                        } else {
                            Color.BLACK
                        }
                    }
                    "amplitude" -> {
                        // Full field colored by absolute amplitude
                        val normalized = ((finalValue + 2f) / 4f).coerceIn(0f, 1f)
                        palette.lerpColor(normalized)
                    }
                    "phase" -> {
                        // Two-tone: positive vs negative regions
                        if (finalValue > tolerance * 0.5f) {
                            palette.lerpColor(0.8f)
                        } else if (finalValue < -tolerance * 0.5f) {
                            palette.lerpColor(0.2f)
                        } else {
                            // Nodal boundary — bright line
                            palette.lerpColor(0.5f)
                        }
                    }
                    "signed" -> {
                        // Smooth S-curve mapping using tanh
                        val curved = (tanh(finalValue * 4f) + 1f) * 0.5f
                        palette.lerpColor(curved.coerceIn(0f, 1f))
                    }
                    else -> {
                        val normalized = ((finalValue + 2f) / 4f).coerceIn(0f, 1f)
                        palette.lerpColor(normalized)
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
