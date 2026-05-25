package com.artmondo.algomodo.generators.fractals

import android.graphics.Color
import android.opengl.GLES30
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPU port. Newton fractal — five styles (classic / sine / polynomial /
 * halley / secant). Roots discovered CPU-side (polynomial) or generated as
 * roots-of-unity, then uploaded as a vec2 uniform array. Shader picks the
 * appropriate iteration based on uStyle.
 */
class NewtonGenerator : GpuGenerator {

    override val id = "fractal-newton"
    override val family = "fractals"
    override val styleName = "Newton Fractal"
    override val definition =
        "Newton's method fractal with multiple styles — classic, sine, polynomial, Halley, and secant variants."
    override val algorithmNotes =
        "GPU fragment shader. Roots computed CPU-side (16-grid Newton search for polynomial, " +
        "roots-of-unity for classic/halley/secant; sine uses periodic root test). Per-pixel iteration " +
        "executes in shader with style-specific branches. Per-style colour LUT computed CPU-side."
    override val supportsVector = false
    override val supportsAnimation = true

    companion object {
        private const val STYLE_CLASSIC = "classic"
        private const val STYLE_SINE = "sine"
        private const val STYLE_POLYNOMIAL = "polynomial"
        private const val STYLE_HALLEY = "halley"
        private const val STYLE_SECANT = "secant"

        private const val MAX_ROOTS = 16
    }

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            "Style", "style", ParamGroup.COMPOSITION,
            "classic: z^n-1 | sine: sin(z) | polynomial: z^n+z\u00b2-1 | halley: 3rd-order | secant: finite-difference",
            listOf(STYLE_CLASSIC, STYLE_SINE, STYLE_POLYNOMIAL, STYLE_HALLEY, STYLE_SECANT),
            STYLE_CLASSIC
        ),
        Parameter.NumberParam("Power", "power", ParamGroup.COMPOSITION, "Degree of the polynomial (ignored for sine style)", 2f, 6f, 1f, 3f),
        Parameter.NumberParam("Zoom", "zoom", ParamGroup.COMPOSITION, "Zoom level into the fractal", 0.5f, 4f, 0.5f, 1f),
        Parameter.NumberParam("Max Iterations", "maxIterations", ParamGroup.COMPOSITION, "Maximum Newton iterations per pixel", 16f, 64f, 8f, 32f),
        Parameter.NumberParam("Damping", "damping", ParamGroup.GEOMETRY, "Relaxation factor \u2014 1 = standard Newton, other values create nova fractals", 0.5f, 1.5f, 0.05f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "root: color by converged root | iteration: shade by speed | blended: both combined", listOf("root", "iteration", "blended"), "blended"),
        Parameter.NumberParam("Speed", "speed", ParamGroup.FLOW_MOTION, "Speed of damping animation", 0.1f, 3.0f, 0.1f, 0.5f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "style" to STYLE_CLASSIC,
        "power" to 3f,
        "zoom" to 1f,
        "maxIterations" to 32f,
        "damping" to 1f,
        "colorMode" to "blended",
        "speed" to 0.5f
    )

    private fun discoverPolynomialRoots(power: Int): Pair<FloatArray, FloatArray> {
        val foundR = mutableListOf<Double>()
        val foundI = mutableListOf<Double>()
        val gridN = 16
        for (gy in 0 until gridN) {
            for (gx in 0 until gridN) {
                var zr = (gx.toDouble() / (gridN - 1)) * 4.0 - 2.0
                var zi = (gy.toDouble() / (gridN - 1)) * 4.0 - 2.0
                for (s in 0 until 200) {
                    var pnm1r = 1.0; var pnm1i = 0.0
                    for (k in 0 until power - 1) {
                        val tr = pnm1r * zr - pnm1i * zi
                        pnm1i = pnm1r * zi + pnm1i * zr
                        pnm1r = tr
                    }
                    val pnr = pnm1r * zr - pnm1i * zi
                    val pni = pnm1r * zi + pnm1i * zr
                    val z2r = zr * zr - zi * zi
                    val z2i = 2.0 * zr * zi
                    val fr = pnr + z2r - 1.0
                    val fi = pni + z2i
                    val fpr = power * pnm1r + 2.0 * zr
                    val fpi = power * pnm1i + 2.0 * zi
                    val denom = fpr * fpr + fpi * fpi
                    if (denom < 1e-20) break
                    zr -= (fr * fpr + fi * fpi) / denom
                    zi -= (fi * fpr - fr * fpi) / denom
                    if (zr * zr + zi * zi > 1e10) break
                }
                if (zr * zr + zi * zi > 100.0) continue
                var isNew = true
                for (k in foundR.indices) {
                    val dr = zr - foundR[k]; val di = zi - foundI[k]
                    if (dr * dr + di * di < 1e-6) { isNew = false; break }
                }
                if (isNew) { foundR.add(zr); foundI.add(zi) }
            }
        }
        if (foundR.isEmpty()) { foundR.add(0.0); foundI.add(0.0) }
        return Pair(
            FloatArray(foundR.size) { foundR[it].toFloat() },
            FloatArray(foundR.size) { foundI[it].toFloat() }
        )
    }

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val style = (params["style"] as? String) ?: STYLE_CLASSIC
        val power = ((params["power"] as? Number)?.toInt() ?: 3).coerceIn(2, 6)
        val zoom = (params["zoom"] as? Number)?.toFloat() ?: 1f
        val maxIter = (params["maxIterations"] as? Number)?.toInt() ?: 32
        val baseDamping = (params["damping"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "blended"
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f

        val isAnim = time > 0f
        val scaledMaxIter = when {
            isAnim -> maxIter.coerceIn(8, 20)
            quality == Quality.DRAFT -> (maxIter / 2).coerceAtLeast(8)
            quality == Quality.ULTRA -> (maxIter * 1.5f).toInt()
            else -> maxIter
        }
        val damping = (baseDamping + sin(time * speed * 0.3) * 0.1).toFloat()

        val baseRange = if (style == STYLE_SINE) (PI * 4.0).toFloat() else 3f
        val aspect = width.toFloat() / height.toFloat()
        val rangeY = baseRange / zoom
        val rangeX = rangeY * aspect
        val rotAngle = time * speed * 0.08f
        val cosR = cos(rotAngle.toDouble()).toFloat()
        val sinR = sin(rotAngle.toDouble()).toFloat()

        // Roots
        val rootsR: FloatArray; val rootsI: FloatArray
        when (style) {
            STYLE_SINE -> { rootsR = FloatArray(0); rootsI = FloatArray(0) }
            STYLE_POLYNOMIAL -> {
                val (rr, ri) = discoverPolynomialRoots(power); rootsR = rr; rootsI = ri
            }
            else -> {
                rootsR = FloatArray(power) { k -> cos(2.0 * PI * k / power).toFloat() }
                rootsI = FloatArray(power) { k -> sin(2.0 * PI * k / power).toFloat() }
            }
        }
        val numRoots = rootsR.size

        // Pack roots into a vec2[MAX_ROOTS] uniform
        val packed = FloatArray(MAX_ROOTS * 2)
        for (i in 0 until minOf(numRoots, MAX_ROOTS)) {
            packed[i * 2] = rootsR[i]; packed[i * 2 + 1] = rootsI[i]
        }

        // Per-root colour table (vec3[MAX_ROOTS]) — blended mode applies
        // iteration shade in shader.
        val colorInts = palette.colorInts()
        val rootCols = FloatArray(MAX_ROOTS * 3)
        for (i in 0 until MAX_ROOTS) {
            val c = colorInts[i % colorInts.size]
            rootCols[i * 3] = Color.red(c) / 255f
            rootCols[i * 3 + 1] = Color.green(c) / 255f
            rootCols[i * 3 + 2] = Color.blue(c) / 255f
        }

        val styleId = when (style) {
            STYLE_SINE -> 1
            STYLE_POLYNOMIAL -> 2
            STYLE_HALLEY -> 3
            STYLE_SECANT -> 4
            else -> 0
        }
        val colorModeId = when (colorMode) { "root" -> 0; "iteration" -> 1; else -> 2 }

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uRoots"), MAX_ROOTS, packed, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uRootColors"), MAX_ROOTS, rootCols, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uNumRoots"), numRoots)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uStyle"), styleId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPower"), power)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMaxIter"), scaledMaxIter)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDamping"), damping)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRange"), rangeX, rangeY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uRot"), cosR, sinR)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSecantOffset"), 0.01f / zoom)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}

        #define MAX_ROOTS 16

        uniform vec2 uRoots[MAX_ROOTS];
        uniform vec3 uRootColors[MAX_ROOTS];
        uniform int uNumRoots;
        uniform int uStyle;        // 0 classic, 1 sine, 2 poly, 3 halley, 4 secant
        uniform int uPower;
        uniform int uMaxIter;
        uniform float uDamping;
        uniform int uColorMode;    // 0 root, 1 iteration, 2 blended
        uniform vec2 uRange;
        uniform vec2 uRot;
        uniform float uSecantOffset;

        const float TOL_SQ = 1e-4;
        const float BAIL_SQ = 1e10;
        const float DENOM_MIN = 1e-20;
        const float INV_PI = 0.31830989;
        const float PI_F = 3.1415927;

        out vec4 fragColor;

        // Complex helpers
        vec2 cmul(vec2 a, vec2 b) { return vec2(a.x*b.x - a.y*b.y, a.x*b.y + a.y*b.x); }

        // Returns (zr, zi, iter, rootIndex). rootIndex = -1 if not converged.
        vec4 iterate(vec2 z0) {
            vec2 z = z0;
            int iter = 0;
            int rootIndex = -1;

            // Secant needs previous z + previous f(z)
            vec2 prevZ = z + vec2(uSecantOffset);
            vec2 prevF = vec2(0.0);
            if (uStyle == 4) {
                vec2 pn = vec2(1.0, 0.0);
                for (int k = 0; k < 6; k++) {
                    if (k >= uPower) break;
                    pn = cmul(pn, prevZ);
                }
                prevF = pn - vec2(1.0, 0.0);
            }

            for (int i = 0; i < 96; i++) {
                if (i >= uMaxIter) break;

                vec2 step;
                bool ok = true;

                if (uStyle == 0) {
                    // Classic: f = z^n - 1, f' = n*z^(n-1)
                    vec2 pnm1 = vec2(1.0, 0.0);
                    for (int k = 0; k < 6; k++) {
                        if (k >= uPower - 1) break;
                        pnm1 = cmul(pnm1, z);
                    }
                    vec2 pn = cmul(pnm1, z);
                    vec2 f = pn - vec2(1.0, 0.0);
                    vec2 fp = float(uPower) * pnm1;
                    float d = dot(fp, fp);
                    if (d < DENOM_MIN) { ok = false; }
                    else step = vec2(f.x*fp.x + f.y*fp.y, f.y*fp.x - f.x*fp.y) / d;
                } else if (uStyle == 1) {
                    // sin(z) for complex z: sin(a+ib) = sin(a)cosh(b) + i cos(a)sinh(b)
                    float sa = sin(z.x); float ca = cos(z.x);
                    float eb = exp(z.y); float enb = 1.0 / eb;
                    float sb = (eb - enb) * 0.5;
                    float cb = (eb + enb) * 0.5;
                    vec2 f = vec2(sa * cb, ca * sb);
                    vec2 fp = vec2(ca * cb, -sa * sb);
                    float d = dot(fp, fp);
                    if (d < DENOM_MIN) { ok = false; }
                    else step = vec2(f.x*fp.x + f.y*fp.y, f.y*fp.x - f.x*fp.y) / d;
                } else if (uStyle == 2) {
                    // Polynomial f = z^n + z^2 - 1
                    vec2 pnm1 = vec2(1.0, 0.0);
                    for (int k = 0; k < 6; k++) {
                        if (k >= uPower - 1) break;
                        pnm1 = cmul(pnm1, z);
                    }
                    vec2 pn = cmul(pnm1, z);
                    vec2 z2 = cmul(z, z);
                    vec2 f = pn + z2 - vec2(1.0, 0.0);
                    vec2 fp = float(uPower) * pnm1 + 2.0 * z;
                    float d = dot(fp, fp);
                    if (d < DENOM_MIN) { ok = false; }
                    else step = vec2(f.x*fp.x + f.y*fp.y, f.y*fp.x - f.x*fp.y) / d;
                } else if (uStyle == 3) {
                    // Halley on z^n - 1
                    vec2 pnm2 = vec2(1.0, 0.0);
                    for (int k = 0; k < 6; k++) {
                        if (k >= uPower - 2) break;
                        pnm2 = cmul(pnm2, z);
                    }
                    vec2 pnm1 = cmul(pnm2, z);
                    vec2 pn = cmul(pnm1, z);
                    vec2 f = pn - vec2(1.0, 0.0);
                    vec2 fp = float(uPower) * pnm1;
                    vec2 fpp = float(uPower * (uPower - 1)) * pnm2;
                    vec2 num = 2.0 * cmul(f, fp);
                    vec2 fp2 = cmul(fp, fp);
                    vec2 den = 2.0 * fp2 - cmul(f, fpp);
                    float d = dot(den, den);
                    if (d < DENOM_MIN) { ok = false; }
                    else step = vec2(num.x*den.x + num.y*den.y, num.y*den.x - num.x*den.y) / d;
                } else {
                    // Secant on z^n - 1
                    vec2 pn = vec2(1.0, 0.0);
                    for (int k = 0; k < 6; k++) {
                        if (k >= uPower) break;
                        pn = cmul(pn, z);
                    }
                    vec2 f = pn - vec2(1.0, 0.0);
                    vec2 df = f - prevF;
                    float dfD = dot(df, df);
                    if (dfD < DENOM_MIN) { ok = false; }
                    else {
                        vec2 dz = z - prevZ;
                        vec2 num = cmul(f, dz);
                        step = vec2(num.x*df.x + num.y*df.y, num.y*df.x - num.x*df.y) / dfD;
                        prevZ = z; prevF = f;
                    }
                }
                if (!ok) break;

                z -= uDamping * step;

                if (dot(z, z) > BAIL_SQ) break;

                // Root test every other iter
                if ((i & 1) == 0) {
                    if (uStyle == 1) {
                        float nearK = floor(z.x * INV_PI + 0.5);
                        float dr = z.x - nearK * PI_F;
                        if (dr * dr + z.y * z.y < TOL_SQ) {
                            int ki = int(nearK);
                            int m = ki % MAX_ROOTS;
                            rootIndex = (m + MAX_ROOTS) % MAX_ROOTS;
                            break;
                        }
                    } else {
                        for (int k = 0; k < MAX_ROOTS; k++) {
                            if (k >= uNumRoots) break;
                            vec2 dr = z - uRoots[k];
                            if (dot(dr, dr) < TOL_SQ) { rootIndex = k; break; }
                        }
                        if (rootIndex >= 0) break;
                    }
                }
                iter++;
            }
            return vec4(z.x, z.y, float(iter), float(rootIndex));
        }

        void main() {
            vec2 uv = gl_FragCoord.xy / uResolution.xy;
            float rawX = (uv.x - 0.5) * uRange.x;
            float rawY = (uv.y - 0.5) * uRange.y;
            vec2 z0 = vec2(rawX * uRot.x - rawY * uRot.y, rawX * uRot.y + rawY * uRot.x);
            vec4 r = iterate(z0);
            int rootIndex = int(r.w);
            int iter = int(r.z);

            vec3 col;
            if (rootIndex < 0) {
                col = vec3(0.039);
            } else {
                float iterT = clamp(1.0 - float(iter) / float(uMaxIter), 0.0, 1.0);
                if (uColorMode == 0) {
                    col = uRootColors[rootIndex];
                } else if (uColorMode == 1) {
                    col = palette_color(iterT);
                } else {
                    float shade = 0.3 + 0.7 * iterT;
                    col = uRootColors[rootIndex] * shade;
                }
            }
            fragColor = vec4(col, 1.0);
        }
    """
}
