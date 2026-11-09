package com.portal.content

import com.portal.core.Http
import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.arr
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.string
import com.portal.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLEncoder

data class CurseFile(val fileId: Long, val modId: Long, val file: ContentFile)

/** Официальное API CurseForge. Нужен бесплатный ключ (console.curseforge.com), его вводит пользователь в настройках. */
object CurseForgeProvider : ContentProvider {
    override val name = "CurseForge"
    private const val API = "https://api.curseforge.com/v1"
    private const val GAME_ID = 432

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun headers(): Map<String, String> {
        val key = SettingsStore.value.cfApiKey.trim()
        if (key.isEmpty()) {
            throw PortalException(
                "Нужен ключ API CurseForge",
                "Получите бесплатный ключ на console.curseforge.com и вставьте его в Настройки → Сервисы. Пока можно пользоваться Modrinth — ключ там не нужен."
            )
        }
        return mapOf("x-api-key" to key, "Accept" to "application/json")
    }

    private fun get(url: String): JsonObject {
        val r = Http.exchange("GET", url, null, headers())
        if (r.status == 403 || r.status == 401) throw PortalException("CurseForge отклонил ключ API", "Проверьте ключ в Настройки → Сервисы.")
        if (!r.ok) throw PortalException("CurseForge вернул ошибку ${r.status}")
        return parseObject(r.text)
    }

    private fun loaderCode(loaders: List<String>): Int? = when (loaders.firstOrNull()) {
        "forge" -> 1
        "neoforge" -> 6
        "fabric", "quilt" -> 4
        else -> null
    }

    private fun num(o: JsonObject, key: String): Long = (o[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0

    override suspend fun search(
        query: String, type: ContentType, mc: String?, loaders: List<String>, offset: Int,
    ): List<ContentItem> = withContext(Dispatchers.IO) {
        val url = StringBuilder("$API/mods/search?gameId=$GAME_ID&classId=${type.cfClass}&pageSize=20&index=$offset&sortField=2&sortOrder=desc")
        if (query.isNotBlank()) url.append("&searchFilter=").append(enc(query))
        if (mc != null) url.append("&gameVersion=").append(enc(mc))
        loaderCode(loaders)?.let { url.append("&modLoaderType=").append(it) }
        val data = get(url.toString()).arr("data") ?: return@withContext emptyList()
        data.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = num(o, "id").toString()
            ContentItem(
                source = "curseforge",
                id = id,
                title = o.string("name") ?: id,
                author = (o.arr("authors")?.firstOrNull() as? JsonObject)?.string("name") ?: "",
                description = o.string("summary") ?: "",
                downloads = num(o, "downloadCount"),
                iconUrl = o.obj("logo")?.string("thumbnailUrl"),
                pageUrl = o.obj("links")?.string("websiteUrl") ?: "https://www.curseforge.com/minecraft",
            )
        }
    }

    private fun parseFile(o: JsonObject): CurseFile {
        val sha1 = o.arr("hashes")?.mapNotNull { it as? JsonObject }?.firstOrNull { num(it, "algo") == 1L }?.string("value")
        val deps = o.arr("dependencies")?.mapNotNull { d ->
            val x = d as? JsonObject ?: return@mapNotNull null
            Dependency(num(x, "modId").toString(), num(x, "relationType") == 3L)
        } ?: emptyList()
        return CurseFile(
            fileId = num(o, "id"), modId = num(o, "modId"),
            file = ContentFile(
                fileName = o.string("fileName") ?: "file.jar",
                url = o.string("downloadUrl"),
                sha1 = sha1, size = num(o, "fileLength"),
                versionName = o.string("displayName") ?: "", dependencies = deps,
            )
        )
    }

    override suspend fun bestFile(projectId: String, type: ContentType, mc: String?, loaders: List<String>): ContentFile =
        withContext(Dispatchers.IO) {
            val url = StringBuilder("$API/mods/${enc(projectId)}/files?pageSize=30")
            if (mc != null) url.append("&gameVersion=").append(enc(mc))
            loaderCode(loaders)?.let { url.append("&modLoaderType=").append(it) }
            val files = get(url.toString()).arr("data")?.mapNotNull { it as? JsonObject } ?: emptyList()
            val chosen = files.firstOrNull { num(it, "releaseType") == 1L } ?: files.firstOrNull()
                ?: throw PortalException(
                    "Нет подходящей версии${if (mc != null) " для Minecraft $mc" else ""}",
                    "Попробуйте другой проект или другую версию игры."
                )
            parseFile(chosen).file
        }

    /** Пакетное получение файлов по id (для сборок CurseForge). */
    suspend fun filesByIds(ids: List<Long>): List<CurseFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<CurseFile>()
        for (chunk in ids.chunked(200)) {
            val body = buildJsonObject { putJsonArray("fileIds") { chunk.forEach { add(JsonPrimitive(it)) } } }.toString()
            val r = Http.postJson("$API/mods/files", body, headers())
            if (!r.ok) throw PortalException("CurseForge вернул ошибку ${r.status}")
            (parseObject(r.text).arr("data") ?: JsonArray(emptyList())).forEach { e -> (e as? JsonObject)?.let { out += parseFile(it) } }
        }
        out
    }
}
