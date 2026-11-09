package com.portal.content

import com.portal.core.Http
import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.arr
import com.portal.core.long
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/** Официальное открытое API Modrinth (ключ не нужен). */
object ModrinthProvider : ContentProvider {
    override val name = "Modrinth"
    private const val API = "https://api.modrinth.com/v2"

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun jsonList(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }

    override suspend fun search(
        query: String, type: ContentType, mc: String?, loaders: List<String>, offset: Int,
    ): List<ContentItem> = withContext(Dispatchers.IO) {
        val facets = ArrayList<String>()
        facets += "[\"project_type:${type.modrinth}\"]"
        if (mc != null) facets += "[\"versions:$mc\"]"
        if (loaders.isNotEmpty()) facets += jsonList(loaders.map { "categories:$it" })
        val index = if (query.isBlank()) "downloads" else "relevance"
        val url = "$API/search?query=${enc(query)}&facets=${enc("[" + facets.joinToString(",") + "]")}&limit=20&offset=$offset&index=$index"
        val hits = parseObject(Http.getString(url)).arr("hits") ?: return@withContext emptyList()
        hits.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.string("project_id") ?: return@mapNotNull null
            ContentItem(
                source = "modrinth",
                id = id,
                title = o.string("title") ?: id,
                author = o.string("author") ?: "",
                description = o.string("description") ?: "",
                downloads = o.long("downloads") ?: 0,
                iconUrl = o.string("icon_url"),
                pageUrl = "https://modrinth.com/${type.modrinth}/${o.string("slug") ?: id}",
            )
        }
    }

    override suspend fun bestFile(projectId: String, type: ContentType, mc: String?, loaders: List<String>): ContentFile =
        withContext(Dispatchers.IO) {
            val q = StringBuilder("$API/project/${enc(projectId)}/version?")
            if (loaders.isNotEmpty()) q.append("loaders=").append(enc(jsonList(loaders))).append("&")
            if (mc != null) q.append("game_versions=").append(enc(jsonList(listOf(mc))))
            val versions = (PortalJson.parseToJsonElement(Http.getString(q.toString())) as JsonArray).mapNotNull { it as? JsonObject }
            val v = versions.firstOrNull { it.string("version_type") == "release" } ?: versions.firstOrNull()
                ?: throw PortalException(
                    "Нет подходящей версии${if (mc != null) " для Minecraft $mc" else ""}${if (loaders.isNotEmpty()) " (${loaders.first()})" else ""}",
                    "Попробуйте другой проект или другую версию игры."
                )
            val files = v.arr("files")?.mapNotNull { it as? JsonObject } ?: emptyList()
            val f = files.firstOrNull { (it["primary"]?.toString() ?: "") == "true" } ?: files.firstOrNull()
                ?: throw PortalException("У этой версии нет файлов для скачивания")
            val deps = v.arr("dependencies")?.mapNotNull { d ->
                val o = d as? JsonObject ?: return@mapNotNull null
                val pid = o.string("project_id") ?: return@mapNotNull null
                Dependency(pid, o.string("dependency_type") == "required")
            } ?: emptyList()
            ContentFile(
                fileName = f.string("filename") ?: "file.jar",
                url = f.string("url"),
                sha1 = f.obj("hashes")?.string("sha1"),
                size = f.long("size") ?: 0,
                versionName = v.string("name") ?: v.string("version_number") ?: "",
                dependencies = deps,
            )
        }
}
