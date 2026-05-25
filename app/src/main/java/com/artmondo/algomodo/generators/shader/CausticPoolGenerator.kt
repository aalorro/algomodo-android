package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU-rendered animated water caustics. A ray traced from the camera hits an
 * animated water surface (sum of radial sine waves emanating from seeded source
 * points), refracts into the pool, and shades a patterned floor with caustic
 * brightness derived from the surface Laplacian. Fresnel-blended with a sky.
 *
 * Audio reactivity: bass swells wave amplitude, mids speed up phase, highs
 * brighten the caustic peaks.
 */
class CausticPoolGenerator : GpuGenerator {

    override val id: String = "shader-caustic-pool"
    override val family: String = "shader"
    override val styleName: String = "Caustic Pool"
    override val definition: String =
        "Volumetric water caustics simulation with refraction patterns on a pool floor."
    override val algorithmNotes: String =
        "Eye-ray hits water plane at y=0, accumulates wave height from N radial sine " +
        "sources (seeded positions). 5-point stencil gives surface normal and Laplacian " +
        "(caustic). Snell refraction projects to the pool floor at y=-poolDepth, which " +
        "is shaded by the chosen Floor Style (Tiles/Marble/Mosaic/Sand) using the " +
        "palette. Sky uses palette sweep, Fresnel mixes reflection. Audio modulates " +
        "amplitude, speed, and caustic brightness."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Camera Dist", "cameraDistance", ParamGroup.COMPOSITION,
            "Distance of camera from origin", 1f, 12f, 0.5f, 4f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 40f),
        Parameter.NumberParam("Camera Angle", "cameraAngle", ParamGroup.COMPOSITION,
            "Horizontal camera orbit angle", 0f, 360f, 5f, 30f),
        Parameter.NumberParam("Camera Height", "cameraHeight", ParamGroup.COMPOSITION,
            "Vertical camera position", 0.5f, 5f, 0.1f, 2.5f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Horizontal light direction", 0f, 360f, 5f, 45f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Vertical light direction", 0.5f, 3f, 0.1f, 1.5f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.4f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Wave Count", "waveCount", ParamGroup.GEOMETRY,
            "Number of radial wave sources", 2f, 8f, 1f, 4f),
        Parameter.NumberParam("Wave Frequency", "waveFrequency", ParamGroup.GEOMETRY,
            "Spatial frequency of waves", 1f, 6f, 0.5f, 3f),
        Parameter.NumberParam("Wave Amplitude", "waveAmplitude", ParamGroup.GEOMETRY,
            "Height of waves", 0.01f, 0.15f, 0.01f, 0.05f),
        Parameter.NumberParam("Depth", "poolDepth", ParamGroup.GEOMETRY,
            "Pool depth below water surface", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.SelectParam("Floor Style", "floorStyle", ParamGroup.TEXTURE,
            "Pattern on the pool floor",
            listOf("Tiles", "Marble", "Mosaic", "Sand"), "Tiles"),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the waves", 0f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraDistance" to 4f,
        "fov" to 40f,
        "cameraAngle" to 30f,
        "cameraHeight" to 2.5f,
        "lightAngle" to 45f,
        "lightHeight" to 1.5f,
        "exposure" to 1.4f,
        "speed" to 0.5f,
        "waveCount" to 4f,
        "waveFrequency" to 3f,
        "waveAmplitude" to 0.05f,
        "poolDepth" to 1.5f,
        "floorStyle" to "Tiles",
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
        val fov = (params["fov"] as? Float) ?: 40f
        val camAngle = (params["cameraAngle"] as? Float) ?: 30f
        val camHeight = (params["cameraHeight"] as? Float) ?: 2.5f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 1.5f
        val exposure = (params["exposure"] as? Float) ?: 1.4f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val waveCount = ((params["waveCount"] as? Float) ?: 4f).toInt().coerceIn(2, 8)
        val waveFrequency = (params["waveFrequency"] as? Float) ?: 3f
        val waveAmplitude = (params["waveAmplitude"] as? Float) ?: 0.05f
        val poolDepth = (params["poolDepth"] as? Float) ?: 1.5f
        val floorStyle = (params["floorStyle"] as? String) ?: "Tiles"
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        val styleId = when (floorStyle) {
            "Marble" -> 1
            "Mosaic" -> 2
            "Sand" -> 3
            else -> 0 // Tiles
        }

        // Seeded wave source positions in the xz plane plus a per-source phase.
        // Mirrors the CPU reference (SeededRNG range -3..3, randomAngle).
        // We pack into a flat float[24] = up to 8 sources of (x, z, phase).
        val waveSources = FloatArray(8 * 3)
        var s = seed
        fun next(): Float {
            // xorshift32 — same family as SeededRNG's underlying generator
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            // Normalise to (-1, 1)
            return ((s ushr 8) and 0xFFFFFF) / 8_388_608f - 1f
        }
        for (i in 0 until 8) {
            waveSources[i * 3] = next() * 3f     // x in -3..3
            waveSources[i * 3 + 1] = next() * 3f // z in -3..3
            waveSources[i * 3 + 2] = next() * Math.PI.toFloat()
        }

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamDist"), camDist)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamAngle"), camAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamHeight"), camHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uWaveCount"), waveCount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWaveFreq"), waveFrequency)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWaveAmp"), waveAmplitude)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPoolDepth"), poolDepth)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFloorStyle"), styleId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uWaveSources"), 8, waveSources, 0)
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
        uniform int uWaveCount;
        uniform float uWaveFreq;
        uniform float uWaveAmp;
        uniform float uPoolDepth;
        uniform int uFloorStyle;
        uniform float uAudioReact;
        uniform vec3 uWaveSources[8]; // (x, z, phase) per source

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float WATER_IOR = 1.33;

        // ---- Hash helpers (used by Mosaic/Sand floor styles) -------------
        float hash21(vec2 p) {
            p = fract(p * vec2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }
        float hash22(vec2 p, float k) {
            return hash21(p + vec2(k * 17.0, k * 29.0));
        }

        // ---- Wave height at (x, z) given current animated time / amp ----
        float waveHeightAt(float x, float z, float ampMul, float timePhase) {
            float h = 0.0;
            float amp = uWaveAmp * ampMul;
            for (int i = 0; i < 8; i++) {
                if (i >= uWaveCount) break;
                vec3 src = uWaveSources[i];
                float dx = x - src.x;
                float dz = z - src.y;
                float d = sqrt(dx * dx + dz * dz);
                float att = amp / (1.0 + d * 0.3);
                h += sin(d * uWaveFreq - (timePhase - src.z)) * att;
            }
            return h;
        }

        // ---- Sky background using palette ----
        vec3 skyColor(vec3 rd) {
            float t = clamp(rd.y * 0.5 + 0.5, 0.0, 1.0);
            return mix(palette_color(0.2) * 0.4,
                       palette_color(0.75) * 0.7, t);
        }

        // ---- Floor pattern selectors (all return base colour in 0..1) ---
        vec3 floorTiles(vec2 p) {
            // Discrete tile palette index lookup. Use a 2-axis modular index
            // so adjacent tiles get different colours.
            float fx = floor(p.x * 2.0);
            float fz = floor(p.y * 2.0);
            float k = mod(abs(fx + fz * 1.7), 5.0) / 4.0;
            return palette_color(k);
        }

        vec3 floorMarble(vec2 p) {
            float vein = sin(p.x * 3.0 + p.y * 2.0
                + sin(p.x * 5.0) * 0.5 + sin(p.y * 4.0) * 0.3) * 0.5 + 0.5;
            return palette_color(vein);
        }

        vec3 floorMosaic(vec2 p) {
            // Cheap voronoi: jittered cell points, find nearest among the 3x3
            // neighbours of the containing cell.
            vec2 s = p * 1.5;
            vec2 cell = floor(s);
            vec2 f = fract(s);
            float minDist = 10.0;
            float cellId = 0.0;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    vec2 c = cell + vec2(float(di), float(dj));
                    float hv = hash21(c);
                    float hv2 = hash22(c, 3.7);
                    vec2 off = vec2(float(di) + hv * 0.8 - f.x,
                                    float(dj) + hv2 * 0.8 - f.y);
                    float d2 = dot(off, off);
                    if (d2 < minDist) { minDist = d2; cellId = hv; }
                }
            }
            float edge = clamp(sqrt(minDist) * 3.0, 0.0, 1.0);
            return palette_color(cellId) * edge;
        }

        vec3 floorSand(vec2 p) {
            float n1 = hash21(floor(p * 10.0));
            float n2 = hash21(floor(p * 3.0) + vec2(13.0, 7.0));
            float blend = clamp(n1 * 0.4 + n2 * 0.6, 0.0, 1.0);
            float sandBright = 0.7 + n1 * 0.3;
            return palette_color(blend) * sandBright;
        }

        vec3 floorColor(vec2 p) {
            if (uFloorStyle == 1) return floorMarble(p);
            if (uFloorStyle == 2) return floorMosaic(p);
            if (uFloorStyle == 3) return floorSand(p);
            return floorTiles(p);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            // Animated time. Mids speed up the wave phase a touch.
            float speed = uSpeed * (1.0 + 0.5 * uAudio.y * uAudioReact);
            float timePhase = uTime * speed * 3.0;

            // Bass swells wave amplitude.
            float ampMul = 1.0 + uAudio.x * 1.2 * uAudioReact;

            // Camera setup — look at origin from (camDist, camHeight) at camAngle.
            float camAng = uCamAngle * PI / 180.0;
            vec3 ro = vec3(sin(camAng) * uCamDist, uCamHeight, cos(camAng) * uCamDist);
            vec3 fw = normalize(vec3(0.0, -0.5, 0.0) - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);
            float focal = 1.0 / tan(uFov * PI / 360.0);
            // Negate uv.y so on-screen up matches world up (compensates for the
            // GpuShaderRunner vertex-shader Y-flip).
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction.
            float la = uLightAngle * PI / 180.0;
            vec3 ld = normalize(vec3(sin(la), uLightHeight, cos(la)));

            // Intersect water plane at y=0. If ray points away from water,
            // render sky directly.
            vec3 col;
            if (abs(rd.y) < 0.0001 || ro.y * rd.y > 0.0) {
                col = skyColor(rd);
            } else {
                float waterHit = -ro.y / rd.y;
                if (waterHit <= 0.0) {
                    col = skyColor(rd);
                } else {
                    float hitX = ro.x + rd.x * waterHit;
                    float hitZ = ro.z + rd.z * waterHit;

                    // 5-point stencil for normal + Laplacian (caustic).
                    float eps = 0.03;
                    float invEps = 1.0 / eps;
                    float invEps2 = 1.0 / (eps * eps);
                    float hc  = waveHeightAt(hitX,       hitZ,       ampMul, timePhase);
                    float hPx = waveHeightAt(hitX + eps, hitZ,       ampMul, timePhase);
                    float hNx = waveHeightAt(hitX - eps, hitZ,       ampMul, timePhase);
                    float hPz = waveHeightAt(hitX,       hitZ + eps, ampMul, timePhase);
                    float hNz = waveHeightAt(hitX,       hitZ - eps, ampMul, timePhase);

                    float dhdx = (hPx - hNx) * 0.5 * invEps;
                    float dhdz = (hPz - hNz) * 0.5 * invEps;
                    float nLen = sqrt(dhdx * dhdx + 1.0 + dhdz * dhdz);
                    vec3 normal = vec3(-dhdx, 1.0, -dhdz) / nLen;

                    float laplacian = abs((hPx - 2.0 * hc + hNx) * invEps2
                                        + (hPz - 2.0 * hc + hNz) * invEps2);
                    float caustic = min(laplacian * 2.0, 3.0);
                    // Highs sharpen caustic peaks.
                    caustic *= 1.0 + uAudio.z * uAudioReact;

                    // Snell refraction through water surface.
                    float NdotI = dot(rd, normal);
                    float eta = 1.0 / WATER_IOR;
                    float k = 1.0 - eta * eta * (1.0 - NdotI * NdotI);

                    vec3 floorCol = vec3(0.0);
                    if (k >= 0.0) {
                        float sq = sqrt(k);
                        vec3 refr = eta * rd - (eta * NdotI + sq) * normal;
                        float floorY = -uPoolDepth;
                        if (refr.y < -0.0001) {
                            float toFloor = (floorY - hc) / refr.y;
                            if (toFloor > 0.0) {
                                vec2 floorP = vec2(hitX + refr.x * toFloor,
                                                   hitZ + refr.z * toFloor);
                                vec3 base = floorColor(floorP);
                                float brightness = 0.3 + caustic * 1.5;
                                float fog = exp(-abs(toFloor) * 0.3);

                                // Palette-derived water tint (dark, blueish bias).
                                vec3 tint = palette_color(0.1) * 0.3
                                          + vec3(0.0, 0.1, 0.15);
                                floorCol = base * brightness * fog
                                         + tint * (1.0 - fog);
                            }
                        }
                    }

                    // Fresnel — inlined Schlick (avoids pow(5)).
                    float r0w = pow((1.0 - WATER_IOR) / (1.0 + WATER_IOR), 2.0);
                    float cosI = abs(NdotI);
                    float oneMinusC = 1.0 - cosI;
                    float omc2 = oneMinusC * oneMinusC;
                    float fres = r0w + (1.0 - r0w) * omc2 * omc2 * oneMinusC;

                    vec3 reflCol = skyColor(reflect(rd, normal));

                    // Direct sun glitter on the wave normal (specular highlight).
                    float spec = pow(max(dot(normal, normalize(ld - rd)), 0.0), 80.0);
                    vec3 specCol = palette_color(0.95) * spec * 0.6;

                    col = mix(floorCol, reflCol, clamp(fres * 1.2, 0.0, 1.0)) + specCol;
                }
            }

            // Exposure + Reinhard tone-map + gamma.
            col *= uExposure;
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
