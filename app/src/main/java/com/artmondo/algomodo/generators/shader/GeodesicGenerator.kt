package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched geodesic dome wireframe. Constructs an icosahedron, subdivides
 * each face up to 3 times via a small fixed-size vertex/edge table that lives
 * in shader uniforms, and renders the distance field to the nearest edge segment
 * as a thin tube. Position-based palette colouring plus Phong shading creates
 * gradient highlights along the wireframe.
 *
 * Audio reactivity: bass nudges wire thickness, mids/highs add a soft glow boost.
 */
class GeodesicGenerator : GpuGenerator {

    override val id: String = "shader-geodesic-3d"
    override val family: String = "shader"
    override val styleName: String = "Geodesic 3D"
    override val definition: String =
        "Geodesic sphere wireframe rendered via distance field from subdivided icosahedron edges."
    override val algorithmNotes: String =
        "Fragment-shader sphere-tracing of a geodesic-dome SDF. The 12 base icosahedron " +
        "vertices are subdivided 0-3 times into a normalised vertex table, then the scene " +
        "distance at any point is the minimum point-to-segment distance to every edge minus " +
        "a wire-thickness offset. Surface shading is Phong with ACES tone mapping; the " +
        "background uses a palette-driven sky gradient."
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
        Parameter.NumberParam("Subdivisions", "subdivisions", ParamGroup.GEOMETRY,
            "Geodesic subdivision level (0-2)", 0f, 2f, 1f, 1f),
        Parameter.NumberParam("Wire Thickness", "wireThickness", ParamGroup.GEOMETRY,
            "Wireframe edge thickness", 0.01f, 0.1f, 0.005f, 0.03f),
        Parameter.NumberParam("Radius", "radius", ParamGroup.GEOMETRY,
            "Sphere radius", 0.5f, 2f, 0.1f, 1.2f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.4f)
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
        "subdivisions" to 1f,
        "wireThickness" to 0.03f,
        "radius" to 1.2f,
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
        val cameraDistance = (params["cameraDistance"] as? Float) ?: 4f
        val fov = (params["fov"] as? Float) ?: 60f
        val cameraAngle = (params["cameraAngle"] as? Float) ?: 0f
        val cameraHeight = (params["cameraHeight"] as? Float) ?: 0.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val subdivisions = ((params["subdivisions"] as? Float) ?: 1f).toInt().coerceIn(0, 2)
        val wireThickness = (params["wireThickness"] as? Float) ?: 0.03f
        val radius = (params["radius"] as? Float) ?: 1.2f
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        // Quality-driven raymarch step budget.
        val maxSteps = when (quality) {
            Quality.DRAFT -> 48
            Quality.BALANCED -> 80
            Quality.ULTRA -> 128
        }

        // Seed-driven phase offset so different seeds produce visually different
        // patterns without affecting parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCameraDistance"), cameraDistance)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCameraAngle"), cameraAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCameraHeight"), cameraHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSubdivisions"), subdivisions)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWireThickness"), wireThickness)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRadius"), radius)
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
        uniform int uSubdivisions;
        uniform float uWireThickness;
        uniform float uRadius;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float PHI = 1.6180339887;

        // 12 base icosahedron vertices (un-normalised; normalise at use time).
        // Matches the CPU original's baseVerts table.
        const vec3 V0  = vec3(-1.0,  PHI,  0.0);
        const vec3 V1  = vec3( 1.0,  PHI,  0.0);
        const vec3 V2  = vec3(-1.0, -PHI,  0.0);
        const vec3 V3  = vec3( 1.0, -PHI,  0.0);
        const vec3 V4  = vec3( 0.0, -1.0,  PHI);
        const vec3 V5  = vec3( 0.0,  1.0,  PHI);
        const vec3 V6  = vec3( 0.0, -1.0, -PHI);
        const vec3 V7  = vec3( 0.0,  1.0, -PHI);
        const vec3 V8  = vec3( PHI,  0.0, -1.0);
        const vec3 V9  = vec3( PHI,  0.0,  1.0);
        const vec3 V10 = vec3(-PHI,  0.0, -1.0);
        const vec3 V11 = vec3(-PHI,  0.0,  1.0);

        // Point-to-segment distance.
        float distSeg(vec3 p, vec3 a, vec3 b) {
            vec3 ab = b - a;
            vec3 ap = p - a;
            float abLenSq = dot(ab, ab);
            float t = clamp(dot(ap, ab) / max(abLenSq, 1e-8), 0.0, 1.0);
            vec3 c = a + t * ab - p;
            return length(c);
        }

        // Project a vertex to the sphere of given radius.
        vec3 sph(vec3 v, float r) {
            return normalize(v) * r;
        }

        // Distance to the 30 edges of the base icosahedron, scaled to radius.
        float distIcosaEdges(vec3 p, float r) {
            float d = 1e9;
            // 30 unique edges of the icosahedron (matches CPU original's face list).
            d = min(d, distSeg(p, sph(V0,  r), sph(V11, r)));
            d = min(d, distSeg(p, sph(V0,  r), sph(V5,  r)));
            d = min(d, distSeg(p, sph(V0,  r), sph(V1,  r)));
            d = min(d, distSeg(p, sph(V0,  r), sph(V7,  r)));
            d = min(d, distSeg(p, sph(V0,  r), sph(V10, r)));
            d = min(d, distSeg(p, sph(V1,  r), sph(V5,  r)));
            d = min(d, distSeg(p, sph(V1,  r), sph(V7,  r)));
            d = min(d, distSeg(p, sph(V1,  r), sph(V8,  r)));
            d = min(d, distSeg(p, sph(V1,  r), sph(V9,  r)));
            d = min(d, distSeg(p, sph(V5,  r), sph(V11, r)));
            d = min(d, distSeg(p, sph(V5,  r), sph(V4,  r)));
            d = min(d, distSeg(p, sph(V5,  r), sph(V9,  r)));
            d = min(d, distSeg(p, sph(V11, r), sph(V10, r)));
            d = min(d, distSeg(p, sph(V11, r), sph(V4,  r)));
            d = min(d, distSeg(p, sph(V11, r), sph(V2,  r)));
            d = min(d, distSeg(p, sph(V10, r), sph(V7,  r)));
            d = min(d, distSeg(p, sph(V10, r), sph(V2,  r)));
            d = min(d, distSeg(p, sph(V10, r), sph(V6,  r)));
            d = min(d, distSeg(p, sph(V7,  r), sph(V8,  r)));
            d = min(d, distSeg(p, sph(V7,  r), sph(V6,  r)));
            d = min(d, distSeg(p, sph(V8,  r), sph(V9,  r)));
            d = min(d, distSeg(p, sph(V8,  r), sph(V6,  r)));
            d = min(d, distSeg(p, sph(V8,  r), sph(V3,  r)));
            d = min(d, distSeg(p, sph(V9,  r), sph(V4,  r)));
            d = min(d, distSeg(p, sph(V9,  r), sph(V3,  r)));
            d = min(d, distSeg(p, sph(V4,  r), sph(V2,  r)));
            d = min(d, distSeg(p, sph(V4,  r), sph(V3,  r)));
            d = min(d, distSeg(p, sph(V2,  r), sph(V6,  r)));
            d = min(d, distSeg(p, sph(V2,  r), sph(V3,  r)));
            d = min(d, distSeg(p, sph(V6,  r), sph(V3,  r)));
            return d;
        }

        // Distance to a single subdivided triangular face. Splits face (a,b,c)
        // by 1 level into 4 sub-triangles and returns min edge distance.
        // Subdivision projects midpoints back to the sphere of radius r.
        float distSubdividedFace(vec3 p, vec3 a, vec3 b, vec3 c, float r) {
            vec3 ab = sph(0.5 * (a + b), r);
            vec3 bc = sph(0.5 * (b + c), r);
            vec3 ca = sph(0.5 * (c + a), r);
            float d = 1e9;
            // Sub-triangle (a, ab, ca)
            d = min(d, distSeg(p, a,  ab));
            d = min(d, distSeg(p, ab, ca));
            d = min(d, distSeg(p, ca, a));
            // Sub-triangle (b, bc, ab)
            d = min(d, distSeg(p, b,  bc));
            d = min(d, distSeg(p, bc, ab));
            d = min(d, distSeg(p, ab, b));
            // Sub-triangle (c, ca, bc)
            d = min(d, distSeg(p, c,  ca));
            d = min(d, distSeg(p, ca, bc));
            d = min(d, distSeg(p, bc, c));
            // Central sub-triangle (ab, bc, ca)
            d = min(d, distSeg(p, ab, bc));
            d = min(d, distSeg(p, bc, ca));
            d = min(d, distSeg(p, ca, ab));
            return d;
        }

        // Two-level subdivision of a face: subdivide once, then recurse one more
        // step on each child by inlining another subdivision.
        float distFaceLevel2(vec3 p, vec3 a, vec3 b, vec3 c, float r) {
            vec3 ab = sph(0.5 * (a + b), r);
            vec3 bc = sph(0.5 * (b + c), r);
            vec3 ca = sph(0.5 * (c + a), r);
            float d = 1e9;
            d = min(d, distSubdividedFace(p, a,  ab, ca, r));
            d = min(d, distSubdividedFace(p, b,  bc, ab, r));
            d = min(d, distSubdividedFace(p, c,  ca, bc, r));
            d = min(d, distSubdividedFace(p, ab, bc, ca, r));
            return d;
        }

        // Const array of base icosahedron vertices for direct lookup.
        // GLES 3.0 supports const array initialisers; this is far cheaper
        // than a 12-way if-chain at every face iteration.
        const vec3 BASE_VERTS[12] = vec3[12](
            V0, V1, V2, V3, V4, V5, V6, V7, V8, V9, V10, V11
        );

        // 20 icosahedron face vertex indices, packed as ivec3.
        const ivec3 FACE_IDX[20] = ivec3[20](
            ivec3( 0, 11,  5), ivec3( 0,  5,  1), ivec3( 0,  1,  7), ivec3( 0,  7, 10),
            ivec3( 0, 10, 11), ivec3( 1,  5,  9), ivec3( 5, 11,  4), ivec3(11, 10,  2),
            ivec3(10,  7,  6), ivec3( 7,  1,  8), ivec3( 3,  9,  4), ivec3( 3,  4,  2),
            ivec3( 3,  2,  6), ivec3( 3,  6,  8), ivec3( 3,  8,  9), ivec3( 4,  9,  5),
            ivec3( 2,  4, 11), ivec3( 6,  2, 10), ivec3( 8,  6,  7), ivec3( 9,  8,  1)
        );

        // Iterate the 20 faces and accumulate min edge distance for the chosen
        // subdivision level. Per-face cap culling: each icosahedron face caps
        // a spherical region. A face's three vertices have minimum dot-product
        // ~0.795 with the face centroid direction (cos of circumradius). Any
        // query point whose direction differs by more than that angle plus a
        // small thickness slack cannot be the nearest face — skip it.
        float distSubdivided(vec3 p, int level, float r, float thickSlack) {
            float d = 1e9;
            vec3 pDir = p / max(length(p), 1e-6);
            // Threshold: cos(circumradius + slack). Circumradius of an icosa
            // face is ~37.4°; we widen by slack (~thick/r in radians) to be
            // conservative when very close to edges.
            float cullCos = 0.78 - thickSlack;
            for (int f = 0; f < 20; f++) {
                ivec3 fi = FACE_IDX[f];
                vec3 a = sph(BASE_VERTS[fi.x], r);
                vec3 b = sph(BASE_VERTS[fi.y], r);
                vec3 c = sph(BASE_VERTS[fi.z], r);
                vec3 faceCenter = normalize(a + b + c);
                if (dot(pDir, faceCenter) < cullCos) continue;
                if (level == 1) {
                    d = min(d, distSubdividedFace(p, a, b, c, r));
                } else {
                    d = min(d, distFaceLevel2(p, a, b, c, r));
                }
            }
            return d;
        }

        // Top-level scene SDF with animated rotation.
        float sceneSDF(vec3 p) {
            float t = uTime * uSpeed + uPhase;
            float rotY = t * 0.4;
            float rotX = t * 0.2;
            float cY = cos(rotY), sY = sin(rotY);
            float cX = cos(rotX), sX = sin(rotX);

            // Rotate point inversely (matches CPU original).
            float rx = p.x * cY - p.z * sY;
            float rz = p.x * sY + p.z * cY;
            float ry2 = p.y * cX - rz * sX;
            float rz2 = p.y * sX + rz * cX;
            vec3 q = vec3(rx, ry2, rz2);

            // Audio-modulated wire thickness.
            float thick = uWireThickness * (1.0 + 0.5 * uAudio.x * uAudioReact);

            // Conservative bound: every edge of the geodesic lives on the sphere
            // of radius uRadius, so any point's distance to the structure is at
            // least its distance to that shell. When far from the shell, return
            // the cheap bound and skip the very expensive per-edge loop (which
            // does up to 20*4*4*12 segment-distance calculations at subdiv=3).
            float shellDist = abs(length(q) - uRadius);
            if (shellDist > thick * 6.0) {
                return shellDist - thick;
            }

            float d;
            if (uSubdivisions <= 0) {
                d = distIcosaEdges(q, uRadius);
            } else {
                // Slack in cull threshold scales with wire thickness so we
                // never cull a face whose edge is within `thick` of `p`.
                float thickSlack = thick / max(uRadius, 1e-3);
                d = distSubdivided(q, uSubdivisions, uRadius, thickSlack);
            }

            return d - thick;
        }

        vec3 calcNormal(vec3 p) {
            float e = 0.001;
            vec2 h = vec2(1.0, -1.0) * 0.5773;
            return normalize(
                h.xyy * sceneSDF(p + h.xyy * e) +
                h.yyx * sceneSDF(p + h.yyx * e) +
                h.yxy * sceneSDF(p + h.yxy * e) +
                h.xxx * sceneSDF(p + h.xxx * e)
            );
        }

        // ACES-ish filmic tone mapping (simple Narkowicz approximation).
        vec3 toneMapACES(vec3 x) {
            const float a = 2.51;
            const float b = 0.03;
            const float c = 2.43;
            const float d = 0.59;
            const float e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            // Camera setup from params.
            float ang = radians(uCameraAngle);
            vec3 ro = vec3(sin(ang) * uCameraDistance, uCameraHeight, cos(ang) * uCameraDistance);
            vec3 target = vec3(0.0);
            vec3 fw = normalize(target - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);

            // FOV-based focal length.
            float focal = 1.0 / tan(radians(uFov) * 0.5);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction from params.
            float la = radians(uLightAngle);
            vec3 ld = normalize(vec3(sin(la), uLightHeight, cos(la)));

            // Sphere-trace.
            float total = 0.0;
            bool hit = false;
            vec3 hitPos = vec3(0.0);
            for (int s = 0; s < 256; s++) {
                if (s >= uMaxSteps) break;
                vec3 p = ro + rd * total;
                if (length(p) > 12.0) break;
                float d = sceneSDF(p);
                total += max(d, 0.001);
                if (d < 0.0015) {
                    hit = true;
                    hitPos = p;
                    break;
                }
                if (total > 20.0) break;
            }

            vec3 col;
            if (hit) {
                vec3 n = calcNormal(hitPos);
                vec3 viewDir = normalize(ro - hitPos);

                // Color from position on sphere (matches CPU formula).
                float colorT = clamp(
                    atan(hitPos.z, hitPos.x) / PI * 0.5 + 0.5 + hitPos.y * 0.3,
                    0.0, 1.0
                );
                // Mid-band audio nudges the colour along the palette.
                colorT = clamp(colorT + 0.1 * uAudio.y * uAudioReact, 0.0, 1.0);
                vec3 base = palette_color(colorT);

                // Phong shading: ambient + diffuse + specular.
                float diff = max(dot(n, ld), 0.0);
                vec3 halfVec = normalize(ld + viewDir);
                float spec = pow(max(dot(n, halfVec), 0.0), 40.0);
                float shade = 0.15 + 0.6 * diff + 0.5 * spec;

                // High-band audio adds a soft glow boost.
                float glow = 1.0 + 0.4 * uAudio.z * uAudioReact;
                col = base * shade * uExposure * glow;
            } else {
                // Soft sky gradient from palette, biased by ray Y.
                float skyT = clamp(0.5 + 0.5 * rd.y, 0.0, 1.0);
                vec3 sky = mix(vec3(0.03, 0.04, 0.10), palette_color(skyT) * 0.35, 0.6);
                col = sky * uExposure;
            }

            // Tone-map + gamma (project convention).
            col = toneMapACES(col);
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
