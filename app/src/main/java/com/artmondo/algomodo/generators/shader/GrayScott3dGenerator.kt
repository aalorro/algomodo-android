package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU "Gray-Scott 3D" — a fragment-shader approximation of the swirly,
 * blob-and-worm patterns produced by 3D Gray-Scott reaction-diffusion.
 *
 * Real Gray-Scott is an iterative PDE that requires ping-pong textures (not
 * supported by [com.artmondo.algomodo.rendering.gl.GpuShaderRunner]), so this
 * generator FAKES the look procedurally: a multi-octave 3D value-noise FBM
 * with iterated domain warping produces a smooth scalar density field whose
 * iso-surface (density > threshold) is ray-marched in real time. Different
 * "preset" choices retune the warp frequency / amplitude to mimic the visual
 * signature of the spots / stripes / worms / mitosis / coral regimes.
 *
 * Audio reactivity: bass nudges the iso-threshold (pulsing surface), mids
 * modulate camera orbit, highs add surface sparkle.
 */
class GrayScott3dGenerator : GpuGenerator {

    override val id: String = "shader-gray-scott-3d"
    override val family: String = "shader"
    override val styleName: String = "Gray-Scott 3D"
    override val definition: String =
        "3D reaction-diffusion patterns (Gray-Scott model) ray-marched as an isosurface."
    override val algorithmNotes: String =
        "Procedural fragment-shader stand-in for 3D Gray-Scott reaction-diffusion. " +
        "Per-frame ping-pong simulation is infeasible without render-to-texture, so a " +
        "multi-octave 3D value-noise FBM with iterated domain warping is sphere-traced " +
        "as an iso-surface (density > threshold = inside). Preset retunes warp frequency " +
        "and amplitude to evoke spots, stripes, worms, mitosis, and coral regimes. " +
        "Time slowly drifts the noise domain to animate growth; audio bass pulses the " +
        "threshold, mids tilt the orbit, highs sparkle the surface."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 5f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 60f),
        Parameter.NumberParam("Camera Angle", "cameraAngle", ParamGroup.COMPOSITION,
            "Horizontal camera orbit angle", 0f, 360f, 5f, 0f),
        Parameter.NumberParam("Camera Height", "cameraHeight", ParamGroup.COMPOSITION,
            "Vertical camera position", -2f, 3f, 0.1f, 0.5f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Horizontal light direction", 0f, 360f, 5f, 45f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Vertical light direction", 0.1f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.SelectParam("Preset", "preset", ParamGroup.COMPOSITION,
            "Reaction-diffusion preset",
            listOf("spots", "stripes", "worms", "mitosis", "coral"), "spots"),
        Parameter.NumberParam("Sim Steps", "simSteps", ParamGroup.GEOMETRY,
            "Number of simulation iterations", 20f, 200f, 10f, 80f),
        Parameter.NumberParam("Threshold", "threshold", ParamGroup.GEOMETRY,
            "Isosurface threshold for V concentration", 0.1f, 0.6f, 0.05f, 0.25f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 5f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "preset" to "spots", "simSteps" to 80f, "threshold" to 0.25f,
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
        val camDist = (params["cameraDistance"] as? Float) ?: 5f
        val fov = (params["fov"] as? Float) ?: 60f
        val camAngle = (params["cameraAngle"] as? Float) ?: 0f
        val camHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val preset = (params["preset"] as? String) ?: "spots"
        val simSteps = ((params["simSteps"] as? Float) ?: 80f).toInt().coerceIn(20, 200)
        val threshold = (params["threshold"] as? Float) ?: 0.25f
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        // Preset → procedural-noise tuning (frequency, warpAmp, octaves, iso bias).
        // Each tuple loosely mimics the visual signature of the corresponding
        // Gray-Scott feed/kill regime.
        val (freq, warpAmp, octaves, isoBias) = when (preset) {
            "stripes"  -> Quad(2.4f, 0.65f, 4, -0.05f)
            "worms"    -> Quad(2.8f, 0.85f, 4,  0.00f)
            "mitosis"  -> Quad(2.0f, 0.40f, 3,  0.05f)
            "coral"    -> Quad(3.2f, 0.55f, 5, -0.02f)
            else       -> Quad(2.2f, 0.30f, 3,  0.00f) // spots
        }

        // Sim steps is repurposed as a "maturity" scalar 0..1 — drives how
        // strongly the iterated warp deforms the field (more steps ≈ more
        // developed Gray-Scott pattern).
        val maturity = (simSteps - 20f) / 180f

        // Seed → phase offset so different seeds produce visually distinct
        // pattern layouts without affecting parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        val maxSteps = when (quality) {
            Quality.DRAFT -> 48
            Quality.BALANCED -> 80
            Quality.ULTRA -> 128
        }

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamDist"), camDist)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamAngle"), camAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamHeight"), camHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uThreshold"), threshold)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phaseOffset)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFreq"), freq)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpAmp"), warpAmp)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uOctaves"), octaves)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uIsoBias"), isoBias)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uMaturity"), maturity)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxSteps"), maxSteps)
    }

    private data class Quad(val a: Float, val b: Float, val c: Int, val d: Float)

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform float uCamDist;
        uniform float uFov;
        uniform float uCamAngle;
        uniform float uCamHeight;
        uniform float uLightAngle;
        uniform float uLightHeight;
        uniform float uExposure;
        uniform float uSpeed;
        uniform float uThreshold;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform float uFreq;
        uniform float uWarpAmp;
        uniform int uOctaves;
        uniform float uIsoBias;
        uniform float uMaturity;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const float PI = 3.14159265359;

        // ---- 3D value noise -----------------------------------------------
        float hash31(vec3 p) {
            p = fract(p * vec3(0.1031, 0.1030, 0.0973));
            p += dot(p, p.yxz + 33.33);
            return fract((p.x + p.y) * p.z);
        }

        float vnoise3(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            vec3 u = f * f * (3.0 - 2.0 * f);

            float n000 = hash31(i + vec3(0.0, 0.0, 0.0));
            float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
            float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
            float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
            float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
            float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
            float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
            float n111 = hash31(i + vec3(1.0, 1.0, 1.0));

            float nx00 = mix(n000, n100, u.x);
            float nx10 = mix(n010, n110, u.x);
            float nx01 = mix(n001, n101, u.x);
            float nx11 = mix(n011, n111, u.x);
            float nxy0 = mix(nx00, nx10, u.y);
            float nxy1 = mix(nx01, nx11, u.y);
            return mix(nxy0, nxy1, u.z);
        }

        // Multi-octave FBM, capped by uOctaves at runtime.
        float fbm(vec3 p) {
            float a = 0.5;
            float sum = 0.0;
            float norm = 0.0;
            for (int i = 0; i < 6; i++) {
                if (i >= uOctaves) break;
                sum += a * vnoise3(p);
                norm += a;
                p *= 2.03;
                a *= 0.5;
            }
            return sum / max(norm, 0.0001);
        }

        // Gray-Scott stand-in density field. Iterated domain warp + FBM gives
        // smooth blob/worm/stripe structures; "maturity" scales how strongly
        // the warp deforms the field (akin to running more sim steps).
        float densityField(vec3 p, float t) {
            vec3 q = p * uFreq + vec3(uPhase, 0.0, uPhase * 0.5);
            // Slow temporal drift to animate the "reaction" growth.
            q += vec3(0.13 * t, -0.09 * t, 0.07 * t);

            float warp = uWarpAmp * (0.5 + 0.5 * uMaturity);

            // Two cheap warp iterations (more than two starts to look mushy
            // and tanks the framerate inside the inner ray-march loop).
            vec3 w1 = vec3(
                fbm(q + vec3(0.0, 1.7, 4.3)),
                fbm(q + vec3(5.2, 1.3, 2.8)),
                fbm(q + vec3(8.1, 3.1, 0.6))
            ) - 0.5;
            q += warp * w1;

            vec3 w2 = vec3(
                fbm(q * 1.4 + vec3(2.1, 0.0, 6.7)),
                fbm(q * 1.4 + vec3(4.4, 5.5, 1.2)),
                fbm(q * 1.4 + vec3(0.8, 3.3, 7.9))
            ) - 0.5;
            q += warp * 0.6 * w2;

            float n = fbm(q);

            // Mild radial fall-off keeps structure inside a sphere-ish volume
            // and gives the ray-marcher a useful bounding behaviour.
            float r = length(p);
            float falloff = smoothstep(1.6, 0.4, r);

            return n * falloff + uIsoBias;
        }

        // SDF: positive outside iso-surface, negative inside.
        // The 0.6 multiplier is an empirical Lipschitz dampener so sphere
        // tracing converges instead of overshooting.
        float sceneSDF(vec3 p, float t, float thr) {
            return (thr - densityField(p, t)) * 0.6;
        }

        vec3 calcNormal(vec3 p, float t, float thr) {
            float e = 0.005;
            return normalize(vec3(
                sceneSDF(p + vec3(e, 0.0, 0.0), t, thr) - sceneSDF(p - vec3(e, 0.0, 0.0), t, thr),
                sceneSDF(p + vec3(0.0, e, 0.0), t, thr) - sceneSDF(p - vec3(0.0, e, 0.0), t, thr),
                sceneSDF(p + vec3(0.0, 0.0, e), t, thr) - sceneSDF(p - vec3(0.0, 0.0, e), t, thr)
            ));
        }

        // Cheap step-count AO: brighter where we converged quickly.
        float ambientOcclusion(int steps) {
            return clamp(1.0 - float(steps) / float(uMaxSteps), 0.15, 1.0);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            float t = uTime * uSpeed;

            // Audio modulation
            float thr = uThreshold + 0.06 * uAudio.x * uAudioReact;
            float orbitTilt = 0.4 * uAudio.y * uAudioReact;

            // Camera build (orbit angle in degrees, height as world Y).
            float camAngleRad = radians(uCamAngle) + t * 0.6 + orbitTilt;
            vec3 ro = vec3(
                sin(camAngleRad) * uCamDist,
                uCamHeight,
                cos(camAngleRad) * uCamDist
            );
            vec3 target = vec3(0.0);
            vec3 fw = normalize(target - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);

            // FOV → focal length (perspective ratio).
            float focal = 1.0 / tan(radians(uFov) * 0.5);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction
            float laRad = radians(uLightAngle);
            vec3 ld = normalize(vec3(cos(laRad), uLightHeight, sin(laRad)));

            // Sphere trace
            float total = 0.0;
            bool hit = false;
            int stepsTaken = 0;
            vec3 p = ro;
            const float MAX_DIST = 12.0;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                p = ro + rd * total;
                float d = sceneSDF(p, t, thr);
                if (d < 0.002) { hit = true; stepsTaken = s; break; }
                total += max(d, 0.01);
                if (total > MAX_DIST) break;
                stepsTaken = s;
            }

            vec3 col;
            if (hit) {
                vec3 n = calcNormal(p, t, thr);
                float diff = max(dot(n, ld), 0.0);
                vec3 viewDir = normalize(ro - p);
                vec3 h = normalize(ld + viewDir);
                float spec = pow(max(dot(n, h), 0.0), 28.0);
                float ao = ambientOcclusion(stepsTaken);

                // Palette index from a mix of position, normal and density.
                float colorT = clamp(0.5 + 0.3 * n.y + 0.2 * p.x + 0.15 * uMaturity, 0.0, 1.0);
                vec3 base = palette_color(colorT);

                float ambient = 0.2;
                col = base * (ambient + 0.7 * diff) * ao + vec3(0.5) * spec * 0.4;

                // High-frequency audio sparkle on the surface
                col += vec3(0.18) * spec * uAudio.z * uAudioReact;
            } else {
                // Soft sky / background palette gradient
                vec3 sky = palette_color(0.55 + 0.45 * uv.y);
                col = mix(vec3(0.03, 0.03, 0.05), sky * 0.35, 0.5);
            }

            col *= uExposure;

            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
