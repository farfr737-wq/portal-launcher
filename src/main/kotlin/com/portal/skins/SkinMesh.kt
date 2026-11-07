package com.portal.skins

import java.awt.image.BufferedImage

/** Один цветной «тексель» модели (четырёхугольник) в пространстве модели. Ось Y вверх, лицо смотрит в −Z. */
class Quad(
    /** 4 вершины: x0,y0,z0,...,x3,y3,z3 */
    val p: FloatArray,
    val nx: Float, val ny: Float, val nz: Float,
    val argb: Int,
    /** Сдвиг глубины: внешний слой рисуется чуть «ближе». */
    val bias: Float,
)

/**
 * Строит 3D-модель игрока из текстуры скина 64×64 (базовый слой + внешний слой одежды).
 * Каждый видимый пиксель текстуры превращается в маленький четырёхугольник.
 */
object SkinMesh {
    private class Box(
        val u: Int, val v: Int, val w: Int, val h: Int, val d: Int,
        val cx: Float, val y0: Float, val inflate: Float, val bias: Float,
    )

    fun build(img: BufferedImage, slim: Boolean): List<Quad> {
        val aw = if (slim) 3 else 4
        val armX = 4f + aw / 2f
        val boxes = listOf(
            // базовый слой
            Box(0, 0, 8, 8, 8, 0f, 24f, 0f, 0f),                 // голова
            Box(16, 16, 8, 12, 4, 0f, 12f, 0f, 0f),               // тело
            Box(40, 16, aw, 12, 4, -armX, 12f, 0f, 0f),           // правая рука
            Box(32, 48, aw, 12, 4, armX, 12f, 0f, 0f),            // левая рука
            Box(0, 16, 4, 12, 4, -2f, 0f, 0f, 0f),                // правая нога
            Box(16, 48, 4, 12, 4, 2f, 0f, 0f, 0f),                // левая нога
            // внешний слой
            Box(32, 0, 8, 8, 8, 0f, 24f, 0.5f, -0.4f),            // шапка
            Box(16, 32, 8, 12, 4, 0f, 12f, 0.25f, -0.4f),         // куртка
            Box(40, 32, aw, 12, 4, -armX, 12f, 0.25f, -0.4f),     // правый рукав
            Box(48, 48, aw, 12, 4, armX, 12f, 0.25f, -0.4f),      // левый рукав
            Box(0, 32, 4, 12, 4, -2f, 0f, 0.25f, -0.4f),          // правая штанина
            Box(0, 48, 4, 12, 4, 2f, 0f, 0.25f, -0.4f),           // левая штанина
        )
        val out = ArrayList<Quad>(4000)
        for (b in boxes) addBox(img, b, out)
        return out
    }

    private fun addBox(img: BufferedImage, b: Box, out: MutableList<Quad>) {
        val inf = b.inflate
        val xmin = b.cx - b.w / 2f - inf
        val xmax = b.cx + b.w / 2f + inf
        val ymin = b.y0 - inf
        val ymax = b.y0 + b.h + inf
        val zmin = -b.d / 2f - inf
        val zmax = b.d / 2f + inf
        val sw = (xmax - xmin) / b.w   // размер текселя по X
        val sh = (ymax - ymin) / b.h
        val sd = (zmax - zmin) / b.d

        // перед (−Z)
        face(img, b, b.u + b.d, b.v + b.d, b.w, b.h, xmin, ymax, zmin, sw, 0f, 0f, 0f, -sh, 0f, 0f, 0f, -1f, out)
        // зад (+Z)
        face(img, b, b.u + b.d + b.w + b.d, b.v + b.d, b.w, b.h, xmax, ymax, zmax, -sw, 0f, 0f, 0f, -sh, 0f, 0f, 0f, 1f, out)
        // правая грань персонажа (−X): u идёт от спины к лицу (−Z)
        face(img, b, b.u, b.v + b.d, b.d, b.h, xmin, ymax, zmax, 0f, 0f, -sd, 0f, -sh, 0f, -1f, 0f, 0f, out)
        // левая грань (+X): u идёт от лица к спине (+Z)
        face(img, b, b.u + b.d + b.w, b.v + b.d, b.d, b.h, xmax, ymax, zmin, 0f, 0f, sd, 0f, -sh, 0f, 1f, 0f, 0f, out)
        // верх (+Y): u по +X, v к лицу (−Z)
        face(img, b, b.u + b.d, b.v, b.w, b.d, xmin, ymax, zmax, sw, 0f, 0f, 0f, 0f, -sd, 0f, 1f, 0f, out)
        // низ (−Y): u по +X, v к спине (+Z)
        face(img, b, b.u + b.d + b.w, b.v, b.w, b.d, xmin, ymin, zmin, sw, 0f, 0f, 0f, 0f, sd, 0f, -1f, 0f, out)
    }

    /**
     * Грань: texX/texY/tw/th — область текстуры; (ox,oy,oz) — угол грани, соответствующий левому верхнему пикселю;
     * (ux,uy,uz) — шаг по u на один тексель; (vx,vy,vz) — шаг по v; (nx,ny,nz) — нормаль.
     */
    private fun face(
        img: BufferedImage, b: Box, texX: Int, texY: Int, tw: Int, th: Int,
        ox: Float, oy: Float, oz: Float,
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        nx: Float, ny: Float, nz: Float,
        out: MutableList<Quad>,
    ) {
        for (j in 0 until th) for (i in 0 until tw) {
            val argb = img.getRGB(texX + i, texY + j)
            if ((argb ushr 24) < 16) continue
            val x0 = ox + ux * i + vx * j
            val y0 = oy + uy * i + vy * j
            val z0 = oz + uz * i + vz * j
            val x1 = x0 + ux; val y1 = y0 + uy; val z1 = z0 + uz        // +u
            val x2 = x1 + vx; val y2 = y1 + vy; val z2 = z1 + vz        // +u +v
            val x3 = x0 + vx; val y3 = y0 + vy; val z3 = z0 + vz        // +v
            out += Quad(floatArrayOf(x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3), nx, ny, nz, argb, b.bias)
        }
    }
}
