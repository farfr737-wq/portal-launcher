package com.portal.loaders

import com.portal.cache.MetadataCache
import com.portal.core.Http
import com.portal.core.HttpException
import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.Report
import com.portal.core.bool
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.string
import com.portal.core.sub
import com.portal.core.writeTextAtomic
import com.portal.versions.GameFiles
import com.portal.versions.VersionResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Fabric и Quilt используют один и тот же формат метаданных (meta-сервер -> готовый профиль версии). */
class FabricLikeProvider(
    override val type: LoaderType,
    private val meta: String,
    private val label: String,
) : LoaderProvider {

    override suspend fun versions(mc: String): List<LoaderVersion> = withContext(Dispatchers.IO) {
        try {
            val arr = PortalJson.parseToJsonElement(MetadataCache.getText("$meta/versions/loader/$mc", 15 * 60_000L)) as JsonArray
            val list = arr.mapNotNull { e ->
                val l = (e as? JsonObject)?.obj("loader") ?: return@mapNotNull null
                val v = l.string("version") ?: return@mapNotNull null
                LoaderVersion(v, recommended = l.bool("stable") == true)
            }
            // Самая новая стабильная — рекомендуемая; остальные не помечаем
            val firstStable = list.indexOfFirst { it.recommended }
            list.mapIndexed { i, v -> v.copy(recommended = i == firstStable) }
        } catch (e: HttpException) {
            if (e.status in 400..499) emptyList() else throw e
        }
    }

    override fun unavailableReason(mc: String) =
        "$label не имеет сборок для Minecraft $mc в официальных метаданных ($meta). Выберите другую версию Minecraft или другой загрузчик."

    override suspend fun install(mc: String, version: LoaderVersion, report: Report): String {
        val id = "$mc-${type.id}-${version.version}"
        report("Получение профиля $label ${version.version}", 0.02f)
        val text = withContext(Dispatchers.IO) {
            Http.getString("$meta/versions/loader/$mc/${version.version}/profile/json")
        }
        val obj = parseObject(text)
        // Приводим id к схеме Portal: <mc>-<loader>-<версия>; inheritsFrom остаётся ванильной версией
        val patched = JsonObject(obj + ("id" to JsonPrimitive(id)))
        withContext(Dispatchers.IO) {
            VersionResolver.versionJson(id).writeTextAtomic(PortalJson.encodeToString(JsonObject.serializer(), patched))
        }
        if (VersionResolver.resolve(id).mainClass.isBlank()) {
            throw PortalException("Профиль $label не содержит mainClass")
        }
        GameFiles.ensure(VersionResolver.resolve(id), report.sub(0.05f, 1f))
        return id
    }
}
