package com.portal.versions

import com.portal.core.NoReport
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.core.Report
import com.portal.core.long
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.string
import com.portal.core.sub
import com.portal.downloads.DownloadTask
import com.portal.downloads.Downloader
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Скачивает/проверяет всё, что нужно версии для запуска: клиентский jar, библиотеки, ассеты, конфиг логирования. */
object GameFiles {
    const val ASSET_URL = "https://resources.download.minecraft.net"

    fun clientJar(v: ResolvedVersion): Path = PortalPaths.versions.resolve(v.jarId).resolve("${v.jarId}.jar")
    fun libraryPath(a: Artifact): Path = PortalPaths.libraries.resolve(a.path)
    fun indexFile(v: ResolvedVersion): Path = PortalPaths.assets.resolve("indexes").resolve("${v.assetIndexId}.json")

    suspend fun ensure(v: ResolvedVersion, report: Report = NoReport, verify: Boolean = false) {
        // 1. Клиент + библиотеки
        report("Проверка библиотек", 0f)
        val tasks = ArrayList<DownloadTask>()
        val missingLocal = ArrayList<String>()

        if (v.clientUrl != null) {
            tasks += DownloadTask(v.clientUrl, clientJar(v), v.clientSha1, "SHA-1", v.clientSize)
        } else if (!Files.isRegularFile(clientJar(v))) {
            throw PortalException(
                "Не найден клиентский файл ${v.jarId}.jar",
                "Установите ванильную версию ${v.jarId} и повторите."
            )
        }

        for (lib in v.libraries) {
            for (a in listOfNotNull(lib.artifact, lib.natives)) {
                val target = libraryPath(a)
                if (a.url.isBlank()) {
                    // Библиотеку создаёт установщик загрузчика — докачать её мы не можем
                    if (!Files.isRegularFile(target)) missingLocal += lib.name
                } else {
                    tasks += DownloadTask(a.url, target, a.sha1, "SHA-1", a.size)
                }
            }
        }
        if (missingLocal.isNotEmpty()) {
            throw PortalException(
                "Отсутствуют библиотеки загрузчика (${missingLocal.size}): ${missingLocal.take(3).joinToString()}",
                "Переустановите загрузчик на странице «Версии» — его установщик пересоздаст эти файлы."
            )
        }
        Downloader.downloadAll(tasks, verify) { done, total ->
            report("Библиотеки: ${done / 1024 / 1024} / ${total / 1024 / 1024} МБ", 0.4f * done / total)
        }

        // 2. Конфиг логирования (log4j)
        val lf = v.loggingFile
        if (lf != null && lf.url.isNotBlank() && v.loggingFileId != null) {
            Downloader.downloadAll(
                listOf(DownloadTask(lf.url, PortalPaths.assets.resolve("log_configs").resolve(v.loggingFileId), lf.sha1, "SHA-1", lf.size))
            )
        }

        // 3. Индекс ассетов и сами ассеты
        report("Индекс ресурсов", 0.4f)
        val idxFile = indexFile(v)
        if (v.assetIndexUrl != null) {
            Downloader.downloadAll(listOf(DownloadTask(v.assetIndexUrl, idxFile, v.assetIndexSha1, "SHA-1", v.assetIndexSize)))
        } else if (!Files.isRegularFile(idxFile)) {
            throw PortalException("Нет индекса ресурсов ${v.assetIndexId}", "Переустановите версию.")
        }
        val assetTasks = withContext(Dispatchers.IO) { assetTasks(idxFile) }
        Downloader.downloadAll(assetTasks, verify) { done, total ->
            report("Ресурсы: ${done * 100 / total}%", 0.4f + 0.6f * done / total)
        }
        Log.info("Файлы версии ${v.id} готовы (${tasks.size} файлов клиента, ${assetTasks.size} ресурсов)")
    }

    fun assetTasks(indexFile: Path): List<DownloadTask> {
        val objects = parseObject(Files.readString(indexFile)).obj("objects") ?: return emptyList()
        val root = PortalPaths.assets.resolve("objects")
        return objects.entries.mapNotNull { (_, e) ->
            val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val h = o.string("hash") ?: return@mapNotNull null
            val pre = h.substring(0, 2)
            DownloadTask("$ASSET_URL/$pre/$h", root.resolve(pre).resolve(h), h, "SHA-1", o.long("size") ?: 0)
        }
    }

    /** Быстрая проверка (без хэшей): всё ли на месте. Используется перед запуском, чтобы не качать зря. */
    fun isComplete(v: ResolvedVersion): Boolean {
        if (!Files.isRegularFile(clientJar(v))) return false
        for (lib in v.libraries) for (a in listOfNotNull(lib.artifact, lib.natives)) {
            if (!Files.isRegularFile(libraryPath(a))) return false
        }
        return Files.isRegularFile(indexFile(v))
    }

    fun sub(report: Report, from: Float, to: Float): Report = report.sub(from, to)
}
