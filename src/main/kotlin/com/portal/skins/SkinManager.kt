package com.portal.skins

import com.portal.core.Database
import com.portal.core.PortalException
import com.portal.core.PortalPaths
import com.portal.logging.Log
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO

enum class SkinModel(val id: String, val title: String) {
    CLASSIC("classic", "Steve (Classic)"),
    SLIM("slim", "Alex (Slim)");

    companion object {
        fun of(id: String?) = if (id == "slim") SLIM else CLASSIC
    }
}

data class LocalSkin(val id: String, val name: String, val file: Path, val model: SkinModel, val addedAt: Long, val lastUsed: Long)

object SkinValidator {
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    const val MAX_BYTES = 512 * 1024

    /** Проверяет, что файл — корректный PNG 64x64 (или старый 64x32). Возвращает изображение 64x64. */
    fun validate(bytes: ByteArray): BufferedImage {
        if (bytes.size > MAX_BYTES) throw PortalException("Файл слишком большой (${bytes.size / 1024} КБ)", "Скин Minecraft весит десятки КБ — выберите другой файл.")
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) {
            throw PortalException("Это не PNG-файл", "Выберите изображение в формате PNG.")
        }
        val img = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            null
        } ?: throw PortalException("Не удалось прочитать изображение", "Файл повреждён.")
        if (img.width != 64 || (img.height != 64 && img.height != 32)) {
            throw PortalException(
                "Неверный размер скина: ${img.width}×${img.height}",
                "Нужен стандартный скин Minecraft 64×64 (допускается старый формат 64×32)."
            )
        }
        return if (img.height == 32) convertLegacy(img) else toArgb(img)
    }

    private fun toArgb(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        return out
    }

    /** Старый формат 64×32 -> 64×64: левые рука и нога зеркалируются из правых. */
    fun convertLegacy(src: BufferedImage): BufferedImage {
        val out = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        mirrorLimb(out, 0, 16, 16, 48)   // нога
        mirrorLimb(out, 40, 16, 32, 48)  // рука
        return out
    }

    private fun mirrorLimb(img: BufferedImage, sx: Int, sy: Int, dx: Int, dy: Int) {
        fun copyFlipped(fx: Int, fy: Int, w: Int, h: Int, tx: Int, ty: Int) {
            for (j in 0 until h) for (i in 0 until w) {
                img.setRGB(tx + (w - 1 - i), ty + j, img.getRGB(fx + i, fy + j))
            }
        }
        copyFlipped(sx + 4, sy, 4, 4, dx + 4, dy)           // верх
        copyFlipped(sx + 8, sy, 4, 4, dx + 8, dy)           // низ
        copyFlipped(sx + 4, sy + 4, 4, 12, dx + 4, dy + 4)  // перед
        copyFlipped(sx + 12, sy + 4, 4, 12, dx + 12, dy + 4) // зад
        copyFlipped(sx, sy + 4, 4, 12, dx + 8, dy + 4)      // правая грань -> левая
        copyFlipped(sx + 8, sy + 4, 4, 12, dx, dy + 4)      // левая грань -> правая
    }

    /** У «тонких» (Alex) скинов столбец x=54..55 на руке полностью прозрачен. */
    fun looksSlim(img: BufferedImage): Boolean {
        for (y in 20..31) for (x in 54..55) if ((img.getRGB(x, y) ushr 24) > 10) return false
        return true
    }
}

/** Локальные скины: файлы в skins/local, метаданные и история использования в SQLite. */
object SkinManager {

    fun list(): List<LocalSkin> = Database.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT id,name,file,model,added_at,last_used FROM local_skins ORDER BY added_at DESC").use { rs ->
                val out = ArrayList<LocalSkin>()
                while (rs.next()) {
                    out += LocalSkin(
                        rs.getString(1), rs.getString(2), PortalPaths.skinsLocal.resolve(rs.getString(3)),
                        SkinModel.of(rs.getString(4)), rs.getLong(5), rs.getLong(6)
                    )
                }
                out
            }
        }
    }

    fun recent(limit: Int = 6): List<LocalSkin> =
        list().filter { it.lastUsed > 0 }.sortedByDescending { it.lastUsed }.take(limit)

    /** Проверяет и сохраняет скин в skins/local. */
    fun import(source: Path, name: String, model: SkinModel): LocalSkin {
        val bytes = Files.readAllBytes(source)
        val img = SkinValidator.validate(bytes)
        val id = UUID.randomUUID().toString()
        val fileName = "$id.png"
        val buf = ByteArrayOutputStream()
        ImageIO.write(img, "png", buf)
        Files.createDirectories(PortalPaths.skinsLocal)
        Files.write(PortalPaths.skinsLocal.resolve(fileName), buf.toByteArray())
        val now = System.currentTimeMillis()
        Database.use { c ->
            c.prepareStatement("INSERT INTO local_skins(id,name,file,model,added_at,last_used) VALUES(?,?,?,?,?,0)").use {
                it.setString(1, id); it.setString(2, name.ifBlank { source.fileName.toString().removeSuffix(".png") })
                it.setString(3, fileName); it.setString(4, model.id); it.setLong(5, now); it.executeUpdate()
            }
        }
        Log.info("Скин сохранён: $name ($fileName, ${model.id})")
        return list().first { it.id == id }
    }

    fun setModel(id: String, model: SkinModel) = Database.use { c ->
        c.prepareStatement("UPDATE local_skins SET model=? WHERE id=?").use { it.setString(1, model.id); it.setString(2, id); it.executeUpdate() }
    }

    fun markUsed(id: String) = Database.use { c ->
        c.prepareStatement("UPDATE local_skins SET last_used=? WHERE id=?").use { it.setLong(1, System.currentTimeMillis()); it.setString(2, id); it.executeUpdate() }
    }

    fun delete(skin: LocalSkin) {
        Database.use { c -> c.prepareStatement("DELETE FROM local_skins WHERE id=?").use { it.setString(1, skin.id); it.executeUpdate() } }
        Files.deleteIfExists(skin.file)
    }

    fun readImage(file: Path): BufferedImage = SkinValidator.validate(Files.readAllBytes(file))
}
