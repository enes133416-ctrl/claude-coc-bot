package com.cocbot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Küçültülmüş kare üzerinde ucuz piksel analizi.
 * Bütün girdi koordinatları 2419x1512 referans sistemindedir.
 */
class Vision(private val bmp: Bitmap) {

    enum class Kind { ORANGE, RED, GREEN, GOLD, WHITE }

    val w = bmp.width
    val h = bmp.height

    private val rgb = IntArray(3)

    fun gx(xRef: Int): Int = (xRef * (w / Ref.W)).toInt().coerceIn(0, w - 1)

    fun gy(yRef: Int): Int = (yRef * (h / Ref.H)).toInt().coerceIn(0, h - 1)

    fun color(xRef: Int, yRef: Int): Int = bmp.getPixel(gx(xRef), gy(yRef))

    private fun isRed(r: Int, g: Int, b: Int) = r > g + 38 && r > b + 38 && r > 85

    /**
     * (2r+1)x(2r+1) kutunun ortalama rengi out'a yazılır.
     * Dönen değer kutudaki renk yayılımıdır (max - min): düz zemin → küçük, bina/ağaç → büyük.
     */
    fun mean(xRef: Int, yRef: Int, r: Int, out: IntArray): Int {
        val cx = gx(xRef)
        val cy = gy(yRef)
        val x0 = max(cx - r, 0)
        val x1 = min(cx + r, w - 1)
        val y0 = max(cy - r, 0)
        val y1 = min(cy + r, h - 1)
        var sr = 0; var sg = 0; var sb = 0; var n = 0
        var lo = 255; var hi = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val c = bmp.getPixel(x, y)
                val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
                sr += rr; sg += gg; sb += bb; n++
                lo = min(lo, min(rr, min(gg, bb)))
                hi = max(hi, max(rr, max(gg, bb)))
            }
        }
        if (n == 0) {
            out[0] = 0; out[1] = 0; out[2] = 0
            return 0
        }
        out[0] = sr / n; out[1] = sg / n; out[2] = sb / n
        return hi - lo
    }

    /** [x0,y0]-[x1,y1] kutusunda koşulu sağlayan örneklerin oranı (0..1). */
    fun ratio(x0: Int, y0: Int, x1: Int, y1: Int, kind: Kind): Float {
        val sx = max(1, (x1 - x0) / 70)
        val sy = max(1, (y1 - y0) / 70)
        var ok = 0; var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = color(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                val hit = when (kind) {
                    Kind.ORANGE -> r > 170 && g > 90 && g < 215 && b < 130
                    Kind.RED -> r > 120 && g < 85 && b < 85
                    Kind.GREEN -> g > 140 && r < 180 && b < 130
                    Kind.GOLD -> r > 150 && g > 110 && b < 90
                    Kind.WHITE -> r > 185 && g > 185 && b > 175
                }
                if (hit) ok++
                n++
                x += sx
            }
            y += sy
        }
        return if (n == 0) 0f else ok.toFloat() / n
    }

    /** Kırmızı bırakma çizgisi rengi mi? Arayüz bölgelerindeki kırmızılar sayılmaz. */
    fun isRedLine(xRef: Int, yRef: Int): Boolean {
        if (Zones.inForbidden(xRef, yRef)) return false
        val c = color(xRef, yRef)
        return isRed(Color.red(c), Color.green(c), Color.blue(c))
    }

    /** Noktanın çevresinde kırmızı bırakma çizgisi var mı? */
    fun nearRedLine(xRef: Int, yRef: Int): Boolean {
        if (isRedLine(xRef, yRef)) return true
        for (i in 0 until 12) {
            val a = i * Math.PI / 6.0
            if (isRedLine(xRef + (34 * cos(a)).toInt(), yRef + (34 * sin(a)).toInt())) return true
        }
        return false
    }

    /**
     * Asker bırakılabilir düz zemin mi?
     * Bina, duvar, ağaç, su ve kırmızı çizgi üzerindeki noktalar elenir.
     */
    fun freeGround(xRef: Int, yRef: Int): Boolean {
        val spread = mean(xRef, yRef, 5, rgb)
        if (spread > 95) return false
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        if (r + g + b < 150) return false      // çok koyu (gölge / su / arayüz)
        if (b > g + 6) return false            // mavimsi (su)
        if (r > g + 30) return false           // kırmızımsı (çizgi, kırmızı süs)
        if (g < r - 5) return false            // sarımsı/kahve (yol, bina zemini)
        return !nearRedLine(xRef, yRef)
    }

    /**
     * Gevşek zemin testi: freeGround'dan daha toleranslı. Çim yamaları, hafif dokulu
     * zemin ve yol kenarları kabul edilir; su, çok koyu ağaç kümeleri ve kırmızı çizgi elenir.
     * Havuzun boş kalıp askerlerin "bırakılamaması" sorununu önlemek için kullanılır.
     */
    fun lenientGround(xRef: Int, yRef: Int): Boolean {
        val spread = mean(xRef, yRef, 4, rgb)
        if (spread > 150) return false
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        if (r + g + b < 120) return false
        if (b > g + 12) return false
        if (r > g + 45) return false
        return !nearRedLine(xRef, yRef)
    }

    /** [yRef] satırındaki kırmızı çizginin yatay aralığı; çizgi yoksa null. */
    fun redRow(yRef: Int): IntArray? {
        val y = gy(yRef)
        var x0 = -1
        var x1 = -1
        var x = 0
        while (x < w) {
            val c = bmp.getPixel(x, y)
            if (isRed(Color.red(c), Color.green(c), Color.blue(c))) {
                val xr = (x * Ref.W / w).toInt()
                if (!Zones.inForbidden(xr, yRef)) {
                    if (x0 < 0) x0 = xr
                    x1 = xr
                }
            }
            x++
        }
        if (x0 < 0 || x1 - x0 < 12) return null
        return intArrayOf(x0, x1)
    }

    /**
     * Kırmızı çizginin sınır kutusu ve üs merkezi: [x0, y0, x1, y1, cx, cy].
     * Çizgi bulunamazsa null.
     */
    fun redBox(): IntArray? {
        var minX = Int.MAX_VALUE; var maxX = -1
        var minY = Int.MAX_VALUE; var maxY = -1
        var cx = 1210; var cy = 720
        var bestW = -1
        var y = 60
        while (y <= 1268) {
            val row = redRow(y)
            if (row != null) {
                if (row[0] < minX) minX = row[0]
                if (row[1] > maxX) maxX = row[1]
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                val wr = row[1] - row[0]
                if (wr > bestW) {         // en geniş satır = yatay köşegen
                    bestW = wr
                    cx = (row[0] + row[1]) / 2
                    cy = y
                }
            }
            y += 6
        }
        if (maxX < 0 || bestW < 60) return null
        // Sağlık kontrolü: gerçek bırakma çizgisi ekranın tamamını kaplamaz.
        // (Hazırlık ekranındaki kırmızı süsler/çalılar çizgi sanılmasın.)
        if (maxX - minX > 2170 || maxY - minY > 1400) return null
        if (minX < 60 && maxX > 2330) return null
        val y1 = maxOf(maxY, 2 * cy - minY)
        return intArrayOf(minX, minY, maxX, y1, cx, cy)
    }
}
