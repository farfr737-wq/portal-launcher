package com.portal.loaders

import com.portal.cache.MetadataCache
import com.portal.core.Http
import com.portal.core.Os
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.core.Report
import com.portal.core.copyTree
import com.portal.core.deleteTree
import com.portal.core.sub
import com.portal.core.writeTextAtomic
import com.portal.downloads.DownloadTask
import com.portal.downloads.Downloader
import com.portal.java.JavaManager
import com.portal.logging.Log
import com.portal.versions.GameFiles
import com.portal.versions.VersionResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * OptiFine не имеет публичного API. Используется официальный сайт optifine.net:
 * 1) список версий берётся со страницы загрузок;
 * 2) файл скачивается по ссылке со страницы adloadx (как делает браузер);
 * 3) запускается ОФИЦИАЛЬНЫЙ установщик OptiFine в изолированной промежуточной папке (его окно нужно подтвердить
 *    кнопкой Install), после чего результат переносится в versions/ и libraries/ Portal.
 * Если авто-загрузка недоступна, положите OptiFine_*.jar в папку «downloads» внутри папки Portal.
 */
class OptiFineProvider : LoaderProvider {
    override val type = LoaderType.OPTIFINE

    private companion object {
        const val SITE = "https://optifine.net"
        val FILE_RE = Regex("adloadx\\?f=(OptiFine_([0-9][0-9.]*)_HD_U_([A-Za-z0-9_]+)\\.jar)")
        val DOWNLOAD_RE = Regex("downloadx\\?f=[^'\"&]+&(?:amp;)?x=[A-Za-z0-9]+")
    }

    private fun allFiles(): List<Triple<String, String, String>> {
        val html = MetadataCache.getText("$SITE/downloads", 60 * 60_000L)
        return FILE_RE.findAll(html).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .distinctBy { it.first }.toList()
    }

    override suspend fun versions(mc: String): List<LoaderVersion> = withContext(Dispatchers.IO) {
        allFiles().filter { it.second == mc }
            .mapIndexed { i, t -> LoaderVersion("HD_U_${t.third}", recommended = i == 0, extra = t.first) }
    }

    override fun unavailableReason(mc: String) =
        "На официальной странице optifine.net нет релизных сборок OptiFine для Minecraft $mc. OptiFine выходит с задержкой после новых версий игры."

    override suspend fun install(mc: String, version: LoaderVersion, report: Report): String {
        val file = version.extra
        val vanilla = VersionResolver.resolve(mc)
        val java = JavaManager.require(vanilla.javaMajor)

        report("Получение OptiFine с optifine.net", 0.03f)
        val jar = obtainJar(file, report.sub(0.03f, 0.3f))

        // Промежуточный «.minecraft» с копией ванильной версии: установщик ожидает найти её там
        val stage = PortalPaths.tmp.resolve("optifine-stage")
        val mcDir = stage.resolve("appdata").resolve(".minecraft")
        withContext(Dispatchers.IO) {
            stage.deleteTree()
            Files.createDirectories(mcDir.resolve("versions"))
            copyTree(PortalPaths.versions.resolve(mc), mcDir.resolve("versions").resolve(mc))
            mcDir.resolve("launcher_profiles.json").writeTextAtomic("{\"profiles\":{},\"version\":3}")
        }

        report("Откроется окно установщика OptiFine — нажмите «Install»", 0.35f)
        runInstallerGui(java.path, jar.toString(), stage)

        val newId = withContext(Dispatchers.IO) {
            val dirs = Files.newDirectoryStream(mcDir.resolve("versions")).use { ds ->
                ds.map { it.fileName.toString() }.filter { it != mc }
            }
            val id = dirs.firstOrNull { it.contains("OptiFine", ignoreCase = true) }
            if (id != null) {
                copyTree(mcDir.resolve("versions").resolve(id), PortalPaths.versions.resolve(id), overwrite = true)
                val libs = mcDir.resolve("libraries")
                if (Files.isDirectory(libs)) copyTree(libs, PortalPaths.libraries, overwrite = true)
            }
            stage.deleteTree()
            id
        } ?: throw PortalException(
            "OptiFine не был установлен",
            "В окне установщика OptiFine нужно нажать «Install». Повторите установку."
        )
        Files.deleteIfExists(jar)
        GameFiles.ensure(VersionResolver.resolve(newId), report.sub(0.9f, 1f))
        return newId
    }

    /** OptiFine как обычный мод (для Forge): скачивает jar и кладёт его в папку mods. */
    suspend fun installAsMod(version: LoaderVersion, modsDir: Path, report: Report) {
        val jar = obtainJar(version.extra, report)
        withContext(Dispatchers.IO) {
            Files.createDirectories(modsDir)
            Files.copy(jar, modsDir.resolve(version.extra), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (jar.parent == PortalPaths.tmp) Files.deleteIfExists(jar)
        }
    }

    private suspend fun obtainJar(file: String, report: Report): Path {
        // Ручной вариант: файл уже лежит в папке downloads
        val manual = PortalPaths.manualDownloads.resolve(file)
        if (Files.isRegularFile(manual)) return manual
        val target = PortalPaths.tmp.resolve(file)
        val url = withContext(Dispatchers.IO) {
            try {
                val page = Http.getString("$SITE/adloadx?f=$file")
                DOWNLOAD_RE.find(page)?.value?.replace("&amp;", "&")?.let { "$SITE/$it" }
            } catch (e: Exception) {
                Log.warn("OptiFine: страница загрузки недоступна: ${e.message}")
                null
            }
        } ?: throw PortalException(
            "Не удалось получить ссылку на скачивание OptiFine с optifine.net",
            "Скачайте $file вручную с optifine.net/downloads и положите в папку ${PortalPaths.manualDownloads}, затем повторите установку."
        )
        Downloader.downloadAll(listOf(DownloadTask(url, target))) { d, t -> report("Скачивание OptiFine", d.toFloat() / t) }
        return target
    }

    private suspend fun runInstallerGui(javaPath: String, jar: String, stage: Path) = withContext(Dispatchers.IO) {
        val appdata = stage.resolve("appdata")
        val pb = ProcessBuilder(javaPath, "-Duser.home=${stage.resolve("home")}", "-cp", jar, "optifine.Installer")
            .directory(stage.toFile())
            .redirectErrorStream(true)
        // Установщик определяет папку Minecraft по APPDATA (Windows) или user.home
        pb.environment()["APPDATA"] = appdata.toString()
        if (!Os.isWindows) Files.createDirectories(stage.resolve("home"))
        val p = pb.start()
        val job = currentCoroutineContext()[Job]
        try {
            thread(isDaemon = true) { p.inputStream.bufferedReader().forEachLine { Log.info("[optifine] $it") } }
            while (!p.waitFor(300, TimeUnit.MILLISECONDS)) job?.ensureActive()
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
    }
}
