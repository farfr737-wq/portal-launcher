package com.portal.accounts

import com.portal.core.Os
import com.portal.core.PortalException
import com.portal.core.PortalJson
import com.portal.core.PortalPaths
import com.portal.core.writeTextAtomic
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

@Serializable
data class Account(
    val id: String,
    val type: String,          // "local" | "microsoft"
    val username: String,
    val uuid: String,
) {
    val isMicrosoft: Boolean get() = type == "microsoft"
}

/** Данные, которые нужны игре для входа. */
data class GameSession(
    val username: String,
    val uuid: String,
    val accessToken: String,
    val userType: String,      // "msa" | "legacy"
    val xuid: String = "0",
)

/**
 * Секреты (refresh-токены) шифруются через Windows DPAPI (привязка к учётной записи Windows).
 * Вне Windows секреты хранятся только в памяти до закрытия лаунчера — в открытом виде на диск не пишутся.
 */
object SecretStore {
    private val memory = HashMap<String, ByteArray>()

    private fun file(name: String) = PortalPaths.accounts.resolve("$name.secret")

    fun put(name: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (Os.isWindows) {
            try {
                val enc = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(bytes)
                file(name).writeTextAtomic(Base64.getEncoder().encodeToString(enc))
                return
            } catch (e: Throwable) {
                Log.error("DPAPI недоступен, секрет не сохранён на диск", e)
            }
        }
        synchronized(memory) { memory[name] = bytes }
    }

    fun get(name: String): String? {
        synchronized(memory) { memory[name]?.let { return String(it, Charsets.UTF_8) } }
        val f = file(name)
        if (!Os.isWindows || !Files.isRegularFile(f)) return null
        return try {
            val enc = Base64.getDecoder().decode(Files.readString(f).trim())
            String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(enc), Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.error("Не удалось расшифровать секрет $name", e)
            null
        }
    }

    fun remove(name: String) {
        synchronized(memory) { memory.remove(name) }
        Files.deleteIfExists(file(name))
    }
}

object AccountStore {
    private val file = PortalPaths.accounts.resolve("accounts.json")
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> get() = _accounts

    fun load() {
        try {
            if (Files.isRegularFile(file)) {
                _accounts.value = PortalJson.decodeFromString(ListSerializer(Account.serializer()), Files.readString(file))
            }
        } catch (e: Exception) {
            Log.error("Не удалось прочитать список аккаунтов", e)
        }
    }

    private fun save() {
        file.writeTextAtomic(PortalJson.encodeToString(ListSerializer(Account.serializer()), _accounts.value))
    }

    val active: Account?
        get() {
            val id = SettingsStore.value.activeAccountId
            return _accounts.value.firstOrNull { it.id == id } ?: _accounts.value.firstOrNull()
        }

    fun setActive(id: String) = SettingsStore.update { it.copy(activeAccountId = id) }

    fun validNickname(name: String) = Regex("^[A-Za-z0-9_]{3,16}$").matches(name)

    /** Локальный профиль для тестов. Это НЕ лицензионная авторизация: на online-mode серверах он не работает. */
    fun addLocal(name: String): Account {
        if (!validNickname(name)) {
            throw PortalException("Недопустимый ник", "Используйте 3–16 символов: латинские буквы, цифры и «_».")
        }
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString()
        val acc = Account(UUID.randomUUID().toString(), "local", name, uuid)
        _accounts.value = _accounts.value + acc
        save()
        if (SettingsStore.value.activeAccountId.isBlank()) setActive(acc.id)
        return acc
    }

    fun addMicrosoft(username: String, uuid: String, refreshToken: String): Account {
        val existing = _accounts.value.firstOrNull { it.isMicrosoft && it.uuid == uuid }
        val acc = existing?.copy(username = username) ?: Account(UUID.randomUUID().toString(), "microsoft", username, uuid)
        _accounts.value = _accounts.value.filter { it.id != acc.id } + acc
        SecretStore.put("ms-${acc.id}", refreshToken)
        save()
        setActive(acc.id)
        return acc
    }

    fun remove(id: String) {
        SecretStore.remove("ms-$id")
        _accounts.value = _accounts.value.filter { it.id != id }
        save()
        if (SettingsStore.value.activeAccountId == id) setActive(_accounts.value.firstOrNull()?.id ?: "")
    }

    /** Сессия для запуска. Для Microsoft обновляет токены через официальные сервисы. */
    suspend fun session(acc: Account): GameSession {
        if (!acc.isMicrosoft) return GameSession(acc.username, acc.uuid, "0", "legacy")
        val refresh = SecretStore.get("ms-${acc.id}")
            ?: throw PortalException(
                "Токен входа Microsoft не найден",
                "Войдите в аккаунт Microsoft заново (профиль внизу окна -> Добавить аккаунт)."
            )
        val r = MicrosoftAuth.refresh(refresh)
        SecretStore.put("ms-${acc.id}", r.refreshToken)
        return GameSession(r.username, r.uuid, r.accessToken, "msa", r.xuid)
    }
}
