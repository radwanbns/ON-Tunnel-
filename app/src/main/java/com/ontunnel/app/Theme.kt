package com.ontunnel.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/** Current theme flag. Read inside composition, so every screen recomposes when it changes. */
internal object Pal {
    var dark by mutableStateOf(true)
}

internal val Bg: Color get() = if (Pal.dark) Color(0xFF0B1220) else Color(0xFFF4F7FB)
internal val SurfaceC: Color get() = if (Pal.dark) Color(0xFF131E33) else Color(0xFFFFFFFF)
internal val SurfaceHigh: Color get() = if (Pal.dark) Color(0xFF1B2A47) else Color(0xFFE0F3F9)
internal val Accent: Color get() = if (Pal.dark) Color(0xFF00C2E8) else Color(0xFF0088A8)
internal val GreenOk: Color get() = if (Pal.dark) Color(0xFF2ECC71) else Color(0xFF1E9E55)
internal val Amber: Color get() = if (Pal.dark) Color(0xFFF5A623) else Color(0xFFE08A00)
internal val RedErr: Color get() = if (Pal.dark) Color(0xFFFF5C6C) else Color(0xFFD93646)
internal val TextMain: Color get() = if (Pal.dark) Color(0xFFE8EEF7) else Color(0xFF0F1A2B)
internal val TextMuted: Color get() = if (Pal.dark) Color(0xFF8A9BB5) else Color(0xFF5B6B84)
/** Icon colour drawn on top of the big connect button. */
internal val OnHero: Color get() = if (Pal.dark) Color(0xFF06101F) else Color.White

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF00C2E8),
    onPrimary = Color(0xFF00232B),
    secondary = Color(0xFF00C2E8),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE8EEF7),
    surface = Color(0xFF131E33),
    onSurface = Color(0xFFE8EEF7),
    surfaceVariant = Color(0xFF1B2A47),
    onSurfaceVariant = Color(0xFF8A9BB5),
    outline = Color(0xFF3A4A66),
    error = Color(0xFFFF5C6C)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0088A8),
    onPrimary = Color.White,
    secondary = Color(0xFF0088A8),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F1A2B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1A2B),
    surfaceVariant = Color(0xFFE0F3F9),
    onSurfaceVariant = Color(0xFF5B6B84),
    outline = Color(0xFFB4C0D2),
    error = Color(0xFFD93646)
)

internal fun appColorScheme(dark: Boolean) = if (dark) DarkScheme else LightScheme

@Composable
internal fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun Choice(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); open = false }
                )
            }
        }
    }
}
