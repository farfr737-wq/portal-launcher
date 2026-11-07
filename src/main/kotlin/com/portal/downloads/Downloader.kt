package com.portal.downloads

import com.portal.core.Http
import com.portal.core.PortalException
import com.portal.core.hex
import com.portal.logging.Log
import com.portal.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class DownloadTask(
    val url: String,
    val target: Path,
    val hash: String? = null,
    val algorithm: String = "SHA-1",
    val size: Long = 0,
)

/** Простое ограничение скорости (общее на все потоки). */
class RateLimiter {
    @Volatile
    var bytesPerSec: Long = 0
    private var windowStart = System.nanoTime()
    private var used = 0L

    fun throttle(n: Int) {
        val limit = bytesPerSec
        if (limit <= 0) return
        var sleepMs = 0L
        synchronized(this) {
            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000L) {
                windowStart = now
                used = 0
            }
            used += n
            if (used > limit) {
                sleepMs = (1000 - (now - windowStart) / 1_000_000).coerceAtLeast(1)
            }
        }
        if (sleepMs > 0) Thread.sleep(sleepMs)
    }
}

/**
 * Многопоточный загрузчик: ограничение потоков и скорости, проверка SHA-1/SHA-256,
 * повтор при ошибках/повреждении, пропуск уже скачанных файлов, отмена через корутины.
 */
object Downloader {
    private val limiter = RateLimiter()
    private const val ATTEMPTS = 3

    suspend fun downloadAll(
        tasks: List<DownloadTask>,
        verifyExisting: Boolean = false,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        if (tasks.isEmpty()) return
        val s = SettingsStore.value
        limiter.bytesPerSec = s.maxDownloadKbps.coerceAtLeast(0) * 1024L
        val total = tasks.sumOf { it.size }.coerceAtLeast(1)
        val done = AtomicLong(0)
        val sem = Semaphore(s.downloadThreads.coerceIn(1, 16))
        coroutineScope {
            tasks.map { t ->
                async(Dispatchers.IO) {
                    sem.withPermit { downloadOne(t, verifyExisting, done, total, onProgress) }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadOne(
        t: DownloadTask,
        verifyExisting: Boolean,
        done: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        if (isValid(t, verifyExisting)) {
            onProgress(done.addAndGet(t.size), total)
            return
        }
        val job = currentCoroutineContext()[Job]
        var last: Exception? = null
        for (attempt in 1..ATTEMPTS) {
            var counted = 0L
            try {
                fetch(t, job) { n ->
                    counted += n
                    onProgress(done.addAndGet(n.toLong()), total)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                done.addAndGet(-counted)
                Log.warn("Загрузка ${t.url} не удалась (попытка $attempt/$ATTEMPTS): ${e.message}")
                if (attempt < ATTEMPTS) delay(400L * attempt)
            }
        }
        throw PortalException(
            "Не удалось скачать файл ${t.target.fileName}",
            "Проверьте подключение к интернету и повторите. Подробности — в разделе «Логи».",
            last
        )
    }

    private fun isValid(t: DownloadTask, verifyHash: Boolean): Boolean {
        if (!Files.isRegularFile(t.target)) return false
        if (t.size > 0 && Files.size(t.target) != t.size) return false
        if (verifyHash && t.hash != null) {
            return com.portal.core.hashFile(t.target, t.algorithm).equals(t.hash, ignoreCase = true)
        }
        return true
    }

    private fun fetch(t: DownloadTask, job: Job?, onBytes: (Int) -> Unit) {
        Files.createDirectories(t.target.parent)
        val part = t.target.resolveSibling(t.target.fileName.toString() + ".part")
        val md = t.hash?.let { MessageDigest.getInstance(t.algorithm) }
        try {
            Http.open(t.url).use { input ->
                Files.newOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        job?.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md?.update(buf, 0, n)
                        limiter.throttle(n)
                        onBytes(n)
                    }
                }
            }
            if (md != null && !md.digest().hex().equals(t.hash, ignoreCase = true)) {
                throw IOException("Контрольная сумма ${t.algorithm} не совпала для ${t.target.fileName}")
            }
            Files.move(part, t.target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(part)
        }
    }
}
