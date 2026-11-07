package com.portal.core

import com.portal.logging.Log
import java.sql.Connection
import java.sql.DriverManager

/** Локальная SQLite-база лаунчера: история локальных скинов и избранные пользователи Portal. Подключение открывается лениво. */
object Database {
    private var conn: Connection? = null

    @Synchronized
    fun <T> use(block: (Connection) -> T): T {
        val c = conn ?: open().also { conn = it }
        return block(c)
    }

    private fun open(): Connection {
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:${PortalPaths.dbFile}")
        c.createStatement().use { st ->
            st.execute("PRAGMA busy_timeout = 3000")
            st.execute(
                """CREATE TABLE IF NOT EXISTS local_skins(
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, file TEXT NOT NULL,
                    model TEXT NOT NULL, added_at INTEGER NOT NULL, last_used INTEGER NOT NULL DEFAULT 0)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS favorites(
                    user_id TEXT PRIMARY KEY, nickname TEXT NOT NULL, added_at INTEGER NOT NULL)"""
            )
        }
        Log.info("SQLite: ${PortalPaths.dbFile}")
        return c
    }

    @Synchronized
    fun close() {
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        conn = null
    }
}
