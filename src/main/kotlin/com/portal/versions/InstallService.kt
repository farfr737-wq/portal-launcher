package com.portal.versions

import com.portal.core.PortalException
import com.portal.core.Report
import com.portal.core.sub
import com.portal.loaders.LoaderType
import com.portal.loaders.LoaderVersion
import com.portal.loaders.Loaders
import com.portal.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Оркестратор установки: ваниль -> загрузчик -> конфиг версии. Одновременно выполняется одна установка. */
object InstallService {
    private val mutex = Mutex()

    suspend fun install(
        mc: String,
        loader: LoaderType,
        loaderVersion: LoaderVersion?,
        report: Report,
    ): InstanceConfig = mutex.withLock {
        Log.info("Установка: Minecraft $mc, загрузчик ${loader.title} ${loaderVersion?.version ?: ""}")
        val hasLoader = loader != LoaderType.VANILLA
        if (hasLoader && loaderVersion == null) {
            throw PortalException("Не выбрана версия загрузчика ${loader.title}")
        }

        val vanilla = VanillaInstaller.install(mc, if (hasLoader) report.sub(0f, 0.45f) else report)
        var finalId = mc
        var resolved = vanilla

        if (hasLoader) {
            val provider = Loaders.get(loader)
            finalId = provider.install(mc, loaderVersion!!, report.sub(0.45f, 1f))
            resolved = VersionResolver.resolve(finalId)
        }

        val old = Instances.load(finalId)
        val cfg = (old ?: InstanceConfig(
            id = finalId,
            displayName = if (hasLoader) "$mc + ${loader.title} ${loaderVersion!!.version}" else "Minecraft $mc",
            mcVersion = mc,
            loader = loader.id,
            loaderVersion = loaderVersion?.version ?: "",
        )).copy(javaMajor = resolved.javaMajor)
        withContext(Dispatchers.IO) {
            Instances.save(cfg)
            java.nio.file.Files.createDirectories(Instances.gameDir(cfg))
        }
        report("Готово", 1f)
        Log.info("Установлено: $finalId (Java ${cfg.javaMajor})")
        cfg
    }

    /** Проверка и докачка файлов версии (вручную, с хэшами). */
    suspend fun verify(cfg: InstanceConfig, report: Report) = mutex.withLock {
        GameFiles.ensure(VersionResolver.resolve(cfg.id), report, verify = true)
    }
}
