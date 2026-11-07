package com.portal.server

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

data class User(
    val id: String,
    val nickname: String,
    val model: String,
    val status: String,
    val createdAt: Long,
    val skinHash: String?,
)

class NicknameTaken : Exception("Этот никнейм уже занят")

/** SQLite-хранилище пользователей Portal. Статус (user/verified/admin) меняется только через админ-API сервера. */
class Store(private val dataDir: Path) {
    private val conn: Connection
    private val rnd = SecureRandom()
    val skinsDir: Path = dataDir.resolve("skins")

    init {
        Files.createDirectories(skinsDir)
        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${dataDir.resolve("portal-server.db")}")
        conn.createStatement().use {
            it.execute("PRAGMA busy_timeout = 3000")
            it.execute(
                """CREATE TABLE IF NOT EXISTS users(
                    id TEXT PRIMARY KEY,
                    nickname TEXT NOT NULL,
                    nick_lower TEXT NOT NULL UNIQUE,
                    model TEXT NOT NULL DEFAULT 'classic',
                    status TEXT NOT NULL DEFAULT 'user',
                    created_at INTEGER NOT NULL,
                    skin_hash TEXT,
                    token_hash TEXT NOT NULL)"""
            )
            it.execute("CREATE INDEX IF NOT EXISTS idx_nick ON users(nick_lower)")
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun row(rs: java.sql.ResultSet) = User(
        rs.getString("id"), rs.getString("nickname"), rs.getString("model"),
        rs.getString("status"), rs.getLong("created_at"), rs.getString("skin_hash"),
    )

    /** Возвращает пользователя и одноразово показываемый токен (в базе хранится только его хэш). */
    @Synchronized
    fun register(nickname: String, model: String): Pair<User, String> {
        val bytes = ByteArray(32).also { rnd.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        try {
            conn.prepareStatement("INSERT INTO users(id,nickname,nick_lower,model,status,created_at,token_hash) VALUES(?,?,?,?, 'user', ?, ?)").use {
                it.setString(1, id); it.setString(2, nickname); it.setString(3, nickname.lowercase())
                it.setString(4, model); it.setLong(5, now); it.setString(6, sha256(token)); it.executeUpdate()
            }
        } catch (e: java.sql.SQLException) {
            if (e.message?.contains("UNIQUE") == true) throw NicknameTaken()
            throw e
        }
        return User(id, nickname, model, "user", now, null) to token
    }

    @Synchronized
    fun byToken(token: String): User? =
        conn.prepareStatement("SELECT * FROM users WHERE token_hash=?").use {
            it.setString(1, sha256(token)); it.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
        }

    @Synchronized
    fun byId(id: String): User? =
        conn.prepareStatement("SELECT * FROM users WHERE id=?").use {
            it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) row(rs) else null }
        }

    @Synchronized
    fun search(q: String, limit: Int): List<User> {
        val like = q.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return conn.prepareStatement("SELECT * FROM users WHERE nick_lower LIKE ? ESCAPE '\\' ORDER BY nick_lower LIMIT ?").use {
            it.setString(1, like); it.setInt(2, limit.coerceIn(1, 50))
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
        }
    }

    @Synchronized
    fun setModel(id: String, model: String) {
        conn.prepareStatement("UPDATE users SET model=? WHERE id=?").use { it.setString(1, model); it.setString(2, id); it.executeUpdate() }
    }

    @Synchronized
    fun setSkin(id: String, png: ByteArray) {
        val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }.take(32)
        Files.write(skinsDir.resolve("$id.png"), png)
        conn.prepareStatement("UPDATE users SET skin_hash=? WHERE id=?").use { it.setString(1, hash); it.setString(2, id); it.executeUpdate() }
    }

    fun skinBytes(id: String): ByteArray? = skinsDir.resolve("$id.png").let { if (Files.isRegularFile(it)) Files.readAllBytes(it) else null }

    /** Только для администратора сервера (заголовок X-Admin-Key). */
    @Synchronized
    fun setStatus(id: String, status: String): Boolean =
        conn.prepareStatement("UPDATE users SET status=? WHERE id=?").use { it.setString(1, status); it.setString(2, id); it.executeUpdate() > 0 }
}
