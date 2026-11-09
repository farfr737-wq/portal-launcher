package com.portal.content

import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.core.Report
import com.portal.core.arr
import com.portal.core.long
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.string
import com.portal.core.sub
import com.portal.core.writeTextAtomic
import com.portal.downloads.DownloadTask
import com.portal.downloads.Downloader
import com.portal.loaders.LoaderType
import com.portal.loaders.LoaderVersion
import com.portal.loaders.Loaders
import com.portal.logging.Log
import com.portal.versions.InstallService
import com.portal.versions.InstanceConfig
import com.portal.versions.Instances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/** Установка сборок: .mrpack (Modrinth) и zip с manifest.json (CurseForge). Каждая сборка — отдельная версия со своей папкой игры. */
object ModpackInstaller {

    private class Pack(
        val name: String, val mc: String, val loader: LoaderType, val loaderVersion: String?,
        val files: List<PackFile>, val overridesDirs: List<String>,
    )

    private class PackFile(val path: String, val url: String?, val sha1: String?, val size: Long, val fallbackPage: String? = null)

    private fun slug(s: String): String {
        val r = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return r.ifBlank { "pack" }.take(40)
    }

    /** Скачивает и устанавливает сборку из каталога. */
    suspend fun installOnline(provider: ContentProvider, item: ContentItem, report: Report): String {
        report("Поиск версии сборки", Float.NaN)
        val f = provider.bestFile(item.id, ContentType.MODPACK, null, emptyList())
        val url = f.url ?: throw PortalException(
            "Автор запретил скачивание «${item.title}» из сторонних программ",
            "Скачайте сборку вручную на странице ${item.pageUrl} и установите кнопкой «Добавить файл сборки»."
        )
        val tmp = PortalPaths.tmp.resolve("pack-" + System.nanoTime() + "-" + f.fileName.substringAfterLast('/'))
        Files.createDirectories(tmp.parent)
        try {
            Downloader.downloadAll(listOf(DownloadTask(url, tmp, f.sha1, "SHA-1", f.size))) { d, t ->
                report("Скачивание сборки", 0.1f * d / t.coerceAtLeast(1))
            }
            return installArchive(tmp, item.title, report.sub(0.1f, 1f))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Установка из файла, выбранного пользователем (.mrpack или .zip). */
    suspend fun installFile(file: Path, report: Report): String =
        installArchive(file, file.fileName.toString().substringBeforeLast('.'), report)

    private suspend fun installArchive(archive: Path, fallbackName: String, report: Report): String {
        report("Чтение сборки", Float.NaN)
        val pack = withContext(Dispatchers.IO) { parse(archive, fallbackName) }
        val packId = "pack-" + slug(pack.name)

        // 1. Базовая версия: Minecraft + загрузчик
        val lv: LoaderVersion? = if (pack.loader == LoaderType.VANILLA) null else {
            val list = Loaders.get(pack.loader).versions(pack.mc)
            list.firstOrNull { it.version == pack.loaderVersion }
                ?: list.firstOrNull { pack.loaderVersion != null && it.version.endsWith(pack.loaderVersion) }
                ?: list.firstOrNull { it.recommended } ?: list.firstOrNull()
                ?: throw PortalException("Для Minecraft ${pack.mc} нет загрузчика ${pack.loader.title}", Loaders.get(pack.loader).unavailableReason(pack.mc))
        }
        val base = InstallService.install(pack.mc, pack.loader, lv, report.sub(0f, 0.5f))

        // 2. Отдельная версия сборки, наследующая базовую
        val dir = Instances.dir(packId)
        withContext(Dispatchers.IO) {
            Files.createDirectories(dir)
            dir.resolve("$packId.json").writeTextAtomic("""{"id":"$packId","inheritsFrom":"${base.id}","type":"release"}""")
        }
        val cfg = InstanceConfig(
            id = packId, displayName = pack.name, mcVersion = pack.mc, loader = base.loader,
            loaderVersion = base.loaderVersion, javaMajor = base.javaMajor,
        )
        withContext(Dispatchers.IO) { Instances.save(cfg); Files.createDirectories(Instances.gameDir(cfg)) }
        val game = Instances.gameDir(cfg).toAbsolutePath().normalize()

        // 3. Файлы сборки
        val missing = ArrayList<String>()
        val tasks = ArrayList<DownloadTask>()
        for (pf in pack.files) {
            val target = game.resolve(pf.path).normalize()
            if (!target.startsWith(game)) { Log.warn("Пропущен небезопасный путь в сборке: ${pf.path}"); continue }
            if (pf.url == null) { missing += pf.fallbackPage ?: pf.path; continue }
            tasks += DownloadTask(pf.url, target, pf.sha1, "SHA-1", pf.size)
        }
        val r3 = report.sub(0.5f, 0.95f)
        Downloader.downloadAll(tasks) { d, t -> r3("Скачивание файлов сборки (${tasks.size})", d.toFloat() / t.coerceAtLeast(1)) }

        // 4. Overrides (конфиги, ресурспаки и т.п.)
        report("Распаковка конфигов сборки", 0.96f)
        withContext(Dispatchers.IO) { extractOverrides(archive, pack.overridesDirs, game) }
        withContext(Dispatchers.IO) { Instances.refreshMods(cfg) }
        report("Готово", 1f)
        Log.info("Сборка установлена: $packId (${tasks.size} файлов, не скачано: ${missing.size})")
        return "Сборка «${pack.name}» установлена." +
            if (missing.isEmpty()) "" else " ${missing.size} файл(ов) запрещено скачивать автором — добавьте их вручную в раздел «Моды» (проект/файл: ${missing.take(3).joinToString()})."
    }

    private fun parse(archive: Path, fallbackName: String): Pack = ZipFile(archive.toFile()).use { zip ->
        val mr = zip.getEntry("modrinth.index.json")
        val cf = zip.getEntry("manifest.json")
        when {
            mr != null -> parseModrinth(zip.getInputStream(mr).readBytes().toString(Charsets.UTF_8), fallbackName)
            cf != null -> parseCurse(zip.getInputStream(cf).readBytes().toString(Charsets.UTF_8), fallbackName)
            else -> throw PortalException("Это не сборка", "Нужен файл .mrpack (Modrinth) или .zip с manifest.json (CurseForge).")
        }
    }

    private fun parseModrinth(text: String, fallbackName: String): Pack {
        val o = parseObject(text)
        val deps = o.obj("dependencies") ?: JsonObject(emptyMap())
        val mc = deps.string("minecraft") ?: throw PortalException("В сборке не указана версия Minecraft")
        val (loader, lv) = when {
            deps.string("fabric-loader") != null -> LoaderType.FABRIC to deps.string("fabric-loader")
            deps.string("quilt-loader") != null -> LoaderType.QUILT to deps.string("quilt-loader")
            deps.string("neoforge") != null -> LoaderType.NEOFORGE to deps.string("neoforge")
            deps.string("forge") != null -> LoaderType.FORGE to deps.string("forge")
            else -> LoaderType.VANILLA to null
        }
        val files = o.arr("files")?.mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            // файлы только для сервера пропускаем
            if (f.obj("env")?.string("client") == "unsupported") return@mapNotNull null
            val path = f.string("path") ?: return@mapNotNull null
            PackFile(path, f.arr("downloads")?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                f.obj("hashes")?.string("sha1"), f.long("fileSize") ?: 0)
        } ?: emptyList()
        return Pack(o.string("name") ?: fallbackName, mc, loader, lv, files, listOf("overrides", "client-overrides"))
    }

    private fun parseCurse(text: String, fallbackName: String): Pack {
        val o = parseObject(text)
        val mcObj = o.obj("minecraft") ?: throw PortalException("В манифесте нет раздела minecraft")
        val mc = mcObj.string("version") ?: throw PortalException("В сборке не указана версия Minecraft")
        val ml = (mcObj.arr("modLoaders")?.mapNotNull { it as? JsonObject }
            ?.let { l -> l.firstOrNull { it["primary"]?.toString() == "true" } ?: l.firstOrNull() })?.string("id") ?: ""
        val (loader, lv) = when {
            ml.startsWith("forge-") -> LoaderType.FORGE to ml.removePrefix("forge-")
            ml.startsWith("neoforge-") -> LoaderType.NEOFORGE to ml.removePrefix("neoforge-")
            ml.startsWith("fabric-") -> LoaderType.FABRIC to ml.removePrefix("fabric-")
            ml.startsWith("quilt-") -> LoaderType.QUILT to ml.removePrefix("quilt-")
            else -> LoaderType.VANILLA to null
        }
        val ids = o.arr("files")?.mapNotNull { (it as? JsonObject)?.let { f -> f.long("fileID") } } ?: emptyList()
        val resolved = if (ids.isEmpty()) emptyList() else kotlinx.coroutines.runBlocking { CurseForgeProvider.filesByIds(ids) }
        val files = resolved.map { cf ->
            PackFile("mods/" + cf.file.fileName.substringAfterLast('/'), cf.file.url, cf.file.sha1, cf.file.size,
                "https://www.curseforge.com/minecraft/mc-mods/" + cf.modId)
        }
        return Pack(o.string("name") ?: fallbackName, mc, loader, lv, files, listOf(o.string("overrides") ?: "overrides"))
    }

    private fun extractOverrides(archive: Path, dirs: List<String>, game: Path) {
        ZipFile(archive.toFile()).use { zip ->
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val dir = dirs.firstOrNull { e.name.startsWith("$it/") } ?: continue
                val rel = e.name.removePrefix("$dir/")
                val target = game.resolve(rel).normalize()
                if (!target.startsWith(game)) { Log.warn("Пропущен небезопасный путь: ${e.name}"); continue }
                Files.createDirectories(target.parent)
                zip.getInputStream(e).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }
}
