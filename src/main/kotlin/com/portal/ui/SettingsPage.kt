package com.portal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portal.cache.CacheManager
import com.portal.core.PortalPaths
import com.portal.core.formatBytes
import com.portal.java.JavaManager
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun SettingsPage(vm: AppViewModel) {
    val s by SettingsStore.state.collectAsState()
    var cacheSize by remember { mutableStateOf(CacheManager.cacheSize()) }
    var javaInput by remember(s.javaPath) { mutableStateOf(s.javaPath) }
    var javaCheck by remember { mutableStateOf<String?>(null) }
    var clientId by remember(s.msClientId) { mutableStateOf(s.msClientId) }
    var cfKey by remember(s.cfApiKey) { mutableStateOf(s.cfApiKey) }
    var dataDir by remember { mutableStateOf(if (PortalPaths.root == PortalPaths.defaultRoot) "" else PortalPaths.root.toString()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        H1("Настройки")

        AppearancePanels(vm, s)

        Panel(Modifier.fillMaxWidth()) {
            H2("Загрузки и производительность")
            SliderRow("Потоков загрузки", s.downloadThreads, 1, 16, 1, "", { v -> SettingsStore.update { it.copy(downloadThreads = v) } })
            SliderRow(
                "Максимальная скорость", s.maxDownloadKbps, 0, 51200, 256, "КБ/с",
                { v -> SettingsStore.update { it.copy(maxDownloadKbps = v) } }, "0 — без ограничения"
            )
            SliderRow("Лимит кэша", s.cacheLimitMb, 50, 2000, 50, "МБ", { v -> SettingsStore.update { it.copy(cacheLimitMb = v) } })
            LabeledSwitch("Автоматическая очистка временных файлов", s.autoCleanTemp, { v -> SettingsStore.update { it.copy(autoCleanTemp = v) } }, "При запуске лаунчера и после закрытия игры")
            LabeledSwitch("Проверять целостность файлов перед запуском", s.verifyIntegrityOnLaunch, { v -> SettingsStore.update { it.copy(verifyIntegrityOnLaunch = v) } }, "Сверяет SHA-1 всех файлов; запуск дольше, но повреждённые файлы докачиваются")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Очистить кэш (${formatBytes(cacheSize)})", {
                    val freed = CacheManager.clearCaches(); cacheSize = CacheManager.cacheSize()
                    vm.toast("Освобождено ${formatBytes(freed)}")
                })
                GhostButton("Очистить временные файлы", { val f = CacheManager.cleanTemp(); vm.toast("Освобождено ${formatBytes(f)}") })
            }
            Muted("Очистка не затрагивает миры, моды, настройки игры, версии и локальные скины.", size = 12)
        }

        Panel(Modifier.fillMaxWidth()) {
            H2("Запуск")
            SliderRow("Память по умолчанию", s.defaultRamMb, 1024, 16384, 512, "МБ", { v -> SettingsStore.update { it.copy(defaultRamMb = v) } },
                "Для версии можно задать своё значение. Лишняя память замедляет игру.")
        }

        Panel(Modifier.fillMaxWidth()) {
            H2("Java")
            if (vm.javaList.isEmpty()) Muted("Java не найдена. Portal скачает подходящую при первом запуске версии.", size = 12)
            vm.javaList.forEach { j -> Muted("Java ${j.major}  (${j.version})  •  ${j.source}\n${j.path}", size = 12) }
            PField(javaInput, { javaInput = it }, "Глобальная Java (пусто — автоопределение)", Modifier.fillMaxWidth())
            javaCheck?.let { Muted(it, size = 12) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Применить", {
                    SettingsStore.update { it.copy(javaPath = javaInput.trim()) }
                    if (javaInput.isNotBlank()) vm.scope.launch {
                        val j = JavaManager.probe(javaInput.trim())
                        javaCheck = if (j != null) "✔ Java ${j.major} (${j.version}) работает" else "✘ Этот файл не является рабочей Java"
                    }
                })
                GhostButton("Обновить список", { vm.scope.launch { vm.javaList = JavaManager.detect(force = true) } })
                listOf(8, 17, 21).forEach { m ->
                    GhostButton("Скачать Java $m", {
                        vm.runBusy("Скачивание Java $m", "Java $m установлена") { r -> JavaManager.download(m, r); vm.javaList = JavaManager.detect(true) }
                    })
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            H2("Сервисы")
            Muted("Для входа через Microsoft нужен собственный Azure client_id (инструкция в README).", size = 12)
            PField(clientId, { clientId = it }, "Azure Application (client) ID", Modifier.fillMaxWidth())
            PField(s.socialServerUrl, { v -> SettingsStore.update { it.copy(socialServerUrl = v.trim()) } }, "Адрес сервера Portal", Modifier.fillMaxWidth())
            Muted("Ключ API CurseForge нужен для каталога CurseForge во вкладке «Контент» (бесплатно: console.curseforge.com). Modrinth работает без ключа.", size = 12)
            PField(cfKey, { cfKey = it }, "Ключ API CurseForge", Modifier.fillMaxWidth())
            PrimaryButton("Сохранить Client ID", { SettingsStore.update { it.copy(msClientId = clientId.trim(), cfApiKey = cfKey.trim()) }; vm.toast("Сохранено") })
        }

        Panel(Modifier.fillMaxWidth()) {
            H2("Папки")
            Muted("Сейчас данные лежат здесь: ${PortalPaths.root}", size = 12)
            PField(dataDir, { dataDir = it }, "Новая папка данных (например, D:\\PortalData)", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Выбрать папку…", { pickFolder("Выберите папку для данных Portal")?.let { dataDir = it } })
                PrimaryButton("Сохранить папку", {
                    try {
                        PortalPaths.setCustomRoot(dataDir)
                        vm.toast("Сохранено. Перезапустите лаунчер — данные будут храниться в новой папке")
                    } catch (e: Throwable) { vm.showError(e, "Не удалось использовать эту папку") }
                })
                GhostButton("Сбросить на стандартную", { PortalPaths.setCustomRoot(""); dataDir = ""; vm.toast("Сброшено. Перезапустите лаунчер") })
            }
            Muted("Уже скачанные версии не переносятся автоматически: при желании скопируйте старую папку в новую целиком.", size = 11)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Папка Portal", { openFolder(PortalPaths.root) })
                GhostButton("Папка логов", { openFolder(PortalPaths.logs) })
            }
        }
    }
}

@Composable
fun LogsPage(vm: AppViewModel) {
    val lines by Log.lines.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        H1("Логи")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Открыть папку логов", { openFolder(PortalPaths.logs) })
            GhostButton("Очистить окно", { Log.clearView() })
        }
        Muted("Подробные логи сохраняются в файлы (хранятся 14 дней). Здесь — последние ${lines.size} строк.", size = 12)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
        LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(PC.panel).padding(12.dp), state = listState) {
            items(lines) { l ->
                Text(
                    l, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = when {
                        "[ERROR]" in l -> PC.danger; "[WARN]" in l -> PC.warn; else -> PC.muted
                    },
                )
            }
        }
    }
}

/** Системное окно выбора папки. Возвращает путь или null, если пользователь отменил. */
fun pickFolder(title: String): String? {
    val fc = javax.swing.JFileChooser()
    fc.dialogTitle = title
    fc.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    fc.isAcceptAllFileFilterUsed = false
    return if (fc.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) fc.selectedFile.absolutePath else null
}
