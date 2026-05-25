package com.artmondo.algomodo.rendering.gl

/**
 * Shared GLSL ES 3.0 helpers for noise-family GPU generators.
 *
 * Provides:
 *  - `snoise(vec2)` — 2D simplex noise in roughly [-1, 1], based on the
 *    Ashima/Stefan Gustavson public-domain implementation. Deterministic,
 *    no texture lookups.
 *  - `fbm(vec2 p, int octaves, float lacunarity, float gain)` — multi-octave
 *    FBM summation, normalised by total amplitude so output stays in [-1, 1].
 *    Mirrors the contract of [com.artmondo.algomodo.core.rng.SimplexNoise.fbm].
 *
 * GLSL ES 3.0 has no recursion and no dynamic loop bounds without `const`
 * limits, so [fbm] caps at 10 octaves via a fixed-iteration loop with an
 * early break (matching the `octaves` UI range of 1..10).
 *
 * The noise output is not bit-identical to the CPU [SimplexNoise] used in the
 * CPU paths — that's a different (also classic Gustavson-derived) variant.
 * Visual character is very close; existing presets may render slightly
 * differently.
 */
object NoiseGlsl {
    const val GLSL_HELPERS = """
        vec3 _noise_permute(vec3 x) {
            return mod(((x * 34.0) + 1.0) * x, 289.0);
        }

        float snoise(vec2 v) {
            const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                                -0.577350269189626, 0.024390243902439);
            vec2 i  = floor(v + dot(v, C.yy));
            vec2 x0 = v - i + dot(i, C.xx);
            vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
            vec4 x12 = x0.xyxy + C.xxzz;
            x12.xy -= i1;
            i = mod(i, 289.0);
            vec3 p = _noise_permute(_noise_permute(i.y + vec3(0.0, i1.y, 1.0))
                                  + i.x + vec3(0.0, i1.x, 1.0));
            vec3 m = max(0.5 - vec3(dot(x0, x0),
                                    dot(x12.xy, x12.xy),
                                    dot(x12.zw, x12.zw)), 0.0);
            m = m * m;
            m = m * m;
            vec3 x = 2.0 * fract(p * C.www) - 1.0;
            vec3 h = abs(x) - 0.5;
            vec3 ox = floor(x + 0.5);
            vec3 a0 = x - ox;
            m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
            vec3 g;
            g.x  = a0.x  * x0.x  + h.x  * x0.y;
            g.yz = a0.yz * x12.xz + h.yz * x12.yw;
            return 130.0 * dot(m, g);
        }

        float fbm(vec2 p, int octaves, float lacunarity, float gain) {
            float sum = 0.0;
            float amp = 1.0;
            float ampSum = 0.0;
            for (int i = 0; i < 10; i++) {
                if (i >= octaves) break;
                sum += snoise(p) * amp;
                ampSum += amp;
                p *= lacunarity;
                amp *= gain;
            }
            return sum / max(ampSum, 0.0001);
        }

        // Ridged multifractal — sum of (1 - |noise|)^2, normalised. Matches the
        // contract of SimplexNoise.ridged: output roughly in [0, 1].
        float ridgedNoise(vec2 p, int octaves, float lacunarity, float gain) {
            float sum = 0.0;
            float amp = 1.0;
            float ampSum = 0.0;
            for (int i = 0; i < 10; i++) {
                if (i >= octaves) break;
                float n = 1.0 - abs(snoise(p));
                sum += n * n * amp;
                ampSum += amp;
                p *= lacunarity;
                amp *= gain;
            }
            return sum / max(ampSum, 0.0001);
        }

        // Turbulence — sum of |noise|, normalised. Matches SimplexNoise.turbulence.
        float turbulenceNoise(vec2 p, int octaves, float lacunarity, float gain) {
            float sum = 0.0;
            float amp = 1.0;
            float ampSum = 0.0;
            for (int i = 0; i < 10; i++) {
                if (i >= octaves) break;
                sum += abs(snoise(p)) * amp;
                ampSum += amp;
                p *= lacunarity;
                amp *= gain;
            }
            return sum / max(ampSum, 0.0001);
        }
    """

    /**
     * Derive a deterministic 2D coordinate offset from an integer seed.
     * Used by noise GPU generators so different seeds produce different fields
     * without per-pixel hashing.
     */
    fun seedToOffset(seed: Int): Pair<Float, Float> {
        var s = if (seed == 0) 0x9E3779B9.toInt() else seed
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        val x = ((s ushr 8) and 0xFFFFFF) / 8_388_608f * 1000f - 500f
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        val y = ((s ushr 8) and 0xFFFFFF) / 8_388_608f * 1000f - 500f
        return x to y
    }
}
