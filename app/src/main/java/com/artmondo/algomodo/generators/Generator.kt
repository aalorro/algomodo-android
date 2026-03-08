package com.artmondo.algomodo.generators

import android.graphics.Bitmap
import android.graphics.Canvas
import com.artmondo.algomodo.data.palettes.Palette
import com.artmondo.algomodo.rendering.SvgPath

enum class Quality {
    DRAFT,
    BALANCED,
    ULTRA
}

enum class ParamGroup(val displayName: String) {
    COMPOSITION("Composition"),
    GEOMETRY("Geometry"),
    FLOW_MOTION("Flow/Motion"),
    TEXTURE("Texture"),
    COLOR("Color"),
    POSTFX("PostFX"),
    OTHER("Other")
}

sealed class Parameter {
    abstract val name: String
    abstract val key: String
    abstract val group: ParamGroup
    abstract val help: String?

    data class NumberParam(
        override val name: String,
        override val key: String,
        override val group: ParamGroup,
        override val help: String?,
        val min: Float,
        val max: Float,
        val step: Float,
        val default: Float
    ) : Parameter()

    data class BooleanParam(
        override val name: String,
        override val key: String,
        override val group: ParamGroup,
        override val help: String?,
        val default: Boolean
    ) : Parameter()

    data class SelectParam(
        override val name: String,
        override val key: String,
        override val group: ParamGroup,
        override val help: String?,
        val options: List<String>,
        val default: String
    ) : Parameter()

    data class ColorParam(
        override val name: String,
        override val key: String,
        override val group: ParamGroup,
        override val help: String?,
        val default: String
    ) : Parameter()

    data class TextParam(
        override val name: String,
        override val key: String,
        override val group: ParamGroup,
        override val help: String?,
        val default: String,
        val placeholder: String? = null,
        val maxLength: Int = 200
    ) : Parameter()
}

data class GeneratorFamily(
    val id: String,
    val displayName: String,
    val description: String,
    val generators: List<Generator>
)

interface Generator {
    val id: String
    val family: String
    val styleName: String
    val definition: String
    val algorithmNotes: String
    val parameterSchema: List<Parameter>
    val supportsVector: Boolean
    val supportsAnimation: Boolean

    fun getDefaultParams(): Map<String, Any>

    fun renderCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        params: Map<String, Any>,
        seed: Int,
        palette: Palette,
        quality: Quality,
        time: Float = 0f
    )

    fun renderVector(
        params: Map<String, Any>,
        seed: Int,
        palette: Palette
    ): List<SvgPath>? = null

    fun estimateCost(params: Map<String, Any>, quality: Quality): Float = 0.5f
}
