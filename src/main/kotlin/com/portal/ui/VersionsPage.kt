package com.portal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.core.Os
import com.portal.core.PortalPaths
import com.portal.loaders.LoaderType
import com.portal.loaders.LoaderVersion
import com.portal.loaders.Loaders
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import com.portal.versions.InstallService
import com.portal.versions.InstanceConfig
import com.portal.versions.Instances
import com.portal.versions.ManifestEntry
import kotlinx.coroutines.launch

@Composable
fun VersionsPage(vm: AppViewModel) {
    var tab by remember { mutableStateOf(if (vm.instances.isEmpty()) 1 else 0) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        H1("Версии")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Установленные (${vm.instances.size})", tab == 0, { tab = 0 })
            Chip("Установить новую", tab == 1, { tab = 1 })
        }
        if (tab == 0) InstalledList(vm) else InstallerPanel(vm) { tab = 0 }
    }
}

@Composable
private fun InstalledList(vm: AppViewModel) {
    if (vm.instances.isEmpty()) {
        Muted("Ничего не установлено. Откройте вкладку «Установить новую».", size = 14)
        return
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.instances.forEach { i ->
                val sel = vm.selected?.id == i.id
                Panel(Modifier.fillMaxWidth(), if (sel) PC.panel2 else PC.panel) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.displayName, color = if (sel) PC.accent else PC.text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Muted("${i.id}  •  Java ${i.javaMajor}", size = 12)
                        }
                        if (!sel) GhostButton("Выбрать", { vm.select(i.id) })
                    }
                }
            }
        }
        val cur = vm.selected
        if (cur != null) {
            Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstanceSettings(vm, cur)
            }
        }
    }
}

@Composable
private fun InstanceSettings(vm: AppViewModel, cfg: InstanceConfig) {
    var c by remember(cfg.id) { mutableStateOf(cfg) }
    var confirmDelete by remember(cfg.id) { mutableStateOf(false) }
    val defaultRam = SettingsStore.value.defaultRamMb
    val ram = if (c.ramMb > 0) c.ramMb else defaultRam
    val totalMb = remember { (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize?.div(1024 * 1024)?.toInt() ?: 16384 }

    Panel(Modifier.fillMaxWidth()) {
        H2("Настройки запуска: ${cfg.displayName}")
        Muted("Загрузчик: ${cfg.loaderTitle} ${cfg.loaderVersion}  •  требуется Java ${cfg.javaMajor}", size = 12)

        SliderRow("Оперативная память", ram, 1024, maxOf(2048, (totalMb / 512) * 512), 512, "МБ", { c = c.copy(ramMb = it) })
        when {
            ram > totalMb * 0.75 -> Text("⚠ Выделено слишком много памяти (${ram} МБ из ${totalMb} МБ). Система и игра могут зависать.", color = PC.danger, fontSize = 12.sp)
            ram > 8192 -> Text("⚠ Больше 8 ГБ обычно не нужно и может вызывать паузы сборщика мусора.", color = PC.warn, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PField(c.width.toString(), { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { c = c.copy(width = it) } }, "Ширина", Modifier.weight(1f))
            PField(c.height.toString(), { v -> v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { c = c.copy(height = it) } }, "Высота", Modifier.weight(1f))
        }
        LabeledSwitch("Полноэкранный режим", c.fullscreen, { c = c.copy(fullscreen = it) })

        var javaOpen by remember { mutableStateOf(false) }
        PField(c.javaPath, { c = c.copy(javaPath = it) }, "Путь к Java для этой версии (пусто — автоопределение)", Modifier.fillMaxWidth())
        FlowRowCompat {
            vm.javaList.filter { it.major == cfg.javaMajor }.forEach { j ->
                Chip("Java ${j.major} • ${j.source}", c.javaPath == j.path, { c = c.copy(javaPath = j.path) })
            }
            Chip("Авто", c.javaPath.isBlank(), { c = c.copy(javaPath = "") })
        }
        PField(c.jvmArgs, { c = c.copy(jvmArgs = it) }, "Дополнительные JVM-аргументы", Modifier.fillMaxWidth())
        PField(c.gameArgs, { c = c.copy(gameArgs = it) }, "Параметры запуска игры", Modifier.fillMaxWidth())
        PField(c.gameDir, { c = c.copy(gameDir = it) }, "Папка игры (пусто — ${PortalPaths.profiles.resolve(cfg.id)})", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Сохранить", { vm.save(c); vm.toast("Настройки сохранены") })
            GhostButton("Папка игры", { openFolder(Instances.gameDir(c)) })
            GhostButton("Проверить файлы", {
                vm.runBusy("Проверка файлов ${cfg.id}", "Файлы в порядке") { r -> InstallService.verify(cfg, r) }
            })
        }
    }

    Panel(Modifier.fillMaxWidth()) {
        val mods = remember(cfg.id, vm.busy) { Instances.scanMods(cfg) }
        H2("Моды (${mods.size})")
        if (cfg.loader == "vanilla") Muted("Для Vanilla моды не используются. Установите Forge, NeoForge, Fabric или Quilt.", size = 12)
        else if (mods.isEmpty()) Muted("Папка mods пуста. Положите .jar-файлы модов в папку mods этой версии.", size = 12)
        mods.take(40).forEach { Muted("• $it", size = 12) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Открыть папку mods", {
                val d = Instances.gameDir(cfg).resolve("mods"); java.nio.file.Files.createDirectories(d); openFolder(d)
            })
            GhostButton("Обновить список", { vm.save(Instances.refreshMods(cfg)); vm.toast("Список модов обновлён") })
        }
    }

    Panel(Modifier.fillMaxWidth()) {
        H2("Удаление")
        Muted("Файлы версии удаляются, миры и моды сохраняются, если не отметить полное удаление.", size = 12)
        if (!confirmDelete) GhostButton("Удалить версию…", { confirmDelete = true }, danger = true)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Удалить только версию", { vm.delete(cfg, false); confirmDelete = false }, danger = true)
            GhostButton("Удалить вместе с мирами и модами", { vm.delete(cfg, true); confirmDelete = false }, danger = true)
            GhostButton("Отмена", { confirmDelete = false })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

fun openFolder(path: java.nio.file.Path) {
    try {
        java.nio.file.Files.createDirectories(path)
        java.awt.Desktop.getDesktop().open(path.toFile())
    } catch (e: Exception) {
        Log.warn("Не удалось открыть папку $path: ${e.message}")
    }
}

// ---------------------------------------------------------------- Установщик

@Composable
private fun InstallerPanel(vm: AppViewModel, onDone: () -> Unit) {
    var showTypes by remember { mutableStateOf(setOf("release")) }
    var mc by remember { mutableStateOf<ManifestEntry?>(null) }
    var loader by remember { mutableStateOf(LoaderType.VANILLA) }
    var loaderVersions by remember { mutableStateOf<List<LoaderVersion>?>(null) }
    var loaderVersion by remember { mutableStateOf<LoaderVersion?>(null) }
    var loaderError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val manifest = vm.manifest
    LaunchedEffect(mc, loader) {
        loaderVersions = null; loaderVersion = null; loaderError = null
        val m = mc ?: return@LaunchedEffect
        if (loader == LoaderType.VANILLA) return@LaunchedEffect
        loading = true
        try {
            val provider = Loaders.get(loader)
            val list = provider.versions(m.id)
            loaderVersions = list
            loaderVersion = list.firstOrNull { it.recommended } ?: list.firstOrNull()
            if (list.isEmpty()) loaderError = provider.unavailableReason(m.id)
        } catch (e: Throwable) {
            Log.warn("Список версий ${loader.title} не загружен: ${e.message}")
            loaderError = "Не удалось получить список версий ${loader.title}: ${e.message}. Проверьте подключение к интернету."
        } finally { loading = false }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.weight(1f).fillMaxSize()) {
            H2("1. Версия Minecraft")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("release" to "Релизы", "snapshot" to "Снапшоты", "old_beta" to "Beta", "old_alpha" to "Alpha").forEach { (k, t) ->
                    Chip(t, k in showTypes, { showTypes = if (k in showTypes) showTypes - k else showTypes + k })
                }
            }
            if (manifest == null) {
                Muted(vm.manifestError ?: "Загрузка списка версий…")
                if (vm.manifestError != null) GhostButton("Повторить", { vm.loadManifest() })
            } else {
                val items = remember(manifest, showTypes) { manifest.versions.filter { it.type in showTypes } }
                PickerList(items, mc, { it.id }, { mc = it }, height = 330.dp, badge = { it.releaseTime.take(10) })
            }
        }
        Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                H2("2. Загрузчик")
                FlowRowCompat {
                    LoaderType.entries.forEach { t -> Chip(t.title, loader == t, { loader = t }) }
                }
                if (loader != LoaderType.VANILLA) {
                    H2("3. Версия загрузчика")
                    when {
                        mc == null -> Muted("Сначала выберите версию Minecraft", size = 12)
                        loading -> { Muted("Загрузка списка…", size = 12); ProgressLine(null) }
                        loaderError != null -> Text(loaderError!!, color = PC.warn, fontSize = 13.sp)
                        loaderVersions != null -> PickerList(
                            loaderVersions!!, loaderVersion, { it.version }, { loaderVersion = it }, height = 170.dp,
                            badge = { if (it.recommended) "рекомендуется" else null },
                        )
                    }
                }
                if (loader == LoaderType.OPTIFINE) Muted("OptiFine устанавливается его официальным установщиком: откроется окно, нажмите «Install».", size = 12)
            }
            Panel(Modifier.fillMaxWidth()) {
                H2("4. Установка")
                val m = mc
                Muted(
                    if (m == null) "Выберите версию Minecraft"
                    else "Minecraft ${m.id} • ${loader.title}${loaderVersion?.let { " ${it.version}" } ?: ""}", size = 13
                )
                Muted("Будет создана отдельная папка версии: ${PortalPaths.versions}", size = 11)
                val ready = m != null && (loader == LoaderType.VANILLA || loaderVersion != null)
                PrimaryButton("Установить", {
                    vm.install(m!!.id, loader, if (loader == LoaderType.VANILLA) null else loaderVersion)
                    onDone()
                }, Modifier.fillMaxWidth(), enabled = ready && vm.busy == null)
            }
        }
    }
}
