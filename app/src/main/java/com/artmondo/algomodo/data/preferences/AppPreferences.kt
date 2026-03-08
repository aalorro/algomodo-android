package com.artmondo.algomodo.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "algomodo_settings")

class AppPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val PERFORMANCE_MODE = booleanPreferencesKey("performanceMode")
        val SHOW_FPS = booleanPreferencesKey("showFps")
        val SEED_LOCKED = booleanPreferencesKey("seedLocked")
        val CANVAS_WIDTH = intPreferencesKey("canvasWidth")
        val CANVAS_HEIGHT = intPreferencesKey("canvasHeight")
        val QUALITY = stringPreferencesKey("quality")
        val ANIMATION_FPS = intPreferencesKey("animationFps")
        val INTERACTION_ENABLED = booleanPreferencesKey("interactionEnabled")
    }

    val theme: Flow<String> = dataStore.data.map { it[THEME] ?: "dark" }
    val performanceMode: Flow<Boolean> = dataStore.data.map { it[PERFORMANCE_MODE] ?: false }
    val showFps: Flow<Boolean> = dataStore.data.map { it[SHOW_FPS] ?: false }
    val seedLocked: Flow<Boolean> = dataStore.data.map { it[SEED_LOCKED] ?: false }
    val canvasWidth: Flow<Int> = dataStore.data.map { it[CANVAS_WIDTH] ?: 1080 }
    val canvasHeight: Flow<Int> = dataStore.data.map { it[CANVAS_HEIGHT] ?: 1080 }
    val quality: Flow<String> = dataStore.data.map { it[QUALITY] ?: "balanced" }
    val animationFps: Flow<Int> = dataStore.data.map { it[ANIMATION_FPS] ?: 24 }
    val interactionEnabled: Flow<Boolean> = dataStore.data.map { it[INTERACTION_ENABLED] ?: false }

    suspend fun setTheme(value: String) { dataStore.edit { it[THEME] = value } }
    suspend fun setPerformanceMode(value: Boolean) { dataStore.edit { it[PERFORMANCE_MODE] = value } }
    suspend fun setShowFps(value: Boolean) { dataStore.edit { it[SHOW_FPS] = value } }
    suspend fun setSeedLocked(value: Boolean) { dataStore.edit { it[SEED_LOCKED] = value } }
    suspend fun setCanvasWidth(value: Int) { dataStore.edit { it[CANVAS_WIDTH] = value } }
    suspend fun setCanvasHeight(value: Int) { dataStore.edit { it[CANVAS_HEIGHT] = value } }
    suspend fun setQuality(value: String) { dataStore.edit { it[QUALITY] = value } }
    suspend fun setAnimationFps(value: Int) { dataStore.edit { it[ANIMATION_FPS] = value } }
    suspend fun setInteractionEnabled(value: Boolean) { dataStore.edit { it[INTERACTION_ENABLED] = value } }
}
