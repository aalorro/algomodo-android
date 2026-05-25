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
 * GPU port. Classic Voronoi: each pixel takes the colour of the cell whose
 * seed point it lies closest to under the chosen metric. Edges are drawn
 * where the gap between the nearest and second-nearest distances is small.
 *
 * Point placement (and simplex-noise animation drift) is computed CPU-side
 * in [bindUniforms] and uploaded as a vec2 array.
 * The fragment shader does a brute-force linear scan per pixel.
 */
class VoronoiCellsGenerator : GpuGenerator {

    override val id = "voronoi-cells"
    override val family = "voronoi"
    override val styleName = "Voronoi Cells"
    override val definition =
        "Classic Voronoi diagram where the plane is partitioned into cells around scattered seed points, each coloured by palette index."
    override val algorithmNotes =
        "GPU shader. Seed points and simplex-noise animation are computed on the CPU " +
        "and uploaded as a vec2 uniform array. Per pixel the fragment shader linearly scans all points for the " +
        "nearest and second-nearest, then maps to palette by index/distance/angle. Edges are drawn where the " +
        "f2-f1 gap is below the border threshold."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 200f, 1f, 40f),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 5f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "", listOf("By Index", "By Distance", "By Angle"), "By Index"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "", 0f, 2f, 0.05f, 0.4f),
        Parameter.NumberParam("Anim Amplitude", "animAmp", ParamGroup.FLOW_MOTION, "Drift distance as a fraction of average cell size", 0f, 1f, 0.05f, 0.2f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f,
        "distanceMetric" to "Euclidean",
        "borderWidth" to 1f,
        "colorMode" to "By Index",
        "animSpeed" to 0.4f,
        "animAmp" to 0.2f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 40)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val edgeWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.4f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0.2f

        val metricId = VoronoiGlsl.metricId(metric)
        val colorModeId = when (colorMode) { "By Distance" -> 1; "By Angle" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        VoronoiGlsl.scatterPoints(px, py, numPoints, width, height, rng)
        VoronoiGlsl.animatePoints(px, py, numPoints, width, height,
            SimplexNoise(seed), time, animSpeed, animAmp)

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEdgeWidth"), edgeWidth)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform float uEdgeWidth;
        uniform int uColorMode;  // 0 = index, 1 = distance, 2 = angle

        out vec4 fragColor;

        const float PI = 3.14159265359;
        const float INV_TWO_PI = 0.15915494309;

        void main() {
            vec2 p = gl_FragCoord.xy;
            vec4 f = voronoiF1F2(p);
            // f.x/f.y are SQUARED for euclidean, LINEAR for manhattan/chebyshev.
            float f1Lin = (uMetric == 0) ? sqrt(f.x) : f.x;
            float f2Lin = (uMetric == 0) ? sqrt(f.y) : f.y;

            float edgeThresh = uEdgeWidth * 2.0;
            bool isEdge = (uEdgeWidth > 0.0) && ((f2Lin - f1Lin) < edgeThresh);

            vec3 col;
            if (isEdge) {
                col = vec3(0.0);
            } else if (uColorMode == 1) {
                // By Distance — normalise against avg cell size sqrt(area/n)
                float avgCellSize = sqrt(uResolution.x * uResolution.y / max(float(uPointCount), 1.0));
                float t = clamp(f1Lin / avgCellSize, 0.0, 1.0);
                col = palette_color(t);
            } else if (uColorMode == 2) {
                // By Angle — angle of the seed point relative to canvas center
                int idx = int(f.z + 0.5);
                vec2 sp = uPoints[idx];
                vec2 d = sp - uResolution * 0.5;
                float t = clamp((atan(d.y, d.x) + PI) * INV_TWO_PI, 0.0, 1.0);
                col = palette_color(t);
            } else {
                // By Index — cycle 5-colour palette
                float t = mod(f.z, 5.0) / 4.0;
                col = palette_color(t);
            }

            fragColor = vec4(col, 1.0);
        }
    """
}
