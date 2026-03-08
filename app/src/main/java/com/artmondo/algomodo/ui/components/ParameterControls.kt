package com.artmondo.algomodo.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artmondo.algomodo.generators.Generator
import com.artmondo.algomodo.generators.ParamGroup
import com.artmondo.algomodo.generators.Parameter
import com.artmondo.algomodo.ui.theme.AccentAmber

@Composable
fun ParameterControls(
    generator: Generator?,
    params: Map<String, Any>,
    lockedParams: Set<String>,
    onParamChange: (String, Any) -> Unit,
    onToggleLock: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (generator == null) {
        Box(modifier = modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("No generator selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val schema = generator.parameterSchema
    val grouped = schema.groupBy { it.group }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for ((group, parameters) in grouped.entries.sortedBy { it.key.ordinal }) {
            item {
                Text(
                    text = group.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(parameters) { param ->
                ParameterRow(
                    parameter = param,
                    value = params[param.key],
                    isLocked = param.key in lockedParams,
                    onValueChange = { onParamChange(param.key, it) },
                    onToggleLock = { onToggleLock(param.key) }
                )
            }
        }
    }
}

@Composable
private fun ParameterRow(
    parameter: Parameter,
    value: Any?,
    isLocked: Boolean,
    onValueChange: (Any) -> Unit,
    onToggleLock: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onToggleLock,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (isLocked) "Locked" else "Unlocked",
                tint = if (isLocked) AccentAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            when (parameter) {
                is Parameter.NumberParam -> NumberControl(parameter, value, onValueChange)
                is Parameter.BooleanParam -> BooleanControl(parameter, value, onValueChange)
                is Parameter.SelectParam -> SelectControl(parameter, value, onValueChange)
                is Parameter.TextParam -> TextControl(parameter, value, onValueChange)
                is Parameter.ColorParam -> ColorControl(parameter, value, onValueChange)
            }
        }
    }
}

@Composable
private fun NumberControl(
    param: Parameter.NumberParam,
    value: Any?,
    onValueChange: (Any) -> Unit
) {
    val currentValue = (value as? Number)?.toFloat() ?: param.default

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(param.name, style = MaterialTheme.typography.bodySmall)
            Text(
                "%.2f".format(currentValue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = currentValue,
            onValueChange = { newVal ->
                val stepped = (Math.round(newVal / param.step) * param.step)
                    .coerceIn(param.min, param.max)
                onValueChange(stepped)
            },
            valueRange = param.min..param.max,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BooleanControl(
    param: Parameter.BooleanParam,
    value: Any?,
    onValueChange: (Any) -> Unit
) {
    val currentValue = value as? Boolean ?: param.default

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(param.name, style = MaterialTheme.typography.bodySmall)
        Switch(
            checked = currentValue,
            onCheckedChange = { onValueChange(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectControl(
    param: Parameter.SelectParam,
    value: Any?,
    onValueChange: (Any) -> Unit
) {
    val currentValue = value as? String ?: param.default
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(param.name, style = MaterialTheme.typography.bodySmall)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = currentValue,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                param.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontSize = 14.sp) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TextControl(
    param: Parameter.TextParam,
    value: Any?,
    onValueChange: (Any) -> Unit
) {
    val currentValue = value as? String ?: param.default

    Column {
        Text(param.name, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = currentValue,
            onValueChange = { onValueChange(it.take(param.maxLength)) },
            placeholder = param.placeholder?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true
        )
    }
}

@Composable
private fun ColorControl(
    param: Parameter.ColorParam,
    value: Any?,
    onValueChange: (Any) -> Unit
) {
    val currentValue = value as? String ?: param.default

    Column {
        Text(param.name, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = currentValue,
            onValueChange = { newVal ->
                if (newVal.matches(Regex("^#[0-9A-Fa-f]{0,6}$"))) {
                    onValueChange(newVal)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true
        )
    }
}
