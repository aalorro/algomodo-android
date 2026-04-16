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
 * Sorts pixel rows/columns/diagonals by brightness with animated threshold waves,
 * bidirectional flow, row displacement glitch, and chromatic aberration -- producing
 * dramatic cascading color streaks.
 *
 * Animated threshold zone ripples across image via sin(y/h + time). Bidirectional
 * sort: ascending in even bands, descending in odd. Row displacement glitch shifts
 * sorted rows horizontally by noise-driven amounts. Chromatic aberration offsets
 * R/B channels. Rich base patterns with quantized noise bands, diagonal cross-hatch,
 * or irregular stripes. Periodic seed shape injection prevents stagnation. Counting
 * sort O(n) with pre-allocated buffers (256 buckets by brightness).
 */
class FluxPixelSortFeedbackGenerator : Generator {

    override val id = "flux-pixel-sort-feedback"
    override val family = "flux"
    override val styleName = "Pixel Sort Feedback"
    override val definition =
        "Sorts pixel rows/columns/diagonals by brightness with animated threshold waves, " +
        "bidirectional flow, row displacement glitch, and chromatic aberration \u2014 producing " +
        "dramatic cascading color streaks"
    override val algorithmNotes =
        "Animated threshold zone ripples across image via sin(y/h + time). Bidirectional sort: " +
        "ascending in even bands, descending in odd. Row displacement glitch shifts sorted rows " +
        "horizontally by noise-driven amounts. Chromatic aberration offsets R/B channels. Rich " +
        "base patterns with quantized noise bands, diagonal cross-hatch, or irregular stripes. " +
        "Periodic seed shape injection prevents stagnation. Counting sort O(n) with pre-allocated " +
        "buffers. Work resolution scales to 0.5x during animation for performance."
    override val supportsVector = false
    override val supportsAnimation = true

    override val parameterSchema = listOf(
        Parameter.SelectParam(
            name = "Sort Direction",
            key = "sortDirection",
            group = ParamGroup.COMPOSITION,
            help = "horizontal: sort rows | vertical: sort columns | diagonal: sort along diagonals",
            options = listOf("horizontal", "vertical", "diagonal"),
            default = "horizontal"
        ),
        Parameter.SelectParam(
            name = "Base Pattern",
            key = "basePattern",
            group = ParamGroup.COMPOSITION,
            help = "noise: quantized noise bands | gradient: diagonal cross-hatch | stripes: irregular vertical stripes",
            options = listOf("noise", "gradient", "stripes"),
            default = "noise"
        ),
        Parameter.NumberParam(
            name = "Threshold",
            key = "threshold",
            group = ParamGroup.TEXTURE,
            help = "Brightness threshold \u2014 pixels brighter than this get sorted",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Sort Strength",
            key = "sortStrength",
            group = ParamGroup.TEXTURE,
            help = "Fraction of pixels in the threshold zone that get sorted",
            min = 0.1f, max = 1f, step = 0.05f, default = 0.8f
        ),
        Parameter.NumberParam(
            name = "Glitch",
            key = "glitch",
            group = ParamGroup.TEXTURE,
            help = "Horizontal row displacement intensity \u2014 higher = more broken/glitchy",
            min = 0f, max = 1f, step = 0.05f, default = 0.3f
        ),
        Parameter.NumberParam(
            name = "Decay",
            key = "decay",
            group = ParamGroup.TEXTURE,
            help = "How much of the previous frame persists \u2014 higher = slower fade to base pattern",
            min = 0.8f, max = 0.99f, step = 0.01f, default = 0.92f
        ),
        Parameter.NumberParam(
            name = "Speed",
            key = "speed",
            group = ParamGroup.FLOW_MOTION,
            help = "Animation speed of the threshold wave and effects",
            min = 0.1f, max = 3f, step = 0.05f, default = 0.5f
        ),
        Parameter.NumberParam(
            name = "Audio Reactivity",
            key = "reactivity",
            group = ParamGroup.FLOW_MOTION,
            help = "Sensitivity to audio input (0 = none)",
            min = 0f, max = 2f, step = 0.1f, default = 1.0f
        )
    )

    override fun getDefaultParams(): Map<String, Any> = mapOf(
        "sortDirection" to "horizontal",
        "basePattern" to "noise",
        "threshold" to 0.3f,
        "sortStrength" to 0.8f,
        "glitch" to 0.3f,
        "decay" to 0.92f,
        "speed" to 0.5f,
        "reactivity" to 1.0f
    )

    // ── Cached state across frames ──────────────────────────────────────
    private data class SortState(
        val seed: Int,
        val w: Int,
        val h: Int,
        val basePattern: String,
        val workBitmap: Bitmap,  // working buffer at possibly reduced resolution
        val baseBitmap: Bitmap,  // base pattern bitmap for blending
        var frameCount: Int
    )

    // Dimension-keyed state cache — concurrent preview + export renders at different
    // sizes can coexist with separate states instead of racing on a single shared state.
    // Evicted entries are not eagerly recycled — letting GC reclaim the bitmaps
    // avoids "recycled bitmap" crashes when a thread still holds a reference.
    private val stateLock = Any()
    private val stateCache = object : LinkedHashMap<String, SortState>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SortState>?): Boolean =
            size > MAX_STATE_ENTRIES
    }

    // Thread-local sort working buffers — since origPixels/origBri/sortedPixels/counts are
    // mutated during countingSortSpan and must not race between preview+export threads,
    // they're allocated per-render and passed as parameters.
    private class SortBuffers(initialSize: Int = 2048) {
        var origPixels = IntArray(initialSize)
        var origBri = IntArray(initialSize)
        var sortedPixels = IntArray(initialSize)
        val counts = IntArray(256)

        fun ensure(len: Int) {
            if (len > origPixels.size) {
                val sz = max(len, 2048)
                origPixels = IntArray(sz)
                origBri = IntArray(sz)
                sortedPixels = IntArray(sz)
            }
        }
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
        val fullW = bitmap.width
        val fullH = bitmap.height

        val sortDirection = (params["sortDirection"] as? String) ?: "horizontal"
        val basePattern = (params["basePattern"] as? String) ?: "noise"
        val decay = (params["decay"] as? Number)?.toFloat() ?: 0.92f
        val speed = (params["speed"] as? Number)?.toFloat() ?: 0.5f
        val rx = (params["reactivity"] as? Number)?.toFloat() ?: 1.0f

        // ── Audio ─────────────────────────────────────────────────────────
        val audioAnalysis = params["_audioAnalysis"] as? AudioAnalysis
        val audioBass = (audioAnalysis?.getBass(time) ?: 0f) * rx
        val audioMid = (audioAnalysis?.getMid(time) ?: 0f) * rx
        val audioHigh = (audioAnalysis?.getHigh(time) ?: 0f) * rx
        val audioEnergy = ((audioBass + audioMid + audioHigh) / 3f)

        // Bass -> lower threshold (more sorting on beats)
        var threshold = ((params["threshold"] as? Number)?.toFloat() ?: 0.3f) - audioBass * 0.5f
        threshold = threshold.coerceIn(0f, 1f)
        // Mid -> increase sort strength
        var sortStrength = ((params["sortStrength"] as? Number)?.toFloat() ?: 0.8f) + audioMid * 0.4f
        sortStrength = sortStrength.coerceIn(0.1f, 1f)
        // High -> chromatic aberration + injection
        val chromaAmt = audioHigh * 4f
        // Energy -> glitch row displacement
        var glitch = ((params["glitch"] as? Number)?.toFloat() ?: 0.3f) + audioEnergy * 0.5f
        glitch = (glitch + audioBass * 0.3f).coerceAtMost(1f)

        val t = time * speed

        // ── Work resolution ───────────────────────────────────────────────
        val isAnimating = time > 0f
        val scale = if (isAnimating) 0.5f else 1f
        val w = (fullW * scale).roundToInt().coerceAtLeast(1)
        val h = (fullH * scale).roundToInt().coerceAtLeast(1)

        // ── State setup ───────────────────────────────────────────────────
        val stateKey = "$seed:$w:$h:$basePattern"
        var st: SortState? = synchronized(stateLock) { stateCache[stateKey] }
        if (st == null) {
            val baseBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            generateBasePattern(baseBmp, w, h, seed, palette, basePattern)

            val workBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // Initialize work bitmap from base pattern
            val workCanvas = Canvas(workBmp)
            workCanvas.drawBitmap(baseBmp, 0f, 0f, null)

            st = SortState(
                seed = seed,
                w = w,
                h = h,
                basePattern = basePattern,
                workBitmap = workBmp,
                baseBitmap = baseBmp,
                frameCount = 0
            )
            synchronized(stateLock) { stateCache[stateKey] = st }
        }

        val totalPixels = w * h

        // ── Read pixels from work bitmap ──────────────────────────────────
        val pixels = IntArray(totalPixels)
        st.workBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // ── Compute brightness for all pixels ─────────────────────────────
        // Local buffers — previously class-level @Volatile IntArrays; made local for
        // thread safety (preview + export threads can run concurrently).
        val sortBuffers = SortBuffers()
        val brightness = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            val px = pixels[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            brightness[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // ── Per-frame RNG ─────────────────────────────────────────────────
        val frameRng = SeededRNG(seed xor (time * 1000f).toInt())
        val noise = SimplexNoise(seed)

        // ── Animated threshold: sort wave ripples across image ─────────────
        val wavePhase = t * 2.5f
        val waveFreq = PI.toFloat() * 2f  // one full wave across image height

        // ── Sort with animated threshold + bidirectional ───────────────────
        when (sortDirection) {
            "horizontal" -> {
                for (y in 0 until h) {
                    val rowNorm = y.toFloat() / h
                    val waveVal = sin(rowNorm * waveFreq + wavePhase)
                    val rowThreshold = threshold + waveVal * 0.25f
                    val threshBri = (rowThreshold * 255f).toInt().coerceIn(0, 255)
                    val bandIdx = (y.toFloat() / (h * 0.1f)).toInt()
                    val reverse = (bandIdx and 1) == 1
                    countingSortSpan(sortBuffers, pixels, brightness, y * w, w, 1, threshBri, sortStrength, reverse)
                }
            }
            "vertical" -> {
                for (x in 0 until w) {
                    val colNorm = x.toFloat() / w
                    val waveVal = sin(colNorm * waveFreq + wavePhase)
                    val colThreshold = threshold + waveVal * 0.25f
                    val threshBri = (colThreshold * 255f).toInt().coerceIn(0, 255)
                    val bandIdx = (x.toFloat() / (w * 0.1f)).toInt()
                    val reverse = (bandIdx and 1) == 1
                    countingSortSpan(sortBuffers, pixels, brightness, x, h, w, threshBri, sortStrength, reverse)
                }
            }
            else -> { // diagonal
                val threshBri = (threshold * 255f).toInt()
                for (sx in 0 until w) {
                    if (frameRng.random() > 0.7f) continue
                    val reverse = (sx and 15) > 7
                    countingSortSpan(sortBuffers, pixels, brightness, sx, min(w - sx, h), w + 1, threshBri, sortStrength, reverse)
                }
                for (sy in 1 until h) {
                    if (frameRng.random() > 0.7f) continue
                    val reverse = (sy and 15) > 7
                    countingSortSpan(sortBuffers, pixels, brightness, sy * w, min(w, h - sy), w + 1, threshBri, sortStrength, reverse)
                }
            }
        }

        // ── Row displacement glitch ───────────────────────────────────────
        if (glitch > 0.01f && sortDirection == "horizontal") {
            val maxShift = (w * glitch * 0.15f).toInt()
            if (maxShift > 0) {
                val rowShift = IntArray(h)
                val noiseScale = 4.0f / h

                for (y in 0 until h) {
                    val n = noise.noise2D(y * noiseScale, t * 0.5f)
                    val rowNorm = y.toFloat() / h
                    val waveVal = sin(rowNorm * waveFreq + wavePhase)
                    val inSortZone = waveVal > -0.3f
                    rowShift[y] = if (inSortZone) (n * maxShift).roundToInt() else 0
                }

                val rb = IntArray(w)

                for (y in 0 until h) {
                    val shift = rowShift[y]
                    if (shift == 0) continue
                    val rowStart = y * w

                    // Copy row to buffer
                    System.arraycopy(pixels, rowStart, rb, 0, w)

                    // Write shifted (wrap around)
                    for (x in 0 until w) {
                        var srcX = x - shift
                        if (srcX < 0) srcX += w
                        else if (srcX >= w) srcX -= w
                        pixels[rowStart + x] = rb[srcX]
                    }
                }
            }
        }

        // ── Chromatic aberration (R/B channel offset) ─────────────────────
        val chromaPx = (chromaAmt + if (isAnimating) 1f else 0f).roundToInt()
        if (chromaPx >= 1) {
            for (y in 0 until h) {
                val rowOff = y * w
                // R channel: shift right (read from left neighbor)
                for (x in w - 1 downTo chromaPx) {
                    val srcPixel = pixels[rowOff + x - chromaPx]
                    val srcR = (srcPixel shr 16) and 0xFF
                    val dstPixel = pixels[rowOff + x]
                    pixels[rowOff + x] = (dstPixel and 0xFF00FFFF.toInt()) or (srcR shl 16)
                }
                // B channel: shift left (read from right neighbor)
                for (x in 0 until w - chromaPx) {
                    val srcPixel = pixels[rowOff + x + chromaPx]
                    val srcB = srcPixel and 0xFF
                    val dstPixel = pixels[rowOff + x]
                    pixels[rowOff + x] = (dstPixel and 0xFFFFFF00.toInt()) or srcB
                }
            }
        }

        // ── Write sorted/glitched pixels back to work bitmap ──────────────
        st.workBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        // ── Seed shape injection (every ~8 frames during animation) ───────
        val colorInts = palette.colorInts()
        val nC = colorInts.size

        val injectFrame = if (isAnimating) ((time * 30f).toInt() % 8 == 0) else false
        if (injectFrame || !isAnimating) {
            val injRng = SeededRNG(seed + (time * 100f).toInt())
            val injCount = if (isAnimating) 3 + (audioEnergy * 5f).toInt() else 0
            val workCanvas = Canvas(st.workBitmap)
            val injPaint = Paint(Paint.ANTI_ALIAS_FLAG)

            for (i in 0 until injCount) {
                val ix = injRng.random() * w
                val iy = injRng.random() * h
                val iSize = 3f + injRng.random() * 12f
                val c = colorInts[(injRng.random() * nC).toInt().coerceIn(0, nC - 1)]
                injPaint.color = c

                if (injRng.random() > 0.5f) {
                    workCanvas.drawCircle(ix, iy, iSize, injPaint)
                } else {
                    workCanvas.drawRect(
                        ix - iSize, iy - iSize * 0.3f,
                        ix + iSize, iy + iSize * 0.3f,
                        injPaint
                    )
                }
            }
        }

        // ── Noise pixel injection (palette sparks) ────────────────────────
        run {
            val perturbCount = max(1, (totalPixels * 0.001f * (1f + audioHigh * 4f)).toInt())
            val workCanvas = Canvas(st.workBitmap)
            val sparkPaint = Paint()
            for (i in 0 until perturbCount) {
                val px = (frameRng.random() * w).toInt()
                val py = (frameRng.random() * h).toInt()
                val c = colorInts[(frameRng.random() * nC).toInt().coerceIn(0, nC - 1)]
                sparkPaint.color = c
                val sparkW = 1f + (audioHigh * 3f).toInt()
                workCanvas.drawRect(px.toFloat(), py.toFloat(), px + sparkW, py + 1f, sparkPaint)
            }
        }

        // ── Blend with base pattern at (1-decay) weight ───────────────────
        val blendAlpha = 1f - decay
        if (blendAlpha > 0.001f) {
            val workCanvas = Canvas(st.workBitmap)
            val blendPaint = Paint().apply {
                alpha = (blendAlpha * 255f).roundToInt().coerceIn(0, 255)
            }
            workCanvas.drawBitmap(st.baseBitmap, 0f, 0f, blendPaint)
        }

        st.frameCount++

        // ── Draw to output canvas ─────────────────────────────────────────
        if (w == fullW && h == fullH) {
            canvas.drawBitmap(st.workBitmap, 0f, 0f, null)
        } else {
            // Upscale from work resolution to full resolution with bilinear filtering
            val srcRect = Rect(0, 0, w, h)
            val dstRect = Rect(0, 0, fullW, fullH)
            val upscalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(st.workBitmap, srcRect, dstRect, upscalePaint)
        }
    }

    // ── Counting sort with reverse option ─────────────────────────────────

    private fun countingSortSpan(
        buffers: SortBuffers,
        pixels: IntArray,
        brightness: IntArray,
        startIdx: Int,
        count: Int,
        stride: Int,
        threshBri: Int,
        sortStrength: Float,
        reverse: Boolean
    ) {
        if (count < 2) return

        var spanStart = -1

        for (i in 0..count) {
            val pixIdx = startIdx + i * stride
            val above = i < count && pixIdx < brightness.size && brightness[pixIdx] > threshBri

            if (above) {
                if (spanStart < 0) spanStart = i
            } else if (spanStart >= 0) {
                val spanLen = i - spanStart
                if (spanLen >= 2) {
                    buffers.ensure(spanLen)
                    val origPixels = buffers.origPixels
                    val origBri = buffers.origBri
                    val sortedPixels = buffers.sortedPixels
                    val counts = buffers.counts

                    for (j in 0 until spanLen) {
                        val pIdx = startIdx + (spanStart + j) * stride
                        origPixels[j] = pixels[pIdx]
                        origBri[j] = brightness[pIdx]
                    }

                    // Counting sort by brightness
                    counts.fill(0)
                    for (j in 0 until spanLen) counts[origBri[j]]++
                    for (b in 1 until 256) counts[b] += counts[b - 1]
                    for (j in spanLen - 1 downTo 0) {
                        counts[origBri[j]]--
                        sortedPixels[counts[origBri[j]]] = origPixels[j]
                    }

                    // Write back -- reversed if needed
                    if (sortStrength >= 0.99f) {
                        if (reverse) {
                            for (j in 0 until spanLen) {
                                pixels[startIdx + (spanStart + j) * stride] = sortedPixels[spanLen - 1 - j]
                            }
                        } else {
                            for (j in 0 until spanLen) {
                                pixels[startIdx + (spanStart + j) * stride] = sortedPixels[j]
                            }
                        }
                    } else {
                        val invStr = 1f - sortStrength
                        for (j in 0 until spanLen) {
                            val pIdx = startIdx + (spanStart + j) * stride
                            val orig = origPixels[j]
                            val sorted = if (reverse) sortedPixels[spanLen - 1 - j] else sortedPixels[j]
                            // Android ARGB_8888: (A << 24) | (R << 16) | (G << 8) | B
                            val origR = (orig shr 16) and 0xFF
                            val origG = (orig shr 8) and 0xFF
                            val origB = orig and 0xFF
                            val sortR = (sorted shr 16) and 0xFF
                            val sortG = (sorted shr 8) and 0xFF
                            val sortB = sorted and 0xFF
                            val r = (sortR * sortStrength + origR * invStr).toInt()
                            val g = (sortG * sortStrength + origG * invStr).toInt()
                            val b = (sortB * sortStrength + origB * invStr).toInt()
                            pixels[pIdx] = (orig and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                        }
                    }

                    // Update brightness for sorted span
                    for (j in 0 until spanLen) {
                        val pIdx = startIdx + (spanStart + j) * stride
                        val px = pixels[pIdx]
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        brightness[pIdx] = (r * 77 + g * 150 + b * 29) shr 8
                    }
                }
                spanStart = -1
            }
        }
    }

    // ── Generate rich base pattern ────────────────────────────────────────

    private fun generateBasePattern(
        baseBitmap: Bitmap,
        w: Int, h: Int,
        seed: Int, palette: Palette, pattern: String
    ) {
        val colorInts = palette.colorInts()
        val nC = colorInts.size
        val nCm1 = nC - 1
        val rng = SeededRNG(seed)

        val pixels = IntArray(w * h)

        when (pattern) {
            "noise" -> {
                // Quantized noise bands -- sharp edges for dramatic sort boundaries
                val noiseObj = SimplexNoise(seed)
                val noiseScale = 2.5f / min(w, h)
                val bandCount = nC * 2 // more bands = more edges

                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val n1 = noiseObj.noise2D(x * noiseScale, y * noiseScale)
                        val n2 = noiseObj.noise2D(
                            x * noiseScale * 2.3f + 50f,
                            y * noiseScale * 2.3f + 50f
                        )
                        var tv = (n1 * 0.65f + n2 * 0.35f) * 0.5f + 0.5f
                        tv = tv.coerceIn(0f, 1f)
                        // Quantize to bands for sharp edges
                        tv = floor(tv * bandCount) / bandCount

                        val ci = tv * nCm1
                        val i0 = ci.toInt()
                        val i1 = if (i0 < nCm1) i0 + 1 else nCm1
                        val f = ci - i0

                        val c0 = colorInts[i0]
                        val c1 = colorInts[i1]
                        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).toInt()
                        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).toInt()
                        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).toInt()
                        pixels[y * w + x] = Color.rgb(r, g, b)
                    }
                }
            }
            "gradient" -> {
                // Diagonal gradient with perpendicular stripe overlay -> cross-hatch
                val stripeFreq = 20 + rng.integer(0, 15)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val diag = (x.toFloat() / w + y.toFloat() / h) * 0.5f
                        val stripe = ((x - y).toFloat() / min(w, h) * stripeFreq).toInt()
                        val stripeOn = (stripe and 1) == 0

                        var tv = if (stripeOn) diag else 1f - diag
                        tv = tv.coerceIn(0f, 1f)

                        val ci = tv * nCm1
                        val i0 = ci.toInt()
                        val i1 = if (i0 < nCm1) i0 + 1 else nCm1
                        val f = ci - i0

                        val c0 = colorInts[i0]
                        val c1 = colorInts[i1]
                        val r = (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * f).toInt()
                        val g = (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * f).toInt()
                        val b = (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * f).toInt()
                        pixels[y * w + x] = Color.rgb(r, g, b)
                    }
                }
            }
            else -> { // "stripes"
                // Irregular stripes with brightness variation -- varied widths
                data class StripeInfo(val x: Int, val w: Int, val ci: Int, val bright: Float)
                val stripes = mutableListOf<StripeInfo>()
                var cx = 0
                while (cx < w) {
                    val sw = 4 + (rng.random() * 30f).toInt()
                    stripes.add(StripeInfo(
                        x = cx, w = sw,
                        ci = (rng.random() * nC).toInt().coerceAtMost(nC - 1),
                        bright = 0.4f + rng.random() * 0.6f
                    ))
                    cx += sw
                }

                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var si = 0
                        for (s in stripes.indices) {
                            if (x >= stripes[s].x && x < stripes[s].x + stripes[s].w) {
                                si = s
                                break
                            }
                        }
                        val s = stripes[si]
                        val c = colorInts[s.ci]
                        val brt = s.bright
                        val r = (Color.red(c) * brt).toInt()
                        val g = (Color.green(c) * brt).toInt()
                        val b = (Color.blue(c) * brt).toInt()
                        pixels[y * w + x] = Color.rgb(r, g, b)
                    }
                }
            }
        }

        baseBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        // Overlay geometric shapes for high-contrast edges
        val shapeCanvas = Canvas(baseBitmap)
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shapeCount = 8 + rng.integer(0, 8)
        for (i in 0 until shapeCount) {
            val sx = rng.random() * w
            val sy = rng.random() * h
            val size = 5f + rng.random() * (min(w, h) * 0.12f)
            val c = colorInts[(rng.random() * nC).toInt().coerceAtMost(nC - 1)]
            val bright = 0.6f + rng.random() * 0.4f

            val r = (Color.red(c) * bright).toInt().coerceIn(0, 255)
            val g = (Color.green(c) * bright).toInt().coerceIn(0, 255)
            val b = (Color.blue(c) * bright).toInt().coerceIn(0, 255)
            val alpha = ((0.7f + rng.random() * 0.3f) * 255f).roundToInt().coerceIn(0, 255)
            shapePaint.color = Color.argb(alpha, r, g, b)

            if (rng.random() > 0.5f) {
                shapeCanvas.drawCircle(sx, sy, size, shapePaint)
            } else {
                shapeCanvas.drawRect(
                    sx - size, sy - size * 0.4f,
                    sx + size, sy + size * 0.4f,
                    shapePaint
                )
            }
        }
    }

    override fun estimateCost(params: Map<String, Any>, quality: Quality): Float {
        val dir = (params["sortDirection"] as? String) ?: "horizontal"
        val base = 250f * if (dir == "diagonal") 1.5f else 1.0f
        return when (quality) {
            Quality.DRAFT -> base * 0.5f / 1000f
            Quality.BALANCED -> base * 0.7f / 1000f
            Quality.ULTRA -> base / 1000f
        }.coerceIn(0.1f, 0.8f)
    }

    companion object {
        private const val MAX_STATE_ENTRIES = 3
    }
}
