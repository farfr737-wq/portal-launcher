package com.portal.skins

import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.Http
import com.portal.core.Report
import com.portal.core.arr
import com.portal.core.bool
import com.portal.core.long
import com.portal.core.obj
import com.portal.core.string
import com.portal.downloads.DownloadTask
import com.portal.downloads.Downloader
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import com.portal.versions.InstanceConfig
import com.portal.versions.Instances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Показ локального скина в игре. Vanilla берёт скин по UUID с серверов Mojang, а у локального профиля его там нет.
 * Решение — мод CustomSkinLoader (скачивается с Modrinth): он читает скин из папки CustomSkinLoader/LocalSkin/skins/<ник>.png.
 */
object SkinInGame {
    /** Копирует выбранный локальный скин туда, где его ищет CustomSkinLoader. Вызывается перед каждым запуском. */
    fun sync(cfg: InstanceConfig, nickname: String) {
        if (cfg.loader == "vanilla" || cfg.loader == "optifine") return
        val skin = SkinManager.list().firstOrNull { it.id == SettingsStore.value.selectedSkinId } ?: return
        val dir = Instances.gameDir(cfg).resolve("CustomSkinLoader").resolve("LocalSkin").resolve("skins")
        Files.createDirectories(dir)
        Files.copy(skin.file, dir.resolve("$nickname.png"), StandardCopyOption.REPLACE_EXISTING)
    }

    suspend fun installMod(cfg: InstanceConfig, report: Report) {
        if (cfg.loader == "vanilla" || cfg.loader == "optifine") {
            throw PortalException("Для этой версии нельзя поставить мод", "Установите версию с Forge, NeoForge, Fabric или Quilt.")
        }
        // Quilt загружает моды Fabric
        val loader = if (cfg.loader == "quilt") "fabric" else cfg.loader
        val url = "https://api.modrinth.com/v2/project/customskinloader/version" +
            "?loaders=" + URLEncoder.encode("[\"$loader\"]", Charsets.UTF_8) +
            "&game_versions=" + URLEncoder.encode("[\"${cfg.mcVersion}\"]", Charsets.UTF_8)
        report("Поиск CustomSkinLoader на Modrinth", 0.1f)
        val versions = withContext(Dispatchers.IO) { PortalJson.parseToJsonElement(Http.getString(url)) as JsonArray }
        val first = versions.firstOrNull() as? JsonObject ?: throw PortalException(
            "Для Minecraft ${cfg.mcVersion} и ${cfg.loaderTitle} нет сборки CustomSkinLoader на Modrinth",
            "Попробуйте другую версию Minecraft или загрузчик."
        )
        val files = first.arr("files")?.mapNotNull { it as? JsonObject } ?: emptyList()
        val file = files.firstOrNull { it.bool("primary") == true } ?: files.firstOrNull()
            ?: throw PortalException("На Modrinth нет файла для скачивания")
        val name = file.string("filename") ?: "CustomSkinLoader.jar"
        val mods = Instances.gameDir(cfg).resolve("mods")
        // старые копии мода заменяем
        withContext(Dispatchers.IO) {
            Files.createDirectories(mods)
            Files.newDirectoryStream(mods, "CustomSkinLoader*.jar").use { ds -> ds.forEach { Files.deleteIfExists(it) } }
        }
        Downloader.downloadAll(
            listOf(DownloadTask(file.string("url") ?: throw PortalException("Нет ссылки на файл"), mods.resolve(name), file.obj("hashes")?.string("sha1"), "SHA-1", file.long("size") ?: 0))
        ) { d, t -> report("Скачивание $name", 0.2f + 0.7f * d / t.coerceAtLeast(1)) }
        withContext(Dispatchers.IO) { sync(cfg, com.portal.accounts.AccountStore.active?.username ?: "") }
        Instances.refreshMods(cfg)
        Log.info("CustomSkinLoader установлен в ${cfg.id}: $name")
    }
}
