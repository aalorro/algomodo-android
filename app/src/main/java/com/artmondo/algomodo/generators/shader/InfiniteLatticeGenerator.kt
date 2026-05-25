package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched infinite 3D lattice. Domain repetition on 1-3 axes folds
 * a single SDF primitive into an infinite scaffold; the camera drifts forward
 * through the lattice to give the sense of falling through architecture.
 *
 * Audio reactivity: bass swells cell size, mids brighten the surface tint,
 * highs drive a specular sparkle on the lattice elements.
 */
class InfiniteLatticeGenerator : GpuGenerator {

    override val id: String = "shader-infinite-lattice"
    override val family: String = "shader"
    override val styleName: String = "Infinite Lattice"
    override val definition: String =
        "Procedurally tiled 3D lattice structures using domain repetition."
    override val algorithmNotes: String =
        "Domain repetition (opRepeat: p - cell * round(p/cell)) on 1-3 axes of a single " +
        "SDF primitive creates an infinite scaffold sphere-traced in a fragment shader. " +
        "Ten shape presets (sphere, box, pillar, cross, capsule, torus, octahedron, " +
        "wireframe, pyramid, MixedHashed) share the same march loop; MixedHashed picks " +
        "a different primitive per cell via integer hash of the cell index. Lighting is " +
        "phong with view-dependent specular, palette colour comes from world-space position, " +
        "and exponential fog hides the far clipping plane."

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
        Parameter.SelectParam("Shape", "latticeShape", ParamGroup.GEOMETRY,
            "Lattice element shape",
            listOf("Sphere", "Box", "Pillar", "Cross", "Capsule", "Torus", "Octahedron", "Wireframe", "Pyramid", "MixedHashed"),
            "Sphere"),
        Parameter.NumberParam("Cell Size", "cellSize", ParamGroup.GEOMETRY,
            "Spacing between repeated elements", 1.5f, 6f, 0.5f, 3f),
        Parameter.SelectParam("Repeat Axes", "repeatAxes", ParamGroup.GEOMETRY,
            "Which axes to repeat along",
            listOf("XYZ", "XZ", "XY", "X"), "XYZ"),
        Parameter.NumberParam("Fog", "fog", ParamGroup.TEXTURE,
            "Exponential fog density", 0f, 0.3f, 0.01f, 0.08f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f, "fov" to 60f, "cameraAngle" to 0f, "cameraHeight" to 0.5f,
        "lightAngle" to 45f, "lightHeight" to 0.8f, "exposure" to 1.2f, "speed" to 0.5f,
        "latticeShape" to "Sphere", "cellSize" to 3f, "repeatAxes" to "XYZ", "fog" to 0.08f,
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
        val camDist = (params["cameraDistance"] as? Float) ?: 4f
        val fov = (params["fov"] as? Float) ?: 60f
        val camAngle = (params["cameraAngle"] as? Float) ?: 0f
        val camHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val shape = (params["latticeShape"] as? String) ?: "Sphere"
        val cellSize = (params["cellSize"] as? Float) ?: 3f
        val axes = (params["repeatAxes"] as? String) ?: "XYZ"
        val fog = (params["fog"] as? Float) ?: 0.08f
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        val shapeId = when (shape) {
            "Box" -> 1; "Pillar" -> 2; "Cross" -> 3; "Capsule" -> 4
            "Torus" -> 5; "Octahedron" -> 6; "Wireframe" -> 7; "Pyramid" -> 8; "MixedHashed" -> 9
            else -> 0
        }
        val repX = if (axes.contains('X')) 1 else 0
        val repY = if (axes.contains('Y')) 1 else 0
        val repZ = if (axes.contains('Z')) 1 else 0

        // Seed = phase offset
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
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uShape"), shapeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCellSize"), cellSize)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uRepX"), repX)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uRepY"), repY)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uRepZ"), repZ)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFog"), fog)
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
        uniform int uShape;
        uniform float uCellSize;
        uniform int uRepX;
        uniform int uRepY;
        uniform int uRepZ;
        uniform float uFog;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        // ------------------------------------------------------------ SDF primitives
        float sdSphere(vec3 p, float r) { return length(p) - r; }

        float sdBox(vec3 p, vec3 b) {
            vec3 q = abs(p) - b;
            return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
        }

        float sdCylinder(vec3 p, float r, float h) {
            vec2 d = abs(vec2(length(p.xz), p.y)) - vec2(r, h);
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
        }

        float sdCapsule(vec3 p, vec3 a, vec3 b, float r) {
            vec3 pa = p - a;
            vec3 ba = b - a;
            float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
            return length(pa - ba * h) - r;
        }

        float sdTorus(vec3 p, float R, float r) {
            vec2 q = vec2(length(p.xz) - R, p.y);
            return length(q) - r;
        }

        float sdOctahedron(vec3 p, float s) {
            p = abs(p);
            return (p.x + p.y + p.z - s) * 0.57735027;
        }

        // Square-based pyramid; apex at +y.
        float sdPyramid(vec3 p, float h) {
            float m2 = h * h + 0.25;
            p.xz = abs(p.xz);
            p.xz = (p.z > p.x) ? p.zx : p.xz;
            p.xz -= 0.5;
            vec3 q = vec3(p.z, h * p.y - 0.5 * p.x, h * p.x + 0.5 * p.y);
            float s = max(-q.x, 0.0);
            float t = clamp((q.y - 0.5 * p.z) / (m2 + 0.25), 0.0, 1.0);
            float a = m2 * (q.x + s) * (q.x + s) + q.y * q.y;
            float b = m2 * (q.x + 0.5 * t) * (q.x + 0.5 * t) + (q.y - m2 * t) * (q.y - m2 * t);
            float d2 = min(q.y, -q.x * m2 - q.y * 0.5) > 0.0 ? 0.0 : min(a, b);
            return sqrt((d2 + q.z * q.z) / m2) * sign(max(q.z, -p.y));
        }

        // Cheap integer hash for MixedHashed cell selection.
        int cellHash(vec3 cell) {
            ivec3 c = ivec3(floor(cell));
            int h = c.x * 374761393 + c.y * 668265263 + c.z * 1274126177;
            h = (h ^ (h >> 13)) * 1274126177;
            return (h >> 16) & 0x7FFFFFFF;
        }

        // Domain-repetition fold: p - cell * round(p/cell).
        float opRepeat(float p, float cell) {
            return p - cell * floor(p / cell + 0.5);
        }

        float primitive(vec3 q, int sid, float elem) {
            if (sid == 1) return sdBox(q, vec3(elem));
            if (sid == 2) return sdCylinder(q, elem * 0.6, elem * 2.0);
            if (sid == 3) {
                float d1 = sdBox(q, vec3(elem * 2.0, elem * 0.3, elem * 0.3));
                float d2 = sdBox(q, vec3(elem * 0.3, elem * 2.0, elem * 0.3));
                float d3 = sdBox(q, vec3(elem * 0.3, elem * 0.3, elem * 2.0));
                return min(d1, min(d2, d3));
            }
            if (sid == 4) return sdCapsule(q, vec3(0.0, -elem, 0.0), vec3(0.0, elem, 0.0), elem * 0.4);
            if (sid == 5) return sdTorus(q, elem, elem * 0.3);
            if (sid == 6) return sdOctahedron(q, elem);
            if (sid == 7) {
                float outer = sdBox(q, vec3(elem));
                float inner = sdBox(q, vec3(elem * 0.8));
                return max(outer, -inner);
            }
            if (sid == 8) return sdPyramid(q - vec3(0.0, elem, 0.0), elem * 2.0);
            return sdSphere(q, elem);
        }

        // Scene: domain-repeat on the requested axes, then evaluate primitive.
        float sceneSDF(vec3 p, float cell, float elem) {
            vec3 q = p;
            if (uRepX == 1) q.x = opRepeat(q.x, cell);
            if (uRepY == 1) q.y = opRepeat(q.y, cell);
            if (uRepZ == 1) q.z = opRepeat(q.z, cell);

            int sid = uShape;
            if (sid == 9) {
                vec3 cellIdx = floor(p / cell);
                sid = cellHash(cellIdx) % 8;
            }
            return primitive(q, sid, elem);
        }

        vec3 calcNormal(vec3 p, float cell, float elem, float eps) {
            vec2 e = vec2(eps, 0.0);
            return normalize(vec3(
                sceneSDF(p + e.xyy, cell, elem) - sceneSDF(p - e.xyy, cell, elem),
                sceneSDF(p + e.yxy, cell, elem) - sceneSDF(p - e.yxy, cell, elem),
                sceneSDF(p + e.yyx, cell, elem) - sceneSDF(p - e.yyx, cell, elem)
            ));
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            float t = uTime * uSpeed + uPhase;

            // Audio-modulated cell size (bass swells the lattice)
            float cell = uCellSize * (1.0 + 0.15 * uAudio.x * uAudioReact);
            float elem = cell * 0.2;

            // Camera: orbit + drift forward through the lattice
            float ang = radians(uCamAngle);
            vec3 ro = vec3(sin(ang) * uCamDist, uCamHeight, cos(ang) * uCamDist);
            ro.z += t * 2.0;

            // Look forward (along -z in camera local frame)
            vec3 target = ro + vec3(0.0, 0.0, -1.0);
            vec3 fw = normalize(target - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);

            float focal = 1.0 / tan(radians(uFov) * 0.5);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction
            float la = radians(uLightAngle);
            vec3 ld = normalize(vec3(cos(la), uLightHeight, sin(la)));

            float total = 0.0;
            float eps = 0.001;
            float maxDist = 60.0;
            bool hit = false;
            vec3 hitP = vec3(0.0);

            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                float d = sceneSDF(p, cell, elem);
                if (d < eps) {
                    hit = true;
                    hitP = p;
                    break;
                }
                total += max(d, eps * 0.5);
                if (total > maxDist) break;
            }

            vec3 col;
            if (hit) {
                vec3 n = calcNormal(hitP, cell, elem, eps * 2.0);
                vec3 v = normalize(ro - hitP);

                // Palette colour driven by world-space position (matches CPU sin warp)
                float cT = clamp(0.5 + 0.5 * sin(hitP.x * 0.5 + hitP.z * 0.3), 0.0, 1.0);
                vec3 base = palette_color(cT);

                // Mid-frequency audio brightens the tint
                base *= 1.0 + 0.3 * uAudio.y * uAudioReact;

                // Phong shading
                float ambient = 0.15;
                float diff = max(dot(n, ld), 0.0) * 0.7;
                vec3 h = normalize(ld + v);
                float spec = pow(max(dot(n, h), 0.0), 30.0) * 0.4;

                // High-frequency audio adds sparkle on the specular highlight
                spec *= 1.0 + 1.2 * uAudio.z * uAudioReact;

                float shade = ambient + diff + spec;

                // Exponential fog hides far clipping
                float fogAmt = 1.0 - exp(-total * uFog);
                col = base * shade * (1.0 - fogAmt);
            } else {
                // Sky: vertical palette gradient
                float skyT = clamp(0.5 + 0.5 * rd.y, 0.0, 1.0);
                col = palette_color(0.15 + 0.7 * skyT) * 0.4;
            }

            // Exposure + filmic tone-map + gamma
            col *= uExposure;
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
