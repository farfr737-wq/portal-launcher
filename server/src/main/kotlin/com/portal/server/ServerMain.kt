package com.portal.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.imageio.ImageIO

private val NICK = Regex("^[A-Za-z0-9_]{3,16}$")
private const val MAX_SKIN = 512 * 1024
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

class ApiError(val status: Int, message: String) : Exception(message)

/** Простейший ограничитель частоты: не более [limit] запросов за окно на ключ (IP). */
class RateLimit(private val limit: Int, private val windowMs: Long) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    fun allow(key: String): Boolean {
        val now = System.currentTimeMillis()
        val q = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
            if (q.size >= limit) return false
            q.addLast(now); return true
        }
    }
}

fun userJson(u: User): JsonObject = buildJsonObject {
    put("id", u.id); put("nickname", u.nickname); put("model", u.model)
    put("status", u.status); put("createdAt", u.createdAt)
    if (u.skinHash != null) put("skinHash", u.skinHash)
}

fun validateSkin(png: ByteArray) {
    if (png.size > MAX_SKIN) throw ApiError(413, "Файл слишком большой")
    if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) throw ApiError(400, "Нужен PNG")
    val img = try { ImageIO.read(ByteArrayInputStream(png)) } catch (_: Exception) { null } ?: throw ApiError(400, "Изображение повреждено")
    if (img.width != 64 || (img.height != 64 && img.height != 32)) throw ApiError(400, "Скин должен быть 64x64 (или 64x32)")
}

fun main() {
    val port = (System.getenv("PORTAL_PORT") ?: "8080").toInt()
    val dataDir = Path.of(System.getenv("PORTAL_DATA") ?: "server-data")
    val adminKey = System.getenv("PORTAL_ADMIN_KEY").orEmpty()
    val bind = System.getenv("PORTAL_BIND") ?: "0.0.0.0"
    val store = Store(dataDir)
    val registerLimit = RateLimit(10, 60 * 60_000L)
    val writeLimit = RateLimit(60, 60_000L)

    val server = HttpServer.create(InetSocketAddress(bind, port), 0)
    server.executor = Executors.newFixedThreadPool(8)

    fun send(ex: HttpExchange, status: Int, body: ByteArray, type: String) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
        ex.close()
    }
    fun json(ex: HttpExchange, status: Int, o: kotlinx.serialization.json.JsonElement) = send(ex, status, o.toString().toByteArray(), "application/json; charset=utf-8")

    fun bearer(ex: HttpExchange): User {
        val h = ex.requestHeaders.getFirst("Authorization") ?: throw ApiError(401, "Требуется авторизация")
        if (!h.startsWith("Bearer ")) throw ApiError(401, "Неверный заголовок Authorization")
        return store.byToken(h.removePrefix("Bearer ").trim()) ?: throw ApiError(401, "Неверный токен")
    }
    fun body(ex: HttpExchange, max: Int): ByteArray {
        val b = ex.requestBody.readNBytes(max + 1)
        if (b.size > max) throw ApiError(413, "Слишком большой запрос")
        return b
    }
    fun jsonBody(ex: HttpExchange): JsonObject = try {
        Json.parseToJsonElement(String(body(ex, 4096))).jsonObject
    } catch (e: ApiError) { throw e } catch (_: Exception) { throw ApiError(400, "Некорректный JSON") }
    fun modelOf(o: JsonObject): String {
        val m = o["model"]?.jsonPrimitive?.contentOrNull ?: "classic"
        if (m != "classic" && m != "slim") throw ApiError(400, "model: classic или slim")
        return m
    }

    server.createContext("/api/") { ex ->
        try {
            val path = ex.requestURI.path.removePrefix("/api")
            val method = ex.requestMethod
            val ip = ex.remoteAddress.address.hostAddress
            val query = (ex.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

            if (method != "GET" && !writeLimit.allow(ip)) throw ApiError(429, "Слишком много запросов")

            when {
                path == "/health" && method == "GET" -> json(ex, 200, buildJsonObject { put("ok", true) })

                path == "/register" && method == "POST" -> {
                    if (!registerLimit.allow(ip)) throw ApiError(429, "Слишком много регистраций с этого адреса")
                    val o = jsonBody(ex)
                    val nick = o["nickname"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (!NICK.matches(nick)) throw ApiError(400, "Ник: 3–16 символов, латиница, цифры и _")
                    val (user, token) = try { store.register(nick, modelOf(o)) } catch (e: NicknameTaken) { throw ApiError(409, e.message!!) }
                    json(ex, 201, buildJsonObject { put("user", userJson(user)); put("token", token) })
                }

                path == "/me" && method == "GET" -> json(ex, 200, userJson(bearer(ex)))

                path == "/me/model" && method == "PUT" -> {
                    val u = bearer(ex)
                    store.setModel(u.id, modelOf(jsonBody(ex)))
                    json(ex, 200, userJson(store.byId(u.id)!!))
                }

                path == "/me/skin" && method == "PUT" -> {
                    val u = bearer(ex)
                    val png = body(ex, MAX_SKIN)
                    validateSkin(png)
                    store.setSkin(u.id, png)
                    json(ex, 200, userJson(store.byId(u.id)!!))
                }

                path == "/users" && method == "GET" ->
                    json(ex, 200, JsonArray(store.search(query["q"].orEmpty().take(16), query["limit"]?.toIntOrNull() ?: 30).map { userJson(it) }))

                path.startsWith("/users/") && method == "GET" -> {
                    val parts = path.removePrefix("/users/").split('/')
                    val user = store.byId(parts[0]) ?: throw ApiError(404, "Пользователь не найден")
                    when {
                        parts.size == 1 -> json(ex, 200, userJson(user))
                        parts.size == 2 && parts[1] == "skin" -> {
                            val png = store.skinBytes(user.id) ?: throw ApiError(404, "Скин не загружен")
                            send(ex, 200, png, "image/png")
                        }
                        else -> throw ApiError(404, "Не найдено")
                    }
                }

                // Назначение статуса — только администратор сервера по секретному ключу
                path.startsWith("/admin/users/") && path.endsWith("/status") && method == "PUT" -> {
                    if (adminKey.isBlank() || ex.requestHeaders.getFirst("X-Admin-Key") != adminKey) throw ApiError(403, "Нет доступа")
                    val id = path.removePrefix("/admin/users/").removeSuffix("/status")
                    val status = jsonBody(ex)["status"]?.jsonPrimitive?.contentOrNull
                    if (status !in setOf("user", "verified", "admin")) throw ApiError(400, "status: user, verified или admin")
                    if (!store.setStatus(id, status!!)) throw ApiError(404, "Пользователь не найден")
                    json(ex, 200, userJson(store.byId(id)!!))
                }

                else -> throw ApiError(404, "Не найдено")
            }
        } catch (e: ApiError) {
            json(ex, e.status, buildJsonObject { put("error", e.message ?: "error") })
        } catch (e: Throwable) {
            System.err.println("Ошибка сервера: $e")
            json(ex, 500, buildJsonObject { put("error", "Внутренняя ошибка сервера") })
        }
    }
    server.start()
    println("Portal server: http://$bind:$port  (данные: ${dataDir.toAbsolutePath()})")
}
