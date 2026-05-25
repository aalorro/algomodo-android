package com.artmondo.algomodo.generators.fractals

import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPU port. Interior-coloured fractals — 5 variants × 6 interior modes ×
 * 5 trap shapes × 3 exterior modes. All branching in the fragment shader is
 * via uniforms (predictable execution per draw call).
 *
 * Behavioural changes from CPU:
 * - Ring buffer reduced from 64 → 32 entries to keep register pressure low.
 * - Brent periodicity check dropped (uniform branch cost > saved iterations).
 * - Trap rotation moves the trap centre instead of rotating each orbit step
 *   (matches the CPU's `baseTrapCX*cos - baseTrapCY*sin` formulation).
 */
class FractalInteriorGenerator : GpuGenerator {

    override val id = "fractal-interior"
    override val family = "fractals"
    override val styleName = "Fractal Interior"
    override val definition =
        "Interior coloring of fractal sets — reveals hidden structure inside the Mandelbrot set, Julia sets, and other fractals using orbit traps, period detection, and multiplier analysis."
    override val algorithmNotes =
        "GPU fragment shader. Five variants (Mandelbrot, Julia, Newton, Tricorn, Burning Ship) " +
        "with six interior colouring modes (orbit trap, period detection, multiplier magnitude/argument, " +
        "interior distance estimate, final value). Ring-buffer of 32 orbit positions retained for " +
        "period detection. Five trap shapes, three exterior modes."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam("Variant", "variant", ParamGroup.COMPOSITION,
            "mandelbrot: z²+c | julia: fixed c | newton: z³-1 root-finding | tricorn: conjugate z | burning-ship: |re|+i|im|",
            listOf("mandelbrot", "julia", "newton", "tricorn", "burning-ship"), "mandelbrot"),
        Parameter.SelectParam("Interior Mode", "interiorMode", ParamGroup.COLOR,
            "trap: orbit trap distance | period: cycle detection | multiplier-mag: derivative magnitude | multiplier-arg: derivative angle | interior-de: distance estimate | final-value: last orbit position",
            listOf("trap", "period", "multiplier-mag", "multiplier-arg", "interior-de", "final-value"), "trap"),
        Parameter.SelectParam("Trap Type", "trapType", ParamGroup.GEOMETRY,
            "point: distance to a point | line: horizontal line | cross: axis cross | circle: ring | polygon: hexagon",
            listOf("point", "line", "cross", "circle", "polygon"), "point"),
        Parameter.SelectParam("Exterior Mode", "exteriorMode", ParamGroup.COLOR,
            "smooth: gradient | banded: discrete steps | subtle: dim exterior",
            listOf("smooth", "banded", "subtle"), "smooth"),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, null, 50f, 800f, 10f, 150f),
        Parameter.NumberParam("Center X", "centerX", ParamGroup.COMPOSITION, null, -2.5f, 1.5f, 0.05f, 0f),
        Parameter.NumberParam("Center Y", "centerY", ParamGroup.COMPOSITION, null, -1.5f, 1.5f, 0.05f, 0f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, null, 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Trap Center X", "trapCX", ParamGroup.GEOMETRY, null, -2f, 2f, 0.05f, 0.25f),
        Parameter.NumberParam("Trap Center Y", "trapCY", ParamGroup.GEOMETRY, null, -2f, 2f, 0.05f, 0.5f),
        Parameter.NumberParam("Julia CX", "juliaCX", ParamGroup.GEOMETRY, "Real part of Julia constant", -2f, 2f, 0.01f, -0.7f),
        Parameter.NumberParam("Julia CY", "juliaCY", ParamGroup.GEOMETRY, "Imaginary part of Julia constant", -2f, 2f, 0.01f, 0.27f),
        Parameter.NumberParam("Trap Radius", "trapRadius", ParamGroup.GEOMETRY, null, 0.01f, 2f, 0.01f, 0.5f),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Multiplier for trap rotation, Julia drift, and color cycling", 0.1f, 5f, 0.1f, 1f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "variant" to "mandelbrot",
        "interiorMode" to "trap",
        "trapType" to "point",
        "exteriorMode" to "smooth",
        "maxIterations" to 150f,
        "centerX" to 0f,
        "centerY" to 0f,
        "zoom" to 1f,
        "trapCX" to 0.25f,
        "trapCY" to 0.5f,
        "juliaCX" to -0.7f,
        "juliaCY" to 0.27f,
        "trapRadius" to 0.5f,
        "animSpeed" to 1f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val variantStr = (params["variant"] as? String) ?: "mandelbrot"
        val intModeStr = (params["interiorMode"] as? String) ?: "trap"
        val trapTypeStr = (params["trapType"] as? String) ?: "point"
        val extModeStr = (params["exteriorMode"] as? String) ?: "smooth"
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 150
        val centerX = (params["centerX"] as? Number)?.toFloat() ?: 0f
        val centerY = (params["centerY"] as? Number)?.toFloat() ?: 0f
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val baseTrapCX = (params["trapCX"] as? Number)?.toFloat() ?: 0.25f
        val baseTrapCY = (params["trapCY"] as? Number)?.toFloat() ?: 0.5f
        val baseJuliaCX = (params["juliaCX"] as? Number)?.toFloat() ?: -0.7f
        val baseJuliaCY = (params["juliaCY"] as? Number)?.toFloat() ?: 0.27f
        val trapRadius = (params["trapRadius"] as? Number)?.toFloat() ?: 0.5f
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 1f

        val variant = when (variantStr) {
            "julia" -> 1; "newton" -> 2; "tricorn" -> 3; "burning-ship" -> 4
            else -> 0
        }
        val intMode = when (intModeStr) {
            "period" -> 1; "multiplier-mag" -> 2; "multiplier-arg" -> 3
            "interior-de" -> 4; "final-value" -> 5; else -> 0
        }
        val trapType = when (trapTypeStr) {
            "line" -> 1; "cross" -> 2; "circle" -> 3; "polygon" -> 4; else -> 0
        }
        val extMode = when (extModeStr) {
            "banded" -> 1; "subtle" -> 2; else -> 0
        }

        val isAnim = time > 0f
        val scaledMaxIter = when {
            isAnim -> (maxIter / 4).coerceIn(15, 50)
            quality == Quality.DRAFT -> (maxIter / 4).coerceIn(15, 50)
            quality == Quality.ULTRA -> (maxIter * 1.5f).toInt()
            else -> maxIter
        }

        val trapCX: Float; val trapCY: Float
        val juliaCX: Float; val juliaCY: Float
        val colorCycleOffset: Int
        if (isAnim) {
            val trapAngle = (time * 0.3 * animSpeed).toDouble()
            val cosT = cos(trapAngle).toFloat()
            val sinT = sin(trapAngle).toFloat()
            trapCX = baseTrapCX * cosT - baseTrapCY * sinT
            trapCY = baseTrapCX * sinT + baseTrapCY * cosT
            juliaCX = baseJuliaCX + 0.1f * cos(time * 0.2 * animSpeed).toFloat()
            juliaCY = baseJuliaCY + 0.1f * sin(time * 0.2 * animSpeed).toFloat()
            colorCycleOffset = (time * 30 * animSpeed).toInt() and 0xFF
        } else {
            trapCX = baseTrapCX; trapCY = baseTrapCY
            juliaCX = baseJuliaCX; juliaCY = baseJuliaCY
            colorCycleOffset = 0
        }

        val aspect = width.toFloat() / height.toFloat()
        val rangeY = 2.6f / zoom
        val rangeX = rangeY * aspect
        val trapScale = 3f / trapRadius.coerceAtLeast(0.01f)

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uVariant"), variant)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uIntMode"), intMode)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTrap"), trapType)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uExtMode"), extMode)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxIter"), scaledMaxIter)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uCenter"), centerX, centerY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRange"), rangeX, rangeY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uTrapC"), trapCX, trapCY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uJuliaC"), juliaCX, juliaCY)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTrapRadius"), trapRadius)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTrapScale"), trapScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColorCycle"), colorCycleOffset / 256f)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        #define RING_SIZE 32
        #define RING_MASK 31

        uniform int uVariant;       // 0 mandel, 1 julia, 2 newton, 3 tricorn, 4 burning
        uniform int uIntMode;       // 0 trap, 1 period, 2 mult-mag, 3 mult-arg, 4 int-de, 5 final
        uniform int uTrap;          // 0 point, 1 line, 2 cross, 3 circle, 4 polygon
        uniform int uExtMode;       // 0 smooth, 1 banded, 2 subtle
        uniform int uMaxIter;
        uniform vec2 uCenter;
        uniform vec2 uRange;
        uniform vec2 uTrapC;
        uniform vec2 uJuliaC;
        uniform float uTrapRadius;
        uniform float uTrapScale;
        uniform float uColorCycle;

        const float ESCAPE_R2 = 65536.0;
        const float LN2 = 0.6931472;
        const float LN_ESC_R = 5.5451774;
        const float PI_F = 3.1415927;
        const float TWO_PI = 6.2831853;
        const int TRAP_SKIP = 3;

        out vec4 fragColor;

        float trapDistance(vec2 z) {
            vec2 d = z - uTrapC;
            if (uTrap == 0) return dot(d, d);          // squared
            if (uTrap == 1) return abs(d.y);
            if (uTrap == 2) return min(abs(d.x), abs(d.y));
            if (uTrap == 3) return abs(length(d) - uTrapRadius);
            // polygon (hexagon)
            float c60 = cos(PI_F / 3.0);
            float s60 = sin(PI_F / 3.0);
            float c120 = cos(2.0 * PI_F / 3.0);
            float s120 = sin(2.0 * PI_F / 3.0);
            float d0 = abs(d.x);
            float d1 = abs(d.x * c60 + d.y * s60);
            float d2 = abs(d.x * c120 + d.y * s120);
            return max(0.0, max(d0, max(d1, d2)) - uTrapRadius);
        }

        vec3 cyclePalette(float t) {
            return palette_color(fract(t + uColorCycle));
        }

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            vec2 pix = uCenter + (uv - 0.5) * uRange;

            vec2 z, c;
            if (uVariant == 1) { z = pix; c = uJuliaC; }
            else if (uVariant == 2) { z = pix; c = vec2(0.0); }
            else { z = vec2(0.0); c = pix; }

            int iter = 0;
            bool escaped = false;
            bool newtonConverged = false;
            float minTD = 1e30;

            // Ring buffer for orbit positions (period detection)
            vec2 ring[RING_SIZE];
            int ringHead = 0;
            bool needRing = (uIntMode >= 1 && uIntMode <= 4) && uVariant != 2;
            bool needTrap = uIntMode == 0;

            // Mandelbrot cardioid/bulb shortcut
            if (uVariant == 0) {
                float crm = c.x - 0.25;
                float ci2 = c.y * c.y;
                float q = crm * crm + ci2;
                if (q * (q + crm) <= 0.25 * ci2 ||
                    (c.x + 1.0) * (c.x + 1.0) + ci2 <= 0.0625) {
                    iter = uMaxIter;
                }
            }

            if (uVariant == 2) {
                // Newton z³-1
                for (int i = 0; i < 512; i++) {
                    if (i >= uMaxIter) break;
                    if (needTrap && iter >= TRAP_SKIP) {
                        float td = trapDistance(z);
                        if (td < minTD) minTD = td;
                    }
                    vec2 z2 = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y);
                    vec2 z3 = vec2(z2.x * z.x - z2.y * z.y, z2.x * z.y + z2.y * z.x);
                    vec2 f = z3 - vec2(1.0, 0.0);
                    vec2 fp = 3.0 * z2;
                    float denom = dot(fp, fp);
                    if (denom < 1e-20) break;
                    vec2 nz = z - vec2(f.x * fp.x + f.y * fp.y, f.y * fp.x - f.x * fp.y) / denom;
                    vec2 dn = nz - z;
                    if (dot(dn, dn) < 1e-10) {
                        newtonConverged = true;
                        z = nz; iter++; break;
                    }
                    z = nz; iter++;
                    if (dot(z, z) > 1e10) { escaped = true; break; }
                }
            } else {
                // Escape-time variants
                for (int i = 0; i < 512; i++) {
                    if (i >= uMaxIter) break;
                    if (needRing) {
                        ring[ringHead & RING_MASK] = z;
                        ringHead++;
                    }
                    if (needTrap && iter >= TRAP_SKIP) {
                        float td = trapDistance(z);
                        if (td < minTD) minTD = td;
                    }

                    float zr = z.x; float zi = z.y;
                    float zr2 = zr * zr; float zi2 = zi * zi;

                    if (uVariant == 4) {
                        // Burning Ship
                        float azr = abs(zr); float azi = abs(zi);
                        z.y = 2.0 * azr * azi + c.y;
                        z.x = zr2 - zi2 + c.x;
                    } else if (uVariant == 3) {
                        // Tricorn
                        z.y = -2.0 * zr * zi + c.y;
                        z.x = zr2 - zi2 + c.x;
                    } else {
                        // Mandelbrot / Julia
                        z.y = 2.0 * zr * zi + c.y;
                        z.x = zr2 - zi2 + c.x;
                    }
                    iter++;
                    if (dot(z, z) > ESCAPE_R2) { escaped = true; break; }
                }
            }

            // Point trap returned squared distance — sqrt now
            if (needTrap && uTrap == 0 && minTD < 1e30) minTD = sqrt(minTD);

            float invMaxIter = 1.0 / float(uMaxIter);
            vec3 col;

            if (uVariant == 2 && newtonConverged) {
                // Newton converged — colour by root angle
                float rootAngle = atan(z.y, z.x);
                float rootIdx = fract(rootAngle / TWO_PI + 1.0);
                float shade = clamp(1.0 - float(iter) * invMaxIter, 0.3, 1.0);
                col = cyclePalette(rootIdx) * shade;
            } else if (escaped) {
                // Exterior colouring
                if (uExtMode == 1) {
                    // Banded
                    float t = fract(float(iter) * 7.0 / 256.0);
                    col = cyclePalette(t);
                } else {
                    float mag = sqrt(dot(z, z));
                    float si = float(iter) + 1.0 - log(log(mag) / LN_ESC_R) / LN2;
                    float mul = uExtMode == 2 ? 1.0 : 3.0;
                    float t = fract(si * invMaxIter * mul);
                    col = cyclePalette(t);
                    if (uExtMode == 2) col *= 0.5;
                }
            } else {
                // Interior colouring
                if (uIntMode == 0) {
                    float normalized = clamp(sqrt(minTD * uTrapScale), 0.0, 1.0);
                    col = cyclePalette(normalized);
                } else if (uIntMode == 5) {
                    float mag = clamp(sqrt(dot(z, z)), 0.0, 2.0) * 0.5;
                    float ang = fract(atan(z.y, z.x) / TWO_PI + 1.0);
                    float combined = fract(ang * 0.7 + mag * 0.3);
                    col = cyclePalette(combined);
                } else {
                    // Period / multiplier modes — detect period from ring
                    int period = 0;
                    if (ringHead >= 2) {
                        int last = ringHead - 1;
                        vec2 ref = ring[last & RING_MASK];
                        int searchLen = min(iter, RING_SIZE - 1);
                        for (int p = 1; p < RING_SIZE; p++) {
                            if (p > searchLen) break;
                            vec2 d = ring[(last - p) & RING_MASK] - ref;
                            if (dot(d, d) < 1e-8) { period = p; break; }
                        }
                    }

                    if (period <= 0) {
                        col = cyclePalette(0.0);
                    } else if (uIntMode == 1) {
                        // Period
                        col = cyclePalette(fract(float(period) * 37.0 / 256.0));
                    } else {
                        // Multiplier — product of 2z over the period
                        vec2 prod = vec2(1.0, 0.0);
                        int start = ringHead - period;
                        for (int k = 0; k < RING_SIZE; k++) {
                            if (k >= period) break;
                            vec2 dRing = 2.0 * ring[(start + k) & RING_MASK];
                            prod = vec2(prod.x * dRing.x - prod.y * dRing.y,
                                        prod.x * dRing.y + prod.y * dRing.x);
                        }
                        float magM = clamp(sqrt(dot(prod, prod)), 0.0, 1.0);
                        float argM = atan(prod.y, prod.x);
                        if (uIntMode == 2) {
                            col = cyclePalette(magM);
                        } else if (uIntMode == 3) {
                            col = cyclePalette(fract(argM / TWO_PI + 1.0));
                        } else {
                            // interior-de
                            float de = clamp(1.0 - magM, 0.0, 1.0);
                            col = cyclePalette(de);
                        }
                    }
                }
            }
            fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """
}
