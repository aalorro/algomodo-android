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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPU port. 3D-styled Voronoi: nearest seed defines a faux normal pointing
 * away from the cell centre. A directional light produces Blinn-Phong shading.
 *
 * The CPU pre-computed a half-vector and a 256-entry specular LUT per frame;
 * we move that math into shader where it's per-pixel-cheap anyway.
 */
class VoronoiDepthGenerator : GpuGenerator {

    override val id = "voronoi-depth"
    override val family = "voronoi"
    override val styleName = "Voronoi Depth"
    override val definition =
        "3D-styled Voronoi cells with faux lighting and depth shading, making each cell appear as a raised plateau."
    override val algorithmNotes =
        "GPU shader. Pixel-to-cell-centre direction is used as the (x,y) of a surface normal, with z derived " +
        "by fast 1 - 0.5*ns^2 approximation. Blinn-Phong style: ambient + diffuse(N.L) + specular(N.H)^shininess. " +
        "Light direction (and optional seed drift) is animated CPU-side."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Cell Count", "cellCount", ParamGroup.COMPOSITION, "", 5f, 150f, 5f, 40f),
        Parameter.NumberParam("Relaxation", "relaxationSteps", ParamGroup.GEOMETRY, "Lloyd relaxation passes for more even cell distribution", 0f, 8f, 1f, 3f),
        Parameter.NumberParam("Tilt Amount", "tiltAmount", ParamGroup.GEOMETRY, "How far cell normals deviate from vertical (0 = flat, 1 = maximum tilt)", 0f, 1f, 0.05f, 0.65f),
        Parameter.NumberParam("Light Angle", "lightAngle", ParamGroup.GEOMETRY, "Horizontal direction of the light source in degrees (0=right, 90=down, 180=left, 270=up)", 0f, 360f, 5f, 225f),
        Parameter.NumberParam("Light Elevation", "lightElevation", ParamGroup.GEOMETRY, "Vertical elevation of the light above the horizon in degrees", 5f, 85f, 5f, 50f),
        Parameter.NumberParam("Ambient", "ambient", ParamGroup.COLOR, "Minimum brightness in shadowed areas", 0f, 0.6f, 0.05f, 0.15f),
        Parameter.NumberParam("Specular", "specular", ParamGroup.COLOR, "Brightness of specular highlight on facing facets", 0f, 1f, 0.05f, 0.45f),
        Parameter.NumberParam("Shininess", "shininess", ParamGroup.TEXTURE, "Specular highlight sharpness — higher = smaller, harder glint", 2f, 64f, 2f, 12f),
        Parameter.NumberParam("Border Width", "borderWidth", ParamGroup.GEOMETRY, "", 0f, 4f, 0.5f, 1f),
        Parameter.SelectParam("Color Mode", "colorMode", ParamGroup.COLOR, "By Normal-Z: palette maps to how steeply each cell faces the viewer", listOf("By Index", "By Position", "By Normal-Z"), "By Index"),
        Parameter.SelectParam("Distance Metric", "distanceMetric", ParamGroup.GEOMETRY, "", listOf("Euclidean", "Manhattan", "Chebyshev"), "Euclidean"),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Controls both light rotation speed and site drift speed", 0f, 2f, 0.05f, 0.5f),
        Parameter.NumberParam("Site Drift", "animAmp", ParamGroup.FLOW_MOTION, "0 = only light rotates; >0 = cells also drift", 0f, 1f, 0.05f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "cellCount" to 40f, "relaxationSteps" to 3f, "tiltAmount" to 0.65f,
        "lightAngle" to 225f, "lightElevation" to 50f, "ambient" to 0.15f,
        "specular" to 0.45f, "shininess" to 12f, "borderWidth" to 1f,
        "colorMode" to "By Index", "distanceMetric" to "Euclidean",
        "animSpeed" to 0.5f, "animAmp" to 0f
    )

    override fun bindUniforms(
        programId: Int, params: Map<String, Any>, seed: Int, palette: Palette,
        quality: Quality, time: Float, width: Int, height: Int
    ) {
        val numPoints = ((params["cellCount"] as? Number)?.toInt() ?: 40)
            .coerceIn(1, VoronoiGlsl.MAX_POINTS)
        val relaxSteps = ((params["relaxationSteps"] as? Number)?.toInt() ?: 3).coerceAtLeast(0)
        val depth = (params["tiltAmount"] as? Number)?.toFloat() ?: 0.65f
        val lightAngleDeg = (params["lightAngle"] as? Number)?.toFloat() ?: 225f
        val lightElevationDeg = (params["lightElevation"] as? Number)?.toFloat() ?: 50f
        val ambient = (params["ambient"] as? Number)?.toFloat() ?: 0.15f
        val specular = (params["specular"] as? Number)?.toFloat() ?: 0.45f
        val shininess = (params["shininess"] as? Number)?.toFloat() ?: 12f
        val borderWidth = (params["borderWidth"] as? Number)?.toFloat() ?: 1f
        val colorMode = (params["colorMode"] as? String) ?: "By Index"
        val metric = (params["distanceMetric"] as? String) ?: "Euclidean"
        val animSpeed = (params["animSpeed"] as? Number)?.toFloat() ?: 0.5f
        val animAmp = (params["animAmp"] as? Number)?.toFloat() ?: 0f

        val metricId = VoronoiGlsl.metricId(metric)
        val colorModeId = when (colorMode) { "By Position" -> 1; "By Normal-Z" -> 2; else -> 0 }

        val rng = SeededRNG(seed)
        val px = FloatArray(numPoints); val py = FloatArray(numPoints)
        VoronoiGlsl.scatterPoints(px, py, numPoints, width, height, rng)
        if (relaxSteps > 0) {
            VoronoiGlsl.lloydRelax(px, py, numPoints, width, height, metricId, passes = relaxSteps)
        }
        if (time > 0f && animAmp > 0f) {
            val noise = SimplexNoise(seed)
            val wf = width.toFloat(); val hf = height.toFloat()
            for (i in 0 until numPoints) {
                px[i] = (px[i] + noise.noise2D(i * 0.35f + 20f, time * 0.12f * animSpeed) * wf * 0.03f * animAmp).coerceIn(0f, wf - 1f)
                py[i] = (py[i] + noise.noise2D(i * 0.35f + 120f, time * 0.12f * animSpeed) * hf * 0.03f * animAmp).coerceIn(0f, hf - 1f)
            }
        }

        // Light vector (animated angle)
        val lightAngle = Math.toRadians((lightAngleDeg + time * 20f * animSpeed).toDouble()).toFloat()
        val elev = Math.toRadians(lightElevationDeg.toDouble()).toFloat()
        val cosE = cos(elev); val sinE = sin(elev)
        val lx = cos(lightAngle) * cosE
        val ly = sin(lightAngle) * cosE
        val lz = sinE
        // Half-vector (viewer assumed at +Z)
        val hLen = sqrt(lx * lx + ly * ly + (lz + 1f) * (lz + 1f))
        val hx: Float; val hy: Float; val hz: Float
        if (hLen > 0.001f) { hx = lx / hLen; hy = ly / hLen; hz = (lz + 1f) / hLen }
        else { hx = 0f; hy = 0f; hz = 1f }

        val avgRadius = sqrt((width.toFloat() * height.toFloat()) / numPoints) * 0.5f

        val packed = VoronoiGlsl.packPoints(px, py, numPoints)

        GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uPoints"),
            VoronoiGlsl.MAX_POINTS, packed, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uPointCount"), numPoints)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uMetric"), metricId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDepth"), depth)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(programId, "uLight"), lx, ly, lz)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(programId, "uHalf"), hx, hy, hz)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAmbient"), ambient)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSpecular"), specular)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uShininess"), shininess)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBorderWidth"), borderWidth)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uColorMode"), colorModeId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uInvAvgRadius"), 1f / avgRadius)
    }

    override fun fragmentShaderSource(): String = """#version 300 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform vec3 uAudio;
        ${PaletteUniform.GLSL_HELPERS}
        ${VoronoiGlsl.GLSL_HELPERS}

        uniform float uDepth;
        uniform vec3 uLight;
        uniform vec3 uHalf;
        uniform float uAmbient;
        uniform float uSpecular;
        uniform float uShininess;
        uniform float uBorderWidth;
        uniform int uColorMode;   // 0 index, 1 position, 2 normal-z
        uniform float uInvAvgRadius;

        out vec4 fragColor;

        void main() {
            vec2 p = gl_FragCoord.xy;
            vec4 f = voronoiF1F2(p);
            float f1Lin = (uMetric == 0) ? sqrt(f.x) : f.x;
            float f2Lin = (uMetric == 0) ? sqrt(f.y) : f.y;

            if (uBorderWidth > 0.0 && (f2Lin - f1Lin) < uBorderWidth * 2.0) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
            }

            int idx = int(f.z + 0.5);
            vec2 sp = uPoints[idx];

            // Surface normal: euclidean distance to seed gives nx/ny
            vec2 toCentre = sp - p;
            float dist = length(toCentre);
            vec2 nxy = (dist > 0.001) ? toCentre / dist : vec2(0.0);
            float edgeFactor = clamp(1.0 - dist * uInvAvgRadius, 0.0, 1.0);
            float ns = edgeFactor * uDepth;
            vec3 n = vec3(nxy * ns, 1.0 - 0.5 * ns * ns);

            float diffuse = clamp(dot(n, uLight), 0.0, 1.0);
            float specDot = clamp(dot(n, uHalf), 0.0, 1.0);
            float specTerm = uSpecular * pow(specDot, uShininess);
            float shading = clamp(uAmbient + (1.0 - uAmbient) * diffuse, 0.0, 1.0);

            vec3 baseCol;
            if (uColorMode == 1) {
                float t = clamp((sp.x / uResolution.x + sp.y / uResolution.y) * 0.5, 0.0, 1.0);
                baseCol = palette_color(t);
            } else if (uColorMode == 2) {
                baseCol = palette_color(clamp(n.z, 0.0, 1.0));
            } else {
                baseCol = palette_color(mod(float(idx), 5.0) / 4.0);
            }

            vec3 col = clamp(baseCol * shading + vec3(specTerm), 0.0, 1.0);
            fragColor = vec4(col, 1.0);
        }
    """
}
