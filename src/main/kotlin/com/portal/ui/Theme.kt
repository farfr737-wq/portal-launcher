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
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Палитра Portal (из ТЗ). Цвета можно менять в Настройки → Оформление; значения читаются как состояние Compose. */
object PC {
    val defaults = mapOf(
        "bg" to 0xFF0B0F14L, "panel" to 0xFF121922L, "panel2" to 0xFF192330L, "accent" to 0xFF5EEAD4L,
        "text" to 0xFFF1F5F9L, "muted" to 0xFF94A3B8L, "outline" to 0xFF2A3647L,
    )

    private var cfg by mutableStateOf(com.portal.settings.SettingsStore.value)

    /** Подписка на изменения настроек (вызывается один раз при старте). */
    fun bind() {
        cfg = com.portal.settings.SettingsStore.value
        I18n.lang = cfg.language
        com.portal.settings.SettingsStore.onChange = { s -> cfg = s; I18n.lang = s.language }
    }

    fun parse(hex: String): Color? {
        val h = hex.trim().removePrefix("#")
        if (h.length != 6 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return Color(0xFF000000 or h.toLong(16))
    }

    private fun pick(custom: String, key: String): Color = parse(custom) ?: Color(defaults.getValue(key))

    val bg: Color get() = pick(cfg.colorBg, "bg")
    val panel: Color get() = pick(cfg.colorPanel, "panel")
    val panel2: Color get() = pick(cfg.colorPanel2, "panel2")
    val accent: Color get() = pick(cfg.colorAccent, "accent")
    val button: Color get() = parse(cfg.colorButton) ?: accent
    val text: Color get() = pick(cfg.colorText, "text")
    val muted: Color get() = pick(cfg.colorMuted, "muted")
    val outline: Color get() = pick(cfg.colorOutline, "outline")
    val danger = Color(0xFFF87171)
    val warn = Color(0xFFFBBF24)

    /** Цвет текста на кнопке: тёмный на светлой кнопке и наоборот. */
    val onButton: Color get() = if (button.luminance() > 0.5f) Color(0xFF0B0F14) else Color.White
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
            containerColor = PC.button, contentColor = PC.onButton,
            disabledContainerColor = PC.panel2, disabledContentColor = PC.muted,
        ),
    ) { Text(tr(text), fontWeight = FontWeight.SemiBold) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (danger) PC.danger.copy(alpha = 0.6f) else PC.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) PC.danger else PC.text, disabledContentColor = PC.muted),
    ) { Text(tr(text)) }
}

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = PC.panel, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(color).glowBorder(18.dp, 0.3f).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun H1(text: String) = Text(tr(text), color = PC.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)

@Composable
fun H2(text: String) = Text(tr(text), color = PC.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 13) =
    Text(tr(text), color = PC.muted, fontSize = size.sp, modifier = modifier)

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
        Text(tr(text), color = if (selected) PC.accent else if (enabled) PC.text else PC.muted, fontSize = 13.sp)
    }
}

@Composable
fun PField(
    value: String, onValueChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, singleLine: Boolean = true, enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(tr(label)) },
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
            Text(tr(label), color = PC.text, fontSize = 14.sp)
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
            Text(tr(label), color = PC.text, fontSize = 14.sp)
            Text("$value ${tr(unit)}", color = PC.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
