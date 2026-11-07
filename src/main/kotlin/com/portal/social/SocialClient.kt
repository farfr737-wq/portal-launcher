package com.portal.social

import com.portal.accounts.SecretStore
import com.portal.core.Database
import com.portal.core.Http
import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.PortalPaths
import com.portal.core.parseObject
import com.portal.core.string
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path

enum class PortalStatus(val id: String, val title: String) {
    USER("user", "Пользователь"),
    VERIFIED("verified", "Проверенный"),
    ADMIN("admin", "Администратор");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: USER
    }
}

/** Профиль пользователя Portal. Статус приходит ТОЛЬКО с сервера. */
@Serializable
data class PortalUser(
    val id: String,
    val nickname: String,
    val model: String = "classic",
    val status: String = "user",
    val createdAt: Long = 0,
    val skinHash: String? = null,
) {
    val portalStatus: PortalStatus get() = PortalStatus.of(status)
}

@Serializable
private data class RegisterResponse(val user: PortalUser, val token: String)

data class Favorite(val userId: String, val nickname: String)

/** Клиент API Portal-сервера (см. папку server/ и docs/API.md). Никаких заглушек: без сервера функции недоступны. */
object SocialClient {
    private const val TOKEN_KEY = "portal-token"

    val serverUrl: String get() = SettingsStore.value.socialServerUrl.trim().trimEnd('/')
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
    val isRegistered: Boolean get() = SettingsStore.value.portalUserId.isNotBlank() && SecretStore.get(TOKEN_KEY) != null

    private fun base(): String {
        if (!isConfigured) {
            throw PortalException(
                "Не указан адрес сервера Portal",
                "Откройте «Сообщество» или Настройки -> Сервисы и введите адрес вашего сервера (например, http://localhost:8080)."
            )
        }
        return serverUrl
    }

    private fun auth(): Map<String, String> {
        val t = SecretStore.get(TOKEN_KEY) ?: throw PortalException("Вы не зарегистрированы в Portal", "Зарегистрируйтесь на странице «Сообщество».")
        return mapOf("Authorization" to "Bearer $t")
    }

    private fun check(r: Http.Response, what: String): Http.Response {
        if (r.ok) return r
        val msg = try {
            parseObject(r.text).string("error")
        } catch (_: Exception) {
            null
        }
        throw PortalException("$what: ${msg ?: "HTTP ${r.status}"}")
    }

    private suspend fun <T> io(what: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PortalException) {
            throw e
        } catch (e: IOException) {
            Log.warn("$what: ${e.message}")
            throw PortalException("Сервер Portal недоступен", "Проверьте адрес сервера (${SettingsStore.value.socialServerUrl}) и подключение.", e)
        }
    }

    suspend fun ping(): Boolean = io("ping") { Http.exchange("GET", base() + "/api/health", timeoutSec = 8).ok }

    suspend fun register(nickname: String, model: String): PortalUser = io("register") {
        val body = buildJsonObject { put("nickname", nickname); put("model", model) }.toString()
        val r = check(Http.postJson(base() + "/api/register", body), "Регистрация")
        val resp = PortalJson.decodeFromString(RegisterResponse.serializer(), r.text)
        SecretStore.put(TOKEN_KEY, resp.token)
        SettingsStore.update { it.copy(portalUserId = resp.user.id) }
        resp.user
    }

    suspend fun me(): PortalUser = io("me") {
        val r = check(Http.exchange("GET", base() + "/api/me", null, auth()), "Профиль")
        PortalJson.decodeFromString(PortalUser.serializer(), r.text)
    }

    suspend fun setModel(model: String): PortalUser = io("model") {
        val body = buildJsonObject { put("model", model) }.toString()
        val r = check(Http.exchange("PUT", base() + "/api/me/model", body.toByteArray(), auth() + ("Content-Type" to "application/json")), "Смена модели")
        PortalJson.decodeFromString(PortalUser.serializer(), r.text)
    }

    suspend fun uploadSkin(png: ByteArray): PortalUser = io("skin") {
        val r = check(Http.exchange("PUT", base() + "/api/me/skin", png, auth() + ("Content-Type" to "image/png")), "Загрузка скина")
        PortalJson.decodeFromString(PortalUser.serializer(), r.text)
    }

    suspend fun search(query: String): List<PortalUser> = io("search") {
        val q = URLEncoder.encode(query, Charsets.UTF_8)
        val r = check(Http.exchange("GET", base() + "/api/users?q=$q&limit=30"), "Поиск")
        PortalJson.decodeFromString(ListSerializer(PortalUser.serializer()), r.text)
    }

    suspend fun user(id: String): PortalUser = io("user") {
        val r = check(Http.exchange("GET", base() + "/api/users/$id"), "Профиль пользователя")
        PortalJson.decodeFromString(PortalUser.serializer(), r.text)
    }

    /**
     * Скин пользователя с кэшированием: файл skins/cache/<id>-<hash>.png скачивается один раз,
     * пока хэш скина на сервере не изменился.
     */
    suspend fun skinFile(user: PortalUser): Path? {
        val hash = user.skinHash ?: return null
        val cached = PortalPaths.skinsCache.resolve("${user.id}-$hash.png")
        if (Files.isRegularFile(cached)) {
            Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            return cached
        }
        return io("skin download") {
            val r = Http.exchange("GET", base() + "/api/users/${user.id}/skin")
            if (r.status == 404) return@io null
            check(r, "Скачивание скина")
            Files.createDirectories(PortalPaths.skinsCache)
            // удаляем старые версии скина этого пользователя
            Files.newDirectoryStream(PortalPaths.skinsCache, "${user.id}-*.png").use { ds -> ds.forEach { Files.deleteIfExists(it) } }
            Files.write(cached, r.body)
            cached
        }
    }

    // ---- избранное (локально, SQLite) ----

    fun favorites(): List<Favorite> = Database.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT user_id, nickname FROM favorites ORDER BY added_at DESC").use { rs ->
                val out = ArrayList<Favorite>()
                while (rs.next()) out += Favorite(rs.getString(1), rs.getString(2))
                out
            }
        }
    }

    fun isFavorite(userId: String): Boolean = Database.use { c ->
        c.prepareStatement("SELECT 1 FROM favorites WHERE user_id=?").use { ps ->
            ps.setString(1, userId); ps.executeQuery().use { it.next() }
        }
    }

    fun setFavorite(user: PortalUser, favorite: Boolean) = Database.use { c ->
        if (favorite) {
            c.prepareStatement("INSERT OR REPLACE INTO favorites(user_id,nickname,added_at) VALUES(?,?,?)").use {
                it.setString(1, user.id); it.setString(2, user.nickname); it.setLong(3, System.currentTimeMillis()); it.executeUpdate()
            }
        } else {
            c.prepareStatement("DELETE FROM favorites WHERE user_id=?").use { it.setString(1, user.id); it.executeUpdate() }
        }
    }

    fun forgetAccount() {
        SecretStore.remove(TOKEN_KEY)
        SettingsStore.update { it.copy(portalUserId = "") }
    }
}
