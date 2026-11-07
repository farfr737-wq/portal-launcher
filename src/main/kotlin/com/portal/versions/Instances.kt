package com.portal.versions

import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.PortalPaths
import com.portal.core.deleteTree
import com.portal.core.writeTextAtomic
import com.portal.logging.Log
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Установленная версия со своими настройками запуска (versions/<id>/portal.json). */
@Serializable
data class InstanceConfig(
    val id: String,
    val displayName: String,
    val mcVersion: String,
    val loader: String = "vanilla",         // vanilla|forge|neoforge|fabric|quilt|optifine
    val loaderVersion: String = "",
    val javaMajor: Int = 8,
    val javaPath: String = "",              // отдельная Java для этой версии
    val ramMb: Int = 0,                      // 0 = значение по умолчанию из настроек
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val width: Int = 854,
    val height: Int = 480,
    val fullscreen: Boolean = false,
    val gameDir: String = "",               // пусто = profiles/<id>
    val mods: List<String> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
) {
    val loaderTitle: String
        get() = when (loader) {
            "forge" -> "Forge"; "neoforge" -> "NeoForge"; "fabric" -> "Fabric"
            "quilt" -> "Quilt"; "optifine" -> "OptiFine"; else -> "Vanilla"
        }
}

object Instances {
    private const val FILE = "portal.json"

    fun dir(id: String): Path = PortalPaths.versions.resolve(id)

    fun gameDir(cfg: InstanceConfig): Path =
        if (cfg.gameDir.isBlank()) PortalPaths.profiles.resolve(cfg.id) else Paths.get(cfg.gameDir)

    /** Сканируется только по запросу (без фонового наблюдения за файловой системой). */
    fun list(): List<InstanceConfig> {
        if (!Files.isDirectory(PortalPaths.versions)) return emptyList()
        return Files.newDirectoryStream(PortalPaths.versions).use { ds ->
            ds.mapNotNull { load(it.fileName.toString()) }
        }.sortedWith(compareBy({ it.mcVersion }, { it.loader }, { it.id }))
    }

    fun load(id: String): InstanceConfig? {
        val f = dir(id).resolve(FILE)
        if (!Files.isRegularFile(f)) return null
        return try {
            PortalJson.decodeFromString(InstanceConfig.serializer(), Files.readString(f))
        } catch (e: Exception) {
            Log.warn("Не удалось прочитать $f: ${e.message}")
            null
        }
    }

    fun save(cfg: InstanceConfig) {
        dir(cfg.id).resolve(FILE).writeTextAtomic(PortalJson.encodeToString(InstanceConfig.serializer(), cfg))
    }

    /** Список модов — jar-файлы из папки mods этой версии. */
    fun scanMods(cfg: InstanceConfig): List<String> {
        val mods = gameDir(cfg).resolve("mods")
        if (!Files.isDirectory(mods)) return emptyList()
        return Files.newDirectoryStream(mods, "*.jar").use { ds -> ds.map { it.fileName.toString() }.sorted() }
    }

    fun refreshMods(cfg: InstanceConfig): InstanceConfig {
        val updated = cfg.copy(mods = scanMods(cfg))
        save(updated)
        return updated
    }

    /** Версии, зависящие от ванильной (загрузчики наследуют её jar). */
    fun dependents(mcId: String): List<InstanceConfig> =
        list().filter { it.id != mcId && it.mcVersion == mcId && it.loader != "vanilla" }

    /**
     * Удаляет файлы версии. Папка профиля (миры, моды, настройки игры) удаляется только если deleteData = true.
     */
    fun delete(cfg: InstanceConfig, deleteData: Boolean) {
        val deps = if (cfg.loader == "vanilla") dependents(cfg.id) else emptyList()
        if (deps.isNotEmpty()) {
            throw PortalException(
                "Нельзя удалить ${cfg.id}: от неё зависят ${deps.joinToString { it.id }}",
                "Сначала удалите версии с загрузчиками."
            )
        }
        dir(cfg.id).deleteTree()
        if (deleteData) gameDir(cfg).deleteTree()
        Log.info("Версия ${cfg.id} удалена (данные профиля удалены: $deleteData)")
    }
}
