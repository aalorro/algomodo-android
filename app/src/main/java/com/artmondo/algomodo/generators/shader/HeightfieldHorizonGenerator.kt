package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU ray-marched terrain heightfield with an atmospheric horizon.
 *
 * A fragment shader sphere-marches a ray against an analytical 2D FBM
 * heightfield (built from a cheap value-noise hash), shades by altitude
 * and slope, and blends into a palette-driven sky / haze toward the
 * horizon. Biome presets adjust the FBM profile to produce mountains,
 * dunes, alien plateaus, or archipelago islands.
 *
 * Audio reactivity: bass nudges terrain amplitude, mids slightly warp the
 * sun direction, highs add subtle sparkle on lit slopes.
 */
class HeightfieldHorizonGenerator : GpuGenerator {

    override val id: String = "shader-heightfield-horizon"
    override val family: String = "shader"
    override val styleName: String = "Heightfield Horizon"
    override val definition: String =
        "Terrain rendering with FBM noise heightfield, biome presets, and atmospheric fog."
    override val algorithmNotes: String =
        "GPU fragment shader marches a ray over an analytical 2D fractional-Brownian-motion " +
        "heightfield (value-noise hash, 5 octaves). Each step advances by a fraction of the gap " +
        "between the ray and the surface; when the ray dips below the heightfield, five binary " +
        "bisection refinements pin down the intersection. A central-difference gradient of the " +
        "FBM gives the surface normal, which drives diffuse + specular shading. Colour comes " +
        "from a palette lookup keyed on altitude and slope, then is blended into a palette-derived " +
        "sky / haze gradient using exp(-dist * density) for atmospheric perspective. Final colour " +
        "is Reinhard-tone-mapped and gamma-corrected."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.NumberParam("Camera Height", "cameraHeight", ParamGroup.COMPOSITION,
            "Camera elevation above terrain", 1f, 10f, 0.5f, 3f),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 70f),
        Parameter.NumberParam("Camera Angle", "cameraAngle", ParamGroup.COMPOSITION,
            "Horizontal look direction", 0f, 360f, 5f, 0f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Sun horizontal direction", 0f, 360f, 5f, 60f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Sun elevation", 0.1f, 2f, 0.1f, 0.6f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.3f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed (camera drift)", 0.1f, 2f, 0.1f, 0.3f),
        Parameter.SelectParam("Biome", "biome", ParamGroup.COMPOSITION,
            "Terrain biome type",
            listOf("Mountains", "Dunes", "AlienPlateaus", "Archipelago"), "Mountains"),
        Parameter.NumberParam("Terrain Scale", "terrainScale", ParamGroup.GEOMETRY,
            "Horizontal terrain scale", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Terrain Height", "terrainHeight", ParamGroup.GEOMETRY,
            "Vertical terrain scale", 0.5f, 4f, 0.25f, 1.5f),
        Parameter.NumberParam("Fog", "fog", ParamGroup.TEXTURE,
            "Atmospheric fog density", 0f, 0.1f, 0.005f, 0.03f),
        Parameter.NumberParam("Audio React", "audioReact", ParamGroup.FLOW_MOTION,
            "How strongly audio modulates the look", 0f, 1f, 0.05f, 0.4f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cameraHeight" to 3f, "fov" to 70f, "cameraAngle" to 0f,
        "lightAngle" to 60f, "lightHeight" to 0.6f, "exposure" to 1.3f, "speed" to 0.3f,
        "biome" to "Mountains", "terrainScale" to 1.5f, "terrainHeight" to 1.5f, "fog" to 0.03f,
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
        val camH = (params["cameraHeight"] as? Float) ?: 3f
        val fov = (params["fov"] as? Float) ?: 70f
        val camAngle = (params["cameraAngle"] as? Float) ?: 0f
        val lightAngle = (params["lightAngle"] as? Float) ?: 60f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.6f
        val exposure = (params["exposure"] as? Float) ?: 1.3f
        val spd = (params["speed"] as? Float) ?: 0.3f
        val biome = (params["biome"] as? String) ?: "Mountains"
        val terrainScale = (params["terrainScale"] as? Float) ?: 1.5f
        val terrainH = (params["terrainHeight"] as? Float) ?: 1.5f
        val fogDensity = (params["fog"] as? Float) ?: 0.03f
        val audioReact = (params["audioReact"] as? Float) ?: 0.4f

        val biomeIdx = when (biome) {
            "Dunes" -> 1
            "AlienPlateaus" -> 2
            "Archipelago" -> 3
            else -> 0 // Mountains
        }

        // Heightfield ray-march likes more steps than a typical DE marcher.
        val maxSteps = when (quality) {
            Quality.DRAFT -> 64
            Quality.BALANCED -> 128
            Quality.ULTRA -> 192
        }

        // Seed = phase offset for camera drift / terrain origin shift.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamHeight"), camH)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCamAngle"), camAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), spd)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBiome"), biomeIdx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTerrainScale"), terrainScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTerrainHeight"), terrainH)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFog"), fogDensity)
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

        uniform float uCamHeight;
        uniform float uFov;
        uniform float uCamAngle;
        uniform float uLightAngle;
        uniform float uLightHeight;
        uniform float uExposure;
        uniform float uSpeed;
        uniform int uBiome;
        uniform float uTerrainScale;
        uniform float uTerrainHeight;
        uniform float uFog;
        uniform float uAudioReact;
        uniform float uPhase;
        uniform int uMaxSteps;

        out vec4 fragColor;

        const float PI = 3.14159265359;

        // ---- Value noise (cheap hash-based) -------------------------------
        float hash21(vec2 p) {
            p = fract(p * vec2(123.34, 456.21));
            p += dot(p, p + 45.32);
            return fract(p.x * p.y);
        }

        float vnoise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            float a = hash21(i);
            float b = hash21(i + vec2(1.0, 0.0));
            float c = hash21(i + vec2(0.0, 1.0));
            float d = hash21(i + vec2(1.0, 1.0));
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        float fbm(vec2 p, int octaves, float lac, float gain) {
            float sum = 0.0;
            float amp = 0.5;
            float freq = 1.0;
            for (int i = 0; i < 8; i++) {
                if (i >= octaves) break;
                sum += amp * vnoise(p * freq);
                freq *= lac;
                amp *= gain;
            }
            return sum;
        }

        // ---- Heightfield (biome-driven) -----------------------------------
        float terrainHeight(vec2 xz) {
            // Normalise to [-1,1]-ish via 2*n-1 where useful.
            vec2 s = xz / uTerrainScale;
            float bassBoost = 1.0 + 0.25 * uAudio.x * uAudioReact;
            float h = 0.0;
            if (uBiome == 1) {
                // Dunes — smooth wind-parallel ridges with asymmetric crest
                // bias. We deliberately AVOID abs(sin(.)) because the sharp
                // V-cusps at sin=0 read as crevices rather than dune troughs.
                // Instead use (sin*0.5+0.5) so the wave is non-negative and
                // rounded at both crest and trough, then apply a mild gamma
                // to lift the crest peaks while keeping troughs flat (mimics
                // the asymmetric windward/leeward profile of real dunes).
                float n = fbm(s * 0.35, 4, 2.0, 0.5);    // 0..1, slow base undulation
                float warp = (n - 0.5) * 4.0;            // FBM phase warp
                float primary = sin(s.x * 1.1 + warp) * 0.5 + 0.5;
                // Pow biases the wave toward the crests (asymmetric profile).
                primary = pow(primary, 0.7);
                // Cross-axis modulation breaks up the perfect parallel look.
                float cross = sin(s.y * 0.45 + n * 2.5) * 0.5 + 0.5;
                // High-freq fine sand grain on the surface.
                float grain = fbm(s * 2.5, 3, 2.0, 0.45) * 0.08;
                h = (primary * 0.65 + cross * 0.2 + n * 0.15 + grain) * uTerrainHeight * 1.3;
            } else if (uBiome == 2) {
                // AlienPlateaus — stepped terraces with cliff-sharp tier
                // edges. Quantising the FBM into discrete elevation steps
                // produces the layered mesa geology that reads as "alien".
                // A small per-tier detail noise keeps tier surfaces from
                // looking perfectly flat, and a touch of edge smoothing
                // avoids extreme aliasing on cliff silhouettes.
                float n = fbm(s * 0.6, 4, 2.0, 0.5);     // 0..1
                const float tiers = 5.0;
                float scaled = n * tiers;
                float tierIdx = floor(scaled);
                float tierFrac = scaled - tierIdx;
                // Sharp cliff: stay flat for most of the tier, then ramp up
                // hard near the top so the next tier line is crisp.
                float edge = smoothstep(0.88, 0.98, tierFrac);
                float terraced = (tierIdx + edge) / tiers;
                // High-frequency rocky surface detail on each tier.
                float detail = (fbm(s * 3.0, 3, 2.0, 0.5) - 0.5) * 0.08;
                h = (terraced + detail) * uTerrainHeight * 1.4;
            } else if (uBiome == 3) {
                // Archipelago
                float n = fbm(s * 0.6, 5, 2.0, 0.5);     // 0..1
                h = max(n - 0.1, 0.0) * uTerrainHeight * 2.0 - 0.3;
            } else {
                // Mountains
                float n = fbm(s * 0.7, 6, 2.1, 0.48) * 2.0 - 1.0; // -1..1
                h = abs(n) * uTerrainHeight * 1.5;
            }
            return h * bassBoost;
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            float t = uTime * uSpeed + uPhase;

            float camRad = uCamAngle * PI / 180.0;
            // Camera drifts forward along the look azimuth.
            vec3 ro = vec3(sin(camRad) * t * 5.0, uCamHeight, cos(camRad) * t * 5.0);
            vec3 tgt = vec3(ro.x + sin(camRad) * 10.0, ro.y - 1.0, ro.z + cos(camRad) * 10.0);

            vec3 fw = normalize(tgt - ro);
            vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
            vec3 up = cross(fw, rt);
            float focal = 1.0 / tan(0.5 * uFov * PI / 180.0);
            vec3 rd = normalize(uv.x * rt - uv.y * up + focal * fw);

            // Light direction (slightly audio-warped).
            float lRad = (uLightAngle + 30.0 * uAudio.y * uAudioReact) * PI / 180.0;
            vec3 ld = normalize(vec3(sin(lRad), uLightHeight, cos(lRad)));

            // ---- Heightfield ray-march ------------------------------------
            float marchT = 0.1;
            float maxDist = 80.0;
            bool hit = false;
            vec3 hitP = vec3(0.0);

            for (int i = 0; i < 512; i++) {
                if (i >= uMaxSteps) break;
                vec3 sp = ro + rd * marchT;
                float th = terrainHeight(sp.xz);
                if (sp.y < th) {
                    // Binary-search refinement.
                    float lo = marchT - max(marchT * 0.1, 0.1);
                    float hi = marchT;
                    for (int r = 0; r < 5; r++) {
                        float mid = 0.5 * (lo + hi);
                        vec3 mp = ro + rd * mid;
                        float mh = terrainHeight(mp.xz);
                        if (mp.y < mh) hi = mid; else lo = mid;
                    }
                    marchT = 0.5 * (lo + hi);
                    hitP = ro + rd * marchT;
                    hit = true;
                    break;
                }
                float gap = sp.y - th;
                marchT += max(gap * 0.5, 0.2);
                if (marchT > maxDist) break;
            }

            vec3 col;
            if (hit) {
                // Normal from terrain gradient (central differences would cost
                // more samples; forward difference matches the CPU source).
                float eps = 0.1;
                float hc = terrainHeight(hitP.xz);
                float hx = terrainHeight(hitP.xz + vec2(eps, 0.0));
                float hz = terrainHeight(hitP.xz + vec2(0.0, eps));
                vec3 n = normalize(vec3(-(hx - hc) / eps, 1.0, -(hz - hc) / eps));

                // Colour from height + slope.
                float slope = 1.0 - n.y;
                float heightNorm = clamp(hitP.y / (uTerrainHeight * 2.0), 0.0, 1.0);
                float colorT = clamp(heightNorm * 0.7 + slope * 0.3, 0.0, 1.0);
                vec3 base = palette_color(colorT);

                // Phong-ish shading: ambient + diffuse + specular.
                vec3 viewDir = -rd;
                float ndl = max(dot(n, ld), 0.0);
                vec3 halfV = normalize(ld + viewDir);
                float spec = pow(max(dot(n, halfV), 0.0), 20.0);
                float shade = 0.2 + 0.65 * ndl + 0.15 * spec;
                // High-frequency audio adds sparkle on lit slopes.
                shade += 0.15 * spec * uAudio.z * uAudioReact;

                vec3 surface = base * shade;

                // Atmospheric haze: blend toward palette-derived fog colour.
                float fogAmt = 1.0 - exp(-marchT * uFog);
                vec3 fogColor = palette_color(0.0) * 0.5;
                col = mix(surface, fogColor, fogAmt);
            } else {
                // Sky gradient: top vs bottom from palette stops 1 and 2,
                // matching the CPU original.
                float skyT = clamp(rd.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 skyTop = palette_color(0.25);
                vec3 skyBot = palette_color(0.5);
                col = mix(skyBot, skyTop, skyT);
            }

            // Exposure + Reinhard tone map + gamma.
            col *= uExposure;
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
