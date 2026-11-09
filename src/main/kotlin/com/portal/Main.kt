package com.portal

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import com.portal.accounts.AccountStore
import com.portal.cache.CacheManager
import com.portal.core.Database
import com.portal.core.PortalPaths
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import com.portal.ui.App
import com.portal.ui.AppViewModel
import com.portal.ui.PortalTheme
import java.awt.Dimension

fun main() {
    com.portal.ui.FirstRunDialog.askIfNeeded()
    PortalPaths.ensureDirs()
    Log.init()
    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.error("Необработанная ошибка в потоке ${t.name}", e) }
    SettingsStore.load()
    com.portal.ui.PC.bind()
    AccountStore.load()

    // Очистка временных файлов при старте (если включена) и контроль размера кэша — один раз, без фоновых таймеров
    Thread({
        try {
            if (SettingsStore.value.autoCleanTemp) CacheManager.cleanTemp()
            CacheManager.enforceLimit()
        } catch (e: Exception) {
            Log.warn("Фоновая очистка не удалась: ${e.message}")
        }
    }, "startup-cleanup").apply { isDaemon = true }.start()

    application {
        val vm = remember { AppViewModel() }
        val st by SettingsStore.state.collectAsState()
        val windowIcon = remember(st.appIcon, st.assetRev) { BitmapPainter(com.portal.ui.Assets.icon(st)) }
        LaunchedEffect(Unit) { vm.start() }
        Window(
            onCloseRequest = {
                Database.close()
                Log.info("Лаунчер закрыт")
                exitApplication()
            },
            title = "Portal Launcher",
            icon = windowIcon,
            state = rememberWindowState(size = DpSize(1120.dp, 720.dp)),
        ) {
            window.minimumSize = Dimension(960, 640)
            PortalTheme { App(vm) }
        }
    }
}
