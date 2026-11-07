package com.portal.accounts

import com.portal.core.Http
import com.portal.core.PortalException
import com.portal.core.arr
import com.portal.core.int
import com.portal.core.long
import com.portal.core.obj
import com.portal.core.parseObject
import com.portal.core.string
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class DeviceCode(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

data class MsResult(
    val refreshToken: String,
    val accessToken: String,
    val username: String,
    val uuid: String,
    val xuid: String,
)

/**
 * Официальный вход Microsoft -> Xbox Live -> XSTS -> Minecraft Services через OAuth 2.0 Device Code Flow.
 * Нужен собственный Azure client_id (публичное приложение). См. README, раздел «Вход через Microsoft».
 */
object MicrosoftAuth {
    private const val TENANT = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val SCOPE = "XboxLive.signin offline_access"

    private fun clientId(): String =
        SettingsStore.value.msClientId.trim().ifEmpty {
            throw PortalException(
                "Не задан Azure Client ID",
                "Откройте Настройки -> Сервисы и вставьте Application (client) ID вашего приложения Azure. Инструкция — в README."
            )
        }

    suspend fun start(): DeviceCode = withContext(Dispatchers.IO) {
        val r = Http.postForm("$TENANT/devicecode", mapOf("client_id" to clientId(), "scope" to SCOPE))
        val o = parseObject(r.text)
        if (!r.ok) throw PortalException("Microsoft отклонил запрос входа: ${o.string("error_description") ?: r.status}", "Проверьте Client ID.")
        DeviceCode(
            userCode = o.string("user_code") ?: "",
            verificationUri = o.string("verification_uri") ?: "https://microsoft.com/link",
            deviceCode = o.string("device_code") ?: "",
            expiresInSec = o.int("expires_in") ?: 900,
            intervalSec = o.int("interval") ?: 5,
        )
    }

    /** Ждёт, пока пользователь подтвердит вход в браузере, затем проходит всю цепочку авторизации. */
    suspend fun awaitLogin(dc: DeviceCode): MsResult {
        val deadline = System.currentTimeMillis() + dc.expiresInSec * 1000L
        var interval = dc.intervalSec.coerceAtLeast(2)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val r = withContext(Dispatchers.IO) {
                Http.postForm(
                    "$TENANT/token",
                    mapOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id" to clientId(),
                        "device_code" to dc.deviceCode,
                    )
                )
            }
            val o = parseObject(r.text)
            if (r.ok) return chain(o.string("access_token")!!, o.string("refresh_token") ?: "")
            when (o.string("error")) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                "authorization_declined" -> throw PortalException("Вход отменён пользователем")
                "expired_token" -> break
                else -> throw PortalException("Ошибка входа Microsoft: ${o.string("error_description") ?: o.string("error")}")
            }
        }
        throw PortalException("Время ожидания входа истекло", "Нажмите «Войти через Microsoft» ещё раз.")
    }

    suspend fun refresh(refreshToken: String): MsResult {
        val r = withContext(Dispatchers.IO) {
            Http.postForm(
                "$TENANT/token",
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to clientId(),
                    "refresh_token" to refreshToken,
                    "scope" to SCOPE,
                )
            )
        }
        val o = parseObject(r.text)
        if (!r.ok) throw PortalException(
            "Не удалось обновить вход Microsoft: ${o.string("error_description") ?: r.status}",
            "Войдите в аккаунт заново."
        )
        return chain(o.string("access_token")!!, o.string("refresh_token") ?: refreshToken)
    }

    private suspend fun chain(msAccessToken: String, refreshToken: String): MsResult = withContext(Dispatchers.IO) {
        // 1. Xbox Live
        val xbl = Http.postJson(
            "https://user.auth.xboxlive.com/user/authenticate",
            buildJsonObject {
                putJsonObject("Properties") {
                    put("AuthMethod", "RPS"); put("SiteName", "user.auth.xboxlive.com"); put("RpsTicket", "d=$msAccessToken")
                }
                put("RelyingParty", "http://auth.xboxlive.com"); put("TokenType", "JWT")
            }.toString()
        )
        if (!xbl.ok) throw PortalException("Xbox Live отклонил вход (${xbl.status})")
        val xblObj = parseObject(xbl.text)
        val xblToken = xblObj.string("Token") ?: throw PortalException("Xbox Live не вернул токен")

        // 2. XSTS
        val xsts = Http.postJson(
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            buildJsonObject {
                putJsonObject("Properties") {
                    put("SandboxId", "RETAIL"); putJsonArray("UserTokens") { add(JsonPrimitive(xblToken)) }
                }
                put("RelyingParty", "rp://api.minecraftservices.com/"); put("TokenType", "JWT")
            }.toString()
        )
        val xstsObj = parseObject(xsts.text)
        if (!xsts.ok) {
            val msg = when (xstsObj.long("XErr")) {
                2148916233L -> "У этого аккаунта Microsoft нет профиля Xbox."
                2148916235L -> "Xbox Live недоступен в вашей стране."
                2148916238L -> "Детский аккаунт: добавьте его в семью Microsoft и разрешите онлайн-игру."
                else -> "Ошибка XSTS (${xsts.status})."
            }
            throw PortalException(msg, "Решите вопрос на account.microsoft.com / xbox.com и повторите.")
        }
        val xstsToken = xstsObj.string("Token") ?: throw PortalException("XSTS не вернул токен")
        val uhs = ((xstsObj.obj("DisplayClaims")?.arr("xui") as? JsonArray)?.firstOrNull() as? JsonObject)?.string("uhs")
            ?: throw PortalException("XSTS не вернул uhs")

        // 3. Minecraft Services
        val mc = Http.postJson(
            "https://api.minecraftservices.com/authentication/login_with_xbox",
            buildJsonObject { put("identityToken", "XBL3.0 x=$uhs;$xstsToken") }.toString()
        )
        if (!mc.ok) throw PortalException("Minecraft Services отклонил вход (${mc.status})")
        val mcToken = parseObject(mc.text).string("access_token") ?: throw PortalException("Нет токена Minecraft")

        // 4. Профиль (проверяет наличие лицензии)
        val prof = Http.exchange("GET", "https://api.minecraftservices.com/minecraft/profile", null, mapOf("Authorization" to "Bearer $mcToken"))
        if (prof.status == 404) {
            throw PortalException(
                "На этом аккаунте Microsoft нет Minecraft: Java Edition",
                "Купите игру на minecraft.net или используйте локальный профиль для тестирования."
            )
        }
        if (!prof.ok) throw PortalException("Не удалось получить профиль Minecraft (${prof.status})")
        val po = parseObject(prof.text)
        Log.info("Вход Microsoft выполнен: ${po.string("name")}")
        MsResult(
            refreshToken = refreshToken,
            accessToken = mcToken,
            username = po.string("name") ?: "Player",
            uuid = formatUuid(po.string("id") ?: ""),
            xuid = uhs,
        )
    }

    private fun formatUuid(raw: String): String =
        if (raw.length == 32) "${raw.substring(0, 8)}-${raw.substring(8, 12)}-${raw.substring(12, 16)}-${raw.substring(16, 20)}-${raw.substring(20)}" else raw
}
