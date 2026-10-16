package com.cocbot

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Dokunulmaması gereken arayüz bölgeleri. Hem bırakma noktası seçiminde
 * hem kırmızı çizgi taramasında (buton kırmızısı çizgi sanılmasın diye) kullanılır.
 */
object Zones {
    /** (x0,y0,x1,y1) referans koordinatta. */
    val FORBIDDEN = arrayOf(
        intArrayOf(0, 0, 520, 345),          // rakip adı + ganimet paneli
        intArrayOf(1860, 0, 2419, 430),      // sağ üst sayaçlar + harita süsü
        intArrayOf(1000, 0, 1420, 150),      // geri sayım / savaş yazısı
        intArrayOf(20, 1140, 1080, 1265),    // Teslim Ol / Savaşı Bitir + güçlendirme kutuları
        intArrayOf(0, 1265, 2419, 1512),     // alt kart satırı
        intArrayOf(2010, 1030, 2419, 1265)   // İleri / Toplam Hasar
    )

    fun inForbidden(x: Int, y: Int): Boolean {
        for (r in FORBIDDEN) if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]) return true
        return false
    }
}

/**
 * Savaş alanını tarayıp asker bırakılabilecek noktaları bulur ve her seçimde
 * "kullanılan noktalardan en uzak" noktayı döndürür; böylece askerler
 * asla aynı yere veya birbirine yakın yerlere bırakılmaz.
 *
 * Harita karesi [Vision] üzerinden okunur; kare canlı ekran değil, savaş
 * başında alınan sabit bir kopya olduğu için kararlar kararlıdır.
 */
class Deployer(private val v: Vision) {

    companion object {
        const val GRID = 18              // tarama adımı (referans piksel)
        const val EDGE = 45              // kırmızı çizgiye yaklaşma payı (px)
        const val MIN_DIST = 150f        // iki bırakma noktası arası en az mesafe
        const val X_MIN = 40
        const val X_MAX = 2390
        const val Y_MIN = 55
        const val Y_MAX = 1135

        /** Üs merkezine bu mesafeden yakın noktalar üssün içi sayılır (kırmızı çizgi yoksa kullanılır). */
        const val OUTSIDE_R = 560f

        /** Kahraman bölgesi: üs sınır kutusuna göre sağ üst (x,y oranı). */
        val HERO_SPOTS = arrayOf(
            floatArrayOf(0.80f, 0.24f),
            floatArrayOf(0.90f, 0.34f),
            floatArrayOf(0.70f, 0.34f),
            floatArrayOf(0.86f, 0.46f),
            floatArrayOf(0.62f, 0.26f)
        )

        /** Büyü bölgesi: üssün ortası (kral/kale bölgesi). */
        val SPELL_SPOTS = arrayOf(
            floatArrayOf(0.50f, 0.50f),
            floatArrayOf(0.44f, 0.46f),
            floatArrayOf(0.56f, 0.54f),
            floatArrayOf(0.50f, 0.58f),
            floatArrayOf(0.44f, 0.58f),
            floatArrayOf(0.56f, 0.44f),
            floatArrayOf(0.38f, 0.50f),
            floatArrayOf(0.62f, 0.50f)
        )
    }

    private val pool = ArrayList<Int>(4096)
    private val taken = ArrayList<Boolean>(4096)
    private val score = ArrayList<Int>(4096)
    private val used = ArrayList<Int>(128)
    private val rgb = IntArray(3)

    private var hasLine = false
    private var bx0 = 0
    private var by0 = 0
    private var bx1 = 0
    private var by1 = 0
    private var centerX = 1210
    private var centerY = 720
    private var halfW = 700f
    private var halfH = 520f

    var points = 0
        private set

    val ready: Boolean get() = points > 0

    /**
     * Alanı tarar.
     * @param requireRedLine true ise yalnızca kırmızı çizginin dışındaki noktalar
     *        (savaş başladıktan sonraki kareler); false ise kırmızı çizgi görünmeden
     *        (keşif ekranı) üs merkezine uzaklık ile dış alan tahmin edilir.
     */
    fun scan(requireRedLine: Boolean = true): Boolean {
        reset()
        points = 0
        hasLine = false
        if (requireRedLine) {
            val b = v.redBox()
            if (b != null && b[2] - b[0] > 300) {
                hasLine = true
                bx0 = b[0]; by0 = b[1]; bx1 = b[2]; by1 = b[3]
                centerX = b[4]
                centerY = b[5]
                halfW = ((bx1 - bx0) / 2f).coerceAtLeast(1f)
                halfH = ((by1 - by0) / 2f).coerceAtLeast(1f)
            }
        }
        var y = Y_MIN
        while (y <= Y_MAX) {
            var x = X_MIN
            while (x <= X_MAX) {
                if (okPoint(x, y)) add(x, y)
                x += GRID
            }
            y += GRID
        }
        rank()
        points = pool.size
        return points > 0
    }

    private fun add(x: Int, y: Int) {
        pool.add(pack(x, y))
        taken.add(false)
        score.add(0)
    }

    /**
     * Havuzu "düz ve aydınlık çim" puanına göre sıralar. Bina, kaya ve ağaç
     * kenarları ya koyu ya da detaylı olduğu için puanları düşük kalır.
     */
    private fun rank() {
        for (i in pool.indices) {
            val spread = v.mean(xOf(pool[i]), yOf(pool[i]), 5, rgb)
            score[i] = (rgb[0] + rgb[1] + rgb[2]) - spread * 2
        }
        val idx = pool.indices.sortedByDescending { score[it] }
        val np = ArrayList<Int>(pool.size)
        val nt = ArrayList<Boolean>(pool.size)
        val ns = ArrayList<Int>(pool.size)
        for (i in idx) {
            np.add(pool[i]); nt.add(false); ns.add(score[i])
        }
        pool.clear(); pool.addAll(np)
        taken.clear(); taken.addAll(nt)
        score.clear(); score.addAll(ns)
    }

    private fun reset() {
        pool.clear()
        taken.clear()
        used.clear()
    }

    private fun okPoint(x: Int, y: Int): Boolean {
        if (forbidden(x, y)) return false
        if (hasLine) {
            if (insideBoundary(x, y)) return false
        } else {
            // kırmızı çizgi görünmüyorsa (keşif karesi): üs merkezine uzaklık ile dış alan tahmini
            if (hypot((x - centerX).toFloat(), (y - centerY).toFloat()) < OUTSIDE_R) return false
        }
        return v.freeGround(x, y)
    }

    /**
     * Kırmızı çizginin içi mi? Bırakma alanı izometrik bir eşkenar dörtgen olduğu için
     * |x-cx|/a + |y-cy|/b < 1 + pay kuralı kullanılır. Çizgi yer yer ağaç/kaya altında
     * kaldığından satır satır tarama güvenilir değildir.
     */
    private fun insideBoundary(x: Int, y: Int): Boolean {
        val dx = kotlin.math.abs(x - centerX).toFloat() / halfW
        val dy = kotlin.math.abs(y - centerY).toFloat() / halfH
        val margin = EDGE / halfW
        return dx + dy < 1f + margin
    }

    /**
     * İlk nokta: en iyi puanlı noktaların içinden üs merkezine en uzak olan.
     * Böylece ilk grup da haritanın bir köşesine değil, düzgün çime dağılır.
     */
    fun first(): Int {
        val limit = maxOf(1, pool.size / 3)
        var bi = -1
        var bestD = -1f
        for (i in 0 until limit) {
            val d = hypot((xOf(pool[i]) - centerX).toFloat(), (yOf(pool[i]) - centerY).toFloat())
            if (d > bestD) {
                bestD = d
                bi = i
            }
        }
        if (bi < 0) return -1
        taken[bi] = true
        used.add(pool[bi])
        return pool[bi]
    }

    /**
     * Kullanılan tüm noktalardan en uzak, en az [minDist] uzaklıkta bir nokta.
     * Daha önce seçilen bir nokta asla tekrar seçilmez. Uygun nokta yoksa -1.
     */
    fun pick(minDist: Float = MIN_DIST): Int {
        var bi = -1
        var bestD = -1f
        for (i in pool.indices) {
            if (taken[i]) continue
            val p = pool[i]
            var d = Float.MAX_VALUE
            for (u in used) {
                val dd = dist(p, u)
                if (dd < d) d = dd
                if (d <= bestD) break
            }
            if (d > bestD) {
                bestD = d
                bi = i
            }
        }
        if (bi < 0 || bestD < minDist) return -1
        taken[bi] = true
        used.add(pool[bi])
        return pool[bi]
    }

    /** Boşaltma için: mesafe kuralını kademeli gevşeterek nokta arar. */
    fun anyFree(): Int {
        for (d in floatArrayOf(MIN_DIST, 130f, 110f, 80f, 55f, 20f)) {
            val p = pick(d)
            if (p >= 0) return p
        }
        return -1
    }

    /**
     * Kahraman için sağ üst bölge: üs merkezinin sağ-üst tarafında, kırmızı çizginin
     * hemen dışındaki geçerli noktalar arasından merkeze en yakın olanlar seçilir.
     */
    fun heroPoint(index: Int): Int {
        val cand = heroCandidates()
        if (cand.isEmpty()) return zonePoint(HERO_SPOTS[0], true)
        cand.sortBy { hypot((xOf(it) - centerX).toFloat(), (yOf(it) - centerY).toFloat()) }
        var pick = cand[0]
        for (minD in floatArrayOf(220f, 150f, 100f, 40f, 0f)) {
            var found = -1
            for (c in cand) {
                var ok = true
                for (u in used) if (dist(c, u) < minD) { ok = false; break }
                if (ok) { found = c; break }
            }
            if (found >= 0) { pick = found; break }
        }
        val idx = pool.indexOf(pick)
        if (idx >= 0) taken[idx] = true
        used.add(pick)
        return pick
    }

    /** Sağ üst bölgedeki geçerli noktalar; daralırsa arama gevşetilir. */
    private fun heroCandidates(): ArrayList<Int> {
        var cand = sector { dx, dy -> dx > 50 && dy < -40 && -dy < 2.2f * dx }
        if (cand.size < 4) cand = sector { dx, _ -> dx > 250 }
        if (cand.size < 4) cand = sector { _, dy -> dy < -250 }
        return cand
    }

    private inline fun sector(test: (Int, Int) -> Boolean): ArrayList<Int> {
        val out = ArrayList<Int>()
        for (p in pool) {
            val dx = xOf(p) - centerX
            val dy = yOf(p) - centerY
            if (test(dx, dy)) out.add(p)
        }
        return out
    }

    /** Büyü için üssün ortasında nokta (büyüler binaların üstüne bırakılabilir). */
    fun spellPoint(index: Int): Int = zonePoint(SPELL_SPOTS[index % SPELL_SPOTS.size], false)

    private val spellUsed = ArrayList<Int>(32)

    /** Büyü bölgesinden (üssün ortası) sıradaki nokta; aynı nokta iki kez seçilmez. */
    fun nextSpellPoint(): Int {
        for (attempt in 0 until 48) {
            val seed = spellUsed.size
            val p = if (seed < SPELL_SPOTS.size) spellPoint(seed) else spiralPoint(seed)
            var ok = true
            for (u in spellUsed) if (dist(p, u) < 60f) { ok = false; break }
            if (ok) {
                spellUsed.add(p)
                return p
            }
            if (attempt > 40) break
        }
        val p = spiralPoint(spellUsed.size + 1)
        spellUsed.add(p)
        return p
    }

    /** Altın açı ile üs merkezinin çevresinde genişleyen sarmal (büyüler için). */
    private fun spiralPoint(seed: Int): Int {
        val golden = 2.39996
        val r = 70f + 62f * kotlin.math.sqrt(seed.toFloat())
        val a = seed * golden
        val x = centerX + (r * cos(a)).toInt()
        val y = centerY + (r * 0.72f * sin(a)).toInt()
        return pack(
            x.coerceIn(X_MIN, X_MAX),
            y.coerceIn(Y_MIN, Y_MAX)
        )
    }

    /**
     * Nokta hâlâ geçerli mi? Bırakma anında canlı karede kontrol edilir:
     * sınır kutusu (savaş boyunca sabit) + o anki zemin durumu.
     */
    fun valid(p: Int, live: Vision): Boolean {
        val x = xOf(p)
        val y = yOf(p)
        if (forbidden(x, y)) return false
        if (hasLine && insideBoundary(x, y)) return false
        return live.freeGround(x, y)
    }

    private fun zonePoint(frac: FloatArray, needFree: Boolean): Int {
        val x = bx0 + ((bx1 - bx0) * frac[0]).toInt()
        val y = by0 + ((by1 - by0) * frac[1]).toInt()
        if (inside(x, y) && (!needFree || v.freeGround(x, y))) return pack(x, y)
        for (r in intArrayOf(70, 130, 190, 260, 330)) {
            for (i in 0 until 12) {
                val a = i * Math.PI / 6.0
                val nx = x + (r * cos(a)).toInt()
                val ny = y + (r * sin(a)).toInt()
                if (!inside(nx, ny) || forbidden(nx, ny)) continue
                if (!needFree || v.freeGround(nx, ny)) return pack(nx, ny)
            }
        }
        return pack(x, y)
    }

    private fun inside(x: Int, y: Int) = x >= X_MIN && x <= X_MAX && y >= Y_MIN && y <= Y_MAX

    private fun forbidden(x: Int, y: Int): Boolean = Zones.inForbidden(x, y)

    private fun dist(a: Int, b: Int): Float =
        hypot((xOf(a) - xOf(b)).toFloat(), (yOf(a) - yOf(b)).toFloat())

    /** Şimdiye kadar kullanılan noktalar (yeni bir taramaya taşınabilir). */
    fun usedPoints(): IntArray = used.toIntArray()

    /** Kullanılan noktaları devralır (yeniden tarama sonrası aynı noktalara bırakılmaz). */
    fun usePoints(pts: IntArray) {
        for (p in pts) if (!used.contains(p)) used.add(p)
    }

    private fun pack(x: Int, y: Int): Int = (x shl 16) or (y and 0xFFFF)

    fun xOf(p: Int): Int = p shr 16

    fun yOf(p: Int): Int = p and 0xFFFF
}
