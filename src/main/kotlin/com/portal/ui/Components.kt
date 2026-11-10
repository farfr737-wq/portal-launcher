package com.portal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.skins.LocalSkin
import com.portal.skins.SkinManager
import com.portal.social.PortalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.nio.file.Path

/** Картинки загружаются лениво — только когда впервые понадобились. */
object Images {
    val portalIcon: ImageBitmap by lazy { useResource("icons/portal.png", ::loadImageBitmap) }
    val background: ImageBitmap by lazy { useResource("images/bg.png", ::loadImageBitmap) }
    val logo: ImageBitmap by lazy { useResource("images/logo-256.png", ::loadImageBitmap) }
    val mascot: ImageBitmap by lazy { useResource("images/mascot.png", ::loadImageBitmap) }
}

/** Фирменная иконка Portal (пиксельная, поэтому без сглаживания). */
@Composable
fun PortalIcon(size: Dp = 16.dp, modifier: Modifier = Modifier) {
    Image(
        bitmap = Images.portalIcon, contentDescription = "Portal",
        modifier = modifier.size(size).clip(RoundedCornerShape(3.dp)),
        filterQuality = FilterQuality.None,
    )
}

/** Ник с иконкой Portal и серверным статусом. */
@Composable
fun PortalNick(name: String, status: PortalStatus = PortalStatus.USER, fontSize: Int = 14, showIcon: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showIcon) {
            PortalIcon((fontSize + 2).dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(name, color = PC.text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold)
        when (status) {
            PortalStatus.VERIFIED -> Badge("Проверен", PC.accent)
            PortalStatus.ADMIN -> Badge("Админ", PC.warn)
            PortalStatus.USER -> {}
        }
    }
}

@Composable
fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) { Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

fun BufferedImage.toBitmap(): ImageBitmap = this.toComposeImageBitmap()

/** Загружает изображение скина в фоне. */
@Composable
fun rememberSkinImage(file: Path?): BufferedImage? {
    val state = produceState<BufferedImage?>(null, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) {
            try { SkinManager.readImage(file) } catch (_: Exception) { null }
        }
    }
    return state.value
}

@Composable
fun rememberSkinImage(skin: LocalSkin?): BufferedImage? = rememberSkinImage(skin?.file)

/** Голова персонажа (лицо + шапка) из текстуры скина. */
@Composable
fun SkinHead(image: BufferedImage?, size: Dp = 40.dp) {
    val bmp = remember(image) { image?.toComposeImageBitmap() }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(PC.panel2)) {
        if (bmp != null) {
            Canvas(Modifier.size(size)) {
                val dst = IntSize(this.size.width.toInt(), this.size.height.toInt())
                drawImage(bmp, IntOffset(8, 8), IntSize(8, 8), IntOffset.Zero, dst, filterQuality = FilterQuality.None)
                drawImage(bmp, IntOffset(40, 8), IntSize(8, 8), IntOffset.Zero, dst, filterQuality = FilterQuality.None)
            }
        } else {
            Box(Modifier.size(size), contentAlignment = Alignment.Center) { PortalIcon(size / 2) }
        }
    }
}

/** Список с поиском для выбора одного элемента (версии Minecraft, версии загрузчика). */
@Composable
fun <T> PickerList(
    items: List<T>, selected: T?, label: (T) -> String,
    onSelect: (T) -> Unit, height: Dp = 180.dp, searchable: Boolean = true,
    badge: (T) -> String? = { null },
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) { if (query.isBlank()) items else items.filter { label(it).contains(query, ignoreCase = true) } }
    Column {
        if (searchable) {
            PField(query, { query = it }, "Поиск", Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(PC.panel2)) {
            if (shown.isEmpty()) {
                Muted("Ничего не найдено", Modifier.padding(14.dp))
            } else {
                LazyColumn {
                    items(shown) { item ->
                        val sel = item == selected
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(item) }
                                .background(if (sel) PC.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label(item), color = if (sel) PC.accent else PC.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            badge(item)?.let { Muted(it, size = 12) }
                        }
                    }
                }
            }
        }
    }
}
