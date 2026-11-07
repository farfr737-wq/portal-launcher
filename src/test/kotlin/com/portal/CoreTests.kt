package com.portal

import com.portal.core.splitArgs
import com.portal.java.JavaManager
import com.portal.skins.SkinValidator
import com.portal.versions.Maven
import com.portal.versions.Rules
import com.portal.core.parseObject
import com.portal.core.arr
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreTests {
    private fun png(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray()
    }

    @Test fun mavenPath() {
        assertEquals("net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar", Maven.path("net.fabricmc:fabric-loader:0.15.0"))
        assertEquals("a/b/c/1/c-1-natives-windows.jar", Maven.path("a.b:c:1:natives-windows"))
    }

    @Test fun javaMajor() {
        assertEquals(8, JavaManager.parseMajor("1.8.0_392"))
        assertEquals(17, JavaManager.parseMajor("17.0.9"))
        assertEquals(21, JavaManager.parseMajor("21"))
    }

    @Test fun splitArgsQuotes() {
        assertEquals(listOf("-Xmx2G", "-Dname=a b", "x"), splitArgs("-Xmx2G \"-Dname=a b\" x"))
    }

    @Test fun skinValidation() {
        SkinValidator.validate(png(64, 64))
        SkinValidator.validate(png(64, 32))
        assertFailsWith<com.portal.core.PortalException> { SkinValidator.validate(png(128, 128)) }
        assertFailsWith<com.portal.core.PortalException> { SkinValidator.validate(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) }
    }

    @Test fun mojangRules() {
        val rules = parseObject("""{"r":[{"action":"allow"},{"action":"disallow","os":{"name":"osx"}}]}""").arr("r")
        // allow по умолчанию, запрет только для macOS
        val onMac = com.portal.core.Os.type == com.portal.core.OsType.MACOS
        assertEquals(!onMac, Rules.allowed(rules))
        assertTrue(Rules.allowed(null))
    }
}
