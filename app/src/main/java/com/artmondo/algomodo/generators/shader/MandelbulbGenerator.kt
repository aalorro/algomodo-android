package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched 3D Mandelbulb fractal. Animates by orbiting the camera
 * and slowly varying the bulb power.
 *
 * Audio reactivity: bass energy nudges the bulb power, mids/highs modulate
 * the surface glow intensity.
 */
class MandelbulbGenerator : GpuGenerator {

    override val id: String = "shader-mandelbulb"
    override val family: String = "shader"
    override val styleName: String = "Mandelbulb"
    override val definition: String = "GPU ray-marched 3D Mandelbulb fractal"
    override val algorithmNotes: String =
        "Fragment-shader sphere-tracing of the standard Mandelbulb distance estimator, " +
        "with palette-mapped surface colouring driven by iteration escape time."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Power", "power", ParamGroup.GEOMETRY,
            "Bulb exponent (classic value is 8)", 2f, 12f, 0.1f, 8f),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY,
            "Distance-estimator iterations (higher = more detail, slower)",
            4f, 12f, 1f, 8f),
        Parameter.NumberParam("Camera Distance", "camDist", ParamGroup.COMPOSITION,
            "Distance from origin to camera", 1.8f, 4.0f, 0.05f, 2.6f),
        Parameter.NumberParam("Orbit Speed", "orbitSpeed", ParamGroup.FLOW_MOTION,
            "Camera orbit speed", 0f, 1.5f, 0.05f, 0.3f),
        Parameter.NumberParam("Glow", "glow", ParamGroup.COLOR,
            "Iteration-driven surface glow intensity", 0f, 2f, 0.05f, 1.0f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "power" to 8f,
        "iterations" to 8f,
        "camDist" to 2.6f,
        "orbitSpeed" to 0.3f,
        "glow" to 1.0f,
        "audioReact" to 0.5f
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
        val power = (params["power"] as? Float) ?: 8f
        val iterations = ((params["iterations"] as? Float) ?: 8f).toInt().coerceIn(4, 12)
        val camDist = (params["camDist"] as? Float) ?: 2.6f
        val orbitSpeed = (params["orbitSpeed"] as? Float) ?: 0.3f
        val glow = (params["glow"] as? Float) ?: 1.0f
        val audioReact = (params["audioReact"] as? Float) ?: 0.5f

        // Quality-driven raymarch step budget.
        val maxSteps = when (quality) {
            Quality.DRAFT -> 48
            Quality.BALANCED -> 80
            Quality.ULTRA -> 128
        }

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPower"), power)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIterations"), iterations)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamDist"), camDist)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uOrbitSpeed"), orbitSpeed)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGlow"), glow)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxSteps"), maxSteps)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform float uPower;
        uniform int uIterations;
        uniform float uCamDist;
        uniform float uOrbitSpeed;
        uniform float uGlow;
        uniform float uAudioReact;
        uniform int uMaxSteps;

        out vec4 fragColor;

        // Mandelbulb distance estimator with iteration count fed back via 'iter'.
        float bulbDE(vec3 p, float power, int maxIter, out float iter) {
            vec3 z = p;
            float dr = 1.0;
            float r = 0.0;
            iter = 0.0;
            for (int i = 0; i < 16; i++) {
                if (i >= maxIter) break;
                r = length(z);
                if (r > 2.0) { iter = float(i) + 1.0 - log2(log2(r)); break; }
                float theta = acos(z.z / r);
                float phi = atan(z.y, z.x);
                dr = pow(r, power - 1.0) * power * dr + 1.0;
                float zr = pow(r, power);
                theta *= power;
                phi *= power;
                z = zr * vec3(sin(theta) * cos(phi), sin(phi) * sin(theta), cos(theta)) + p;
                iter = float(i + 1);
            }
            return 0.5 * log(r) * r / dr;
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            // Audio-modulated bulb power
            float power = uPower + uAudio.x * 1.2 * uAudioReact;

            // Orbit camera around origin
            float t = uTime * uOrbitSpeed;
            vec3 ro = vec3(sin(t) * uCamDist, 0.4 * cos(t * 0.7), cos(t) * uCamDist);
            vec3 fw = normalize(-ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);
            vec3 rd = normalize(uv.x * rt - uv.y * up + 1.4 * fw);

            float total = 0.0;
            float iter = 0.0;
            float minDist = 1e9;
            bool hit = false;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                if (length(p) > 6.0) break;
                float d = bulbDE(p, power, uIterations, iter);
                minDist = min(minDist, d);
                total += max(d, 0.0005);
                if (d < 0.0008) { hit = true; break; }
                if (total > 8.0) break;
            }

            vec3 col;
            if (hit) {
                // Surface colour from iteration escape time, plus mid/high audio glow
                float t01 = clamp(iter / float(uIterations) + 0.15 * uAudio.y * uAudioReact, 0.0, 1.0);
                vec3 base = palette_color(t01);
                float depthFade = exp(-0.18 * total);
                float glowBoost = uGlow * (0.6 + 0.4 * uAudio.z * uAudioReact);
                col = base * depthFade * glowBoost;
                col += 0.08 * vec3(uAudio.z);
            } else {
                // Background: soft palette gradient based on screen Y plus the
                // minimum distance reached (gives a subtle silhouette glow).
                float halo = exp(-22.0 * minDist);
                vec3 sky = palette_color(0.5 + 0.5 * uv.y);
                col = mix(vec3(0.02, 0.02, 0.05), sky * 0.5, 0.4) + palette_color(0.85) * halo * 0.6;
            }

            // Cheap tone-map + gamma
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
