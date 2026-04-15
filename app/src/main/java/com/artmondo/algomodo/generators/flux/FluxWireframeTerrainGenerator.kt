package com.artmondo.algomodo.generators.flux

import android.graphics.*
import com.artmondo.algomodo.audio.AudioAnalysis
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.core.rng.SimplexNoise
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.*

/**
 * Synthwave 80s terrain -- perspective-projected 3D grid with ridged noise,
 * retro sun, star field, and neon glow scanlines.
 *
 * Multiple terrain noise modes (rolling FBM, ridged abs-noise, volcanic craters,
 * plateaus). Scene layers: sky gradient, twinkling star field, distant mountain
 * silhouette, synthwave sun with horizontal slices. Pre-computed Float arrays for
 * heights and projected screen coords. Back-to-front scanline rendering.
 */
class FluxWireframeTerrainGenerator : Generator {

    override val id = "flux-wireframe-terrain"
    override val family = "flux"
    override val styleName = "Wireframe Terrain"
    override val definition =
        "Synthwave 80s terrain \u2014 perspective-projected 3D grid with ridged noise, " +
        "retro sun, star field, and neon glow scanlines"
    override val algorithmNotes =
        "Multiple terrain noise modes (rolling FBM, ridged abs-noise, volcanic craters, plateaus). " +
        "Scene layers: sky gradient, twinkling star field, distant mountain silhouette, synthwave sun with horizontal slices. " +
        "Pre-computed Float arrays for heights and projected screen coords. Back-to-front scanline rendering."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema: List<Parameter> = listOf(
        Parameter.SelectParam(
            "Terrain", "terrainType", ParamGroup.COMPOSITION,
            "rolling: smooth hills | ridged: sharp peaks | volcanic: crater-like | plateaus: flat-top mesas",
            listOf("rolling", "ridged", "volcanic", "plateaus"), "ridged"
        ),
        Parameter.SelectParam(
            "Scene", "scene", ParamGroup.COMPOSITION,
            "synthwave: retro sun + sky gradient | starfield: stars + nebula | void: minimal dark | neon: bright grid glow",
            listOf("synthwave", "starfield", "void", "neon"), "synthwave"
        ),
        Parameter.SelectParam(
            "Render", "scanlineMode", ParamGroup.COMPOSITION,
            "wireframe: stroke grid lines | filled: solid polygon rows | glow: neon wireframe with bloom",
            listOf("wireframe", "filled", "glow"), "glow"
        ),
        Parameter.SelectParam(
            "Color Mode", "colorMode", ParamGroup.COLOR,
            null,
            listOf("height", "depth", "palette-gradient"), "height"
        ),
        Parameter.NumberParam(
            "Grid Width", "gridWidth", ParamGroup.GEOMETRY, null,
            20f, 100f, 5f, 60f
        ),
        Parameter.NumberParam(
            "Grid Depth", "gridDepth", ParamGroup.GEOMETRY, null,
            20f, 80f, 5f, 50f
        ),
        Parameter.NumberParam(
            "Noise Scale", "noiseScale", ParamGroup.GEOMETRY, null,
            0.5f, 4f, 0.1f, 1.5f
        ),
        Parameter.NumberParam(
            "Height Scale", "heightScale", ParamGroup.GEOMETRY, null,
            0.2f, 2f, 0.1f, 0.8f
        ),
        Parameter.NumberParam(
            "Perspective", "perspective", ParamGroup.GEOMETRY, null,
            0.5f, 3f, 0.1f, 1.5f
        ),
        Parameter.NumberParam(
            "Speed", "speed", ParamGroup.FLOW_MOTION, null,
            0.1f, 3f, 0.05f, 0.4f
        ),
        Parameter.NumberParam(
            "Audio Reactivity", "reactivity", ParamGroup.FLOW_MOTION,
            "Sensitivity to audio input (0 = none)",
            0f, 2f, 0.1f, 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "terrainType" to "ridged",
        "scene" to "synthwave",
        "scanlineMode" to "glow",
        "colorMode" to "height",
        "gridWidth" to 60f,
        "gridDepth" to 50f,
        "noiseScale" to 1.5f,
        "heightScale" to 0.8f,
        "perspective" to 1.5f,
        "speed" to 0.4f,
        "reactivity" to 1.0f
    )

    // ── Pre-allocated grid buffers ───────────────────────────────────────
    @Volatile private var _heights: FloatArray? = null
    @Volatile private var _screenX: FloatArray? = null
    @Volatile private var _screenY: FloatArray? = null
    @Volatile private var _lastGridSize = 0

    // ── Cached star positions ────────────────────────────────────────────
    @Volatile private var _starX: FloatArray? = null
    @Volatile private var _starY: FloatArray? = null
    @Volatile private var _starBright: FloatArray? = null
    @Volatile private var _starPhase: FloatArray? = null
    @Volatile private var _starSeed = -1

    // ── Cached mountain silhouette ───────────────────────────────────────
    @Volatile private var _mtHeights: FloatArray? = null
    @Volatile private var _mtSeed = -1

    // ── Cached eruption points ───────────────────────────────────────────
    @Volatile private var _eruptX: FloatArray? = null
    @Volatile private var _eruptZ: FloatArray? = null
    @Volatile private var _eruptPhase: FloatArray? = null
    @Volatile private var _eruptStrength: FloatArray? = null
    @Volatile private var _eruptSeed = -1

    // ── Shooting star state ──────────────────────────────────────────────
    @Volatile private var _shootX: FloatArray? = null
    @Volatile private var _shootY: FloatArray? = null
    @Volatile private var _shootDX: FloatArray? = null
    @Volatile private var _shootDY: FloatArray? = null
    @Volatile private var _shootPhase: FloatArray? = null
    @Volatile private var _shootLen: FloatArray? = null
    @Volatile private var _shootSeed = -1

    private fun ensureGridBuffers(size: Int) {
        if (_lastGridSize != size) {
            _heights = FloatArray(size)
            _screenX = FloatArray(size)
            _screenY = FloatArray(size)
            _lastGridSize = size
        }
    }

    private fun ensureStars(seed: Int, w: Int, h: Int, horizonY: Float) {
        if (_starSeed == seed) return
        _starSeed = seed
        val rng = SeededRNG(seed + 7777)
        val sx = FloatArray(STAR_COUNT)
        val sy = FloatArray(STAR_COUNT)
        val sb = FloatArray(STAR_COUNT)
        val sp = FloatArray(STAR_COUNT)
        for (i in 0 until STAR_COUNT) {
            sx[i] = rng.random() * w
            sy[i] = rng.random() * horizonY
            sb[i] = rng.range(0.3f, 1.0f)
            sp[i] = rng.range(0f, TAU)
        }
        _starX = sx; _starY = sy; _starBright = sb; _starPhase = sp
    }

    private fun ensureMountains(seed: Int) {
        if (_mtSeed == seed) return
        _mtSeed = seed
        val noise = SimplexNoise(seed + 3333)
        val mh = FloatArray(MT_POINTS)
        for (i in 0 until MT_POINTS) {
            val x = i.toFloat() / MT_POINTS
            mh[i] = noise.fbm(x * 3f, 0f, 3, 2f, 0.5f) * 0.5f + 0.3f
        }
        _mtHeights = mh
    }

    private fun ensureEruptions(seed: Int) {
        if (_eruptSeed == seed) return
        _eruptSeed = seed
        val rng = SeededRNG(seed + 5555)
        val ex = FloatArray(MAX_ERUPTIONS)
        val ez = FloatArray(MAX_ERUPTIONS)
        val ep = FloatArray(MAX_ERUPTIONS)
        val es = FloatArray(MAX_ERUPTIONS)
        for (i in 0 until MAX_ERUPTIONS) {
            ex[i] = rng.range(0.15f, 0.85f)
            ez[i] = rng.range(0.1f, 0.8f)
            ep[i] = rng.range(0f, TAU)
            es[i] = rng.range(0.4f, 1.0f)
        }
        _eruptX = ex; _eruptZ = ez; _eruptPhase = ep; _eruptStrength = es
    }

    private fun ensureShooters(seed: Int, w: Int, horizonY: Float) {
        if (_shootSeed == seed) return
        _shootSeed = seed
        val rng = SeededRNG(seed + 9999)
        val shX = FloatArray(MAX_SHOOTERS)
        val shY = FloatArray(MAX_SHOOTERS)
        val shDX = FloatArray(MAX_SHOOTERS)
        val shDY = FloatArray(MAX_SHOOTERS)
        val shP = FloatArray(MAX_SHOOTERS)
        val shL = FloatArray(MAX_SHOOTERS)
        for (i in 0 until MAX_SHOOTERS) {
            shX[i] = rng.range(w * 0.1f, w * 0.9f)
            shY[i] = rng.range(horizonY * 0.05f, horizonY * 0.6f)
            val angle = rng.range(0.3f, 1.0f) // downward-right
            shDX[i] = cos(angle) * w * 0.3f
            shDY[i] = sin(angle) * horizonY * 0.2f
            shP[i] = rng.range(0f, 20f) // staggered appearance
            shL[i] = rng.range(40f, 100f)
        }
        _shootX = shX; _shootY = shY; _shootDX = shDX; _shootDY = shDY
        _shootPhase = shP; _shootLen = shL
    }

    // ── Palette interpolation helper ─────────────────────────────────────
    private fun lerpPaletteRgb(rawColors: Array<IntArray>, t01: Float): IntArray {
        val n = rawColors.size
        val ci = t01.coerceIn(0f, 1f) * (n - 1)
        val i0 = ci.toInt()
        val i1 = if (i0 < n - 1) i0 + 1 else n - 1
        val f = ci - i0
        return intArrayOf(
            (rawColors[i0][0] + (rawColors[i1][0] - rawColors[i0][0]) * f).toInt(),
            (rawColors[i0][1] + (rawColors[i1][1] - rawColors[i0][1]) * f).toInt(),
            (rawColors[i0][2] + (rawColors[i1][2] - rawColors[i0][2]) * f).toInt()
        )
    }

    override fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float
    ) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val wI = bitmap.width
        val hI = bitmap.height

        // ── Extract parameters ───────────────────────────────────────────
        val terrainType = (params["terrainType"] as? String) ?: "ridged"
        val scene = (params["scene"] as? String) ?: "synthwave"
        val gridWidth = ((params["gridWidth"] as? Number)?.toInt() ?: 60).coerceAtLeast(5)
        val gridDepth = ((params["gridDepth"] as? Number)?.toInt() ?: 50).coerceAtLeast(5)
        val noiseScaleParam = (params["noiseScale"] as? Number)?.toFloat() ?: 1.5f
        val heightScaleParam = (params["heightScale"] as? Number)?.toFloat() ?: 0.8f
        val perspectiveVal = (params["perspective"] as? Number)?.toFloat() ?: 1.5f
        val scanlineMode = (params["scanlineMode"] as? String) ?: "glow"
        val colorMode = (params["colorMode"] as? String) ?: "height"
        val spd = (params["speed"] as? Number)?.toFloat() ?: 0.4f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio ────────────────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx
        val audioEnergy = ((audioAnalysis?.getBass(time) ?: 0f) +
                (audioAnalysis?.getMid(time) ?: 0f) +
                (audioAnalysis?.getHigh(time) ?: 0f)) / 3f * rx

        val heightScale = heightScaleParam * (1f + audioBass * 1.5f)
        val noiseScale = noiseScaleParam * (1f + audioMid * 0.8f)

        val t = time * spd
        val horizonY = h * 0.42f

        // ── Parse palette to RGB arrays ──────────────────────────────────
        val colorInts = palette.colorInts()
        val nColors = colorInts.size
        val rawColors = Array(nColors) { i ->
            val c = colorInts[i]
            intArrayOf(Color.red(c), Color.green(c), Color.blue(c))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()

        // ── 1. Sky background ────────────────────────────────────────────
        when (scene) {
            "synthwave" -> {
                // Deep purple -> dark magenta -> dark at top
                paint.shader = LinearGradient(
                    0f, 0f, 0f, horizonY,
                    intArrayOf(0xFF0A0010.toInt(), 0xFF1A0030.toInt(), 0xFF3D0060.toInt(), 0xFF200040.toInt()),
                    floatArrayOf(0f, 0.5f, 0.85f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, horizonY, paint)
                paint.shader = null
                // Ground area -- dark
                paint.color = 0xFF050008.toInt()
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, horizonY, w, h, paint)
            }
            "starfield" -> {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, horizonY,
                    intArrayOf(0xFF000008.toInt(), 0xFF050015.toInt(), 0xFF0A0020.toInt()),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, horizonY, paint)
                paint.shader = null
                paint.color = 0xFF030008.toInt()
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, horizonY, w, h, paint)
            }
            "neon" -> {
                canvas.drawColor(Color.BLACK)
            }
            else -> {
                // void
                paint.color = 0xFF020204.toInt()
                paint.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, w, h, paint)
            }
        }

        // ── 2. Stars ─────────────────────────────────────────────────────
        if (scene == "starfield" || scene == "synthwave") {
            ensureStars(seed, wI, hI, horizonY)
            val starX = _starX!!
            val starY = _starY!!
            val starBright = _starBright!!
            val starPhase = _starPhase!!
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            for (i in 0 until STAR_COUNT) {
                val twinkle = 0.5f + 0.5f * sin(t * 2f + starPhase[i])
                val bright = starBright[i] * twinkle
                val sz = bright * 1.5f
                paint.alpha = (bright * 255f).toInt().coerceIn(0, 255)
                canvas.drawRect(
                    starX[i] - sz * 0.5f, starY[i] - sz * 0.5f,
                    starX[i] + sz * 0.5f, starY[i] + sz * 0.5f, paint
                )
            }
            paint.alpha = 255
        }

        // ── 2b. Shooting stars ───────────────────────────────────────────
        if (scene == "starfield" || scene == "synthwave") {
            ensureShooters(seed, wI, horizonY)
            val shootX = _shootX!!
            val shootY = _shootY!!
            val shootDX = _shootDX!!
            val shootDY = _shootDY!!
            val shootPhase = _shootPhase!!
            val shootLen = _shootLen!!

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND

            for (i in 0 until MAX_SHOOTERS) {
                // Each shooter appears for ~0.4s every ~8s (staggered by phase)
                val cycle = 8f
                val localT = ((t * 1.5f + shootPhase[i]) % cycle)
                if (localT > 0.5f) continue // not visible
                val progress = localT / 0.5f // 0->1 over the streak duration
                val alpha = if (progress < 0.3f) progress / 0.3f else 1f - (progress - 0.3f) / 0.7f
                val headX = shootX[i] + shootDX[i] * progress
                val headY = shootY[i] + shootDY[i] * progress
                val tailLen = shootLen[i] * (1f - progress * 0.5f)

                val tailNormX = shootDX[i] * tailLen / (w * 0.3f)
                val tailNormY = shootDY[i] * tailLen / (horizonY * 0.2f)

                paint.shader = LinearGradient(
                    headX, headY,
                    headX - shootDX[i] * 0.15f, headY - shootDY[i] * 0.15f,
                    Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                    Shader.TileMode.CLAMP
                )
                paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = 1.5f

                canvas.drawLine(headX, headY, headX - tailNormX, headY - tailNormY, paint)
            }
            paint.shader = null
            paint.alpha = 255
        }

        // ── 3. Synthwave sun ─────────────────────────────────────────────
        if (scene == "synthwave") {
            val sunR = min(w, h) * 0.18f
            val sunX = w * 0.5f
            val sunY = horizonY

            // Sun body -- gradient from hot to deep, using last palette color
            val sc = rawColors[nColors - 1]
            paint.shader = RadialGradient(
                sunX, sunY, sunR,
                intArrayOf(
                    Color.rgb(min(255, sc[0] + 80), min(255, sc[1] + 60), sc[2]),
                    Color.rgb(sc[0], sc[1], sc[2]),
                    Color.argb(204, sc[0], sc[1] shr 1, sc[2]),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL

            // Clip to upper semicircle
            canvas.save()
            canvas.clipRect(0f, 0f, w, sunY)

            // Draw sun circle
            canvas.drawCircle(sunX, sunY, sunR, paint)
            paint.shader = null

            // Horizontal slice lines through the sun (synthwave signature)
            // Use CLEAR xfermode to cut slices
            val clearPaint = Paint()
            clearPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            clearPaint.style = Paint.Style.FILL

            // We need a layer to use CLEAR mode properly
            canvas.saveLayer(sunX - sunR, sunY - sunR, sunX + sunR, sunY, null)
            // Redraw the sun in this layer
            paint.shader = RadialGradient(
                sunX, sunY, sunR,
                intArrayOf(
                    Color.rgb(min(255, sc[0] + 80), min(255, sc[1] + 60), sc[2]),
                    Color.rgb(sc[0], sc[1], sc[2]),
                    Color.argb(204, sc[0], sc[1] shr 1, sc[2]),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.4f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(sunX, sunY, sunR, paint)
            paint.shader = null

            val sliceCount = 8
            for (s in 1..sliceCount) {
                val sliceY = sunY - sunR * (s.toFloat() / (sliceCount + 1))
                val sliceH = 1f + s * 0.8f // thicker slices towards bottom
                canvas.drawRect(
                    sunX - sunR, sliceY - sliceH * 0.5f,
                    sunX + sunR, sliceY + sliceH * 0.5f,
                    clearPaint
                )
            }
            canvas.restore() // layer
            canvas.restore() // clip

            // Sun glow / halo
            val haloAlpha = (0.15f + audioEnergy * 0.1f).coerceIn(0f, 1f)
            paint.alpha = (haloAlpha * 255f).toInt().coerceIn(0, 255)
            paint.shader = RadialGradient(
                sunX, sunY, sunR * 2.5f,
                intArrayOf(
                    Color.argb(102, sc[0], sc[1], sc[2]),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(sunR * 0.8f / (sunR * 2.5f), 1f),
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            canvas.drawRect(sunX - sunR * 2.5f, sunY - sunR * 2.5f, sunX + sunR * 2.5f, sunY, paint)
            paint.shader = null
            paint.alpha = 255
        }

        // ── 4. Distant mountain silhouette ───────────────────────────────
        if (scene == "synthwave" || scene == "starfield") {
            ensureMountains(seed)
            val mtHeights = _mtHeights!!
            val mtColor = if (scene == "synthwave")
                Color.argb(230, 30, 0, 50) else Color.argb(230, 10, 10, 30)

            paint.color = mtColor
            paint.style = Paint.Style.FILL
            path.reset()
            path.moveTo(0f, horizonY)
            for (i in 0 until MT_POINTS) {
                val x = (i.toFloat() / (MT_POINTS - 1)) * w
                val mtH = mtHeights[i] * h * 0.12f
                path.lineTo(x, horizonY - mtH)
            }
            path.lineTo(w, horizonY)
            path.close()
            canvas.drawPath(path, paint)
        }

        // ── 5. Horizon glow line ─────────────────────────────────────────
        if (scene != "void") {
            val hlc = rawColors[min(1, nColors - 1)]
            val glowAlpha = (0.5f + audioEnergy * 0.3f).coerceIn(0f, 1f)
            paint.shader = LinearGradient(
                0f, horizonY - 3f, 0f, horizonY + 8f,
                intArrayOf(
                    Color.argb(0, 0, 0, 0),
                    Color.argb((glowAlpha * 255).toInt(), hlc[0], hlc[1], hlc[2]),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, horizonY - 3f, w, horizonY + 8f, paint)
            paint.shader = null
        }

        // ── 6. Compute terrain heights ───────────────────────────────────
        val simplex = SimplexNoise(seed)
        val terrainId = when (terrainType) {
            "rolling" -> 0; "ridged" -> 1; "volcanic" -> 2; else -> 3
        }

        val totalPoints = gridWidth * gridDepth
        ensureGridBuffers(totalPoints)
        val heights = _heights!!
        val screenXArr = _screenX!!
        val screenYArr = _screenY!!

        var minH = Float.MAX_VALUE
        var maxH = -Float.MAX_VALUE

        // Pre-compute eruption state for this frame
        ensureEruptions(seed)
        val eruptX = _eruptX!!
        val eruptZ = _eruptZ!!
        val eruptPhase = _eruptPhase!!
        val eruptStrength = _eruptStrength!!
        val eruptActive = FloatArray(MAX_ERUPTIONS)
        for (e in 0 until MAX_ERUPTIONS) {
            // Each eruption pulses on a ~5s cycle, active for ~1.5s
            val cycle = (t * 0.8f + eruptPhase[e]) % 5f
            eruptActive[e] = if (cycle < 1.5f) sin(cycle / 1.5f * PI.toFloat()) * eruptStrength[e] else 0f
        }

        // Energy pulse wave -- sweeps from horizon to camera every ~4s
        val pulseCycle = 4f
        val pulseProgress = (t % pulseCycle) / pulseCycle // 0 at horizon, 1 at camera
        val pulseWidth = 0.08f // width of the bright band in depth-space

        for (gz in 0 until gridDepth) {
            val rowOffset = gz * gridWidth
            val gzNorm = gz.toFloat() / (gridDepth - 1)

            // Energy pulse height boost for this row
            val pulseDist = abs(gzNorm - (1f - pulseProgress))
            val pulseBoost = if (pulseDist < pulseWidth)
                (1f - pulseDist / pulseWidth) * 0.35f * heightScale else 0f

            for (gx in 0 until gridWidth) {
                val nx = gx * noiseScale / gridWidth
                val nz = gz * noiseScale / gridDepth + t * 0.12f

                var heightVal: Float = when (terrainId) {
                    0 -> {
                        // Rolling: smooth FBM hills
                        simplex.fbm(nx, nz, 2, 2f, 0.5f) * heightScale
                    }
                    1 -> {
                        // Ridged: sharp peaks
                        val n1 = abs(simplex.noise2D(nx * 1.5f, nz * 1.5f))
                        val n2 = abs(simplex.noise2D(nx * 3f, nz * 3f)) * 0.5f
                        (1f - n1 - n2 * 0.5f) * heightScale
                    }
                    2 -> {
                        // Volcanic: crater-like
                        val base = simplex.fbm(nx, nz, 2, 2f, 0.5f)
                        val crater = simplex.noise2D(nx * 0.8f, nz * 0.8f)
                        val rim = exp((-crater * crater * 4f).toDouble()).toFloat() * 0.6f
                        (base * 0.5f + rim) * heightScale
                    }
                    else -> {
                        // Plateaus: flat-top mesas
                        val raw = simplex.fbm(nx, nz, 2, 2f, 0.5f)
                        val steps = 5f
                        (Math.round(raw * steps) / steps) * heightScale
                    }
                }

                // Eruption spikes -- localized height bursts
                val gxNorm = gx.toFloat() / (gridWidth - 1)
                for (e in 0 until MAX_ERUPTIONS) {
                    if (eruptActive[e] <= 0f) continue
                    val dx = gxNorm - eruptX[e]
                    val dz = gzNorm - eruptZ[e]
                    val d2 = dx * dx + dz * dz
                    if (d2 < 0.02f) {
                        heightVal += eruptActive[e] * heightScale * 1.2f * exp((-d2 * 80f).toDouble()).toFloat()
                    }
                }

                // Energy pulse wave -- lifts terrain in a band
                heightVal += pulseBoost

                // Audio bass creates ripple through terrain
                if (audioBass > 0.05f) {
                    val dist = abs(gzNorm - 0.3f)
                    heightVal += audioBass * 0.3f * exp((-dist * dist * 20f).toDouble()).toFloat()
                }

                heights[rowOffset + gx] = heightVal
                if (heightVal < minH) minH = heightVal
                if (heightVal > maxH) maxH = heightVal
            }
        }

        val heightRange = maxH - minH
        val invHeightRange = if (heightRange > 0.001f) 1f / heightRange else 1f

        // ── 7. Project to screen ─────────────────────────────────────────
        // Camera sways and bobs
        val camOffX = sin(t * 0.3f) * 0.03f + sin(t * 0.7f) * 0.01f
        val camTilt = sin(t * 0.2f) * 0.015f
        // Zoom pulse synced to eruptions -- subtle forward surge
        val zoomPulse = 1f + sin(t * 0.5f) * 0.02f

        for (gz in 0 until gridDepth) {
            val rowOffset = gz * gridWidth
            val gzNorm = gz.toFloat() / (gridDepth - 1)

            // Glitch: rows near the energy pulse wave get horizontal displacement
            val pulseDist = abs(gzNorm - (1f - pulseProgress))
            val glitchShift = if (pulseDist < pulseWidth * 1.5f)
                sin(gz * 17f + t * 30f) * 0.02f * (1f - pulseDist / (pulseWidth * 1.5f)) else 0f

            for (gx in 0 until gridWidth) {
                val idx = rowOffset + gx
                val worldX = (gx.toFloat() / (gridWidth - 1) - 0.5f) * 2f + camOffX + glitchShift
                val worldZ = (gz.toFloat() / (gridDepth - 1)) * 2f
                val worldY = heights[idx] + camTilt * worldZ

                val pv = perspectiveVal * zoomPulse
                val denom = worldZ + pv
                val invDenom = 1f / denom
                screenXArr[idx] = w * 0.5f + worldX * pv * w * invDenom
                screenYArr[idx] = horizonY + (worldZ * 0.5f - worldY) * pv * h * 0.5f * invDenom
            }
        }

        // ── 8. Render terrain scanlines ──────────────────────────────────
        val baseLineWidth = when (quality) {
            Quality.DRAFT -> 1f; Quality.ULTRA -> 2f; else -> 1.5f
        }
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        val isGlow = scanlineMode == "glow"
        val isNeon = scene == "neon"

        // Android Canvas does not have shadowBlur like HTML Canvas.
        // We simulate glow with BlurMaskFilter for glow mode.
        val glowFilter = if (isGlow) {
            val blur = when (quality) {
                Quality.DRAFT -> 8f; Quality.ULTRA -> 20f; else -> 14f
            }
            BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        } else null

        // Pre-build palette color cache for height-mapped rows (64 entries)
        val palCache = IntArray(64)
        for (i in 0 until 64) {
            val t01 = i / 63f
            val rgb = lerpPaletteRgb(rawColors, t01)
            palCache[i] = Color.rgb(rgb[0], rgb[1], rgb[2])
        }

        // Helper to get a color from height with optional alpha
        fun palFromHeight(ht: Float, alpha: Float): Int {
            val rgb = lerpPaletteRgb(rawColors, ht)
            return Color.argb(
                (alpha * 255f).toInt().coerceIn(0, 255),
                rgb[0], rgb[1], rgb[2]
            )
        }

        fun palCacheFromHeight(ht: Float): Int {
            return palCache[((ht * 63f + 0.5f).toInt()).coerceIn(0, 63)]
        }

        // ── Render back-to-front ─────────────────────────────────────────
        for (gz in gridDepth - 1 downTo 0) {
            val rowOffset = gz * gridWidth
            val depthT = gz.toFloat() / (gridDepth - 1) // 0=closest, 1=farthest
            val fogAlpha = 0.1f + 0.9f * (1f - depthT) * (1f - depthT)

            // Neon mode: closer rows glow brighter
            val neonBoost = if (isNeon) (1f - depthT) * 0.4f else 0f

            val rowColor: Int
            val rowColorOpaque: Int

            when (colorMode) {
                "depth" -> {
                    rowColor = palFromHeight(1f - depthT, fogAlpha)
                    rowColorOpaque = palCacheFromHeight(1f - depthT)
                }
                "palette-gradient" -> {
                    val palT = (gz % (nColors * 3)).toFloat() / (nColors * 3)
                    rowColor = palFromHeight(palT, fogAlpha)
                    rowColorOpaque = palCacheFromHeight(palT)
                }
                else -> {
                    // height mode -- row color used as fallback
                    rowColor = palFromHeight(0.5f, fogAlpha)
                    rowColorOpaque = palCache[32]
                }
            }

            if (scanlineMode == "filled") {
                // ── Filled mode ──────────────────────────────────────────
                if (gz < gridDepth - 1) {
                    val nextRowOffset = (gz + 1) * gridWidth
                    paint.style = Paint.Style.FILL
                    paint.maskFilter = null

                    for (gx in 0 until gridWidth - 1) {
                        val idx0 = rowOffset + gx
                        val idx1 = rowOffset + gx + 1
                        val idx2 = nextRowOffset + gx + 1
                        val idx3 = nextRowOffset + gx

                        val quadColor: Int = if (colorMode == "height") {
                            val avgH = (heights[idx0] + heights[idx1] + heights[idx2] + heights[idx3]) * 0.25f
                            val ht = (avgH - minH) * invHeightRange
                            palFromHeight(ht, fogAlpha)
                        } else {
                            rowColor
                        }

                        paint.color = quadColor
                        path.reset()
                        path.moveTo(screenXArr[idx0], screenYArr[idx0])
                        path.lineTo(screenXArr[idx1], screenYArr[idx1])
                        path.lineTo(screenXArr[idx2], screenYArr[idx2])
                        path.lineTo(screenXArr[idx3], screenYArr[idx3])
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                }

                // Scanline stroke on top
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = baseLineWidth * 0.6f
                paint.color = rowColor
                paint.maskFilter = null
                path.reset()
                path.moveTo(screenXArr[rowOffset], screenYArr[rowOffset])
                for (gx in 1 until gridWidth) {
                    path.lineTo(screenXArr[rowOffset + gx], screenYArr[rowOffset + gx])
                }
                canvas.drawPath(path, paint)

            } else {
                // ── Wireframe / Glow mode ────────────────────────────────
                val lw = baseLineWidth * (1f + neonBoost)
                paint.style = Paint.Style.STROKE
                paint.maskFilter = glowFilter

                if (colorMode == "height") {
                    // Batch consecutive segments of the same color bucket
                    var prevBucket = -1

                    for (gx in 0 until gridWidth) {
                        val idx = rowOffset + gx
                        val ht = (heights[idx] - minH) * invHeightRange
                        val bucket = ((ht * 63f + 0.5f).toInt()).coerceIn(0, 63)

                        if (bucket != prevBucket) {
                            // Finish previous batch
                            if (prevBucket >= 0) {
                                canvas.drawPath(path, paint)
                            }
                            // Start new batch
                            val segColor = palFromHeight(ht, fogAlpha)
                            paint.color = segColor
                            // Peak glow: high peaks get thicker lines
                            paint.strokeWidth = lw * (0.8f + ht * 0.6f)
                            path.reset()
                            path.moveTo(screenXArr[idx], screenYArr[idx])
                            prevBucket = bucket
                        } else {
                            path.lineTo(screenXArr[idx], screenYArr[idx])
                        }
                    }
                    if (prevBucket >= 0) {
                        canvas.drawPath(path, paint)
                    }
                } else {
                    paint.color = rowColor
                    paint.strokeWidth = lw
                    path.reset()
                    path.moveTo(screenXArr[rowOffset], screenYArr[rowOffset])
                    for (gx in 1 until gridWidth) {
                        path.lineTo(screenXArr[rowOffset + gx], screenYArr[rowOffset + gx])
                    }
                    canvas.drawPath(path, paint)
                }

                // Vertical connectors
                if (gz > 0) {
                    val prevRowOffset = (gz - 1) * gridWidth
                    val vertStep = when (quality) {
                        Quality.DRAFT -> 5; Quality.ULTRA -> 2; else -> 3
                    }
                    paint.strokeWidth = baseLineWidth * 0.4f
                    paint.alpha = (fogAlpha * 0.4f * 255f).toInt().coerceIn(0, 255)
                    paint.color = (paint.color and 0x00FFFFFF) or (paint.alpha shl 24)

                    path.reset()
                    var gx = 0
                    while (gx < gridWidth) {
                        val idxC = rowOffset + gx
                        val idxP = prevRowOffset + gx
                        path.moveTo(screenXArr[idxC], screenYArr[idxC])
                        path.lineTo(screenXArr[idxP], screenYArr[idxP])
                        gx += vertStep
                    }
                    canvas.drawPath(path, paint)
                    paint.alpha = 255
                }
            }
        }

        // Reset mask filter
        paint.maskFilter = null

        // ── 9. Energy pulse wave glow band ───────────────────────────────
        run {
            val pulseDepthZ = (1f - pulseProgress) * 2f // world Z
            val pv = perspectiveVal * zoomPulse
            val pulseDenom = pulseDepthZ + pv
            val pulseScreenY = horizonY + (pulseDepthZ * 0.5f) * pv * h * 0.5f / pulseDenom
            val bandH = h * 0.04f * (1f - pulseProgress * 0.5f) // thinner as it approaches

            val pulseAlpha = (0.25f + audioEnergy * 0.2f).coerceIn(0f, 1f)
            val pc = rawColors[min(2, nColors - 1)]

            paint.shader = LinearGradient(
                0f, pulseScreenY - bandH, 0f, pulseScreenY + bandH,
                intArrayOf(
                    Color.argb(0, 0, 0, 0),
                    Color.argb((pulseAlpha * 255).toInt(), pc[0], pc[1], pc[2]),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            // Use ADD blend mode for lighter compositing
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            canvas.drawRect(0f, pulseScreenY - bandH, w, pulseScreenY + bandH, paint)
            paint.xfermode = null
            paint.shader = null
        }

        // ── 9b. Eruption glow spots ──────────────────────────────────────
        for (e in 0 until MAX_ERUPTIONS) {
            if (eruptActive[e] <= 0.1f) continue
            // Project eruption center to screen
            val eWorldX = (eruptX[e] - 0.5f) * 2f
            val eWorldZ = eruptZ[e] * 2f
            val pv = perspectiveVal * zoomPulse
            val eDenom = eWorldZ + pv
            val eScreenX = w * 0.5f + eWorldX * pv * w / eDenom
            val eScreenY = horizonY + eWorldZ * 0.5f * pv * h * 0.5f / eDenom
            val glowR = (1f - eWorldZ * 0.3f) * w * 0.06f * eruptActive[e]

            val ec = rawColors[(e + 1) % nColors]
            paint.shader = RadialGradient(
                eScreenX, eScreenY, glowR.coerceAtLeast(1f),
                intArrayOf(
                    Color.argb((eruptActive[e] * 0.6f * 255f).toInt().coerceIn(0, 255), ec[0], ec[1], ec[2]),
                    Color.argb(0, 0, 0, 0)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            canvas.drawCircle(eScreenX, eScreenY, glowR.coerceAtLeast(1f), paint)
            paint.xfermode = null
            paint.shader = null
        }

        // ── 10. Ground plane glow near camera ────────────────────────────
        if (scene != "void") {
            val glowH = h * 0.08f
            val gc = rawColors[0]
            val glowAlpha = (0.15f + audioEnergy * 0.15f).coerceIn(0f, 1f)
            paint.shader = LinearGradient(
                0f, h - glowH, 0f, h,
                intArrayOf(
                    Color.argb(0, 0, 0, 0),
                    Color.argb((glowAlpha * 255).toInt(), gc[0], gc[1], gc[2])
                ),
                null,
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, h - glowH, w, h, paint)
            paint.shader = null
        }

        // ── 11. Audio energy flash ───────────────────────────────────────
        if (audioHigh > 0.3f) {
            val flashAlpha = ((audioHigh - 0.3f) * 0.2f).coerceIn(0f, 1f)
            paint.color = Color.WHITE
            paint.alpha = (flashAlpha * 255f).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.FILL
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.xfermode = null
            paint.alpha = 255
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val gw = (params["gridWidth"] as? Number)?.toFloat() ?: 60f
        val gd = (params["gridDepth"] as? Number)?.toFloat() ?: 50f
        val mode = (params["scanlineMode"] as? String) ?: "glow"
        val modeMultiplier = when (mode) {
            "filled" -> 1.5f; "glow" -> 1.3f; else -> 1.0f
        }
        // Normalize to 0..1 range: max case is 100*80*2*1.5+300 = 24300
        val raw = gw * gd * 2f * modeMultiplier + 300f
        return (raw / 24300f).coerceIn(0.1f, 1f)
    }

    companion object {
        private const val TAU = 6.2831855f // 2 * PI
        private const val STAR_COUNT = 200
        private const val MT_POINTS = 120
        private const val MAX_ERUPTIONS = 6
        private const val MAX_SHOOTERS = 4
    }
}
