package com.artmondo.algomodo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val generatorId: String,
    val seed: Int,
    val paramsJson: String,
    val paletteName: String,
    val paletteColorsJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val thumbnail: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PresetEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
