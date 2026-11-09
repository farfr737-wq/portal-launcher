package com.portal.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.portal.accounts.AccountStore
import com.portal.core.ErrorAction
import com.portal.core.PortalException
import com.portal.core.Report
import com.portal.java.JavaInstall
import com.portal.java.JavaManager
import com.portal.launcher.GameLauncher
import com.portal.loaders.LoaderType
import com.portal.loaders.LoaderVersion
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import com.portal.versions.InstallService
import com.portal.versions.InstanceConfig
import com.portal.versions.Instances
import com.portal.versions.Manifest
import com.portal.versions.VersionManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Page(val title: String) {
    HOME("Главная"), VERSIONS("Версии"), CONTENT("Контент"), SKINS("Скины"), COMMUNITY("Сообщество"), SETTINGS("Настройки"), LOGS("Логи")
}

class UiError(val title: String, val hint: String?, val action: ErrorAction?)

class BusyState(val title: String) {
    var message by mutableStateOf("")
    var fraction by mutableStateOf<Float?>(null)
    var job: Job? = null
}

enum class GameState { IDLE, PREPARING, RUNNING }

class AppViewModel {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var page by mutableStateOf(Page.HOME)
    val instances = mutableStateListOf<InstanceConfig>()
    var selectedId by mutableStateOf(SettingsStore.value.selectedInstanceId)
    var manifest by mutableStateOf<VersionManifest?>(null)
    var manifestError by mutableStateOf<String?>(null)
    var error by mutableStateOf<UiError?>(null)
    var busy by mutableStateOf<BusyState?>(null)
    var gameState by mutableStateOf(GameState.IDLE)
    var gameStatus by mutableStateOf("")
    var gameProgress by mutableStateOf<Float?>(null)
    var javaList by mutableStateOf<List<JavaInstall>>(emptyList())
    var toast by mutableStateOf<String?>(null)

    val selected: InstanceConfig? get() = instances.firstOrNull { it.id == selectedId } ?: instances.firstOrNull()

    fun start() {
        refreshInstances()
        loadManifest()
        scope.launch { javaList = JavaManager.detect() }
    }

    fun refreshInstances() {
        val list = Instances.list()
        instances.clear(); instances.addAll(list)
        if (instances.none { it.id == selectedId }) selectedId = instances.firstOrNull()?.id ?: ""
    }

    fun loadManifest() {
        scope.launch {
            try {
                manifest = Manifest.load(); manifestError = null
            } catch (e: Exception) {
                Log.warn("Манифест версий недоступен: ${e.message}")
                manifestError = "Не удалось загрузить список версий Minecraft. Проверьте подключение к интернету."
            }
        }
    }

    fun select(id: String) {
        selectedId = id
        SettingsStore.update { it.copy(selectedInstanceId = id) }
    }

    fun showError(e: Throwable, title: String? = null) {
        if (e is CancellationException) return
        Log.error(title ?: "Ошибка", e)
        error = if (e is PortalException) UiError(e.message ?: "Ошибка", e.hint, e.action)
        else UiError(title ?: "Произошла ошибка", (e.message ?: e.javaClass.simpleName) + ". Подробности — в разделе «Логи».", null)
    }

    fun toast(msg: String) { toast = msg }

    /** Запускает длительную операцию с индикатором прогресса и кнопкой «Отмена». */
    fun runBusy(title: String, successMsg: String?, block: suspend (Report) -> Unit) {
        if (busy != null) return
        val b = BusyState(title)
        busy = b
        b.job = scope.launch {
            try {
                block { msg, f ->
                    if (msg.isNotBlank()) b.message = msg
                    if (!f.isNaN()) b.fraction = f
                }
                if (successMsg != null) toast = successMsg
            } catch (e: CancellationException) {
                toast = "Отменено"
                Log.info("Операция отменена: $title")
            } catch (e: Throwable) {
                showError(e, "$title: ошибка")
            } finally {
                busy = null
                withContext(Dispatchers.Default) { refreshInstances() }
            }
        }
    }

    fun cancelBusy() { busy?.job?.cancel() }

    fun install(mc: String, loader: LoaderType, lv: LoaderVersion?, optifine: LoaderVersion? = null) {
        runBusy("Установка Minecraft $mc ${if (loader == LoaderType.VANILLA) "" else loader.title}".trim(), null) { report ->
            val cfg = InstallService.install(mc, loader, lv, report, optifine)
            selectedId = cfg.id
            SettingsStore.update { it.copy(selectedInstanceId = cfg.id) }
            toast = "Установлено: ${cfg.displayName}"
        }
    }

    fun runAction(a: ErrorAction) {
        error = null
        runBusy(a.label, "Готово") { a.run() }
    }

    fun delete(cfg: InstanceConfig, deleteData: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Instances.delete(cfg, deleteData) }
                toast = "Версия удалена"
            } catch (e: Throwable) { showError(e) }
            refreshInstances()
        }
    }

    fun save(cfg: InstanceConfig) {
        try {
            Instances.save(cfg)
            val i = instances.indexOfFirst { it.id == cfg.id }
            if (i >= 0) instances[i] = cfg
        } catch (e: Throwable) { showError(e, "Не удалось сохранить настройки версии") }
    }

    /** Нажатие «ИГРАТЬ»: сессия -> файлы -> Java -> процесс. */
    fun play() {
        val cfg = selected ?: run { page = Page.VERSIONS; return }
        if (gameState != GameState.IDLE) return
        val acc = AccountStore.active ?: run {
            error = UiError("Нет профиля для игры", "Создайте локальный профиль или войдите через Microsoft (кнопка профиля внизу окна).", null)
            return
        }
        gameState = GameState.PREPARING
        gameProgress = null
        scope.launch {
            try {
                gameStatus = "Авторизация"
                val session = AccountStore.session(acc)
                val plan = GameLauncher.prepare(cfg, session) { m, f -> gameStatus = m; if (!f.isNaN()) gameProgress = f }
                gameStatus = "Запуск игры"
                gameProgress = null
                val proc = GameLauncher.start(plan, session)
                gameState = GameState.RUNNING
                gameStatus = "Игра запущена"
                val started = System.currentTimeMillis()
                withContext(Dispatchers.IO) { proc.process.waitFor() }
                val code = proc.process.exitValue()
                Log.info("Игра ${cfg.id} завершилась с кодом $code")
                if (code != 0 && System.currentTimeMillis() - started < 120_000) {
                    val tail = synchronized(proc.tail) { proc.tail.takeLast(6).joinToString("\n") }
                    error = UiError(
                        "Игра завершилась с ошибкой (код $code)",
                        "Полный лог: ${proc.logFile}\n\n$tail",
                        null
                    )
                }
            } catch (e: Throwable) {
                showError(e, "Не удалось запустить игру")
            } finally {
                gameState = GameState.IDLE
                gameStatus = ""
                gameProgress = null
                if (SettingsStore.value.autoCleanTemp) withContext(Dispatchers.IO) { com.portal.cache.CacheManager.cleanTemp() }
            }
        }
    }
}
