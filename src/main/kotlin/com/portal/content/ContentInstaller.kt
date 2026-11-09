package com.portal.content

import com.portal.core.PortalException
import com.portal.core.Report
import com.portal.core.deleteTree
import com.portal.downloads.DownloadTask
import com.portal.downloads.Downloader
import com.portal.logging.Log
import com.portal.versions.InstanceConfig
import com.portal.versions.Instances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class LocalFile(val path: Path, val name: String, val size: Long)

/** Установка модов, ресурспаков, шейдеров и датапаков в папку выбранной версии (вместе с обязательными зависимостями). */
object ContentInstaller {

    fun providerOf(source: String): ContentProvider = if (source == "curseforge") CurseForgeProvider else ModrinthProvider

    private fun safeName(n: String) = n.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }

    /** Миры (папки saves с level.dat) — нужны для установки датапаков. */
    fun worlds(cfg: InstanceConfig): List<String> {
        val saves = Instances.gameDir(cfg).resolve("saves")
        if (!Files.isDirectory(saves)) return emptyList()
        return Files.newDirectoryStream(saves).use { ds ->
            ds.filter { Files.isRegularFile(it.resolve("level.dat")) }.map { it.fileName.toString() }.sorted()
        }
    }

    fun targetDir(cfg: InstanceConfig, type: ContentType, world: String?): Path {
        val game = Instances.gameDir(cfg)
        return when (type) {
            ContentType.DATAPACK -> {
                val w = world ?: throw PortalException("Выберите мир для датапака", "Датапаки устанавливаются в конкретный мир. Сначала создайте мир в игре.")
                game.resolve("saves").resolve(w).resolve("datapacks")
            }
            ContentType.MODPACK -> throw PortalException("Сборки устанавливаются как отдельная версия")
            else -> game.resolve(type.folder)
        }
    }

    fun listFiles(cfg: InstanceConfig, type: ContentType, world: String?): List<LocalFile> {
        if (type == ContentType.MODPACK) return emptyList()
        val dir = try { targetDir(cfg, type, world) } catch (_: PortalException) { return emptyList() }
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { ds ->
            ds.filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .map { LocalFile(it, it.fileName.toString(), if (Files.isRegularFile(it)) Files.size(it) else 0) }
                .sortedBy { it.name.lowercase() }
        }
    }

    /** Удаляет файл только если он лежит внутри папки этого типа контента. */
    fun delete(cfg: InstanceConfig, type: ContentType, world: String?, file: LocalFile) {
        val dir = targetDir(cfg, type, world).toAbsolutePath().normalize()
        val p = file.path.toAbsolutePath().normalize()
        if (!p.startsWith(dir) || p == dir) throw PortalException("Нельзя удалить файл вне папки контента")
        if (Files.isDirectory(p)) p.deleteTree() else Files.deleteIfExists(p)
        Log.info("Удалён файл контента: $p")
    }

    /** Установка проекта из Modrinth/CurseForge. Для сборок создаёт отдельную версию. */
    suspend fun install(
        provider: ContentProvider, item: ContentItem, type: ContentType,
        cfg: InstanceConfig?, world: String?, report: Report,
    ): String {
        if (type == ContentType.MODPACK) return ModpackInstaller.installOnline(provider, item, report)
        cfg ?: throw PortalException("Сначала установите версию Minecraft", "Страница «Версии» → «Установить новую».")
        val loaders = if (type == ContentType.MOD) loaderFilters(cfg) else emptyList()
        if (type == ContentType.MOD && loaders.isEmpty()) {
            throw PortalException("Для версии ${cfg.loaderTitle} моды не подходят", "Установите Forge, NeoForge, Fabric или Quilt на странице «Версии».")
        }
        val dir = targetDir(cfg, type, world)
        val done = HashSet<String>()
        val installed = ArrayList<String>()

        suspend fun one(projectId: String, pageUrl: String?, depth: Int): Unit {
            if (!done.add(projectId) || depth > 4) return
            report(if (depth == 0) "Поиск подходящей версии" else "Зависимость: $projectId", Float.NaN)
            val f = try {
                provider.bestFile(projectId, type, cfg.mcVersion, loaders)
            } catch (e: PortalException) {
                if (depth == 0) throw e
                Log.warn("Зависимость $projectId пропущена: ${e.message}")
                return
            }
            val url = f.url
            if (url == null) {
                if (depth == 0) {
                    throw PortalException(
                        "Автор запретил скачивание «${item.title}» из сторонних программ",
                        "Скачайте файл вручную на странице ${pageUrl ?: ""} и добавьте его кнопкой «Добавить файл»."
                    )
                }
                Log.warn("Зависимость $projectId недоступна для скачивания из лаунчера")
                return
            }
            Downloader.downloadAll(listOf(DownloadTask(url, dir.resolve(safeName(f.fileName)), f.sha1, "SHA-1", f.size))) { d, t ->
                report("Скачивание ${f.fileName}", if (depth == 0) 0.9f * d / t.coerceAtLeast(1) else Float.NaN)
            }
            installed += f.fileName
            if (type == ContentType.MOD) for (d in f.dependencies) if (d.required) one(d.projectId, null, depth + 1)
        }

        one(item.id, item.pageUrl, 0)
        if (type == ContentType.MOD) withContext(Dispatchers.IO) { Instances.refreshMods(cfg) }
        return "Установлено: " + installed.joinToString().take(140)
    }

    /** Копирует выбранные пользователем файлы в нужную папку. Возвращает число скопированных файлов. */
    suspend fun importFiles(files: List<Path>, cfg: InstanceConfig, type: ContentType, world: String?): Int = withContext(Dispatchers.IO) {
        val allowed = when (type) {
            ContentType.MOD -> listOf(".jar")
            ContentType.RESOURCEPACK, ContentType.SHADER, ContentType.DATAPACK -> listOf(".zip")
            ContentType.MODPACK -> emptyList()
        }
        val bad = files.filter { f -> allowed.none { f.fileName.toString().lowercase().endsWith(it) } }
        if (bad.isNotEmpty()) {
            throw PortalException(
                "Неподходящий тип файла: ${bad.first().fileName}",
                "Для раздела «${type.title}» нужны файлы ${allowed.joinToString()}."
            )
        }
        val dir = targetDir(cfg, type, world)
        Files.createDirectories(dir)
        files.forEach { Files.copy(it, dir.resolve(it.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) }
        if (type == ContentType.MOD) Instances.refreshMods(cfg)
        files.size
    }
}
