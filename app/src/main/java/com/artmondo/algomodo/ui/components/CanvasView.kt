package com.artmondo.algomodo.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
            modifier = modifier
        )
    } else {
        StaticCanvas(
            generator = generator,
            params = params,
            seed = seed,
            palette = palette,
            quality = quality,
            postFX = postFX,
            renderTrigger = renderTrigger,
            modifier = modifier
        )
    }
}

@Composable
private fun StaticCanvas(
    generator: Generator,
    params: Map<String, Any>,
    seed: Int,
    palette: Palette,
    quality: Quality,
    postFX: PostFXSettings,
    renderTrigger: Int,
    modifier: Modifier = Modifier
) {
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(generator.id, params, seed, palette, quality, postFX, renderTrigger) {
        withContext(Dispatchers.Default) {
            val size = when (quality) {
                Quality.DRAFT -> 360
                Quality.BALANCED -> 540
                Quality.ULTRA -> 810
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.BLACK)
            try {
                val staticTime = if (generator.supportsAnimation) 2.0f else 0f
                generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, staticTime)
                PostFXProcessor.apply(bitmap, postFX)
            } catch (e: Exception) {
                android.util.Log.e("AlgoCanvas", "Render failed for ${generator.id}", e)
                // Draw red X on error so user sees something went wrong
                val errPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.RED
                    strokeWidth = 4f
                    style = android.graphics.Paint.Style.STROKE
                }
                canvas.drawLine(0f, 0f, size.toFloat(), size.toFloat(), errPaint)
                canvas.drawLine(size.toFloat(), 0f, 0f, size.toFloat(), errPaint)
            }
            renderedBitmap?.recycle()
            renderedBitmap = bitmap
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
