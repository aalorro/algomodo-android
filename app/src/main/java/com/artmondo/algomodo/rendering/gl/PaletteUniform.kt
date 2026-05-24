package com.artmondo.algomodo.rendering.gl

import android.graphics.Color
import com.artmondo.algomodo.data.palettes.Palette

/**
 * Packs a [Palette] into a `vec3[5]` uniform array as floats in 0..1 range.
 *
 * Always emits 5 entries: if the palette has fewer colours, the last one is
 * repeated. If it has more, only the first 5 are used. This matches the
 * fixed-size `uniform vec3 uPalette[5];` declared in shader sources.
 */
object PaletteUniform {
    private const val SIZE = 5

    fun toUniformFloats(palette: Palette): FloatArray {
        val ints = palette.colorInts()
        val out = FloatArray(SIZE * 3)
        for (i in 0 until SIZE) {
            val c = if (i < ints.size) ints[i] else ints.last()
            out[i * 3]     = Color.red(c) / 255f
            out[i * 3 + 1] = Color.green(c) / 255f
            out[i * 3 + 2] = Color.blue(c) / 255f
        }
        return out
    }

    /**
     * GLSL helper code that all shader sources can include. Provides
     * `palette_color(float t)` returning the gradient-interpolated colour
     * at position t in [0, 1] across the 5 palette entries.
     */
    const val GLSL_HELPERS = """
        uniform vec3 uPalette[5];

        vec3 palette_color(float t) {
            t = clamp(t, 0.0, 1.0) * 4.0;
            int i = int(t);
            if (i >= 4) { i = 3; }
            float f = t - float(i);
            return mix(uPalette[i], uPalette[i + 1], f);
        }
    """
}
