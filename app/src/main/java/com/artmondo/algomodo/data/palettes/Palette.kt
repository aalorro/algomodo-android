package com.artmondo.algomodo.data.palettes

import android.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
data class Palette(
    val name: String,
    val colors: List<String> // Always 5 hex colors
) {
    fun colorInts(): List<Int> = colors.map { Color.parseColor(it) }

    fun colorAt(index: Int): Int = Color.parseColor(colors[index % colors.size])

    fun lerpColor(t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val scaled = clamped * (colors.size - 1)
        val i = scaled.toInt().coerceAtMost(colors.size - 2)
        val frac = scaled - i
        val c1 = colorAt(i)
        val c2 = colorAt(i + 1)
        val r = ((Color.red(c1) * (1 - frac) + Color.red(c2) * frac)).toInt()
        val g = ((Color.green(c1) * (1 - frac) + Color.green(c2) * frac)).toInt()
        val b = ((Color.blue(c1) * (1 - frac) + Color.blue(c2) * frac)).toInt()
        return Color.rgb(r, g, b)
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
        Palette("Cosmic", listOf("#0D0221", "#0A0548", "#450920", "#A2095B", "#E9178A"))
    )

    val default = all[0] // Vibrant

    fun byName(name: String): Palette? = all.find { it.name == name }
}
