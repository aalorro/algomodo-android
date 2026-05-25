package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU port of TwistedForms: domain-deformed SDF primitives ray-marched on
 * the fragment shader. Applies a chosen deformation (Twist / Bend /
 * NoiseDisplace / Ripple / TwistAndBend) to the sample point, then evaluates
 * one of several base primitives (Box / Cylinder / Torus / Capsule /
 * Octahedron). Distance is halved to keep the Lipschitz bound conservative
 * after the deformation breaks it.
 *
 * Audio reactivity: bass nudges the deformation strength, mids add a colour
 * shift, highs add a subtle rim glow.
 */
class TwistedFormsGenerator : GpuGenerator {

    override val id: String = "shader-twisted-forms"
    override val family: String = "shader"
    override val styleName: String = "Twisted Forms"
    override val definition: String =
        "Domain-twisted SDF geometries producing organic sculptural shapes."
    override val algorithmNotes: String =
        "Fragment shader sphere-traces a base SDF primitive (box/cylinder/torus/capsule/" +
        "octahedron) after applying a domain deformation (twist about Y, bend about Z, " +
        "noise displacement, radial ripple, or twist+bend). Distance estimate is halved " +
        "to compensate for the deformation breaking the Lipschitz bound. Lit with Phong " +
        "shading plus 5-step ambient occlusion, with palette colour driven by the surface " +
        "normal. Camera orbits around the origin; audio modulates twist amount and glow."
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
        Parameter.SelectParam("Deformation", "deformation", ParamGroup.GEOMETRY,
            "Domain deformation type",
            listOf("Twist", "Bend", "NoiseDisplace", "Ripple", "TwistAndBend"), "Twist"),
        Parameter.SelectParam("Base Shape", "baseShape", ParamGroup.GEOMETRY,
            "Base SDF primitive",
            listOf("Box", "Cylinder", "Torus", "Capsule", "Octahedron"), "Torus"),
        Parameter.NumberParam("Deform Strength", "deformStrength", ParamGroup.GEOMETRY,
            "Deformation intensity", 0.5f, 5f, 0.25f, 2f),
        Parameter.NumberParam("Deform Freq", "deformFrequency", ParamGroup.GEOMETRY,
            "Spatial frequency of deformation", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "deformation" to "Twist", "baseShape" to "Torus",
        "deformStrength" to 2f, "deformFrequency" to 1.5f,
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
        val camDist = (params["cameraDistance"] as? Float) ?: 4f
        val fov = (params["fov"] as? Float) ?: 60f
        val camAngle = (params["cameraAngle"] as? Float) ?: 0f
        val camHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val deformation = (params["deformation"] as? String) ?: "Twist"
        val baseShape = (params["baseShape"] as? String) ?: "Torus"
        val deformStr = (params["deformStrength"] as? Float) ?: 2f
        val deformFreq = (params["deformFrequency"] as? Float) ?: 1.5f
        val audioReact = (params["audioReact"] as? Float) ?: 0.5f

        val deformIdx = when (deformation) {
            "Twist" -> 0
            "Bend" -> 1
            "NoiseDisplace" -> 2
            "Ripple" -> 3
            "TwistAndBend" -> 4
            else -> 0
        }
        val shapeIdx = when (baseShape) {
            "Box" -> 0
            "Cylinder" -> 1
            "Torus" -> 2
            "Capsule" -> 3
            "Octahedron" -> 4
            else -> 2
        }

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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uDeformation"), deformIdx)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBaseShape"), shapeIdx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDeformStrength"), deformStr)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDeformFreq"), deformFreq)
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
        uniform int uDeformation;
        uniform int uBaseShape;
        uniform float uDeformStrength;
        uniform float uDeformFreq;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const float PI = 3.14159265359;

        // -------- cheap hash-based value noise --------
        float hash3(vec3 p) {
            p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
            p *= 17.0;
            return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
        }

        float vnoise(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float n000 = hash3(i + vec3(0.0, 0.0, 0.0));
            float n100 = hash3(i + vec3(1.0, 0.0, 0.0));
            float n010 = hash3(i + vec3(0.0, 1.0, 0.0));
            float n110 = hash3(i + vec3(1.0, 1.0, 0.0));
            float n001 = hash3(i + vec3(0.0, 0.0, 1.0));
            float n101 = hash3(i + vec3(1.0, 0.0, 1.0));
            float n011 = hash3(i + vec3(0.0, 1.0, 1.0));
            float n111 = hash3(i + vec3(1.0, 1.0, 1.0));
            float nx00 = mix(n000, n100, f.x);
            float nx10 = mix(n010, n110, f.x);
            float nx01 = mix(n001, n101, f.x);
            float nx11 = mix(n011, n111, f.x);
            float nxy0 = mix(nx00, nx10, f.y);
            float nxy1 = mix(nx01, nx11, f.y);
            return mix(nxy0, nxy1, f.z) * 2.0 - 1.0;
        }

        float fbm3(vec3 p) {
            float a = 0.5;
            float s = 0.0;
            for (int i = 0; i < 3; i++) {
                s += a * vnoise(p);
                p *= 2.0;
                a *= 0.5;
            }
            return s;
        }

        // -------- SDF primitives --------
        float sdBox(vec3 p, vec3 b) {
            vec3 q = abs(p) - b;
            return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
        }

        float sdCylinder(vec3 p, float r, float h) {
            vec2 d = abs(vec2(length(p.xz), p.y)) - vec2(r, h);
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
        }

        float sdTorus(vec3 p, float R, float r) {
            vec2 q = vec2(length(p.xz) - R, p.y);
            return length(q) - r;
        }

        float sdCapsule(vec3 p, vec3 a, vec3 b, float r) {
            vec3 pa = p - a;
            vec3 ba = b - a;
            float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
            return length(pa - ba * h) - r;
        }

        float sdOctahedron(vec3 p, float s) {
            p = abs(p);
            return (p.x + p.y + p.z - s) * 0.57735027;
        }

        // -------- scene SDF with deformation --------
        float sceneSDF(vec3 p, float animRate, float t) {
            vec3 q = p;

            if (uDeformation == 0) {
                // Twist about Y
                float a = q.y * animRate;
                float c = cos(a);
                float s = sin(a);
                vec2 r = mat2(c, -s, s, c) * q.xz;
                q.x = r.x;
                q.z = r.y;
            } else if (uDeformation == 1) {
                // Bend about Z (rotates XY by angle scaled by X)
                float a = q.x * animRate * 0.5;
                float c = cos(a);
                float s = sin(a);
                vec2 r = mat2(c, -s, s, c) * q.xy;
                q.x = r.x;
                q.y = r.y;
            } else if (uDeformation == 2) {
                // Noise displacement
                float n0 = fbm3(p * uDeformFreq + vec3(t * 0.2, 0.0, 0.0));
                float n1 = vnoise(vec3(p.y, p.z, p.x) * uDeformFreq + vec3(17.0, 0.0, t * 0.2));
                float n2 = vnoise(vec3(p.z, p.x, p.y) * uDeformFreq + vec3(31.0, t * 0.2, 0.0));
                q.x += n0 * uDeformStrength * 0.2;
                q.y += n1 * uDeformStrength * 0.2;
                q.z += n2 * uDeformStrength * 0.2;
            } else if (uDeformation == 3) {
                // Radial ripple displaces Y
                float d = length(q.xz);
                float ripple = sin(d * uDeformFreq * 3.0 - t * 2.0) * uDeformStrength * 0.1;
                q.y += ripple;
            } else {
                // Twist + Bend
                float a1 = q.y * animRate * 0.5;
                float c1 = cos(a1);
                float s1 = sin(a1);
                vec2 r1 = mat2(c1, -s1, s1, c1) * q.xz;
                q.x = r1.x;
                q.z = r1.y;
                float a2 = q.x * animRate * 0.3;
                float c2 = cos(a2);
                float s2 = sin(a2);
                vec2 r2 = mat2(c2, -s2, s2, c2) * q.xy;
                q.x = r2.x;
                q.y = r2.y;
            }

            float d;
            if (uBaseShape == 0) {
                d = sdBox(q, vec3(0.8));
            } else if (uBaseShape == 1) {
                d = sdCylinder(q, 0.6, 1.2);
            } else if (uBaseShape == 2) {
                d = sdTorus(q, 1.0, 0.35);
            } else if (uBaseShape == 3) {
                d = sdCapsule(q, vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0), 0.4);
            } else {
                d = sdOctahedron(q, 1.0);
            }

            // Halve distance to compensate for domain deformation breaking Lipschitz bound
            return d * 0.5;
        }

        vec3 calcNormal(vec3 p, float animRate, float t) {
            float e = 0.001;
            vec2 k = vec2(1.0, -1.0);
            return normalize(
                k.xyy * sceneSDF(p + k.xyy * e, animRate, t) +
                k.yyx * sceneSDF(p + k.yyx * e, animRate, t) +
                k.yxy * sceneSDF(p + k.yxy * e, animRate, t) +
                k.xxx * sceneSDF(p + k.xxx * e, animRate, t)
            );
        }

        float ambientOcclusion(vec3 p, vec3 n, float animRate, float t) {
            float ao = 0.0;
            float scale = 1.0;
            for (int i = 0; i < 5; i++) {
                float h = 0.01 + 0.12 * float(i) / 4.0;
                float d = sceneSDF(p + n * h, animRate, t);
                ao += (h - d) * scale;
                scale *= 0.85;
            }
            return clamp(1.0 - 1.4 * ao, 0.0, 1.0);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            float t = uTime * uSpeed + uPhase;

            // Audio-modulated deformation rate
            float bassMod = uAudio.x * uAudioReact;
            float animRate = uDeformStrength + sin(t) * 0.5 + bassMod * 1.0;

            // Camera position from orbit angle + height
            float camA = radians(uCamAngle);
            vec3 ro = vec3(sin(camA) * uCamDist, uCamHeight, cos(camA) * uCamDist);
            vec3 ta = vec3(0.0);
            vec3 fw = normalize(ta - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);
            float focal = 1.0 / tan(radians(uFov) * 0.5);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction
            float la = radians(uLightAngle);
            vec3 ld = normalize(vec3(sin(la), uLightHeight, cos(la)));

            // Ray-march
            float total = 0.0;
            bool hit = false;
            float minDist = 1e9;
            const float EPS = 0.0015;
            const float FAR = 30.0;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                float d = sceneSDF(p, animRate, t);
                minDist = min(minDist, d);
                if (d < EPS) { hit = true; break; }
                total += max(d, EPS * 0.5);
                if (total > FAR) break;
            }

            vec3 col;
            if (hit) {
                vec3 p = ro + rd * total;
                vec3 n = calcNormal(p, animRate, t);

                // Palette colour from normal matcap-style
                float colorT = clamp(n.x * 0.5 + 0.5 + n.y * 0.3, 0.0, 1.0);
                colorT = clamp(colorT + 0.15 * uAudio.y * uAudioReact, 0.0, 1.0);
                vec3 base = palette_color(colorT);

                // Phong shading
                vec3 v = normalize(ro - p);
                float diff = max(dot(n, ld), 0.0);
                vec3 r = reflect(-ld, n);
                float spec = pow(max(dot(r, v), 0.0), 35.0);
                float ao = ambientOcclusion(p, n, animRate, t);
                float shade = 0.15 + 0.65 * diff + 0.5 * spec;

                // Rim glow modulated by highs
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
                float rimBoost = 0.25 * uAudio.z * uAudioReact;

                col = base * shade * ao + vec3(rim * rimBoost);
            } else {
                // Sky gradient from palette
                vec3 top = palette_color(0.25);
                vec3 bot = palette_color(0.55);
                float sky = clamp(rd.y * 0.5 + 0.5, 0.0, 1.0);
                col = mix(bot, top, sky) * 0.55;
            }

            // Exposure
            col *= uExposure;

            // Tone-map + gamma
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
