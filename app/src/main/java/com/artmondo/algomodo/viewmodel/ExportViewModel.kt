package com.artmondo.algomodo.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.export.*
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.PostFXProcessor
import com.artmondo.algomodo.rendering.PostFXSettings
import com.artmondo.algomodo.rendering.SvgBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ExportUiState(
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportFormat: String = "png",
    val gifDuration: Int = 5,
    val gifResolution: Int = 600,
    val gifBoomerang: Boolean = false,
    val gifEndless: Boolean = true,
    val videoDuration: Int = 15,
    val lastExportUri: Uri? = null,
    val error: String? = null
)

@HiltViewModel
class ExportViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    fun setExportFormat(format: String) {
        _state.update { it.copy(exportFormat = format) }
    }

    fun setGifDuration(duration: Int) {
        _state.update { it.copy(gifDuration = duration) }
    }

    fun setGifResolution(resolution: Int) {
        _state.update { it.copy(gifResolution = resolution) }
    }

    fun setGifBoomerang(boomerang: Boolean) {
        _state.update { it.copy(gifBoomerang = boomerang, gifEndless = if (boomerang) false else it.gifEndless) }
    }

    fun setGifEndless(endless: Boolean) {
        _state.update { it.copy(gifEndless = endless, gifBoomerang = if (endless) false else it.gifBoomerang) }
    }

    fun setVideoDuration(duration: Int) {
        _state.update { it.copy(videoDuration = duration.coerceIn(1, 60)) }
    }

    fun quickSave(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        postFX: PostFXSettings,
        isAnimating: Boolean,
        snapshotTime: Float = 0f
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isExporting = true, error = null) }
            try {
                val size = 1080
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, snapshotTime)
                PostFXProcessor.apply(bitmap, postFX)

                val uri = PngExporter.export(context, bitmap, "algomodo_${System.currentTimeMillis()}")
                _state.update { it.copy(isExporting = false, lastExportUri = uri) }
                bitmap.recycle()
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, error = e.message) }
            }
        }
    }

    fun exportPng(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        postFX: PostFXSettings,
        width: Int,
        height: Int,
        snapshotTime: Float = 0f
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isExporting = true, error = null) }
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, snapshotTime)
                PostFXProcessor.apply(bitmap, postFX)

                val uri = PngExporter.export(context, bitmap, "algomodo_${System.currentTimeMillis()}")
                _state.update { it.copy(isExporting = false, lastExportUri = uri) }
                bitmap.recycle()
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, error = e.message) }
            }
        }
    }

    fun exportJpg(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        postFX: PostFXSettings,
        width: Int,
        height: Int,
        snapshotTime: Float = 0f
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isExporting = true, error = null) }
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                // Fill white background for JPG
                canvas.drawColor(android.graphics.Color.WHITE)
                generator.renderCanvas(canvas, bitmap, params, seed, palette, quality, snapshotTime)
                PostFXProcessor.apply(bitmap, postFX)

                val uri = JpgExporter.export(context, bitmap, "algomodo_${System.currentTimeMillis()}")
                _state.update { it.copy(isExporting = false, lastExportUri = uri) }
                bitmap.recycle()
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, error = e.message) }
            }
        }
    }

    fun exportSvg(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        width: Int,
        height: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isExporting = true, error = null) }
            try {
                val paths = generator.renderVector(params, seed, palette)
                if (paths != null) {
                    val svgContent = SvgBuilder.build(paths, width, height)
                    val uri = SvgExporter.export(context, svgContent, "algomodo_${System.currentTimeMillis()}")
                    _state.update { it.copy(isExporting = false, lastExportUri = uri) }
                } else {
                    _state.update { it.copy(isExporting = false, error = "Generator does not support SVG export") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, error = e.message) }
            }
        }
    }

    fun exportGif(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        fps: Int
    ) {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isExporting = true, error = null) }
            try {
                val uri = GifExporter.export(
                    context = context,
                    generator = generator,
                    params = params,
                    seed = seed,
                    palette = palette,
                    quality = quality,
                    resolution = s.gifResolution,
                    durationSeconds = s.gifDuration,
                    fps = fps,
                    boomerang = s.gifBoomerang,
                    endless = s.gifEndless,
                    fileName = "algomodo_${System.currentTimeMillis()}",
                    onProgress = { p ->
                        _state.update { it.copy(exportProgress = p) }
                    }
                )
                _state.update { it.copy(isExporting = false, exportProgress = 0f, lastExportUri = uri) }
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, exportProgress = 0f, error = e.message ?: "GIF export failed") }
            }
        }
    }

    fun exportVideo(
        context: Context,
        generator: Generator,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        fps: Int
    ) {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isExporting = true, error = null) }
            try {
                val resolution = s.gifResolution // use same resolution setting
                val uri = VideoExporter.export(
                    context = context,
                    generator = generator,
                    params = params,
                    seed = seed,
                    palette = palette,
                    quality = quality,
                    width = resolution,
                    height = resolution,
                    fps = fps,
                    durationSeconds = s.videoDuration,
                    fileName = "algomodo_${System.currentTimeMillis()}",
                    onProgress = { p ->
                        _state.update { it.copy(exportProgress = p) }
                    }
                )
                if (uri != null) {
                    _state.update { it.copy(isExporting = false, exportProgress = 0f, lastExportUri = uri) }
                } else {
                    _state.update { it.copy(isExporting = false, exportProgress = 0f, error = "MP4 export failed") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isExporting = false, exportProgress = 0f, error = e.message ?: "MP4 export failed") }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearLastExport() {
        _state.update { it.copy(lastExportUri = null) }
    }
}
