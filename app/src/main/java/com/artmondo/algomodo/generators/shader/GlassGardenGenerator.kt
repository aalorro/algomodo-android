package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched garden of translucent crystalline / glass shapes.
 *
 * A handful of seeded spheres are smooth-min unioned into a single SDF, then
 * sphere-traced in a fragment shader. Shading fakes refractive glass with a
 * Fresnel-driven mix of palette-tinted "interior" colour and reflected sky,
 * plus a sharp specular highlight and view-grazing edge brightening.
 *
 * Audio reactivity: bass swells the object radii, mids warp the camera orbit
 * speed, highs intensify the specular sparkle.
 */
class GlassGardenGenerator : GpuGenerator {

    override val id: String = "shader-glass-garden"
    override val family: String = "shader"
    override val styleName: String = "Glass Garden"
    override val definition: String =
        "Refractive and reflective glass surfaces with Fresnel blending and multi-bounce ray tracing."
    override val algorithmNotes: String =
        "Sphere-traced SDF of N seeded spheres unioned with a smooth-min operator on top of a " +
        "ground plane. Per-pixel shading approximates glass by Fresnel-mixing a palette-tinted " +
        "interior colour with a sampled sky reflection, then adds Blinn-Phong specular and " +
        "view-grazing edge highlights. Material choice biases the index of refraction and Beer's " +
        "law tint absorption."
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
        Parameter.SelectParam("Material", "material", ParamGroup.TEXTURE,
            "Glass material type",
            listOf("Glass", "TintedGlass", "Diamond", "Liquid"), "Glass"),
        Parameter.NumberParam("Bounces", "maxBounces", ParamGroup.GEOMETRY,
            "Maximum ray bounce count", 1f, 4f, 1f, 2f),
        Parameter.NumberParam("Object Count", "objectCount", ParamGroup.COMPOSITION,
            "Number of glass objects", 2f, 6f, 1f, 3f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 5f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "material" to "Glass", "maxBounces" to 2f, "objectCount" to 3f,
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
        val cameraDistance = (params["cameraDistance"] as? Float) ?: 5f
        val fov = (params["fov"] as? Float) ?: 60f
        val cameraAngle = (params["cameraAngle"] as? Float) ?: 0f
        val cameraHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val material = (params["material"] as? String) ?: "Glass"
        val maxBounces = ((params["maxBounces"] as? Float) ?: 2f).toInt().coerceIn(1, 4)
        val objectCount = ((params["objectCount"] as? Float) ?: 3f).toInt().coerceIn(2, 6)
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        val ior = when (material) {
            "Diamond" -> 2.42f
            "Liquid" -> 1.33f
            else -> 1.5f
        }
        val absorption = when (material) {
            "TintedGlass" -> 0.6f
            "Liquid" -> 0.8f
            "Diamond" -> 0.1f
            else -> 0f
        }
        // Tint colour palette-position bias per material (mapped in shader).
        val tintBias = when (material) {
            "TintedGlass" -> 0.75f
            "Liquid" -> 0.55f
            "Diamond" -> 0.95f
            else -> 0.35f
        }

        // Seed-driven phase offset so different seeds rearrange the garden
        // without altering parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        // Pack up to 6 seeded sphere transforms (cx, cy, cz, radius) into a
        // vec4[6] uniform. Reproduces the CPU SeededRNG layout: 4 ranges per
        // sphere in [-1.5,1.5], [-0.5,1.0], [-1.5,1.5], [0.5,1.0]. We use a
        // simple xorshift-style hash here so the layout is deterministic from
        // the seed without depending on the JVM RNG.
        val spheres = FloatArray(6 * 4)
        var s = (seed * 0x9E3779B1.toInt()) xor 0x12345678.toInt()
        fun nextRange(lo: Float, hi: Float): Float {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            val u = ((s ushr 8) and 0xFFFFFF) / 16777216f
            return lo + u * (hi - lo)
        }
        for (i in 0 until 6) {
            spheres[i * 4 + 0] = nextRange(-1.5f, 1.5f)
            spheres[i * 4 + 1] = nextRange(-0.5f, 1f)
            spheres[i * 4 + 2] = nextRange(-1.5f, 1.5f)
            spheres[i * 4 + 3] = nextRange(0.5f, 1f)
        }

        val maxSteps = when (quality) {
            Quality.DRAFT -> 48
            Quality.BALANCED -> 80
            Quality.ULTRA -> 128
        }

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCameraDistance"), cameraDistance)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCameraAngle"), cameraAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCameraHeight"), cameraHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uIor"), ior)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAbsorption"), absorption)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTintBias"), tintBias)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxBounces"), maxBounces)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uObjectCount"), objectCount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phaseOffset)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxSteps"), maxSteps)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "uSpheres"), 6, spheres, 0)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform float uCameraDistance;
        uniform float uFov;
        uniform float uCameraAngle;
        uniform float uCameraHeight;
        uniform float uLightAngle;
        uniform float uLightHeight;
        uniform float uExposure;
        uniform float uSpeed;
        uniform float uIor;
        uniform float uAbsorption;
        uniform float uTintBias;
        uniform int uMaxBounces;
        uniform int uObjectCount;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;
        uniform vec4 uSpheres[6];

        out vec4 fragColor;

        const float PI = 3.14159265359;

        // Smooth-min union for two SDF distances.
        float smin(float a, float b, float k) {
            float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
            return mix(b, a, h) - k * h * (1.0 - h);
        }

        float sphereSDF(vec3 p, vec3 c, float r) {
            return length(p - c) - r;
        }

        // Scene: ground plane + smooth-min union of up to uObjectCount spheres.
        float sceneSDF(vec3 p, float radiusBoost) {
            vec4 s0 = uSpheres[0];
            float d = sphereSDF(p, s0.xyz, s0.w * radiusBoost);
            for (int i = 1; i < 6; i++) {
                if (i >= uObjectCount) break;
                vec4 si = uSpheres[i];
                float di = sphereSDF(p, si.xyz, si.w * radiusBoost);
                d = smin(d, di, 0.3);
            }
            float ground = p.y + 1.5;
            return min(d, ground);
        }

        // Central-difference normal estimate.
        vec3 calcNormal(vec3 p, float radiusBoost, float eps) {
            vec2 e = vec2(eps, 0.0);
            return normalize(vec3(
                sceneSDF(p + e.xyy, radiusBoost) - sceneSDF(p - e.xyy, radiusBoost),
                sceneSDF(p + e.yxy, radiusBoost) - sceneSDF(p - e.yxy, radiusBoost),
                sceneSDF(p + e.yyx, radiusBoost) - sceneSDF(p - e.yyx, radiusBoost)
            ));
        }

        // Schlick's approximation of the Fresnel reflectance.
        float fresnelSchlick(float cosT, float ior) {
            float r0 = (1.0 - ior) / (1.0 + ior);
            r0 = r0 * r0;
            return r0 + (1.0 - r0) * pow(1.0 - cosT, 5.0);
        }

        // Sphere-trace the scene. Returns t at hit (or -1.0 if missed).
        // Writes hit position into outP.
        float rayMarch(vec3 ro, vec3 rd, float radiusBoost, out vec3 outP) {
            float t = 0.0;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * t;
                float d = sceneSDF(p, radiusBoost);
                if (d < 0.001) { outP = p; return t; }
                t += max(d, 0.0008);
                if (t > 20.0) break;
            }
            outP = ro + rd * t;
            return -1.0;
        }

        // Sky / background colour for a given ray direction.
        vec3 sampleSky(vec3 rd) {
            // Palette gradient up the y-axis, with a brighter horizon band.
            float t = clamp(0.5 + 0.5 * rd.y, 0.0, 1.0);
            vec3 sky = palette_color(t);
            float horizon = exp(-6.0 * abs(rd.y));
            return sky * (0.55 + 0.45 * t) + palette_color(0.85) * horizon * 0.25;
        }

        // Ground checkerboard shading.
        vec3 shadeGround(vec3 p, vec3 n, vec3 v, vec3 l) {
            float check = mod(floor(p.x * 2.0) + floor(p.z * 2.0), 2.0);
            float groundTone = mix(0.15, 0.4, check);
            float diff = clamp(dot(n, l), 0.0, 1.0);
            float amb = 0.25;
            return vec3(groundTone) * (amb + 0.7 * diff);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;
            float t = uTime * uSpeed + uPhase;

            // Audio-modulated dynamics: bass swells radii, mids speed up
            // orbit, highs sharpen specular sparkle.
            float radiusBoost = 1.0 + 0.15 * uAudio.x * uAudioReact;
            float midOrbit = 1.0 + 0.5 * uAudio.y * uAudioReact;
            float sparkleBoost = 1.0 + 1.5 * uAudio.z * uAudioReact;

            // Build orbiting camera at (cameraAngle + 8*t) degrees, height
            // cameraHeight, at radius cameraDistance from origin.
            float ang = radians(uCameraAngle) + t * 8.0 * midOrbit * 0.0174533;
            vec3 ro = vec3(cos(ang) * uCameraDistance, uCameraHeight, sin(ang) * uCameraDistance);
            vec3 ta = vec3(0.0, 0.3, 0.0);
            vec3 fw = normalize(ta - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);
            // Map FOV to a focal length so wider FOV expands the view.
            float focal = 1.0 / tan(radians(uFov) * 0.5);
            // Negate uv.y because GpuShaderRunner's vertex shader Y-flip makes
            // gl_FragCoord.y grow top→bottom; without this the scene renders
            // upside-down (floor above the objects).
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction (unit vector toward the light).
            float la = radians(uLightAngle);
            vec3 ld = normalize(vec3(cos(la), uLightHeight, sin(la)));

            // Bounce loop: primary hit, then optional reflect / refract bounces.
            vec3 col = vec3(0.0);
            vec3 curRo = ro;
            vec3 curRd = rd;
            float attenuation = 1.0;

            for (int b = 0; b < 5; b++) {
                if (b > uMaxBounces) break;

                vec3 hitP;
                float tHit = rayMarch(curRo, curRd, radiusBoost, hitP);

                if (tHit < 0.0) {
                    // Missed: accumulate sky and stop.
                    col += sampleSky(curRd) * attenuation;
                    break;
                }

                bool isGround = hitP.y < -1.4;
                vec3 n = calcNormal(hitP, radiusBoost, 0.0025);
                vec3 v = normalize(curRo - hitP);

                if (isGround) {
                    col += shadeGround(hitP, n, v, ld) * attenuation;
                    break;
                }

                // Glass surface shading. Sample environment along reflected
                // and refracted rays for the next bounce, but also add a
                // direct palette-tinted interior contribution this hop so
                // the surface still reads as coloured glass at bounce 0.
                float cosI = clamp(dot(-curRd, n), 0.0, 1.0);
                float fres = fresnelSchlick(cosI, uIor);

                // Palette-tinted interior colour, biased by material.
                float interiorT = clamp(uTintBias + 0.25 * (0.5 + 0.5 * n.y), 0.0, 1.0);
                vec3 tint = palette_color(interiorT);

                // Specular highlight (Blinn-Phong style).
                vec3 h = normalize(ld + v);
                float spec = pow(clamp(dot(n, h), 0.0, 1.0), 80.0);

                // Edge brightening for that glassy rim.
                float edge = pow(1.0 - cosI, 3.0);

                vec3 surfaceContrib = tint * (0.25 + 0.55 * cosI) +
                                      vec3(spec) * 0.7 * sparkleBoost +
                                      vec3(edge) * 0.35;
                col += surfaceContrib * attenuation * (0.35 + 0.3 * fres);

                // Beer's law absorption for tinted media along the ray segment.
                if (uAbsorption > 0.0) {
                    attenuation *= exp(-uAbsorption * tHit * 0.5);
                }
                attenuation *= 0.85;

                // Choose next ray: reflect when fresnel dominates, otherwise
                // refract into the surface (with TIR fallback to reflection).
                vec3 nextDir;
                if (fres > 0.5 || b == uMaxBounces) {
                    nextDir = reflect(curRd, n);
                } else {
                    float eta = 1.0 / uIor;
                    vec3 r = refract(curRd, n, eta);
                    if (dot(r, r) < 1e-6) {
                        nextDir = reflect(curRd, n);
                    } else {
                        nextDir = r;
                    }
                }
                curRo = hitP + nextDir * 0.01;
                curRd = nextDir;
            }

            // Exposure + tone-map + gamma.
            col *= uExposure;
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
