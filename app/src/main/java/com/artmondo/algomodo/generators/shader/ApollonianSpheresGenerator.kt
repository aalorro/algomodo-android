package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched Apollonian Gasket: infinite recursive sphere packing produced
 * by iterated tetrahedral folding (abs + descending sort) plus sphere inversion.
 *
 * Audio reactivity: bass nudges the inversion radius (causing the packing to
 * breathe), mids brighten the surface, highs energise the fresnel rim glow.
 */
class ApollonianSpheresGenerator : GpuGenerator {

    override val id: String = "shader-apollonian-spheres"
    override val family: String = "shader"
    override val styleName: String = "Apollonian Spheres"
    override val definition: String =
        "Infinite recursive sphere packing via inversive geometry with orbit trap coloring."
    override val algorithmNotes: String =
        "Fragment-shader sphere-tracing of a Kaleidoscopic IFS distance estimator: each iteration " +
        "folds the point with abs() and descending-sort, subtracts a packing offset, then performs " +
        "a sphere inversion when inside the invariant ball. Accumulated scale yields the distance " +
        "estimate; the minimum squared radius across iterations forms an orbit trap that drives " +
        "palette coloring. A fresnel-style rim glow adds silhouette highlights."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 4f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 40f),
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
        Parameter.SelectParam("Packing", "packingStyle", ParamGroup.COMPOSITION,
            "Packing geometry preset",
            listOf("Classic", "Dense", "Lacy", "Cathedral"), "Classic"),
        Parameter.NumberParam("Iterations", "iterations", ParamGroup.GEOMETRY,
            "Fractal iteration count", 2f, 16f, 1f, 8f),
        Parameter.NumberParam("Sphere Size", "sphereSize", ParamGroup.GEOMETRY,
            "Radius of packed spheres", 0.05f, 0.8f, 0.05f, 0.3f),
        Parameter.NumberParam("Rim Glow", "rimGlow", ParamGroup.TEXTURE,
            "Fresnel rim glow intensity", 0f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Roughness", "roughness", ParamGroup.TEXTURE,
            "Surface roughness (lower = shinier)", 0.1f, 1f, 0.05f, 0.4f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 40f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "packingStyle" to "Classic", "iterations" to 8f, "sphereSize" to 0.3f,
        "rimGlow" to 0.5f, "roughness" to 0.4f, "audioReact" to 0.4f
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
        val fov = (params["fov"] as? Float) ?: 40f
        val camAngle = (params["cameraAngle"] as? Float) ?: 0f
        val camHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val packing = (params["packingStyle"] as? String) ?: "Classic"
        val iterations = ((params["iterations"] as? Float) ?: 8f).toInt().coerceIn(2, 16)
        val sphereSize = (params["sphereSize"] as? Float) ?: 0.3f
        val rimGlow = (params["rimGlow"] as? Float) ?: 0.5f
        val roughness = (params["roughness"] as? Float) ?: 0.4f
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        // Packing presets — match the CPU original.
        val ox: Float; val oy: Float; val oz: Float; val invR2: Float
        when (packing) {
            "Dense" -> { ox = 1.1f; oy = 1.1f; oz = 1.1f; invR2 = 1.2f }
            "Lacy" -> { ox = 1.0f; oy = 1.5f; oz = 1.0f; invR2 = 1.5f }
            "Cathedral" -> { ox = 1.2f; oy = 0.8f; oz = 1.2f; invR2 = 1.0f }
            else -> { ox = 1.0f; oy = 1.0f; oz = 1.0f; invR2 = 1.0f }
        }

        // Quality-driven raymarch step budget.
        val maxSteps = when (quality) {
            Quality.DRAFT -> 48
            Quality.BALANCED -> 80
            Quality.ULTRA -> 128
        }

        // Seed-driven phase offset so different seeds produce visually different
        // patterns without affecting parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamDist"), camDist)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamAngle"), camAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamHeight"), camHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(programId, "uOffset"), ox, oy, oz)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvR2"), invR2)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIterations"), iterations)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSphereSize"), sphereSize)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRimGlow"), rimGlow)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRoughness"), roughness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phaseOffset)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxSteps"), maxSteps)
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
        uniform vec3 uOffset;
        uniform float uInvR2;
        uniform int uIterations;
        uniform float uSphereSize;
        uniform float uRimGlow;
        uniform float uRoughness;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float DEG = PI / 180.0;

        // Apollonian Kaleidoscopic IFS distance estimator.
        // Folds with abs + descending sort, then sphere-inverts inside the
        // invariant ball. Audio-modulated invariant radius lets the bass
        // "breathe" the packing.
        float apollonianDE(vec3 p, float cosRot, float sinRot, float invR2) {
            // Pre-rotation about Y to spin the fractal as time advances.
            vec3 q = vec3(p.x * cosRot + p.z * sinRot,
                          p.y,
                         -p.x * sinRot + p.z * cosRot);
            float scale = 1.0;
            for (int i = 0; i < 16; i++) {
                if (i >= uIterations) break;
                q = abs(q);
                // Descending sort (largest into x, smallest into z).
                if (q.x < q.y) q.xy = q.yx;
                if (q.x < q.z) q.xz = q.zx;
                if (q.y < q.z) q.yz = q.zy;
                q -= uOffset;
                float r2 = dot(q, q);
                if (r2 < invR2) {
                    float k = invR2 / r2;
                    q *= k;
                    scale *= k;
                }
                q += uOffset;
            }
            return (length(q) - uSphereSize) / scale;
        }

        // Combined DE + orbit trap (minimum radius across iterations).
        // out.x = distance, out.y = trap value in 0..1
        vec2 apollonianDETrap(vec3 p, float cosRot, float sinRot, float invR2) {
            vec3 q = vec3(p.x * cosRot + p.z * sinRot,
                          p.y,
                         -p.x * sinRot + p.z * cosRot);
            float scale = 1.0;
            float minR2 = 1e9;
            for (int i = 0; i < 16; i++) {
                if (i >= uIterations) break;
                q = abs(q);
                if (q.x < q.y) q.xy = q.yx;
                if (q.x < q.z) q.xz = q.zx;
                if (q.y < q.z) q.yz = q.zy;
                q -= uOffset;
                float r2 = dot(q, q);
                minR2 = min(minR2, r2);
                if (r2 < invR2) {
                    float k = invR2 / r2;
                    q *= k;
                    scale *= k;
                }
                q += uOffset;
            }
            return vec2((length(q) - uSphereSize) / scale, sqrt(minR2) * 0.5);
        }

        vec3 estimateNormal(vec3 p, float cosRot, float sinRot, float invR2, float eps) {
            // Forward-difference normal (4 SDF evaluations instead of 6).
            float nc = apollonianDE(p, cosRot, sinRot, invR2);
            float nx = apollonianDE(p + vec3(eps, 0.0, 0.0), cosRot, sinRot, invR2) - nc;
            float ny = apollonianDE(p + vec3(0.0, eps, 0.0), cosRot, sinRot, invR2) - nc;
            float nz = apollonianDE(p + vec3(0.0, 0.0, eps), cosRot, sinRot, invR2) - nc;
            vec3 n = vec3(nx, ny, nz);
            float len = length(n);
            return (len > 0.0) ? n / len : vec3(0.0, 1.0, 0.0);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            float t = uTime * uSpeed + uPhase;

            // Audio modulation: bass breathes the invariant ball radius slightly,
            // mids brighten the diffuse term, highs amplify the rim glow.
            float invR2 = uInvR2 * (1.0 + 0.08 * uAudio.x * uAudioReact);
            float midBoost = 1.0 + 0.35 * uAudio.y * uAudioReact;
            float highBoost = 1.0 + 1.2 * uAudio.z * uAudioReact;

            // Camera orbit (matches CPU: angle drifts as time * 10 degrees).
            float camAng = (uCamAngle + t * 10.0) * DEG;
            vec3 ro = vec3(sin(camAng) * uCamDist, uCamHeight, cos(camAng) * uCamDist);

            // Build camera basis pointing at origin.
            vec3 fw = normalize(-ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);

            // FOV in degrees -> focal length for pinhole projection.
            float halfFovTan = tan(0.5 * uFov * DEG);
            vec3 rd = normalize(uv.x * rt * halfFovTan - uv.y * up * halfFovTan + fw);

            // Light direction (horizontal angle + vertical height).
            float la = uLightAngle * DEG;
            vec3 ld = normalize(vec3(cos(la), uLightHeight, sin(la)));

            // Rotation factors for the fractal's slow Y-spin.
            float rotY = t * 0.3;
            float cosRot = cos(rotY);
            float sinRot = sin(rotY);

            // Sphere-trace with over-relaxation (Apollonian DE is roughly
            // Lipschitz-1, so a 1.3x step is safe).
            float marchT = 0.0;
            bool hit = false;
            vec3 hitPos = vec3(0.0);
            const float maxDist = 60.0;
            const float epsilon = 0.001;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                hitPos = ro + rd * marchT;
                float d = apollonianDE(hitPos, cosRot, sinRot, invR2);
                if (d < epsilon) { hit = true; break; }
                marchT += d * 1.3;
                if (marchT > maxDist) break;
            }

            vec3 col;
            if (hit) {
                float normalEps = epsilon * 2.0;
                vec3 normal = estimateNormal(hitPos, cosRot, sinRot, invR2, normalEps);

                // View direction (toward camera).
                vec3 viewDir = normalize(ro - hitPos);

                // Orbit-trap based palette lookup.
                vec2 deTrap = apollonianDETrap(hitPos, cosRot, sinRot, invR2);
                float colorT = clamp(deTrap.y, 0.0, 1.0);
                vec3 baseColor = palette_color(colorT);

                // Phong-ish shading: ambient + diffuse + specular.
                float NdotL = max(0.0, dot(normal, ld));
                vec3 halfV = normalize(ld + viewDir);
                float NdotH = max(0.0, dot(normal, halfV));

                float shininess = 10.0 + (1.0 - uRoughness) * 90.0;
                float specAmt = (1.0 - uRoughness) * 0.8;

                float ambient = 0.15;
                float diffuse = 0.6 * NdotL * midBoost;
                float specular = specAmt * pow(NdotH, shininess);
                float shade = ambient + diffuse + specular;

                // Cheap step-count based ambient occlusion proxy (avoids
                // additional DE evaluations vs. the CPU's multi-tap AO loop).
                float ao = 1.0 - clamp(marchT * 0.015, 0.0, 0.45);

                // Fresnel rim glow.
                float NdotV = max(0.0, dot(normal, viewDir));
                float fresnel = pow(1.0 - NdotV, 3.0) * uRimGlow * highBoost;
                vec3 rimColor = uPalette[0];

                col = baseColor * shade * ao + rimColor * fresnel;
                col *= uExposure;
            } else {
                // Sky gradient: deep blue/purple, no fractal hit.
                float v = 0.5 * (rd.y + 1.0);
                vec3 top = vec3(0.05, 0.05, 0.12);
                vec3 bot = vec3(0.12, 0.08, 0.15);
                col = mix(bot, top, v);

                // Subtle palette tint reacting to highs for sparkle.
                col += palette_color(0.5 + 0.5 * uv.y) * 0.05 * uAudio.z * uAudioReact;
                col *= uExposure;
            }

            // Tone-map + gamma (per pipeline convention).
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
