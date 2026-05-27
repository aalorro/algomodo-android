package com.artmondo.algomodo.generators.procedural

import android.graphics.Color
import android.opengl.GLES30
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * GPU-rendered 2D SDF generator. CPU computes per-frame primitive data
 * (positions, rotations, types, colours) deterministically from the seed and
 * uploads as uniform arrays. The fragment shader evaluates the SDF (with
 * optional domain warping/folding/repetition), applies smooth boolean ops,
 * and shades interior + exterior with glow halos, distance-band contours,
 * and edge highlights.
 *
 * Ported from the original CPU implementation; id, parameter schema and
 * defaults are preserved.
 */
class SdfRaymarchGenerator : GpuGenerator {

    override val id = "procedural-sdf-raymarch"
    override val family = "procedural"
    override val styleName = "SDF Raymarch"
    override val definition =
        "2D signed-distance-field rendering with smooth boolean operations, domain warping, glow halos, and distance-band contours."
    override val algorithmNotes =
        "CPU computes per-frame SDF primitives (circle/roundbox/ring/cross/triangle) with seeded " +
        "positions and orbit animation, uploaded as uniform arrays. Fragment shader evaluates the " +
        "SDF, applies smooth union/subtract blending, optional domain warping (simplex noise), " +
        "abs-fold for fractal mode, and modulo repetition for lattice mode. Interior gets depth-" +
        "shaded palette colour; exterior gets glow halo, distance bands, and edge highlights."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam("Scene", "sceneType", ParamGroup.COMPOSITION,
            "spheres | boxes | rings | blend: mixed | lattice: tiled | fractal: folded",
            listOf("spheres", "boxes", "rings", "blend", "lattice", "fractal"), "blend"),
        Parameter.NumberParam("Complexity", "complexity", ParamGroup.COMPOSITION,
            "Number of SDF primitives", 1f, 8f, 1f, 4f),
        Parameter.NumberParam("Glow", "glowIntensity", ParamGroup.TEXTURE,
            "Glow halo intensity around shapes", 0f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Band Width", "bandWidth", ParamGroup.TEXTURE,
            "Distance-based contour band width (0 = off)", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Smooth Blend", "smoothBlend", ParamGroup.GEOMETRY,
            "Smooth union/subtract blending factor", 0f, 1f, 0.05f, 0.3f),
        Parameter.NumberParam("Warp", "warpAmount", ParamGroup.GEOMETRY,
            "Domain warping intensity via noise", 0f, 1f, 0.05f, 0.2f),
        Parameter.NumberParam("Rotation Speed", "rotationSpeed", ParamGroup.FLOW_MOTION,
            "Primitive orbit speed", 0f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION,
            "Global animation speed", 0.1f, 2f, 0.1f, 0.5f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Audio reactivity strength", 0f, 2f, 0.1f, 1.0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sceneType" to "blend", "complexity" to 4f, "glowIntensity" to 0.5f,
        "bandWidth" to 0.3f, "smoothBlend" to 0.3f, "warpAmount" to 0.2f,
        "rotationSpeed" to 0.5f, "speed" to 0.5f, "reactivity" to 1.0f
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
        val wf = width.toFloat()
        val hf = height.toFloat()
        val minDim = min(width, height)
        val minDimF = minDim.toFloat()
        val cx = wf / 2f
        val cy = hf / 2f

        val sceneType = (params["sceneType"] as? String) ?: "blend"
        val complexity = ((params["complexity"] as? Number)?.toInt() ?: 4).coerceIn(1, 8)
        val reactivity = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * reactivity
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * reactivity

        val glowIntensity = ((params["glowIntensity"] as? Number)?.toFloat() ?: 0.5f) + audioBass * 0.5f
        val bandWidth = (params["bandWidth"] as? Number)?.toFloat() ?: 0.3f
        val smoothK = ((params["smoothBlend"] as? Number)?.toFloat() ?: 0.3f) * minDimF * 0.15f
        val warpAmount = ((params["warpAmount"] as? Number)?.toFloat() ?: 0.2f) * minDimF * 0.08f
        val rotSpeed = ((params["rotationSpeed"] as? Number)?.toFloat() ?: 0.5f) * (1f + audioMid * 1.5f)
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val t = time * spd

        val colors = palette.colorInts()
        val nC = colors.size

        val isFractal = sceneType == "fractal"
        val isLattice = sceneType == "lattice"
        val warpThreshold = minDimF * 0.08f  // matches CPU `warpAmount > 0.1f` after scaling
        val hasWarp = warpAmount > warpThreshold * 0.05f

        val rng = SeededRNG(seed)

        val maxPrims = (complexity * (if (isFractal) 2 else 1) +
            if (isFractal) min(3, complexity) else 0).coerceAtMost(MAX_PRIMS)

        val primType = IntArray(MAX_PRIMS)
        val primCxBase = FloatArray(MAX_PRIMS)
        val primCyBase = FloatArray(MAX_PRIMS)
        val primR = FloatArray(MAX_PRIMS)
        val primHW = FloatArray(MAX_PRIMS)
        val primHH = FloatArray(MAX_PRIMS)
        val primRR = FloatArray(MAX_PRIMS)
        val primThick = FloatArray(MAX_PRIMS)
        val primOrbitR = FloatArray(MAX_PRIMS)
        val primOrbitSpd = FloatArray(MAX_PRIMS)
        val primOrbitPhase = FloatArray(MAX_PRIMS)
        val primColorIdx = IntArray(MAX_PRIMS)
        val primOp = IntArray(MAX_PRIMS)
        val primRotation = FloatArray(MAX_PRIMS)

        var primCount = 0

        // Mirror CPU addPrim() — sequence of RNG draws determines positions.
        // Two passes when fractal (depth 0 and depth 1).
        fun addPrim(depth: Int) {
            val count = if (depth == 0) complexity else min(3, complexity)
            val sizeScale = if (depth == 0) 1f else 0.4f
            for (i in 0 until count) {
                if (primCount >= maxPrims) break
                val idx = primCount++

                primType[idx] = when (sceneType) {
                    "spheres" -> 0
                    "boxes" -> 1
                    "rings" -> 2
                    "blend" -> rng.integer(0, 4)
                    "lattice" -> rng.integer(0, 3)
                    "fractal" -> if (depth == 0) rng.integer(0, 2) else 0
                    else -> 0
                }

                if (isLattice) {
                    primCxBase[idx] = rng.range(0.35f, 0.65f) * wf
                    primCyBase[idx] = rng.range(0.35f, 0.65f) * hf
                    primR[idx] = rng.range(0.04f, 0.10f) * minDimF * sizeScale
                } else {
                    primCxBase[idx] = rng.range(0.15f, 0.85f) * wf
                    primCyBase[idx] = rng.range(0.15f, 0.85f) * hf
                    primR[idx] = rng.range(0.06f, 0.20f) * minDimF * sizeScale
                }

                val hw = rng.range(0.05f, 0.18f) * minDimF * sizeScale
                val hh = rng.range(0.05f, 0.18f) * minDimF * sizeScale
                val rr = rng.range(0.01f, 0.05f) * minDimF * sizeScale
                primHW[idx] = hw - rr
                primHH[idx] = hh - rr
                primRR[idx] = rr
                primThick[idx] = rng.range(0.008f, 0.03f) * minDimF * sizeScale
                primOrbitR[idx] = rng.range(0.02f, 0.12f) * minDimF * sizeScale
                primOrbitSpd[idx] = rng.range(0.5f, 2.0f) * if (rng.random() > 0.5f) 1f else -1f
                primOrbitPhase[idx] = rng.randomAngle()
                primColorIdx[idx] = rng.integer(0, nC - 1)
                primOp[idx] = if (i == 0 || rng.random() > 0.3f) 0 else 1
                primRotation[idx] = rng.range(0f, PI.toFloat())
            }
        }

        addPrim(0)
        if (isFractal) addPrim(1)

        // Compute animated positions + rotation for this frame
        val primCenter = FloatArray(MAX_PRIMS * 2)
        val primRotCS = FloatArray(MAX_PRIMS * 2)
        val primGeom = FloatArray(MAX_PRIMS * 4)
        val primColor = FloatArray(MAX_PRIMS * 3)
        val primColor2 = FloatArray(MAX_PRIMS * 3)
        for (i in 0 until MAX_PRIMS) {
            val a = t * rotSpeed * primOrbitSpd[i] + primOrbitPhase[i]
            primCenter[i * 2] = primCxBase[i] + cos(a) * primOrbitR[i]
            primCenter[i * 2 + 1] = primCyBase[i] + sin(a) * primOrbitR[i]
            val rot = primRotation[i] + t * rotSpeed * 0.5f
            primRotCS[i * 2] = cos(rot)
            primRotCS[i * 2 + 1] = sin(rot)
            primGeom[i * 4] = primR[i]
            primGeom[i * 4 + 1] = primHW[i]
            primGeom[i * 4 + 2] = primHH[i]
            primGeom[i * 4 + 3] = primRR[i]

            val c = colors[primColorIdx[i] % nC]
            primColor[i * 3] = Color.red(c) / 255f
            primColor[i * 3 + 1] = Color.green(c) / 255f
            primColor[i * 3 + 2] = Color.blue(c) / 255f

            val c2 = colors[(primColorIdx[i] + 1) % nC]
            primColor2[i * 3] = Color.red(c2) / 255f
            primColor2[i * 3 + 1] = Color.green(c2) / 255f
            primColor2[i * 3 + 2] = Color.blue(c2) / 255f
        }

        // Lattice cell size
        val cellSize = if (isLattice) minDimF / (1.5f + complexity * 0.4f) else 0f

        // Fractal fold params — use rng state right after last addPrim
        val foldAngle = if (isFractal) rng.range(PI.toFloat() * 0.2f, PI.toFloat() * 0.8f) else 0f
        val foldIterations = if (isFractal) min(complexity, 4) else 0

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPrimCount"), primCount)
        GLES30.glUniform1iv(GLES30.glGetUniformLocation(programId, "uPrimType"), MAX_PRIMS, primType, 0)
        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPrimCenter"), MAX_PRIMS, primCenter, 0)
        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPrimRotCS"), MAX_PRIMS, primRotCS, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(programId, "uPrimGeom"), MAX_PRIMS, primGeom, 0)
        GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uPrimThick"), MAX_PRIMS, primThick, 0)
        GLES30.glUniform1iv(GLES30.glGetUniformLocation(programId, "uPrimOp"), MAX_PRIMS, primOp, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uPrimColor"), MAX_PRIMS, primColor, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uPrimColor2"), MAX_PRIMS, primColor2, 0)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTime2"), t)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGlowIntensity"), glowIntensity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBandWidth"), bandWidth)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSmoothK"), smoothK)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWarpAmount"), warpAmount)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uMinDim"), minDimF)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uHasWarp"), if (hasWarp) 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIsFractal"), if (isFractal) 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIsLattice"), if (isLattice) 1 else 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uFoldCS"), cos(foldAngle), sin(foldAngle))
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFoldIters"), foldIterations)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCellSize"), cellSize)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        // Inline a small simplex noise for domain warping (avoid pulling in
        // the full NoiseGlsl helpers — we only need one snoise() call).
        vec3 _sdf_permute(vec3 x) { return mod(((x * 34.0) + 1.0) * x, 289.0); }
        float snoise2(vec2 v) {
            const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                                -0.577350269189626, 0.024390243902439);
            vec2 i  = floor(v + dot(v, C.yy));
            vec2 x0 = v - i + dot(i, C.xx);
            vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
            vec4 x12 = x0.xyxy + C.xxzz;
            x12.xy -= i1;
            i = mod(i, 289.0);
            vec3 p = _sdf_permute(_sdf_permute(i.y + vec3(0.0, i1.y, 1.0))
                                + i.x + vec3(0.0, i1.x, 1.0));
            vec3 m = max(0.5 - vec3(dot(x0, x0),
                                    dot(x12.xy, x12.xy),
                                    dot(x12.zw, x12.zw)), 0.0);
            m = m * m; m = m * m;
            vec3 x = 2.0 * fract(p * C.www) - 1.0;
            vec3 h = abs(x) - 0.5;
            vec3 ox = floor(x + 0.5);
            vec3 a0 = x - ox;
            m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
            vec3 g;
            g.x  = a0.x  * x0.x  + h.x  * x0.y;
            g.yz = a0.yz * x12.xz + h.yz * x12.yw;
            return 130.0 * dot(m, g);
        }

        const int MAX_PRIMS = $MAX_PRIMS;

        uniform int   uPrimCount;
        uniform int   uPrimType[MAX_PRIMS];
        uniform vec2  uPrimCenter[MAX_PRIMS];
        uniform vec2  uPrimRotCS[MAX_PRIMS];        // (cos, sin)
        uniform vec4  uPrimGeom[MAX_PRIMS];          // (r, hw, hh, rr)
        uniform float uPrimThick[MAX_PRIMS];
        uniform int   uPrimOp[MAX_PRIMS];            // 0=union, 1=subtract
        uniform vec3  uPrimColor[MAX_PRIMS];
        uniform vec3  uPrimColor2[MAX_PRIMS];

        uniform float uTime2;
        uniform float uGlowIntensity;
        uniform float uBandWidth;
        uniform float uSmoothK;
        uniform float uWarpAmount;
        uniform float uMinDim;
        uniform int   uHasWarp;
        uniform int   uIsFractal;
        uniform int   uIsLattice;
        uniform vec2  uFoldCS;
        uniform int   uFoldIters;
        uniform float uCellSize;

        out vec4 fragColor;

        float sdfPrim(int type, vec2 d, float r, float hw, float hh, float rr, float thick) {
            if (type == 0) {
                return length(d) - r;
            } else if (type == 1) {
                // Rounded box: hw/hh are pre-subtracted (hw - rr stored)
                vec2 a = abs(d) - vec2(hw, hh);
                vec2 a0 = max(a, 0.0);
                return length(a0) + min(max(a.x, a.y), 0.0) - rr;
            } else if (type == 2) {
                // Ring
                return abs(length(d) - r) - thick;
            } else if (type == 3) {
                // Cross
                float armW = thick * 3.0;
                float d1 = max(abs(d.x) - r * 0.8, abs(d.y) - armW);
                float d2 = max(abs(d.x) - armW, abs(d.y) - r * 0.8);
                return min(d1, d2);
            } else {
                // Equilateral triangle (signed)
                float k = sqrt(3.0);
                float tx = abs(d.x) - r;
                float ty = d.y + r / k;
                if (tx + k * ty > 0.0) {
                    float ntx = (tx - k * ty) / 2.0;
                    float nty = (-k * tx - ty) / 2.0;
                    tx = ntx; ty = nty;
                }
                tx = clamp(tx, -2.0 * r, 0.0);
                return sqrt(tx * tx + ty * ty) * ((ty > 0.0) ? 1.0 : -1.0);
            }
        }

        void main() {
            float w = uResolution.x;
            float h = uResolution.y;
            float cx = w * 0.5;
            float cy = h * 0.5;

            vec2 frag = gl_FragCoord.xy;
            float qx = frag.x;
            float qy = frag.y;

            // Domain warping
            if (uHasWarp == 1) {
                float warpScale = 2.5 / uMinDim;
                float nx = qx * warpScale + uTime2 * 0.05;
                float ny = qy * warpScale;
                qx += snoise2(vec2(nx, ny)) * uWarpAmount;
                qy += snoise2(vec2(ny + 31.7, nx + 17.3)) * uWarpAmount;
            }

            // Fractal domain folding
            if (uIsFractal == 1) {
                float fx = qx - cx;
                float fy = qy - cy;
                float offset = uMinDim * 0.08;
                for (int fi = 0; fi < 4; fi++) {
                    if (fi >= uFoldIters) break;
                    fx = abs(fx);
                    fy = abs(fy);
                    float rx2 = fx * uFoldCS.x - fy * uFoldCS.y;
                    float ry2 = fx * uFoldCS.y + fy * uFoldCS.x;
                    fx = rx2 - offset;
                    fy = ry2 - offset;
                }
                qx = fx + cx;
                qy = fy + cy;
            }

            // Lattice domain repetition
            if (uIsLattice == 1 && uCellSize > 1.0) {
                float halfCell = uCellSize * 0.5;
                qx = mod(mod(qx, uCellSize) + uCellSize, uCellSize) - halfCell + cx;
                qy = mod(mod(qy, uCellSize) + uCellSize, uCellSize) - halfCell + cy;
            }

            float d = 1e20;
            int closest = 0;
            bool useSmooth = uSmoothK >= 0.001;

            for (int i = 0; i < MAX_PRIMS; i++) {
                if (i >= uPrimCount) break;

                vec2 dv = vec2(qx, qy) - uPrimCenter[i];
                int type = uPrimType[i];
                // Per-primitive rotation for non-circle types
                if (type != 0) {
                    vec2 cs = uPrimRotCS[i];
                    vec2 r2 = vec2(dv.x * cs.x + dv.y * cs.y, -dv.x * cs.y + dv.y * cs.x);
                    dv = r2;
                }

                vec4 g = uPrimGeom[i];
                float di = sdfPrim(type, dv, g.x, g.y, g.z, g.w, uPrimThick[i]);

                if (i == 0) {
                    d = di; closest = 0;
                } else if (uPrimOp[i] == 1) {
                    // Smooth subtract
                    if (useSmooth) {
                        float h2 = clamp(0.5 - 0.5 * (d + di) / uSmoothK, 0.0, 1.0);
                        float nd = d * (1.0 - h2) + (-di) * h2 + uSmoothK * h2 * (1.0 - h2);
                        if (nd != d) closest = i;
                        d = nd;
                    } else {
                        float nd = max(d, -di);
                        if (nd != d) closest = i;
                        d = nd;
                    }
                } else {
                    // Smooth union
                    if (useSmooth) {
                        if (di < d) closest = i;
                        float h2 = clamp(0.5 + 0.5 * (di - d) / uSmoothK, 0.0, 1.0);
                        d = d * h2 + di * (1.0 - h2) - uSmoothK * h2 * (1.0 - h2);
                    } else {
                        if (di < d) { d = di; closest = i; }
                    }
                }
            }

            // Resolve colours of the closest primitive via linear scan
            vec3 c1 = vec3(0.0);
            vec3 c2 = vec3(0.0);
            for (int i = 0; i < MAX_PRIMS; i++) {
                if (i == closest) {
                    c1 = uPrimColor[i];
                    c2 = uPrimColor2[i];
                    break;
                }
            }
            // Scale colour back to 0..255 range matching CPU math
            vec3 cR255 = c1 * 255.0;
            vec3 cR255b = c2 * 255.0;

            float shadeDiv = 1.0 / (uMinDim * 0.05);
            float glowDiv = -1.0 / (uMinDim * 0.06);
            float bandScale = (uBandWidth > 0.01) ? (3.14159265 / (uBandWidth * uMinDim * 0.05)) : 0.0;
            bool hasGlow = uGlowIntensity > 0.0;
            bool hasBand = bandScale > 0.0;
            float edgeWidth = uMinDim * 0.003;
            float edgeDiv = 1.0 / edgeWidth;

            // Background gradient: palette[0] dimmed
            vec3 bg0 = uPalette[0] * (0.04 * 255.0);

            float r; float g; float b;
            if (d < 0.0) {
                float depth = min(1.0, -d * shadeDiv);
                float shade = 0.65 + 0.35 * depth;
                float blend = depth * 0.3;
                r = (cR255.r * (1.0 - blend) + cR255b.r * blend) * shade;
                g = (cR255.g * (1.0 - blend) + cR255b.g * blend) * shade;
                b = (cR255.b * (1.0 - blend) + cR255b.b * blend) * shade;
            } else {
                float distFromCenter = length(vec2(frag.x - cx, frag.y - cy));
                float bgFade = min(1.0, distFromCenter / (uMinDim * 0.7));
                r = bg0.r * (1.0 - bgFade * 0.5);
                g = bg0.g * (1.0 - bgFade * 0.5);
                b = bg0.b * (1.0 - bgFade * 0.5);
                if (hasGlow) {
                    float glow = exp(d * glowDiv) * uGlowIntensity;
                    r += cR255.r * glow;
                    g += cR255.g * glow;
                    b += cR255.b * glow;
                }
                if (hasBand) {
                    float band = (sin(d * bandScale) * 0.5 + 0.5) * 0.3;
                    r += cR255.r * band;
                    g += cR255.g * band;
                    b += cR255.b * band;
                }
            }

            // Edge highlight at d ≈ 0
            float edgeFactor = 1.0 - min(1.0, abs(d) * edgeDiv);
            if (edgeFactor > 0.0) {
                float edgeBright = edgeFactor * edgeFactor * 0.8;
                r += (255.0 - r) * edgeBright;
                g += (255.0 - g) * edgeBright;
                b += (255.0 - b) * edgeBright;
            }

            vec3 col = clamp(vec3(r, g, b) / 255.0, 0.0, 1.0);
            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val complexity = (params["complexity"] as? Number)?.toInt() ?: 4
        val scene = (params["sceneType"] as? String) ?: "blend"
        val warp = (params["warpAmount"] as? Number)?.toFloat() ?: 0.2f
        val base = complexity * 0.05f + if (scene == "fractal") 0.15f else 0f
        return (base + 0.1f + if (warp > 0.1f) 0.05f else 0f).coerceIn(0.15f, 0.5f)
    }

    companion object {
        private const val MAX_PRIMS = 20
    }
}
