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
 * Keşifte ganimet 1.6M'nin altındaysa "İleri" ile yeni köy aranır, üstündeyse saldırı planı işler:
 *   0) ilk asker bırakılmadan önce ekran iki parmakla sıkıştırılarak (pinch) olabildiğince
 *      uzaklaştırılır; böylece büyük köyler de ekrana sığar, bırakma alanı bulunur
 *   0b) kartlar sayaçlarından sınıflandırılır: "xN" sayacı olan → büyü, olmayan → kahraman
 *   1) x40 asker kartı: 10'ar asker, 10 sn arayla 4 grup (40 asker) ve hepsi birbirinden uzak noktalara
 *   1b) gruplar bitince (~40. sn) kartta asker kalmışsa (sayaçtan okunur) askerler tek tek,
 *       sırayla farklı köşelere bırakılır
 *   2) 30. sn'de ekran aşağı kaydırılır: köyün alt köşeleri görünür, sonraki askerler oraya bırakılır
 *   3) kahramanlar güvenli köşelere, büyüler üssün ortasına (kral bölgesi)
 *   4) kahramanlar bırakıldıktan 10 sn sonra kartlara tekrar dokunarak yetenekler
 *   5) elde kalan tüm kartlar (x40 + kahraman + büyü) köşelere ve üs merkezine boşaltılır
 *   6) 70-85 sn arası kartlarda kalan son askerler yine tek tek, sırayla farklı köşelere bırakılır
 *   7) 85. sn'de "Teslim Ol" → onayda "Tamam" → sonuçta "Eve Dön"
 */
class BotLoop(private val svc: BotService) {

    companion object {
        const val WAIT_MS = 2000L            // dokunuş sonrası ekran değişimi bekleme üst sınırı
        const val MAX_ATTEMPTS = 3           // aynı sahnede art arda en fazla deneme
        const val RETREAT_ATTEMPTS = 8       // teslim ol / Tamam / Eve Dön için deneme sayısı
        const val LOOT_LIMIT = 1_600_000L    // altın + iksir bunu geçerse saldır
        const val TAP_GAP_MS = 220L          // bırakma dokunuşları arası bekleme
        const val UNKNOWN_WARN_MS = 30_000L
        const val BATTLE_WARN_MS = 240_000L

        // Saldırı planı
        const val TOKEN_GROUPS = 4           // x40 kartı → 4 grup
        const val TOKEN_PER_GROUP = 10       // her grupta 10 asker
        const val GROUP_DELAY_MS = 10_000L   // gruplar arası 10 sn
        const val HERO_ABILITY_DELAY_MS = 10_000L
        const val BATTLE_LIMIT_MS = 85_000L  // 85 sn sonra teslim ol
        const val BATTLE_HARD_LIMIT_MS = 95_000L
        const val FLUSH_TAPS = 4             // kart başına boşaltma dokunuşu
        const val FLUSH_ROUNDS = 4           // sayaç hâlâ görünüyorsa tekrar sayısı
        const val FINAL_DROP_MS = 70_000L    // 70 sn sonra kalan askerler tek tek köşelere
        const val FINAL_GAP_MS = 450L        // son bırakmalar arası bekleme

        // Aşağı kaydırma — 30. sn'de köyün alt köşeleri görünsün
        const val PAN_DOWN_MS = 30_000L      // bu süreden sonra ekran aşağı kaydırılır
        const val PAN_X = 1210f              // sürükleme ekseni (ekranın ortası)
        const val PAN_Y_FROM = 1050f         // parmağın başlangıcı
        const val PAN_Y_TO = 600f            // parmağın bitişi (yukarı sürükleme = görünüm aşağı iner)
        const val PAN_DRAG_MS = 600L         // sürükleme süresi
        const val PAN_SETTLE_MS = 900L       // kaydırma sonrası ekranın oturması

        // Uzaklaştırma (zoom-out) — saldırı öncesi
        const val ZOOM_PINCHES = 2           // sıkıştırma sayısı
        const val ZOOM_PINCH_MS = 500L       // tek sıkıştırmanın süresi
        const val ZOOM_GAP_MS = 600L         // sıkıştırmalar arası bekleme
        const val ZOOM_SETTLE_MS = 900L      // son sıkıştırma sonrası oturma süresi
        const val ZOOM_X = 1210f             // sıkıştırma ekseni (ekranın ortası)
        const val ZOOM_Y_TOP = 360f          // üst parmağın başlangıcı
        const val ZOOM_Y_BOT = 1130f         // alt parmağın başlangıcı
        const val ZOOM_Y_TOP_END = 700f      // üst parmağın bitişi (merkeze doğru)
        const val ZOOM_Y_BOT_END = 812f      // alt parmağın bitişi (merkeze doğru)

        // 40. saniyeden sonra kartta kalan askerler
        const val LEFTOVER_BATCH = 8         // tur başına bırakılan asker
        const val LEFTOVER_GAP_MS = 400L     // bırakmalar arası bekleme
        const val LEFTOVER_MAX_ROUNDS = 8    // güvenlik: en fazla tur sayısı
        const val LEFTOVER_MAX_MS = 58_000L  // bu süreyi aşarsa kahramanlara geçilir
        const val LEFTOVER_ASSUME_TOTAL = 42 // sayaç okunamazsa varsayılan toplam asker

        // Kart yuvaları (0 tabanlı) — referans 2419x1512
        val CARD_X = intArrayOf(326, 528, 704, 874, 1043, 1232, 1401, 1571, 1740, 1900)
        const val CARD_TAP_Y = 1332
        val CARD_LBL_X = arrayOf(
            intArrayOf(258, 398), intArrayOf(460, 600), intArrayOf(636, 776),
            intArrayOf(807, 946), intArrayOf(976, 1114), intArrayOf(1165, 1304),
            intArrayOf(1334, 1473), intArrayOf(1504, 1643), intArrayOf(1673, 1812),
            intArrayOf(1838, 1990)
        )
        const val CARD_LBL_Y0 = 1288
        const val CARD_LBL_Y1 = 1330

        const val SLOT_TOKENS = 0                     // x40 asker kartı (tek asker türü)
        // Diğer kartlar savaş başında sayaçlarından sınıflandırılır (classifySlots):
        // "xN" sayacı olan slot → büyü, sayacı olmayan slot → kahraman

        const val SURRENDER_X = 152; const val SURRENDER_Y = 1193   // kırmızı Teslim Ol / Savaşı Bitir
        const val TAMAM_X = 1451; const val TAMAM_Y = 941           // onay ekranı yeşil Tamam
        const val EVE_DON_X = 1211; const val EVE_DON_Y = 1308      // sonuç ekranı Eve Dön
    }

    private enum class Stage { SCOUT, ATTACK, RETREAT }
    private enum class Phase { TOKENS, LEFTOVER, HEROES, SPELLS, ABILITY, FLUSH, WAIT }

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
    private var phase = Phase.TOKENS
    private var attackStart = 0L
    private var heroDeployAt = 0L
    private var lastGroupEndAt = 0L
    private var groupCount = 0
    private var tokenPlaced = 0
    private var heroIndex = 0
    private var spellIndex = 0
    private var abilityIndex = 0
    private var flushIndex = 0
    private var flushRound = 0
    private var spellSeed = 0
    private var battleScanned = false
    private var scanTries = 0
    private var retreatAt = 0L

    // Uzaklaştırma ve kalan asker durumu
    private var zoomedOut = false
    private var zoomPinch = 0
    private var zoomAt = 0L
    private var zoomRetries = 0
    private var leftoverRounds = 0
    private var leftoverDropped = 0

    // Aşağı kaydırma ve kart sınıflandırma durumu
    private var pannedDown = false
    private var slotsClassified = false
    private var heroSlots = intArrayOf(2, 3, 4)      // savaş başında sınıflandırılır
    private var spellSlots = intArrayOf(5, 6, 7, 8)  // savaş başında sınıflandırılır
    private var spellCasts = IntArray(4)
    private var finalHeroTried = false
    private val flushSlots = ArrayList<Int>()
    private val flushIsSpell = ArrayList<Boolean>()

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

        // Saldırı planı çok uzadıysa (sahne tanınamasa bile) yine de teslim olunur
        if (stage == Stage.ATTACK && attackStart > 0 && now - attackStart >= BATTLE_HARD_LIMIT_MS &&
            (scene == null || scene == Scene.ATTACK_PREP || scene == Scene.ATTACK_BATTLE || scene == Scene.SURRENDER_CONFIRM)
        ) {
            svc.log("⚠ Süre aşıldı, teslim olunuyor")
            startRetreat(frame, now)
            return
        }

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
                Stage.RETREAT -> retreat(frame, scene, now)
                Stage.ATTACK -> battle(frame, scene, now)
                else -> scout(frame, now)
            }
            Scene.ATTACK_BATTLE -> when (stage) {
                Stage.RETREAT -> retreat(frame, scene, now)
                Stage.ATTACK -> battle(frame, scene, now)
                else -> {
                    svc.log("Savaş ortasında başlatıldı, plan devreye giriyor")
                    beginAttack(SystemClock.elapsedRealtime())
                    battle(frame, scene, now)
                }
            }
            Scene.SURRENDER_CONFIRM -> when (stage) {
                Stage.RETREAT -> retreat(frame, scene, now)
                Stage.ATTACK -> act(frame, scene, now, "Tamam (savaşı başlat)", RETREAT_ATTEMPTS) {
                    tapRef(frame, TAMAM_X.toFloat(), TAMAM_Y.toFloat())
                }
                else -> svc.log("Onay ekranı (bot beklemiyor, dokunulmuyor)")
            }
        }
    }

    // ---------------------------------------------------------------- keşif

    private suspend fun scout(frame: Frame, now: Long) {
        // Ganimet: sarı altın (y≈178) ve pembe iksir (y≈245); kara iksir okunmaz
        val gold = Ocr.readNumber(frame.full, 110, 150, 340, 205)
        val elixir = Ocr.readNumber(frame.full, 110, 222, 340, 275)
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
            svc.log("Toplam $total ≤ $LOOT_LIMIT → İleri")
            tapRef(frame, 2220f, 1148f)
        } else {
            svc.log("Toplam $total > $LOOT_LIMIT → saldırı başlıyor")
            beginAttack(now)
            tokenPhase(frame, 0L)
        }
    }

    // ------------------------------------------------------------- saldırı

    private fun beginAttack(now: Long) {
        stage = Stage.ATTACK
        phase = Phase.TOKENS
        attackStart = if (now > 0) now else SystemClock.elapsedRealtime()
        heroDeployAt = 0L
        lastGroupEndAt = 0L
        groupCount = 0
        tokenPlaced = 0
        heroIndex = 0
        spellIndex = 0
        abilityIndex = 0
        flushIndex = 0
        flushRound = 0
        spellSeed = 0
        battleScanned = false
        scanTries = 0
        retreatAt = 0L
        zoomedOut = false
        zoomPinch = 0
        zoomAt = 0L
        zoomRetries = 0
        leftoverRounds = 0
        leftoverDropped = 0
        pannedDown = false
        slotsClassified = false
        heroSlots = intArrayOf(2, 3, 4)
        spellSlots = intArrayOf(5, 6, 7, 8)
        spellCasts = IntArray(4)
        finalHeroTried = false
        releaseMap()
    }

    private fun finishAttack() {
        releaseMap()
        stage = Stage.SCOUT
        phase = Phase.TOKENS
        attackStart = 0L
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
        val battleScene = scene == Scene.ATTACK_BATTLE

        // Saldırı öncesi ekran olabildiğince uzaklaştırılır (büyük köyler ekrana sığsın)
        if (!ensureZoomed(frame)) return

        // Kartlar sınıflandırılır: sayacı (xN) olan → büyü, olmayan → kahraman
        if (!slotsClassified) classifySlots(frame)

        // 30 sn dolunca ekran aşağı kaydırılır: köyün alt köşeleri görünsün, oraya da asker bırakılsın
        if (!pannedDown && attackStart > 0 && now - attackStart >= PAN_DOWN_MS) {
            panDown(frame)
            return
        }

        val d = deployer
        if (d == null) {
            if (!prepareMap(frame, battleScene)) {
                // Bırakma alanı çıkmadıysa ekranı bir kez daha uzaklaştırıp yeniden tara
                if (scanTries >= 3 && zoomRetries < 2) {
                    zoomRetries++
                    zoomPinch = 0
                    zoomAt = 0L
                    zoomedOut = false
                    scanTries = 0
                    svc.log("Bırakma alanı çıkmadı, tekrar uzaklaştırılıyor")
                }
                return
            }
            battleScanned = battleScene
        } else if (battleScene && !battleScanned) {
            if (!prepareMap(frame, true)) return
            battleScanned = true
        }

        val elapsed = now - attackStart
        if (elapsed >= BATTLE_HARD_LIMIT_MS) {
            startRetreat(frame, now)
            return
        }

        // 70 sn dolduysa hangi aşamada olursa olsun teslim ol
        if (elapsed >= BATTLE_LIMIT_MS) {
            startRetreat(frame, now)
            return
        }

        when (phase) {
            Phase.TOKENS -> tokenPhase(frame, elapsed)
            Phase.LEFTOVER -> leftoverPhase(frame, elapsed)
            Phase.HEROES -> heroPhase(frame)
            Phase.SPELLS -> spellPhase(frame)
            Phase.ABILITY -> abilityPhase(frame, now)
            Phase.FLUSH -> flushPhase(frame)
            Phase.WAIT -> if (elapsed >= BATTLE_LIMIT_MS) {
                startRetreat(frame, now)
            } else if (elapsed >= FINAL_DROP_MS) {
                // 70-85 sn arası: kartlarda kalan son askerler tek tek köşelere bırakılır
                finalPhase(frame)
            } else {
                svc.log("Son askerler için ${(FINAL_DROP_MS - elapsed) / 1000} sn")
                delay(150)
            }
        }
    }

    /**
     * Saldırı öncesi ekran olabildiğince uzaklaştırılır: [ZOOM_PINCHES] kez iki parmaklı
     * sıkıştırma (parmaklar merkeze yaklaşıyor = zoom-out), sonra ekranın oturması beklenir.
     * true dönerse görünüm hazırdır, saldırı planı devam edebilir.
     */
    private suspend fun ensureZoomed(frame: Frame): Boolean {
        if (zoomedOut) return true
        if (zoomPinch < ZOOM_PINCHES) {
            if (zoomAt == 0L || SystemClock.elapsedRealtime() - zoomAt >= ZOOM_GAP_MS) {
                zoomPinch++
                svc.log("Ekran uzaklaştırılıyor ($zoomPinch/$ZOOM_PINCHES)")
                pinchRef(frame)
                zoomAt = SystemClock.elapsedRealtime()
            }
            return false
        }
        if (SystemClock.elapsedRealtime() - zoomAt < ZOOM_SETTLE_MS) return false
        zoomedOut = true
        return true
    }

    /** Dikey sıkıştırma: parmaklar ekranın ortasında birbirine yaklaşıyor = uzaklaştırma. */
    private suspend fun pinchRef(frame: Frame) {
        val top = PointF()
        val bot = PointF()
        val topEnd = PointF()
        val botEnd = PointF()
        Ref.map(ZOOM_X, ZOOM_Y_TOP, frame.w, frame.h, top)
        Ref.map(ZOOM_X, ZOOM_Y_BOT, frame.w, frame.h, bot)
        Ref.map(ZOOM_X, ZOOM_Y_TOP_END, frame.w, frame.h, topEnd)
        Ref.map(ZOOM_X, ZOOM_Y_BOT_END, frame.w, frame.h, botEnd)
        svc.pinch(
            top.x, top.y, bot.x, bot.y,
            topEnd.x, topEnd.y, botEnd.x, botEnd.y,
            ZOOM_PINCH_MS
        )
    }

    /**
     * Kartları sayaçlarından sınıflandırır: "xN" sayacı olan slotlar büyü,
     * sayacı olmayanlar kahraman (boş slotlar da kahraman görünür; dokunmak zararsızdır).
     * Orduda tek asker türü (x40) olduğu varsayılır.
     */
    private fun classifySlots(frame: Frame) {
        val heroes = ArrayList<Int>()
        val spells = ArrayList<Int>()
        for (s in 1..9) {
            val c = readCount(frame, s)
            if (c != null && c > 0) spells.add(s) else heroes.add(s)
        }
        // tanıma tutarsızsa (hiç büyü ya da hiç kahraman çıkmadıysa) varsayılan düzene dön
        if (heroes.isEmpty() || spells.isEmpty()) {
            heroSlots = intArrayOf(2, 3, 4)
            spellSlots = intArrayOf(5, 6, 7, 8)
        } else {
            heroSlots = heroes.toIntArray()
            spellSlots = spells.toIntArray()
        }
        spellCasts = IntArray(spellSlots.size)
        slotsClassified = true
        svc.log("Kartlar: ${heroSlots.size} kahraman, ${spellSlots.size} büyü")
    }

    /**
     * 30. sn: ekran aşağı kaydırılır (parmak yukarı sürüklenir) → köyün alt köşeleri
     * görünür hale gelir. Görünüm değiştiği için bırakma alanı yeniden taranır;
     * sonraki asker/kahraman bırakmaları dinamik köşeler sayesinde alt bölgeye gider.
     */
    private suspend fun panDown(frame: Frame) {
        pannedDown = true
        svc.log("Ekran aşağı kaydırılıyor (alt köşelere asker bırakılacak)")
        // seçili kart varsa bırak (sürüklemeye asker sıkıştırmasın)
        tapCard(frame, SLOT_TOKENS)
        delay(200)
        swipeRef(frame, PAN_X, PAN_Y_FROM, PAN_X, PAN_Y_TO, PAN_DRAG_MS)
        releaseMap()          // görünüm değişti: alan yeniden taranacak
        battleScanned = false
        delay(PAN_SETTLE_MS)
    }

    /** Referans koordinatta dikey sürükleme: (x, yFrom) → (x, yTo). */
    private suspend fun swipeRef(frame: Frame, xRef: Float, yFromRef: Float, yToRef: Float, durationMs: Long) {
        val from = PointF()
        val to = PointF()
        Ref.map(xRef, yFromRef, frame.w, frame.h, from)
        Ref.map(xRef, yToRef, frame.w, frame.h, to)
        svc.swipe(from.x, from.y, to.x, to.y, durationMs)
    }

    /** 1) x40 kartından 10'ar asker, 10 sn arayla 4 grup; hepsi ayrı noktalara. */
    private suspend fun tokenPhase(frame: Frame, elapsed0: Long) {
        val d = deployer ?: return
        var elapsed = elapsed0
        // her grup, bir önceki grubun BİTİŞİNDEN 10 sn sonra başlar
        while (groupCount < TOKEN_GROUPS &&
            (groupCount == 0 || SystemClock.elapsedRealtime() - lastGroupEndAt >= GROUP_DELAY_MS)
        ) {
            tapCard(frame, SLOT_TOKENS)
            delay(150)
            var placed = 0
            for (i in 0 until TOKEN_PER_GROUP) {
                if (!svc.running) return
                var firstDone = tokenPlaced > 0
                val ok = place(frame, { again ->
                    if (!firstDone && !again) { firstDone = true; d.first() }
                    else d.anyFree()
                })
                if (!ok) { delay(TAP_GAP_MS); continue }
                placed++
                tokenPlaced++
                delay(TAP_GAP_MS)
            }
            groupCount++
            lastGroupEndAt = SystemClock.elapsedRealtime()
            svc.log("Asker grubu $groupCount/$TOKEN_GROUPS: $placed (toplam $tokenPlaced)")
            elapsed = SystemClock.elapsedRealtime() - attackStart
        }
        if (groupCount >= TOKEN_GROUPS) {
            // gruplar bitti (~40. sn): kartta kalan askerler için ayrı aşama
            phase = Phase.LEFTOVER
        }
    }

    /**
     * 1b) Gruplar bitince x40 kartının sayacı okunur; asker kalmışsa (ör. 15) hepsi
     * TEK TEK ve sırayla FARKLI KÖŞELERE bırakılır (sol üst → sağ üst → sol alt → sağ alt).
     * Köşeler her zaman düz çimdir; oyun dokunuşu reddetmez, sayaç güvenle düşer.
     * Sayaç okunamazsa şimdiye kadar bırakılan asker sayısından tahmin yapılır.
     */
    private suspend fun leftoverPhase(frame: Frame, elapsed: Long) {
        val d = deployer ?: return
        if (leftoverRounds >= LEFTOVER_MAX_ROUNDS || leftoverDropped >= 44 ||
            elapsed >= LEFTOVER_MAX_MS
        ) {
            phase = Phase.HEROES
            heroIndex = 0
            return
        }
        val cnt = readCount(frame, SLOT_TOKENS)
        val placed = tokenPlaced + leftoverDropped
        val target = when {
            cnt != null -> cnt
            placed < LEFTOVER_ASSUME_TOTAL -> LEFTOVER_ASSUME_TOTAL - placed
            else -> 0
        }
        if (target <= 0) {
            phase = Phase.HEROES
            heroIndex = 0
            svc.log("Kartta asker kalmadı")
            return
        }
        leftoverRounds++
        val n = target.coerceAtMost(LEFTOVER_BATCH)
        tapCard(frame, SLOT_TOKENS)
        delay(150)
        var placedNow = 0
        for (i in 0 until n) {
            if (!svc.running) return
            if (place(frame, { _ -> d.cornerNext() })) {
                placedNow++
                leftoverDropped++
            }
            delay(LEFTOVER_GAP_MS)
        }
        svc.log("Kalan askerler: $placedNow bırakıldı (kartta ~${cnt ?: target})")
    }

    /** 3) Kahramanlar güvenli köşelere bırakılır (köşeler her zaman düz çimdir). */
    private suspend fun heroPhase(frame: Frame) {
        val d = deployer ?: return
        if (heroIndex >= heroSlots.size) {
            phase = Phase.SPELLS
            spellIndex = 0
            return
        }
        val slot = heroSlots[heroIndex]
        tapCard(frame, slot)
        delay(150)
        place(frame, { _ -> d.cornerNext() })
        heroIndex++
        heroDeployAt = SystemClock.elapsedRealtime()   // yetenek sayacı SON kahramandan başlar
        svc.log("Kahraman $heroIndex → köşe")
        if (heroIndex >= heroSlots.size) {
            phase = Phase.SPELLS
            spellIndex = 0
        }
    }

    /** 3b) Büyüler üssün ortasına (kral bölgesi); kart adedi kadar atış. */
    private suspend fun spellPhase(frame: Frame) {
        val d = deployer ?: return
        if (spellIndex >= spellSlots.size) {
            phase = Phase.ABILITY
            abilityIndex = 0
            return
        }
        val slot = spellSlots[spellIndex]
        if (spellCasts[spellIndex] == 0) {
            spellCasts[spellIndex] = (readCount(frame, slot) ?: 1).coerceIn(1, 8)
            svc.log("Büyü ${spellIndex + 1}: ${spellCasts[spellIndex]} atış")
        }
        for (k in 0 until spellCasts[spellIndex]) {
            if (!svc.running) return
            tapCard(frame, slot)
            delay(120)
            tapPoint(frame, d.nextSpellPoint())
            delay(TAP_GAP_MS)
        }
        spellIndex++
        if (spellIndex >= spellSlots.size) {
            phase = Phase.ABILITY
            abilityIndex = 0
        }
    }

    /** 4) Kahraman bırakıldıktan 10 sn sonra kartlara tekrar dokun = yetenek. */
    private suspend fun abilityPhase(frame: Frame, now: Long) {
        if (heroDeployAt != 0L && now - heroDeployAt < HERO_ABILITY_DELAY_MS) {
            svc.log("Yetenek için ${(HERO_ABILITY_DELAY_MS - (now - heroDeployAt)) / 1000} sn")
            delay(150)
            return
        }
        if (abilityIndex >= heroSlots.size) {
            buildFlush(frame)
            phase = Phase.FLUSH
            return
        }
        tapCard(frame, heroSlots[abilityIndex])
        abilityIndex++
        svc.log("Kahraman yeteneği $abilityIndex")
        delay(600)
    }

    /** 5) Elde kalan kartlar: x40 ve kahramanlar köşelere, büyüler üs merkezine boşaltılır. */
    private fun buildFlush(frame: Frame) {
        flushSlots.clear()
        flushIsSpell.clear()
        // x40 kartı her zaman denenir: geçersiz yere dokunulduğu için içeride asker kalmış olabilir
        flushSlots.add(SLOT_TOKENS)
        flushIsSpell.add(false)
        // bırakılamamış kahramanlar: kart + köşe dokunuşuyla tekrar denenir (boşsa zararsız)
        for (s in heroSlots) {
            flushSlots.add(s)
            flushIsSpell.add(false)
        }
        for (s in spellSlots) {
            flushSlots.add(s)
            flushIsSpell.add(true)
        }
        flushIndex = 0
        flushRound = 0
        svc.log("Kalan kartlar boşaltılıyor (x40 + kahramanlar dahil)")
    }

    private suspend fun flushPhase(frame: Frame) {
        val d = deployer ?: return
        if (flushIndex >= flushSlots.size) {
            phase = Phase.WAIT
            return
        }
        val slot = flushSlots[flushIndex]
        val isSpell = flushIsSpell[flushIndex]
        if (flushRound == 0) {
            tapCard(frame, slot)
            delay(150)
        }
        var placed = 0
        for (i in 0 until FLUSH_TAPS) {
            if (!svc.running) return
            if (isSpell) {
                tapPoint(frame, d.nextSpellPoint())
            } else {
                // askerler köşelere: köşeler her zaman düz çimdir, oyun dokunuşu reddetmez
                if (!place(frame, { _ -> d.cornerNext() })) continue
            }
            placed++
            delay(TAP_GAP_MS)
        }
        flushRound++
        val maxRounds = if (slot == SLOT_TOKENS) 12 else FLUSH_ROUNDS
        delay(250)
        val fresh = svc.grab()
        val left = if (fresh != null) {
            try { flushCountLeft(fresh, slot) } finally { fresh.recycle() }
        } else flushCountLeft(frame, slot)
        if (left && flushRound < maxRounds) {
            svc.log("Kart ${slot + 1}: kalan var → tekrar")
            return
        }
        if (placed == 0) svc.log("Kart ${slot + 1}: uygun nokta bulunamadı")
        flushIndex++
        flushRound = 0
    }

    /** Kartın sol üst köşesindeki "xNN" sayacı hâlâ duruyor mu? (okunamazsa hayır) */
    private fun flushCountLeft(frame: Frame, slot: Int): Boolean {
        val left = readCount(frame, slot)
        if (left == null) {
            // okunamadı: x40 / büyü kartında sayaç yoksa kart bitmiş demektir; yine de x40 için 40 dokunuşa kadar zorla
            return slot == SLOT_TOKENS && tokenPlaced + flushRound * FLUSH_TAPS < 44
        }
        return left > 0
    }

    private fun readCount(frame: Frame, slot: Int): Int? {
        val x = CARD_LBL_X[slot.coerceIn(0, CARD_LBL_X.size - 1)]
        return Ocr.readCount(frame.full, x[0], CARD_LBL_Y0, x[1], CARD_LBL_Y1)
    }

    /**
     * 6) 70-85 sn arası: kartlarda kalan son askerler TEK TEK ve sırayla farklı
     * köşelere bırakılır (her karede bir asker). Önce kahraman kartları bir kez
     * daha denenir (bırakılmamışsa düşer, bırakılmışsa dokunmak zararsızdır).
     * x40 sayacı okunamadıysa yine de denenir. 85 sn dolunca teslim olunur.
     */
    private suspend fun finalPhase(frame: Frame) {
        val d = deployer ?: return
        if (!finalHeroTried) {
            finalHeroTried = true
            for (s in heroSlots) {
                if (!svc.running) return
                tapCard(frame, s)
                delay(120)
                place(frame, { _ -> d.cornerNext() })
                delay(250)
            }
            return
        }
        val tTokens = readCount(frame, SLOT_TOKENS)
        // null = sayaç okunamadı: kart boş olabilir ama asker kalmış da olabilir → yine de dene
        if (tTokens != null && tTokens <= 0) {
            svc.log("Kartlar boş, 85 sn'de teslim")
            delay(300)
            return
        }
        tapCard(frame, SLOT_TOKENS)
        delay(120)
        place(frame, { _ -> d.cornerNext() })
        delay(FINAL_GAP_MS)
    }

    // ------------------------------------------------------------- teslim

    private suspend fun startRetreat(frame: Frame, now: Long) {
        releaseMap()
        stage = Stage.RETREAT
        retreatAt = now
        lastTapScene = Scene.ATTACK_BATTLE
        lastTapAt = now
        svc.log("85 sn doldu → Teslim Ol")
        tapRef(frame, SURRENDER_X.toFloat(), SURRENDER_Y.toFloat())
    }

    private suspend fun retreat(frame: Frame, scene: Scene, now: Long) {
        if (retreatAt > 0 && now - retreatAt > 120_000L) {
            svc.stopWithWarning("⚠ Savaştan çıkılamadı, durdu")
            return
        }
        when (scene) {
            Scene.SURRENDER_CONFIRM ->
                act(frame, scene, now, "Tamam (teslim onayı)", RETREAT_ATTEMPTS) {
                    tapRef(frame, TAMAM_X.toFloat(), TAMAM_Y.toFloat())
                }
            Scene.RESULT -> {
                finishAttack()
                act(frame, scene, now, "Eve Dön", RETREAT_ATTEMPTS) {
                    tapRef(frame, EVE_DON_X.toFloat(), EVE_DON_Y.toFloat())
                }
            }
            Scene.HOME -> {
                finishAttack()
                svc.log("Köye dönüldü")
            }
            else -> act(frame, scene, now, "Teslim Ol", RETREAT_ATTEMPTS) {
                tapRef(frame, SURRENDER_X.toFloat(), SURRENDER_Y.toFloat())
            }
        }
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

    /**
     * Noktayı geçerli bir yere bırakır: seçilen nokta canlı karede geçersizse
     * (bina/kaya/ağaç üstü, çizgi içi) sıradaki adayı dener.
     * @param picker sonraki adayı üreten fonksiyon
     */
    private suspend fun place(
        frame: Frame,
        picker: (Boolean) -> Int,
        maxTries: Int = 4
    ): Boolean {
        val live = Vision(frame.small)
        var last = -1
        for (i in 0 until maxTries) {
            val p = picker(i > 0)
            if (p < 0) break
            last = p
            val ok = deployer?.valid(p, live) ?: false
            if (ok || i == maxTries - 1) {
                tapPoint(frame, p)
                return true
            }
        }
        if (last < 0) return false
        tapPoint(frame, last)
        return true
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
