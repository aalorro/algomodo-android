package com.artmondo.algomodo.data.palettes

import android.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Palette(
    val name: String,
    val colors: List<String> // Always 5 hex colors
) {
    // Cached parsed color ints — avoids Color.parseColor() on every access
    private val parsedColors: IntArray by lazy {
        IntArray(colors.size) { Color.parseColor(colors[it]) }
    }

    fun colorInts(): List<Int> = parsedColors.toList()

    fun colorAt(index: Int): Int = parsedColors[index % parsedColors.size]

    fun lerpColor(t: Float): Int {
        val pc = parsedColors
        val clamped = t.coerceIn(0f, 1f)
        val scaled = clamped * (pc.size - 1)
        val i = scaled.toInt().coerceAtMost(pc.size - 2)
        val frac = scaled - i
        val c1 = pc[i]
        val c2 = pc[i + 1]
        val r = ((Color.red(c1) * (1 - frac) + Color.red(c2) * frac)).toInt()
        val g = ((Color.green(c1) * (1 - frac) + Color.green(c2) * frac)).toInt()
        val b = ((Color.blue(c1) * (1 - frac) + Color.blue(c2) * frac)).toInt()
        return Color.rgb(r, g, b)
    }

    /** Pre-compute a lookup table for fast indexed palette access in pixel loops. */
    fun buildLut(size: Int = 256): IntArray = IntArray(size) { i ->
        lerpColor(i.toFloat() / (size - 1).coerceAtLeast(1))
    }
}

object CustomPaletteHelper {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(jsonStr: String): List<Palette> = try {
        json.decodeFromString<List<Palette>>(jsonStr)
    } catch (_: Exception) {
        emptyList()
    }

    fun serialize(palettes: List<Palette>): String =
        json.encodeToString(palettes)

    fun nextDefaultName(existing: List<Palette>): String {
        val usedNumbers = existing.mapNotNull {
            Regex("^Custom (\\d+)$").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        val next = (1..5).first { it !in usedNumbers }
        return "Custom $next"
    }
}

object CuratedPalettes {
    val all = listOf(
        Palette("Vibrant", listOf("#FF006E", "#FB5607", "#FFBE0B", "#8338EC", "#3A86FF")),
        Palette("Ocean", listOf("#03045E", "#0077B6", "#00B4D8", "#90E0EF", "#CAF0F8")),
        Palette("Sunset", listOf("#FF4800", "#FF6000", "#FF8500", "#FFB300", "#FFDB00")),
        Palette("Forest", listOf("#1B4332", "#2D6A4F", "#40916C", "#74C69D", "#D8F3DC")),
        Palette("Monochrome", listOf("#111111", "#333333", "#666666", "#999999", "#EEEEEE")),
        Palette("Pastel", listOf("#FFB3BA", "#FFDFBA", "#FFFFBA", "#BAFFC9", "#BAE1FF")),
        Palette("Neon", listOf("#FF00FF", "#00FFFF", "#FFFF00", "#FF0080", "#00FF80")),
        Palette("Ember", listOf("#1A0000", "#5C0A00", "#B32000", "#E05000", "#FF8040")),
        Palette("Arctic", listOf("#E0F7FA", "#80DEEA", "#26C6DA", "#00838F", "#004D40")),
        Palette("Cosmic", listOf("#0D0221", "#0A0548", "#450920", "#A2095B", "#E9178A")),
        Palette("Rainbow", listOf("#FF0000", "#FF8800", "#FFEE00", "#00CC44", "#3366FF")),
        Palette("Contrast", listOf("#000000", "#FFFFFF", "#FF0000", "#FFFF00", "#0000FF")),
        Palette("Earth", listOf("#5C3D2E", "#A0522D", "#C89B7B", "#D4A76A", "#F5DEB3")),
        Palette("Nature", listOf("#2E7D32", "#66BB6A", "#AED581", "#81D4FA", "#FFCC80")),
        Palette("Sakura", listOf("#3D0C11", "#A3344A", "#E8758F", "#F2B5C8", "#FBE8EE")),
        Palette("Cyber", listOf("#0A0A2A", "#1B03A3", "#7209B7", "#F72585", "#4CC9F0")),
        Palette("Vapor", listOf("#FF6AD5", "#C774E8", "#AD8CFF", "#8795E8", "#94D0FF")),
        Palette("Lava", listOf("#0C0404", "#4A0E0E", "#B7202E", "#FF5722", "#FFEB3B")),
        Palette("Tide", listOf("#05445E", "#189AB4", "#75E6DA", "#D4F1F9", "#F5E6CA"))
    )

    // Display-only placeholders for random palette chips
    val randomPlaceholder = Palette("RAND 5", listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF"))
    val random8Placeholder = Palette("RAND 8", listOf("#FF0000", "#FF8800", "#FFEE00", "#00CC44", "#0088FF", "#8800FF", "#FF0088", "#00FFCC"))
    val random10Placeholder = Palette("RAND 10", listOf("#FF0000", "#FF6600", "#FFCC00", "#66FF00", "#00FF66", "#00CCFF", "#0066FF", "#6600FF", "#CC00FF", "#FF0066"))

    /** Palettes with enough average brightness to work on dark backgrounds. */
    val bright: List<Palette> by lazy {
        all.filter { palette ->
            val colors = palette.colors.map { Color.parseColor(it) }
            val avgLum = colors.sumOf { c ->
                Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
            } / (colors.size * 1000)
            avgLum >= 80
        }.ifEmpty { all }
    }

    val default = all[0] // Vibrant

    fun byName(name: String): Palette? = all.find { it.name == name }

    /** Generate a palette with 5 visually diverse random colors using golden-angle hue spacing. */
    fun random(): Palette = randomN(5, "RAND 5")

    /** Generate a palette with 8 related colors — analogous hue spread with varied saturation/lightness. */
    fun random8(): Palette {
        val baseHue = (Math.random() * 360).toFloat()
        val spread = 40f + (Math.random() * 40f).toFloat() // 40–80 degree hue range
        val colors = (0 until 8).map { i ->
            val hue = (baseHue + (i.toFloat() / 7f) * spread) % 360f
            val sat = 0.45f + (Math.random() * 0.50f).toFloat()
            val lit = 0.30f + (Math.random() * 0.45f).toFloat()
            hslToHex(hue, sat, lit)
        }
        return Palette("RAND 8", colors)
    }

    /** Generate a palette with 10 totally unrelated random colors — each hue fully independent. */
    fun random10(): Palette {
        val colors = (0 until 10).map {
            val hue = (Math.random() * 360).toFloat()
            val sat = 0.50f + (Math.random() * 0.45f).toFloat()
            val lit = 0.35f + (Math.random() * 0.40f).toFloat()
            hslToHex(hue, sat, lit)
        }
        return Palette("RAND 10", colors)
    }

    private fun randomN(count: Int, name: String): Palette {
        val baseHue = (Math.random() * 360).toFloat()
        val colors = (0 until count).map { i ->
            val hue = (baseHue + i * 137.508f) % 360f // golden angle
            val sat = 0.60f + (Math.random() * 0.30f).toFloat()
            val lit = 0.50f + (Math.random() * 0.25f).toFloat()
            hslToHex(hue, sat, lit)
        }
        return Palette(name, colors)
    }

    private fun hslToHex(h: Float, s: Float, l: Float): String {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f  -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else     -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }
}
