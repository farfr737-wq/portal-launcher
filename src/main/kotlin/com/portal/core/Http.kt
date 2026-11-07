package com.portal.core

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpException(val status: Int, val url: String, val body: String = "") :
    IOException("HTTP $status для $url")

object Http {
    const val USER_AGENT = "PortalLauncher/1.0"

    val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    class Response(val status: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
        val ok: Boolean get() = status in 200..299
    }

    fun exchange(
        method: String,
        url: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 60,
    ): Response {
        val b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSec))
            .header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
        return Response(r.statusCode(), r.body())
    }

    fun getString(url: String, headers: Map<String, String> = emptyMap()): String {
        val r = exchange("GET", url, null, headers)
        if (!r.ok) throw HttpException(r.status, url, r.text.take(300))
        return r.text
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val r = exchange("GET", url, null, headers)
        if (!r.ok) throw HttpException(r.status, url, r.text.take(300))
        return r.body
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Response =
        exchange("POST", url, json.toByteArray(), headers + ("Content-Type" to "application/json") + ("Accept" to "application/json"))

    fun postForm(url: String, form: Map<String, String>): Response {
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
        }
        return exchange("POST", url, body.toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
    }

    /** Потоковое чтение (для больших файлов). Вызывающий обязан закрыть поток. */
    fun open(url: String): InputStream {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", USER_AGENT)
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in 200..299) {
            resp.body().close()
            throw HttpException(resp.statusCode(), url)
        }
        return resp.body()
    }
}
