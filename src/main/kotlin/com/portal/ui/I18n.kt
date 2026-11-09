package com.portal.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Lang(val code: String, val name: String)

/**
 * Простая локализация: ключ — русский текст (исходный язык интерфейса).
 * Если перевода нет, показывается русский текст. Язык хранится как состояние Compose — интерфейс обновляется сразу.
 */
object I18n {
    val languages = listOf(
        Lang("ru", "Русский"), Lang("en", "English"), Lang("zh", "中文"), Lang("hi", "हिन्दी"),
        Lang("es", "Español"), Lang("fr", "Français"), Lang("ar", "العربية"), Lang("pt", "Português"),
        Lang("bn", "বাংলা"), Lang("de", "Deutsch"), Lang("ja", "日本語"), Lang("id", "Bahasa Indonesia"),
    )

    var lang by mutableStateOf("ru")

    // порядок переводов в таблице: en, zh, hi, es, fr, ar, pt, bn, de, ja, id
    private val order = listOf("en", "zh", "hi", "es", "fr", "ar", "pt", "bn", "de", "ja", "id")

    private val table: Map<String, Map<String, String>> by lazy {
        val out = HashMap<String, Map<String, String>>()
        for ((ru, tr) in I18nData.rows) {
            val m = HashMap<String, String>()
            order.forEachIndexed { i, code -> tr.getOrNull(i)?.let { if (it.isNotBlank()) m[code] = it } }
            out[ru] = m
        }
        out
    }

    fun tr(ru: String): String {
        val l = lang
        if (l == "ru") return ru
        return table[ru]?.get(l) ?: table[ru]?.get("en") ?: ru
    }
}

/** Перевод строки интерфейса на выбранный язык. */
fun tr(ru: String): String = I18n.tr(ru)
