package com.portal.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Где лежит папка данных. Не зависит от PortalPaths, чтобы вопрос о папке можно было задать до инициализации путей. */
object Locations {
    /** Файл-указатель вне папки данных: в нём записан путь, выбранный пользователем. */
    val pointerFile: Path =
        if (Os.isWindows) Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PortalLauncher-location.txt")
        else Paths.get(System.getProperty("user.home"), ".portal-launcher-location")

    val defaultRoot: Path =
        if (Os.isWindows) Paths.get(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PortalLauncher")
        else Paths.get(System.getProperty("user.home"), ".portal-launcher")

    fun readPointer(): String? = try {
        if (Files.isRegularFile(pointerFile)) Files.readString(pointerFile).trim().ifBlank { null } else null
    } catch (_: Exception) {
        null
    }

    /** Сохраняет новую папку данных (вступает в силу после перезапуска). Пустая строка — вернуть стандартную. */
    fun setCustomRoot(path: String) {
        val p = path.trim()
        if (p.isEmpty()) {
            Files.deleteIfExists(pointerFile)
        } else {
            Files.createDirectories(Paths.get(p))
            Files.writeString(pointerFile, p)
        }
    }

    /** Порядок выбора: -Dportal.home -> PORTAL_HOME -> указатель -> стандартная папка. */
    fun resolveRoot(): Path {
        val sys = System.getProperty("portal.home")
        val env = System.getenv("PORTAL_HOME")
        val ptr = readPointer()
        return when {
            !sys.isNullOrBlank() -> Paths.get(sys)
            !env.isNullOrBlank() -> Paths.get(env)
            ptr != null -> Paths.get(ptr)
            else -> defaultRoot
        }
    }

    /** Самый первый запуск: папку ещё не выбирали и стандартная папка пуста. */
    fun isFirstRun(): Boolean =
        System.getProperty("portal.home").isNullOrBlank() && System.getenv("PORTAL_HOME").isNullOrBlank() &&
            readPointer() == null && !Files.isDirectory(defaultRoot.resolve("launcher"))
}
