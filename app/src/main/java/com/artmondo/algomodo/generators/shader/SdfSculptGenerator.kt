package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched SDF sculpture: combines fractal distance estimators
 * (Menger, Sierpinski, Mandelbox, Kaleidoscopic IFS) with rotating camera,
 * Phong shading, AO, and Fresnel rim glow — all in a fragment shader.
 *
 * Audio reactivity: bass nudges the iteration fold scale, mids shift the
 * surface palette, highs intensify the rim glow.
 */
class SdfSculptGenerator : GpuGenerator {

    override val id: String = "shader-sdf-sculpt"
    override val family: String = "shader"
    override val styleName: String = "SDF Sculpt"
    override val definition: String =
        "Fractal sculptures with Phong lighting, soft shadows, and Fresnel rim glow."
    override val algorithmNotes: String =
        "Fragment-shader sphere-tracing of four fractal distance estimators (Menger sponge, " +
        "Sierpinski tetrahedron, Mandelbox, Kaleidoscopic IFS). A continuously rotating frame, " +
        "orbit-trap palette coloring, screen-space ambient occlusion, and a Fresnel-driven rim " +
        "term combine into a slow-evolving sculptural look. Quality controls the per-pixel " +
        "raymarch step budget."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 4f),
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
        Parameter.SelectParam("Fractal", "fractal", ParamGroup.GEOMETRY,
            "Fractal sculpture type",
            listOf("Menger", "Sierpinski", "Mandelbox", "Kaleidoscopic"), "Menger"),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY,
            "Fractal iteration depth", 2f, 12f, 1f, 5f),
        Parameter.NumberParam("Fold Scale", "foldScale", ParamGroup.GEOMETRY,
            "Scale factor per fold iteration", 1.5f, 3.5f, 0.1f, 2.5f),
        Parameter.NumberParam("Rim Glow", "rimGlow", ParamGroup.TEXTURE,
            "Fresnel rim glow intensity", 0f, 2f, 0.1f, 0.6f),
        Parameter.NumberParam("Roughness", "roughness", ParamGroup.TEXTURE,
            "Surface roughness", 0.1f, 1f, 0.05f, 0.35f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "fractal" to "Menger", "iterations" to 5f, "foldScale" to 2.5f,
        "rimGlow" to 0.6f, "roughness" to 0.35f, "audioReact" to 0.5f
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
        val camDist = (params["cameraDistance"] as? Float) ?: 4f
        val fov = (params["fov"] as? Float) ?: 60f
        val camAngle = (params["cameraAngle"] as? Float) ?: 0f
        val camHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val fractal = (params["fractal"] as? String) ?: "Menger"
        val iterations = ((params["iterations"] as? Float) ?: 5f).toInt().coerceIn(2, 12)
        val foldScale = (params["foldScale"] as? Float) ?: 2.5f
        val rimGlow = (params["rimGlow"] as? Float) ?: 0.6f
        val roughness = (params["roughness"] as? Float) ?: 0.35f
        val audioReact = (params["audioReact"] as? Float) ?: 0.5f

        val fractalIdx = when (fractal) {
            "Sierpinski" -> 1
            "Mandelbox" -> 2
            "Kaleidoscopic" -> 3
            else -> 0
        }

        // Quality-driven raymarch step budget.
        val maxSteps = when (quality) {
            Quality.DRAFT -> 48
            Quality.BALANCED -> 80
            Quality.ULTRA -> 128
        }

        // Seed-driven phase offset so different seeds rotate to different
        // starting orientations without affecting parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamDist"), camDist)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamAngle"), camAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamHeight"), camHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFractal"), fractalIdx)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIterations"), iterations)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFoldScale"), foldScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRimGlow"), rimGlow)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRoughness"), roughness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxSteps"), maxSteps)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phaseOffset)
    }

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
        uniform int uFractal;
        uniform int uIterations;
        uniform float uFoldScale;
        uniform float uRimGlow;
        uniform float uRoughness;
        uniform float uAudioReact;
        uniform int uMaxSteps;
        uniform float uPhase;

        out vec4 fragColor;

        const float PI = 3.14159265359;

        float sdBox(vec3 p, vec3 b) {
            vec3 q = abs(p) - b;
            return min(max(q.x, max(q.y, q.z)), 0.0) + length(max(q, 0.0));
        }

        // ----- Fractal distance estimators -----

        float deMenger(vec3 p, int iters, float scale) {
            float d = sdBox(p, vec3(1.0));
            float s = 1.0;
            for (int i = 0; i < 12; i++) {
                if (i >= iters) break;
                vec3 a = mod(p * s + 1.0, 2.0) - 1.0;
                s *= scale;
                vec3 r = 1.0 - 3.0 * abs(a);
                float crossD = max(r.x, max(r.y, r.z)) / s;
                d = max(d, -crossD);
            }
            return d;
        }

        float deSierpinski(vec3 p, int iters, float scale) {
            for (int i = 0; i < 12; i++) {
                if (i >= iters) break;
                if (p.x + p.y < 0.0) { float t = -p.y; p.y = -p.x; p.x = t; }
                if (p.x + p.z < 0.0) { float t = -p.z; p.z = -p.x; p.x = t; }
                if (p.y + p.z < 0.0) { float t = -p.z; p.z = -p.y; p.y = t; }
                p = p * scale - (scale - 1.0);
            }
            return (length(p) - 1.5) * pow(scale, -float(iters));
        }

        float deMandelbox(vec3 p, int iters, float scale) {
            vec3 z = p;
            float dr = 1.0;
            const float foldLimit = 1.0;
            const float fixedR2 = 1.0;
            const float minR2 = 0.25;
            for (int i = 0; i < 12; i++) {
                if (i >= iters) break;
                z = clamp(z, -foldLimit, foldLimit) * 2.0 - z;
                float r2 = dot(z, z);
                if (r2 < minR2) {
                    float t = fixedR2 / minR2;
                    z *= t; dr *= t;
                } else if (r2 < fixedR2) {
                    float t = fixedR2 / r2;
                    z *= t; dr *= t;
                }
                z = z * scale + p;
                dr = dr * abs(scale) + 1.0;
            }
            return length(z) / abs(dr);
        }

        float deKaleidoscopic(vec3 p, int iters, float scale) {
            for (int i = 0; i < 12; i++) {
                if (i >= iters) break;
                p = abs(p);
                if (p.x < p.y) { float t = p.x; p.x = p.y; p.y = t; }
                if (p.x < p.z) { float t = p.x; p.x = p.z; p.z = t; }
                if (p.y < p.z) { float t = p.y; p.y = p.z; p.z = t; }
                p = p * scale - (scale - 1.0) * 0.5;
            }
            return sdBox(p, vec3(0.5)) * pow(scale, -float(iters));
        }

        // Rotation matrix shared by SDF and orbit-trap colour.
        mat3 buildRotation(float rotY, float rotX) {
            float cy = cos(rotY); float sy = sin(rotY);
            float cx = cos(rotX); float sx = sin(rotX);
            mat3 rY = mat3(
                cy, 0.0, -sy,
                0.0, 1.0, 0.0,
                sy, 0.0,  cy
            );
            mat3 rX = mat3(
                1.0, 0.0, 0.0,
                0.0, cx,   sx,
                0.0, -sx,  cx
            );
            return rX * rY;
        }

        float sceneSDF(vec3 p, mat3 rot, float foldScale, int iters) {
            vec3 q = rot * p;
            if (uFractal == 1) return deSierpinski(q, iters, foldScale);
            if (uFractal == 2) return deMandelbox(q, iters, foldScale);
            if (uFractal == 3) return deKaleidoscopic(q, iters, foldScale);
            return deMenger(q, iters, foldScale);
        }

        vec3 calcNormal(vec3 p, mat3 rot, float foldScale, int iters, float eps) {
            vec2 e = vec2(eps, 0.0);
            return normalize(vec3(
                sceneSDF(p + e.xyy, rot, foldScale, iters) - sceneSDF(p - e.xyy, rot, foldScale, iters),
                sceneSDF(p + e.yxy, rot, foldScale, iters) - sceneSDF(p - e.yxy, rot, foldScale, iters),
                sceneSDF(p + e.yyx, rot, foldScale, iters) - sceneSDF(p - e.yyx, rot, foldScale, iters)
            ));
        }

        // Orbit-trap colour from minimum radius during fractal iteration.
        float fractalTrap(vec3 p, mat3 rot, float foldScale, int iters) {
            vec3 q = rot * p;
            float minR2 = 1e9;
            if (uFractal == 1) {
                for (int i = 0; i < 12; i++) {
                    if (i >= iters) break;
                    if (q.x + q.y < 0.0) { float t = -q.y; q.y = -q.x; q.x = t; }
                    if (q.x + q.z < 0.0) { float t = -q.z; q.z = -q.x; q.x = t; }
                    if (q.y + q.z < 0.0) { float t = -q.z; q.z = -q.y; q.y = t; }
                    q = q * foldScale - (foldScale - 1.0);
                    minR2 = min(minR2, dot(q, q));
                }
            } else {
                for (int i = 0; i < 12; i++) {
                    if (i >= iters) break;
                    q = abs(q);
                    if (q.x < q.y) { float t = q.x; q.x = q.y; q.y = t; }
                    if (q.x < q.z) { float t = q.x; q.x = q.z; q.z = t; }
                    if (q.y < q.z) { float t = q.y; q.y = q.z; q.z = t; }
                    q = q * foldScale - (foldScale - 1.0) * 0.5;
                    minR2 = min(minR2, dot(q, q));
                }
            }
            return sqrt(minR2) * 0.3;
        }

        // Cheap 5-tap ambient occlusion along the surface normal.
        float ambientOcclusion(vec3 p, vec3 n, mat3 rot, float foldScale, int iters) {
            float occ = 0.0;
            float sca = 1.0;
            for (int i = 0; i < 5; i++) {
                float h = 0.02 + 0.12 * float(i) / 4.0;
                float d = sceneSDF(p + n * h, rot, foldScale, iters);
                occ += (h - d) * sca;
                sca *= 0.85;
            }
            return clamp(1.0 - 1.5 * occ, 0.0, 1.0);
        }

        vec3 toneMapACES(vec3 c) {
            const float a = 2.51;
            const float b = 0.03;
            const float cc = 2.43;
            const float d = 0.59;
            const float e = 0.14;
            return clamp((c * (a * c + b)) / (c * (cc * c + d) + e), 0.0, 1.0);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            float t = uTime * uSpeed + uPhase;

            // Audio-modulated fold scale (bass drives subtle pulse of the fractal).
            float foldScale = uFoldScale + 0.25 * uAudio.x * uAudioReact;

            // Slow continuous Y + X rotation of the sculpture.
            float rotY = t * 0.3 + uPhase;
            float rotX = t * 0.15 + uPhase * 0.5;
            mat3 rot = buildRotation(rotY, rotX);

            // Camera placement from polar angle/height/distance.
            float ca = radians(uCamAngle);
            vec3 ro = vec3(sin(ca) * uCamDist, uCamHeight, cos(ca) * uCamDist);
            vec3 fw = normalize(-ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);
            float focal = 1.0 / tan(radians(uFov) * 0.5);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction from polar angle/height.
            float la = radians(uLightAngle);
            vec3 ld = normalize(vec3(sin(la), uLightHeight, cos(la)));

            // Sphere-trace.
            float total = 0.0;
            bool hit = false;
            float eps = 0.0015;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                if (length(p) > 8.0 && total > uCamDist) break;
                float d = sceneSDF(p, rot, foldScale, uIterations);
                total += max(d, eps * 0.5);
                if (d < eps) { hit = true; break; }
                if (total > 20.0) break;
            }

            vec3 col;
            if (hit) {
                vec3 p = ro + rd * total;
                vec3 n = calcNormal(p, rot, foldScale, uIterations, eps * 2.0);
                vec3 v = normalize(ro - p);

                // Phong shading.
                float shininess = 10.0 + (1.0 - uRoughness) * 90.0;
                float specAmt   = (1.0 - uRoughness) * 0.7;
                float diff = max(0.0, dot(n, ld));
                vec3 h = normalize(ld + v);
                float spec = pow(max(0.0, dot(n, h)), shininess) * specAmt;
                float shade = 0.12 + 0.6 * diff + spec;

                // AO.
                float ao = ambientOcclusion(p, n, rot, foldScale, uIterations);

                // Orbit-trap palette colour, with mid-band audio shift.
                float trap = clamp(fractalTrap(p, rot, foldScale, uIterations), 0.0, 1.0);
                float trapShift = clamp(trap + 0.12 * uAudio.y * uAudioReact, 0.0, 1.0);
                vec3 baseCol = palette_color(trapShift);

                // Fresnel rim glow, boosted by high-band audio.
                float NdotV = max(0.0, dot(n, v));
                float fresnel = pow(1.0 - NdotV, 3.0) * uRimGlow * (1.0 + 0.6 * uAudio.z * uAudioReact);
                vec3 rimCol = palette_color(0.5);

                col = baseCol * shade * ao + rimCol * fresnel;
            } else {
                // Soft palette sky gradient.
                float v = clamp(0.5 - 0.5 * rd.y, 0.0, 1.0);
                vec3 top = palette_color(0.85);
                vec3 bot = palette_color(0.15);
                col = mix(top, bot, v) * 0.35;
            }

            col *= uExposure;
            col = toneMapACES(col);

            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
