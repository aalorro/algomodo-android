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

/**
 * GPU port. Centroidal Voronoi via Lloyd's relaxation — same as VoronoiCells
 * but the "Relaxed" toggle is always on and the number of passes is exposed.
 * Adds "Show Seeds" (draws a small white circle at every centroid) and "By
 * Position" color mode (palette indexed by seed distance from origin).
 *
 * Lloyd relaxation runs CPU-side. The Show Seeds dot is drawn analytically
 * in the fragment shader via a near-zero f1 test.
 */
class CentroidalVoronoiGenerator : GpuGenerator {

    override val id = "centroidal-voronoi"
    override val family = "voronoi"
    override val styleName = "Centroidal Voronoi"
    override val definition =
        "Centroidal Voronoi tessellation where Lloyd's relaxation iteratively moves seed points to their cell centroids, producing regular honeycomb-like patterns."
    override val algorithmNotes =
        "GPU shader. CPU pre-runs Lloyd relaxation for the requested number of passes, then animates the relaxed " +
        "seeds via simplex noise. The fragment shader does a brute-force nearest/second-nearest scan. Show Seeds " +
        "draws a 3px white dot at each centroid by checking pixel-to-nearest distance."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 5f, 50f),
        Parameter.NumberParam("Relaxation Steps", "relaxationSteps", ParamGroup.GEOMETRY, "Lloyd relaxation passes — more steps = more regular hexagonal cells", 0f, 15f, 1f, 5f),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 6f, 0.5f, 1.5f),
        Parameter.BooleanParam("Show Seeds", "showSeeds", ParamGroup.GEOMETRY, "Draw the centroid seed point in each cell", false),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("By Index", "By Distance", "By Position"), "By Index"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 50f, "relaxationSteps" to 5f, "borderWidth" to 1.5f,
        "showSeeds" to false, "colorMode" to "By Index",
        "distanceMetric" to "Euclidean", "animSpeed" to 0.4f, "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 50)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val relaxSteps = ((params["relaxationSteps"] as? Number)?.toInt() ?: 5).coerceAtLeast(0)
        val showSeeds = (params["showSeeds"] as? Boolean) ?: false
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1.5f
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = VoronoiGlsl.metricId(metric)
        val colorModeId = when (colorMode) { "By Distance" -> 1; "By Position" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        VoronoiGlsl.scatterPoints(px, py, numPoints, width, height, rng)
        if (relaxSteps > 0) {
            VoronoiGlsl.lloydRelax(px, py, numPoints, width, height, metricId, passes = relaxSteps)
        }
        VoronoiGlsl.animatePoints(px, py, numPoints, width, height,
            SimplexNoise(seed), time, animSpeed, animAmp,
            keyStep = 0.5f, baseX = 10f, baseY = 110f, driftScale = 0.02f)

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBorderWidth"), borderWidth)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uShowSeeds"), if (showSeeds) 1 else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform float uBorderWidth;
        uniform int uShowSeeds;
        uniform int uColorMode;   // 0 = index, 1 = distance, 2 = position

        out vec4 fragColor;

        void main() {
            vec2 p = gl_FragCoord.xy;
            vec4 f = voronoiF1F2(p);
            float f1Lin = (uMetric == 0) ? sqrt(f.x) : f.x;
            float f2Lin = (uMetric == 0) ? sqrt(f.y) : f.y;

            float edgeThresh = uBorderWidth * 2.0;
            bool isEdge = (uBorderWidth > 0.0) && ((f2Lin - f1Lin) < edgeThresh);

            vec3 col;
            if (isEdge) {
                col = vec3(0.0);
            } else if (uColorMode == 1) {
                float avgCellSize = sqrt(uResolution.x * uResolution.y / max(float(uPointCount), 1.0));
                float t = clamp(f1Lin / avgCellSize, 0.0, 1.0);
                col = palette_color(t);
            } else if (uColorMode == 2) {
                int idx = int(f.z + 0.5);
                vec2 sp = uPoints[idx];
                float diagonal = sqrt(uResolution.x * uResolution.x + uResolution.y * uResolution.y);
                float t = clamp(length(sp) / diagonal, 0.0, 1.0);
                col = palette_color(t);
            } else {
                float t = mod(f.z, 5.0) / 4.0;
                col = palette_color(t);
            }

            // Show seeds — paint a small white dot anywhere within 3 pixels of
            // the nearest centroid (overrides everything else).
            if (uShowSeeds == 1 && f1Lin < 3.0) {
                col = vec3(1.0);
            }

            fragColor = vec4(col, 1.0);
        }
    """
}
