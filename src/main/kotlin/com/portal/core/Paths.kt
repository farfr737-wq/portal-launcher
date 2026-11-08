package com.portal.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Раскладка папок лаунчера. Корень совпадает с игровым корнем (libraries/assets/versions),
 * поэтому официальные установщики Forge/NeoForge работают прямо в нём.
 */
object PortalPaths {
    /** Файл-указатель вне папки данных: в нём записан путь, выбранный пользователем (Настройки -> Папки). */
    val pointerFile: Path =
        if (Os.isWindows) Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PortalLauncher-location.txt")
        else Paths.get(System.getProperty("user.home"), ".portal-launcher-location")

    private fun readPointer(): String? = try {
        if (Files.isRegularFile(pointerFile)) Files.readString(pointerFile).trim().ifBlank { null } else null
    } catch (_: Exception) {
        null
    }

    /** Сохраняет новую папку данных; вступает в силу после перезапуска лаунчера. Пустая строка — вернуть стандартную. */
    fun setCustomRoot(path: String) {
        val p = path.trim()
        if (p.isEmpty()) {
            Files.deleteIfExists(pointerFile)
        } else {
            Files.createDirectories(Paths.get(p))
            Files.writeString(pointerFile, p)
        }
    }

    val defaultRoot: Path =
        if (Os.isWindows) Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PortalLauncher")
        else Paths.get(System.getProperty("user.home"), ".portal-launcher")

    /** Порядок выбора: -Dportal.home -> переменная PORTAL_HOME -> указатель из настроек -> стандартная папка. */
    val root: Path = run {
        val sys = System.getProperty("portal.home")
        val env = System.getenv("PORTAL_HOME")
        val ptr = readPointer()
        when {
            !sys.isNullOrBlank() -> Paths.get(sys)
            !env.isNullOrBlank() -> Paths.get(env)
            ptr != null -> Paths.get(ptr)
            else -> defaultRoot
        }
    }

    val launcher: Path = root.resolve("launcher")
    val accounts: Path = launcher.resolve("accounts")
    val settingsFile: Path = launcher.resolve("settings.json")
    val dbFile: Path = launcher.resolve("portal.db")

    val versions: Path = root.resolve("versions")
    val libraries: Path = root.resolve("libraries")
    val assets: Path = root.resolve("assets")
    val javaDir: Path = root.resolve("java")
    val profiles: Path = root.resolve("profiles")

    val skinsLocal: Path = root.resolve("skins").resolve("local")
    val skinsCache: Path = root.resolve("skins").resolve("cache")

    val logs: Path = root.resolve("logs")
    val cache: Path = root.resolve("cache")
    val metaCache: Path = cache.resolve("meta")
    val tmp: Path = root.resolve("tmp")

    /** Сюда можно вручную положить скачанные файлы (например, OptiFine), если авто-загрузка недоступна. */
    val manualDownloads: Path = root.resolve("downloads")

    fun ensureDirs() {
        listOf(
            launcher, accounts, versions, libraries, assets, javaDir, profiles,
            skinsLocal, skinsCache, logs, metaCache, tmp, manualDownloads
        ).forEach { Files.createDirectories(it) }
    }
}