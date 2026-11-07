package com.portal.cache

import com.portal.core.Http
import com.portal.core.PortalPaths
import com.portal.core.clearDirectory
import com.portal.core.sha1Hex
import com.portal.core.sizeOfTree
import com.portal.core.writeTextAtomic
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Дисковый кэш метаданных (манифесты, списки версий загрузчиков) с TTL. При отсутствии сети отдаёт устаревшую копию. */
object MetadataCache {
    fun getText(url: String, ttlMs: Long = 60 * 60_000L): String {
        val file = PortalPaths.metaCache.resolve(sha1Hex(url) + ".json")
        if (Files.isRegularFile(file)) {
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()
            if (age < ttlMs) return Files.readString(file)
        }
        return try {
            val text = Http.getString(url)
            file.writeTextAtomic(text)
            text
        } catch (e: IOException) {
            if (Files.isRegularFile(file)) {
                Log.warn("Нет доступа к $url, использую кэш: ${e.message}")
                Files.readString(file)
            } else throw e
        }
    }
}

object CacheManager {
    /** Временные файлы лаунчера. Миры, моды, версии и настройки не затрагиваются. */
    fun cleanTemp(): Long {
        val freed = PortalPaths.tmp.clearDirectory()
        if (freed > 0) Log.info("Очищены временные файлы: $freed байт")
        return freed
    }

    /** Кэш метаданных и кэш чужих скинов. Локальные скины (skins/local) НЕ трогаем. */
    fun clearCaches(): Long {
        val freed = PortalPaths.metaCache.clearDirectory() + PortalPaths.skinsCache.clearDirectory()
        Log.info("Кэш очищен: $freed байт")
        return freed
    }

    fun cacheSize(): Long = sizeOfTree(PortalPaths.cache) + sizeOfTree(PortalPaths.skinsCache)

    fun enforceLimit() {
        val limit = SettingsStore.value.cacheLimitMb.coerceAtLeast(10) * 1024L * 1024L
        prune(PortalPaths.skinsCache, limit / 2)
        prune(PortalPaths.metaCache, limit / 2)
    }

    /** Удаляет самые старые файлы, пока размер папки больше лимита. */
    fun prune(dir: Path, limitBytes: Long) {
        if (!Files.isDirectory(dir)) return
        val files = Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            .sortedBy { Files.getLastModifiedTime(it).toMillis() }
        var total = files.sumOf { Files.size(it) }
        for (f in files) {
            if (total <= limitBytes) break
            total -= Files.size(f)
            Files.deleteIfExists(f)
        }
    }
}
