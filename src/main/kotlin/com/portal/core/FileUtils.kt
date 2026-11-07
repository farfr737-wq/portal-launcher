package com.portal.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun sha1Hex(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).hex()

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

fun hashFile(path: Path, algorithm: String = "SHA-1"): String {
    val md = MessageDigest.getInstance(algorithm)
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().hex()
}

/** Атомарная запись: сначала во временный файл, затем перемещение — файл не остаётся «половинчатым». */
fun Path.writeTextAtomic(text: String) {
    parent?.let { Files.createDirectories(it) }
    val tmp = resolveSibling(fileName.toString() + ".tmp")
    Files.writeString(tmp, text)
    Files.move(tmp, this, StandardCopyOption.REPLACE_EXISTING)
}

/** Рекурсивное удаление папки (только внутри переданного пути). */
fun Path.deleteTree() {
    if (!Files.exists(this)) return
    Files.walkFileTree(this, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file); return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
        }
    })
}

/** Удаляет содержимое папки, саму папку оставляет. */
fun Path.clearDirectory(): Long {
    if (!Files.isDirectory(this)) return 0
    var freed = 0L
    Files.newDirectoryStream(this).use { ds ->
        for (p in ds) {
            freed += sizeOfTree(p)
            if (Files.isDirectory(p)) p.deleteTree() else Files.deleteIfExists(p)
        }
    }
    return freed
}

fun sizeOfTree(path: Path): Long {
    if (!Files.exists(path)) return 0
    if (Files.isRegularFile(path)) return Files.size(path)
    var total = 0L
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            total += attrs.size(); return FileVisitResult.CONTINUE
        }
    })
    return total
}

fun copyTree(from: Path, to: Path, overwrite: Boolean = false) {
    Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.createDirectories(to.resolve(from.relativize(dir).toString())); return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val target = to.resolve(from.relativize(file).toString())
            if (overwrite || !Files.exists(target)) Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            return FileVisitResult.CONTINUE
        }
    })
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "%.1f КБ".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f МБ".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f ГБ".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

/** Разбор строки аргументов с поддержкой кавычек. */
fun splitArgs(line: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    var has = false
    for (c in line) {
        when {
            quote != null -> if (c == quote) quote = null else cur.append(c)
            c == '"' || c == '\'' -> { quote = c; has = true }
            c.isWhitespace() -> if (cur.isNotEmpty() || has) { out += cur.toString(); cur.setLength(0); has = false }
            else -> cur.append(c)
        }
    }
    if (cur.isNotEmpty() || has) out += cur.toString()
    return out
}
