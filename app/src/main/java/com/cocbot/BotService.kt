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
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    /**
     * İki parmaklı sıkıştırma (pinch). (x1,y1) ve (x2,y2) parmakları [durationMs] süresinde
     * (tx1,ty1) ve (tx2,ty2) noktalarına kayar. Parmaklar birbirine yaklaşıyorsa
     * harita uzaklaştırılır (zoom-out), uzaklaşıyorsa yaklaştırılır (zoom-in).
     */
    open suspend fun pinch(
        x1: Float, y1: Float, x2: Float, y2: Float,
        tx1: Float, ty1: Float, tx2: Float, ty2: Float,
        durationMs: Long
    ): Boolean {
        if (!running) return false
        val path1 = Path().apply { moveTo(x1, y1); lineTo(tx1, ty1) }
        val path2 = Path().apply { moveTo(x2, y2); lineTo(tx2, ty2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, durationMs))
            .build()
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    /** Tek parmaklı sürükleme (swipe): (x1,y1)'den (x2,y2)'ye [durationMs] süresinde. Haritayı kaydırır. */
    open suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (!running) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        }
    }
}
