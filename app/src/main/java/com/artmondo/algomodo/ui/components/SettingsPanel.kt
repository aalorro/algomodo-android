package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.generators.Quality
import com.artmondo.algomodo.rendering.PostFXSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    theme: String,
    quality: Quality,
    performanceMode: Boolean,
    showFps: Boolean,
    interactionEnabled: Boolean,
    animationFps: Int,
    postFX: PostFXSettings,
    onThemeChange: (String) -> Unit,
    onQualityChange: (Quality) -> Unit,
    onPerformanceModeChange: (Boolean) -> Unit,
    onShowFpsChange: (Boolean) -> Unit,
    onInteractionChange: (Boolean) -> Unit,
    onAnimationFpsChange: (Int) -> Unit,
    onPostFXChange: (PostFXSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Appearance
        SectionHeader("Appearance")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = theme == "dark", onClick = { onThemeChange("dark") }, shape = SegmentedButtonDefaults.itemShape(0, 3)) { Text("Dark") }
                SegmentedButton(selected = theme == "light", onClick = { onThemeChange("light") }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("Light") }
                SegmentedButton(selected = theme == "system", onClick = { onThemeChange("system") }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("Auto") }
            }
        }

        // Canvas
        SectionHeader("Canvas")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Quality", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(selected = quality == Quality.DRAFT, onClick = { onQualityChange(Quality.DRAFT) }, shape = SegmentedButtonDefaults.itemShape(0, 3)) { Text("Draft", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip) }
                SegmentedButton(selected = quality == Quality.BALANCED, onClick = { onQualityChange(Quality.BALANCED) }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("Mid", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip) }
                SegmentedButton(selected = quality == Quality.ULTRA, onClick = { onQualityChange(Quality.ULTRA) }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("Ultra", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Clip) }
            }
        }

        // Interaction
        SectionHeader("Interaction")
        SettingsSwitch("Touch interaction", interactionEnabled, onInteractionChange)

        // Performance
        SectionHeader("Performance")
        SettingsSwitch("Performance mode", performanceMode, onPerformanceModeChange)
        SettingsSwitch("Show FPS", showFps, onShowFpsChange)

        // Animation
        SectionHeader("Animation")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Target FPS", style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow {
                listOf(12, 24, 30, 60).forEachIndexed { idx, fps ->
                    SegmentedButton(
                        selected = animationFps == fps,
                        onClick = { onAnimationFpsChange(fps) },
                        shape = SegmentedButtonDefaults.itemShape(idx, 4)
                    ) { Text("$fps") }
                }
            }
        }

        // PostFX
        SectionHeader("Post FX")
        PostFXSlider("Grain", postFX.grain, 0f, 0.5f, 0.01f) {
            onPostFXChange(postFX.copy(grain = it))
        }
        PostFXSlider("Vignette", postFX.vignette, 0f, 2f, 0.1f) {
            onPostFXChange(postFX.copy(vignette = it))
        }
        PostFXIntSlider("Dither Levels", postFX.dither, 0, 16) {
            onPostFXChange(postFX.copy(dither = it))
        }
        PostFXIntSlider("Posterize Bits", postFX.posterize, 0, 8) {
            onPostFXChange(postFX.copy(posterize = it))
        }

    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PostFXSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = {
                val stepped = (Math.round(it / step) * step).coerceIn(min, max)
                onValueChange(stepped)
            },
            valueRange = min..max,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PostFXIntSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = max - min - 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
