package com.cocbot

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** ML Kit (cihaz içi, Latin) ile küçük bölgeden yazı okuma. Ana döngü thread'inde çağrılır (arka plan). */
object Ocr {
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** "x40", "X2", "x 3" gibi kart sayaçlarını yakalar. */
    private val countPattern = Regex("[xX×*]\\s*(\\d{1,3})")

    fun warmUp() { rec }

    /** Referans koordinatlı dikdörtgeni orijinal çözünürlükten kırpıp büyütür. */
    private fun crop(full: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, scale: Int): Bitmap? {
        val a = PointF()
        val b = PointF()
        Ref.map(x0.toFloat(), y0.toFloat(), full.width, full.height, a)
        Ref.map(x1.toFloat(), y1.toFloat(), full.width, full.height, b)
        val l = a.x.toInt().coerceIn(0, full.width - 8)
        val t = a.y.toInt().coerceIn(0, full.height - 8)
        val w = (b.x.toInt() - l).coerceIn(8, full.width - l)
        val h = (b.y.toInt() - t).coerceIn(8, full.height - t)
        return try {
            val base = Bitmap.createBitmap(full, l, t, w, h)
            val big = Bitmap.createScaledBitmap(base, w * scale, h * scale, true)
            if (big !== base) base.recycle()
            big
        } catch (e: Exception) {
            null
        }
    }

    /** Bölgedeki ham yazıyı döndürür (yoksa null). */
    fun readText(full: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, scale: Int = 2): String? {
        val big = crop(full, x0, y0, x1, y1, scale) ?: return null
        try {
            val text = Tasks.await(rec.process(InputImage.fromBitmap(big, 0))).text
            return if (text.isBlank()) null else text
        } catch (e: Exception) {
            return null
        } finally {
            big.recycle()
        }
    }

    /** Kartın üstündeki adet etiketi ("x40" → 40). Okunamazsa null. */
    fun readCount(full: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Int? {
        val text = readText(full, x0, y0, x1, y1, 3) ?: return null
        val m = countPattern.find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** Referans koordinatlı dikdörtgenden sayı okur. Okunamazsa null. */
    fun readNumber(full: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Long? {
        val text = readText(full, x0, y0, x1, y1, 2) ?: return null
        return parse(text)
    }

    private fun parse(text: String): Long? {
        val sb = StringBuilder()
        for (c in text) {
            when {
                c.isDigit() -> sb.append(c)
                c == 'O' || c == 'o' -> sb.append('0')
                c == 'I' || c == 'l' || c == '|' -> sb.append('1')
                c == 'S' || c == 's' -> sb.append('5')
                c == 'B' -> sb.append('8')
                c == 'Z' -> sb.append('2')
            }
        }
        if (sb.isEmpty() || sb.length > 9) return null
        return sb.toString().toLongOrNull()
    }
}
