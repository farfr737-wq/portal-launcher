package com.portal.versions

import com.portal.core.Os
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.core.arr
import com.portal.core.long
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.str
import com.portal.core.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

data class Artifact(val path: String, val url: String, val sha1: String?, val size: Long)

data class Library(
    val name: String,
    val artifact: Artifact?,
    val natives: Artifact?,
    val extractExclude: List<String>,
) {
    val key: String get() = Maven.key(name) + if (natives != null) ":natives" else ""
}

data class ArgItem(val values: List<String>, val rules: JsonArray?)

data class ResolvedVersion(
    val id: String,
    val jarId: String,
    val type: String,
    val mainClass: String,
    val assetIndexId: String,
    val assetIndexUrl: String?,
    val assetIndexSha1: String?,
    val assetIndexSize: Long,
    val libraries: List<Library>,
    val jvmArgs: List<ArgItem>,
    val gameArgs: List<ArgItem>,
    val legacyArgs: String?,
    val javaMajor: Int,
    val clientUrl: String?,
    val clientSha1: String?,
    val clientSize: Long,
    val loggingArg: String?,
    val loggingFile: Artifact?,
    val loggingFileId: String?,
)

/** Читает versions/<id>/<id>.json и сливает цепочку inheritsFrom (загрузчик -> ванильная версия). */
object VersionResolver {
    fun versionDir(id: String): Path = PortalPaths.versions.resolve(id)
    fun versionJson(id: String): Path = versionDir(id).resolve("$id.json")
    fun exists(id: String): Boolean = Files.isRegularFile(versionJson(id))

    fun resolve(id: String, depth: Int = 0): ResolvedVersion {
        if (depth > 8) throw PortalException("Слишком глубокая цепочка inheritsFrom у версии $id")
        val path = versionJson(id)
        if (!Files.isRegularFile(path)) {
            throw PortalException("Файл версии не найден: $id", "Переустановите версию на странице «Версии».")
        }
        val o = try {
            parseObject(Files.readString(path))
        } catch (e: Exception) {
            throw PortalException("Файл версии $id повреждён", "Переустановите версию на странице «Версии».", e)
        }
        val parent = o.string("inheritsFrom")?.let { resolve(it, depth + 1) }
        return merge(id, o, parent)
    }

    private fun merge(id: String, o: JsonObject, p: ResolvedVersion?): ResolvedVersion {
        val libs = LinkedHashMap<String, Library>()
        for (l in parseLibraries(o.arr("libraries"))) libs.putIfAbsent(l.key, l)
        p?.libraries?.forEach { libs.putIfAbsent(it.key, it) }

        val args = o.obj("arguments")
        val jvm = (p?.jvmArgs ?: emptyList()) + parseArgs(args?.arr("jvm"))
        val game = (p?.gameArgs ?: emptyList()) + parseArgs(args?.arr("game"))
        val idx = o.obj("assetIndex")
        val client = o.obj("downloads")?.obj("client")
        val logging = o.obj("logging")?.obj("client")
        val logFile = logging?.obj("file")

        return ResolvedVersion(
            id = id,
            jarId = o.string("jar") ?: p?.jarId ?: id,
            type = o.string("type") ?: p?.type ?: "release",
            mainClass = o.string("mainClass") ?: p?.mainClass
            ?: throw PortalException("В JSON версии $id нет mainClass"),
            assetIndexId = idx?.string("id") ?: o.string("assets") ?: p?.assetIndexId ?: "legacy",
            assetIndexUrl = idx?.string("url") ?: p?.assetIndexUrl,
            assetIndexSha1 = idx?.string("sha1") ?: p?.assetIndexSha1,
            assetIndexSize = idx?.long("size") ?: p?.assetIndexSize ?: 0,
            libraries = libs.values.toList(),
            jvmArgs = jvm,
            gameArgs = game,
            legacyArgs = o.string("minecraftArguments") ?: p?.legacyArgs,
            javaMajor = o.obj("javaVersion")?.long("majorVersion")?.toInt() ?: p?.javaMajor ?: 8,
            clientUrl = client?.string("url") ?: p?.clientUrl,
            clientSha1 = client?.string("sha1") ?: p?.clientSha1,
            clientSize = client?.long("size") ?: p?.clientSize ?: 0,
            loggingArg = logging?.string("argument") ?: p?.loggingArg,
            loggingFile = logFile?.let { artifactOf(it, it.string("id") ?: "log4j.xml") } ?: p?.loggingFile,
            loggingFileId = logFile?.string("id") ?: p?.loggingFileId,
        )
    }

    private fun artifactOf(o: JsonObject, fallbackPath: String? = null): Artifact? {
        val path = o.string("path") ?: fallbackPath ?: return null
        return Artifact(path, o.string("url") ?: "", o.string("sha1"), o.long("size") ?: 0)
    }

    private fun parseArgs(a: JsonArray?): List<ArgItem> = a?.map { e ->
        when (e) {
            is JsonPrimitive -> ArgItem(listOf(e.content), null)
            is JsonObject -> {
                val v = e["value"]
                val values = when (v) {
                    is JsonArray -> v.map { it.str() ?: "" }
                    is JsonPrimitive -> listOf(v.content)
                    else -> emptyList()
                }
                ArgItem(values, e.arr("rules"))
            }
            else -> ArgItem(emptyList(), null)
        }
    } ?: emptyList()

    private fun parseLibraries(arr: JsonArray?): List<Library> {
        val res = ArrayList<Library>()
        arr?.forEach { el ->
            val l = el as? JsonObject ?: return@forEach
            if (!Rules.allowed(l.arr("rules"))) return@forEach
            val name = l.string("name") ?: return@forEach
            val dl = l.obj("downloads")
            var artifact = dl?.obj("artifact")?.let { artifactOf(it) }
            val nativeKey = l.obj("natives")?.string(Os.type.mojang)
                ?.replace("\${arch}", if (Os.is64) "64" else "32")
            var natives: Artifact? = null
            if (nativeKey != null) {
                natives = dl?.obj("classifiers")?.obj(nativeKey)?.let { artifactOf(it) }
                    ?: run {
                        val coord = if (name.count { c -> c == ':' } >= 3) name else "$name:$nativeKey"
                        val path = Maven.path(coord)
                        Artifact(path, "https://libraries.minecraft.net/$path", null, 0)
                    }
            } else if (artifact == null && dl == null) {
                // maven-стиль (Fabric/Quilt): {"name": "...", "url": "https://maven..."}
                val base = (l.string("url") ?: "https://libraries.minecraft.net/").let { if (it.endsWith("/")) it else "$it/" }
                val path = Maven.path(name)
                artifact = Artifact(path, base + path, l.string("sha1"), l.long("size") ?: 0)
            }
            val exclude = l.obj("extract")?.arr("exclude")?.mapNotNull { it.str() } ?: emptyList()
            res += Library(name, artifact, natives, exclude)
        }
        return res
    }
}
