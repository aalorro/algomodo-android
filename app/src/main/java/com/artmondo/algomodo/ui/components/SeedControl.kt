package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.ui.theme.AccentAmber

@Composable
fun SeedControl(
    seed: Int,
    isLocked: Boolean,
    onSeedChange: (Int) -> Unit,
    onToggleLock: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var textValue by remember(seed) { mutableStateOf(seed.toString()) }
    val textColor = MaterialTheme.colorScheme.onSurface
    val borderColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Seed", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = textValue,
                onValueChange = { newVal ->
                    textValue = newVal.filter { it.isDigit() }.take(6)
                    textValue.toIntOrNull()?.let { onSeedChange(it.coerceIn(0, 999999)) }
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = textColor
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        IconButton(onClick = { onToggleLock(!isLocked) }, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (isLocked) "Seed locked" else "Seed unlocked",
                tint = if (isLocked) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        IconButton(onClick = { onSeedChange((Math.random() * 999999).toInt()) }, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Casino,
                contentDescription = "Random seed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
