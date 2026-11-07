package com.portal.logging

import com.portal.core.PortalPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Файловый логгер + кольцевой буфер последних строк для страницы «Логи». */
object Log {
    private const val MAX_LINES = 600
    private const val KEEP_DAYS = 14L

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private var writer: BufferedWriter? = null

    @Synchronized
    fun init() {
        try {
            Files.createDirectories(PortalPaths.logs)
            pruneOld()
            val file = PortalPaths.logs.resolve("launcher-${LocalDate.now()}.log")
            writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) {
            writer = null
        }
        info("=== Portal Launcher запущен (Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")}) ===")
    }

    private fun pruneOld() {
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        Files.newDirectoryStream(PortalPaths.logs, "*.log").use { ds ->
            for (p in ds) if (Files.getLastModifiedTime(p).toMillis() < limit) Files.deleteIfExists(p)
        }
    }

    fun info(msg: String) = write("INFO", msg, null)
    fun warn(msg: String, t: Throwable? = null) = write("WARN", msg, t)
    fun error(msg: String, t: Throwable? = null) = write("ERROR", msg, t)

    @Synchronized
    private fun write(level: String, msg: String, t: Throwable?) {
        val line = "${LocalDateTime.now().format(timeFmt)} [$level] $msg"
        val full = if (t == null) line else line + "\n" + stack(t)
        try {
            writer?.let { it.write(full); it.newLine(); it.flush() }
        } catch (_: Exception) {
        }
        _lines.update { (it + line + (if (t != null) listOf("    ${t.javaClass.simpleName}: ${t.message}") else emptyList())).takeLast(MAX_LINES) }
    }

    fun clearView() {
        _lines.value = emptyList()
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().trimEnd()
    }
}
