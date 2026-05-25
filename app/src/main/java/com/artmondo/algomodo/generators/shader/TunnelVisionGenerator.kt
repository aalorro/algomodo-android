package com.artmondo.algomodo.generators.shader

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform

/**
 * GPU 2D-style procedural tunnel. UV coords are mapped to polar (angle, depth = 1/r)
 * and a procedural cross-section / wall texture is sampled at scrolled coordinates,
 * producing the classic flying-through-a-tube effect cheaply in a single fragment
 * shader pass.
 *
 * Audio reactivity: bass distorts the tunnel path / cross-section, mids brighten
 * the wall pattern, highs sparkle on the lit ridges.
 */
class TunnelVisionGenerator : GpuGenerator {

    override val id: String = "shader-tunnel-vision"
    override val family: String = "shader"
    override val styleName: String = "Tunnel Vision"
    override val definition: String =
        "Flying through an SDF tube with procedural wall textures and sinuous path warping."
    override val algorithmNotes: String =
        "Fragment shader maps screen UVs into polar coordinates (angle = atan(uv.y, uv.x), " +
        "depth = 1 / length(uv)) to produce a cheap 2D-style tunnel. The cross-section " +
        "parameter modulates the radius around the angle (circle / square / hex / star / " +
        "gear / flower), and the wall texture (stripes / grid / voronoi / noise) is sampled " +
        "at (angle, depth + uTime) so the pattern scrolls toward the viewer. Path warping " +
        "shifts UVs with sin/cos of depth at two incommensurate frequencies derived from the " +
        "seed, giving sinuous tunnels without ray-marching. A cheap directional ridge term " +
        "driven by Light Angle / Light Height fakes a phong-style highlight, and the result " +
        "is palette-mapped, fog-faded with depth, and tone-mapped."
    override val supportsVector: Boolean = false
    override val supportsAnimation: Boolean = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Cross Section", "crossSection", ParamGroup.GEOMETRY,
            "Tunnel cross-section shape",
            listOf("Circle", "Square", "Hexagon", "Star", "Gear", "Flower"), "Circle"),
        Parameter.SelectParam("Wall Texture", "wallTexture", ParamGroup.TEXTURE,
            "Procedural pattern on tunnel walls",
            listOf("stripes", "grid", "voronoi", "noise"), "grid"),
        Parameter.NumberParam("FOV", "fov", ParamGroup.COMPOSITION,
            "Field of view in degrees", 20f, 120f, 5f, 60f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.TEXTURE,
            "Horizontal light direction", 0f, 360f, 5f, 45f),
        Parameter.NumberParam("Light Height", "lightHeight", ParamGroup.TEXTURE,
            "Vertical light direction", 0.1f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Exposure", "exposure", ParamGroup.TEXTURE,
            "Tone mapping exposure", 0.5f, 3f, 0.1f, 1.2f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Warp Amp", "warpAmplitude", ParamGroup.GEOMETRY,
            "How much the tunnel path curves", 0f, 2f, 0.1f, 0.8f),
        Parameter.NumberParam("Warp Freq", "warpFrequency", ParamGroup.GEOMETRY,
            "Spatial frequency of tunnel curves", 0.5f, 3f, 0.25f, 1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "crossSection" to "Circle",
        "wallTexture" to "grid",
        "fov" to 60f,
        "lightAngle" to 45f,
        "lightHeight" to 0.8f,
        "exposure" to 1.2f,
        "speed" to 0.5f,
        "warpAmplitude" to 0.8f,
        "warpFrequency" to 1f
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
        val crossSection = (params["crossSection"] as? String) ?: "Circle"
        val wallTexture = (params["wallTexture"] as? String) ?: "grid"
        val fov = (params["fov"] as? Float) ?: 60f
        val lightAngle = (params["lightAngle"] as? Float) ?: 45f
        val lightHeight = (params["lightHeight"] as? Float) ?: 0.8f
        val exposure = (params["exposure"] as? Float) ?: 1.2f
        val speed = (params["speed"] as? Float) ?: 0.5f
        val warpAmp = (params["warpAmplitude"] as? Float) ?: 0.8f
        val warpFreq = (params["warpFrequency"] as? Float) ?: 1f

        val crossIdx = when (crossSection) {
            "Square" -> 1
            "Hexagon" -> 2
            "Star" -> 3
            "Gear" -> 4
            "Flower" -> 5
            else -> 0 // Circle
        }
        val wallIdx = when (wallTexture) {
            "stripes" -> 0
            "voronoi" -> 2
            "noise" -> 3
            else -> 1 // grid
        }

        // Seed-driven phase offset so different seeds produce visually different
        // tunnel paths/textures without changing parameter semantics.
        val phaseOffset = ((seed and 0xFFFF) / 65535f) * (2f * Math.PI.toFloat())
        // Two incommensurate frequency multipliers derived from the seed,
        // matching the CPU original's f1 / f2 idea.
        val f1 = 0.3f + ((seed ushr 3) and 0xFF) / 255f * 0.5f
        val f2 = 0.3f + ((seed ushr 11) and 0xFF) / 255f * 0.5f

        // Audio reactivity strength is baked in (no separate param to keep the
        // schema VERBATIM); the shader still gates on warpAmp / exposure.
        val audioReact = 0.5f

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uCrossSection"), crossIdx)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uWallTexture"), wallIdx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFov"), fov)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightAngle"), lightAngle)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLightHeight"), lightHeight)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExposure"), exposure)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpeed"), speed)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpAmp"), warpAmp)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpFreq"), warpFreq)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPhase"), phaseOffset)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uF1"), f1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uF2"), f2)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAudioReact"), audioReact)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform int uCrossSection;   // 0 Circle 1 Square 2 Hexagon 3 Star 4 Gear 5 Flower
        uniform int uWallTexture;    // 0 stripes 1 grid 2 voronoi 3 noise
        uniform float uFov;
        uniform float uLightAngle;
        uniform float uLightHeight;
        uniform float uExposure;
        uniform float uSpeed;
        uniform float uWarpAmp;
        uniform float uWarpFreq;
        uniform float uPhase;
        uniform float uF1;
        uniform float uF2;
        uniform float uAudioReact;

        out vec4 fragColor;

        // -------- small hash / noise helpers --------
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
        float fbm(vec2 p) {
            float v = 0.0;
            float a = 0.5;
            for (int i = 0; i < 4; i++) {
                v += a * vnoise(p);
                p *= 2.02;
                a *= 0.5;
            }
            return v;
        }

        // Cross-section radius modulation as a function of angle.
        // Returns a multiplicative factor (close to 1.0) applied to base radius.
        float crossRadius(float ang) {
            if (uCrossSection == 1) {
                // Square: radius along the chebyshev contour
                return 1.0 / max(abs(cos(ang)), abs(sin(ang)));
            } else if (uCrossSection == 2) {
                // Hexagon-ish
                return 1.0 / max(abs(cos(ang)) * 0.866025 + abs(sin(ang)) * 0.5, abs(sin(ang)));
            } else if (uCrossSection == 3) {
                // Star
                return 1.0 / (cos(ang * 5.0) * 0.3 + 0.7);
            } else if (uCrossSection == 4) {
                // Gear: piecewise teeth
                float tooth = step(0.0, cos(ang * 8.0)) * 0.15;
                return 1.0 + tooth;
            } else if (uCrossSection == 5) {
                // Flower
                return 1.0 / (cos(ang * 6.0) * 0.25 + 0.75);
            }
            // Circle
            return 1.0;
        }

        // Wall texture: returns vec2(brightness, colourCoord) sampled at scrolled
        // (angle, depth) coordinates.
        vec2 wallTex(float ang, float depth) {
            // Scroll depth toward the viewer with time
            float z = depth;
            if (uWallTexture == 0) {
                // Stripes along depth
                float s = sin(z * 5.0) * 0.5 + 0.5;
                return vec2(0.5 + s * 0.5, fract(z * 0.3));
            } else if (uWallTexture == 2) {
                // Voronoi-ish cell pattern in (ang, z)
                vec2 p = vec2(ang * 3.0, z * 3.0);
                vec2 i = floor(p);
                vec2 f = fract(p) - 0.5;
                float d = length(f);
                // sample a couple of neighbour cells for a softer pattern
                float minD = d;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        vec2 g = vec2(float(ox), float(oy));
                        vec2 r = g - (fract(p) - 0.5) + (hash21(i + g) - 0.5);
                        minD = min(minD, length(r));
                    }
                }
                return vec2(clamp(0.4 + minD * 0.9, 0.0, 1.0), minD);
            } else if (uWallTexture == 3) {
                // FBM noise wall
                float n = fbm(vec2(ang * 2.0, z * 2.0));
                return vec2(0.4 + n * 0.6, n);
            }
            // Grid (default)
            float gx = (abs(sin(ang * 8.0)) < 0.05) ? 0.3 : 1.0;
            float gz = (abs(sin(z * 8.0)) < 0.05) ? 0.3 : 1.0;
            return vec2(min(gx, gz), fract((ang + z) * 0.2));
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;

            // FOV affects how compressed the centre is — wider FOV = more squished
            // tunnel walls, narrower = stretched. Convert degrees to a scalar.
            float fovScale = tan(radians(uFov) * 0.5);
            uv *= 1.0 / max(fovScale, 0.05);

            float t = uTime * uSpeed + uPhase;

            // Path warping: offset UVs by sin/cos of pseudo-depth at two incommensurate
            // frequencies derived from the seed. Audio bass amplifies the warp.
            float warpAmp = uWarpAmp * (1.0 + uAudio.x * uAudioReact);
            float wf = uWarpFreq;
            uv.x += sin(t * uF1 * wf) * warpAmp * 0.3;
            uv.y += cos(t * uF2 * wf) * warpAmp * 0.15;

            // Polar map: angle + depth (1/r). Outside the screen edges r->big so depth->0.
            float r = length(uv);
            // Avoid pathological r=0 at the centre vanishing point.
            r = max(r, 1e-3);

            float ang = atan(uv.y, uv.x);

            // Cross-section modulation: warp the angular radius shape
            float csR = crossRadius(ang);
            // Effective radius normalised to the cross-section
            float rN = r * csR;

            float depth = 1.0 / rN;

            // Sample wall texture at scrolled angular/depth coords
            vec2 wt = wallTex(ang, depth + t);
            float brightness = wt.x;
            float colourT = wt.y;

            // Cheap directional lighting: project the (sin(ang), cos(ang)) wall normal
            // against a light direction derived from Light Angle / Light Height.
            float la = radians(uLightAngle);
            vec3 lightDir = normalize(vec3(cos(la), sin(la), uLightHeight));
            // Effective tunnel-wall normal in 2D (points outward from screen centre)
            vec2 nrm2 = normalize(uv);
            vec3 nrm = vec3(nrm2.x, nrm2.y, 0.3);
            nrm = normalize(nrm);
            float lambert = clamp(dot(nrm, lightDir) * 0.5 + 0.5, 0.0, 1.0);
            // Specular-ish ridge
            float spec = pow(lambert, 12.0);

            // Mids brighten the wall pattern, highs sparkle
            float midBoost = 1.0 + uAudio.y * uAudioReact * 0.6;
            float sparkle = spec * (0.4 + 0.8 * uAudio.z * uAudioReact);

            // Palette-mapped wall colour banded by colourT, then modulated by
            // shading + texture brightness.
            vec3 base = palette_color(fract(colourT * 0.7 + depth * 0.05));
            vec3 col = base * brightness * (0.4 + 0.7 * lambert) * midBoost;
            col += vec3(sparkle) * 0.5;

            // Depth fog: pixels further down the tunnel (depth bigger) get more fog.
            float fogAmt = 1.0 - exp(-depth * 0.06);
            col *= (1.0 - fogAmt);

            // Add a soft palette glow at the vanishing point so the centre isn't black
            float centreGlow = exp(-rN * 18.0);
            col += palette_color(0.85) * centreGlow * 0.6;

            // Exposure
            col *= uExposure;

            // Tone-map + gamma
            col = col / (1.0 + col);
            col = pow(col, vec3(1.0 / 2.2));
            fragColor = vec4(col, 1.0);
        }
    """
}
