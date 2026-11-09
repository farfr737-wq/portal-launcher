package com.portal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.settings.LauncherSettings
import com.portal.settings.SettingsStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Иконки и обои: встроенные (из ресурсов) и загруженные пользователем (копируются в папку данных). */
object Assets {
    val iconPresets = listOf("green", "blue", "purple", "pink", "red", "gray", "white")
    val wallpaperPresets = (1..13).map { "w%02d".format(it) }

    private val customIcon: Path get() = PortalPaths.launcher.resolve("custom-icon.png")
    private val customBg: Path get() = PortalPaths.launcher.resolve("custom-background.png")

    private val cache = HashMap<String, ImageBitmap>()

    @Synchronized
    private fun cached(key: String, load: () -> ImageBitmap?): ImageBitmap? {
        cache[key]?.let { return it }
        val v = try { load() } catch (_: Throwable) { null } ?: return null
        cache[key] = v
        return v
    }

    fun preset(kind: String, name: String): ImageBitmap? =
        cached("$kind/$name") { useResource("themes/$kind/$name" + if (kind == "icons") ".png" else ".jpg", ::loadImageBitmap) }

    fun icon(s: LauncherSettings): ImageBitmap = when {
        s.appIcon == "custom" -> cached("custom-icon/${s.assetRev}") { ImageIO.read(customIcon.toFile())?.toComposeImageBitmap() }
        s.appIcon.isNotBlank() -> preset("icons", s.appIcon)
        else -> null
    } ?: Images.logo

    /** null — использовать стандартный фон Portal. */
    fun wallpaper(s: LauncherSettings): ImageBitmap? = when {
        s.wallpaper == "custom" -> cached("custom-bg/${s.assetRev}") { ImageIO.read(customBg.toFile())?.toComposeImageBitmap() }
        s.wallpaper.isNotBlank() -> preset("wallpapers", s.wallpaper)
        else -> null
    }

    private fun scaled(img: BufferedImage, maxW: Int, maxH: Int): BufferedImage {
        val k = minOf(maxW.toDouble() / img.width, maxH.toDouble() / img.height, 1.0)
        val w = (img.width * k).toInt().coerceAtLeast(1)
        val h = (img.height * k).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(img, 0, 0, w, h, null); g.dispose()
        return out
    }

    /** Копирует выбранную картинку в папку данных (с уменьшением), чтобы она не зависела от исходного файла. */
    fun importCustom(isIcon: Boolean, src: Path) {
        val img = try { ImageIO.read(src.toFile()) } catch (_: Exception) { null }
            ?: throw PortalException("Не удалось прочитать картинку", "Поддерживаются PNG, JPG, GIF, BMP.")
        val out = if (isIcon) scaled(img, 256, 256) else scaled(img, 1920, 1080)
        Files.createDirectories(PortalPaths.launcher)
        ImageIO.write(out, "png", (if (isIcon) customIcon else customBg).toFile())
        SettingsStore.update {
            if (isIcon) it.copy(appIcon = "custom", assetRev = it.assetRev + 1)
            else it.copy(wallpaper = "custom", assetRev = it.assetRev + 1)
        }
    }
}

private class Preset(val name: String, val c: List<String>)   // bg, panel, panel2, accent, button, text, muted, outline

private val presets = listOf(
    Preset("Portal", listOf("", "", "", "", "", "", "", "")),
    Preset("Ocean", listOf("#0A1020", "#101A30", "#16233F", "#60A5FA", "", "#F1F5F9", "#94A3B8", "#26385C")),
    Preset("Violet", listOf("#100B18", "#181222", "#221A30", "#C084FC", "", "#F5F3FF", "#A79BB8", "#3A2D52")),
    Preset("Forest", listOf("#0B120D", "#111B14", "#182620", "#4ADE80", "", "#F0FDF4", "#8FAE98", "#27402F")),
    Preset("Crimson", listOf("#140B0B", "#1D1212", "#291A1A", "#F87171", "", "#FEF2F2", "#B39494", "#4A2A2A")),
    Preset("Light", listOf("#F1F5F9", "#FFFFFF", "#E2E8F0", "#0D9488", "#0D9488", "#0F172A", "#475569", "#CBD5E1")),
)

@Composable
private fun ColorField(label: String, value: String, def: Long, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val shown = PC.parse(text) ?: androidx.compose.ui.graphics.Color(def)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(shown).border(1.dp, PC.outline, RoundedCornerShape(8.dp)))
        PField(text, { t ->
            text = t
            if (t.isBlank()) onChange("") else if (PC.parse(t) != null) onChange(if (t.startsWith("#")) t else "#$t")
        }, label, Modifier.width(190.dp))
    }
}

@Composable
private fun Thumb(bmp: ImageBitmap?, selected: Boolean, w: Int, h: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(w.dp, h.dp).clip(shape).background(PC.panel2)
            .border(if (selected) 2.dp else 1.dp, if (selected) PC.accent else PC.outline, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { if (bmp != null) Image(bmp, null, Modifier.size(w.dp, h.dp), contentScale = ContentScale.Crop) }
}

/** Панели «Язык» и «Оформление» страницы настроек. */
@Composable
fun AppearancePanels(vm: AppViewModel, s: LauncherSettings) {
    Panel(Modifier.fillMaxWidth()) {
        H2("Язык")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            I18n.languages.forEach { l -> Chip(l.name, s.language == l.code, { SettingsStore.update { it.copy(language = l.code) } }) }
        }
        Muted("Интерфейс переключается сразу. Названия версий, описания из каталогов и часть технических сообщений остаются на языке источника.", size = 12)
    }

    Panel(Modifier.fillMaxWidth()) {
        H2("Оформление")
        Muted("Готовые схемы", size = 12)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                Chip(p.name, false, {
                    SettingsStore.update {
                        it.copy(colorBg = p.c[0], colorPanel = p.c[1], colorPanel2 = p.c[2], colorAccent = p.c[3],
                            colorButton = p.c[4], colorText = p.c[5], colorMuted = p.c[6], colorOutline = p.c[7])
                    }
                })
            }
        }
        Muted("Свои цвета (HEX, например #5EEAD4; пусто — по умолчанию)", size = 12)
        val d = PC.defaults
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ColorField("Фон", s.colorBg, d.getValue("bg")) { v -> SettingsStore.update { it.copy(colorBg = v) } }
            ColorField("Панели", s.colorPanel, d.getValue("panel")) { v -> SettingsStore.update { it.copy(colorPanel = v) } }
            ColorField("Вторые панели", s.colorPanel2, d.getValue("panel2")) { v -> SettingsStore.update { it.copy(colorPanel2 = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ColorField("Акцент", s.colorAccent, d.getValue("accent")) { v -> SettingsStore.update { it.copy(colorAccent = v) } }
            ColorField("Кнопки", s.colorButton, d.getValue("accent")) { v -> SettingsStore.update { it.copy(colorButton = v) } }
            ColorField("Обводка", s.colorOutline, d.getValue("outline")) { v -> SettingsStore.update { it.copy(colorOutline = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ColorField("Текст", s.colorText, d.getValue("text")) { v -> SettingsStore.update { it.copy(colorText = v) } }
            ColorField("Второй текст", s.colorMuted, d.getValue("muted")) { v -> SettingsStore.update { it.copy(colorMuted = v) } }
        }

        Muted("Иконка лаунчера (окно и логотип в меню)", size = 12)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(Images.logo, s.appIcon == "", 56, 56) { SettingsStore.update { it.copy(appIcon = "") } }
            Assets.iconPresets.forEach { n -> Thumb(Assets.preset("icons", n), s.appIcon == n, 56, 56) { SettingsStore.update { it.copy(appIcon = n) } } }
            if (s.appIcon == "custom") Thumb(Assets.icon(s), true, 56, 56) {}
            GhostButton("Своя иконка…", {
                pickImage("Выберите картинку для иконки")?.let { f ->
                    try { Assets.importCustom(true, f) } catch (e: Throwable) { vm.showError(e) }
                }
            })
        }
        Muted("Иконка файла PortalLauncher.exe в проводнике задаётся при сборке (packaging/icon.ico).", size = 11)

        Muted("Фоновое изображение", size = 12)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(Images.background, s.wallpaper == "", 100, 58) { SettingsStore.update { it.copy(wallpaper = "") } }
            Assets.wallpaperPresets.forEach { n -> Thumb(Assets.preset("wallpapers", n), s.wallpaper == n, 100, 58) { SettingsStore.update { it.copy(wallpaper = n) } } }
            if (s.wallpaper == "custom") Thumb(Assets.wallpaper(s), true, 100, 58) {}
            GhostButton("Своё фото…", {
                pickImage("Выберите фоновое изображение")?.let { f ->
                    try { Assets.importCustom(false, f) } catch (e: Throwable) { vm.showError(e) }
                }
            })
        }
        SliderRow("Затемнение фона", s.wallpaperDim, 0, 90, 5, "%", { v -> SettingsStore.update { it.copy(wallpaperDim = v) } })
        LabeledSwitch("Показывать фон на всех страницах", s.wallpaperEverywhere, { v -> SettingsStore.update { it.copy(wallpaperEverywhere = v) } }, "Если выключено, фон виден только на главной")
        GhostButton("Сбросить оформление", {
            SettingsStore.update {
                it.copy(colorBg = "", colorPanel = "", colorPanel2 = "", colorAccent = "", colorButton = "", colorText = "", colorMuted = "",
                    colorOutline = "", appIcon = "", wallpaper = "", wallpaperDim = 55, wallpaperEverywhere = true)
            }
        })
    }
}

fun pickImage(title: String): Path? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
    d.setFilenameFilter { _, n -> n.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".bmp") || it.endsWith(".gif") } }
    d.isVisible = true
    val f = d.file ?: return null
    return Path.of(d.directory, f)
}
