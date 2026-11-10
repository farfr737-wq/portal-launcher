package com.portal.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Порти — маскот Portal. Лёгко «дышит» (покачивание вверх-вниз). */
@Composable
fun Mascot(size: Dp, modifier: Modifier = Modifier, bob: Boolean = true) {
    val t = rememberInfiniteTransition(label = "bob")
    val dy by t.animateFloat(
        0f, if (bob) 5f else 0f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dy",
    )
    Image(
        Images.mascot, "Порти",
        modifier.size(size).offset(y = (-dy).dp),
        filterQuality = FilterQuality.None,
    )
}

/** Вращающееся кольцо портала (две дуги с градиентом). */
@Composable
fun PortalRing(size: Dp, modifier: Modifier = Modifier, speedMs: Int = 3600) {
    val t = rememberInfiniteTransition(label = "ring")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(speedMs, easing = LinearEasing)), label = "a")
    val accent = PC.accent
    val violet = Color(0xFF8B5CF6)
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension * 0.07f
        val inset = w
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        rotate(angle) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, violet, accent, Color.Transparent), center),
                0f, 200f, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        rotate(-angle * 0.7f) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.6f), Color.Transparent), center),
                0f, 140f, false, Offset(inset + w * 1.8f, inset + w * 1.8f),
                Size(arcSize.width - w * 3.6f, arcSize.height - w * 3.6f), style = Stroke(w * 0.6f, cap = StrokeCap.Round),
            )
        }
    }
}

/** Порти внутри кольца портала. */
@Composable
fun MascotPortal(size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.86f).clip(RoundedCornerShape(percent = 50)).background(Brush.radialGradient(listOf(PC.accent.copy(alpha = 0.18f), Color.Transparent))))
        PortalRing(size)
        Mascot(size * 0.62f)
    }
}

/** Реплика Порти в «облачке». */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    Box(
        modifier.clip(shape).background(PC.panel.copy(alpha = 0.92f)).border(1.dp, PC.accent.copy(alpha = 0.5f), shape)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) { Text(tr(text), color = PC.text, fontSize = 13.sp) }
}

/** Маленькая подпись-раздел: КАПС с разрядкой. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(tr(text).uppercase(), color = PC.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = modifier)
}

/** Пустое состояние с Порти. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Mascot(72.dp)
        Muted(text)
    }
}

/** Рамка с лёгким свечением от акцентного цвета в левом верхнем углу. */
fun Modifier.glowBorder(radius: Dp = 18.dp, strength: Float = 0.4f): Modifier = composed2(radius, strength)

private fun Modifier.composed2(radius: Dp, strength: Float): Modifier =
    this.border(1.dp, Brush.linearGradient(listOf(PC.accent.copy(alpha = strength), PC.outline, PC.outline.copy(alpha = 0.6f))), RoundedCornerShape(radius))

/** Простые пиксельные значки разделов, нарисованные кодом. */
@Composable
fun NavGlyph(page: Page, color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 10f
        val st = Stroke(u * 0.9f)
        fun sq(x: Float, y: Float, w: Float, h: Float, fill: Boolean = true) =
            drawRect(color, Offset(x * u, y * u), Size(w * u, h * u), style = if (fill) Fill else st)
        when (page) {
            Page.HOME -> {
                val p = Path().apply { moveTo(1f * u, 5f * u); lineTo(5f * u, 1.2f * u); lineTo(9f * u, 5f * u); lineTo(9f * u, 9f * u); lineTo(1f * u, 9f * u); close() }
                drawPath(p, color, style = st)
                sq(4f, 6f, 2f, 3f)
            }
            Page.VERSIONS -> { sq(1f, 1f, 8f, 2f); sq(1f, 4f, 8f, 2f); sq(1f, 7f, 8f, 2f) }
            Page.CONTENT -> { sq(1f, 1f, 3.5f, 3.5f); sq(5.5f, 1f, 3.5f, 3.5f); sq(1f, 5.5f, 3.5f, 3.5f); sq(5.5f, 5.5f, 3.5f, 3.5f, false) }
            Page.SKINS -> { sq(2f, 1f, 6f, 6f, false); sq(3.5f, 3f, 1f, 1.2f); sq(5.5f, 3f, 1f, 1.2f); sq(2f, 8f, 6f, 1f) }
            Page.COMMUNITY -> {
                drawCircle(color, u * 1.8f, Offset(3.5f * u, 3.5f * u)); drawCircle(color, u * 1.8f, Offset(6.8f * u, 3.5f * u), alpha = 0.7f)
                sq(1.5f, 6.5f, 4f, 2.5f); sq(6f, 6.5f, 3f, 2.5f)
            }
            Page.SETTINGS -> {
                drawCircle(color, u * 2.2f, center, style = st)
                for (i in 0 until 8) rotate(i * 45f) { drawLine(color, Offset(center.x, u * 0.4f), Offset(center.x, u * 1.8f), u * 0.9f) }
            }
            Page.LOGS -> { sq(1f, 1.5f, 8f, 1f); sq(1f, 4.5f, 6f, 1f); sq(1f, 7.5f, 7f, 1f) }
        }
    }
}

/** Большая кнопка «Играть»: пилюля с градиентом и треугольником. */
@Composable
fun PlayPill(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(32.dp)
    val a = if (enabled) PC.button else PC.panel2
    val b = if (enabled) lerp(PC.button, Color(0xFF8B5CF6), 0.35f) else PC.panel2
    val fg = if (enabled) PC.onButton else PC.muted
    Row(
        modifier.height(60.dp).clip(shape).background(Brush.horizontalGradient(listOf(a, b)))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val p = Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }
            drawPath(p, fg)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
    }
}
