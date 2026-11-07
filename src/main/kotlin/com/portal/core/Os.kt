package com.portal.core

import java.nio.file.Path

enum class OsType(val mojang: String) { WINDOWS("windows"), LINUX("linux"), MACOS("osx") }

/** Всё, что зависит от ОС, собрано здесь — для будущей поддержки Linux. */
object Os {
    private val name = (System.getProperty("os.name") ?: "").lowercase()

    val type: OsType = when {
        name.contains("win") -> OsType.WINDOWS
        name.contains("mac") || name.contains("darwin") -> OsType.MACOS
        else -> OsType.LINUX
    }

    val isWindows: Boolean get() = type == OsType.WINDOWS
    val version: String = System.getProperty("os.version") ?: ""

    val archId: String = (System.getProperty("os.arch") ?: "").lowercase().let {
        when {
            it.contains("aarch64") || it.contains("arm64") -> "arm64"
            it == "x86" || it == "i386" || it == "i686" -> "x86"
            else -> "x86_64"
        }
    }

    val is64: Boolean get() = archId != "x86"
    val classpathSeparator: String get() = if (isWindows) ";" else ":"

    fun javaBinary(javaHome: Path, gui: Boolean = false): Path =
        javaHome.resolve("bin").resolve(
            if (isWindows) (if (gui) "javaw.exe" else "java.exe") else "java"
        )
}
