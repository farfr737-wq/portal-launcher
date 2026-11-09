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
    val cfApiKey: String = "",
    // Оформление
    val language: String = "ru",
    val colorBg: String = "",                // "#RRGGBB", пусто = по умолчанию
    val colorPanel: String = "",
    val colorPanel2: String = "",
    val colorAccent: String = "",
    val colorButton: String = "",
    val colorText: String = "",
    val colorMuted: String = "",
    val colorOutline: String = "",
    val appIcon: String = "",                // "", имя встроенной иконки или "custom"
    val wallpaper: String = "",              // "", имя встроенных обоев (w01…) или "custom"
    val wallpaperDim: Int = 55,              // затемнение обоев, %
    val wallpaperEverywhere: Boolean = true, // фон под всеми страницами, а не только на главной
    val assetRev: Int = 0,                   // растёт при замене своей картинки — сбрасывает кэш
)

/** Настройки хранятся в одном JSON-файле, запись — только при изменении (без фонового опроса). */
object SettingsStore {
    private val _state = MutableStateFlow(LauncherSettings())
    val state: StateFlow<LauncherSettings> get() = _state

    val value: LauncherSettings get() = _state.value

    /** Вызывается после каждого изменения (интерфейс подписывается на тему и язык). */
    @Volatile var onChange: ((LauncherSettings) -> Unit)? = null

    fun load() {
        try {
            if (Files.isRegularFile(PortalPaths.settingsFile)) {
                _state.value = PortalJson.decodeFromString(Files.readString(PortalPaths.settingsFile))
                onChange?.invoke(_state.value)
            }
        } catch (e: Exception) {
            Log.error("Не удалось прочитать settings.json, используются значения по умолчанию", e)
        }
    }

    @Synchronized
    fun update(block: (LauncherSettings) -> LauncherSettings) {
        val next = block(_state.value)
        _state.value = next
        onChange?.invoke(next)
        try {
            PortalPaths.settingsFile.writeTextAtomic(PortalJson.encodeToString(LauncherSettings.serializer(), next))
        } catch (e: Exception) {
            Log.error("Не удалось сохранить настройки", e)
        }
    }
}
