package com.artmondo.algomodo.core.recipe

import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.rendering.PostFXSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class CanvasSettings(
    val width: Int = 1080,
    val height: Int = 1080,
    val background: String = "#000000",
    val devicePixelRatio: Int = 2,
    val quality: String = "balanced"
)

@Serializable
data class RecipeJson(
    val generatorId: String,
    val seed: Int,
    val params: JsonObject,
    val palette: PaletteJson,
    val canvasSettings: CanvasSettings = CanvasSettings(),
    val postFX: PostFXJson? = null,
    val version: String = "1.1.0"
)

@Serializable
data class PaletteJson(
    val name: String,
    val colors: List<String>
)

@Serializable
data class PostFXJson(
    val grain: Float = 0f,
    val vignette: Float = 0f,
    val dither: Int = 0,
    val posterize: Int = 0
)

object RecipeSerializer {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(
        generatorId: String,
        seed: Int,
        params: Map<String, Any>,
        palette: Palette,
        postFX: PostFXSettings,
        canvasSettings: CanvasSettings = CanvasSettings()
    ): String {
        val paramsObj = buildJsonObject {
            for ((key, value) in params) {
                when (value) {
                    is Float -> put(key, value)
                    is Double -> put(key, value)
                    is Int -> put(key, value)
                    is Boolean -> put(key, value)
                    is String -> put(key, value)
                    is Number -> put(key, value.toDouble())
                }
            }
        }

        val recipe = RecipeJson(
            generatorId = generatorId,
            seed = seed,
            params = paramsObj,
            palette = PaletteJson(palette.name, palette.colors),
            canvasSettings = canvasSettings,
            postFX = PostFXJson(postFX.grain, postFX.vignette, postFX.dither, postFX.posterize)
        )

        return json.encodeToString(RecipeJson.serializer(), recipe)
    }

    fun deserialize(jsonString: String): RecipeJson {
        return json.decodeFromString(RecipeJson.serializer(), jsonString)
    }

    fun recipeToParams(recipe: RecipeJson): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for ((key, element) in recipe.params) {
            when (element) {
                is JsonPrimitive -> {
                    when {
                        element.isString -> result[key] = element.content
                        element.booleanOrNull != null -> result[key] = element.boolean
                        element.floatOrNull != null -> result[key] = element.float
                        element.intOrNull != null -> result[key] = element.int
                    }
                }
                else -> {} // skip complex types
            }
        }
        return result
    }

    fun recipeToPalette(recipe: RecipeJson): Palette {
        return Palette(recipe.palette.name, recipe.palette.colors)
    }

    fun recipeToPostFX(recipe: RecipeJson): PostFXSettings {
        val fx = recipe.postFX ?: return PostFXSettings()
        return PostFXSettings(fx.grain, fx.vignette, fx.dither, fx.posterize)
    }
}
