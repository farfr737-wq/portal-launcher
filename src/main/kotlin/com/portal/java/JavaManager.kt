package com.portal.java

import com.portal.core.ErrorAction
import com.portal.core.Http
import com.portal.core.Os
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.core.Report
import com.portal.core.arr
import com.portal.core.deleteTree
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.long
import com.portal.core.string
import com.portal.downloads.DownloadTask
import com.portal.downloads.Downloader
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class JavaInstall(val path: String, val major: Int, val version: String, val source: String)

object JavaManager {
    @Volatile
    private var cache: List<JavaInstall>? = null

    val detected: List<JavaInstall> get() = cache ?: emptyList()

    /** "1.8.0_392" -> 8, "17.0.9" -> 17, "21" -> 21 */
    fun parseMajor(version: String): Int {
        val v = version.trim()
        val parts = v.split('.', '-', '+', '_')
        val first = parts.firstOrNull()?.toIntOrNull() ?: return 0
        return if (first == 1) (parts.getOrNull(1)?.toIntOrNull() ?: 8) else first
    }

    /** Запускает `java -version` и разбирает результат. Возвращает null, если Java нерабочая. */
    fun probe(javaExe: String, source: String = "вручную"): JavaInstall? {
        return try {
            val p = ProcessBuilder(javaExe, "-version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly(); return null
            }
            val m = Regex("version \"([^\"]+)\"").find(out) ?: return null
            val ver = m.groupValues[1]
            JavaInstall(javaExe, parseMajor(ver), ver, source)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun detect(force: Boolean = false): List<JavaInstall> {
        cache?.let { if (!force) return it }
        val candidates = withContext(Dispatchers.IO) { candidates() }
        val result = coroutineScope {
            candidates.map { (path, src) -> async(Dispatchers.IO) { probe(path, src) } }.awaitAll()
        }.filterNotNull().distinctBy { it.path.lowercase() }.sortedByDescending { it.major }
        cache = result
        Log.info("Найдено Java: " + result.joinToString { "${it.major} (${it.path})" })
        return result
    }

    private fun candidates(): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        fun addHome(home: Path, src: String) {
            val exe = Os.javaBinary(home)
            if (Files.isRegularFile(exe)) out.putIfAbsent(exe.toString(), src)
        }
        fun addChildren(dir: Path, src: String) {
            if (!Files.isDirectory(dir)) return
            try {
                Files.newDirectoryStream(dir).use { ds -> ds.forEach { addHome(it, src) } }
            } catch (_: Exception) {
            }
        }

        // Собственные рантаймы Portal
        addChildren(PortalPaths.javaDir, "Portal")
        // JAVA_HOME
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { addHome(Paths.get(it), "JAVA_HOME") }
        // PATH
        System.getenv("PATH")?.split(java.io.File.pathSeparator)?.forEach { dir ->
            if (dir.isNotBlank()) {
                val exe = Paths.get(dir).resolve(if (Os.isWindows) "java.exe" else "java")
                if (Files.isRegularFile(exe)) out.putIfAbsent(exe.toString(), "PATH")
            }
        }
        if (Os.isWindows) {
            val pf = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            val vendors = listOf(
                "Java", "Eclipse Adoptium", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto",
                "Semeru", "Eclipse Foundation", "AdoptOpenJDK", "Temurin"
            )
            for (base in pf) for (v in vendors) addChildren(Paths.get(base, v), "Установленная")
            // Рантаймы официального лаунчера Mojang
            System.getenv("APPDATA")?.let { appdata ->
                val rt = Paths.get(appdata, ".minecraft", "runtime")
                if (Files.isDirectory(rt)) {
                    try {
                        Files.walk(rt, 4).use { s ->
                            s.filter { it.fileName.toString() == "java.exe" }.forEach { out.putIfAbsent(it.toString(), "Minecraft Launcher") }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            addChildren(Paths.get("/usr/lib/jvm"), "Установленная")
        }
        return out.map { it.key to it.value }
    }

    /** Выбор Java: путь версии -> глобальный путь -> лучшая из найденных. */
    suspend fun choose(requiredMajor: Int, instancePath: String): JavaInstall? {
        for (manual in listOf(instancePath, SettingsStore.value.javaPath)) {
            if (manual.isNotBlank()) {
                val j = withContext(Dispatchers.IO) { probe(manual, "вручную") }
                if (j == null) Log.warn("Указанная Java не работает: $manual")
                else {
                    if (j.major < requiredMajor) Log.warn("Выбранная Java ${j.major} старше требуемой $requiredMajor")
                    return j
                }
            }
        }
        val list = detect()
        list.firstOrNull { it.major == requiredMajor }?.let { return it }
        // Для Java 8 (LaunchWrapper/старые Forge) допустима только 8; для 9+ подойдёт более новая
        if (requiredMajor > 8) return list.filter { it.major > requiredMajor }.minByOrNull { it.major }
        return null
    }

    /** Возвращает подходящую Java или бросает ошибку с кнопкой «Скачать». */
    suspend fun require(requiredMajor: Int, instancePath: String = ""): JavaInstall =
        choose(requiredMajor, instancePath) ?: throw PortalException(
            "Не найдена Java $requiredMajor",
            "Эта версия Minecraft требует Java $requiredMajor. Portal может скачать её с официального сайта Adoptium (Eclipse Temurin).",
            action = ErrorAction("Скачать Java $requiredMajor") {
                download(requiredMajor) { _, _ -> }
            }
        )

    /** Загрузка JRE Eclipse Temurin с проверкой SHA-256 через официальное API Adoptium. */
    suspend fun download(major: Int, report: Report): JavaInstall {
        val os = when (Os.type) {
            com.portal.core.OsType.WINDOWS -> "windows"
            com.portal.core.OsType.LINUX -> "linux"
            com.portal.core.OsType.MACOS -> "mac"
        }
        val arch = if (Os.archId == "arm64") "aarch64" else "x64"
        report("Запрос Adoptium для Java $major", 0f)
        val meta = withContext(Dispatchers.IO) {
            Http.getString("https://api.adoptium.net/v3/assets/latest/$major/hotspot?architecture=$arch&image_type=jre&os=$os&vendor=eclipse")
        }
        val arr = PortalJsonArray(meta)
        val pkg = arr.firstOrNull()?.obj("binary")?.obj("package")
            ?: throw PortalException("Adoptium не предоставляет Java $major для этой системы", "Установите Java вручную и укажите путь в настройках.")
        val link = pkg.string("link") ?: throw PortalException("Adoptium: нет ссылки на загрузку")
        val sha256 = pkg.string("checksum")
        val size = pkg.long("size") ?: 0
        val zip = PortalPaths.tmp.resolve("jre-$major.zip")
        Downloader.downloadAll(listOf(DownloadTask(link, zip, sha256, "SHA-256", size))) { done, total ->
            report("Скачивание Java $major: ${done * 100 / total}%", 0.9f * done / total)
        }
        report("Распаковка Java $major", 0.92f)
        val target = PortalPaths.javaDir.resolve("jre-$major")
        withContext(Dispatchers.IO) {
            target.deleteTree()
            unzipStrip(zip, target)
            Files.deleteIfExists(zip)
        }
        val exe = Os.javaBinary(target)
        val j = withContext(Dispatchers.IO) { probe(exe.toString(), "Portal") }
            ?: throw PortalException("Скачанная Java не запускается", "Повторите загрузку или установите Java вручную.")
        cache = null
        report("Java $major готова", 1f)
        Log.info("Java $major установлена: ${j.path}")
        return j
    }

    private fun PortalJsonArray(text: String): List<kotlinx.serialization.json.JsonObject> =
        (com.portal.core.PortalJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray)
            .mapNotNull { it as? kotlinx.serialization.json.JsonObject }

    /** Распаковка zip с отбрасыванием верхней папки и защитой от zip-slip. */
    private fun unzipStrip(zip: Path, target: Path) {
        Files.createDirectories(target)
        val root = target.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(zip)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.substringAfter('/', "")
                if (name.isEmpty()) continue
                val out = root.resolve(name).normalize()
                if (!out.startsWith(root)) throw PortalException("Небезопасный путь в архиве: ${e.name}")
                if (e.isDirectory) Files.createDirectories(out)
                else {
                    Files.createDirectories(out.parent)
                    Files.copy(zin, out)
                }
            }
        }
    }
}
