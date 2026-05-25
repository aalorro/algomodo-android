package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched cluster of soft metaballs. A small fixed pool of spheres is
 * blended via a polynomial smooth-minimum (smin) producing organic, jelly-like
 * surfaces. Ball positions are derived deterministically from the seed and
 * animate via sin/cos of [uTime].
 *
 * Audio reactivity: bass slightly inflates ball radii, mids modulate the
 * surface palette offset, highs add a rim/glow term.
 */
class MetaballCluster3dGenerator : GpuGenerator {

    override val id: String = "shader-metaball-cluster-3d"
    override val family: String = "shader"
    override val styleName: String = "Metaball Cluster 3D"
    override val definition: String =
        "Soft blobs that merge organically in 3D, rendered via smooth-minimum ray marching."
    override val algorithmNotes: String =
        "Fragment-shader sphere tracing of a polynomial smooth-union (smin) over a small fixed " +
        "pool of metaballs. Each ball has a seed-derived rest position, radius, orbital axis, and " +
        "phase, and animates by adding sin/cos(uTime) offsets along its axis. The scene SDF is the " +
        "smin reduction of all ball SDFs; the surface normal is recovered with a 4-tap central " +
        "gradient. Surface colour comes from a palette lookup driven by world position plus a mid/high " +
        "audio term, with a Fresnel-style rim glow added on top. Standard Reinhard + 2.2 gamma."
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
        Parameter.NumberParam("Balls", "ballCount", ParamGroup.COMPOSITION,
            "Number of metaballs", 4f, 25f, 1f, 6f),
        Parameter.NumberParam("Smoothness", "smoothness", ParamGroup.GEOMETRY,
            "Merge smoothness between balls", 0.5f, 3f, 0.25f, 1.5f),
        Parameter.NumberParam("Motion Radius", "motionRadius", ParamGroup.FLOW_MOTION,
            "Orbital motion radius", 0.5f, 3f, 0.25f, 1.5f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f,
        "fov" to 60f,
        "cameraAngle" to 0f,
        "cameraHeight" to 0.5f,
        "lightAngle" to 45f,
        "lightHeight" to 0.8f,
        "exposure" to 1.2f,
        "speed" to 0.5f,
        "ballCount" to 6f,
        "smoothness" to 1.5f,
        "motionRadius" to 1.5f,
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
        val cameraDistance = (params["cameraDistance"] as? Float) ?: 4f
        val fov = (params["fov"] as? Float) ?: 60f
        val cameraAngle = (params["cameraAngle"] as? Float) ?: 0f
        val cameraHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val ballCount = ((params["ballCount"] as? Float) ?: 6f).toInt().coerceIn(2, 25)
        val smoothness = (params["smoothness"] as? Float) ?: 1.5f
        val motionRadius = (params["motionRadius"] as? Float) ?: 1.5f
        val audioReact = (params["audioReact"] as? Float) ?: 0.5f

        // Seed-driven phase offset so different seeds produce distinct ball
        // layouts/orbits without changing parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBallCount"), ballCount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSmoothness"), smoothness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uMotionRadius"), motionRadius)
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

        uniform float uCameraDistance;
        uniform float uFov;
        uniform float uCameraAngle;
        uniform float uCameraHeight;
        uniform float uLightAngle;
        uniform float uLightHeight;
        uniform float uExposure;
        uniform float uSpeed;
        uniform int uBallCount;
        uniform float uSmoothness;
        uniform float uMotionRadius;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const int MAX_BALLS = 25;
        const float PI = 3.14159265358979;

        // Cheap hash-based "random" in [0,1) from an integer index and a salt.
        float hash11(float n) {
            return fract(sin(n) * 43758.5453123);
        }

        // Returns ball position and radius for index i at current time.
        // x,y,z = animated position; w = radius (audio-inflated).
        vec4 ballAt(int i) {
            float fi = float(i) + 1.0;
            float s = uPhase;

            // Seeded base position in [-1.5,1.5] x [-1,1] x [-1.5,1.5]
            vec3 basePos = vec3(
                (hash11(fi * 12.9898 + s) - 0.5) * 3.0,
                (hash11(fi * 78.233  + s * 1.7) - 0.5) * 2.0,
                (hash11(fi * 37.719  + s * 2.3) - 0.5) * 3.0
            );

            float radius = 0.3 + hash11(fi * 91.345 + s * 0.5) * 0.4;

            // Orbit speed in [0.3,1.2], randomly signed.
            float orbitSpd = (0.3 + hash11(fi * 53.987 + s * 1.1) * 0.9)
                           * (hash11(fi * 17.111 + s * 0.7) > 0.5 ? 1.0 : -1.0);
            float orbitPhase = hash11(fi * 23.713 + s * 1.9) * 2.0 * PI;

            // Normalised orbit axis.
            vec3 axis = normalize(vec3(
                hash11(fi * 11.111 + s * 3.1) - 0.5,
                hash11(fi * 22.222 + s * 3.3) - 0.5,
                hash11(fi * 33.333 + s * 3.7) - 0.5
            ) + 1e-4);

            float a = uTime * uSpeed * orbitSpd + orbitPhase;
            float ca = cos(a) * uMotionRadius * 0.3;
            float sa = sin(a) * uMotionRadius * 0.3;

            // Same orbit construction as CPU original: scaled axis + perpendicular swirl.
            vec3 perp1 = vec3(axis.z, axis.x, axis.y);
            vec3 anim = basePos + axis * ca + perp1 * sa * 0.5;

            // Audio inflates radii slightly via bass.
            float r = radius * (1.0 + 0.15 * uAudio.x * uAudioReact);
            return vec4(anim, r);
        }

        // Polynomial smooth-minimum (Inigo Quilez).
        float sminPoly(float a, float b, float k) {
            float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
            return mix(b, a, h) - k * h * (1.0 - h);
        }

        float sceneSDF(vec3 p) {
            vec4 b0 = ballAt(0);
            float d = length(p - b0.xyz) - b0.w;
            for (int i = 1; i < MAX_BALLS; i++) {
                if (i >= uBallCount) break;
                vec4 b = ballAt(i);
                float di = length(p - b.xyz) - b.w;
                d = sminPoly(d, di, uSmoothness);
            }
            return d;
        }

        // 4-tap central-difference normal.
        vec3 calcNormal(vec3 p) {
            const float h = 0.002;
            const vec2 k = vec2(1.0, -1.0);
            return normalize(
                k.xyy * sceneSDF(p + k.xyy * h) +
                k.yyx * sceneSDF(p + k.yyx * h) +
                k.yxy * sceneSDF(p + k.yxy * h) +
                k.xxx * sceneSDF(p + k.xxx * h)
            );
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            // Camera setup from spherical params.
            float ca = radians(uCameraAngle);
            vec3 ro = vec3(sin(ca) * uCameraDistance, uCameraHeight, cos(ca) * uCameraDistance);
            vec3 fw = normalize(-ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);

            // FOV-driven focal length.
            float focal = 1.0 / tan(radians(uFov) * 0.5);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction in world space.
            float la = radians(uLightAngle);
            vec3 ld = normalize(vec3(sin(la), uLightHeight, cos(la)));

            // Sphere-trace.
            float total = 0.0;
            bool hit = false;
            vec3 pHit = vec3(0.0);
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                float d = sceneSDF(p);
                if (d < 0.0008) { hit = true; pHit = p; break; }
                total += max(d, 0.0008);
                if (total > 20.0) break;
            }

            vec3 col;
            if (hit) {
                vec3 n = calcNormal(pHit);
                vec3 v = normalize(ro - pHit);

                // Diffuse + specular (Phong-ish).
                float diff = max(dot(n, ld), 0.0);
                vec3 half = normalize(ld + v);
                float spec = pow(max(dot(n, half), 0.0), 40.0);
                float ambient = 0.2;

                // Palette lookup driven by surface position and audio mid band.
                float t01 = clamp(
                    0.5 + 0.3 * (n.x + n.y) + 0.15 * uAudio.y * uAudioReact,
                    0.0, 1.0
                );
                vec3 base = palette_color(t01);

                // Fresnel-style rim glow from highs.
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
                vec3 rimCol = palette_color(0.85) * rim * (0.4 + 0.8 * uAudio.z * uAudioReact);

                col = base * (ambient + 0.65 * diff) + vec3(0.5) * spec + rimCol;
                col *= uExposure;
            } else {
                // Soft palette sky gradient, biased to the upper palette slots.
                vec3 top = palette_color(0.55);
                vec3 bot = palette_color(0.15);
                float h01 = clamp(0.5 + 0.5 * rd.y, 0.0, 1.0);
                col = mix(bot, top, h01) * 0.6 * uExposure;
            }

            // Reinhard tone-map + gamma.
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
