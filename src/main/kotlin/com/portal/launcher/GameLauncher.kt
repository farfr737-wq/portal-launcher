package com.portal.launcher

import com.portal.accounts.GameSession
import com.portal.core.NoReport
import com.portal.core.Os
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.core.Report
import com.portal.core.deleteTree
import com.portal.core.parseObject
import com.portal.core.splitArgs
import com.portal.core.obj
import com.portal.core.string
import com.portal.java.JavaManager
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import com.portal.versions.GameFiles
import com.portal.versions.InstanceConfig
import com.portal.versions.Instances
import com.portal.versions.ResolvedVersion
import com.portal.versions.Rules
import com.portal.versions.VersionResolver
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

const val LAUNCHER_NAME = "PortalLauncher"
const val LAUNCHER_VERSION = "1.0.0"

class LaunchPlan(
    val command: List<String>,
    val workDir: Path,
    val instance: InstanceConfig,
    val javaPath: String,
    val javaMajor: Int,
) {
    /** Команда для лога — без токена доступа. */
    fun safeCommand(token: String): String = command.joinToString(" ") { if (token.length > 8) it.replace(token, "***") else it }
}

class GameProcess(val process: Process, val tail: ArrayDeque<String>, val logFile: Path)

object GameLauncher {

    /** Проверяет файлы, подбирает Java, распаковывает natives и собирает команду запуска. */
    suspend fun prepare(cfg: InstanceConfig, session: GameSession, report: Report = NoReport): LaunchPlan {
        val resolved = VersionResolver.resolve(cfg.id)
        val settings = SettingsStore.value

        val complete = GameFiles.isComplete(resolved)
        if (!complete || settings.verifyIntegrityOnLaunch) {
            report(if (complete) "Проверка целостности файлов" else "Докачка недостающих файлов", 0.05f)
            GameFiles.ensure(resolved, report, verify = settings.verifyIntegrityOnLaunch)
        }

        report("Подбор Java", 0.9f)
        val java = JavaManager.require(resolved.javaMajor, cfg.javaPath)

        val gameDir = Instances.gameDir(cfg)
        Files.createDirectories(gameDir)
        val nativesDir = Instances.dir(cfg.id).resolve("natives")
        extractNatives(resolved, nativesDir)
        prepareLegacyAssets(resolved, gameDir)

        val cmd = buildCommand(cfg, resolved, session, java.path, nativesDir, gameDir)
        return LaunchPlan(cmd, gameDir, cfg, java.path, java.major)
    }

    private fun buildCommand(
        cfg: InstanceConfig,
        v: ResolvedVersion,
        s: GameSession,
        javaExe: String,
        nativesDir: Path,
        gameDir: Path,
    ): List<String> {
        val settings = SettingsStore.value
        val ram = if (cfg.ramMb > 0) cfg.ramMb else settings.defaultRamMb

        val features = HashSet<String>()
        features += "has_custom_resolution"

        val cp = LinkedHashSet<String>()
        for (lib in v.libraries) lib.artifact?.let { cp += GameFiles.libraryPath(it).toString() }
        cp += GameFiles.clientJar(v).toString()

        val isVirtual = assetFlags(v).first
        val assetsRoot = PortalPaths.assets
        val vars = mapOf(
            "auth_player_name" to s.username,
            "version_name" to cfg.id,
            "game_directory" to gameDir.toString(),
            "assets_root" to assetsRoot.toString(),
            "assets_index_name" to v.assetIndexId,
            "auth_uuid" to s.uuid.replace("-", ""),
            "auth_access_token" to s.accessToken,
            "auth_session" to "token:${s.accessToken}:${s.uuid.replace("-", "")}",
            "clientid" to "0",
            "auth_xuid" to s.xuid,
            "user_type" to s.userType,
            "user_properties" to "{}",
            "version_type" to (if (v.type.isBlank()) "Portal" else v.type),
            "natives_directory" to nativesDir.toString(),
            "launcher_name" to LAUNCHER_NAME,
            "launcher_version" to LAUNCHER_VERSION,
            "classpath" to cp.joinToString(Os.classpathSeparator),
            "classpath_separator" to Os.classpathSeparator,
            "library_directory" to PortalPaths.libraries.toString(),
            "game_assets" to (if (isVirtual) assetsRoot.resolve("virtual").resolve("legacy").toString() else assetsRoot.toString()),
            "resolution_width" to cfg.width.toString(),
            "resolution_height" to cfg.height.toString(),
            "path" to "",
        )

        fun subst(arg: String): String {
            var r = arg
            for ((k, value) in vars) r = r.replace("\${$k}", value)
            return r
        }

        val cmd = ArrayList<String>()
        cmd += Os.javaBinary(Path.of(javaExe).parent.parent, gui = true).let { if (Files.isRegularFile(it)) it.toString() else javaExe }
        cmd += "-Xmx${ram}m"
        cmd += "-Xms${minOf(ram, 512)}m"

        val jvm = v.jvmArgs.filter { Rules.allowed(it.rules, features) }.flatMap { it.values }
        if (jvm.isEmpty()) {
            cmd += "-Djava.library.path=${nativesDir}"
            cmd += "-cp"
            cmd += cp.joinToString(Os.classpathSeparator)
        } else {
            jvm.forEach { cmd += subst(it) }
        }
        // Конфигурация логирования log4j (если есть)
        val logArg = v.loggingArg
        val logId = v.loggingFileId
        if (logArg != null && logId != null) {
            cmd += logArg.replace("\${path}", PortalPaths.assets.resolve("log_configs").resolve(logId).toString())
        }
        cmd += splitArgs(cfg.jvmArgs)
        cmd += v.mainClass

        val game: List<String> = if (v.legacyArgs != null && v.gameArgs.isEmpty()) {
            splitArgs(v.legacyArgs).map { subst(it) } + listOf("--width", cfg.width.toString(), "--height", cfg.height.toString())
        } else {
            v.gameArgs.filter { Rules.allowed(it.rules, features) }.flatMap { it.values }.map { subst(it) }
        }
        cmd += game
        if (cfg.fullscreen) cmd += "--fullscreen"
        cmd += splitArgs(cfg.gameArgs)
        return cmd
    }

    /** (virtual, map_to_resources) из индекса ресурсов. */
    private fun assetFlags(v: ResolvedVersion): Pair<Boolean, Boolean> {
        val f = GameFiles.indexFile(v)
        if (!Files.isRegularFile(f)) return false to false
        return try {
            val o = parseObject(Files.readString(f))
            (o["virtual"].toString() == "true") to (o["map_to_resources"].toString() == "true")
        } catch (_: Exception) {
            false to false
        }
    }

    /** Старые версии (до 1.7.3 и т.п.) ждут ресурсы в виртуальной структуре с реальными именами файлов. */
    private fun prepareLegacyAssets(v: ResolvedVersion, gameDir: Path) {
        val (virtual, mapToResources) = assetFlags(v)
        if (!virtual && !mapToResources) return
        val objects = parseObject(Files.readString(GameFiles.indexFile(v))).obj("objects") ?: return
        val target = if (virtual) PortalPaths.assets.resolve("virtual").resolve("legacy") else gameDir.resolve("resources")
        for ((name, e) in objects) {
            val hash = (e as? kotlinx.serialization.json.JsonObject)?.string("hash") ?: continue
            val src = PortalPaths.assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash)
            val dst = target.resolve(name).normalize()
            if (!dst.startsWith(target.normalize()) || Files.exists(dst) || !Files.isRegularFile(src)) continue
            Files.createDirectories(dst.parent)
            Files.copy(src, dst)
        }
    }

    /** Распаковка нативных библиотек (только старый формат до 1.19; в новом они лежат прямо в classpath). */
    private fun extractNatives(v: ResolvedVersion, dir: Path) {
        val natives = v.libraries.filter { it.natives != null }
        val marker = dir.resolve(".extracted")
        val signature = natives.joinToString("|") { it.natives!!.path }
        if (Files.isRegularFile(marker) && Files.readString(marker) == signature) return
        dir.deleteTree()
        Files.createDirectories(dir)
        val root = dir.toAbsolutePath().normalize()
        for (lib in natives) {
            val jar = GameFiles.libraryPath(lib.natives!!)
            if (!Files.isRegularFile(jar)) continue
            ZipInputStream(Files.newInputStream(jar)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory || e.name.startsWith("META-INF/") || lib.extractExclude.any { e.name.startsWith(it) }) continue
                    val out = root.resolve(e.name).normalize()
                    if (!out.startsWith(root)) throw PortalException("Небезопасный путь в архиве natives: ${e.name}")
                    Files.createDirectories(out.parent)
                    Files.copy(zin, out)
                }
            }
        }
        Files.writeString(marker, signature)
    }

    /** Запускает игру; вывод пишется в logs/game-<id>.log, последние строки хранятся для диагностики. */
    fun start(plan: LaunchPlan, session: GameSession): GameProcess {
        Log.info("Запуск ${plan.instance.id}: ${plan.safeCommand(session.accessToken)}")
        val pb = ProcessBuilder(plan.command).directory(plan.workDir.toFile()).redirectErrorStream(true)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            throw PortalException("Не удалось запустить Java: ${e.message}", "Проверьте путь к Java в настройках версии.", e)
        }
        val logFile = PortalPaths.logs.resolve("game-${plan.instance.id}.log")
        val tail = ArrayDeque<String>()
        Thread({
            try {
                Files.newBufferedWriter(logFile).use { w ->
                    p.inputStream.bufferedReader().forEachLine { line ->
                        w.write(line); w.newLine()
                        synchronized(tail) {
                            tail.addLast(line)
                            if (tail.size > 120) tail.removeFirst()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "game-output").apply { isDaemon = true }.start()
        return GameProcess(p, tail, logFile)
    }
}
