package com.cocbot

import android.graphics.PointF

/** 2419 x 1512 referans görüntüden gerçek (veya küçültülmüş) görüntü koordinatına tek dönüşüm. */
object Ref {
    const val W = 2419f
    const val H = 1512f

    /** x_gerçek = x_ref * (w / 2419), y_gerçek = y_ref * (h / 1512). Sonuç out'a yazılır. */
    fun map(xRef: Float, yRef: Float, w: Int, h: Int, out: PointF) {
        out.x = xRef * (w / W)
        out.y = yRef * (h / H)
    }
}
