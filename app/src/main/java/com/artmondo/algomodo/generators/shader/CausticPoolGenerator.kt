package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * Animated water-caustic pattern, GPU-rendered. Cheap iterated sine warp
 * produces shimmering bands that pulse with audio.
 */
class CausticPoolGenerator : GpuGenerator {

    override val id: String = "shader-caustic-pool"
    override val family: String = "shader"
    override val styleName: String = "Caustic Pool"
    override val definition: String = "GPU-rendered animated caustic light pattern"
    override val algorithmNotes: String =
        "Iterated sine-warp domain distortion in a fragment shader, palette-mapped " +
        "by accumulated band intensity. Time and audio drive the warp."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Scale", "scale", ParamGroup.GEOMETRY,
            "Pattern scale", 1f, 12f, 0.1f, 5f),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.TEXTURE,
            "Warp iterations (more = more detail)", 2f, 8f, 1f, 5f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0f, 3f, 0.05f, 0.7f),
        Parameter.NumberParam("Intensity", "intensity", ParamGroup.COLOR,
            "Caustic band brightness", 0.4f, 2.5f, 0.05f, 1.4f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the warp", 0f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "scale" to 5f,
        "iterations" to 5f,
        "speed" to 0.7f,
        "intensity" to 1.4f,
        "audioReact" to 0.4f
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
        val scale = (params["scale"] as? Float) ?: 5f
        val iterations = ((params["iterations"] as? Float) ?: 5f).toInt().coerceIn(2, 8)
        val speed = (params["speed"] as? Float) ?: 0.7f
        val intensity = (params["intensity"] as? Float) ?: 1.4f
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        // Seed-driven phase offset so different seeds produce visually different
        // patterns without affecting parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScale"), scale)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIterations"), iterations)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uIntensity"), intensity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phaseOffset)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform float uScale;
        uniform int uIterations;
        uniform float uSpeed;
        uniform float uIntensity;
        uniform float uAudioReact;
        uniform float uPhase;

        out vec4 fragColor;

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;
            float t = uTime * uSpeed + uPhase;

            // Audio-modulated warp amplitude
            float warpAmp = 0.6 + 0.5 * uAudio.x * uAudioReact;

            vec2 p = uv * uScale;
            vec2 q = p;
            for (int k = 1; k < 16; k++) {
                if (k > uIterations) break;
                float fk = float(k);
                q.x = p.x + (warpAmp / fk) * sin(fk * p.y + t + 0.3 * fk) + 0.6;
                q.y = p.y + (warpAmp / fk) * sin(fk * p.x + t - 0.4 * fk) + 1.6;
                p = q;
            }

            // Caustic band intensity from accumulated warp position
            float v = 0.5 + 0.5 * sin(q.x * 1.4 + q.y * 1.4);
            v = pow(v, 1.5);
            float intensity = uIntensity * (0.85 + 0.4 * uAudio.y * uAudioReact);

            // High frequencies drive a sparkly highlight
            float sparkle = pow(v, 8.0) * (0.5 + 0.8 * uAudio.z * uAudioReact);

            vec3 col = palette_color(clamp(v * intensity, 0.0, 1.0));
            col += vec3(sparkle) * 0.4;

            // Soft vignette
            float r = length(uv);
            col *= smoothstep(1.3, 0.2, r);

            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
