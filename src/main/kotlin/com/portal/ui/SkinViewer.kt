package com.portal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.portal.skins.Quad
import com.portal.skins.SkinMesh
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.sin

/** Интерактивный 3D-просмотр скина (программный рендер: проекция + сортировка по глубине). Вращение мышью. */
@Composable
fun SkinViewer3D(image: BufferedImage?, slim: Boolean, modifier: Modifier = Modifier) {
    var yaw by remember { mutableFloatStateOf(0.6f) }
    var pitch by remember { mutableFloatStateOf(0.15f) }
    val quads: List<Quad> = remember(image, slim) { if (image == null) emptyList() else SkinMesh.build(image, slim) }

    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(PC.panel2)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    yaw += drag.x * 0.012f
                    pitch = (pitch + drag.y * 0.008f).coerceIn(-0.9f, 0.9f)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Muted("Нет скина для просмотра")
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val scale = size.height / 40f
                val cx = size.width / 2f
                val cy = size.height / 2f + 2f * scale
                val cy0 = 16f // центр модели по высоте
                val cYaw = cos(yaw); val sYaw = sin(yaw)
                val cPit = cos(pitch); val sPit = sin(pitch)

                class Proj(val q: Quad, val depth: Float, val xs: FloatArray, val ys: FloatArray, val light: Float)

                val list = ArrayList<Proj>(quads.size)
                val xs0 = FloatArray(4); val ys0 = FloatArray(4)
                for (q in quads) {
                    // нормаль в пространстве камеры
                    val nx1 = q.nx * cYaw + q.nz * sYaw
                    val nz1 = -q.nx * sYaw + q.nz * cYaw
                    val ny2 = q.ny * cPit - nz1 * sPit
                    val nz2 = q.ny * sPit + nz1 * cPit
                    if (nz2 >= -0.001f) continue // камера смотрит вдоль +Z: видимы грани с нормалью к камере (nz < 0)
                    var depth = 0f
                    for (k in 0 until 4) {
                        val x = q.p[k * 3]; val y = q.p[k * 3 + 1] - cy0; val z = q.p[k * 3 + 2]
                        val x1 = x * cYaw + z * sYaw
                        val z1 = -x * sYaw + z * cYaw
                        val y2 = y * cPit - z1 * sPit
                        val z2 = y * sPit + z1 * cPit
                        xs0[k] = cx + x1 * scale
                        ys0[k] = cy - y2 * scale
                        depth += z2
                    }
                    val light = (0.62f + 0.38f * (-nz2) + 0.12f * ny2).coerceIn(0.35f, 1f)
                    list += Proj(q, depth / 4f + q.bias, xs0.copyOf(), ys0.copyOf(), light)
                }
                list.sortByDescending { it.depth }
                val path = Path()
                for (p in list) {
                    val argb = p.q.argb
                    val r = ((argb shr 16) and 0xFF) / 255f * p.light
                    val g = ((argb shr 8) and 0xFF) / 255f * p.light
                    val b = (argb and 0xFF) / 255f * p.light
                    path.reset()
                    path.moveTo(p.xs[0], p.ys[0]); path.lineTo(p.xs[1], p.ys[1])
                    path.lineTo(p.xs[2], p.ys[2]); path.lineTo(p.xs[3], p.ys[3]); path.close()
                    val color = Color(r, g, b, 1f)
                    drawPath(path, color)
                    // тонкая обводка скрывает швы между соседними текселями
                    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(0.6f))
                }
            }
        }
    }
}

/** Просмотрщик с подписью и кнопками вращения. */
@Composable
fun SkinViewerCard(image: BufferedImage?, slim: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        SkinViewer3D(image, slim, Modifier.fillMaxWidth().weight(1f))
        Muted("Потяните мышью, чтобы повернуть модель", size = 11)
    }
}
