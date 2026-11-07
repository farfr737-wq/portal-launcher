package com.portal.loaders

import com.portal.cache.MetadataCache
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.core.Report
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.string
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Forge и NeoForge: берём список версий из официальных maven-метаданных, скачиваем ОФИЦИАЛЬНЫЙ установщик
 * и запускаем его в режиме `--installClient` в корень Portal (он сам скачивает и проверяет библиотеки и
 * выполняет процессоры). Простое создание папок здесь не используется.
 */
class ForgeProvider(override val type: LoaderType) : LoaderProvider {

    private val isNeo = type == LoaderType.NEOFORGE

    private companion object {
        const val FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
        const val FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        const val NEO_MAVEN = "https://maven.neoforged.net/releases/net/neoforged"
        val VERSION_TAG = Regex("<version>([^<]+)</version>")

        /** NeoForge 21.1.x -> Minecraft 1.21.1, 21.0.x -> 1.21, 20.4.x -> 1.20.4. */
        fun neoMc(v: String): String? {
            val m = Regex("^(\\d+)\\.(\\d+)\\.").find(v) ?: return null
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            return if (b == "0") "1.$a" else "1.$a.$b"
        }
    }

    private fun metaVersions(url: String): List<String> =
        VERSION_TAG.findAll(MetadataCache.getText(url, 30 * 60_000L)).map { it.groupValues[1] }.toList()

    override suspend fun versions(mc: String): List<LoaderVersion> = withContext(Dispatchers.IO) {
        if (!isNeo) {
            val all = metaVersions("$FORGE_MAVEN/maven-metadata.xml")
            val recommended = try {
                parseObject(MetadataCache.getText(FORGE_PROMOS, 30 * 60_000L)).obj("promos")?.string("$mc-recommended")
            } catch (_: Exception) {
                null
            }
            all.filter { it.startsWith("$mc-") }.map { it.removePrefix("$mc-") }.reversed()
                .map { LoaderVersion(it, recommended = it == recommended, extra = "forge") }
        } else {
            val result = ArrayList<LoaderVersion>()
            // Старый артефакт NeoForge для 1.20.1 (net.neoforged:forge)
            if (mc == "1.20.1") {
                metaVersions("$NEO_MAVEN/forge/maven-metadata.xml").filter { it.startsWith("1.20.1-") }.reversed()
                    .forEach { result += LoaderVersion(it.removePrefix("1.20.1-"), extra = "forge") }
            }
            metaVersions("$NEO_MAVEN/neoforge/maven-metadata.xml").filter { neoMc(it) == mc }.reversed()
                .forEach { result += LoaderVersion(it, extra = "neoforge") }
            // Самая новая стабильная (без alpha/beta) — рекомендуемая
            val firstStable = result.indexOfFirst { !it.version.contains("beta") && !it.version.contains("alpha") }
            result.mapIndexed { i, v -> v.copy(recommended = i == firstStable) }
        }
    }

    override fun unavailableReason(mc: String) =
        if (isNeo) "Для Minecraft $mc нет сборок NeoForge на maven.neoforged.net. NeoForge выпускается для 1.20.1 и новее — выберите другую версию Minecraft."
        else "Для Minecraft $mc нет сборок Forge на maven.minecraftforge.net. Выберите другую версию Minecraft или другой загрузчик."

    private fun installerUrl(mc: String, v: LoaderVersion): Pair<String, String> {
        return when {
            !isNeo -> {
                val full = "$mc-${v.version}"
                "$FORGE_MAVEN/$full/forge-$full-installer.jar" to full
            }
            v.extra == "forge" -> {
                val full = "$mc-${v.version}"
                "$NEO_MAVEN/forge/$full/forge-$full-installer.jar" to full
            }
            else -> "$NEO_MAVEN/neoforge/${v.version}/neoforge-${v.version}-installer.jar" to v.version
        }
    }

    override suspend fun install(mc: String, version: LoaderVersion, report: Report): String {
        val (url, full) = installerUrl(mc, version)
        val vanilla = VersionResolver.resolve(mc)
        val java = JavaManager.require(vanilla.javaMajor)

        report("Скачивание установщика ${type.title}", 0.02f)
        val jar = PortalPaths.tmp.resolve("${type.id}-$full-installer.jar")
        Downloader.downloadAll(listOf(DownloadTask(url, jar))) { d, t -> report("Установщик ${type.title}", 0.02f + 0.08f * d / t) }

        // Установщику нужен launcher_profiles.json в корне
        val profiles = PortalPaths.root.resolve("launcher_profiles.json")
        if (!Files.exists(profiles)) profiles.writeTextAtomic("{\"profiles\":{},\"version\":3}")

        val before = withContext(Dispatchers.IO) { versionDirs() }
        report("Запуск установщика ${type.title} (Java ${java.major})", 0.12f)
        runInstaller(java.path, jar.toString(), report)
        val after = withContext(Dispatchers.IO) { versionDirs() }

        val id = findInstalledId(mc, version.version, before, after)
            ?: throw PortalException(
                "Установщик ${type.title} завершился, но новая версия не найдена в папке versions",
                "Откройте раздел «Логи» — там вывод установщика."
            )
        Files.deleteIfExists(jar)
        GameFiles.ensure(VersionResolver.resolve(id), report.sub(0.9f, 1f))
        return id
    }

    private fun versionDirs(): Set<String> {
        if (!Files.isDirectory(PortalPaths.versions)) return emptySet()
        return Files.newDirectoryStream(PortalPaths.versions).use { ds -> ds.map { it.fileName.toString() }.toSet() }
    }

    private fun findInstalledId(mc: String, loaderVersion: String, before: Set<String>, after: Set<String>): String? {
        val fresh = (after - before).filter { VersionResolver.exists(it) }
        fresh.firstOrNull { it != mc }?.let { return it }
        // Переустановка: папка уже была — ищем по версии загрузчика
        val key = loaderVersion.substringBefore('-')
        return after.firstOrNull { it != mc && it.contains(key) && VersionResolver.exists(it) }
    }

    private suspend fun runInstaller(javaPath: String, jar: String, report: Report) = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(javaPath, "-jar", jar, "--installClient", PortalPaths.root.toString())
            .directory(PortalPaths.root.toFile())
            .redirectErrorStream(true)
        val p = pb.start()
        val job = currentCoroutineContext()[Job]
        try {
            val reader = thread(isDaemon = true, name = "installer-output") {
                p.inputStream.bufferedReader().forEachLine { line ->
                    Log.info("[installer] $line")
                    report(line.take(90), Float.NaN)
                }
            }
            while (!p.waitFor(300, TimeUnit.MILLISECONDS)) job?.ensureActive()
            reader.join(2000)
            if (p.exitValue() != 0) {
                throw PortalException(
                    "Установщик ${type.title} завершился с ошибкой (код ${p.exitValue()})",
                    "Подробности — в разделе «Логи». Частые причины: нет интернета или неподходящая Java."
                )
            }
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
    }
}
