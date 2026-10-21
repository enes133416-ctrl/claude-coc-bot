package com.cocbot

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Durum makinesi. Her karede önce sahne tanınır; tanınmazsa hiçbir yere dokunulmaz.
 *
 * Köy → Savaş menüsü → Ordu → rakip önizleme → savaş → sonuç → köy
 *
 * v3 saldırı planı (50 tazı + kral):
 *   0) Ganimet (altın + iksir) 1.000.000'u geçmiyorsa "İleri" ile yeni köy aranır.
 *   1) Geçtiyse: ekran iki parmakla UZAKLAŞTIRILIR (pinch-in), harita yeniden taranır.
 *   2) Tazı kartına bir kez basılır ve 15 sn boyunca durmadan, HER SEFERİNDE AYNI ANDA
 *      3 ayrı geçerli noktaya 3 tazı bırakılır (tek jestte üç parmak).
 *   3) 15 sn dolunca kral kartına basılır ve sağ üst/ön cepheye bırakılır.
 *   4) Elde tazı kaldıysa kısa bir boşaltma turu daha yapılır (kart boş kalmasın).
 *   5) TESLİM OLUNMAZ: savaş kendiliğinden bitene kadar beklenir, sonuç ekranında "Eve Dön".
 */
class BotLoop(private val svc: BotService) {

    companion object {
        const val WAIT_MS = 2000L            // dokunuş sonrası ekran değişimi bekleme üst sınırı
        const val MAX_ATTEMPTS = 3           // aynı sahnede art arda en fazla deneme
        const val RETREAT_ATTEMPTS = 8       // Eve Dön için deneme sayısı
        const val LOOT_LIMIT = 1_000_000L    // altın + iksir bunu GEÇERSE saldır
        const val TAP_GAP_MS = 90L           // bırakma dokunuşları arası bekleme
        const val UNKNOWN_WARN_MS = 30_000L
        const val BATTLE_WARN_MS = 240_000L

        // --- v3 saldırı planı ---
        const val HOUND_MS = 15_000L         // tazı bırakma penceresi (15 sn)
        const val HOUNDS_PER_BURST = 3       // her seferinde AYNI ANDA 3 noktaya 3 tazı
        const val BURST_GAP_MS = 60L         // iki üçlü bırakma arası
        const val CARD_KEEP_MS = 2_500L      // kart seçimini tazeleme aralığı
        const val CARD_REFRESH = 2           // kartın toplam dokunulma sayısı (ilk + 1 tazeleme)
        const val SCENE_CHECK_MS = 1_500L    // bırakma sırasında sahne kontrolü
        const val KING_FLUSH_ROUNDS = 6      // kraldan sonra kalan tazı için tur sayısı
        const val KING_WAIT_MAX_MS = 60_000L // kral, savaş başlaması için en fazla bu kadar bekler
        const val ZOOM_PINCHES = 2           // uzaklaştırma kıstırması sayısı
        const val ZOOM_WAIT_MS = 600L        // kıstırmadan sonra kameranın oturması
        const val NATURAL_LIMIT_MS = 210_000L // savaş kendiliğinden bitmezse dur (teslim YOK)

        // Kart yuvaları (0 tabanlı) — referans 2419x1512
        val CARD_X = intArrayOf(326, 528, 704, 874, 1043, 1232, 1401, 1571, 1740, 1900)
        const val CARD_TAP_Y = 1382
        val CARD_LBL_X = arrayOf(
            intArrayOf(250, 405), intArrayOf(440, 610), intArrayOf(620, 785),
            intArrayOf(795, 955), intArrayOf(965, 1120), intArrayOf(1155, 1310),
            intArrayOf(1325, 1480), intArrayOf(1495, 1650), intArrayOf(1665, 1820),
            intArrayOf(1835, 2000)
        )
        const val CARD_LBL_Y0 = 1255
        const val CARD_LBL_Y1 = 1345

        const val SLOT_HOUNDS = 0            // x50 tazı kartı
        const val SLOT_KING = 1              // kral kartı

        const val SURRENDER_X = 152; const val SURRENDER_Y = 1193   // kırmızı Savaşı Bitir / Teslim Ol
        const val TAMAM_X = 1451; const val TAMAM_Y = 941           // onay ekranı yeşil Tamam
        const val EVE_DON_X = 1211; const val EVE_DON_Y = 1308      // sonuç ekranı Eve Dön
    }

    private enum class Stage { SCOUT, ATTACK }
    private enum class Phase { ZOOM, HOUNDS, KING, WAIT }

    private val pt = PointF()

    private var lastTapScene: Scene? = null
    private var lastTapAt = 0L
    private var attempts = 0
    private var unknownSince = 0L

    // Keşif durumu
    private var pending: Pair<Long, Long>? = null
    private var prevVillage: Pair<Long, Long>? = null
    private var ileriAt = 0L
    private var ileriTries = 0

    // Savaş planı durumu
    private var stage = Stage.SCOUT
    private var phase = Phase.ZOOM
    private var attackStart = 0L
    private var firstDeployAt = 0L
    private var houndStartAt = 0L
    private var houndWindows = 0
    private var houndTaps = 0
    private var cardTaps = 0
    private var cardKeepAt = 0L
    private var sceneCheckAt = 0L
    private var kingWaitAt = 0L
    private var battleScanned = false
    private var scanTries = 0

    private var mapBmp: Bitmap? = null
    private var deployer: Deployer? = null

    suspend fun run() {
        while (svc.running) {
            try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                svc.log("Hata: ${e.javaClass.simpleName}")
                delay(100)
            }
        }
    }

    private suspend fun step() {
        val frame = svc.grab()
        if (frame == null) {          // OS ekran görüntüsü hız sınırı vb.: kısa bekle, tekrar al
            delay(40)
            return
        }
        try {
            val scene = Scenes.detect(frame.small)
            handle(frame, scene)
        } finally {
            frame.recycle()
        }
    }

    private suspend fun handle(frame: Frame, scene: Scene?) {
        val now = SystemClock.elapsedRealtime()

        if (scene == null) {
            if (unknownSince == 0L) unknownSince = now
            val limit = if (stage == Stage.ATTACK) BATTLE_WARN_MS else UNKNOWN_WARN_MS
            when {
                now - unknownSince > limit -> svc.log("⚠ Bilinmeyen ekran (dokunulmuyor)")
                stage == Stage.ATTACK -> svc.log("Savaş sürüyor…")
                else -> svc.log("Ekran bekleniyor…")
            }
            return
        }
        unknownSince = 0L

        when (scene) {
            Scene.HOME -> {
                if (stage != Stage.SCOUT) finishAttack()
                act(frame, scene, now, "Savaş") { tapRef(frame, 147f, 1375f) }
            }
            Scene.WAR_MENU -> act(frame, scene, now, "Eşleşme Bul") { tapRef(frame, 383f, 1060f) }
            Scene.ARMY -> act(frame, scene, now, "Saldır!") { tapRef(frame, 2050f, 1238f) }
            Scene.RESULT -> {
                if (stage != Stage.SCOUT) finishAttack()
                act(frame, scene, now, "Eve Dön", RETREAT_ATTEMPTS) { tapRef(frame, EVE_DON_X.toFloat(), EVE_DON_Y.toFloat()) }
            }
            Scene.ATTACK_PREP -> when (stage) {
                Stage.ATTACK -> battle(frame, scene, now)
                else -> scout(frame, now)
            }
            Scene.ATTACK_BATTLE -> when (stage) {
                Stage.ATTACK -> battle(frame, scene, now)
                else -> {
                    svc.log("Savaş ortasında başlatıldı, plan devreye giriyor")
                    beginAttack(SystemClock.elapsedRealtime())
                    battle(frame, scene, now)
                }
            }
            Scene.SURRENDER_CONFIRM ->
                // Teslim olunmuyor: bu ekran beklenmiyor, karışmıyoruz (İptal'e de basmıyoruz).
                svc.log("Onay ekranı (bot beklemiyor, dokunulmuyor)")
        }
    }

    // ---------------------------------------------------------------- keşif

    private suspend fun scout(frame: Frame, now: Long) {
        // Ganimet: sarı altın (y≈178) ve pembe iksir (y≈245); kara iksir okunmaz
        val gold = Ocr.readNumber(frame.full, 105, 138, 345, 200)
        val elixir = Ocr.readNumber(frame.full, 105, 220, 345, 275)
        if (gold == null || elixir == null) {
            pending = null
            svc.log("Ganimet okunuyor…")
            return
        }
        val cur = gold to elixir

        // İleri'den sonra eski köy hâlâ görünüyorsa yeni köyü bekle
        if (cur == prevVillage && now - ileriAt < WAIT_MS) {
            pending = null
            svc.log("Yeni köy bekleniyor…")
            return
        }
        // Yanlış okumaya karşı: iki ardışık karede aynı değer
        if (cur != pending) {
            pending = cur
            svc.log("Ganimet okunuyor…")
            return
        }
        if (prevVillage != null && cur != prevVillage) ileriTries = 0

        val total = gold + elixir
        if (total <= LOOT_LIMIT) {
            if (ileriTries >= MAX_ATTEMPTS) {
                svc.stopWithWarning("⚠ İleri çalışmadı, durdu")
                return
            }
            ileriTries++
            prevVillage = cur
            ileriAt = now
            pending = null
            svc.log("Toplam $total ≤ 1M → İleri")
            tapRef(frame, 2220f, 1148f)
        } else {
            svc.log("Toplam $total > 1M → saldırı başlıyor")
            beginAttack(now)
        }
    }

    // ------------------------------------------------------------- saldırı

    private fun beginAttack(now: Long) {
        stage = Stage.ATTACK
        phase = Phase.ZOOM
        attackStart = if (now > 0) now else SystemClock.elapsedRealtime()
        firstDeployAt = 0L
        houndStartAt = 0L
        houndWindows = 0
        houndTaps = 0
        cardTaps = 0
        cardKeepAt = 0L
        sceneCheckAt = 0L
        kingWaitAt = 0L
        battleScanned = false
        scanTries = 0
        releaseMap()
    }

    private fun finishAttack() {
        releaseMap()
        stage = Stage.SCOUT
        phase = Phase.ZOOM
        attackStart = 0L
        firstDeployAt = 0L
        houndStartAt = 0L
        battleScanned = false
        scanTries = 0
    }

    private fun releaseMap() {
        deployer = null
        mapBmp?.recycle()
        mapBmp = null
    }

    /** Savaş alanı kopyasını hazırlar (keşifte çizgisiz, savaşta kırmızı çizgi ile). */
    private suspend fun prepareMap(frame: Frame, battleScene: Boolean): Boolean {
        val old = deployer
        val map = try {
            frame.small.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Throwable) {
            null
        } ?: return false
        val nd = Deployer(Vision(map))
        nd.scan(requireRedLine = battleScene)
        if (!nd.ready && battleScene && scanTries >= 2) {
            // çizgi bulunamadıysa üs merkezine uzaklık kuralına düş
            nd.scan(requireRedLine = false)
        }
        if (!nd.ready) {
            map.recycle()
            scanTries++
            svc.log("Bırakma alanı aranıyor…")
            return false
        }
        if (old != null) nd.usePoints(old.usedPoints())
        releaseMap()
        mapBmp = map
        deployer = nd
        svc.log("Alan hazır (${nd.points} nokta)")
        return true
    }

    private suspend fun battle(frame: Frame, scene: Scene, now: Long) {
        if (stage != Stage.ATTACK) return

        // 1) Zoom: harita taramasından ÖNCE, tek seferlik
        if (phase == Phase.ZOOM) {
            zoomPhase(frame)
            return
        }

        val battleScene = scene == Scene.ATTACK_BATTLE
        val d = deployer
        if (d == null) {
            if (!prepareMap(frame, battleScene)) return
            battleScanned = battleScene
        } else if (battleScene && !battleScanned) {
            if (!prepareMap(frame, true)) return
            battleScanned = true
        }

        when (phase) {
            Phase.ZOOM -> zoomPhase(frame)
            Phase.HOUNDS -> houndPhase(frame)
            Phase.KING -> kingPhase(frame, battleScene)
            Phase.WAIT -> {
                // Savaş gerçekten başladıysa ve kartta hâlâ tazı varsa (sayaç > 0)
                // bir 15 sn'lik pencere daha aç: hazırlık ekranında yapılan dokunuşlar boşa gitmiş olabilir.
                val left = readCount(frame, SLOT_HOUNDS) ?: 0
                if (battleScene && left > 0 && houndWindows < 2) {
                    houndWindows++
                    svc.log("Kartta $left tazı kaldı → 15 sn daha bırakılıyor")
                    houndStartAt = 0L
                    phase = Phase.HOUNDS
                    return
                }
                val ref = if (firstDeployAt > 0) firstDeployAt else attackStart
                if (now - ref > NATURAL_LIMIT_MS) {
                    svc.stopWithWarning("⚠ Savaş kendiliğinden bitmedi, durdu")
                } else {
                    svc.log("Savaş sürüyor… ${(now - ref) / 1000} sn")
                    delay(200)
                }
            }
        }
    }

    /** Ekranı iki parmakla uzaklaştırır (kıstırma); alan sonra yeniden taranır. */
    private suspend fun zoomPhase(frame: Frame) {
        val c = PointF()
        Ref.map(1210f, 700f, frame.w, frame.h, c)
        val r0 = frame.w * 0.13f
        val r1 = frame.w * 0.035f
        svc.log("Ekran uzaklaştırılıyor")
        for (i in 0 until ZOOM_PINCHES) {
            if (!svc.running) return
            svc.pinch(c.x, c.y, r0, r1, 260L)
            delay(140)
        }
        delay(ZOOM_WAIT_MS)
        releaseMap()          // zoom'lu kareyle alanı yeniden tara
        houndStartAt = 0L
        phase = Phase.HOUNDS
        svc.log("Uzaklaştırıldı → tazılar bırakılıyor (15 sn)")
    }

    /**
     * 15 sn boyunca durmadan: kartı seç, AYNI ANDA 3 geçerli noktaya 3 tazı bırak, tekrar.
     * Kart sayacı okunmaz/okunması beklenmez; geçersiz yere denk gelen dokunuşlar telafi edilir.
     */
    private suspend fun houndPhase(frame: Frame) {
        if (houndStartAt == 0L) {
            houndStartAt = SystemClock.elapsedRealtime()
            if (houndWindows == 0) houndWindows = 1
            cardKeepAt = 0L
            cardTaps = 0
            sceneCheckAt = 0L
            svc.log("Tazı penceresi ${houndWindows}/2 başladı")
        }
        while (true) {
            if (!svc.running) return
            val t = SystemClock.elapsedRealtime()
            if (t - houndStartAt >= HOUND_MS) break

            // Sahne değişti mi? (savaş bittiyse boşa dokunmayalım)
            if (t - sceneCheckAt >= SCENE_CHECK_MS) {
                sceneCheckAt = t
                val st = currentScene()
                if (st == Scene.RESULT || st == Scene.HOME) {
                    svc.log("Savaş bitti (${houndTaps} tazı dokunuşu)")
                    return
                }
            }

            // Kart seçimini tazele. (Seçili karta tekrar basmak seçimi kaldırabilir;
            // bu yüzden yalnızca ilk dokunuş + tek bir tazeleme yapılır.)
            if (cardTaps < CARD_REFRESH && (cardKeepAt == 0L || t - cardKeepAt >= CARD_KEEP_MS)) {
                tapCard(frame, SLOT_HOUNDS)
                cardTaps++
                cardKeepAt = t
                delay(80)
            }

            val n = deployBurst(frame, HOUNDS_PER_BURST)
            if (n > 0) {
                houndTaps += n
                if (firstDeployAt == 0L) firstDeployAt = SystemClock.elapsedRealtime()
            }
            delay(BURST_GAP_MS)
        }
        svc.log("Tazı penceresi bitti: $houndTaps asker bırakıldı")
        phase = Phase.KING
    }

    /**
     * Kralı sağ üst/ön cepheye bırakır.
     * Savaş henüz başlamadıysa (hazırlık geri sayımı) kral boşa gitmesin diye beklenir;
     * en fazla [KING_WAIT_MAX_MS] beklenir. Ardından kartta tazı kaldıysa boşaltılır.
     */
    private suspend fun kingPhase(frame: Frame, battleScene: Boolean) {
        val d = deployer
        if (d == null) {
            svc.log("Kral: alan hazır değil, sonra denenecek")
            return
        }
        if (!battleScene) {
            if (kingWaitAt == 0L) kingWaitAt = SystemClock.elapsedRealtime()
            val waited = SystemClock.elapsedRealtime() - kingWaitAt
            if (waited < KING_WAIT_MAX_MS) {
                svc.log("Kral için savaşın başlaması bekleniyor (${waited / 1000} sn)")
                delay(300)
                return
            }
        }
        tapCard(frame, SLOT_KING)
        delay(180)
        val p = d.heroPoint(0)
        if (p >= 0) tapPoint(frame, p) else tapRef(frame, 1620f, 470f)
        if (firstDeployAt == 0L) firstDeployAt = SystemClock.elapsedRealtime()
        svc.log("Kral bırakıldı → savaşın bitmesi bekleniyor")

        // Elde tazı kaldıysa ve savaş sürüyorsa: kart sayacı hâlâ dolu görünüyorsa kısa boşaltma turu
        var round = 0
        while (battleScene && round < KING_FLUSH_ROUNDS && svc.running) {
            val fresh = svc.grab()
            val left = if (fresh != null) {
                try { flushCountLeft(fresh, SLOT_HOUNDS) } finally { fresh.recycle() }
            } else false
            if (!left) break
            round++
            tapCard(frame, SLOT_HOUNDS)
            delay(100)
            repeat(4) {
                val n = deployBurst(frame, HOUNDS_PER_BURST)
                if (n > 0) houndTaps += n
                delay(120)
            }
        }
        if (round > 0) svc.log("Kalan tazılar boşaltıldı (${round} tur)")
        phase = Phase.WAIT
    }

    /**
     * Aynı anda [count] ayrı geçerli noktaya dokunur (tek jestte çok parmak).
     * Jest kabul edilmezse dokunuşlar kısa aralıkla tek tek gönderilir.
     */
    private suspend fun deployBurst(frame: Frame, count: Int): Int {
        val d = deployer ?: return 0
        val live = Vision(frame.small)
        val xs = FloatArray(count)
        val ys = FloatArray(count)
        var n = 0
        val q = PointF()
        while (n < count) {
            var p = d.anyFree()
            var tries = 0
            while (p >= 0 && !d.valid(p, live) && tries < 3) { p = d.anyFree(); tries++ }
            if (p < 0) break
            Ref.map(d.xOf(p).toFloat(), d.yOf(p).toFloat(), frame.w, frame.h, q)
            xs[n] = q.x; ys[n] = q.y
            n++
        }
        if (n == 0) return 0
        if (n == count && svc.tapMulti(xs, ys)) return n
        var done = 0
        for (i in 0 until n) {
            if (svc.tap(xs[i], ys[i])) done++
            delay(45)
        }
        return done
    }

    /** Şu anki sahneyi taze kareden okur (bırakma sırasında kontrol için). */
    private suspend fun currentScene(): Scene? {
        val f = svc.grab() ?: return null
        return try { Scenes.detect(f.small) } finally { f.recycle() }
    }

    /** Kartın sol üstündeki "xNN" sayacı hâlâ duruyor mu? (okunamazsa hayır) */
    private fun flushCountLeft(frame: Frame, slot: Int): Boolean {
        val left = readCount(frame, slot) ?: return false
        return left > 0
    }

    private fun readCount(frame: Frame, slot: Int): Int? {
        val x = CARD_LBL_X[slot.coerceIn(0, CARD_LBL_X.size - 1)]
        return Ocr.readCount(frame.full, x[0], CARD_LBL_Y0, x[1], CARD_LBL_Y1)
    }

    // ------------------------------------------------------------- yardımcı

    /** Tek dokunuşluk sahneler: dokun, ekran değişimini bekle, gerekirse tekrar dene. */
    private suspend fun act(
        frame: Frame,
        scene: Scene,
        now: Long,
        label: String,
        maxTries: Int = MAX_ATTEMPTS,
        tap: suspend () -> Unit
    ) {
        if (scene == lastTapScene) {
            if (now - lastTapAt < WAIT_MS) {
                svc.log("$label sonrası ekran bekleniyor")
                return
            }
            if (attempts >= maxTries) {
                svc.stopWithWarning("⚠ Ekran değişmedi ($label), durdu")
                return
            }
        } else {
            attempts = 0
        }
        attempts++
        lastTapScene = scene
        lastTapAt = now
        svc.log(label)
        tap()
    }

    private suspend fun tapCard(frame: Frame, slot: Int) {
        tapRef(frame, CARD_X[slot.coerceIn(0, CARD_X.size - 1)].toFloat(), CARD_TAP_Y.toFloat())
    }

    private suspend fun tapPoint(frame: Frame, p: Int) {
        val d = deployer ?: return
        tapRef(frame, d.xOf(p).toFloat(), d.yOf(p).toFloat())
    }

    private suspend fun tapRef(frame: Frame, xRef: Float, yRef: Float) {
        Ref.map(xRef, yRef, frame.w, frame.h, pt)
        svc.tap(pt.x, pt.y)
    }
}
