package com.artmondo.algomodo.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.PostFXProcessor
import com.artmondo.algomodo.rendering.PostFXSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Composable
fun AlgoCanvas(
    generator: Generator?,
    params: Map<String, Any>,
    seed: Int,
    palette: Palette,
    quality: Quality,
    postFX: PostFXSettings,
    isAnimating: Boolean,
    animationFps: Int,
    showFps: Boolean,
    renderTrigger: Int,
    onPauseTimeCapture: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (generator == null) {
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {}
        return
    }

    // Shared animation time — written by AnimationCanvas thread, read when switching to static
    val animTimeBits = remember { AtomicLong(java.lang.Float.floatToIntBits(2.0f).toLong()) }

    if (isAnimating && generator.supportsAnimation) {
        AnimationCanvas(
            generator = generator,
            params = params,
            seed = seed,
            palette = palette,
            quality = quality,
            fps = animationFps,
            showFps = showFps,
            renderTrigger = renderTrigger,
            animTimeBits = animTimeBits,
            modifier = modifier
        )
    } else {
        val capturedTime = java.lang.Float.intBitsToFloat(animTimeBits.get().toInt())
        val staticTime = if (generator.supportsAnimation) capturedTime else 0f

        // Notify parent of the paused time for export use
        LaunchedEffect(staticTime) {
            onPauseTimeCapture?.invoke(staticTime)
        }

        StaticCanvas(
            generator = generator,
            params = params,
            seed = seed,
            palette = palette,
            quality = quality,
            postFX = postFX,
            staticTime = staticTime,
            renderTrigger = renderTrigger,
            modifier = modifier
        )
    }
}

// Single-thread dispatcher so at most one render occupies a thread at a time.
// Stale queued renders are skipped via the generation counter below.
private val renderDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
private fun StaticCanvas(
    generator: Generator,
    params: Map<String, Any>,
    seed: Int,
    palette: Palette,
    quality: Quality,
    postFX: PostFXSettings,
    staticTime: Float = if (generator.supportsAnimation) 2.0f else 0f,
    renderTrigger: Int,
    modifier: Modifier = Modifier
) {
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(false) }
    val renderGeneration = remember { AtomicInteger(0) }

    DisposableEffect(Unit) {
        onDispose { renderedBitmap?.recycle() }
    }

    LaunchedEffect(generator.id, params, seed, palette, quality, postFX, staticTime, renderTrigger) {
        val myGeneration = renderGeneration.incrementAndGet()
        isRendering = true
        var newBitmap: Bitmap? = null
        try {
            withContext(renderDispatcher) {
                // Skip if a newer render was already requested while we
                // were queued — avoids piling up stale work.
                if (renderGeneration.get() != myGeneration) return@withContext

                val size = when (quality) {
                    Quality.DRAFT -> 360
                    Quality.BALANCED -> 540
                    Quality.ULTRA -> 810
                }
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                newBitmap = bitmap
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.BLACK)
                try {
                    generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, staticTime)
                    PostFXProcessor.apply(bitmap, postFX)

                    // Safety net: if output is nearly all-black, re-render at time=0
                    // to catch generators with scroll/animation offsets that push
                    // content off canvas at time=2.0
                    if (generator.supportsAnimation && isBitmapBlank(bitmap, size)) {
                        canvas.drawColor(android.graphics.Color.BLACK)
                        generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, 0f)
                        PostFXProcessor.apply(bitmap, postFX)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AlgoCanvas", "Render failed for ${generator.id}", e)
                    canvas.drawColor(android.graphics.Color.BLACK)
                    val errPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.RED
                        strokeWidth = 4f
                        style = android.graphics.Paint.Style.STROKE
                    }
                    canvas.drawLine(0f, 0f, size.toFloat(), size.toFloat(), errPaint)
                    canvas.drawLine(size.toFloat(), 0f, 0f, size.toFloat(), errPaint)
                }
            }
            // Back on Main thread — safe to swap bitmap state without
            // racing Compose's draw pass.
            if (newBitmap != null && renderGeneration.get() == myGeneration) {
                val old = renderedBitmap
                renderedBitmap = newBitmap
                newBitmap = null  // ownership transferred, don't recycle in finally
                old?.recycle()
            }
        } finally {
            // If cancelled or stale, recycle the orphaned bitmap
            newBitmap?.recycle()
            isRendering = false
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(Color.Black)
    ) {
        renderedBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Generated art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        if (isRendering) {
            NeonProgressBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 25.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 25.dp)
                    .height(3.dp)
            )
        }
    }
}

@Composable
private fun AnimationCanvas(
    generator: Generator,
    params: Map<String, Any>,
    seed: Int,
    palette: Palette,
    quality: Quality,
    fps: Int,
    showFps: Boolean,
    renderTrigger: Int,
    animTimeBits: AtomicLong,
    modifier: Modifier = Modifier
) {
    // Mutable state holder that the render thread reads from
    val stateHolder = remember {
        object {
            @Volatile var currentGenerator: Generator = generator
            @Volatile var currentParams: Map<String, Any> = params
            @Volatile var currentSeed: Int = seed
            @Volatile var currentPalette: Palette = palette
            @Volatile var currentQuality: Quality = quality
            @Volatile var currentShowFps: Boolean = showFps
            @Volatile var resetRequested: Boolean = false
        }
    }

    // Update the holder whenever compose state changes
    LaunchedEffect(generator, params, seed, palette, quality, showFps) {
        stateHolder.currentGenerator = generator
        stateHolder.currentParams = params.toMap()
        stateHolder.currentSeed = seed
        stateHolder.currentPalette = palette
        stateHolder.currentQuality = quality
        stateHolder.currentShowFps = showFps
    }

    // Reload: reset animation time to 0
    LaunchedEffect(renderTrigger) {
        stateHolder.resetRequested = true
    }

    AndroidView(
        factory = { context ->
            object : SurfaceView(context), SurfaceHolder.Callback {
                private var animThread: Thread? = null
                @Volatile private var running = false
                private var startTime = 0L
                private var fpsCounter = 0
                private var lastFpsTime = 0L
                private var currentFps = 0

                init {
                    holder.addCallback(this)
                }

                override fun surfaceCreated(holder: SurfaceHolder) {
                    running = true
                    startTime = System.nanoTime()
                    lastFpsTime = startTime
                    animThread = Thread {
                        val targetNanos = 1_000_000_000L / fps
                        val w = width
                        val h = height
                        val maxSize = when (stateHolder.currentQuality) {
                            Quality.DRAFT -> 360
                            Quality.BALANCED -> 540
                            Quality.ULTRA -> 810
                        }
                        val size = minOf(w, h).coerceAtMost(maxSize)
                        var bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        var bitmapCanvas = Canvas(bitmap)
                        val fpsPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.GREEN
                            textSize = 28f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.MONOSPACE
                        }

                        while (running) {
                            // Handle reload: reset animation clock
                            if (stateHolder.resetRequested) {
                                stateHolder.resetRequested = false
                                startTime = System.nanoTime()
                            }

                            val frameStart = System.nanoTime()
                            val time = (frameStart - startTime) / 1_000_000_000f
                            animTimeBits.set(java.lang.Float.floatToIntBits(time).toLong())

                            // Read latest state each frame
                            val gen = stateHolder.currentGenerator
                            val p = stateHolder.currentParams
                            val s = stateHolder.currentSeed
                            val pal = stateHolder.currentPalette
                            val q = stateHolder.currentQuality
                            val sf = stateHolder.currentShowFps

                            bitmapCanvas.drawColor(android.graphics.Color.BLACK)
                            try {
                                gen.renderCanvas(bitmapCanvas, bitmap, p, s, pal, q, time)
                            } catch (e: Exception) {
                                android.util.Log.e("AlgoCanvas", "Anim render failed for ${gen.id}", e)
                            }

                            val canvas = holder.lockCanvas() ?: continue
                            canvas.drawColor(android.graphics.Color.BLACK)
                            val scale = minOf(canvas.width.toFloat() / size, canvas.height.toFloat() / size)
                            val dx = (canvas.width - size * scale) / 2f
                            val dy = (canvas.height - size * scale) / 2f
                            canvas.save()
                            canvas.translate(dx, dy)
                            canvas.scale(scale, scale)
                            canvas.drawBitmap(bitmap, 0f, 0f, null)
                            canvas.restore()

                            if (sf) {
                                fpsCounter++
                                val now = System.nanoTime()
                                if (now - lastFpsTime >= 1_000_000_000L) {
                                    currentFps = fpsCounter
                                    fpsCounter = 0
                                    lastFpsTime = now
                                }
                                canvas.drawText("$currentFps FPS", 16f, canvas.height - 16f, fpsPaint)
                            }

                            holder.unlockCanvasAndPost(canvas)

                            val elapsed = System.nanoTime() - frameStart
                            if (elapsed < targetNanos) {
                                Thread.sleep((targetNanos - elapsed) / 1_000_000)
                            }
                        }
                        bitmap.recycle()
                    }.also { it.start() }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    running = false
                    try { animThread?.join(500) } catch (_: Exception) {}
                }
            }
        },
        modifier = modifier
            .aspectRatio(1f)
    )
}

@Composable
private fun NeonProgressBar(modifier: Modifier = Modifier) {
    val neonGreen = Color(0xFF39FF14)
    val glowGreen = Color(0x6639FF14)
    val infiniteTransition = rememberInfiniteTransition(label = "neon")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "neonProgress"
    )

    ComposeCanvas(modifier = modifier) {
        val barWidth = size.width * 0.35f
        val x = progress * (size.width + barWidth) - barWidth

        // Glow layer
        drawRect(
            color = glowGreen,
            topLeft = Offset(x - 4f, -2f),
            size = Size(barWidth + 8f, size.height + 4f)
        )
        // Bright bar
        drawRect(
            color = neonGreen,
            topLeft = Offset(x, 0f),
            size = Size(barWidth, size.height)
        )
    }
}

/** Spot-check whether a bitmap is nearly all-black by sampling pixels. */
private fun isBitmapBlank(bitmap: Bitmap, size: Int): Boolean {
    val step = (size / 8).coerceAtLeast(1)
    var totalBrightness = 0L
    var samples = 0
    for (y in step until size step step) {
        for (x in step until size step step) {
            val px = bitmap.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            totalBrightness += r + g + b
            samples++
        }
    }
    if (samples == 0) return true
    // Average brightness per sample across R+G+B (max 765)
    // Threshold: if average < 3 (~0.4% brightness), consider blank
    return (totalBrightness / samples) < 3
}
