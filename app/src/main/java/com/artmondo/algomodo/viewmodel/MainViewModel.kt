package com.artmondo.algomodo.viewmodel

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artmondo.algomodo.core.recipe.CanvasSettings
import com.artmondo.algomodo.core.recipe.RecipeSerializer
import com.artmondo.algomodo.core.registry.GeneratorRegistry
import com.artmondo.algomodo.core.rng.SeededRNG
import com.artmondo.algomodo.data.db.PresetDao
import com.artmondo.algomodo.data.db.PresetEntity
import com.artmondo.algomodo.data.palettes.CuratedPalettes
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.data.preferences.AppPreferences
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.PostFXSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject

data class HistorySnapshot(
    val params: Map<String, Any>,
    val palette: Palette,
    val seed: Int,
    val selectedGeneratorId: String,
    val selectedFamilyId: String,
    val postFX: PostFXSettings
)

data class MainUiState(
    val generator: Generator? = null,
    val familyId: String = "noise",
    val params: Map<String, Any> = emptyMap(),
    val seed: Int = 0,
    val palette: Palette = CuratedPalettes.default,
    val postFX: PostFXSettings = PostFXSettings(),
    val quality: Quality = Quality.DRAFT,
    val isAnimating: Boolean = false,
    val animationFps: Int = 24,
    val seedLocked: Boolean = false,
    val lockedParams: Set<String> = emptySet(),
    val sourceImage: Bitmap? = null,
    val theme: String = "dark",
    val performanceMode: Boolean = false,
    val showFps: Boolean = false,
    val interactionEnabled: Boolean = false,
    val renderTrigger: Int = 0, // increment to force re-render
    val activeTab: Int = 0 // 0=generators, 1=params, 2=export, 3=settings
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val presetDao: PresetDao,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    val presets: Flow<List<PresetEntity>> = presetDao.getAllPresets()

    // Undo/Redo history
    private val undoStack = ArrayDeque<HistorySnapshot>(50)
    private val redoStack = ArrayDeque<HistorySnapshot>(50)

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    init {
        viewModelScope.launch {
            // Load preferences
            launch { prefs.theme.collect { _state.update { s -> s.copy(theme = it) } } }
            launch { prefs.performanceMode.collect { _state.update { s -> s.copy(performanceMode = it) } } }
            launch { prefs.showFps.collect { _state.update { s -> s.copy(showFps = it) } } }
            launch { prefs.seedLocked.collect { _state.update { s -> s.copy(seedLocked = it) } } }
            launch { prefs.animationFps.collect { _state.update { s -> s.copy(animationFps = it) } } }
            launch { prefs.interactionEnabled.collect { _state.update { s -> s.copy(interactionEnabled = it) } } }
            launch {
                prefs.quality.collect { q ->
                    val quality = when (q) {
                        "draft" -> Quality.DRAFT
                        "ultra" -> Quality.ULTRA
                        else -> Quality.BALANCED
                    }
                    _state.update { s -> s.copy(quality = quality) }
                }
            }
        }

        // Initialize with random generator and seed
        val randomSeed = (Math.random() * 999999).toInt()
        val randomGen = GeneratorRegistry.randomNonImageGenerator()
        if (randomGen != null) {
            _state.update {
                it.copy(
                    generator = randomGen,
                    familyId = randomGen.family,
                    params = randomGen.getDefaultParams(),
                    seed = randomSeed,
                    palette = CuratedPalettes.all.random()
                )
            }
        } else {
            _state.update { it.copy(seed = randomSeed) }
        }
    }

    private fun pushHistory() {
        val s = _state.value
        val gen = s.generator ?: return
        if (undoStack.size >= 50) undoStack.removeFirst()
        undoStack.addLast(
            HistorySnapshot(s.params, s.palette, s.seed, gen.id, s.familyId, s.postFX)
        )
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val s = _state.value
        val gen = s.generator ?: return
        if (redoStack.size >= 50) redoStack.removeFirst()
        redoStack.addLast(
            HistorySnapshot(s.params, s.palette, s.seed, gen.id, s.familyId, s.postFX)
        )
        val snapshot = undoStack.removeLast()
        val restoredGen = GeneratorRegistry.get(snapshot.selectedGeneratorId) ?: gen
        _state.update {
            it.copy(
                generator = restoredGen,
                familyId = snapshot.selectedFamilyId,
                params = snapshot.params,
                seed = snapshot.seed,
                palette = snapshot.palette,
                postFX = snapshot.postFX,
                renderTrigger = it.renderTrigger + 1
            )
        }
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val s = _state.value
        val gen = s.generator ?: return
        if (undoStack.size >= 50) undoStack.removeFirst()
        undoStack.addLast(
            HistorySnapshot(s.params, s.palette, s.seed, gen.id, s.familyId, s.postFX)
        )
        val snapshot = redoStack.removeLast()
        val restoredGen = GeneratorRegistry.get(snapshot.selectedGeneratorId) ?: gen
        _state.update {
            it.copy(
                generator = restoredGen,
                familyId = snapshot.selectedFamilyId,
                params = snapshot.params,
                seed = snapshot.seed,
                palette = snapshot.palette,
                postFX = snapshot.postFX,
                renderTrigger = it.renderTrigger + 1
            )
        }
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun selectGenerator(generator: Generator) {
        pushHistory()
        _state.update {
            it.copy(
                generator = generator,
                familyId = generator.family,
                params = generator.getDefaultParams(),
                renderTrigger = it.renderTrigger + 1
            )
        }
    }

    fun selectFamily(familyId: String) {
        _state.update { it.copy(familyId = familyId) }
    }

    fun updateParam(key: String, value: Any) {
        pushHistory()
        _state.update {
            it.copy(
                params = it.params + (key to value),
                renderTrigger = it.renderTrigger + 1
            )
        }
    }

    fun setSeed(seed: Int) {
        pushHistory()
        _state.update { it.copy(seed = seed.coerceIn(0, 999999), renderTrigger = it.renderTrigger + 1) }
    }

    fun setPalette(palette: Palette) {
        pushHistory()
        _state.update { it.copy(palette = palette, renderTrigger = it.renderTrigger + 1) }
    }

    fun setPostFX(postFX: PostFXSettings) {
        pushHistory()
        _state.update { it.copy(postFX = postFX, renderTrigger = it.renderTrigger + 1) }
    }

    fun toggleAnimation() {
        _state.update { it.copy(isAnimating = !it.isAnimating) }
    }

    fun setAnimating(animating: Boolean) {
        _state.update { it.copy(isAnimating = animating) }
    }

    fun randomize() {
        pushHistory()
        val s = _state.value
        val gen = s.generator ?: return
        val rng = SeededRNG((Math.random() * 999999).toInt())

        val newSeed = if (s.seedLocked) s.seed else rng.integer(0, 999999)
        val newPalette = if ("palette" in s.lockedParams) s.palette else CuratedPalettes.all[rng.integer(0, CuratedPalettes.all.size - 1)]

        val newParams = s.params.toMutableMap()
        for (param in gen.parameterSchema) {
            if (param.key in s.lockedParams) continue
            when (param) {
                is Parameter.NumberParam -> {
                    val steps = ((param.max - param.min) / param.step).toInt()
                    newParams[param.key] = param.min + rng.integer(0, steps) * param.step
                }
                is Parameter.BooleanParam -> {
                    newParams[param.key] = rng.boolean()
                }
                is Parameter.SelectParam -> {
                    newParams[param.key] = rng.pick(param.options)
                }
                is Parameter.TextParam, is Parameter.ColorParam -> {
                    // Never randomize text or color params
                }
            }
        }

        _state.update {
            it.copy(seed = newSeed, palette = newPalette, params = newParams, renderTrigger = it.renderTrigger + 1)
        }
    }

    fun surpriseMe() {
        pushHistory()
        val rng = SeededRNG((Math.random() * 999999).toInt())
        val gen = GeneratorRegistry.randomNonImageGenerator() ?: return
        val newSeed = rng.integer(0, 999999)
        val newPalette = CuratedPalettes.all[rng.integer(0, CuratedPalettes.all.size - 1)]

        val newParams = mutableMapOf<String, Any>()
        for (param in gen.parameterSchema) {
            when (param) {
                is Parameter.NumberParam -> {
                    val steps = ((param.max - param.min) / param.step).toInt()
                    newParams[param.key] = param.min + rng.integer(0, steps) * param.step
                }
                is Parameter.BooleanParam -> newParams[param.key] = rng.boolean()
                is Parameter.SelectParam -> newParams[param.key] = rng.pick(param.options)
                is Parameter.TextParam -> newParams[param.key] = param.default
                is Parameter.ColorParam -> newParams[param.key] = param.default
            }
        }

        _state.update {
            it.copy(
                generator = gen,
                familyId = gen.family,
                seed = newSeed,
                palette = newPalette,
                params = newParams,
                renderTrigger = it.renderTrigger + 1,
                activeTab = 0
            )
        }
    }

    fun reload() {
        _state.update { it.copy(renderTrigger = it.renderTrigger + 1) }
    }

    fun toggleParamLock(key: String) {
        _state.update {
            val newLocked = if (key in it.lockedParams) it.lockedParams - key else it.lockedParams + key
            it.copy(lockedParams = newLocked)
        }
    }

    fun setSourceImage(bitmap: Bitmap?) {
        _state.update { it.copy(sourceImage = bitmap, renderTrigger = it.renderTrigger + 1) }
    }

    fun setActiveTab(tab: Int) {
        _state.update { it.copy(activeTab = tab) }
    }

    // Settings
    fun setTheme(theme: String) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    fun setQuality(quality: Quality) {
        viewModelScope.launch {
            prefs.setQuality(when (quality) {
                Quality.DRAFT -> "draft"
                Quality.BALANCED -> "balanced"
                Quality.ULTRA -> "ultra"
            })
        }
        _state.update { it.copy(quality = quality, renderTrigger = it.renderTrigger + 1) }
    }

    fun setPerformanceMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setPerformanceMode(enabled) }
    }

    fun setShowFps(show: Boolean) {
        viewModelScope.launch { prefs.setShowFps(show) }
    }

    fun setAnimationFps(fps: Int) {
        viewModelScope.launch { prefs.setAnimationFps(fps) }
        _state.update { it.copy(animationFps = fps) }
    }

    fun setSeedLocked(locked: Boolean) {
        viewModelScope.launch { prefs.setSeedLocked(locked) }
        _state.update { it.copy(seedLocked = locked) }
    }

    fun setInteractionEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setInteractionEnabled(enabled) }
    }

    // Presets
    fun savePreset(name: String) {
        val s = _state.value
        val gen = s.generator ?: return
        val paramsJson = buildJsonObject {
            for ((key, value) in s.params) {
                when (value) {
                    is Float -> put(key, value)
                    is Double -> put(key, value)
                    is Int -> put(key, value)
                    is Boolean -> put(key, value)
                    is String -> put(key, value)
                }
            }
        }.toString()
        val colorsJson = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
            s.palette.colors
        )
        viewModelScope.launch(Dispatchers.Default) {
            // Render a small thumbnail
            val thumbBytes = try {
                val thumbSize = 120
                val thumbBitmap = Bitmap.createBitmap(thumbSize, thumbSize, Bitmap.Config.ARGB_8888)
                val thumbCanvas = Canvas(thumbBitmap)
                thumbCanvas.drawColor(android.graphics.Color.BLACK)
                val renderParams = if (s.sourceImage != null)
                    s.params + ("_sourceImage" to s.sourceImage) else s.params
                gen.renderCanvas(thumbCanvas, thumbBitmap, renderParams, s.seed, s.palette, Quality.DRAFT, 0f)
                val stream = java.io.ByteArrayOutputStream()
                thumbBitmap.compress(Bitmap.CompressFormat.PNG, 80, stream)
                thumbBitmap.recycle()
                stream.toByteArray()
            } catch (e: Exception) {
                null
            }

            presetDao.insertPreset(
                PresetEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    generatorId = gen.id,
                    seed = s.seed,
                    paramsJson = paramsJson,
                    paletteName = s.palette.name,
                    paletteColorsJson = colorsJson,
                    thumbnail = thumbBytes
                )
            )
        }
    }

    fun loadPreset(preset: PresetEntity) {
        pushHistory()
        val gen = GeneratorRegistry.get(preset.generatorId) ?: return
        val paramsObj = Json.parseToJsonElement(preset.paramsJson).jsonObject
        val params = mutableMapOf<String, Any>()
        for ((key, element) in paramsObj) {
            when {
                element is JsonPrimitive && element.isString -> params[key] = element.content
                element is JsonPrimitive && element.booleanOrNull != null -> params[key] = element.boolean
                element is JsonPrimitive && element.floatOrNull != null -> params[key] = element.float
                element is JsonPrimitive && element.intOrNull != null -> params[key] = element.int
            }
        }
        val colors: List<String> = Json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
            preset.paletteColorsJson
        )
        _state.update {
            it.copy(
                generator = gen,
                familyId = gen.family,
                params = params,
                seed = preset.seed,
                palette = Palette(preset.paletteName, colors),
                renderTrigger = it.renderTrigger + 1
            )
        }
    }

    fun deletePreset(presetId: String) {
        viewModelScope.launch { presetDao.deleteById(presetId) }
    }

    fun exportRecipeJson(): String {
        val s = _state.value
        val gen = s.generator ?: return "{}"
        return RecipeSerializer.serialize(gen.id, s.seed, s.params, s.palette, s.postFX)
    }

    fun exportPresetsText(callback: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            val allPresets = presetDao.getAllPresetsSync()
            val sb = StringBuilder()
            for ((index, p) in allPresets.withIndex()) {
                if (index > 0) sb.append("\n")
                sb.appendLine("=== ALGOMODO PRESET ===")
                sb.appendLine("id: ${p.id}")
                sb.appendLine("name: ${p.name}")
                sb.appendLine("generator: ${p.generatorId}")
                sb.appendLine("seed: ${p.seed}")
                sb.appendLine("palette: ${p.paletteName}")
                // Colors: parse JSON array to comma-separated hex
                try {
                    val colors: List<String> = Json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                        p.paletteColorsJson
                    )
                    sb.appendLine("colors: ${colors.joinToString(", ")}")
                } catch (_: Exception) {
                    sb.appendLine("colors: ")
                }
                sb.appendLine("[params]")
                // Params: parse JSON object to key = value lines
                try {
                    val paramsObj = Json.parseToJsonElement(p.paramsJson).jsonObject
                    for ((key, element) in paramsObj) {
                        when {
                            element is JsonPrimitive && element.isString ->
                                sb.appendLine("$key = \"${element.content}\"")
                            else ->
                                sb.appendLine("$key = $element")
                        }
                    }
                } catch (_: Exception) {}
                sb.appendLine("=== END ===")
            }
            callback(sb.toString())
        }
    }

    fun importPresetsText(text: String): Int {
        // Detect format: plain text vs legacy JSON
        val trimmed = text.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return importPresetsLegacyJson(trimmed)
        }
        var count = 0
        try {
            // Split into preset blocks
            val blocks = trimmed.split("=== END ===")
            viewModelScope.launch(Dispatchers.IO) {
                for (block in blocks) {
                    val presetStart = block.indexOf("=== ALGOMODO PRESET ===")
                    if (presetStart < 0) continue
                    val content = block.substring(presetStart + "=== ALGOMODO PRESET ===".length).trim()
                    val lines = content.lines()

                    var name = ""
                    var generatorId = ""
                    var seed = 0
                    var paletteName = ""
                    var colors = listOf<String>()
                    val params = mutableMapOf<String, Any>()
                    var inParams = false

                    for (line in lines) {
                        val l = line.trim()
                        if (l.isEmpty()) continue
                        if (l == "[params]") { inParams = true; continue }

                        if (!inParams) {
                            when {
                                l.startsWith("name: ") -> name = l.removePrefix("name: ")
                                l.startsWith("generator: ") -> generatorId = l.removePrefix("generator: ")
                                l.startsWith("seed: ") -> seed = l.removePrefix("seed: ").toIntOrNull() ?: 0
                                l.startsWith("palette: ") -> paletteName = l.removePrefix("palette: ")
                                l.startsWith("colors: ") -> {
                                    colors = l.removePrefix("colors: ")
                                        .split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                }
                            }
                        } else {
                            // Parse key = value
                            val eqIdx = l.indexOf(" = ")
                            if (eqIdx < 0) continue
                            val key = l.substring(0, eqIdx).trim()
                            val raw = l.substring(eqIdx + 3).trim()
                            val value: Any = when {
                                raw.startsWith("\"") && raw.endsWith("\"") ->
                                    raw.substring(1, raw.length - 1)
                                raw == "true" -> true
                                raw == "false" -> false
                                raw.contains(".") -> raw.toFloatOrNull() ?: raw
                                else -> raw.toIntOrNull() ?: raw
                            }
                            params[key] = value
                        }
                    }

                    if (name.isEmpty() || generatorId.isEmpty()) continue

                    // Convert params map to JSON string
                    val paramsJson = buildJsonObject {
                        for ((k, v) in params) {
                            when (v) {
                                is Float -> put(k, v)
                                is Int -> put(k, v)
                                is Boolean -> put(k, v)
                                is String -> put(k, v)
                            }
                        }
                    }.toString()

                    // Convert colors list to JSON array string
                    val colorsJson = Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                        colors
                    )

                    presetDao.insertPreset(
                        PresetEntity(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            generatorId = generatorId,
                            seed = seed,
                            paramsJson = paramsJson,
                            paletteName = paletteName,
                            paletteColorsJson = colorsJson
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Algomodo", "Failed to import presets", e)
        }
        return count
    }

    private fun importPresetsLegacyJson(json: String): Int {
        var count = 0
        try {
            val arr = Json.parseToJsonElement(json).jsonArray
            viewModelScope.launch(Dispatchers.IO) {
                for (elem in arr) {
                    val obj = elem.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val generatorId = obj["generatorId"]?.jsonPrimitive?.contentOrNull ?: continue
                    val seed = obj["seed"]?.jsonPrimitive?.intOrNull ?: 0
                    val paramsJson = obj["paramsJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    val paletteName = obj["paletteName"]?.jsonPrimitive?.contentOrNull ?: ""
                    val paletteColorsJson = obj["paletteColorsJson"]?.jsonPrimitive?.contentOrNull ?: "[]"
                    presetDao.insertPreset(
                        PresetEntity(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            generatorId = generatorId,
                            seed = seed,
                            paramsJson = paramsJson,
                            paletteName = paletteName,
                            paletteColorsJson = paletteColorsJson
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Algomodo", "Failed to import legacy JSON presets", e)
        }
        return count
    }

    fun importRecipe(jsonString: String): Boolean {
        return try {
            val recipe = RecipeSerializer.deserialize(jsonString)
            val gen = GeneratorRegistry.get(recipe.generatorId) ?: return false
            pushHistory()
            _state.update {
                it.copy(
                    generator = gen,
                    familyId = gen.family,
                    params = RecipeSerializer.recipeToParams(recipe),
                    seed = recipe.seed,
                    palette = RecipeSerializer.recipeToPalette(recipe),
                    postFX = RecipeSerializer.recipeToPostFX(recipe),
                    renderTrigger = it.renderTrigger + 1
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

}
