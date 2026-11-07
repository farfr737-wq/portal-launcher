package com.portal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Палитра Portal (из ТЗ). */
object PC {
    val bg = Color(0xFF0B0F14)
    val panel = Color(0xFF121922)
    val panel2 = Color(0xFF192330)
    val accent = Color(0xFF5EEAD4)
    val text = Color(0xFFF1F5F9)
    val muted = Color(0xFF94A3B8)
    val outline = Color(0xFF2A3647)
    val danger = Color(0xFFF87171)
    val warn = Color(0xFFFBBF24)
}

@Composable
fun PortalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PC.accent, onPrimary = PC.bg,
            background = PC.bg, onBackground = PC.text,
            surface = PC.panel, onSurface = PC.text,
            surfaceVariant = PC.panel2, onSurfaceVariant = PC.muted,
            error = PC.danger, outline = PC.outline,
        ),
        content = content,
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PC.accent, contentColor = PC.bg,
            disabledContainerColor = PC.panel2, disabledContentColor = PC.muted,
        ),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (danger) PC.danger.copy(alpha = 0.6f) else PC.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) PC.danger else PC.text, disabledContentColor = PC.muted),
    ) { Text(text) }
}

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = PC.panel, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(color).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun H1(text: String) = Text(text, color = PC.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)

@Composable
fun H2(text: String) = Text(text, color = PC.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 13) =
    Text(text, color = PC.muted, fontSize = size.sp, modifier = modifier)

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape)
            .background(if (selected) PC.accent.copy(alpha = 0.16f) else PC.panel2)
            .border(1.dp, if (selected) PC.accent else PC.outline, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (selected) PC.accent else if (enabled) PC.text else PC.muted, fontSize = 13.sp)
    }
}

@Composable
fun PField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = true, enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        modifier = modifier, singleLine = singleLine, enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PC.accent, unfocusedBorderColor = PC.outline,
            focusedLabelColor = PC.accent, unfocusedLabelColor = PC.muted,
            cursorColor = PC.accent, focusedTextColor = PC.text, unfocusedTextColor = PC.text,
            disabledTextColor = PC.muted, disabledBorderColor = PC.outline,
        ),
    )
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = PC.text, fontSize = 14.sp)
            if (hint != null) Muted(hint, size = 12)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PC.bg, checkedTrackColor = PC.accent,
                uncheckedThumbColor = PC.muted, uncheckedTrackColor = PC.panel2, uncheckedBorderColor = PC.outline,
            ),
        )
    }
}

@Composable
fun SliderRow(label: String, value: Int, min: Int, max: Int, step: Int, unit: String, onChange: (Int) -> Unit, hint: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = PC.text, fontSize = 14.sp)
            Text("$value $unit", color = PC.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        val steps = ((max - min) / step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((Math.round(it / step) * step).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = PC.accent, activeTrackColor = PC.accent, inactiveTrackColor = PC.panel2),
        )
        if (hint != null) Muted(hint, size = 12)
    }
}

@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction == null) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth(), color = PC.accent, trackColor = PC.panel2)
    } else {
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth(), color = PC.accent, trackColor = PC.panel2)
    }
}

val PagePadding = PaddingValues(24.dp)
