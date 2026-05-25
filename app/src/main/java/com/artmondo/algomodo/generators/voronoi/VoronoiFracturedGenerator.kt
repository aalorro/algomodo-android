package com.artmondo.algomodo.generators.voronoi

import android.opengl.GLES30
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.GpuGenerator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.gl.PaletteUniform
import com.artmondo.algomodo.rendering.gl.VoronoiGlsl
import kotlin.math.sqrt

/**
 * GPU port. Two-level Voronoi — primary "shards" + secondary "fractures".
 * Uses a second uniform point array [uFracturePoints] for the fracture set.
 * The combined index `near0 * 7 + near1` drives palette selection (mirrors
 * the CPU behaviour).
 *
 * Distance metric is implemented inline inside [fracturedScan] so we can
 * share a single scan for both point sets.
 */
class VoronoiFracturedGenerator : GpuGenerator {

    override val id = "voronoi-fractured"
    override val family = "voronoi"
    override val styleName = "Voronoi Fractured"
    override val definition =
        "Multi-level fractured Voronoi where each cell is recursively subdivided by secondary Voronoi seeds, creating shattered-glass patterns."
    override val algorithmNotes =
        "GPU shader. A primary set of seed points defines large shards; a jittered-grid fracture set " +
        "subdivides each shard. Both F1/F2 scans run on the GPU using the chosen metric. " +
        "Combined index = near0 * 7 + near1. Animation drift on both point sets is computed CPU-side."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Shard Count", "shardCount", ParamGroup.COMPOSITION, "Number of primary fracture regions", 5f, 100f, 5f, 25f),
        Parameter.NumberParam("Fracture Lines", "fractureCount", ParamGroup.COMPOSITION, "Secondary crack density within each shard", 10f, 200f, 10f, 60f),
        Parameter.NumberParam("Crack Width", "crackWidth", ParamGroup.GEOMETRY, "Width of primary shard boundaries", 0.5f, 6f, 0.5f, 1.5f),
        Parameter.NumberParam("Fracture Width", "fractureWidth", ParamGroup.GEOMETRY, "Width of secondary fracture lines within shards", 0.2f, 3f, 0.1f, 0.8f),
        Parameter.NumberParam("Shard Shading", "shadeStrength", ParamGroup.TEXTURE, "Directional brightness gradient within each shard to suggest 3D tilt", 0f, 1f, 0.05f, 0.5f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("palette-cycle", "palette-gradient", "monochrome"), "palette-cycle"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.3f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.15f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "shardCount" to 25f,
        "fractureCount" to 60f,
        "crackWidth" to 1.5f,
        "fractureWidth" to 0.8f,
        "shadeStrength" to 0.5f,
        "colorMode" to "palette-cycle",
        "distanceMetric" to "Euclidean",
        "animSpeed" to 0.3f,
        "animAmp" to 0.15f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["shardCount"] as? Number)?.toInt() ?: 25)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val fractureCount = ((params["fractureCount"] as? Number)?.toInt() ?: 60)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val crackWidth = (params["crackWidth"] as? Number)?.toFloat() ?: 1.5f
        val fractureWidth = (params["fractureWidth"] as? Number)?.toFloat() ?: 0.8f
        val shadeStrength = (params["shadeStrength"] as? Number)?.toFloat() ?: 0.5f
        val colorMode = (params["colorMode"] as? String) ?: "palette-cycle"
        val distanceMetric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.3f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.15f

        val metricId = VoronoiGlsl.metricId(distanceMetric)
        val colorModeId = when (colorMode) { "palette-gradient" -> 1; "monochrome" -> 2; else -> 0 }

        val wf = width.toFloat(); val hf = height.toFloat()
        val noise = SimplexNoise(seed)

        // Level 0: random shards
        val rng0 = SeededRNG(seed)
        val px0 = FloatArray(numPoints); val py0 = FloatArray(numPoints)
        for (i in 0 until numPoints) {
            px0[i] = rng0.random() * width
            py0[i] = rng0.random() * height
        }

        // Level 1: jittered grid for fractures
        val rng1 = SeededRNG(seed + 7919)
        val px1 = FloatArray(fractureCount); val py1 = FloatArray(fractureCount)
        val cols1 = sqrt(fractureCount.toFloat()).toInt().coerceAtLeast(1)
        val rows1 = (fractureCount + cols1 - 1) / cols1
        val stepW = wf / cols1; val stepH = hf / rows1
        var fi = 0
        for (r in 0 until rows1) {
            for (c in 0 until cols1) {
                if (fi >= fractureCount) break
                px1[fi] = ((c + 0.5f) * stepW + (rng1.random() - 0.5f) * stepW * 0.5f).coerceIn(0f, wf - 1f)
                py1[fi] = ((r + 0.5f) * stepH + (rng1.random() - 0.5f) * stepH * 0.5f).coerceIn(0f, hf - 1f)
                fi++
            }
        }

        if (time > 0f && animSpeed > 0f && animAmp > 0f) {
            val amp = animAmp * 0.15f
            for (i in 0 until numPoints) {
                px0[i] = (px0[i] + noise.noise2D(i * 0.2f, time * animSpeed) * wf * amp).coerceIn(0f, wf - 1f)
                py0[i] = (py0[i] + noise.noise2D(i * 0.2f + 500f, time * animSpeed) * hf * amp).coerceIn(0f, hf - 1f)
            }
            val speed1 = animSpeed * 0.5f
            for (i in 0 until fractureCount) {
                px1[i] = (px1[i] + noise.noise2D(i * 0.2f + 100f, time * speed1) * wf * amp).coerceIn(0f, wf - 1f)
                py1[i] = (py1[i] + noise.noise2D(i * 0.2f + 600f, time * speed1) * hf * amp).coerceIn(0f, hf - 1f)
            }
        }

        val packed0 = VoronoiGlsl.packPoints(px0, py0, numPoints)
        val packed1 = VoronoiGlsl.packPoints(px1, py1, fractureCount)

        val cellRadius = sqrt(wf * hf / numPoints.toFloat()) * 0.5f
        // Thresholds are in linear pixels — shader converts the F1/F2 squared
        // distances to linear before comparing. Multipliers chosen so that
        // crackWidth=1.5 gives a ~3px crack and fractureWidth=0.8 gives ~1.6px.
        val crackThresh = crackWidth * 2f
        val fractureThresh = fractureWidth * 2f

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed0, 0)
        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uFracturePoints"),
            VoronoiGlsl.MAX_POINTS, packed1, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uFractureCount"), fractureCount)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uCrackThresh"), crackThresh)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFractureThresh"), fractureThresh)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uShadeStrength"), shadeStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvCellRadius"), 1f / cellRadius)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform vec2 uFracturePoints[VORONOI_MAX_POINTS];
        uniform int uFractureCount;
        uniform int uColorMode;          // 0 palette-cycle, 1 palette-gradient, 2 monochrome
        uniform float uCrackThresh;      // linear-pixel gap threshold (level 0)
        uniform float uFractureThresh;   // linear-pixel gap threshold (level 1)
        uniform float uShadeStrength;
        uniform float uInvCellRadius;

        out vec4 fragColor;

        // F1/F2 scan against the primary shards. Distances are returned in the
        // same "units" the CPU uses: squared for euclidean (no sqrt), linear
        // for manhattan/chebyshev — which keeps the (f2 - f1) gap comparison
        // matched to the CPU thresholds.
        vec4 shardsScan(vec2 p) {
            float f1 = 1e30; float f2 = 1e30; float idx = 0.0;
            for (int i = 0; i < VORONOI_MAX_POINTS; i++) {
                if (i >= uPointCount) break;
                vec2 d = p - uPoints[i];
                float dist;
                if (uMetric == 1)      dist = abs(d.x) + abs(d.y);
                else if (uMetric == 2) dist = max(abs(d.x), abs(d.y));
                else                   dist = dot(d, d);   // euclidean squared
                if (dist < f1) { f2 = f1; f1 = dist; idx = float(i); }
                else if (dist < f2) { f2 = dist; }
            }
            return vec4(f1, f2, idx, 0.0);
        }

        // Same as shardsScan but against uFracturePoints / uFractureCount.
        vec4 fractureScan(vec2 p) {
            float f1 = 1e30; float f2 = 1e30; float idx = 0.0;
            for (int i = 0; i < VORONOI_MAX_POINTS; i++) {
                if (i >= uFractureCount) break;
                vec2 d = p - uFracturePoints[i];
                float dist;
                if (uMetric == 1)      dist = abs(d.x) + abs(d.y);
                else if (uMetric == 2) dist = max(abs(d.x), abs(d.y));
                else                   dist = dot(d, d);
                if (dist < f1) { f2 = f1; f1 = dist; idx = float(i); }
                else if (dist < f2) { f2 = dist; }
            }
            return vec4(f1, f2, idx, 0.0);
        }

        void main() {
            vec2 p = gl_FragCoord.xy;
            vec4 a = shardsScan(p);
            vec4 b = fractureScan(p);

            // Linearise euclidean (squared) gaps so the threshold is in pixels.
            float aGap = (uMetric == 0) ? (sqrt(a.y) - sqrt(a.x)) : (a.y - a.x);
            float bGap = (uMetric == 0) ? (sqrt(b.y) - sqrt(b.x)) : (b.y - b.x);
            bool isCrack    = aGap < uCrackThresh;
            bool isFracture = bGap < uFractureThresh;

            int near0 = int(a.z + 0.5);
            int near1 = int(b.z + 0.5);
            float combinedIndex = float(near0) * 7.0 + float(near1);

            vec3 baseCol;
            if (uColorMode == 1) {
                // palette-gradient: t = near0 / (numPoints - 1)
                float denom = max(float(uPointCount - 1), 1.0);
                float t = clamp(float(near0) / denom, 0.0, 1.0);
                baseCol = palette_color(t);
            } else if (uColorMode == 2) {
                // monochrome: palette[0] * (0.5..1.0) hashed brightness
                vec3 base = uPalette[0];
                float hash = mod(combinedIndex * 4973.0, 256.0);
                float br = 0.5 + 0.5 * (hash / 255.0);
                baseCol = clamp(base * br, 0.0, 1.0);
            } else {
                // palette-cycle: cycles through 5 swatches by combinedIndex
                float t = mod(combinedIndex, 5.0) / 4.0;
                baseCol = palette_color(t);
            }

            // Shard shading: dot of cell-local normal with light dir (0.707, 0.707)
            if (uShadeStrength > 0.0) {
                vec2 cellD = p - uPoints[near0];
                vec2 n = clamp(cellD * uInvCellRadius, -1.0, 1.0);
                float shade = 1.0 + (n.x * 0.7071 + n.y * 0.7071) * uShadeStrength;
                baseCol = clamp(baseCol * shade, 0.0, 1.0);
            }

            vec3 col;
            if (isCrack) col = vec3(0.0);
            else if (isFracture) col = baseCol * 0.4;
            else col = baseCol;

            fragColor = vec4(col, 1.0);
        }
    """
}
