package com.artmondo.algomodo.generators.geometry

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.PI
import kotlin.math.sin

/**
 * GPU-rendered moire interference patterns. Two overlaid copies of a base
 * pattern (lines / circles / dots / radial) produce per-pixel interference
 * via multiplication.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved so existing presets continue to load.
 */
class MoireGenerator : GpuGenerator {

    override val id = "geo-moire"
    override val family = "geometry"
    override val styleName = "Moire Patterns"
    override val definition =
        "Optical moire interference patterns created by overlaying two offset copies of circles, lines, grids, dots, or radial patterns."
    override val algorithmNotes =
        "Per-pixel fragment shader on the GPU. Two layers of the chosen base pattern (lines/circles/" +
        "dots/radial) are evaluated independently — one centered, one offset and rotated — and " +
        "multiplied to produce the moire beat. Output is mapped via palette/bw/complement colour modes."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            name = "Pattern", key = "pattern", group = ParamGroup.GEOMETRY,
            help = "Base pattern: two copies are overlaid at offset angle/position to create interference",
            options = listOf("lines", "circles", "dots", "radial"), default = "lines"
        ),
        Parameter.NumberParam(
            name = "Frequency", key = "frequency", group = ParamGroup.GEOMETRY,
            help = "Lines/circles per unit \u2014 higher = finer grating, more complex moir\u00e9 bands",
            min = 2f, max = 60f, step = 1f, default = 20f
        ),
        Parameter.NumberParam(
            name = "Angle (\u00b0)", key = "angle", group = ParamGroup.GEOMETRY,
            help = "Relative rotation between the two overlapping patterns \u2014 small angles \u2192 wide beating bands",
            min = 0f, max = 90f, step = 0.5f, default = 5f
        ),
        Parameter.NumberParam(
            name = "Offset", key = "offset", group = ParamGroup.GEOMETRY,
            help = "Translational offset of the second pattern (pixels)",
            min = 0f, max = 50f, step = 1f, default = 0f
        ),
        Parameter.SelectParam(
            name = "Color Mode", key = "colorMode", group = ParamGroup.COLOR,
            help = "palette: interference mapped to palette gradient | bw: black & white | complement: dual-tone palette ends",
            options = listOf("palette", "bw", "complement"), default = "palette"
        ),
        Parameter.SelectParam(
            name = "Anim Mode", key = "animMode", group = ParamGroup.FLOW_MOTION,
            help = "rotate: angle drifts continuously | slide: offset translates | zoom: frequency oscillates",
            options = listOf("rotate", "slide", "zoom"), default = "rotate"
        ),
        Parameter.NumberParam(
            name = "Speed", key = "speed", group = ParamGroup.FLOW_MOTION, help = null,
            min = 0.1f, max = 3f, step = 0.1f, default = 0.5f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "pattern" to "lines", "frequency" to 20f, "angle" to 5f, "offset" to 0f,
        "colorMode" to "palette", "animMode" to "rotate", "speed" to 0.5f
    )

    override fun bindUniforms(
        programId: Int,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float,
        width: Int,
        height: Int
    ) {
        val pattern = (params["pattern"] as? String) ?: "lines"
        val baseFrequency = (params["frequency"] as? Number)?.toFloat() ?: 20f
        val baseAngle = (params["angle"] as? Number)?.toFloat() ?: 5f
        val baseOffset = (params["offset"] as? Number)?.toFloat() ?: 0f
        val colorMode = (params["colorMode"] as? String) ?: "palette"
        val animMode = (params["animMode"] as? String) ?: "rotate"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

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
        val patternId = when (pattern) {
            "lines" -> 0; "circles" -> 1; "dots" -> 2; "radial" -> 3
            else -> 0
        }
        val colorId = when (colorMode) {
            "palette" -> 0; "bw" -> 1; "complement" -> 2
            else -> 0
        }

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPatternId"), patternId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFrequency"), animFrequency)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uOffset"), animOffset)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRotation"), rotationRad)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform int   uPatternId;   // 0 lines, 1 circles, 2 dots, 3 radial
        uniform int   uColorMode;   // 0 palette, 1 bw, 2 complement
        uniform float uFrequency;
        uniform float uOffset;
        uniform float uRotation;

        out vec4 fragColor;

        const float TAU = 6.28318530718;

        float patternValue(vec2 frag, vec2 center, float spacing, float rotation) {
            vec2 d = frag - center;
            float cR = cos(rotation);
            float sR = sin(rotation);
            float rx = d.x * cR - d.y * sR;
            float ry = d.x * sR + d.y * cR;
            float tps = TAU / spacing;

            if (uPatternId == 1) {
                // circles
                float dist = length(vec2(rx, ry));
                return sin(dist * tps) * 0.5 + 0.5;
            } else if (uPatternId == 2) {
                // dots
                float vx = cos(rx * tps) * 0.5 + 0.5;
                float vy = cos(ry * tps) * 0.5 + 0.5;
                return vx * vy;
            } else if (uPatternId == 3) {
                // radial
                float ang = atan(ry, rx);
                return sin(ang * spacing * 0.5) * 0.5 + 0.5;
            } else {
                // lines
                return sin(rx * tps) * 0.5 + 0.5;
            }
        }

        void main() {
            float w = uResolution.x;
            float h = uResolution.y;
            float cx = w * 0.5;
            float cy = h * 0.5;
            vec2 frag = gl_FragCoord.xy;

            float v1 = patternValue(frag, vec2(cx, cy), uFrequency, 0.0);
            float offShift = uOffset * uFrequency * 0.1;
            float v2 = patternValue(frag,
                vec2(cx + offShift, cy + offShift),
                uFrequency, uRotation);

            float interference = clamp(v1 * v2, 0.0, 1.0);

            vec3 col;
            if (uColorMode == 1) {
                // bw
                col = vec3(interference);
            } else if (uColorMode == 2) {
                // complement: dual-tone palette ends
                float t = (interference > 0.5)
                    ? (interference - 0.5) * 2.0
                    : (1.0 - interference * 2.0);
                col = palette_color(clamp(t, 0.0, 1.0));
            } else {
                // palette
                col = palette_color(interference);
            }

            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.15f
}
