package com.portal.loaders

import com.portal.core.PortalException
import com.portal.core.Report

enum class LoaderType(val id: String, val title: String) {
    VANILLA("vanilla", "Vanilla"),
    FORGE("forge", "Forge"),
    NEOFORGE("neoforge", "NeoForge"),
    FABRIC("fabric", "Fabric"),
    QUILT("quilt", "Quilt"),
    OPTIFINE("optifine", "OptiFine");
}

data class LoaderVersion(
    val version: String,
    val recommended: Boolean = false,
    /** Служебные данные провайдера (источник, имя файла и т.п.). */
    val extra: String = "",
)

interface LoaderProvider {
    val type: LoaderType

    /** Версии загрузчика для данной версии Minecraft (пусто, если не поддерживается). */
    suspend fun versions(mc: String): List<LoaderVersion>

    /** Понятное объяснение, почему загрузчик недоступен. */
    fun unavailableReason(mc: String): String

    /** Устанавливает загрузчик (ванильная версия [mc] уже установлена). Возвращает id установленной версии. */
    suspend fun install(mc: String, version: LoaderVersion, report: Report): String
}

object Loaders {
    private val providers: Map<LoaderType, LoaderProvider> = listOf<LoaderProvider>(
        FabricLikeProvider(LoaderType.FABRIC, "https://meta.fabricmc.net/v2", "Fabric"),
        FabricLikeProvider(LoaderType.QUILT, "https://meta.quiltmc.org/v3", "Quilt"),
        ForgeProvider(LoaderType.FORGE),
        ForgeProvider(LoaderType.NEOFORGE),
        OptiFineProvider(),
    ).associateBy { it.type }

    fun get(type: LoaderType): LoaderProvider =
        providers[type] ?: throw PortalException("Загрузчик ${type.title} не поддерживается")
}
