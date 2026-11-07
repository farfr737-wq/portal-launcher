package com.portal.versions

import com.portal.core.Os
import com.portal.core.arr
import com.portal.core.obj
import com.portal.core.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Правила Mojang (rules: allow/disallow по ОС, архитектуре и «features»). */
object Rules {
    fun allowed(rules: JsonArray?, features: Set<String> = emptySet()): Boolean {
        if (rules == null || rules.isEmpty()) return true
        var allowed = false
        for (r in rules) {
            val o = r as? JsonObject ?: continue
            if (matches(o, features)) allowed = o.string("action") == "allow"
        }
        return allowed
    }

    private fun matches(rule: JsonObject, features: Set<String>): Boolean {
        rule.obj("os")?.let { os ->
            os.string("name")?.let { if (it != Os.type.mojang) return false }
            os.string("arch")?.let { if (it != Os.archId) return false }
            os.string("version")?.let { if (!Regex(it).containsMatchIn(Os.version)) return false }
        }
        rule.obj("features")?.let { f ->
            for ((k, v) in f) {
                val want = (v as? JsonPrimitive)?.booleanOrNull ?: true
                if ((k in features) != want) return false
            }
        }
        return true
    }
}

object Maven {
    /** "group:artifact:version[:classifier][@ext]" -> относительный путь в maven-хранилище. */
    fun path(coord: String): String {
        val ext = if ('@' in coord) coord.substringAfter('@') else "jar"
        val c = coord.substringBefore('@').split(':')
        require(c.size >= 3) { "Некорректная maven-координата: $coord" }
        val g = c[0].replace('.', '/')
        val a = c[1]
        val v = c[2]
        val cl = c.getOrNull(3)
        return "$g/$a/$v/$a-$v${if (cl != null) "-$cl" else ""}.$ext"
    }

    /** Ключ без версии: нужен, чтобы библиотека загрузчика перекрывала ванильную. */
    fun key(coord: String): String {
        val c = coord.substringBefore('@').split(':')
        return "${c[0]}:${c.getOrNull(1)}:${c.getOrNull(3) ?: ""}"
    }
}
