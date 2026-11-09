package com.portal.content

import com.portal.versions.InstanceConfig

enum class ContentType(
    val title: String,
    /** Папка внутри папки игры (для датапаков — внутри мира, для сборок не используется). */
    val folder: String,
    /** Тип проекта в Modrinth. */
    val modrinth: String,
    /** classId в CurseForge. */
    val cfClass: Int,
) {
    MOD("Моды", "mods", "mod", 6),
    RESOURCEPACK("Ресурспаки", "resourcepacks", "resourcepack", 12),
    SHADER("Шейдеры", "shaderpacks", "shader", 6552),
    DATAPACK("Датапаки", "datapacks", "datapack", 6945),
    MODPACK("Сборки", "", "modpack", 4471);
}

data class ContentItem(
    val source: String,          // "modrinth" | "curseforge"
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val downloads: Long,
    val iconUrl: String?,
    val pageUrl: String,
)

data class Dependency(val projectId: String, val required: Boolean)

data class ContentFile(
    val fileName: String,
    /** null — автор запретил скачивание из сторонних программ (CurseForge). */
    val url: String?,
    val sha1: String?,
    val size: Long,
    val versionName: String,
    val dependencies: List<Dependency>,
)

interface ContentProvider {
    val name: String

    /** [loaders] — допустимые загрузчики (пусто — без фильтра), [mc] — версия игры (null — без фильтра). */
    suspend fun search(query: String, type: ContentType, mc: String?, loaders: List<String>, offset: Int): List<ContentItem>

    /** Лучшая (последняя релизная) совместимая версия проекта. Бросает PortalException, если подходящей нет. */
    suspend fun bestFile(projectId: String, type: ContentType, mc: String?, loaders: List<String>): ContentFile
}

fun loaderFilters(cfg: InstanceConfig): List<String> = when (cfg.loader) {
    "forge" -> listOf("forge")
    "neoforge" -> listOf("neoforge")
    "fabric" -> listOf("fabric")
    "quilt" -> listOf("quilt", "fabric")   // Quilt загружает моды Fabric
    else -> emptyList()
}

fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
