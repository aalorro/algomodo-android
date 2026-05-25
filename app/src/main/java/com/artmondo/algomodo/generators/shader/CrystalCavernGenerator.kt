package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched crystal cavern. Domain-repeated lattice of rotated boxes
 * and octahedra combined with smooth-min boolean ops produces an interlocking
 * field of crystals, lit with simple AO based on step count and a Fresnel rim.
 *
 * Audio reactivity: bass nudges the crystal sharpness, mids modulate light
 * intensity, highs add sparkle to the rim glow.
 */
class CrystalCavernGenerator : GpuGenerator {

    override val id: String = "shader-crystal-cavern"
    override val family: String = "shader"
    override val styleName: String = "Crystal Cavern"
    override val definition: String =
        "Crystal formations via 3D Voronoi cell structure combined with fractal domain operations."
    override val algorithmNotes: String =
        "Fragment-shader sphere-tracing of an SDF cavern: a lattice of crystals built " +
        "via domain repetition (mod) of rotated boxes and octahedra, combined with " +
        "smooth-min boolean unions and a screw-symmetry twist along the vertical axis. " +
        "Shading uses step-count ambient occlusion, a Lambert + Blinn-Phong specular " +
        "term, and a Fresnel rim glow palette-mapped by surface position."
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
        Parameter.NumberParam("Cell Scale", "cellScale", ParamGroup.GEOMETRY,
            "Size of Voronoi cells", 1f, 4f, 0.25f, 2f),
        Parameter.NumberParam("Crystal Sharpness", "sharpness", ParamGroup.GEOMETRY,
            "Sharpness of crystal facets", 0.1f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Rim Glow", "rimGlow", ParamGroup.TEXTURE,
            "Fresnel rim glow intensity", 0f, 2f, 0.1f, 0.7f),
        Parameter.NumberParam("Roughness", "roughness", ParamGroup.TEXTURE,
            "Surface roughness", 0.1f, 1f, 0.05f, 0.25f),
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
        "cellScale" to 2f,
        "sharpness" to 0.8f,
        "rimGlow" to 0.7f,
        "roughness" to 0.25f,
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
        val cellScale = (params["cellScale"] as? Float) ?: 2f
        val sharpness = (params["sharpness"] as? Float) ?: 0.8f
        val rimGlow = (params["rimGlow"] as? Float) ?: 0.7f
        val roughness = (params["roughness"] as? Float) ?: 0.25f
        val audioReact = (params["audioReact"] as? Float) ?: 0.5f

        // Seed-driven phase offset so different seeds produce visually different
        // crystal arrangements without changing parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        // Quality-driven raymarch step budget.
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
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCellScale"), cellScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSharpness"), sharpness)
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

        uniform float uCameraDistance;
        uniform float uFov;
        uniform float uCameraAngle;
        uniform float uCameraHeight;
        uniform float uLightAngle;
        uniform float uLightHeight;
        uniform float uExposure;
        uniform float uSpeed;
        uniform float uCellScale;
        uniform float uSharpness;
        uniform float uRimGlow;
        uniform float uRoughness;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float DEG2RAD = PI / 180.0;

        // 2D rotation
        mat2 rot2(float a) {
            float c = cos(a);
            float s = sin(a);
            return mat2(c, -s, s, c);
        }

        // Smooth minimum (polynomial) for boolean union of crystals
        float smin(float a, float b, float k) {
            float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
            return mix(b, a, h) - k * h * (1.0 - h);
        }

        // Box SDF
        float sdBox(vec3 p, vec3 b) {
            vec3 q = abs(p) - b;
            return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
        }

        // Octahedron SDF
        float sdOcta(vec3 p, float s) {
            p = abs(p);
            return (p.x + p.y + p.z - s) * 0.57735027;
        }

        // A single crystal cluster: rotated box unioned with a rotated octahedron.
        // Used as the per-cell primitive in the domain-repeated lattice.
        float crystalUnit(vec3 p, float sharpness) {
            // Rotate the box on two axes so its facets aren't axis-aligned
            vec3 pb = p;
            pb.xz = rot2(0.7) * pb.xz;
            pb.yz = rot2(0.5) * pb.yz;
            // Scale box thickness with sharpness so higher sharpness = thinner crystals
            float thickness = 0.32 / max(sharpness, 0.1);
            float boxD = sdBox(pb, vec3(0.18, 0.55, 0.18) * (0.5 + 0.5 / max(sharpness, 0.1)))
                         - 0.02;

            vec3 po = p;
            po.xy = rot2(0.9) * po.xy;
            float octaD = sdOcta(po, 0.42);

            return smin(boxD, octaD, 0.08);
        }

        // Cavern SDF: lattice of crystals via domain repetition, plus screw symmetry
        // twist along the vertical axis, bounded by an outer sphere shell.
        float sceneSDF(vec3 p) {
            // Screw symmetry: twist the world about the Y axis with height
            float twist = 0.45 * sin(uPhase * 0.7);
            float a = p.y * twist + uPhase * 0.13;
            vec3 q = p;
            q.xz = rot2(a) * q.xz;

            // Scale into lattice space and repeat
            float cs = max(uCellScale, 0.25);
            vec3 r = q / cs;
            vec3 cellId = floor(r + 0.5);
            vec3 local = r - cellId;

            // Per-cell jitter so the lattice doesn't look perfectly regular.
            // Cheap hash from cell id.
            float h = fract(sin(dot(cellId, vec3(127.1, 311.7, 74.7))) * 43758.5453);
            float h2 = fract(sin(dot(cellId, vec3(269.5, 183.3, 246.1))) * 43758.5453);
            vec3 jitter = (vec3(h, h2, fract(h + h2)) - 0.5) * 0.35;
            local += jitter;

            // Per-cell rotation makes adjacent crystals interlock at different angles
            float cellRot = (h - 0.5) * 2.0 * PI;
            local.xz = rot2(cellRot) * local.xz;

            float sharp = uSharpness + uAudio.x * 0.6 * uAudioReact;
            float crystal = crystalUnit(local, sharp) * cs;

            // Also overlay a coarser cluster (2x the cell size, offset) for layered look
            vec3 r2 = (q + vec3(0.5, 0.0, 0.3)) / (cs * 2.0);
            vec3 cellId2 = floor(r2 + 0.5);
            vec3 local2 = r2 - cellId2;
            float h3 = fract(sin(dot(cellId2, vec3(54.3, 91.7, 33.1))) * 43758.5453);
            local2.xz = rot2(h3 * 6.2831) * local2.xz;
            float crystal2 = crystalUnit(local2, sharp * 0.8) * (cs * 2.0);

            float crystals = smin(crystal, crystal2, 0.18);

            // Outer spherical shell bound so the lattice doesn't fill infinite space
            float bound = length(p) - (4.5 + uCellScale * 0.4);

            return max(crystals, bound);
        }

        // Estimate normal via tetrahedral gradient (4 SDF samples)
        vec3 calcNormal(vec3 p, float eps) {
            vec2 e = vec2(1.0, -1.0) * 0.5773;
            return normalize(
                e.xyy * sceneSDF(p + e.xyy * eps) +
                e.yyx * sceneSDF(p + e.yyx * eps) +
                e.yxy * sceneSDF(p + e.yxy * eps) +
                e.xxx * sceneSDF(p + e.xxx * eps)
            );
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            float t = uTime * uSpeed;

            // Camera orbit
            float camA = uCameraAngle * DEG2RAD + t * (10.0 * DEG2RAD);
            float camDist = uCameraDistance;
            vec3 ro = vec3(sin(camA) * camDist, uCameraHeight, cos(camA) * camDist);
            vec3 target = vec3(0.0);
            vec3 fw = normalize(target - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);

            // FOV-driven focal length
            float fovRad = uFov * DEG2RAD;
            float focal = 1.0 / tan(fovRad * 0.5);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction
            float lightA = uLightAngle * DEG2RAD;
            vec3 ld = normalize(vec3(
                cos(lightA) * sqrt(max(0.0, 1.0 - uLightHeight * uLightHeight * 0.25)),
                uLightHeight,
                sin(lightA) * sqrt(max(0.0, 1.0 - uLightHeight * uLightHeight * 0.25))
            ));

            // Ray march
            float total = 0.0;
            int stepsTaken = 0;
            bool hit = false;
            float minDist = 1e9;
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                float d = sceneSDF(p);
                minDist = min(minDist, d);
                if (d < 0.0012) { hit = true; stepsTaken = s; break; }
                total += max(d * 0.85, 0.0008);
                if (total > 24.0) break;
                stepsTaken = s;
            }

            vec3 col;
            if (hit) {
                vec3 p = ro + rd * total;
                vec3 n = calcNormal(p, 0.0025);
                vec3 v = normalize(ro - p);

                // Step-count ambient occlusion: more steps to find this surface
                // means it's wedged into a crevice, so occlude it more.
                float aoSteps = float(stepsTaken) / float(uMaxSteps);
                float ao = clamp(1.0 - aoSteps * 1.2, 0.15, 1.0);

                // Lambert + Blinn-Phong specular
                float diff = max(0.0, dot(n, ld));
                vec3 h = normalize(ld + v);
                float shininess = 10.0 + (1.0 - uRoughness) * 120.0;
                float specAmt = (1.0 - uRoughness) * 0.9;
                float spec = pow(max(0.0, dot(n, h)), shininess) * specAmt;

                float lightBoost = 0.85 + 0.5 * uAudio.y * uAudioReact;
                float shade = (0.18 + 0.7 * diff) * lightBoost + spec;

                // Palette colour from surface position (per-cell variation comes
                // from the lattice indexing inside the SDF).
                float tCol = fract(p.x * 0.31 + p.y * 0.27 + p.z * 0.19 + uPhase * 0.05);
                vec3 base = palette_color(tCol);

                // Rim glow (Fresnel-style), tinted by a complementary palette pick
                float NdotV = max(0.0, dot(n, v));
                float fres = pow(1.0 - NdotV, 3.0) * uRimGlow * (1.0 + 0.6 * uAudio.z * uAudioReact);
                vec3 rimTint = palette_color(fract(tCol + 0.5));

                col = base * shade * ao + rimTint * fres;

                // Subtle depth fade so distant crystals don't blow out
                col *= exp(-0.04 * total);
            } else {
                // Sky / background: palette gradient based on view direction, with
                // a soft halo where rays nearly grazed the cavern.
                float halo = exp(-12.0 * minDist);
                vec3 sky = mix(
                    palette_color(0.05),
                    palette_color(0.35),
                    smoothstep(-0.3, 0.5, rd.y)
                );
                col = sky * 0.25 + palette_color(0.8) * halo * 0.4;
            }

            // Exposure, tone-map, gamma
            col *= uExposure;
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
