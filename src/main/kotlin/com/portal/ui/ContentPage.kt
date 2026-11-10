package com.portal.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.content.ContentInstaller
import com.portal.content.ContentItem
import com.portal.content.ContentType
import com.portal.content.LocalFile
import com.portal.content.ModpackInstaller
import com.portal.content.formatCount
import com.portal.content.loaderFilters
import com.portal.core.Http
import com.portal.core.formatBytes
import com.portal.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.imageio.ImageIO

private val iconCache = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 120
}

@Composable
private fun RemoteIcon(url: String?, size: Int = 48) {
    val bmp by produceState<ImageBitmap?>(null, url) {
        if (url == null) { value = null; return@produceState }
        val cached = synchronized(iconCache) { iconCache[url] }
        if (cached != null) { value = cached; return@produceState }
        value = withContext(Dispatchers.IO) {
            try {
                ImageIO.read(java.io.ByteArrayInputStream(Http.getBytes(url)))?.toComposeImageBitmap()
            } catch (_: Exception) { null }
        }
        synchronized(iconCache) { iconCache[url] = value }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(PC.panel2)) {
        bmp?.let { Image(it, null, Modifier.size(size.dp), contentScale = ContentScale.Crop) }
    }
}

/** Выбор нескольких файлов системным диалогом. */
private fun pickFiles(title: String, exts: List<String>): List<Path> {
    val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
    d.isMultipleMode = true
    if (exts.isNotEmpty()) d.setFilenameFilter { _, name -> exts.any { name.lowercase().endsWith(it) } }
    d.isVisible = true
    return d.files?.map { it.toPath() } ?: emptyList()
}

@Composable
fun ContentPage(vm: AppViewModel) {
    val settings by SettingsStore.state.collectAsState()
    var instanceId by remember { mutableStateOf(vm.selected?.id ?: "") }
    val cfg = vm.instances.firstOrNull { it.id == instanceId } ?: vm.selected
    var source by remember { mutableStateOf("modrinth") }
    var type by remember { mutableStateOf(ContentType.MOD) }
    var world by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<ContentItem>() }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var offset by remember { mutableStateOf(0) }

    val worlds = remember(cfg?.id, refresh, type) { if (cfg != null && type == ContentType.DATAPACK) ContentInstaller.worlds(cfg) else emptyList() }
    LaunchedEffect(worlds) { if (world == null || world !in worlds) world = worlds.firstOrNull() }
    val installed = remember(cfg?.id, type, world, refresh) { if (cfg != null) ContentInstaller.listFiles(cfg, type, world) else emptyList() }

    fun search(reset: Boolean) {
        if (reset) { results.clear(); offset = 0 }
        loading = true; searchError = null
        val start = offset
        val provider = ContentInstaller.providerOf(source)
        val mc = if (type == ContentType.MODPACK) null else cfg?.mcVersion
        val loaders = if (type == ContentType.MOD && cfg != null) loaderFilters(cfg) else emptyList()
        vm.scope.launch {
            try {
                val found = provider.search(query.trim(), type, mc, loaders, start)
                withContext(Dispatchers.Main.immediate) {
                    results.addAll(found); offset = start + found.size; hasMore = found.size >= 20
                }
            } catch (e: Throwable) {
                searchError = (e as? com.portal.core.PortalException)?.let { it.message + (it.hint?.let { h -> "\n$h" } ?: "") }
                    ?: ("Не удалось выполнить поиск: " + (e.message ?: e.javaClass.simpleName))
            } finally { loading = false }
        }
    }
    LaunchedEffect(source, type, cfg?.id) { search(true) }

    fun install(item: ContentItem) {
        val provider = ContentInstaller.providerOf(source)
        vm.runBusy("Установка: ${item.title}", null) { report ->
            val msg = ContentInstaller.install(provider, item, type, cfg, world, report)
            vm.toast(msg); refresh++
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        H1("Контент")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ContentType.values().forEach { t -> Chip(t.title, type == t, { type = t }) }
            Spacer(Modifier.weight(1f))
            Chip("Modrinth", source == "modrinth", { source = "modrinth" })
            Chip("CurseForge", source == "curseforge", { source = "curseforge" })
        }
        if (type != ContentType.MODPACK) {
            if (vm.instances.isEmpty()) {
                Muted("Сначала установите версию Minecraft на странице «Версии».")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted("Куда устанавливать:")
                    vm.instances.take(8).forEach { i -> Chip(i.displayName, i.id == cfg?.id, { instanceId = i.id }) }
                }
            }
            if (type == ContentType.DATAPACK) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted("Мир:")
                    if (worlds.isEmpty()) Muted("миров нет — создайте мир в игре")
                    worlds.take(10).forEach { w -> Chip(w, w == world, { world = w }) }
                }
            }
        }
        if (source == "curseforge" && settings.cfApiKey.isBlank()) {
            Panel(Modifier.fillMaxWidth(), PC.panel2) {
                Text(tr("Для CurseForge нужен бесплатный ключ API (console.curseforge.com). Вставьте его в Настройки → Сервисы. Modrinth работает без ключа."), color = PC.warn, fontSize = 13.sp)
                GhostButton("Открыть настройки", { vm.page = Page.SETTINGS })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PField(query, { query = it }, "Поиск", Modifier.weight(1f))
            PrimaryButton("Найти", { search(true) })
            GhostButton(if (type == ContentType.MODPACK) "Добавить файл сборки" else "Добавить файл с компьютера", {
                if (type == ContentType.MODPACK) {
                    val f = pickFiles("Файл сборки (.mrpack / .zip)", listOf(".mrpack", ".zip")).firstOrNull()
                    if (f != null) vm.runBusy("Установка сборки", null) { r -> vm.toast(ModpackInstaller.installFile(f, r)); refresh++ }
                } else if (cfg != null) {
                    val exts = if (type == ContentType.MOD) listOf(".jar") else listOf(".zip")
                    val files = pickFiles("Выберите файлы", exts)
                    if (files.isNotEmpty()) vm.scope.launch {
                        try {
                            val n = ContentInstaller.importFiles(files, cfg, type, world)
                            vm.toast("Добавлено файлов: $n"); refresh++
                        } catch (e: Throwable) { vm.showError(e) }
                    }
                }
            }, enabled = type == ContentType.MODPACK || (cfg != null && (type != ContentType.DATAPACK || world != null)))
        }
        searchError?.let { Text(it, color = PC.danger, fontSize = 13.sp) }

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LazyColumn(Modifier.weight(3f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { item ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PC.panel).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RemoteIcon(item.iconUrl)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.title, color = PC.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Muted("   ${item.author}  •  ⬇ ${formatCount(item.downloads)}", size = 12)
                            }
                            Text(item.description, color = PC.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        PrimaryButton("Установить", { install(item) }, enabled = type == ContentType.MODPACK || (cfg != null && (type != ContentType.DATAPACK || world != null)))
                    }
                }
                item {
                    when {
                        loading -> Muted("Загрузка списка…", Modifier.padding(8.dp))
                        results.isEmpty() && searchError == null -> EmptyState("Ничего не найдено")
                        hasMore -> GhostButton("Показать ещё", { search(false) })
                        else -> {}
                    }
                }
            }
            if (type != ContentType.MODPACK) {
                Panel(Modifier.weight(2f).fillMaxSize()) {
                    H2("Установлено")
                    Muted(cfg?.displayName ?: "", size = 12)
                    if (installed.isEmpty()) Muted("Пока пусто.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(installed) { f -> InstalledRow(vm, f, onDelete = {
                            try { ContentInstaller.delete(cfg!!, type, world, f); refresh++ } catch (e: Throwable) { vm.showError(e) }
                        }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledRow(vm: AppViewModel, f: LocalFile, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(PC.panel2).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.name, color = PC.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.size > 0) Muted(formatBytes(f.size), size = 11)
        }
        if (confirm) {
            GhostButton("Удалить", { confirm = false; onDelete() }, danger = true)
        } else {
            GhostButton("✕", { confirm = true })
        }
    }
}
