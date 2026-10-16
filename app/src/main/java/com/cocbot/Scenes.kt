package com.cocbot

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max

enum class Scene {
    HOME,          // köy
    WAR_MENU,      // savaş menüsü
    ARMY,          // ordu ekranı (Saldır!)
    ATTACK_PREP,   // rakip önizleme / keşif ("İleri" + "Savaşı Bitir")
    ATTACK_BATTLE, // savaş sürüyor (kırmızı "Teslim Ol")
    SURRENDER_CONFIRM, // "Teslim olalım mı?" onay ekranı
    RESULT         // savaş sonucu ("Eve Dön")
}

/** Referans koordinatta (x,y) noktasının beklenen rengi. */
class Probe(val x: Int, val y: Int, val r: Int, val g: Int, val b: Int)

/**
 * Ucuz piksel örnekleme ile sahne tanıma (küçültülmüş görüntüde).
 * Savaş ekranları büyük renkli butonların oranıyla, köy/menü/ordu ekranları
 * sabit nokta imzalarıyla tanınır.
 */
object Scenes {
    private const val TOL = 45
    private val pt = PointF()
    private val rgb = IntArray(3)

    private val sigs: List<Pair<Scene, Array<Probe>>> = listOf(
        Scene.HOME to arrayOf(
            Probe(60, 1290, 250, 202, 14), Probe(240, 1290, 250, 202, 14),
            Probe(2200, 1290, 250, 202, 14), Probe(2370, 1290, 250, 202, 14),
            Probe(60, 1460, 252, 181, 15), Probe(2190, 1470, 253, 177, 14),
            Probe(2340, 700, 206, 193, 148)
        ),
        Scene.WAR_MENU to arrayOf(
            Probe(150, 1060, 248, 173, 45), Probe(620, 1060, 248, 173, 45),
            Probe(200, 995, 254, 204, 82), Probe(580, 995, 254, 204, 83),
            Probe(200, 1130, 250, 180, 52), Probe(580, 1130, 250, 180, 52),
            Probe(420, 1440, 155, 219, 85), Probe(700, 1300, 96, 66, 55),
            Probe(1300, 1300, 96, 66, 55), Probe(60, 600, 104, 71, 56)
        ),
        Scene.ARMY to arrayOf(
            Probe(2205, 275, 205, 18, 22), Probe(2250, 275, 189, 16, 21),
            Probe(2230, 1260, 157, 230, 78), Probe(2200, 1000, 93, 64, 52),
            Probe(160, 330, 139, 90, 66), Probe(1200, 330, 139, 90, 66)
        )
    )

    /** (xRef,yRef) civarının 3x3 ortalama rengini out'a yazar. */
    private fun avg(bmp: Bitmap, xRef: Int, yRef: Int, out: IntArray) {
        Ref.map(xRef.toFloat(), yRef.toFloat(), bmp.width, bmp.height, pt)
        val cx = pt.x.toInt()
        val cy = pt.y.toInt()
        var r = 0; var g = 0; var b = 0; var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val x = (cx + dx).coerceIn(0, bmp.width - 1)
                val y = (cy + dy).coerceIn(0, bmp.height - 1)
                val c = bmp.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
            }
        }
        out[0] = r / n
        out[1] = g / n
        out[2] = b / n
    }

    /** Hangi sahnedeyiz? Tanınamazsa null (bu durumda bot hiçbir yere dokunmaz). */
    fun detect(small: Bitmap): Scene? {
        val v = Vision(small)

        // Keşif ekranı: sağ altta turuncu "İleri" butonu
        if (v.ratio(2040, 1050, 2400, 1250, Vision.Kind.ORANGE) > 0.25f) return Scene.ATTACK_PREP

        // "Teslim olalım mı?" onay ekranı: ortada beyaz kutu + yeşil "Tamam"
        if (v.ratio(1150, 640, 1300, 1030, Vision.Kind.WHITE) > 0.45f &&
            v.ratio(1245, 865, 1660, 1020, Vision.Kind.GREEN) > 0.25f
        ) return Scene.SURRENDER_CONFIRM

        // Savaş sonucu: altın şerit + yeşil "Eve Dön"
        if (v.ratio(1040, 1250, 1390, 1372, Vision.Kind.GREEN) > 0.25f &&
            v.ratio(700, 380, 1750, 560, Vision.Kind.GOLD) > 0.12f
        ) return Scene.RESULT

        // Sabit nokta imzaları: köy / savaş menüsü / ordu
        for ((scene, probes) in sigs) {
            var ok = 0
            for (p in probes) {
                avg(small, p.x, p.y, rgb)
                val d = max(abs(rgb[0] - p.r), max(abs(rgb[1] - p.g), abs(rgb[2] - p.b)))
                if (d <= TOL) ok++
            }
            if (ok >= probes.size - 1) return scene
        }

        // Savaş ekranı: sol alttaki kırmızı "Teslim Ol" butonu
        if (v.ratio(35, 1150, 290, 1240, Vision.Kind.RED) > 0.35f) return Scene.ATTACK_BATTLE

        return null
    }
}
