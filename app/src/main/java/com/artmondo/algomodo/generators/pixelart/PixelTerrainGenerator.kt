package com.artmondo.algomodo.generators.pixelart

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class PixelTerrainGenerator : Generator {
    override val id = "pixel-terrain"
    override val family = "pixel-art"
    override val styleName = "Pixel Terrain"
    override val definition = "A heightmap generated via diamond-square algorithm at low resolution, with altitude bands mapped to palette colors — resembling an 8-bit RPG overworld."
    override val algorithmNotes = "Uses diamond-square (midpoint displacement) to create a heightmap on a (2^n+1) grid. Heights are quantized into altitude bands and mapped to palette colors: deep water, shallow water, sand, grass, forest, mountain, snow."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.NumberParam("Grid Size", "gridSize", ParamGroup.GEOMETRY, "Pixel grid resolution (internally rounds to 2^n+1 for diamond-square)", 32f, 128f, 8f, 64f),
        Parameter.NumberParam("Roughness", "roughness", ParamGroup.TEXTURE, "Terrain roughness (higher = more jagged)", 0.1f, 1f, 0.05f, 0.5f),
        Parameter.NumberParam("Sea Level", "seaLevel", ParamGroup.COMPOSITION, "Height below which is water", 0.1f, 0.7f, 0.05f, 0.35f),
        Parameter.NumberParam("Altitude Bands", "altitudeBands", ParamGroup.COLOR, "Number of discrete altitude color bands", 4f, 16f, 1f, 7f),
        Parameter.BooleanParam("Wrap Edges", "wrapEdges", ParamGroup.GEOMETRY, "Whether terrain wraps (toroidal)", false),
        Parameter.NumberParam("Anim Speed", "animSpeed", ParamGroup.FLOW_MOTION, "Animation speed for sea level oscillation", 0.1f, 3f, 0.1f, 1f),
        Parameter.NumberParam("Reactivity", "reactivity", ParamGroup.FLOW_MOTION, "Audio reactivity strength", 0f, 2f, 0.1f, 0f)
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "gridSize" to 64f, "roughness" to 0.5f, "seaLevel" to 0.35f, "altitudeBands" to 7f,
        "wrapEdges" to false, "animSpeed" to 1f, "reactivity" to 0f
    )

    companion object {
        @Volatile private var animKey: String = ""
        @Volatile private var heightmapCache: DoubleArray = DoubleArray(0)
        @Volatile private var mapSizeCache: Int = 0
    }

    private fun diamondSquare(size: Int, roughness: Float, rng: SeededRNG): DoubleArray {
        val map = DoubleArray(size * size)
        map[0] = rng.random().toDouble()
        map[size - 1] = rng.random().toDouble()
        map[(size - 1) * size] = rng.random().toDouble()
        map[(size - 1) * size + size - 1] = rng.random().toDouble()

        var step = size - 1
        var scale = roughness.toDouble()
        while (step > 1) {
            val half = step shr 1
            // Diamond
            var y = 0
            while (y < size - 1) {
                var x = 0
                while (x < size - 1) {
                    val tl = map[y * size + x]
                    val tr = map[y * size + x + step]
                    val bl = map[(y + step) * size + x]
                    val br = map[(y + step) * size + x + step]
                    val avg = (tl + tr + bl + br) * 0.25
                    map[(y + half) * size + x + half] = avg + (rng.random() - 0.5) * scale
                    x += step
                }
                y += step
            }
            // Square
            y = 0
            while (y < size) {
                var x = if (((y / half) % 2) == 0) half else 0
                while (x < size) {
                    var sum = 0.0; var count = 0
                    if (y >= half) { sum += map[(y - half) * size + x]; count++ }
                    if (y + half < size) { sum += map[(y + half) * size + x]; count++ }
                    if (x >= half) { sum += map[y * size + x - half]; count++ }
                    if (x + half < size) { sum += map[y * size + x + half]; count++ }
                    map[y * size + x] = sum / count + (rng.random() - 0.5) * scale
                    x += step
                }
                y += half
            }
            step = half
            scale *= 0.5
        }

        var mn = Double.POSITIVE_INFINITY; var mx = Double.NEGATIVE_INFINITY
        for (i in 0 until size * size) {
            if (map[i] < mn) mn = map[i]
            if (map[i] > mx) mx = map[i]
        }
        val range = if (mx - mn == 0.0) 1.0 else mx - mn
        for (i in 0 until size * size) map[i] = (map[i] - mn) / range
        return map
    }

    private fun renderTerrain(
        pixels: IntArray, heightmap: DoubleArray, mapSize: Int,
        renderSize: Int, seaLevel: Float, bands: Int, colors: Array<IntArray>
    ) {
        val nc = colors.size
        for (y in 0 until renderSize) {
            for (x in 0 until renderSize) {
                val mx = min(mapSize - 1, (x.toFloat() / renderSize * mapSize).toInt())
                val my = min(mapSize - 1, (y.toFloat() / renderSize * mapSize).toInt())
                val h = heightmap[my * mapSize + mx].toFloat()
                val idx = y * renderSize + x
                val r: Int; val g: Int; val b: Int
                if (h < seaLevel) {
                    val waterT = h / seaLevel
                    val wBand = min(1, (waterT * 2f).toInt())
                    val wci = wBand.toFloat() * min(1, nc - 1)
                    val wi0 = wci.toInt()
                    val wi1 = min(nc - 1, wi0 + 1)
                    val wf = wci - wi0
                    r = (colors[wi0][0] + (colors[wi1][0] - colors[wi0][0]) * wf).toInt()
                    g = (colors[wi0][1] + (colors[wi1][1] - colors[wi0][1]) * wf).toInt()
                    b = (colors[wi0][2] + (colors[wi1][2] - colors[wi0][2]) * wf).toInt()
                } else {
                    val landH = (h - seaLevel) / (1f - seaLevel)
                    val landBand = min(bands - 1, (landH * (bands - 2) + 2).toInt())
                    val lt = landBand.toFloat() / max(1, bands - 1)
                    val lci = lt * (nc - 1)
                    val li0 = lci.toInt()
                    val li1 = min(nc - 1, li0 + 1)
                    val lf = lci - li0
                    r = (colors[li0][0] + (colors[li1][0] - colors[li0][0]) * lf).toInt()
                    g = (colors[li0][1] + (colors[li1][1] - colors[li0][1]) * lf).toInt()
                    b = (colors[li0][2] + (colors[li1][2] - colors[li0][2]) * lf).toInt()
                }
                pixels[idx] = PixelArtUtil.rgb(r, g, b)
            }
        }
    }

    override fun renderCanvas(
        canvas: Canvas, bitmap: Bitmap, params: Map<String, Any>, seed: Int,
        palette: Palette, quality: Quality, time: Float
    ) {
        val gridSize = PixelArtUtil.pi(params, "gridSize", 64).coerceIn(16, 128)
        val roughness = PixelArtUtil.pf(params, "roughness", 0.5f)
        val seaLevel = PixelArtUtil.pf(params, "seaLevel", 0.35f)
        val bands = PixelArtUtil.pi(params, "altitudeBands", 7).coerceIn(2, 16)
        val speed = PixelArtUtil.pf(params, "animSpeed", 1f)

        var pow = 1
        while (pow + 1 < gridSize) pow = pow shl 1
        val mapSize = pow + 1

        val colors = PixelArtUtil.paletteRgb(palette)

        val heightmap: DoubleArray
        val effSea: Float
        if (time == 0f) {
            heightmap = diamondSquare(mapSize, roughness, SeededRNG(seed))
            effSea = seaLevel
        } else {
            val key = "$seed|$mapSize|$roughness"
            synchronized(Companion) {
                if (animKey != key || heightmapCache.size != mapSize * mapSize) {
                    heightmapCache = diamondSquare(mapSize, roughness, SeededRNG(seed))
                    mapSizeCache = mapSize
                    animKey = key
                }
                heightmap = heightmapCache
            }
            val oscillation = sin(time * speed * 0.5f) * 0.15f
            effSea = (seaLevel + oscillation).coerceIn(0.05f, 0.85f)
        }

        val pixels = IntArray(gridSize * gridSize)
        renderTerrain(pixels, heightmap, mapSize, gridSize, effSea, bands, colors)
        PixelArtUtil.blitNearest(canvas, bitmap, pixels, gridSize)
    }
}
