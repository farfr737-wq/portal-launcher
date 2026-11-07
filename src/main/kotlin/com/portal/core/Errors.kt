package com.portal.core

/** Действие, которое UI может предложить рядом с ошибкой (например, «Скачать Java 17»). */
class ErrorAction(val label: String, val run: suspend () -> Unit)

/** Ошибка с понятным пользователю текстом и подсказкой, как её исправить. */
class PortalException(
    message: String,
    val hint: String? = null,
    cause: Throwable? = null,
    val action: ErrorAction? = null,
) : Exception(message, cause)

/** Отчёт о прогрессе: сообщение и доля 0..1 (NaN — доля не изменилась). */
typealias Report = (message: String, fraction: Float) -> Unit

val NoReport: Report = { _, _ -> }

/** Отображает под-диапазон [from, to] общего прогресса. */
fun Report.sub(from: Float, to: Float): Report = { msg, f ->
    if (f.isNaN()) this(msg, Float.NaN) else this(msg, from + (to - from) * f.coerceIn(0f, 1f))
}
