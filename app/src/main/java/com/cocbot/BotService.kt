package com.cocbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Tam çözünürlüklü (orijinal) ve küçültülmüş (tanıma için) ekran görüntüsü. */
class Frame(val full: Bitmap, val small: Bitmap) {
    val w: Int get() = full.width
    val h: Int get() = full.height
    fun recycle() {
        try { small.recycle() } catch (_: Throwable) {}
        try { full.recycle() } catch (_: Throwable) {}
    }
}

open class BotService : AccessibilityService() {

    companion object {
        @Volatile var instance: BotService? = null
        const val SMALL_W = 720
        const val TAP_MS = 40L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var loopJob: Job? = null
    private lateinit var overlay: Overlay

    @Volatile var running = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = Overlay(this) { toggle() }
        overlay.show()
        scope.launch { try { Ocr.warmUp() } catch (_: Throwable) {} }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        loopJob?.cancel()
        if (::overlay.isInitialized) overlay.hide()
        scope.cancel()
        shotExecutor.shutdown()
        instance = null
        super.onDestroy()
    }

    open fun toggle() { if (running) stopBot() else startBot() }

    open fun startBot() {
        if (running) return
        running = true
        overlay.setRunning(true)
        overlay.log("Başladı")
        loopJob = scope.launch { BotLoop(this@BotService).run() }
    }

    open fun stopBot() {
        running = false
        loopJob?.cancel()
        loopJob = null
        overlay.setRunning(false)
        overlay.log("Durduruldu")
    }

    open fun stopWithWarning(msg: String) {
        stopBot()
        overlay.log(msg)
    }

    open fun log(s: String) { if (::overlay.isInitialized) overlay.log(s) }

    /** Tam çözünürlüklü yazılım bitmap'i; başarısızsa (ör. OS hız sınırı) null. */
    private suspend fun screenshotFull(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, shotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                            bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                        } catch (_: Throwable) {
                        } finally {
                            try { r.hardwareBuffer.close() } catch (_: Throwable) {}
                        }
                        if (cont.isActive) cont.resume(bmp) else bmp?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
    }

    open suspend fun grab(): Frame? {
        val full = screenshotFull() ?: return null
        val sh = (full.height.toFloat() * SMALL_W / full.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(full, SMALL_W, sh, true)
        return Frame(full, small)
    }

    /** Kısa (≈40 ms) tek dokunuş. Durdurulduysa hiçbir şey göndermez. */
    open suspend fun tap(x: Float, y: Float): Boolean {
        if (!running) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return dispatchAwait(gesture)
    }

    /**
     * Aynı anda birden çok noktaya dokunma (çok parmak, tek jest).
     * Tazılar 3'er 3'er bırakılırken kullanılır. Jest kabul edilmezse false döner;
     * çağıran taraf tek tek dokunuşa düşer.
     */
    open suspend fun tapMulti(xs: FloatArray, ys: FloatArray): Boolean {
        if (!running) return false
        if (xs.isEmpty()) return false
        if (xs.size == 1) return tap(xs[0], ys[0])
        val b = GestureDescription.Builder()
        for (i in xs.indices) {
            val path = Path().apply { moveTo(xs[i], ys[i]) }
            b.addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
        }
        return dispatchAwait(b.build())
    }

    /**
     * İki parmakla kıstırma. [r0] > [r1] ise parmaklar birbirine yaklaşır → ekran UZAKLAŞIR.
     * [r0] < [r1] ise ekran yakınlaşır.
     */
    open suspend fun pinch(cx: Float, cy: Float, r0: Float, r1: Float, ms: Long = 260L): Boolean {
        if (!running) return false
        val left = Path().apply { moveTo(cx - r0, cy); lineTo(cx - r1, cy) }
        val right = Path().apply { moveTo(cx + r0, cy); lineTo(cx + r1, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(left, 0, ms))
            .addStroke(GestureDescription.StrokeDescription(right, 0, ms))
            .build()
        return dispatchAwait(gesture)
    }

    /** Hareketi gönderir ve bitmesini bekler (tamamlandı → true, iptal → false). */
    private suspend fun dispatchAwait(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = try {
                dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
            } catch (e: Throwable) {
                false
            }
            if (!ok && cont.isActive) cont.resume(false)
        }
}
