package com.portal.versions

import com.portal.cache.MetadataCache
import com.portal.core.NoReport
import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.PortalPaths
import com.portal.core.Report
import com.portal.core.sub
import com.portal.downloads.DownloadTask
import com.portal.downloads.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class ManifestLatest(val release: String = "", val snapshot: String = "")

@Serializable
data class ManifestEntry(
    val id: String,
    val type: String,
    val url: String,
    val sha1: String? = null,
    val releaseTime: String = "",
)

@Serializable
data class VersionManifest(
    val latest: ManifestLatest = ManifestLatest(),
    val versions: List<ManifestEntry> = emptyList(),
)

/** Официальный Version Manifest Mojang. */
object Manifest {
    const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    suspend fun load(): VersionManifest = withContext(Dispatchers.IO) {
        PortalJson.decodeFromString(VersionManifest.serializer(), MetadataCache.getText(URL, 30 * 60_000L))
    }
}

object VanillaInstaller {
    /** Устанавливает ванильную версию (или докачивает недостающее). Возвращает разобранную версию. */
    suspend fun install(mcId: String, report: Report = NoReport): ResolvedVersion {
        report("Получение манифеста версий", 0f)
        val entry = Manifest.load().versions.firstOrNull { it.id == mcId }
            ?: throw PortalException(
                "Версия $mcId не найдена в официальном манифесте Mojang",
                "Проверьте номер версии или обновите список на странице «Версии»."
            )
        val json = PortalPaths.versions.resolve(mcId).resolve("$mcId.json")
        Downloader.downloadAll(listOf(DownloadTask(entry.url, json, entry.sha1, "SHA-1", 0)))
        val resolved = VersionResolver.resolve(mcId)
        GameFiles.ensure(resolved, report.sub(0.03f, 1f))
        return resolved
    }
}
