package com.portal.settings

import com.portal.core.PortalJson
import com.portal.core.PortalPaths
import com.portal.core.writeTextAtomic
import com.portal.logging.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Files

@Serializable
data class LauncherSettings(
    // Загрузки
    val maxDownloadKbps: Int = 0,            // 0 = без ограничения
    val downloadThreads: Int = 6,
    val autoCleanTemp: Boolean = true,
    val verifyIntegrityOnLaunch: Boolean = false,
    val cacheLimitMb: Int = 200,
    // Запуск
    val defaultRamMb: Int = 2048,
    val javaPath: String = "",               // глобальная Java (пусто = автоопределение)
    val selectedInstanceId: String = "",
    val activeAccountId: String = "",
    val selectedSkinId: String = "",
    // Сервисы
    val socialServerUrl: String = "",
    val portalUserId: String = "",
    val msClientId: String = "",
)

/** Настройки хранятся в одном JSON-файле, запись — только при изменении (без фонового опроса). */
object SettingsStore {
    private val _state = MutableStateFlow(LauncherSettings())
    val state: StateFlow<LauncherSettings> get() = _state

    val value: LauncherSettings get() = _state.value

    fun load() {
        try {
            if (Files.isRegularFile(PortalPaths.settingsFile)) {
                _state.value = PortalJson.decodeFromString(Files.readString(PortalPaths.settingsFile))
            }
        } catch (e: Exception) {
            Log.error("Не удалось прочитать settings.json, используются значения по умолчанию", e)
        }
    }

    @Synchronized
    fun update(block: (LauncherSettings) -> LauncherSettings) {
        val next = block(_state.value)
        _state.value = next
        try {
            PortalPaths.settingsFile.writeTextAtomic(PortalJson.encodeToString(LauncherSettings.serializer(), next))
        } catch (e: Exception) {
            Log.error("Не удалось сохранить настройки", e)
        }
    }
}
