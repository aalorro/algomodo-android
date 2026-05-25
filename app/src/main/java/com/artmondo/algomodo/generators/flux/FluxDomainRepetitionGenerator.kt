package com.artmondo.algomodo.generators.flux

import android.opengl.GLES30
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPU-rendered domain repetition. Tiled SDF shapes (circle / box / star /
 * hexagon) with six motion modes (static, pulse, spin, wave, drift, explode),
 * crisp neon edges and exponential glow falloff.
 *
 * Each pixel iterates a small neighbourhood of cells (±1 normally, ±2 for
 * drift / explode where shapes can leave their own cell) and accumulates the
 * minimum SDF distance + associated cell colour, then evaluates a CPU-
 * compatible interior / edge / glow shading function.
 */
class FluxDomainRepetitionGenerator : GpuGenerator {

    override val id = "flux-domain-repetition"
    override val family = "flux"
    override val styleName = "Domain Repetition"
    override val definition =
        "SDF shapes tiled via modulo coordinates with per-cell variation -- neon edges, six motion modes (static, pulse, spin, wave, drift, explode), and crisp rendering"
    override val algorithmNotes =
        "GPU fragment shader. Per-pixel: locate the host cell, iterate ±1 or ±2 neighbour " +
        "cells (depending on motion mode), compute per-cell hash → radius / rotation / " +
        "color / motion offsets, evaluate the chosen SDF, keep the minimum distance + " +
        "owning cell colour, then shade with interior / neon-edge / exp-glow falloff."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Shape",
            key = "shape",
            group = ParamGroup.COMPOSITION,
            help = "SDF primitive repeated across the grid",
            options = listOf("circle", "box", "star", "hexagon"),
            default = "circle"
        ),
        Parameter.SelectParam(
            name = "Motion",
            key = "motionMode",
            group = ParamGroup.FLOW_MOTION,
            help = "static: no animation | pulse: breathing shapes | spin: per-cell rotation | wave: ripple across grid | drift: shapes wander | explode: burst from center",
            options = listOf("static", "pulse", "spin", "wave", "drift", "explode"),
            default = "wave"
        ),
        Parameter.NumberParam(
            name = "Cell Size",
            key = "cellSize",
            group = ParamGroup.GEOMETRY,
            help = "Size of each repeating cell in pixels",
            min = 30f, max = 200f, step = 5f, default = 80f
        ),
        Parameter.NumberParam(
            name = "Size Variation",
            key = "sizeVariation",
            group = ParamGroup.GEOMETRY,
            help = "Per-cell random variation in shape size",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Rotation Variation",
            key = "rotationVariation",
            group = ParamGroup.GEOMETRY,
            help = "Per-cell random variation in rotation angle",
            min = 0f, max = 1f, step = 0.05f, default = 0.4f
        ),
        Parameter.NumberParam(
            name = "Fill Ratio",
            key = "fillRatio",
            group = ParamGroup.GEOMETRY,
            help = "Shape radius relative to cell size",
            min = 0.2f, max = 0.9f, step = 0.05f, default = 0.6f
        ),
        Parameter.NumberParam(
            name = "Edge Glow",
            key = "edgeGlow",
            group = ParamGroup.TEXTURE,
            help = "Neon glow intensity around shape edges",
            min = 0f, max = 1f, step = 0.05f, default = 0.4f
        ),
        Parameter.NumberParam(
            name = "Edge Sharpness",
            key = "edgeSharpness",
            group = ParamGroup.TEXTURE,
            help = "Crispness of edge lines -- higher = thinner, sharper neon",
            min = 0.2f, max = 2f, step = 0.1f, default = 1.0f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation speed",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "shape" to "circle",
        "motionMode" to "wave",
        "cellSize" to 80f,
        "sizeVariation" to 0.3f,
        "rotationVariation" to 0.4f,
        "fillRatio" to 0.6f,
        "edgeGlow" to 0.4f,
        "edgeSharpness" to 1.0f,
        "speed" to 0.5f,
        "reactivity" to 1.0f
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
        val shapeStr = (params["shape"] as? String) ?: "circle"
        val motionStr = (params["motionMode"] as? String) ?: "wave"
        val baseCellSize = ((params["cellSize"] as? Number)?.toFloat() ?: 80f).coerceIn(30f, 200f)
        val sizeVar = ((params["sizeVariation"] as? Number)?.toFloat() ?: 0.3f).coerceIn(0f, 1f)
        val rotVar = ((params["rotationVariation"] as? Number)?.toFloat() ?: 0.4f).coerceIn(0f, 1f)
        val fillRatio = ((params["fillRatio"] as? Number)?.toFloat() ?: 0.6f).coerceIn(0.2f, 0.9f)
        val edgeGlowAmt = ((params["edgeGlow"] as? Number)?.toFloat() ?: 0.4f).coerceIn(0f, 1f)
        val edgeSharp = ((params["edgeSharpness"] as? Number)?.toFloat() ?: 1.0f).coerceIn(0.2f, 2f)
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx

        val shapeId = when (shapeStr) {
            "circle" -> 0
            "box" -> 1
            "star" -> 2
            else -> 3
        }

        val motionId = when (motionStr) {
            "static" -> 0
            "pulse" -> 1
            "spin" -> 2
            "wave" -> 3
            "drift" -> 4
            else -> 5
        }

        val t = time * spd

        // Pulse modulates the global cell size
        val cellSize = if (motionId == 1) {
            baseCellSize * (1f + sin(t * 1.4f) * 0.06f)
        } else {
            baseCellSize
        }

        // Visible cell range and explode/wave constants (mirror CPU)
        val extend = if (motionId == 4 || motionId == 5) 2 else 1
        val cellX0 = -extend
        val cellY0 = -extend
        val cellX1 = floor((width - 1).toFloat() / cellSize).toInt() + extend
        val cellY1 = floor((height - 1).toFloat() / cellSize).toInt() + extend
        val waveDiag = sqrt((cellX1.toFloat() * cellX1 + cellY1.toFloat() * cellY1)) + 1f
        val explodeCx = (cellX0 + cellX1) * 0.5f
        val explodeCy = (cellY0 + cellY1) * 0.5f
        val explodeMaxR = sqrt(
            (cellX1 - explodeCx) * (cellX1 - explodeCx) +
            (cellY1 - explodeCy) * (cellY1 - explodeCy)
        ) + 1f

        val rng = SeededRNG(seed)
        val seedBits = (rng.random() * 0x7fffffff).toInt()

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uShapeId"), shapeId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMotionId"), motionId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCellSize"), cellSize)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSizeVar"), sizeVar)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uRotVar"), rotVar)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFillRatio"), fillRatio)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEdgeGlow"), edgeGlowAmt)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEdgeSharp"), edgeSharp)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uT"), t)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uExtend"), extend)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSeed"), seedBits)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(programId, "uAudioBmh"), audioBass, audioMid, audioHigh)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uWaveInvDiag"), 1f / waveDiag)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uExplodeC"), explodeCx, explodeCy)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uExplodeMaxR"), explodeMaxR)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;
        precision highp int;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        uniform int   uShapeId;     // 0 circle, 1 box, 2 star, 3 hex
        uniform int   uMotionId;    // 0 static, 1 pulse, 2 spin, 3 wave, 4 drift, 5 explode
        uniform float uCellSize;
        uniform float uSizeVar;
        uniform float uRotVar;
        uniform float uFillRatio;
        uniform float uEdgeGlow;
        uniform float uEdgeSharp;
        uniform float uT;           // time * speed
        uniform int   uExtend;      // 1 or 2
        uniform int   uSeed;        // seedBits
        uniform vec3  uAudioBmh;    // bass, mid, high (already pre-multiplied by reactivity)
        uniform float uWaveInvDiag;
        uniform vec2  uExplodeC;
        uniform float uExplodeMaxR;

        out vec4 fragColor;

        const float PI_F = 3.14159265358979;
        const float TAU = 6.28318530717958;
        const float HEX_K1 = 0.8660254037844387;
        const float HEX_K2 = 0.5;
        const float STAR_INNER = 0.38;

        // ── Per-cell integer hash (matches CPU pattern reasonably; doesn't have
        //    to be bit-identical, just deterministic per (cx, cy, seed)).
        uint hashCell(int cx, int cy, int constantA, int constantB) {
            uint a = uint(cx) * uint(constantA);
            uint b = uint(cy) * uint(constantB);
            uint h = (a ^ b ^ uint(uSeed)) & 0x7fffffffu;
            return h;
        }
        float hashF(int cx, int cy, int constantA, int constantB) {
            return float(hashCell(cx, cy, constantA, constantB)) / float(0x7fffffff);
        }

        // ── SDFs ────────────────────────────────────────────────────────────
        float sdCircle(vec2 p, float r) {
            return length(p) - r;
        }
        float sdBox(vec2 p, float hw) {
            vec2 d = abs(p) - vec2(hw);
            return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
        }
        float sdStar(vec2 p, float r) {
            float angle = atan(p.y, p.x);
            float dist = length(p);
            float segAngle = PI_F / 5.0;
            float dblSeg = segAngle * 2.0;
            float innerR = r * STAR_INNER;
            float a = mod(angle, dblSeg);
            float tt = abs(a - segAngle) / segAngle;
            return dist - (innerR + (r - innerR) * (1.0 - tt));
        }
        float sdHex(vec2 p, float r) {
            vec2 q = abs(p);
            float dot2 = 2.0 * min(HEX_K1 * q.x + HEX_K2 * q.y, 0.0);
            q -= dot2 * vec2(HEX_K1, HEX_K2);
            q.x -= clamp(q.x, -r, r);
            q.y -= r;
            return length(q) * sign(q.y);
        }
        float evalSdf(vec2 p, float r) {
            if (uShapeId == 0) return sdCircle(p, r);
            else if (uShapeId == 1) return sdBox(p, r * 0.85);
            else if (uShapeId == 2) return sdStar(p, r);
            else                    return sdHex(p, r);
        }

        // ── Per-cell motion modulation. Writes radius, rotation, offset; returns
        //    the cell's chosen colour parameter (0..1) for palette lookup.
        void evalCell(int cx, int cy,
                      out float radius,
                      out float rot,
                      out vec2 offset,
                      out float colorParam)
        {
            float hashF1 = hashF(cx, cy, 73856093, 19349663);
            float hashF2 = hashF(cx, cy, 83492791, 56198423);
            float hashF3 = hashF(cx, cy, 12345689, 98765431);
            float cellPhase = hashF3 * TAU;

            float halfCell = uCellSize * 0.5;
            float effFill = clamp(uFillRatio + uAudioBmh.x * 0.2, 0.15, 0.95);
            float audioRotOffset = uAudioBmh.y * 0.5;

            float motionRadius = effFill;
            float motionRotation = hashF2 * uRotVar * PI_F + audioRotOffset;
            vec2 motionOff = vec2(0.0);

            if (uMotionId == 1) {
                float breathe = sin(uT * 1.4 + cellPhase) * 0.15;
                motionRadius = clamp(effFill + breathe * effFill, 0.15, 0.95);
            } else if (uMotionId == 2) {
                float spinDir = (hashF1 > 0.5) ? 1.0 : -1.0;
                float spinSpeed = 0.5 + hashF3 * 1.5;
                motionRotation += uT * spinSpeed * spinDir;
            } else if (uMotionId == 3) {
                float cellDist = (float(cx) + float(cy)) * uWaveInvDiag;
                float wave = sin(uT * 2.5 - cellDist * TAU * 2.0 + cellPhase * 0.3);
                motionRadius = clamp(effFill + wave * 0.12, 0.15, 0.95);
                motionRotation += wave * 0.4;
            } else if (uMotionId == 4) {
                float driftAmp = halfCell * 0.4;
                motionOff.x = sin(uT * 0.7 + cellPhase) * driftAmp
                            + sin(uT * 1.3 + hashF1 * TAU) * driftAmp * 0.4;
                motionOff.y = cos(uT * 0.9 + cellPhase + 1.2) * driftAmp
                            + cos(uT * 1.1 + hashF2 * TAU) * driftAmp * 0.4;
                motionRotation += uT * 0.5;
            } else if (uMotionId == 5) {
                vec2 ec = vec2(float(cx), float(cy)) - uExplodeC;
                float edist = length(ec);
                float enorm = edist / uExplodeMaxR;
                float pushPhase = sin(uT * 1.2) * 0.5 + 0.5;
                float pushAmp = pushPhase * halfCell * 1.2 * enorm;
                if (edist > 0.001) {
                    motionOff = (ec / edist) * pushAmp;
                }
                motionRadius = clamp(effFill * (1.0 - pushPhase * 0.25 * enorm) + (1.0 - enorm) * 0.05,
                                     0.15, 0.95);
                motionRotation += uT * (0.3 + enorm * 0.8);
            }

            // Audio high-band jitter for wave / drift
            if (uAudioBmh.z > 0.01 && (uMotionId == 3 || uMotionId == 4)) {
                motionOff.x += sin(cellPhase + uT * 8.0) * uAudioBmh.z * halfCell * 0.15;
                motionOff.y += cos(cellPhase + uT * 8.0) * uAudioBmh.z * halfCell * 0.15;
            }

            radius = halfCell * motionRadius * (1.0 + (hashF1 - 0.5) * uSizeVar);
            rot = motionRotation;
            offset = motionOff;

            // Colour parameter — pick 1/5 palette stops via hash, with a colour
            // shift over time for wave/explode (matches CPU `(hash1 + colorShift*nc) % nc`)
            float baseIdx = floor(hashF1 * 5.0); // 0..4
            if (uMotionId == 3 || uMotionId == 5) {
                float shift = sin(uT * 0.8 + cellPhase) * 0.5 + 0.5; // 0..1
                baseIdx = mod(baseIdx + floor(shift * 5.0), 5.0);
            }
            colorParam = baseIdx / 4.0;
        }

        void main() {
            vec2 frag = gl_FragCoord.xy;
            float invCell = 1.0 / uCellSize;

            int baseCx = int(floor(frag.x * invCell));
            int baseCy = int(floor(frag.y * invCell));

            float edgeBandWidth = max(2.5 / uEdgeSharp, 0.5);
            float glowFalloff = (uEdgeGlow > 0.01) ? (0.08 + uEdgeGlow * 0.22) : 0.0;
            bool hasGlow = (glowFalloff > 0.0);

            float minD = 1e9;
            vec3  bestColor = vec3(0.0);

            int E = uExtend;
            for (int dy = -2; dy <= 2; dy++) {
                if (dy < -E || dy > E) continue;
                for (int dx = -2; dx <= 2; dx++) {
                    if (dx < -E || dx > E) continue;

                    int cx = baseCx + dx;
                    int cy = baseCy + dy;

                    float radius, rot, colorParam;
                    vec2 offset;
                    evalCell(cx, cy, radius, rot, offset, colorParam);

                    float halfCell = uCellSize * 0.5;
                    vec2 centre = vec2(float(cx) * uCellSize + halfCell,
                                       float(cy) * uCellSize + halfCell) + offset;

                    vec2 local = frag - centre;
                    float cs = cos(rot);
                    float sn = sin(rot);
                    vec2 rotated = vec2(cs * local.x - sn * local.y,
                                        sn * local.x + cs * local.y);

                    float d = evalSdf(rotated, radius);
                    if (d < minD) {
                        minD = d;
                        bestColor = palette_color(colorParam);
                    }
                }
            }

            vec3 col = vec3(0.0);
            float d = minD;
            vec3 neonColor = mix(bestColor, vec3(1.0), 0.6);

            if (d < -edgeBandWidth) {
                col = bestColor;
            } else if (d < 0.0) {
                float edgeT = 1.0 - (-d / edgeBandWidth);
                float intensity = edgeT * edgeT * edgeT;
                col = mix(bestColor, neonColor, intensity);
            } else if (d < edgeBandWidth) {
                float edgeT = 1.0 - d / edgeBandWidth;
                float intensity = edgeT * edgeT;
                col = neonColor * intensity;
            } else if (hasGlow) {
                float falloffDist = d - edgeBandWidth;
                float glow = exp(-falloffDist * glowFalloff);
                if (glow >= 0.008) {
                    col = bestColor * (glow * 0.7);
                }
            }

            fragColor = vec4(col, 1.0);
        }
    """

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val shape = (params["shape"] as? String) ?: "circle"
        val motion = (params["motionMode"] as? String) ?: "wave"
        val shapeMul = if (shape == "star" || shape == "hexagon") 1.3f else 1f
        val motionMul = if (motion == "drift" || motion == "explode") 2.0f else 1f
        return (0.2f * shapeMul * motionMul).coerceIn(0.1f, 0.8f)
    }
}
